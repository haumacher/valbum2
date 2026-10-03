/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.raw.RawFile;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.shared.util.UpdateTransient;
import de.haumacher.util.servlet.Util;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A raw file and the JPEG of the same name are one photograph, see issue #191.
 *
 * <p>
 * The author's decision: <code>IMG_1.CR2</code> and <code>IMG_1.JPG</code> in one folder &mdash; the
 * same base name, compared ignoring case &mdash; are <em>the same photo</em>, one entry of the
 * album, not two photos and not a group of alternatives. The JPEG is the photo (its
 * {@link ImagePart}, its previews made from the JPEG itself) and the raw is its <em>companion</em>,
 * named by {@link ImagePart#getRaw()}. A HEIC/HEIF stands where the JPEG does, because the cameras
 * that shoot raw beside a processed picture write one or the other.
 * </p>
 *
 * <h2>Reconciling a sidecar with the files</h2>
 *
 * <p>
 * The stored field is a statement, and the files are the truth; {@link #reconcile} makes the first
 * true to the second on every read, never writing (the album's next ordinary write stores it):
 * </p>
 * <ol>
 * <li>A companion that is no raw, not a plain name, the part's own name, claimed twice or not in the
 * folder is answered as none.</li>
 * <li>A part whose own file is gone and whose companion is there is the raw now, its edits kept.</li>
 * <li>A raw no part claims is paired with the JPEG of its base name, where there is one that claims
 * no raw yet:
 * <ul>
 * <li>both listed &mdash; they arrived apart, the raw first, and an earlier read listed it as a photo
 * of its own: <b>the JPEG's part wins, field by field</b>; a rating, comment, orientation, camera,
 * position or face tags it leaves empty is taken from the raw's part, the privacy is the stricter of
 * the two, the date stays the JPEG's ({@link #merge}); the raw's part leaves the album;</li>
 * <li>the JPEG listed, the raw new &mdash; the raw is its companion;</li>
 * <li>the raw listed, the JPEG new &mdash; the raw's part becomes the JPEG's: it takes the JPEG's
 * name, kind and size and keeps everything stored about it, its place in the album included
 * ({@link #adopt});</li>
 * <li>both new &mdash; the JPEG is analysed and carries the raw, which is not analysed at all.</li>
 * </ul>
 * </li>
 * </ol>
 * <p>
 * A raw without a JPEG of its name is a photograph of its own and carries no companion. Where an
 * index picture named a part that was renamed or merged here, it follows.
 * </p>
 */
public final class RawPairs {

	/** The extensions a raw's companion photograph may have, lower case. */
	private static final Set<String> COMPANION_EXTENSIONS =
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList("jpg", "jpeg", "heic", "heif")));

	private RawPairs() {
		// Static utility.
	}

	/** Analyses a file the album does not list yet, <code>null</code> where it cannot. */
	public interface Analyzer {

		/** The part describing the given file, <code>null</code> where it cannot be read. */
		ImagePart analyze(File file);
	}

	/** What {@link #reconcile} leaves to the loader. */
	public static final class Plan {

		private final Set<String> _skip = new HashSet<>();

		private final Map<String, String> _rawFor = new HashMap<>();

		/** Whether the file of the given name is no new photograph: a companion, or adopted already. */
		public boolean skips(String name) {
			return _skip.contains(name);
		}

		/** The companion the new photograph of the given name carries, the empty string for none. */
		public String rawFor(String name) {
			String raw = _rawFor.get(name);
			return raw == null ? "" : raw;
		}
	}

	/** Whether the given name is that of a photograph a raw may be the companion of. */
	public static boolean isCompanionType(String name) {
		String suffix = Util.suffix(name);
		return suffix != null && COMPANION_EXTENSIONS.contains(suffix);
	}

	/** The base name pairs are found by: the name without its extension, lower case. */
	public static String base(String name) {
		int dot = name.lastIndexOf('.');
		return (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
	}

	/**
	 * The given name with its base replaced by that of the other one, the extension kept as it is:
	 * where a raw follows a photograph that landed as <code>IMG_1-2.JPG</code>, it asks for
	 * <code>IMG_1-2.CR2</code>, so that the two keep one base name.
	 */
	public static String withBaseOf(String name, String other) {
		int dot = name.lastIndexOf('.');
		int otherDot = other.lastIndexOf('.');
		String extension = dot < 0 ? "" : name.substring(dot);
		return (otherDot < 0 ? other : other.substring(0, otherDot)) + extension;
	}

	/**
	 * The companion file of the given part in the given folder, <code>null</code> where it has none
	 * or the name it carries is no plain name of a regular raw file there.
	 */
	public static File companion(File folder, ImagePart image) {
		String raw = image.getRaw();
		if (raw == null || raw.isEmpty() || !isPlainName(raw) || !RawFile.isRawName(raw)
			|| LibraryFiles.isIgnored(raw)) {
			return null;
		}
		File file = new File(folder, raw);
		if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		return file;
	}

	/**
	 * The part of the given album that the file of the given name belongs to: the part of that name,
	 * else the part whose companion it is; <code>null</code> for neither.
	 */
	public static ImagePart partOf(AlbumInfo album, String name) {
		ImagePart companionOf = null;
		for (ImagePart image : images(album)) {
			if (name.equals(image.getName())) {
				return image;
			}
			if (companionOf == null && name.equals(image.getRaw())) {
				companionOf = image;
			}
		}
		return companionOf;
	}

	/**
	 * The given album with the file of the given name gone from its folder (set aside as a
	 * duplicate, see {@link MoveService#setAsideDuplicates}): a companion that left is no companion
	 * any more, a photograph that left while its companion stays is the raw now, and one without a
	 * companion leaves the album.
	 */
	public static void fileLeft(AlbumInfo album, String name) {
		ImagePart image = partOf(album, name);
		if (image == null) {
			return;
		}
		if (!name.equals(image.getName())) {
			image.setRaw("");
			return;
		}
		String raw = image.getRaw();
		if (raw != null && !raw.isEmpty()) {
			rename(album, image, raw);
			image.setRaw("");
			UpdateTransient.updateTransient(album);
			return;
		}
		MoveService.detach(album, image);
	}

	/**
	 * Makes the companions the given album states true to the given files, see the class comment.
	 *
	 * @param files
	 *        The photographs of the album's folder, as the loader lists them.
	 * @param analyzer
	 *        Analyses a JPEG that is to take over the part of its raw.
	 * @return What is left to the loader: which files are no new photograph, and which raw a new one
	 *         carries.
	 */
	public static Plan reconcile(AlbumInfo album, File[] files, Analyzer analyzer) {
		Plan plan = new Plan();
		UpdateTransient.updateTransient(album);
		Map<String, File> present = new HashMap<>();
		for (File file : files) {
			present.put(file.getName(), file);
		}
		Map<String, ImagePart> byName = new HashMap<>(album.getImageByName());
		List<ImagePart> parts = images(album);

		// 1. A stated companion that is not there, or no companion at all, is none.
		Set<String> claimed = new HashSet<>();
		for (ImagePart image : parts) {
			String raw = image.getRaw();
			if (raw == null || raw.isEmpty()) {
				continue;
			}
			if (!isPlainName(raw) || !RawFile.isRawName(raw) || raw.equals(image.getName())
				|| !present.containsKey(raw) || !claimed.add(raw)) {
				image.setRaw("");
			}
		}

		// A claimed raw that is listed as a photograph of its own too is merged into its claimer.
		boolean merged = false;
		for (ImagePart image : parts) {
			String raw = image.getRaw();
			ImagePart own = raw.isEmpty() ? null : byName.get(raw);
			if (own == null || own == image || !present.containsKey(image.getName())) {
				continue;
			}
			merge(image, own);
			MoveService.detach(album, own);
			renameCover(album, raw, image.getName());
			byName.remove(raw);
			merged = true;
		}
		if (merged) {
			UpdateTransient.updateTransient(album);
			parts = images(album);
		}

		// 2. A photograph whose own file is gone and whose companion is there is the raw now.
		boolean renamed = false;
		for (ImagePart image : parts) {
			String raw = image.getRaw();
			if (raw.isEmpty() || present.containsKey(image.getName()) || byName.containsKey(raw)) {
				continue;
			}
			byName.remove(image.getName());
			rename(album, image, raw);
			image.setRaw("");
			byName.put(raw, image);
			claimed.remove(raw);
			renamed = true;
		}
		if (renamed) {
			UpdateTransient.updateTransient(album);
		}

		// 3. A raw nobody claims is paired with the photograph of its base name.
		Map<String, List<String>> photosByBase = new TreeMap<>();
		List<String> raws = new ArrayList<>();
		for (String name : present.keySet()) {
			if (RawFile.isRawName(name)) {
				raws.add(name);
			} else if (isCompanionType(name)) {
				photosByBase.computeIfAbsent(base(name), b -> new ArrayList<>()).add(name);
			}
		}
		Collections.sort(raws);
		for (List<String> names : photosByBase.values()) {
			Collections.sort(names);
		}
		Set<String> paired = new HashSet<>();
		for (ImagePart image : images(album)) {
			if (!image.getRaw().isEmpty()) {
				paired.add(image.getName());
			}
		}
		boolean changed = false;
		for (String raw : raws) {
			if (claimed.contains(raw)) {
				plan._skip.add(raw);
				continue;
			}
			String photo = null;
			for (String candidate : photosByBase.getOrDefault(base(raw), Collections.emptyList())) {
				if (!paired.contains(candidate)) {
					photo = candidate;
					break;
				}
			}
			if (photo == null) {
				// A raw of its own.
				continue;
			}
			ImagePart rawPart = byName.get(raw);
			ImagePart photoPart = byName.get(photo);
			if (photoPart != null && rawPart != null) {
				merge(photoPart, rawPart);
				MoveService.detach(album, rawPart);
				renameCover(album, raw, photo);
				photoPart.setRaw(raw);
				byName.remove(raw);
				changed = true;
			} else if (photoPart != null) {
				photoPart.setRaw(raw);
			} else if (rawPart != null) {
				ImagePart analysed = analyzer.analyze(present.get(photo));
				if (analysed == null) {
					// The JPEG cannot be read: the raw stays the photograph it was.
					continue;
				}
				adopt(album, rawPart, analysed, raw);
				byName.remove(raw);
				byName.put(photo, rawPart);
				plan._skip.add(photo);
				changed = true;
			} else {
				plan._rawFor.put(photo, raw);
			}
			paired.add(photo);
			plan._skip.add(raw);
		}
		if (changed) {
			UpdateTransient.updateTransient(album);
		}
		return plan;
	}

	/**
	 * Merges the part a raw was listed under into the part of its JPEG, both stored, see the class
	 * comment: the JPEG's part wins field by field, a field it leaves empty is taken from the raw's,
	 * and the privacy is the stricter of the two, so that a merge never shows anybody a photograph
	 * one of the two parts hid from them.
	 */
	static void merge(ImagePart photo, ImagePart raw) {
		if (photo.getRating() == 0) {
			photo.setRating(raw.getRating());
		}
		photo.setPrivacy(Math.max(photo.getPrivacy(), raw.getPrivacy()));
		if (isBlank(photo.getComment())) {
			photo.setComment(raw.getComment());
		}
		if (photo.getOrientation() == Orientation.IDENTITY) {
			photo.setOrientation(raw.getOrientation());
		}
		if (isBlank(photo.getCamera())) {
			photo.setCamera(raw.getCamera());
		}
		if (photo.getLocation() == null && raw.getLocation() != null) {
			photo.setLocation(raw.getLocation());
		}
		if (photo.getTags().isEmpty() && !raw.getTags().isEmpty()) {
			photo.setTags(new ArrayList<>(raw.getTags()));
		}
	}

	/**
	 * Makes the stored part of a raw the part of the JPEG that arrived after it, see the class
	 * comment: everything stored about it stays, the name, kind and size become the JPEG's, and a
	 * camera or position the raw's part lacks is taken from the JPEG.
	 */
	static void adopt(AlbumInfo album, ImagePart rawPart, ImagePart analysed, String raw) {
		rename(album, rawPart, analysed.getName());
		rawPart.setKind(analysed.getKind());
		rawPart.setWidth(analysed.getWidth());
		rawPart.setHeight(analysed.getHeight());
		if (isBlank(rawPart.getCamera())) {
			rawPart.setCamera(analysed.getCamera());
		}
		if (rawPart.getLocation() == null && analysed.getLocation() != null) {
			rawPart.setLocation(analysed.getLocation());
		}
		rawPart.setRaw(raw);
	}

	/** Renames the given part, its album's index picture following. */
	private static void rename(AlbumInfo album, ImagePart image, String newName) {
		String old = image.getName();
		image.setName(newName);
		renameCover(album, old, newName);
	}

	private static void renameCover(AlbumInfo album, String old, String newName) {
		ThumbnailInfo cover = album.getIndexPicture();
		if (cover != null && old.equals(cover.getImage())) {
			cover.setImage(newName);
		}
	}

	/** Every photograph of the given album, the members of a group one by one, in album order. */
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

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private static boolean isPlainName(String name) {
		return !name.isEmpty() && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.equals(".")
			&& !name.equals("..") && !name.startsWith(".");
	}
}
