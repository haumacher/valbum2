/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The contacts of a space: the people behind its personal share links, see issue #198.
 *
 * <p>
 * <b>A contact is not a user.</b> No role, no device, no clearance: what a contact may see and do
 * is exactly what the personal link they opened allows, see {@link ShareStore.Link}. This store
 * holds who they are &mdash; the name the space gives them, their own display name, their
 * addresses &mdash; and the browsers they are recognised on, their <em>contact credentials</em>.
 * A credential is a secret like a device token and is stored as its hash, never itself.
 * </p>
 *
 * <p>
 * An address belongs to one contact at most: a contact entered again with an address the register
 * already holds <em>is</em> that contact. Addresses are normalised before they are compared, see
 * {@link #normalize(String, String)}.
 * </p>
 *
 * <p>
 * The file lives in {@link UserStore#DIRECTORY_NAME} of the space and is versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"contacts":[{"id":"…","name":"Tante Petra","displayName":"Petra",
 *   "addresses":[{"kind":"email","value":"petra@gmx.de","proven":false}],
 *   "created":"…","createdBy":"alice","firstSeen":"…","lastSeen":"…","blocked":"",
 *   "sessions":[{"id":"…","tokenHash":"&lt;64 hex&gt;","link":"&lt;share id&gt;","created":"…",
 *     "expires":"…","remember":true,"lastUsed":"…"}]}]}
 * </pre>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ContactStore {

	private static final Logger LOG = Logger.getLogger(ContactStore.class.getName());

	/** The name of the store within {@link UserStore#DIRECTORY_NAME}. */
	public static final String FILE_NAME = "contacts.json";

	/** The version this build writes. */
	public static final int VERSION = 1;

	/** The address kind of an e-mail address, as stored. */
	public static final String EMAIL = "email";

	/** The address kind of a phone number, as stored. */
	public static final String PHONE = "phone";

	/** How long a remembered credential lives, renewed on use. */
	public static final Duration REMEMBERED = Duration.ofDays(90);

	/** How long a credential lives that was not to be remembered. */
	public static final Duration SESSION = Duration.ofHours(24);

	/**
	 * How often the use of a credential is written down.
	 *
	 * <p>
	 * A credential is presented with every request of a session &mdash; every thumbnail &mdash;
	 * and the store is not written for each: the last use, the renewal of a remembered credential
	 * and the contact's "last seen" move in steps of this length.
	 * </p>
	 */
	public static final Duration USE_GRANULARITY = Duration.ofHours(1);

	private static final int TOKEN_BYTES = 32;

	private static final int ID_BYTES = 9;

	/** The full form of an e-mail address, <code>Tante Petra &lt;petra@gmx.de&gt;</code>. */
	private static final Pattern FULL_FORM = Pattern.compile("^\\s*\"?([^\"<]*?)\"?\\s*<\\s*([^<>\\s]+)\\s*>\\s*$");

	private static final Pattern EMAIL_FORM = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

	private static final Pattern PHONE_FORM = Pattern.compile("^\\+?[0-9 ()./\\-]+$");

	/** An address of a contact. */
	public static final class Address {

		private final String _kind;

		private final String _value;

		private boolean _proven;

		/** Creates an {@link Address} of a normalised value. */
		public Address(String kind, String value, boolean proven) {
			_kind = kind;
			_value = value;
			_proven = proven;
		}

		/** {@link ContactStore#EMAIL} or {@link ContactStore#PHONE}. */
		public String getKind() {
			return _kind;
		}

		/** The normalised value. */
		public String getValue() {
			return _value;
		}

		/** Whether the contact proved the address themselves (issues #199, #200). */
		public boolean isProven() {
			return _proven;
		}

		/** Whether this is the same address as the given one. */
		public boolean sameAs(Address other) {
			return _kind.equals(other._kind) && _value.equals(other._value);
		}

		/**
		 * The address with most of it masked, for a caller who must not learn it.
		 *
		 * <p>
		 * <code>p•••@gmx.de</code> and <code>+49•••34</code>: enough for the person it belongs to to
		 * recognise it, too little for anybody else to use it.
		 * </p>
		 */
		public String masked() {
			if (EMAIL.equals(_kind)) {
				int at = _value.indexOf('@');
				if (at > 0) {
					return _value.charAt(0) + "•••" + _value.substring(at);
				}
			}
			if (_value.length() > 6) {
				return _value.substring(0, 3) + "•••" + _value.substring(_value.length() - 2);
			}
			return "•••";
		}
	}

	/** A browser a contact is recognised on: a contact credential, stored as its hash. */
	public static final class Session {

		private final String _id;

		private final String _tokenHash;

		private final String _link;

		private final String _created;

		private String _expires;

		private final boolean _remember;

		private String _lastUsed;

		Session(String id, String tokenHash, String link, String created, String expires, boolean remember,
				String lastUsed) {
			_id = id;
			_tokenHash = tokenHash;
			_link = link;
			_created = created;
			_expires = expires;
			_remember = remember;
			_lastUsed = lastUsed;
		}

		/** The id of the session; what a media signature of a contact names, see {@link MediaSignatures}. */
		public String getId() {
			return _id;
		}

		String getTokenHash() {
			return _tokenHash;
		}

		/** The id of the share link it was opened through. */
		public String getLink() {
			return _link;
		}

		/** When it began. */
		public String getCreated() {
			return _created;
		}

		/** When it ends. */
		public String getExpires() {
			return _expires;
		}

		/** Whether it is renewed on use. */
		public boolean isRemember() {
			return _remember;
		}

		/** When it was last used, to {@link ContactStore#USE_GRANULARITY}. */
		public String getLastUsed() {
			return _lastUsed;
		}

		/** Whether it has run out at the given moment; an unreadable expiry has. */
		public boolean isExpired(Instant now) {
			try {
				return !Instant.parse(_expires).isAfter(now);
			} catch (DateTimeException ex) {
				return true;
			}
		}
	}

	/**
	 * An authenticator app of a contact, see issue #208: the secret it shares with the server and the
	 * last step a code of it was accepted for.
	 *
	 * <p>
	 * Unlike a token, the secret cannot be stored as a hash: the server computes the codes from it.
	 * It is therefore stored as it is, which makes <code>contacts.json</code> a file to keep as
	 * secret as the server's own settings.
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
	 * A passkey of a contact, see issue #204: what the server needs to check an assertion of it
	 * &mdash; the credential's public key with its id (the authenticator's attested credential
	 * data) and its signature counter. Nothing of it is a secret.
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

	/** A passkey found in the register, with the contact holding it. */
	public static final class PasskeyHolder {

		private final Contact _contact;

		private final Passkey _passkey;

		PasskeyHolder(Contact contact, Passkey passkey) {
			_contact = contact;
			_passkey = passkey;
		}

		/** The contact. */
		public Contact getContact() {
			return _contact;
		}

		/** The passkey. */
		public Passkey getPasskey() {
			return _passkey;
		}
	}

	/** A contact of the space. */
	public static final class Contact {

		private final String _id;

		private String _name;

		private String _displayName;

		private final List<Address> _addresses = new ArrayList<>();

		private final String _created;

		private final String _createdBy;

		private String _firstSeen;

		private String _lastSeen;

		private String _blocked;

		private final List<Session> _sessions = new ArrayList<>();

		private Authenticator _totp;

		private Authenticator _totpPending;

		private final List<Passkey> _passkeys = new ArrayList<>();

		Contact(String id, String name, String displayName, String created, String createdBy, String firstSeen,
				String lastSeen, String blocked) {
			_id = id;
			_name = name;
			_displayName = displayName;
			_created = created;
			_createdBy = createdBy;
			_firstSeen = firstSeen;
			_lastSeen = lastSeen;
			_blocked = blocked;
		}

		/** The id; an upload is attributed to {@link #getSubject()}. */
		public String getId() {
			return _id;
		}

		/** How an attribution names this contact's contribution, see issue #53. */
		public String getSubject() {
			return "contact:" + _id;
		}

		/** The name the space gives the contact. */
		public String getName() {
			return _name;
		}

		/** See {@link #getName()}. */
		void setName(String name) {
			_name = name;
		}

		/** The contact's own name, empty while they gave none. */
		public String getDisplayName() {
			return _displayName;
		}

		/** The name to greet the contact by: their own, else the space's. */
		public String greeting() {
			return _displayName.isEmpty() ? _name : _displayName;
		}

		/** The contact's addresses. */
		public List<Address> getAddresses() {
			return Collections.unmodifiableList(_addresses);
		}

		/** When the contact was entered. */
		public String getCreated() {
			return _created;
		}

		/** Who entered the contact. */
		public String getCreatedBy() {
			return _createdBy;
		}

		/** When the contact first opened a link. */
		public String getFirstSeen() {
			return _firstSeen;
		}

		/** When the contact was last seen. */
		public String getLastSeen() {
			return _lastSeen;
		}

		/** When the contact was shut out of the whole space, empty while they are not. */
		public String getBlocked() {
			return _blocked;
		}

		/** Whether the contact is shut out of the whole space. */
		public boolean isBlocked() {
			return !_blocked.isEmpty();
		}

		/** The contact's live sessions. */
		public List<Session> getSessions() {
			return Collections.unmodifiableList(_sessions);
		}

		/**
		 * The authenticator app that signs the contact in, <code>null</code> while none does (issue
		 * #208).
		 */
		public Authenticator getAuthenticator() {
			return _totp;
		}

		/**
		 * The authenticator app being set up, <code>null</code> while none is: its secret signs
		 * nobody in until one code of it was confirmed (issue #208).
		 */
		public Authenticator getPendingAuthenticator() {
			return _totpPending;
		}

		/** The contact's passkeys (issue #204), in the order they were registered. */
		public List<Passkey> getPasskeys() {
			return Collections.unmodifiableList(_passkeys);
		}

		/** Whether the contact holds the given address. */
		boolean holds(Address address) {
			for (Address own : _addresses) {
				if (own.sameAs(address)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public String toString() {
			return getSubject() + " (" + _name + ")";
		}
	}

	/** A contact credential that was just issued, with its secret, answered exactly once. */
	public static final class Issued {

		private final Contact _contact;

		private final Session _session;

		private final String _credential;

		Issued(Contact contact, Session session, String credential) {
			_contact = contact;
			_session = session;
			_credential = credential;
		}

		/** The contact it identifies. */
		public Contact getContact() {
			return _contact;
		}

		/** The session it opened. */
		public Session getSession() {
			return _session;
		}

		/** The credential, never stored. */
		public String getCredential() {
			return _credential;
		}
	}

	/** A credential that was recognised: whose it is and which session. */
	public static final class Recognized {

		private final Contact _contact;

		private final Session _session;

		Recognized(Contact contact, Session session) {
			_contact = contact;
			_session = session;
		}

		/** The contact. */
		public Contact getContact() {
			return _contact;
		}

		/** The session. */
		public Session getSession() {
			return _session;
		}
	}

	/** Thrown for an address or a name the register cannot take. */
	public static final class Refused extends Exception {

		/** Creates a {@link Refused} saying why. */
		public Refused(String message) {
			super(message);
		}
	}

	/** A recipient as a request describes it: an existing contact, or a new one. */
	public static final class Request {

		private final String _id;

		private final String _name;

		private final List<Address> _addresses;

		Request(String id, String name, List<Address> addresses) {
			_id = id;
			_name = name;
			_addresses = addresses;
		}

		/** The id of an existing contact, empty for a new one. */
		public String getId() {
			return _id;
		}

		/** The name of a new contact, after the fallback to its first address. */
		public String getName() {
			return _name;
		}

		/** The normalised addresses of a new contact. */
		public List<Address> getAddresses() {
			return _addresses;
		}
	}

	private final Path _file;

	private final SecureRandom _random = new SecureRandom();

	private List<Contact> _contacts = new ArrayList<>();

	private boolean _damaged;

	/** Creates the {@link ContactStore} of the space rooted at the given folder and reads it. */
	public ContactStore(Path basePath) {
		_file = basePath.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		load();
	}

	/** The file this store is persisted in. */
	public Path getFile() {
		return _file;
	}

	/** Every contact, in the order they were entered. */
	public synchronized List<Contact> getContacts() {
		return Collections.unmodifiableList(new ArrayList<>(_contacts));
	}

	/** The contact of the given id, <code>null</code> if there is none. */
	public synchronized Contact get(String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		for (Contact contact : _contacts) {
			if (contact.getId().equals(id)) {
				return contact;
			}
		}
		return null;
	}

	// --- Addresses. ---

	/**
	 * The normalised form of an address.
	 *
	 * <p>
	 * An e-mail address is trimmed and lower-cased (the full form
	 * <code>Name &lt;address&gt;</code> is read as its address). A phone number loses its blanks,
	 * dashes, dots, slashes and brackets and is written in E.164 (<code>+4917…</code>) where it
	 * says its country (<code>+</code> or <code>00</code>), and kept as given otherwise &mdash; a
	 * national number names a country nobody told this server.
	 * </p>
	 *
	 * @throws Refused
	 *         For something that is no address of the kind.
	 */
	public static String normalize(String kind, String value) throws Refused {
		String text = value == null ? "" : value.trim();
		if (EMAIL.equals(kind)) {
			Matcher full = FULL_FORM.matcher(text);
			if (full.matches()) {
				text = full.group(2);
			}
			text = text.toLowerCase(Locale.ROOT);
			if (!EMAIL_FORM.matcher(text).matches() || text.startsWith("@") || text.endsWith("@")) {
				throw new Refused(addressInvalid(value));
			}
			return text;
		}
		if (PHONE.equals(kind)) {
			if (!PHONE_FORM.matcher(text).matches() || text.indexOf('+', 1) >= 0) {
				throw new Refused(addressInvalid(value));
			}
			String digits = text.replaceAll("[^0-9]", "");
			if (digits.length() < 3) {
				throw new Refused(addressInvalid(value));
			}
			if (text.startsWith("+") && digits.length() <= 15) {
				return "+" + digits;
			}
			if (digits.startsWith("00") && digits.length() > 4 && digits.length() <= 17) {
				return "+" + digits.substring(2);
			}
			return text;
		}
		throw new Refused(addressInvalid(value));
	}

	/** The name the full form of an e-mail address carries, empty where it is no full form. */
	public static String nameOf(String value) {
		Matcher full = value == null ? null : FULL_FORM.matcher(value.trim());
		return full != null && full.matches() ? full.group(1).trim() : "";
	}

	/** The message an address that is none is refused with. */
	public static String addressInvalid(String value) {
		return "'" + value + "' is no e-mail address or phone number this server can read.";
	}

	/** The message a contact without any name and without any address is refused with. */
	public static final String NAME_REQUIRED = "A new contact needs a name or an address.";

	/** The message a recipient naming no contact of this space is refused with. */
	public static String unknownContact(String id) {
		return "There is no contact '" + id + "' in this space.";
	}

	/**
	 * Reads a recipient a request describes, without changing anything.
	 *
	 * @param id
	 *        The id of an existing contact, or empty.
	 * @param name
	 *        The name of a new contact, may be empty.
	 * @param addresses
	 *        The new contact's addresses as pairs of kind and value, unnormalised.
	 * @throws Refused
	 *         For an unknown contact, an unreadable address, or a contact without any name.
	 */
	public synchronized Request read(String id, String name, List<String[]> addresses) throws Refused {
		String contactId = id == null ? "" : id.trim();
		if (!contactId.isEmpty()) {
			if (get(contactId) == null) {
				throw new Refused(unknownContact(contactId));
			}
			return new Request(contactId, "", Collections.emptyList());
		}
		String given = name == null ? "" : name.trim();
		List<Address> normalized = new ArrayList<>();
		for (String[] address : addresses) {
			if (given.isEmpty() && EMAIL.equals(address[0])) {
				given = nameOf(address[1]);
			}
			Address value = new Address(address[0], normalize(address[0], address[1]), false);
			boolean twice = false;
			for (Address seen : normalized) {
				twice |= seen.sameAs(value);
			}
			if (!twice) {
				normalized.add(value);
			}
		}
		if (given.isEmpty() && !normalized.isEmpty()) {
			// Named by its first address, which is what the sharer typed (issue #198).
			given = normalized.get(0).getValue();
		}
		if (given.isEmpty()) {
			throw new Refused(NAME_REQUIRED);
		}
		return new Request("", given, normalized);
	}

	/**
	 * The contact a request describes, entered into the register where it is new.
	 *
	 * <p>
	 * A new contact holding an address the register already knows is that contact: no two
	 * contacts share an address. The addresses it brings that nobody holds yet are added to it;
	 * its name is the one it already has.
	 * </p>
	 */
	public synchronized Contact enter(Request request, String createdBy) throws IOException {
		if (!request.getId().isEmpty()) {
			return get(request.getId());
		}
		Contact found = null;
		for (Address address : request.getAddresses()) {
			found = byAddress(address);
			if (found != null) {
				break;
			}
		}
		boolean changed = false;
		if (found == null) {
			found = new Contact(freeId(), request.getName(), "", Instant.now().toString(),
				createdBy == null ? "" : createdBy, "", "", "");
			_contacts.add(found);
			changed = true;
		}
		for (Address address : request.getAddresses()) {
			if (byAddress(address) == null) {
				found._addresses.add(address);
				changed = true;
			}
		}
		if (changed) {
			store();
		}
		return found;
	}

	/** The contact holding the given normalised e-mail address, <code>null</code> if none does. */
	public synchronized Contact byEmail(String email) {
		return byAddress(new Address(EMAIL, email, false));
	}

	/** The message an address proven by one contact and held by another is refused with (issue #199). */
	public static final String ADDRESS_ELSEWHERE =
		"This address belongs to somebody else here. Ask the person who shared the link.";

	/**
	 * Records that an e-mail address was proven, see issue #199.
	 *
	 * <p>
	 * For a given contact the address is marked proven, or added as proven where nobody holds it.
	 * Without one, the contact holding the address is that person and the address is marked proven
	 * on them; where nobody holds it, a new contact is entered with it &mdash; the visitor of an
	 * open personal link becomes a contact of the space.
	 * </p>
	 *
	 * @param contactId
	 *        The contact who proved it, <code>null</code> for a visitor who is nobody yet.
	 * @param email
	 *        The normalised address.
	 * @param name
	 *        The name of a new contact; empty names them by the address.
	 * @param createdBy
	 *        Who enters a new contact.
	 * @return The contact the address belongs to; <code>null</code> where the given contact is gone.
	 * @throws Refused
	 *         {@link #ADDRESS_ELSEWHERE} where the given contact proved an address another one holds.
	 */
	public synchronized Contact prove(String contactId, String email, String name, String createdBy)
			throws Refused, IOException {
		Address proven = new Address(EMAIL, email, true);
		Contact holder = byAddress(proven);
		Contact contact;
		if (contactId != null) {
			contact = get(contactId);
			if (contact == null) {
				return null;
			}
			if (holder != null && holder != contact) {
				throw new Refused(ADDRESS_ELSEWHERE);
			}
		} else if (holder != null) {
			contact = holder;
		} else {
			String given = name == null ? "" : name.trim();
			contact = new Contact(freeId(), given.isEmpty() ? email : given, "", Instant.now().toString(),
				createdBy == null ? "" : createdBy, "", "", "");
			_contacts.add(contact);
		}
		boolean held = false;
		for (Address address : contact._addresses) {
			if (address.sameAs(proven)) {
				address._proven = true;
				held = true;
			}
		}
		if (!held) {
			contact._addresses.add(proven);
		}
		store();
		return contact;
	}

	/** The contact holding the given address, <code>null</code> if none does. */
	private Contact byAddress(Address address) {
		for (Contact contact : _contacts) {
			if (contact.holds(address)) {
				return contact;
			}
		}
		return null;
	}

	// --- Authenticator apps (issue #208). ---

	/**
	 * Starts setting up an authenticator app for the given contact: the secret is kept as pending
	 * and signs nobody in before {@link #confirmTotp} found a code of it. An app the contact uses
	 * already keeps working meanwhile.
	 *
	 * @return The contact, <code>null</code> if there is none.
	 */
	public synchronized Contact startTotp(String contactId, String secret, Instant now) throws IOException {
		Contact contact = get(contactId);
		if (contact == null) {
			return null;
		}
		contact._totpPending = new Authenticator(secret, now.truncatedTo(ChronoUnit.SECONDS).toString(), 0);
		store();
		return contact;
	}

	/**
	 * Makes the pending authenticator app of the given contact the one that signs them in, where its
	 * secret is still the given one; the given step is used up from then on.
	 *
	 * @return Whether it was confirmed.
	 */
	public synchronized boolean confirmTotp(String contactId, String secret, long step, Instant now)
			throws IOException {
		Contact contact = get(contactId);
		if (contact == null || contact._totpPending == null || !contact._totpPending.getSecret().equals(secret)) {
			return false;
		}
		contact._totp = new Authenticator(secret, now.truncatedTo(ChronoUnit.SECONDS).toString(), step);
		contact._totpPending = null;
		store();
		return true;
	}

	/**
	 * Uses up the given step of the given contact's authenticator app: a code is accepted once, and
	 * never one of an earlier step after it.
	 *
	 * @return Whether the step was still unused, and the secret still the one the code was checked
	 *         against.
	 */
	public synchronized boolean useTotpStep(String contactId, String secret, long step) throws IOException {
		Contact contact = get(contactId);
		if (contact == null || contact._totp == null || !contact._totp.getSecret().equals(secret)
			|| step <= contact._totp.getLastStep()) {
			return false;
		}
		contact._totp._lastStep = step;
		store();
		return true;
	}

	/**
	 * Removes the authenticator app of the given contact, the one in use and one being set up.
	 *
	 * @return Whether there was one.
	 */
	public synchronized boolean removeTotp(String contactId) throws IOException {
		Contact contact = get(contactId);
		if (contact == null || (contact._totp == null && contact._totpPending == null)) {
			return false;
		}
		contact._totp = null;
		contact._totpPending = null;
		store();
		return true;
	}

	// --- Passkeys (issue #204). ---

	/**
	 * Adds a passkey to the given contact.
	 *
	 * @return The contact, <code>null</code> if there is none or another contact of the space holds
	 *         a passkey of that id already.
	 */
	public synchronized Contact addPasskey(String contactId, Passkey passkey) throws IOException {
		Contact contact = get(contactId);
		if (contact == null) {
			return null;
		}
		PasskeyHolder holder = byPasskey(passkey.getId());
		if (holder != null) {
			return holder.getContact() == contact ? contact : null;
		}
		contact._passkeys.add(passkey);
		store();
		return contact;
	}

	/** The passkey of the given credential id and its contact, <code>null</code> if nobody here holds it. */
	public synchronized PasskeyHolder byPasskey(String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		for (Contact contact : _contacts) {
			for (Passkey passkey : contact._passkeys) {
				if (passkey.getId().equals(id)) {
					return new PasskeyHolder(contact, passkey);
				}
			}
		}
		return null;
	}

	/** Writes down that the given passkey signed in, with what its authenticator reported. */
	public synchronized void usedPasskey(String contactId, String id, long counter, boolean backupState,
			Instant now) throws IOException {
		Contact contact = get(contactId);
		if (contact == null) {
			return;
		}
		for (Passkey passkey : contact._passkeys) {
			if (passkey.getId().equals(id)) {
				passkey._counter = counter;
				passkey._backupState = backupState;
				passkey._lastUsed = now.truncatedTo(ChronoUnit.SECONDS).toString();
				store();
				return;
			}
		}
	}

	/**
	 * Removes a passkey of the given contact.
	 *
	 * @return Whether there was one of that id.
	 */
	public synchronized boolean removePasskey(String contactId, String id) throws IOException {
		Contact contact = get(contactId);
		if (contact == null) {
			return false;
		}
		for (Iterator<Passkey> it = contact._passkeys.iterator(); it.hasNext();) {
			if (it.next().getId().equals(id)) {
				it.remove();
				store();
				return true;
			}
		}
		return false;
	}

	// --- Credentials. ---

	/**
	 * Issues a contact credential: this browser is the given contact from now on.
	 *
	 * @param link
	 *        The id of the share link it is issued through.
	 * @param remember
	 *        {@link #REMEMBERED} renewed on use, else {@link #SESSION}.
	 * @param displayName
	 *        The contact's own name, empty to keep what they had.
	 */
	public synchronized Issued issue(String contactId, String link, boolean remember, String displayName)
			throws IOException {
		Contact contact = get(contactId);
		if (contact == null) {
			return null;
		}
		byte[] bytes = new byte[TOKEN_BYTES];
		_random.nextBytes(bytes);
		String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = Instant.now();
		String hour = now.truncatedTo(ChronoUnit.SECONDS).toString();
		Session session = new Session(freeSessionId(), UserStore.hash(credential), link == null ? "" : link,
			hour, now.plus(remember ? REMEMBERED : SESSION).toString(), remember, hour);
		contact._sessions.add(session);
		if (contact._firstSeen.isEmpty()) {
			contact._firstSeen = hour;
		}
		contact._lastSeen = hour;
		String own = displayName == null ? "" : displayName.trim();
		if (!own.isEmpty()) {
			contact._displayName = own;
		}
		store();
		return new Issued(contact, session, credential);
	}

	/** Takes back a credential just issued, see {@link #issue}. */
	synchronized void withdraw(Issued issued) throws IOException {
		if (issued.getContact()._sessions.remove(issued.getSession())) {
			store();
		}
	}

	/**
	 * The contact and session a credential identifies, <code>null</code> for a credential this
	 * space did not issue, that ran out or was ended.
	 *
	 * <p>
	 * A use is written down at most every {@link #USE_GRANULARITY}: the last use, the contact's
	 * last seen, and for a remembered credential a fresh {@link #REMEMBERED} lifetime.
	 * </p>
	 */
	public synchronized Recognized recognize(String credential) {
		if (credential == null || credential.isEmpty()) {
			return null;
		}
		byte[] hash = UserStore.hash(credential).getBytes(StandardCharsets.US_ASCII);
		Instant now = Instant.now();
		for (Contact contact : _contacts) {
			for (Session session : contact._sessions) {
				if (!MessageDigest.isEqual(hash, session.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
					continue;
				}
				if (session.isExpired(now) || contact.isBlocked()) {
					return null;
				}
				used(contact, session, now);
				return new Recognized(contact, session);
			}
		}
		return null;
	}

	/** The contact and session of the given session id, live or not; <code>null</code> if there is none. */
	public synchronized Recognized session(String sessionId) {
		for (Contact contact : _contacts) {
			for (Session session : contact._sessions) {
				if (session.getId().equals(sessionId)) {
					return new Recognized(contact, session);
				}
			}
		}
		return null;
	}

	private void used(Contact contact, Session session, Instant now) {
		Instant last;
		try {
			last = Instant.parse(session._lastUsed);
		} catch (DateTimeException ex) {
			last = Instant.EPOCH;
		}
		if (Duration.between(last, now).compareTo(USE_GRANULARITY) < 0) {
			return;
		}
		String at = now.truncatedTo(ChronoUnit.SECONDS).toString();
		session._lastUsed = at;
		contact._lastSeen = at;
		if (session.isRemember()) {
			session._expires = now.plus(REMEMBERED).toString();
		}
		try {
			store();
		} catch (IOException ex) {
			// The use is remembered in memory; the next write carries it.
			LOG.log(Level.WARNING, "Cannot write the use of a contact credential: " + ex.getMessage());
		}
	}

	/**
	 * Ends the sessions of the given contact, all of them or those opened through one link.
	 *
	 * @param link
	 *        The id of the share link, <code>null</code> for every session.
	 * @return How many were ended.
	 */
	public synchronized int endSessions(String contactId, String link) throws IOException {
		Contact contact = get(contactId);
		if (contact == null) {
			return 0;
		}
		int ended = 0;
		for (Iterator<Session> it = contact._sessions.iterator(); it.hasNext();) {
			Session session = it.next();
			if (link == null || link.equals(session.getLink())) {
				it.remove();
				ended++;
			}
		}
		if (ended > 0) {
			store();
		}
		return ended;
	}

	/**
	 * Ends every session opened through the given share link, of every contact, see issue #217.
	 *
	 * <p>
	 * The link was deleted: the browsers that came in through it are signed out of it. The contacts
	 * themselves stay, with their names, addresses and the sessions they opened through other
	 * links.
	 * </p>
	 *
	 * @return How many sessions were ended.
	 */
	public synchronized int endSessionsOfLink(String link) throws IOException {
		if (link == null || link.isEmpty()) {
			return 0;
		}
		int ended = 0;
		for (Contact contact : _contacts) {
			for (Iterator<Session> it = contact._sessions.iterator(); it.hasNext();) {
				if (link.equals(it.next().getLink())) {
					it.remove();
					ended++;
				}
			}
		}
		if (ended > 0) {
			store();
		}
		return ended;
	}

	/**
	 * Ends one session of the given contact, see issue #203: that browser is signed out.
	 *
	 * @return Whether there was such a session.
	 */
	public synchronized boolean endSession(String contactId, String sessionId) throws IOException {
		Contact contact = get(contactId);
		if (contact == null || sessionId == null) {
			return false;
		}
		for (Iterator<Session> it = contact._sessions.iterator(); it.hasNext();) {
			if (it.next().getId().equals(sessionId)) {
				it.remove();
				store();
				return true;
			}
		}
		return false;
	}

	/**
	 * Ends every session of the given contact but the given one, see issue #203: "sign out others".
	 *
	 * @return How many were ended.
	 */
	public synchronized int endOtherSessions(String contactId, String keepSessionId) throws IOException {
		Contact contact = get(contactId);
		if (contact == null) {
			return 0;
		}
		int ended = 0;
		for (Iterator<Session> it = contact._sessions.iterator(); it.hasNext();) {
			if (!it.next().getId().equals(keepSessionId)) {
				it.remove();
				ended++;
			}
		}
		if (ended > 0) {
			store();
		}
		return ended;
	}

	/**
	 * On how many browsers besides the given session the given contact is signed in: their live
	 * sessions, see issue #203.
	 */
	public synchronized int otherSessions(String contactId, String sessionId) {
		Contact contact = get(contactId);
		if (contact == null) {
			return 0;
		}
		Instant now = Instant.now();
		int result = 0;
		for (Session session : contact._sessions) {
			if (!session.getId().equals(sessionId) && !session.isExpired(now)) {
				result++;
			}
		}
		return result;
	}

	/**
	 * Deletes the given contact from the register, see issue #203.
	 *
	 * <p>
	 * Deleting deletes: their name, addresses and sessions go, so every credential of theirs is
	 * from then on one this space never issued. What they uploaded keeps the name that was copied
	 * onto it at the upload (issue #53); what the share links remember of them is the
	 * {@link ShareStore}'s part, see {@link ShareStore#forgetContact(String)}.
	 * </p>
	 *
	 * @return The deleted contact, <code>null</code> if there is none.
	 */
	public synchronized Contact delete(String contactId) throws IOException {
		Contact contact = get(contactId);
		if (contact == null) {
			return null;
		}
		_contacts.remove(contact);
		store();
		return contact;
	}

	/**
	 * Gives the given contact another name in the space.
	 *
	 * <p>
	 * What their uploads are labelled with was copied at the upload and stays (issue #53); the
	 * owner screens of issue #203 offer this.
	 * </p>
	 *
	 * @return The contact, <code>null</code> if there is none.
	 * @throws Refused
	 *         For a blank name.
	 */
	public synchronized Contact rename(String contactId, String name) throws Refused, IOException {
		String given = name == null ? "" : name.trim();
		if (given.isEmpty()) {
			throw new Refused(NAME_REQUIRED);
		}
		Contact contact = get(contactId);
		if (contact != null) {
			contact.setName(given);
			store();
		}
		return contact;
	}

	/**
	 * Shuts the given contact out of the whole space, or lets them in again.
	 *
	 * <p>
	 * Shutting out ends every credential of the contact at once.
	 * </p>
	 *
	 * @return The contact, <code>null</code> if there is none.
	 */
	public synchronized Contact block(String contactId, boolean blocked) throws IOException {
		Contact contact = get(contactId);
		if (contact == null) {
			return null;
		}
		if (blocked) {
			if (!contact.isBlocked()) {
				contact._blocked = Instant.now().toString();
			}
			contact._sessions.clear();
		} else {
			contact._blocked = "";
		}
		store();
		return contact;
	}

	private String freeId() {
		while (true) {
			String id = randomId();
			if (get(id) == null) {
				return id;
			}
		}
	}

	private String freeSessionId() {
		while (true) {
			String id = randomId();
			if (session(id) == null) {
				return id;
			}
		}
	}

	private String randomId() {
		byte[] bytes = new byte[ID_BYTES];
		_random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	// --- Persistence. ---

	private void load() {
		if (!Files.exists(_file)) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			_contacts = readContacts(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A broken register must not lock the server up; it recognises nobody instead.
			LOG.log(Level.WARNING, "Cannot read the contacts '" + _file + "': " + ex.getMessage());
			_contacts = new ArrayList<>();
			_damaged = true;
		}
	}

	private static List<Contact> readContacts(JsonReader in) throws IOException {
		List<Contact> result = new ArrayList<>();
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case "version":
					version = in.nextInt();
					break;
				case "contacts":
					in.beginArray();
					while (in.hasNext()) {
						result.add(readContact(in));
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		if (version > VERSION) {
			LOG.warning("The contacts were written by a newer version (" + version + " > " + VERSION + ").");
		}
		return result;
	}

	private static Contact readContact(JsonReader in) throws IOException {
		String id = "";
		String name = "";
		String displayName = "";
		String created = "";
		String createdBy = "";
		String firstSeen = "";
		String lastSeen = "";
		String blocked = "";
		List<Address> addresses = new ArrayList<>();
		List<Session> sessions = new ArrayList<>();
		Authenticator totp = null;
		Authenticator totpPending = null;
		List<Passkey> passkeys = new ArrayList<>();
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case "id":
					id = in.nextString();
					break;
				case "name":
					name = in.nextString();
					break;
				case "displayName":
					displayName = in.nextString();
					break;
				case "created":
					created = in.nextString();
					break;
				case "createdBy":
					createdBy = in.nextString();
					break;
				case "firstSeen":
					firstSeen = in.nextString();
					break;
				case "lastSeen":
					lastSeen = in.nextString();
					break;
				case "blocked":
					blocked = in.nextString();
					break;
				case "addresses":
					in.beginArray();
					while (in.hasNext()) {
						addresses.add(readAddress(in));
					}
					in.endArray();
					break;
				case "sessions":
					in.beginArray();
					while (in.hasNext()) {
						sessions.add(readSession(in));
					}
					in.endArray();
					break;
				case "totp":
					totp = readAuthenticator(in);
					break;
				case "totpPending":
					totpPending = readAuthenticator(in);
					break;
				case "passkeys":
					in.beginArray();
					while (in.hasNext()) {
						Passkey passkey = readPasskey(in);
						if (!passkey.getId().isEmpty() && !passkey.getData().isEmpty()) {
							passkeys.add(passkey);
						}
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		Contact contact = new Contact(id, name, displayName, created, createdBy, firstSeen, lastSeen, blocked);
		contact._addresses.addAll(addresses);
		contact._totp = totp;
		contact._totpPending = totpPending;
		contact._passkeys.addAll(passkeys);
		Instant now = Instant.now();
		for (Session session : sessions) {
			// A credential that ran out is of no use to anybody; it is not carried on.
			if (!session.isExpired(now)) {
				contact._sessions.add(session);
			}
		}
		return contact;
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

	private static Address readAddress(JsonReader in) throws IOException {
		String kind = "";
		String value = "";
		boolean proven = false;
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case "kind":
					kind = in.nextString();
					break;
				case "value":
					value = in.nextString();
					break;
				case "proven":
					proven = in.nextBoolean();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return new Address(kind, value, proven);
	}

	private static Session readSession(JsonReader in) throws IOException {
		String id = "";
		String tokenHash = "";
		String link = "";
		String created = "";
		String expires = "";
		boolean remember = false;
		String lastUsed = "";
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case "id":
					id = in.nextString();
					break;
				case "tokenHash":
					tokenHash = in.nextString();
					break;
				case "link":
					link = in.nextString();
					break;
				case "created":
					created = in.nextString();
					break;
				case "expires":
					expires = in.nextString();
					break;
				case "remember":
					remember = in.nextBoolean();
					break;
				case "lastUsed":
					lastUsed = in.nextString();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return new Session(id, tokenHash, link, created, expires, remember, lastUsed);
	}

	/** Writes this store to disk, atomically. */
	public synchronized void store() throws IOException {
		Path directory = _file.getParent();
		Files.createDirectories(directory);
		if (_damaged && Files.exists(_file)) {
			Path broken = directory.resolve(FILE_NAME + ".broken-" + Instant.now().toString().replace(':', '-'));
			Files.move(_file, broken, StandardCopyOption.REPLACE_EXISTING);
			LOG.warning("Kept the unreadable contacts as '" + broken + "'.");
		}
		_damaged = false;

		Path tmpFile = Files.createTempFile(directory, "contacts", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name("version");
				out.value(VERSION);
				out.name("contacts");
				out.beginArray();
				for (Contact contact : _contacts) {
					writeContact(out, contact);
				}
				out.endArray();
				out.endObject();
			}
		}
		Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writeContact(JsonWriter out, Contact contact) throws IOException {
		out.beginObject();
		out.name("id");
		out.value(contact.getId());
		out.name("name");
		out.value(contact.getName());
		out.name("displayName");
		out.value(contact.getDisplayName());
		out.name("addresses");
		out.beginArray();
		for (Address address : contact._addresses) {
			out.beginObject();
			out.name("kind");
			out.value(address.getKind());
			out.name("value");
			out.value(address.getValue());
			out.name("proven");
			out.value(address.isProven());
			out.endObject();
		}
		out.endArray();
		out.name("created");
		out.value(contact.getCreated());
		out.name("createdBy");
		out.value(contact.getCreatedBy());
		out.name("firstSeen");
		out.value(contact.getFirstSeen());
		out.name("lastSeen");
		out.value(contact.getLastSeen());
		out.name("blocked");
		out.value(contact.getBlocked());
		out.name("sessions");
		out.beginArray();
		for (Session session : contact._sessions) {
			out.beginObject();
			out.name("id");
			out.value(session.getId());
			out.name("tokenHash");
			out.value(session.getTokenHash());
			out.name("link");
			out.value(session.getLink());
			out.name("created");
			out.value(session.getCreated());
			out.name("expires");
			out.value(session.getExpires());
			out.name("remember");
			out.value(session.isRemember());
			out.name("lastUsed");
			out.value(session.getLastUsed());
			out.endObject();
		}
		out.endArray();
		// Written only where there is one, so that a register without any reads as before.
		if (contact._totp != null) {
			out.name("totp");
			writeAuthenticator(out, contact._totp);
		}
		if (contact._totpPending != null) {
			out.name("totpPending");
			writeAuthenticator(out, contact._totpPending);
		}
		if (!contact._passkeys.isEmpty()) {
			out.name("passkeys");
			out.beginArray();
			for (Passkey passkey : contact._passkeys) {
				writePasskey(out, passkey);
			}
			out.endArray();
		}
		out.endObject();
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
