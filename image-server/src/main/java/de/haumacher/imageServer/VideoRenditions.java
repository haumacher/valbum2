/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.VideoProbe;
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
import java.util.Map;
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

	/** The environment variable naming the directories the dynamic loader searches. */
	static final String LIBRARY_PATH = "LD_LIBRARY_PATH";

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

		/** The name every rendition of this kind begins with. */
		public String prefix() {
			return _prefix;
		}

		/** The extension every rendition carries, whatever the original is called. */
		public static String extension() {
			return MP4;
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
		FAILED,

		/**
		 * This server cannot make renditions at all, see {@link VideoRenditions#unavailability()}.
		 */
		UNAVAILABLE,

		/**
		 * A transcode of the background was put aside for a request, see
		 * {@link VideoRenditions#make(File, Kind)}: nothing of it is kept, and it is made again
		 * later.
		 */
		DEFERRED
	}

	/** The answer of {@link VideoRenditions#lookup(File, Kind)}. */
	public static final class Rendition {

		private final State _state;

		private final File _file;

		private final String _reason;

		Rendition(State state, File file) {
			this(state, file, null);
		}

		Rendition(State state, File file, String reason) {
			_state = state;
			_file = file;
			_reason = reason;
		}

		/** Why the rendition is not there; <code>null</code> when nothing went wrong. */
		public String getReason() {
			return _reason;
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
		return PreviewCache.isVideoName(file.getName());
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

		String unavailable = unavailability();
		if (unavailable != null) {
			// Nothing is queued that cannot be done; the caller is told so at once.
			return new Rendition(State.UNAVAILABLE, rendition, unavailable);
		}

		String key = rendition.getAbsolutePath();
		String failure = _failed.get(key);
		if (failure != null) {
			return new Rendition(State.FAILED, rendition, failure);
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
	 * The very rule of the poster frame, see {@link PreviewCache#upToDate(File, File)} and issue
	 * #235: fresh exactly while the original has the size and the modification stamp recorded
	 * beside the rendition when it was made, so that a rendition made of a half-copied file is made
	 * again after <code>cp -p</code> restored the original's older stamp (issue #236). A rendition
	 * made before records were kept is judged by the old rule (the original not newer than it), and
	 * nothing is transcoded again for an upgrade.
	 * </p>
	 */
	static boolean upToDate(File video, File rendition) {
		return PreviewCache.upToDate(video, rendition);
	}

	/**
	 * Makes the rendition of the given kind for the given video now, in the calling thread: the
	 * background work of issue #236.
	 *
	 * <h3>Requests first</h3>
	 *
	 * <p>
	 * There is one transcode at a time, and a request for a rendition always goes first. A
	 * background transcode starts only while no request's transcode is queued or running, and a
	 * request whose rendition is not there yet <em>preempts</em> the background transcode that is
	 * running: its FFmpeg is killed, its temporary output deleted (nothing half-made is ever taken for
	 * a rendition), and this method answers {@link State#DEFERRED} at once, so that the caller puts the
	 * unit back and makes it again, from the start, later. Where the request asks for the very
	 * rendition the background is making, nothing is killed: the request waits for it, and it is
	 * never preempted any more. A request's transcode is preempted by nothing: a further request
	 * queues behind it, as it always did.
	 * </p>
	 *
	 * <p>
	 * As long as requests for renditions keep coming, a background transcode is put aside again and
	 * again: it waits until they stop, exactly as the photographs of the background wait for requests.
	 * </p>
	 *
	 * @return {@link State#READY}, or why there is none: {@link State#FAILED} (remembered, as a
	 *         request's failure is), {@link State#UNAVAILABLE}, {@link State#DEFERRED} (a request
	 *         came first), or {@link State#PENDING} when the server stops.
	 */
	public Rendition make(File file, Kind kind) throws InterruptedException {
		File rendition = file(file, kind);
		if (upToDate(file, rendition)) {
			return new Rendition(State.READY, rendition);
		}
		String unavailable = unavailability();
		if (unavailable != null) {
			return new Rendition(State.UNAVAILABLE, rendition, unavailable);
		}
		String key = rendition.getAbsolutePath();
		String failure = _failed.get(key);
		if (failure != null) {
			return new Rendition(State.FAILED, rendition, failure);
		}
		BackgroundJob mine;
		synchronized (_slot) {
			if (_stopped) {
				return new Rendition(State.PENDING, rendition);
			}
			if (_busy || _requests > 0) {
				// A request's transcode is queued or running: it goes first, and this comes back.
				return new Rendition(State.DEFERRED, rendition);
			}
			_busy = true;
			mine = new BackgroundJob(key);
			_background = mine;
		}
		try {
			transcode(file, rendition, kind, mine);
			return upToDate(file, rendition) ? new Rendition(State.READY, rendition)
				: new Rendition(State.PENDING, rendition);
		} catch (Throwable ex) {
			if (mine._preempted) {
				LOG.info("Put the background " + kind.parameter() + " rendition of '" + file.getName()
					+ "' aside for a request; it is made again later.");
				return new Rendition(State.DEFERRED, rendition);
			}
			if (_stopped || Thread.currentThread().isInterrupted()) {
				return new Rendition(State.PENDING, rendition);
			}
			LOG.log(Level.WARNING, "Cannot create the " + kind.parameter() + " rendition of '"
				+ file.getName() + "': " + reason(ex), ex);
			_failed.put(key, reason(ex));
			return new Rendition(State.FAILED, rendition, reason(ex));
		} finally {
			synchronized (_slot) {
				_background = null;
				_busy = false;
				_slot.notifyAll();
			}
		}
	}

	/** The background transcode holding the transcoder, see {@link #make(File, Kind)}. */
	private static final class BackgroundJob {

		final String _key;

		/** Whether a request may not preempt it: it asked for this very rendition. */
		boolean _promoted;

		volatile boolean _preempted;

		/** Its FFmpeg, <code>null</code> before it started; guarded by {@link VideoRenditions#_slot}. */
		Process _process;

		BackgroundJob(String key) {
			_key = key;
		}
	}

	/** Guards the one transcode at a time: {@link #_busy}, {@link #_requests}, {@link #_background}. */
	private final Object _slot = new Object();

	/** Whether a transcode holds the slot. */
	private boolean _busy;

	/** How many request transcodes are queued or running. */
	private int _requests;

	/** The background transcode holding the slot, <code>null</code> for none. */
	private BackgroundJob _background;

	/**
	 * Takes the slot for a request's transcode of the given rendition, preempting a background
	 * transcode of another one, waiting for one of the same, see {@link #make(File, Kind)}.
	 */
	private void acquireForRequest(String key) throws InterruptedException {
		synchronized (_slot) {
			while (_busy) {
				BackgroundJob background = _background;
				if (background != null) {
					if (background._key.equals(key)) {
						background._promoted = true;
					} else if (!background._promoted && !background._preempted) {
						background._preempted = true;
						if (background._process != null) {
							kill(background._process);
						}
					}
				}
				_slot.wait();
			}
			_busy = true;
		}
	}

	/**
	 * Kills the given transcode with whatever it started, so that nothing holds its output open and
	 * the thread reading it is free at once.
	 */
	private static void kill(Process process) {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
	}

	private void releaseForRequest() {
		synchronized (_slot) {
			_busy = false;
			_requests--;
			_slot.notifyAll();
		}
	}

	/** Queues the transcode unless it is queued; the task in flight, <code>null</code> when stopping. */
	private Future<?> queue(File file, File rendition, Kind kind, String key) {
		if (_stopped) {
			return null;
		}
		// One queued transcode per rendition, however many requests ask for it.
		return _inFlight.computeIfAbsent(key, ignored -> {
			synchronized (_slot) {
				// Counted from the moment it is queued: no background transcode starts meanwhile.
				_requests++;
			}
			try {
				return _transcoder.submit(() -> {
					boolean acquired = false;
					try {
						acquireForRequest(key);
						acquired = true;
						transcode(file, rendition, kind, null);
					} catch (InterruptedException ex) {
						// The server is stopping; the next start makes the rendition.
						Thread.currentThread().interrupt();
					} catch (Throwable ex) {
						// Every Throwable, an Error included: a native library that does not load
						// throws an UnsatisfiedLinkError, and an uncaught one would leave the
						// request answered with 202 for ever — the silent failure the 202 protocol
						// must never produce, see issue #81.
						LOG.log(Level.WARNING, "Cannot create the " + kind.parameter() + " rendition of '"
							+ file.getName() + "': " + reason(ex), ex);
						_failed.put(key, reason(ex));
					} finally {
						if (acquired) {
							releaseForRequest();
						} else {
							synchronized (_slot) {
								_requests--;
								_slot.notifyAll();
							}
						}
						_inFlight.remove(key);
					}
				});
			} catch (java.util.concurrent.RejectedExecutionException ex) {
				// The server is stopping; the next start will make the rendition.
				synchronized (_slot) {
					_requests--;
				}
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

	/** How many transcodes are queued or running. For the tests. */
	int queued() {
		return _inFlight.size();
	}

	/** Why the rendition at the given path failed, <code>null</code> if it did not. */
	String failure(File rendition) {
		return _failed.get(rendition.getAbsolutePath());
	}

	/** Remembers a failed transcode without running one. For the tests. */
	void remember(File rendition, String reason) {
		_failed.put(rendition.getAbsolutePath(), reason);
	}

	/**
	 * Forgets that a rendition of the given folder ever failed, see issue #98.
	 *
	 * <p>
	 * A failure is remembered for the lifetime of the process, so that a file FFmpeg cannot read
	 * does not keep the machine busy. That memory is the very thing an administrator refreshing
	 * the folder's cache wants undone: the rendition is thrown away to be made again, and a
	 * remembered failure would answer <code>500</code> for ever instead of trying. Only this
	 * folder's renditions are forgotten — every other folder keeps its memory.
	 * </p>
	 *
	 * @param cacheDir
	 *        The folder's {@value PreviewCache#CACHE_DIRECTORY_NAME} directory, the parent of
	 *        every rendition of that folder; it need not exist.
	 * @return How many remembered failures were dropped.
	 */
	public int forget(File cacheDir) {
		String prefix = cacheDir.getAbsolutePath() + File.separator;
		int forgotten = 0;
		for (String key : new ArrayList<>(_failed.keySet())) {
			// The renditions of this folder and of no other: a path below the folder's cache
			// directory, with nothing further below it.
			if (key.startsWith(prefix) && key.indexOf(File.separatorChar, prefix.length()) < 0
				&& _failed.remove(key) != null) {
				forgotten++;
			}
		}
		return forgotten;
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
		synchronized (_slot) {
			_slot.notifyAll();
		}
		_transcoder.shutdownNow();
		Process running = _running;
		if (running != null) {
			kill(running);
		}
	}

	/**
	 * Runs FFmpeg to produce the rendition, writing it to a temporary name first.
	 */
	private void transcode(File file, File rendition, Kind kind, BackgroundJob background) throws IOException {
		if (upToDate(file, rendition)) {
			// Queued twice before the first one finished.
			return;
		}
		File cacheDir = rendition.getParentFile();
		if (!cacheDir.exists()) {
			cacheDir.mkdirs();
		}
		File tmp = new File(cacheDir, rendition.getName() + PreviewCache.TMP_SUFFIX);
		// The original as it is before it is read: if it changes while it is read (a copy still
		// going on), the record no longer matches and the rendition is made again, see upToDate.
		String source = PreviewCache.record(file);
		File record = PreviewCache.recordOf(rendition);
		try {
			List<String> command = command(file, tmp, kind);
			LOG.info("Transcoding the " + kind.parameter() + " rendition of '" + file.getName() + "'"
				+ (background == null ? "." : " in the background."));
			run(command, background);
			if (background != null && background._preempted) {
				// Killed for a request: whatever FFmpeg left is no rendition (deleted below).
				throw new IOException("Put aside for a request.");
			}
			if (!tmp.exists() || tmp.length() == 0) {
				throw new IOException("FFmpeg produced no output.");
			}
			// No moment with the new rendition and the old record: the old one goes first.
			Files.deleteIfExists(record.toPath());
			moveIntoPlace(tmp, rendition);
			try {
				Files.write(record.toPath(), source.getBytes(StandardCharsets.UTF_8));
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Cannot record the original of '" + rendition + "': " + ex.getMessage());
			}
			LOG.info("The " + kind.parameter() + " rendition of '" + file.getName() + "' is ready: "
				+ rendition.length() + " bytes.");
		} finally {
			tmp.delete();
		}
	}

	/** Runs the given command, keeping the tail of its error output for the message. */
	private void run(List<String> command, BackgroundJob background) throws IOException {
		ProcessBuilder builder = program(command);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		_running = process;
		if (background != null) {
			synchronized (_slot) {
				background._process = process;
				if (background._preempted) {
					// Preempted while it was being started.
					kill(process);
				}
			}
		}
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
			// The last line FFmpeg wrote is what it says went wrong; it travels to the app in the
			// ErrorInfo of the refusal (issue #184), so that "cannot be prepared" comes with a why.
			String last = tail.isEmpty() ? "" : tail.peekLast().trim();
			throw new IOException(last.isEmpty() ? message + "." : message + ": " + last);
		}
	}

	/**
	 * The {@link ProcessBuilder} for running the bundled FFmpeg program, with the library path the
	 * child process needs.
	 *
	 * <p>
	 * The bundled program and its libraries are extracted side by side into the JavaCPP cache
	 * directory, and the program finds them through a run path of <code>$ORIGIN/</code>. Which
	 * flavour of run path that is decides whether the child process starts at all: the
	 * <code>linux-x86_64</code> program carries <code>DT_RPATH</code>, which the loader applies
	 * transitively, but the <code>linux-arm64</code> and <code>linux-armhf</code> programs carry
	 * <code>DT_RUNPATH</code>, which the loader applies to the program's own direct dependencies
	 * only. So on ARM the indirect ones — <code>libasound.so.2</code> behind
	 * <code>libavdevice</code>, the VideoCore libraries behind <code>libavcodec</code> — are
	 * looked for on the system path, where a plain Debian machine does not have them, although the
	 * very same files sit beside the program. In the JVM itself that never shows, because the
	 * JavaCPP presets preload those libraries in process; it shows only here, where FFmpeg runs as
	 * a child process (issue #87).
	 * </p>
	 *
	 * <p>
	 * Therefore the child is given <code>LD_LIBRARY_PATH</code> pointing at the directory the
	 * program was extracted to, in front of whatever the environment already said. Every library a
	 * platform artifact ships then reaches the child on every architecture, whatever its run path
	 * says, and only libraries that no artifact ships have to come from the system (the Debian
	 * package recommends those, see <code>src/deb/control/control</code>). Do not "clean this up":
	 * without it the ARM packages cannot transcode at all.
	 * </p>
	 *
	 * <p>
	 * And the libraries of that directory are made reachable by the names the program asks for
	 * ({@link #linkSonames(File)}): since the presets 1.5.9 (issue #210) the <code>linux-x86_64</code>
	 * artifact ships VA-API as <code>libva.so</code> and <code>libva-drm.so</code>, while
	 * <code>libavutil</code> links <code>libva.so.2</code> and <code>libva-drm.so.2</code>. In the
	 * JVM that never shows, because the dynamic loader matches a library preloaded in process by its
	 * soname; a child process looks for a <em>file</em> of that name, finds none beside the
	 * program and none on a headless system, and does not start.
	 * </p>
	 *
	 * @param command
	 *        The command line, the program itself first.
	 */
	public static ProcessBuilder program(List<String> command) {
		ProcessBuilder builder = new ProcessBuilder(command);
		File directory = new File(command.get(0)).getParentFile();
		// A bare program name (found on the PATH) has no directory of its own: the working
		// directory is not where its libraries are and must never be put on the loader's path.
		if (directory != null) {
			linkSonames(directory);
			Map<String, String> environment = builder.environment();
			environment.put(LIBRARY_PATH,
				libraryPath(directory.getAbsolutePath(), environment.get(LIBRARY_PATH)));
		}
		return builder;
	}

	/** The directories {@link #linkSonames(File)} has looked at, by absolute path. */
	private static final Map<String, Boolean> LINKED = new ConcurrentHashMap<>();

	/**
	 * Makes every library of the given directory that is stored under another name than its soname
	 * reachable by its soname as well, through a symbolic link beside it, see
	 * {@link #program(List)}.
	 *
	 * <p>
	 * Only an unversioned <code>lib*.so</code> that is a file of its own is looked at (what JavaCPP
	 * links itself, <code>libavcodec.so</code> &rarr; <code>libavcodec.so.60</code>, is a link
	 * already), and only where no file of the soname's name is there; nothing is ever replaced.
	 * Once per directory and process. A directory that cannot be written leaves the child to the
	 * system's copy, with one line in the log.
	 * </p>
	 *
	 * @return The links made, for the tests.
	 */
	static List<String> linkSonames(File directory) {
		List<String> made = new ArrayList<>();
		if (LINKED.putIfAbsent(directory.getAbsolutePath(), Boolean.TRUE) != null) {
			return made;
		}
		File[] files = directory.listFiles();
		if (files == null) {
			return made;
		}
		for (File file : files) {
			String name = file.getName();
			if (!name.startsWith("lib") || !name.endsWith(".so") || !Files.isRegularFile(file.toPath(),
				java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			try {
				byte[] content = Files.readAllBytes(file.toPath());
				if (!Elf.isElf(content)) {
					continue;
				}
				String soname = Elf.soname(content);
				if (soname == null || soname.equals(name) || soname.indexOf('/') >= 0) {
					continue;
				}
				java.nio.file.Path link = directory.toPath().resolve(soname);
				if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
					continue;
				}
				Files.createSymbolicLink(link, java.nio.file.Paths.get(name));
				made.add(soname);
			} catch (java.nio.file.FileAlreadyExistsException ex) {
				// Another process sharing the cache made it in the meantime.
			} catch (IOException | UnsupportedOperationException ex) {
				LOG.log(Level.WARNING, "Cannot make the bundled '" + name + "' reachable by its soname in '"
					+ directory + "'; the FFmpeg program needs the system's copy: " + ex.getMessage(), ex);
			}
		}
		return made;
	}

	/**
	 * The library path for the child process: the directory the program was extracted to first, what
	 * the environment already said behind it.
	 *
	 * @param directory
	 *        Where the bundled program and its libraries are.
	 * @param inherited
	 *        What the environment says, may be <code>null</code> or empty.
	 */
	static String libraryPath(String directory, String inherited) {
		if (inherited == null || inherited.isEmpty()) {
			return directory;
		}
		return directory + File.pathSeparator + inherited;
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
		Commands commands = _commands;
		if (commands != null) {
			return commands.command(file, target, kind);
		}
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
		command.add(videoFilter(file, kind == Kind.TEASER ? TEASER_HEIGHT : PLAYBACK_HEIGHT));
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
		command.addAll(slices(encoder(), threads()));
		// The index in front, so that playing can start with the first bytes.
		command.add("-movflags");
		command.add("+faststart");
		command.add("-f");
		command.add(MP4);
		command.add(target.getAbsolutePath());
		return command;
	}

	/** Another program in place of FFmpeg, for the tests of the preemption; see {@link #setCommands}. */
	interface Commands {

		/** The command line writing the given rendition of the given video to the given file. */
		List<String> command(File file, File target, Kind kind) throws IOException;
	}

	private volatile Commands _commands;

	/** Runs the given program in place of FFmpeg, <code>null</code> for FFmpeg; tests only. */
	void setCommands(Commands commands) {
		_commands = commands;
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

	/**
	 * The filter chain of a rendition: deinterlacing where the stream is interlaced, then the
	 * scaling of {@link #scaleFilter(int)}.
	 *
	 * <p>
	 * An AVCHD camcorder records 1080i, two fields per frame half a frame time apart, which a
	 * browser plays as combs on everything that moves (issue #192). Where the stream says it is
	 * interlaced ({@link VideoProbe#isInterlaced()}, any container) it is deinterlaced with
	 * <code>yadif</code> at one frame per frame; <code>deint=interlaced</code> leaves a frame alone
	 * that is flagged progressive, so a stream switching between both is not softened where it
	 * need not be. A stream that says it is progressive, or a file the probe cannot open, is
	 * scaled only.
	 * </p>
	 */
	static String videoFilter(File file, int shortSide) {
		return (interlaced(file) ? DEINTERLACE + "," : "") + scaleFilter(shortSide);
	}

	/** The deinterlacing filter of {@link #videoFilter(File, int)}. */
	static final String DEINTERLACE = "yadif=deint=interlaced";

	private static boolean interlaced(File file) {
		try {
			return VideoProbe.probe(file).isInterlaced();
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.FINE, "Cannot probe '" + file.getName() + "' for interlacing.", ex);
			return false;
		}
	}

	/**
	 * The slices the encoder is asked for, so that it can use the threads it is given.
	 *
	 * <p>
	 * OpenH264 encodes slices in parallel and nothing in parallel within one. The FFmpeg 5.1 of the
	 * presets 1.5.8 left the number of slices to OpenH264, which chose them by its threads; the
	 * FFmpeg 6.0 of the presets 1.5.9 (issue #210) asks for one slice unless told otherwise, and a
	 * 1080p rendition took half as long again (20 s of 1080p: 3.3 s before, 4.8 s on one slice).
	 * Never more than {@link #MAX_SLICES}: more slices only cost here (with eight threads, four
	 * slices encoded fastest and eight slower than two). <code>libx264</code> threads by frames and
	 * is left alone.
	 * </p>
	 */
	static List<String> slices(String encoder, int threads) {
		int slices = Math.min(threads, MAX_SLICES);
		if ("libopenh264".equals(encoder) && slices > 1) {
			return List.of("-slices", Integer.toString(slices));
		}
		return List.of();
	}

	/** The most slices OpenH264 is asked for, see {@link #slices(String, int)}. */
	static final int MAX_SLICES = 4;

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

	/**
	 * How the FFmpeg program is found.
	 *
	 * <p>
	 * A seam, so that a test can make the program unloadable without a machine that has no FFmpeg.
	 * </p>
	 */
	interface ProgramLocator {

		/** The path of the FFmpeg program, or a throw saying why there is none. */
		String locate() throws Exception;
	}

	private static final ProgramLocator BUNDLED = () -> Loader.load(org.bytedeco.ffmpeg.ffmpeg.class);

	private static volatile ProgramLocator _locator = BUNDLED;

	private static volatile String _encoder;

	/** Why renditions cannot be made here, <code>null</code> when they can. */
	private static volatile String _unavailable;

	private static volatile boolean _checked;

	private static final Object AVAILABILITY_LOCK = new Object();

	/**
	 * Replaces how the FFmpeg program is found and forgets what was found before. Tests only.
	 *
	 * @param locator
	 *        <code>null</code> to go back to the program the bundled artifact ships.
	 */
	static void setProgramLocator(ProgramLocator locator) {
		synchronized (AVAILABILITY_LOCK) {
			_locator = locator == null ? BUNDLED : locator;
			_encoder = null;
			_unavailable = null;
			_checked = false;
		}
	}

	/**
	 * Whether this server can transcode at all.
	 *
	 * <p>
	 * Asked once and remembered: the program is either there with an H.264 encoder or it is not,
	 * and a machine does not grow one while the server runs. A server that cannot transcode says
	 * so on every rendition request instead of queueing work that cannot be done — on a runner
	 * without ALSA and X11 the native libraries of the bundled FFmpeg do not load at all, which
	 * used to surface as a request answered <code>202</code> for ever (issue #81).
	 * </p>
	 *
	 * @return The reason renditions are not available, <code>null</code> when they are.
	 */
	public static String unavailability() {
		if (_checked) {
			return _unavailable;
		}
		synchronized (AVAILABILITY_LOCK) {
			if (!_checked) {
				try {
					String executable = executable();
					String encoder = encoder();
					LOG.info("Video renditions are available: '" + executable + "' with '" + encoder + "'.");
					_unavailable = null;
				} catch (Throwable ex) {
					// Every Throwable: a missing native library arrives as an Error.
					String reason = reason(ex);
					LOG.log(Level.WARNING, "Video renditions are not available: " + reason, ex);
					_unavailable = reason;
				}
				_checked = true;
			}
			return _unavailable;
		}
	}

	/** The encoder in use, for the start-up message; <code>null</code> if there is none. */
	public static String encoderName() {
		return _encoder;
	}

	/** What to tell about the given failure, never empty. */
	private static String reason(Throwable ex) {
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

	/**
	 * The FFmpeg program the bundled artifact ships.
	 *
	 * <p>
	 * Asked of the locator every time rather than remembered: it is a map lookup after the first
	 * call, and a test that makes the program fail later must be able to.
	 * </p>
	 */
	public static String executable() throws IOException {
		try {
			String executable = _locator.locate();
			if (executable == null) {
				throw new IOException("No FFmpeg program available.");
			}
			return executable;
		} catch (IOException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IOException("No FFmpeg program available.", ex);
		}
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
		ProcessBuilder builder = program(List.of(executable(), "-hide_banner", "-encoders"));
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
