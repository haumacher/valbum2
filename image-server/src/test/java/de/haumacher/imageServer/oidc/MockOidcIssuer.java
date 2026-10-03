/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small OpenID Connect provider on the loopback interface, for the tests of issue #200.
 *
 * <p>
 * It serves what a real provider serves to a server &mdash; the discovery document, its signing
 * keys and the token endpoint, which checks the client's secret, the redirect address and the PKCE
 * verifier and answers an RS256-signed ID token &mdash; and stands in for the browser's visit to
 * the authorization endpoint with {@link #authorize(String)}, which checks the request and answers
 * the parameters the provider would send the browser back with.
 * </p>
 */
@SuppressWarnings("javadoc")
public final class MockOidcIssuer implements AutoCloseable {

	public static final String CLIENT_ID = "valbum-test-client";

	public static final String SECRET = "the-client-secret-4711";

	/** Who signs in next. */
	public static final class Identity {

		final String _email;

		final Object _verified;

		final String _name;

		public Identity(String email, Object verified, String name) {
			_email = email;
			_verified = verified;
			_name = name;
		}
	}

	private static final class Grant {

		final String _nonce;

		final String _challenge;

		final String _redirectUri;

		final Identity _identity;

		Grant(String nonce, String challenge, String redirectUri, Identity identity) {
			_nonce = nonce;
			_challenge = challenge;
			_redirectUri = redirectUri;
			_identity = identity;
		}
	}

	private final HttpServer _server;

	private final RSAKey _key;

	private final RSAKey _foreignKey;

	private final Map<String, Grant> _codes = new ConcurrentHashMap<>();

	private final AtomicInteger _tokenRequests = new AtomicInteger();

	private volatile Identity _next = new Identity("vera@web.de", Boolean.TRUE, "Vera");

	private volatile boolean _signWithForeignKey;

	private volatile String _audience = CLIENT_ID;

	private volatile String _nonce;

	public MockOidcIssuer() throws Exception {
		_key = new RSAKeyGenerator(2048).keyID("k1").generate();
		_foreignKey = new RSAKeyGenerator(2048).keyID("k1").generate();
		_server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		_server.createContext("/.well-known/openid-configuration", this::discovery);
		_server.createContext("/jwks", this::jwks);
		_server.createContext("/token", this::token);
		_server.start();
	}

	/** The issuer, <code>http://127.0.0.1:&lt;port&gt;</code>. */
	public String issuer() {
		return "http://127.0.0.1:" + _server.getAddress().getPort();
	}

	public String discoveryUrl() {
		return issuer() + "/.well-known/openid-configuration";
	}

	/** The provider as the server is configured with it. */
	public OidcProvider provider(String label) {
		return new OidcProvider("google", label, CLIENT_ID, SECRET, discoveryUrl());
	}

	public void next(String email, Object verified, String name) {
		_next = new Identity(email, verified, name);
	}

	public void signWithForeignKey(boolean value) {
		_signWithForeignKey = value;
	}

	public void audience(String value) {
		_audience = value;
	}

	/** A nonce to put into the ID token instead of the one the request carried, <code>null</code> for that one. */
	public void nonce(String value) {
		_nonce = value;
	}

	public int tokenRequests() {
		return _tokenRequests.get();
	}

	/**
	 * The browser's visit to the authorization endpoint: checks the request a server sent the
	 * browser with and answers the parameters the browser comes back to the callback with.
	 */
	public Map<String, String[]> authorize(String url) {
		URI uri = URI.create(url);
		if (!uri.toString().startsWith(issuer() + "/authorize?")) {
			throw new AssertionError("Not this provider's authorization endpoint: " + url);
		}
		Map<String, String> query = parse(uri.getRawQuery());
		expect("client_id", CLIENT_ID, query);
		expect("response_type", "code", query);
		expect("code_challenge_method", "S256", query);
		String scope = query.get("scope");
		if (scope == null || !(" " + scope + " ").contains(" openid ") || !scope.contains("email")
			|| !scope.contains("profile")) {
			throw new AssertionError("Scopes: " + scope);
		}
		for (String needed : new String[] { "state", "nonce", "code_challenge", "redirect_uri" }) {
			if (query.get(needed) == null) {
				throw new AssertionError("No " + needed + " in " + url);
			}
		}
		String code = UUID.randomUUID().toString();
		_codes.put(code, new Grant(query.get("nonce"), query.get("code_challenge"), query.get("redirect_uri"), _next));
		Map<String, String[]> back = new LinkedHashMap<>();
		back.put("code", new String[] { code });
		back.put("state", new String[] { query.get("state") });
		return back;
	}

	/** The redirect address the given authorization address names. */
	public static String redirectUri(String url) {
		return parse(URI.create(url).getRawQuery()).get("redirect_uri");
	}

	/** The state the given authorization address carries. */
	public static String state(String url) {
		return parse(URI.create(url).getRawQuery()).get("state");
	}

	private static void expect(String name, String value, Map<String, String> query) {
		if (!value.equals(query.get(name))) {
			throw new AssertionError(name + ": expected " + value + ", got " + query.get(name));
		}
	}

	private void discovery(HttpExchange exchange) throws IOException {
		String issuer = issuer();
		String json = "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer + "/authorize\","
			+ "\"token_endpoint\":\"" + issuer + "/token\",\"jwks_uri\":\"" + issuer + "/jwks\","
			+ "\"response_types_supported\":[\"code\"],\"subject_types_supported\":[\"public\"],"
			+ "\"id_token_signing_alg_values_supported\":[\"RS256\"],"
			+ "\"token_endpoint_auth_methods_supported\":[\"client_secret_basic\"],"
			+ "\"code_challenge_methods_supported\":[\"S256\"],"
			+ "\"scopes_supported\":[\"openid\",\"email\",\"profile\"]}";
		answer(exchange, 200, json);
	}

	private void jwks(HttpExchange exchange) throws IOException {
		answer(exchange, 200, new JWKSet(_key.toPublicJWK()).toString());
	}

	private void token(HttpExchange exchange) throws IOException {
		_tokenRequests.incrementAndGet();
		try {
			String expected = "Basic " + Base64.getEncoder()
				.encodeToString((CLIENT_ID + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
			if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
				answer(exchange, 401, "{\"error\":\"invalid_client\"}");
				return;
			}
			Map<String, String> form = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			Grant grant = form.get("code") == null ? null : _codes.remove(form.get("code"));
			if (grant == null || !"authorization_code".equals(form.get("grant_type"))
				|| !grant._redirectUri.equals(form.get("redirect_uri"))) {
				answer(exchange, 400, "{\"error\":\"invalid_grant\"}");
				return;
			}
			String verifier = form.get("code_verifier");
			String challenge = verifier == null ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
			if (!grant._challenge.equals(challenge)) {
				answer(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"PKCE\"}");
				return;
			}
			Date now = new Date();
			JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.issuer(issuer())
				.subject("sub-" + grant._identity._email)
				.audience(_audience)
				.issueTime(now)
				.expirationTime(new Date(now.getTime() + 600_000))
				.claim("nonce", _nonce == null ? grant._nonce : _nonce);
			if (grant._identity._email != null) {
				claims.claim("email", grant._identity._email);
			}
			if (grant._identity._verified != null) {
				claims.claim("email_verified", grant._identity._verified);
			}
			if (grant._identity._name != null) {
				claims.claim("name", grant._identity._name);
			}
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims.build());
			jwt.sign(new RSASSASigner(_signWithForeignKey ? _foreignKey : _key));
			answer(exchange, 200, "{\"access_token\":\"at-" + UUID.randomUUID() + "\",\"token_type\":\"Bearer\","
				+ "\"expires_in\":3600,\"scope\":\"openid email profile\",\"id_token\":\"" + jwt.serialize() + "\"}");
		} catch (Exception ex) {
			answer(exchange, 500, "{\"error\":\"server_error\"}");
		}
	}

	private static void answer(HttpExchange exchange, int status, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static Map<String, String> parse(String query) {
		Map<String, String> result = new HashMap<>();
		if (query == null || query.isEmpty()) {
			return result;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			result.put(name, value);
		}
		return result;
	}

	@Override
	public void close() {
		_server.stop(0);
	}
}
