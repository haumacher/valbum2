/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.util.servlet.Util;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;

/**
 * The transcoded sidecars of a video: a file that plays at once, and a short teaser, see issue #74.
 *
 * <p>
 * The original of a phone video is 4K at 50-100 Mbit/s with its index at the end of a file of a
 * few hundred megabytes. Range requests make it playable, but every start pays two round trips for
 * the index and the bitrate saturates a home network, so the server plays a rendition and keeps the
 * original for the download — exactly what a photo's thumbnail does for its original.
 * </p>
 *
 * <p>
 * The renditions live beside the poster frame in the album's {@value PreviewCache#CACHE_DIRECTORY_NAME}
 * and the original is never touched. They are made by the FFmpeg <em>program</em> the bundled
 * <code>org.bytedeco:ffmpeg</code> artifact ships, run as a child process with an argument list —
 * never a command line a file name could be smuggled into — because transcoding frame by frame
 * through Java is far slower on the kind of machine this server runs on.
 * </p>
 *
 * <p>
 * A transcode takes minutes, so no request ever waits for one: a rendition that is not there yet is
 * answered with <code>202</code> and queued on a single-thread executor of its own. One transcode
 * at a time, deliberately independent of the preview permits of issue #69: a transcode is minutes
 * of full CPU and would otherwise starve the thumbnails of a whole album.
 * </p>
 */
public class VideoRenditions {

	private static final Logger LOG = Logger.getLogger(VideoRenditions.class.getName());

	/** The extension of every rendition, whatever the original is called. */
	private static final String MP4 = "mp4";

	/** How many lines of the child's error output are kept for the log. */
	private static final int ERROR_TAIL_LINES = 20;

	/** The kinds of rendition a video has. */
	public enum Kind {

		/**
		 * The file that is played: 720 px on the short side, moderate bitrate, index in front.
		 */
		PLAYBACK("video-"),

		/**
		 * The few seconds shown while pointing at the album tile: tiny, silent, index in front.
		 */
		TEASER("teaser-");

		private final String _prefix;

		Kind(String prefix) {
			_prefix = prefix;
		}

		/** The name a rendition of this kind has, beside the original's poster frame. */
		public String fileName(String originalName) {
			return _prefix + originalName + (Util.suffix(originalName).equals(MP4) ? "" : "." + MP4);
		}

		/** The <code>type</code> parameter this kind is asked for with. */
		public String parameter() {
			return this == PLAYBACK ? "video" : "teaser";
		}
	}

	/** What a request for a rendition is answered with. */
	public enum State {

		/** The rendition is there and can be served. */
		READY,

		/** The rendition is being made, or was just queued; the caller comes back later. */
		PENDING,

		/** Making the rendition failed; coming back will not help. */
		FAILED
	}

	/** The answer of {@link VideoRenditions#lookup(File, Kind)}. */
	public static final class Rendition {

		private final State _state;

		private final File _file;

		Rendition(State state, File file) {
			_state = state;
			_file = file;
		}

		/** Whether the rendition is ready, still being made, or failed for good. */
		public State getState() {
			return _state;
		}

		/** The rendition file; only meaningful for {@link State#READY}. */
		public File getFile() {
			return _file;
		}
	}

	/** Whether the given file is a video this server makes renditions of. */
	public static boolean isVideo(File file) {
		return MP4.equals(Util.suffix(file.getName()));
	}

	private final ExecutorService _transcoder = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "video-transcoder");
		// The server must be able to exit even while a transcode is running.
		thread.setDaemon(true);
		return thread;
	});

	/** The transcodes queued or running, by the absolute path of the rendition they produce. */
	private final ConcurrentHashMap<String, Future<?>> _inFlight = new ConcurrentHashMap<>();

	/**
	 * The renditions whose transcode failed, by absolute path.
	 *
	 * <p>
	 * Remembered for the lifetime of the process: a file FFmpeg cannot read will not become
	 * readable, and without this every request would queue the same doomed transcode again and
	 * keep the machine busy with it.
	 * </p>
	 */
	private final ConcurrentHashMap<String, String> _failed = new ConcurrentHashMap<>();

	/** The child process of the transcode currently running, to kill it when the server stops. */
	private volatile Process _running;

	private volatile boolean _stopped;

	/**
	 * The rendition of the given kind for the given video, queueing the transcode if it is missing.
	 */
	public Rendition lookup(File file, Kind kind) {
		File rendition = file(file, kind);
		if (upToDate(file, rendition)) {
			return new Rendition(State.READY, rendition);
		}

		String key = rendition.getAbsolutePath();
		String failure = _failed.get(key);
		if (failure != null) {
			return new Rendition(State.FAILED, rendition);
		}
		queue(file, rendition, kind, key);
		return new Rendition(State.PENDING, rendition);
	}

	/** Where the rendition of the given kind for the given video lives. */
	public static File file(File video, Kind kind) {
		File cacheDir = new File(video.getParentFile(), PreviewCache.CACHE_DIRECTORY_NAME);
		return new File(cacheDir, kind.fileName(video.getName()));
	}

	/**
	 * Whether the rendition is there and still describes the original.
	 *
	 * <p>
	 * The same rule the poster frame follows, see {@link PreviewCache#LAST_UPDATE}.
	 * </p>
	 */
	private static boolean upToDate(File video, File rendition) {
		if (!rendition.exists()) {
			return false;
		}
		long renditionTime = rendition.lastModified();
		return video.lastModified() <= renditionTime && renditionTime >= PreviewCache.LAST_UPDATE;
	}

	private void queue(File file, File rendition, Kind kind, String key) {
		if (_stopped) {
			return;
		}
		// One queued transcode per rendition, however many requests ask for it.
		_inFlight.computeIfAbsent(key, ignored -> {
			try {
				return _transcoder.submit(() -> {
					try {
						transcode(file, rendition, kind);
					} catch (IOException | RuntimeException ex) {
						LOG.log(Level.WARNING, "Cannot create the " + kind.parameter() + " rendition of '"
							+ file.getName() + "': " + ex.getMessage(), ex);
						_failed.put(key, ex.getMessage());
					} finally {
						_inFlight.remove(key);
					}
				});
			} catch (java.util.concurrent.RejectedExecutionException ex) {
				// The server is stopping; the next start will make the rendition.
				return null;
			}
		});
	}

	/**
	 * Waits until every transcode queued so far has ended.
	 *
	 * <p>
	 * The executor has one thread and answers in order, so a task queued behind them runs only
	 * when they are done. For the tests, which must not poll a directory.
	 * </p>
	 *
	 * @return Whether the queue ran empty within the given time.
	 */
	boolean awaitQueue(long timeoutMs) throws InterruptedException {
		try {
			_transcoder.submit(() -> {
				// Nothing; being run at all is the answer.
			}).get(timeoutMs, TimeUnit.MILLISECONDS);
			return true;
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
			return false;
		} catch (java.util.concurrent.RejectedExecutionException ex) {
			return true;
		}
	}

	/** Why the rendition at the given path failed, <code>null</code> if it did not. */
	String failure(File rendition) {
		return _failed.get(rendition.getAbsolutePath());
	}

	/**
	 * Stops accepting transcodes and kills the one that is running.
	 *
	 * <p>
	 * A transcode is minutes long, so waiting for it would hold up the shutdown of the server; the
	 * rendition is a cache entry and the next start makes it again.
	 * </p>
	 */
	public void shutdown() {
		_stopped = true;
		_transcoder.shutdownNow();
		Process running = _running;
		if (running != null) {
			running.destroyForcibly();
		}
	}

	/**
	 * Runs FFmpeg to produce the rendition, writing it to a temporary name first.
	 */
	private void transcode(File file, File rendition, Kind kind) throws IOException {
		if (upToDate(file, rendition)) {
			// Queued twice before the first one finished.
			return;
		}
		File cacheDir = rendition.getParentFile();
		if (!cacheDir.exists()) {
			cacheDir.mkdirs();
		}
		File tmp = new File(cacheDir, rendition.getName() + PreviewCache.TMP_SUFFIX);
		try {
			List<String> command = command(file, tmp, kind);
			LOG.info("Transcoding the " + kind.parameter() + " rendition of '" + file.getName() + "'.");
			run(command);
			if (!tmp.exists() || tmp.length() == 0) {
				throw new IOException("FFmpeg produced no output.");
			}
			moveIntoPlace(tmp, rendition);
			LOG.info("The " + kind.parameter() + " rendition of '" + file.getName() + "' is ready: "
				+ rendition.length() + " bytes.");
		} finally {
			tmp.delete();
		}
	}

	/** Runs the given command, keeping the tail of its error output for the message. */
	private void run(List<String> command) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		_running = process;
		Deque<String> tail = new ArrayDeque<>();
		try (BufferedReader reader =
			new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				tail.addLast(line);
				if (tail.size() > ERROR_TAIL_LINES) {
					tail.removeFirst();
				}
			}
		} finally {
			_running = null;
		}
		int status;
		try {
			status = process.waitFor();
		} catch (InterruptedException ex) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while transcoding.", ex);
		}
		if (status != 0) {
			String message = "FFmpeg failed with exit code " + status;
			LOG.warning(message + ":\n" + String.join("\n", tail));
			throw new IOException(message + ".");
		}
	}

	/**
	 * The FFmpeg command line for the given rendition, as an argument list.
	 *
	 * <p>
	 * An argument list, never a string: a file name is data and must not be able to become part of
	 * a command.
	 * </p>
	 */
	List<String> command(File file, File target, Kind kind) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(executable());
		command.add("-hide_banner");
		// Never wait for a console that is not there.
		command.add("-nostdin");
		command.add("-y");
		command.add("-loglevel");
		command.add("error");
		if (kind == Kind.TEASER) {
			// Seek before the input: FFmpeg jumps to the key frame instead of decoding up to it.
			command.add("-ss");
			command.add(String.format(java.util.Locale.ROOT, "%.3f", teaserStart(file)));
		}
		command.add("-i");
		command.add(file.getAbsolutePath());
		if (kind == Kind.TEASER) {
			command.add("-t");
			command.add(String.format(java.util.Locale.ROOT, "%.3f", TEASER_SECONDS));
			command.add("-an");
		} else {
			// The audio is optional: a video recorded silently has no audio stream at all.
			command.add("-map");
			command.add("0:v:0");
			command.add("-map");
			command.add("0:a:0?");
			command.add("-c:a");
			command.add("aac");
			command.add("-b:a");
			command.add("128k");
			command.add("-ac");
			command.add("2");
		}
		command.add("-vf");
		command.add(scaleFilter(kind == Kind.TEASER ? TEASER_HEIGHT : PLAYBACK_HEIGHT));
		command.add("-c:v");
		command.add(encoder());
		command.add("-b:v");
		command.add(kind == Kind.TEASER ? "400k" : "3500k");
		// Never more than 30 frames a second; a slower original keeps its rate.
		command.add("-fpsmax");
		command.add("30");
		command.add("-pix_fmt");
		command.add("yuv420p");
		command.add("-threads");
		command.add(Integer.toString(threads()));
		// The index in front, so that playing can start with the first bytes.
		command.add("-movflags");
		command.add("+faststart");
		command.add("-f");
		command.add(MP4);
		command.add(target.getAbsolutePath());
		return command;
	}

	/** The short side of the playback rendition, at most. */
	static final int PLAYBACK_HEIGHT = 720;

	/** The short side of the teaser, at most. */
	static final int TEASER_HEIGHT = 240;

	/** How long a teaser is. */
	static final double TEASER_SECONDS = 3.0;

	/** A video shorter than this is teased from its start. */
	static final double TEASER_MIN_DURATION = 5.0;

	/** Where in the video the teaser is taken from. */
	static final double TEASER_POSITION = 0.1;

	/**
	 * Scales to at most the given number of pixels on the short side, and never up.
	 *
	 * <p>
	 * <code>-2</code> lets FFmpeg compute the other side from the aspect ratio and keeps it even,
	 * which every H.264 encoder needs.
	 * </p>
	 */
	static String scaleFilter(int shortSide) {
		String limited = "min(" + shortSide + "\\,";
		return "scale=w=if(gt(iw\\,ih)\\,-2\\," + limited + "iw)):h=if(gt(iw\\,ih)\\," + limited + "ih)\\,-2)";
	}

	/** How many threads a transcode may use: half the machine, so that the server stays answerable. */
	private static int threads() {
		return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
	}

	/** Where the teaser starts: about a tenth in, or at the beginning of a short video. */
	private double teaserStart(File file) {
		double duration = duration(file);
		if (duration < TEASER_MIN_DURATION) {
			return 0.0;
		}
		return Math.min(duration * TEASER_POSITION, Math.max(0.0, duration - TEASER_SECONDS));
	}

	/** How long the given video is, in seconds; <code>0</code> if that cannot be found out. */
	private static double duration(File file) {
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
			grabber.start();
			return grabber.getLengthInTime() / 1000000.0;
		} catch (Exception ex) {
			LOG.log(Level.FINE, "Cannot read the duration of '" + file.getName() + "'.", ex);
			return 0.0;
		}
	}

	private static void moveIntoPlace(File tmp, File target) throws IOException {
		try {
			Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException | UnsupportedOperationException ex) {
			Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static volatile String _executable;

	private static volatile String _encoder;

	/** The FFmpeg program the bundled artifact ships. */
	static String executable() throws IOException {
		String executable = _executable;
		if (executable == null) {
			try {
				executable = Loader.load(org.bytedeco.ffmpeg.ffmpeg.class);
			} catch (RuntimeException ex) {
				throw new IOException("No FFmpeg program available.", ex);
			}
			_executable = executable;
		}
		return executable;
	}

	/**
	 * The H.264 encoder to use, asked of the program itself once.
	 *
	 * <p>
	 * H.264 is what a browser plays. The bundled build is the LGPL flavour, which carries
	 * <code>libopenh264</code> but not <code>libx264</code>; the better one is taken where it is
	 * there.
	 * </p>
	 */
	static String encoder() throws IOException {
		String encoder = _encoder;
		if (encoder == null) {
			encoder = findEncoder();
			_encoder = encoder;
		}
		return encoder;
	}

	private static String findEncoder() throws IOException {
		List<String> available = new ArrayList<>();
		ProcessBuilder builder = new ProcessBuilder(executable(), "-hide_banner", "-encoders");
		builder.redirectErrorStream(true);
		Process process = builder.start();
		try (InputStream in = process.getInputStream();
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.contains("264")) {
					available.add(line);
				}
			}
		}
		try {
			process.waitFor();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while asking FFmpeg for its encoders.", ex);
		}
		for (String candidate : new String[] { "libx264", "libopenh264" }) {
			for (String line : available) {
				if (line.contains(" " + candidate + " ")) {
					LOG.info("Transcoding videos with '" + candidate + "'.");
					return candidate;
				}
			}
		}
		throw new IOException("No H.264 encoder in the bundled FFmpeg; videos cannot be transcoded.");
	}

}
