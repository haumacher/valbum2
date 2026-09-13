/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.InvitationStore;
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
 * The {@link SharingFixture} of issue #49 as {@link LinkTestCase} sets it up: alice is the admin
 * and the library is migrated, bob, carol and dave are members and eve is a guest. That is exactly
 * the library an invitation lands in.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public abstract class InviteTestCase extends LinkTestCase {

	// --- Inviting over HTTP. ---

	/** Sends <code>&lt;data&gt;/?action=invite</code> asking for the given role. */
	protected FakeResponse invite(String token, String role) throws Exception {
		return invite(token, role, "", "");
	}

	/** Sends <code>&lt;data&gt;/?action=invite</code> with role, expiry and note. */
	protected FakeResponse invite(String token, String role, String expires, String note) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "invite");
		String body = "{\"role\":\"" + role + "\",\"expires\":\"" + expires + "\",\"note\":\"" + note + "\"}";
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

	/** Sends <code>&lt;data&gt;/?action=pair</code> accepting the given invitation token. */
	protected FakeResponse accept(String invitation, String userName) throws Exception {
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

	/** The invitation store on disk, freshly read. */
	protected InvitationStore store() {
		return new InvitationStore(_base);
	}

	/**
	 * Issues an invitation directly in the store and answers its token.
	 *
	 * <p>
	 * The way to build an invitation that is already expired: the endpoint refuses to create one,
	 * and rightly so. The caller restarts the servlet, so that it reads what was written here.
	 * </p>
	 */
	protected String issue(String role, String invitedBy, String expires) throws Exception {
		InvitationStore.Issued issued = new InvitationStore(_base).create(role, invitedBy, "", expires);
		restartServer();
		return issued.getToken();
	}
}
