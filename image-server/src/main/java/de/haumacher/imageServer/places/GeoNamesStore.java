/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The directory of GeoNames files a {@link Places} reads, and the one download that fills it,
 * see issue #234.
 *
 * <h2>What is downloaded</h2>
 *
 * <p>
 * Only public files of <code>https://download.geonames.org/export/dump/</code>, by their names:
 * <code>countryInfo.txt</code>, <code>admin1CodesASCII.txt</code>, <code>admin2Codes.txt</code>,
 * <code>shapes_simplified_low.json.zip</code>, and <code>&lt;CC&gt;.zip</code> for each country a
 * position was looked up in, and <code>alternatenames/&lt;CC&gt;.zip</code> for each country a place
 * name was asked for in another language. A request carries the name of the file and nothing else: no position
 * ever leaves the server. The files go into this directory, never into a photo folder.
 * </p>
 *
 * <h2>When</h2>
 *
 * <ul>
 * <li>A file is fetched when it is asked for and missing ({@link #get(String)}); the asking
 * lookup answers "unavailable" and the position is looked up again later.</li>
 * <li>One download at a time on one low-priority thread, and a pause of
 * {@link Settings#gap} between two, so a library of photos from forty countries does not hammer
 * the server.</li>
 * <li>A failed download is not looped: it is tried again after a back-off that doubles from
 * {@link Settings#firstBackoff} up to {@link Settings#maxBackoff}, and only when the file is
 * asked for again. Until then {@link #problem(String)} says what failed and when the next try
 * is.</li>
 * <li>A downloaded file is checked for a newer version at most every {@link Settings#refresh}
 * (90 days by default), with a conditional request (<code>If-None-Match</code>,
 * <code>If-Modified-Since</code>): an unchanged file costs a <code>304</code>. A new version is
 * downloaded beside the old one (<code>.part</code>), {@link Check checked} &mdash; parsed
 * completely &mdash; and only then moved into its place; a failed or broken download leaves the
 * old file in use, without a gap.</li>
 * <li>What was downloaded is recorded beside the file (<code>&lt;name&gt;.download</code>:
 * address, <code>ETag</code>, <code>Last-Modified</code>, the time of the last check). A file
 * without that record was placed by hand &mdash; an air-gapped server's &mdash; and is never
 * replaced.</li>
 * </ul>
 *
 * <p>
 * A store made without {@link Settings} downloads nothing at all: it reads what lies in the
 * directory (tests, and a server configured not to download).
 * </p>
 */
public final class GeoNamesStore implements Closeable {

	private static final Logger LOG = Logger.getLogger(GeoNamesStore.class.getName());

	/** The countries: ISO code, name, GeoNames id. */
	public static final String COUNTRY_INFO = "countryInfo.txt";

	/** The names of the first administrative divisions. */
	public static final String ADMIN1 = "admin1CodesASCII.txt";

	/** The names of the second administrative divisions. */
	public static final String ADMIN2 = "admin2Codes.txt";

	/** The simplified outlines of the countries. */
	public static final String SHAPES = "shapes_simplified_low.json.zip";

	/** Where GeoNames publishes its dump. */
	public static final URI GEONAMES = URI.create("https://download.geonames.org/export/dump/");

	/** The suffix of the record of a download, see {@link GeoNamesStore}. */
	static final String RECORD_SUFFIX = ".download";

	/** The suffix of a download in progress. */
	static final String PART_SUFFIX = ".part";

	/** The file of a country. */
	public static String countryFile(String iso) {
		return iso + ".zip";
	}

	/** The folder of the alternate names, below the directory as below {@link #GEONAMES}. */
	public static final String ALTERNATE_NAMES = "alternatenames";

	/** The file of the alternate names of a country, see {@link PlaceNames}. */
	public static String alternateNamesFile(String iso) {
		return ALTERNATE_NAMES + "/" + iso + ".zip";
	}

	/**
	 * The names a file may have been placed under by hand besides its own: a country file or the
	 * outlines unpacked.
	 */
	static List<String> alternatives(String name) {
		if (name.endsWith(".zip")) {
			String base = name.substring(0, name.length() - 4);
			return List.of(base.endsWith(".json") ? base : base + ".txt");
		}
		return List.of();
	}

	/** How and when this store downloads. */
	public static final class Settings {

		URI base = GEONAMES;

		Duration refresh = Duration.ofDays(90);

		Duration gap = Duration.ofSeconds(10);

		Duration firstBackoff = Duration.ofMinutes(10);

		Duration maxBackoff = Duration.ofDays(1);

		Clock clock = Clock.systemUTC();

		/** The address the files are fetched below; {@link GeoNamesStore#GEONAMES} by default. */
		public Settings setBase(URI base) {
			this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
			return this;
		}

		/** How long a downloaded file is used before it is checked for a newer version. */
		public Settings setRefresh(Duration refresh) {
			this.refresh = refresh;
			return this;
		}

		/** The least pause between the start of two downloads. */
		public Settings setGap(Duration gap) {
			this.gap = gap;
			return this;
		}

		/** The pause after the first failure of a file, doubled with each further one. */
		public Settings setFirstBackoff(Duration firstBackoff) {
			this.firstBackoff = firstBackoff;
			return this;
		}

		/** The longest pause after a failure. */
		public Settings setMaxBackoff(Duration maxBackoff) {
			this.maxBackoff = maxBackoff;
			return this;
		}

		/** The clock of refresh and back-off. */
		public Settings setClock(Clock clock) {
			this.clock = clock;
			return this;
		}
	}

	/**
	 * Checks a downloaded file before it replaces the one in use.
	 */
	public interface Check {
		/**
		 * Reads the given file completely.
		 *
		 * @param name
		 *        The name it will have.
		 * @param candidate
		 *        The downloaded file.
		 * @return What was read, handed to the {@link Listener}; may be <code>null</code>.
		 * @throws IOException
		 *         If the file is not what its name says: then it is dropped.
		 */
		Object check(String name, Path candidate) throws IOException;
	}

	/** Told about a file that was replaced. */
	public interface Listener {
		/**
		 * The file of the given name is new.
		 *
		 * @param checked
		 *        What the {@link Check} answered for it.
		 */
		void replaced(String name, Object checked);
	}

	/** What {@link #status()} says about a file. */
	public record FileStatus(String name, boolean present, String state) {
		// A triple.
	}

	private static final class State {
		boolean _queued;

		boolean _running;

		int _failures;

		Instant _nextAttempt = Instant.MIN;

		String _error;
	}

	private final Path _directory;

	private final Settings _settings;

	private final ExecutorService _worker;

	private final HttpClient _http;

	private final Map<String, State> _states = new HashMap<>();

	private volatile Check _check = (name, candidate) -> null;

	private volatile Listener _listener = (name, checked) -> {
		// Nobody listens.
	};

	private Instant _lastStart = Instant.MIN;

	/** Who is told when a download attempt ended, see {@link #addAttemptListener(Runnable)}. */
	private final List<Runnable> _attemptListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** How many download attempts ended, see {@link #generation()}. */
	private final java.util.concurrent.atomic.AtomicLong _generation = new java.util.concurrent.atomic.AtomicLong();

	/**
	 * A store that only reads the given directory and downloads nothing.
	 */
	public GeoNamesStore(Path directory) {
		this(directory, null);
	}

	/**
	 * A store that downloads into the given directory.
	 *
	 * @param settings
	 *        How and when, <code>null</code> to download nothing.
	 */
	public GeoNamesStore(Path directory, Settings settings) {
		_directory = directory;
		_settings = settings;
		if (settings == null) {
			_worker = null;
			_http = null;
		} else {
			_worker = Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "valbum-geonames");
				thread.setDaemon(true);
				thread.setPriority(Thread.MIN_PRIORITY);
				return thread;
			});
			_http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30)).build();
		}
	}

	/** The directory the files lie in. */
	public Path getDirectory() {
		return _directory;
	}

	/** Whether this store downloads. */
	public boolean isDownloading() {
		return _settings != null;
	}

	/** Sets what checks a downloaded file before it is used. */
	public void setCheck(Check check) {
		_check = check;
	}

	/** Sets who is told about a replaced file. */
	public void setListener(Listener listener) {
		_listener = listener;
	}

	/**
	 * Tells the given listener whenever a download attempt ended, successfully or not, see issue
	 * #234: what was waiting for a file asks again then. Called on the download thread, after the
	 * new file is in place and the {@link Listener} has taken it in; a listener only takes note.
	 */
	public void addAttemptListener(Runnable listener) {
		_attemptListeners.add(listener);
	}

	/** Stops telling the given listener, see {@link #addAttemptListener(Runnable)}. */
	public void removeAttemptListener(Runnable listener) {
		_attemptListeners.remove(listener);
	}

	/**
	 * How many download attempts have ended: an answer given before one ended may be a different
	 * one now.
	 */
	public long generation() {
		return _generation.get();
	}

	/**
	 * Whether the file of the given name is missing and on its way: queued or being downloaded.
	 * <code>false</code> for a file that is there, one whose download failed and waits for its
	 * back-off, and for every file of a store that downloads nothing.
	 */
	public boolean isLoading(String name) {
		if (_settings == null || existing(name) != null) {
			return false;
		}
		synchronized (_states) {
			State state = _states.get(name);
			return state == null ? false : state._queued || state._running;
		}
	}

	/**
	 * The file of the given name to read, <code>null</code> while there is none.
	 *
	 * <p>
	 * Asks for the file to be downloaded where it is missing, and for a newer version where it is
	 * due for a check, see {@link GeoNamesStore}; the answer is what lies here now.
	 * </p>
	 */
	public Path get(String name) {
		Path result = existing(name);
		if (_settings != null) {
			if (result == null) {
				schedule(name);
			} else if (dueForRefresh(name)) {
				schedule(name);
			}
		}
		return result;
	}

	private Path existing(String name) {
		Path file = _directory.resolve(name);
		if (Files.isRegularFile(file)) {
			return file;
		}
		for (String alternative : alternatives(name)) {
			Path other = _directory.resolve(alternative);
			if (Files.isRegularFile(other)) {
				return other;
			}
		}
		return null;
	}

	private boolean dueForRefresh(String name) {
		Properties record = readRecord(name);
		if (record == null || !Files.isRegularFile(_directory.resolve(name))) {
			// Placed by hand: never replaced.
			return false;
		}
		Instant checked = instant(record.getProperty("checked"));
		return checked == null || !checked.plus(_settings.refresh).isAfter(_settings.clock.instant());
	}

	/**
	 * Why the file of the given name is not there, for a message such as "place names unavailable:
	 * ...".
	 */
	public String problem(String name) {
		if (existing(name) != null) {
			return null;
		}
		if (_settings == null) {
			return name + " is missing in " + _directory + "; this server downloads nothing, the file is to be "
				+ "placed there by hand (from " + GEONAMES + ")";
		}
		synchronized (_states) {
			State state = _states.get(name);
			if (state == null || state._queued || state._running) {
				return name + " is being downloaded from GeoNames";
			}
			if (state._error != null) {
				return "the download of " + name + " failed (" + state._error + "); next attempt after "
					+ state._nextAttempt;
			}
			return name + " is missing";
		}
	}

	/** What is known about every file asked for or present, for a report. */
	public List<FileStatus> status() {
		TreeSet<String> names = new TreeSet<>();
		synchronized (_states) {
			names.addAll(_states.keySet());
		}
		try (var files = Files.list(_directory)) {
			files.filter(p -> !Files.isDirectory(p)).map(p -> p.getFileName().toString())
				.filter(n -> !n.endsWith(RECORD_SUFFIX) && !n.endsWith(PART_SUFFIX) && !n.endsWith(".tmp")
					&& !n.endsWith(Places.CACHE_SUFFIX))
				.forEach(names::add);
		} catch (IOException ex) {
			// No directory yet: only what was asked for.
		}
		List<FileStatus> result = new ArrayList<>();
		for (String name : names) {
			boolean present = existing(name) != null;
			String state;
			synchronized (_states) {
				State s = _states.get(name);
				if (s != null && s._running) {
					state = "downloading";
				} else if (s != null && s._queued) {
					state = "queued";
				} else if (s != null && s._error != null) {
					state = (present ? "refresh failed: " : "failed: ") + s._error + ", next attempt after "
						+ s._nextAttempt;
				} else if (!present) {
					state = "missing";
				} else if (readRecord(name) == null) {
					state = "placed by hand";
				} else {
					state = "downloaded, checked " + readRecord(name).getProperty("checked");
				}
			}
			result.add(new FileStatus(name, present, state));
		}
		return result;
	}

	private void schedule(String name) {
		synchronized (_states) {
			State state = _states.computeIfAbsent(name, n -> new State());
			if (state._queued || state._running || _settings.clock.instant().isBefore(state._nextAttempt)) {
				return;
			}
			state._queued = true;
		}
		_worker.execute(() -> fetch(name));
	}

	private void fetch(String name) {
		State state;
		synchronized (_states) {
			state = _states.get(name);
			state._queued = false;
			state._running = true;
		}
		String error = null;
		try {
			pause();
			error = download(name);
		} catch (IOException | RuntimeException ex) {
			error = reason(ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			error = "interrupted";
		} finally {
			synchronized (_states) {
				state._running = false;
				if (error == null) {
					state._failures = 0;
					state._error = null;
					state._nextAttempt = Instant.MIN;
				} else {
					state._failures++;
					Duration backoff = _settings.firstBackoff.multipliedBy(1L << Math.min(20, state._failures - 1));
					if (backoff.compareTo(_settings.maxBackoff) > 0) {
						backoff = _settings.maxBackoff;
					}
					state._error = error + " at " + _settings.clock.instant();
					state._nextAttempt = _settings.clock.instant().plus(backoff);
					LOG.warning("GeoNames: " + name + ": " + state._error + "; next attempt after "
						+ state._nextAttempt);
				}
				// Before anyone waiting is woken: whoever wakes sees the attempt counted.
				_generation.incrementAndGet();
				_states.notifyAll();
			}
			for (Runnable listener : _attemptListeners) {
				try {
					listener.run();
				} catch (RuntimeException ex) {
					LOG.log(Level.WARNING, "GeoNames: a listener failed after the download of " + name + ".", ex);
				}
			}
		}
	}

	private void pause() throws InterruptedException {
		Instant next = _lastStart.equals(Instant.MIN) ? Instant.MIN : _lastStart.plus(_settings.gap);
		long wait = next.equals(Instant.MIN) ? 0 : Duration.between(_settings.clock.instant(), next).toMillis();
		if (wait > 0) {
			Thread.sleep(wait);
		}
		_lastStart = _settings.clock.instant();
	}

	/** Downloads one file; answers the error, <code>null</code> for success. */
	private String download(String name) throws IOException, InterruptedException {
		Path target = _directory.resolve(name);
		Files.createDirectories(target.getParent());
		Path part = _directory.resolve(name + PART_SUFFIX);
		URI uri = _settings.base.resolve(name);
		Properties record = readRecord(name);
		HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30))
			.header("User-Agent", "VAlbum (https://github.com/haumacher/valbum2)");
		boolean conditional = record != null && Files.isRegularFile(target);
		if (conditional) {
			String etag = record.getProperty("etag");
			if (etag != null) {
				request.header("If-None-Match", etag);
			}
			String modified = record.getProperty("lastModified");
			if (modified != null) {
				request.header("If-Modified-Since", modified);
			}
		}
		try {
			HttpResponse<Path> response = _http.send(request.build(), HttpResponse.BodyHandlers.ofFile(part));
			int status = response.statusCode();
			if (status == 304 && conditional) {
				record.setProperty("checked", _settings.clock.instant().toString());
				writeRecord(name, record);
				LOG.info("GeoNames: " + name + " is unchanged.");
				return null;
			}
			if (status != 200) {
				return "HTTP " + status + " for " + uri;
			}
			Object checked;
			try {
				checked = _check.check(name, part);
			} catch (IOException | RuntimeException ex) {
				return "the file downloaded is broken: " + reason(ex);
			}
			Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			Properties fresh = new Properties();
			fresh.setProperty("url", uri.toString());
			response.headers().firstValue("ETag").ifPresent(v -> fresh.setProperty("etag", v));
			response.headers().firstValue("Last-Modified").ifPresent(v -> fresh.setProperty("lastModified", v));
			fresh.setProperty("checked", _settings.clock.instant().toString());
			writeRecord(name, fresh);
			LOG.info("GeoNames: downloaded " + name + " (" + Files.size(target) + " bytes).");
			try {
				_listener.replaced(name, checked);
			} catch (RuntimeException ex) {
				LOG.log(Level.WARNING, "GeoNames: cannot take in the new " + name + ".", ex);
			}
			return null;
		} finally {
			Files.deleteIfExists(part);
		}
	}

	/** A record read before, valid while its file's modification time is the same. */
	private record CachedRecord(long modified, Properties record) {
		// A pair.
	}

	private final Map<String, CachedRecord> _records = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * The record of a download, <code>null</code> for a file placed by hand; read once and again
	 * only when the record changed, since every lookup asks.
	 */
	private Properties readRecord(String name) {
		Path file = _directory.resolve(name + RECORD_SUFFIX);
		long modified;
		try {
			modified = Files.getLastModifiedTime(file).toMillis();
		} catch (IOException ex) {
			_records.remove(name);
			return null;
		}
		CachedRecord cached = _records.get(name);
		if (cached != null && cached.modified() == modified) {
			return cached.record();
		}
		Properties result = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			result.load(in);
		} catch (IOException ex) {
			return null;
		}
		_records.put(name, new CachedRecord(modified, result));
		return result;
	}

	private void writeRecord(String name, Properties record) throws IOException {
		Path file = _directory.resolve(name + RECORD_SUFFIX);
		Path tmp = _directory.resolve(name + RECORD_SUFFIX + ".tmp");
		try (OutputStream out = Files.newOutputStream(tmp)) {
			record.store(out, "Downloaded by VAlbum from GeoNames (CC BY 4.0); without this file it is never refreshed");
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		_records.remove(name);
	}

	private static Instant instant(String text) {
		if (text == null) {
			return null;
		}
		try {
			return Instant.parse(text);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static String reason(Throwable ex) {
		String message = ex.getMessage();
		return message == null || message.isEmpty() ? ex.getClass().getSimpleName()
			: ex.getClass().getSimpleName() + ": " + message;
	}

	/**
	 * Waits until no download is queued or running, for a test.
	 *
	 * @return Whether that happened in time.
	 */
	public boolean awaitIdle(Duration timeout) throws InterruptedException {
		long end = System.nanoTime() + timeout.toNanos();
		synchronized (_states) {
			while (true) {
				boolean busy = false;
				for (State state : _states.values()) {
					busy |= state._queued || state._running;
				}
				if (!busy) {
					return true;
				}
				long left = (end - System.nanoTime()) / 1_000_000;
				if (left <= 0) {
					return false;
				}
				_states.wait(left);
			}
		}
	}

	@Override
	public void close() {
		if (_worker != null) {
			_worker.shutdownNow();
			_http.shutdownNow();
		}
	}
}
