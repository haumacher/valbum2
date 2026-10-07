/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.PhotoRef;
import de.haumacher.imageServer.shared.util.UpdateTransient;
import de.haumacher.imageServer.upload.HashCache;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

/**
 * Points the collections of one space at the originals {@link ReplaceOriginals} put in place.
 *
 * <p>
 * A collection of issue #221 references a photograph by its content hash and the path it was last
 * seen at, see {@link PhotoCollections#locate(PhotoRef)}. A replacement changes the hash of the
 * file at that path, and the redacted copy, the one file that still holds the old hash, is set
 * aside below <code>.valbum/</code>, where no lookup finds it: every reference to it would be
 * answered missing. So every reference to a replaced copy is rewritten to the original's hash and
 * its path in the library.
 * </p>
 *
 * <p>
 * A reference whose path names another file that still holds the old hash (a copy of the redacted
 * file elsewhere in the space) still finds that copy and is left as it is. A collection is
 * written exactly as the server writes it after an addition or a removal: read through
 * {@link ResourceCache#sidecar(File)}, changed in its references alone, and stored by
 * {@link ImageServlet#storeSidecar(File, byte[])}, its labels, headings, names and cover untouched.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class CollectionRewrite {

	/** What a replaced copy's references are rewritten to. */
	record Replacement(String hash, String path) {
	}

	/** What a pass over one space found, or did. */
	static final class Result {
		int _references;

		int _collections;

		/** How many references were (or would be) rewritten. */
		int getReferences() {
			return _references;
		}

		/** How many collections were (or would be) written. */
		int getCollections() {
			return _collections;
		}
	}

	private CollectionRewrite() {
		// Static utility.
	}

	/**
	 * Rewrites the references to the replaced copies in every collection of the given space.
	 *
	 * @param root
	 *        The root of the space.
	 * @param replaced
	 *        The replacements of this space, by the hash of the redacted copy.
	 * @param dryRun
	 *        Whether to count only, and write nothing.
	 * @param problems
	 *        Where a collection that cannot be written is reported.
	 */
	static Result rewrite(Path root, Map<String, Replacement> replaced, boolean dryRun, List<String> problems)
			throws IOException {
		Result result = new Result();
		if (replaced.isEmpty()) {
			return result;
		}
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(root) && LibraryFiles.isIgnored(dir.getFileName().toString())) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				if (!Files.isRegularFile(dir.resolve("index.json"))) {
					return FileVisitResult.CONTINUE;
				}
				FolderResource stored = ResourceCache.sidecar(dir.toFile());
				if (!PhotoCollections.isCollection(stored)) {
					return FileVisitResult.CONTINUE;
				}
				AlbumInfo collection = (AlbumInfo) stored;
				UpdateTransient.updateTransient(collection);
				int changed = 0;
				for (ImagePart reference : PhotoCollections.references(collection)) {
					PhotoRef ref = reference.getRef();
					Replacement replacement = replaced.get(ref.getHash());
					if (replacement == null || findsAnotherCopy(root, ref, replacement)) {
						continue;
					}
					ref.setHash(replacement.hash()).setPath(replacement.path());
					changed++;
				}
				if (changed == 0) {
					return FileVisitResult.CONTINUE;
				}
				if (!dryRun) {
					try {
						ImageServlet.storeSidecar(dir.toFile(), ImageServlet.sidecarOf(collection));
					} catch (IOException ex) {
						problems.add("the collection '" + dir + "' cannot be written (" + ex.getMessage() + ")");
						return FileVisitResult.CONTINUE;
					}
				}
				result._references += changed;
				result._collections++;
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				return FileVisitResult.CONTINUE;
			}
		});
		return result;
	}

	/**
	 * Whether the given reference names, by its path, a file other than the replaced one that
	 * still holds the old contents, and so still finds them.
	 */
	private static boolean findsAnotherCopy(Path root, PhotoRef ref, Replacement replacement) {
		String hint = ref.getPath();
		if (hint.isEmpty() || hint.equals(replacement.path()) || !LibraryFiles.isLibraryPath(hint)) {
			return false;
		}
		Path hinted = root.resolve(hint).normalize();
		if (!hinted.startsWith(root) || !Files.isRegularFile(hinted)) {
			return false;
		}
		String stored = new HashCache(hinted.getParent().toFile()).storedHashByName()
			.get(hinted.getFileName().toString());
		return ref.getHash().equals(stored);
	}
}
