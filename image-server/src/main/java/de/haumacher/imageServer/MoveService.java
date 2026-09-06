/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
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
import java.util.HashSet;
import java.util.List;
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
 * intact and is appended to the target's, and its entry in the {@link HashCache} moves from the
 * one folder's sidecar to the other's. Previews are derived data: whatever {@link PreviewCache}
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

	/** The message of an image whose contents the target folder already held. */
	public static String duplicate(String existing) {
		return "The target folder already holds this photo as '" + existing + "'; the file was set aside in '"
			+ DUPLICATES_PATH + "'.";
	}

	/** The message of a group some of whose images the target folder already held. */
	public static String membersSetAside(int count) {
		return count + " image(s) of this group were already in the target folder; those files were set aside in '"
			+ DUPLICATES_PATH + "'.";
	}

	/** The message a name is refused with that the same request already asked to move. */
	public static String namedTwice(String name) {
		return "'" + name + "' is named more than once in this request; it moves only once.";
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

	private final Path _basePath;

	private final ResourceCache _cache;

	/**
	 * Creates a {@link MoveService}.
	 *
	 * @param basePath
	 *        The base folder of the server (not the caller's space): the duplicates folder lives
	 *        below it, so that a set-aside file never lands in an album.
	 * @param cache
	 *        The cache that must forget what the move changed.
	 */
	public MoveService(Path basePath, ResourceCache cache) {
		_basePath = basePath;
		_cache = cache;
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

		AlbumInfo sourceAlbum = albumOf(source);
		AlbumInfo targetAlbum = albumOf(target);
		// A folder that describes itself as a folder of folders has no place for an image; its
		// sidecar is the owner's statement and this server does not overrule it.
		FolderResource targetSidecar = ResourceCache.sidecar(targetFolder);
		boolean targetTakesImages = !(targetSidecar instanceof ListingInfo);

		// What lands in a folder is filed by the folder's rule, see issue #48. Only folders are:
		// an image lands in an album, never in a folder of folders, and is refused above.
		PlacementRule rule = PlacementRule.of(targetSidecar);

		List<Entry> entries = classify(sourceFolder, targetFolder, sourceAlbum, targetTakesImages, rule, names);

		MoveResult result = MoveResult.create();
		HashCache sourceHashes = new HashCache(sourceFolder);
		HashCache targetHashes = new HashCache(targetFolder);
		boolean sourceChanged = false;
		boolean targetChanged = false;
		boolean stopped = false;
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
							targetFolder, targetHashes, sourceHashes));
						sourceChanged = true;
						targetChanged = true;
					} else {
						result.addOutcome(moveImage(entry, sourceAlbum, targetAlbum, sourceFolder, targetFolder,
							targetHashes, sourceHashes));
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
					UpdateTransient.updateTransient(targetAlbum);
					ImageServlet.storeSidecar(targetFolder, json(targetAlbum));
					targetHashes.flush();
				}
			} finally {
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
	 */
	private static List<Entry> classify(File sourceFolder, File targetFolder, AlbumInfo sourceAlbum,
			boolean targetTakesImages, PlacementRule rule, List<String> names) {
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

			File file = new File(sourceFolder, name);
			if (!file.exists()) {
				entry._refusal = notFound(name);
				continue;
			}

			if (file.isDirectory()) {
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
			if (group != null && representative(group) == image) {
				// The whole group travels: an album shows a group by its representative, and a
				// group left without it would be a different thing.
				entry._group = group;
			} else {
				entry._image = image;
			}
		}
		return result;
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
		File[] children = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
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

	/** Moves a single image out of the source album and appends it to the target album. */
	private MoveOutcome moveImage(Entry entry, AlbumInfo sourceAlbum, AlbumInfo targetAlbum, File sourceFolder,
			File targetFolder, HashCache targetHashes, HashCache sourceHashes) throws IOException {
		ImagePart image = entry._image;
		Landing landing = landFile(image.getName(), sourceFolder, targetFolder, targetHashes, sourceHashes);

		detach(sourceAlbum, image);
		if (landing._existing != null) {
			return outcome(entry._name, landing._existing, duplicate(landing._existing));
		}

		image.setName(landing._newName);
		image.setGroup(null);
		targetAlbum.addPart(image);
		return outcome(entry._name, landing._newName, "");
	}

	/**
	 * Moves a whole group: every member file, and the {@link ImageGroup} as one part of the target
	 * album.
	 */
	private MoveOutcome moveGroup(Entry entry, List<Entry> entries, AlbumInfo sourceAlbum, AlbumInfo targetAlbum,
			File sourceFolder, File targetFolder, HashCache targetHashes, HashCache sourceHashes)
			throws IOException {
		ImageGroup group = entry._group;
		ImagePart head = representative(group);
		List<ImagePart> members = new ArrayList<>(group.getImages());

		List<ImagePart> moved = new ArrayList<>(members.size());
		String headName = null;
		int setAside = 0;
		for (ImagePart member : members) {
			String asked = member.getName();
			Landing landing = landFile(asked, sourceFolder, targetFolder, targetHashes, sourceHashes);
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
			targetAlbum.addPart(group);
		} else if (moved.size() == 1) {
			// A group of one is not a group.
			ImagePart single = moved.get(0);
			single.setGroup(null);
			targetAlbum.addPart(single);
		}

		String message = setAside == 0 ? "" : membersSetAside(setAside);
		return outcome(entry._name, headName == null ? "" : headName, message);
	}

	/** Where a single file ended up, see {@link MoveService#landFile(String, File, File, HashCache, HashCache)}. */
	private static final class Landing {

		/** The name the file has in the target folder now, <code>null</code> if it was set aside. */
		final String _newName;

		/** The name of the file at the target that already held these contents, else <code>null</code>. */
		final String _existing;

		Landing(String newName, String existing) {
			_newName = newName;
			_existing = existing;
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
			HashCache sourceHashes) throws IOException {
		File file = new File(sourceFolder, name);
		String hash = sourceHashes.hashByName().get(name);
		if (hash == null) {
			// The source folder was never uploaded to; the file is hashed now.
			hash = HashCache.sha256(file);
		}

		String existing = targetHashes.nameOf(hash);
		if (existing != null) {
			File aside = setAside(hash, name);
			Files.move(file.toPath(), aside.toPath());
			LOG.info("The target already holds '" + name + "' as '" + existing + "'; set aside as '" + aside + "'.");
			return new Landing(null, existing);
		}

		File moved = ImageServlet.freeName(targetFolder, name);
		Files.move(file.toPath(), moved.toPath());
		targetHashes.put(moved, hash);
		LOG.info("Moved image '" + file + "' to '" + moved + "'.");
		return new Landing(moved.getName(), null);
	}

	/** The file a duplicate is set aside as; nothing there is ever overwritten either. */
	private File setAside(String hash, String name) throws IOException {
		File folder = _basePath.resolve(UserStore.DIRECTORY_NAME).resolve(DUPLICATES_FOLDER).toFile();
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
	private static void detach(AlbumInfo album, ImagePart image) {
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
	 * The cover of an album whose cover was moved away is the album's first image, computed
	 * exactly as a listing computes the cover of a folder without a sidecar; an album with no
	 * image left has no cover.
	 * </p>
	 */
	private static void repairIndexPicture(AlbumInfo album) {
		ThumbnailInfo indexPicture = album.getIndexPicture();
		if (indexPicture == null) {
			return;
		}
		UpdateTransient.updateTransient(album);
		if (album.getImageByName().containsKey(indexPicture.getImage())) {
			return;
		}
		ImagePart first = PrivacyFilter.firstImage(album);
		album.setIndexPicture(first == null ? null : PrivacyFilter.thumbnail(first));
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

	/** Whether the given file is the given folder itself or lies below it. */
	private static boolean isBelow(File file, File folder) {
		Path candidate = file.getAbsoluteFile().toPath().normalize();
		Path root = folder.getAbsoluteFile().toPath().normalize();
		return candidate.startsWith(root);
	}

	/** Whether the given name addresses a single entry of a folder. */
	private static boolean isPlainName(String name) {
		if (name == null || name.isEmpty() || name.startsWith(".")) {
			// A name starting with a dot is the server's own business (the sidecars, the user
			// store, the upload staging area) and no album entry.
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
	private static byte[] json(FolderResource resource) throws IOException {
		AlbumDate.clearDerived(resource);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer,
			StandardCharsets.UTF_8)))) {
			resource.writeTo(json);
		}
		return buffer.toByteArray();
	}

}
