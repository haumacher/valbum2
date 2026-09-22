/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.faces.PeopleStore;
import de.haumacher.imageServer.faces.PeopleStore.Name;
import de.haumacher.imageServer.faces.PeopleStore.PersonRefused;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.PersonList;
import de.haumacher.imageServer.shared.model.UserEntry;
import de.haumacher.imageServer.shared.model.UserList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * The nickname a person is shown by on a photograph, see issue #146.
 *
 * <p>
 * One field, one convention, one place: <code>Berta M&uuml;ller (Tante Berta)</code> is typed into
 * the name field and split by {@link PeopleStore#parseName(String)}, so every client asks for the
 * same thing and the register answers both halves. What is tested here is the rule itself with its
 * edges, that the canonical name goes on being the unique one while a nickname is not, that the
 * file round-trips (including one written before this build), and that everything which
 * <em>identifies</em> a person &mdash; <code>?type=users</code>, the XMP import &mdash; goes on
 * naming them canonically.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestPersonNickname extends FacesTestCase {

	private static final String EVE_TOKEN = "eve-token-0123456789";

	@Override
	protected void extraUsers(UserStore store) {
		User eve = new User("eve", Roles.EDIT, "", Instant.now().toString(), Clearances.ALL, true);
		eve.addDevice(new Device("eve's device", UserStore.hash(EVE_TOKEN), Instant.now().toString()));
		store.addUser(eve);
	}

	// --- The rule. ---

	/** The whole convention as a table; every other test here rests on this one. */
	public void testParseName() throws Exception {
		assertName("Berta Müller", "Tante Berta", "Berta Müller (Tante Berta)");
		assertName("Berta Müller", "", "Berta Müller");

		assertName("Berta (Tante Berta) Müller", "", "Berta (Tante Berta) Müller");

		assertName("Oma", "", "  Oma  ");
		assertName("Berta Müller", "Tante Berta", "Berta Müller ( Tante Berta )");
		assertName("Berta Müller", "Tante Berta", "  Berta Müller (Tante Berta)  ");

		// Nesting is counted from the back, so the whole inner group is the nickname.
		assertName("A", "B (C)", "A (B (C))");

		// Brackets that do not balance out are simply characters of a name.
		assertName("Berta Müller)", "", "Berta Müller)");
		assertName("A) B (C", "", "A) B (C");
		assertName("A) B", "C", "A) B (C)");

		// An empty bracket is how a nickname is taken back by editing.
		assertName("Berta", "", "Berta ()");
		assertName("Berta", "", "Berta (   )");

		// A nickname needs somebody to be the nickname of.
		assertRefused("(Oma)");
		assertRefused("  (Oma)  ");
		assertRefused("()");
		assertRefused("   ");
		assertRefused("");
		assertRefused(null);
	}

	private static void assertName(String canonical, String nickname, String typed) throws Exception {
		Name parsed = PeopleStore.parseName(typed);
		assertEquals("Canonical name of '" + typed + "'.", canonical, parsed.getCanonical());
		assertEquals("Nickname of '" + typed + "'.", nickname, parsed.getNickname());
	}

	private static void assertRefused(String typed) {
		try {
			PeopleStore.parseName(typed);
			fail("Expected a refusal of '" + typed + "'.");
		} catch (PersonRefused ex) {
			assertEquals(400, ex.getStatus());
			assertEquals(PeopleStore.NAME_REQUIRED, ex.getMessage());
		}
	}

	// --- Creating and renaming. ---

	/** What is typed is stored as two fields and answered as two fields. */
	public void testCreatingWithANickname() throws Exception {
		createSpace(A_ONE);

		Person berta = created("Berta Müller (Tante Berta)");
		assertEquals("Berta Müller", berta.getName());
		assertEquals("Tante Berta", berta.getNickname());

		Person plain = created("Carl Meier");
		assertEquals("Carl Meier", plain.getName());
		assertEquals("Somebody without a nickname has none, not an empty one to show.",
			"", plain.getNickname());

		PersonList listed = people(_adminToken);
		assertEquals(2, listed.getPeople().size());
		assertEquals("Tante Berta", listed.getPeople().get(0).getNickname());
		assertEquals("", listed.getPeople().get(1).getNickname());

		FakeResponse blank = post("/", "create-person", "{\"name\":\"(Oma)\"}", _adminToken);
		assertEquals(400, blank.status());
		assertEquals(PeopleStore.NAME_REQUIRED, errorMessage(blank));
		assertEquals("And nothing was written.", 2, people(_adminToken).getPeople().size());
	}

	/** The field holds the whole statement: a plain name clears the nickname. */
	public void testRenamingClearsTheNickname() throws Exception {
		createSpace(A_ONE);
		Person berta = created("Berta Müller (Tante Berta)");

		Person renamed = renamed(berta.getId(), "Berta Schmidt (Oma Berta)");
		assertEquals("Berta Schmidt", renamed.getName());
		assertEquals("Oma Berta", renamed.getNickname());

		Person plain = renamed(berta.getId(), "Berta Schmidt");
		assertEquals("Berta Schmidt", plain.getName());
		assertEquals("Deleting the bracket takes the nickname back.", "", plain.getNickname());

		assertEquals("", people(_adminToken).getPeople().get(0).getNickname());
	}

	/** The canonical name is the unique one; two grandmothers are both "Oma". */
	public void testOnlyTheCanonicalNameIsUnique() throws Exception {
		createSpace(A_ONE);
		created("Berta Müller (Oma)");

		Person anna = created("Anna Schmidt (Oma)");
		assertEquals("A nickname need not be unique.", "Oma", anna.getNickname());

		FakeResponse again = post("/", "create-person",
			"{\"name\":\"berta müller (x)\"}", _adminToken);
		assertEquals(409, again.status());
		assertEquals(PeopleStore.personExists("berta müller"), errorMessage(again));

		FakeResponse renamed = post("/", "rename-person",
			"{\"id\":\"" + anna.getId() + "\",\"name\":\"BERTA MÜLLER (Oma)\"}", _adminToken);
		assertEquals("A rename onto another canonical name is refused too.", 409, renamed.status());
	}

	/** A typed name finds a person by either half; the import's lookup does not. */
	public void testLookingUpATypedName() throws Exception {
		createSpace(A_ONE);
		Person berta = created("Berta Müller (Tante Berta)");

		PeopleStore store = new PeopleStore(_base);
		assertEquals(berta.getId(), store.byName("Berta Müller").getId());
		assertEquals(berta.getId(), store.byName("  tante berta ").getId());
		assertNull(store.byName("Tante Erna"));

		assertEquals(berta.getId(), store.byCanonicalName("berta müller").getId());
		assertNull("A nickname is nobody's canonical name.", store.byCanonicalName("Tante Berta"));
	}

	// --- The file. ---

	/** Both halves survive a restart, and a register written before this build reads as it did. */
	public void testTheFile() throws Exception {
		createSpace(A_ONE);
		created("Berta Müller (Tante Berta)");
		created("Carl Meier");

		PeopleStore reread = new PeopleStore(_base);
		assertEquals("Berta Müller", reread.getPeople().get(0).getName());
		assertEquals("Tante Berta", reread.getPeople().get(0).getNickname());
		assertEquals("", reread.getPeople().get(1).getNickname());

		String written = Files.readString(reread.getFile(), StandardCharsets.UTF_8);
		assertTrue(written, written.contains("\"nickname\":\"Tante Berta\""));
		assertEquals("Written only where there is one, so an old file stays the file it was.",
			1, count(written, "\"nickname\""));

		restart();
		assertEquals("Tante Berta", people(_adminToken).getPeople().get(0).getNickname());
	}

	/** A register of an older build knows no nickname, and says so rather than failing. */
	public void testARegisterWrittenBeforeThisBuild() throws Exception {
		createSpace(A_ONE);
		Path file = new PeopleStore(_base).getFile();
		Files.writeString(file,
			"{\"version\":1,\"people\":[{\"id\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"name\":\"Berta Müller\","
				+ "\"aliases\":[],\"user\":\"\",\"created\":\"2026-01-01T00:00:00Z\","
				+ "\"createdBy\":\"user:haui\"}]}",
			StandardCharsets.UTF_8);

		PeopleStore old = new PeopleStore(_base);
		assertEquals(1, old.getPeople().size());
		assertEquals("Berta Müller", old.getPeople().get(0).getName());
		assertEquals("", old.getPeople().get(0).getNickname());

		restart();
		Person answered = people(_adminToken).getPeople().get(0);
		assertEquals("Berta Müller", answered.getName());
		assertEquals("", answered.getNickname());
	}

	// --- Merging and identifying. ---

	/** The survivor keeps their nickname, and takes the other's where they have none. */
	public void testMergingCarriesTheNickname() throws Exception {
		createSpace(A_ONE);

		Person keeps = created("Berta Müller (Tante Berta)");
		Person other = created("B. Müller (Oma)");
		assertEquals("Tante Berta", merged(keeps.getId(), other.getId()).getNickname());

		Person bare = created("Carl Meier");
		Person nicknamed = created("C. Meier (Opa)");
		Person survivor = merged(bare.getId(), nicknamed.getId());
		assertEquals("Carl Meier", survivor.getName());
		assertEquals("Better a nickname than none.", "Opa", survivor.getNickname());

		restart();
		assertEquals("Tante Berta", people(_adminToken).getPeople().get(0).getNickname());
		assertEquals("Opa", people(_adminToken).getPeople().get(1).getNickname());
	}

	/** A member is named by their canonical name, whatever they are called on a photograph. */
	public void testTheUserListNamesThemCanonically() throws Exception {
		createSpace(A_ONE);
		Person berta = created("Berta Müller (Tante Berta)");

		FakeResponse linked = post("/", "link-person",
			"{\"id\":\"" + berta.getId() + "\",\"user\":\"eve\"}", _adminToken);
		assertEquals(linked.body(), 200, linked.status());

		UserEntry eve = entry(users(), "eve");
		assertEquals(berta.getId(), eve.getPerson());
		assertEquals("Identifying somebody is the full name's job.",
			"Berta Müller", eve.getPersonName());
	}

	// --- Helpers. ---

	private Person created(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private Person renamed(String id, String name) throws Exception {
		FakeResponse response = post("/", "rename-person",
			"{\"id\":\"" + id + "\",\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private Person merged(String into, String from) throws Exception {
		FakeResponse response = post("/", "merge-persons",
			"{\"into\":\"" + into + "\",\"from\":\"" + from + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private PersonList people(String token) throws Exception {
		FakeResponse response = get("/", "people", token);
		assertEquals(response.body(), 200, response.status());
		return PersonList.readPersonList(reader(response.body()));
	}

	private UserList users() throws Exception {
		FakeResponse response = get("/", "users", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return UserList.readUserList(reader(response.body()));
	}

	private static UserEntry entry(UserList users, String name) {
		for (UserEntry entry : users.getUsers()) {
			if (name.equals(entry.getName())) {
				return entry;
			}
		}
		fail("No user '" + name + "'.");
		return null;
	}

	private static int count(String text, String needle) {
		int result = 0;
		for (int n = text.indexOf(needle); n >= 0; n = text.indexOf(needle, n + needle.length())) {
			result++;
		}
		return result;
	}
}
