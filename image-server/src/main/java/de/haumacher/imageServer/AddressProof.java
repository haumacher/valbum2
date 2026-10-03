/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.AuthService.Identification;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.mail.CodeMail;
import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.EmailProofSent;
import de.haumacher.imageServer.shared.model.ProofMethod;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
 * </ul>
 * <p>
 * Everybody else &mdash; a first open (which needs no proof), the own token of an addressed link, a
 * credential such a link does not admit, an anonymous link, a member, an anonymous caller &mdash;
 * proves nothing here.
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
		ADD;
	}

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
				default:
					return "contact:" + _contact.getId();
			}
		}
	}

	private final AuthService _auth;

	private final EmailProofs _proofs;

	private final String _spaceName;

	AddressProof(AuthService auth, EmailProofs proofs, String spaceName) {
		_auth = auth;
		_proofs = proofs;
		_spaceName = spaceName;
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
		if (identification.getStatus() == HttpServletResponse.SC_UNAUTHORIZED && link.isPersonal()
			&& !link.isAddressed()) {
			return Kind.OPEN;
		}
		return null;
	}

	/** The methods <code>IdentifyRequired.methods</code> names for the given refusal. */
	List<ProofMethod> methods(Identification identification) {
		List<ProofMethod> result = new ArrayList<>();
		if (!_proofs.isAvailable()) {
			return result;
		}
		Kind kind = kind(identification);
		if (kind == Kind.OPEN || (kind == Kind.RECIPIENT && hasEmail(identification.getContact()))) {
			result.add(ProofMethod.create().setName(EmailProofs.METHOD));
		}
		return result;
	}

	/** The methods <code>ShareInfo.methods</code> names for a recognised contact. */
	List<ProofMethod> contactMethods() {
		List<ProofMethod> result = new ArrayList<>();
		if (_proofs.isAvailable()) {
			result.add(ProofMethod.create().setName(EmailProofs.METHOD));
		}
		return result;
	}

	private static boolean hasEmail(ContactStore.Contact contact) {
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
		if (kind == Kind.OPEN) {
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
		EmailProofs.Sent sent = _proofs.send(target.scope(), target._email, target._link.getId(), client,
			(code, minutes) -> CodeMail.of(language, _spaceName, code, minutes));
		LOG.info("Mailed a code through the share link " + target._link.getId() + " (" + target._kind + ").");
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
			LOG.info("Added a proven address to " + contact + ".");
			ContactStore.Session session = caller.getSession();
			return ContactCredential.create()
				.setCredential("")
				.setExpires(session == null ? "" : session.getExpires())
				.setRemember(session != null && session.isRemember())
				.setContact(AuthService.contactInfo(contact));
		}
		ContactStore.Issued issued = contacts.issue(contact.getId(), link.getId(), remember, displayName);
		if (issued == null) {
			throw new AuthService.Refused(HttpServletResponse.SC_GONE, AuthService.RECIPIENT_GONE);
		}
		LOG.info("Identified " + contact + " by a proven address through the share link " + link.getId()
			+ (remember ? ", remembered." : "."));
		return ContactCredential.create()
			.setCredential(issued.getCredential())
			.setExpires(issued.getSession().getExpires())
			.setRemember(issued.getSession().isRemember())
			.setContact(AuthService.contactInfo(contact));
	}
}
