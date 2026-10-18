/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.index.VectorIndex;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.IndexProgress;
import de.haumacher.imageServer.shared.model.SimilarGroup;
import de.haumacher.imageServer.shared.model.SimilarList;
import de.haumacher.imageServer.shared.model.SimilarPhoto;
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
 * Photographs of the same scenery in several albums of a space, see issue #257 and
 * {@link SimilarList}: the overview beside the {@link Duplicates} of #220.
 *
 * <p>
 * Read-only and derived from the vectors of the content search. The space's {@link SearchIndex}
 * links each still picture the caller may see to those of its nearest that look alike
 * ({@link SearchIndex#scenery}: one query of the vector graph per photograph, what the caller may
 * not see left out inside the query); every photograph linked is then asked what its album would
 * answer the caller, exactly as a copy of the duplicates is, and only what the album shows them is
 * linked. What is linked is a group (union-find): shots of the same scenery need not all be near
 * each other, the second hill of a walk is near the first and the third.
 * </p>
 *
 * <h2>Three levels of the same</h2>
 *
 * <p>
 * Two copies of one content (the same hash) are never linked to each other: they are a duplicate,
 * which {@link Duplicates} shows; a group holds one of them (the first in its order) and the shots
 * that look like it. A group is what a caller sees in <b>at least two albums</b>: like shots in one
 * album alone are the album's business (its bursts are its stacks, see
 * {@link de.haumacher.imageServer.content.Stacks}), and an overview of the space is for what
 * lies apart.
 * </p>
 */
public final class SimilarShots {

	private static final Logger LOG = Logger.getLogger(SimilarShots.class.getName());

	private SimilarShots() {
		// Static only.
	}

	/**
	 * The groups of the given scenery as the given view shows them.
	 *
	 * @param hashes
	 *        The content hashes of the files of the album at the given folder path, by file name;
	 *        asked once per album.
	 * @param content
	 *        Whether the space has the content search on.
	 */
	public static SimilarList compute(SearchIndex.Scenery scenery, Duplicates.View view,
			Function<String, Map<String, String>> hashes, boolean content) {
		Map<String, AlbumInfo> albums = new HashMap<>();
		Map<String, Map<String, ImagePart>> shown = new HashMap<>();
		Map<String, Map<String, String>> hashesOf = new HashMap<>();
		Map<Long, Node> nodes = new HashMap<>();
		for (Map.Entry<Long, PhotoSearch.Match> entry : scenery.matches().entrySet()) {
			Node node = node(entry.getValue(), albums, shown, hashesOf, view, hashes);
			if (node != null) {
				nodes.put(entry.getKey(), node);
			}
		}

		Map<Long, Long> parent = new HashMap<>();
		for (VectorIndex.Link link : scenery.links()) {
			Node from = nodes.get(Long.valueOf(link.from()));
			Node to = nodes.get(Long.valueOf(link.to()));
			if (from == null || to == null || (from._hash != null && from._hash.equals(to._hash))) {
				// Not shown to this caller, or the same content: a duplicate, no similar shot.
				continue;
			}
			union(parent, from._id, to._id);
		}

		Map<Long, List<Node>> byRoot = new LinkedHashMap<>();
		for (Long id : parent.keySet()) {
			byRoot.computeIfAbsent(find(parent, id), x -> new ArrayList<>()).add(nodes.get(id));
		}

		Comparator<Node> order = Comparator
			.comparingLong((Node n) -> n._album.getEffectiveDate() == 0 ? Long.MAX_VALUE : n._album.getEffectiveDate())
			.thenComparing(n -> n._folder).thenComparing(n -> n._image.getName());
		List<SimilarGroup> groups = new ArrayList<>();
		for (List<Node> members : byRoot.values()) {
			members.sort(order);
			List<Node> kept = new ArrayList<>();
			Set<String> contents = new HashSet<>();
			Set<String> folders = new HashSet<>();
			for (Node node : members) {
				if (node._hash != null && !contents.add(node._hash)) {
					// Another copy of a content the group holds already.
					continue;
				}
				kept.add(node);
				folders.add(node._folder);
			}
			if (kept.size() < 2 || folders.size() < 2) {
				continue;
			}
			SimilarGroup group = SimilarGroup.create().setDate(kept.get(0)._image.getDate());
			for (Node node : kept) {
				group.addPhoto(SimilarPhoto.create()
					.setAlbum(node._folder)
					.setTitle(node._album.getTitle())
					.setAlbumDate(node._album.getEffectiveDate())
					.setName(node._image.getName())
					.setImage(Duplicates.tile(node._image)));
			}
			groups.add(group);
		}
		groups.sort(Comparator.comparingInt((SimilarGroup g) -> -g.getPhotos().size())
			.thenComparing(Comparator.comparingLong(SimilarGroup::getDate).reversed())
			.thenComparing(g -> g.getPhotos().get(0).getAlbum() + "/" + g.getPhotos().get(0).getName()));
		return SimilarList.create()
			.setGroups(groups)
			.setDescribed(IndexProgress.create().setDone(scenery.described()).setTotal(scenery.total()))
			.setContent(content);
	}

	private static Node node(PhotoSearch.Match match, Map<String, AlbumInfo> albums,
			Map<String, Map<String, ImagePart>> shown, Map<String, Map<String, String>> hashesOf, Duplicates.View view,
			Function<String, Map<String, String>> hashes) {
		String path = match.getRelative();
		int slash = path.lastIndexOf('/');
		String folder = slash < 0 ? "" : path.substring(0, slash);
		try {
			AlbumInfo album = albums.computeIfAbsent(folder, view::album);
			if (album == null) {
				return null;
			}
			ImagePart image = shown.computeIfAbsent(folder, view::photos).get(match.getName());
			if (image == null || de.haumacher.imageServer.cache.BrokenParts.isBroken(image)) {
				return null;
			}
			String hash = hashesOf.computeIfAbsent(folder, hashes).get(image.getName());
			return new Node(match.getId(), folder, image, album, hash);
		} catch (RuntimeException ex) {
			// One unreadable album is not a reason to answer nothing.
			LOG.log(Level.WARNING, "Cannot read '" + path + "' for the similar shots: " + ex.getMessage(), ex);
			return null;
		}
	}

	private static Long find(Map<Long, Long> parent, Long key) {
		Long root = key;
		while (!parent.get(root).equals(root)) {
			root = parent.get(root);
		}
		Long node = key;
		while (!node.equals(root)) {
			Long next = parent.get(node);
			parent.put(node, root);
			node = next;
		}
		return root;
	}

	private static void union(Map<Long, Long> parent, long a, long b) {
		Long keyA = Long.valueOf(a);
		Long keyB = Long.valueOf(b);
		parent.putIfAbsent(keyA, keyA);
		parent.putIfAbsent(keyB, keyB);
		Long rootA = find(parent, keyA);
		Long rootB = find(parent, keyB);
		if (!rootA.equals(rootB)) {
			parent.put(rootB, rootA);
		}
	}

	/** One photograph of one album, as the caller is shown it. */
	private static final class Node {
		final long _id;

		final String _folder;

		final ImagePart _image;

		final AlbumInfo _album;

		/** The content hash of its file, <code>null</code> where it is not hashed yet. */
		final String _hash;

		Node(long id, String folder, ImagePart image, AlbumInfo album, String hash) {
			_id = id;
			_folder = folder;
			_image = image;
			_album = album;
			_hash = hash;
		}
	}
}
