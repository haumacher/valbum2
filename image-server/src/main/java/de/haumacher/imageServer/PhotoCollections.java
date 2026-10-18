/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
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
 * The references of issue #221 and issue #307: parts of an album that stand for photographs of other
 * albums of the same space instead of holding files.
 *
 * <p>
 * Every album may hold references beside its own files. A reference is an {@link ImagePart} of the
 * album's <code>index.json</code> carrying a {@link PhotoRef}; its stored form holds nothing but its
 * name (unique in the album, among its files too), the reference, the album's own labels and own
 * description of it, and the photograph's recording time as last seen (a hint, like the path, that
 * places it among the album's photographs by date) &mdash; see {@link #stored(ImagePart)}. Nothing is
 * ever copied on disk: everything else about the photograph is read from, and written to, the album
 * it lies in.
 * </p>
 *
 * <p>
 * A <em>collection</em> (issue #221) is an album without files of its own. It is stored with the
 * kind {@link AlbumKind#COLLECTION} while it holds references and no file, so that a build older
 * than issue #307, which knows references in a collection only, still reads it unchanged; see
 * {@link #storedKind(AlbumInfo, boolean)}. This build reads that kind as an album.
 * </p>
 *
 * <ul>
 * <li><b>Resolution.</b> A reference is found by its content hash: first at the path it was last
 * seen at (one look into that folder's <code>.hashes.json</code>), then through the space's
 * {@link HashIndex} (issue #118), so it survives a move of the photograph, a rename of its album
 * and a rename of the file. A hash the space no longer holds is a <em>missing</em> part.</li>
 * <li><b>Visibility.</b> A reference is answered exactly as the photograph would be answered in its
 * own album to the same caller &mdash; privacy, the rating floor and the trash, the inbox rule, faces
 * &mdash; see {@link #resolve(AlbumInfo, Function, boolean, boolean)}; the album's own filters (a
 * share link's privacy, rating and label) then apply on top, its label filter to the album's own
 * labels.</li>
 * <li><b>Addresses.</b> A reference is addressed <code>&lt;album&gt;/&lt;name&gt;</code> like any
 * photograph of an album, and the server serves the source file behind it, so a link confined to
 * the album reaches what the album shows and nothing else.</li>
 * <li><b>Not a copy.</b> A reference is no file of its album: the search, the duplicates, the faces,
 * the places, the bursts and the news know the photograph where it lies, once.</li>
 * </ul>
 */
public final class PhotoCollections {

	/** The message a gone photograph is answered with where its picture is asked for. */
	public static final String MISSING =
		"This photo is no longer in the library; remove it from the album.";

	/** The message an addition to something that is no album is refused with. */
	public static final String NOT_A_COLLECTION =
		"Photos are added as references to an album only, not to a folder, the inbox or a saved search.";

	/** The message an addition from something that is no album is refused with. */
	public static final String NOT_AN_ALBUM = "Photos are added as references from an album or a search.";

	/** The message an addition through a share link is refused with. */
	public static final String COLLECT_REFUSED = "A share link cannot add references to an album.";

	/** The message an unreadable addition is refused with. */
	public static final String COLLECT_UNREADABLE = "The photos to add to the album cannot be read.";

	/** The message the face editing of a reference is refused with. */
	public static final String FACES_REFUSED = "Faces are named in the photo's own album, not where it is referenced.";

	/** The outcome of a reference taken out of an album. */
	public static final String REMOVED = "Removed from the album; the photo stays in its own album.";

	/** The message a reference moved into or out of the inbox or a saved search is refused with. */
	public static final String MOVE_REFUSED = "A reference can be moved to another album only.";

	/** The message an upload under the name of a reference is refused with. */
	public static String nameTaken(String name) {
		return "The album holds a reference named '" + name + "'.";
	}

	/** The message a photograph the album already holds or references is answered with. */
	public static String alreadyCollected(String name) {
		return "Already in the album as '" + name + "'.";
	}

	private final Path _root;

	private final HashIndex _index;

	/**
	 * Creates the references of the space at the given root.
	 *
	 * @param index
	 *        The hash index of that space, asked where a photograph is now.
	 */
	public PhotoCollections(Path root, HashIndex index) {
		_root = root.toAbsolutePath().normalize();
		_index = index;
	}

	/**
	 * Whether the given folder resource is <em>stored</em> as a collection (an album of references
	 * and no files, see {@link #storedKind(AlbumInfo, boolean)}). This build reads it as an album;
	 * the question matters only where an older build is concerned.
	 */
	public static boolean isCollection(Resource resource) {
		return resource instanceof AlbumInfo && ((AlbumInfo) resource).getKind() == AlbumKind.COLLECTION;
	}

	/** Whether the given part is a reference to a photograph of another album. */
	public static boolean isReference(ImagePart part) {
		return part != null && part.getRef() != null;
	}

	/**
	 * Whether the given part is a reference as an album stores it, not resolved to its photograph,
	 * or one whose photograph is gone: a part without a picture.
	 */
	public static boolean isUnresolved(ImagePart part) {
		return isReference(part) && (part.isMissing() || (part.getWidth() == 0 && part.getHeight() == 0));
	}

	/** Whether the given folder resource is an album holding at least one reference. */
	public static boolean holdsReferences(Resource resource) {
		if (!(resource instanceof AlbumInfo) || ((AlbumInfo) resource).getKind() == AlbumKind.SEARCH) {
			return false;
		}
		for (AlbumPart part : ((AlbumInfo) resource).getParts()) {
			if (part instanceof ImagePart) {
				if (isReference((ImagePart) part)) {
					return true;
				}
			} else if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					if (isReference(member)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Whether the given folder holds references, read from its sidecar alone. */
	public static boolean holdsReferences(File folder) {
		return folder != null && folder.isDirectory() && holdsReferences(ResourceCache.sidecar(folder));
	}

	/** The names of the references of the given folder, read from its sidecar alone. */
	public static Set<String> referenceNames(File folder) {
		Set<String> result = new HashSet<>();
		if (folder == null || !new File(folder, "index.json").isFile()) {
			return result;
		}
		Resource sidecar = ResourceCache.sidecar(folder);
		if (sidecar instanceof AlbumInfo && ((AlbumInfo) sidecar).getKind() != AlbumKind.SEARCH) {
			for (ImagePart reference : references((AlbumInfo) sidecar)) {
				result.add(reference.getName());
			}
		}
		return result;
	}

	/** The names of the references of the given album. */
	public static Set<String> referenceNames(AlbumInfo album) {
		Set<String> result = new HashSet<>();
		for (ImagePart reference : references(album)) {
			result.add(reference.getName());
		}
		return result;
	}

	/** The reference of the given name in the given album, <code>null</code> if it holds none. */
	public static ImagePart referenceNamed(AlbumInfo album, String name) {
		if (name == null || album == null || album.getKind() == AlbumKind.SEARCH) {
			return null;
		}
		for (ImagePart reference : references(album)) {
			if (name.equals(reference.getName())) {
				return reference;
			}
		}
		return null;
	}

	/** The reference of the given name in the given folder resource, <code>null</code> if none. */
	public static ImagePart referenceNamed(Resource resource, String name) {
		return resource instanceof AlbumInfo ? referenceNamed((AlbumInfo) resource, name) : null;
	}

	/** Every reference of the given album, in its order. */
	public static List<ImagePart> references(AlbumInfo album) {
		List<ImagePart> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && isReference((ImagePart) part)) {
				result.add((ImagePart) part);
			} else if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					if (isReference(member)) {
						result.add(member);
					}
				}
			}
		}
		return result;
	}

	/** Every photograph of the given album that is a file of its own, the members of a group one by one. */
	public static List<ImagePart> ownImages(AlbumInfo album) {
		List<ImagePart> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				if (!isReference((ImagePart) part)) {
					result.add((ImagePart) part);
				}
			} else if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					if (!isReference(member)) {
						result.add(member);
					}
				}
			}
		}
		return result;
	}

	/**
	 * The given album without its references: what lies in its folder. The album itself where it
	 * holds none; otherwise a copy of its statements sharing its other parts.
	 */
	public static AlbumInfo ownOnly(AlbumInfo album) {
		if (!holdsReferences(album)) {
			return album;
		}
		AlbumInfo result = statements(album);
		if (album.getIndexPicture() != null) {
			result.setIndexPicture(album.getIndexPicture());
		}
		List<AlbumPart> parts = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				if (!isReference((ImagePart) part)) {
					parts.add(part);
				}
			} else if (part instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) part;
				List<ImagePart> members = new ArrayList<>();
				for (ImagePart member : group.getImages()) {
					if (!isReference(member)) {
						members.add(member);
					}
				}
				if (members.size() == group.getImages().size()) {
					parts.add(part);
				} else if (members.size() == 1) {
					parts.add(members.get(0));
				} else if (!members.isEmpty()) {
					parts.add(ImageGroup.create().setImages(members).setRepresentative(0));
				}
			} else {
				parts.add(part);
			}
		}
		result.setParts(parts);
		return result;
	}

	/** A copy of the given album's own statements, without parts and picture. */
	private static AlbumInfo statements(AlbumInfo album) {
		return AlbumInfo.create()
			.setKind(album.getKind())
			.setQuery(album.getQuery())
			.setSearchProblem(album.getSearchProblem())
			.setTitle(album.getTitle())
			.setSubTitle(album.getSubTitle())
			// A star is the album's own statement, see issue #239.
			.setStarred(album.isStarred())
			.setPlaceCaption(album.getPlaceCaption())
			.setDate(album.getDate())
			.setEffectiveDate(album.getEffectiveDate())
			.setFacesPending(album.isFacesPending())
			.setPreparing(album.isPreparing())
			.setRights(album.getRights());
	}

	/**
	 * The kind the given album is stored with, see issue #307.
	 *
	 * <p>
	 * An album that holds references and no file of its own is stored as a
	 * {@link AlbumKind#COLLECTION} (and so is a collection that holds nothing yet): a build older than issue #307 knows references in a collection
	 * only, reads it unchanged and keeps its references through its own writes. Every other album is
	 * stored as {@link AlbumKind#ALBUM} &mdash; an older build shows its files and not its
	 * references, and its next write of the album drops them (the sidecar it replaces is kept as the
	 * timestamped backup every write leaves). A saved search keeps its kind. This build reads either
	 * kind as an album.
	 * </p>
	 *
	 * @param holdsFiles
	 *        Whether the folder holds a photograph of its own, whether or not the sidecar lists it.
	 */
	public static AlbumKind storedKind(AlbumInfo album, boolean holdsFiles) {
		AlbumKind kind = album.getKind();
		if (kind == AlbumKind.SEARCH) {
			return kind;
		}
		boolean collection = !references(album).isEmpty() || kind == AlbumKind.COLLECTION;
		return !holdsFiles && ownImages(album).isEmpty() && collection ? AlbumKind.COLLECTION : AlbumKind.ALBUM;
	}

	/**
	 * Reduces every reference of the given album to its {@link #stored(ImagePart) stored form}, see
	 * {@link de.haumacher.imageServer.AlbumDate#clearDerived}: what the photograph says is the
	 * photograph's own and never stored where it is referenced.
	 *
	 * @return Whether anything changed.
	 */
	public static boolean clearDerived(AlbumInfo album) {
		if (album.getKind() == AlbumKind.SEARCH) {
			return false;
		}
		boolean changed = false;
		List<AlbumPart> parts = album.getParts();
		for (int n = 0; n < parts.size(); n++) {
			AlbumPart part = parts.get(n);
			if (part instanceof ImagePart) {
				ImagePart image = (ImagePart) part;
				if (isReference(image)) {
					ImagePart stored = stored(image);
					if (!same(stored, image)) {
						parts.set(n, stored);
						changed = true;
					}
				}
			} else if (part instanceof ImageGroup) {
				List<ImagePart> members = ((ImageGroup) part).getImages();
				for (int m = 0; m < members.size(); m++) {
					ImagePart member = members.get(m);
					if (isReference(member)) {
						ImagePart stored = stored(member);
						if (!same(stored, member)) {
							members.set(m, stored);
							changed = true;
						}
					}
				}
			}
		}
		return changed;
	}

	private static boolean same(ImagePart a, ImagePart b) {
		return json(a).equals(json(b));
	}

	private static String json(ImagePart part) {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			part.writeTo(json);
		} catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}

	/**
	 * Where the photograph of the given reference lies now, <code>null</code> if the space no longer
	 * holds its contents.
	 *
	 * <p>
	 * Reading never writes: the hint stored with the reference is refreshed by the album's next
	 * write, not here.
	 * </p>
	 */
	public PathInfo locate(PhotoRef ref) {
		return locate(ref, new HashMap<>());
	}

	/**
	 * {@link #locate(PhotoRef)} with the hash sidecars read so far, so that an album of a hundred
	 * references to one album reads that album's sidecar once.
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
	 * The given album with its references resolved for a caller, see the class comment.
	 *
	 * <p>
	 * A part that is a file of the album is the very part given; a reference becomes a copy of the
	 * photograph as the caller is answered it in its own album, named as the album names it, with
	 * the album's own labels and the album's own description where it has one (the photograph's
	 * otherwise). An album without files of its own (a collection) that chose no picture, or one the
	 * caller may not see, is shown by the first photograph the caller may see; every other album's
	 * picture is the album's choice, which the privacy filter replaces where it is hidden.
	 * </p>
	 *
	 * @param album
	 *        What the cache holds; never modified.
	 * @param sources
	 *        The album of a folder as this caller is answered it in that album &mdash; the inbox
	 *        rule, privacy, rating floor and faces applied &mdash;, <code>null</code> where the
	 *        caller is shown nothing there. Asked once per folder.
	 * @param showMissing
	 *        Whether a reference whose photograph is gone is answered, as {@link ImagePart#isMissing()
	 *        missing}: to the editors of the album, who can remove it.
	 * @param showRefs
	 *        Whether the {@link ImagePart#getRef() reference} is answered: to the members of the
	 *        space alone.
	 * @return A new album; its resolved parts are copies.
	 */
	public AlbumInfo resolve(AlbumInfo album, Function<PathInfo, AlbumInfo> sources, boolean showMissing,
			boolean showRefs) {
		AlbumInfo result = statements(album);
		Map<File, Map<String, String>> sidecars = new HashMap<>();
		Map<PathInfo, AlbumInfo> albums = new HashMap<>();
		Set<String> shown = new HashSet<>();
		List<AlbumPart> parts = new ArrayList<>();
		boolean ownFiles = false;
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				ImagePart image = (ImagePart) part;
				if (!isReference(image)) {
					parts.add(image);
					ownFiles = true;
					continue;
				}
				ImagePart answer = resolvePart(image, sources, showMissing, showRefs, sidecars, albums);
				if (answer != null) {
					parts.add(answer);
					shown.add(answer.getName());
				}
			} else if (part instanceof ImageGroup) {
				ImageGroup group = (ImageGroup) part;
				List<ImagePart> members = new ArrayList<>();
				boolean changed = false;
				for (ImagePart member : group.getImages()) {
					if (!isReference(member)) {
						members.add(member);
						ownFiles = true;
						continue;
					}
					changed = true;
					ImagePart answer = resolvePart(member, sources, showMissing, showRefs, sidecars, albums);
					if (answer != null) {
						members.add(answer);
						shown.add(answer.getName());
					}
				}
				if (!changed) {
					parts.add(group);
				} else if (members.size() == 1) {
					parts.add(members.get(0));
				} else if (!members.isEmpty()) {
					String head = representative(group);
					int index = 0;
					for (int n = 0; n < members.size(); n++) {
						if (members.get(n).getName().equals(head)) {
							index = n;
						}
					}
					parts.add(ImageGroup.create().setImages(members).setRepresentative(index));
				}
			} else {
				parts.add(part);
			}
		}
		result.setParts(parts);

		ThumbnailInfo cover = album.getIndexPicture();
		if (ownFiles) {
			// The album's own choice, which the privacy filter replaces where it is hidden.
			if (cover != null) {
				result.setIndexPicture(cover);
			}
		} else if (cover != null && shown.contains(cover.getImage()) && !isMissing(result, cover.getImage())) {
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

	private static String representative(ImageGroup group) {
		int index = group.getRepresentative();
		List<ImagePart> images = group.getImages();
		return index >= 0 && index < images.size() ? images.get(index).getName() : null;
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

	/**
	 * The given reference as the caller is answered it, see
	 * {@link #resolve(AlbumInfo, Function, boolean, boolean)}; <code>null</code> where the caller is
	 * shown nothing of it.
	 */
	public ImagePart resolveOne(ImagePart reference, Function<PathInfo, AlbumInfo> sources, boolean showMissing,
			boolean showRefs) {
		return resolvePart(reference, sources, showMissing, showRefs, new HashMap<>(), new HashMap<>());
	}

	private ImagePart resolvePart(ImagePart reference, Function<PathInfo, AlbumInfo> sources, boolean showMissing,
			boolean showRefs, Map<File, Map<String, String>> sidecars, Map<PathInfo, AlbumInfo> albums) {
		PhotoRef ref = reference.getRef();
		PathInfo image = locate(ref, sidecars);
		if (image == null) {
			if (!showMissing) {
				return null;
			}
			return ImagePart.create().setName(reference.getName()).setMissing(true)
				.setComment(reference.getComment())
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
		if (source == null || isReference(source)) {
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
		if (!reference.getComment().isEmpty()) {
			// The album's own description of the photograph, see issue #307.
			answer.setComment(reference.getComment());
		}
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
	 * The stored form of a reference: its name, its reference, its labels, its own description and
	 * the recording time of its photograph as last seen, nothing else.
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

	/** The stored form of the given reference, see {@link #stored(String, PhotoRef, List)}. */
	public static ImagePart stored(ImagePart reference) {
		return stored(reference.getName(), reference.getRef(), reference.getLabels())
			.setComment(reference.getComment() == null ? "" : reference.getComment())
			.setDate(reference.getDate());
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

	/** The references of the given album by their name, in its order. */
	static Map<String, ImagePart> byName(AlbumInfo album) {
		Map<String, ImagePart> result = new LinkedHashMap<>();
		for (ImagePart reference : references(album)) {
			result.putIfAbsent(reference.getName(), reference);
		}
		return result;
	}
}
