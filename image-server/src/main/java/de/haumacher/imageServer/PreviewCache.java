/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.png.PngDirectory;
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

	private static final String PNG = "png";

	private static final String JPEG = "jpeg";

	private static final String JPG = "jpg";

	public static final Set<String> SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(JPG, JPEG, PNG, MP4)));

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
			generate(file, previewCache, suffix, imageType);
		}
		return previewCache;
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
	private static void generate(File file, File previewCache, String suffix, String imageType)
			throws PreviewException {
		String key = previewCache.getAbsolutePath();
		CompletableFuture<Void> mine = new CompletableFuture<>();
		CompletableFuture<Void> running = IN_FLIGHT.putIfAbsent(key, mine);
		if (running != null) {
			await(running, previewCache);
			return;
		}
		try {
			build(file, previewCache, suffix, imageType);
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
	private static void build(File file, File previewCache, String suffix, String imageType)
			throws PreviewException {
		String fileName = file.getName();
		if (!SUPPORTED_EXTENSIONS.contains(suffix)) {
			throw new PreviewException("Unsupported format: " + fileName);
		}

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
				switch (suffix) {
					case JPG:
					case JPEG:
					case PNG:
						try {
							createImagePreview(file, tmp, imageType);
						} catch (ImageProcessingException | MetadataException | IOException ex) {
							throw new PreviewException("Cannot create image preview for '" + fileName + "'.", ex);
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

				BufferedImage copy = new BufferedImage(previewWidth, previewHeight, imageType(orig));
				Graphics2D g = (Graphics2D) copy.getGraphics();

				double scaleX = Math.min(1.0, ((double) previewWidth) / decodedWidth);
				double scaleY = Math.min(1.0, ((double) previewHeight) / decodedHeight);

				AffineTransform tx = new AffineTransform();
				tx.translate((previewWidth - rawWidth * scaleX) / 2, (previewHeight - rawHeight * scaleY) / 2);
				tx.scale(scaleX, scaleY);
				applyOrientation(tx, orientation, rawWidth / 2, rawHeight / 2);
				g.setTransform(tx);

				g.drawImage(orig, null, 0, 0);
				ImageIO.write(copy, imgType, previewCache);
			} finally {
				reader.dispose();
			}
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

	private static void applyOrientation(AffineTransform tx, Orientation orientation, int centerX, int centerY) {
		int rotation = 0;
		boolean flip = false;
		switch (orientation) {
			case IDENTITY:
				break;
			case FLIP_H:
				flip = true;
				break;
			case ROT_180:
				rotation = 180;
				break;
			case FLIP_V:
				rotation = 180;
				flip = true;
				break;
			case ROT_L_FLIP_V:
				rotation = -90;
				flip = true;
				break;
			case ROT_L:
				rotation = 90;
				break;
			case ROT_L_FLIP_H:
				rotation = 90;
				flip = true;
				break;
			case ROT_R:
				rotation = -90;
				break;
			default:
				break;
		}
		if (flip) {
			tx.scale(-1, 1);
		}
		tx.rotate(Math.toRadians(rotation), centerX, centerY);
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

		throw new IllegalArgumentException("Neither JPG nor PNG image.");
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

		Metadata metadata = ImageMetadataReader.readMetadata(file);
		Mp4Directory mp4Directory = metadata.getFirstDirectoryOfType(Mp4Directory.class);
		if (mp4Directory != null && mp4Directory.containsTag(Mp4Directory.TAG_ROTATION)) {
			int rotation = mp4Directory.getInt(Mp4Directory.TAG_ROTATION);
			while (rotation < 0) {
				rotation += 360;
			}
			if (rotation != 0) {
				// Apply transformation to the preview image.

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
