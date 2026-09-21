/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.faces.PeopleStore;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.PersonList;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.UserEntry;
import de.haumacher.imageServer.shared.model.UserList;
import java.time.Instant;

/**
 * A person of the register who is a member of the space, see issue #128.
 *
 * <p>
 * The link is one string on the person and is answered from both ends &mdash;
 * <code>?type=people</code> says which member a person is, <code>?type=users</code> says which
 * person a member is. What is tested here is who may say it, what happens to it when people are
 * merged or accounts removed, and that it survives a restart, because it lies in
 * <code>people.json</code> like everything else about a person.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestPersonLink extends FacesTestCase {

	private static final String EVE_TOKEN = "eve-token-0123456789";

	private static final String BOB_TOKEN = "bob-token-0123456789";

	private static final String DAVE_TOKEN = "dave-token-0123456789";

	@Override
	protected void extraUsers(UserStore store) {
		store.addUser(user("eve", EVE_TOKEN, Roles.EDIT));
		store.addUser(user("bob", BOB_TOKEN, Roles.CONTRIBUTE));
		store.addUser(user("dave", DAVE_TOKEN, Roles.VIEW));
	}

	private static User user(String name, String token, String role) {
		User result = new User(name, role, "", Instant.now().toString(), Clearances.ALL, true);
		result.addDevice(new Device(name + "'s device", UserStore.hash(token), Instant.now().toString()));
		return result;
	}

	// --- The link itself. ---

	/** Link, read from both ends, unlink; and a restart changes nothing. */
	public void testAPersonIsAMember() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");

		Person linked = link(anna.getId(), "eve", _adminToken);
		assertEquals("eve", linked.getUser());
		assertEquals("The register says which member this person is.", "eve", user(anna.getId()));

		UserEntry eve = entry(users(_adminToken), "eve");
		assertEquals("And the user list says which person this member is.", anna.getId(), eve.getPerson());
		assertEquals("Anna", eve.getPersonName());
		assertEquals("Nobody else is anybody.", "", entry(users(_adminToken), "bob").getPerson());

		restart();
		assertEquals("The link lies in people.json like everything else.", "eve", user(anna.getId()));
		assertEquals(anna.getId(), entry(users(_adminToken), "eve").getPerson());

		assertEquals("", link(anna.getId(), "", _adminToken).getUser());
		assertEquals("", user(anna.getId()));
		assertEquals("", entry(users(_adminToken), "eve").getPerson());
		assertEquals("", entry(users(_adminToken), "eve").getPersonName());
	}

	/** Renaming a person renames what the user list shows; the link is one string in one place. */
	public void testTheUserListShowsTheNameOfToday() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");
		link(anna.getId(), "eve", _adminToken);

		assertEquals(200, post("/", "rename-person",
			"{\"id\":\"" + anna.getId() + "\",\"name\":\"Anna Schmidt\"}", _adminToken).status());
		assertEquals("Anna Schmidt", entry(users(_adminToken), "eve").getPersonName());
	}

	// --- Who may say it. ---

	/** A member who may edit says "this is me", and nothing more. */
	public void testAMemberLinksThemselvesAndNobodyElse() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");
		Person other = created("Bob");

		Person mine = link(anna.getId(), "eve", EVE_TOKEN);
		assertEquals("eve", mine.getUser());

		FakeResponse somebodyElse = linkResponse(other.getId(), "dave", EVE_TOKEN);
		assertEquals(somebodyElse.body(), 403, somebodyElse.status());
		assertEquals(ImageServlet.LINK_REFUSED, errorMessage(somebodyElse));
		assertEquals("Nothing was written.", "", user(other.getId()));

		// And they take themselves off again.
		assertEquals("", link(anna.getId(), "", EVE_TOKEN).getUser());
	}

	/** Taking somebody else's account off a person is saying something about them, too. */
	public void testAMemberDoesNotUnlinkSomebodyElse() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");
		link(anna.getId(), "dave", _adminToken);

		FakeResponse refused = linkResponse(anna.getId(), "", EVE_TOKEN);
		assertEquals(refused.body(), 403, refused.status());
		assertEquals(ImageServlet.LINK_REFUSED, errorMessage(refused));
		assertEquals("dave", user(anna.getId()));
	}

	/** A contributor, a viewer, an anonymous caller and a share link say nothing at all. */
	public void testNobodyElseLinksAnybody() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");

		FakeResponse contributor = linkResponse(anna.getId(), "bob", BOB_TOKEN);
		assertEquals(contributor.body(), 403, contributor.status());
		assertEquals(ImageServlet.LINK_REFUSED, errorMessage(contributor));

		FakeResponse viewer = linkResponse(anna.getId(), "dave", DAVE_TOKEN);
		assertEquals(403, viewer.status());
		assertEquals(ImageServlet.LINK_REFUSED, errorMessage(viewer));

		FakeResponse anonymous = linkResponse(anna.getId(), "eve", null);
		assertEquals(401, anonymous.status());

		FakeResponse shared = linkResponse(anna.getId(), "eve", shareToken());
		assertEquals(403, shared.status());
		assertEquals("A link is handed an album, not the space's bookkeeping.",
			ImageServlet.PEOPLE_REFUSED, errorMessage(shared));

		assertEquals("Not one of them wrote anything.", "", user(anna.getId()));
	}

	// --- What must exist, and what may not be said twice. ---

	/** Both ends of the link must be somebody. */
	public void testTheEndsOfTheLinkMustExist() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");

		FakeResponse person = linkResponse("nobody", "eve", _adminToken);
		assertEquals(404, person.status());
		assertEquals(PeopleStore.unknownPerson("nobody"), errorMessage(person));

		FakeResponse member = linkResponse(anna.getId(), "nobody", _adminToken);
		assertEquals(404, member.status());
		assertEquals(AuthService.unknownUser("nobody"), errorMessage(member));

		FakeResponse unreadable = post("/", "link-person", "{{{", _adminToken);
		assertEquals(400, unreadable.status());
		assertEquals(ImageServlet.PERSON_UNREADABLE, errorMessage(unreadable));

		assertEquals("", user(anna.getId()));
	}

	/** A pending user is nobody yet: there is nothing to link a person to (issue #89). */
	public void testAPendingUserCannotBeLinked() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");

		FakeResponse invited = post("/", "invite",
			"{\"role\":\"edit\",\"clearance\":\"all\",\"mayShare\":false,\"note\":\"\",\"expires\":\"\","
				+ "\"recipient\":\"Frank\"}", _adminToken);
		assertEquals(invited.body(), 200, invited.status());
		UserEntry pending = pending(users(_adminToken));
		assertTrue("An invitation is a pending user.", pending.isPending());
		assertEquals("Who has no name to be named by.", "", pending.getName());

		FakeResponse refused = linkResponse(anna.getId(), "Frank", _adminToken);
		assertEquals("The memento is not a name.", 404, refused.status());
		assertEquals(AuthService.unknownUser("Frank"), errorMessage(refused));
		assertEquals("", user(anna.getId()));
	}

	/** One member is at most one person. */
	public void testAMemberIsOnePerson() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");
		Person annie = created("Annie");
		link(anna.getId(), "eve", _adminToken);

		FakeResponse again = linkResponse(annie.getId(), "eve", _adminToken);
		assertEquals(409, again.status());
		assertEquals(PeopleStore.userAlreadyLinked("eve"), errorMessage(again));
		assertEquals("The first link stands.", "eve", user(anna.getId()));
		assertEquals("", user(annie.getId()));

		assertEquals("Saying what is already so is no conflict.", "eve",
			link(anna.getId(), "eve", _adminToken).getUser());
	}

	// --- Merging. ---

	/** Two people who are both a member are two people; the server does not guess which link goes. */
	public void testMergingTwoMembersIsRefused() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");
		Person annie = created("Annie");
		link(anna.getId(), "eve", _adminToken);
		link(annie.getId(), "dave", _adminToken);

		FakeResponse refused = post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + annie.getId() + "\"}", _adminToken);
		assertEquals(409, refused.status());
		assertEquals(PeopleStore.MERGE_LINKED, errorMessage(refused));
		assertEquals("Nothing was merged.", 2, people(_adminToken).getPeople().size());

		// Unlink one of them, and the merge goes through and keeps the link that is left.
		link(annie.getId(), "", _adminToken);
		assertEquals(200, post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + annie.getId() + "\"}", _adminToken).status());
		assertEquals("eve", user(anna.getId()));
	}

	/** The survivor of a merge takes the link the other one carried. */
	public void testTheSurvivorTakesTheLink() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");
		Person annie = created("Annie");
		link(annie.getId(), "eve", _adminToken);

		assertEquals(200, post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + annie.getId() + "\"}", _adminToken).status());
		assertEquals("eve", user(anna.getId()));
		assertEquals("And the user list follows.", anna.getId(), entry(users(_adminToken), "eve").getPerson());
	}

	// --- Removing the account. ---

	/** Removing a member unlinks the person and keeps the person and their tags. */
	public void testRemovingAMemberKeepsThePerson() throws Exception {
		createSpace(A_ONE);
		Person anna = created("Anna");
		link(anna.getId(), "eve", _adminToken);

		boolean tagged = detectorAvailable();
		if (tagged) {
			index();
			assertEquals(200, post("/" + ALBUM + "/", "tag-faces",
				"{\"faces\":[{\"image\":\"" + A_ONE + "\",\"face\":0,\"person\":\"" + anna.getId()
					+ "\",\"state\":\"CONFIRMED\"}]}", _adminToken).status());
		}

		FakeResponse removed = post("/", "remove-user", "{\"name\":\"eve\"}", _adminToken);
		assertEquals(removed.body(), 200, removed.status());
		UserList after = UserList.readUserList(reader(removed.body()));
		for (UserEntry entry : after.getUsers()) {
			assertFalse("The account is gone.", "eve".equals(entry.getName()));
		}

		PersonList people = people(_adminToken);
		assertEquals("The person stays: photographs of a former member are still of that person.",
			1, people.getPeople().size());
		assertEquals("Anna", people.getPeople().get(0).getName());
		assertEquals("And is nobody in particular any more.", "", people.getPeople().get(0).getUser());

		if (tagged) {
			ImagePart image = image(album("/" + ALBUM + "/", _adminToken), A_ONE);
			assertEquals("The tags are untouched.", 1, image.getTags().size());
			assertEquals(anna.getId(), image.getTags().get(0).getPerson());
		}
	}

	// --- Helpers. ---

	private Person created(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private Person link(String id, String user, String token) throws Exception {
		FakeResponse response = linkResponse(id, user, token);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private FakeResponse linkResponse(String id, String user, String token) throws Exception {
		return post("/", "link-person", "{\"id\":\"" + id + "\",\"user\":\"" + user + "\"}", token);
	}

	/** Which member the person of the given id is, as <code>?type=people</code> answers it. */
	private String user(String id) throws Exception {
		for (Person person : people(_adminToken).getPeople()) {
			if (person.getId().equals(id)) {
				return person.getUser();
			}
		}
		fail("No person '" + id + "' in the register.");
		return null;
	}

	private PersonList people(String token) throws Exception {
		FakeResponse response = get("/", "people", token);
		assertEquals(response.body(), 200, response.status());
		return PersonList.readPersonList(reader(response.body()));
	}

	private UserList users(String token) throws Exception {
		FakeResponse response = get("/", "users", token);
		assertEquals(response.body(), 200, response.status());
		return UserList.readUserList(reader(response.body()));
	}

	private static UserEntry entry(UserList users, String name) {
		for (UserEntry entry : users.getUsers()) {
			if (entry.getName().equals(name)) {
				return entry;
			}
		}
		fail("No user '" + name + "' in the list.");
		return null;
	}

	private static UserEntry pending(UserList users) {
		for (UserEntry entry : users.getUsers()) {
			if (entry.isPending()) {
				return entry;
			}
		}
		fail("No pending user in the list.");
		return null;
	}

	private String shareToken() throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "share",
			"{\"label\":\"Look\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,"
				+ "\"rights\":[{\"name\":\"view\"}]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return ShareLinkCreated.readShareLinkCreated(reader(response.body())).getToken();
	}
}
