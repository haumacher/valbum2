/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.auth.UserStore.Login;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides who may read and who may write, see {@link AuthMode}.
 *
 * <p>
 * A caller identifies itself with an <code>Authorization: Bearer &lt;token&gt;</code> header
 * carrying a token this server issued during sign-in, see {@link #pair(PairRequest)}. Everything
 * the servlet needs to know about a request is condensed into a {@link Caller}: who it is, what it
 * may do, and which folder its paths are resolved against, see {@link Caller#getSpace()}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AuthService {

	private static final Logger LOG = Logger.getLogger(AuthService.class.getName());

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

	/**
	 * The message an anonymous caller is refused with once the library belongs to its users.
	 *
	 * <p>
	 * Before the library is migrated the base folder <em>is</em> the owner's library and anonymous
	 * reads stay open in mode {@link AuthMode#WRITES}. Afterwards the base folder holds nothing but
	 * user spaces, and an anonymous caller has no space to look at.
	 * </p>
	 */
	public static final String LIBRARY_REFUSED =
		"This library belongs to its users. Sign in on this device to see your photos, "
			+ "or open a share link you were given.";

	/** The message a caller is refused with whose stored user has a role this build does not know. */
	public static final String ROLE_REFUSED =
		"This user has a role this server does not know; the user store needs repair. Known roles: "
			+ "admin, member, guest.";

	private static final String BEARER_PREFIX = "Bearer ";

	/** How a request identified itself, see {@link AuthService#caller(HttpServletRequest)}. */
	public static final class Caller {

		/** A caller that sent no token at all. */
		public static final Caller ANONYMOUS = new Caller(null, null, false, null);

		/** A caller that sent a token this server does not know. */
		public static final Caller INVALID = new Caller(null, null, true, TOKEN_REFUSED);

		private final User _user;

		private final String _deviceName;

		private final boolean _tokenPresented;

		private final String _refusal;

		private Caller(User user, String deviceName, boolean tokenPresented, String refusal) {
			_user = user;
			_deviceName = deviceName;
			_tokenPresented = tokenPresented;
			_refusal = refusal;
		}

		/** A caller whose token is known but who is refused for the given reason. */
		static Caller rejected(String refusal) {
			return new Caller(null, null, true, refusal);
		}

		/** A signed-in caller. */
		static Caller signedIn(User user, String deviceName) {
			return new Caller(user, deviceName, true, null);
		}

		/** The name of the paired device, or the empty string for an unidentified caller. */
		public String getDeviceName() {
			return _deviceName == null ? "" : _deviceName;
		}

		/** Whether this caller is a device paired with this server. */
		public boolean isPaired() {
			return _user != null;
		}

		/** Whether this caller presented a token that this server does not know or accept. */
		public boolean hasInvalidToken() {
			return _tokenPresented && _user == null;
		}

		/** The name of the signed-in user, the empty string for an anonymous caller. */
		public String getUserName() {
			return _user == null ? "" : _user.getName();
		}

		/** The role of the signed-in user, the empty string for an anonymous caller. */
		public String getRole() {
			return _user == null ? "" : _user.getRole();
		}

		/**
		 * The folder below the server's base folder every path of this caller is resolved against.
		 *
		 * <p>
		 * The empty string is the base folder itself: that is an anonymous caller of an unmigrated
		 * library, a server running without authentication, and the owner of a library that was
		 * never migrated.
		 * </p>
		 */
		public String getSpace() {
			return _user == null ? "" : _user.getSpace();
		}

		/** Why this caller is refused, <code>null</code> if it is not refused for its own sake. */
		String getRefusal() {
			return _refusal;
		}
	}

	private final AuthMode _mode;

	private final String _pairingSecret;

	private final Path _basePath;

	private final UserStore _users;

	/**
	 * Creates an {@link AuthService}.
	 *
	 * @param mode
	 *        What requires a paired device.
	 * @param pairingSecret
	 *        The secret a device must present to be paired, <code>null</code> while pairing is
	 *        impossible.
	 * @param basePath
	 *        The root of the served album tree; the user store lives below it and the user spaces
	 *        are folders in it.
	 */
	public AuthService(AuthMode mode, String pairingSecret, Path basePath) {
		_mode = mode;
		_pairingSecret = pairingSecret;
		_basePath = basePath;
		_users = mode == AuthMode.OFF ? null : new UserStore(basePath);
	}

	/** An {@link AuthService} serving every request, as before issue #28. */
	public static AuthService disabled() {
		return new AuthService(AuthMode.OFF, null, null);
	}

	/** What requires a paired device. */
	public AuthMode getMode() {
		return _mode;
	}

	/** The users of this server, <code>null</code> while {@link AuthMode#OFF}. */
	public UserStore getUsers() {
		return _users;
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
		Login login = _users.lookup(token);
		if (login == null) {
			return Caller.INVALID;
		}
		if (!Roles.isKnown(login.getUser().getRole())) {
			LOG.warning("Refusing the user '" + login.getUser().getName() + "' with the unknown role '"
				+ login.getUser().getRole() + "'.");
			return Caller.rejected(ROLE_REFUSED);
		}
		return Caller.signedIn(login.getUser(), login.getDevice().getName());
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

	/**
	 * Whether the library was migrated into user spaces, see
	 * {@link LibraryMigration#migrate(Path, String)}.
	 *
	 * <p>
	 * While it was not, the base folder is the owner's library and an anonymous caller may look at
	 * it exactly as before issue #45.
	 * </p>
	 */
	public boolean isLibraryMigrated() {
		if (_users == null) {
			return false;
		}
		User owner = _users.getOwner();
		return owner != null && !owner.getSpace().isEmpty();
	}

	/** Whether the given caller may read. */
	public boolean readAllowed(Caller caller) {
		if (caller.hasInvalidToken()) {
			return false;
		}
		if (!caller.isPaired() && isLibraryMigrated()) {
			return false;
		}
		return _mode != AuthMode.ALL || caller.isPaired();
	}

	/** Whether the given caller may write. */
	public boolean writeAllowed(Caller caller) {
		if (caller.hasInvalidToken()) {
			return false;
		}
		if (_mode == AuthMode.OFF) {
			return true;
		}
		if (!caller.isPaired() && isLibraryMigrated()) {
			return false;
		}
		return caller.isPaired();
	}

	/** Why the given caller is refused, ready to be shown to the user. */
	public String refusal(Caller caller, boolean write) {
		String own = caller.getRefusal();
		if (own != null) {
			return own;
		}
		if (!caller.isPaired() && isLibraryMigrated()) {
			return LIBRARY_REFUSED;
		}
		return write ? WRITE_REFUSED : READ_REFUSED;
	}

	/**
	 * The folder every path of the given caller is resolved against.
	 *
	 * <p>
	 * A user's space folder is created when it is first needed, so that a user who has never
	 * stored anything still has a root to look at.
	 * </p>
	 */
	public Path spaceRoot(Caller caller, Path basePath) {
		String space = caller.getSpace();
		if (space.isEmpty()) {
			return basePath;
		}
		Path root = basePath.resolve(space);
		if (!Files.isDirectory(root)) {
			try {
				Files.createDirectories(root);
				LOG.info("Created the space of '" + caller.getUserName() + "': " + root);
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Cannot create the space '" + root + "': " + ex.getMessage());
			}
		}
		return root;
	}

	/** What the given caller is allowed to do, see {@link AuthInfo}. */
	public AuthInfo authInfo(Caller caller) {
		return AuthInfo.create()
			.setMode(_mode.protocolName())
			.setDeviceName(caller.getDeviceName())
			.setWriteAllowed(writeAllowed(caller))
			.setUserName(caller.getUserName())
			.setRole(caller.getRole())
			.setSpace(caller.getSpace());
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
	 * Signs a device in and issues its token.
	 *
	 * <p>
	 * The pairing secret signs in the library owner: an empty {@link PairRequest#getUserName()}
	 * means "the owner" (that is what an app from before issue #45 sends), a non-empty one names
	 * the owner while it has no name yet and must match the owner's name afterwards.
	 * </p>
	 *
	 * @throws PairRefused
	 *         If the server does not pair at all, the secret does not match, or the request names
	 *         somebody other than the library owner.
	 */
	public PairResponse pair(PairRequest request) throws PairRefused, IOException {
		if (_mode == AuthMode.OFF) {
			throw new PairRefused(HttpServletResponse.SC_FORBIDDEN, PAIRING_DISABLED);
		}
		if (_pairingSecret == null || _pairingSecret.isEmpty() || !matches(request.getSecret(), _pairingSecret)) {
			throw new PairRefused(HttpServletResponse.SC_FORBIDDEN, SECRET_REFUSED);
		}

		User owner;
		synchronized (_users) {
			try {
				owner = _users.nameOwner(request.getUserName());
			} catch (IllegalArgumentException ex) {
				throw new PairRefused(HttpServletResponse.SC_UNAUTHORIZED, ex.getMessage());
			}
		}

		String token = _users.addDevice(owner, request.getDeviceName());
		String deviceName = owner.getDevices().get(owner.getDevices().size() - 1).getName();
		if (!owner.getSpace().isEmpty() && _basePath != null) {
			spaceRoot(Caller.signedIn(owner, deviceName), _basePath);
		}
		return PairResponse.create()
			.setToken(token)
			.setDeviceName(deviceName)
			.setUserName(owner.getName())
			.setRole(owner.getRole())
			.setSpace(owner.getSpace());
	}

	private static boolean matches(String presented, String expected) {
		if (presented == null) {
			return false;
		}
		return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
			expected.getBytes(StandardCharsets.UTF_8));
	}
}
