/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.PhotoRef;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.shared.util.UpdateTransient;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeping identical copies of a photograph as references to one of them, see issue #307
 * (package 3) and {@link de.haumacher.imageServer.shared.model.KeepAsReferencesRequest}.
 *
 * <p>
 * A photograph that lies as identical files in several albums (the "photos in several albums" of
 * issue #220) is kept as one file, the <em>original</em> the user chose, and every other copy is
 * replaced in its album by a reference to the original, so that each album goes on showing it:
 * </p>
 * <ul>
 * <li>The reference stands in the copy's place in the album's order (in its group, where it was a
 * member of one) and is named freely in the album beside the copy's file, which keeps its name
 * until the trash is purged. The album's cover follows.</li>
 * <li>The copy's labels become the reference's: they are the album's own statements about the
 * photograph, and a reference has no fallback for them. Its description becomes the reference's own
 * where it differs from the original's; otherwise the reference has none, and the original's is
 * shown.</li>
 * <li>What belongs to the photograph &mdash; rating, privacy, crop, turn, faces &mdash; is the
 * original's from now on, see {@link PhotoCollections}. A copy more private than the original is
 * therefore refused (see {@link #morePrivate(String)}): replacing it would show the photograph in
 * its album to more people than before.</li>
 * <li>The copy's file goes into the trash of its album: it is rated as trash there and deleted by
 * the next purge. It is no photograph anybody still needs, so it never triggers the keeping of
 * {@link ReferencedOriginals}: every reference that showed the copy is pointed at the original
 * first ({@link ReferencedOriginals#retarget}), and the new reference resolves to the original
 * (see {@link PhotoCollections#locate(PhotoRef)}), never to a file in the trash.</li>
 * </ul>
 *
 * <p>
 * The rights, the visibility and the per-copy refusals are the servlet's, see
 * {@link ImageServlet}; this class carries out what was found acceptable.
 * </p>
 */
public final class KeepAsReferences {

	private static final Logger LOG = Logger.getLogger(KeepAsReferences.class.getName());

	/** The message the whole request is refused with where its body cannot be read. */
	public static final String UNREADABLE = "The photos to keep as references cannot be read.";

	/** The message a share link is refused with. */
	public static final String SHARE_REFUSED = "A share link cannot keep photos as references.";

	/** The message a request naming no copy is refused with. */
	public static final String NO_COPY = "Name at least one copy to keep as a reference.";

	/** The message a copy is refused with whose file is not the original's contents. */
	public static final String NOT_A_COPY = "This photo is not an identical copy of the original; it stays as it is.";

	/** The message the original named as one of its own copies is refused with. */
	public static final String IS_ORIGINAL = "This is the original itself.";

	/** The message a copy in the original's own album is refused with. */
	public static final String SAME_ALBUM =
		"This copy lies in the album of the original, which shows the photo already; delete it there instead.";

	/** The message a copy in the inbox, a saved search or no album at all is refused with. */
	public static final String NOT_AN_ALBUM =
		"Only a copy in an album can be kept as a reference; sort it out of the inbox first.";

	/** The message a copy rated as trash is refused with. */
	public static final String TRASHED = "This copy is in the trash already.";

	/** The message a copy in another library is refused with. */
	public static final String OTHER_SPACE = "This copy lies in another library than the original.";

	/** The message a copy named twice is refused with. */
	public static final String NAMED_TWICE = "This copy is named more than once; it is kept as a reference once.";

	/** The message a copy is refused with whose album the caller may not change. */
	public static String editRefused(String album) {
		return "You may not change '" + album + "'; the copy there stays as it is.";
	}

	/**
	 * The message a copy is refused with that is more private than the original: as a reference it
	 * would be shown as the original is, to more people than it is shown now.
	 */
	public static String morePrivate(String original) {
		return "This copy is more private than the original in '" + original
			+ "', and as a reference it would be shown as the original is. Make the original as private first, or keep this copy as the original.";
	}

	/** What a copy that was replaced is reported with. */
	public static String kept(String album, String original) {
		return "Kept in '" + album + "' as a reference to the original in '" + original
			+ "'; the copy's file is in the trash of '" + album + "'.";
	}

	/** One copy that is to be replaced. */
	public static final class Copy {
		final PathInfo _file;

		final String _label;

		/**
		 * Creates a {@link Copy}.
		 *
		 * @param file
		 *        The copy's file.
		 * @param label
		 *        How the request named it.
		 */
		public Copy(PathInfo file, String label) {
			_file = file;
			_label = label;
		}
	}

	private final ResourceCache _cache;

	private final PhotoCollections _collections;

	private final ReferencedOriginals _originals;

	/**
	 * Creates the service for the space at the given root.
	 */
	public KeepAsReferences(java.nio.file.Path spaceRoot, ResourceCache cache, PhotoCollections collections) {
		_cache = cache;
		_collections = collections;
		_originals = new ReferencedOriginals(spaceRoot, cache, collections);
	}

	/**
	 * Replaces the given copies by references to the given original.
	 *
	 * @param original
	 *        The original's file.
	 * @param originalPart
	 *        The original's part as its album stores it.
	 * @param hash
	 *        The contents of the original and of every copy.
	 * @return The name of the reference that replaced each copy, by its {@link Copy#_label label};
	 *         a copy that could not be replaced is absent, its reason in <code>failures</code>.
	 */
	public Map<String, String> keep(PathInfo original, ImagePart originalPart, String hash, List<Copy> copies,
			Map<String, String> failures) throws IOException {
		Map<String, String> result = new LinkedHashMap<>();
		if (copies.isEmpty()) {
			return result;
		}

		// Whatever showed a copy shows the original from now on, before the copy goes into the trash:
		// nothing is kept for it then, see ReferencedOriginals.
		List<ReferencedOriginals.Doomed> doomed = new ArrayList<>();
		for (Copy copy : copies) {
			doomed.add(new ReferencedOriginals.Doomed(copy._file, hash, copy._label));
		}
		_originals.retarget(doomed, original);

		// One sidecar write per album.
		Map<PathInfo, List<Copy>> byAlbum = new LinkedHashMap<>();
		for (Copy copy : copies) {
			byAlbum.computeIfAbsent(copy._file.parent(), x -> new ArrayList<>()).add(copy);
		}
		String path = _collections.relative(original);
		for (Map.Entry<PathInfo, List<Copy>> entry : byAlbum.entrySet()) {
			PathInfo folder = entry.getKey();
			try {
				replace(folder, entry.getValue(), originalPart, original.getName(), hash, path, result, failures);
			} catch (IOException | RuntimeException ex) {
				LOG.log(Level.WARNING, "Cannot keep the copies of '" + folder.toFile() + "' as references: "
					+ ex.getMessage(), ex);
				for (Copy copy : entry.getValue()) {
					if (!result.containsKey(copy._label)) {
						failures.put(copy._label, MoveService.failed(ex.getMessage()));
					}
				}
			} finally {
				_cache.invalidateTree(folder);
			}
		}
		return result;
	}

	private void replace(PathInfo folder, List<Copy> copies, ImagePart originalPart, String originalName, String hash,
			String path, Map<String, String> result, Map<String, String> failures) throws IOException {
		File dir = folder.toFile();
		Resource resource = _cache.lookup(folder);
		if (!(resource instanceof AlbumInfo)) {
			for (Copy copy : copies) {
				failures.put(copy._label, NOT_AN_ALBUM);
			}
			return;
		}
		AlbumInfo album = (AlbumInfo) resource;
		Set<String> taken = new HashSet<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				taken.add(((ImagePart) part).getName());
			} else if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					taken.add(member.getName());
				}
			}
		}
		String[] files = dir.list();
		if (files != null) {
			taken.addAll(Arrays.asList(files));
		}
		boolean changed = false;
		for (Copy copy : copies) {
			ImagePart part = Crops.findImage(album, copy._file.getName());
			if (part == null || PhotoCollections.isReference(part)) {
				failures.put(copy._label, ImageServlet.notInAlbum(copy._file.getName()));
				continue;
			}
			String name = PhotoCollections.freeName(taken, originalName);
			taken.add(name);
			List<LabelName> labels = new ArrayList<>(part.getLabels());
			ImagePart reference = PhotoCollections.stored(name,
				PhotoRef.create().setHash(hash).setPath(path).setCreated(PhotoCollections.created()), labels);
			String comment = part.getComment() == null ? "" : part.getComment();
			String originalComment = originalPart.getComment() == null ? "" : originalPart.getComment();
			// A description of its own only where the copy said something else than the original.
			reference.setComment(comment.equals(originalComment) ? "" : comment);
			reference.setDate(part.getDate());

			insertBefore(album, part, reference);
			part.setRating(Ratings.TRASH);
			ThumbnailInfo cover = album.getIndexPicture();
			if (cover != null && part.getName().equals(cover.getImage())) {
				// The album keeps showing the photograph it chose.
				cover.setImage(name);
			}
			changed = true;
			result.put(copy._label, name);
			LOG.info("Kept '" + copy._file.toFile() + "' as the reference '" + name + "' to '" + path
				+ "'; the copy is rated as trash.");
		}
		if (changed) {
			UpdateTransient.updateTransient(album);
			ImageServlet.storeSidecar(dir, MoveService.json(album));
		}
	}

	/** Inserts the given reference into the given album right before the given part, in its group where it has one. */
	private static void insertBefore(AlbumInfo album, ImagePart part, ImagePart reference) {
		List<AlbumPart> parts = album.getParts();
		for (int n = 0; n < parts.size(); n++) {
			AlbumPart candidate = parts.get(n);
			if (candidate == part) {
				parts.add(n, reference);
				return;
			}
			if (candidate instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) candidate;
				List<ImagePart> members = group.getImages();
				int index = members.indexOf(part);
				if (index < 0) {
					continue;
				}
				int representative = group.getRepresentative();
				List<ImagePart> updated = new ArrayList<>(members);
				updated.add(index, reference);
				group.setImages(updated);
				// The group goes on being shown by the photograph it was shown by.
				group.setRepresentative(representative == index ? index : representative > index ? representative + 1
					: representative);
				return;
			}
		}
		parts.add(reference);
	}
}
