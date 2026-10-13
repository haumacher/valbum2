/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.content.ContentModel.ModelFile;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * The files of the {@link ContentModel} in one precision, and the one download that fetches them,
 * see issue #257 &mdash; the pattern of the GeoNames files of #234.
 *
 * <h2>Where</h2>
 *
 * <p>
 * Below the embedder's folder (<code>VALBUM_EMBEDDER_DIR</code>), in a folder named after the
 * repository and the revision (<code>embeddinggemma-2-ONNX@daa72c51/</code>), each file under its
 * name in the repository (<code>tokenizer.json</code>, <code>onnx/model.onnx</code>, ...). Never in
 * a photo folder.
 * </p>
 *
 * <h2>When</h2>
 *
 * <ul>
 * <li>Nothing happens until {@link #request()} is called: the first time a space asks for content
 * search. A library that never does downloads nothing.</li>
 * <li>One file at a time on one low-priority thread. A file is downloaded beside its place
 * (<code>.part</code>), resumed where an interrupted download stopped (an HTTP range request), checked
 * against the SHA-256 of the pin, and only then moved into its place.</li>
 * <li>What was checked is recorded beside the file (<code>&lt;name&gt;.download</code>: hash, size,
 * modification time, where it came from), so that a restart does not read two gigabytes again.</li>
 * <li>A failed download is tried again after a back-off that doubles from
 * {@link Settings#firstBackoff} up to {@link Settings#maxBackoff}, and only when asked again; until
 * then {@link #problem()} says what failed and when the next try is.</li>
 * </ul>
 *
 * <h2>Offline</h2>
 *
 * <p>
 * A server without internet access takes the files placed into the folder by hand, under the same
 * names. Each is checked against the pin once; one that does not match is named in
 * {@link #problem()} and never touched &mdash; it is the administrator's file.
 * </p>
 */
public final class ModelStore implements Closeable {

	private static final Logger LOG = Logger.getLogger(ModelStore.class.getName());

	/** Where the model is downloaded from. */
	public static final URI HUGGING_FACE = URI.create("https://huggingface.co/");

	/** The suffix of the record of a checked file. */
	static final String RECORD_SUFFIX = ".download";

	/** The suffix of a download in progress. */
	static final String PART_SUFFIX = ".part";

	/** How and when the store downloads. */
	public static final class Settings {

		URI base = HUGGING_FACE;

		Duration firstBackoff = Duration.ofMinutes(10);

		Duration maxBackoff = Duration.ofDays(1);

		Duration stall = Duration.ofMinutes(2);

		Clock clock = Clock.systemUTC();

		/** The address the repositories are found below; {@link ModelStore#HUGGING_FACE} by default. */
		public Settings setBase(URI base) {
			this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
			return this;
		}

		/** The pause after the first failure, doubled with each further one. */
		public Settings setFirstBackoff(Duration firstBackoff) {
			this.firstBackoff = firstBackoff;
			return this;
		}

		/** The longest pause after a failure. */
		public Settings setMaxBackoff(Duration maxBackoff) {
			this.maxBackoff = maxBackoff;
			return this;
		}

		/** How long a download may receive nothing before it is given up (and resumed later). */
		public Settings setStall(Duration stall) {
			this.stall = stall;
			return this;
		}

		/** The clock of the back-off. */
		public Settings setClock(Clock clock) {
			this.clock = clock;
			return this;
		}
	}

	/** Closes the connections of stalled downloads. */
	private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "valbum-content-model-watchdog");
			thread.setDaemon(true);
			return thread;
		});

	private final Path _directory;

	private final ContentModel _model;

	private final List<ModelFile> _files;

	private final Settings _settings;

	private final ExecutorService _worker;

	private final HttpClient _http;

	private final Object _lock = new Object();

	private boolean _running;

	private boolean _ready;

	private int _failures;

	private Instant _nextAttempt = Instant.MIN;

	private String _error;

	private String _current;

	private final AtomicLong _received = new AtomicLong();

	/**
	 * Creates a {@link ModelStore}.
	 *
	 * @param parent
	 *        The embedder's folder; the model's own folder is made below it.
	 * @param settings
	 *        How to download, <code>null</code> to download nothing (only what was placed by hand
	 *        is used).
	 */
	public ModelStore(Path parent, ContentModel model, String precision, Settings settings) {
		_directory = parent.resolve(folderName(model));
		_model = model;
		_files = model.files(precision);
		_settings = settings;
		_worker = Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "valbum-content-model");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		_http = settings == null ? null
			: HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30))
				.build();
	}

	/** The folder of the model below the embedder's folder: repository and revision. */
	static String folderName(ContentModel model) {
		String repository = model.getRepository();
		return repository.substring(repository.lastIndexOf('/') + 1) + "@" + model.getRevision().substring(0, 8);
	}

	/** The folder the files lie in, handed to the service. */
	public Path getDirectory() {
		return _directory;
	}

	/** How many bytes the model takes. */
	public long totalSize() {
		return _files.stream().mapToLong(ModelFile::size).sum();
	}

	/**
	 * Whether every file is there and was checked; cheap, it reads the records only. A file
	 * changed after its check is not.
	 */
	public boolean isReady() {
		synchronized (_lock) {
			if (_ready) {
				return true;
			}
		}
		for (ModelFile file : _files) {
			if (!checked(file)) {
				return false;
			}
		}
		synchronized (_lock) {
			_ready = true;
		}
		return true;
	}

	/**
	 * Asks for the model: checks what is there and downloads what is missing, in the background.
	 * Does nothing while that runs, while a failure is backed off, and when the model is ready.
	 */
	public void request() {
		if (isReady()) {
			return;
		}
		synchronized (_lock) {
			if (_running || _settings != null && _settings.clock.instant().isBefore(_nextAttempt)) {
				return;
			}
			if (_settings == null && _error != null) {
				// Nothing will change without the administrator; checked again at the next start.
				return;
			}
			_running = true;
		}
		_worker.execute(this::prepare);
	}

	/** Whether the files are being checked or downloaded now. */
	public boolean isRunning() {
		synchronized (_lock) {
			return _running;
		}
	}

	/** Why the model cannot be used now, <code>null</code> when it can. */
	public String problem() {
		if (isReady()) {
			return null;
		}
		synchronized (_lock) {
			if (_running) {
				if (_current == null) {
					return "the model files are being checked in " + _directory;
				}
				long total = totalSize();
				return "the model (" + megabytes(total) + ") is being downloaded into " + _directory + ": "
					+ megabytes(_received.get()) + " so far, now " + _current;
			}
			if (_error != null) {
				return _error + (_settings == null ? "" : "; next attempt after " + _nextAttempt);
			}
			return "the model (" + megabytes(totalSize()) + ") is not in " + _directory + " yet";
		}
	}

	/**
	 * Waits until the model is ready or its preparation ended in a failure.
	 *
	 * @return Whether it is ready.
	 */
	public boolean awaitReady(Duration timeout) throws InterruptedException {
		long end = System.nanoTime() + timeout.toNanos();
		synchronized (_lock) {
			while (_running) {
				long left = (end - System.nanoTime()) / 1_000_000;
				if (left <= 0) {
					break;
				}
				_lock.wait(left);
			}
		}
		return isReady();
	}

	private void prepare() {
		String error = null;
		try {
			for (ModelFile file : _files) {
				error = ensure(file);
				if (error != null) {
					break;
				}
			}
		} catch (IOException | RuntimeException ex) {
			error = reason(ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			error = "interrupted";
		}
		synchronized (_lock) {
			_running = false;
			_current = null;
			if (error == null) {
				_failures = 0;
				_error = null;
				_nextAttempt = Instant.MIN;
			} else {
				_failures++;
				_error = error;
				if (_settings != null) {
					Duration backoff = _settings.firstBackoff.multipliedBy(1L << Math.min(20, _failures - 1));
					if (backoff.compareTo(_settings.maxBackoff) > 0) {
						backoff = _settings.maxBackoff;
					}
					_nextAttempt = _settings.clock.instant().plus(backoff);
				}
				LOG.warning("Content search: " + error + (_settings == null ? "" : "; next attempt after " + _nextAttempt));
			}
			_lock.notifyAll();
		}
	}

	/** Makes one file ready; answers the error, <code>null</code> for success. */
	private String ensure(ModelFile file) throws IOException, InterruptedException {
		if (checked(file)) {
			return null;
		}
		Path target = target(file);
		if (Files.isRegularFile(target)) {
			Properties record = readRecord(file);
			String hash = sha256(target);
			if (hash.equals(file.sha256()) && Files.size(target) == file.size()) {
				writeRecord(file, record == null ? "placed by hand" : record.getProperty("source", "downloaded"));
				return null;
			}
			if (record == null) {
				return target + " was placed by hand but is not the file of the pinned model (SHA-256 " + hash
					+ ", expected " + file.sha256() + "); replace or remove it";
			}
			// Downloaded once and changed since: fetched again.
			Files.delete(target);
			Files.deleteIfExists(record(file));
		}
		if (_settings == null) {
			return file.name() + " is missing in " + _directory + "; this server downloads nothing, the file is to be "
				+ "placed there by hand (from " + HUGGING_FACE + _model.getRepository() + ", revision "
				+ _model.getRevision() + ")";
		}
		return download(file);
	}

	private String download(ModelFile file) throws IOException, InterruptedException {
		Path target = target(file);
		Files.createDirectories(target.getParent());
		Path part = target.resolveSibling(target.getFileName() + PART_SUFFIX);
		URI uri = _settings.base.resolve(_model.getRepository() + "/resolve/" + _model.getRevision() + "/" + file.name());
		synchronized (_lock) {
			_current = file.name();
		}
		long have = Files.isRegularFile(part) ? Files.size(part) : 0;
		if (have > file.size()) {
			Files.delete(part);
			have = 0;
		}
		if (have < file.size()) {
			HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofHours(6))
				.header("User-Agent", "VAlbum (https://github.com/haumacher/valbum2)");
			if (have > 0) {
				request.header("Range", "bytes=" + have + "-");
			}
			HttpResponse<InputStream> response =
				_http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
			int status = response.statusCode();
			try (InputStream in = response.body()) {
				boolean append;
				if (status == 206 && have > 0) {
					append = true;
				} else if (status == 200) {
					// The server ignores the range: from the start.
					append = false;
				} else {
					return "HTTP " + status + " for " + uri;
				}
				LOG.info("Content search: downloading " + file.name() + " (" + megabytes(file.size()) + ")"
					+ (append ? ", resumed at " + megabytes(have) : "") + ".");
				// A connection that stalls would block the read for ever: a watchdog closes it.
				AtomicLong progress = new AtomicLong(System.nanoTime());
				java.util.concurrent.atomic.AtomicBoolean stalled = new java.util.concurrent.atomic.AtomicBoolean();
				long stall = _settings.stall.toNanos();
				java.util.concurrent.ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
					if (System.nanoTime() - progress.get() > stall && !stalled.getAndSet(true)) {
						try {
							in.close();
						} catch (IOException ex) {
							// Closed is closed.
						}
					}
				}, 100, Math.max(100, _settings.stall.toMillis() / 4), java.util.concurrent.TimeUnit.MILLISECONDS);
				try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
					byte[] buffer = new byte[1 << 16];
					int n;
					while ((n = in.read(buffer)) > 0) {
						out.write(buffer, 0, n);
						_received.addAndGet(n);
						progress.set(System.nanoTime());
						if (Thread.currentThread().isInterrupted()) {
							throw new InterruptedException();
						}
					}
				} catch (IOException ex) {
					if (stalled.get()) {
						return "the download of " + file.name() + " received nothing for " + _settings.stall.toSeconds()
							+ " s; it is resumed at the next attempt";
					}
					throw ex;
				} finally {
					watchdog.cancel(false);
				}
			}
		}
		long size = Files.size(part);
		if (size != file.size()) {
			if (size > file.size()) {
				Files.delete(part);
			}
			// Shorter: resumed at the next attempt.
			return "the download of " + file.name() + " stopped at " + size + " of " + file.size() + " bytes";
		}
		String hash = sha256(part);
		if (!hash.equals(file.sha256())) {
			Files.delete(part);
			return "the download of " + file.name() + " is not the file of the pinned model (SHA-256 " + hash
				+ ", expected " + file.sha256() + ")";
		}
		Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		writeRecord(file, uri.toString());
		LOG.info("Content search: downloaded and checked " + file.name() + ".");
		return null;
	}

	private Path target(ModelFile file) {
		return _directory.resolve(file.name());
	}

	private Path record(ModelFile file) {
		Path target = target(file);
		return target.resolveSibling(target.getFileName() + RECORD_SUFFIX);
	}

	/** Whether the file is there and its record says it was checked as it is now. */
	private boolean checked(ModelFile file) {
		Properties record = readRecord(file);
		if (record == null) {
			return false;
		}
		try {
			Path target = target(file);
			return file.sha256().equals(record.getProperty("sha256")) && Files.size(target) == file.size()
				&& String.valueOf(Files.getLastModifiedTime(target).toMillis()).equals(record.getProperty("modified"));
		} catch (IOException ex) {
			return false;
		}
	}

	private Properties readRecord(ModelFile file) {
		Path path = record(file);
		if (!Files.isRegularFile(path)) {
			return null;
		}
		Properties result = new Properties();
		try (InputStream in = Files.newInputStream(path)) {
			result.load(in);
		} catch (IOException ex) {
			return null;
		}
		return result;
	}

	private void writeRecord(ModelFile file, String source) throws IOException {
		Path target = target(file);
		Properties record = new Properties();
		record.setProperty("sha256", file.sha256());
		record.setProperty("size", String.valueOf(Files.size(target)));
		record.setProperty("modified", String.valueOf(Files.getLastModifiedTime(target).toMillis()));
		record.setProperty("source", source);
		record.setProperty("checked", Instant.now().toString());
		Path path = record(file);
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try (OutputStream out = Files.newOutputStream(tmp)) {
			record.store(out, "Checked by VAlbum against the pinned SHA-256 (issue #257); without this file it is checked again");
		}
		Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** The SHA-256 of a file, in hex. */
	static String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new IOException(ex);
		}
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[1 << 16];
			int n;
			while ((n = in.read(buffer)) > 0) {
				digest.update(buffer, 0, n);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	static String megabytes(long bytes) {
		return (bytes + 512 * 1024) / (1024 * 1024) + " MB";
	}

	private static String reason(Throwable ex) {
		String message = ex.getMessage();
		return message == null || message.isEmpty() ? ex.getClass().getSimpleName()
			: ex.getClass().getSimpleName() + ": " + message;
	}

	@Override
	public void close() {
		_worker.shutdownNow();
		if (_http != null) {
			_http.shutdownNow();
		}
	}
}
