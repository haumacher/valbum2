/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The ways a principal signs in on another browser besides what this one holds: an authenticator
 * app (issue #208) and passkeys (issue #204).
 *
 * <p>
 * <b>One mechanism, two holders</b> (issue #233): a contact of a personal link
 * ({@link ContactStore.Contact}) and a member ({@link UserStore.User}) each carry one of these,
 * and their register writes it under the same names &mdash; <code>totp</code>,
 * <code>totpPending</code>, <code>passkeys</code> &mdash; only where there is something to write,
 * so that a register without any reads and writes exactly as before. What a code or a passkey may
 * do is decided once, by {@link TotpSignIns} and the passkey ceremony, over a
 * {@link SignInHolder} and its {@link SignInRegister}.
 * </p>
 *
 * <p>
 * Changed only by the register holding it, under its lock, see
 * {@link SignInRegister#change(SignInHolder, java.util.function.Predicate)}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class SignIns {

	/** The field of the authenticator app in use. */
	private static final String TOTP__PROP = "totp";

	/** The field of the authenticator app being set up. */
	private static final String TOTP_PENDING__PROP = "totpPending";

	/** The field of the passkeys. */
	private static final String PASSKEYS__PROP = "passkeys";

	/**
	 * An authenticator app, see issue #208: the secret it shares with the server and the last step a
	 * code of it was accepted for.
	 *
	 * <p>
	 * Unlike a token, the secret cannot be stored as a hash: the server computes the codes from it.
	 * It is therefore stored as it is, which makes the register holding it (<code>contacts.json</code>,
	 * <code>users.json</code>) a file to keep as secret as the server's own settings.
	 * </p>
	 */
	public static final class Authenticator {

		private final String _secret;

		private final String _since;

		private long _lastStep;

		Authenticator(String secret, String since, long lastStep) {
			_secret = secret;
			_since = since;
			_lastStep = lastStep;
		}

		/** The secret, in Base32. */
		public String getSecret() {
			return _secret;
		}

		/** When it was set up (confirmed, for an active one; started, for a pending one). */
		public String getSince() {
			return _since;
		}

		/** The last step a code was accepted for; a code of it or an earlier one is used up. */
		public long getLastStep() {
			return _lastStep;
		}
	}

	/**
	 * A passkey, see issue #204: what the server needs to check an assertion of it &mdash; the
	 * credential's public key with its id (the authenticator's attested credential data) and its
	 * signature counter. Nothing of it is a secret.
	 */
	public static final class Passkey {

		private final String _id;

		private final String _created;

		private String _lastUsed;

		private final String _data;

		private long _counter;

		private final boolean _uvInitialized;

		private final boolean _backupEligible;

		private boolean _backupState;

		private final List<String> _transports;

		/**
		 * Creates a {@link Passkey}.
		 *
		 * @param id
		 *        The credential id, base64url without padding.
		 * @param data
		 *        The attested credential data, base64url without padding.
		 */
		public Passkey(String id, String created, String lastUsed, String data, long counter, boolean uvInitialized,
				boolean backupEligible, boolean backupState, List<String> transports) {
			_id = id;
			_created = created;
			_lastUsed = lastUsed;
			_data = data;
			_counter = counter;
			_uvInitialized = uvInitialized;
			_backupEligible = backupEligible;
			_backupState = backupState;
			_transports = new ArrayList<>(transports);
		}

		/** The credential id, base64url without padding. */
		public String getId() {
			return _id;
		}

		/** When it was registered. */
		public String getCreated() {
			return _created;
		}

		/** When it last signed in, empty while it never did. */
		public String getLastUsed() {
			return _lastUsed;
		}

		/** The attested credential data (id and public key), base64url without padding. */
		public String getData() {
			return _data;
		}

		/** The signature counter of the last assertion. */
		public long getCounter() {
			return _counter;
		}

		/** Whether the authenticator verified the user when it was registered. */
		public boolean isUvInitialized() {
			return _uvInitialized;
		}

		/** Whether the passkey may be synced to other devices. */
		public boolean isBackupEligible() {
			return _backupEligible;
		}

		/** Whether the passkey was synced, as last reported. */
		public boolean isBackupState() {
			return _backupState;
		}

		/** The transports the authenticator named. */
		public List<String> getTransports() {
			return Collections.unmodifiableList(_transports);
		}
	}

	private Authenticator _totp;

	private Authenticator _totpPending;

	private final List<Passkey> _passkeys = new ArrayList<>();

	/**
	 * The authenticator app that signs the holder in, <code>null</code> while none does (issue
	 * #208).
	 */
	public Authenticator getAuthenticator() {
		return _totp;
	}

	/**
	 * The authenticator app being set up, <code>null</code> while none is: its secret signs nobody
	 * in until one code of it was confirmed (issue #208).
	 */
	public Authenticator getPendingAuthenticator() {
		return _totpPending;
	}

	/** The passkeys (issue #204), in the order they were registered. */
	public List<Passkey> getPasskeys() {
		return Collections.unmodifiableList(_passkeys);
	}

	/** The passkey of the given credential id, <code>null</code> for none of these. */
	public Passkey passkey(String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		for (Passkey passkey : _passkeys) {
			if (passkey.getId().equals(id)) {
				return passkey;
			}
		}
		return null;
	}

	// --- Changes, made by the register under its lock; each answers whether it changed anything. ---

	/**
	 * Starts setting up an authenticator app: the secret is kept as pending and signs nobody in
	 * before {@link #confirmTotp} found a code of it. An app in use keeps working meanwhile.
	 */
	public boolean startTotp(String secret, Instant now) {
		_totpPending = new Authenticator(secret, now.truncatedTo(ChronoUnit.SECONDS).toString(), 0);
		return true;
	}

	/**
	 * Makes the pending authenticator app the one that signs in, where its secret is still the
	 * given one; the given step is used up from then on.
	 */
	public boolean confirmTotp(String secret, long step, Instant now) {
		if (_totpPending == null || !_totpPending.getSecret().equals(secret)) {
			return false;
		}
		_totp = new Authenticator(secret, now.truncatedTo(ChronoUnit.SECONDS).toString(), step);
		_totpPending = null;
		return true;
	}

	/**
	 * Uses up the given step of the authenticator app: a code is accepted once, and never one of an
	 * earlier step after it.
	 *
	 * @return Whether the step was still unused, and the secret still the one the code was checked
	 *         against.
	 */
	public boolean useTotpStep(String secret, long step) {
		if (_totp == null || !_totp.getSecret().equals(secret) || step <= _totp.getLastStep()) {
			return false;
		}
		_totp._lastStep = step;
		return true;
	}

	/** Removes the authenticator app, the one in use and one being set up. */
	public boolean removeTotp() {
		if (_totp == null && _totpPending == null) {
			return false;
		}
		_totp = null;
		_totpPending = null;
		return true;
	}

	/** Adds a passkey. */
	public boolean addPasskey(Passkey passkey) {
		_passkeys.add(passkey);
		return true;
	}

	/** Writes down that the given passkey signed in, with what its authenticator reported. */
	public boolean usedPasskey(String id, long counter, boolean backupState, Instant now) {
		Passkey passkey = passkey(id);
		if (passkey == null) {
			return false;
		}
		passkey._counter = counter;
		passkey._backupState = backupState;
		passkey._lastUsed = now.truncatedTo(ChronoUnit.SECONDS).toString();
		return true;
	}

	/** Removes the passkey of the given id. */
	public boolean removePasskey(String id) {
		for (Iterator<Passkey> it = _passkeys.iterator(); it.hasNext();) {
			if (it.next().getId().equals(id)) {
				it.remove();
				return true;
			}
		}
		return false;
	}

	// --- Persistence. ---

	/**
	 * Reads the value of the given field, if it is one of these.
	 *
	 * @return Whether it was; the caller skips the value otherwise.
	 */
	public boolean read(String name, JsonReader in) throws IOException {
		switch (name) {
			case TOTP__PROP:
				_totp = readAuthenticator(in);
				return true;
			case TOTP_PENDING__PROP:
				_totpPending = readAuthenticator(in);
				return true;
			case PASSKEYS__PROP:
				in.beginArray();
				while (in.hasNext()) {
					Passkey passkey = readPasskey(in);
					if (!passkey.getId().isEmpty() && !passkey.getData().isEmpty()) {
						_passkeys.add(passkey);
					}
				}
				in.endArray();
				return true;
			default:
				return false;
		}
	}

	/**
	 * Writes the fields of these into the object being written, each only where there is one, so
	 * that a register without any reads as before.
	 */
	public void write(JsonWriter out) throws IOException {
		if (_totp != null) {
			out.name(TOTP__PROP);
			writeAuthenticator(out, _totp);
		}
		if (_totpPending != null) {
			out.name(TOTP_PENDING__PROP);
			writeAuthenticator(out, _totpPending);
		}
		if (!_passkeys.isEmpty()) {
			out.name(PASSKEYS__PROP);
			out.beginArray();
			for (Passkey passkey : _passkeys) {
				writePasskey(out, passkey);
			}
			out.endArray();
		}
	}

	private static Authenticator readAuthenticator(JsonReader in) throws IOException {
		String secret = "";
		String since = "";
		long lastStep = 0;
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case "secret":
					secret = in.nextString();
					break;
				case "since":
					since = in.nextString();
					break;
				case "lastStep":
					lastStep = in.nextLong();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return secret.isEmpty() ? null : new Authenticator(secret, since, lastStep);
	}

	private static Passkey readPasskey(JsonReader in) throws IOException {
		String id = "";
		String created = "";
		String lastUsed = "";
		String data = "";
		long counter = 0;
		boolean uvInitialized = false;
		boolean backupEligible = false;
		boolean backupState = false;
		List<String> transports = new ArrayList<>();
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case "id":
					id = in.nextString();
					break;
				case "created":
					created = in.nextString();
					break;
				case "lastUsed":
					lastUsed = in.nextString();
					break;
				case "data":
					data = in.nextString();
					break;
				case "counter":
					counter = in.nextLong();
					break;
				case "uvInitialized":
					uvInitialized = in.nextBoolean();
					break;
				case "backupEligible":
					backupEligible = in.nextBoolean();
					break;
				case "backupState":
					backupState = in.nextBoolean();
					break;
				case "transports":
					in.beginArray();
					while (in.hasNext()) {
						transports.add(in.nextString());
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return new Passkey(id, created, lastUsed, data, counter, uvInitialized, backupEligible, backupState,
			transports);
	}

	private static void writePasskey(JsonWriter out, Passkey passkey) throws IOException {
		out.beginObject();
		out.name("id");
		out.value(passkey.getId());
		out.name("created");
		out.value(passkey.getCreated());
		out.name("lastUsed");
		out.value(passkey.getLastUsed());
		out.name("data");
		out.value(passkey.getData());
		out.name("counter");
		out.value(passkey.getCounter());
		out.name("uvInitialized");
		out.value(passkey.isUvInitialized());
		out.name("backupEligible");
		out.value(passkey.isBackupEligible());
		out.name("backupState");
		out.value(passkey.isBackupState());
		out.name("transports");
		out.beginArray();
		for (String transport : passkey.getTransports()) {
			out.value(transport);
		}
		out.endArray();
		out.endObject();
	}

	private static void writeAuthenticator(JsonWriter out, Authenticator authenticator) throws IOException {
		out.beginObject();
		out.name("secret");
		out.value(authenticator.getSecret());
		out.name("since");
		out.value(authenticator.getSince());
		out.name("lastStep");
		out.value(authenticator.getLastStep());
		out.endObject();
	}
}
