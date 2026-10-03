/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Short-lived signed addresses of one file of one video, see issue #185.
 *
 * <p>
 * On the web the app hands a video to an HTML <code>&lt;video&gt;</code> element, which fetches the
 * address <em>itself</em> and cannot send an <code>Authorization</code> header. In a space that shows
 * nothing to anonymous callers every such fetch is refused. So a signed-in device (or a share
 * link) asks — with its bearer — for a signature over exactly one path and one {@link Kind kind}
 * of file, and the element fetches the ordinary address with
 * <code>&amp;{@value #PARAMETER}=&lt;signature&gt;</code> appended.
 * </p>
 *
 * <p>
 * The signature is <code>&lt;subject kind&gt;.&lt;id&gt;.&lt;expiry&gt;.&lt;mac&gt;</code>:
 * </p>
 * <ul>
 * <li>the subject kind is <code>d</code> for a paired device and <code>s</code> for a share link,
 * the id the device's or the link's id in its store — an id, never a token, so the credential of
 * the device never appears in an address, a proxy log, the browser history or a
 * <code>Referer</code>;</li>
 * <li>the expiry is in seconds since the epoch, {@link #LIFETIME} after the signature was
 * issued;</li>
 * <li>the mac is an HMAC-SHA256, base64url without padding, keyed with the secret of the space
 * over {@value #DOMAIN}, the kind, the subject, the expiry and the path of the request, one per
 * line.</li>
 * </ul>
 *
 * <p>
 * The secret is 32 random bytes, made at first use in the space's
 * <code>{@value UserStore#DIRECTORY_NAME}/{@value #FILE_NAME}</code>, never answered, and readable
 * by the server's user alone: written as every store of this package is written — a temporary file,
 * which Java creates owner-only, moved into place — and its permissions set to
 * <code>rw-------</code> explicitly where the file system knows POSIX permissions. Losing it is
 * harmless: a new one is made and every signature in flight is refused, which the app answers by
 * asking again.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class MediaSignatures {

	private static final Logger LOG = Logger.getLogger(MediaSignatures.class.getName());

	/** The query parameter carrying a signature. */
	public static final String PARAMETER = "media";

	/** The file below {@link UserStore#DIRECTORY_NAME} holding the secret of the space. */
	public static final String FILE_NAME = "media-secret";

	/**
	 * How long a signature is good for.
	 *
	 * <p>
	 * Long enough for the range requests a browser keeps making while it plays a long video; the
	 * app asks anew when the player is refused, see issue #185.
	 * </p>
	 */
	public static final Duration LIFETIME = Duration.ofMinutes(10);

	/** The first line of every signed text, so that a mac of this kind can mean nothing else. */
	static final String DOMAIN = "valbum-media-1";

	/** The subject kind of a paired device. */
	public static final String DEVICE = "d";

	/** The subject kind of a share link. */
	public static final String SHARE = "s";

	/**
	 * The subject kind of a session of a personal link, see issue #198.
	 *
	 * <p>
	 * Its id is the link's id and the contact session's id joined by {@link #CONTACT_SEPARATOR}: the
	 * signed address is served as that very session, which is still a link <em>and</em> a person.
	 * </p>
	 */
	public static final String CONTACT = "c";

	/** What joins the link's and the session's id in a {@link #CONTACT} signature; no base64url character. */
	public static final String CONTACT_SEPARATOR = "~";

	private static final String ALGORITHM = "HmacSHA256";

	private static final int SECRET_BYTES = 32;

	/** Which file of a video a signature opens. */
	public enum Kind {

		/** The playable rendition, <code>?type=video</code>, see issue #74. */
		VIDEO("video", "video"),

		/** The teaser, <code>?type=teaser</code>. */
		TEASER("teaser", "teaser"),

		/** The file as it was taken: the plain address without a type. */
		ORIGINAL("original", null);

		private final String _name;

		private final String _type;

		Kind(String name, String type) {
			_name = name;
			_type = type;
		}

		/** How the kind is named in <code>for=</code> and in the signed text. */
		public String getName() {
			return _name;
		}

		/** The <code>type</code> parameter of the address serving it, <code>null</code> for none. */
		public String getType() {
			return _type;
		}

		/** The right the plain request for this file asks for. */
		public String right() {
			return this == ORIGINAL ? Rights.DOWNLOAD : Rights.VIEW;
		}

		/** The kind named <code>name</code> in <code>for=</code>, <code>null</code> for none. */
		public static Kind named(String name) {
			for (Kind kind : values()) {
				if (kind._name.equals(name)) {
					return kind;
				}
			}
			return null;
		}

		/**
		 * The kind a request with the given <code>type</code> parameter asks for, <code>null</code>
		 * where no signature can ever open it — a listing, a description, a thumbnail, anything.
		 */
		public static Kind ofType(String type) {
			for (Kind kind : values()) {
				if (type == null ? kind._type == null : type.equals(kind._type)) {
					return kind;
				}
			}
			return null;
		}
	}

	/** What a presented signature turned out to be, see {@link #verify}. */
	public static final class Verified {

		/** The outcome. */
		public enum Status {
			/** Good for this request. */
			VALID,

			/** Correctly signed, and over. */
			EXPIRED,

			/** Not a signature of this space over this request. */
			INVALID;
		}

		private final Status _status;

		private final String _subjectKind;

		private final String _id;

		Verified(Status status, String subjectKind, String id) {
			_status = status;
			_subjectKind = subjectKind;
			_id = id;
		}

		/** The outcome. */
		public Status getStatus() {
			return _status;
		}

		/** {@link MediaSignatures#DEVICE} or {@link MediaSignatures#SHARE}, where valid. */
		String getSubjectKind() {
			return _subjectKind;
		}

		/** The id of the device or link, where valid. */
		String getId() {
			return _id;
		}
	}

	private static final Verified INVALID = new Verified(Verified.Status.INVALID, null, null);

	private final Path _file;

	private final SecureRandom _random = new SecureRandom();

	private byte[] _secret;

	/**
	 * Creates the {@link MediaSignatures} of the space rooted at the given folder.
	 *
	 * <p>
	 * Nothing is read or written until the first signature is made or checked.
	 * </p>
	 */
	public MediaSignatures(Path spaceRoot) {
		_file = spaceRoot.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
	}

	/** Where the secret lies. */
	public Path getFile() {
		return _file;
	}

	/**
	 * The signature over the given request.
	 *
	 * @param path
	 *        The path of the request as the servlet sees it (its path info), the empty string for
	 *        none.
	 * @param kind
	 *        Which file of the video it opens.
	 * @param subjectKind
	 *        {@link #DEVICE} or {@link #SHARE}.
	 * @param id
	 *        The id of the device or link; never contains a <code>.</code>.
	 * @param expires
	 *        Seconds since the epoch.
	 */
	public String sign(String path, Kind kind, String subjectKind, String id, long expires) throws IOException {
		if (id == null || id.isEmpty() || id.indexOf('.') >= 0) {
			throw new IllegalArgumentException("No id a signature can carry: '" + id + "'.");
		}
		return subjectKind + "." + id + "." + expires + "." + mac(path, kind, subjectKind, id, expires);
	}

	/**
	 * Checks the given signature against the request it was presented with.
	 *
	 * <p>
	 * The path and the kind are those of the request, not of the signature: a signature presented
	 * for another file, another kind or a listing is simply not a signature over that request.
	 * The mac is compared in constant time and before the expiry is looked at, so an expired
	 * signature is told apart from a forged one only where it was genuinely issued.
	 * </p>
	 *
	 * @param kind
	 *        The kind the request asks for, <code>null</code> where it asks for something no
	 *        signature opens.
	 * @param now
	 *        Seconds since the epoch.
	 */
	public Verified verify(String value, String path, Kind kind, long now) {
		if (value == null || kind == null) {
			return INVALID;
		}
		String[] parts = value.split("\\.", -1);
		if (parts.length != 4) {
			return INVALID;
		}
		String subjectKind = parts[0];
		String id = parts[1];
		if (!(DEVICE.equals(subjectKind) || SHARE.equals(subjectKind) || CONTACT.equals(subjectKind))
			|| id.isEmpty()) {
			return INVALID;
		}
		long expires;
		try {
			expires = Long.parseLong(parts[2]);
		} catch (NumberFormatException ex) {
			return INVALID;
		}
		byte[] presented;
		try {
			presented = Base64.getUrlDecoder().decode(parts[3]);
		} catch (IllegalArgumentException ex) {
			return INVALID;
		}
		byte[] expected;
		try {
			expected = Base64.getUrlDecoder().decode(mac(path, kind, subjectKind, id, expires));
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot check a media signature: " + ex.getMessage(), ex);
			return INVALID;
		}
		if (!MessageDigest.isEqual(expected, presented)) {
			return INVALID;
		}
		if (now >= expires) {
			return new Verified(Verified.Status.EXPIRED, subjectKind, id);
		}
		return new Verified(Verified.Status.VALID, subjectKind, id);
	}

	private String mac(String path, Kind kind, String subjectKind, String id, long expires) throws IOException {
		String text = DOMAIN + "\n" + kind.getName() + "\n" + subjectKind + "\n" + id + "\n" + expires + "\n"
			+ (path == null ? "" : path);
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret(), ALGORITHM));
			return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException ex) {
			throw new IOException("No " + ALGORITHM + " on this machine.", ex);
		}
	}

	/** The secret of the space, read or made at first use. */
	private synchronized byte[] secret() throws IOException {
		if (_secret != null) {
			return _secret;
		}
		if (Files.exists(_file)) {
			byte[] read = decode(Files.readAllBytes(_file));
			if (read != null) {
				_secret = read;
				return _secret;
			}
			LOG.warning("The media secret '" + _file + "' is unreadable; a new one is made.");
		}
		byte[] secret = new byte[SECRET_BYTES];
		_random.nextBytes(secret);
		Path directory = _file.getParent();
		Files.createDirectories(directory);
		// A temporary file is created owner-only; the explicit permissions say so where the
		// file system knows them, see the class comment.
		Path tmp = Files.createTempFile(directory, FILE_NAME, ".tmp");
		try {
			try {
				Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
			} catch (UnsupportedOperationException ex) {
				// Not a POSIX file system: what the temporary file got is all there is.
			}
			Files.write(tmp, Base64.getEncoder().encodeToString(secret).getBytes(StandardCharsets.US_ASCII));
			try {
				Files.move(tmp, _file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ex) {
				Files.move(tmp, _file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
		LOG.info("Made the media secret '" + _file + "'.");
		_secret = secret;
		return _secret;
	}

	private static byte[] decode(byte[] contents) {
		try {
			byte[] result = Base64.getDecoder().decode(new String(contents, StandardCharsets.US_ASCII).trim());
			return result.length >= SECRET_BYTES ? result : null;
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
