/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLContext;

/**
 * An embedder service in this process that speaks the protocol of
 * <code>image-server/src/embedder/embedder.py</code> without a model, see issue #257: it answers a
 * vector that says what it was given, and records every request.
 */
@SuppressWarnings("javadoc")
final class FakeEmbedderService implements AutoCloseable {

	/** One embeddings request as received. */
	record Request(String input, String modality, int dimensions, String encoding, String authorization) {
		// The fields the client sends.
	}

	private final HttpServer _server;

	final List<Request> _requests = Collections.synchronizedList(new ArrayList<>());

	volatile String _status = "ready";

	volatile String _error;

	/** A status to answer embeddings with instead of a vector, 0 for a vector. */
	volatile int _failWith;

	volatile String _failMessage = "broken";

	/** The width of the vectors answered; the client must refuse another than it asked for. */
	volatile int _width = Embedder.DIMENSION;

	/** Whether the vectors answered are of length 1. */
	volatile boolean _normalised = true;

	/** Answers vectors as JSON arrays instead of base64. */
	volatile boolean _floats;

	/** How long an answer takes. */
	volatile long _delayMillis;

	/** The key every request must carry, <code>null</code> for none. */
	volatile String _key;

	/** The preprocessing the service names, -1 for none (an older service). */
	volatile int _preprocessing = ContentModel.PREPROCESSING;

	/** The precision the health check names. */
	volatile String _precision = "int8";

	/** The precision the vectors are said to be of, <code>null</code> for {@link #_precision}. */
	volatile String _answerPrecision;

	/** The Authorization headers of the health checks. */
	final List<String> _healthAuthorizations = Collections.synchronizedList(new ArrayList<>());

	FakeEmbedderService() throws IOException {
		this(null);
	}

	/** A service speaking HTTPS with the given context, plain HTTP for <code>null</code>. */
	FakeEmbedderService(SSLContext tls) throws IOException {
		InetSocketAddress address = new InetSocketAddress("127.0.0.1", 0);
		if (tls == null) {
			_server = HttpServer.create(address, 0);
		} else {
			HttpsServer https = HttpsServer.create(address, 0);
			https.setHttpsConfigurator(new HttpsConfigurator(tls));
			_server = https;
		}
		_server.createContext("/health", this::health);
		_server.createContext("/v1/embeddings", this::embeddings);
		_server.start();
	}

	URI url() {
		return URI.create((_server instanceof HttpsServer ? "https" : "http") + "://127.0.0.1:" + port() + "/");
	}

	/** Answers 401 where the request does not carry the key, as the service does. */
	private boolean refused(HttpExchange exchange) throws IOException {
		if (_key == null) {
			return false;
		}
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header == null) {
			answer(exchange, 401, "{\"error\":{\"message\":\"this embedder service needs a key\",\"type\":\"unauthorized\"}}");
			return true;
		}
		if (!header.equals("Bearer " + _key)) {
			answer(exchange, 401, "{\"error\":{\"message\":\"the key is wrong\",\"type\":\"unauthorized\"}}");
			return true;
		}
		return false;
	}

	private String model(String precision) {
		return "\"revision\":\"daa72c51243991dfcaf9f9137d2c573d8f7790c0\",\"precision\":\"" + precision + "\""
			+ (_preprocessing < 0 ? "" : ",\"preprocessing\":" + _preprocessing);
	}

	int port() {
		return _server.getAddress().getPort();
	}

	private void health(HttpExchange exchange) throws IOException {
		_healthAuthorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
		if (refused(exchange)) {
			return;
		}
		String body = "{\"status\":\"" + _status + "\",\"model\":\"google/embeddinggemma-2\","
			+ "\"repository\":\"onnx-community/embeddinggemma-2-ONNX\"," + model(_precision) + ",\"dimension\":768"
			+ (_error == null ? "" : ",\"error\":\"" + _error + "\"") + "}";
		answer(exchange, 200, body);
	}

	private void embeddings(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Request request = parse(body, exchange.getRequestHeaders().getFirst("Authorization"));
		_requests.add(request);
		if (refused(exchange)) {
			return;
		}
		if (_delayMillis > 0) {
			try {
				Thread.sleep(_delayMillis);
			} catch (InterruptedException ex) {
				return;
			}
		}
		if (_failWith != 0) {
			answer(exchange, _failWith, "{\"error\":{\"message\":\"" + _failMessage + "\",\"type\":\"x\"}}");
			return;
		}
		if (!"ready".equals(_status)) {
			answer(exchange, 503, "{\"error\":{\"message\":\"the model is " + _status + "\",\"type\":\"unavailable\"}}");
			return;
		}
		float[] vector = new float[_width];
		vector[0] = 3;
		vector[1] = 4;
		double scale = _normalised ? 5 : 1;
		StringBuilder floats = new StringBuilder("[");
		ByteBuffer bytes = ByteBuffer.allocate(_width * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int n = 0; n < _width; n++) {
			float value = (float) (vector[n] / scale);
			bytes.putFloat(value);
			floats.append(n == 0 ? "" : ",").append(value);
		}
		floats.append("]");
		String embedding = _floats ? floats.toString()
			: "\"" + Base64.getEncoder().encodeToString(bytes.array()) + "\"";
		String precision = _answerPrecision == null ? _precision : _answerPrecision;
		answer(exchange, 200, "{\"object\":\"list\",\"model\":\"google/embeddinggemma-2\"," + model(precision)
			+ ",\"data\":[{\"object\":"
			+ "\"embedding\",\"index\":0,\"embedding\":" + embedding + "}],\"usage\":{\"prompt_tokens\":3}}");
	}

	private static Request parse(String body, String authorization) throws IOException {
		String input = null;
		String modality = "text";
		String encoding = "float";
		int dimensions = 768;
		try (JsonReader json = new JsonReader(new ReaderAdapter(new StringReader(body)))) {
			json.beginObject();
			while (json.hasNext()) {
				switch (json.nextName()) {
					case "input" -> {
						json.beginArray();
						input = json.nextString();
						json.endArray();
					}
					case "modality" -> modality = json.nextString();
					case "dimensions" -> dimensions = json.nextInt();
					case "encoding_format" -> encoding = json.nextString();
					default -> json.skipValue();
				}
			}
			json.endObject();
		}
		return new Request(input, modality, dimensions, encoding, authorization);
	}

	private static void answer(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	@Override
	public void close() {
		_server.stop(0);
	}
}
