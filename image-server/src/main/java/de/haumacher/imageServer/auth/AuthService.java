/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.PathInfo;
import de.haumacher.imageServer.auth.GrantStore.Grant;
import de.haumacher.imageServer.auth.GroupStore.Group;
import de.haumacher.imageServer.auth.UserStore.Login;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.links.LinkStore;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.InvitationInfo;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.ShareInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides who may read and who may write, see {@link AuthMode}.
 *
 * <p>
 * A caller identifies itself with an <code>Authorization: Bearer &lt;token&gt;</code> header
 * carrying a token this server issued during sign-in, see {@link #pair(PairRequest)}. Everything
 * the servlet needs to know about a request is condensed into a {@link Caller}: who it is, what it
 * may do, and which folder its paths are resolved against, see {@link Caller#getSpace()}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AuthService {

	private static final Logger LOG = Logger.getLogger(AuthService.class.getName());

	/** The message an anonymous caller is refused a write with. */
	public static final String WRITE_REFUSED =
		"This server requires a paired device for changes. Pair this device in the server settings.";

	/** The message an anonymous caller is refused a read with. */
	public static final String READ_REFUSED =
		"This server requires a paired device. Pair this device in the server settings.";

	/** The message a caller with an unknown token is refused with. */
	public static final String TOKEN_REFUSED =
		"This device is no longer paired with the server. Pair it again in the server settings.";

	/** The message a wrong pairing secret is refused with. */
	public static final String SECRET_REFUSED = "Wrong pairing secret.";

	/** The message pairing is refused with while the server runs without authentication. */
	public static final String PAIRING_DISABLED =
		"This server runs without authentication; there is nothing to pair with.";

	/**
	 * The message an anonymous caller is refused with once the library belongs to its users.
	 *
	 * <p>
	 * Before the library is migrated the base folder <em>is</em> the owner's library and anonymous
	 * reads stay open in mode {@link AuthMode#WRITES}. Afterwards the base folder holds nothing but
	 * user spaces, and an anonymous caller has no space to look at.
	 * </p>
	 */
	public static final String LIBRARY_REFUSED =
		"This library belongs to its users. Sign in on this device to see your photos, "
			+ "or open a share link you were given.";

	/** The message a caller is refused with whose stored user has a role this build does not know. */
	public static final String ROLE_REFUSED =
		"This user has a role this server does not know; the user store needs repair. Known roles: "
			+ "admin, member, guest.";

	/**
	 * The first character of a path segment addressing another user's library, see
	 * {@link #resolve(Caller, Path, String)}.
	 *
	 * <p>
	 * The <code>~</code> of home directories: <code>&lt;data&gt;/~alice/2024/</code> is the folder
	 * <code>2024</code> in alice's space, whoever is asking. It is the only way out of one's own
	 * space, and it is what a share link and a copied URL use.
	 * </p>
	 */
	public static final String HOME_PREFIX = "~";

	/**
	 * The message a chain of link entries is refused with that is too long or closed into a ring.
	 *
	 * <p>
	 * A link may point at a folder holding links of its own, and following them is what makes a
	 * shared folder of shared folders work, see issue #50. A chain that never ends is a mistake
	 * somebody made and is answered as a path that is not there — spoken, never silently.
	 * </p>
	 */
	public static final String LINK_LOOP =
		"This shared album cannot be opened: the links leading to it point in a circle.";

	/** The message a link record naming a target outside any library is refused with. */
	public static final String LINK_ESCAPED =
		"This shared album cannot be opened: it points outside the library it names.";

	/** The message a caller is refused with that may not look at a folder. */
	public static final String VIEW_REFUSED = "You may not look at this album.";

	/** The message a caller is refused with that may look but not download originals. */
	public static final String DOWNLOAD_REFUSED =
		"You may look at this album but not download its original files.";

	/** The message a caller is refused with that may look but not add to a folder. */
	public static final String CONTRIBUTE_REFUSED = "You may look at this album but not add to it.";

	/** The message a caller is refused with that may look but not change a folder. */
	public static final String EDIT_REFUSED = "You may look at this album but not change it.";

	/** The message a caller is refused the grants of a space with. */
	public static final String GRANTS_REFUSED =
		"Only the owner of this library and the administrator may see and change who it is shared with.";

	/** The message a caller of an expired share link is refused with, see issue #51. */
	public static final String LINK_EXPIRED = "This link has expired.";

	/** The message a caller of a withdrawn share link is refused with. */
	public static final String LINK_REVOKED = "This link was withdrawn.";

	/** The message a caller of an expired invitation is refused with, see issue #52. */
	public static final String INVITATION_EXPIRED = "This invitation has expired.";

	/** The message a caller of a withdrawn invitation is refused with. */
	public static final String INVITATION_REVOKED = "This invitation was withdrawn.";

	/** The message a caller of an invitation somebody already accepted is refused with. */
	public static final String INVITATION_USED = "This invitation was already used.";

	/** The message a token that is no invitation of this server is refused acceptance with. */
	public static final String INVITATION_UNKNOWN_TOKEN =
		"This invitation is not one this server knows. Ask for a new one.";

	/** The message a request naming an invitation this server does not have is refused with. */
	public static final String INVITATION_UNKNOWN = "There is no invitation of that id.";

	/** The message an invitation with an unreadable expiry is refused creation with. */
	public static final String INVITATION_EXPIRY_REFUSED =
		"The expiry of an invitation is an ISO-8601 instant such as '2026-12-24T00:00:00Z', "
			+ "or empty for seven days from now.";

	/** The message an invitation into the administrator's seat is refused with. */
	public static final String INVITATION_ADMIN_REFUSED =
		"A library has exactly one owner and nobody is invited into that seat. Invite a 'member' or a 'guest'.";

	/** The message an invitation naming a role this server does not know is refused with. */
	public static String invitationRoleRefused(String role) {
		return "'" + role + "' is no role this server invites anybody as. Invite a 'member' or a 'guest'.";
	}

	/** The message a guest is refused the issuing of an invitation with. */
	public static final String INVITE_GUEST_REFUSED =
		"A guest invites nobody; ask the member who invited you.";

	/** The message a member is refused the issuing of an invitation with under {@link InviteMode#ADMIN}. */
	public static final String INVITE_ADMIN_ONLY =
		"The administrator invites people on this server. Ask them for an invitation.";

	/** The message a caller is refused the list of invitations with. */
	public static final String INVITATIONS_REFUSED =
		"Only the members of this server may see the invitations they issued.";

	/** The message an invitation is refused a name that is already taken with. */
	public static String nameTaken(String name) {
		return "The name '" + name + "' is already taken on this server. Choose another one.";
	}

	/**
	 * The message a member cannot be created with while the library was never migrated.
	 *
	 * <p>
	 * A member owns a folder below the base folder, and while the library was never migrated the
	 * base folder <em>is</em> the owner's library: a new folder there would land among her albums.
	 * Migrating is explicit and deliberate (<code>--migrate-to-user</code>), so this is said rather
	 * than done, see issue #45.
	 * </p>
	 */
	public static final String SPACE_REFUSED =
		"This library still lies directly in the server's folder, so there is no room for a member's "
			+ "own albums beside it. The owner moves it into a folder of their own with "
			+ "'--migrate-to-user <name>' first; guests can be invited either way.";

	/** The message a caller is refused the promotion of somebody with. */
	public static final String PROMOTE_REFUSED =
		"Only the administrator of this server turns a guest into a member.";

	/** The message the promotion of somebody who is no guest is refused with. */
	public static String notAGuest(String name) {
		return "'" + name + "' is not a guest; only a guest becomes a member.";
	}

	/** The message a request naming a user this server does not know is refused with. */
	public static String unknownUser(String name) {
		return "There is no user '" + name + "' on this server.";
	}

	/** The message a promotion is refused with that would overwrite something. */
	public static String promotionBlocked(String name) {
		return "The folder '" + name + "' already exists and is not empty; nothing was moved.";
	}

	/**
	 * The message a write into a guest's root is refused with, see issue #52.
	 *
	 * <p>
	 * A guest's root is not a library: it holds the albums that were shared with them and nothing
	 * else. What they may add to somebody else's album is a grant's business, see issue #49.
	 * </p>
	 */
	public static final String GUEST_SPACE_REFUSED =
		"A guest has no albums of their own: this is where the albums shared with you appear, and "
			+ "nothing can be put here. Ask a member to make you one.";

	/** The message a grant on a guest's root is refused with. */
	public static final String GUEST_GRANT_REFUSED =
		"A guest's albums are the ones shared with them; there is nothing here to share with anybody.";

	/** The message a share link is refused a read outside what it opens with. */
	public static final String SHARE_READ_REFUSED = "This link does not open this album.";

	/** The message a share link is refused a change with. */
	public static final String SHARE_WRITE_REFUSED = "This link does not allow changes here.";

	/**
	 * The message a share link is refused a path outside its subtree with.
	 *
	 * <p>
	 * A <code>404</code>, never a refusal: from inside a link there is nothing else, and saying
	 * "you may not" about a folder would say that the folder is there.
	 * </p>
	 */
	public static final String SHARE_CONFINED =
		"This link opens one album; there is nothing else to see from here.";

	/** The message a share link carrying the {@link Rights#EDIT} right is refused creation with. */
	public static final String SHARE_EDIT_REFUSED =
		"A share link never allows editing; give 'view', 'download' or 'contribute'.";

	/** The message a share link with a privacy limit outside the scale is refused creation with. */
	public static final String SHARE_PRIVACY_REFUSED =
		"The privacy limit of a share link is 0 (public), 1 (members) or 2 (private).";

	/** The message a share link with a rating limit outside the scale is refused creation with. */
	public static final String SHARE_RATING_REFUSED = "The rating limit of a share link is -2 to 2.";

	/** The message a share link with an unreadable expiry is refused creation with. */
	public static final String SHARE_EXPIRY_REFUSED =
		"The expiry of a share link is an ISO-8601 instant such as '2026-12-24T00:00:00Z', "
			+ "or empty for a link that never expires.";

	/** The message a request naming a share link this server does not have is refused with. */
	public static final String SHARE_UNKNOWN = "There is no share link of that id on this album.";

	/** The message a caller is refused the list of users with. */
	public static final String USERS_REFUSED =
		"Only the members of this server may see who else uses it.";

	/** The message a caller is refused a group of somebody else with. */
	public static final String GROUP_REFUSED = "Only the owner of a group may change or remove it.";

	/** The message a guest is refused the creation of a group with. */
	public static final String GROUP_CREATE_REFUSED =
		"A guest has no groups of their own; ask a member to put you in one.";

	/** The message a request for a group that does not exist is refused with. */
	public static final String GROUP_UNKNOWN = "There is no group of that name.";

	/** The message a path leaving the space it is resolved against is refused with. */
	public static final String PATH_ESCAPED = "This path is outside the library it addresses.";

	/**
	 * The message a path naming a user this server does not know is refused with.
	 *
	 * <p>
	 * It is also what a folder named <code>~something</code> at the top of a space would be
	 * answered with, which is why such a folder is refused on creation, on upload and on a move:
	 * the canonical form shadows it and it could never be reached again.
	 * </p>
	 */
	public static String unknownSpace(String name) {
		return "There is no user '" + name + "' on this server. A path starting with '" + HOME_PREFIX
			+ "' addresses another user's library; a folder of your own cannot be named that way.";
	}

	/** The message an entry whose name starts with {@link #HOME_PREFIX} is refused with. */
	public static String homeNameRefused(String name) {
		return "'" + name + "' cannot lie at the top of a library: a name starting with '" + HOME_PREFIX
			+ "' addresses another user's library.";
	}

	/** The message a grant to somebody this server does not know is refused with. */
	public static String unknownSubject(String subject) {
		return "'" + subject + "' is nobody this server knows. " + Subjects.SUBJECT_REFUSED;
	}

	/** The message a grant of a right this server does not know is refused with. */
	public static String unknownRight(String right) {
		return "'" + right + "' is no right this server knows. Known rights: " + Rights.names() + ".";
	}

	/** The message the missing right is named with, see {@link Rights}. */
	public static String rightRefused(String right) {
		switch (right) {
			case Rights.DOWNLOAD:
				return DOWNLOAD_REFUSED;
			case Rights.CONTRIBUTE:
				return CONTRIBUTE_REFUSED;
			case Rights.EDIT:
				return EDIT_REFUSED;
			default:
				return VIEW_REFUSED;
		}
	}

	private static final String BEARER_PREFIX = "Bearer ";

	/** How a request identified itself, see {@link AuthService#caller(HttpServletRequest)}. */
	public static final class Caller {

		/** A caller that sent no token at all. */
		public static final Caller ANONYMOUS = new Caller(null, null, false, null, null, null);

		/** A caller that sent a token this server does not know. */
		public static final Caller INVALID = new Caller(null, null, true, TOKEN_REFUSED, null, null);

		private final User _user;

		private final String _deviceName;

		private final boolean _tokenPresented;

		private final String _refusal;

		private final ShareStore.Link _share;

		private final String _gone;

		private InvitationStore.Link _invitation;

		private String _invitationGone;

		private Caller(User user, String deviceName, boolean tokenPresented, String refusal, ShareStore.Link share,
				String gone) {
			_user = user;
			_deviceName = deviceName;
			_tokenPresented = tokenPresented;
			_refusal = refusal;
			_share = share;
			_gone = gone;
		}

		/** A caller whose token is known but who is refused for the given reason. */
		static Caller rejected(String refusal) {
			return new Caller(null, null, true, refusal, null, null);
		}

		/** A signed-in caller. */
		static Caller signedIn(User user, String deviceName) {
			return new Caller(user, deviceName, true, null, null, null);
		}

		/**
		 * A caller presenting an invitation token, see issue #52.
		 *
		 * <p>
		 * Anonymous everywhere but at <code>?type=auth</code>: an invitation is no login, it opens
		 * nothing and it may do nothing. It is carried on the {@link Caller} only so that the app
		 * can be told who invited it and as what, before it asks the user for a name. In a migrated
		 * library such a caller is therefore answered {@link AuthService#LIBRARY_REFUSED} like
		 * anybody else who has not signed in — which is exactly right: they have not.
		 * </p>
		 *
		 * @param gone
		 *        Why the invitation cannot be accepted any more, <code>null</code> while it can.
		 */
		static Caller invitation(InvitationStore.Link invitation, String gone) {
			Caller result = new Caller(null, null, false, null, null, null);
			result._invitation = invitation;
			result._invitationGone = gone;
			return result;
		}

		/** The invitation this caller presented, <code>null</code> for everybody else. */
		public InvitationStore.Link getInvitation() {
			return _invitation;
		}

		/**
		 * Why this caller's invitation cannot be accepted any more, <code>null</code> while it can.
		 *
		 * <p>
		 * Only <code>?type=auth</code> ever asks: on every other endpoint an invitation token is a
		 * token the caller has no business sending, and the caller is simply anonymous there.
		 * </p>
		 */
		public String getInvitationGone() {
			return _invitationGone;
		}

		/**
		 * A caller holding a live share link, see issue #51.
		 *
		 * <p>
		 * Nobody's user: it has no space, it cannot pair, and everything it may do comes from the
		 * grant made out to {@link ShareStore.Link#getSubject() its subject}. Its paths are resolved
		 * relative to the link's target, which is the root of everything it can reach, see
		 * {@link AuthService#resolve(Caller, Path, String)}.
		 * </p>
		 */
		static Caller shareLink(ShareStore.Link share) {
			return new Caller(null, null, true, null, share, null);
		}

		/**
		 * A caller holding a share link that expired or was withdrawn.
		 *
		 * <p>
		 * Answered with <code>410 Gone</code> on every endpoint, <code>?type=auth</code> included,
		 * so that the app shows a plain page saying what happened instead of an error dump.
		 * </p>
		 */
		static Caller shareGone(ShareStore.Link share, String message) {
			return new Caller(null, null, true, message, share, message);
		}

		/** The share link this caller holds, <code>null</code> for everybody else. */
		public ShareStore.Link getShare() {
			return _share;
		}

		/** Whether this caller opened a share link, live or not. */
		public boolean isShareLink() {
			return _share != null;
		}

		/** Why this caller's share link opens nothing any more, <code>null</code> while it does. */
		public String getGone() {
			return _gone;
		}

		/** Whether this caller's share link expired or was withdrawn. */
		public boolean isShareGone() {
			return _gone != null;
		}

		/**
		 * How a {@link GrantStore.Grant} would name this caller, see {@link Subjects}.
		 *
		 * <p>
		 * The seam of issue #53: whoever contributes something is named by exactly one string, and
		 * a share link's contribution is named by its link, not by a person.
		 * </p>
		 */
		public String subject() {
			if (_share != null) {
				return _share.getSubject();
			}
			if (_user != null && !_user.getName().isEmpty()) {
				return Subjects.user(_user.getName());
			}
			return Subjects.ANONYMOUS;
		}

		/**
		 * What to show as the contributor of what this caller uploads, see issue #53.
		 *
		 * <p>
		 * The name of the user, or the label of the share link a contribution came through; the
		 * empty string for an anonymous caller and for a link without a label. It is copied into
		 * the hash sidecar at the upload rather than looked up later, so a link that is renamed or
		 * withdrawn still says who contributed.
		 * </p>
		 */
		public String contributorLabel() {
			if (_share != null) {
				return getShareLabel();
			}
			return getUserName();
		}

		/** The label of the share link this caller holds, the empty string for everybody else. */
		public String getShareLabel() {
			return _share == null ? "" : _share.getLabel();
		}

		/** The name of the paired device, or the empty string for an unidentified caller. */
		public String getDeviceName() {
			return _deviceName == null ? "" : _deviceName;
		}

		/** Whether this caller is a device paired with this server. */
		public boolean isPaired() {
			return _user != null;
		}

		/**
		 * Whether this caller presented a token that this server does not know or accept.
		 *
		 * <p>
		 * A share link is not such a caller: it presented a token this server issued, and what it
		 * may do is decided by its grant like everybody else's, see issue #51.
		 * </p>
		 */
		public boolean hasInvalidToken() {
			return _tokenPresented && _user == null && _share == null;
		}

		/** The name of the signed-in user, the empty string for an anonymous caller. */
		public String getUserName() {
			return _user == null ? "" : _user.getName();
		}

		/** The role of the signed-in user, the empty string for an anonymous caller. */
		public String getRole() {
			return _user == null ? "" : _user.getRole();
		}

		/**
		 * The folder below the server's base folder every path of this caller is resolved against.
		 *
		 * <p>
		 * The empty string is the base folder itself: that is an anonymous caller of an unmigrated
		 * library, a server running without authentication, and the owner of a library that was
		 * never migrated.
		 * </p>
		 */
		public String getSpace() {
			return _user == null ? "" : _user.getSpace();
		}

		/** Why this caller is refused, <code>null</code> if it is not refused for its own sake. */
		String getRefusal() {
			return _refusal;
		}
	}

	private final AuthMode _mode;

	private final String _pairingSecret;

	private final Path _basePath;

	private final UserStore _users;

	private final GrantStore _grants;

	private final GroupStore _groups;

	private final ShareStore _shares;

	private final InvitationStore _invitations;

	private final InviteMode _inviteMode;

	/**
	 * Creates an {@link AuthService} whose members may invite, see {@link InviteMode#MEMBERS}.
	 */
	public AuthService(AuthMode mode, String pairingSecret, Path basePath) {
		this(mode, pairingSecret, basePath, InviteMode.MEMBERS);
	}

	/**
	 * Creates an {@link AuthService}.
	 *
	 * @param mode
	 *        What requires a paired device.
	 * @param pairingSecret
	 *        The secret a device must present to be paired, <code>null</code> while pairing is
	 *        impossible.
	 * @param basePath
	 *        The root of the served album tree; the user store lives below it and the user spaces
	 *        are folders in it.
	 * @param inviteMode
	 *        Who may hand out an invitation, see issue #52.
	 */
	public AuthService(AuthMode mode, String pairingSecret, Path basePath, InviteMode inviteMode) {
		_mode = mode;
		_pairingSecret = pairingSecret;
		_basePath = basePath;
		_inviteMode = inviteMode;
		_users = mode == AuthMode.OFF ? null : new UserStore(basePath);
		_grants = mode == AuthMode.OFF ? null : new GrantStore(basePath);
		_groups = mode == AuthMode.OFF ? null : new GroupStore(basePath);
		_shares = mode == AuthMode.OFF ? null : new ShareStore(basePath);
		_invitations = mode == AuthMode.OFF ? null : new InvitationStore(basePath);
	}

	/** An {@link AuthService} serving every request, as before issue #28. */
	public static AuthService disabled() {
		return new AuthService(AuthMode.OFF, null, null);
	}

	/** What requires a paired device. */
	public AuthMode getMode() {
		return _mode;
	}

	/** The users of this server, <code>null</code> while {@link AuthMode#OFF}. */
	public UserStore getUsers() {
		return _users;
	}

	/** The sharing grants of this server, <code>null</code> while {@link AuthMode#OFF}. */
	public GrantStore getGrants() {
		return _grants;
	}

	/** The user groups of this server, <code>null</code> while {@link AuthMode#OFF}. */
	public GroupStore getGroups() {
		return _groups;
	}

	/** The share links of this server, <code>null</code> while {@link AuthMode#OFF}. */
	public ShareStore getShares() {
		return _shares;
	}

	/** The invitations of this server, <code>null</code> while {@link AuthMode#OFF}. */
	public InvitationStore getInvitations() {
		return _invitations;
	}

	/** Who may hand out an invitation on this server, see issue #52. */
	public InviteMode getInviteMode() {
		return _inviteMode;
	}

	/**
	 * Whether the given caller may hand out an invitation, see issue #52.
	 *
	 * <p>
	 * A member may, unless the server was started with {@link InviteMode#ADMIN}; the admin always
	 * may; a guest never does, and an anonymous caller is refused before this is asked.
	 * </p>
	 */
	public boolean mayInvite(Caller caller) {
		if (!caller.isPaired() || Roles.GUEST.equals(caller.getRole())) {
			return false;
		}
		return _inviteMode != InviteMode.ADMIN || Roles.ADMIN.equals(caller.getRole());
	}

	/** The root of the given guest, see {@link UserStore#GUESTS_DIRECTORY_NAME}. */
	public static Path guestRoot(Path basePath, String name) {
		return basePath.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.GUESTS_DIRECTORY_NAME).resolve(name);
	}

	/**
	 * The folder the given user's paths are resolved against, without creating anything.
	 *
	 * <p>
	 * A member's space folder, the base folder for the owner of a library that was never migrated,
	 * and the little root of issue #52 for a guest. The creating counterpart is
	 * {@link #spaceRoot(User, Path)}; reading must never create a folder, see
	 * {@link de.haumacher.imageServer.links.LinkService}.
	 * </p>
	 */
	public static Path spaceFolder(Path basePath, User user) {
		if (Roles.GUEST.equals(user.getRole())) {
			return guestRoot(basePath, user.getName());
		}
		return user.getSpace().isEmpty() ? basePath : basePath.resolve(user.getSpace());
	}

	/** Generates a pairing secret for a server that was not given one. */
	public static String generateSecret() {
		byte[] bytes = new byte[12];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Identifies the sender of the given request. */
	public Caller caller(HttpServletRequest request) {
		if (_mode == AuthMode.OFF) {
			return Caller.ANONYMOUS;
		}
		String token = token(request);
		if (token == null) {
			return Caller.ANONYMOUS;
		}
		Login login = _users.lookup(token);
		if (login == null) {
			// Not a device token; it may still be a share link of issue #51.
			ShareStore.Link share = _shares.lookup(token);
			if (share == null) {
				// Nor a share link; it may still be an invitation of issue #52. The share store is
				// asked first because a share token is a session that reaches every endpoint, while
				// an invitation is anonymous everywhere but at "?type=auth" — and a token can never
				// be in both stores, both being 32 random bytes.
				InvitationStore.Link invitation = _invitations.lookup(token);
				if (invitation == null) {
					return Caller.INVALID;
				}
				return Caller.invitation(invitation, invitationGone(invitation));
			}
			if (share.isRevoked()) {
				return Caller.shareGone(share, LINK_REVOKED);
			}
			if (share.isExpired(java.time.Instant.now())) {
				return Caller.shareGone(share, LINK_EXPIRED);
			}
			return Caller.shareLink(share);
		}
		if (!Roles.isKnown(login.getUser().getRole())) {
			LOG.warning("Refusing the user '" + login.getUser().getName() + "' with the unknown role '"
				+ login.getUser().getRole() + "'.");
			return Caller.rejected(ROLE_REFUSED);
		}
		return Caller.signedIn(login.getUser(), login.getDevice().getName());
	}

	/** The bearer token of the given request, <code>null</code> if it carries none. */
	private static String token(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null) {
			return null;
		}
		String trimmed = header.trim();
		if (trimmed.length() <= BEARER_PREFIX.length()
			|| !trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return null;
		}
		String token = trimmed.substring(BEARER_PREFIX.length()).trim();
		return token.isEmpty() ? null : token;
	}

	/**
	 * Whether the library was migrated into user spaces, see
	 * {@link LibraryMigration#migrate(Path, String)}.
	 *
	 * <p>
	 * While it was not, the base folder is the owner's library and an anonymous caller may look at
	 * it exactly as before issue #45.
	 * </p>
	 */
	public boolean isLibraryMigrated() {
		if (_users == null) {
			return false;
		}
		User owner = _users.getOwner();
		return owner != null && !owner.getSpace().isEmpty();
	}

	/** Whether the given caller may read. */
	public boolean readAllowed(Caller caller) {
		if (caller.hasInvalidToken()) {
			return false;
		}
		if (caller.isShareLink()) {
			// A live link reads, in a migrated library too: LIBRARY_REFUSED tells people to open a
			// share link they were given, and this is them doing it, see issue #51. What the link
			// may do where it points at is still the grant's business, see #rights(Caller, PathInfo).
			return !caller.isShareGone();
		}
		if (!caller.isPaired() && isLibraryMigrated()) {
			return false;
		}
		return _mode != AuthMode.ALL || caller.isPaired();
	}

	/** Whether the given caller may write. */
	public boolean writeAllowed(Caller caller) {
		if (caller.hasInvalidToken()) {
			return false;
		}
		if (_mode == AuthMode.OFF) {
			return true;
		}
		if (caller.isShareLink()) {
			// A link is no paired device: what it may change is exactly what its grant says, asked
			// for at the path the request names, see #mayContribute(Caller, PathInfo).
			return false;
		}
		if (!caller.isPaired() && isLibraryMigrated()) {
			return false;
		}
		return caller.isPaired();
	}

	/**
	 * What the given caller may do with the resource at the given path, see issue #49.
	 *
	 * <p>
	 * The one method that decides. Every endpoint asks it (through {@link #mayView},
	 * {@link #mayDownload}, {@link #mayContribute} and {@link #mayEdit}) and none of them decides
	 * for itself: a path is not a permission, and the rights of a request are computed from who
	 * the caller is and where the path lies, on every request.
	 * </p>
	 *
	 * <p>
	 * The rules, in the order they apply:
	 * </p>
	 * <ul>
	 * <li>Mode {@link AuthMode#OFF} knows no users and no grants: everybody holds everything, as
	 * before issue #28.</li>
	 * <li>The owner of the space the path lies in holds every right in it.</li>
	 * <li>Everybody else holds the union of the rights of every {@link Grant} whose target is the
	 * path or a folder above it <em>in the same space</em> and whose subject names them: their
	 * user, a {@link Group} they are in, or {@link Subjects#ANONYMOUS}, which names everybody,
	 * signed in or not. The implications are then applied, see
	 * {@link Rights#closure(java.util.Collection)}.</li>
	 * <li>An anonymous caller of a library that was never migrated holds {@link Rights#READ_ONLY}
	 * on the base folder while the mode is {@link AuthMode#WRITES}: the single-user library on the
	 * home network looks exactly as it did before issue #45.</li>
	 * </ul>
	 *
	 * <p>
	 * "The space the path lies in" is the <em>deepest</em> space folder containing it, see
	 * {@link #spaceOf(PathInfo)}, and it is not the same question as "whose library did this
	 * request reach". In a library that was never migrated the owner's space <em>is</em> the base
	 * folder and the spaces of the members lie inside it; the owner keeps reaching them as she
	 * always did (they are in her own tree, and issue #45 changed nothing about that until the
	 * library is migrated), but a grant of hers stops at the boundary of a member's space: one
	 * user can never share what is not theirs. Migrating the library (<code>--migrate-to-user</code>)
	 * is what closes the first half of that, and it is a deliberate act, not a silent one.
	 * </p>
	 *
	 * <p>
	 * The administrator is <em>not</em> a special case here. They manage users, groups and grants,
	 * and they browse what is theirs and what they were granted, nothing else: a role that could
	 * read every album of every member would make the privacy levels of issue #46 a decoration.
	 * </p>
	 *
	 * <p>
	 * A <code>token:</code> subject (issue #51) never names anybody in this build: such a grant is
	 * read and written faithfully and grants nothing.
	 * </p>
	 *
	 * @param caller
	 *        Who sent the request, see {@link #caller(HttpServletRequest)}.
	 * @param path
	 *        The resource being reached, already resolved against a space, see
	 *        {@link #resolve(Caller, Path, String)}.
	 * @return The rights held, with the implications applied; never <code>null</code>.
	 */
	public Set<String> rights(Caller caller, PathInfo path) {
		if (_mode == AuthMode.OFF) {
			return Rights.ALL;
		}
		if (caller.hasInvalidToken() || caller.isShareGone()) {
			return Rights.NONE;
		}

		Set<String> held = new LinkedHashSet<>(spaceRights(caller, path));
		Space space = spaceOf(path);
		if (space != null && !held.containsAll(Rights.ALL)) {
			Set<String> subjects = subjects(caller);
			for (Grant grant : _grants.covering(space.getOwner(), space.getPath())) {
				if (subjects.contains(grant.getSubject())) {
					held.addAll(grant.getRights());
				}
			}
		}
		return Rights.closure(held);
	}

	/**
	 * The rights the given caller holds at the given path without any grant.
	 *
	 * <p>
	 * That is the own space under the rules of the {@link AuthMode}, and it is what the server did
	 * before issue #49: the owner of a space holds everything in it, and the anonymous caller of a
	 * library that was never migrated may look at the base folder while the mode is
	 * {@link AuthMode#WRITES}.
	 * </p>
	 */
	public Set<String> spaceRights(Caller caller, PathInfo path) {
		if (_mode == AuthMode.OFF) {
			return Rights.ALL;
		}
		if (caller.hasInvalidToken() || caller.isShareLink()) {
			// A share link owns no space and is nobody's anonymous visitor: it holds what its grant
			// gives it and not one right more, see issue #51.
			return Rights.NONE;
		}
		if (caller.isPaired()) {
			return isOwnSpace(caller, path) ? Rights.ALL : Rights.NONE;
		}
		if (!isLibraryMigrated() && _mode == AuthMode.WRITES && isBaseSpace(path)) {
			return Rights.READ_ONLY;
		}
		return Rights.NONE;
	}

	/**
	 * The message a share link's move is refused with, see issue #53.
	 *
	 * <p>
	 * A link is a window on somebody else's album and has no library of its own; there is nowhere
	 * for it to move a photo to. Taking a contribution back is therefore the one thing a link
	 * cannot do, and it is said rather than shown as a puzzling "not found".
	 * </p>
	 */
	public static final String SHARE_MOVE_REFUSED =
		"A share link can add photos, but not move them: it has no albums of its own.";

	/** Whether the given caller may look at the listing, the thumbnails and the previews here. */
	public boolean mayView(Caller caller, PathInfo path) {
		return rights(caller, path).contains(Rights.VIEW);
	}

	/** Whether the given caller may fetch the original files here. */
	public boolean mayDownload(Caller caller, PathInfo path) {
		return rights(caller, path).contains(Rights.DOWNLOAD);
	}

	/**
	 * Whether the given caller may change what is in the folder at the given path, see issue #47.
	 *
	 * <p>
	 * Editing is storing the folder's sidecar, moving entries out of it and applying its placement
	 * rule. The owner of the space holds it everywhere in it; anybody else holds it where a grant
	 * says so, see {@link #rights(Caller, PathInfo)}.
	 * </p>
	 *
	 * @param caller
	 *        Who sent the request, see {@link #caller(HttpServletRequest)}.
	 * @param path
	 *        The folder being changed, already resolved against a space.
	 */
	public boolean mayEdit(Caller caller, PathInfo path) {
		return rights(caller, path).contains(Rights.EDIT);
	}

	/**
	 * Whether the given caller may add entries to the folder at the given path, see issue #47.
	 *
	 * <p>
	 * Contributing is the weaker of the two rights: a member may add photos to a shared event
	 * album without being allowed to rearrange it. It is implied by {@link #mayEdit}.
	 * </p>
	 *
	 * @param caller
	 *        Who sent the request, see {@link #caller(HttpServletRequest)}.
	 * @param path
	 *        The folder being added to, already resolved against a space.
	 */
	public boolean mayContribute(Caller caller, PathInfo path) {
		return rights(caller, path).contains(Rights.CONTRIBUTE);
	}

	/**
	 * Whether the given caller manages the grants on the space the given path lies in.
	 *
	 * <p>
	 * The owner of the space, and the administrator, who keeps the server in order without being
	 * able to look into every album, see {@link #rights(Caller, PathInfo)}.
	 * </p>
	 */
	public boolean mayManageGrants(Caller caller, PathInfo path) {
		if (!caller.isPaired()) {
			return false;
		}
		if (Roles.ADMIN.equals(caller.getRole())) {
			return true;
		}
		Space space = spaceOf(path);
		return space != null && space.getOwner().equals(caller.getUserName());
	}

	/** Whether the given caller may see who else uses this server: members and the admin. */
	public boolean maySeeUsers(Caller caller) {
		if (_mode == AuthMode.OFF) {
			return true;
		}
		return caller.isPaired() && !Roles.GUEST.equals(caller.getRole());
	}

	/** Whether the given caller may have groups of their own: members and the admin. */
	public boolean mayOwnGroups(Caller caller) {
		return caller.isPaired() && !Roles.GUEST.equals(caller.getRole());
	}

	/**
	 * The subjects a grant may name the given caller by, see {@link Subjects}.
	 *
	 * <p>
	 * What {@link #rights(Caller, PathInfo)} asks the {@link GrantStore} with, and what the link
	 * entries of issue #50 are materialised from: the grants naming one of these are the albums
	 * shared with this caller.
	 * </p>
	 */
	public Set<String> subjects(Caller caller) {
		Set<String> result = new LinkedHashSet<>();
		// "anonymous" names everybody: a grant to it is what opens an album to the world.
		result.add(Subjects.ANONYMOUS);
		if (caller.isShareLink()) {
			// The one line issue #49 left open: a share link is named by its own subject, see #51.
			result.add(caller.getShare().getSubject());
			return result;
		}
		String name = caller.getUserName();
		if (caller.isPaired() && !name.isEmpty()) {
			result.add(Subjects.user(name));
			for (String group : _groups.groupsOf(name)) {
				result.add(Subjects.group(group));
			}
		}
		return result;
	}

	/**
	 * Which user's space the given path lies in, and where in it.
	 *
	 * <p>
	 * The <em>deepest</em> space folder containing the path, never the folder the path happened to
	 * be resolved against: in a library that was never migrated the base folder is the owner's
	 * space and the spaces of the other users lie <em>inside</em> it, so
	 * <code>&lt;base&gt;/bob/Secret</code> is bob's, whichever way it was reached. That is what
	 * stops a {@link Grant} of the owner's at the boundary of somebody else's space — one user can
	 * never share what is not theirs, see {@link #rights(Caller, PathInfo)}.
	 * </p>
	 *
	 * @return <code>null</code> if the path lies in no space at all: that is the base folder of a
	 *         migrated library, which holds nothing but the spaces of the users, and anything
	 *         outside the served tree.
	 */
	public Space spaceOf(PathInfo path) {
		if (_users == null || _basePath == null || path.getBasePath() == null) {
			return null;
		}
		Path base = normalize(_basePath);
		Path resolved = normalize(path.toFile().toPath());
		if (!resolved.startsWith(base)) {
			return null;
		}
		String relative = base.relativize(resolved).toString().replace(java.io.File.separatorChar, '/');

		Space guest = guestSpaceOf(relative);
		if (guest != null) {
			return guest;
		}

		User deepest = null;
		for (User user : _users.getUsers()) {
			String space = user.getSpace();
			if (space.isEmpty() || !GrantStore.isBelow(relative, space)) {
				continue;
			}
			if (deepest == null || space.length() > deepest.getSpace().length()) {
				deepest = user;
			}
		}
		if (deepest != null) {
			String space = deepest.getSpace();
			return new Space(deepest.getName(),
				relative.equals(space) ? "" : relative.substring(space.length() + 1));
		}

		User owner = _users.getOwner();
		if (owner != null && owner.getSpace().isEmpty()) {
			// The library was never migrated: the base folder is the owner's own library.
			return new Space(owner.getName(), relative);
		}
		return null;
	}

	/**
	 * The space of a guest the given base-relative path lies in, see issue #52.
	 *
	 * <p>
	 * A guest's root lies below {@link UserStore#DIRECTORY_NAME}, so it is never a folder of
	 * anybody's album tree and never shadowed by one: it is asked for before the member spaces,
	 * and no member space can ever contain it, a user name being unable to start with a dot.
	 * </p>
	 *
	 * @return <code>null</code> if the path lies in no guest's root.
	 */
	private Space guestSpaceOf(String relative) {
		String head = UserStore.DIRECTORY_NAME + "/" + UserStore.GUESTS_DIRECTORY_NAME + "/";
		if (!relative.startsWith(head)) {
			return null;
		}
		String rest = relative.substring(head.length());
		int slash = rest.indexOf('/');
		String name = slash < 0 ? rest : rest.substring(0, slash);
		User user = name.isEmpty() ? null : _users.getUser(name);
		if (user == null || !Roles.GUEST.equals(user.getRole())) {
			return null;
		}
		return new Space(name, slash < 0 ? "" : rest.substring(slash + 1));
	}

	/** Whether the given path lies in the root of a guest, see issue #52. */
	public boolean isGuestSpace(PathInfo path) {
		Space space = spaceOf(path);
		if (space == null || _users == null) {
			return false;
		}
		User user = _users.getUser(space.getOwner());
		return user != null && Roles.GUEST.equals(user.getRole());
	}

	/**
	 * The name of the user whose space the given path lies in.
	 *
	 * @return The empty string if the path lies in no space at all, see {@link #spaceOf(PathInfo)}.
	 */
	public String ownerOf(PathInfo path) {
		Space space = spaceOf(path);
		return space == null ? null : space.getOwner();
	}

	/** Which space a path lies in and where in it, see {@link AuthService#spaceOf(PathInfo)}. */
	public static final class Space {

		private final String _owner;

		private final String _path;

		Space(String owner, String path) {
			_owner = owner;
			_path = path;
		}

		/** The name of the user who owns the space. */
		public String getOwner() {
			return _owner;
		}

		/**
		 * The path relative to the owner's space folder, <code>/</code> as separator.
		 *
		 * <p>
		 * The coordinates a {@link GrantStore.Grant#getPath() grant} is given in; the empty string
		 * is the space itself.
		 * </p>
		 */
		public String getPath() {
			return _path;
		}
	}

	/**
	 * Whether the given path is resolved against the folder the given caller's own paths resolve
	 * against.
	 *
	 * <p>
	 * That is the caller's own library, and it is what the server granted them before issue #49:
	 * the owner of a library that was never migrated keeps seeing everything below the base folder
	 * exactly as they did, the spaces of the members inside it included, see
	 * {@link #spaceRights(Caller, PathInfo)}. What she may <em>share</em> of it is a different
	 * question, decided by {@link #spaceOf(PathInfo)}.
	 * </p>
	 */
	private boolean isOwnSpace(Caller caller, PathInfo path) {
		if (_basePath == null || path.getBasePath() == null) {
			return false;
		}
		Path root;
		if (Roles.GUEST.equals(caller.getRole())) {
			// A guest has no library, but they do own their own little root: the links that were
			// made for them are theirs to look at, to rearrange and to decline, see issue #52.
			// What must not land in it is a photo, and that is refused by role, never by rights.
			root = guestRoot(_basePath, caller.getUserName());
		} else {
			String space = caller.getSpace();
			if (space.isEmpty() && !isUnmigratedOwner(caller)) {
				// The base folder of a migrated library belongs to nobody.
				return false;
			}
			root = space.isEmpty() ? _basePath : _basePath.resolve(space);
		}
		return normalize(path.getBasePath()).equals(normalize(root));
	}

	/** Whether the given caller is the owner of a library that was never migrated. */
	private boolean isUnmigratedOwner(Caller caller) {
		User owner = _users == null ? null : _users.getOwner();
		return owner != null && owner.getSpace().isEmpty() && owner.getName().equals(caller.getUserName());
	}

	/** Whether the given path is resolved against the server's base folder itself. */
	private boolean isBaseSpace(PathInfo path) {
		Path root = path.getBasePath();
		return root != null && _basePath != null && normalize(root).equals(normalize(_basePath));
	}

	private static Path normalize(Path path) {
		return path.toAbsolutePath().normalize();
	}

	/** Where a request path was resolved to, see {@link AuthService#resolve(Caller, Path, String)}. */
	public static final class Location {

		private final String _prefix;

		private final Space _space;

		private final PathInfo _path;

		private final String _linkOwner;

		private final String _linkTarget;

		private final String _linkPath;

		Location(String prefix, Space space, PathInfo path) {
			this(prefix, space, path, "", "", "");
		}

		Location(String prefix, Space space, PathInfo path, String linkOwner, String linkTarget, String linkPath) {
			_prefix = prefix;
			_space = space;
			_path = path;
			_linkOwner = linkOwner;
			_linkTarget = linkTarget;
			_linkPath = linkPath;
		}

		/**
		 * Whether the request reached this path through a link entry, see issue #50.
		 *
		 * <p>
		 * What lies here belongs to somebody else, and the rights and the clearance of this request
		 * are the ones on the target, exactly as they are for a canonical <code>~owner/…</code>
		 * request: access is checked against the grant on the target, never against the link.
		 * </p>
		 */
		public boolean isLinked() {
			return !_linkPath.isEmpty();
		}

		/** The name of the user the link led into, empty if no link was followed. */
		public String getLinkOwner() {
			return _linkOwner;
		}

		/** The link's target in the owner's coordinates, see {@link LinkStore.Link#getPath()}. */
		public String getLinkTarget() {
			return _linkTarget;
		}

		/** Where the link lies in the request's own coordinates: the viewer's path to it. */
		public String getLinkPath() {
			return _linkPath;
		}

		/**
		 * How the request spelled the library it reached: <code>~alice/</code> for the canonical
		 * form, the empty string for the caller's own space.
		 */
		public String getPrefix() {
			return _prefix;
		}

		/** The name of the user whose space the path lies in, empty if it lies in none. */
		public String getOwner() {
			return _space == null ? "" : _space.getOwner();
		}

		/**
		 * The path relative to the owner's space folder: the coordinates a
		 * {@link GrantStore.Grant} is given in, see {@link Space#getPath()}.
		 */
		public String getOwnerPath() {
			return _space == null ? "" : _space.getPath();
		}

		/** The resolved path. */
		public PathInfo getPath() {
			return _path;
		}

		/**
		 * The given path of this location's library, spelled the way the request that reached it
		 * spells paths.
		 *
		 * <p>
		 * The one place a resolved path is written back into an answer: every path the server
		 * answers is in the coordinates of the request that asked for it, or a client would
		 * navigate to it in its own library, see
		 * {@link de.haumacher.imageServer.shared.model.CreateResult#getPath()}.
		 * </p>
		 *
		 * @param path
		 *        A path relative to the folder this location was resolved against, see
		 *        {@link PathInfo#relativePath()}.
		 */
		public String spell(String path) {
			if (!isLinked()) {
				return _prefix + path;
			}
			// The request came through a link, so the resolved path is spelled in the target
			// owner's coordinates while the request speaks the viewer's: the linked subtree is
			// mapped back onto the viewer's path to the link, see issue #50.
			if (_linkTarget.isEmpty()) {
				return _prefix + _linkPath + (path.isEmpty() ? "" : "/" + path);
			}
			if (path.equals(_linkTarget)) {
				return _prefix + _linkPath;
			}
			if (path.startsWith(_linkTarget + "/")) {
				return _prefix + _linkPath + path.substring(_linkTarget.length());
			}
			// Outside the linked subtree there is no viewer's path at all; the canonical form of
			// the owner's library is the one spelling that is always right.
			return HOME_PREFIX + getOwner() + (path.isEmpty() ? "" : "/" + path);
		}

		/** The path of this location itself, spelled as the request spells it. */
		public String spell() {
			return spell(_path.relativePath());
		}
	}

	/** Thrown when a request path cannot be resolved, see {@link AuthService#resolve(Caller, Path, String)}. */
	public static class PathRefused extends Exception {

		private final int _status;

		/** Creates a {@link PathRefused}. */
		public PathRefused(int status, String message) {
			super(message);
			_status = status;
		}

		/** The HTTP status to answer with. */
		public int getStatus() {
			return _status;
		}
	}

	/**
	 * Resolves a request path against a space, the one place every method does it.
	 *
	 * <p>
	 * A path whose first segment starts with {@link #HOME_PREFIX} is the canonical form: it names
	 * the space of that user and the rest is the path in it. Everything else is resolved against
	 * the caller's own space, as it was before issue #49. Which space the path ends up in is what
	 * decides whose grants are asked, see {@link #rights(Caller, PathInfo)}; reaching a space is
	 * never by itself a permission to look into it.
	 * </p>
	 *
	 * @param caller
	 *        Who sent the request.
	 * @param basePath
	 *        The root of the served album tree.
	 * @param relativePath
	 *        The request path without its leading <code>/</code>; empty for the space root.
	 * @throws PathRefused
	 *         If the path leaves the space it is resolved against, or names a user this server
	 *         does not know.
	 */
	public Location resolve(Caller caller, Path basePath, String relativePath) throws PathRefused {
		String rest = relativePath == null ? "" : relativePath;
		if (caller.isShareLink()) {
			return resolveShared(caller, basePath, rest);
		}
		String prefix;
		Path root;
		if (rest.startsWith(HOME_PREFIX)) {
			int slash = rest.indexOf('/');
			String name = slash < 0 ? rest.substring(HOME_PREFIX.length())
				: rest.substring(HOME_PREFIX.length(), slash);
			rest = slash < 0 ? "" : rest.substring(slash + 1);
			User user = _users == null ? null : _users.getUser(name);
			if (user == null || name.isEmpty()) {
				throw new PathRefused(HttpServletResponse.SC_NOT_FOUND, unknownSpace(name));
			}
			prefix = HOME_PREFIX + name + "/";
			root = spaceRoot(user, basePath);
		} else {
			root = spaceRoot(caller, basePath);
			prefix = "";
		}

		if (rest.isEmpty()) {
			PathInfo path = new PathInfo(root);
			return new Location(prefix, spaceOf(path), path);
		}
		Path relative = java.nio.file.Paths.get(rest).normalize();
		if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()) {
			throw new PathRefused(HttpServletResponse.SC_NOT_FOUND, PATH_ESCAPED);
		}
		return walk(prefix, root, relative, basePath);
	}

	/**
	 * Resolves a request path of a share-link caller, confined to what the link opens (issue #51).
	 *
	 * <p>
	 * The link's target is the caller's root: <code>/</code> is the shared folder, so the app's tree
	 * has the shared album or folder at its top and every deep link below it keeps working. Nothing
	 * else exists from here — the canonical form <code>~&lt;user&gt;/…</code>, a <code>..</code>, and
	 * a link entry of issue #50 that leads out of the shared subtree are all answered as a path that
	 * is not there, because from inside a link there really is nothing there.
	 * </p>
	 *
	 * <p>
	 * A #50 link <em>inside</em> the shared subtree is followed exactly as it is for anybody else,
	 * as long as what it reaches stays inside the subtree: the confinement is a question about where
	 * a path ends up, never about how it was spelled.
	 * </p>
	 */
	private Location resolveShared(Caller caller, Path basePath, String relativePath) throws PathRefused {
		ShareStore.Link share = caller.getShare();
		User owner = _users == null ? null : _users.getUser(share.getOwner());
		if (owner == null) {
			// The user whose album was shared is gone; the link leads nowhere, and it says so.
			throw new PathRefused(HttpServletResponse.SC_NOT_FOUND, unknownSpace(share.getOwner()));
		}
		Path space = spaceRoot(owner, basePath);
		Path root = share.getPath().isEmpty() ? space : space.resolve(share.getPath());

		if (relativePath.startsWith(HOME_PREFIX)) {
			// The canonical form is the one way out of a space, and a link has none to go to.
			throw new PathRefused(HttpServletResponse.SC_NOT_FOUND, SHARE_CONFINED);
		}

		Location location;
		if (relativePath.isEmpty()) {
			PathInfo path = new PathInfo(root);
			location = new Location("", spaceOf(path), path);
		} else {
			Path relative = java.nio.file.Paths.get(relativePath).normalize();
			if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()) {
				throw new PathRefused(HttpServletResponse.SC_NOT_FOUND, SHARE_CONFINED);
			}
			location = walk("", root, relative, basePath);
		}

		if (!isBelow(location.getPath(), root)) {
			LOG.warning("Refusing the path '" + relativePath + "' of the share link " + share
				+ ": it leaves what the link opens.");
			throw new PathRefused(HttpServletResponse.SC_NOT_FOUND, SHARE_CONFINED);
		}
		return location;
	}

	/**
	 * Whether the given caller can reach the given path at all, see issue #51.
	 *
	 * <p>
	 * Everybody but a share-link caller can reach everything the server serves — whether they may
	 * <em>look</em> at it is {@link #rights(Caller, PathInfo)}, and a different question. A link
	 * caller reaches what lies inside the link's target and nothing else, so a tile pointing out of
	 * it is not shown: it could not be opened, see {@link #resolveShared(Caller, Path, String)}.
	 * </p>
	 */
	public boolean reachable(Caller caller, Path basePath, PathInfo path) {
		if (!caller.isShareLink()) {
			return true;
		}
		ShareStore.Link share = caller.getShare();
		User owner = _users == null ? null : _users.getUser(share.getOwner());
		if (owner == null) {
			return false;
		}
		Path space = spaceRoot(owner, basePath);
		return isBelow(path, share.getPath().isEmpty() ? space : space.resolve(share.getPath()));
	}

	/** Whether the given path lies at or below the given folder. */
	private static boolean isBelow(PathInfo path, Path folder) {
		return normalize(path.toFile().toPath()).startsWith(normalize(folder));
	}

	/**
	 * How many link entries one request may follow, see {@link #walk(String, Path, Path, Path)}.
	 *
	 * <p>
	 * A shared folder may hold links of the owner's, which is why a chain exists at all; a chain
	 * this long is a mistake rather than a library, and the request is refused rather than followed
	 * until the stack ends.
	 * </p>
	 */
	private static final int MAX_LINK_DEPTH = 8;

	/**
	 * Walks a request path segment by segment, following the link entries of issue #50.
	 *
	 * <p>
	 * A segment that exists on disk is an ordinary step. A segment that does not, but names a
	 * {@link LinkStore.Link} of the folder reached so far, re-roots the rest of the path at the
	 * link's target: the remaining segments are resolved in the target owner's space, and the
	 * {@link Location} carries the owner and the target path, so that {@link #rights(Caller,
	 * PathInfo)} and {@link #clearance(Caller, PathInfo)} ask about the target — access is checked
	 * against the grant on the target, never against the link. What the answer is <em>spelled</em>
	 * in stays the request's own coordinates, see {@link Location#spell(String)}.
	 * </p>
	 *
	 * <p>
	 * The disk is asked first and the link store only for a segment that is not there, so a library
	 * without a single share pays nothing for this beyond the {@link Files#exists(Path,
	 * java.nio.file.LinkOption...)} it does anyway.
	 * </p>
	 */
	private Location walk(String prefix, Path root, Path relative, Path basePath) throws PathRefused {
		Path currentRoot = root;
		Path consumed = null;
		String spelled = "";
		String linkOwner = "";
		String linkTarget = "";
		String linkPath = "";
		Set<String> visited = new HashSet<>();
		int hops = 0;

		for (Path segment : relative) {
			String name = segment.toString();
			Path folder = consumed == null ? currentRoot : currentRoot.resolve(consumed);
			Path candidate = folder.resolve(name);

			if (_users != null && !Files.exists(candidate) && Files.isDirectory(folder)
				&& LinkStore.exists(folder.toFile())) {
				LinkStore.Link link = new LinkStore(folder.toFile()).get(name);
				if (link != null) {
					User owner = _users.getUser(link.getOwner());
					if (owner == null) {
						// The user the link names is gone; the link leads nowhere, and it says so.
						throw new PathRefused(HttpServletResponse.SC_NOT_FOUND, unknownSpace(link.getOwner()));
					}
					String target = linkTarget(link);
					if (++hops > MAX_LINK_DEPTH || !visited.add(link.getOwner() + "/" + target)) {
						LOG.warning("Refusing the link chain at '" + link + "': too long or circular.");
						throw new PathRefused(HttpServletResponse.SC_NOT_FOUND, LINK_LOOP);
					}
					currentRoot = spaceRoot(owner, basePath);
					consumed = target.isEmpty() ? null : java.nio.file.Paths.get(target);
					linkOwner = link.getOwner();
					linkTarget = target;
					linkPath = spelled + name;
					spelled = linkPath + "/";
					continue;
				}
			}

			consumed = consumed == null ? java.nio.file.Paths.get(name) : consumed.resolve(name);
			spelled = spelled + name + "/";
		}

		PathInfo path = consumed == null ? new PathInfo(currentRoot) : new PathInfo(currentRoot, consumed);
		// Which library the path lies in is decided by where it lies, never by how it was reached:
		// "~alice/bob/Secret" is bob's, not alice's, see spaceOf(PathInfo).
		return new Location(prefix, spaceOf(path), path, linkOwner, linkTarget, linkPath);
	}

	/**
	 * The target of the given link as a path relative to its owner's space.
	 *
	 * <p>
	 * A link may only ever point into a user's library: a record that names something else is a
	 * damaged sidecar, and following it would be the one way out of the folder tree this server
	 * serves.
	 * </p>
	 */
	private static String linkTarget(LinkStore.Link link) throws PathRefused {
		String path = link.getPath();
		if (path.isEmpty()) {
			return "";
		}
		Path relative = java.nio.file.Paths.get(path).normalize();
		if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()) {
			LOG.warning("Refusing the link '" + link + "': its target leaves the library.");
			throw new PathRefused(HttpServletResponse.SC_NOT_FOUND, LINK_ESCAPED);
		}
		return relative.toString().replace(java.io.File.separatorChar, '/');
	}

	/** Why the given caller is refused, ready to be shown to the user. */
	public String refusal(Caller caller, boolean write) {
		String own = caller.getRefusal();
		if (own != null) {
			return own;
		}
		if (caller.isShareLink()) {
			// Never LIBRARY_REFUSED: telling somebody who opened a share link to open a share link
			// would be nonsense, and there is nothing they could sign in as, see issue #51.
			return write ? SHARE_WRITE_REFUSED : SHARE_READ_REFUSED;
		}
		if (!caller.isPaired() && isLibraryMigrated()) {
			return LIBRARY_REFUSED;
		}
		return write ? WRITE_REFUSED : READ_REFUSED;
	}

	/**
	 * Why the given caller is refused the given right, ready to be shown to the user.
	 *
	 * <p>
	 * A caller that is nobody yet is told how to become somebody — that is the message of issue
	 * #28 and #45, and it stays what it was, the migrated library included. A signed-in caller is
	 * told which right they are missing instead: signing in again would not help them.
	 * </p>
	 */
	public String refusal(Caller caller, String right, boolean write) {
		if (caller.isPaired() || caller.isShareLink()) {
			return rightRefused(right);
		}
		return refusal(caller, write);
	}

	/**
	 * The folder every path of the given caller is resolved against.
	 *
	 * <p>
	 * A user's space folder is created when it is first needed, so that a user who has never
	 * stored anything still has a root to look at.
	 * </p>
	 */
	public Path spaceRoot(Caller caller, Path basePath) {
		return spaceRoot(caller.getSpace(), caller.getUserName(), caller.getRole(), basePath);
	}

	/**
	 * The folder the paths of the given user are resolved against.
	 *
	 * <p>
	 * The same folder {@link #spaceRoot(Caller, Path)} answers, reached by the canonical form
	 * <code>~&lt;user&gt;</code> instead of by the caller's own membership, see
	 * {@link #resolve(Caller, Path, String)}.
	 * </p>
	 */
	public Path spaceRoot(User user, Path basePath) {
		return spaceRoot(user.getSpace(), user.getName(), user.getRole(), basePath);
	}

	/** See {@link #spaceRoot(User, Path)}. */
	private Path spaceRoot(String space, String userName, String role, Path basePath) {
		boolean guest = Roles.GUEST.equals(role);
		if (space.isEmpty() && !guest) {
			return basePath;
		}
		Path root = guest ? guestRoot(basePath, userName) : basePath.resolve(space);
		if (!Files.isDirectory(root)) {
			try {
				Files.createDirectories(root);
				LOG.info("Created the space of '" + userName + "': " + root);
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Cannot create the space '" + root + "': " + ex.getMessage());
			}
		}
		return root;
	}

	/**
	 * How much of the images at the given path the given caller may see, see {@link Privacy}.
	 *
	 * <p>
	 * The clearance is computed from who the caller is and where the path lies, nothing else. It
	 * is the single place that decides visibility; the servlet only compares it with the
	 * {@link de.haumacher.imageServer.shared.model.ImagePart#getPrivacy() privacy} of an image.
	 * </p>
	 *
	 * <ul>
	 * <li>An anonymous caller has {@link Privacy#PUBLIC} clearance, so the single-user library of
	 * mode {@link AuthMode#WRITES} keeps working on the home network, minus its restricted
	 * images.</li>
	 * <li>The owner of the space the path lies in has {@link Privacy#PRIVATE} clearance, see
	 * {@link #ownerOf(PathInfo)}.</li>
	 * <li>A signed-in caller reaching a path through a {@link Grant} on somebody else's album has
	 * {@link Privacy#MEMBERS} clearance (issue #49): they are a member of that album, not its
	 * owner, so its private images stay hidden from them.</li>
	 * <li>Mode {@link AuthMode#OFF} knows no users at all: everything is visible, as before issue
	 * #46.</li>
	 * </ul>
	 *
	 * @param caller
	 *        Who sent the request, see {@link #caller(HttpServletRequest)}.
	 * @param path
	 *        The resource being read, already resolved against the caller's space.
	 */
	public int clearance(Caller caller, PathInfo path) {
		if (_mode == AuthMode.OFF) {
			return Privacy.PRIVATE;
		}
		if (caller.isShareLink()) {
			// A link holder is a member of the album it opens, never its owner, and the author's
			// own limit cuts that down further, see issue #51.
			return Math.min(Privacy.MEMBERS, caller.getShare().getMaxPrivacy());
		}
		if (!caller.isPaired()) {
			return Privacy.PUBLIC;
		}
		if (spaceRights(caller, path).containsAll(Rights.ALL)) {
			// The owner of the library the path lies in, see AuthService#spaceRights.
			return Privacy.PRIVATE;
		}
		// A signed-in caller reaching somebody else's album through a grant is a member of it.
		return mayView(caller, path) ? Privacy.MEMBERS : Privacy.PUBLIC;
	}

	/**
	 * The lowest {@link de.haumacher.imageServer.shared.model.ImagePart#getRating() rating} the
	 * given caller is served, see issue #51.
	 *
	 * <p>
	 * {@link Ratings#MIN} for everybody but a share link, which is to say: no filtering at all. The
	 * rating is what the viewer filters an album by; only a share link turns it into a limit the
	 * server enforces on the way out, see {@link de.haumacher.imageServer.PrivacyFilter}.
	 * </p>
	 */
	public int minRating(Caller caller) {
		return caller.isShareLink() ? caller.getShare().getMinRating() : Ratings.MIN;
	}

	/**
	 * What the given caller is allowed to do, see {@link AuthInfo}.
	 *
	 * <p>
	 * A share-link caller is answered with the link it opened ({@link AuthInfo#getShare()}), which
	 * is how the app learns that this is a session inside one subtree; its
	 * {@link AuthInfo#isWriteAllowed() writeAllowed} says whether the link allows contributions,
	 * asked at the link's own target. Everybody else is answered exactly as before, with no
	 * {@link AuthInfo#getShare() share} at all.
	 * </p>
	 *
	 * @param basePath
	 *        The root of the served album tree, needed to ask a link's target what it allows;
	 *        <code>null</code> is answered without that question.
	 */
	public AuthInfo authInfo(Caller caller, Path basePath) {
		AuthInfo result = AuthInfo.create()
			.setMode(_mode.protocolName())
			.setDeviceName(caller.getDeviceName())
			.setWriteAllowed(writeAllowed(caller))
			.setUserName(caller.getUserName())
			.setRole(caller.getRole())
			.setSpace(caller.getSpace());
		if (caller.getInvitation() != null) {
			// An invitation is no login: the caller is anonymous above and stays anonymous. This is
			// the one thing the server says about the token it was handed, see issue #52.
			InvitationStore.Link invitation = caller.getInvitation();
			return result.setInvitation(InvitationInfo.create()
				.setRole(invitation.getRole())
				.setInvitedBy(invitation.getInvitedBy())
				.setNote(invitation.getNote())
				.setExpires(invitation.getExpires()));
		}
		if (!caller.isShareLink() || caller.isShareGone() || basePath == null) {
			return result;
		}

		ShareStore.Link share = caller.getShare();
		Set<String> rights;
		try {
			rights = rights(caller, resolve(caller, basePath, "").getPath());
		} catch (PathRefused ex) {
			// The shared folder is gone; the link opens nothing, and it says so by allowing nothing.
			LOG.warning("Cannot ask the target of " + share + ": " + ex.getMessage());
			rights = Rights.NONE;
		}
		return result
			.setWriteAllowed(rights.contains(Rights.CONTRIBUTE))
			.setShare(ShareInfo.create()
				.setLabel(share.getLabel())
				.setExpires(share.getExpires())
				.setRights(Rights.onTheWire(rights))
				.setPath(canonical(share)));
	}

	/**
	 * The canonical <code>~&lt;owner&gt;/&lt;path&gt;</code> of the target of the given share link.
	 *
	 * <p>
	 * The one spelling of a folder that is right whoever is asking, see {@link #HOME_PREFIX}. The
	 * app shows it to name what the link opens; it is never a path the link caller may request —
	 * from inside a link the target is <code>/</code>, see {@link #resolveShared(Caller, Path, String)}.
	 * </p>
	 */
	public static String canonical(ShareStore.Link share) {
		return HOME_PREFIX + share.getOwner() + (share.getPath().isEmpty() ? "" : "/" + share.getPath());
	}

	/** Thrown by {@link AuthService#pair(PairRequest)} when the request is not honoured. */
	public static class PairRefused extends Exception {

		private final int _status;

		/** Creates a {@link PairRefused}. */
		public PairRefused(int status, String message) {
			super(message);
			_status = status;
		}

		/** The HTTP status to answer with. */
		public int getStatus() {
			return _status;
		}
	}

	/**
	 * Signs a device in and issues its token.
	 *
	 * <p>
	 * The pairing secret signs in the library owner: an empty {@link PairRequest#getUserName()}
	 * means "the owner" (that is what an app from before issue #45 sends), a non-empty one names
	 * the owner while it has no name yet and must match the owner's name afterwards.
	 * </p>
	 *
	 * @throws PairRefused
	 *         If the server does not pair at all, the secret does not match, or the request names
	 *         somebody other than the library owner.
	 */
	public PairResponse pair(PairRequest request) throws PairRefused, IOException {
		if (_mode == AuthMode.OFF) {
			throw new PairRefused(HttpServletResponse.SC_FORBIDDEN, PAIRING_DISABLED);
		}
		String invitation = request.getInvitation() == null ? "" : request.getInvitation().trim();
		if (!invitation.isEmpty()) {
			return accept(request, invitation);
		}
		if (_pairingSecret == null || _pairingSecret.isEmpty() || !matches(request.getSecret(), _pairingSecret)) {
			throw new PairRefused(HttpServletResponse.SC_FORBIDDEN, SECRET_REFUSED);
		}

		User owner;
		synchronized (_users) {
			try {
				owner = _users.nameOwner(request.getUserName());
			} catch (IllegalArgumentException ex) {
				throw new PairRefused(HttpServletResponse.SC_UNAUTHORIZED, ex.getMessage());
			}
		}

		String token = _users.addDevice(owner, request.getDeviceName());
		String deviceName = owner.getDevices().get(owner.getDevices().size() - 1).getName();
		if (!owner.getSpace().isEmpty() && _basePath != null) {
			spaceRoot(Caller.signedIn(owner, deviceName), _basePath);
		}
		return PairResponse.create()
			.setToken(token)
			.setDeviceName(deviceName)
			.setUserName(owner.getName())
			.setRole(owner.getRole())
			.setSpace(owner.getSpace());
	}

	/**
	 * Why the given invitation can no longer be accepted, <code>null</code> while it can.
	 *
	 * <p>
	 * Three different endings, and each of them says which: expired, already used, withdrawn. A
	 * person who was handed a link deserves to be told what happened to it, see issue #51 where the
	 * same rule was set for a share link.
	 * </p>
	 */
	public static String invitationGone(InvitationStore.Link invitation) {
		if (invitation.isRevoked()) {
			return INVITATION_REVOKED;
		}
		if (invitation.isUsed()) {
			return INVITATION_USED;
		}
		if (invitation.isExpired(java.time.Instant.now())) {
			return INVITATION_EXPIRED;
		}
		return null;
	}

	/**
	 * Accepts an invitation: creates the user it offers and signs this device in as them (#52).
	 *
	 * <p>
	 * Accepting <em>is</em> pairing, which is why it lives here and answers a
	 * {@link PairResponse}: the app that ends up holding the device token cannot tell how the token
	 * was earned, and nothing else in the server can either. The invitation is used up before the
	 * answer is written, so a token that raced itself creates exactly one user.
	 * </p>
	 *
	 * <p>
	 * A member needs a folder of their own, which a library that was never migrated has no room
	 * for: that is refused, spoken, and nothing is created. A guest needs no folder at all and is
	 * accepted either way, see {@link UserStore#GUESTS_DIRECTORY_NAME}.
	 * </p>
	 */
	private PairResponse accept(PairRequest request, String token) throws PairRefused, IOException {
		InvitationStore.Link invitation = _invitations.lookup(token);
		if (invitation == null) {
			throw new PairRefused(HttpServletResponse.SC_GONE, INVITATION_UNKNOWN_TOKEN);
		}
		String gone = invitationGone(invitation);
		if (gone != null) {
			throw new PairRefused(HttpServletResponse.SC_GONE, gone);
		}
		String role = Roles.MEMBER.equals(invitation.getRole()) || Roles.GUEST.equals(invitation.getRole())
			? invitation.getRole() : null;
		if (role == null) {
			// A record from a store this build does not understand; it creates nobody.
			LOG.warning("Refusing " + invitation + ": its role is none this build creates anybody with.");
			throw new PairRefused(HttpServletResponse.SC_GONE, invitationRoleRefused(invitation.getRole()));
		}

		String name;
		try {
			name = UserStore.checkUserName(request.getUserName());
		} catch (IllegalArgumentException ex) {
			throw new PairRefused(HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
		}

		User user;
		String deviceName;
		synchronized (_users) {
			if (!isNameFree(name)) {
				throw new PairRefused(HttpServletResponse.SC_CONFLICT, nameTaken(name));
			}
			boolean member = Roles.MEMBER.equals(role);
			if (member && !isLibraryMigrated()) {
				throw new PairRefused(HttpServletResponse.SC_CONFLICT, SPACE_REFUSED);
			}
			user = _users.addUser(new User(name, role, member ? name : "", java.time.Instant.now().toString()));
			// The device token and the user are written in one store, by this call.
			String issued = _users.addDevice(user, request.getDeviceName());
			deviceName = user.getDevices().get(user.getDevices().size() - 1).getName();
			// Used up before the answer: a single-use token must not survive its own success.
			_invitations.markUsed(invitation.getId(), name);
			if (_basePath != null && member) {
				// The member's own folder, made now rather than at their first upload, so that
				// their very first listing is of something that exists.
				spaceRoot(user, _basePath);
			}
			LOG.info("Accepted " + invitation + ": created the " + role + " '" + name + "'.");
			return PairResponse.create()
				.setToken(issued)
				.setDeviceName(deviceName)
				.setUserName(name)
				.setRole(role)
				.setSpace(user.getSpace());
		}
	}

	/**
	 * Whether nothing on this server answers to the given name yet.
	 *
	 * <p>
	 * A user name is three things at once: the subject of a grant, the name of a space folder, and
	 * what tells a <code>user:</code> subject from a <code>group:</code> one. So all three are
	 * asked — a user, a group, and an entry that already lies at the top of the base folder — and a
	 * name that is any of them is refused rather than quietly made unique.
	 * </p>
	 */
	public boolean isNameFree(String name) {
		if (_users != null && _users.getUser(name) != null) {
			return false;
		}
		if (_groups != null && _groups.getGroup(name) != null) {
			return false;
		}
		return _basePath == null || !Files.exists(_basePath.resolve(name));
	}

	/** Thrown when a management request is not carried out; nothing was changed in that case. */
	public static class Refused extends Exception {

		private final int _status;

		/** Creates a {@link Refused}. */
		public Refused(int status, String message) {
			super(message);
			_status = status;
		}

		/** The HTTP status to answer with. */
		public int getStatus() {
			return _status;
		}
	}

	/**
	 * Turns the guest of the given name into a member with a space of their own, see issue #52.
	 *
	 * <p>
	 * One rename does the whole move: a guest's root is already shaped like a space root (link
	 * sidecars beside a {@value UserStore#DIRECTORY_NAME} holding the share registry), so renaming
	 * <code>{@value UserStore#DIRECTORY_NAME}/{@value UserStore#GUESTS_DIRECTORY_NAME}/&lt;name&gt;</code>
	 * to <code>&lt;name&gt;</code> makes it the new space and carries the links and the registry
	 * into exactly the places a member keeps them. It is a rename within one file system, like the
	 * library migration of issue #45 — nothing is copied and no photo is touched, there being none.
	 * </p>
	 *
	 * <p>
	 * There is no way back: demotion is not offered, see {@link User#setRole(String)}. Everything
	 * is checked before anything is moved.
	 * </p>
	 *
	 * @return The promoted user.
	 * @throws Refused
	 *         If the promotion is not carried out; nothing was moved in that case.
	 */
	public User promote(String userName) throws Refused, IOException {
		if (_users == null || _basePath == null) {
			throw new Refused(HttpServletResponse.SC_FORBIDDEN, PAIRING_DISABLED);
		}
		synchronized (_users) {
			String name = userName == null ? "" : userName.trim();
			User user = name.isEmpty() ? null : _users.getUser(name);
			if (user == null) {
				throw new Refused(HttpServletResponse.SC_NOT_FOUND, unknownUser(name));
			}
			if (!Roles.GUEST.equals(user.getRole())) {
				throw new Refused(HttpServletResponse.SC_BAD_REQUEST, notAGuest(name));
			}
			if (!isLibraryMigrated()) {
				throw new Refused(HttpServletResponse.SC_CONFLICT, SPACE_REFUSED);
			}

			Path target = _basePath.resolve(name);
			Path source = guestRoot(_basePath, name);
			if (Files.exists(target)) {
				// Somebody's folder is already there; a promotion never merges two folders.
				throw new Refused(HttpServletResponse.SC_CONFLICT, promotionBlocked(name));
			}
			if (Files.isDirectory(source)) {
				Files.move(source, target);
			} else {
				// A guest nothing was ever shared with has no root yet; the member's is made empty.
				Files.createDirectories(target);
			}

			user.setRole(Roles.MEMBER);
			user.setSpace(name);
			_users.store();
			LOG.info("Promoted the guest '" + name + "' to a member with the space '" + name + "'.");
			return user;
		}
	}

	private static boolean matches(String presented, String expected) {
		if (presented == null) {
			return false;
		}
		return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
			expected.getBytes(StandardCharsets.UTF_8));
	}
}
