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

		/** Creates a {@link Detected} face, in the pixels of the preview it was found on. */
		public Detected(double x, double y, double w, double h, double score, float[] embedding) {
			_x = x;
			_y = y;
			_w = w;
			_h = h;
			_score = score;
			_embedding = embedding;
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

	/** What one photograph is handed to, so that a test can count calls without a model. */
	public interface Detector {

		/** The faces of the given preview image, in the pixels of that preview. */
		Result detect(File preview) throws IOException;
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
		Detector detector = _detector;
		if (detector != null) {
			return detector.detect(preview);
		}
		String unavailable = unavailability();
		if (unavailable != null) {
			throw new IOException("Face detection is not available: " + unavailable);
		}
		return detectWithOpenCv(preview);
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
	private static Result detectWithOpenCv(File preview) throws IOException {
		synchronized (LOCK) {
			org.opencv.core.Mat image = org.opencv.imgcodecs.Imgcodecs.imread(preview.getAbsolutePath());
			if (image == null || image.empty()) {
				throw new IOException("Cannot read the preview '" + preview.getAbsolutePath() + "'.");
			}
			try {
				return detect(image);
			} finally {
				image.release();
			}
		}
	}

	private static Result detect(org.opencv.core.Mat image) throws IOException {
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
					if (Math.min(w, h) / scale < MIN_FACE_PIXELS) {
						// Too small on the preview to say anything about; see MIN_FACE_PIXELS.
						continue;
					}
					float[] embedding = embed(sface, scaled, found.row(row));
					// Back into the pixels of the preview itself, whatever the network was fed.
					faces.add(new Detected(values[0] / scale, values[1] / scale, w / scale, h / scale,
						values[found.cols() - 1], embedding));
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
