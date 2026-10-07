/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The labels of the photographs of an album, see issue #213.
 *
 * <p>
 * A label is the author's sub-view of one album — "the day we met Anna and Ben" — stored on every
 * photograph carrying it ({@link ImagePart#getLabels()}) and nowhere else: the labels of an album
 * are the labels its photographs carry. A share link may show the photographs of one label only
 * ({@link de.haumacher.imageServer.auth.ShareStore.Link#getPhotoLabel()}), which the
 * {@link PrivacyFilter} and the image endpoints apply through {@link #shows(ImagePart, String)};
 * the members see every label, and nobody else sees any ({@link #withoutLabels(AlbumInfo)}).
 * </p>
 */
public final class Labels {

	/**
	 * What a photograph a share link does not show is answered with: it is not there for the link,
	 * exactly as a photograph of an inbox is not there for whoever may not see it (issue #135).
	 */
	public static final String NOT_FOUND = "There is no such photograph here.";

	/** Refusal of a label filter on a link to a folder of folders, see issue #213. */
	public static final String LABEL_NEEDS_ALBUM =
		"Only a link to an album can show the photographs of one label: labels belong to one album.";

	/** Refusal of a <code>?action=relabel</code> through a share link. */
	public static final String RELABEL_REFUSED = "Only an editor of the album may rename or remove its labels.";

	/** Refusal of an unreadable <code>?action=relabel</code>. */
	public static final String RELABEL_UNREADABLE = "The label change cannot be read.";

	/** Refusal of <code>?action=relabel</code> on something that is no album. */
	public static final String RELABEL_NOT_AN_ALBUM = "Only an album has labels.";

	/** The refusal of a label filter naming a label no photograph of the album carries. */
	public static String unknownLabel(String label) {
		return "No photograph of this album is labeled '" + label + "'.";
	}

	/** The refusal of a label change whose label no photograph of the album carries. */
	public static String nothingLabeled(String label) {
		return "No photograph of this album is labeled '" + label + "'.";
	}

	/** Recognises a body that names the field at all, see {@link #keepUnknown}. */
	private static final Pattern LABELS_KEY = Pattern.compile("\"labels\"\\s*:");

	private Labels() {
		// Static utility.
	}

	/** Whether the given photograph carries the given label. */
	public static boolean carries(ImagePart image, String label) {
		for (LabelName name : image.getLabels()) {
			if (name.getName().equals(label)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a view showing the photographs of the given label shows the given photograph.
	 *
	 * @param label
	 *        The label of the view, the empty string for a view of every photograph.
	 */
	public static boolean shows(ImagePart image, String label) {
		return label == null || label.isEmpty() || carries(image, label);
	}

	/** Every label the photographs of the given album carry, in the order they are met. */
	public static Set<String> of(AlbumInfo album) {
		Set<String> result = new LinkedHashSet<>();
		for (ImagePart image : images(album)) {
			for (LabelName name : image.getLabels()) {
				result.add(name.getName());
			}
		}
		return result;
	}

	/** Gives the given photograph the given label, unless it carries it already. */
	public static boolean add(ImagePart image, String label) {
		if (label == null || label.isEmpty() || carries(image, label)) {
			return false;
		}
		image.addLabel(LabelName.create().setName(label));
		return true;
	}

	/**
	 * Renames the given label on every photograph of the given album, or removes it where the new
	 * name is empty.
	 *
	 * <p>
	 * A photograph that already carries the new name keeps it once; the place of the label among a
	 * photograph's labels is kept.
	 * </p>
	 *
	 * @return How many photographs changed.
	 */
	public static int relabel(AlbumInfo album, String from, String to) {
		int changed = 0;
		for (ImagePart image : images(album)) {
			if (!carries(image, from)) {
				continue;
			}
			boolean hasTarget = !to.isEmpty() && carries(image, to);
			List<LabelName> labels = new ArrayList<>(image.getLabels().size());
			for (LabelName name : image.getLabels()) {
				if (!name.getName().equals(from)) {
					labels.add(name);
				} else if (!to.isEmpty() && !hasTarget) {
					labels.add(LabelName.create().setName(to));
					hasTarget = true;
				}
			}
			image.setLabels(labels);
			changed++;
		}
		return changed;
	}

	/**
	 * The given album without labels, for a caller who is answered none.
	 *
	 * <p>
	 * A copy, never the cached album, and only where there is something to take out. The labels are
	 * the members' bookkeeping, like the attribution of issue #96 and the names of issue #125: a
	 * share link, an anonymous visitor and the author's preview as the public sees it are answered
	 * none, see {@link de.haumacher.imageServer.faces.Faces#maySee}.
	 * </p>
	 */
	public static AlbumInfo withoutLabels(AlbumInfo album) {
		if (!labeled(album)) {
			return album;
		}
		AlbumInfo result = AlbumInfo.create()
			.setKind(album.getKind())
			// What a saved search looks for is not a question of who is asking (#227).
			.setQuery(album.getQuery())
			.setTitle(album.getTitle())
			.setSubTitle(album.getSubTitle())
			.setStarred(album.isStarred())
			.setDate(album.getDate())
			.setEffectiveDate(album.getEffectiveDate())
			.setFacesPending(album.isFacesPending());
		if (album.getIndexPicture() != null) {
			result.setIndexPicture(album.getIndexPicture());
		}
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.addPart(withoutLabels((ImagePart) part));
			} else if (part instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) part;
				ImageGroup copy = ImageGroup.create().setRepresentative(group.getRepresentative());
				for (ImagePart image : group.getImages()) {
					copy.addImage(withoutLabels(image));
				}
				result.addPart(copy);
			} else {
				result.addPart(part);
			}
		}
		return result;
	}

	/** The given photograph without labels, see {@link #withoutLabels(AlbumInfo)}. */
	public static ImagePart withoutLabels(ImagePart image) {
		if (image.getLabels().isEmpty()) {
			return image;
		}
		ImagePart copy = FaceIndex.copyOf(image);
		copy.setLabels(Collections.emptyList());
		return copy;
	}

	/** Whether any photograph of the given album carries a label. */
	public static boolean labeled(AlbumInfo album) {
		for (ImagePart image : images(album)) {
			if (!image.getLabels().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Keeps the stored labels of an album written back by a client that does not know them.
	 *
	 * <p>
	 * Every client of this build writes the field of every photograph, an empty list included; a
	 * body that never names it was written by an app older than issue #213, which read the album
	 * without its labels and would otherwise remove every one of them by saving a rotation. Such a
	 * body gets the labels the album stores, photograph by photograph.
	 * </p>
	 *
	 * @param contents
	 *        The body as it was received.
	 * @param received
	 *        The parsed body, to be stored.
	 * @param stored
	 *        What the cache holds for the folder.
	 * @return Whether anything was carried over, so that the body has to be written anew.
	 */
	public static boolean keepUnknown(byte[] contents, FolderResource received, Resource stored) {
		if (!(received instanceof AlbumInfo) || !(stored instanceof AlbumInfo) || !labeled((AlbumInfo) stored)) {
			return false;
		}
		if (LABELS_KEY.matcher(new String(contents, StandardCharsets.UTF_8)).find()) {
			return false;
		}
		Map<String, List<LabelName>> byName = new HashMap<>();
		for (ImagePart image : images((AlbumInfo) stored)) {
			if (!image.getLabels().isEmpty()) {
				byName.put(image.getName(), image.getLabels());
			}
		}
		boolean kept = false;
		for (ImagePart image : images((AlbumInfo) received)) {
			List<LabelName> labels = byName.get(image.getName());
			if (labels != null && image.getLabels().isEmpty()) {
				List<LabelName> copy = new ArrayList<>(labels.size());
				for (LabelName name : labels) {
					copy.add(LabelName.create().setName(name.getName()));
				}
				image.setLabels(copy);
				kept = true;
			}
		}
		return kept;
	}

	/** Every photograph of the given album, the members of a group one by one. */
	static List<ImagePart> images(AlbumInfo album) {
		List<ImagePart> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add((ImagePart) part);
			} else if (part instanceof ImageGroup) {
				result.addAll(((ImageGroup) part).getImages());
			}
		}
		return result;
	}
}
