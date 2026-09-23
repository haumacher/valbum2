/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.MoveService.MoveRefused;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.util.UpdateTransient;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.util.servlet.Util;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Deleting albums and folders, see issue #109.
 *
 * <p>
 * Deleting is a {@link MoveService move}, and the one the server already knows how to do: an album
 * that holds photographs is renamed — once, as a whole — into the {@value #TRASH_FOLDER} folder of
 * its space, where its sidecars, its hash cache and everything below it ride along by nature. The
 * server does not delete originals, and this endpoint is no exception: what leaves the tree is
 * still on the disk, and emptying the trash is the owner's act, exactly as it is for the
 * {@link MoveService#DUPLICATES_FOLDER duplicates}.
 * </p>
 *
 * <p>
 * A single photograph is deleted the same way and by the same mechanism (issue #131): it is moved
 * into <code>&lt;space&gt;/.valbum/trash/&lt;album&gt;/</code>, keeping its
 * {@link de.haumacher.imageServer.shared.model.ImagePart} and its hash entry, so that what was
 * thrown away lies in the trash beside the other photographs of the album it came from.
 * </p>
 *
 * <p>
 * The one thing that really is removed is a folder that holds <em>no picture at all</em> — no
 * image and no video anywhere below it, a photo rated &minus;2 counting as a picture like any
 * other, because the rating is a statement about a photograph, not about whether it exists. Such a
 * folder is empty in the only sense that matters, and moving it to the trash would ask the user to
 * empty the trash of nothing. Even then the deletion is a <em>name</em> rule and never "delete the
 * directory": every file below it must be one the server itself wrote (see {@link #removable}), or
 * nothing is removed and the folder goes to the trash after all. A file the album's own tooling
 * left there survives — in the trash, where its owner can find it.
 * </p>
 *
 * <p>
 * The one exception to "the server deletes only what it wrote" (issue #152): the one act that
 * removes a photograph from disk is an administrator's {@link #purge(PathInfo) purge} of the
 * photographs of an album rated &minus;2.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class DeleteService {

	private static final Logger LOG = Logger.getLogger(DeleteService.class.getName());

	/**
	 * The folder below {@value UserStore#DIRECTORY_NAME} a deleted album is set aside in.
	 *
	 * <p>
	 * Below the <em>space</em>, like the {@link MoveService#DUPLICATES_FOLDER duplicates}: nothing
	 * crosses a space boundary since issue #82, and what one space throws away is no business of
	 * another. Nothing below {@value UserStore#DIRECTORY_NAME} is ever served, so a deleted album
	 * is gone from every listing the moment it is renamed.
	 * </p>
	 */
	public static final String TRASH_FOLDER = "trash";

	/** How the trash folder is named in a message shown to the user. */
	private static final String TRASH_PATH = UserStore.DIRECTORY_NAME + "/" + TRASH_FOLDER;

	/** The message an unreadable delete request is refused with. */
	public static final String DELETE_UNREADABLE = "The delete request cannot be read.";

	/** The message a delete in a folder that does not exist is refused with. */
	public static final String FOLDER_MISSING = "The folder to delete from does not exist.";

	/** The message a caller is refused with that may not change the folder. */
	public static final String EDIT_REFUSED = "You may not change the folder you are deleting from.";

	/**
	 * The message a share-link caller is refused with.
	 *
	 * <p>
	 * Not the generic "this link does not allow changes here": a link may well allow adding
	 * photos, and deleting an album is still none of its business.
	 * </p>
	 */
	public static final String SHARE_DELETE_REFUSED = "A share link cannot delete an album.";

	/**
	 * The message a caller is refused with that may not purge the trash of an album, see issue #152.
	 *
	 * <p>
	 * Purging deletes photographs from disk, so it is the administrator's act alone — a share link
	 * included in the refusal, whatever it allows.
	 * </p>
	 */
	public static final String PURGE_REFUSED = "Only an administrator may purge the trash of an album.";

	/** The message a purge is refused with when a trashed photograph is not a file of the album. */
	public static String purgeUnresolved(String name) {
		return "'" + name + "' is not a file of this album; nothing was purged.";
	}

	/** The message an entry the folder does not hold is refused with. */
	public static String notFound(String name) {
		return "'" + name + "' does not exist in this folder.";
	}

	/** The message an entry is refused with that is neither an album, a folder nor a photograph. */
	public static String notAnEntry(String name) {
		return "'" + name + "' is not an album, a folder or an image; it cannot be deleted.";
	}

	/** The message a name is refused with that the same request already asked to delete. */
	public static String namedTwice(String name) {
		return "'" + name + "' is named more than once in this request; it is deleted only once.";
	}

	/** The message the root of the space itself is refused with. */
	public static final String ROOT_REFUSED = "The top level of your library cannot be deleted.";

	/** What an entry that held no picture is reported with. */
	public static final String REMOVED = "Removed: it held no image.";

	/**
	 * What a photograph deleted by a {@link #purge(PathInfo) purge} is reported with, see issue
	 * #152: the "no new name" of {@link #REMOVED}, with a reason that is true of a photograph.
	 */
	public static final String PURGED = "Deleted from disk: it was rated as trash.";

	/** What an entry that went to the trash is reported with. */
	public static String trashed(String name) {
		return "Moved to the trash of the space ('" + TRASH_PATH + "') as '" + name + "'.";
	}

	/** The message an entry is reported with whose rename or removal failed. */
	public static String failed(String reason) {
		return "The delete failed: " + reason;
	}

	/** How a name clash in the trash is spelled: when it was thrown away, before the name. */
	static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final Path _spaceRoot;

	private final ResourceCache _cache;

	/**
	 * Creates a {@link DeleteService}.
	 *
	 * @param spaceRoot
	 *        The root folder of the caller's space, which holds its
	 *        {@value UserStore#DIRECTORY_NAME} and therefore its trash. In a server hosting one
	 *        space this is the base folder it was started with.
	 * @param cache
	 *        The cache that must forget what was deleted.
	 */
	public DeleteService(Path spaceRoot, ResourceCache cache) {
		_spaceRoot = spaceRoot;
		_cache = cache;
	}

	/**
	 * Deletes the named entries of the given folder.
	 *
	 * <p>
	 * One {@link MoveOutcome} per requested name, in the order they were asked for, exactly as a
	 * move answers: {@link MoveOutcome#getNewName()} is the name the entry now carries in the
	 * trash, or the empty string when it was removed for good, and
	 * {@link MoveOutcome#getMessage()} says which of the two happened — or why nothing happened.
	 * A name that cannot be deleted refuses only itself.
	 * </p>
	 *
	 * @param folder
	 *        The folder the entries live in.
	 * @param names
	 *        The names of the entries to delete.
	 * @throws MoveRefused
	 *         If the request as a whole is impossible.
	 */
	public MoveResult delete(PathInfo folder, List<String> names) throws MoveRefused, IOException {
		File dir = folder.toFile();
		if (!dir.isDirectory()) {
			throw new MoveRefused(HttpServletResponse.SC_NOT_FOUND, FOLDER_MISSING);
		}

		MoveOutcome[] outcomes = new MoveOutcome[names.size()];
		List<String> images = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		boolean changed = false;
		try {
			for (int n = 0, size = names.size(); n < size; n++) {
				String name = names.get(n);
				if (!seen.add(name)) {
					outcomes[n] = outcome(name, "", namedTwice(name));
					continue;
				}
				String refusal = refuse(dir, name);
				if (refusal != null) {
					outcomes[n] = outcome(name, "", refusal);
					continue;
				}

				File entry = new File(dir, name);
				if (!entry.isDirectory()) {
					// A single image: it is moved into the album this one has in the trash, by the
					// move of issue #47, all of them in one go below.
					images.add(name);
					continue;
				}
				try {
					outcomes[n] = delete(entry);
					changed = true;
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Cannot delete '" + entry.getAbsolutePath() + "': " + ex.getMessage(), ex);
					outcomes[n] = outcome(name, "", failed(ex.getMessage()));
				}
			}

			if (!images.isEmpty()) {
				changed = true;
				fill(outcomes, names, deleteImages(folder, images));
			}
		} finally {
			if (changed) {
				// The folder and everything that was below it: the listing shows one entry less,
				// and whatever a preview cache held for a path that is gone is derived data.
				_cache.invalidateTree(folder);
			}
		}

		MoveResult result = MoveResult.create();
		for (int n = 0, size = names.size(); n < size; n++) {
			// A name that reached neither branch cannot happen; saying so beats an empty slot.
			result.addOutcome(outcomes[n] == null ? outcome(names.get(n), "", failed("not handled")) : outcomes[n]);
		}
		return result;
	}

	/**
	 * Deletes every photograph of the given album that is rated {@link Ratings#TRASH} from disk, see
	 * issue #152.
	 *
	 * <p>
	 * The purge of an album's trash, and the one act of this server that removes a photograph for
	 * good. Per photograph: the original is unlinked, its
	 * {@link de.haumacher.imageServer.shared.model.ImagePart} leaves the album's sidecar, its entry
	 * leaves the {@value HashCache#FILE_NAME}, and the files the server generated from it in the
	 * album's {@value PreviewCache#CACHE_DIRECTORY_NAME} — its preview, its playback rendition and
	 * teaser, its face crops, each under its <code>.tmp</code> name too — are deleted with it (see
	 * {@link #generatedOf(File, String)}). Nothing else is touched: no other photograph, no foreign
	 * file, no other cache file; the <code>faces.json</code> entry, keyed by the content hash, may
	 * stay, because it describes contents and not a file.
	 * </p>
	 *
	 * <p>
	 * The photographs go <em>one by one</em>, a group's members as much as a plain part: the
	 * rating belongs to one photograph, and a trashed representative must not take the members
	 * somebody kept with it. A group loses what was trashed of it, and a group left with one member
	 * becomes that plain part.
	 * </p>
	 *
	 * <p>
	 * Like {@link #removable(File)}, the whole list of files is computed before anything is
	 * touched: a photograph the sidecar names that is not a plain regular file of this very folder
	 * — gone, a directory, a link, a name that is a path — refuses the whole request, and nothing
	 * is deleted at all.
	 * </p>
	 *
	 * @param folder
	 *        The album to purge.
	 * @return One outcome per photograph, {@link #PURGED} with no new name; empty where the album
	 *         holds nothing rated {@link Ratings#TRASH} (or is no album at all).
	 * @throws MoveRefused
	 *         If the folder does not exist, or one of the photographs cannot be resolved to a file of
	 *         it.
	 */
	public MoveResult purge(PathInfo folder) throws MoveRefused, IOException {
		File dir = folder.toFile();
		if (!dir.isDirectory()) {
			throw new MoveRefused(HttpServletResponse.SC_NOT_FOUND, FOLDER_MISSING);
		}
		MoveResult result = MoveResult.create();
		Resource resource = _cache.lookup(folder);
		if (!(resource instanceof AlbumInfo)) {
			return result;
		}
		AlbumInfo album = (AlbumInfo) resource;
		List<ImagePart> trashed = trashed(album);
		if (trashed.isEmpty()) {
			return result;
		}

		// Everything that will be deleted, before anything is: one name that does not resolve and
		// nothing happens.
		Path dirPath = dir.getAbsoluteFile().toPath().normalize();
		List<File> originals = new ArrayList<>();
		List<List<File>> generated = new ArrayList<>();
		for (ImagePart image : trashed) {
			String name = image.getName();
			File file = new File(dir, name);
			Path path = file.getAbsoluteFile().toPath().normalize();
			if (!isPlainName(name) || !dirPath.equals(path.getParent())
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
				throw new MoveRefused(HttpServletResponse.SC_CONFLICT, purgeUnresolved(name));
			}
			originals.add(file);
			generated.add(generatedOf(dir, name));
		}

		// Read before the files go, so that the refresh below sees them vanish.
		HashCache hashes = new HashCache(dir);
		boolean changed = false;
		try {
			for (int n = 0, size = trashed.size(); n < size; n++) {
				ImagePart image = trashed.get(n);
				File original = originals.get(n);
				try {
					Files.delete(original.toPath());
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Cannot delete '" + original.getAbsolutePath() + "': " + ex.getMessage(), ex);
					result.addOutcome(outcome(image.getName(), "", failed(ex.getMessage())));
					continue;
				}
				MoveService.detach(album, image);
				changed = true;
				for (File file : generated.get(n)) {
					if (!file.delete() && file.exists()) {
						// Derived data: what cannot be deleted is abandoned, the photograph is gone.
						LOG.warning("Cannot delete the cached file '" + file.getAbsolutePath() + "'.");
					}
				}
				LOG.info("Purged '" + original.getAbsolutePath() + "': it was rated as trash.");
				result.addOutcome(outcome(image.getName(), "", PURGED));
			}
		} finally {
			if (changed) {
				try {
					MoveService.repairIndexPicture(album);
					UpdateTransient.updateTransient(album);
					ImageServlet.storeSidecar(dir, MoveService.json(album));
					// The hashes of the vanished files go with them: a refresh forgets what is gone.
					hashes.refresh();
					hashes.flush();
				} finally {
					_cache.invalidateTree(folder);
				}
			}
		}
		return result;
	}

	/**
	 * The photographs of the given album rated {@link Ratings#TRASH}, group members counted one by
	 * one, in the order the album holds them.
	 */
	static List<ImagePart> trashed(AlbumInfo album) {
		List<ImagePart> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				addTrashed(result, (ImagePart) part);
			} else if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					addTrashed(result, member);
				}
			}
		}
		return result;
	}

	private static void addTrashed(List<ImagePart> result, ImagePart image) {
		if (image.getRating() <= Ratings.TRASH) {
			result.add(image);
		}
	}

	/**
	 * The files of the given folder's {@value PreviewCache#CACHE_DIRECTORY_NAME} the server generated
	 * from the photograph of the given name, see {@link #purge(PathInfo)}.
	 *
	 * <p>
	 * A name rule, exact per kind, and {@link CacheRefresh#isGenerated(String)} has to name the file
	 * too: the preview (named as {@link PreviewCache#createPreview(File)} names it), the two video
	 * renditions ({@link VideoRenditions.Kind#fileName(String)}) and the face crops of either naming
	 * (<code>face-&lt;name&gt;-f&lt;12 hex&gt;.jpg</code>, <code>face-&lt;name&gt;-&lt;n&gt;.jpg</code>),
	 * each also under its {@value PreviewCache#TMP_SUFFIX} name. A file of another photograph whose
	 * name merely begins with this one's is never matched.
	 * </p>
	 */
	static List<File> generatedOf(File dir, String name) {
		List<File> result = new ArrayList<>();
		File[] files = CacheRefresh.cacheDir(dir).listFiles();
		if (files == null) {
			return result;
		}
		String suffix = Util.suffix(name);
		String previewType = "png".equals(suffix) ? "png" : "jpg";
		Set<String> exact = new HashSet<>();
		exact.add(PreviewCache.PREVIEW_PREFIX + name + (previewType.equals(suffix) ? "" : "." + previewType));
		for (VideoRenditions.Kind kind : VideoRenditions.Kind.values()) {
			exact.add(kind.fileName(name));
		}
		String cropPrefix = de.haumacher.imageServer.faces.FaceIndex.CROP_PREFIX + name + "-";
		for (File file : files) {
			String fileName = file.getName();
			if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || !CacheRefresh.isGenerated(fileName)) {
				continue;
			}
			String plain = fileName.endsWith(PreviewCache.TMP_SUFFIX)
				? fileName.substring(0, fileName.length() - PreviewCache.TMP_SUFFIX.length())
				: fileName;
			if (exact.contains(plain)
				|| (plain.startsWith(cropPrefix) && CROP_KEY.matcher(plain.substring(cropPrefix.length())).matches())) {
				result.add(file);
			}
		}
		return result;
	}

	/** What follows <code>face-&lt;name&gt;-</code> in the name of a crop, see issues #124 and #141. */
	private static final Pattern CROP_KEY = Pattern.compile("(f[0-9a-fA-F]{12}|[0-9]+)\\.[jJ][pP][gG]");

	/**
	 * Moves the named images of the given album into the album it has in the trash, see issue
	 * #131.
	 *
	 * <p>
	 * Deleting a single photograph is the move of issue #47 and nothing else: the file is renamed,
	 * its {@link de.haumacher.imageServer.shared.model.ImagePart} leaves the album's sidecar and
	 * lands in the one of the trash album, and its hash entry (with the contributor it names, see
	 * issue #53) rides along. Nothing is unlinked, here as everywhere: what left the album is in
	 * <code>{@value UserStore#DIRECTORY_NAME}/{@value #TRASH_FOLDER}/&lt;album&gt;/</code>, where
	 * its owner can find it — and find it together with the other photographs of the album it came
	 * from, which is why the trash holds an album per album and not one heap.
	 * </p>
	 *
	 * <p>
	 * The trash album is a folder like any other: the move writes its sidecar (the generic album a
	 * folder without one is described by) and its hash cache, so that it reads as an album from
	 * disk. A name it already holds is given a free one exactly as a colliding upload is, and
	 * contents it already holds make the file a duplicate, which is set aside rather than
	 * overwritten.
	 * </p>
	 */
	private MoveResult deleteImages(PathInfo folder, List<String> names) throws IOException {
		// The album keeps its name in the trash, so that what was thrown away is findable by where
		// it came from. At the root of a space that is the name of the space folder itself.
		String albumName = folder.getName();
		File trashAlbum = _spaceRoot.resolve(UserStore.DIRECTORY_NAME).resolve(TRASH_FOLDER).resolve(albumName)
			.toFile();
		if (!trashAlbum.isDirectory() && !trashAlbum.mkdirs()) {
			throw new IOException("Cannot create the trash folder: " + trashAlbum.getAbsolutePath());
		}
		PathInfo target = new PathInfo(_spaceRoot,
			Paths.get(UserStore.DIRECTORY_NAME, TRASH_FOLDER, albumName));
		try {
			return new MoveService(_cache).move(folder, target, names);
		} catch (MoveRefused ex) {
			// The target is the server's own folder and the source has just been checked; a
			// refusal here concerns the request as a whole and every named image alike.
			MoveResult result = MoveResult.create();
			for (String name : names) {
				result.addOutcome(outcome(name, "", failed(ex.getMessage())));
			}
			return result;
		}
	}

	/** Puts the outcomes of the image move back into the slots of the names that asked for them. */
	private static void fill(MoveOutcome[] outcomes, List<String> names, MoveResult moved) {
		for (MoveOutcome moveOutcome : moved.getOutcomes()) {
			for (int n = 0, size = names.size(); n < size; n++) {
				if (outcomes[n] == null && names.get(n).equals(moveOutcome.getName())) {
					String message = moveOutcome.getMessage();
					outcomes[n] = outcome(moveOutcome.getName(), moveOutcome.getNewName(),
						message.isEmpty() ? trashed(moveOutcome.getNewName()) : message);
					break;
				}
			}
		}
	}

	/** Why the named entry cannot be deleted, <code>null</code> while it can. */
	private String refuse(File dir, String name) {
		if (!isPlainName(name)) {
			// A name starting with a dot is the server's own business and no album entry; a name
			// that is a path is no name at all.
			return notAnEntry(name);
		}
		File entry = new File(dir, name);
		if (!entry.exists()) {
			return notFound(name);
		}
		if (!entry.isDirectory()) {
			if (!ResourceCache.isImage(entry)) {
				// A sidecar, a note, whatever else lies beside the photographs: this endpoint
				// deletes albums, folders and photographs, and says so rather than doing
				// something else.
				return notAnEntry(name);
			}
			return null;
		}
		Path path = entry.getAbsoluteFile().toPath().normalize();
		if (path.equals(_spaceRoot.toAbsolutePath().normalize())) {
			// Not reachable through a plain name today; the rule is stated where it is decided.
			return ROOT_REFUSED;
		}
		return null;
	}

	/** Removes the given folder, or renames it into the trash of its space. */
	private MoveOutcome delete(File entry) throws IOException {
		String name = entry.getName();
		List<File> removable = removable(entry);
		if (removable != null) {
			remove(removable);
			LOG.info("Removed '" + entry.getAbsolutePath() + "': it held no image.");
			return outcome(name, "", REMOVED);
		}

		File target = trashName(name);
		Files.move(entry.toPath(), target.toPath());
		LOG.info("Moved '" + entry.getAbsolutePath() + "' to the trash as '" + target.getAbsolutePath() + "'.");
		return outcome(name, target.getName(), trashed(target.getName()));
	}

	/**
	 * Everything below the given folder that may be physically deleted, the folder itself last.
	 *
	 * <p>
	 * <code>null</code> as soon as one file is found that the server did not write — a photograph
	 * above all, but a text file of the owner's just as much. This is the whole of the doctrine:
	 * the answer is computed before anything is touched, so a folder is either removed completely
	 * or not at all, and a single original anywhere below sends the whole folder to the trash
	 * instead.
	 * </p>
	 *
	 * <p>
	 * What counts as the server's own:
	 * </p>
	 * <ul>
	 * <li>The sidecars of a folder: <code>index.json</code>, the timestamped backups a sidecar
	 * write leaves behind (see {@link ImageServlet#storeSidecar(File, byte[])}) and the hash cache
	 * {@value HashCache#FILE_NAME}.</li>
	 * <li>Everything inside a {@value PreviewCache#CACHE_DIRECTORY_NAME} directory. That directory
	 * belongs to the server, and a folder left behind because one stray file sits in it would be
	 * neither deleted nor findable.</li>
	 * </ul>
	 *
	 * <p>
	 * A file in the cache directory is still <em>looked at</em>: an image there that
	 * {@link CacheRefresh#isGenerated(String) the server did not generate} counts as a picture like
	 * any other and sends the folder to the trash, files and all. Nothing anybody may want back is
	 * ever deleted for the sake of a directory name.
	 * </p>
	 */
	static List<File> removable(File folder) {
		List<File> result = new ArrayList<>();
		return collect(folder, false, result) ? result : null;
	}

	/**
	 * Collects what may be deleted below the given directory, depth first.
	 *
	 * @param inCache
	 *        Whether this directory lies inside a {@value PreviewCache#CACHE_DIRECTORY_NAME}
	 *        directory.
	 * @return Whether everything found may be deleted.
	 */
	private static boolean collect(File dir, boolean inCache, List<File> result) {
		File[] contents = dir.listFiles();
		if (contents == null) {
			// A directory that cannot be listed is not one whose contents anybody may delete.
			return false;
		}
		for (File file : contents) {
			if (Files.isSymbolicLink(file.toPath())) {
				// A link points somewhere this folder does not own. Deleting it would delete a
				// name, and walking it would delete somebody else's files; the folder goes to the
				// trash instead, where the link travels along as the name it is.
				return false;
			}
			if (file.isDirectory()) {
				if (!collect(file, inCache || PreviewCache.CACHE_DIRECTORY_NAME.equals(file.getName()), result)) {
					return false;
				}
			} else if (isPicture(file, inCache) || !deletable(file, inCache)) {
				return false;
			} else {
				result.add(file);
			}
		}
		// After its contents: a directory is removed when it is empty and never before.
		result.add(dir);
		return true;
	}

	/**
	 * Whether the given file is a picture that must never be deleted.
	 *
	 * <p>
	 * A preview, a playback rendition and a teaser are pictures of a picture; everything else that
	 * {@link ResourceCache#isImage(File) looks like an image} is one, wherever it lies.
	 * </p>
	 */
	private static boolean isPicture(File file, boolean inCache) {
		if (inCache && CacheRefresh.isGenerated(file.getName())) {
			return false;
		}
		return ResourceCache.isImage(file);
	}

	/** Whether the given file is one the server itself wrote, see {@link #removable(File)}. */
	private static boolean deletable(File file, boolean inCache) {
		if (inCache) {
			return true;
		}
		String name = file.getName();
		return "index.json".equals(name) || name.startsWith("index.json.") || HashCache.FILE_NAME.equals(name);
	}

	/** Deletes the collected files, in the order they were collected. */
	private static void remove(List<File> files) throws IOException {
		for (File file : files) {
			if (!file.delete() && file.exists()) {
				throw new IOException("Cannot delete '" + file.getAbsolutePath() + "'.");
			}
		}
	}

	/**
	 * Where a folder of the given name lands in the trash of its space.
	 *
	 * <p>
	 * Its own name while the trash is free of it, and
	 * <code>&lt;yyyyMMdd-HHmmss&gt;-&lt;name&gt;</code> otherwise: the prefix says when the album
	 * was thrown away, sorts by that, and reads as what it is. Two deletions within one second get
	 * a number behind the whole name, the way a colliding upload does (see
	 * {@link ImageServlet#freeName(File, String)}, which cannot be used here: it numbers before
	 * the file extension, and a folder has none).
	 * </p>
	 */
	private File trashName(String name) throws IOException {
		File trash = _spaceRoot.resolve(UserStore.DIRECTORY_NAME).resolve(TRASH_FOLDER).toFile();
		if (!trash.isDirectory() && !trash.mkdirs()) {
			throw new IOException("Cannot create the trash folder: " + trash.getAbsolutePath());
		}
		File plain = new File(trash, name);
		if (!plain.exists()) {
			return plain;
		}
		String stamped = ZonedDateTime.now(ZoneId.systemDefault()).format(STAMP) + "-" + name;
		File result = new File(trash, stamped);
		for (int num = 2; result.exists(); num++) {
			result = new File(trash, stamped + "-" + num);
		}
		return result;
	}

	/**
	 * Whether every one of the given names is a photograph of the given folder, see issue #131.
	 *
	 * <p>
	 * What decides whether the contributor exception of issue #53 can apply at all: it lets
	 * somebody take their own photograph back out of an album, and never a whole album out of a
	 * listing. One name that is not an image of this folder — a folder, or nothing at all — and
	 * the request is an edit of the folder, refused as such.
	 * </p>
	 */
	public static boolean onlyImages(File dir, List<String> names) {
		if (names.isEmpty()) {
			return false;
		}
		for (String name : names) {
			if (!isPlainName(name)) {
				return false;
			}
			File entry = new File(dir, name);
			if (!entry.isFile() || !ResourceCache.isImage(entry)) {
				return false;
			}
		}
		return true;
	}

	/** Whether the given name addresses a single entry of a folder, see {@link MoveService}. */
	private static boolean isPlainName(String name) {
		if (name == null || name.isEmpty() || name.startsWith(".")) {
			return false;
		}
		return name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.equals("..");
	}

	private static MoveOutcome outcome(String name, String newName, String message) {
		return MoveOutcome.create().setName(name).setNewName(newName).setMessage(message);
	}

	/**
	 * The one line a start-up says about a duplicates folder left below the base folder of a
	 * multi-space server, see issue #109.
	 *
	 * <p>
	 * Until this change {@link MoveService} set a duplicate aside below the folder the server was
	 * started with; since it sets it aside below the caller's <em>space</em>, a server hosting
	 * several spaces has a folder nobody writes to any more. Nothing is moved and nothing is
	 * deleted — those are somebody's photographs — but a folder nothing will ever mention again is
	 * mentioned once, so that it is not simply forgotten.
	 * </p>
	 *
	 * @param basePath
	 *        The folder the server was started with.
	 * @return The line to print, <code>null</code> where there is no such folder.
	 */
	public static String legacyDuplicates(Path basePath) {
		File folder = basePath.resolve(UserStore.DIRECTORY_NAME).resolve(MoveService.DUPLICATES_FOLDER).toFile();
		String[] contents = folder.list();
		if (contents == null) {
			return null;
		}
		return "Set-aside duplicates of an earlier version are in '" + folder.getAbsolutePath() + "' ("
			+ contents.length + " file(s)); they stay there — new ones are set aside below each space's "
			+ UserStore.DIRECTORY_NAME + "/" + MoveService.DUPLICATES_FOLDER + ".";
	}

}
