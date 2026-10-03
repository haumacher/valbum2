/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.passkeys;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Passkeys of the contacts of personal share links, see issue #204: WebAuthn registration and
 * assertion through <a href="https://github.com/webauthn4j/webauthn4j">webauthn4j</a>.
 *
 * <p>
 * One instance per server, like the sign-in through OpenID Connect: the relying party is the host
 * of <code>VALBUM_PUBLIC_URL</code> and the only origin accepted is that address's scheme, host and
 * port; without a public address there is nothing to bind a passkey to, and {@link #NONE} offers
 * none. A ceremony is started by the server, which makes a random challenge and keeps it in memory
 * under a single-use <em>ticket</em> for {@link #LIFETIME}, bound to the space and to whoever
 * started it; the browser answers with what <code>navigator.credentials</code> made, and the
 * answer is checked against that challenge, the origin and the relying party.
 * </p>
 *
 * <p>
 * A passkey is <em>discoverable</em> (resident): a sign-in names no credential, and the browser
 * offers whichever of the person's passkeys belong to this host, so that the identification card of
 * a group link or an open link reveals nobody's passkeys. Attestation is not asked for
 * (<code>none</code>): a passkey proves that the same authenticator signs again, which is all a
 * contact's sign-in needs. User verification is preferred, not required.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Passkeys {

	private static final Logger LOG = Logger.getLogger(Passkeys.class.getName());

	/** The name of the method, as <code>IdentifyRequired.methods</code> carries it. */
	public static final String METHOD = "passkey";

	/** How long a ceremony may take. */
	public static final Duration LIFETIME = Duration.ofMinutes(5);

	/** At most so many ceremonies under way. */
	public static final int MAX_PENDING = 10_000;

	/** What every request is answered where passkeys are not offered. */
	public static final String NOT_CONFIGURED =
		"This server offers no passkeys: it has no public address (VALBUM_PUBLIC_URL).";

	/** What an answer is refused with that belongs to no ceremony under way, or to another one. */
	public static final String TICKET_UNKNOWN = "The passkey request ran out. Start again.";

	/** What a registration is refused with that the browser's answer does not support. */
	public static final String REGISTRATION_FAILED = "The passkey could not be saved. Try again.";

	/**
	 * What a sign-in is refused with, whatever was wrong: a passkey this space does not know, one
	 * of somebody the link does not let in, a signature that does not hold.
	 */
	public static final String SIGN_IN_REFUSED = "This passkey does not open this link.";

	/** What is answered while too many ceremonies are under way. */
	public static final String TOO_MANY = "Too many passkey requests are under way. Try again in a few minutes.";

	/** An instance that offers nothing. */
	public static final Passkeys NONE = new Passkeys();

	private static final List<PublicKeyCredentialParameters> ALGORITHMS = List.of(
		new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
		new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.EdDSA),
		new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

	/** Why a request was not served. */
	public static final class Refused extends Exception {

		private final int _status;

		Refused(int status, String message) {
			super(message);
			_status = status;
		}

		/** The HTTP status to answer. */
		public int getStatus() {
			return _status;
		}
	}

	/** A ceremony that was started: the ticket naming it and the options for the browser. */
	public static final class Started {

		private final String _ticket;

		private final String _options;

		private final Instant _expires;

		Started(String ticket, String options, Instant expires) {
			_ticket = ticket;
			_options = options;
			_expires = expires;
		}

		/** The ticket the answer must name. */
		public String getTicket() {
			return _ticket;
		}

		/** The options for <code>navigator.credentials</code>, in their JSON form. */
		public String getOptions() {
			return _options;
		}

		/** Until when the answer is taken. */
		public Instant getExpires() {
			return _expires;
		}
	}

	/** What a sign-in proved: whose passkey it was, and what to write down about its use. */
	public static final class Asserted {

		private final ContactStore.Contact _contact;

		private final String _id;

		private final long _counter;

		private final boolean _backupState;

		Asserted(ContactStore.Contact contact, String id, long counter, boolean backupState) {
			_contact = contact;
			_id = id;
			_counter = counter;
			_backupState = backupState;
		}

		/** The contact whose passkey signed. */
		public ContactStore.Contact getContact() {
			return _contact;
		}

		/** The credential id. */
		public String getId() {
			return _id;
		}

		/** The signature counter the authenticator reported. */
		public long getCounter() {
			return _counter;
		}

		/** Whether the authenticator reported the passkey as synced. */
		public boolean isBackupState() {
			return _backupState;
		}
	}

	private enum Purpose {
		REGISTER, SIGN_IN;
	}

	private static final class Pending {

		final Purpose _purpose;

		final String _binding;

		final byte[] _challenge;

		final Instant _expires;

		Pending(Purpose purpose, String binding, byte[] challenge, Instant expires) {
			_purpose = purpose;
			_binding = binding;
			_challenge = challenge;
			_expires = expires;
		}
	}

	private final String _rpId;

	private final Origin _origin;

	private final Clock _clock;

	private final WebAuthnManager _manager;

	private final AttestedCredentialDataConverter _converter;

	private final SecureRandom _random = new SecureRandom();

	private final Map<String, Pending> _pending = new HashMap<>();

	private Passkeys() {
		_rpId = null;
		_origin = null;
		_clock = Clock.systemUTC();
		_manager = null;
		_converter = null;
	}

	/**
	 * Creates the passkeys of a server reached at the given public address.
	 *
	 * @param publicUrl
	 *        <code>VALBUM_PUBLIC_URL</code>: its host is the relying party, its scheme, host and port
	 *        the one origin a ceremony is accepted from.
	 */
	public Passkeys(String publicUrl, Clock clock) {
		URI uri = URI.create(publicUrl);
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		_rpId = uri.getHost().toLowerCase(Locale.ROOT);
		int port = uri.getPort();
		boolean standard = port < 0 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
		_origin = new Origin(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (standard ? "" : ":" + port));
		_clock = clock;
		ObjectConverter objects = new ObjectConverter();
		_manager = WebAuthnManager.createNonStrictWebAuthnManager(objects);
		_converter = new AttestedCredentialDataConverter(objects);
	}

	/** Whether passkeys are offered at all. */
	public boolean isAvailable() {
		return _manager != null;
	}

	/** The relying party: the host of the public address; <code>null</code> where none is offered. */
	public String getRpId() {
		return _rpId;
	}

	/** The one origin a ceremony is accepted from, <code>null</code> where none is offered. */
	public String getOrigin() {
		return _origin == null ? null : _origin.toString();
	}

	/**
	 * Starts registering a passkey for the given contact.
	 *
	 * @param binding
	 *        Who the ceremony belongs to: the space and the contact; the answer must name the same.
	 * @param rpName
	 *        The name of the relying party the browser shows: the space's.
	 * @param exclude
	 *        The contact's passkeys, which the authenticator need not make again.
	 */
	public Started registration(String binding, ContactStore.Contact contact, String rpName, List<String> exclude)
			throws Refused {
		available();
		byte[] challenge = random(32);
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			json.beginObject();
			json.name("rp");
			json.beginObject();
			json.name("id");
			json.value(_rpId);
			json.name("name");
			json.value(rpName);
			json.endObject();
			json.name("user");
			json.beginObject();
			json.name("id");
			json.value(base64Url(contact.getId().getBytes(StandardCharsets.UTF_8)));
			json.name("name");
			json.value(contact.greeting());
			json.name("displayName");
			json.value(contact.greeting());
			json.endObject();
			json.name("challenge");
			json.value(base64Url(challenge));
			json.name("pubKeyCredParams");
			json.beginArray();
			for (PublicKeyCredentialParameters parameters : ALGORITHMS) {
				json.beginObject();
				json.name("type");
				json.value("public-key");
				json.name("alg");
				json.value(parameters.getAlg().getValue());
				json.endObject();
			}
			json.endArray();
			json.name("timeout");
			json.value(LIFETIME.toMillis());
			json.name("excludeCredentials");
			descriptors(json, exclude);
			json.name("authenticatorSelection");
			json.beginObject();
			json.name("residentKey");
			json.value("required");
			json.name("requireResidentKey");
			json.value(true);
			json.name("userVerification");
			json.value("preferred");
			json.endObject();
			json.name("attestation");
			json.value("none");
			json.endObject();
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return start(Purpose.REGISTER, binding, challenge, buffer.toString());
	}

	/**
	 * Checks the browser's answer to a registration and answers the passkey to store.
	 *
	 * @throws Refused
	 *         <code>400</code> {@link #TICKET_UNKNOWN} for an answer to no registration of this
	 *         binding under way, {@link #REGISTRATION_FAILED} for one that does not hold.
	 */
	public ContactStore.Passkey register(String binding, String ticket, String response) throws Refused {
		available();
		Pending pending = take(ticket, Purpose.REGISTER, binding);
		RegistrationData data;
		try {
			data = _manager.verifyRegistrationResponseJSON(response,
				new RegistrationParameters(serverProperty(pending), ALGORITHMS, false, true));
		} catch (RuntimeException ex) {
			LOG.warning("Refusing a passkey registration: " + ex);
			throw new Refused(400, REGISTRATION_FAILED);
		}
		AuthenticatorData<?> authenticator = data.getAttestationObject().getAuthenticatorData();
		AttestedCredentialData credential = authenticator.getAttestedCredentialData();
		if (credential == null) {
			throw new Refused(400, REGISTRATION_FAILED);
		}
		List<String> transports = new ArrayList<>();
		if (data.getTransports() != null) {
			for (AuthenticatorTransport transport : data.getTransports()) {
				transports.add(transport.getValue());
			}
		}
		return new ContactStore.Passkey(base64Url(credential.getCredentialId()), now().toString(), "",
			base64Url(_converter.convert(credential)), authenticator.getSignCount(), authenticator.isFlagUV(),
			authenticator.isFlagBE(), authenticator.isFlagBS(), transports);
	}

	/**
	 * Starts a sign-in with a passkey.
	 *
	 * @param binding
	 *        Who the ceremony belongs to: the space, the link and whom the link asks for; the answer
	 *        must name the same.
	 */
	public Started signIn(String binding) throws Refused {
		available();
		byte[] challenge = random(32);
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			json.beginObject();
			json.name("challenge");
			json.value(base64Url(challenge));
			json.name("rpId");
			json.value(_rpId);
			json.name("timeout");
			json.value(LIFETIME.toMillis());
			json.name("userVerification");
			json.value("preferred");
			// Discoverable: the browser offers the person's own passkeys; nobody else's are named.
			json.name("allowCredentials");
			json.beginArray();
			json.endArray();
			json.endObject();
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return start(Purpose.SIGN_IN, binding, challenge, buffer.toString());
	}

	/**
	 * Checks the browser's answer to a sign-in.
	 *
	 * @param lookup
	 *        The passkey of a credential id in the caller's space, <code>null</code> for one it does
	 *        not know.
	 * @param admits
	 *        Whether the link lets the given contact in.
	 * @throws Refused
	 *         <code>400</code> {@link #TICKET_UNKNOWN} for an answer to no sign-in of this binding
	 *         under way; <code>403</code> {@link #SIGN_IN_REFUSED} for everything else, whatever
	 *         it was.
	 */
	public Asserted check(String binding, String ticket, String response,
			Function<String, ContactStore.PasskeyHolder> lookup,
			java.util.function.Predicate<ContactStore.Contact> admits) throws Refused {
		available();
		Pending pending = take(ticket, Purpose.SIGN_IN, binding);
		AuthenticationData data;
		try {
			data = _manager.parseAuthenticationResponseJSON(response);
		} catch (RuntimeException ex) {
			LOG.warning("Refusing an unreadable passkey: " + ex);
			throw new Refused(403, SIGN_IN_REFUSED);
		}
		String id = base64Url(data.getCredentialId());
		ContactStore.PasskeyHolder holder = lookup.apply(id);
		if (holder == null) {
			LOG.info("Refusing a passkey this space does not know.");
			throw new Refused(403, SIGN_IN_REFUSED);
		}
		ContactStore.Passkey passkey = holder.getPasskey();
		try {
			AttestedCredentialData credential = _converter.convert(Base64.getUrlDecoder().decode(passkey.getData()));
			Set<AuthenticatorTransport> transports = new HashSet<>();
			for (String transport : passkey.getTransports()) {
				transports.add(AuthenticatorTransport.create(transport));
			}
			CredentialRecordImpl record = new CredentialRecordImpl(new NoneAttestationStatement(),
				Boolean.valueOf(passkey.isUvInitialized()), Boolean.valueOf(passkey.isBackupEligible()),
				Boolean.valueOf(passkey.isBackupState()), passkey.getCounter(), credential, null, null, null,
				transports);
			_manager.verify(data, new AuthenticationParameters(serverProperty(pending), record, null, false, true));
		} catch (RuntimeException ex) {
			LOG.warning("Refusing the passkey of " + holder.getContact() + ": " + ex);
			throw new Refused(403, SIGN_IN_REFUSED);
		}
		// Checked only once the signature holds: a credential id alone says nothing about whom a
		// link lets in.
		if (!admits.test(holder.getContact())) {
			LOG.info("Refusing the passkey of " + holder.getContact() + ", whom the link does not let in.");
			throw new Refused(403, SIGN_IN_REFUSED);
		}
		AuthenticatorData<?> authenticator = data.getAuthenticatorData();
		return new Asserted(holder.getContact(), id, authenticator.getSignCount(), authenticator.isFlagBS());
	}

	/** Now, by the clock of this instance. */
	public Instant now() {
		return _clock.instant();
	}

	private void available() throws Refused {
		if (!isAvailable()) {
			throw new Refused(501, NOT_CONFIGURED);
		}
	}

	private synchronized Started start(Purpose purpose, String binding, byte[] challenge, String options)
			throws Refused {
		Instant now = _clock.instant();
		prune(now);
		if (_pending.size() >= MAX_PENDING) {
			throw new Refused(503, TOO_MANY);
		}
		String ticket = base64Url(random(18));
		Instant expires = now.plus(LIFETIME);
		_pending.put(ticket, new Pending(purpose, binding, challenge, expires));
		return new Started(ticket, options, expires);
	}

	/** Takes the ceremony of the given ticket out, whatever comes of it: an answer is taken once. */
	private synchronized Pending take(String ticket, Purpose purpose, String binding) throws Refused {
		Instant now = _clock.instant();
		prune(now);
		Pending pending = ticket == null ? null : _pending.remove(ticket);
		if (pending == null || pending._purpose != purpose || !pending._binding.equals(binding)) {
			throw new Refused(400, TICKET_UNKNOWN);
		}
		return pending;
	}

	private void prune(Instant now) {
		for (Iterator<Pending> it = _pending.values().iterator(); it.hasNext();) {
			if (!it.next()._expires.isAfter(now)) {
				it.remove();
			}
		}
	}

	private ServerProperty serverProperty(Pending pending) {
		return new ServerProperty(_origin, _rpId, new DefaultChallenge(pending._challenge));
	}

	private static void descriptors(JsonWriter json, List<String> ids) throws IOException {
		json.beginArray();
		for (String id : ids) {
			json.beginObject();
			json.name("type");
			json.value("public-key");
			json.name("id");
			json.value(id);
			json.endObject();
		}
		json.endArray();
	}

	private byte[] random(int length) {
		byte[] result = new byte[length];
		_random.nextBytes(result);
		return result;
	}

	/** Base64url without padding, the encoding of WebAuthn's JSON. */
	public static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
