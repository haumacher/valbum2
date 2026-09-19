/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.Invitation;
import de.haumacher.imageServer.shared.model.InvitationCreated;
import de.haumacher.imageServer.shared.model.InvitationList;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.UserEntry;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The request helpers the invitation tests of issue #52 are driven with.
 *
 * <p>
 * The {@link SharingFixture} of issue #49 as {@link SpaceTestCase} sets it up: alice is the admin
 * and the library is migrated, bob, carol and dave are members and eve is a guest. That is exactly
 * the library an invitation lands in.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public abstract class InviteTestCase extends SpaceTestCase {

	// --- Inviting over HTTP. ---

	/** Sends <code>&lt;data&gt;/?action=invite</code> asking for the given role. */
	protected FakeResponse invite(String token, String role) throws Exception {
		return invite(token, role, "", "");
	}

	/** Sends <code>&lt;data&gt;/?action=invite</code> with role, expiry and note. */
	protected FakeResponse invite(String token, String role, String expires, String note) throws Exception {
		return invite(token, role, expires, note, "");
	}

	/** Sends <code>&lt;data&gt;/?action=invite</code> with role, expiry, note and recipient. */
	protected FakeResponse invite(String token, String role, String expires, String note, String recipient)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "invite");
		String body = "{\"role\":\"" + role + "\",\"expires\":\"" + expires + "\",\"note\":\"" + note
			+ "\",\"recipient\":\"" + recipient + "\"}";
		return post("/", body, token, parameters);
	}

	/** Sends <code>&lt;data&gt;/?action=uninvite</code> naming the invitation of the given id. */
	protected FakeResponse uninvite(String token, String id) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "uninvite");
		return post("/", "{\"id\":\"" + id + "\"}", token, parameters);
	}

	/** Sends <code>&lt;data&gt;/?type=invitations</code>. */
	protected FakeResponse invitations(String token) throws Exception {
		return get("/", "invitations", token);
	}

	/** Sends <code>&lt;data&gt;/?action=promote</code> naming the user to promote. */
	protected FakeResponse promote(String token, String name) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "promote");
		return post("/", "{\"name\":\"" + name + "\"}", token, parameters);
	}

	/**
	 * Sends <code>&lt;data&gt;/?action=pair</code> redeeming the given invitation token.
	 *
	 * <p>
	 * The ordinary pairing since issue #89: the token of an invitation is a code, and it travels
	 * in the field every other code travels in. What the retired {@link #acceptAsBefore} field
	 * does is asserted separately.
	 * </p>
	 */
	protected FakeResponse accept(String invitation, String userName) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "pair");
		String body = "{\"deviceCode\":\"" + invitation + "\",\"deviceName\":\"Phone\",\"userName\":\""
			+ userName + "\"}";
		return post("/", body, null, parameters);
	}

	/** Redeems an invitation through the retired <code>invitation</code> field (issue #52). */
	protected FakeResponse acceptAsBefore(String invitation, String userName) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "pair");
		String body = "{\"invitation\":\"" + invitation + "\",\"deviceName\":\"Phone\",\"userName\":\""
			+ userName + "\"}";
		return post("/", body, null, parameters);
	}

	/** Sends <code>&lt;data&gt;/?type=auth</code> with the given bearer. */
	protected FakeResponse authOf(String token) throws Exception {
		return get("/", "auth", token);
	}

	// --- Reading the answers. ---

	protected static InvitationCreated created(FakeResponse response) throws IOException {
		return InvitationCreated.readInvitationCreated(reader(body(response)));
	}

	protected static InvitationList list(FakeResponse response) throws IOException {
		return InvitationList.readInvitationList(reader(body(response)));
	}

	protected static PairResponse paired(FakeResponse response) throws IOException {
		return PairResponse.readPairResponse(reader(body(response)));
	}

	protected static AuthInfo auth(FakeResponse response) throws IOException {
		return AuthInfo.readAuthInfo(reader(body(response)));
	}

	protected static UserEntry user(FakeResponse response) throws IOException {
		return UserEntry.readUserEntry(reader(body(response)));
	}

	/** The invitation of the given id in a listing, <code>null</code> if it lists none. */
	protected static Invitation invitation(InvitationList list, String id) {
		for (Invitation invitation : list.getInvitations()) {
			if (invitation.getId().equals(id)) {
				return invitation;
			}
		}
		return null;
	}

	/** The invitations of the running server, derived from its users and codes (issue #89). */
	protected java.util.List<AuthService.Invited> stored() throws Exception {
		return servlet().auth().invitations();
	}

	/** The invitation of the given id on the running server, <code>null</code> if there is none. */
	protected AuthService.Invited stored(String id) throws Exception {
		return servlet().auth().invitation(id);
	}

	/**
	 * Issues an invitation directly, without the endpoint, and answers its token.
	 *
	 * <p>
	 * The way to build an invitation that is already expired or that a test wants to see before
	 * the servlet does: the endpoint refuses a past instant, and rightly so.
	 * </p>
	 */
	protected String issue(String role, String invitedBy, String expires) throws Exception {
		return servlet().auth()
			.createInvitation(invitedBy, "", role, Clearances.ofRole(role), false, "", expires, "")
			.getToken();
	}
}
