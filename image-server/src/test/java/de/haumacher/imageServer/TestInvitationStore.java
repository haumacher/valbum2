/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.InvitationStore.Issued;
import de.haumacher.imageServer.auth.InvitationStore.Link;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the {@link InvitationStore} of issue #52.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestInvitationStore extends TestCase {

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-invitation-store-test");
	}

	@Override
	protected void tearDown() throws Exception {
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testACreatedInvitationIsFoundByIdAndByToken() throws Exception {
		InvitationStore store = new InvitationStore(_base);

		Issued issued = store.create(Roles.EDIT, "alice", "Uncle Bob", "");
		Link invitation = issued.getInvitation();

		assertSame(invitation, store.get(invitation.getId()));
		assertSame(invitation, store.lookup(issued.getToken()));
		assertNull(store.lookup("a token nobody ever issued"));
		assertNull(store.get("no such id"));
		assertEquals(Roles.EDIT, invitation.getRole());
		assertEquals("alice", invitation.getInvitedBy());
		assertEquals("Uncle Bob", invitation.getNote());
		assertTrue("A fresh invitation can be accepted.", invitation.isLive());
		assertFalse("An invitation without an expiry lives seven days, never forever.",
			invitation.getExpires().isEmpty());
	}

	public void testTheStoreHoldsTheHashNeverTheToken() throws Exception {
		InvitationStore store = new InvitationStore(_base);

		Issued issued = store.create(Roles.VIEW, "alice", "", "");

		String contents = read(store.getFile());
		assertFalse("The token itself must never be stored: " + contents, contents.contains(issued.getToken()));
		assertTrue("The store must hold the token's hash: " + contents,
			contents.contains(UserStore.hash(issued.getToken())));
		assertTrue("The store must be versioned: " + contents, contents.contains("\"version\":1"));
		assertEquals("The store lives beside the other stores of the server.",
			_base.resolve(UserStore.DIRECTORY_NAME).resolve(InvitationStore.FILE_NAME), store.getFile());
	}

	public void testAnAcceptedInvitationIsMarkedUsedAndKept() throws Exception {
		InvitationStore store = new InvitationStore(_base);
		Issued issued = store.create(Roles.EDIT, "alice", "", "");

		Link used = store.markUsed(issued.getInvitation().getId(), "carol");

		assertTrue(used.isUsed());
		assertEquals("carol", used.getUsedBy());
		assertFalse(used.getUsed().isEmpty());
		assertFalse("A used invitation opens nothing any more.", used.isLive());
		assertEquals("The record stays; nothing is ever deleted.", 1, store.getInvitations().size());
		assertSame("The token still finds the record, so that the caller can be told why.", used,
			store.lookup(issued.getToken()));
	}

	public void testAWithdrawnInvitationIsMarkedRevokedAndKept() throws Exception {
		InvitationStore store = new InvitationStore(_base);
		Issued issued = store.create(Roles.VIEW, "bob", "", "");

		Link revoked = store.revoke(issued.getInvitation().getId());

		assertTrue(revoked.isRevoked());
		assertFalse(revoked.isLive());
		assertEquals(1, store.getInvitations().size());
		assertNull("Withdrawing an invitation that is not there changes nothing.", store.revoke("no such id"));
	}

	public void testAnInvitationIsDeadWhenItExpiredWasUsedOrWasWithdrawn() throws Exception {
		InvitationStore store = new InvitationStore(_base);
		Instant now = Instant.parse("2026-09-13T12:00:00Z");

		Link live = store.create(Roles.EDIT, "alice", "", "2026-09-20T00:00:00Z").getInvitation();
		Link expired = store.create(Roles.EDIT, "alice", "", "2026-09-01T00:00:00Z").getInvitation();
		Link used = store.create(Roles.EDIT, "alice", "", "2026-09-20T00:00:00Z").getInvitation();
		store.markUsed(used.getId(), "carol");
		Link revoked = store.create(Roles.EDIT, "alice", "", "2026-09-20T00:00:00Z").getInvitation();
		store.revoke(revoked.getId());

		assertFalse(live.isDead(now));
		assertTrue(expired.isDead(now));
		assertTrue(expired.isExpired(now));
		assertTrue(used.isDead(now));
		assertTrue(revoked.isDead(now));
	}

	public void testAnUnreadableExpiryIsTreatedAsExpired() throws Exception {
		InvitationStore store = new InvitationStore(_base);

		Link broken = store.create(Roles.EDIT, "alice", "", "whenever").getInvitation();

		assertTrue("An invitation whose lifetime nobody can read is not one that lives forever.",
			broken.isExpired(Instant.now()));
		assertTrue(broken.isDead(Instant.now()));
	}

	public void testAStoreWrittenByThisBuildReloadsEqual() throws Exception {
		InvitationStore store = new InvitationStore(_base);
		Issued member = store.create(Roles.EDIT, "alice", "Uncle Bob", "2026-12-24T00:00:00Z");
		Issued guest = store.create(Roles.VIEW, "bob", "", "");
		store.markUsed(guest.getInvitation().getId(), "carol");
		store.revoke(member.getInvitation().getId());

		InvitationStore reloaded = new InvitationStore(_base);

		assertEquals(2, reloaded.getInvitations().size());
		Link reloadedMember = reloaded.lookup(member.getToken());
		assertEquals(member.getInvitation().getId(), reloadedMember.getId());
		assertEquals(Roles.EDIT, reloadedMember.getRole());
		assertEquals("alice", reloadedMember.getInvitedBy());
		assertEquals("Uncle Bob", reloadedMember.getNote());
		assertEquals("2026-12-24T00:00:00Z", reloadedMember.getExpires());
		assertEquals(member.getInvitation().getCreated(), reloadedMember.getCreated());
		assertTrue(reloadedMember.isRevoked());
		Link reloadedGuest = reloaded.lookup(guest.getToken());
		assertEquals(Roles.VIEW, reloadedGuest.getRole());
		assertEquals("carol", reloadedGuest.getUsedBy());
		assertEquals(guest.getInvitation().getUsed(), reloadedGuest.getUsed());
	}

	public void testAFieldThisBuildDoesNotKnowIsSkippedWithoutLosingTheRest() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(InvitationStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, ("{\"version\":2,\"invitations\":[{\"id\":\"abc\",\"tokenHash\":\""
			+ UserStore.hash("the-token") + "\",\"role\":\"guest\",\"invitedBy\":\"alice\","
			+ "\"note\":\"n\",\"expires\":\"2099-01-01T00:00:00Z\",\"created\":\"2026-09-13T10:00:00Z\","
			+ "\"used\":\"\",\"usedBy\":\"\",\"revoked\":\"\",\"invitedByDevice\":\"a field from the future\"}],"
			+ "\"somethingElse\":42}").getBytes(StandardCharsets.UTF_8));

		InvitationStore store = new InvitationStore(_base);

		assertEquals(1, store.getInvitations().size());
		Link invitation = store.get("abc");
		assertNotNull("An unknown field must not lose the record.", invitation);
		assertEquals("The stored string is kept as it is; it is read as a role where it is used.",
			"guest", invitation.getRole());
		assertEquals(Roles.VIEW, Roles.of(invitation.getRole()));
		assertEquals("alice", invitation.getInvitedBy());
		assertEquals("n", invitation.getNote());
		assertSame(invitation, store.lookup("the-token"));
		assertTrue(invitation.isLive());
	}

	public void testAnUnreadableStoreIsKeptAsideInsteadOfOverwritten() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(InvitationStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, "this is not JSON".getBytes(StandardCharsets.UTF_8));

		InvitationStore store = new InvitationStore(_base);
		assertTrue("A broken store must not lock the server up.", store.getInvitations().isEmpty());
		store.create(Roles.EDIT, "alice", "", "");

		String[] names = file.getParent().toFile().list();
		boolean kept = false;
		for (String name : names) {
			kept |= name.startsWith(InvitationStore.FILE_NAME + ".broken-");
		}
		assertTrue("The unreadable file must be kept for repair: " + String.join(", ", names), kept);
		assertEquals(1, new InvitationStore(_base).getInvitations().size());
	}

	private static String read(Path file) throws Exception {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}
}
