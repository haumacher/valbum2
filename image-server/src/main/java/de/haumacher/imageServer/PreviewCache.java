/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.gif.GifHeaderDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.webp.WebpDirectory;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.faces.Faces;
import de.haumacher.imageServer.heif.HeifDecoder;
import de.haumacher.imageServer.heif.HeifFile;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.util.Orientations;
import de.haumacher.util.servlet.Util;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber.Exception;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * Algorithm to generated and cache preview versions of image and video data.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class PreviewCache {

	/** The directory a folder's generated previews are kept in, beside the originals. */
	public static final String CACHE_DIRECTORY_NAME = ".vacache";

	/** The name every preview begins with, inside {@value #CACHE_DIRECTORY_NAME}. */
	public static final String PREVIEW_PREFIX = "preview-";


	private static final String MP4 = "mp4";

	private static final String MOV = "mov";

	private static final String M4V = "m4v";

	private static final String THREE_GP = "3gp";

	private static final String PNG = "png";

	private static final String JPEG = "jpeg";

	private static final String JPG = "jpg";

	private static final String HEIC = "heic";

	private static final String HEIF = "heif";

	private static final String WEBP = "webp";

	private static final String GIF = "gif";

	/**
	 * The colour a transparent pixel of a picture is shown on in its preview, which is a JPEG and
	 * has no transparency (issue #190): white, the page the album's tiles stand on, so that a
	 * sticker or a logo cut out of its background looks in the album as it looks on a web page.
	 */
	public static final java.awt.Color TRANSPARENT_BACKGROUND = java.awt.Color.WHITE;

	/**
	 * The extensions of the files the library holds, lower case: a file of another extension is
	 * refused at the upload and never listed.
	 */
	public static final Set<String> SUPPORTED_EXTENSIONS =
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList(JPG, JPEG, PNG, WEBP, GIF, HEIC, HEIF, MP4, MOV, M4V, THREE_GP)));

	/**
	 * The extensions of the videos among {@link #SUPPORTED_EXTENSIONS}, lower case: the ISO media
	 * and QuickTime containers the bundled FFmpeg reads — an mp4, a QuickTime movie (every iPhone
	 * video, issue #189), an iTunes <code>.m4v</code> and a 3GPP file of an older phone. Each has a
	 * poster frame and the renditions of {@link VideoRenditions}.
	 */
	public static final Set<String> VIDEO_EXTENSIONS =
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList(MP4, MOV, M4V, THREE_GP)));

	/** Whether a file of the given name is a video, by its extension in any case. */
	public static boolean isVideoName(String name) {
		String suffix = Util.suffix(name);
		return suffix != null && VIDEO_EXTENSIONS.contains(suffix);
	}

	/**
	 * The name every display rendition begins with, inside {@value #CACHE_DIRECTORY_NAME}, see
	 * {@link #createDisplay(File)}.
	 */
	public static final String DISPLAY_PREFIX = "display-";

	/** The extension of a display rendition. */
	public static final String DISPLAY_EXTENSION = JPG;

	/**
	 * The longest side of a display rendition, see {@link #createDisplay(File)}: every phone
	 * photograph up to 16 MP passes unscaled, a 50 or 200 MP one is brought down to a picture a
	 * screen can show.
	 */
	public static final int DISPLAY_LONG_SIDE = 4096;

	private static final int PREVIEW_HEIGHT = 600;

	private static final int PREVIEW_HEIGHT_PORTRAIT = 2 * PREVIEW_HEIGHT;

	/**
	 * The maximum width of an image (relative to its height) to interpret it as a portrait image.
	 *
	 * <p>
	 * Must be kept in sync with <code>Content.maxPortraitUnitWidth</code> of the Flutter app's
	 * <code>album_layout.dart</code>, which is the canonical album layout implementation.
	 * </p>
	 */
	private static final double MAX_PORTRAIT_UNIT_WIDTH = 0.75;

	/**
	 * Time of the last update that required to re-build preview images:
	 * 2022-05-15 13:30:00 in <code>Europe/Berlin</code>.
	 *
	 * <p>
	 * A constant, computed from an explicit zone rather than parsed from a literal: the former
	 * {@link java.text.SimpleDateFormat} pattern resolved the zone name <code>MEST</code> only
	 * on a runtime whose locale data, default locale and default time zone happened to know it,
	 * and warned and fell back to 0 everywhere else (issue #67) — which invalidated every cached
	 * preview.
	 * </p>
	 */
	static final long LAST_UPDATE =
		ZonedDateTime.of(2022, 5, 15, 13, 30, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli();

	/**
	 * Time of the last update that required to re-build preview images.
	 */
	public static long lastUpdate() {
		return LAST_UPDATE;
	}

	private static final Logger LOG = Logger.getLogger(PreviewCache.class.getName());

	/**
	 * The system property overriding how many previews are generated at the same time.
	 */
	public static final String PREVIEW_THREADS_PROPERTY = "valbum.previewThreads";

	/** The name a preview is written under before it is moved into place. */
	public static final String TMP_SUFFIX = ".tmp";

	/**
	 * How many previews may be generated at the same time.
	 *
	 * <p>
	 * An album page asks for all of its thumbnails at once and Jetty answers on as many threads as
	 * it has (200 by default), so without a limit a page of a hundred fresh photos starts a hundred
	 * decodes, which on a small machine thrashes CPU, disk and the decoder's own buffers for no
	 * gain: the previews arrive no earlier, they only arrive all late.
	 * </p>
	 *
	 * <p>
	 * A fixed number of permits, deliberately <em>not</em> a limit derived from free memory:
	 * {@link Runtime#freeMemory()} reports the state the collector happens to be in, not what the
	 * next decode needs, and a decision built on it is neither testable nor reproducible. With the
	 * decode itself bounded by the preview size (issue #68) the memory of one generation is known
	 * in advance, so the count is there to spend CPU and disk sensibly and can be chosen for the
	 * machine: {@link Runtime#availableProcessors()}, overridable by the system property
	 * {@value #PREVIEW_THREADS_PROPERTY} or the server's <code>--preview-threads</code> option.
	 * </p>
	 *
	 * <p>
	 * Only the <em>generation</em> of a preview takes a permit. Serving one that is already in
	 * the cache is a file read like any other and never waits, so a warm album is answered at full
	 * speed no matter what the limit is.
	 * </p>
	 */
	private static volatile Semaphore permits = new Semaphore(configuredPermits(), true);

	/** How many permits {@link #permits} was created with, for the start-up message. */
	private static volatile int permitCount = permits.availablePermits();

	/**
	 * The generations currently in flight, by the absolute path of the preview they produce.
	 *
	 * <p>
	 * Two requests for the same not-yet-cached preview (a listing tile and the album, or two
	 * devices) would otherwise both generate it and both write the same file, so one of them could
	 * serve a half-written image. The second request finds the first one's future here and waits
	 * for it instead of doing the work again; a failure reaches every waiter as the same
	 * {@link PreviewException}, and the entry is dropped afterwards so that a later request may
	 * try again.
	 * </p>
	 */
	private static final ConcurrentHashMap<String, CompletableFuture<Void>> IN_FLIGHT = new ConcurrentHashMap<>();

	/** Observation points of the generation, attached by the concurrency tests only. */
	interface Hook {

		/** A permit for generating the preview of the given original was taken. */
		void permitAcquired(File file);

		/** The generation of the given original begins, inside the permit. */
		void generationStarted(File file);

		/** The generation of the given original has ended, successfully or not. */
		void generationFinished(File file);

	}

	private static final Hook NO_HOOK = new Hook() {
		@Override
		public void permitAcquired(File file) {
			// Nothing to observe in production.
		}

		@Override
		public void generationStarted(File file) {
			// Nothing to observe in production.
		}

		@Override
		public void generationFinished(File file) {
			// Nothing to observe in production.
		}
	};

	private static volatile Hook hook = NO_HOOK;

	/** How many previews may be generated at the same time. */
	static int permitCount() {
		return permitCount;
	}

	/**
	 * Sets how many previews may be generated at the same time, see {@link #permits}.
	 *
	 * <p>
	 * Called at start-up from the command line, and by the tests. Not while previews are being
	 * generated: the permits in flight are given back to the semaphore that handed them out.
	 * </p>
	 */
	public static void setPermitCount(int count) {
		if (count < 1) {
			throw new IllegalArgumentException("At least one preview must be generated at a time: " + count);
		}
		permits = new Semaphore(count, true);
		permitCount = count;
	}

	/** Attaches an observer of the generation, <code>null</code> to detach. Tests only. */
	static void setHook(Hook newHook) {
		hook = newHook == null ? NO_HOOK : newHook;
	}

	private static int configuredPermits() {
		String value = System.getProperty(PREVIEW_THREADS_PROPERTY);
		if (value != null && !value.isEmpty()) {
			try {
				int count = Integer.parseInt(value.trim());
				if (count >= 1) {
					return count;
				}
				LOG.warning("Ignoring '" + PREVIEW_THREADS_PROPERTY + "=" + value + "': not a positive number.");
			} catch (NumberFormatException ex) {
				LOG.warning("Ignoring '" + PREVIEW_THREADS_PROPERTY + "=" + value + "': not a number.");
			}
		}
		return Runtime.getRuntime().availableProcessors();
	}

	/**
	 * Lookup or creates the preview data for the given image or video file.
	 */
	public static File createPreview(File file) throws PreviewException {
		String fileName = file.getName();
		String suffix = Util.suffix(fileName);
		String imageType = imageType(suffix);

		File cacheDir = new File(file.getParentFile(), CACHE_DIRECTORY_NAME);
		File previewCache = new File(cacheDir, PREVIEW_PREFIX + fileName + (suffix.equals(imageType) ? "" : "." + imageType));
		if (!upToDate(file, previewCache)) {
			if (!SUPPORTED_EXTENSIONS.contains(suffix)) {
				throw new PreviewException("Unsupported format: " + fileName);
			}
			generate(file, previewCache, tmp -> makePreview(file, tmp, suffix, imageType));
		}
		return previewCache;
	}

	/**
	 * Where the display rendition of the given original lies, made or not, see
	 * {@link #createDisplay(File)}.
	 */
	public static File displayFile(File file) {
		return new File(new File(file.getParentFile(), CACHE_DIRECTORY_NAME),
			DISPLAY_PREFIX + file.getName() + "." + DISPLAY_EXTENSION);
	}

	/**
	 * Whether the given original has a display rendition: a format no browser and no app can be
	 * expected to decode, which is a HEIC/HEIF photograph and nothing else (issue #186).
	 */
	public static boolean needsDisplay(File file) {
		return HeifFile.isHeif(file);
	}

	/**
	 * Looks up or creates the display rendition of the given HEIC/HEIF photograph, see issue #186.
	 *
	 * <p>
	 * A JPEG of the photograph upright, at most {@value #DISPLAY_LONG_SIDE} pixels on its long side
	 * and never scaled up, made on the first request and kept in
	 * {@value #CACHE_DIRECTORY_NAME} as <code>display-&lt;name&gt;.jpg</code>: what the viewer shows
	 * in place of an original that Chrome, Firefox and the app's own decoder cannot read. It is
	 * made exactly as a preview is — under one of the {@link #permits}, the second request waiting
	 * for the first, written to {@value #TMP_SUFFIX} and moved into place — and goes stale like one
	 * (the original newer, {@link #LAST_UPDATE}); <code>?action=refresh-cache</code> and the purge
	 * throw it away with the preview, see {@link CacheRefresh#isGenerated(String)}.
	 * </p>
	 */
	public static File createDisplay(File file) throws PreviewException {
		if (!needsDisplay(file)) {
			throw new PreviewException("No display rendition for '" + file.getName() + "'.");
		}
		File display = displayFile(file);
		if (!upToDate(file, display)) {
			generate(file, display, tmp -> {
				try {
					HeifFile heif = HeifFile.read(file);
					int width = heif.getDisplayWidth();
					int height = heif.getDisplayHeight();
					double scale = Math.min(1.0, ((double) DISPLAY_LONG_SIDE) / Math.max(width, height));
					HeifDecoder.writeUprightJpeg(file, heif, Math.max(1, (int) Math.round(width * scale)),
						Math.max(1, (int) Math.round(height * scale)), tmp);
				} catch (IOException ex) {
					throw new PreviewException("Cannot create the display rendition of '" + file.getName() + "': "
						+ ex.getMessage(), ex);
				}
			});
		}
		return display;
	}

	/** Writes a generated file into the temporary name it is given. */
	private interface Maker {

		/** Writes into the given temporary file. */
		void make(File tmp) throws PreviewException;
	}

	/** Writes the preview of the given original. */
	private static void makePreview(File file, File tmp, String suffix, String imageType) throws PreviewException {
		String fileName = file.getName();
		switch (suffix) {
			case JPG:
			case JPEG:
			case PNG:
			case WEBP:
			case GIF:
				try {
					createImagePreview(file, tmp, imageType);
				} catch (ImageProcessingException | MetadataException | IOException ex) {
					throw new PreviewException("Cannot create image preview for '" + fileName + "'.", ex);
				}
				break;
			case HEIC:
			case HEIF:
				try {
					createHeifPreview(file, tmp);
				} catch (IOException ex) {
					throw new PreviewException("Cannot create image preview for '" + fileName + "': "
						+ ex.getMessage(), ex);
				}
				break;
			default:
				try {
					createVideoPreview(file, tmp);
				} catch (ImageProcessingException | MetadataException | IOException ex) {
					throw new PreviewException("Cannot create video preview for '" + fileName + "'.", ex);
				}
				break;
		}
	}

	/**
	 * Whether the cached preview is there and still describes the given original.
	 *
	 * <p>
	 * A preview from before the last change of the generator is stale, see {@link #LAST_UPDATE}.
	 * The temporary file a generation writes is never mistaken for a preview: it is named
	 * {@value #TMP_SUFFIX} behind the preview's own name and nothing ever looks it up.
	 * </p>
	 */
	private static boolean upToDate(File file, File previewCache) {
		if (!previewCache.exists()) {
			return false;
		}
		long previewTime = previewCache.lastModified();
		return file.lastModified() <= previewTime && previewTime >= LAST_UPDATE;
	}

	/**
	 * Generates the preview, or waits for the generation somebody else has already started.
	 */
	private static void generate(File file, File previewCache, Maker maker) throws PreviewException {
		String key = previewCache.getAbsolutePath();
		CompletableFuture<Void> mine = new CompletableFuture<>();
		CompletableFuture<Void> running = IN_FLIGHT.putIfAbsent(key, mine);
		if (running != null) {
			await(running, previewCache);
			return;
		}
		try {
			build(file, previewCache, maker);
			mine.complete(null);
		} catch (PreviewException | RuntimeException | Error ex) {
			mine.completeExceptionally(ex);
			throw ex;
		} finally {
			IN_FLIGHT.remove(key, mine);
		}
	}

	/**
	 * Waits for the generation that is already in flight and shares its outcome.
	 */
	private static void await(CompletableFuture<Void> running, File previewCache) throws PreviewException {
		try {
			running.get();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new PreviewException("Interrupted while waiting for the preview '"
				+ previewCache.getName() + "'.", ex);
		} catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof PreviewException) {
				// The same failure the generating request is answered with.
				throw (PreviewException) cause;
			}
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			if (cause instanceof Error) {
				throw (Error) cause;
			}
			throw new PreviewException("Cannot create the preview '" + previewCache.getName() + "'.", cause);
		}
	}

	/**
	 * Builds the preview, holding one of the {@link #permits}.
	 *
	 * <p>
	 * The preview is written to a temporary name beside it and moved into place when it is
	 * complete, so that a request being served the preview at the same time never reads a
	 * half-written file. A temporary file left behind by a crash is overwritten by the next
	 * generation and is never served.
	 * </p>
	 */
	private static void build(File file, File previewCache, Maker maker) throws PreviewException {
		String fileName = file.getName();

		Semaphore semaphore = permits;
		try {
			semaphore.acquire();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new PreviewException("Interrupted while waiting to create the preview of '"
				+ fileName + "'.", ex);
		}
		hook.permitAcquired(file);
		try {
			// The generation may have been waited out; nothing is built twice.
			if (upToDate(file, previewCache)) {
				return;
			}

			File cacheDir = previewCache.getParentFile();
			if (!cacheDir.exists()) {
				cacheDir.mkdirs();
			}
			File tmp = new File(cacheDir, previewCache.getName() + TMP_SUFFIX);
			hook.generationStarted(file);
			try {
				maker.make(tmp);
				try {
					moveIntoPlace(tmp, previewCache);
				} catch (IOException ex) {
					throw new PreviewException("Cannot store the preview of '" + fileName + "'.", ex);
				}
			} finally {
				// Nothing half-written is left behind; after the move there is nothing to delete.
				tmp.delete();
				hook.generationFinished(file);
			}
		} finally {
			semaphore.release();
		}
	}

	/**
	 * Makes the completed temporary file the preview, in one step where the file system can.
	 */
	private static void moveIntoPlace(File tmp, File previewCache) throws IOException {
		try {
			Files.move(tmp.toPath(), previewCache.toPath(), StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException | UnsupportedOperationException ex) {
			LOG.log(Level.FINE, "No atomic move for '" + previewCache + "', replacing instead.", ex);
			Files.move(tmp.toPath(), previewCache.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String imageType(String suffix) {
		switch (suffix) {
		case PNG: return PNG;
		}
		return JPG;
	}

	/**
	 * Creates the preview of the given image.
	 *
	 * <p>
	 * The original is never decoded at full resolution: a phone photo of 50 megapixels would need
	 * 150-200&nbsp;MB of heap for one request and exhausted the small heap of the deployment
	 * (issue #68). The metadata already know the original's size before a pixel is read, so the
	 * reader is asked for a subsampled raster (see {@link #subsampling(int, int, int, int)}) that
	 * is at most twice the preview in each dimension, whatever the original's size. The scale and
	 * orientation transform below is therefore computed against the raster actually decoded, not
	 * against the original's size.
	 * </p>
	 *
	 * <p>
	 * A WebP (read by the TwelveMonkeys plugin) and a GIF (read by ImageIO itself) take this very
	 * path, issue #190: the preview of an animated one is its first frame — image 0 of the reader —
	 * while the original keeps its animation, and a transparent pixel is shown on
	 * {@link #TRANSPARENT_BACKGROUND}, because their preview is a JPEG.
	 * </p>
	 */
	private static void createImagePreview(File file, File previewCache, String imgType)
			throws ImageProcessingException, IOException, MetadataException {
		Metadata metadata = ImageMetadataReader.readMetadata(file);
		int orientationCode = getImageOrientation(metadata);
		Orientation orientation = Orientations.fromCode(orientationCode);
		boolean swapped = orientationCode >= 5;

		try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
			if (in == null) {
				throw new IOException("Cannot open image data of '" + file.getName() + "'.");
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				throw new IOException("No image reader for '" + file.getName() + "'.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);

				ImageDimension dimension = imageDimension(metadata, reader, swapped);
				int origWidth = dimension.getWidth();
				int origHeight = dimension.getHeight();

				int[] box = previewBox(origWidth, origHeight);
				int previewWidth = box[0];
				int previewHeight = box[1];

				BufferedImage orig =
					read(reader, subsampling(origWidth, origHeight, previewWidth, previewHeight));

				// The raster as decoded, in the file's own (un-rotated) orientation.
				int rawWidth = orig.getWidth();
				int rawHeight = orig.getHeight();

				// The same raster as the viewer sees it, which is what the preview box is filled with.
				int decodedWidth = swapped ? rawHeight : rawWidth;
				int decodedHeight = swapped ? rawWidth : rawHeight;

				// A picture smaller than its box is never scaled up (below), so the box shrinks to the
				// picture instead: a canvas larger than the picture would carry black margins into the
				// rendition, and a tile drawing that rendition into its row would show the margins as a
				// picture that does not fill its place (issue #165). The rectangle FaceIndex.content
				// computes — min(preview, display), centred — is then the whole canvas.
				previewWidth = Math.min(previewWidth, decodedWidth);
				previewHeight = Math.min(previewHeight, decodedHeight);

				boolean opaque = JPG.equals(imgType);
				BufferedImage copy = new BufferedImage(previewWidth, previewHeight,
					opaque ? jpegType(orig) : imageType(orig));
				Graphics2D g = (Graphics2D) copy.getGraphics();
				if (opaque && orig.getColorModel().hasAlpha()) {
					// A JPEG has no transparency: what the picture leaves open is shown on white.
					g.setColor(TRANSPARENT_BACKGROUND);
					g.fillRect(0, 0, previewWidth, previewHeight);
				}

				double scaleX = Math.min(1.0, ((double) previewWidth) / decodedWidth);
				double scaleY = Math.min(1.0, ((double) previewHeight) / decodedHeight);

				AffineTransform tx = new AffineTransform();
				tx.translate((previewWidth - decodedWidth * scaleX) / 2, (previewHeight - decodedHeight * scaleY) / 2);
				tx.scale(scaleX, scaleY);
				tx.concatenate(orientationTransform(orientation, rawWidth, rawHeight));
				g.setTransform(tx);

				g.drawImage(orig, null, 0, 0);
				ImageIO.write(copy, imgType, previewCache);
			} finally {
				reader.dispose();
			}
		}
	}

	/**
	 * Creates the preview of the given HEIC/HEIF photograph, see issue #186.
	 *
	 * <p>
	 * The program of {@link HeifDecoder} decodes the raw raster straight at the size the preview
	 * needs, so no more than the preview ever enters the heap; it is then turned upright by the
	 * very transform a JPEG is turned by, {@link #orientationTransform}, the container's
	 * <code>irot</code>/<code>imir</code> standing for the EXIF orientation (see
	 * {@link HeifFile#getOrientation()}). The face index finds its faces on this picture and maps
	 * them back into the raw raster by the same table, so both frames agree for a HEIC as for a
	 * JPEG (issue #142).
	 * </p>
	 */
	private static void createHeifPreview(File file, File previewCache) throws IOException {
		HeifFile heif = HeifFile.read(file);
		Orientation orientation = heif.getOrientation();
		int displayWidth = heif.getDisplayWidth();
		int displayHeight = heif.getDisplayHeight();
		int[] box = previewBox(displayWidth, displayHeight);
		// Never scaled up, and the canvas no larger than the picture (issue #165).
		int previewWidth = Math.min(box[0], displayWidth);
		int previewHeight = Math.min(box[1], displayHeight);
		boolean swapped = Faces.swaps(orientation);
		int rawWidth = swapped ? previewHeight : previewWidth;
		int rawHeight = swapped ? previewWidth : previewHeight;

		BufferedImage raw = HeifDecoder.decodeRaw(file, heif, rawWidth, rawHeight);
		BufferedImage copy = new BufferedImage(previewWidth, previewHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = copy.createGraphics();
		try {
			g.setTransform(orientationTransform(orientation, rawWidth, rawHeight));
			g.drawImage(raw, null, 0, 0);
		} finally {
			g.dispose();
		}
		if (!ImageIO.write(copy, JPG, previewCache)) {
			throw new IOException("No JPEG writer.");
		}
	}

	/**
	 * The size of the canvas the preview of a picture of the given display size is drawn into, as
	 * <code>{width, height}</code> in pixels.
	 *
	 * <p>
	 * The one rule that says how large a preview is: {@value #PREVIEW_HEIGHT} pixels high, twice
	 * that for a portrait, and as wide as the picture's own aspect makes it. It is a method rather
	 * than arithmetic inside the generator because the face index of issue #140 has to know how
	 * large a face lands on the preview <em>before</em> deciding whether to look at the original,
	 * and a second copy of this rule would be a second answer to that question.
	 * </p>
	 *
	 * @param displayWidth
	 *        The width of the picture as the viewer sees it, the EXIF orientation applied.
	 * @param displayHeight
	 *        Its height, likewise.
	 */
	public static int[] previewBox(int displayWidth, int displayHeight) {
		if (displayWidth <= 0 || displayHeight <= 0) {
			return new int[] { PREVIEW_HEIGHT, PREVIEW_HEIGHT };
		}
		double unitWidth = ((double) displayWidth) / displayHeight;
		int previewHeight = unitWidth <= MAX_PORTRAIT_UNIT_WIDTH ? PREVIEW_HEIGHT_PORTRAIT : PREVIEW_HEIGHT;
		int previewWidth = Math.max(1, (int) Math.round(previewHeight * unitWidth));
		return new int[] { previewWidth, previewHeight };
	}

	/**
	 * Decodes the first image of the given reader, sampling only every <code>n</code>-th pixel.
	 */
	private static BufferedImage read(ImageReader reader, int n) throws IOException {
		ImageReadParam param = reader.getDefaultReadParam();
		if (n > 1) {
			param.setSourceSubsampling(n, n, 0, 0);
		}
		return reader.read(0, param);
	}

	/**
	 * The factor to sample the original with so that the decoded raster still covers the preview.
	 *
	 * <p>
	 * The largest <code>n &gt;= 1</code> with <code>origWidth / n &gt;= previewWidth</code> and
	 * <code>origHeight / n &gt;= previewHeight</code>, so that the decoded raster is never smaller
	 * than the preview (nothing is up-scaled that was not up-scaled before) and never more than
	 * twice its size in a dimension (a bounded amount of heap, independent of the original).
	 * </p>
	 */
	static int subsampling(int origWidth, int origHeight, int previewWidth, int previewHeight) {
		int n = Math.min(bound(origWidth, previewWidth), bound(origHeight, previewHeight));
		return Math.max(1, n);
	}

	private static int bound(int orig, int preview) {
		if (orig <= 0 || preview <= 0) {
			return 1;
		}
		return orig / preview;
	}

	/**
	 * The original's dimension as the viewer sees it, from the metadata where they have it.
	 *
	 * <p>
	 * Some files carry neither the JPEG frame header nor the PNG header the metadata are read
	 * from; the reader's own idea of the size stands in, so that no file is ever decoded without
	 * a bound on the raster.
	 * </p>
	 */
	private static ImageDimension imageDimension(Metadata metadata, ImageReader reader, boolean swapped)
			throws IOException {
		try {
			ImageDimension dimension = getImageDimension(metadata);
			if (dimension.getWidth() > 0 && dimension.getHeight() > 0) {
				return dimension;
			}
		} catch (MetadataException | IllegalArgumentException ex) {
			// No dimension in the metadata, ask the reader below.
		}
		int rawWidth = reader.getWidth(0);
		int rawHeight = reader.getHeight(0);
		return swapped ? new ImageDimension(rawHeight, rawWidth) : new ImageDimension(rawWidth, rawHeight);
	}

	/**
	 * The type to create the preview raster with: the decoded raster's own type, unless the reader
	 * produced a custom one that {@link BufferedImage} cannot be constructed with.
	 */
	private static int imageType(BufferedImage orig) {
		int type = orig.getType();
		return type == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : type;
	}

	/**
	 * The type to create a JPEG preview's raster with: the decoded raster's own type where the JPEG
	 * writer takes it, three bytes per pixel otherwise — for a picture with transparency (a WebP
	 * with alpha, a GIF with a transparent colour), which the writer refuses, and for the palette
	 * of a GIF, which a scaled drawing must not be forced back into (issue #190).
	 */
	private static int jpegType(BufferedImage orig) {
		switch (orig.getType()) {
			case BufferedImage.TYPE_3BYTE_BGR:
			case BufferedImage.TYPE_INT_RGB:
			case BufferedImage.TYPE_INT_BGR:
			case BufferedImage.TYPE_BYTE_GRAY:
				return orig.getType();
			default:
				return BufferedImage.TYPE_INT_RGB;
		}
	}

	/**
	 * The transform that brings the raw raster of a file with the given EXIF orientation upright,
	 * see issue #143.
	 *
	 * <p>
	 * It maps the raw rectangle <code>[0,rawWidth] x [0,rawHeight]</code> onto the shown rectangle
	 * <code>[0,displayWidth] x [0,displayHeight]</code> — one entry per orientation and no special
	 * case, the pixel form of the very table {@link de.haumacher.imageServer.faces.Faces} states for
	 * the normalised box of a face. Both have to say the same thing: a face is found on this
	 * preview and its box written back into the raw raster by that table, so a preview drawn by
	 * another rule would put every face in the wrong place.
	 * </p>
	 *
	 * <p>
	 * It replaces the former {@code scale(-1, 1)}, which mirrored about the axis <code>x = 0</code>
	 * rather than about the middle of the picture and therefore drew the four mirrored orientations
	 * (the codes 2, 4, 5 and 7) entirely <em>beside</em> the canvas: their preview was an empty
	 * rectangle, which is why the face index of issue #124 was answered no face at all for such a
	 * file.
	 * </p>
	 */
	public static AffineTransform orientationTransform(Orientation orientation, double rawWidth, double rawHeight) {
		// u = m00 * s + m01 * t + m02, v = m10 * s + m11 * t + m12, with (s,t) the raw pixel.
		switch (orientation) {
			case FLIP_H:
				return new AffineTransform(-1, 0, 0, 1, rawWidth, 0);
			case ROT_180:
				return new AffineTransform(-1, 0, 0, -1, rawWidth, rawHeight);
			case FLIP_V:
				return new AffineTransform(1, 0, 0, -1, 0, rawHeight);
			case ROT_L_FLIP_V:
				return new AffineTransform(0, 1, 1, 0, 0, 0);
			case ROT_L:
				return new AffineTransform(0, 1, -1, 0, rawHeight, 0);
			case ROT_L_FLIP_H:
				return new AffineTransform(0, -1, -1, 0, rawHeight, rawWidth);
			case ROT_R:
				return new AffineTransform(0, -1, 1, 0, 0, rawWidth);
			case IDENTITY:
			default:
				return new AffineTransform();
		}
	}

	private static ImageDimension getImageDimension(Metadata metadata) throws MetadataException {
		JpegDirectory jpegDirectory = metadata.getFirstDirectoryOfType(JpegDirectory.class);
		if (jpegDirectory != null) {
			int rawWidth = jpegDirectory.getImageWidth();
			int rawHeight = jpegDirectory.getImageHeight();
			int width, height;
			if (getImageOrientation(metadata) >= 5) {
				width = rawHeight;
				height = rawWidth;
			} else {
				width = rawWidth;
				height = rawHeight;
			}
			return new ImageDimension(width, height);
		}

		PngDirectory pngDirectory = metadata.getFirstDirectoryOfType(PngDirectory.class);
		if (pngDirectory != null) {
			int width = pngDirectory.getInt(PngDirectory.TAG_IMAGE_WIDTH);
			int height = pngDirectory.getInt(PngDirectory.TAG_IMAGE_HEIGHT);
			return new ImageDimension(width, height);
		}

		WebpDirectory webpDirectory = metadata.getFirstDirectoryOfType(WebpDirectory.class);
		if (webpDirectory != null && webpDirectory.containsTag(WebpDirectory.TAG_IMAGE_WIDTH)) {
			int rawWidth = webpDirectory.getInt(WebpDirectory.TAG_IMAGE_WIDTH);
			int rawHeight = webpDirectory.getInt(WebpDirectory.TAG_IMAGE_HEIGHT);
			// A WebP may carry an EXIF chunk, and its orientation is applied as a JPEG's is.
			boolean swapped = getImageOrientation(metadata) >= 5;
			return swapped ? new ImageDimension(rawHeight, rawWidth) : new ImageDimension(rawWidth, rawHeight);
		}

		GifHeaderDirectory gifDirectory = metadata.getFirstDirectoryOfType(GifHeaderDirectory.class);
		if (gifDirectory != null) {
			int width = gifDirectory.getInt(GifHeaderDirectory.TAG_IMAGE_WIDTH);
			int height = gifDirectory.getInt(GifHeaderDirectory.TAG_IMAGE_HEIGHT);
			return new ImageDimension(width, height);
		}

		throw new IllegalArgumentException("Neither JPG, PNG, WebP nor GIF image.");
	}

	private static int getImageOrientation(Metadata metadata) throws MetadataException {
		ExifIFD0Directory exifIFD0Directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
		if (exifIFD0Directory != null && exifIFD0Directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
			return exifIFD0Directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
		}
		return 1;
	}

	private static void createVideoPreview(File file, File previewCache) throws Exception,
			ImageProcessingException, IOException, MetadataException {
		BufferedImage image = getPreviewFrame(file);

		// The rotation of an mp4 and of a QuickTime movie alike (issue #189): a portrait iPhone
		// video is stored on its side and turned by the matrix of its track.
		int rotation = ImageData.rotation(ImageData.readMetadata(file));
		if (rotation != 0) {
			int rawWidth = image.getWidth();
			int rawHeight = image.getHeight();

			int width, height;
			if (rotation == 90 || rotation == 270) {
				width = rawHeight;
				height = rawWidth;
			} else {
				width = rawWidth;
				height = rawHeight;
			}

			BufferedImage copy = new BufferedImage(width, height, image.getType());
			Graphics2D g = (Graphics2D) copy.getGraphics();
			AffineTransform tx = new AffineTransform();
			tx.translate((width - rawWidth) / 2, (height - rawHeight) / 2);
			tx.rotate(-Math.toRadians(rotation), rawWidth / 2, rawHeight / 2);
			g.setTransform(tx);
			g.drawImage(image, null, 0, 0);

			image = copy;
		}
		ImageIO.write(image, JPG, previewCache);
	}

	private static BufferedImage getPreviewFrame(File file) throws Exception {
		try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(file)) {
			g.start();
			Frame firstFrame = g.grabKeyFrame();
			try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
				return converter.convert(firstFrame);
			}
		}
	}

}
