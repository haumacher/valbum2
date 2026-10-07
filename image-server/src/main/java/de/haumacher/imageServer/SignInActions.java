/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.ImageServlet.Context;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.SignInHolder;
import de.haumacher.imageServer.auth.SignInRegister;
import de.haumacher.imageServer.auth.SignIns;
import de.haumacher.imageServer.auth.Totp;
import de.haumacher.imageServer.auth.TotpSignIns;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.passkeys.Passkeys;
import de.haumacher.imageServer.shared.model.ContactSignIns;
import de.haumacher.imageServer.shared.model.PasskeyOptions;
import de.haumacher.imageServer.shared.model.PasskeyResponse;
import de.haumacher.imageServer.shared.model.SignInRemove;
import de.haumacher.imageServer.shared.model.TotpCode;
import de.haumacher.imageServer.shared.model.TotpSetup;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * The requests of the ways a contact is recognised on another browser besides their link: an
 * authenticator app (issue #208) and passkeys (issue #204) &mdash; and since issue #233 the same for
 * a member, whose holder the server picks from the caller.
 *
 * <p>
 * Two sides. <b>The contact</b>, in a session of a personal link, sets an authenticator app up
 * (<code>?action=totp-setup</code>, then <code>?action=totp-confirm</code> with one code of it) and
 * removes it (<code>?action=remove-sign-in</code>); <b>a visitor not recognised yet</b> signs in
 * with a code (<code>?action=totp-verify</code>), which ends exactly as a proven address does
 * ({@link AddressProof#identified}). <b>A member who manages the contacts</b> removes a contact's
 * authenticator (<code>?action=remove-contact-sign-in</code>, the rights of
 * <code>?action=block-contact</code>); <b>the administrator</b> a member's
 * (<code>?action=remove-user-sign-in</code>).
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class SignInActions {

	private static final Logger LOG = Logger.getLogger(SignInActions.class.getName());

	/** The method name of an authenticator app in a {@link SignInRemove}. */
	static final String TOTP = TotpSignIns.METHOD;

	/** The method name of a member's proven e-mail address in a {@link SignInRemove} (issue #233). */
	static final String EMAIL = "email";

	/**
	 * What setting up a way to sign in is refused to a caller who is neither a signed-in member nor a
	 * recognised contact.
	 */
	static final String SIGN_INS_REFUSED =
		"Only a signed-in member, or a person recognised on a personal link, sets up their own ways to sign in.";

	/** What an unreadable request is answered. */
	static final String UNREADABLE = "The request about a way to sign in cannot be read.";

	/** What a request to remove a way to sign in is answered where there is none. */
	static final String NO_SIGN_IN = "There is no such way to sign in (any more).";

	/** What a request naming a method this server does not know is answered. */
	static String methodUnknown(String method) {
		return "There is no way to sign in called '" + method + "'.";
	}

	private final ImageServlet _servlet;

	private final AuthService _auth;

	private final String _issuer;

	private final Passkeys _passkeys;

	private final String _space;

	SignInActions(ImageServlet servlet, AuthService auth, String issuer, Passkeys passkeys, String space) {
		_servlet = servlet;
		_auth = auth;
		_issuer = issuer;
		_passkeys = passkeys;
		_space = space;
	}

	/** Answers the given action, if it is one of these; answers whether it was. */
	boolean handle(String action, Context context) throws IOException {
		switch (action == null ? "" : action) {
			case "totp-verify":
				totpVerify(context);
				return true;
			case "totp-setup":
				totpSetup(context);
				return true;
			case "totp-confirm":
				totpConfirm(context);
				return true;
			case "remove-sign-in":
				removeOwn(context);
				return true;
			case "remove-contact-sign-in":
				removeContacts(context);
				return true;
			case "remove-user-sign-in":
				removeUsers(context);
				return true;
			case "passkey-register-start":
				passkeyRegisterStart(context);
				return true;
			case "passkey-register":
				passkeyRegister(context);
				return true;
			case "passkey-start":
				passkeyStart(context);
				return true;
			case "passkey-verify":
				passkeyVerify(context);
				return true;
			default:
				return false;
		}
	}

	/**
	 * What the given holder's ways to sign in are, as they are told: a contact in their session, a
	 * member in their devices (issue #233), who are told their proven addresses too.
	 */
	static ContactSignIns signIns(SignInHolder holder, Passkeys passkeys) {
		SignIns.Authenticator authenticator = holder.getSignIns().getAuthenticator();
		ContactSignIns result = ContactSignIns.create()
			.setAuthenticator(authenticator == null ? "" : authenticator.getSince())
			.setPasskeysOffered(passkeys.isAvailable());
		for (SignIns.Passkey passkey : holder.getSignIns().getPasskeys()) {
			result.addPasskey(PersonalLinks.passkey(passkey));
		}
		if (holder instanceof UserStore.User user) {
			for (ContactStore.Address address : user.getAddresses()) {
				result.addAddresse(PersonalLinks.address(address));
			}
		}
		return result;
	}

	/** A holder of ways to sign in, with the register keeping them. */
	private record Own(SignInRegister register, SignInHolder holder) {
		// Nothing but the pair.
	}

	// --- A visitor signing in. ---

	private void totpVerify(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.isShareGone()) {
			_servlet.gone(context, caller);
			return;
		}
		if (_auth.getTotp() == null || caller.getIdentification() == null) {
			if (caller.mustIdentify()) {
				_servlet.gone(context, caller);
			} else {
				LOG.warning("Refusing a code of an authenticator app outside a personal link that asks who this is.");
				ImageServlet.errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AddressProof.TOTP_NOT_HERE);
			}
			return;
		}
		TotpCode request = readCode(context);
		if (request == null) {
			return;
		}
		try {
			ImageServlet.serveJsonObject(context.response(), _servlet.addressProof().verifyTotp(caller,
				request.getCode(), request.getAddress(), context.request().getRemoteAddr(), request.isRemember(),
				request.getDisplayName(), request.getDeviceName()));
		} catch (TotpSignIns.Refused ex) {
			refused(context, ex);
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing a code of an authenticator app: " + ex.getMessage());
			ImageServlet.errorInfo(context, ex.getStatus(), ex.getMessage());
		}
	}

	// --- One's own: a contact in a session of a personal link, or a signed-in member. ---

	private void totpSetup(Context context) throws IOException {
		Own own = own(context);
		if (own == null) {
			return;
		}
		SignInHolder holder = own.holder();
		String secret = _auth.getTotp().setUp(own.register(), holder);
		if (secret == null) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_GONE, AuthService.RECIPIENT_GONE);
			return;
		}
		String account = holder.getSignInName();
		LOG.info("Setting up an authenticator app for " + holder.getSignInKey() + ".");
		ImageServlet.serveJsonObject(context.response(), TotpSetup.create()
			.setSecret(secret)
			.setUri(otpauth(_issuer, account, secret))
			.setIssuer(_issuer)
			.setAccount(account));
	}

	private void totpConfirm(Context context) throws IOException {
		Own own = own(context);
		if (own == null) {
			return;
		}
		TotpCode request = readCode(context);
		if (request == null) {
			return;
		}
		try {
			_auth.getTotp().confirm(own.register(), own.holder(), request.getCode(),
				context.request().getRemoteAddr());
		} catch (TotpSignIns.Refused ex) {
			refused(context, ex);
			return;
		}
		ImageServlet.serveJsonObject(context.response(), signIns(own.holder(), _passkeys));
	}

	private void removeOwn(Context context) throws IOException {
		Own own = own(context);
		if (own == null) {
			return;
		}
		SignInRemove request = readRemove(context);
		if (request == null) {
			return;
		}
		if (remove(context, own, request)) {
			LOG.info("Removed the " + request.getMethod() + " of " + own.holder().getSignInKey() + ", at their request.");
			ImageServlet.serveJsonObject(context.response(), signIns(own.holder(), _passkeys));
		}
	}

	// --- A member's, about others. ---

	private void removeContacts(Context context) throws IOException {
		Caller caller = _servlet.contactManager(context);
		if (caller == null) {
			return;
		}
		SignInRemove request = readRemove(context);
		if (request == null) {
			return;
		}
		ContactStore.Contact contact = _auth.getContacts().get(request.getContact());
		if (contact == null) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_NOT_FOUND,
				ContactStore.unknownContact(request.getContact()));
			return;
		}
		if (remove(context, new Own(_auth.getContacts(), contact), request)) {
			LOG.info("Removed the " + request.getMethod() + " of " + contact + ", by '" + caller.getUserName() + "'.");
			ImageServlet.serveJsonObject(context.response(), _servlet.contactOnTheWire(contact));
		}
	}

	/**
	 * The administrator removes a way a member signs in (issue #233), at
	 * <code>?action=remove-user-sign-in</code>: their authenticator app, a passkey, an address.
	 */
	private void removeUsers(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!_servlet.administrator(context, caller)) {
			return;
		}
		SignInRemove request = readRemove(context);
		if (request == null) {
			return;
		}
		UserStore.User user = _auth.getUsers().getUser(request.getUser());
		if (user == null) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_NOT_FOUND, AuthService.unknownUser(request.getUser()));
			return;
		}
		if (remove(context, new Own(_auth.getUsers(), user), request)) {
			LOG.info("Removed the " + request.getMethod() + " of '" + user.getName() + "', by '" + caller.getUserName()
				+ "'.");
			ImageServlet.serveJsonObject(context.response(), _servlet.userList());
		}
	}

	/** Removes what the request names from the given holder; answers whether it did. */
	private boolean remove(Context context, Own own, SignInRemove request) throws IOException {
		boolean removed;
		if (TOTP.equals(request.getMethod())) {
			removed = _auth.getTotp().remove(own.register(), own.holder());
		} else if (Passkeys.METHOD.equals(request.getMethod())) {
			removed = own.register().removePasskey(own.holder(), request.getId());
		} else if (EMAIL.equals(request.getMethod()) && own.holder() instanceof UserStore.User user) {
			removed = _auth.getUsers().removeAddress(user, request.getId() == null ? "" : request.getId().trim());
		} else {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, methodUnknown(request.getMethod()));
			return false;
		}
		if (!removed) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_NOT_FOUND, NO_SIGN_IN);
			return false;
		}
		return true;
	}

	// --- Passkeys (issues #204, #233). ---

	/**
	 * What a registration is bound to: the space and the holder, whose key tells a contact from a
	 * member &mdash; a ticket of one never registers for the other.
	 */
	private String registerBinding(SignInHolder holder) {
		return "register:" + _space + ":" + holder.getSignInKey();
	}

	private void passkeyRegisterStart(Context context) throws IOException {
		Own own = own(context);
		if (own == null) {
			return;
		}
		SignInHolder holder = own.holder();
		try {
			options(context, _passkeys.registration(registerBinding(holder), holder, holder.passkeyHandle(_space),
				_issuer));
		} catch (Passkeys.Refused ex) {
			passkeyRefused(context, ex);
		}
	}

	private void passkeyRegister(Context context) throws IOException {
		Own own = own(context);
		if (own == null) {
			return;
		}
		PasskeyResponse request = readPasskey(context);
		if (request == null) {
			return;
		}
		SignInHolder holder = own.holder();
		try {
			SignIns.Passkey passkey = _passkeys.register(registerBinding(holder), request.getTicket(),
				request.getResponse());
			if (!own.register().addPasskey(holder, passkey)) {
				ImageServlet.errorInfo(context, HttpServletResponse.SC_CONFLICT, Passkeys.REGISTRATION_FAILED);
				return;
			}
		} catch (Passkeys.Refused ex) {
			passkeyRefused(context, ex);
			return;
		}
		LOG.info("Registered a passkey of " + holder.getSignInKey() + ".");
		ImageServlet.serveJsonObject(context.response(), signIns(holder, _passkeys));
	}

	/**
	 * Starts a passkey sign-in: on a personal link that asks who this is, bound to the link; for a
	 * caller who holds nothing at all, the member sign-in of the sign-in form (issue #233), which
	 * <code>?action=pair</code> finishes.
	 */
	private void passkeyStart(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.isShareGone()) {
			_servlet.gone(context, caller);
			return;
		}
		if (!_passkeys.isAvailable()) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_NOT_IMPLEMENTED, Passkeys.NOT_CONFIGURED);
			return;
		}
		String binding = _auth.getContacts() == null ? null : AddressProof.passkeyBinding(_space, caller);
		if (binding == null && memberSignIn(caller)) {
			binding = AuthService.memberPasskeyBinding(_space);
		}
		if (binding == null) {
			notHere(context, caller);
			return;
		}
		try {
			options(context, _passkeys.signIn(binding));
		} catch (Passkeys.Refused ex) {
			passkeyRefused(context, ex);
		}
	}

	/** Whether the given caller signs in as a member on the sign-in form: somebody who holds nothing. */
	private boolean memberSignIn(Caller caller) {
		return _auth.getUsers() != null && !caller.isPaired() && !caller.isShareLink() && caller.getInvitation() == null;
	}

	private void notHere(Context context, Caller caller) throws IOException {
		if (caller.mustIdentify()) {
			_servlet.gone(context, caller);
		} else {
			LOG.warning("Refusing a passkey outside a personal link that asks who this is.");
			ImageServlet.errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AddressProof.PASSKEY_NOT_HERE);
		}
	}

	private void passkeyVerify(Context context) throws IOException {
		Caller caller = passkeyCaller(context);
		if (caller == null) {
			return;
		}
		PasskeyResponse request = readPasskey(context);
		if (request == null) {
			return;
		}
		try {
			ImageServlet.serveJsonObject(context.response(), _servlet.addressProof().verifyPasskey(caller, _space,
				request.getTicket(), request.getResponse(), request.isRemember(), request.getDisplayName(),
				request.getDeviceName()));
		} catch (Passkeys.Refused ex) {
			passkeyRefused(context, ex);
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing a passkey: " + ex.getMessage());
			ImageServlet.errorInfo(context, ex.getStatus(), ex.getMessage());
		}
	}

	/**
	 * The caller of a passkey sign-in on a link, <code>null</code> where the response is complete: a
	 * link that is gone is answered so; a server without passkeys {@link Passkeys#NOT_CONFIGURED}; a
	 * caller with nothing to sign in to here what it is answered anywhere.
	 */
	private Caller passkeyCaller(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.isShareGone()) {
			_servlet.gone(context, caller);
			return null;
		}
		if (!_passkeys.isAvailable()) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_NOT_IMPLEMENTED, Passkeys.NOT_CONFIGURED);
			return null;
		}
		if (_auth.getContacts() == null || AddressProof.passkeyBinding(_space, caller) == null) {
			notHere(context, caller);
			return null;
		}
		return caller;
	}

	private static void options(Context context, Passkeys.Started started) throws IOException {
		ImageServlet.serveJsonObject(context.response(), PasskeyOptions.create()
			.setTicket(started.getTicket())
			.setOptions(started.getOptions())
			.setExpires(started.getExpires().toString()));
	}

	private static PasskeyResponse readPasskey(Context context) throws IOException {
		try {
			return PasskeyResponse.readPasskeyResponse(new JsonReader(new ReaderAdapter(new InputStreamReader(
				new ByteArrayInputStream(ImageServlet.readBody(context.request())), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable passkey answer: " + ex.getMessage());
			ImageServlet.errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, UNREADABLE);
			return null;
		}
	}

	private static void passkeyRefused(Context context, Passkeys.Refused ex) throws IOException {
		LOG.warning("Refusing a passkey request: " + ex.getMessage());
		ImageServlet.errorInfo(context, ex.getStatus(), ex.getMessage());
	}

	// --- Helpers. ---

	/**
	 * Whose ways a request about one's own ways to sign in is about: a contact in a session of a
	 * personal link, or a signed-in member (issue #233); <code>null</code> where the response is
	 * complete.
	 */
	private Own own(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (_servlet.gone(context, caller)) {
			return null;
		}
		if (_auth.getTotp() != null) {
			if (caller.getContact() != null && caller.getSession() != null) {
				return new Own(_auth.getContacts(), caller.getContact());
			}
			if (caller.isPaired() && !caller.isShareLink() && caller.getUser() != null) {
				return new Own(_auth.getUsers(), caller.getUser());
			}
		}
		if (!caller.isPaired() && !caller.isShareLink()) {
			_servlet.unauthorized(context, caller, true);
			return null;
		}
		ImageServlet.errorInfo(context, HttpServletResponse.SC_FORBIDDEN, SIGN_INS_REFUSED);
		return null;
	}

	private static TotpCode readCode(Context context) throws IOException {
		try {
			return TotpCode.readTotpCode(new JsonReader(new ReaderAdapter(new InputStreamReader(
				new ByteArrayInputStream(ImageServlet.readBody(context.request())), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable code of an authenticator app: " + ex.getMessage());
			ImageServlet.errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, UNREADABLE);
			return null;
		}
	}

	private static SignInRemove readRemove(Context context) throws IOException {
		try {
			return SignInRemove.readSignInRemove(new JsonReader(new ReaderAdapter(new InputStreamReader(
				new ByteArrayInputStream(ImageServlet.readBody(context.request())), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, UNREADABLE);
			return null;
		}
	}

	private static void refused(Context context, TotpSignIns.Refused ex) throws IOException {
		LOG.warning("Refusing a code of an authenticator app: " + ex.getMessage());
		if (ex.getRetryAfter() > 0) {
			context.response().setHeader("Retry-After", Long.toString(ex.getRetryAfter()));
		}
		ImageServlet.errorInfo(context, ex.getStatus(), ex.getMessage());
	}

	/**
	 * The <code>otpauth://</code> address of the given secret, see issue #208: what an authenticator
	 * app reads from a link or a QR code, with every default spelled out.
	 */
	static String otpauth(String issuer, String account, String secret) {
		return "otpauth://totp/" + encode(issuer) + ":" + encode(account) + "?secret=" + secret + "&issuer="
			+ encode(issuer) + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.PERIOD_SECONDS;
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
