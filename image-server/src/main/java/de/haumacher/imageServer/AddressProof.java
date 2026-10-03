/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.AuthService.Identification;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.TotpSignIns;
import de.haumacher.imageServer.mail.CodeMail;
import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.oidc.OidcLogins;
import de.haumacher.imageServer.oidc.OidcProvider;
import de.haumacher.imageServer.passkeys.Passkeys;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.EmailProofSent;
import de.haumacher.imageServer.shared.model.ProofMethod;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Who may prove which e-mail address on a personal share link, and what a proof makes of them, see
 * issue #199.
 *
 * <p>
 * The mechanism (the code, its lifetime, its attempts, the rate limits) is {@link EmailProofs};
 * this decides, from the {@link Caller}, which address a request may name and what a right code
 * does:
 * </p>
 * <ul>
 * <li><b>A recipient's own link that was opened before</b> ({@link AuthService#IDENTIFY_REQUIRED}):
 * an address the space saved with that contact, named only by its masked form or its position
 * among the masked addresses the refusal showed &mdash; never a typed address, so the link is no
 * oracle for the contact's addresses. A right code marks the address proven and issues the
 * contact's credential.</li>
 * <li><b>An open personal link</b> ({@link AuthService#IDENTIFY_OPEN}): any address. A right code finds the contact holding it, or enters a new one, marks
 * it proven and issues their credential.</li>
 * <li><b>A contact who is recognised already</b>: any address, added to them as proven ("Add your
 * e-mail so we recognise you on other devices"); the credential they hold stays.</li>
 * <li><b>The group link</b> (issue #211): the own token of an addressed link, and a credential such
 * a link does not admit ({@link Kind#ADDRESSED}). A typed address, and the answer is the same
 * whether or not it is a recipient's: a code is made, hashed and stored and the rate limits count
 * in either case, and the mail goes out in the background only where the space saved the address
 * with a recipient of the link who is neither shut out of it nor blocked
 * ({@link EmailProofs#sendQuietly}); a right code makes the visitor that recipient and never
 * creates anybody.</li>
 * </ul>
 * <p>
 * Everybody else &mdash; a first open (which needs no proof), an anonymous link, a member, an
 * anonymous caller &mdash; proves nothing here by a code.
 * </p>
 *
 * <p>
 * <b>A provider of OpenID Connect (issue #200)</b> proves an address the visitor never types, so it
 * is no oracle for anybody's addresses, and what it proves ends exactly as a right code does
 * ({@link #provenByProvider}). The same callers may use it; on the own token of an addressed link
 * (and with a credential such a link does not admit) the proven address must be one of the link's
 * recipients ({@link Kind#ADDRESSED}), as for a code. A recipient's own link stays its
 * recipient's: there the address must be one the space saved with that very contact, the rule of the
 * masked choice above, because the token says whose link it is; an address of another recipient of
 * the same link is refused like a stranger's ("this link was shared with someone else") &mdash;
 * that person has a link of their own.
 * </p>
 *
 * <p>
 * <b>A passkey (issue #204) and an authenticator app (issue #208)</b> prove no address but the
 * contact themself; they end in the same step ({@link #identified}), are named in the methods only
 * where a contact the link may let in set one up, and on the links that name nobody every failure is
 * answered alike ({@link #verifyPasskey}, {@link #verifyTotp}).
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class AddressProof {

	private static final Logger LOG = Logger.getLogger(AddressProof.class.getName());

	/** What a caller is answered who has no address to prove here. */
	static final String PROOF_NOT_HERE = "An e-mail address is proven on a personal share link only.";

	/** What a recipient's link is answered naming an address the refusal did not offer. */
	static final String ADDRESS_NOT_OFFERED = "Choose one of the e-mail addresses shown.";

	/** What an unreadable request is answered. */
	static final String PROOF_UNREADABLE = "The request about an e-mail address cannot be read.";

	/** The <code>createdBy</code> of a contact a visitor of an open link entered by proving an address. */
	static String createdBy(ShareStore.Link link) {
		return "link:" + link.getId();
	}

	/** What a request is about: whose address, which one, and what a right code does. */
	enum Kind {
		/** A recipient's own link that was opened before. */
		RECIPIENT,

		/** The visitor of an open personal link. */
		OPEN,

		/** A recognised contact adding an address. */
		ADD,

		/**
		 * The own token of an addressed link (the group link of issue #211), or a credential such a
		 * link does not admit: only an address of one of the link's recipients proves anything here.
		 */
		ADDRESSED;
	}

	/** What a provider's address is answered that is not the one the link was sent to (issue #200). */
	static final String SHARED_WITH_SOMEONE_ELSE =
		"This link was shared with someone else: the address you signed in with is not one it was sent to.";

	/** The address a request names, resolved against the caller. */
	static final class Target {

		final Kind _kind;

		final ShareStore.Link _link;

		final ContactStore.Contact _contact;

		final String _email;

		Target(Kind kind, ShareStore.Link link, ContactStore.Contact contact, String email) {
			_kind = kind;
			_link = link;
			_contact = contact;
			_email = email;
		}

		/** The scope a code is sent and checked in: the same caller, the same link, the same person. */
		String scope() {
			switch (_kind) {
				case RECIPIENT:
					return "recipient:" + _link.getId() + ":" + _contact.getId();
				case OPEN:
					return "open:" + _link.getId();
				case ADDRESSED:
					return "group:" + _link.getId();
				default:
					return "contact:" + _contact.getId();
			}
		}
	}

	private final AuthService _auth;

	private final EmailProofs _proofs;

	private final OidcLogins _oidc;

	private final String _spaceName;

	private final Passkeys _passkeys;

	AddressProof(AuthService auth, EmailProofs proofs, OidcLogins oidc, String spaceName, Passkeys passkeys) {
		_auth = auth;
		_proofs = proofs;
		_oidc = oidc;
		_spaceName = spaceName;
		_passkeys = passkeys;
	}

	/** Whether a code can be mailed at all. */
	boolean isAvailable() {
		return _proofs.isAvailable();
	}

	/**
	 * Whether the given caller has an address to prove here; where not, the servlet answers what it
	 * answers the caller anywhere.
	 */
	static boolean mayProve(Caller caller) {
		if (caller.getContact() != null) {
			return true;
		}
		Identification identification = caller.getIdentification();
		return identification != null && kind(identification) != null;
	}

	/** What the given refusal of a personal link lets its caller prove, <code>null</code> for nothing. */
	private static Kind kind(Identification identification) {
		ShareStore.Link link = identification.getLink();
		if (identification.getRecipient() != null) {
			return identification.isFirstOpen() || identification.getContact() == null ? null : Kind.RECIPIENT;
		}
		if (!link.isPersonal()) {
			return null;
		}
		if (link.isAddressed()) {
			return Kind.ADDRESSED;
		}
		return identification.getStatus() == HttpServletResponse.SC_UNAUTHORIZED ? Kind.OPEN : null;
	}

	/**
	 * Whether the given refusal is one of the group link of issue #211: the own token of an
	 * addressed link, which names no address.
	 */
	static boolean isGroup(Identification identification) {
		return kind(identification) == Kind.ADDRESSED;
	}

	/**
	 * What the given caller may prove through a provider (issue #200), <code>null</code> for
	 * nothing: what a code proves.
	 */
	static Kind providerKind(Caller caller) {
		if (caller.getContact() != null) {
			return Kind.ADD;
		}
		Identification identification = caller.getIdentification();
		return identification == null ? null : kind(identification);
	}

	/** The methods <code>IdentifyRequired.methods</code> names for the given refusal. */
	List<ProofMethod> methods(Identification identification) {
		List<ProofMethod> result = new ArrayList<>();
		Kind kind = kind(identification);
		boolean email = kind == Kind.RECIPIENT && hasEmail(identification.getContact());
		if (kind == Kind.OPEN || kind == Kind.ADDRESSED || email) {
			if (_proofs.isAvailable()) {
				result.add(ProofMethod.create().setName(EmailProofs.METHOD));
			}
			addProviders(result);
		}
		// A passkey (#204) and an authenticator app (#208) only where a contact this link may let in
		// set one up: never the default way in, and never offered to somebody who has none.
		if (kind != null && _passkeys.isAvailable()
			&& anyCandidate(identification, kind, contact -> !contact.getPasskeys().isEmpty())) {
			result.add(ProofMethod.create().setName(Passkeys.METHOD));
		}
		if (kind != null && anyCandidate(identification, kind, contact -> contact.getAuthenticator() != null)) {
			result.add(ProofMethod.create().setName(TotpSignIns.METHOD));
		}
		return result;
	}

	/**
	 * Whether a contact the given refusal may let in has what the given test asks for: on a
	 * recipient's own link that recipient, on the group link one of its recipients, on an open link
	 * anybody of the space &mdash; each neither blocked nor shut out of the link.
	 */
	private boolean anyCandidate(Identification identification, Kind kind,
			Predicate<ContactStore.Contact> test) {
		ShareStore.Link link = identification.getLink();
		ContactStore contacts = _auth.getContacts();
		switch (kind) {
			case RECIPIENT:
				return admissible(link, identification.getContact()) && test.test(identification.getContact());
			case ADDRESSED:
				for (ShareStore.Recipient recipient : link.getRecipients()) {
					ContactStore.Contact contact = contacts.get(recipient.getContact());
					if (admissible(link, contact) && test.test(contact)) {
						return true;
					}
				}
				return false;
			case OPEN:
				for (ContactStore.Contact contact : contacts.getContacts()) {
					if (admissible(link, contact) && test.test(contact)) {
						return true;
					}
				}
				return false;
			default:
				return false;
		}
	}

	private static boolean admissible(ShareStore.Link link, ContactStore.Contact contact) {
		return contact != null && !contact.isBlocked() && !link.isShutOut(contact.getId());
	}

	/**
	 * Signs the caller in by a code of an authenticator app, see issue #208, and answers the
	 * credential a right code issues: the same step as a proven address.
	 *
	 * <p>
	 * On a recipient's own link the code is that recipient's. On an open link and on the group link
	 * of issue #211, which name nobody, the request names the contact by an e-mail address saved with
	 * them &mdash; on the group link with one of its recipients &mdash; and whatever is wrong (no such
	 * contact, no authenticator, a wrong or a used code) is answered the one sentence
	 * {@link TotpSignIns#CODE_WRONG}, so that the answer says nothing about whose address it is.
	 * </p>
	 */
	ContactCredential verifyTotp(Caller caller, String code, String address, String client, boolean remember,
			String displayName) throws TotpSignIns.Refused, AuthService.Refused, IOException {
		Identification identification = caller.getIdentification();
		Kind kind = identification == null ? null : kind(identification);
		if (kind == null) {
			throw new AuthService.Refused(HttpServletResponse.SC_BAD_REQUEST, TOTP_NOT_HERE);
		}
		ShareStore.Link link = identification.getLink();
		TotpSignIns totp = _auth.getTotp();
		ContactStore.Contact contact;
		if (kind == Kind.RECIPIENT) {
			contact = _auth.getContacts().get(identification.getContact().getId());
			if (contact == null) {
				throw new AuthService.Refused(HttpServletResponse.SC_GONE, AuthService.RECIPIENT_GONE);
			}
			totp.verify(contact, null, code, client);
		} else {
			String email = typed(address);
			contact = kind == Kind.OPEN ? _auth.getContacts().byEmail(email) : recipientHolding(link, email);
			totp.verify(contact, TotpSignIns.addressKey(link.getId(), email), code, client);
		}
		return identified(link, contact, remember, displayName, "a code of their authenticator app");
	}

	/**
	 * What the given refusal binds a passkey sign-in to (issue #204): the space, the link and whom
	 * it asks for; <code>null</code> for a caller who signs in with nothing here.
	 */
	static String passkeyBinding(String space, Caller caller) {
		Identification identification = caller.getIdentification();
		Kind kind = identification == null ? null : kind(identification);
		if (kind == null) {
			return null;
		}
		ContactStore.Contact contact = kind == Kind.RECIPIENT ? identification.getContact() : null;
		return "sign-in:" + space + ":" + identification.getLink().getId() + ":" + kind + ":"
			+ (contact == null ? "" : contact.getId());
	}

	/**
	 * Signs the caller in by a passkey, see issue #204, and answers the credential: the same step as
	 * a proven address. The passkey must be one of the space's contacts' whom the link asks for
	 * &mdash; on a recipient's own link that recipient, on the group link one of its recipients, on
	 * an open link anybody &mdash; and every failure is the one {@link Passkeys#SIGN_IN_REFUSED}.
	 */
	ContactCredential verifyPasskey(Caller caller, String space, String ticket, String response, boolean remember,
			String displayName) throws Passkeys.Refused, AuthService.Refused, IOException {
		String binding = passkeyBinding(space, caller);
		if (binding == null) {
			throw new AuthService.Refused(HttpServletResponse.SC_BAD_REQUEST, PASSKEY_NOT_HERE);
		}
		Identification identification = caller.getIdentification();
		Kind kind = kind(identification);
		ShareStore.Link link = identification.getLink();
		ContactStore contacts = _auth.getContacts();
		Passkeys.Asserted asserted = _passkeys.check(binding, ticket, response, contacts::byPasskey, contact -> {
			switch (kind) {
				case RECIPIENT:
					return contact.getId().equals(identification.getContact().getId());
				case ADDRESSED:
					return link.recipient(contact.getId()) != null;
				default:
					return true;
			}
		});
		contacts.usedPasskey(asserted.getContact().getId(), asserted.getId(), asserted.getCounter(),
			asserted.isBackupState(), _passkeys.now());
		return identified(link, contacts.get(asserted.getContact().getId()), remember, displayName, "a passkey");
	}

	/** What a passkey sign-in is refused outside a personal link that asks who this is. */
	static final String PASSKEY_NOT_HERE = "A passkey signs in on a personal share link that asks who you are.";

	/** What a request for a code of an authenticator app is refused outside a personal link that asks who this is. */
	static final String TOTP_NOT_HERE =
		"A code of an authenticator app signs in on a personal share link that asks who you are.";

	/**
	 * The methods <code>ShareInfo.methods</code> names for a recognised contact, and
	 * <code>AuthInfo.proofMethods</code> for a signed-in member (issue #211): every way the server
	 * can prove an address.
	 */
	List<ProofMethod> contactMethods() {
		List<ProofMethod> result = new ArrayList<>();
		if (_proofs.isAvailable()) {
			result.add(ProofMethod.create().setName(EmailProofs.METHOD));
		}
		addProviders(result);
		return result;
	}

	/** One method per provider of OpenID Connect the server offers, see issue #200. */
	private void addProviders(List<ProofMethod> methods) {
		for (OidcProvider provider : _oidc.getProviders()) {
			methods.add(ProofMethod.create().setName(provider.method()).setLabel(provider.getLabel()));
		}
	}

	/**
	 * The target of a sign-in through a provider, see issue #200: whose address the caller may
	 * prove, the address itself to come from the provider. <code>null</code> for a caller who
	 * proves nothing here.
	 */
	static Target providerTarget(Caller caller) {
		Kind kind = providerKind(caller);
		if (kind == null) {
			return null;
		}
		if (kind == Kind.ADD) {
			return new Target(kind, caller.getShare(), caller.getContact(), null);
		}
		Identification identification = caller.getIdentification();
		return new Target(kind, identification.getLink(),
			kind == Kind.RECIPIENT ? identification.getContact() : null, null);
	}

	/** Whether the given contact has an e-mail address saved in the space. */
	static boolean hasEmail(ContactStore.Contact contact) {
		for (ContactStore.Address address : contact.getAddresses()) {
			if (ContactStore.EMAIL.equals(address.getKind())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The address the given request names, see the class comment.
	 *
	 * @throws AuthService.Refused
	 *         <code>400</code> for an address that is none, or one a recipient's link did not offer.
	 */
	Target target(Caller caller, String address, int choice) throws AuthService.Refused {
		if (caller.getContact() != null) {
			return new Target(Kind.ADD, caller.getShare(), caller.getContact(), typed(address));
		}
		Identification identification = caller.getIdentification();
		Kind kind = kind(identification);
		if (kind == Kind.OPEN || kind == Kind.ADDRESSED) {
			return new Target(kind, identification.getLink(), null, typed(address));
		}
		ContactStore.Contact contact = identification.getContact();
		return new Target(kind, identification.getLink(), contact, offered(contact, address, choice));
	}

	/** A typed address, normalised; refused where it is none a mail can go to. */
	private static String typed(String address) throws AuthService.Refused {
		String email;
		try {
			email = ContactStore.normalize(ContactStore.EMAIL, address);
			new InternetAddress(email, true);
		} catch (ContactStore.Refused ex) {
			throw new AuthService.Refused(HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
		} catch (AddressException ex) {
			throw new AuthService.Refused(HttpServletResponse.SC_BAD_REQUEST, ContactStore.addressInvalid(address));
		}
		return email;
	}

	/**
	 * The e-mail address of the given contact a recipient's link names: by its position among the
	 * masked addresses (counted from one, as the refusal listed them), or by its masked form where
	 * exactly one e-mail address is masked so. Anything else &mdash; a typed address above all &mdash;
	 * is refused alike, whether or not it is the contact's.
	 */
	private static String offered(ContactStore.Contact contact, String address, int choice)
			throws AuthService.Refused {
		List<ContactStore.Address> addresses = contact.getAddresses();
		if (choice > 0) {
			if (choice <= addresses.size() && ContactStore.EMAIL.equals(addresses.get(choice - 1).getKind())) {
				return addresses.get(choice - 1).getValue();
			}
			throw new AuthService.Refused(HttpServletResponse.SC_BAD_REQUEST, ADDRESS_NOT_OFFERED);
		}
		String masked = address == null ? "" : address.trim();
		String found = null;
		int matches = 0;
		for (ContactStore.Address candidate : addresses) {
			if (ContactStore.EMAIL.equals(candidate.getKind()) && candidate.masked().equals(masked)) {
				found = candidate.getValue();
				matches++;
			}
		}
		if (matches != 1) {
			throw new AuthService.Refused(HttpServletResponse.SC_BAD_REQUEST, ADDRESS_NOT_OFFERED);
		}
		return found;
	}

	/** Mails a code for the given target. */
	EmailProofSent prove(Target target, String client, String acceptLanguage) throws EmailProofs.Refused {
		String language = CodeMail.language(acceptLanguage);
		EmailProofs.MailText text = (code, minutes) -> CodeMail.of(language, _spaceName, code, minutes);
		EmailProofs.Sent sent;
		if (target._kind == Kind.ADDRESSED) {
			// The group link: the same work and the same answer for a recipient and a stranger.
			ContactStore.Contact holder = recipientHolding(target._link, target._email);
			boolean deliver = holder != null && !holder.isBlocked() && !target._link.isShutOut(holder.getId());
			sent = _proofs.sendQuietly(target.scope(), target._email, target._link.getId(), client, text, deliver);
			LOG.info((deliver ? "Mailing a code" : "Mailing no code to an address that is no admitted recipient's")
				+ " through the group link " + target._link.getId() + ".");
		} else {
			sent = _proofs.send(target.scope(), target._email, target._link.getId(), client, text);
			LOG.info("Mailed a code through the share link " + target._link.getId() + " (" + target._kind + ").");
		}
		return EmailProofSent.create()
			.setAddress(new ContactStore.Address(ContactStore.EMAIL, target._email, false).masked())
			.setExpires(sent.getExpires().toString())
			.setAttempts(EmailProofs.ATTEMPTS);
	}

	/**
	 * Checks the code for the given target and makes of the caller what the address proves.
	 *
	 * @param caller
	 *        Who asks; a recognised contact keeps their credential.
	 */
	ContactCredential verify(Caller caller, Target target, String code, String client, boolean remember,
			String displayName) throws EmailProofs.Refused, AuthService.Refused, IOException {
		_proofs.verify(target.scope(), target._email, code, client);
		Target resolved = target._kind == Kind.ADDRESSED ? recipientTarget(target._link, target._email, "a code")
			: target;
		return proven(caller, resolved, remember, displayName, "a proven address");
	}

	/**
	 * The contact the space saved the given address with, where they are a recipient of the given
	 * link; <code>null</code> for anybody else's address.
	 */
	private ContactStore.Contact recipientHolding(ShareStore.Link link, String email) {
		ContactStore.Contact holder = _auth.getContacts().byEmail(email);
		return holder == null || link.recipient(holder.getId()) == null ? null : holder;
	}

	/**
	 * What a proven address makes of a visitor of the group link (issue #211): the recipient holding
	 * it.
	 *
	 * @throws AuthService.Refused
	 *         <code>403</code> {@link #SHARED_WITH_SOMEONE_ELSE} for an address of no recipient,
	 *         <code>410</code> {@link AuthService#CONTACT_SHUT_OUT} for a recipient shut out of the
	 *         link or blocked.
	 */
	private Target recipientTarget(ShareStore.Link link, String email, String how) throws AuthService.Refused {
		ContactStore.Contact holder = recipientHolding(link, email);
		if (holder == null) {
			LOG.info("Refusing " + how + " on the addressed link " + link.getId() + ": not a recipient's address.");
			throw new AuthService.Refused(HttpServletResponse.SC_FORBIDDEN, SHARED_WITH_SOMEONE_ELSE);
		}
		if (holder.isBlocked() || link.isShutOut(holder.getId())) {
			throw new AuthService.Refused(HttpServletResponse.SC_GONE, AuthService.CONTACT_SHUT_OUT);
		}
		return new Target(Kind.RECIPIENT, link, holder, email);
	}

	/**
	 * Makes of the caller what an address a provider proved makes of them, see issue #200: exactly
	 * what a right code does, once the address is checked against whom the link asks for.
	 *
	 * @param target
	 *        What {@link #providerTarget(Caller)} answers for the caller now.
	 * @param email
	 *        The address the provider confirmed, normalised.
	 * @throws AuthService.Refused
	 *         <code>403</code> {@link #SHARED_WITH_SOMEONE_ELSE} for an address the link does not ask
	 *         for, and every refusal a proven address meets.
	 */
	ContactCredential provenByProvider(Caller caller, Target target, String email, boolean remember,
			String displayName, OidcProvider provider) throws AuthService.Refused, IOException {
		ShareStore.Link link = target._link;
		Target resolved;
		switch (target._kind) {
			case RECIPIENT: {
				ContactStore.Contact contact = _auth.getContacts().get(target._contact.getId());
				if (contact == null) {
					throw new AuthService.Refused(HttpServletResponse.SC_GONE, AuthService.RECIPIENT_GONE);
				}
				if (!holdsEmail(contact, email)) {
					LOG.info("Refusing a sign-in with " + provider + " on the link of " + contact
						+ ": another address.");
					throw new AuthService.Refused(HttpServletResponse.SC_FORBIDDEN, SHARED_WITH_SOMEONE_ELSE);
				}
				resolved = new Target(Kind.RECIPIENT, link, contact, email);
				break;
			}
			case ADDRESSED:
				resolved = recipientTarget(link, email, "a sign-in with " + provider);
				break;
			default:
				resolved = new Target(target._kind, link, target._contact, email);
				break;
		}
		return proven(caller, resolved, remember, displayName, "an address " + provider + " confirmed");
	}

	private static boolean holdsEmail(ContactStore.Contact contact, String email) {
		for (ContactStore.Address address : contact.getAddresses()) {
			if (ContactStore.EMAIL.equals(address.getKind()) && address.getValue().equals(email)) {
				return true;
			}
		}
		return false;
	}

	/** What a proven address makes of the caller, the second half of {@link #verify}. */
	private ContactCredential proven(Caller caller, Target target, boolean remember, String displayName,
			String how) throws AuthService.Refused, IOException {
		ContactStore contacts = _auth.getContacts();
		ShareStore.Link link = target._link;
		ContactStore.Contact contact;
		try {
			switch (target._kind) {
				case ADD:
					contact = contacts.prove(target._contact.getId(), target._email, "", "");
					break;
				case RECIPIENT:
					contact = contacts.prove(target._contact.getId(), target._email, "", "");
					break;
				default:
					ContactStore.Contact holder = contacts.byEmail(target._email);
					if (holder != null && (holder.isBlocked() || link.isShutOut(holder.getId()))) {
						throw new AuthService.Refused(HttpServletResponse.SC_GONE, AuthService.CONTACT_SHUT_OUT);
					}
					contact = contacts.prove(null, target._email, displayName, createdBy(link));
					break;
			}
		} catch (ContactStore.Refused ex) {
			throw new AuthService.Refused(HttpServletResponse.SC_CONFLICT, ex.getMessage());
		}
		if (contact == null) {
			throw new AuthService.Refused(HttpServletResponse.SC_GONE, AuthService.RECIPIENT_GONE);
		}
		if (target._kind == Kind.ADD) {
			LOG.info("Added " + how + " to " + contact + ".");
			ContactStore.Session session = caller.getSession();
			return ContactCredential.create()
				.setCredential("")
				.setExpires(session == null ? "" : session.getExpires())
				.setRemember(session != null && session.isRemember())
				.setContact(AuthService.contactInfo(contact));
		}
		return issue(link, contact, remember, displayName, how);
	}

	/**
	 * What a proven identity makes of a visitor who is not recognised yet, the step every way in
	 * ends with &mdash; a mailed code, a provider (issue #200), an authenticator app (issue #208), a
	 * passkey (issue #204): the given contact, unless they are shut out of the link or out of the
	 * space, gets a credential through the given link.
	 *
	 * @throws AuthService.Refused
	 *         <code>410</code> {@link AuthService#CONTACT_SHUT_OUT} for a contact shut out,
	 *         {@link AuthService#RECIPIENT_GONE} for one deleted meanwhile.
	 */
	ContactCredential identified(ShareStore.Link link, ContactStore.Contact contact, boolean remember,
			String displayName, String how) throws AuthService.Refused, IOException {
		if (contact == null) {
			throw new AuthService.Refused(HttpServletResponse.SC_GONE, AuthService.RECIPIENT_GONE);
		}
		if (contact.isBlocked() || link.isShutOut(contact.getId())) {
			throw new AuthService.Refused(HttpServletResponse.SC_GONE, AuthService.CONTACT_SHUT_OUT);
		}
		return issue(link, contact, remember, displayName, how);
	}

	private ContactCredential issue(ShareStore.Link link, ContactStore.Contact contact, boolean remember,
			String displayName, String how) throws AuthService.Refused, IOException {
		ContactStore contacts = _auth.getContacts();
		ContactStore.Issued issued = contacts.issue(contact.getId(), link.getId(), remember, displayName);
		if (issued == null) {
			throw new AuthService.Refused(HttpServletResponse.SC_GONE, AuthService.RECIPIENT_GONE);
		}
		LOG.info("Identified " + contact + " by " + how + " through the share link " + link.getId()
			+ (remember ? ", remembered." : "."));
		return ContactCredential.create()
			.setCredential(issued.getCredential())
			.setExpires(issued.getSession().getExpires())
			.setRemember(issued.getSession().isRemember())
			.setContact(AuthService.contactInfo(contact));
	}
}
