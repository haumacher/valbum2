/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.PhotoRef;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.imageServer.upload.HashIndex;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Collections of issue #221: albums whose parts reference photographs of other albums of the same
 * space instead of holding files.
 *
 * <p>
 * A collection is an ordinary album folder whose <code>index.json</code> says
 * {@link AlbumKind#COLLECTION} and whose parts hold nothing but their name (unique in the
 * collection), a {@link PhotoRef} and the collection's own labels &mdash; the stored form pinned by
 * <code>src/test/fixtures/collection/index.json</code>. The folder holds no photograph, and nothing
 * is ever copied: everything about a referenced photograph is read from, and written to, the album
 * it lies in.
 * </p>
 *
 * <ul>
 * <li><b>Resolution.</b> A reference is found by its content hash: first at the path it was last
 * seen at (one look into that folder's <code>.hashes.json</code>), then through the space's
 * {@link HashIndex} (issue #118), so it survives a move of the photograph, a rename of its album
 * and a rename of the file. A hash the space no longer holds is a <em>missing</em> part.</li>
 * <li><b>Visibility.</b> A part is answered exactly as the photograph would be answered in its own
 * album to the same caller &mdash; privacy, the rating floor and the trash, the inbox rule, faces
 * &mdash; see {@link #resolve(AlbumInfo, Function, boolean, boolean)}; a share link on the
 * collection then applies its own filters on top, its label filter to the collection's own
 * labels.</li>
 * <li><b>Addresses.</b> A part is addressed <code>&lt;collection&gt;/&lt;name&gt;</code> like any
 * photograph of an album, and the server serves the source file behind it, so a link confined to
 * the collection reaches what the collection shows and nothing else.</li>
 * </ul>
 */
public final class PhotoCollections {

	/** The message a gone photograph is answered with where its picture is asked for. */
	public static final String MISSING =
		"This photo is no longer in the library; remove it from the collection.";

	/** The message an upload into a collection is refused with. */
	public static final String UPLOAD_REFUSED =
		"A collection holds no files of its own: upload into an album and add the photos to the collection from there.";

	/** The message a move into or out of a collection is refused with. */
	public static final String MOVE_REFUSED =
		"A collection holds references, not files: add photos with 'Add to collection' and take them out with 'Remove from collection'.";

	/** The message a folder created inside a collection is refused with. */
	public static final String FOLDER_REFUSED = "A collection holds photos only, no albums or folders.";

	/** The message an addition to something that is no collection is refused with. */
	public static final String NOT_A_COLLECTION = "Photos can only be added to a collection.";

	/** The message an addition from something that is no album is refused with. */
	public static final String NOT_AN_ALBUM = "Photos are added to a collection from an album or another collection.";

	/** The message an addition through a share link is refused with. */
	public static final String COLLECT_REFUSED = "A share link cannot add photos to a collection.";

	/** The message an unreadable addition is refused with. */
	public static final String COLLECT_UNREADABLE = "The photos to add to the collection cannot be read.";

	/** The message the face editing of a collection is refused with. */
	public static final String FACES_REFUSED = "Faces are named in the photo's own album, not in a collection.";

	/** The outcome of a reference taken out of a collection. */
	public static final String REMOVED = "Removed from the collection; the photo stays in its album.";

	/** The message a photograph the collection already references is answered with. */
	public static String alreadyCollected(String name) {
		return "Already in the collection as '" + name + "'.";
	}

	private final Path _root;

	private final HashIndex _index;

	/**
	 * Creates the collections of the space at the given root.
	 *
	 * @param index
	 *        The hash index of that space, asked where a photograph is now.
	 */
	public PhotoCollections(Path root, HashIndex index) {
		_root = root.toAbsolutePath().normalize();
		_index = index;
	}

	/** Whether the given folder resource is a collection. */
	public static boolean isCollection(Resource resource) {
		return resource instanceof AlbumInfo && ((AlbumInfo) resource).getKind() == AlbumKind.COLLECTION;
	}

	/** Whether the given folder is a collection, read from its sidecar alone. */
	public static boolean isCollection(File folder) {
		return folder != null && folder.isDirectory() && isCollection(ResourceCache.sidecar(folder));
	}

	/** The reference of the given name in the given collection, <code>null</code> if it holds none. */
	public static ImagePart referenceNamed(AlbumInfo collection, String name) {
		if (name == null) {
			return null;
		}
		for (AlbumPart part : collection.getParts()) {
			if (part instanceof ImagePart && name.equals(((ImagePart) part).getName())
				&& ((ImagePart) part).getRef() != null) {
				return (ImagePart) part;
			}
		}
		return null;
	}

	/** Every reference of the given collection, in its order. */
	public static List<ImagePart> references(AlbumInfo collection) {
		List<ImagePart> result = new ArrayList<>();
		for (AlbumPart part : collection.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getRef() != null) {
				result.add((ImagePart) part);
			} else if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					if (member.getRef() != null) {
						result.add(member);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Where the photograph of the given reference lies now, <code>null</code> if the space no longer
	 * holds its contents.
	 *
	 * <p>
	 * Reading never writes: the hint stored with the reference is refreshed by the collection's
	 * next write, not here.
	 * </p>
	 */
	public PathInfo locate(PhotoRef ref) {
		return locate(ref, new HashMap<>());
	}

	/**
	 * {@link #locate(PhotoRef)} with the hash sidecars read so far, so that a collection of a
	 * hundred photographs of one album reads that album's sidecar once.
	 */
	public PathInfo locate(PhotoRef ref, Map<File, Map<String, String>> sidecars) {
		if (ref == null || ref.getHash().isEmpty()) {
			return null;
		}
		String hash = ref.getHash();
		String hint = ref.getPath();
		if (!hint.isEmpty() && LibraryFiles.isLibraryPath(hint)) {
			PathInfo hinted = pathOf(hint);
			if (hinted != null) {
				File folder = hinted.toFile().getParentFile();
				Map<String, String> hashes =
					sidecars.computeIfAbsent(folder, f -> new HashCache(f).storedHashByName());
				if (hash.equals(hashes.get(hinted.getName())) && hinted.toFile().isFile()) {
					return hinted;
				}
				// Renamed in its own folder.
				for (Map.Entry<String, String> entry : hashes.entrySet()) {
					if (hash.equals(entry.getValue())) {
						PathInfo renamed = hinted.parent().child(entry.getKey());
						if (renamed.toFile().isFile()) {
							return renamed;
						}
					}
				}
			}
		}
		String found = _index.pathOf(hash);
		if (found == null || !LibraryFiles.isLibraryPath(found)) {
			return null;
		}
		return pathOf(found);
	}

	/** The given path relative to the space root, <code>null</code> where it leaves the space. */
	private PathInfo pathOf(String relative) {
		Path path = Paths.get(relative).normalize();
		if (path.isAbsolute() || path.toString().isEmpty() || path.startsWith("..")) {
			return null;
		}
		return new PathInfo(_root, path);
	}

	/** The path of the given photograph relative to the space root, <code>/</code>-separated. */
	public String relative(PathInfo image) {
		return _root.relativize(image.toFile().toPath().toAbsolutePath().normalize()).toString()
			.replace(File.separatorChar, '/');
	}

	/**
	 * The given collection as a caller is answered it, see the class comment.
	 *
	 * @param collection
	 *        What the cache holds; never modified.
	 * @param sources
	 *        The album of a folder as this caller is answered it in that album &mdash; the inbox
	 *        rule, privacy, rating floor and faces applied &mdash;, <code>null</code> where the
	 *        caller is shown nothing there. Asked once per folder.
	 * @param showMissing
	 *        Whether a reference whose photograph is gone is answered, as {@link ImagePart#isMissing()
	 *        missing}: to the editors of the collection, who can remove it.
	 * @param showRefs
	 *        Whether the {@link ImagePart#getRef() reference} is answered: to the members of the
	 *        space alone.
	 * @return A new album; its parts are copies.
	 */
	public AlbumInfo resolve(AlbumInfo collection, Function<PathInfo, AlbumInfo> sources, boolean showMissing,
			boolean showRefs) {
		AlbumInfo result = AlbumInfo.create()
			.setKind(AlbumKind.COLLECTION)
			.setTitle(collection.getTitle())
			.setSubTitle(collection.getSubTitle())
			.setDate(collection.getDate())
			.setEffectiveDate(collection.getEffectiveDate());
		Map<File, Map<String, String>> sidecars = new HashMap<>();
		Map<PathInfo, AlbumInfo> albums = new HashMap<>();
		Set<String> shown = new HashSet<>();
		List<AlbumPart> parts = new ArrayList<>();
		for (AlbumPart part : collection.getParts()) {
			if (part instanceof Heading) {
				parts.add(part);
				continue;
			}
			for (ImagePart reference : partsOf(part)) {
				ImagePart answer = resolvePart(reference, sources, showMissing, showRefs, sidecars, albums);
				if (answer != null) {
					parts.add(answer);
					shown.add(answer.getName());
				}
			}
		}
		result.setParts(parts);

		ThumbnailInfo cover = collection.getIndexPicture();
		if (cover != null && shown.contains(cover.getImage()) && !isMissing(result, cover.getImage())) {
			result.setIndexPicture(cover);
		} else {
			// A picture this caller may not see is never the cover; the first one they may is.
			ImagePart first = null;
			for (AlbumPart part : parts) {
				if (part instanceof ImagePart && !((ImagePart) part).isMissing()) {
					first = (ImagePart) part;
					break;
				}
			}
			if (first != null) {
				result.setIndexPicture(PrivacyFilter.thumbnail(first));
			}
		}
		return result;
	}

	private static boolean isMissing(AlbumInfo album, String name) {
		ImagePart part = referenceOrPart(album, name);
		return part == null || part.isMissing();
	}

	private static ImagePart referenceOrPart(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		return null;
	}

	/** The photographs of a part: a plain part itself, a group (which a collection never stores) its members. */
	private static List<ImagePart> partsOf(AlbumPart part) {
		List<ImagePart> result = new ArrayList<>();
		if (part instanceof ImagePart) {
			result.add((ImagePart) part);
		} else if (part instanceof ImageGroup) {
			result.addAll(((ImageGroup) part).getImages());
		}
		return result;
	}

	private ImagePart resolvePart(ImagePart reference, Function<PathInfo, AlbumInfo> sources, boolean showMissing,
			boolean showRefs, Map<File, Map<String, String>> sidecars, Map<PathInfo, AlbumInfo> albums) {
		PhotoRef ref = reference.getRef();
		if (ref == null) {
			// A part without a reference stands for nothing in a collection.
			return null;
		}
		PathInfo image = locate(ref, sidecars);
		if (image == null) {
			if (!showMissing) {
				return null;
			}
			return ImagePart.create().setName(reference.getName()).setMissing(true)
				.setRef(PhotoRef.create().setHash(ref.getHash()).setPath(ref.getPath()));
		}
		PathInfo folder = image.parent();
		AlbumInfo album;
		if (albums.containsKey(folder)) {
			album = albums.get(folder);
		} else {
			album = sources.apply(folder);
			albums.put(folder, album);
		}
		if (album == null) {
			return null;
		}
		ImagePart source = Crops.findImage(album, image.getName());
		if (source == null) {
			// Hidden from this caller in its own album: not there for them here either.
			return null;
		}
		ImagePart answer = copy(source);
		answer.setName(reference.getName());
		List<LabelName> labels = new ArrayList<>();
		for (LabelName label : reference.getLabels()) {
			labels.add(LabelName.create().setName(label.getName()));
		}
		answer.setLabels(labels);
		answer.setMissing(false);
		answer.setRef(showRefs ? PhotoRef.create().setHash(ref.getHash()).setPath(relative(image)) : null);
		return answer;
	}

	/** A deep copy of the given part, through the model's own reader and writer. */
	public static ImagePart copy(ImagePart part) {
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer,
				StandardCharsets.UTF_8)))) {
				part.writeTo(json);
			}
			return (ImagePart) AlbumPart.readAlbumPart(new JsonReader(new ReaderAdapter(new InputStreamReader(
				new ByteArrayInputStream(buffer.toByteArray()), StandardCharsets.UTF_8))));
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot copy a part: " + ex.getMessage(), ex);
		}
	}

	/**
	 * The stored form of a reference: its name, its reference and its labels, nothing else.
	 */
	public static ImagePart stored(String name, PhotoRef ref, List<LabelName> labels) {
		ImagePart result = ImagePart.create().setName(name)
			.setRef(PhotoRef.create().setHash(ref.getHash()).setPath(ref.getPath()));
		List<LabelName> copy = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (LabelName label : labels) {
			String text = label.getName() == null ? "" : label.getName().trim();
			if (!text.isEmpty() && seen.add(text)) {
				copy.add(LabelName.create().setName(text));
			}
		}
		result.setLabels(copy);
		return result;
	}

	/** A name the given names do not hold yet, built from the given file name as an upload's is. */
	public static String freeName(Set<String> taken, String fileName) {
		if (!taken.contains(fileName)) {
			return fileName;
		}
		int dot = fileName.lastIndexOf('.');
		String base = dot > 0 ? fileName.substring(0, dot) : fileName;
		String extension = dot > 0 ? fileName.substring(dot) : "";
		for (int n = 2;; n++) {
			String candidate = base + "-" + n + extension;
			if (!taken.contains(candidate)) {
				return candidate;
			}
		}
	}

	/** The references of the given collection by their name, in its order. */
	static Map<String, ImagePart> byName(AlbumInfo collection) {
		Map<String, ImagePart> result = new LinkedHashMap<>();
		for (ImagePart reference : references(collection)) {
			result.putIfAbsent(reference.getName(), reference);
		}
		return result;
	}
}
