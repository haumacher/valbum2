/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonToken;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * The {@link Embedder} that talks to an embedder service, see issue #257: the Python service of
 * <code>image-server/src/embedder</code> on <code>127.0.0.1</code>, started by
 * {@link ManagedEmbedder}, or one given by its address (<code>VALBUM_EMBEDDER_URL</code>).
 *
 * <p>
 * The protocol is the OpenAI embeddings call, <code>POST /v1/embeddings</code>, with a picture as
 * a base64 data URI and <code>"modality": "image"</code>, and <code>GET /health</code>, which names
 * the model, its revision and precision. The task prefixes are added here, see
 * {@link Embedder.TextKind}. The vectors come back as base64 of little-endian float32, already
 * truncated to {@link Embedder#DIMENSION} and normalised again; this client checks both.
 * </p>
 *
 * <p>
 * A service that does not answer, is still loading its model or failed to load it makes a call
 * fail with {@link EmbedderUnavailableException}, saying which. A refused input (a picture that is
 * no picture) fails with a plain {@link IOException}: that photograph will not do better later.
 * </p>
 *
 * <h2>A service on another machine</h2>
 *
 * <p>
 * The package <code>valbum-embedder</code> runs the service on a machine of its own, see the
 * README. Every request then carries its key (<code>Authorization: Bearer</code>,
 * <code>VALBUM_EMBEDDER_KEY</code>); a refused key makes the service unavailable, with the
 * service's reason. An <code>https</code> address is checked against the trusted certificate
 * authorities of the JVM, or, where a SHA-256 fingerprint is pinned
 * (<code>VALBUM_EMBEDDER_CERT_SHA256</code>, for a self-signed certificate), against that
 * fingerprint alone.
 * </p>
 *
 * <h2>Requests at once</h2>
 *
 * <p>
 * The service names in its health how many requests it embeds at once (<code>parallel</code>, issue
 * #311; an older service names none and embeds one at a time). A service on a machine of its own
 * ({@link #separate()}) takes the content search's units on a lane of their own, beside the photo
 * work, as many at once as it embeds ({@link #lane()}); the pictures and documents of the background
 * are bounded here, for all spaces of the server together, to that many requests in flight, so that
 * none waits in the service's queue against its time limit. A search's query is never held back by
 * them. The child of {@link ManagedEmbedder} shares the server's machine and has no lane.
 * </p>
 *
 * <h2>The model of every vector</h2>
 *
 * <p>
 * The service names the revision, precision and preprocessing of the model in its health and with
 * every vector. A service whose preprocessing is not {@link ContentModel#PREPROCESSING} is refused
 * (another release of the embedder: its vectors would carry a stamp that lies). A vector computed
 * with another model than the one {@link #stamp()} answered is refused, and {@link #stamp()} follows
 * the service afterwards: vectors of different stamps are never mixed.
 * </p>
 */
public class HttpEmbedder implements Embedder {

	private static final Logger LOG = Logger.getLogger(HttpEmbedder.class.getName());

	/** How long to wait for a connection. */
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	/** How long a health check may take. */
	static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * How long a text may take: a query is tens of milliseconds on a PC, a few hundred on a
	 * Raspberry Pi; the rest is room for a picture embedded before it.
	 */
	static final Duration TEXT_TIMEOUT = Duration.ofMinutes(2);

	/** How long a picture may take: seconds on a PC, tens of seconds on a Raspberry Pi 5. */
	static final Duration IMAGE_TIMEOUT = Duration.ofMinutes(10);

	/**
	 * What {@link #health()} answers; <code>preprocessing</code> is -1 where the service names none,
	 * <code>parallel</code> is 1 where it names none (a service before issue #311).
	 */
	public record Health(String status, String model, String revision, String precision, int dimension, String error,
			int preprocessing, String detail, int parallel) {

		/** Whether the service can embed. */
		public boolean isReady() {
			return "ready".equals(status);
		}

		/** Whether the service will never embed (it failed to load its model). */
		public boolean isFailed() {
			return "failed".equals(status);
		}
	}

	private final URI _base;

	private final String _key;

	private final HttpClient _http;

	private final Duration _textTimeout;

	private final Duration _imageTimeout;

	private volatile String _stamp;

	private volatile Health _lastHealth;

	private boolean _report;

	/** Whether the service runs on a machine of its own, see {@link #separate()}. */
	private volatile boolean _separate;

	/** How many requests of the background may be in flight, see {@link #lane()}. */
	private final Gate _gate = new Gate();

	private String _reported = "";

	/**
	 * Creates a {@link HttpEmbedder} for the service at the given address, e.g.
	 * <code>http://127.0.0.1:8091/</code>, that sends no key.
	 */
	public HttpEmbedder(URI base) {
		this(base, null, null, TEXT_TIMEOUT, IMAGE_TIMEOUT);
	}

	/**
	 * Creates a {@link HttpEmbedder} for a service of its own.
	 *
	 * @param key
	 *        The key every request carries, <code>null</code> for none.
	 * @param certSha256
	 *        The SHA-256 of the service's certificate, 32 bytes, for a self-signed one; <code>null</code>
	 *        to check an <code>https</code> address against the trusted certificate authorities.
	 */
	public HttpEmbedder(URI base, String key, byte[] certSha256) {
		this(base, key, certSha256, TEXT_TIMEOUT, IMAGE_TIMEOUT);
	}

	/** Creates a {@link HttpEmbedder} with other time limits (a test). */
	HttpEmbedder(URI base, Duration textTimeout, Duration imageTimeout) {
		this(base, null, null, textTimeout, imageTimeout);
	}

	/** Creates a {@link HttpEmbedder} with a key, a pinned certificate and other time limits (a test). */
	HttpEmbedder(URI base, String key, byte[] certSha256, Duration textTimeout, Duration imageTimeout) {
		_base = base.toString().endsWith("/") ? base : URI.create(base + "/");
		_key = key == null || key.isEmpty() ? null : key;
		_textTimeout = textTimeout;
		_imageTimeout = imageTimeout;
		HttpClient.Builder http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
			.version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER);
		if (certSha256 != null) {
			http.sslContext(pinned(certSha256));
		}
		_http = http.build();
	}

	/**
	 * Logs every change of what {@link #unavailability()} says, for an administrator who reads the
	 * server's log: what a service of its own is used with; the {@link ManagedEmbedder} logs its child
	 * itself.
	 */
	public HttpEmbedder reportChanges() {
		_report = true;
		return this;
	}

	/**
	 * Marks the service as one on a machine of its own (<code>VALBUM_EMBEDDER_URL</code>), see issue
	 * #311: the content search then runs beside the server's own work, as many requests at once as
	 * the service embeds, see {@link #lane()}.
	 */
	public HttpEmbedder separate() {
		_separate = true;
		return this;
	}

	/**
	 * As many as the service embeds at once (the <code>parallel</code> of its last health, 1 before
	 * it answered or where it names none) for a service on a machine of its own, see
	 * {@link #separate()}; 0 for one that shares the server's machine.
	 */
	@Override
	public int lane() {
		return _separate ? _gate.max() : 0;
	}

	/** Whether requests carry a key. */
	public boolean hasKey() {
		return _key != null;
	}

	/** The address of the service. */
	public URI getBase() {
		return _base;
	}

	@Override
	public float[] embedText(String text, TextKind kind) throws IOException {
		return embed(kind.apply(text), "text", _textTimeout);
	}

	@Override
	public float[] embedDocument(String title, String text) throws IOException {
		return background(Embedder.document(title, text), "text", _textTimeout);
	}

	@Override
	public float[] embedImage(byte[] image) throws IOException {
		String uri = "data:" + mimeType(image) + ";base64," + Base64.getEncoder().encodeToString(image);
		return background(uri, "image", _imageTimeout);
	}

	/**
	 * A request of the background (a photo's picture or text): to a service of its own, one of at
	 * most {@link #lane()} in flight, waiting for its turn before its time limit starts.
	 */
	private float[] background(String input, String modality, Duration timeout) throws IOException {
		if (!_separate) {
			return embed(input, modality, timeout);
		}
		try {
			_gate.acquire();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new EmbedderUnavailableException("interrupted while waiting for the embedder service", ex);
		}
		try {
			return embed(input, modality, timeout);
		} finally {
			_gate.release();
		}
	}

	/** How many background requests are in flight now; for the tests. */
	int inFlight() {
		return _gate.used();
	}

	/**
	 * A counting semaphore whose number of permits follows the service, see
	 * {@link HttpEmbedder#lane()}.
	 */
	private static final class Gate {

		private int _max = 1;

		private int _used;

		synchronized int max() {
			return _max;
		}

		synchronized int used() {
			return _used;
		}

		synchronized void setMax(int max) {
			_max = Math.max(1, max);
			notifyAll();
		}

		synchronized void acquire() throws InterruptedException {
			while (_used >= _max) {
				wait();
			}
			_used++;
		}

		synchronized void release() {
			_used--;
			notifyAll();
		}
	}

	/** The media type of an encoded picture by its first bytes; the service reads the bytes anyway. */
	static String mimeType(byte[] image) {
		if (image.length > 3 && (image[0] & 0xFF) == 0x89 && image[1] == 'P' && image[2] == 'N' && image[3] == 'G') {
			return "image/png";
		}
		if (image.length > 11 && image[0] == 'R' && image[1] == 'I' && image[8] == 'W' && image[9] == 'E') {
			return "image/webp";
		}
		return "image/jpeg";
	}

	@Override
	public String stamp() {
		String result = _stamp;
		if (result == null) {
			try {
				result = stampOf(health());
			} catch (IOException ex) {
				return null;
			}
		}
		return result;
	}

	private String stampOf(Health health) {
		if (health.isReady() && incompatibility(health) == null) {
			_stamp = ContentModel.stamp(health.revision(), health.precision(), DIMENSION);
		}
		return _stamp;
	}

	/**
	 * Why vectors of the service cannot be told apart from those of another model, <code>null</code>
	 * where they can: it names its model, and preprocesses as this server's
	 * {@link ContentModel#PREPROCESSING} says.
	 */
	private String incompatibility(Health health) {
		if (health.revision() == null || health.precision() == null) {
			return "the embedder service at " + _base + " does not name the revision and precision of its model";
		}
		if (health.preprocessing() != ContentModel.PREPROCESSING) {
			return "the embedder service at " + _base + " preprocesses by version "
				+ (health.preprocessing() < 0 ? "(none named)" : health.preprocessing()) + ", this server by version "
				+ ContentModel.PREPROCESSING + ": its vectors would be stamped wrongly; install the embedder of the "
				+ "same VAlbum release as this server";
		}
		return null;
	}

	@Override
	public String unavailability() {
		return note(problem());
	}

	private String problem() {
		try {
			Health health = health();
			if (health.isReady()) {
				return incompatibility(health);
			}
			return describe(health);
		} catch (EmbedderUnavailableException ex) {
			return ex.getMessage();
		} catch (IOException ex) {
			return "the embedder service at " + _base + " answers nonsense: " + ex.getMessage();
		}
	}

	/** Logs a change of the availability, where {@link #reportChanges()} asks for it. */
	private String note(String problem) {
		if (_report) {
			String text = Objects.requireNonNullElse(problem, "");
			boolean changed;
			synchronized (this) {
				changed = !text.equals(_reported);
				_reported = text;
			}
			if (changed) {
				if (problem == null) {
					LOG.info("Content search: the embedder service at " + _base + " is ready (" + _stamp + ").");
				} else {
					LOG.warning("Content search: " + problem);
				}
			}
		}
		return problem;
	}

	private String describe(Health health) {
		if (health.isFailed()) {
			return "the embedder service at " + _base + " cannot load the model: " + health.error();
		}
		return "the embedder service at " + _base + " is " + health.status() + " the model"
			+ (health.detail() == null ? "" : " (" + health.detail() + ")");
	}

	@Override
	public boolean awaitAvailable(Duration timeout) throws InterruptedException {
		long end = System.nanoTime() + timeout.toNanos();
		while (true) {
			try {
				Health health = health();
				if (health.isReady()) {
					// A service that is not of this server's preprocessing will not become one.
					return incompatibility(health) == null;
				}
				if (health.isFailed()) {
					return false;
				}
			} catch (IOException ex) {
				// Not there (yet): asked again below.
			}
			long left = end - System.nanoTime();
			if (left <= 0) {
				return false;
			}
			Thread.sleep(Math.min(1000, Math.max(1, left / 1_000_000)));
		}
	}

	/** Whether the last health check said the service failed to load its model for good, or never fits. */
	boolean lastHealthFailed() {
		Health health = _lastHealth;
		return health != null && (health.isFailed() || health.isReady() && incompatibility(health) != null);
	}

	/**
	 * What the service says about itself.
	 *
	 * @throws EmbedderUnavailableException
	 *         Where it does not answer.
	 */
	public Health health() throws IOException {
		HttpRequest request = authorised(HttpRequest.newBuilder(_base.resolve("health"))).timeout(HEALTH_TIMEOUT).GET()
			.build();
		String body = send(request, "health");
		String status = null;
		String model = null;
		String revision = null;
		String precision = null;
		String error = null;
		String detail = null;
		int dimension = 0;
		int preprocessing = -1;
		int parallel = 1;
		try (JsonReader json = new JsonReader(new ReaderAdapter(new StringReader(body)))) {
			json.beginObject();
			while (json.hasNext()) {
				switch (json.nextName()) {
					case "status" -> status = json.nextString();
					case "model" -> model = json.nextString();
					case "revision" -> revision = json.nextString();
					case "precision" -> precision = json.nextString();
					case "dimension" -> dimension = json.nextInt();
					case "error" -> error = json.nextString();
					case "detail" -> detail = json.nextString();
					case "preprocessing" -> preprocessing = json.nextInt();
					case "parallel" -> parallel = Math.max(1, json.nextInt());
					default -> json.skipValue();
				}
			}
			json.endObject();
		} catch (RuntimeException ex) {
			throw new IOException("Cannot read the health of the embedder service: " + ex.getMessage(), ex);
		}
		if (status == null) {
			throw new IOException("The embedder service names no status.");
		}
		Health result = new Health(status, model, revision, precision, dimension, error, preprocessing, detail,
			parallel);
		_lastHealth = result;
		_gate.setMax(parallel);
		stampOf(result);
		return result;
	}

	private float[] embed(String input, String modality, Duration timeout) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			json.beginObject();
			json.name("input").beginArray().value(input).endArray();
			json.name("modality").value(modality);
			json.name("dimensions").value(DIMENSION);
			json.name("encoding_format").value("base64");
			json.endObject();
		}
		HttpRequest request = authorised(HttpRequest.newBuilder(_base.resolve("v1/embeddings"))).timeout(timeout)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(buffer.toString(), StandardCharsets.UTF_8)).build();
		String body;
		try {
			body = send(request, modality);
		} catch (EmbedderUnavailableException ex) {
			note(ex.getMessage());
			throw ex;
		}
		Answer answer = answer(body);
		checkModel(answer);
		return answer.vector();
	}

	/** Refuses a vector of another model than the one {@link #stamp()} answered. */
	private void checkModel(Answer answer) throws EmbedderUnavailableException {
		if (answer.revision() == null || answer.precision() == null || answer.preprocessing() < 0) {
			throw new EmbedderUnavailableException(note("the embedder service at " + _base
				+ " does not name the model of its vectors; install the embedder of the same VAlbum release as "
				+ "this server"));
		}
		if (answer.preprocessing() != ContentModel.PREPROCESSING) {
			_stamp = null;
			throw new EmbedderUnavailableException(note("the embedder service at " + _base
				+ " preprocesses by version " + answer.preprocessing() + ", this server by version "
				+ ContentModel.PREPROCESSING + "; install the embedder of the same VAlbum release as this server"));
		}
		String stamp = ContentModel.stamp(answer.revision(), answer.precision(), DIMENSION);
		String known = stamp();
		if (!stamp.equals(known)) {
			// The service was changed (another precision, another release) since its stamp was asked.
			_stamp = null;
			throw new EmbedderUnavailableException(note("the embedder service at " + _base + " now embeds with "
				+ stamp + ", not " + known + "; the vector is not used, vectors of different models are never mixed"));
		}
	}

	private HttpRequest.Builder authorised(HttpRequest.Builder request) {
		if (_key != null) {
			request.header("Authorization", "Bearer " + _key);
		}
		return request;
	}

	/** The body of a successful answer; every failure as the exception its kind asks for. */
	private String send(HttpRequest request, String what) throws IOException {
		HttpResponse<String> response;
		try {
			response = _http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (ConnectException | HttpConnectTimeoutException ex) {
			throw new EmbedderUnavailableException("the embedder service at " + _base + " does not answer", ex);
		} catch (HttpTimeoutException ex) {
			throw new EmbedderUnavailableException("the embedder service at " + _base + " did not answer within "
				+ request.timeout().map(Duration::toString).orElse("its time") + " (" + what + ")", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new EmbedderUnavailableException("interrupted while waiting for the embedder service", ex);
		} catch (SSLException ex) {
			throw new EmbedderUnavailableException("the embedder service at " + _base + " fails the TLS handshake: "
				+ reason(ex) + (ex instanceof SSLHandshakeException
					? " (a self-signed certificate is accepted by its SHA-256 fingerprint, " + EmbedderSettings.CERT_SHA256
						+ ")"
					: ""),
				ex);
		} catch (IOException ex) {
			// A connection reset by a service that just ended: it is not there.
			throw new EmbedderUnavailableException("the embedder service at " + _base + " is not reachable: "
				+ ex.getMessage(), ex);
		}
		int status = response.statusCode();
		if (status == 200) {
			return response.body();
		}
		String message = errorMessage(response.body());
		if (status == 401) {
			throw new EmbedderUnavailableException("the embedder service at " + _base + " refuses this server: " + message
				+ (_key == null ? " (" + EmbedderSettings.KEY + " is not set here)" : ""));
		}
		if (status == 503) {
			throw new EmbedderUnavailableException("the embedder service at " + _base + " cannot embed now: " + message);
		}
		throw new IOException("The embedder service refused the " + what + " (HTTP " + status + "): " + message);
	}

	private static String errorMessage(String body) {
		try (JsonReader json = new JsonReader(new ReaderAdapter(new StringReader(body)))) {
			json.beginObject();
			while (json.hasNext()) {
				if ("error".equals(json.nextName()) && json.peek() == JsonToken.BEGIN_OBJECT) {
					json.beginObject();
					while (json.hasNext()) {
						if ("message".equals(json.nextName())) {
							return json.nextString();
						}
						json.skipValue();
					}
					json.endObject();
				} else {
					json.skipValue();
				}
			}
		} catch (IOException | RuntimeException ex) {
			// No error object: the body as it is.
		}
		return body.length() > 200 ? body.substring(0, 200) + "..." : body;
	}

	/** An embeddings answer: its one vector, and the model it names (<code>null</code>, -1 where it names none). */
	record Answer(float[] vector, String revision, String precision, int preprocessing) {
		// A tuple.
	}

	/** The one vector of an answer, checked. */
	static float[] vector(String body) throws IOException {
		return answer(body).vector();
	}

	/** The answer to an embeddings request, its vector checked. */
	static Answer answer(String body) throws IOException {
		float[] result = null;
		int count = 0;
		String revision = null;
		String precision = null;
		int preprocessing = -1;
		try (JsonReader json = new JsonReader(new ReaderAdapter(new StringReader(body)))) {
			json.beginObject();
			while (json.hasNext()) {
				String name = json.nextName();
				if ("revision".equals(name)) {
					revision = json.nextString();
					continue;
				}
				if ("precision".equals(name)) {
					precision = json.nextString();
					continue;
				}
				if ("preprocessing".equals(name)) {
					preprocessing = json.nextInt();
					continue;
				}
				if (!"data".equals(name)) {
					json.skipValue();
					continue;
				}
				json.beginArray();
				while (json.hasNext()) {
					json.beginObject();
					while (json.hasNext()) {
						if ("embedding".equals(json.nextName())) {
							result = json.peek() == JsonToken.STRING ? decode(json.nextString()) : readArray(json);
							count++;
						} else {
							json.skipValue();
						}
					}
					json.endObject();
				}
				json.endArray();
			}
			json.endObject();
		} catch (RuntimeException ex) {
			throw new IOException("Cannot read the answer of the embedder service: " + ex.getMessage(), ex);
		}
		if (count != 1) {
			throw new IOException("The embedder service answered " + count + " vectors for one input.");
		}
		check(result);
		return new Answer(result, revision, precision, preprocessing);
	}

	private static float[] readArray(JsonReader json) throws IOException {
		float[] values = new float[DIMENSION];
		int n = 0;
		json.beginArray();
		while (json.hasNext()) {
			double value = json.nextDouble();
			if (n < values.length) {
				values[n] = (float) value;
			}
			n++;
		}
		json.endArray();
		if (n != DIMENSION) {
			throw new IOException("The embedder service answered a vector of " + n + " numbers, not " + DIMENSION + ".");
		}
		return values;
	}

	private static float[] decode(String base64) throws IOException {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(base64);
		} catch (IllegalArgumentException ex) {
			throw new IOException("The embedder service answered no base64: " + ex.getMessage());
		}
		if (bytes.length != DIMENSION * Float.BYTES) {
			throw new IOException("The embedder service answered a vector of " + bytes.length / Float.BYTES
				+ " numbers, not " + DIMENSION + ".");
		}
		float[] result = new float[DIMENSION];
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(result);
		return result;
	}

	/** A vector is finite and of length 1: anything else is not a vector of this model. */
	private static void check(float[] vector) throws IOException {
		double sum = 0;
		for (float value : vector) {
			if (!Float.isFinite(value)) {
				throw new IOException("The embedder service answered a vector with " + value + " in it.");
			}
			sum += (double) value * value;
		}
		if (Math.abs(Math.sqrt(sum) - 1) > 1e-3) {
			throw new IOException("The embedder service answered a vector of length " + Math.sqrt(sum) + ", not 1.");
		}
	}

	private static String reason(Throwable ex) {
		String message = ex.getMessage();
		return message == null || message.isEmpty() ? ex.getClass().getSimpleName() : message;
	}

	/** The SHA-256 of a certificate's DER encoding, the fingerprint <code>openssl x509 -fingerprint -sha256</code> shows. */
	static byte[] fingerprint(X509Certificate certificate) throws CertificateException {
		try {
			return MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
		} catch (NoSuchAlgorithmException ex) {
			throw new CertificateException(ex);
		}
	}

	/** A TLS context that trusts the one certificate of the given SHA-256 fingerprint, and nothing else. */
	private static SSLContext pinned(byte[] sha256) {
		try {
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, new TrustManager[] { new PinnedTrustManager(sha256.clone()) }, null);
			return context;
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("No TLS in this JVM: " + ex.getMessage(), ex);
		}
	}

	/**
	 * Trusts the server certificate of one fingerprint. The pin replaces the check of the issuer and
	 * of the host name: a self-signed certificate has no issuer to check, and whoever holds its private
	 * key is the service, whatever name it is reached by. An {@link X509ExtendedTrustManager}, so that
	 * the JSSE does not add a host name check of its own.
	 */
	private static final class PinnedTrustManager extends X509ExtendedTrustManager {

		private final byte[] _sha256;

		PinnedTrustManager(byte[] sha256) {
			_sha256 = sha256;
		}

		private void check(X509Certificate[] chain) throws CertificateException {
			if (chain == null || chain.length == 0) {
				throw new CertificateException("the embedder service shows no certificate");
			}
			byte[] actual = fingerprint(chain[0]);
			if (!MessageDigest.isEqual(actual, _sha256)) {
				throw new CertificateException("the certificate of the embedder service has the SHA-256 fingerprint "
					+ HexFormat.ofDelimiter(":").withUpperCase().formatHex(actual) + ", not the one pinned in "
					+ EmbedderSettings.CERT_SHA256);
			}
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			check(chain);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket socket)
				throws CertificateException {
			check(chain);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
				throws CertificateException {
			check(chain);
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			throw new CertificateException("no client is trusted here");
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket socket)
				throws CertificateException {
			throw new CertificateException("no client is trusted here");
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
				throws CertificateException {
			throw new CertificateException("no client is trusted here");
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	}

	@Override
	public void close() {
		_http.shutdownNow();
	}

	@Override
	public String toString() {
		return "embedder service at " + _base;
	}
}
