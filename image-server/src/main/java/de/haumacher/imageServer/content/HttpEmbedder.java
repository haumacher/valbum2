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
import java.time.Duration;
import java.util.Base64;

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
 */
public class HttpEmbedder implements Embedder {

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

	/** What {@link #health()} answers. */
	public record Health(String status, String model, String revision, String precision, int dimension, String error) {

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

	private final HttpClient _http;

	private final Duration _textTimeout;

	private final Duration _imageTimeout;

	private volatile String _stamp;

	private volatile Health _lastHealth;

	/**
	 * Creates a {@link HttpEmbedder} for the service at the given address, e.g.
	 * <code>http://127.0.0.1:8091/</code>.
	 */
	public HttpEmbedder(URI base) {
		this(base, TEXT_TIMEOUT, IMAGE_TIMEOUT);
	}

	/** Creates a {@link HttpEmbedder} with other time limits (a test). */
	HttpEmbedder(URI base, Duration textTimeout, Duration imageTimeout) {
		_base = base.toString().endsWith("/") ? base : URI.create(base + "/");
		_textTimeout = textTimeout;
		_imageTimeout = imageTimeout;
		_http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER).build();
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
		return embed(Embedder.document(title, text), "text", _textTimeout);
	}

	@Override
	public float[] embedImage(byte[] image) throws IOException {
		String uri = "data:" + mimeType(image) + ";base64," + Base64.getEncoder().encodeToString(image);
		return embed(uri, "image", _imageTimeout);
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
		if (health.isReady() && health.revision() != null && health.precision() != null) {
			_stamp = ContentModel.stamp(health.revision(), health.precision(), DIMENSION);
		}
		return _stamp;
	}

	@Override
	public String unavailability() {
		try {
			Health health = health();
			if (health.isReady()) {
				return null;
			}
			return describe(health);
		} catch (EmbedderUnavailableException ex) {
			return ex.getMessage();
		} catch (IOException ex) {
			return "the embedder service at " + _base + " answers nonsense: " + ex.getMessage();
		}
	}

	private String describe(Health health) {
		if (health.isFailed()) {
			return "the embedder service at " + _base + " cannot load the model: " + health.error();
		}
		return "the embedder service at " + _base + " is " + health.status() + " the model";
	}

	@Override
	public boolean awaitAvailable(Duration timeout) throws InterruptedException {
		long end = System.nanoTime() + timeout.toNanos();
		while (true) {
			try {
				Health health = health();
				if (health.isReady()) {
					return true;
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

	/** Whether the last health check said the service failed to load its model for good. */
	boolean lastHealthFailed() {
		Health health = _lastHealth;
		return health != null && health.isFailed();
	}

	/**
	 * What the service says about itself.
	 *
	 * @throws EmbedderUnavailableException
	 *         Where it does not answer.
	 */
	public Health health() throws IOException {
		HttpRequest request = HttpRequest.newBuilder(_base.resolve("health")).timeout(HEALTH_TIMEOUT).GET().build();
		String body = send(request, "health");
		String status = null;
		String model = null;
		String revision = null;
		String precision = null;
		String error = null;
		int dimension = 0;
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
		Health result = new Health(status, model, revision, precision, dimension, error);
		_lastHealth = result;
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
		HttpRequest request = HttpRequest.newBuilder(_base.resolve("v1/embeddings")).timeout(timeout)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(buffer.toString(), StandardCharsets.UTF_8)).build();
		return vector(send(request, modality));
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

	/** The one vector of an answer, checked. */
	static float[] vector(String body) throws IOException {
		float[] result = null;
		int count = 0;
		try (JsonReader json = new JsonReader(new ReaderAdapter(new StringReader(body)))) {
			json.beginObject();
			while (json.hasNext()) {
				if (!"data".equals(json.nextName())) {
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
		return result;
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

	@Override
	public void close() {
		_http.shutdownNow();
	}

	@Override
	public String toString() {
		return "embedder service at " + _base;
	}
}
