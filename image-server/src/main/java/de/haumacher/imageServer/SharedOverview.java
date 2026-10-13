/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.ImageServlet.Context;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.OverviewLink;
import de.haumacher.imageServer.shared.model.OverviewLinkRequest;
import de.haumacher.imageServer.shared.model.SharedAlbum;
import de.haumacher.imageServer.shared.model.SharedWithYou;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * The overview of everything shared with a contact, see issue #267.
 *
 * <p>
 * <b>Address.</b> One stable address per space, <code>&lt;app base&gt;/shared/</code>, which the
 * application is served at like a share link; its requests go to the space's data root with the
 * contact credential alone (<code>X-VAlbum-Contact</code>) and <code>X-VAlbum-Shared: all</code>,
 * no bearer. <code>&lt;data&gt;/?type=shared</code> answers a {@link SharedWithYou}: one
 * {@link SharedAlbum} per link the contact may open, see {@link AuthService#sharedWith}.
 * </p>
 *
 * <p>
 * <b>Opening an album.</b> A recipient's own token is stored only as its hash, so the overview
 * cannot hand out the link's address. An album is opened at
 * <code>&lt;app base&gt;/shared/&lt;link id&gt;/</code> instead, whose requests carry
 * <code>X-VAlbum-Shared: &lt;link id&gt;</code> beside the credential: the server resolves it to that
 * link for that contact ({@link AuthService#caller}), with exactly the link's rights, clearance,
 * rating floor and label, confined to its folder. A link id opens nothing without a credential the
 * link admits, and no token or hash of any link is ever answered.
 * </p>
 *
 * <p>
 * <b>A contact's own overview link.</b> <code>&lt;data&gt;/?action=overview-link</code> issues a
 * contact a token of their own (a member who manages the contacts asks), stored only as its hash
 * and opened at <code>&lt;app base&gt;/s/&lt;token&gt;/</code>: its first open identifies them as a
 * recipient's own link does, and the application then leaves for the overview.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class SharedOverview {

	private static final Logger LOG = Logger.getLogger(SharedOverview.class.getName());

	/** The <code>type</code> of the overview's request. */
	static final String TYPE = "shared";

	/** The <code>action</code> issuing a contact's own overview link. */
	static final String LINK_ACTION = "overview-link";

	/** What an unreadable request for an overview link is answered. */
	static final String UNREADABLE = "The request about an overview link cannot be read.";

	private final ImageServlet _servlet;

	private final AuthService _auth;

	private final Path _basePath;

	SharedOverview(ImageServlet servlet, AuthService auth, Path basePath) {
		_servlet = servlet;
		_auth = auth;
		_basePath = basePath;
	}

	/**
	 * Answers <code>?type=shared</code>: the overview for a contact, and for everybody else why there
	 * is none &mdash; a member is one of the space, a link opens its own album only, and whoever is
	 * nobody is asked who they are.
	 */
	void serve(Context context, Caller caller) throws IOException {
		if (_auth.getContacts() == null) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_NOT_FOUND, AuthService.SHARED_UNAVAILABLE);
			return;
		}
		if (caller.isPaired()) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_CONFLICT, AuthService.SHARED_MEMBER);
			return;
		}
		ContactStore.Contact contact = caller.getContact();
		if (contact == null) {
			if (caller.isShareLink() || caller.isShareGone() || caller.hasInvalidToken()) {
				LOG.warning("Refusing the overview of what is shared to a link or a token of no contact.");
				ImageServlet.errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARED_LINK_REFUSED);
				return;
			}
			// Nobody: asked who they are, as the overview's own requests are.
			_servlet.gone(context, _auth.sharedNobody());
			return;
		}
		SharedWithYou result = SharedWithYou.create()
			.setContact(AuthService.contactInfo(contact))
			.setOtherSessions(_auth.getContacts().otherSessions(contact.getId(),
				caller.getSession() == null ? "" : caller.getSession().getId()))
			.setSignIns(SignInActions.signIns(contact, _servlet.passkeys()));
		for (ShareStore.Link link : _auth.sharedWith(contact)) {
			result.addAlbum(album(link, _auth.linkCaller(link, contact, caller.getSession())));
		}
		ImageServlet.serveJsonObject(context.response(), result);
	}

	/** One album of the overview: what the link itself shows of its folder. */
	private SharedAlbum album(ShareStore.Link link, Caller linkCaller) {
		SharedAlbum result = SharedAlbum.create()
			.setLink(link.getId())
			.setSharedBy(link.getCreatedBy())
			.setExpires(link.getExpires())
			.setCreated(link.getCreated());
		PathInfo folder;
		try {
			folder = _auth.resolve(linkCaller, _basePath, "").getPath();
		} catch (AuthService.PathRefused ex) {
			// The shared folder is gone: the album is listed, and opening it says why.
			result.setRights(Rights.onTheWire(Rights.NONE));
			return result;
		}
		result.setRights(Rights.onTheWire(_auth.rights(linkCaller, folder)));
		if (!folder.toFile().isDirectory()) {
			return result;
		}
		FolderInfo tile;
		try {
			tile = _servlet.tileFor(folder, linkCaller);
		} catch (de.haumacher.imageServer.cache.ResourceCache.UnreadableSidecar ex) {
			// A folder whose sidecar cannot be read shows no picture, see issue #254.
			tile = FolderInfo.create().setName(folder.getName());
		}
		if (link.getPath().isEmpty()) {
			// The whole space: no folder name of its own to show.
			tile.setName("");
		}
		return result.setFolder(tile);
	}

	/**
	 * Answers <code>?action=overview-link</code>: issues the contact a fresh overview link, the one
	 * before void, or withdraws it. Asked by a member who manages the contacts, see
	 * {@link ImageServlet#contactManager}.
	 */
	void issueLink(Context context) throws IOException {
		Caller caller = _servlet.contactManager(context);
		if (caller == null) {
			return;
		}
		OverviewLinkRequest request;
		try {
			request = OverviewLinkRequest.readOverviewLinkRequest(new JsonReader(new ReaderAdapter(
				new InputStreamReader(new ByteArrayInputStream(ImageServlet.readBody(context.request())),
					StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, UNREADABLE);
			return;
		}
		ContactStore contacts = _auth.getContacts();
		ContactStore.Contact contact = contacts.get(request.getContact());
		if (contact == null) {
			ImageServlet.errorInfo(context, HttpServletResponse.SC_NOT_FOUND,
				ContactStore.unknownContact(request.getContact()));
			return;
		}
		OverviewLink result = OverviewLink.create().setUrl("");
		if (request.isWithdraw()) {
			contacts.withdrawOverview(contact.getId());
			LOG.info("Withdrew the overview link of " + contact + ", by '" + caller.getUserName() + "'.");
		} else {
			String token = contacts.issueOverview(contact.getId());
			LOG.info("Issued an overview link to " + contact + ", by '" + caller.getUserName() + "'.");
			result.setUrl(_servlet.overviewUrl(context, token));
		}
		ImageServlet.serveJsonObject(context.response(), result.setContact(_servlet.contactOnTheWire(contact)));
	}
}
