/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.shared.util.UpdateTransient;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Moves images, albums and folders from one folder into another one, see issue #47.
 *
 * <p>
 * A move is a rename and nothing else: {@link Files#move(Path, Path, java.nio.file.CopyOption...)}
 * within the same file system, never a copy. No original is deleted, resized or rewritten; the
 * only two places a moved file can land are the target folder and, when the target already holds
 * the very same contents, the {@value #DUPLICATES_FOLDER} folder below {@value
 * UserStore#DIRECTORY_NAME}.
 * </p>
 *
 * <p>
 * Everything the album knows about a moved image travels with it: its {@link ImagePart} leaves the
 * source <code>index.json</code> with rating, privacy level, comment, orientation and dimensions
 * intact and takes its place by date in the target's, see {@link #insertByDate(AlbumInfo, AlbumPart)}
 * and issue #262, and its entry in the {@link HashCache} — the content
 * hash and who contributed the photo, see issue #53 — moves from the one folder's sidecar to the
 * other's. A whole folder moves by one rename, so its sidecars ride along by nature. Previews are derived data: whatever {@link PreviewCache}
 * holds for the old path is abandoned, never moved.
 * </p>
 *
 * <p>
 * Both sidecars are written once, after the last entry was dealt with (see
 * {@link #move(PathInfo, PathInfo, List)}), and through the very code path a client's PUT takes,
 * see {@link ImageServlet#storeSidecar(File, byte[])}: there is one sidecar format, and a move
 * writes no other.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class MoveService {

	private static final Logger LOG = Logger.getLogger(MoveService.class.getName());

	/**
	 * The folder below {@link UserStore#DIRECTORY_NAME} a duplicate is set aside in.
	 *
	 * <p>
	 * The server never deletes an original. A file whose contents the target folder already holds
	 * cannot stay in the source album (the album is what the user is tidying up) and must not
	 * become a second copy at the target either, so it is moved out of the way and kept. Emptying
	 * that folder is the owner's act, never the server's.
	 * </p>
	 *
	 * <p>
	 * Below the <em>space</em> the move happens in, like the
	 * {@link DeleteService#TRASH_FOLDER trash} of issue #109: nothing crosses a space boundary
	 * since issue #82, and one space's photographs have no business lying below another's. A
	 * server hosting a single space writes exactly where it always wrote, that space being the
	 * base folder; what an older multi-space server left below the base folder stays there and is
	 * named once at start-up, see {@link DeleteService#legacyDuplicates(Path)}.
	 * </p>
	 */
	public static final String DUPLICATES_FOLDER = "duplicates";

	/** How the duplicates folder is named in a message shown to the user. */
	private static final String DUPLICATES_PATH = UserStore.DIRECTORY_NAME + "/" + DUPLICATES_FOLDER;

	/** The message an unreadable move request is refused with. */
	public static final String MOVE_UNREADABLE = "The move request cannot be read.";

	/** The message a move out of a folder that does not exist is refused with. */
	public static final String SOURCE_MISSING = "The folder to move from does not exist.";

	/** The message a move into a folder that does not exist is refused with. */
	public static final String TARGET_MISSING = "The folder to move to does not exist.";

	/** The message a move into something that is not a folder is refused with. */
	public static final String TARGET_NOT_A_FOLDER = "The target of a move must be a folder.";

	/** The message a target path leaving the caller's library is refused with. */
	public static final String TARGET_ESCAPED = "The folder to move to is outside your library.";

	/** The message a move into the folder that is being moved out of is refused with. */
	public static final String SAME_FOLDER = "The source and the target folder are the same; nothing would move.";

	/** The message a caller is refused with that may not change the source folder. */
	public static final String EDIT_REFUSED = "You may not change the folder you are moving from.";

	/** The message a caller is refused with that may not add to the target folder. */
	public static final String CONTRIBUTE_REFUSED = "You may not add to the folder you are moving to.";

	/**
	 * The message a caller is refused with that moves what somebody else contributed, see issue
	 * #53.
	 *
	 * <p>
	 * A contributor may take their own contribution back out of an album without holding the edit
	 * right on it, and nobody else's: the rule is all or nothing over the whole request, so a
	 * selection that mixes one's own photos with the owner's moves nothing at all.
	 * </p>
	 */
	public static final String CONTRIBUTION_REFUSED =
		"You may only move photos out of this album that you contributed yourself.";

	/** The message an entry the source folder does not hold is refused with. */
	public static String notFound(String name) {
		return "'" + name + "' does not exist in the folder to move from.";
	}

	/** The message an entry is refused with that is not an album entry at all. */
	public static String notAnEntry(String name) {
		return "'" + name + "' is not an image and not a folder; it cannot be moved.";
	}

	/** The message a folder is refused with whose name the target folder already uses. */
	public static String nameTaken(String name) {
		return "'" + name + "' already exists in the target folder; nothing is overwritten.";
	}

	/** The message a folder is refused with that would be moved into itself or below itself. */
	public static String intoItself(String name) {
		return "'" + name + "' cannot be moved into itself.";
	}

	/** The message an image is refused with whose target folder is a folder of folders. */
	public static String notAnAlbum(String name) {
		return "'" + name + "' cannot be moved: the target folder is a folder of folders, not an album.";
	}

	/**
	 * The message an album or a folder is refused with whose target folder is an album.
	 *
	 * <p>
	 * The mirror image of {@link #notAnAlbum(String)}, see issue #113: an album is a directory like
	 * any other on disk, so nothing but this stops a folder from being nested in one — and the
	 * model has no place for a folder inside an album, so it would be invisible from that moment
	 * on.
	 * </p>
	 */
	public static String notAFolder(String name) {
		return "'" + name + "' cannot be moved: the target is an album, and an album holds no folders.";
	}

	/** The message of an image whose contents the target folder already held. */
	public static String duplicate(String existing) {
		return "The target folder already holds this photo as '" + existing + "'; the file was set aside in '"
			+ DUPLICATES_PATH + "'.";
	}

	/**
	 * The message of a photograph that took the place of the target album's reference to it, see
	 * issue #307: an album never shows one photograph twice.
	 */
	public static final String TOOK_REFERENCE_PLACE =
		"The album showed this photo as a reference; the file took its place there, with the album's labels and description.";

	/** The message of a group some of whose images the target folder already held. */
	public static String membersSetAside(int count) {
		return count + " image(s) of this group were already in the target folder; those files were set aside in '"
			+ DUPLICATES_PATH + "'.";
	}

	/** The message a name is refused with that the same request already asked to move. */
	public static String namedTwice(String name) {
		return "'" + name + "' is named more than once in this request; it moves only once.";
	}

	/** The message a link is refused with that would be moved into another user's library. */
	public static String linkEscaped(String name) {
		return "'" + name + "' is an album of somebody else's; it can only be kept in your own library.";
	}

	/** The message a link is refused with that would be moved into the album it points at. */
	public static String linkIntoTarget(String name) {
		return "'" + name + "' cannot be moved into the album it points at.";
	}

	/** The message a folder is reported with that files nothing, see {@link #place(PathInfo)}. */
	public static final String NO_RULE = "This folder files nothing by itself: it has no placement rule.";

	/** The message an entry is reported with that nothing says a date about. */
	public static final String KEPT_NO_DATE =
		"Kept: nothing says when this happened, so there is no folder to file it in.";

	/** The message a folder that cannot be listed is refused with. */
	public static final String FOLDER_UNREADABLE = "The folder cannot be listed.";

	/** The message an entry is reported with whose rename failed. */
	public static String failed(String reason) {
		return "The move failed: " + reason;
	}

	/** The message the entries behind a failed one are reported with. */
	public static final String ABANDONED = "Not moved: an earlier entry of this request could not be moved.";

	private final ResourceCache _cache;

	/** Decides whose space a folder lies in, <code>null</code> in a server without users. */
	private final AuthService _auth;

	/**
	 * Creates a {@link MoveService}.
	 *
	 * <p>
	 * No base folder is needed any more (issue #109): what a move sets aside goes below the space
	 * the move happens in, and that space is read from the path being moved out of, see
	 * {@link PathInfo#getBasePath()} and {@link #DUPLICATES_FOLDER}.
	 * </p>
	 *
	 * @param cache
	 *        The cache that must forget what the move changed.
	 */
	public MoveService(ResourceCache cache) {
		this(cache, null);
	}

	/**
	 * Creates a {@link MoveService} that can move the link entries of issue #50, too.
	 *
	 * @param cache
	 *        The cache that must forget what the move changed.
	 * @param auth
	 *        Who owns which space: a link is an album of somebody else's and may only ever be kept
	 *        in the recipient's own library, so a move that would carry it into another space is
	 *        refused. <code>null</code> in a server without users, which has no links either.
	 */
	public MoveService(ResourceCache cache, AuthService auth) {
		_cache = cache;
		_auth = auth;
	}

	/** Who moves, see {@link #movedBy(String, String)}; the empty string for nobody named. */
	private String _mover = "";

	private String _moverLabel = "";

	/**
	 * The moment of the move running now where it takes photographs out of the inbox into an
	 * album, see {@link #movedBy(String, String)}; <code>0</code> for any other move.
	 */
	private long _outOfInbox;

	/**
	 * Names who moves, see issue #268: a photograph this service takes out of the inbox into an
	 * album records the mover and the moment in its attribution (see
	 * {@link HashCache.Attribution#movedOutOfInbox(String, String, long)}), because that is when it
	 * appears for everybody else, and the bell of the others tells them so.
	 *
	 * @param subject
	 *        The {@link de.haumacher.imageServer.auth.AuthService.Caller#subject() subject} of the
	 *        caller moving.
	 * @param label
	 *        What to show for them.
	 * @return This service.
	 */
	public MoveService movedBy(String subject, String label) {
		_mover = subject == null ? "" : subject;
		_moverLabel = label == null ? "" : label;
		return this;
	}

	/**
	 * Thrown when the request as a whole cannot be carried out; the servlet answers with an
	 * {@link de.haumacher.imageServer.shared.model.ErrorInfo} carrying {@link #getMessage()}.
	 */
	public static class MoveRefused extends Exception {

		private final int _status;

		/** Creates a {@link MoveRefused}. */
		public MoveRefused(int status, String message) {
			super(message);
			_status = status;
		}

		/** The HTTP status to answer with. */
		public int getStatus() {
			return _status;
		}
	}

	/** What is to happen to one requested name. */
	private static final class Entry {

		final String _name;

		/** Why this entry does not move, <code>null</code> while it does. */
		String _refusal;

		/** The directory to rename, <code>null</code> unless this is a folder entry. */
		File _folder;

		/**
		 * The folder this entry is renamed into: the target folder, or the year (or month) folder
		 * of the target's placement rule, see {@link MoveService#destination(PlacementRule, File,
		 * File)}.
		 */
		File _destination;

		/**
		 * The name to report the entry as: its own name, or its path below the target folder
		 * (<code>2020/2020 Trip</code>) when a placement rule filed it away.
		 */
		String _newName;

		/** The single image to move, <code>null</code> unless this is an image entry. */
		ImagePart _image;

		/** The group to move as a whole, <code>null</code> unless this is a group entry. */
		ImageGroup _group;

		/** The outcome of an entry that was already dealt with as part of a group. */
		String _movedWithGroup;

		Entry(String name) {
			_name = name;
		}
	}

	/**
	 * Moves the named entries of the source folder into the target folder.
	 *
	 * <p>
	 * Every name is checked before anything is renamed, and a name that cannot be moved refuses
	 * only itself: the answer carries one {@link MoveOutcome} per requested name, in the order
	 * they were asked for. A rename that fails half-way stops the request; the entries behind it
	 * are reported as {@link #ABANDONED} and both sidecars still describe exactly what did move,
	 * because they are written after the loop rather than inside it — one sidecar write per folder
	 * and per request, not one per moved image, and a single timestamped backup instead of a pile
	 * of them.
	 * </p>
	 *
	 * @param source
	 *        The folder the entries are taken out of.
	 * @param target
	 *        The folder they are moved into.
	 * @param names
	 *        The names of the entries to move.
	 * @throws MoveRefused
	 *         If the request as a whole is impossible.
	 */
	public MoveResult move(PathInfo source, PathInfo target, List<String> names) throws MoveRefused, IOException {
		return move(source, target, names, false, null);
	}

	/**
	 * Moves the named entries of the source folder into the target folder, and covers a target
	 * that was created for this move, see issues #273 and #277.
	 *
	 * <p>
	 * Adding photographs never touches the cover of an album that existed before: it keeps the
	 * cover it chose, and one that chose none goes on being shown by the default of issue #260.
	 * Only an album created for the move is given a cover, after the last entry was dealt with and
	 * before its sidecar is written, so the move and the cover are one step and one sidecar write,
	 * see {@link #coverCreated(AlbumInfo, Set, String, Map)}.
	 * </p>
	 *
	 * @param created
	 *        Whether the target was created for this move, in the dialog it was asked from; the
	 *        server cannot tell, the client says so.
	 * @param coverHint
	 *        The name of the entry the move was opened on, <code>null</code> or empty for none;
	 *        only read where <code>created</code>.
	 * @see #move(PathInfo, PathInfo, List)
	 */
	public MoveResult move(PathInfo source, PathInfo target, List<String> names, boolean created,
			String coverHint) throws MoveRefused, IOException {
		File sourceFolder = source.toFile();
		File targetFolder = target.toFile();
		if (!sourceFolder.isDirectory()) {
			throw new MoveRefused(HttpServletResponse.SC_NOT_FOUND, SOURCE_MISSING);
		}
		if (!targetFolder.exists()) {
			throw new MoveRefused(HttpServletResponse.SC_NOT_FOUND, TARGET_MISSING);
		}
		if (!targetFolder.isDirectory()) {
			throw new MoveRefused(HttpServletResponse.SC_BAD_REQUEST, TARGET_NOT_A_FOLDER);
		}
		if (sourceFolder.getAbsoluteFile().equals(targetFolder.getAbsoluteFile())) {
			throw new MoveRefused(HttpServletResponse.SC_BAD_REQUEST, SAME_FOLDER);
		}

		// The space the move happens in: its .valbum holds what is set aside, see #82 and #109.
		Path space = source.getBasePath();

		AlbumInfo sourceAlbum = albumOf(source);
		AlbumInfo targetAlbum = albumOf(target);
		// A folder that describes itself as a folder of folders has no place for an image; its
		// sidecar is the owner's statement and this server does not overrule it.
		FolderResource targetSidecar = ResourceCache.sidecar(targetFolder);
		boolean targetTakesImages = !(targetSidecar instanceof ListingInfo);
		// The mirror image, see issue #113: a folder that describes itself as an album has no place
		// for a folder, and a folder nested in one could never be addressed again.
		boolean targetTakesFolders = !(targetSidecar instanceof AlbumInfo);

		// What lands in a folder is filed by the folder's rule, see issue #48. Only folders are:
		// an image lands in an album, never in a folder of folders, and is refused above.
		PlacementRule rule = PlacementRule.of(targetSidecar);

		// Whether naming an image names the group behind it: in an album it does, in an inbox
		// there is no group to see, see issue #131.
		boolean groupsTravel = Inboxes.groupsTravel(sourceAlbum);

		List<Entry> entries = classify(sourceFolder, targetFolder, sourceAlbum, targetTakesImages,
			targetTakesFolders, rule, target.isRoot(), groupsTravel, names);

		// What the target held before, so that what lands there is told apart afterwards: a target
		// created for this move is covered by one of those, see issue #277.
		Set<AlbumPart> heldBefore = Collections.newSetFromMap(new IdentityHashMap<>());
		heldBefore.addAll(targetAlbum.getParts());

		// Where every photograph of the request ended up, by its name in the source folder: the
		// name it landed under, or the name of the copy the target already held, see issue #273.
		Map<String, String> landedAs = new HashMap<>();

		// A move out of the inbox into an album is when its photographs appear for the others, see
		// issue #268: one moment for the whole move, so that the bell groups it as one.
		_outOfInbox = !_mover.isEmpty() && Inboxes.isInbox(sourceFolder) && !Inboxes.isInbox(targetFolder)
			? System.currentTimeMillis() : 0;

		MoveResult result = MoveResult.create();
		HashCache sourceHashes = new HashCache(sourceFolder);
		HashCache targetHashes = new HashCache(targetFolder);
		boolean sourceChanged = false;
		boolean targetChanged = false;
		boolean stopped = false;
		_landingAlbum = targetAlbum;
		try {
			for (Entry entry : entries) {
				if (entry._refusal != null) {
					result.addOutcome(outcome(entry._name, "", entry._refusal));
					continue;
				}
				if (entry._movedWithGroup != null) {
					result.addOutcome(outcome(entry._name, entry._movedWithGroup, ""));
					continue;
				}
				if (stopped) {
					result.addOutcome(outcome(entry._name, "", ABANDONED));
					continue;
				}

				try {
					if (entry._folder != null) {
						result.addOutcome(moveFolder(entry));
					} else if (entry._group != null) {
						result.addOutcome(moveGroup(entry, entries, sourceAlbum, targetAlbum, sourceFolder,
							targetFolder, targetHashes, sourceHashes, space, landedAs));
						sourceChanged = true;
						targetChanged = true;
					} else {
						result.addOutcome(moveImage(entry, sourceAlbum, targetAlbum, sourceFolder, targetFolder,
							targetHashes, sourceHashes, space, landedAs));
						sourceChanged = true;
						targetChanged = true;
					}
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Cannot move '" + entry._name + "' from '" + sourceFolder + "' to '"
						+ targetFolder + "': " + ex.getMessage(), ex);
					result.addOutcome(outcome(entry._name, "", failed(ex.getMessage())));
					stopped = true;
				}
			}
		} finally {
			try {
				if (sourceChanged) {
					repairIndexPicture(sourceAlbum);
					UpdateTransient.updateTransient(sourceAlbum);
					ImageServlet.storeSidecar(sourceFolder, json(sourceAlbum));

					// The hashes of the vanished files go with them: a refresh forgets what is gone.
					sourceHashes.refresh();
					sourceHashes.flush();
				}
				if (targetChanged) {
					if (created) {
						coverCreated(targetAlbum, heldBefore, coverHint, landedAs);
					}
					UpdateTransient.updateTransient(targetAlbum);
					ImageServlet.storeSidecar(targetFolder, json(targetAlbum));
					targetHashes.flush();
				}
			} finally {
				_landingAlbum = null;
				// Both folders changed, and so did the listings above them. Whatever a preview
				// cache holds for an old path is derived data and is simply abandoned.
				_cache.invalidateTree(source);
				_cache.invalidateTree(target);
			}
		}
		return result;
	}

	/**
	 * Decides for every requested name what it is and whether it can move at all.
	 *
	 * <p>
	 * Nothing is renamed here: a request is looked at as a whole first, so that a refusal never
	 * leaves a half-moved album behind.
	 * </p>
	 *
	 * @param groupsTravel
	 *        Whether naming the representative of an {@link ImageGroup} names the whole group, see
	 *        {@link Inboxes#groupsTravel(AlbumInfo)}.
	 */
	private List<Entry> classify(File sourceFolder, File targetFolder, AlbumInfo sourceAlbum,
			boolean targetTakesImages, boolean targetTakesFolders, PlacementRule rule,
			boolean targetIsSpaceRoot, boolean groupsTravel, List<String> names) {
		List<Entry> result = new ArrayList<>(names.size());
		Set<String> seen = new HashSet<>();
		for (String name : names) {
			Entry entry = new Entry(name);
			result.add(entry);

			if (!seen.add(name)) {
				entry._refusal = namedTwice(name);
				continue;
			}
			if (!isPlainName(name)) {
				entry._refusal = notAnEntry(name);
				continue;
			}
			if (targetIsSpaceRoot && name.startsWith(AuthService.HOME_PREFIX)) {
				// The canonical form "~<user>" shadows such a name at the top of a space; an entry
				// moved there could never be addressed again, see issue #49.
				entry._refusal = AuthService.homeNameRefused(name);
				continue;
			}

			File file = new File(sourceFolder, name);
			if (!file.exists()) {
				entry._refusal = notFound(name);
				continue;
			}

			if (file.isDirectory()) {
				if (!targetTakesFolders) {
					// Refused before anything is renamed, exactly as an image is refused a folder
					// of folders, see issue #113.
					entry._refusal = notAFolder(name);
					continue;
				}
				entry._folder = file;
				Entry destination = destination(rule, targetFolder, file);
				entry._destination = destination._destination;
				entry._newName = destination._newName;
				if (new File(entry._destination, name).exists()) {
					entry._refusal = nameTaken(name);
				} else if (isBelow(targetFolder, file)) {
					entry._refusal = intoItself(name);
				}
				continue;
			}

			if (!ResourceCache.isImage(file)) {
				entry._refusal = notAnEntry(name);
				continue;
			}

			ImagePart image = sourceAlbum == null ? null : sourceAlbum.getImageByName().get(name);
			if (image == null) {
				// An image file the album does not describe: its analysis failed, and there is
				// nothing to carry over. It is not an entry of this album.
				entry._refusal = notFound(name);
				continue;
			}
			if (!targetTakesImages) {
				entry._refusal = notAnAlbum(name);
				continue;
			}

			ImageGroup group = image.getGroup();
			if (groupsTravel && group != null && representative(group) == image) {
				// The whole group travels: an album shows a group by its representative, and a
				// group left without it would be a different thing. An inbox shows no group at
				// all, so there one photograph is one photograph, see Inboxes#groupsTravel.
				entry._group = group;
			} else {
				entry._image = image;
			}
		}
		return result;
	}

	/**
	 * Renames a folder within the folder it already lies in, carrying every reference along, see
	 * issue #130.
	 *
	 * <p>
	 * The same same-filesystem rename a move does, with the same refusal at a name that is taken:
	 * nothing is copied, and the sidecar, the <code>.hashes.json</code> and the <code>.vacache</code>
	 * of the folder ride along inside it because they never left it. The one reference that lives
	 * outside the folder is a share link's path, which is rewritten here in the same step, see
	 * {@link ShareStore#rename(String, String, String)}.
	 * </p>
	 *
	 * <p>
	 * Nothing is placed: a folder renamed where it stands stays where it stands, and a rule files
	 * on creation, on a move, and on {@link #place(PathInfo)} — never behind the owner's back.
	 * </p>
	 *
	 * @param folder
	 *        The folder to rename.
	 * @param newName
	 *        Its new name, a plain name and no path.
	 * @param owner
	 *        The space the folder lies in, empty if it lies in none.
	 * @param ownerPath
	 *        The folder's path in that space, the coordinates a share link is stored in.
	 * @throws MoveRefused
	 *         If the folder is gone, the name is no name or a sibling already holds it. Nothing is
	 *         renamed in that case.
	 */
	public void renameFolder(PathInfo folder, String newName, String owner, String ownerPath)
			throws MoveRefused, IOException {
		File dir = folder.toFile();
		if (!dir.isDirectory()) {
			throw new MoveRefused(HttpServletResponse.SC_NOT_FOUND, SOURCE_MISSING);
		}
		if (!isPlainName(newName) || !FolderNames.isLegal(newName)) {
			throw new MoveRefused(HttpServletResponse.SC_BAD_REQUEST, FolderNames.illegalName(newName));
		}
		File parent = dir.getParentFile();
		if (parent == null) {
			throw new MoveRefused(HttpServletResponse.SC_NOT_FOUND, SOURCE_MISSING);
		}
		File target = new File(parent, newName);
		// A rename that only changes the spelling of the name is no clash: on a case-insensitive
		// file system the target "is" the source, and refusing it would freeze every such name.
		if (target.exists() && !target.getCanonicalFile().equals(dir.getCanonicalFile())) {
			throw new MoveRefused(HttpServletResponse.SC_CONFLICT, nameTaken(newName));
		}

		Files.move(dir.toPath(), target.toPath());
		LOG.info("Renamed folder '" + dir + "' to '" + target + "'.");

		ShareStore shares = _auth == null ? null : _auth.getShares();
		if (shares != null) {
			shares.rename(owner, ownerPath, siblingPath(ownerPath, newName));
		}

		// A folder above that is shown by this one names it, and the name has just changed.
		followRename(parent, dir.getName(), newName);

		// The folder is gone under its old name, and the listing above shows the new one.
		_cache.invalidateTree(folder.parent());
	}

	/**
	 * Carries a folder's rename into the choice the folder above it made, see issue #110.
	 *
	 * <p>
	 * A folder of folders is shown by the picture of the child it named, and that name is the
	 * child's name on disk. Issue #130 lets a folder be renamed by writing its properties, so the
	 * name in the sidecar above would be left pointing at nothing — and nothing is what the tile
	 * would then show. The choice is therefore rewritten in the same step, exactly as
	 * {@link ShareStore#rename} rewrites the path of every link on the folder.
	 * </p>
	 *
	 * <p>
	 * Only the one folder directly above is asked, and only when it named exactly this child: a
	 * name is a statement about a child, and a statement about somebody else's child is not this
	 * server's to rewrite. A sidecar that cannot be written leaves the choice dangling, which is
	 * the folder icon and no failure — the rename itself has happened and must not be undone.
	 * </p>
	 */
	private static void followRename(File parent, String oldName, String newName) {
		FolderResource sidecar = ResourceCache.sidecar(parent);
		if (!(sidecar instanceof ListingInfo)) {
			return;
		}
		ListingInfo listing = (ListingInfo) sidecar;
		if (!oldName.equals(listing.getIndex())) {
			return;
		}
		listing.setIndex(newName);
		try {
			ImageServlet.storeSidecar(parent, json(listing));
			LOG.info("The folder '" + parent + "' is shown by '" + newName + "' now.");
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot follow the rename of '" + oldName + "' to '" + newName
				+ "' in the folder above it: " + ex.getMessage(), ex);
		}
	}

	/** The path of the sibling of the given path that carries the given name. */
	public static String siblingPath(String path, String name) {
		int slash = path == null ? -1 : path.lastIndexOf('/');
		return slash < 0 ? name : path.substring(0, slash + 1) + name;
	}

	/**
	 * Renames a directory into the folder it belongs in; everything inside rides along by nature.
	 *
	 * <p>
	 * That folder is the target folder, unless the target files what lands in it by year or month:
	 * then it is the year (or month) folder, which is created here and nowhere else, see
	 * {@link PlacementRule}.
	 * </p>
	 */
	private MoveOutcome moveFolder(Entry entry) throws IOException {
		File destination = entry._destination;
		if (!destination.isDirectory() && !destination.mkdirs()) {
			throw new IOException("Cannot create the folder '" + destination.getAbsolutePath() + "'.");
		}
		File moved = new File(destination, entry._name);
		Files.move(entry._folder.toPath(), moved.toPath());
		LOG.info("Moved folder '" + entry._folder + "' to '" + moved + "'.");
		return outcome(entry._name, entry._newName, "");
	}



	/**
	 * Where a folder of the given name lands below the given target, and how that is reported.
	 *
	 * <p>
	 * The date a rule files by is the cheap one, read from the moved folder's own sidecar and its
	 * name; no image is opened, see {@link AlbumDate#ofFolder(FolderResource, String)}. A folder
	 * that is itself a year or month folder is not filed again, and neither is one nothing says a
	 * date about: it lands where it was sent.
	 * </p>
	 *
	 * @return An {@link Entry} used for nothing but its {@link Entry#_destination} and its
	 *         {@link Entry#_newName}.
	 */
	private static Entry destination(PlacementRule rule, File targetFolder, File folder) {
		String name = folder.getName();
		Entry result = new Entry(name);
		result._destination = targetFolder;
		result._newName = name;

		if (!rule.isActive() || PlacementRule.isPlacementFolder(name)) {
			return result;
		}
		AlbumDate date = AlbumDate.ofFolder(ResourceCache.sidecar(folder), name);
		Path placement = rule.placementFor(targetFolder.toPath(), date);
		if (placement == null) {
			return result;
		}
		result._destination = placement.toFile();
		result._newName = targetFolder.toPath().relativize(placement).toString().replace(File.separatorChar, '/')
			+ "/" + name;
		return result;
	}

	/**
	 * Files everything that is already in the given folder by the folder's own placement rule, see
	 * issue #48.
	 *
	 * <p>
	 * This is the "apply once" of the rule and the only place it reaches backwards: nothing here
	 * ever happens implicitly, at start-up or on a read. Every direct sub-folder is moved into its
	 * year (or month) folder by the very rename a move uses, with the same refusals — a name the
	 * year folder already holds is refused and nothing is overwritten. A folder that is itself a
	 * year or month folder is passed over silently, since it is where it belongs; a folder nothing
	 * says a date about is reported as kept, since a silent no-op is no answer.
	 * </p>
	 *
	 * @param folder
	 *        The folder carrying the rule.
	 * @return One {@link MoveOutcome} per child that was looked at, in the order of their names.
	 * @throws MoveRefused
	 *         If the folder does not exist or cannot be listed.
	 */
	public MoveResult place(PathInfo folder) throws MoveRefused, IOException {
		File dir = folder.toFile();
		if (!dir.isDirectory()) {
			throw new MoveRefused(HttpServletResponse.SC_NOT_FOUND, SOURCE_MISSING);
		}
		File[] children = dir.listFiles(f -> f.isDirectory() && !LibraryFiles.isIgnored(f));
		if (children == null) {
			throw new MoveRefused(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, FOLDER_UNREADABLE);
		}
		Arrays.sort(children, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));

		PlacementRule rule = PlacementRule.of(dir);
		MoveResult result = MoveResult.create();
		boolean stopped = false;
		try {
			for (File child : children) {
				String name = child.getName();
				if (!rule.isActive()) {
					// Not a silent no-op: every child is named, and so is the reason.
					result.addOutcome(outcome(name, "", NO_RULE));
					continue;
				}
				if (PlacementRule.isPlacementFolder(name)) {
					// A year or month folder is where it belongs; it is no failure.
					continue;
				}
				if (stopped) {
					result.addOutcome(outcome(name, "", ABANDONED));
					continue;
				}

				Entry entry = destination(rule, dir, child);
				entry._folder = child;
				if (dir.equals(entry._destination)) {
					result.addOutcome(outcome(name, "", KEPT_NO_DATE));
					continue;
				}
				if (new File(entry._destination, name).exists()) {
					result.addOutcome(outcome(name, "", nameTaken(name)));
					continue;
				}

				try {
					result.addOutcome(moveFolder(entry));
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Cannot file '" + name + "' in '" + dir + "': " + ex.getMessage(), ex);
					result.addOutcome(outcome(name, "", failed(ex.getMessage())));
					stopped = true;
				}
			}
		} finally {
			// Whatever moved changed this folder and the year folders below it.
			_cache.invalidateTree(folder);
		}
		return result;
	}


	/**
	 * The album files land in right now, see {@link #liveNameOf(HashCache, String)}: a file of it
	 * rated as trash holds no contents a file arriving would duplicate.
	 */
	private AlbumInfo _landingAlbum;

	/**
	 * The name of the reference of the target album a rescue is replacing right now, see
	 * {@link #rescue(PathInfo, PathInfo, List)}: free for the file that takes its place.
	 */
	private String _freed;

	/**
	 * A photograph to move into an album that references it, in place of that reference, see
	 * {@link #rescue(PathInfo, PathInfo, List)}.
	 */
	public static final class Rescue {

		final String _name;

		final String _reference;

		final Integer _rating;

		/**
		 * Creates a {@link Rescue}.
		 *
		 * @param name
		 *        The name of the photograph in the source folder.
		 * @param reference
		 *        The name of the reference of the target album it replaces.
		 * @param rating
		 *        The rating it has in the target album, <code>null</code> for the one it has: the
		 *        rating as trash that made a write a delete is no statement about the album it
		 *        goes to, see {@link ReferencedOriginals}.
		 */
		public Rescue(String name, String reference, Integer rating) {
			_name = name;
			_reference = reference;
			_rating = rating;
		}
	}

	/**
	 * Moves photographs of the source album into the target album, each in the place of a reference
	 * of the target album to it, see issue #307 and {@link ReferencedOriginals}.
	 *
	 * <p>
	 * The move of {@link #move(PathInfo, PathInfo, List)} for one photograph at a time and never its
	 * group: the file is renamed, its part travels with everything the album knows about it &mdash;
	 * rating, privacy, crop, turn, time, place, faces &mdash;, its hash entry, its raw companion, its
	 * analysis record and its content description ride along. What is different is where it lands:
	 * under the name of the reference it replaces (unique among the album's files, see
	 * {@link ImageServlet#freeName(File, String, String)}), in the reference's place in the album's
	 * order, with the reference's labels and, where the reference has a description of its own, that
	 * description. Where the target already holds the very contents as a file of its own, the file
	 * is set aside as a duplicate and the reference goes all the same: the album shows its own copy.
	 * </p>
	 *
	 * <p>
	 * No right is asked here: the caller decides, see {@link ReferencedOriginals}.
	 * </p>
	 *
	 * @return The name every rescued photograph has in the target folder now (the one of the copy
	 *         the target held, where it was set aside), by its name in the source folder; a
	 *         photograph or reference that is no longer there is absent.
	 */
	public Map<String, String> rescue(PathInfo source, PathInfo target, List<Rescue> rescues) throws IOException {
		File sourceFolder = source.toFile();
		File targetFolder = target.toFile();
		Path space = source.getBasePath();
		AlbumInfo sourceAlbum = albumOf(source);
		AlbumInfo targetAlbum = albumOf(target);
		Map<String, String> result = new java.util.LinkedHashMap<>();
		HashCache sourceHashes = new HashCache(sourceFolder);
		HashCache targetHashes = new HashCache(targetFolder);
		boolean changed = false;
		_landingAlbum = targetAlbum;
		try {
			for (Rescue rescue : rescues) {
				UpdateTransient.updateTransient(sourceAlbum);
				ImagePart image = sourceAlbum.getImageByName().get(rescue._name);
				ImagePart reference = PhotoCollections.referenceNamed(targetAlbum, rescue._reference);
				if (image == null || PhotoCollections.isReference(image) || reference == null
					|| !new File(sourceFolder, rescue._name).isFile()) {
					LOG.warning("Cannot move '" + rescue._name + "' of '" + sourceFolder + "' in the place of '"
						+ rescue._reference + "' of '" + targetFolder + "': one of them is gone.");
					continue;
				}
				File raw = RawPairs.companion(sourceFolder, image);
				Landing landing;
				_freed = rescue._reference;
				try {
					landing = landFile(rescue._name, rescue._reference, sourceFolder, targetFolder, targetHashes,
						sourceHashes, space);
				} finally {
					_freed = null;
				}
				changed = true;
				detach(sourceAlbum, image);
				carryRaw(image, raw, landing, targetAlbum, targetFolder, targetHashes, sourceHashes, space);
				if (landing._existing != null) {
					int at = removeReference(targetAlbum, reference);
					ThumbnailInfo cover = targetAlbum.getIndexPicture();
					if (cover != null && rescue._reference.equals(cover.getImage())) {
						cover.setImage(landing._existing);
					}
					if (at < 0) {
						LOG.warning("The reference '" + rescue._reference + "' of '" + targetFolder + "' was gone.");
					}
					result.put(rescue._name, landing._existing);
					continue;
				}
				image.setName(landing._newName);
				image.setGroup(null);
				if (rescue._rating != null) {
					image.setRating(rescue._rating.intValue());
				}
				if (image.getRating() == de.haumacher.imageServer.auth.Ratings.TRASH) {
					// A photograph an older build rated as trash is kept because it is still shown:
					// it never arrives in the trash of the album that keeps it.
					image.setRating(0);
				}
				takePlace(targetAlbum, image, reference);
				result.put(rescue._name, landing._newName);
			}
		} finally {
			_landingAlbum = null;
			try {
				if (changed) {
					repairIndexPicture(sourceAlbum);
					UpdateTransient.updateTransient(sourceAlbum);
					ImageServlet.storeSidecar(sourceFolder, json(sourceAlbum));
					sourceHashes.refresh();
					sourceHashes.flush();
					UpdateTransient.updateTransient(targetAlbum);
					ImageServlet.storeSidecar(targetFolder, json(targetAlbum));
					targetHashes.flush();
				}
			} finally {
				_cache.invalidateTree(source);
				_cache.invalidateTree(target);
			}
		}
		return result;
	}

	/**
	 * Puts the given photograph, just arrived in the given album under its new name, in the place of
	 * the album's reference to it, see issue #307: in the reference's place in the album's order,
	 * with the reference's labels and, where the reference has a description of its own, that
	 * description (the photograph's own stays otherwise); the album's cover follows. Every other
	 * reference of the album to the same contents goes too: an album never shows one photograph
	 * twice.
	 */
	static void takePlace(AlbumInfo album, ImagePart image, ImagePart reference) {
		String hash = reference.getRef().getHash();
		int at = removeReference(album, reference);
		if (!reference.getComment().isEmpty()) {
			// The album's own description of the photograph is the file's there now; without one, the
			// photograph's own stays.
			image.setComment(reference.getComment());
		}
		List<de.haumacher.imageServer.shared.model.LabelName> labels = new ArrayList<>();
		for (de.haumacher.imageServer.shared.model.LabelName label : reference.getLabels()) {
			labels.add(de.haumacher.imageServer.shared.model.LabelName.create().setName(label.getName()));
		}
		image.setLabels(labels);
		List<AlbumPart> parts = album.getParts();
		parts.add(at < 0 ? parts.size() : Math.min(at, parts.size()), image);
		ThumbnailInfo cover = album.getIndexPicture();
		if (cover != null && reference.getName().equals(cover.getImage())) {
			cover.setImage(image.getName());
		}
		for (ImagePart other : PhotoCollections.references(album)) {
			if (hash.equals(other.getRef().getHash())) {
				removeReference(album, other);
				if (cover != null && other.getName().equals(cover.getImage())) {
					cover.setImage(image.getName());
				}
			}
		}
	}

	/** The first reference of the given album to the given contents, <code>null</code> for none. */
	static ImagePart referenceTo(AlbumInfo album, String hash) {
		if (hash == null) {
			return null;
		}
		for (ImagePart reference : PhotoCollections.references(album)) {
			if (hash.equals(reference.getRef().getHash())) {
				return reference;
			}
		}
		return null;
	}

	/**
	 * The name of a file of the given folder holding the given contents that is not rated as trash in
	 * the album files land in, <code>null</code> for none.
	 *
	 * <p>
	 * A file in the trash is on its way out, see issue #307: a photograph arriving beside it is no
	 * duplicate of it, and setting it aside would throw it into the trash with that file.
	 * </p>
	 */
	private String liveNameOf(HashCache hashes, String hash) throws IOException {
		for (Map.Entry<String, String> entry : hashes.hashByName().entrySet()) {
			if (!hash.equals(entry.getValue())) {
				continue;
			}
			ImagePart part = _landingAlbum == null ? null : Crops.findImage(_landingAlbum, entry.getKey());
			if (part != null && !PhotoCollections.isReference(part)
				&& part.getRating() <= de.haumacher.imageServer.auth.Ratings.TRASH) {
				continue;
			}
			return entry.getKey();
		}
		return null;
	}

	/** The hash of the named file of the given folder, as its sidecar knows it or hashed now. */
	private static String hashOf(HashCache hashes, File folder, String name) throws IOException {
		String hash = hashes.hashByName().get(name);
		return hash != null ? hash : HashCache.sha256(new File(folder, name));
	}

	/**
	 * Takes the given reference out of the given album.
	 *
	 * @return The index in the album's parts where it stood, or where the group it stood in ends;
	 *         <code>-1</code> where the album does not hold it.
	 */
	private static int removeReference(AlbumInfo album, ImagePart reference) {
		List<AlbumPart> parts = album.getParts();
		for (int n = 0; n < parts.size(); n++) {
			AlbumPart part = parts.get(n);
			if (part == reference) {
				parts.remove(n);
				return n;
			}
			if (part instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) part;
				if (!group.getImages().contains(reference)) {
					continue;
				}
				ImagePart head = representative(group);
				group.removeImage(reference);
				List<ImagePart> members = group.getImages();
				if (members.isEmpty()) {
					parts.remove(n);
					return n;
				}
				if (members.size() == 1) {
					ImagePart single = members.get(0);
					single.setGroup(null);
					parts.set(n, single);
				} else {
					group.setRepresentative(Math.max(0, members.indexOf(head)));
				}
				return n + 1;
			}
		}
		return -1;
	}

	/**
	 * Moves a single image out of the source album and inserts it by its date into the target album,
	 * see {@link #insertByDate(AlbumInfo, AlbumPart)}.
	 */
	private MoveOutcome moveImage(Entry entry, AlbumInfo sourceAlbum, AlbumInfo targetAlbum, File sourceFolder,
			File targetFolder, HashCache targetHashes, HashCache sourceHashes, Path space,
			Map<String, String> landedAs) throws IOException {
		ImagePart image = entry._image;
		File raw = RawPairs.companion(sourceFolder, image);
		// A photograph the target shows as a reference takes that reference's place, under its name,
		// see issue #307.
		ImagePart reference = referenceTo(targetAlbum, hashOf(sourceHashes, sourceFolder, image.getName()));
		Landing landing = landInPlaceOf(reference, image.getName(), sourceFolder, targetFolder, targetHashes,
			sourceHashes, space);
		landedAs.put(image.getName(), landing.name());

		detach(sourceAlbum, image);
		carryRaw(image, raw, landing, targetAlbum, targetFolder, targetHashes, sourceHashes, space);
		if (landing._existing != null) {
			return outcome(entry._name, landing._existing, duplicate(landing._existing));
		}

		image.setName(landing._newName);
		image.setGroup(null);
		if (reference != null) {
			takePlace(targetAlbum, image, reference);
			return outcome(entry._name, landing._newName, TOOK_REFERENCE_PLACE);
		}
		insertByDate(targetAlbum, image);
		return outcome(entry._name, landing._newName, "");
	}

	/**
	 * Lands the named file in the target folder, under the name of the given reference of the target
	 * album where there is one (see {@link ImageServlet#freeName(File, String, String)}), else under
	 * its own name.
	 */
	private Landing landInPlaceOf(ImagePart reference, String name, File sourceFolder, File targetFolder,
			HashCache targetHashes, HashCache sourceHashes, Path space) throws IOException {
		if (reference == null) {
			return landFile(name, sourceFolder, targetFolder, targetHashes, sourceHashes, space);
		}
		_freed = reference.getName();
		try {
			return landFile(name, reference.getName(), sourceFolder, targetFolder, targetHashes, sourceHashes, space);
		} finally {
			_freed = null;
		}
	}

	/**
	 * The member of a group arriving in the given album replaces the album's reference to it, see
	 * {@link #takePlace(AlbumInfo, ImagePart, ImagePart)}: it keeps its place in its group, and takes
	 * the reference's labels and description, while the reference (and every other reference to the
	 * same contents) goes.
	 */
	private static void replaceReference(AlbumInfo album, ImagePart member, ImagePart reference) {
		String hash = reference.getRef().getHash();
		if (!reference.getComment().isEmpty()) {
			member.setComment(reference.getComment());
		}
		List<de.haumacher.imageServer.shared.model.LabelName> labels = new ArrayList<>();
		for (de.haumacher.imageServer.shared.model.LabelName label : reference.getLabels()) {
			labels.add(de.haumacher.imageServer.shared.model.LabelName.create().setName(label.getName()));
		}
		member.setLabels(labels);
		ThumbnailInfo cover = album.getIndexPicture();
		for (ImagePart other : PhotoCollections.references(album)) {
			if (hash.equals(other.getRef().getHash())) {
				removeReference(album, other);
				if (cover != null && other.getName().equals(cover.getImage())) {
					cover.setImage(member.getName());
				}
			}
		}
	}

	/**
	 * Moves the raw companion of a photograph that has just landed after it, see issue #191: a raw
	 * and its JPEG are one photograph, and what acts on the one acts on both.
	 *
	 * <p>
	 * The raw asks for the base name the photograph landed under (<code>IMG_1-2.JPG</code> takes
	 * <code>IMG_1-2.CR2</code> along), so the pair stays a pair, and it is a file of its own for
	 * everything else: its own hash, its own free name, and set aside on its own where the target
	 * already holds its contents. The part names the companion as it landed, or none where it was
	 * set aside; a photograph that was itself set aside hands a raw that landed to the target's copy
	 * of it where that one has none.
	 * </p>
	 */
	private void carryRaw(ImagePart image, File raw, Landing landing, AlbumInfo targetAlbum, File targetFolder,
			HashCache targetHashes, HashCache sourceHashes, Path space) throws IOException {
		if (raw == null) {
			image.setRaw("");
			return;
		}
		String photoName = landing._existing != null ? landing._existing : landing._newName;
		String wanted = RawPairs.withBaseOf(raw.getName(), photoName);
		Landing rawLanding = landFile(raw.getName(), wanted, raw.getParentFile(), targetFolder, targetHashes,
			sourceHashes, space);
		String landed = rawLanding._existing != null ? "" : rawLanding._newName;
		if (landing._existing != null) {
			image.setRaw("");
			UpdateTransient.updateTransient(targetAlbum);
			ImagePart copy = targetAlbum.getImageByName().get(landing._existing);
			if (copy != null && copy.getRaw().isEmpty() && !landed.isEmpty()) {
				copy.setRaw(landed);
			}
			return;
		}
		image.setRaw(landed);
	}

	/**
	 * Moves a whole group: every member file, and the {@link ImageGroup} as one part of the target
	 * album.
	 */
	private MoveOutcome moveGroup(Entry entry, List<Entry> entries, AlbumInfo sourceAlbum, AlbumInfo targetAlbum,
			File sourceFolder, File targetFolder, HashCache targetHashes, HashCache sourceHashes, Path space,
			Map<String, String> landedAs) throws IOException {
		ImageGroup group = entry._group;
		ImagePart head = representative(group);
		List<ImagePart> members = new ArrayList<>(group.getImages());

		List<ImagePart> moved = new ArrayList<>(members.size());
		String headName = null;
		int setAside = 0;
		int replaced = 0;
		for (ImagePart member : members) {
			String asked = member.getName();
			File raw = RawPairs.companion(sourceFolder, member);
			// A member the target shows as a reference replaces it, see issue #307.
			ImagePart reference = referenceTo(targetAlbum, hashOf(sourceHashes, sourceFolder, asked));
			Landing landing =
				landInPlaceOf(reference, asked, sourceFolder, targetFolder, targetHashes, sourceHashes, space);
			landedAs.put(asked, landing.name());
			carryRaw(member, raw, landing, targetAlbum, targetFolder, targetHashes, sourceHashes, space);
			if (landing._existing == null && reference != null) {
				member.setName(landing._newName);
				replaceReference(targetAlbum, member, reference);
				replaced++;
			}
			if (landing._existing != null) {
				setAside++;
				group.removeImage(member);
				if (member == head) {
					headName = landing._existing;
				}
			} else {
				member.setName(landing._newName);
				moved.add(member);
				if (member == head) {
					headName = landing._newName;
				}
			}
			// A name of this request that is a member of this group has been dealt with here.
			for (Entry other : entries) {
				if (other != entry && other._refusal == null && other._movedWithGroup == null
					&& asked.equals(other._name)) {
					other._movedWithGroup = landing._existing != null ? landing._existing : landing._newName;
					other._image = null;
					other._group = null;
				}
			}
		}

		// The group part, and every member that an earlier entry of this request had already
		// taken out of it and left in the album as a plain part.
		sourceAlbum.removePart(group);
		for (ImagePart member : members) {
			sourceAlbum.removePart(member);
		}
		if (moved.size() > 1) {
			group.setImages(moved);
			group.setRepresentative(Math.max(0, moved.indexOf(head)));
			insertByDate(targetAlbum, group);
		} else if (moved.size() == 1) {
			// A group of one is not a group.
			ImagePart single = moved.get(0);
			single.setGroup(null);
			insertByDate(targetAlbum, single);
		}

		String message = setAside == 0 ? "" : membersSetAside(setAside);
		if (replaced > 0) {
			message = message.isEmpty() ? TOOK_REFERENCE_PLACE : message + " " + TOOK_REFERENCE_PLACE;
		}
		return outcome(entry._name, headName == null ? "" : headName, message);
	}

	/**
	 * Inserts a part that has just been moved into the given album at its date, see issue #262.
	 *
	 * <p>
	 * The album's order is the author's, possibly arranged by hand and divided by {@link Heading}s,
	 * and it is never resorted: only the new part takes a place in it. That place is directly after
	 * the last photograph (or group, dated by its representative) whose date is at or before the
	 * part's own, so that a photograph of the evening of day 1 lands before the heading "Day 2", not
	 * behind it. Equal dates keep what is there first; since the parts of one request are inserted
	 * in the order asked, each behind the ones inserted before it, those keep the order asked too.
	 * </p>
	 *
	 * <p>
	 * A part earlier than every dated photograph goes before the first photograph of the album, that
	 * is behind the headings that lead the album, which are the title of what follows. A part
	 * without a date, an album that holds no dated photograph at all (nothing to place it against,
	 * an empty album included) and an album with nothing but headings take it at the end, as every
	 * move did before.
	 * </p>
	 *
	 * <p>
	 * "The last one at or before" is a sensible meaning in an album sorted by hand, too, where the
	 * dates do not rise along the order: the part lands behind the point the author's sequence last
	 * reached its time, so it never splits a stretch the author placed after something later than
	 * it, and in an album sorted by date — the common case, and the one the analysis derives for a
	 * folder without a sidecar — it is exactly the sorted place. An undated photograph of the album
	 * is passed over, since it says nothing about where the time is.
	 * </p>
	 *
	 * @param album
	 *        The album to insert into.
	 * @param part
	 *        The {@link ImagePart} or {@link ImageGroup} to insert; it must not be a part of the
	 *        album yet.
	 */
	static void insertByDate(AlbumInfo album, AlbumPart part) {
		List<AlbumPart> parts = album.getParts();
		long date = dateOf(part);
		if (date == 0) {
			parts.add(part);
			return;
		}

		int firstImage = -1;
		boolean anyDated = false;
		for (int n = 0, cnt = parts.size(); n < cnt; n++) {
			AlbumPart existing = parts.get(n);
			if (existing instanceof Heading) {
				continue;
			}
			if (firstImage < 0) {
				firstImage = n;
			}
			long existingDate = dateOf(existing);
			if (existingDate != 0) {
				anyDated = true;
			}
		}
		if (!anyDated) {
			parts.add(part);
			return;
		}

		for (int n = parts.size() - 1; n >= 0; n--) {
			AlbumPart existing = parts.get(n);
			if (existing instanceof Heading) {
				continue;
			}
			long existingDate = dateOf(existing);
			if (existingDate != 0 && existingDate <= date) {
				parts.add(n + 1, part);
				return;
			}
		}
		parts.add(firstImage, part);
	}

	/**
	 * The date the given part is placed by, see {@link #insertByDate(AlbumInfo, AlbumPart)}:
	 * <code>0</code> for none, a group dated by its representative, a heading by nothing.
	 */
	private static long dateOf(AlbumPart part) {
		if (part instanceof ImagePart) {
			return ((ImagePart) part).getDate();
		}
		if (part instanceof ImageGroup) {
			ImagePart head = representative((ImageGroup) part);
			return head == null ? 0 : head.getDate();
		}
		return 0;
	}

	/** Where a single file ended up, see {@link MoveService#landFile}. */
	private static final class Landing {

		/** The name the file has in the target folder now, <code>null</code> if it was set aside. */
		final String _newName;

		/** The name of the file at the target that already held these contents, else <code>null</code>. */
		final String _existing;

		Landing(String newName, String existing) {
			_newName = newName;
			_existing = existing;
		}

		/** The name the file's contents have in the target folder now, whether it landed or not. */
		String name() {
			return _existing != null ? _existing : _newName;
		}
	}

	/**
	 * Renames one file into the target folder, or sets it aside when the target already holds its
	 * contents.
	 *
	 * <p>
	 * Both conflicts follow the rules an upload follows (issue #29): the same contents under any
	 * name are a duplicate, and the same name with different contents gets a free name, computed
	 * by the very method the upload uses, see {@link ImageServlet#freeName(File, String)}.
	 * </p>
	 */
	private Landing landFile(String name, File sourceFolder, File targetFolder, HashCache targetHashes,
			HashCache sourceHashes, Path space) throws IOException {
		return landFile(name, name, sourceFolder, targetFolder, targetHashes, sourceHashes, space);
	}

	/**
	 * Renames one file into the target folder under the given wanted name (or a free one beside it),
	 * see {@link #landFile(String, File, File, HashCache, HashCache, Path)}.
	 */
	private Landing landFile(String name, String wanted, File sourceFolder, File targetFolder,
			HashCache targetHashes, HashCache sourceHashes, Path space) throws IOException {
		File file = new File(sourceFolder, name);
		String hash = sourceHashes.hashByName().get(name);
		if (hash == null) {
			// The source folder was never uploaded to; the file is hashed now.
			hash = HashCache.sha256(file);
		}
		// Attribution travels with the photo: it is the source folder's record, carried into the
		// target's, see issue #53.
		HashCache.Attribution attribution = sourceHashes.attributionOf(name);
		if (_outOfInbox > 0) {
			// Out of the inbox: who moved it and when, see issue #268.
			attribution = attribution.movedOutOfInbox(_mover, _moverLabel, _outOfInbox);
		}

		String existing = liveNameOf(targetHashes, hash);
		if (existing != null) {
			File aside = setAside(space, hash, name);
			Files.move(file.toPath(), aside.toPath());
			LOG.info("The target already holds '" + name + "' as '" + existing + "'; set aside as '" + aside + "'.");
			return new Landing(null, existing);
		}

		File moved = ImageServlet.freeName(targetFolder, wanted, _freed);
		Files.move(file.toPath(), moved.toPath());
		targetHashes.put(moved, hash, attribution);
		// What the background found the file to be travels with it, broken or not, see issue #253.
		de.haumacher.imageServer.cache.AnalysisRecord.carry(sourceFolder, name, moved);
		// And so does its description for the content search, see issue #257.
		de.haumacher.imageServer.content.ContentCache.carry(sourceFolder, hash, targetFolder);
		LOG.info("Moved image '" + file + "' to '" + moved + "'.");
		return new Landing(moved.getName(), null);
	}

	/**
	 * The file a duplicate is set aside as; nothing there is ever overwritten either.
	 *
	 * @param space
	 *        The root of the space the move happens in, see {@link #DUPLICATES_FOLDER}.
	 */
	private File setAside(Path space, String hash, String name) throws IOException {
		File folder = space.resolve(UserStore.DIRECTORY_NAME).resolve(DUPLICATES_FOLDER).toFile();
		if (!folder.isDirectory() && !folder.mkdirs()) {
			throw new IOException("Cannot create the folder for duplicates: " + folder.getAbsolutePath());
		}
		return ImageServlet.freeName(folder, hash + "-" + name);
	}

	/**
	 * Takes the given image out of the album it is a part of.
	 *
	 * <p>
	 * An image that belongs to a group leaves the group; a group left with a single image is
	 * replaced by that image, since a group of one is not a group.
	 * </p>
	 */
	public static void detach(AlbumInfo album, ImagePart image) {
		ImageGroup group = image.getGroup();
		if (group == null) {
			album.removePart(image);
			return;
		}

		ImagePart head = representative(group);
		group.removeImage(image);
		image.setGroup(null);

		List<ImagePart> rest = group.getImages();
		if (rest.isEmpty()) {
			album.removePart(group);
		} else if (rest.size() == 1) {
			ImagePart single = rest.get(0);
			single.setGroup(null);
			album.getParts().set(album.getParts().indexOf(group), single);
		} else {
			group.setRepresentative(Math.max(0, rest.indexOf(head)));
		}
	}

	/**
	 * Points the album's index picture at an image that is still there.
	 *
	 * <p>
	 * The cover of an album whose cover was moved away is the album's first best-rated image (see
	 * {@link PrivacyFilter#bestImage(Iterable)}), the rule a cover is chosen by for an album that
	 * is created for a move too (issue #277, {@link #coverCreated(AlbumInfo, Set, String, Map)});
	 * an album with no image left has no cover. The new cover is framed in the frame its image is displayed in and
	 * says so, see {@link ThumbnailInfo#getOrientation()} and issue #115.
	 * </p>
	 */
	static void repairIndexPicture(AlbumInfo album) {
		ThumbnailInfo indexPicture = album.getIndexPicture();
		if (indexPicture == null) {
			return;
		}
		UpdateTransient.updateTransient(album);
		if (album.getImageByName().containsKey(indexPicture.getImage())) {
			return;
		}
		ImagePart best = PrivacyFilter.bestImage(album.getParts());
		album.setIndexPicture(best == null ? null : PrivacyFilter.thumbnail(best));
	}

	/**
	 * Gives an album that was created for this move its cover, see issues #273 and #277.
	 *
	 * <p>
	 * The rule of {@link PrivacyFilter#createdCover(Iterable, ImagePart)} among the parts that
	 * landed (every part of the album not in <code>heldBefore</code>, in the album's order), each
	 * named by the name it landed under. The hint names the photograph as it was named in the
	 * source folder and is followed to the name it landed with after a collision, or — where it
	 * was set aside because the album already held the identical file — to the copy that is there.
	 * The cover is framed by {@link PrivacyFilter#thumbnail(ImagePart)}, the frame of issue #115
	 * measured on the picture as it is shown, its crop applied (#212); the crop's region itself is
	 * derived on every read and never stored, see {@link Crops#withRegion(AlbumInfo,
	 * ThumbnailInfo)}.
	 * </p>
	 *
	 * <p>
	 * An album that has a live cover keeps it: a choice is never overridden.
	 * </p>
	 *
	 * @param heldBefore
	 *        The parts the album held before the move, compared by identity.
	 * @param coverHint
	 *        The source name of the photograph the move was opened on, <code>null</code> or empty
	 *        for none.
	 * @param landedAs
	 *        Where every photograph of the request ended up, by its name in the source folder.
	 */
	private static void coverCreated(AlbumInfo album, Set<AlbumPart> heldBefore, String coverHint,
			Map<String, String> landedAs) {
		UpdateTransient.updateTransient(album);
		ThumbnailInfo indexPicture = album.getIndexPicture();
		if (indexPicture != null && album.getImageByName().containsKey(indexPicture.getImage())) {
			return;
		}
		List<AlbumPart> landed = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (!heldBefore.contains(part)) {
				landed.add(part);
			}
		}
		String hintedName = coverHint == null || coverHint.isEmpty() ? null : landedAs.get(coverHint);
		ImagePart hinted = hintedName == null ? null : album.getImageByName().get(hintedName);
		ImagePart cover = PrivacyFilter.createdCover(landed, hinted);
		if (cover != null) {
			album.setIndexPicture(PrivacyFilter.thumbnail(cover));
		}
	}

	/** The image an {@link ImageGroup} is shown by, <code>null</code> if it has none. */
	private static ImagePart representative(ImageGroup group) {
		List<ImagePart> images = group.getImages();
		int index = group.getRepresentative();
		if (images.isEmpty()) {
			return null;
		}
		return images.get(index >= 0 && index < images.size() ? index : 0);
	}

	/**
	 * The album the given folder is described by.
	 *
	 * <p>
	 * Read the way the servlet reads it, so that a move sees the same album a <code>GET</code>
	 * would answer with. A folder that holds neither images nor a sidecar is described as a
	 * listing; the album a move needs there is the one the loader would make up as soon as the
	 * first image lands, see {@link ResourceCache#genericAlbum(PathInfo)}.
	 * </p>
	 */
	private AlbumInfo albumOf(PathInfo path) {
		Resource resource = _cache.lookup(path);
		if (resource instanceof AlbumInfo) {
			return (AlbumInfo) resource;
		}
		return ResourceCache.genericAlbum(path);
	}

	/**
	 * Whether every named entry of the source folder was contributed by the given subject, see
	 * issue #53.
	 *
	 * <p>
	 * This is what lets a contributor take their own contribution back out of an album they may
	 * add to but not edit. It is deliberately narrow:
	 * </p>
	 * <ul>
	 * <li>Only image files count. A folder, a link entry or a name the album does not know is
	 * nobody's contribution, so a request naming one is not a taking-back at all.</li>
	 * <li>A named image that heads a group carries the whole group along, so every member of that
	 * group must be the caller's too — a move must never carry somebody else's photo out on the
	 * back of one's own.</li>
	 * <li>All or nothing: one foreign name refuses the whole request, and nothing moves. A
	 * half-executed move would leave the album in a state nobody asked for.</li>
	 * </ul>
	 *
	 * <p>
	 * Nothing is written and nothing is hashed here: the source folder's hash sidecar is read as
	 * it stands, see {@link HashCache#recorded(File)}.
	 * </p>
	 *
	 * @param source
	 *        The folder the entries would be taken out of.
	 * @param names
	 *        The names the request asks to move.
	 * @param subject
	 *        Who is asking, see
	 *        {@link de.haumacher.imageServer.auth.AuthService.Caller#subject()}.
	 */
	public boolean contributedBy(PathInfo source, List<String> names, String subject) {
		if (subject == null || subject.isEmpty() || names.isEmpty()) {
			return false;
		}
		File sourceFolder = source.toFile();
		if (!sourceFolder.isDirectory()) {
			return false;
		}
		Map<String, HashCache.Attribution> recorded = HashCache.recorded(sourceFolder);
		AlbumInfo album = albumOf(source);
		for (String name : names) {
			if (!isPlainName(name)) {
				return false;
			}
			File file = new File(sourceFolder, name);
			if (!file.isFile() || !ResourceCache.isImage(file)) {
				return false;
			}
			ImagePart image = album == null ? null : album.getImageByName().get(name);
			if (image == null) {
				return false;
			}
			ImageGroup group = image.getGroup();
			if (group != null && representative(group) == image) {
				for (ImagePart member : group.getImages()) {
					if (!contributed(recorded, member.getName(), subject)) {
						return false;
					}
				}
			} else if (!contributed(recorded, name, subject)) {
				return false;
			}
		}
		return true;
	}

	private static boolean contributed(Map<String, HashCache.Attribution> recorded, String name, String subject) {
		HashCache.Attribution attribution = recorded.get(name);
		return attribution != null && subject.equals(attribution.getContributor());
	}

	/** Whether the given file is the given folder itself or lies below it. */
	private static boolean isBelow(File file, File folder) {
		Path candidate = file.getAbsoluteFile().toPath().normalize();
		Path root = folder.getAbsoluteFile().toPath().normalize();
		return candidate.startsWith(root);
	}

	/** Whether the given name addresses a single entry of a folder. */
	private static boolean isPlainName(String name) {
		if (name == null || name.isEmpty() || LibraryFiles.isIgnored(name)) {
			// A name starting with a dot is the server's own business (the sidecars, the user
			// store, the upload staging area), and the litter of another system is nobody's: no
			// album entry either, see LibraryFiles.
			return false;
		}
		return name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.equals("..");
	}

	private static MoveOutcome outcome(String name, String newName, String message) {
		return MoveOutcome.create().setName(name).setNewName(newName).setMessage(message);
	}

	/**
	 * The given folder resource as the bytes of its sidecar.
	 *
	 * <p>
	 * What the server derived on the way out is dropped on the way in: an album carries an
	 * effective date only in an answer, never in a file, see
	 * {@link AlbumDate#clearDerived(FolderResource)}.
	 * </p>
	 */
	static byte[] json(FolderResource resource) throws IOException {
		AlbumDate.clearDerived(resource);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer,
			StandardCharsets.UTF_8)))) {
			resource.writeTo(json);
		}
		return buffer.toByteArray();
	}

}
