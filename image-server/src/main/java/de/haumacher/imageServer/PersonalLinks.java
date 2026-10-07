/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.SignIns;
import de.haumacher.imageServer.shared.model.AddressKind;
import de.haumacher.imageServer.shared.model.Contact;
import de.haumacher.imageServer.shared.model.ContactAddress;
import de.haumacher.imageServer.shared.model.ContactList;
import de.haumacher.imageServer.shared.model.ContactPasskey;
import de.haumacher.imageServer.shared.model.ContactSession;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.IdentifyRequired;
import de.haumacher.imageServer.shared.model.LinkVisitor;
import de.haumacher.imageServer.shared.model.MaskedAddress;
import de.haumacher.imageServer.shared.model.ProofMethod;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareRecipient;
import de.haumacher.imageServer.shared.model.ShareType;
import java.util.ArrayList;
import java.util.List;

/**
 * How the contacts and the personal share links of issue #198 are carried by the protocol.
 *
 * <p>
 * The one place their stored form becomes the wire form, so that a listing, a creation and a
 * refusal say the same thing about a contact. Nothing here ever carries a secret: a token is
 * answered once by the servlet that made it, a credential once by <code>?action=identify</code>,
 * and their hashes never.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class PersonalLinks {

	/** What a request for the contacts is refused to a share link with. */
	public static final String CONTACTS_REFUSED = "A share link does not see the contacts of the space.";

	/** What an anonymous link with recipients is refused with. */
	public static final String ANONYMOUS_RECIPIENTS =
		"An anonymous link has no recipients; make it personal to send it to chosen people.";

	/** What a request naming a link that is not personal is refused with. */
	public static final String NOT_PERSONAL = "This share link is not personal; it has no recipients.";

	/** What a request naming a contact that is no recipient of the link is refused with. */
	public static final String NOT_A_RECIPIENT = "This contact is no recipient of the share link.";

	/** What an unreadable request about a recipient or a contact is refused with. */
	public static final String UNREADABLE = "The request about a contact cannot be read.";

	/** What a request to manage the contacts is refused to a caller who is no member with the share flag (issue #203). */
	public static final String CONTACTS_MANAGE_REFUSED =
		"Only a member who may share links manages the contacts of the space.";

	/** What a blank name for a contact is refused with (issue #203). */
	public static final String CONTACT_NAME_REQUIRED = "A contact needs a name.";

	/** What a request for a session the contact does not have is refused with (issue #203). */
	public static final String SESSION_UNKNOWN = "This browser session of the contact does not exist (any more).";

	/** What "sign out others" is refused to a caller who is no contact (issue #203). */
	public static final String OTHER_SESSIONS_REFUSED =
		"Only a person who opened a personal link signs out their other browsers.";

	/** What a recipient shut out of the whole space is refused with as a new recipient. */
	public static String contactBlocked(String name) {
		return "'" + name + "' is shut out of every link of this space.";
	}

	private PersonalLinks() {
		// Static utility.
	}

	/** The register as <code>?type=contacts</code> answers it. */
	public static ContactList contacts(ContactStore store) {
		return contacts(store, null, Contributions.Counts.NONE);
	}

	/**
	 * The register as <code>?type=contacts</code> answers it since issue #203: with how many
	 * photographs each contact added and the label of the link each session was opened through.
	 */
	public static ContactList contacts(ContactStore store, ShareStore shares, Contributions.Counts counts) {
		ContactList result = ContactList.create();
		if (store != null) {
			for (ContactStore.Contact contact : store.getContacts()) {
				result.addContact(contact(contact, shares, counts));
			}
		}
		return result;
	}

	/** One contact as members are told about them, with what issue #203 adds. */
	public static Contact contact(ContactStore.Contact contact, ShareStore shares, Contributions.Counts counts) {
		Contact result = contact(contact);
		result.setUploads(counts.of(contact.getSubject()));
		if (shares != null) {
			for (ContactSession session : result.getSessions()) {
				ShareStore.Link link = shares.get(session.getLink());
				session.setLinkLabel(link == null ? "" : link.getLabel());
			}
		}
		return result;
	}

	/** One contact as members are told about them. */
	public static Contact contact(ContactStore.Contact contact) {
		Contact result = Contact.create()
			.setId(contact.getId())
			.setName(contact.getName())
			.setDisplayName(contact.getDisplayName())
			.setCreated(contact.getCreated())
			.setCreatedBy(contact.getCreatedBy())
			.setFirstSeen(contact.getFirstSeen())
			.setLastSeen(contact.getLastSeen())
			.setBlocked(contact.getBlocked())
			// Whether an authenticator app signs them in (#208), never its secret.
			.setAuthenticator(contact.getAuthenticator() == null ? "" : contact.getAuthenticator().getSince());
		for (ContactStore.Address address : contact.getAddresses()) {
			result.addAddresse(address(address));
		}
		for (SignIns.Passkey passkey : contact.getPasskeys()) {
			result.addPasskey(passkey(passkey));
		}
		for (ContactStore.Session session : contact.getSessions()) {
			result.addSession(ContactSession.create()
				.setId(session.getId())
				.setLink(session.getLink())
				.setCreated(session.getCreated())
				.setExpires(session.getExpires())
				.setRemember(session.isRemember())
				.setLastUsed(session.getLastUsed()));
		}
		return result;
	}

	/** One passkey on the wire (issue #204): when it was made and last used, never its key. */
	public static ContactPasskey passkey(SignIns.Passkey passkey) {
		return ContactPasskey.create()
			.setId(passkey.getId())
			.setCreated(passkey.getCreated())
			.setLastUsed(passkey.getLastUsed());
	}

	/** One address on the wire. */
	public static ContactAddress address(ContactStore.Address address) {
		return ContactAddress.create()
			.setKind(kind(address.getKind()))
			.setValue(address.getValue())
			.setProven(address.isProven());
	}

	/** The addresses of the given contact on the wire. */
	public static List<ContactAddress> addresses(ContactStore.Contact contact) {
		List<ContactAddress> result = new ArrayList<>();
		if (contact != null) {
			for (ContactStore.Address address : contact.getAddresses()) {
				result.add(address(address));
			}
		}
		return result;
	}

	/** The wire kind of a stored address kind. */
	public static AddressKind kind(String kind) {
		return ContactStore.PHONE.equals(kind) ? AddressKind.PHONE : AddressKind.EMAIL;
	}

	/** The stored kind of a wire address kind. */
	public static String kind(AddressKind kind) {
		return kind == AddressKind.PHONE ? ContactStore.PHONE : ContactStore.EMAIL;
	}

	/** Adds what is peculiar to a personal link to its wire form. */
	public static ShareLink withRecipients(ShareLink wire, ShareStore.Link link, ContactStore contacts) {
		return withRecipients(wire, link, contacts, Contributions.Counts.NONE);
	}

	/**
	 * Adds what is peculiar to a personal link to its wire form, with who came in through it, when,
	 * and how many photographs each added (issue #203).
	 */
	public static ShareLink withRecipients(ShareLink wire, ShareStore.Link link, ContactStore contacts,
			Contributions.Counts counts) {
		wire.setType(link.isPersonal() ? ShareType.PERSONAL : ShareType.ANONYMOUS);
		wire.setAddressed(link.isAddressed());
		java.util.Set<String> recipients = new java.util.HashSet<>();
		for (ShareStore.Recipient recipient : link.getRecipients()) {
			String id = recipient.getContact();
			recipients.add(id);
			ContactStore.Contact contact = contacts == null ? null : contacts.get(id);
			ShareStore.Visit visit = link.visitOf(id);
			wire.addRecipient(ShareRecipient.create()
				.setContact(id)
				.setName(contact == null ? "" : contact.getName())
				.setAddresses(addresses(contact))
				.setIssued(recipient.getIssued())
				.setOpened(recipient.getOpened())
				.setShutOut(link.shutOutAt(id))
				.setFirstOpened(visit != null ? visit.getFirst() : recipient.getOpened())
				.setLastSeen(visit != null ? visit.getLast() : "")
				.setUploads(counts.of(subject(id), link.getId())));
		}
		for (java.util.Map.Entry<String, ShareStore.Visit> entry : link.getVisitors().entrySet()) {
			String id = entry.getKey();
			if (recipients.contains(id)) {
				continue;
			}
			ContactStore.Contact contact = contacts == null ? null : contacts.get(id);
			if (contact == null) {
				// Deleted from the space: as if they had never come.
				continue;
			}
			wire.addVisitor(LinkVisitor.create()
				.setContact(id)
				.setName(contact.getName())
				.setFirstSeen(entry.getValue().getFirst())
				.setLastSeen(entry.getValue().getLast())
				.setUploads(counts.of(contact.getSubject(), link.getId()))
				.setShutOut(link.shutOutAt(id)));
		}
		return wire;
	}

	/** How an attribution names a contact of the given id. */
	private static String subject(String contactId) {
		return "contact:" + contactId;
	}

	/**
	 * The refusal (an {@link ErrorInfo} carrying an {@link IdentifyRequired}) a caller of a personal link who is not let in yet is answered with.
	 *
	 * <p>
	 * The contact's addresses are masked, and named only where the token presented was the
	 * contact's own and was opened before: whoever holds it is asked to prove one of them, by one of
	 * the given methods (issue #199). A first open names the contact to confirm and no address at all,
 * and the own token of an addressed link &mdash; the group link of issue #211 &mdash; names nobody.
	 * </p>
	 *
	 * @param title
	 *        The title of the shared folder, see {@link SharePreview#linkTitle}; never the label.
	 */
	public static ErrorInfo identifyRequired(AuthService.Identification identification, List<ProofMethod> methods,
			String title) {
		ShareStore.Link link = identification.getLink();
		IdentifyRequired result = IdentifyRequired.create()
			.setFirstOpen(identification.isFirstOpen())
			// The label is its maker's alone; the visitor is told the shared folder's title.
			.setTitle(title)
			.setSharedBy(link.getCreatedBy())
			// The group link of issue #211 names no address: that would reveal the group.
			.setGroup(AddressProof.isGroup(identification));
		ContactStore.Contact contact = identification.getContact();
		if (contact != null) {
			result.setContact(AuthService.contactInfo(contact));
			if (!identification.isFirstOpen()) {
				for (ContactStore.Address address : contact.getAddresses()) {
					result.addAddresse(MaskedAddress.create().setKind(kind(address.getKind())).setMasked(address.masked()));
				}
			}
		}
		// The mailed code of issue #199 where the server can send one; OpenID Connect is #200.
		result.setMethods(methods);
		return ErrorInfo.create().setMessage(identification.getMessage()).setIdentify(result);
	}
}
