/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.shared.model.AddressKind;
import de.haumacher.imageServer.shared.model.Contact;
import de.haumacher.imageServer.shared.model.ContactList;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The contacts of a space, see issue #198: who they are, how their addresses are normalised, and
 * who is told about them.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestContacts extends PersonalLinkTestCase {

	// --- Normalising addresses. ---

	public void testAnEmailAddressIsLowerCasedAndReadFromItsFullForm() throws Exception {
		assertEquals("petra@gmx.de", ContactStore.normalize(ContactStore.EMAIL, "  Petra@GMX.de "));
		assertEquals("petra@gmx.de", ContactStore.normalize(ContactStore.EMAIL, "Tante Petra <Petra@gmx.de>"));
		assertEquals("petra@gmx.de", ContactStore.normalize(ContactStore.EMAIL, "\"Tante Petra\" <petra@gmx.de>"));
		assertEquals("Tante Petra", ContactStore.nameOf("\"Tante Petra\" <petra@gmx.de>"));
		assertEquals("", ContactStore.nameOf("petra@gmx.de"));
		for (String bad : new String[] { "", "petra", "@gmx.de", "petra@", "pe tra@gmx.de", "a@b@c" }) {
			try {
				ContactStore.normalize(ContactStore.EMAIL, bad);
				fail("'" + bad + "' is no address.");
			} catch (ContactStore.Refused expected) {
				assertEquals(ContactStore.addressInvalid(bad), expected.getMessage());
			}
		}
	}

	public void testAPhoneNumberIsE164WhereItSaysItsCountry() throws Exception {
		assertEquals("+491711234567", ContactStore.normalize(ContactStore.PHONE, "+49 171 123-4567"));
		assertEquals("+491711234567", ContactStore.normalize(ContactStore.PHONE, "0049 (171) 123 45 67"));
		assertEquals("+491711234567", ContactStore.normalize(ContactStore.PHONE, "+49/171.1234567"));
		assertEquals("A national number names no country; it is kept as given.", "0171 1234567",
			ContactStore.normalize(ContactStore.PHONE, " 0171 1234567 "));
		for (String bad : new String[] { "", "12", "call me", "+49+171", "petra@gmx.de" }) {
			try {
				ContactStore.normalize(ContactStore.PHONE, bad);
				fail("'" + bad + "' is no number.");
			} catch (ContactStore.Refused expected) {
				// As it should be.
			}
		}
	}

	public void testAMaskedAddressShowsLittle() throws Exception {
		assertEquals("p•••@gmx.de", new ContactStore.Address(ContactStore.EMAIL, "petra@gmx.de", false).masked());
		assertEquals("+49•••67",
			new ContactStore.Address(ContactStore.PHONE, "+491711234567", false).masked());
		assertEquals("•••", new ContactStore.Address(ContactStore.PHONE, "112", false).masked());
	}

	// --- The register. ---

	public void testANewContactWithAKnownAddressIsThatContact() throws Exception {
		ContactStore store = new ContactStore(_base);
		ContactStore.Contact petra =
			store.enter(store.read("", "Tante Petra", addresses(ContactStore.EMAIL, "petra@gmx.de")), "alice");
		ContactStore.Contact again = store.enter(store.read("", "Petra Müller", addresses(ContactStore.EMAIL,
			"PETRA@gmx.de", ContactStore.PHONE, "+49 171 1234567")), "bob");

		assertSame(petra, again);
		assertEquals("The name the space gave her stays.", "Tante Petra", again.getName());
		assertEquals("The new address was added to her.", 2, again.getAddresses().size());
		assertEquals(1, store.getContacts().size());
		assertEquals("alice", again.getCreatedBy());
	}

	public void testANamelessContactIsNamedByItsFirstAddress() throws Exception {
		ContactStore store = new ContactStore(_base);
		assertEquals("petra@gmx.de",
			store.enter(store.read("", "  ", addresses(ContactStore.EMAIL, "Petra@gmx.de")), "alice").getName());
		assertEquals("Tante Klara",
			store.enter(store.read("", "", addresses(ContactStore.EMAIL, "Tante Klara <klara@web.de>")), "alice")
				.getName());
		try {
			store.read("", " ", Collections.emptyList());
			fail("A contact needs a name or an address.");
		} catch (ContactStore.Refused expected) {
			assertEquals(ContactStore.NAME_REQUIRED, expected.getMessage());
		}
		try {
			store.read("nobody", "", Collections.emptyList());
			fail("An unknown contact is none.");
		} catch (ContactStore.Refused expected) {
			assertEquals(ContactStore.unknownContact("nobody"), expected.getMessage());
		}
	}

	public void testTheRegisterRoundTripsReadAndWrite() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		credential(tokenOf(created, "Tante Petra"));
		identify(tokenOf(created, "Klaus"), false);
		postAs("/", "block-contact", "{\"contact\":\"" + contactOf(created, "Klaus") + "\",\"shutOut\":true}",
			SharingFixture.ALICE, null);

		ContactStore store = new ContactStore(_base);
		store.store();
		String first = new String(Files.readAllBytes(store.getFile()), StandardCharsets.UTF_8);
		new ContactStore(_base).store();
		String second = new String(Files.readAllBytes(store.getFile()), StandardCharsets.UTF_8);
		assertEquals(first, second);
		assertTrue(first, first.contains("\"version\":1"));
		assertTrue(first, first.contains("\"kind\":\"phone\""));
		assertTrue(first, first.contains("\"tokenHash\""));

		ContactStore.Contact petra = store.get(contactOf(created, "Tante Petra"));
		assertEquals(1, petra.getSessions().size());
		assertTrue(petra.getSessions().get(0).isRemember());
		assertFalse(petra.getFirstSeen().isEmpty());
		assertTrue(store.get(contactOf(created, "Klaus")).isBlocked());
	}

	public void testAnUnreadableRegisterRecognisesNobodyAndIsKept() throws Exception {
		ContactStore store = new ContactStore(_base);
		Files.createDirectories(store.getFile().getParent());
		Files.write(store.getFile(), "{not json".getBytes(StandardCharsets.UTF_8));

		ContactStore broken = new ContactStore(_base);
		assertTrue(broken.getContacts().isEmpty());
		broken.enter(broken.read("", "Klaus", addresses(ContactStore.PHONE, "+491711234567")), "alice");
		try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(store.getFile().getParent())) {
			assertTrue("The unreadable file is kept for repair.",
				files.anyMatch(file -> file.getFileName().toString().startsWith("contacts.json.broken-")));
		}
	}

	// --- Who is told. ---

	public void testEveryMemberSeesTheContacts() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));

		for (String member : new String[] { SharingFixture.ALICE, SharingFixture.BOB, SharingFixture.CAROL,
			SharingFixture.DAVE, SharingFixture.EVE }) {
			FakeResponse response = get("/", "contacts", member);
			assertEquals(response.body(), 200, response.status());
			ContactList list = contactList(response);
			assertEquals(2, list.getContacts().size());
			Contact petra = list.getContacts().get(0);
			assertEquals("Tante Petra", petra.getName());
			assertEquals(AddressKind.EMAIL, petra.getAddresses().get(0).getKind());
			assertEquals("petra@gmx.de", petra.getAddresses().get(0).getValue());
			assertFalse(petra.getAddresses().get(0).isProven());
			assertEquals("alice", petra.getCreatedBy());
		}
		credential(tokenOf(created, "Tante Petra"));
		Contact petra = contactList(get("/", "contacts", SharingFixture.DAVE)).getContacts().get(0);
		assertEquals("A member sees where she is signed in, never the credential.", 1, petra.getSessions().size());
		assertFalse(get("/", "contacts", SharingFixture.DAVE).body().contains("tokenHash"));
	}

	public void testNoLinkAndNobodyAnonymousSeesTheContacts() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);

		FakeResponse anonymous = get("/", "contacts", null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());
		FakeResponse link = get("/", "contacts", zooToken());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, link.status());
		assertEquals(PersonalLinks.CONTACTS_REFUSED, errorMessage(link));
		FakeResponse contact = getAs("/", "contacts", token, credential);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, contact.status());
		assertEquals(PersonalLinks.CONTACTS_REFUSED, errorMessage(contact));
		assertFalse(contact.body().contains("gmx"));
	}

	public void testAServerWithoutAuthenticationHasNoContacts() throws Exception {
		_authMode = AuthMode.OFF;
		restartServer();
		FakeResponse response = get("/", "contacts", null);
		assertEquals(200, response.status());
		assertTrue(contactList(response).getContacts().isEmpty());
	}

	public void testAnUnreadableRecipientEntersNobody() throws Exception {
		FakeResponse response = sharePersonal(ZOO, SharingFixture.ALICE, email("Tante Petra", PETRA),
			email("Klaus", "not an address"));

		assertEquals(response.body(), HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ContactStore.addressInvalid("not an address"), errorMessage(response));
		assertTrue(new ContactStore(_base).getContacts().isEmpty());
		assertTrue(links(shares(ZOO, SharingFixture.ALICE)).getLinks().isEmpty());
	}

	public void testAKnownContactIsChosenByIdAndNamedOnce() throws Exception {
		ShareLinkCreated first = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(first, "Tante Petra");

		ShareLinkCreated second = created("/" + SharingFixture.PUBLIC + "/", known(petra),
			email("Petra", "petra@gmx.de"));
		assertEquals("The same contact twice is one recipient.", 1, second.getRecipients().size());
		assertEquals(petra, second.getRecipients().get(0).getContact());
		assertEquals(1, new ContactStore(_base).getContacts().size());
	}

	private static List<String[]> addresses(String... pairs) {
		List<String[]> result = new ArrayList<>();
		for (int n = 0; n < pairs.length; n += 2) {
			result.add(Arrays.copyOfRange(pairs, n, n + 2));
		}
		return result;
	}
}
