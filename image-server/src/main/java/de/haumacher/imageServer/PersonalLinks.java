/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.shared.model.AddressKind;
import de.haumacher.imageServer.shared.model.Contact;
import de.haumacher.imageServer.shared.model.ContactAddress;
import de.haumacher.imageServer.shared.model.ContactList;
import de.haumacher.imageServer.shared.model.ContactSession;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.IdentifyRequired;
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

	/** What a recipient shut out of the whole space is refused with as a new recipient. */
	public static String contactBlocked(String name) {
		return "'" + name + "' is shut out of every link of this space.";
	}

	private PersonalLinks() {
		// Static utility.
	}

	/** The register as <code>?type=contacts</code> answers it. */
	public static ContactList contacts(ContactStore store) {
		ContactList result = ContactList.create();
		if (store != null) {
			for (ContactStore.Contact contact : store.getContacts()) {
				result.addContact(contact(contact));
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
			.setBlocked(contact.getBlocked());
		for (ContactStore.Address address : contact.getAddresses()) {
			result.addAddresse(address(address));
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
		wire.setType(link.isPersonal() ? ShareType.PERSONAL : ShareType.ANONYMOUS);
		for (ShareStore.Recipient recipient : link.getRecipients()) {
			ContactStore.Contact contact = contacts == null ? null : contacts.get(recipient.getContact());
			wire.addRecipient(ShareRecipient.create()
				.setContact(recipient.getContact())
				.setName(contact == null ? "" : contact.getName())
				.setAddresses(addresses(contact))
				.setIssued(recipient.getIssued())
				.setOpened(recipient.getOpened())
				.setShutOut(link.shutOutAt(recipient.getContact())));
		}
		return wire;
	}

	/**
	 * The refusal (an {@link ErrorInfo} carrying an {@link IdentifyRequired}) a caller of a personal link who is not let in yet is answered with.
	 *
	 * <p>
	 * The contact's addresses are masked, and named only where the token presented was the
	 * contact's own and was opened before: whoever holds it is asked to prove one of them, by one of
	 * the given methods (issue #199). A first open names the contact to confirm and no address at all.
	 * </p>
	 */
	public static ErrorInfo identifyRequired(AuthService.Identification identification, List<ProofMethod> methods) {
		ShareStore.Link link = identification.getLink();
		IdentifyRequired result = IdentifyRequired.create()
			.setFirstOpen(identification.isFirstOpen())
			.setLabel(link.getLabel())
			.setSharedBy(link.getCreatedBy());
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
