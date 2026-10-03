/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.DuplicateCopy;
import de.haumacher.imageServer.shared.model.DuplicateGroup;
import de.haumacher.imageServer.shared.model.DuplicateList;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.IndexProgress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The photographs of a space that lie in more than one album, see issue #220 and
 * {@link DuplicateList}.
 *
 * <p>
 * Read-only and derived: the space's {@link de.haumacher.imageServer.upload.HashIndex} says which
 * contents lie in more than one folder, and every copy is then asked what the album itself would
 * answer the caller — the copy is listed only where the album shows it to them. A content of
 * which the caller sees fewer than two albums is no group for them, so the list never says that
 * there is a copy they may not see.
 * </p>
 *
 * <p>
 * <b>A raw and its JPEG are one photograph</b> (issue #191), but two contents: the JPEG's hash and
 * the raw's are found as two groups of the same copies. Every file is therefore answered as the
 * photograph it belongs to (the JPEG's name, the name the album addresses it by), and groups that
 * share a photograph are one group — so a pair in two albums is one row, and a raw standing alone
 * in one album beside the pair in another is found as the same photograph.
 * </p>
 */
public class Duplicates {

	private static final Logger LOG = Logger.getLogger(Duplicates.class.getName());

	/** What the caller sees of one album, see {@link #compute}. */
	public interface View {

		/**
		 * The album at the given folder path (relative to the space root, the empty string for the
		 * root) as the caller is answered it, <code>null</code> where it is none or they may not see
		 * it.
		 */
		AlbumInfo album(String folder);

		/**
		 * The photograph the file of the given path (relative to the space root) belongs to as the
		 * caller is answered it, <code>null</code> where they may not see it: above their
		 * clearance, below their rating floor, the trash, or of an inbox they are not shown it in.
		 */
		ImagePart photo(String path);
	}

	/**
	 * The groups of the given index entries as the given view shows them.
	 *
	 * @param shared
	 *        Content hash to every path holding it, see
	 *        {@link de.haumacher.imageServer.upload.HashIndex#shared()}.
	 * @param progress
	 *        How far the index has got.
	 */
	public static DuplicateList compute(Map<String, List<String>> shared, IndexProgress progress, View view) {
		Map<String, AlbumInfo> albums = new HashMap<>();
		Function<String, AlbumInfo> album = folder -> albums.computeIfAbsent(folder, view::album);

		// One photograph of one album is one node; every hash links the photographs holding it.
		Map<String, Photo> photos = new LinkedHashMap<>();
		Map<String, String> parent = new HashMap<>();
		Map<String, String> hashOf = new HashMap<>();
		for (Map.Entry<String, List<String>> entry : shared.entrySet()) {
			String first = null;
			for (String path : entry.getValue()) {
				Photo photo = photo(path, album, view);
				if (photo == null) {
					continue;
				}
				photos.putIfAbsent(photo.key(), photo);
				parent.putIfAbsent(photo.key(), photo.key());
				hashOf.putIfAbsent(photo.key(), entry.getKey());
				if (first == null) {
					first = photo.key();
				} else {
					union(parent, first, photo.key());
				}
			}
		}

		Map<String, List<Photo>> byRoot = new LinkedHashMap<>();
		for (Photo photo : photos.values()) {
			byRoot.computeIfAbsent(find(parent, photo.key()), x -> new ArrayList<>()).add(photo);
		}

		List<DuplicateGroup> groups = new ArrayList<>();
		for (Map.Entry<String, List<Photo>> entry : byRoot.entrySet()) {
			List<Photo> members = entry.getValue();
			Set<String> folders = new HashSet<>();
			for (Photo photo : members) {
				folders.add(photo._folder);
			}
			if (folders.size() < 2) {
				// Seen in one album only: nothing to tell this caller.
				continue;
			}
			members.sort(Comparator.comparingLong((Photo p) -> p._album.getEffectiveDate() == 0 ? Long.MAX_VALUE
				: p._album.getEffectiveDate()).thenComparing(p -> p._folder).thenComparing(p -> p._name));
			DuplicateGroup group = DuplicateGroup.create()
				.setHash(hashOf.get(members.get(0).key()))
				.setDate(members.get(0)._date);
			List<DuplicateCopy> copies = new ArrayList<>(members.size());
			for (Photo photo : members) {
				copies.add(DuplicateCopy.create()
					.setAlbum(photo._folder)
					.setTitle(photo._album.getTitle())
					.setAlbumDate(photo._album.getEffectiveDate())
					.setName(photo._name));
			}
			group.setCopies(copies);
			group.setImage(tile(members.get(0)._image));
			groups.add(group);
		}
		groups.sort(Comparator.comparingInt((DuplicateGroup g) -> -g.getCopies().size())
			.thenComparing(Comparator.comparingLong(DuplicateGroup::getDate).reversed())
			.thenComparing(g -> g.getCopies().get(0).getAlbum() + "/" + g.getCopies().get(0).getName()));
		return DuplicateList.create().setGroups(groups).setIndexed(progress);
	}

	/** What the thumbnail of the given photograph needs, and nothing else of it. */
	private static ImagePart tile(ImagePart image) {
		ImagePart result = ImagePart.create()
			.setKind(image.getKind())
			.setName(image.getName())
			.setDate(image.getDate())
			.setWidth(image.getWidth())
			.setHeight(image.getHeight())
			.setOrientation(image.getOrientation());
		if (image.getCrop() != null) {
			result.setCrop(image.getCrop());
		}
		return result;
	}

	/** The photograph of the given path as the view shows it, <code>null</code> if it does not. */
	private static Photo photo(String path, Function<String, AlbumInfo> albums, View view) {
		int slash = path.lastIndexOf('/');
		String folder = slash < 0 ? "" : path.substring(0, slash);
		try {
			AlbumInfo album = albums.apply(folder);
			if (album == null) {
				return null;
			}
			ImagePart image = view.photo(path);
			if (image == null) {
				return null;
			}
			return new Photo(folder, image, album);
		} catch (RuntimeException ex) {
			// One unreadable album is not a reason to answer nothing.
			LOG.log(Level.WARNING, "Cannot read '" + path + "' for the duplicates: " + ex.getMessage(), ex);
			return null;
		}
	}

	/**
	 * Whether the given photograph is shown to a caller of the given clearance and rating floor.
	 *
	 * <p>
	 * The album's own rule (see {@link PrivacyFilter}), and one more: a photograph rated
	 * {@link Ratings#TRASH} is never a copy here, not even for an editor — it is on its way out, and
	 * the album shows it nowhere but in its trash, where an entry of this list could not open it.
	 * </p>
	 */
	public static boolean shows(ImagePart image, int clearance, int minRating) {
		return Privacy.visible(image.getPrivacy(), clearance)
			&& Ratings.visible(image.getRating(), Ratings.withoutTrash(minRating));
	}

	private static String find(Map<String, String> parent, String key) {
		String root = key;
		while (!parent.get(root).equals(root)) {
			root = parent.get(root);
		}
		// Path compression.
		String node = key;
		while (!node.equals(root)) {
			String next = parent.get(node);
			parent.put(node, root);
			node = next;
		}
		return root;
	}

	private static void union(Map<String, String> parent, String a, String b) {
		String rootA = find(parent, a);
		String rootB = find(parent, b);
		if (!rootA.equals(rootB)) {
			parent.put(rootB, rootA);
		}
	}

	/** One photograph of one album. */
	private static final class Photo {
		final String _folder;

		final String _name;

		final long _date;

		final ImagePart _image;

		final AlbumInfo _album;

		Photo(String folder, ImagePart image, AlbumInfo album) {
			_folder = folder;
			_name = image.getName();
			_date = image.getDate();
			_image = image;
			_album = album;
		}

		String key() {
			return _folder.isEmpty() ? _name : _folder + "/" + _name;
		}
	}
}
