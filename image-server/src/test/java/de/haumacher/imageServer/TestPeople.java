/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.faces.PeopleStore;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.PersonList;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The people register of a space: creating, renaming, merging and who may, see issue #125.
 *
 * <p>
 * Not one of these tests needs a detector: the register is a file of names beside the album tree
 * and answers the same on a machine that cannot look at a photograph at all.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestPeople extends FacesTestCase {

	private static final String BOB_TOKEN = "bob-token-0123456789";

	private static final String DAVE_TOKEN = "dave-token-0123456789";

	@Override
	protected void extraUsers(UserStore store) {
		store.addUser(user("bob", BOB_TOKEN, Roles.CONTRIBUTE));
		store.addUser(user("dave", DAVE_TOKEN, Roles.VIEW));
	}

	private static User user(String name, String token, String role) {
		User result = new User(name, role, "", Instant.now().toString(), Clearances.ALL, true);
		result.addDevice(new Device(name + "'s device", UserStore.hash(token), Instant.now().toString()));
		return result;
	}

	// --- The register. ---

	/** Create, read, rename, merge, read again. */
	public void testTheRegisterIsKept() throws Exception {
		createSpace(A_ONE);

		Person anna = created("Anna");
		Person bob = created("Bob");
		assertFalse("Every person has an id of their own.", anna.getId().equals(bob.getId()));
		assertEquals(22, anna.getId().length());
		assertEquals(names("Anna", "Bob"), names(people(_adminToken)));

		FakeResponse renamed = post("/", "rename-person",
			"{\"id\":\"" + anna.getId() + "\",\"name\":\"Anna Schmidt\"}", _adminToken);
		assertEquals(renamed.body(), 200, renamed.status());
		assertEquals(anna.getId(), person(renamed).getId());
		assertEquals(names("Anna Schmidt", "Bob"), names(people(_adminToken)));

		FakeResponse merged = post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + bob.getId() + "\"}", _adminToken);
		assertEquals(merged.body(), 200, merged.status());
		assertEquals("The survivor keeps their id.", anna.getId(), person(merged).getId());

		PersonList after = people(_adminToken);
		assertEquals("The merged person is gone from the list.", names("Anna Schmidt"), names(after));
		assertEquals("And is an alias of the one they were merged into.", 1,
			after.getPeople().get(0).getAliases().size());
		assertEquals(bob.getId(), after.getPeople().get(0).getAliases().get(0).getId());
	}

	/** An id that was merged away goes on naming the person it was merged into. */
	public void testAnAliasResolves() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");
		Person bob = created("Bob");
		assertEquals(200, post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + bob.getId() + "\"}", _adminToken).status());

		PeopleStore people = _servlet.people();
		assertEquals("The old id still names somebody.", anna.getId(), people.resolve(bob.getId()).getId());

		// A merge of the merged one is a merge of the survivor: renaming through the old id works.
		FakeResponse renamed = post("/", "rename-person",
			"{\"id\":\"" + bob.getId() + "\",\"name\":\"Anna B\"}", _adminToken);
		assertEquals(renamed.body(), 200, renamed.status());
		assertEquals(anna.getId(), person(renamed).getId());
	}

	/** What is written survives a restart, aliases and all. */
	public void testTheRegisterIsOnDisk() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");
		Person bob = created("Bob");
		assertEquals(200, post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + bob.getId() + "\"}", _adminToken).status());

		PeopleStore reread = new PeopleStore(_base);
		assertEquals(1, reread.getPeople().size());
		assertEquals("Anna", reread.getPeople().get(0).getName());
		assertEquals(anna.getId(), reread.resolve(bob.getId()).getId());
		assertEquals("user:haui", reread.getPeople().get(0).getCreatedBy());

		String written = Files.readString(reread.getFile(), StandardCharsets.UTF_8);
		assertTrue(written, written.contains("\"version\":1"));
		assertTrue(written, written.contains("\"aliases\":[\"" + bob.getId() + "\"]"));
	}

	/** A name is given once, whatever it is spelled like; and nobody is nameless. */
	public void testNames() throws Exception {
		createSpace(A_ONE);
		created("Anna");

		FakeResponse again = post("/", "create-person", "{\"name\":\"  anna \"}", _adminToken);
		assertEquals(409, again.status());
		assertEquals(PeopleStore.personExists("anna"), errorMessage(again));

		FakeResponse blank = post("/", "create-person", "{\"name\":\"   \"}", _adminToken);
		assertEquals(400, blank.status());
		assertEquals(PeopleStore.NAME_REQUIRED, errorMessage(blank));

		Person bob = created("Bob");
		FakeResponse collide = post("/", "rename-person",
			"{\"id\":\"" + bob.getId() + "\",\"name\":\"ANNA\"}", _adminToken);
		assertEquals(409, collide.status());

		FakeResponse itself = post("/", "rename-person",
			"{\"id\":\"" + bob.getId() + "\",\"name\":\"Bob\"}", _adminToken);
		assertEquals("Keeping one's own name is not a duplicate.", 200, itself.status());
	}

	/** An id nobody holds is nobody, and nobody is merged into themselves. */
	public void testUnknownIds() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");

		FakeResponse rename = post("/", "rename-person", "{\"id\":\"nobody\",\"name\":\"X\"}", _adminToken);
		assertEquals(404, rename.status());
		assertEquals(PeopleStore.unknownPerson("nobody"), errorMessage(rename));

		FakeResponse into = post("/", "merge-persons",
			"{\"into\":\"nobody\",\"from\":\"" + anna.getId() + "\"}", _adminToken);
		assertEquals(404, into.status());

		FakeResponse from = post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"nobody\"}", _adminToken);
		assertEquals(404, from.status());

		FakeResponse self = post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + anna.getId() + "\"}", _adminToken);
		assertEquals(400, self.status());
		assertEquals(PeopleStore.MERGE_SELF, errorMessage(self));
	}

	/** A request nobody can read is refused, and nothing is written. */
	public void testAnUnreadableRequest() throws Exception {
		createSpace(A_ONE);
		FakeResponse response = post("/", "create-person", "not json at all", _adminToken);
		assertEquals(400, response.status());
		assertEquals(ImageServlet.PERSON_UNREADABLE, errorMessage(response));
		assertEquals(0, people(_adminToken).getPeople().size());
	}

	// --- Who may. ---

	/** Everybody who is signed in reads the register; a viewer does not change it. */
	public void testAViewerReadsAndDoesNotWrite() throws Exception {
		createSpace(A_ONE);
		created("Anna");

		assertEquals("The names are what the boxes mean.", names("Anna"), names(people(DAVE_TOKEN)));

		FakeResponse refused = post("/", "create-person", "{\"name\":\"Bob\"}", DAVE_TOKEN);
		assertEquals(refused.body(), 403, refused.status());
	}

	/** A contributor may not change the register either. */
	public void testAContributorDoesNotWrite() throws Exception {
		createSpace(A_ONE);
		FakeResponse refused = post("/", "create-person", "{\"name\":\"Bob\"}", BOB_TOKEN);
		assertEquals(refused.body(), 403, refused.status());
	}

	/** A share link is told that the people are not its business; an anonymous caller is asked in. */
	public void testAShareLinkAndAnAnonymousCaller() throws Exception {
		createSpace(A_ONE);
		created("Anna");
		String token = shareToken();

		FakeResponse shared = get("/", "people", token);
		assertEquals(403, shared.status());
		assertEquals(ImageServlet.PEOPLE_REFUSED, errorMessage(shared));

		FakeResponse creating = post("/", "create-person", "{\"name\":\"Bob\"}", token);
		assertEquals(403, creating.status());
		assertEquals(ImageServlet.PEOPLE_REFUSED, errorMessage(creating));

		FakeResponse anonymous = get("/", "people", null);
		assertEquals("This space is open to look at, and the people in it are not.", 401,
			anonymous.status());
	}

	/** A server without authentication has no members to be one of: everybody is its owner. */
	public void testAServerWithoutAuthentication() throws Exception {
		createSpace(A_ONE);
		created("Anna");

		// The same library, served by a servlet that asks nobody who they are.
		_servlet.destroy();
		_servlet = new ImageServlet(_base.toFile(), AuthService.disabled(), "",
			SpaceStore.load(_base, ""));
		_servlet.init();

		assertEquals(names("Anna"), names(people(null)));
		FakeResponse created = post("/", "create-person", "{\"name\":\"Bob\"}", null);
		assertEquals(created.body(), 200, created.status());
	}

	// --- Helpers. ---

	private Person created(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return person(response);
	}

	private static Person person(FakeResponse response) throws Exception {
		return Person.readPerson(reader(response.body()));
	}

	private PersonList people(String token) throws Exception {
		FakeResponse response = get("/", "people", token);
		assertEquals(response.body(), 200, response.status());
		return PersonList.readPersonList(reader(response.body()));
	}

	private static List<String> names(PersonList people) {
		List<String> result = new ArrayList<>();
		for (Person person : people.getPeople()) {
			result.add(person.getName());
		}
		return result;
	}

	private static List<String> names(String... names) {
		List<String> result = new ArrayList<>();
		for (String name : names) {
			result.add(name);
		}
		return result;
	}

	private String shareToken() throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "share",
			"{\"label\":\"Look\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,"
				+ "\"rights\":[{\"name\":\"view\"}]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return ShareLinkCreated.readShareLinkCreated(reader(response.body())).getToken();
	}
}
