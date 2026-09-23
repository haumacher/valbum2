/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The detector and the recogniser themselves: OpenCV, two bundled ONNX models, see issue #124.
 *
 * <p>
 * The same shape as {@link de.haumacher.imageServer.VideoRenditions} gives FFmpeg (issue #74): a
 * bundled binary that a platform may simply not have, asked once whether it works at all, and a
 * server that says so in one line and goes on serving albums when it does not. Nothing here is
 * ever on the request path — {@link FaceIndex} calls it on a thread of its own.
 * </p>
 *
 * <h2>Which OpenCV</h2>
 *
 * <p>
 * The <code>org.bytedeco:opencv</code> artifact the JavaCPP presets ship is already in this
 * server's dependencies (the video path pulls in <code>javacv-platform</code>), so the face index
 * costs the packages not one further native library, see
 * <code>TestDebianPackageLibraries</code>. What it does <em>not</em> ship is a JavaCPP wrapper of
 * <code>FaceDetectorYN</code>/<code>FaceRecognizerSF</code> at this version; the official OpenCV
 * Java bindings (<code>org.opencv.*</code>, loaded through
 * <code>org.bytedeco.opencv.opencv_java</code>) are in the very same artifact and do have them,
 * so that is the door used here.
 * </p>
 *
 * <h2>The models</h2>
 *
 * <p>
 * Bundled as resources beside this class, with their sources and licences in
 * <code>NOTICE.txt</code>. OpenCV can only be handed a <em>file</em> name, so they are unpacked
 * once into a directory of the temporary file space, named after the model stamp — never into the
 * library, which this server writes nothing into but an album's own cache.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class FaceDetection {

	private static final Logger LOG = Logger.getLogger(FaceDetection.class.getName());

	/** The bundled detector. */
	static final String DETECTOR_RESOURCE = "face_detection_yunet_2022mar.onnx";

	/** The bundled recogniser. */
	static final String RECOGNISER_RESOURCE = "face_recognition_sface_2021dec.onnx";

	/**
	 * What every cached detection is stamped with, see {@link FaceCache}.
	 *
	 * <p>
	 * The two models and the shape of what is computed from them. Change it whenever any of those
	 * changes and every album re-detects itself, because a cache whose stamp does not match is not
	 * read at all. It is the only invalidation rule there is besides the content hash.
	 * </p>
	 */
	public static final String MODEL = "yunet-2022mar+sface-2021dec/1";

	/**
	 * The size the detector's input is scaled to at most, on the long side.
	 *
	 * <p>
	 * The preview of {@link de.haumacher.imageServer.PreviewCache} is 600 px high (1200 for a
	 * portrait), so it normally passes through untouched; the limit is there so that a panorama
	 * cannot hand the network a raster of arbitrary width.
	 * </p>
	 */
	static final int MAX_INPUT = 1600;

	/**
	 * How small a face may be on the preview and still be kept, in pixels of the shorter side.
	 *
	 * <p>
	 * Below this the crop is a few blurred pixels and the embedding is noise: a face that small in
	 * a 600 px preview is a face in a crowd, which this index is not for.
	 * </p>
	 */
	static final double MIN_FACE_PIXELS = 40.0;

	/**
	 * How small a face may be on the preview and still be kept when the original confirms it, see
	 * issue #140.
	 *
	 * <p>
	 * Below {@link #MIN_FACE_PIXELS} the preview says almost nothing — but the file usually says a
	 * great deal, because a face 24&nbsp;px across on a 600&nbsp;px preview of a 24&nbsp;megapixel
	 * photograph is 240&nbsp;px in the file. Such a candidate is therefore kept <em>only</em> when
	 * a second look at the original finds a face where the preview said one is; one that is not
	 * confirmed is dropped, so looking further down does not mean believing more.
	 * </p>
	 */
	static final double MIN_FACE_PIXELS_REFINABLE = 24.0;

	/**
	 * Below how many pixels on the preview a face is described from the original instead, see issue
	 * #140.
	 *
	 * <p>
	 * The input size of SFace: a face smaller than this reaches the recogniser upscaled, which is
	 * exactly the loss issue #140 is about. A face at or above it is described from the preview as
	 * before — there is nothing to gain, and the original is not opened at all.
	 * </p>
	 */
	static final double REFINE_PIXELS = 112.0;

	/** How well a face found in the original must meet the box the preview named. */
	static final double REFINE_IOU = 0.3;

	/** How sure the detector must be. */
	static final float SCORE_THRESHOLD = 0.9f;

	/** How much two boxes may overlap before the weaker one is dropped. */
	static final float NMS_THRESHOLD = 0.3f;

	/** How many faces at most are taken from one photograph. */
	static final int TOP_K = 100;

	/** What one photograph's preview was found to hold. */
	public static final class Result {

		private final int _width;

		private final int _height;

		private final List<Detected> _faces;

		/** Creates a {@link Result}; a {@link Detector} of one's own builds one of these. */
		public Result(int width, int height, List<Detected> faces) {
			_width = width;
			_height = height;
			_faces = faces;
		}

		/** The width of the preview the faces were found on, in pixels. */
		public int getWidth() {
			return _width;
		}

		/** The height of the preview the faces were found on, in pixels. */
		public int getHeight() {
			return _height;
		}

		/** The faces, in the pixels of that preview. */
		public List<Detected> getFaces() {
			return _faces;
		}
	}

	/** One face as the detector and the recogniser saw it, in the pixels of the preview. */
	public static final class Detected {

		private final double _x;

		private final double _y;

		private final double _w;

		private final double _h;

		private final double _score;

		private final float[] _embedding;

		private final boolean _refined;

		/** Creates a {@link Detected} face, in the pixels of the preview it was found on. */
		public Detected(double x, double y, double w, double h, double score, float[] embedding) {
			this(x, y, w, h, score, embedding, false);
		}

		/**
		 * Creates a {@link Detected} face that says whether it was described from the original, see
		 * issue #140.
		 */
		public Detected(double x, double y, double w, double h, double score, float[] embedding,
				boolean refined) {
			_x = x;
			_y = y;
			_w = w;
			_h = h;
			_score = score;
			_embedding = embedding;
			_refined = refined;
		}

		/**
		 * Whether this face was looked at in the original because the preview was too small for it,
		 * see issue #140.
		 */
		public boolean isRefined() {
			return _refined;
		}

		/** The left edge of the face, in pixels of the preview. */
		public double getX() {
			return _x;
		}

		/** The top edge of the face, in pixels of the preview. */
		public double getY() {
			return _y;
		}

		/** The width of the face, in pixels of the preview. */
		public double getW() {
			return _w;
		}

		/** The height of the face, in pixels of the preview. */
		public double getH() {
			return _h;
		}

		/** How sure the detector was, between zero and one. */
		public double getScore() {
			return _score;
		}

		/** The 128 numbers the recogniser describes this face with; never answered on the wire. */
		public float[] getEmbedding() {
			return _embedding;
		}
	}

	/** What a second look at the original said about one face, in the pixels of the preview. */
	public static final class Refined {

		private final double _x;

		private final double _y;

		private final double _w;

		private final double _h;

		private final float[] _embedding;

		/**
		 * Creates a {@link Refined} face.
		 *
		 * <p>
		 * The box is answered in the pixels of the <em>preview</em>, although it was measured in the
		 * original: the refiner owns the mapping between the two frames (it had to build the region
		 * from it), and everything in this class speaks preview pixels, so there is exactly one
		 * place where the two frames meet, see {@link FaceIndex}.
		 * </p>
		 */
		public Refined(double x, double y, double w, double h, float[] embedding) {
			_x = x;
			_y = y;
			_w = w;
			_h = h;
			_embedding = embedding == null ? new float[0] : embedding;
		}

		/** The left edge of the face, in pixels of the preview. */
		public double getX() {
			return _x;
		}

		/** The top edge of the face, in pixels of the preview. */
		public double getY() {
			return _y;
		}

		/** The width of the face, in pixels of the preview. */
		public double getW() {
			return _w;
		}

		/** The height of the face, in pixels of the preview. */
		public double getH() {
			return _h;
		}

		/** The numbers the recogniser computed from the original; never answered on the wire. */
		public float[] getEmbedding() {
			return _embedding;
		}
	}

	/**
	 * A second look at one candidate in the original it was found in, see issue #140.
	 *
	 * <p>
	 * The seam between the detector, which knows nothing but a raster, and {@link FaceIndex}, which
	 * knows which file the preview was made from and how the two frames map onto each other. A test
	 * stubs it to say what a refinement would have found — or that it found nothing, which is how
	 * the rule about a candidate below {@link #MIN_FACE_PIXELS} is asked.
	 * </p>
	 */
	public interface Refiner {

		/**
		 * A better description of the given candidate, or <code>null</code> when the original holds
		 * no face where the preview said one is.
		 *
		 * @throws IOException
		 *         When the original could not be looked at at all — which is not the same as
		 *         finding nothing: the candidate keeps what the preview said and is looked at again
		 *         on a later pass.
		 */
		Refined refine(Detected candidate) throws IOException;
	}

	/** What one photograph is handed to, so that a test can count calls without a model. */
	public interface Detector {

		/** The faces of the given preview image, in the pixels of that preview. */
		Result detect(File preview) throws IOException;

		/**
		 * The same, with a way of looking at the original where the preview is too small, see issue
		 * #140.
		 *
		 * <p>
		 * A detector of a test's own answers what it always answered: it decides the boxes itself
		 * and there is nothing about them that a second look could improve.
		 * </p>
		 */
		default Result detect(File preview, Refiner refiner) throws IOException {
			return detect(preview);
		}

		/**
		 * The face of a region of an original that somebody marked, see issue #155 and
		 * {@link FaceDetection#search(java.awt.image.BufferedImage, double, double, double, double)}.
		 *
		 * <p>
		 * A detector of a test's own finds nothing there unless it says otherwise, so that a test
		 * which never thought about marking sees a marked region stored as a region, which is what a
		 * detector that finds nothing makes of it.
		 * </p>
		 */
		default Refined search(java.awt.image.BufferedImage region, double x, double y, double w, double h)
				throws IOException {
			return null;
		}
	}

	private FaceDetection() {
		// Static use only.
	}

	private static final Object LOCK = new Object();

	private static volatile String _unavailable;

	private static volatile boolean _checked;

	private static volatile Detector _detector;

	private static Object _yunet;

	private static Object _sface;

	/**
	 * Replaces what a photograph is handed to. Tests only.
	 *
	 * <p>
	 * What was found out about the machine itself is deliberately <em>not</em> forgotten by this: a
	 * machine either loads OpenCV or it does not, that does not change because somebody installed a
	 * detector of their own, and forgetting it would mean loading the recogniser — forty megabytes
	 * of weights — once per test.
	 * </p>
	 *
	 * @param detector
	 *        <code>null</code> to go back to the bundled models.
	 */
	public static void setDetector(Detector detector) {
		synchronized (LOCK) {
			_detector = detector;
		}
	}

	/**
	 * Why faces cannot be detected on this machine, <code>null</code> when they can.
	 *
	 * <p>
	 * Asked once and remembered, exactly like
	 * {@link de.haumacher.imageServer.VideoRenditions#unavailability()}: a machine whose OpenCV
	 * does not load does not grow one while the server runs, and a space that asked for faces is
	 * told so once, in one line, instead of failing every album.
	 * </p>
	 */
	public static String unavailability() {
		if (_detector != null) {
			// Somebody brought their own; the machine is not asked at all.
			return null;
		}
		if (_checked) {
			return _unavailable;
		}
		synchronized (LOCK) {
			if (!_checked) {
				try {
					load();
					_unavailable = null;
					LOG.info("Face detection is available: OpenCV with '" + MODEL + "'.");
				} catch (Throwable ex) {
					// Every Throwable: a native library that is not there arrives as an Error.
					_unavailable = reason(ex);
					LOG.log(Level.WARNING, "Face detection is not available: " + _unavailable, ex);
				}
				_checked = true;
			}
			return _unavailable;
		}
	}

	/** The faces of the given preview image, in the pixels of that preview. */
	public static Result detect(File preview) throws IOException {
		return detect(preview, null);
	}

	/**
	 * The faces of the given preview image, in the pixels of that preview, every one of them too
	 * small on it looked up in the original, see issue #140.
	 *
	 * @param refiner
	 *        How to look at the original, <code>null</code> to stay on the preview as before.
	 */
	public static Result detect(File preview, Refiner refiner) throws IOException {
		Detector detector = _detector;
		if (detector != null) {
			return detector.detect(preview, refiner);
		}
		String unavailable = unavailability();
		if (unavailable != null) {
			throw new IOException("Face detection is not available: " + unavailable);
		}
		return detectWithOpenCv(preview, refiner);
	}

	/**
	 * Loads OpenCV and the two models.
	 *
	 * <p>
	 * Reflection, on purpose: this class is compiled and loaded on every machine, and only a
	 * machine that really switched faces on ever touches OpenCV. Nothing here is on a hot path —
	 * it happens once per process.
	 * </p>
	 */
	private static void load() throws Exception {
		org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class);
		File detector = unpack(DETECTOR_RESOURCE);
		File recogniser = unpack(RECOGNISER_RESOURCE);
		_yunet = org.opencv.objdetect.FaceDetectorYN.create(detector.getAbsolutePath(), "",
			new org.opencv.core.Size(320, 320), SCORE_THRESHOLD, NMS_THRESHOLD, TOP_K);
		_sface = org.opencv.objdetect.FaceRecognizerSF.create(recogniser.getAbsolutePath(), "");
	}

	/**
	 * Runs the detector and the recogniser over one image.
	 *
	 * <p>
	 * Under one lock: the two OpenCV objects carry state (the detector its input size) and the
	 * index hands them one image at a time anyway.
	 * </p>
	 */
	private static Result detectWithOpenCv(File preview, Refiner refiner) throws IOException {
		synchronized (LOCK) {
			org.opencv.core.Mat image = org.opencv.imgcodecs.Imgcodecs.imread(preview.getAbsolutePath());
			if (image == null || image.empty()) {
				throw new IOException("Cannot read the preview '" + preview.getAbsolutePath() + "'.");
			}
			try {
				return detect(image, refiner);
			} finally {
				image.release();
			}
		}
	}

	private static Result detect(org.opencv.core.Mat image, Refiner refiner) throws IOException {
		org.opencv.objdetect.FaceDetectorYN yunet = (org.opencv.objdetect.FaceDetectorYN) _yunet;
		org.opencv.objdetect.FaceRecognizerSF sface = (org.opencv.objdetect.FaceRecognizerSF) _sface;

		org.opencv.core.Mat scaled = image;
		double scale = 1.0;
		int longSide = Math.max(image.width(), image.height());
		if (longSide > MAX_INPUT) {
			scale = ((double) MAX_INPUT) / longSide;
			scaled = new org.opencv.core.Mat();
			org.opencv.imgproc.Imgproc.resize(image, scaled, new org.opencv.core.Size(
				Math.max(1, (int) Math.round(image.width() * scale)),
				Math.max(1, (int) Math.round(image.height() * scale))));
		}
		try {
			yunet.setInputSize(new org.opencv.core.Size(scaled.width(), scaled.height()));
			org.opencv.core.Mat found = new org.opencv.core.Mat();
			try {
				yunet.detect(scaled, found);
				List<Detected> faces = new ArrayList<>();
				for (int row = 0; row < found.rows(); row++) {
					float[] values = new float[found.cols()];
					found.get(row, 0, values);
					double w = values[2];
					double h = values[3];
					double side = Math.min(w, h) / scale;
					if (side < MIN_FACE_PIXELS_REFINABLE) {
						// Too small on the preview for even the original to be asked about.
						continue;
					}
					double score = values[found.cols() - 1];
					// Back into the pixels of the preview itself, whatever the network was fed.
					Detected candidate =
						new Detected(values[0] / scale, values[1] / scale, w / scale, h / scale, score, null);
					Refined better = null;
					boolean looked = false;
					if (refiner != null && side < REFINE_PIXELS) {
						try {
							better = refiner.refine(candidate);
							// Looked at the original: whether it found a better face or nothing at
							// all, there is nothing more this build can say about this one.
							looked = true;
						} catch (IOException | RuntimeException ex) {
							// The original could not be read; the preview's word stands and a later
							// pass asks again.
							LOG.log(Level.INFO, "Cannot look at the original of '" + preview(image)
								+ "': " + reason(ex));
						}
					}
					if (better != null) {
						faces.add(new Detected(better.getX(), better.getY(), better.getW(), better.getH(),
							score, better.getEmbedding(), true));
						continue;
					}
					if (side < MIN_FACE_PIXELS) {
						// Small, and the original did not confirm it: no face, see
						// MIN_FACE_PIXELS_REFINABLE.
						continue;
					}
					float[] embedding = embed(sface, scaled, found.row(row));
					faces.add(new Detected(candidate.getX(), candidate.getY(), candidate.getW(),
						candidate.getH(), score, embedding, looked || side >= REFINE_PIXELS));
				}
				return new Result(image.width(), image.height(), faces);
			} finally {
				found.release();
			}
		} catch (RuntimeException ex) {
			throw new IOException("Cannot detect faces: " + ex.getMessage(), ex);
		} finally {
			if (scaled != image) {
				scaled.release();
			}
		}
	}

	/**
	 * The face of the given region of an original that best meets the given box, see issue #140.
	 *
	 * <p>
	 * The second pass: the region was cut around a face the preview reported, so the detector is
	 * asked the same question again on pixels ten times as many, and the answer that overlaps the
	 * box the preview named by more than {@link #REFINE_IOU} is that face at full resolution — box,
	 * landmarks and, through the landmarks, an embedding computed from an aligned crop that no
	 * longer has to be scaled up. Anything else in the region is somebody else and is left to the
	 * pass over their own preview.
	 * </p>
	 *
	 * @param region
	 *        The piece of the original, see {@link Originals}.
	 * @param x
	 *        The left edge of the face the preview named, in the pixels of that region.
	 * @param y
	 *        Its top edge, likewise.
	 * @param w
	 *        Its width, likewise.
	 * @param h
	 *        Its height, likewise.
	 * @return The face as the region shows it, in the pixels of the region, or <code>null</code>
	 *         when the region holds no such face.
	 */
	public static Refined refine(java.awt.image.BufferedImage region, double x, double y, double w, double h)
			throws IOException {
		return inRegion(region, x, y, w, h, false);
	}

	/**
	 * The face somebody pointed at in the given region of an original, see issue #155.
	 *
	 * <p>
	 * The question of a hand-marked box, which is not the question of {@link #refine}: a box drawn
	 * by hand is rarely the detector's own box and a click is no box at all, only a point with a
	 * little room around it. So a face <em>containing the centre</em> of the given box is the face
	 * that was meant — of several such, the one the box overlaps most, and of equal ones the
	 * smallest, which is the face and not the head behind it — and only where no face contains
	 * the centre does a face overlapping the box by more than {@link #REFINE_IOU} count.
	 * </p>
	 *
	 * <p>
	 * Asked of the {@link Detector} a test installed, where there is one; the bundled models are
	 * never loaded for it then.
	 * </p>
	 *
	 * @param region
	 *        The piece of the original, see {@link Originals}.
	 * @param x
	 *        The left edge of the marked box, in the pixels of that region.
	 * @param y
	 *        Its top edge, likewise.
	 * @param w
	 *        Its width, likewise.
	 * @param h
	 *        Its height, likewise.
	 * @return The face, in the pixels of the region and with its embedding, or <code>null</code>
	 *         when the region holds no face there.
	 */
	public static Refined search(java.awt.image.BufferedImage region, double x, double y, double w, double h)
			throws IOException {
		Detector detector = _detector;
		if (detector != null) {
			return detector.search(region, x, y, w, h);
		}
		return inRegion(region, x, y, w, h, true);
	}

	private static Refined inRegion(java.awt.image.BufferedImage region, double x, double y, double w,
			double h, boolean aroundCentre) throws IOException {
		String unavailable = unavailability();
		if (unavailable != null) {
			throw new IOException("Face detection is not available: " + unavailable);
		}
		synchronized (LOCK) {
			org.opencv.objdetect.FaceDetectorYN yunet = (org.opencv.objdetect.FaceDetectorYN) _yunet;
			org.opencv.objdetect.FaceRecognizerSF sface = (org.opencv.objdetect.FaceRecognizerSF) _sface;
			org.opencv.core.Mat image = toMat(region);
			org.opencv.core.Mat scaled = image;
			try {
				double scale = 1.0;
				int longSide = Math.max(image.width(), image.height());
				if (longSide > MAX_INPUT) {
					scale = ((double) MAX_INPUT) / longSide;
					scaled = new org.opencv.core.Mat();
					org.opencv.imgproc.Imgproc.resize(image, scaled, new org.opencv.core.Size(
						Math.max(1, (int) Math.round(image.width() * scale)),
						Math.max(1, (int) Math.round(image.height() * scale))));
				}
				yunet.setInputSize(new org.opencv.core.Size(scaled.width(), scaled.height()));
				org.opencv.core.Mat found = new org.opencv.core.Mat();
				try {
					yunet.detect(scaled, found);
					int best = -1;
					double bestOverlap = REFINE_IOU;
					float[] bestValues = null;
					double centreX = (x + w / 2) * scale;
					double centreY = (y + h / 2) * scale;
					boolean bestContains = false;
					for (int row = 0; row < found.rows(); row++) {
						float[] values = new float[found.cols()];
						found.get(row, 0, values);
						double overlap = iou(x * scale, y * scale, w * scale, h * scale,
							values[0], values[1], values[2], values[3]);
						boolean contains = aroundCentre && centreX >= values[0]
							&& centreX <= values[0] + values[2] && centreY >= values[1]
							&& centreY <= values[1] + values[3];
						boolean better;
						if (contains) {
							better = !bestContains || overlap > bestOverlap
								|| (overlap == bestOverlap && bestValues != null
									&& values[2] * values[3] < bestValues[2] * bestValues[3]);
						} else {
							better = !bestContains && overlap > bestOverlap;
						}
						if (better) {
							bestOverlap = overlap;
							best = row;
							bestValues = values;
							bestContains = contains;
						}
					}
					if (best < 0) {
						return null;
					}
					float[] embedding = embed(sface, scaled, found.row(best));
					return new Refined(bestValues[0] / scale, bestValues[1] / scale, bestValues[2] / scale,
						bestValues[3] / scale, embedding);
				} finally {
					found.release();
				}
			} catch (RuntimeException ex) {
				throw new IOException("Cannot look at the original: " + ex.getMessage(), ex);
			} finally {
				if (scaled != image) {
					scaled.release();
				}
				image.release();
			}
		}
	}

	/** How much the two boxes overlap, between zero and one. */
	static double iou(double ax, double ay, double aw, double ah, double bx, double by, double bw,
			double bh) {
		double left = Math.max(ax, bx);
		double top = Math.max(ay, by);
		double right = Math.min(ax + aw, bx + bw);
		double bottom = Math.min(ay + ah, by + bh);
		if (right <= left || bottom <= top) {
			return 0;
		}
		double intersection = (right - left) * (bottom - top);
		double union = aw * ah + bw * bh - intersection;
		return union <= 0 ? 0 : intersection / union;
	}

	/** The given raster as OpenCV wants it: three bytes per pixel, blue first. */
	private static org.opencv.core.Mat toMat(java.awt.image.BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		byte[] data = new byte[width * height * 3];
		int[] row = new int[width];
		int offset = 0;
		for (int y = 0; y < height; y++) {
			image.getRGB(0, y, width, 1, row, 0, width);
			for (int x = 0; x < width; x++) {
				int rgb = row[x];
				data[offset++] = (byte) (rgb & 0xFF);
				data[offset++] = (byte) ((rgb >> 8) & 0xFF);
				data[offset++] = (byte) ((rgb >> 16) & 0xFF);
			}
		}
		org.opencv.core.Mat result =
			new org.opencv.core.Mat(height, width, org.opencv.core.CvType.CV_8UC3);
		result.put(0, 0, data);
		return result;
	}

	/** What to call the picture in a log line; the detector only ever sees a raster. */
	private static String preview(org.opencv.core.Mat image) {
		return image.width() + "x" + image.height();
	}

	private static float[] embed(org.opencv.objdetect.FaceRecognizerSF sface, org.opencv.core.Mat image,
			org.opencv.core.Mat face) {
		org.opencv.core.Mat aligned = new org.opencv.core.Mat();
		org.opencv.core.Mat feature = new org.opencv.core.Mat();
		try {
			sface.alignCrop(image, face, aligned);
			sface.feature(aligned, feature);
			float[] result = new float[feature.cols()];
			feature.get(0, 0, result);
			return result;
		} finally {
			aligned.release();
			feature.release();
			face.release();
		}
	}

	/**
	 * Unpacks a bundled model into the temporary file space, once per stamp.
	 *
	 * <p>
	 * Never into the library: the one thing this server writes beside photographs is an album's
	 * own {@value de.haumacher.imageServer.PreviewCache#CACHE_DIRECTORY_NAME}.
	 * </p>
	 */
	static File unpack(String resource) throws IOException {
		Path directory = Path.of(System.getProperty("java.io.tmpdir"), "valbum-models",
			MODEL.replace('/', '-').replace('+', '-'));
		Files.createDirectories(directory);
		Path target = directory.resolve(resource);
		try (InputStream in = FaceDetection.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException("The model '" + resource + "' is not bundled with this build.");
			}
			if (Files.isRegularFile(target)) {
				// Unpacked before, by this process or an earlier one; the stamp is in the path.
				return target.toFile();
			}
			Path tmp = Files.createTempFile(directory, resource, ".tmp");
			try {
				try (OutputStream out = Files.newOutputStream(tmp)) {
					in.transferTo(out);
				}
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(tmp);
			}
		}
		return target.toFile();
	}

	/** What to tell about the given failure, never empty. */
	static String reason(Throwable ex) {
		String message = ex.getMessage();
		if (message == null || message.isEmpty()) {
			message = ex.getClass().getName();
		} else {
			message = ex.getClass().getSimpleName() + ": " + message;
		}
		Throwable cause = ex.getCause();
		if (cause != null && cause != ex) {
			message = message + " (" + reason(cause) + ")";
		}
		return message;
	}
}
