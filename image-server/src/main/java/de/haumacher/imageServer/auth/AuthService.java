/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Decides who may read and who may write, see {@link AuthMode}.
 *
 * <p>
 * A caller identifies itself with an <code>Authorization: Bearer &lt;token&gt;</code> header
 * carrying a token this server issued during pairing, see {@link #pair(PairRequest)}. Everything
 * the servlet needs to know about a request is condensed into a {@link Caller}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AuthService {

	/** The message an anonymous caller is refused a write with. */
	public static final String WRITE_REFUSED =
		"This server requires a paired device for changes. Pair this device in the server settings.";

	/** The message an anonymous caller is refused a read with. */
	public static final String READ_REFUSED =
		"This server requires a paired device. Pair this device in the server settings.";

	/** The message a caller with an unknown token is refused with. */
	public static final String TOKEN_REFUSED =
		"This device is no longer paired with the server. Pair it again in the server settings.";

	/** The message a wrong pairing secret is refused with. */
	public static final String SECRET_REFUSED = "Wrong pairing secret.";

	/** The message pairing is refused with while the server runs without authentication. */
	public static final String PAIRING_DISABLED =
		"This server runs without authentication; there is nothing to pair with.";

	private static final String BEARER_PREFIX = "Bearer ";

	/** How a request identified itself, see {@link AuthService#caller(HttpServletRequest)}. */
	public static final class Caller {

		/** A caller that sent no token at all. */
		public static final Caller ANONYMOUS = new Caller(null, false);

		/** A caller that sent a token this server does not know. */
		public static final Caller INVALID = new Caller(null, true);

		private final String _deviceName;

		private final boolean _tokenPresented;

		private Caller(String deviceName, boolean tokenPresented) {
			_deviceName = deviceName;
			_tokenPresented = tokenPresented;
		}

		/** The name of the paired device, or the empty string for an unidentified caller. */
		public String getDeviceName() {
			return _deviceName == null ? "" : _deviceName;
		}

		/** Whether this caller is a device paired with this server. */
		public boolean isPaired() {
			return _deviceName != null;
		}

		/** Whether this caller presented a token that this server does not know. */
		public boolean hasInvalidToken() {
			return _tokenPresented && _deviceName == null;
		}
	}

	private final AuthMode _mode;

	private final String _pairingSecret;

	private final DeviceStore _devices;

	/**
	 * Creates an {@link AuthService}.
	 *
	 * @param mode
	 *        What requires a paired device.
	 * @param pairingSecret
	 *        The secret a device must present to be paired, <code>null</code> while pairing is
	 *        impossible.
	 * @param basePath
	 *        The root of the served album tree; the device store lives below it.
	 */
	public AuthService(AuthMode mode, String pairingSecret, Path basePath) {
		_mode = mode;
		_pairingSecret = pairingSecret;
		_devices = mode == AuthMode.OFF ? null : new DeviceStore(basePath);
	}

	/** An {@link AuthService} serving every request, as before issue #28. */
	public static AuthService disabled() {
		return new AuthService(AuthMode.OFF, null, null);
	}

	/** What requires a paired device. */
	public AuthMode getMode() {
		return _mode;
	}

	/** The devices paired with this server, <code>null</code> while {@link AuthMode#OFF}. */
	public DeviceStore getDevices() {
		return _devices;
	}

	/** Generates a pairing secret for a server that was not given one. */
	public static String generateSecret() {
		byte[] bytes = new byte[12];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Identifies the sender of the given request. */
	public Caller caller(HttpServletRequest request) {
		if (_mode == AuthMode.OFF) {
			return Caller.ANONYMOUS;
		}
		String token = token(request);
		if (token == null) {
			return Caller.ANONYMOUS;
		}
		String deviceName = _devices.deviceName(token);
		return deviceName == null ? Caller.INVALID : new Caller(deviceName, true);
	}

	/** The bearer token of the given request, <code>null</code> if it carries none. */
	private static String token(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null) {
			return null;
		}
		String trimmed = header.trim();
		if (trimmed.length() <= BEARER_PREFIX.length()
			|| !trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return null;
		}
		String token = trimmed.substring(BEARER_PREFIX.length()).trim();
		return token.isEmpty() ? null : token;
	}

	/** Whether the given caller may read. */
	public boolean readAllowed(Caller caller) {
		if (caller.hasInvalidToken()) {
			return false;
		}
		return _mode != AuthMode.ALL || caller.isPaired();
	}

	/** Whether the given caller may write. */
	public boolean writeAllowed(Caller caller) {
		if (caller.hasInvalidToken()) {
			return false;
		}
		return _mode == AuthMode.OFF || caller.isPaired();
	}

	/** Why the given caller is refused, ready to be shown to the user. */
	public String refusal(Caller caller, boolean write) {
		if (caller.hasInvalidToken()) {
			return TOKEN_REFUSED;
		}
		return write ? WRITE_REFUSED : READ_REFUSED;
	}

	/** What the given caller is allowed to do, see {@link AuthInfo}. */
	public AuthInfo authInfo(Caller caller) {
		return AuthInfo.create()
			.setMode(_mode.protocolName())
			.setDeviceName(caller.getDeviceName())
			.setWriteAllowed(writeAllowed(caller));
	}

	/** Thrown by {@link AuthService#pair(PairRequest)} when the request is not honoured. */
	public static class PairRefused extends Exception {

		private final int _status;

		/** Creates a {@link PairRefused}. */
		public PairRefused(int status, String message) {
			super(message);
			_status = status;
		}

		/** The HTTP status to answer with. */
		public int getStatus() {
			return _status;
		}
	}

	/**
	 * Issues a token for the requesting device.
	 *
	 * @throws PairRefused
	 *         If the server does not pair at all, or the secret does not match.
	 */
	public PairResponse pair(PairRequest request) throws PairRefused, IOException {
		if (_mode == AuthMode.OFF) {
			throw new PairRefused(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN, PAIRING_DISABLED);
		}
		if (_pairingSecret == null || _pairingSecret.isEmpty() || !matches(request.getSecret(), _pairingSecret)) {
			throw new PairRefused(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN, SECRET_REFUSED);
		}
		String token = _devices.pair(request.getDeviceName());
		return PairResponse.create()
			.setToken(token)
			.setDeviceName(_devices.getDevices().get(_devices.getDevices().size() - 1).getName());
	}

	private static boolean matches(String presented, String expected) {
		if (presented == null) {
			return false;
		}
		return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
			expected.getBytes(StandardCharsets.UTF_8));
	}
}
