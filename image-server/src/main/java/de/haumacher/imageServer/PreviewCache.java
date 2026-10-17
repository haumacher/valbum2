/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.gif.GifHeaderDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.webp.WebpDirectory;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.cache.VideoProbe;
import de.haumacher.imageServer.coded.CodedPicture;
import de.haumacher.imageServer.coded.CodedPictures;
import de.haumacher.imageServer.faces.Faces;
import de.haumacher.imageServer.heif.HeifDecoder;
import de.haumacher.imageServer.heif.HeifFile;
import de.haumacher.imageServer.raw.RawFile;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
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

	private static final String MTS = "mts";

	private static final String M2TS = "m2ts";

	private static final String AVI = "avi";

	private static final String MKV = "mkv";

	private static final String WEBM = "webm";

	private static final String PNG = "png";

	private static final String JPEG = "jpeg";

	private static final String JPG = "jpg";

	private static final String HEIC = "heic";

	private static final String HEIF = "heif";

	private static final String AVIF = "avif";

	private static final String JXL = "jxl";

	private static final String WEBP = "webp";

	private static final String GIF = "gif";

	private static final String DNG = "dng";

	private static final String CR2 = "cr2";

	private static final String CR3 = "cr3";

	private static final String NEF = "nef";

	private static final String ARW = "arw";

	private static final String ORF = "orf";

	private static final String RW2 = "rw2";

	private static final String RAF = "raf";

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
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList(JPG, JPEG, PNG, WEBP, GIF, HEIC, HEIF, AVIF, JXL, MP4, MOV, M4V, THREE_GP, MTS, M2TS, AVI,
			MKV, WEBM, DNG, CR2, CR3, NEF, ARW, ORF, RW2, RAF)));

	/**
	 * The extensions of the raw photographs among {@link #SUPPORTED_EXTENSIONS}, lower case, see
	 * issue #191 and {@link RawFile}: a phone's DNG (Android, Apple ProRAW), Canon's CR2 and CR3,
	 * Nikon's NEF, Sony's ARW, Olympus' ORF, Panasonic's RW2 and Fujifilm's RAF. Each is shown through
	 * the JPEG preview it carries, and is downloaded as it came.
	 */
	public static final Set<String> RAW_EXTENSIONS =
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList(DNG, CR2, CR3, NEF, ARW, ORF, RW2, RAF)));

	/**
	 * The extensions of the videos among {@link #SUPPORTED_EXTENSIONS}, lower case: the ISO media
	 * and QuickTime containers the bundled FFmpeg reads — an mp4, a QuickTime movie (every iPhone
	 * video, issue #189), an iTunes <code>.m4v</code> and a 3GPP file of an older phone — and the
	 * containers only FFmpeg reads (issue #192, {@link VideoProbe}): an AVCHD camcorder's
	 * <code>.mts</code>/<code>.m2ts</code>, an older camera's <code>.avi</code>, a Matroska
	 * <code>.mkv</code> and a <code>.webm</code>. Each has a poster frame and the renditions of
	 * {@link VideoRenditions}.
	 */
	public static final Set<String> VIDEO_EXTENSIONS =
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList(MP4, MOV, M4V, THREE_GP, MTS, M2TS, AVI, MKV, WEBM)));

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
	 * How many times the {@link #previewBox(int, int) box} of a preview the large preview of issue
	 * #241 is: {@value} times as high and as wide, 1200 pixels high, 2400 for a portrait.
	 */
	public static final int LARGE_FACTOR = 2;

	/**
	 * What follows <code>preview-&lt;name&gt;</code> in the name of a large preview, see
	 * {@link #largePreviewFile(File, double[])} and issue #241.
	 */
	public static final String LARGE_INFIX = "-large";

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
	 * What the record of the original a generated file was made from was named after, behind the
	 * generated file's own name, by the builds of issue #235. No build writes one since issue #256:
	 * the freshness of a generated file is the background's, see {@link #drop(File)}. One left
	 * behind is litter, removed by {@link #dropLeftoverRecords(File)} when the background works on
	 * its folder.
	 */
	public static final String SOURCE_SUFFIX = ".source";

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
	 * Looks up or makes the preview of the given image or video file.
	 *
	 * <p>
	 * What the background does, see {@link CatchUp}; a request of a server with a background never
	 * makes one, it asks {@link #previewTarget(File, double[], boolean)} and {@link #present(File)}
	 * (issue #256). A preview that is there is taken as it is.
	 * </p>
	 */
	public static File createPreview(File file) throws PreviewException {
		String fileName = file.getName();
		String suffix = Util.suffix(fileName);
		String imageType = imageType(suffix);

		File previewCache = previewFile(file);
		if (!present(previewCache)) {
			if (!SUPPORTED_EXTENSIONS.contains(suffix)) {
				throw new PreviewException("Unsupported format: " + fileName);
			}
			generate(file, previewCache, tmp -> makePreview(file, tmp, suffix, imageType, 1));
		}
		return previewCache;
	}

	/** Where the preview of the given original lies, made or not, see {@link #createPreview(File)}. */
	public static File previewFile(File file) {
		String fileName = file.getName();
		String suffix = Util.suffix(fileName);
		String imageType = imageType(suffix);
		File cacheDir = new File(file.getParentFile(), CACHE_DIRECTORY_NAME);
		return new File(cacheDir, PREVIEW_PREFIX + fileName + (suffix.equals(imageType) ? "" : "." + imageType));
	}

	/** What follows <code>preview-&lt;name&gt;</code> in the name of a cut preview, see issue #212. */
	public static final String CROP_INFIX = "-c";

	/**
	 * Where the preview of the given region of the given photograph lies, made or not, see
	 * {@link #createPreview(File, double[])}: <code>preview-&lt;name&gt;-c&lt;12 hex&gt;.&lt;type&gt;</code>,
	 * the hex digits being {@link Crops#key(double[])}.
	 */
	public static File croppedPreviewFile(File file, double[] region) {
		String fileName = file.getName();
		String imageType = imageType(Util.suffix(fileName));
		return new File(new File(file.getParentFile(), CACHE_DIRECTORY_NAME),
			PREVIEW_PREFIX + fileName + CROP_INFIX + Crops.key(region) + "." + imageType);
	}

	/**
	 * Looks up or creates the preview of a region of the given photograph, see issue #212.
	 *
	 * <p>
	 * The region is given in the frame of the preview &mdash; the file upright by its own EXIF
	 * orientation, normalised to <code>0..1</code> &mdash; and the result is the preview a picture of
	 * exactly that region would have: as large as {@link #previewBox(int, int)} makes it for the
	 * region's own size and aspect, never larger than the region's pixels (issue #165). It is read
	 * out of the <em>original</em>, not out of the whole preview, so that a small region of a large
	 * photograph is as sharp in its tile as a whole photograph is in its own; and never as the whole
	 * raster: the region is decoded subsampled to at least the size it is wanted at
	 * ({@link Originals#decodeLongSideAtLeast}, the memory rule of issue #68), turned upright and
	 * scaled to the preview.
	 * </p>
	 *
	 * <p>
	 * Cached beside the whole preview under a name carrying the region
	 * ({@link #croppedPreviewFile(File, double[])}), made under the same permits, written to
	 * {@value #TMP_SUFFIX} and moved into place, dropped by the same rule (see {@link #drop(File)}). A
	 * video has none.
	 * </p>
	 *
	 * @param region
	 *        <code>{x, y, w, h}</code>, <code>null</code> for the whole picture, which is
	 *        {@link #createPreview(File)}.
	 */
	public static File createPreview(File file, double[] region) throws PreviewException {
		if (region == null) {
			return createPreview(file);
		}
		String fileName = file.getName();
		String suffix = Util.suffix(fileName);
		if (!SUPPORTED_EXTENSIONS.contains(suffix) || VIDEO_EXTENSIONS.contains(suffix)) {
			throw new PreviewException("No cut preview of '" + fileName + "'.");
		}
		File previewCache = croppedPreviewFile(file, region);
		if (!present(previewCache)) {
			generate(file, previewCache, tmp -> {
				try {
					createCroppedPreview(file, region, tmp, imageType(suffix), 1);
				} catch (PictureTooLargeException | de.haumacher.imageServer.raw.NoEmbeddedPreviewException ex) {
					throw new PreviewException(ex.getMessage(), ex);
				} catch (IOException | RuntimeException ex) {
					throw new PreviewException("Cannot create the cut preview of '" + fileName + "'.", ex);
				}
			});
		}
		return previewCache;
	}

	/**
	 * Where the large preview of the given original lies, made or not, see
	 * {@link #createLargePreview(File, double[])}: <code>preview-&lt;name&gt;-large.&lt;type&gt;</code>
	 * for the whole picture, <code>preview-&lt;name&gt;-large-c&lt;12 hex&gt;.&lt;type&gt;</code> for a
	 * region of it, beside the preview of the same picture so that both can be there.
	 *
	 * @param region
	 *        <code>{x, y, w, h}</code>, <code>null</code> for the whole picture.
	 */
	public static File largePreviewFile(File file, double[] region) {
		String fileName = file.getName();
		String imageType = imageType(Util.suffix(fileName));
		return new File(new File(file.getParentFile(), CACHE_DIRECTORY_NAME), PREVIEW_PREFIX + fileName + LARGE_INFIX
			+ (region == null ? "" : CROP_INFIX + Crops.key(region)) + "." + imageType);
	}

	/**
	 * Looks up or creates the large preview of the given photograph or of a region of it, see issue
	 * #241.
	 *
	 * <p>
	 * The preview a starred (2&times;2) tile of a listing asks for where its pixels exceed the
	 * preview's: made by the very rule of {@link #createPreview(File)} and
	 * {@link #createPreview(File, double[])} &mdash; the same orientation, the same cut, never
	 * larger than the picture or the region &mdash; with the {@link #previewBox(int, int) box}
	 * {@value #LARGE_FACTOR} times as high and as wide. Cached under its own name
	 * ({@link #largePreviewFile(File, double[])}) beside the preview, under the same permits and
	 * dropped by the same rule.
	 * </p>
	 *
	 * <p>
	 * A video has none: its poster is the frame at the video's own size and not made by the box,
	 * so the large preview of a video is its {@link #createPreview(File) preview}.
	 * </p>
	 *
	 * @param region
	 *        <code>{x, y, w, h}</code>, <code>null</code> for the whole picture.
	 */
	public static File createLargePreview(File file, double[] region) throws PreviewException {
		String fileName = file.getName();
		String suffix = Util.suffix(fileName);
		if (VIDEO_EXTENSIONS.contains(suffix)) {
			if (region != null) {
				throw new PreviewException("No cut preview of '" + fileName + "'.");
			}
			return createPreview(file);
		}
		if (!SUPPORTED_EXTENSIONS.contains(suffix)) {
			throw new PreviewException("Unsupported format: " + fileName);
		}
		String imageType = imageType(suffix);
		File previewCache = largePreviewFile(file, region);
		if (!present(previewCache)) {
			if (region == null) {
				generate(file, previewCache, tmp -> makePreview(file, tmp, suffix, imageType, LARGE_FACTOR));
			} else {
				generate(file, previewCache, tmp -> {
					try {
						createCroppedPreview(file, region, tmp, imageType, LARGE_FACTOR);
					} catch (PictureTooLargeException | de.haumacher.imageServer.raw.NoEmbeddedPreviewException ex) {
						throw new PreviewException(ex.getMessage(), ex);
					} catch (IOException | RuntimeException ex) {
						throw new PreviewException("Cannot create the cut preview of '" + fileName + "'.", ex);
					}
				});
			}
		}
		return previewCache;
	}

	/** What follows <code>preview-&lt;name&gt;</code> in the name of a video's chosen poster, see issue #285. */
	public static final String POSTER_INFIX = "-p";

	/**
	 * Where the poster of the given video at the given moment lies, made or not, see
	 * {@link #createPoster(File, long)}: <code>preview-&lt;name&gt;-p&lt;millis&gt;.jpg</code>, the
	 * {@link #previewFile(File) preview} itself for the first frame (a moment of <code>0</code> or
	 * less).
	 *
	 * <p>
	 * The moment is part of the name, as the region of a cut preview is (issue #212): a poster chosen
	 * anew is another file, so neither a cache on the server nor one in the app can show the frame
	 * chosen before, and a poster being made for the old moment while the new one is chosen lands
	 * under its own name instead of overwriting the new one.
	 * </p>
	 */
	public static File posterFile(File file, long millis) {
		if (millis <= 0) {
			return previewFile(file);
		}
		return new File(new File(file.getParentFile(), CACHE_DIRECTORY_NAME),
			PREVIEW_PREFIX + file.getName() + POSTER_INFIX + millis + "." + JPG);
	}

	/**
	 * Looks up or creates the poster of the given video at the given moment, see issue #285 and
	 * {@link de.haumacher.imageServer.shared.model.ImagePart#getPoster()}.
	 *
	 * <p>
	 * The frame shown at the moment (the first one at or after it, decoded from the key frame before
	 * it, never that key frame itself), drawn exactly as the {@link #createPreview(File) preview} of
	 * the first frame is &mdash; the field and the aspect of {@link #shownFrame} (issue #192), the
	 * rotation of the container &mdash; cached under its moment ({@link #posterFile(File, long)}),
	 * made under the same permits and dropped by the same rule. A moment of <code>0</code> or less is
	 * the preview of the first frame.
	 * </p>
	 *
	 * @param millis
	 *        The moment in milliseconds from the start of the video.
	 */
	public static File createPoster(File file, long millis) throws PreviewException {
		if (millis <= 0) {
			return createPreview(file);
		}
		String fileName = file.getName();
		if (!VIDEO_EXTENSIONS.contains(Util.suffix(fileName))) {
			throw new PreviewException("No poster of '" + fileName + "', it is no video.");
		}
		File poster = posterFile(file, millis);
		if (!present(poster)) {
			generate(file, poster, tmp -> {
				try {
					createVideoPreview(file, tmp, millis);
				} catch (ImageProcessingException | MetadataException | IOException ex) {
					throw new PreviewException("Cannot create the poster of '" + fileName + "' at " + millis + " ms.",
						ex);
				}
			});
		}
		return poster;
	}

	/** Writes the preview of the given region, see {@link #createPreview(File, double[])}. */
	private static void createCroppedPreview(File file, double[] region, File tmp, String imgType, int factor)
			throws IOException {
		Orientation exif = de.haumacher.imageServer.faces.FaceIndex.exifOrientation(file);
		int rawWidth;
		int rawHeight;
		if (CodedPictures.handles(file)) {
			CodedPicture coded = CodedPictures.read(file);
			rawWidth = coded.getRawWidth();
			rawHeight = coded.getRawHeight();
		} else {
			try (PictureReader picture = PictureReader.open(file)) {
				rawWidth = picture.getWidth();
				rawHeight = picture.getHeight();
			}
		}
		double[] raw = de.haumacher.imageServer.faces.Faces.toRaw(exif, region[0], region[1], region[2], region[3]);
		boolean swapped = de.haumacher.imageServer.faces.Faces.swaps(exif);

		// The region as it is shown, in pixels of the file.
		int shownWidth = Math.max(1, (int) Math.round((swapped ? rawHeight : rawWidth) * region[2]));
		int shownHeight = Math.max(1, (int) Math.round((swapped ? rawWidth : rawHeight) * region[3]));
		int[] box = previewBox(shownWidth, shownHeight, factor);
		// Never scaled up, and the canvas no larger than the region (issue #165).
		int previewWidth = Math.min(box[0], shownWidth);
		int previewHeight = Math.min(box[1], shownHeight);

		de.haumacher.imageServer.faces.Originals.Region read =
			de.haumacher.imageServer.faces.Originals.decodeLongSideAtLeast(file,
				raw[0] * rawWidth, raw[1] * rawHeight, (raw[0] + raw[2]) * rawWidth, (raw[1] + raw[3]) * rawHeight,
				Math.max(previewWidth, previewHeight));
		BufferedImage upright = de.haumacher.imageServer.faces.Originals.upright(read.getImage(), exif);

		BufferedImage copy = new BufferedImage(previewWidth, previewHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = copy.createGraphics();
		try {
			g.setColor(TRANSPARENT_BACKGROUND);
			g.fillRect(0, 0, previewWidth, previewHeight);
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(upright, 0, 0, previewWidth, previewHeight, null);
		} finally {
			g.dispose();
		}
		if (!ImageIO.write(copy, imgType, tmp)) {
			throw new IOException("No writer for '" + imgType + "'.");
		}
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
	 * expected to decode, which is a HEIC/HEIF photograph (issue #186), an AVIF or a JPEG XL picture
	 * (issue #193), see {@link CodedPictures}, and a raw one (issue #191), and nothing else.
	 */
	public static boolean needsDisplay(File file) {
		return CodedPictures.handles(file) || RawFile.isRaw(file);
	}

	/**
	 * Looks up or creates the display rendition of the given HEIC/HEIF photograph, see issue #186.
	 *
	 * <p>
	 * A JPEG of the photograph upright, at most {@value #DISPLAY_LONG_SIDE} pixels on its long side
	 * and never scaled up, made by the background (issue #256) and kept in
	 * {@value #CACHE_DIRECTORY_NAME} as <code>display-&lt;name&gt;.jpg</code>: what the viewer shows
	 * in place of an original that Chrome, Firefox and the app's own decoder cannot read. It is
	 * made exactly as a preview is — under one of the {@link #permits}, the second request waiting
	 * for the first, written to {@value #TMP_SUFFIX} and moved into place — and is dropped like one
	 * when its original changes ({@link #drop(File)}); <code>?action=refresh-cache</code> and the purge
	 * throw it away with the preview, see {@link CacheRefresh#isGenerated(String)}.
	 * </p>
	 */
	public static File createDisplay(File file) throws PreviewException {
		if (!needsDisplay(file)) {
			throw new PreviewException("No display rendition for '" + file.getName() + "'.");
		}
		File display = displayFile(file);
		if (!present(display)) {
			generate(file, display, tmp -> {
				if (RawFile.isRaw(file)) {
					createRawDisplay(file, tmp);
					return;
				}
				try {
					CodedPicture heif = CodedPictures.read(file);
					int width = heif.getDisplayWidth();
					int height = heif.getDisplayHeight();
					double scale = Math.min(1.0, ((double) DISPLAY_LONG_SIDE) / Math.max(width, height));
					heif.writeUprightJpeg(file, Math.max(1, (int) Math.round(width * scale)),
						Math.max(1, (int) Math.round(height * scale)), tmp);
				} catch (IOException ex) {
					throw new PreviewException("Cannot create the display rendition of '" + file.getName() + "': "
						+ ex.getMessage(), ex);
				}
			});
		}
		return display;
	}

	/**
	 * Writes the display rendition of a raw photograph, see issue #191: its embedded JPEG upright,
	 * subsampled by the smallest whole factor that brings its long side to at most
	 * {@value #DISPLAY_LONG_SIDE} pixels, so that the decode never holds more than the rendition.
	 */
	private static void createRawDisplay(File file, File tmp) throws PreviewException {
		try (PictureReader picture = PictureReader.open(file)) {
			Orientation orientation = Orientations.fromCode(getImageOrientation(ImageData.readMetadata(file)));
			int rawWidth = picture.getWidth();
			int rawHeight = picture.getHeight();
			int sampling = Math.max(1, (Math.max(rawWidth, rawHeight) + DISPLAY_LONG_SIDE - 1) / DISPLAY_LONG_SIDE);
			BufferedImage raw = picture.read(null, sampling);
			writeUpright(raw, orientation, tmp);
		} catch (PictureTooLargeException | de.haumacher.imageServer.raw.NoEmbeddedPreviewException ex) {
			throw new PreviewException(ex.getMessage(), ex);
		} catch (ImageProcessingException | MetadataException | IOException ex) {
			throw new PreviewException("Cannot create the display rendition of '" + file.getName() + "': "
				+ ex.getMessage(), ex);
		}
	}

	/** Writes the given raw raster as a JPEG, turned upright by the given orientation. */
	private static void writeUpright(BufferedImage raw, Orientation orientation, File target) throws IOException {
		boolean swapped = Faces.swaps(orientation);
		int width = swapped ? raw.getHeight() : raw.getWidth();
		int height = swapped ? raw.getWidth() : raw.getHeight();
		BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = copy.createGraphics();
		try {
			g.setTransform(orientationTransform(orientation, raw.getWidth(), raw.getHeight()));
			g.drawImage(raw, null, 0, 0);
		} finally {
			g.dispose();
		}
		if (!ImageIO.write(copy, JPG, target)) {
			throw new IOException("No JPEG writer.");
		}
	}

	/** Writes a generated file into the temporary name it is given. */
	private interface Maker {

		/** Writes into the given temporary file. */
		void make(File tmp) throws PreviewException;
	}

	/** Writes the preview of the given original. */
	private static void makePreview(File file, File tmp, String suffix, String imageType, int factor)
			throws PreviewException {
		String fileName = file.getName();
		switch (suffix) {
			case JPG:
			case JPEG:
			case PNG:
			case WEBP:
			case GIF:
			case DNG:
			case CR2:
			case CR3:
			case NEF:
			case ARW:
			case ORF:
			case RW2:
			case RAF:
				// A raw photograph is read through the JPEG preview it carries, which PictureReader
				// opens in its place, see issue #191.
				try {
					createImagePreview(file, tmp, imageType, factor);
				} catch (PictureTooLargeException | de.haumacher.imageServer.raw.NoEmbeddedPreviewException ex) {
					// Its message says what is wrong and what to do, and is what the request is answered.
					throw new PreviewException(ex.getMessage(), ex);
				} catch (ImageProcessingException | MetadataException | IOException ex) {
					throw new PreviewException("Cannot create image preview for '" + fileName + "'.", ex);
				}
				break;
			case HEIC:
			case HEIF:
			case AVIF:
			case JXL:
				try {
					createHeifPreview(file, tmp, factor);
				} catch (IOException ex) {
					throw new PreviewException("Cannot create image preview for '" + fileName + "': "
						+ ex.getMessage(), ex);
				}
				break;
			default:
				try {
					createVideoPreview(file, tmp, 0);
				} catch (ImageProcessingException | MetadataException | IOException ex) {
					throw new PreviewException("Cannot create video preview for '" + fileName + "'.", ex);
				}
				break;
		}
	}

	/**
	 * Whether the given generated file is there to be served, see issue #256.
	 *
	 * <p>
	 * A look at the generated file alone: neither the original nor any record of it is read. Whether
	 * the file still describes its original is the background's business &mdash; when it finds an
	 * original changed (its size or its modification time no longer the ones of
	 * {@link de.haumacher.imageServer.cache.AnalysisRecord}, compared for equality, which also
	 * catches <code>cp -p</code> setting an old stamp), it {@link #drop(File) drops} everything made
	 * of it and makes it again. A file from before the last change of the generator
	 * ({@link #LAST_UPDATE}) counts as missing. The temporary file a generation writes is never
	 * mistaken for a generated file: it is named {@value #TMP_SUFFIX} behind the file's own name and
	 * nothing ever looks it up.
	 * </p>
	 */
	public static boolean present(File generated) {
		return generated.isFile() && generated.lastModified() >= LAST_UPDATE;
	}

	/**
	 * Why the background could not make the given generated file, <code>null</code> where it did not
	 * fail (or did not try yet), see {@link #FAILURES}.
	 */
	public static PreviewException failure(File generated) {
		return FAILURES.get(generated.getAbsolutePath());
	}

	/**
	 * What the generation of a file failed with in this process, by the absolute path of the file it
	 * was to make: a request for it is answered the failure instead of "not there yet" (issue #256),
	 * so the app does not wait for what will not come. Kept in memory only: a restart tries again.
	 */
	private static final ConcurrentHashMap<String, PreviewException> FAILURES = new ConcurrentHashMap<>();

	/** How many generated files were made in this process, see {@link #made()}. */
	private static final java.util.concurrent.atomic.AtomicLong MADE = new java.util.concurrent.atomic.AtomicLong();

	/**
	 * How many generated files were made (an original read for it) in this process: what a test
	 * counts to tell that a request decoded nothing, see issue #256.
	 */
	public static long made() {
		return MADE.get();
	}

	/** Counts one generated file made outside of {@link #generate}, a face crop, see {@link #made()}. */
	public static void countMade() {
		MADE.incrementAndGet();
	}

	/**
	 * Throws away every file generated from the given original, see issue #256: the background found
	 * it changed. What is gone is made again by the background's next steps; a request meanwhile is
	 * answered "not there yet".
	 *
	 * @return How many files were deleted.
	 */
	public static int drop(File original) {
		int dropped = 0;
		for (File generated : DeleteService.generatedOf(original.getParentFile(), original.getName())) {
			FAILURES.remove(generated.getAbsolutePath());
			if (generated.delete()) {
				dropped++;
			} else {
				LOG.log(Level.WARNING, "Cannot delete the stale file '" + generated.getAbsolutePath() + "'.");
			}
		}
		String prefix = CacheRefresh.cacheDir(original.getParentFile()).getAbsolutePath() + File.separator;
		FAILURES.keySet().removeIf(key -> key.startsWith(prefix + PREVIEW_PREFIX + original.getName())
			|| key.startsWith(prefix + DISPLAY_PREFIX + original.getName()));
		return dropped;
	}

	/** Forgets every failure of a generated file of the given folder, see {@link CacheRefresh#refresh(File)}. */
	static void forgetFailures(File folder) {
		String prefix = CacheRefresh.cacheDir(folder).getAbsolutePath() + File.separator;
		FAILURES.keySet().removeIf(key -> key.startsWith(prefix));
	}

	/**
	 * Removes the records of the originals that the builds of issue #235 wrote beside every generated
	 * file ({@value #SOURCE_SUFFIX}), from the given folder's cache directory, see issue #256.
	 *
	 * <p>
	 * Litter since no build reads or writes them: removed by the background when it works on the
	 * folder (its previews step), which every folder passes at the first start after the upgrade.
	 * The generated files themselves stay and are taken as current; nothing is made again for the
	 * upgrade.
	 * </p>
	 *
	 * @return How many were removed.
	 */
	public static int dropLeftoverRecords(File folder) {
		File[] files = CacheRefresh.cacheDir(folder).listFiles(
			(dir, name) -> name.endsWith(SOURCE_SUFFIX) && CacheRefresh.isGenerated(name));
		if (files == null) {
			return 0;
		}
		int removed = 0;
		for (File file : files) {
			if (file.isFile() && file.delete()) {
				removed++;
			}
		}
		return removed;
	}

	/**
	 * Where the preview a request asks for lies, see issue #256: the {@link #previewFile(File) preview}
	 * of the original, its {@link #croppedPreviewFile(File, double[]) cut} one for a region, or the
	 * {@link #largePreviewFile(File, double[]) large} one, as {@link #createPreview(File, double[])}
	 * and {@link #createLargePreview(File, double[])} make them. Nothing is made, nothing is read.
	 *
	 * @throws PreviewException
	 *         Where there is no such preview at all: a format of no preview, a cut preview of a video.
	 */
	public static File previewTarget(File file, double[] region, boolean large) throws PreviewException {
		return previewTarget(file, region, large, 0);
	}

	/**
	 * Where the preview a request asks for lies, see {@link #previewTarget(File, double[], boolean)}:
	 * for a video with a chosen moment (issue #285) its {@link #posterFile(File, long) poster} at that
	 * moment, of every size.
	 *
	 * @param posterMillis
	 *        The moment of a video's poster, <code>0</code> for the first frame.
	 */
	public static File previewTarget(File file, double[] region, boolean large, long posterMillis)
			throws PreviewException {
		String fileName = file.getName();
		String suffix = Util.suffix(fileName);
		if (!SUPPORTED_EXTENSIONS.contains(suffix)) {
			throw new PreviewException("Unsupported format: " + fileName);
		}
		if (VIDEO_EXTENSIONS.contains(suffix)) {
			if (region != null) {
				throw new PreviewException("No cut preview of '" + fileName + "'.");
			}
			return posterFile(file, posterMillis);
		}
		if (posterMillis > 0) {
			throw new PreviewException("No poster of '" + fileName + "', it is no video.");
		}
		if (large) {
			return largePreviewFile(file, region);
		}
		return region == null ? previewFile(file) : croppedPreviewFile(file, region);
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
			FAILURES.remove(key);
			mine.complete(null);
		} catch (PreviewException ex) {
			FAILURES.put(key, ex);
			mine.completeExceptionally(ex);
			throw ex;
		} catch (RuntimeException | Error ex) {
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
			if (present(previewCache)) {
				return;
			}

			File cacheDir = previewCache.getParentFile();
			if (!cacheDir.exists()) {
				cacheDir.mkdirs();
			}
			File tmp = new File(cacheDir, previewCache.getName() + TMP_SUFFIX);
			hook.generationStarted(file);
			MADE.incrementAndGet();
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
	 *
	 * <p>
	 * The pixels are read through {@link PictureReader} (issue #207), which composes the first frame
	 * of a GIF or an animated WebP onto its canvas and makes a decoder that holds the whole picture —
	 * a WebP — reserve that memory first, refusing with a {@link PictureTooLargeException} what the
	 * heap cannot hold.
	 * </p>
	 */
	private static void createImagePreview(File file, File previewCache, String imgType, int factor)
			throws ImageProcessingException, IOException, MetadataException {
		Metadata metadata = ImageData.readMetadata(file);
		int orientationCode = getImageOrientation(metadata);
		Orientation orientation = Orientations.fromCode(orientationCode);
		boolean swapped = orientationCode >= 5;

		try (PictureReader picture = PictureReader.open(file)) {
			// The picture of a raw is its embedded JPEG, whatever size the raw's own tags say.
			ImageDimension dimension = RawFile.isRaw(file) ? imageDimension(picture, swapped)
				: imageDimension(metadata, picture, swapped);
			int origWidth = dimension.getWidth();
			int origHeight = dimension.getHeight();

			int[] box = previewBox(origWidth, origHeight, factor);
			int previewWidth = box[0];
			int previewHeight = box[1];

			BufferedImage orig = picture.read(null, subsampling(origWidth, origHeight, previewWidth, previewHeight));

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
	 *
	 * <p>
	 * An AVIF takes the same path with the program's AV1 decoder, and a JPEG XL picture is decoded
	 * by {@link de.haumacher.imageServer.jxl.JxlFile}, which samples the whole picture down to the
	 * raster asked for (issue #193); both are a {@link CodedPicture}.
	 * </p>
	 */
	private static void createHeifPreview(File file, File previewCache, int factor) throws IOException {
		CodedPicture heif = CodedPictures.read(file);
		Orientation orientation = heif.getOrientation();
		int displayWidth = heif.getDisplayWidth();
		int displayHeight = heif.getDisplayHeight();
		int[] box = previewBox(displayWidth, displayHeight, factor);
		// Never scaled up, and the canvas no larger than the picture (issue #165).
		int previewWidth = Math.min(box[0], displayWidth);
		int previewHeight = Math.min(box[1], displayHeight);
		boolean swapped = Faces.swaps(orientation);
		int rawWidth = swapped ? previewHeight : previewWidth;
		int rawHeight = swapped ? previewWidth : previewHeight;

		BufferedImage raw = heif.decodeRaw(file, rawWidth, rawHeight);
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
		return previewBox(displayWidth, displayHeight, 1);
	}

	/**
	 * The {@link #previewBox(int, int) box} of a preview the given number of times as high and as
	 * wide, see {@link #LARGE_FACTOR} and issue #241: the same rule, scaled.
	 */
	static int[] previewBox(int displayWidth, int displayHeight, int factor) {
		if (displayWidth <= 0 || displayHeight <= 0) {
			return new int[] { factor * PREVIEW_HEIGHT, factor * PREVIEW_HEIGHT };
		}
		double unitWidth = ((double) displayWidth) / displayHeight;
		int previewHeight =
			factor * (unitWidth <= MAX_PORTRAIT_UNIT_WIDTH ? PREVIEW_HEIGHT_PORTRAIT : PREVIEW_HEIGHT);
		int previewWidth = Math.max(1, (int) Math.round(previewHeight * unitWidth));
		return new int[] { previewWidth, previewHeight };
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
	private static ImageDimension imageDimension(Metadata metadata, PictureReader picture, boolean swapped) {
		try {
			ImageDimension dimension = getImageDimension(metadata);
			if (dimension.getWidth() > 0 && dimension.getHeight() > 0) {
				return dimension;
			}
		} catch (MetadataException | IllegalArgumentException ex) {
			// No dimension in the metadata, ask the reader below.
		}
		return imageDimension(picture, swapped);
	}

	/** The picture's dimension as the viewer sees it, as the reader says it. */
	private static ImageDimension imageDimension(PictureReader picture, boolean swapped) {
		int rawWidth = picture.getWidth();
		int rawHeight = picture.getHeight();
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
		return ImageData.orientationCode(metadata);
	}

	/**
	 * Writes the poster of the given video: the frame at the given moment, see
	 * {@link #createPoster(File, long)}, the first key frame for a moment of <code>0</code>.
	 */
	private static void createVideoPreview(File file, File previewCache, long millis) throws Exception,
			ImageProcessingException, IOException, MetadataException {
		BufferedImage image = getPreviewFrame(file, millis);
		if (image == null) {
			throw new IOException("No picture in '" + file.getName() + "'.");
		}

		VideoProbe probe = VideoProbe.handles(file.getName()) ? VideoProbe.probe(file) : null;
		if (probe != null) {
			image = shownFrame(image, probe);
		}

		// The rotation of an mp4 and of a QuickTime movie alike (issue #189): a portrait iPhone
		// video is stored on its side and turned by the matrix of its track. A container only
		// FFmpeg reads says it in its display matrix (issue #192), and so does a movie over 4 GiB
		// metadata-extractor cannot read (issue #255).
		int rotation = probe != null ? probe.getRotation() : ImageData.videoRotation(file);
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

	/**
	 * The poster frame of a video read through {@link VideoProbe} as it is shown, before its
	 * rotation, see issue #192.
	 *
	 * <p>
	 * An interlaced frame (an AVCHD camcorder's 1080i) holds two fields half a frame time apart,
	 * which a still picture shows as combs on everything that moves: the poster is drawn from the
	 * first field's lines alone, doubled. And a raster with a sample aspect ratio (HDV's and
	 * AVCHD's 1440 × 1080) is stretched to the width it is shown at.
	 * </p>
	 */
	private static BufferedImage shownFrame(BufferedImage frame, VideoProbe probe) {
		int rawWidth = frame.getWidth();
		int rawHeight = frame.getHeight();
		boolean quarter = probe.getRotation() == 90 || probe.getRotation() == 270;
		int width = quarter ? probe.getHeight() : probe.getWidth();
		int height = quarter ? probe.getWidth() : probe.getHeight();
		boolean field = probe.isInterlaced() && rawHeight >= 2;
		if (!field && width == rawWidth && height == rawHeight) {
			return frame;
		}
		BufferedImage source = frame;
		if (field) {
			source = new BufferedImage(rawWidth, (rawHeight + 1) / 2, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < source.getHeight(); y++) {
				for (int x = 0; x < rawWidth; x++) {
					source.setRGB(x, y, frame.getRGB(x, 2 * y));
				}
			}
		}
		BufferedImage shown = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = shown.createGraphics();
		try {
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(source, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		return shown;
	}

	/**
	 * The raw frame of the given video at the given moment, before {@link #shownFrame} and the
	 * rotation: the first key frame for a moment of <code>0</code> or less, the poster every video had
	 * before issue #285.
	 *
	 * <p>
	 * For a later moment the grabber seeks to the key frame before it and decodes forward to the
	 * first frame at or after it ({@link FFmpegFrameGrabber#setVideoTimestamp(long)}, then frames are
	 * read on until one is at the moment, whatever the seek left behind): what the player showed when
	 * it was stopped there, not the key frame a plain seek lands on, which may be seconds earlier. A
	 * moment past the last frame (the container's duration rounds up, or the player stopped on its
	 * last frame) is the last frame there is.
	 * </p>
	 */
	static BufferedImage getPreviewFrame(File file, long millis) throws Exception {
		try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(file);
				Java2DFrameConverter converter = new Java2DFrameConverter()) {
			g.start();
			if (millis <= 0) {
				return converter.convert(g.grabKeyFrame());
			}
			long target = millis * 1000L;
			// Half a frame early still is the frame at the moment: a time stamp of the decoder is
			// rounded to its own time base.
			double rate = g.getFrameRate();
			long slack = rate > 0 ? (long) (500_000 / rate) : 0;
			g.setVideoTimestamp(target);
			Frame frame;
			while ((frame = g.grabImage()) != null) {
				if (frame.timestamp >= target - slack) {
					return converter.convert(frame);
				}
			}
			// Past the last frame: the last one, read from a key frame a little before the moment.
			g.setTimestamp(Math.max(0, target - LAST_FRAME_WINDOW));
			BufferedImage last = null;
			while ((frame = g.grabImage()) != null) {
				last = Java2DFrameConverter.cloneBufferedImage(converter.convert(frame));
			}
			if (last != null) {
				return last;
			}
			g.setTimestamp(0);
			return converter.convert(g.grabKeyFrame());
		}
	}

	/**
	 * How far before a moment past the last frame the last frame is looked for, in microseconds, see
	 * {@link #getPreviewFrame(File, long)}.
	 */
	private static final long LAST_FRAME_WINDOW = 3_000_000L;


}
