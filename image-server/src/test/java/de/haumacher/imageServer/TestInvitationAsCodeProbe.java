/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.shared.model.PairRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Probe for issue #89 (step two): invitations carried over from the store of the build before,
 * composed with the dead-address redirect of #88 and with a second space.
 */
@SuppressWarnings("javadoc")
public class TestInvitationAsCodeProbe extends InviteTestCase {

	/** A link sent under the old store keeps every #88 answer after the migration. */
	public void testAMigratedInvitationKeepsItsRedirectReasons() throws Exception {
		InvitationStore old = new InvitationStore(_base);
		String live = old.create(Roles.VIEW, Clearances.PUBLIC, false, "alice", "Grandma",
			Instant.now().plus(Duration.ofDays(3)).toString()).getToken();
		String stale = old.create(Roles.VIEW, "alice", "Late",
			Instant.now().minus(Duration.ofDays(1)).toString()).getToken();
		InvitationStore.Issued withdrawn = old.create(Roles.VIEW, "alice", "Sorry", "");
		old.revoke(withdrawn.getInvitation().getId());
		restartServer();
		assertEquals(HttpServletResponse.SC_OK, authOf(live).status());

		InvitationRedirect redirect = new InvitationRedirect(
			Spaces.detect(_base, SpaceMode.SINGLE, AuthMode.WRITES, InviteMode.MEMBERS));
		assertNull("A live link serves the app.", redirect.redirect("", "i", live));
		// A dead invitation of the old store is dropped by the migration, not carried over: its
		// link is answered as unknown, which still sends the visitor to the start page with a word.
		assertEquals("/?invitation=unknown", redirect.redirect("", "i", stale));
		assertEquals("/?invitation=unknown", redirect.redirect("", "i", withdrawn.getToken()));
		assertEquals("/?invitation=unknown", redirect.redirect("", "i", "never-issued"));

		assertEquals(HttpServletResponse.SC_OK, accept(live, "grandma").status());
		// The redirect of a running server shares the servlet's stores; this test's stands apart
		// and reads the folder anew, as a restarted server would.
		InvitationRedirect afterwards = new InvitationRedirect(
			Spaces.detect(_base, SpaceMode.SINGLE, AuthMode.WRITES, InviteMode.MEMBERS));
		assertEquals("The second click on the same link goes to the start page.",
			"/?invitation=used", afterwards.redirect("", "i", live));
		assertEquals(HttpServletResponse.SC_GONE, authOf(live).status());
	}

	/** An invitation belongs to its space: elsewhere its token is nobody and signs nobody in. */
	public void testAnInvitationOfOneSpaceIsNobodyInAnother() throws Exception {
		String alice = signInAlice();
		String token = tokenOf(created(invite(alice, Roles.VIEW)).getUrl());

		Path other = Files.createTempDirectory("valbum-other-space");
		try {
			AuthService elsewhere = new AuthService(AuthMode.WRITES, other);
			try {
				elsewhere.pair(PairRequest.create().setDeviceCode(token).setUserName("mallory").setDeviceName("Phone"));
				fail("A token of another space must not sign anybody in.");
			} catch (AuthService.PairRefused expected) {
				assertEquals(HttpServletResponse.SC_UNAUTHORIZED, expected.getStatus());
				assertEquals(AuthService.DEVICE_CODE_UNKNOWN, expected.getMessage());
			}
			assertEquals("Nothing was created over there.", 1, elsewhere.getUsers().getUsers().size());
			// And the invitation is still whole where it belongs.
			assertEquals(HttpServletResponse.SC_OK, accept(token, "grandma").status());
		} finally {
			try (Stream<Path> walk = Files.walk(other)) {
				walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
	}

	private String signInAlice() throws Exception {
		FakeResponse response = post("/", Codes.pairRequest(Codes.forUser(servlet().auth(), "alice"),
			"Alice's laptop", "alice"), null, java.util.Collections.singletonMap("action", "pair"));
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return paired(response).getToken();
	}

	private static String tokenOf(String url) {
		String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		return trimmed.substring(trimmed.lastIndexOf('/') + 1);
	}

}
