/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.links;

import de.haumacher.imageServer.AlbumDate;
import de.haumacher.imageServer.PathInfo;
import de.haumacher.imageServer.PlacementRule;
import de.haumacher.imageServer.PrivacyFilter;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.AuthService.Space;
import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.GrantStore.Grant;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.links.LinkStore.Link;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What a link entry means for a listing, for a share that is new and for one that is removed, see
 * issue #50.
 *
 * <p>
 * The three things a link needs beyond being resolved (which is
 * {@link AuthService#resolve(Caller, Path, String)}):
 * </p>
 * <ul>
 * <li>{@link #augment(ListingInfo, PathInfo, Caller, int)} shows the links of a folder as tiles
 * built from their targets, and omits the ones the caller may not view.</li>
 * <li>{@link #materialise(Caller, PathInfo)} creates the links for the grants a caller holds and
 * has not seen yet, once, when their own space root is listed.</li>
 * <li>{@link #unlink(Caller, PathInfo, List)} removes a link and remembers that the recipient does
 * not want it back.</li>
 * </ul>
 *
 * <p>
 * Everything this class writes lies in the recipient's own space: a {@value LinkStore#FILE_NAME}
 * beside their folders and the {@link ShareRegistry} below their space root. The owner's library is
 * only ever read — no folder of hers is created, renamed, written or removed because somebody
 * linked, browsed or unlinked her album, and nothing there records who did.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class LinkService {

	private static final Logger LOG = Logger.getLogger(LinkService.class.getName());

	/** The message the removal of a link is reported with. */
	public static final String UNLINKED =
		"Removed from your albums. The album itself is untouched and stays with its owner.";

	/** The message an entry that is no link is refused the removal with. */
	public static String notALink(String name) {
		return "'" + name + "' is not a shared album in this folder; only a shared album can be removed here.";
	}

	private final Path _basePath;

	private final AuthService _auth;

	private final PrivacyFilter _privacy;

	/**
	 * Creates a {@link LinkService}.
	 *
	 * @param basePath
	 *        The root of the served album tree; the user spaces are folders in it.
	 * @param auth
	 *        Who the caller is and what they may do, asked about the <em>target</em> of every link.
	 * @param privacy
	 *        Hides the cover of a link whose target holds it above the caller's clearance.
	 */
	public LinkService(Path basePath, AuthService auth, PrivacyFilter privacy) {
		_basePath = basePath;
		_auth = auth;
		_privacy = privacy;
	}

	/**
	 * The given listing with the links of the folder shown as tiles, in the listing's own order.
	 *
	 * <p>
	 * A link is shown as what it points at: its title, its subtitle, its cover and its date come
	 * from the target's own sidecar and folder name, read exactly as the target's own tile would be
	 * (see {@link ResourceCache#folderInfo(File)}), while its {@link FolderInfo#getName() name} is
	 * the one it has here — so the cover URL the app builds
	 * (<code>&lt;listing&gt;/&lt;name&gt;/&lt;image&gt;</code>) resolves through the link like any
	 * other path.
	 * </p>
	 *
	 * <p>
	 * Two links are never shown: one whose target the caller may not view (a revoked grant hides
	 * the tile, and the record stays, so re-granting shows it again) and one whose target is gone
	 * (the record is removed here — a sidecar of the recipient's, never anybody's photo).
	 * </p>
	 *
	 * @param listing
	 *        The listing as the cache built it; it is never modified.
	 * @param folder
	 *        Where that listing lies.
	 * @param caller
	 *        Who is asking; the rights on every <em>target</em> are asked about them.
	 * @param viewAs
	 *        The clearance the request lowered itself to, see
	 *        {@link de.haumacher.imageServer.auth.Privacy#viewAs(String)}.
	 * @return The listing itself when this folder holds no visible link.
	 */
	public ListingInfo augment(ListingInfo listing, PathInfo folder, Caller caller, int viewAs) {
		File dir = folder.toFile();
		if (!LinkStore.exists(dir)) {
			return listing;
		}
		LinkStore links = new LinkStore(dir);
		List<FolderInfo> tiles = new ArrayList<>();
		for (Link link : links.getLinks()) {
			PathInfo target = target(link);
			if (target == null || !target.toFile().isDirectory()) {
				if (caller.isShareLink()) {
					// A link holder is a visitor in somebody else's library and writes nothing in
					// it, not even a tidy-up: the owner's folders are what they were, see #51.
					continue;
				}
				// The album this link pointed at is gone. Nothing here is a user's file: the
				// record goes, so that the tile does not come back on every listing.
				try {
					links.remove(link.getName());
					LOG.info("Removed the dangling link '" + link + "' from '" + links.getFile() + "'.");
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Cannot remove the dangling link '" + link + "': " + ex.getMessage(), ex);
				}
				continue;
			}
			if (!_auth.mayView(caller, target) || !_auth.reachable(caller, _basePath, target)) {
				// The grant was revoked: the tile disappears, the record stays. A share link is
				// shown no tile leading out of what it opens, either — it could not be opened.
				continue;
			}
			tiles.add(tile(link, target, caller, viewAs));
		}
		if (tiles.isEmpty()) {
			return listing;
		}

		List<FolderInfo> folders = new ArrayList<>(listing.getFolders());
		folders.addAll(tiles);
		folders.sort(ResourceCache.BY_DATE);
		return ListingInfo.create()
			.setTitle(listing.getTitle())
			.setPlacement(listing.getPlacement())
			.setFolders(folders);
	}

	/** The tile of a single link: the target's, under the link's name and marked as a link. */
	private FolderInfo tile(Link link, PathInfo target, Caller caller, int viewAs) {
		FolderInfo tile = ResourceCache.folderInfo(target.toFile());
		tile.setName(link.getName());
		tile.setLink(link.canonical());
		int clearance = Math.min(_auth.clearance(caller, target), viewAs);
		return _privacy.filterEntry(tile, target, clearance, _auth.minRating(caller));
	}

	/**
	 * Creates the links for everything that was shared with the caller and is not in their space
	 * yet, see issue #50.
	 *
	 * <p>
	 * Called when a caller lists the root of their own space, and nowhere else: a share becomes a
	 * link the first time the recipient looks at their tree, so a group that gains a member fans
	 * nothing out — the new member simply sees the links at their next root listing.
	 * </p>
	 *
	 * <p>
	 * Which grants: every {@link Grant} whose subject names the caller by their user or by a group
	 * they are in. Never {@link Subjects#ANONYMOUS} — everybody's grants are not somebody's albums,
	 * and a library opened to the world would otherwise land in every member's tree — and never a
	 * grant on the caller's own space, which they see anyway. What the space has already dealt with
	 * is what the {@link ShareRegistry} says, so nothing is ever created twice and a
	 * {@link ShareRegistry#DECLINED declined} share stays gone.
	 * </p>
	 *
	 * <p>
	 * The new link is named after the target folder and filed by the root's own placement rule
	 * (issue #48), using the target's date: a shared album lands in the recipient's year folder
	 * exactly as one of their own would.
	 * </p>
	 *
	 * @return Whether anything was created; the caller invalidates its cache then, since a
	 *         placement rule may have created a year folder.
	 */
	public boolean materialise(Caller caller, PathInfo root) {
		UserStore users = _auth.getUsers();
		GrantStore grants = _auth.getGrants();
		if (users == null || grants == null || !caller.isPaired()) {
			// Mode "off" knows no users and no grants, and an anonymous caller has no space.
			return false;
		}
		Set<String> subjects = new LinkedHashSet<>(_auth.subjects(caller));
		subjects.remove(Subjects.ANONYMOUS);
		if (subjects.isEmpty()) {
			return false;
		}

		File rootDir = root.toFile();
		ShareRegistry registry = null;
		boolean created = false;
		for (Grant grant : grants.getGrants()) {
			if (!subjects.contains(grant.getSubject()) || grant.getOwner().equals(caller.getUserName())) {
				continue;
			}
			User owner = users.getUser(grant.getOwner());
			if (owner == null) {
				continue;
			}
			PathInfo target = target(owner, grant.getPath());
			if (target == null || !target.toFile().isDirectory()) {
				// Nothing is recorded: the folder may yet appear, and then the link is made.
				continue;
			}
			if (registry == null) {
				// Built at the first candidate and not before: a caller nothing was shared with
				// leaves no file behind, and a guest without a space leaves no folder either.
				registry = new ShareRegistry(rootDir.toPath());
			}
			if (registry.knows(grant.getOwner(), grant.getPath())) {
				continue;
			}

			try {
				link(rootDir, grant, target, owner);
				registry.link(grant.getOwner(), grant.getPath());
				created = true;
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Cannot link the album shared by '" + grant.getOwner() + "' into the space of '"
					+ caller.getUserName() + "': " + ex.getMessage(), ex);
			}
		}
		return created;
	}

	/** Creates one link in the caller's space root, filed by that root's placement rule. */
	private void link(File rootDir, Grant grant, PathInfo target, User owner) throws IOException {
		String targetName = target.toFile().getName();
		String name = grant.getPath().isEmpty() ? owner.getName() : targetName;

		File folder = rootDir;
		if (!PlacementRule.isPlacementFolder(targetName)) {
			File placed = PlacementRule.of(rootDir).create(rootDir,
				AlbumDate.ofFolder(ResourceCache.sidecar(target.toFile()), targetName));
			if (placed != null) {
				folder = placed;
			}
		}

		LinkStore links = new LinkStore(folder);
		String free = freeName(folder, links, name);
		links.put(free, grant.getOwner(), grant.getPath());
		LOG.info("Linked '" + LinkStore.canonical(grant.getOwner(), grant.getPath()) + "' as '" + free + "' in '"
			+ folder + "'.");
	}

	/**
	 * Removes the named links of the given folder, see issue #50.
	 *
	 * <p>
	 * Deleting a link is declining the share: the record goes and the {@link ShareRegistry} of the
	 * space remembers the target as {@link ShareRegistry#DECLINED}, so that the next listing of the
	 * root does not put it back and a later grant on the same album does not either. Nothing of the
	 * owner's is touched — the album stays exactly where it is and the grant stays exactly as it
	 * was; only this recipient stopped keeping it in their tree.
	 * </p>
	 *
	 * <p>
	 * The answer speaks the vocabulary of a move (issue #47): one {@link MoveOutcome} per requested
	 * name, in the order they were asked for, and a name that is no link of this folder says so
	 * instead of failing the request.
	 * </p>
	 */
	public MoveResult unlink(Caller caller, PathInfo folder, List<String> names) throws IOException {
		LinkStore links = new LinkStore(folder.toFile());
		ShareRegistry registry = registryOf(folder);
		MoveResult result = MoveResult.create();
		for (String name : names) {
			Link link = links.get(name);
			if (link == null) {
				result.addOutcome(outcome(name, "", notALink(name)));
				continue;
			}
			links.remove(name);
			if (registry != null) {
				registry.decline(link.getOwner(), link.getPath());
			}
			LOG.info("Removed the link '" + link + "' from '" + links.getFile() + "'.");
			result.addOutcome(outcome(name, "", UNLINKED));
		}
		return result;
	}

	/**
	 * The {@link ShareRegistry} of the space the given path lies in, <code>null</code> if it lies
	 * in none.
	 */
	public ShareRegistry registryOf(PathInfo path) {
		Space space = _auth.spaceOf(path);
		if (space == null) {
			return null;
		}
		User owner = _auth.getUsers() == null ? null : _auth.getUsers().getUser(space.getOwner());
		if (owner == null) {
			return null;
		}
		return new ShareRegistry(spaceFolder(owner));
	}

	/**
	 * Where the given link points, <code>null</code> if its owner is nobody this server knows.
	 *
	 * <p>
	 * The path is built against the owner's space folder, so that the {@link PathInfo} is the one
	 * the servlet would build for a canonical <code>~owner/…</code> request: the same cache key,
	 * the same {@link AuthService#spaceOf(PathInfo) space} and therefore the same rights.
	 * </p>
	 */
	public PathInfo target(Link link) {
		return target(_auth, _basePath, link);
	}

	/**
	 * {@link #target(Link)} without a {@link LinkService}, for the move of issue #47.
	 *
	 * @param auth
	 *        Who the users are, <code>null</code> in a server without any; such a server has no
	 *        links either, and the answer is then <code>null</code>.
	 */
	public static PathInfo target(AuthService auth, Path basePath, Link link) {
		UserStore users = auth == null ? null : auth.getUsers();
		User owner = users == null ? null : users.getUser(link.getOwner());
		return owner == null ? null : target(basePath, owner, link.getPath());
	}

	private PathInfo target(User owner, String path) {
		return target(_basePath, owner, path);
	}

	private static PathInfo target(Path basePath, User owner, String path) {
		Path root = spaceFolder(basePath, owner);
		if (path.isEmpty()) {
			return new PathInfo(root);
		}
		Path relative = java.nio.file.Paths.get(path).normalize();
		if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()) {
			// A link may only ever point into a user's library, see AuthService#LINK_ESCAPED.
			LOG.warning("Ignoring the link target '" + path + "' of '" + owner.getName() + "': it leaves the library.");
			return null;
		}
		return new PathInfo(root, relative);
	}

	/**
	 * The folder the given user's paths are resolved against, without creating it.
	 *
	 * <p>
	 * Reading a link must not create anything at all, the space folder of its owner included: a
	 * listing is a read.
	 * </p>
	 */
	private Path spaceFolder(User owner) {
		return spaceFolder(_basePath, owner);
	}

	private static Path spaceFolder(Path basePath, User owner) {
		return owner.getSpace().isEmpty() ? basePath : basePath.resolve(owner.getSpace());
	}

	/**
	 * A name for a new link in the given folder that nothing there uses yet.
	 *
	 * <p>
	 * The rule of an upload and of a move (issue #47): a clash is resolved by appending a number,
	 * and nothing is ever replaced. A link is an entry without a file extension, so the number goes
	 * at the end of the whole name (<code>Zoo</code>, <code>Zoo-2</code>), and both the folders on
	 * disk and the links already recorded are what it has to avoid — a link never shadows a folder
	 * of one's own, and a folder created later wins the name back, since resolution asks the disk
	 * first.
	 * </p>
	 */
	public static String freeName(File folder, LinkStore links, String name) {
		if (isFree(folder, links, name)) {
			return name;
		}
		int num = 2;
		while (true) {
			String candidate = name + "-" + num;
			if (isFree(folder, links, candidate)) {
				return candidate;
			}
			num++;
		}
	}

	private static boolean isFree(File folder, LinkStore links, String name) {
		return !new File(folder, name).exists() && links.get(name) == null;
	}

	private static MoveOutcome outcome(String name, String newName, String message) {
		return MoveOutcome.create().setName(name).setNewName(newName).setMessage(message);
	}
}
