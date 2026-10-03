/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.oidc;

import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import de.haumacher.imageServer.auth.ContactStore;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.exception.http.RedirectionAction;
import org.pac4j.core.exception.http.WithLocationAction;
import org.pac4j.core.http.callback.NoParameterCallbackUrlResolver;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.oidc.client.OidcClient;
import org.pac4j.oidc.config.OidcConfiguration;
import org.pac4j.oidc.profile.OidcProfile;
import org.pac4j.oidc.profile.OidcProfileDefinition;

/**
 * "Continue with Google": proving an e-mail address through OpenID Connect, see issue #200.
 *
 * <p>
 * The mechanism, one instance for the whole server like the mailed code of issue #199; who may prove
 * which address and what a proven address makes of them stays <code>AddressProof</code>'s, so that a
 * provider's sign-in ends exactly as a right code does. The flow is the authorization code with PKCE,
 * scopes <code>openid email profile</code>, through pac4j:
 * </p>
 * <ol>
 * <li><b>Start</b> (<code>&lt;data&gt;/?action=oidc-start</code>, the link's token as the bearer):
 * {@link #start} remembers who started what &mdash; the space, the link, the person the link was
 * asking for, the address of the link to return to &mdash; under a fresh <code>state</code> and
 * answers the provider's address and a {@link Started#getBinding() binding}, a secret only the
 * starter holds.</li>
 * <li><b>Callback</b> (<code>&lt;context&gt;/oidc/callback</code>, one address for every space):
 * {@link #callback} takes the <code>state</code> back exactly once, lets pac4j exchange the code
 * (with the PKCE verifier) and check the ID token (signature by the provider's keys, issuer,
 * audience, expiry, nonce), accepts the address only with <code>email_verified: true</code>, and
 * answers where the browser goes: the link it came from, with a one-time code behind
 * <code>#oidc=</code>. A refusal travels the same way, so the page says it in its own place.</li>
 * <li><b>Exchange</b> (<code>&lt;data&gt;/?action=oidc-exchange</code>, the link's token again):
 * {@link #redeem} hands the result out once, in the space it was started in, to whoever shows the
 * binding; the servlet then asks the live caller everything a mailed code would be asked.</li>
 * </ol>
 *
 * <p>
 * <b>The state</b> is <code>&lt;space&gt;.&lt;nonce&gt;.&lt;mac&gt;</code>: the space's segment
 * (base64url) and 24 random bytes, signed with a key that lives as long as the process (HMAC-SHA256).
 * What it stands for is kept here in memory only, for {@link #LIFETIME}, and taken out at the first
 * callback that names it: a replayed, forged, expired or unknown state is refused before anything
 * is asked of the provider, and a restart forgets every sign-in under way. <b>The credential</b>
 * never travels in an address: what lands in the browser's history is a code that is worth nothing
 * without the link's token and the binding, works once, and dies after {@link #EXCHANGE_LIFETIME}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class OidcLogins {

	private static final Logger LOG = Logger.getLogger(OidcLogins.class.getName());

	/** The prefix of the name of a method that signs in through a provider: <code>oidc:google</code>. */
	public static final String METHOD_PREFIX = "oidc:";

	/** The segment below the context root the callback lives at. */
	public static final String URL_SEGMENT = "oidc";

	/** The callback, below {@link #URL_SEGMENT}: <code>&lt;context&gt;/oidc/callback</code>. */
	public static final String CALLBACK = "callback";

	/** The name of the code behind the <code>#</code> of the link the browser returns to. */
	public static final String FRAGMENT = "oidc";

	/** The scopes asked for. */
	public static final String SCOPES = "openid email profile";

	/** How long a started sign-in may take until the provider sends the browser back. */
	public static final Duration LIFETIME = Duration.ofMinutes(10);

	/** How long the code of a finished sign-in may wait for its exchange. */
	public static final Duration EXCHANGE_LIFETIME = Duration.ofMinutes(5);

	/** How many sign-ins may be under way at once, so that nobody fills the memory with starts. */
	public static final int MAX_PENDING = 10_000;

	/** How long the server waits for a provider. */
	static final int TIMEOUT_MILLIS = 10_000;

	/** What a server without a provider (or without a public address) answers a sign-in. */
	public static final String NOT_CONFIGURED = "Signing in with another account is not set up on this server.";

	/** What a callback is answered whose state is not one this server has under way. */
	public static final String STATE_REFUSED = "This sign-in cannot be finished here: it was finished "
		+ "already, it took too long, or it was not started on this server. Go back to the link and start "
		+ "again.";

	/** What an exchange is answered whose code is unknown, used, run out, or not this link's. */
	public static final String EXCHANGE_UNKNOWN = "This sign-in is no longer valid. Start it again from the link.";

	/** What a start is answered while {@link #MAX_PENDING} sign-ins are under way. */
	public static final String TOO_MANY = "Too many sign-ins are under way. Try again in a few minutes.";

	/** What a start naming no configured provider is answered. */
	public static String providerUnknown(String id) {
		return "There is no sign-in with '" + id + "' on this server.";
	}

	/** What a start is answered where the provider cannot be reached. */
	public static String unreachable(String label) {
		return label + " cannot be reached right now. Try again later, or confirm your e-mail address another way.";
	}

	/** What the visitor is told where the provider sent them back without a sign-in. */
	public static String cancelled(String label) {
		return "The sign-in with " + label + " was cancelled or refused.";
	}

	/** What the visitor is told where the provider's answer could not be confirmed. */
	public static String failed(String label) {
		return "The sign-in with " + label + " could not be confirmed. Try again.";
	}

	/** What the visitor is told whose address the provider has not confirmed. */
	public static String notVerified(String label) {
		return label + " has not confirmed this e-mail address. Confirm it another way, or sign in with an "
			+ "account whose address is confirmed.";
	}

	/** Nothing configured: no method is offered, the callback is not there. */
	public static final OidcLogins NONE = new OidcLogins(null, List.of(), Clock.systemUTC());

	/** The pac4j session key the state of a sign-in is handed to pac4j's state generator under. */
	private static final String STATE_KEY = "valbum.oidc.state";

	/** A refusal of a sign-in, with the status to answer it with. */
	public static final class Refused extends Exception {

		private final int _status;

		/** Creates a {@link Refused}. */
		public Refused(int status, String message) {
			super(message);
			_status = status;
		}

		/** The HTTP status to answer. */
		public int getStatus() {
			return _status;
		}
	}

	/**
	 * What a sign-in is started for, see {@link OidcLogins#start}: everything the exchange must find
	 * again, decided by whoever started it.
	 */
	public static final class Start {

		private final String _space;

		private final String _link;

		private final String _kind;

		private final String _contact;

		private final String _returnUrl;

		private final boolean _remember;

		private final String _displayName;

		/**
		 * Creates a {@link Start}.
		 *
		 * @param space
		 *        The segment of the space, empty in a single-space server.
		 * @param link
		 *        The id of the share link.
		 * @param kind
		 *        What the caller had to prove, as <code>AddressProof</code> tells it.
		 * @param contact
		 *        The contact the link asked for, empty where it named nobody.
		 * @param returnUrl
		 *        The absolute address of the link the browser returns to.
		 * @param remember
		 *        Whether to remember the browser.
		 * @param displayName
		 *        The name the visitor gave, empty for the provider's.
		 */
		public Start(String space, String link, String kind, String contact, String returnUrl, boolean remember,
				String displayName) {
			_space = space;
			_link = link;
			_kind = kind;
			_contact = contact == null ? "" : contact;
			_returnUrl = returnUrl;
			_remember = remember;
			_displayName = displayName == null ? "" : displayName.trim();
		}

		/** The segment of the space. */
		public String getSpace() {
			return _space;
		}

		/** The id of the share link. */
		public String getLink() {
			return _link;
		}

		/** What the caller had to prove. */
		public String getKind() {
			return _kind;
		}

		/** The contact the link asked for, empty for nobody. */
		public String getContact() {
			return _contact;
		}

		/** The address of the link to return to. */
		public String getReturnUrl() {
			return _returnUrl;
		}

		/** Whether to remember the browser. */
		public boolean isRemember() {
			return _remember;
		}

		/** The name the visitor gave, empty for none. */
		public String getDisplayName() {
			return _displayName;
		}
	}

	/** A started sign-in, the answer of {@link OidcLogins#start}. */
	public static final class Started {

		private final String _url;

		private final String _binding;

		private final Instant _expires;

		Started(String url, String binding, Instant expires) {
			_url = url;
			_binding = binding;
			_expires = expires;
		}

		/** The provider's address the browser goes to. */
		public String getUrl() {
			return _url;
		}

		/** The secret the exchange must show. */
		public String getBinding() {
			return _binding;
		}

		/** Until when the sign-in may be finished. */
		public Instant getExpires() {
			return _expires;
		}
	}

	/** What a finished sign-in says, handed out once by {@link OidcLogins#redeem}. */
	public static final class Proven {

		private final Start _start;

		private final OidcProvider _provider;

		private final String _email;

		private final String _name;

		private final int _status;

		private final String _refusal;

		Proven(Start start, OidcProvider provider, String email, String name, int status, String refusal) {
			_start = start;
			_provider = provider;
			_email = email;
			_name = name;
			_status = status;
			_refusal = refusal;
		}

		/** What the sign-in was started for. */
		public Start getStart() {
			return _start;
		}

		/** The provider that proved the address. */
		public OidcProvider getProvider() {
			return _provider;
		}

		/** The proven address, normalised; empty for a refusal. */
		public String getEmail() {
			return _email;
		}

		/** The name the provider knows the person by, cleaned; empty where it says none. */
		public String getName() {
			return _name;
		}

		/** Whether the sign-in was refused, see {@link #getRefusal()}. */
		public boolean isRefused() {
			return _refusal != null;
		}

		/** The status of a refusal. */
		public int getStatus() {
			return _status;
		}

		/** Why the sign-in was refused, <code>null</code> where it was not. */
		public String getRefusal() {
			return _refusal;
		}
	}

	/** A sign-in between its start and its callback. */
	private static final class Pending {

		final Start _start;

		final OidcProvider _provider;

		final byte[] _bindingHash;

		final Instant _expires;

		/** What pac4j keeps between the two halves: its state, the nonce, the PKCE verifier. */
		final Map<String, Object> _session = new HashMap<>();

		Pending(Start start, OidcProvider provider, byte[] bindingHash, Instant expires) {
			_start = start;
			_provider = provider;
			_bindingHash = bindingHash;
			_expires = expires;
		}
	}

	/** A finished sign-in waiting for its exchange. */
	private static final class Finished {

		final Proven _proven;

		final byte[] _bindingHash;

		final Instant _expires;

		Finished(Proven proven, byte[] bindingHash, Instant expires) {
			_proven = proven;
			_bindingHash = bindingHash;
			_expires = expires;
		}
	}

	private final String _publicUrl;

	private final List<OidcProvider> _providers;

	private final Clock _clock;

	private final SecureRandom _random = new SecureRandom();

	private final byte[] _key = new byte[32];

	private final Map<String, Pending> _pending = new ConcurrentHashMap<>();

	private final Map<String, Finished> _finished = new ConcurrentHashMap<>();

	private final Map<String, OidcClient> _clients = new HashMap<>();

	/**
	 * Creates the sign-in of a server.
	 *
	 * @param publicUrl
	 *        The public address of the context root (<code>VALBUM_PUBLIC_URL</code>), which the
	 *        provider's redirect address is spelled from; <code>null</code> offers nothing.
	 * @param providers
	 *        The configured providers.
	 * @param clock
	 *        The clock lifetimes are measured by.
	 */
	public OidcLogins(String publicUrl, List<OidcProvider> providers, Clock clock) {
		_publicUrl = publicUrl;
		_providers = Collections.unmodifiableList(new ArrayList<>(providers));
		_clock = clock;
		_random.nextBytes(_key);
	}

	/** Whether a sign-in can be offered: a public address and at least one provider. */
	public boolean isAvailable() {
		return _publicUrl != null && !_providers.isEmpty();
	}

	/** The providers offered, empty where nothing is available. */
	public List<OidcProvider> getProviders() {
		return isAvailable() ? _providers : List.of();
	}

	/** The provider of the given id, <code>null</code> where none is offered. */
	public OidcProvider provider(String id) {
		for (OidcProvider provider : getProviders()) {
			if (provider.getId().equals(id)) {
				return provider;
			}
		}
		return null;
	}

	/** The public address of the context root, <code>null</code> where none is configured. */
	public String getPublicUrl() {
		return _publicUrl;
	}

	/** The redirect address registered with every provider: <code>&lt;public url&gt;/oidc/callback</code>. */
	public String redirectUri() {
		return redirectUri(_publicUrl);
	}

	/** The redirect address spelled from the given public address of the context root. */
	public static String redirectUri(String publicUrl) {
		return publicUrl + "/" + URL_SEGMENT + "/" + CALLBACK;
	}

	// --- Start. ---

	/**
	 * Starts a sign-in with the given provider.
	 *
	 * @throws Refused
	 *         <code>503</code> where too many are under way or the provider cannot be reached.
	 */
	public Started start(OidcProvider provider, Start start) throws Refused {
		prune();
		if (_pending.size() >= MAX_PENDING) {
			throw new Refused(HttpServletResponse.SC_SERVICE_UNAVAILABLE, TOO_MANY);
		}
		OidcClient client = client(provider);
		String nonce = random(24);
		String state = encode(start.getSpace()) + "." + nonce + "." + mac(start.getSpace(), nonce);
		String binding = random(32);
		Instant expires = _clock.instant().plus(LIFETIME);
		Pending pending = new Pending(start, provider, hash(binding), expires);
		pending._session.put(STATE_KEY, state);
		RedirectionAction action;
		try {
			action = client.getRedirectionAction(context("POST", redirectUri(), Map.of(), pending)).orElse(null);
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot start a sign-in with " + provider + ": " + ex.getMessage(), ex);
			throw new Refused(HttpServletResponse.SC_SERVICE_UNAVAILABLE, unreachable(provider.getLabel()));
		}
		if (!(action instanceof WithLocationAction)) {
			LOG.warning("Cannot start a sign-in with " + provider + ": pac4j answered " + action + ".");
			throw new Refused(HttpServletResponse.SC_SERVICE_UNAVAILABLE, unreachable(provider.getLabel()));
		}
		_pending.put(nonce, pending);
		LOG.info("Started a sign-in with " + provider + " on the share link " + start.getLink() + ".");
		return new Started(((WithLocationAction) action).getLocation(), binding, expires);
	}

	/** The pac4j client of the given provider, made and initialised (the discovery read) at first use. */
	private OidcClient client(OidcProvider provider) throws Refused {
		synchronized (_clients) {
			OidcClient existing = _clients.get(provider.getId());
			if (existing != null) {
				return existing;
			}
			OidcConfiguration config = new OidcConfiguration();
			config.setClientId(provider.getClientId());
			config.setSecret(provider.getSecret());
			config.setDiscoveryURI(provider.getDiscoveryUrl());
			config.setScope(SCOPES);
			config.setResponseType("code");
			config.setWithState(true);
			config.setUseNonce(true);
			config.setDisablePkce(false);
			config.setPkceMethod(CodeChallengeMethod.S256);
			// Everything is read from the ID token, which is signed; the user-info endpoint would only
			// add what an unsigned answer says.
			config.setCallUserInfoEndpoint(false);
			config.setConnectTimeout(TIMEOUT_MILLIS);
			config.setReadTimeout(TIMEOUT_MILLIS);
			// The state is the server's own, see the class comment.
			config.setStateGenerator(ctx -> (String) ctx.sessionStore().get(ctx.webContext(), STATE_KEY)
				.orElseThrow(() -> new IllegalStateException("No state.")));
			OidcClient client = new OidcClient(config);
			client.setName("oidc-" + provider.getId());
			client.setCallbackUrl(redirectUri());
			client.setCallbackUrlResolver(new NoParameterCallbackUrlResolver());
			try {
				client.init();
			} catch (RuntimeException ex) {
				LOG.log(Level.WARNING, "Cannot read the configuration of " + provider + " at "
					+ provider.getDiscoveryUrl() + ": " + ex.getMessage(), ex);
				throw new Refused(HttpServletResponse.SC_SERVICE_UNAVAILABLE, unreachable(provider.getLabel()));
			}
			_clients.put(provider.getId(), client);
			return client;
		}
	}

	// --- Callback. ---

	/**
	 * Finishes the provider's half of a sign-in: the browser came back with the given parameters.
	 *
	 * @return Where the browser goes: the link it came from, with the code of the exchange behind
	 *         <code>#oidc=</code> &mdash; for a sign-in that worked and for one that was refused alike.
	 * @throws Refused
	 *         <code>400</code> {@link #STATE_REFUSED} for a state that is not one under way; nothing
	 *         is asked of the provider then.
	 */
	public String callback(Map<String, String[]> parameters) throws Refused {
		prune();
		String state = parameter(parameters, "state");
		String nonce = verifiedNonce(state);
		Pending pending = nonce == null ? null : _pending.remove(nonce);
		if (pending == null || pending._expires.isBefore(_clock.instant())) {
			LOG.warning("Refusing a sign-in callback: " + (nonce == null ? "no valid state" : "no sign-in under way for its state")
				+ ".");
			throw new Refused(HttpServletResponse.SC_BAD_REQUEST, STATE_REFUSED);
		}
		Proven proven = verify(pending, parameters);
		String code = random(32);
		_finished.put(code, new Finished(proven, pending._bindingHash, _clock.instant().plus(EXCHANGE_LIFETIME)));
		return pending._start.getReturnUrl() + "#" + FRAGMENT + "=" + code;
	}

	/** The nonce of the given state where its signature holds, <code>null</code> otherwise. */
	private String verifiedNonce(String state) {
		if (state == null) {
			return null;
		}
		String[] parts = state.split("\\.", -1);
		if (parts.length != 3) {
			return null;
		}
		String space;
		try {
			space = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException ex) {
			return null;
		}
		byte[] expected = mac(space, parts[1]).getBytes(StandardCharsets.US_ASCII);
		if (!MessageDigest.isEqual(expected, parts[2].getBytes(StandardCharsets.US_ASCII))) {
			return null;
		}
		Pending pending = _pending.get(parts[1]);
		if (pending != null && !pending._start.getSpace().equals(space)) {
			return null;
		}
		return parts[1];
	}

	/** What the provider's answer proves, a refusal included. */
	private Proven verify(Pending pending, Map<String, String[]> parameters) {
		OidcProvider provider = pending._provider;
		String label = provider.getLabel();
		Start start = pending._start;
		String error = parameter(parameters, "error");
		if (error != null) {
			LOG.info("The sign-in with " + provider + " ended without an identity: " + printable(error) + ".");
			return refused(start, provider, HttpServletResponse.SC_FORBIDDEN, cancelled(label));
		}
		if (parameter(parameters, "code") == null) {
			LOG.warning("The sign-in with " + provider + " came back without a code.");
			return refused(start, provider, HttpServletResponse.SC_BAD_REQUEST, failed(label));
		}
		for (String foreign : List.of("id_token", "access_token", "token", "logout_token", "sid")) {
			// Only the code is taken from the address; a token in it would be the browser's word.
			if (parameters.containsKey(foreign)) {
				LOG.warning("The sign-in with " + provider + " came back carrying '" + foreign + "'.");
				return refused(start, provider, HttpServletResponse.SC_BAD_REQUEST, failed(label));
			}
		}
		OidcProfile profile;
		try {
			OidcClient client = client(provider);
			CallContext context = context("GET", redirectUri(), parameters, pending);
			Credentials credentials = client.getCredentials(context).orElse(null);
			Credentials validated = credentials == null ? null : client.validateCredentials(context, credentials).orElse(null);
			UserProfile user = validated == null ? null : client.getUserProfile(context, validated).orElse(null);
			profile = user instanceof OidcProfile ? (OidcProfile) user : null;
		} catch (Refused ex) {
			return refused(start, provider, ex.getStatus(), ex.getMessage());
		} catch (RuntimeException ex) {
			LOG.warning("Cannot confirm the sign-in with " + provider + ": " + ex.getMessage());
			LOG.log(Level.FINE, "The refusal in full.", ex);
			profile = null;
		}
		if (profile == null || profile.getIdTokenString() == null) {
			LOG.warning("The sign-in with " + provider + " gave no ID token that holds.");
			return refused(start, provider, HttpServletResponse.SC_FORBIDDEN, failed(label));
		}
		Object verified = profile.getAttribute(OidcProfileDefinition.EMAIL_VERIFIED);
		String email = profile.getEmail();
		if (!Boolean.TRUE.equals(verified) || email == null || email.isBlank()) {
			LOG.info("The sign-in with " + provider + " named no confirmed address (email_verified: " + verified + ").");
			return refused(start, provider, HttpServletResponse.SC_FORBIDDEN, notVerified(label));
		}
		String normalized;
		try {
			normalized = ContactStore.normalize(ContactStore.EMAIL, email);
			new InternetAddress(normalized, true);
		} catch (ContactStore.Refused | AddressException ex) {
			LOG.warning("The sign-in with " + provider + " named an address that is none.");
			return refused(start, provider, HttpServletResponse.SC_FORBIDDEN, notVerified(label));
		}
		LOG.info("The sign-in with " + provider + " confirmed an address for the share link " + start.getLink() + ".");
		return new Proven(start, provider, normalized, cleanName(profile.getDisplayName()), HttpServletResponse.SC_OK,
			null);
	}

	private static Proven refused(Start start, OidcProvider provider, int status, String message) {
		return new Proven(start, provider, "", "", status, message);
	}

	/**
	 * The name the provider knows a person by, as a display name: control characters taken out,
	 * blanks collapsed, at most 100 characters. Plain text, escaped by whatever shows it.
	 */
	static String cleanName(String name) {
		if (name == null) {
			return "";
		}
		String plain = name.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ").replaceAll("\\s+", " ").trim();
		return plain.length() > 100 ? plain.substring(0, 100).trim() : plain;
	}

	/** A provider's word for the log: printable ASCII only, short. */
	private static String printable(String text) {
		String plain = text.replaceAll("[^\\x20-\\x7E]", "?");
		return plain.length() > 80 ? plain.substring(0, 80) : plain;
	}

	// --- Exchange. ---

	/**
	 * Hands out the result of a finished sign-in, once.
	 *
	 * @param space
	 *        The space asking; a sign-in started in another space is unknown here.
	 * @param code
	 *        The code from <code>#oidc=</code>.
	 * @param binding
	 *        The binding the start answered.
	 * @return What the sign-in says, <code>null</code> where the code is unknown, used, run out,
	 *         of another space, or shown without its binding &mdash; in every such case the code is
	 *         used up.
	 */
	public Proven redeem(String space, String code, String binding) {
		prune();
		if (code == null || code.isEmpty()) {
			return null;
		}
		Finished finished = _finished.remove(code);
		if (finished == null || finished._expires.isBefore(_clock.instant())) {
			return null;
		}
		if (!finished._proven.getStart().getSpace().equals(space)) {
			LOG.warning("Refusing the exchange of a sign-in of another space.");
			return null;
		}
		if (binding == null || !MessageDigest.isEqual(finished._bindingHash, hash(binding))) {
			LOG.warning("Refusing the exchange of a sign-in without the binding of its start.");
			return null;
		}
		return finished._proven;
	}

	// --- Helpers. ---

	/** Drops every sign-in that ran out. */
	private void prune() {
		Instant now = _clock.instant();
		for (Iterator<Pending> it = _pending.values().iterator(); it.hasNext();) {
			if (it.next()._expires.isBefore(now)) {
				it.remove();
			}
		}
		for (Iterator<Finished> it = _finished.values().iterator(); it.hasNext();) {
			if (it.next()._expires.isBefore(now)) {
				it.remove();
			}
		}
	}

	private static CallContext context(String method, String url, Map<String, String[]> parameters,
			Pending pending) {
		return new CallContext(new FlowContext(method, url, parameters), new MapSessionStore(pending._session));
	}

	private static String parameter(Map<String, String[]> parameters, String name) {
		String[] values = parameters.get(name);
		return values == null || values.length == 0 ? null : values[0];
	}

	private String random(int bytes) {
		byte[] value = new byte[bytes];
		_random.nextBytes(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static String encode(String text) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	private String mac(String space, String nonce) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(_key, "HmacSHA256"));
			byte[] value = mac.doFinal((space + "\n" + nonce).getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("No HMAC-SHA256 in this Java runtime.", ex);
		}
	}

	private static byte[] hash(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("No SHA-256 in this Java runtime.", ex);
		}
	}

	/** pac4j's memory of one sign-in: a map of its own, never an HTTP session. */
	private static final class MapSessionStore implements SessionStore {

		private final Map<String, Object> _values;

		MapSessionStore(Map<String, Object> values) {
			_values = values;
		}

		@Override
		public Optional<String> getSessionId(WebContext context, boolean createSession) {
			return Optional.of("oidc");
		}

		@Override
		public Optional<Object> get(WebContext context, String key) {
			return Optional.ofNullable(_values.get(key));
		}

		@Override
		public void set(WebContext context, String key, Object value) {
			if (value == null || "".equals(value)) {
				_values.remove(key);
			} else {
				_values.put(key, value);
			}
		}

		@Override
		public boolean destroySession(WebContext context) {
			_values.clear();
			return true;
		}

		@Override
		public Optional<Object> getTrackableSession(WebContext context) {
			return Optional.empty();
		}

		@Override
		public Optional<SessionStore> buildFromTrackableSession(WebContext context, Object trackableSession) {
			return Optional.empty();
		}

		@Override
		public boolean renewSession(WebContext context) {
			return false;
		}
	}
}
