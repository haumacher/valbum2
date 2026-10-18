/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.KeptPhoto;
import de.haumacher.imageServer.upload.HashCache;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deleting an original that other albums reference, see issue #307 (package 2): a delete never
 * leaves references into nothing.
 *
 * <p>
 * A photograph that is deleted where it lies &mdash; by the delete action, by a write rating it as
 * trash, with the album it lies in, or by the purge of the trash &mdash; while another album still
 * references it does not go into the trash. It moves into the album of its <em>oldest</em>
 * reference instead and takes that reference's place there: under the reference's name, in the
 * reference's place in the album's order, with the reference's labels and, where the reference has
 * a description of its own, that description (the original's travels with the file otherwise).
 * Everything else the photograph's own album knew about it travels with the file as in any move,
 * see {@link MoveService#rescue(PathInfo, PathInfo, java.util.List)}. Every other reference to it
 * follows by its hash; its path hint is refreshed in the same step, so that it resolves at the
 * first look. Only a photograph no surviving album references goes into the trash.
 * </p>
 *
 * <p>
 * <b>No reference points into the trash.</b> Throwing a photograph into the trash is deleting it,
 * so a photograph rated as trash is never referenced: adding a reference to one is refused (see
 * {@link PhotoCollections#TRASHED_REFUSED}). A reference into the trash that an older build left
 * is healed by the purge of that trash at the latest, before any file is destroyed, see
 * {@link DeleteService#purge(PathInfo, boolean)}.
 * </p>
 *
 * <p>
 * <b>The oldest reference.</b> References are ordered by the moment they were made
 * ({@link de.haumacher.imageServer.shared.model.PhotoRef#getCreated()}, stamped since this rule
 * exists), then by the path of their album relative to the space root, then by their name. A
 * reference made before the moment was recorded counts <code>0</code>: it is older than every
 * stamped one, and among such references the album path decides. The rule depends on nothing but
 * what is stored, so a dry run and the delete after it pick the same album.
 * </p>
 *
 * <p>
 * <b>Identical files.</b> A reference names contents, and the same contents may lie as files in
 * several albums (see {@link PhotoCollections#locate(de.haumacher.imageServer.shared.model.PhotoRef)}).
 * A reference <em>to</em> a photograph that leaves is one that resolves to its file and would find no
 * other file of its contents afterwards; one that would still find another &mdash; not rated as
 * trash, not leaving in the same step, and at least as private as the one it showed &mdash; keeps
 * nothing and follows that file instead, its hint pointed at it. So a copy of a photograph that is
 * thrown away while the original is kept is no photograph anybody still needs, and the rescue never
 * mistakes one for the other.
 * </p>
 *
 * <p>
 * <b>Chains.</b> An album that the same operation deletes (or one below it) is never a receiver:
 * the next reference is asked, and where every referencing album goes away too the photograph goes
 * into the trash with its album. The references an album held go with that album.
 * </p>
 *
 * <p>
 * <b>Rights.</b> The move is a consequence of the deleter's act and is carried out whether or not
 * the deleter may add to the receiving album: the photograph was shown there already, to the very
 * people who may see that album. It never lowers privacy: the file keeps the privacy and the rating
 * floor it had (the reference was shown by them), so nobody is shown more than before.
 * </p>
 *
 * <p>
 * Finding the references is a walk over the sidecars of the space, on a delete alone and never in a
 * listing (issue #286): a sidecar is parsed only where its text names one of the hashes deleted.
 * </p>
 */
public final class ReferencedOriginals {

	private static final Logger LOG = Logger.getLogger(ReferencedOriginals.class.getName());

	/** What a photograph a delete moved into an album that references it is reported with. */
	public static String kept(String title) {
		return "Still shown in '" + title + "': moved there in the place of its reference instead of into the trash.";
	}

	/** What a photograph a purge moved into an album that references it is reported with. */
	public static String keptInTrash(String title) {
		return "Still shown in '" + title + "': moved there in the place of its reference instead of being deleted.";
	}

	/** A photograph about to leave the library. */
	public static final class Doomed {
		final PathInfo _photo;

		final String _hash;

		final String _label;

		/**
		 * Creates a {@link Doomed}.
		 *
		 * @param photo
		 *        Where it lies.
		 * @param hash
		 *        Its content hash.
		 * @param label
		 *        How it is named in the answer, see {@link KeptPhoto#getPhoto()}.
		 */
		public Doomed(PathInfo photo, String hash, String label) {
			_photo = photo;
			_hash = hash;
			_label = label;
		}

		/** Where it lies. */
		public PathInfo photo() {
			return _photo;
		}

		/** How it is named in the answer. */
		public String label() {
			return _label;
		}
	}

	/** A reference to a photograph about to leave the library. */
	private static final class Holder {
		final PathInfo _album;

		final String _albumPath;

		final String _title;

		final String _name;

		final long _created;

		Holder(PathInfo album, String albumPath, String title, String name, long created) {
			_album = album;
			_albumPath = albumPath;
			_title = title;
			_name = name;
			_created = created;
		}
	}

	/** The order of the oldest reference first, see the class comment. */
	private static final Comparator<Holder> OLDEST_FIRST = Comparator.<Holder> comparingLong(h -> h._created)
		.thenComparing(h -> h._albumPath).thenComparing(h -> h._name);

	/** Where a referenced photograph goes instead of the trash. */
	public static final class Receiver {
		final Doomed _doomed;

		final Holder _holder;

		final List<Holder> _followers;

		/** The name the file is expected to have in the receiving album. */
		final String _name;

		Receiver(Doomed doomed, Holder holder, List<Holder> followers, String name) {
			_doomed = doomed;
			_holder = holder;
			_followers = followers;
			_name = name;
		}

		/** The photograph. */
		public Doomed doomed() {
			return _doomed;
		}

		/** What a dry run reports. */
		public KeptPhoto expected() {
			return KeptPhoto.create().setPhoto(_doomed._label).setAlbum(_holder._album.relativePath())
				.setTitle(_holder._title).setName(_name);
		}

		/** The title of the receiving album. */
		public String title() {
			return _holder._title;
		}
	}

	/**
	 * A reference that follows another file of the same contents instead of keeping a photograph
	 * that leaves the library, see the class comment: its hint is pointed at that file.
	 */
	private static final class Follow {
		final PathInfo _album;

		final String _name;

		final PathInfo _to;

		Follow(PathInfo album, String name, PathInfo to) {
			_album = album;
			_name = name;
			_to = to;
		}
	}

	/** What {@link ReferencedOriginals#plan(Collection, Collection)} found. */
	public static final class Plan {
		final List<Receiver> _receivers = new ArrayList<>();

		final List<Follow> _follows = new ArrayList<>();

		/** Where each photograph goes instead of the trash, in the order asked. */
		public List<Receiver> receivers() {
			return _receivers;
		}

		/** Whether nothing is to happen: no photograph is kept and no reference follows another file. */
		public boolean isEmpty() {
			return _receivers.isEmpty() && _follows.isEmpty();
		}
	}

	/** The root of the space as the caller's paths are built on, see {@link PathInfo#child(String)}. */
	private final Path _base;

	/** The same, absolute and normalized, for comparisons. */
	private final Path _spaceRoot;

	private final ResourceCache _cache;

	private final PhotoCollections _collections;

	/**
	 * Creates the rule for the space at the given root.
	 *
	 * @param collections
	 *        The references of that space, which locate a photograph by its hash.
	 */
	public ReferencedOriginals(Path spaceRoot, ResourceCache cache, PhotoCollections collections) {
		_base = spaceRoot;
		_spaceRoot = spaceRoot.toAbsolutePath().normalize();
		_cache = cache;
		_collections = collections;
	}

	/**
	 * The named photograph of the given folder as a {@link Doomed}, <code>null</code> where nothing
	 * can reference it (the folder knows no hash of it).
	 */
	public static Doomed photo(PathInfo folder, String name, String label) {
		String hash = new HashCache(folder.toFile()).storedHashByName().get(name);
		return hash == null ? null : new Doomed(folder.child(name), hash, label);
	}

	/**
	 * Adds every photograph below the given folder, the folder itself included, to the given list,
	 * labelled by its path below the folder the delete happens in.
	 *
	 * @param prefix
	 *        The label of the given folder, <code>/</code>-terminated.
	 */
	public static void photosBelow(PathInfo folder, String prefix, List<Doomed> result) {
		File dir = folder.toFile();
		File[] contents = dir.listFiles();
		if (contents == null) {
			return;
		}
		Map<String, String> hashes = new HashCache(dir).storedHashByName();
		for (File file : contents) {
			String name = file.getName();
			if (LibraryFiles.isIgnored(name) || Files.isSymbolicLink(file.toPath())) {
				continue;
			}
			if (file.isDirectory()) {
				photosBelow(folder.child(name), prefix + name + "/", result);
			} else if (ResourceCache.isImage(file) && hashes.containsKey(name)) {
				result.add(new Doomed(folder.child(name), hashes.get(name), prefix + name));
			}
		}
	}

	/**
	 * Where each of the given photographs goes instead of the trash; one that no surviving album
	 * references is absent. Nothing is changed.
	 *
	 * <p>
	 * A reference that would still find its contents in another file once the given photographs are
	 * gone &mdash; a file not rated as trash and at least as private as the one it showed, see
	 * {@link PhotoCollections#twin(String, PathInfo, java.util.Set, Map)} &mdash; keeps nothing: it
	 * follows that file, see issue #307. Only a reference left with nothing to show keeps the
	 * photograph.
	 * </p>
	 *
	 * @param goingAway
	 *        The folders the same operation deletes: no album in or below one of them receives.
	 */
	public Plan plan(Collection<Doomed> photos, Collection<PathInfo> goingAway) throws IOException {
		Plan result = new Plan();
		if (photos.isEmpty()) {
			return result;
		}
		Map<String, List<Doomed>> byHash = new LinkedHashMap<>();
		for (Doomed doomed : photos) {
			byHash.computeIfAbsent(doomed._hash, x -> new ArrayList<>()).add(doomed);
		}
		List<Path> away = new ArrayList<>();
		for (PathInfo folder : goingAway) {
			away.add(path(folder.toFile()));
		}
		Map<Doomed, List<Holder>> holders = holders(byHash, away, result._follows, null);
		for (Doomed doomed : photos) {
			List<Holder> found = holders.get(doomed);
			if (found == null) {
				continue;
			}
			found.sort(OLDEST_FIRST);
			Path own = path(doomed._photo.toFile().getParentFile());
			Holder receiver = null;
			List<Holder> followers = new ArrayList<>();
			for (Holder holder : found) {
				Path album = path(holder._album.toFile());
				if (isBelowAny(album, away)) {
					// Deleted in the same step, with its references.
					continue;
				}
				if (receiver == null && !album.equals(own)) {
					receiver = holder;
				} else {
					followers.add(holder);
				}
			}
			if (receiver != null) {
				result._receivers.add(new Receiver(doomed, receiver, followers, expectedName(doomed, receiver)));
			}
		}
		return result;
	}

	/** The name the photograph will have in the receiving album, see {@link MoveService#rescue}. */
	private static String expectedName(Doomed doomed, Holder receiver) throws IOException {
		File folder = receiver._album.toFile();
		String existing = new HashCache(folder).nameOf(doomed._hash);
		if (existing != null) {
			return existing;
		}
		return ImageServlet.freeName(folder, receiver._name, receiver._name).getName();
	}

	/**
	 * Moves every planned photograph into its receiving album and refreshes the other references to
	 * it, see the class comment.
	 *
	 * @param ratings
	 *        The rating a photograph is given in its receiving album by where it lies now,
	 *        <code>null</code> (or absent) to keep the one it has.
	 * @return What was kept, one entry per photograph that moved, its album relative to the space
	 *         root.
	 */
	public List<KeptPhoto> rescue(Plan plan, Map<PathInfo, Integer> ratings) throws IOException {
		List<KeptPhoto> result = new ArrayList<>();
		Map<PathInfo, Map<String, String>> hints = new LinkedHashMap<>();
		// A reference that follows another file of its contents is pointed at it first, see
		// #plan(Collection, Collection).
		for (Follow follow : plan._follows) {
			hints.computeIfAbsent(follow._album, x -> new HashMap<>()).put(follow._name, _collections.relative(follow._to));
		}
		// One move per source folder and receiving album, in the order planned.
		Map<List<PathInfo>, List<Receiver>> moves = new LinkedHashMap<>();
		for (Receiver receiver : plan._receivers) {
			moves.computeIfAbsent(List.of(receiver._doomed._photo.parent(), receiver._holder._album),
				x -> new ArrayList<>()).add(receiver);
		}
		MoveService service = new MoveService(_cache);
		for (Map.Entry<List<PathInfo>, List<Receiver>> move : moves.entrySet()) {
			PathInfo source = move.getKey().get(0);
			PathInfo album = move.getKey().get(1);
			List<MoveService.Rescue> rescues = new ArrayList<>();
			for (Receiver receiver : move.getValue()) {
				rescues.add(new MoveService.Rescue(receiver._doomed._photo.getName(), receiver._holder._name,
					ratings == null ? null : ratings.get(receiver._doomed._photo)));
			}
			Map<String, String> landed = service.rescue(source, album, rescues);
			for (Receiver receiver : move.getValue()) {
				String name = landed.get(receiver._doomed._photo.getName());
				if (name == null) {
					continue;
				}
				LOG.info("Kept '" + receiver._doomed._photo.toFile() + "' in '" + album.toFile() + "' as '" + name
					+ "': it is referenced there.");
				result.add(KeptPhoto.create().setPhoto(receiver._doomed._label).setAlbum(album.relativePath())
					.setTitle(receiver._holder._title).setName(name));
				String path = _collections.relative(album.child(name));
				for (Holder follower : receiver._followers) {
					hints.computeIfAbsent(follower._album, x -> new HashMap<>()).put(follower._name, path);
				}
			}
		}
		for (Map.Entry<PathInfo, Map<String, String>> entry : hints.entrySet()) {
			refreshHints(entry.getKey(), entry.getValue());
		}
		return result;
	}

	/**
	 * Points every reference of the space that shows one of the given files at the given file of the
	 * same contents instead, see issue #307 and {@link KeepAsReferences}: the copies are about to be
	 * replaced by references to that file and go into the trash, and what showed a copy shows the
	 * original from now on. The caller checked that the original is at least as private as each copy.
	 */
	public void retarget(Collection<Doomed> copies, PathInfo original) throws IOException {
		if (copies.isEmpty()) {
			return;
		}
		Map<String, List<Doomed>> byHash = new LinkedHashMap<>();
		for (Doomed copy : copies) {
			byHash.computeIfAbsent(copy._hash, x -> new ArrayList<>()).add(copy);
		}
		Plan plan = new Plan();
		holders(byHash, new ArrayList<>(), plan._follows, original);
		rescue(plan, null);
	}

	/**
	 * Points the named references of the given album at where their photograph lies now: they would
	 * find it by its hash, but at the first look only through their hint.
	 */
	private void refreshHints(PathInfo album, Map<String, String> paths) {
		File dir = album.toFile();
		try {
			FolderResource sidecar = ResourceCache.sidecar(dir);
			if (!(sidecar instanceof AlbumInfo)) {
				return;
			}
			boolean changed = false;
			for (ImagePart reference : PhotoCollections.references((AlbumInfo) sidecar)) {
				String path = paths.get(reference.getName());
				if (path != null && !path.equals(reference.getRef().getPath())) {
					reference.getRef().setPath(path);
					changed = true;
				}
			}
			if (changed) {
				ImageServlet.storeSidecar(dir, MoveService.json(sidecar));
			}
		} catch (IOException | RuntimeException ex) {
			// The reference finds its photograph by its hash all the same.
			LOG.log(Level.WARNING, "Cannot refresh the references of '" + dir + "': " + ex.getMessage(), ex);
		} finally {
			_cache.invalidate(album);
		}
	}

	/** Every reference of the space to one of the given photographs, by photograph. */
	private Map<Doomed, List<Holder>> holders(Map<String, List<Doomed>> byHash, List<Path> away, List<Follow> follows,
			PathInfo preferred) throws IOException {
		Map<Doomed, List<Holder>> result = new HashMap<>();
		Map<File, Map<String, String>> sidecars = new HashMap<>();
		Set<Path> leaving = new HashSet<>();
		for (List<Doomed> doomed : byHash.values()) {
			for (Doomed photo : doomed) {
				leaving.add(path(photo._photo.toFile()));
			}
		}
		Files.walkFileTree(_spaceRoot, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(_spaceRoot) && LibraryFiles.isIgnored(dir.getFileName().toString())) {
					// The server's own folders, the trash among them, and what other systems write.
					return FileVisitResult.SKIP_SUBTREE;
				}
				scan(dir, byHash, sidecars, leaving, away, result, follows, preferred);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				return FileVisitResult.CONTINUE;
			}
		});
		return result;
	}

	private void scan(Path dir, Map<String, List<Doomed>> byHash, Map<File, Map<String, String>> sidecars,
			Set<Path> leaving, List<Path> away, Map<Doomed, List<Holder>> result, List<Follow> follows,
			PathInfo preferred) {
		Path index = dir.resolve("index.json");
		if (!Files.isRegularFile(index)) {
			return;
		}
		AlbumInfo album;
		try {
			String text = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
			boolean named = false;
			for (String hash : byHash.keySet()) {
				if (text.contains(hash)) {
					named = true;
					break;
				}
			}
			if (!named) {
				return;
			}
			FolderResource sidecar = ResourceCache.sidecar(dir.toFile());
			if (!(sidecar instanceof AlbumInfo) || ((AlbumInfo) sidecar).getKind() == AlbumKind.SEARCH) {
				return;
			}
			album = (AlbumInfo) sidecar;
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot look for references in '" + dir + "': " + ex.getMessage(), ex);
			return;
		}
		Path below = _spaceRoot.relativize(dir.toAbsolutePath().normalize());
		PathInfo albumPath = below.toString().isEmpty() ? new PathInfo(_base) : new PathInfo(_base, below);
		String relative = below.toString().replace(File.separatorChar, '/');
		String title = album.getTitle() == null || album.getTitle().isEmpty() ? dir.getFileName().toString()
			: album.getTitle();
		boolean goesAway = isBelowAny(dir.toAbsolutePath().normalize(), away);
		for (ImagePart reference : PhotoCollections.references(album)) {
			String hash = reference.getRef().getHash();
			List<Doomed> candidates = byHash.get(hash);
			if (candidates == null) {
				continue;
			}
			PathInfo located = _collections.locate(reference.getRef(), sidecars);
			if (located == null) {
				continue;
			}
			Path photo = path(located.toFile());
			if (!leaving.contains(photo)) {
				// It shows another file of these contents already, see PhotoCollections#locate; one whose
				// hint names a file that leaves is pointed at the file it shows.
				PathInfo hinted = _collections.hinted(reference.getRef(), sidecars);
				if (!goesAway && hinted != null && leaving.contains(path(hinted.toFile()))) {
					follows.add(new Follow(albumPath, reference.getName(), located));
				}
				continue;
			}
			if (!goesAway) {
				// Another file of the contents that stays, as private as this one: the reference
				// follows it and keeps nothing, see issue #307.
				PathInfo twin = preferred != null ? preferred : _collections.twin(hash, located, leaving, sidecars);
				if (twin != null) {
					follows.add(new Follow(albumPath, reference.getName(), twin));
					continue;
				}
			}
			for (Doomed doomed : candidates) {
				if (photo.equals(path(doomed._photo.toFile()))) {
					result.computeIfAbsent(doomed, x -> new ArrayList<>()).add(new Holder(albumPath, relative, title,
						reference.getName(), reference.getRef().getCreated()));
				}
			}
		}
	}

	private static Path path(File file) {
		return file.getAbsoluteFile().toPath().normalize();
	}

	private static boolean isBelowAny(Path path, List<Path> roots) {
		for (Path root : roots) {
			if (path.startsWith(root)) {
				return true;
			}
		}
		return false;
	}
}
