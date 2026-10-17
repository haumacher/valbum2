/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@link Embedder} that runs its own service, see issue #257: the server starts the Python
 * embedder service as a child process when content search is first asked for, and talks to it with
 * a {@link HttpEmbedder}.
 *
 * <h2>Why a child process</h2>
 *
 * <p>
 * One way for every installation: the Debian package (no second systemd unit, nothing running for a
 * library that never asks), the container (one process tree under the entry point, no second
 * container to keep in step) and a plain <code>java -jar</code> during development. The child
 * inherits the server's user and its sandbox, listens on <code>127.0.0.1</code> only, runs at a
 * lower priority ({@link PythonService#NICE}), ends by itself after a while without work (giving
 * its 1&ndash;2 GB back) and when the server ends (its stdin closes), and is started again on the
 * next request.
 * </p>
 *
 * <h2>States</h2>
 *
 * <ul>
 * <li><b>idle</b>: nothing started. Every call starts the preparation and fails with
 * {@link EmbedderUnavailableException} saying so.</li>
 * <li><b>preparing</b>: the {@link Launcher} makes the service ready (its environment, the model's
 * download); the message says what it is doing.</li>
 * <li><b>starting</b>: the process runs and loads the model.</li>
 * <li><b>ready</b>: calls go to the service.</li>
 * <li><b>failed</b>: the preparation or the process failed; the reason is kept, and nothing is
 * tried again before a back-off (doubling from a minute to an hour) has passed.</li>
 * </ul>
 */
public final class ManagedEmbedder implements Embedder {

	private static final Logger LOG = Logger.getLogger(ManagedEmbedder.class.getName());

	/** What makes the service ready to run. */
	public interface Launcher extends Closeable {

		/**
		 * Makes everything ready (may take long: a download) and answers the command that runs the
		 * service. The service prints <code>{"listening": &lt;port&gt;}</code> as its first line and
		 * ends when its stdin closes.
		 *
		 * @param progress
		 *        Told what is being done, for {@link Embedder#unavailability()}.
		 * @throws IOException
		 *         Saying why the service cannot run.
		 */
		List<String> prepare(Consumer<String> progress) throws IOException, InterruptedException;

		/** The stamp of the vectors of the service, see {@link ContentModel#stamp(String, int)}. */
		String stamp();

		@Override
		void close();
	}

	/** How long a process may take to say its port. */
	static final Duration LISTEN_TIMEOUT = Duration.ofSeconds(60);

	private static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);

	private static final Duration MAX_BACKOFF = Duration.ofHours(1);

	private enum State {
		IDLE, PREPARING, STARTING, READY, FAILED
	}

	private final Launcher _launcher;

	private final Clock _clock;

	private final Object _lock = new Object();

	private State _state = State.IDLE;

	private String _message;

	private int _failures;

	private Instant _nextAttempt = Instant.MIN;

	private Process _process;

	private HttpEmbedder _client;

	private Thread _starter;

	private boolean _closed;

	/** Creates a {@link ManagedEmbedder} that starts nothing until it is asked for a vector. */
	public ManagedEmbedder(Launcher launcher) {
		this(launcher, Clock.systemUTC());
	}

	/** Creates a {@link ManagedEmbedder} with the given clock of the back-off (a test). */
	ManagedEmbedder(Launcher launcher, Clock clock) {
		_launcher = launcher;
		_clock = clock;
	}

	@Override
	public float[] embedText(String text, TextKind kind) throws IOException {
		return client().embedText(text, kind);
	}

	@Override
	public float[] embedDocument(String title, String text) throws IOException {
		return client().embedDocument(title, text);
	}

	@Override
	public float[] embedImage(byte[] image) throws IOException {
		return client().embedImage(image);
	}

	/** The client of the running service; starts it where none runs. */
	private HttpEmbedder client() throws EmbedderUnavailableException {
		synchronized (_lock) {
			if (_state == State.READY && _process.isAlive()) {
				return _client;
			}
			if (_state == State.STARTING && _process.isAlive()) {
				// Loading the model; the service answers 503 with the reason while it does.
				return _client;
			}
			start();
			throw new EmbedderUnavailableException(describe());
		}
	}

	@Override
	public String stamp() {
		return _launcher.stamp();
	}

	@Override
	public String unavailability() {
		HttpEmbedder client;
		synchronized (_lock) {
			if (_state != State.READY && _state != State.STARTING) {
				start();
				return describe();
			}
			client = _client;
		}
		String problem = client.unavailability();
		if (problem != null && client.lastHealthFailed()) {
			// The model does not load: the process is of no use, and starting it again at once
			// would not help either.
			Process process;
			synchronized (_lock) {
				if (_client != client) {
					return describe();
				}
				process = _process;
				_process = null;
				_client = null;
			}
			client.close();
			if (process != null) {
				stop(process);
			}
			fail(problem);
			synchronized (_lock) {
				return describe();
			}
		}
		synchronized (_lock) {
			if (_state == State.STARTING) {
				if (problem == null) {
					_state = State.READY;
					LOG.info("Content search: the embedder service is ready at " + client.getBase() + ".");
				} else {
					// While the model loads, the service may not even answer its health check.
					return "the embedder service is starting and loading the model (" + problem + ")";
				}
			}
		}
		return problem;
	}

	/** What {@link #peekUnavailability()} says of a service that was never asked for. */
	static final String NOT_STARTED = "the embedder service of this server is not running: it is prepared (its "
		+ "Python environment and the model are downloaded once) and started when a space switches content search on";

	@Override
	public String peekUnavailability() {
		synchronized (_lock) {
			if (_state == State.IDLE) {
				return NOT_STARTED;
			}
			if (_state != State.READY && _state != State.STARTING) {
				return describe();
			}
		}
		return unavailability();
	}

	@Override
	public boolean awaitAvailable(Duration timeout) throws InterruptedException {
		long end = System.nanoTime() + timeout.toNanos();
		while (true) {
			if (unavailability() == null) {
				return true;
			}
			synchronized (_lock) {
				if (_state == State.FAILED || _closed) {
					return false;
				}
			}
			long left = end - System.nanoTime();
			if (left <= 0) {
				return false;
			}
			Thread.sleep(Math.min(1000, Math.max(1, left / 1_000_000)));
		}
	}

	/** What the state means, for a message; called with the lock held. */
	private String describe() {
		return switch (_state) {
			case IDLE -> "the embedder service is not running";
			case PREPARING -> "the embedder service is being prepared: " + _message;
			case STARTING -> "the embedder service is starting";
			case READY -> "the embedder service is ready";
			case FAILED -> "the embedder service cannot run: " + _message + " (next attempt after " + _nextAttempt + ")";
		};
	}

	/** Starts the preparation where nothing runs and no back-off forbids it; called with the lock held. */
	private void start() {
		if (_closed || _state == State.PREPARING) {
			return;
		}
		if (_state == State.FAILED && _clock.instant().isBefore(_nextAttempt)) {
			return;
		}
		if ((_state == State.READY || _state == State.STARTING) && _process != null && _process.isAlive()) {
			return;
		}
		_state = State.PREPARING;
		_message = "starting";
		_starter = new Thread(this::run, "valbum-content-embedder");
		_starter.setDaemon(true);
		_starter.setPriority(Thread.MIN_PRIORITY);
		_starter.start();
	}

	private void run() {
		Process process = null;
		try {
			List<String> command = _launcher.prepare(message -> {
				synchronized (_lock) {
					_message = message;
				}
			});
			LOG.info("Content search: starting " + String.join(" ", command));
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectError(ProcessBuilder.Redirect.PIPE);
			process = builder.start();
			pipeLog(process);
			int port = readPort(process);
			HttpEmbedder client = new HttpEmbedder(URI.create("http://127.0.0.1:" + port + "/"));
			synchronized (_lock) {
				if (_closed) {
					client.close();
					stop(process);
					return;
				}
				_process = process;
				_client = client;
				_state = State.STARTING;
				_failures = 0;
				_message = null;
			}
			watch(process, client);
		} catch (IOException | RuntimeException ex) {
			if (process != null) {
				stop(process);
			}
			fail(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
		} catch (InterruptedException ex) {
			if (process != null) {
				stop(process);
			}
			fail("interrupted");
		}
	}

	private void fail(String reason) {
		synchronized (_lock) {
			_failures++;
			Duration backoff = FIRST_BACKOFF.multipliedBy(1L << Math.min(20, _failures - 1));
			if (backoff.compareTo(MAX_BACKOFF) > 0) {
				backoff = MAX_BACKOFF;
			}
			_state = State.FAILED;
			_message = reason;
			_nextAttempt = _clock.instant().plus(backoff);
			LOG.warning("Content search: the embedder service cannot run: " + reason + "; next attempt after "
				+ _nextAttempt);
		}
	}

	/** The port of the first line of the process, <code>{"listening": 34567}</code>. */
	private static int readPort(Process process) throws IOException, InterruptedException {
		CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
			try {
				return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
					.readLine();
			} catch (IOException ex) {
				return null;
			}
		});
		String first;
		try {
			first = line.get(LISTEN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException ex) {
			throw new IOException("the service did not say its port within " + LISTEN_TIMEOUT.toSeconds() + " s");
		} catch (ExecutionException ex) {
			throw new IOException("cannot read the port of the service: " + ex.getCause());
		}
		if (first == null) {
			process.waitFor(5, TimeUnit.SECONDS);
			throw new IOException("the service ended before it listened"
				+ (process.isAlive() ? "" : " (status " + process.exitValue() + ")") + "; see the log above");
		}
		try (JsonReader json = new JsonReader(new ReaderAdapter(new StringReader(first)))) {
			json.beginObject();
			while (json.hasNext()) {
				if ("listening".equals(json.nextName())) {
					return json.nextInt();
				}
				json.skipValue();
			}
		} catch (IOException | RuntimeException ex) {
			// Reported below.
		}
		throw new IOException("the service said no port but: " + first);
	}

	/** The service's stderr into the server's log. */
	private static void pipeLog(Process process) {
		Thread thread = new Thread(() -> {
			try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = err.readLine()) != null) {
					LOG.info("Content search: " + line);
				}
			} catch (IOException ex) {
				// The process ended.
			}
		}, "valbum-content-embedder-log");
		thread.setDaemon(true);
		thread.start();
	}

	/** Waits for the process to end and takes note: idle end, or failure. */
	private void watch(Process process, HttpEmbedder client) {
		Thread thread = new Thread(() -> {
			int status;
			try {
				status = process.waitFor();
			} catch (InterruptedException ex) {
				return;
			}
			client.close();
			synchronized (_lock) {
				if (_process != process) {
					return;
				}
				_process = null;
				_client = null;
				// Never "ready" without a process, not even until fail() below takes the lock.
				_state = State.IDLE;
				if (_closed) {
					return;
				}
				if (status == 0) {
					LOG.info("Content search: the embedder service ended after a time without work; it is started "
						+ "again when needed.");
					return;
				}
			}
			fail("the service ended with status " + status + "; see the log above");
		}, "valbum-content-embedder-watch");
		thread.setDaemon(true);
		thread.start();
	}

	private static void stop(Process process) {
		try {
			process.getOutputStream().close();
		} catch (IOException ex) {
			// Gone already.
		}
		try {
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				process.destroy();
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			}
		} catch (InterruptedException ex) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	/** The process of the service while it runs, for a measurement; <code>null</code> otherwise. */
	public Process getProcess() {
		synchronized (_lock) {
			return _process;
		}
	}

	@Override
	public void close() {
		Process process;
		Thread starter;
		synchronized (_lock) {
			_closed = true;
			process = _process;
			starter = _starter;
			_process = null;
			if (_client != null) {
				_client.close();
				_client = null;
			}
		}
		if (starter != null) {
			starter.interrupt();
		}
		if (process != null) {
			stop(process);
		}
		try {
			_launcher.close();
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "Content search: cannot close the launcher.", ex);
		}
	}

	@Override
	public String toString() {
		return _launcher.toString();
	}
}
