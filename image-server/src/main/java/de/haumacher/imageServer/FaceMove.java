/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.upload.HashCache;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hands the faces of a redacted copy over to the original {@link ReplaceOriginals} put in its
 * place.
 *
 * <p>
 * The album's {@link FaceCache} is keyed by the content hash, which a replacement changes. The
 * two files hold the same picture by proof ({@link SameRecording}: the same scan data, the same
 * size, the same recording time), so what was found in the one is what is in the other: the
 * detections, their embeddings and clusters, the EXIF orientation, the second look of issue #163
 * and &mdash; what no new pass over the preview would find again &mdash; the faces somebody marked
 * by hand (issue #155). The entry is moved to the original's hash, and so are the cached crops of
 * <code>?type=face</code>, whose names carry that hash ({@link FaceIndex#cropFile(File, String,
 * FaceCache.Face)}). An entry the original's hash already has is kept, and the old one is kept
 * where another file of the album still holds the old contents.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class FaceMove {

	private FaceMove() {
		// Static utility.
	}

	/**
	 * Moves the faces of the given photograph from its old contents to its new ones.
	 *
	 * @param image
	 *        The photograph in the library, under its library name.
	 * @param oldHash
	 *        The hash of the redacted copy.
	 * @param newHash
	 *        The hash of the original.
	 * @param dryRun
	 *        Whether to say only whether there is anything to move.
	 * @return Whether there were (or would be) faces to move.
	 */
	static boolean move(Path image, String oldHash, String newHash, boolean dryRun) throws IOException {
		File folder = image.getParent().toFile();
		if (!FaceCache.file(folder).isFile()) {
			return false;
		}
		FaceCache cache = new FaceCache(folder);
		if (!cache.knows(oldHash) || cache.knows(newHash)) {
			return false;
		}
		if (dryRun) {
			return true;
		}
		List<FaceCache.Face> faces = cache.facesOf(oldHash);
		cache.put(newHash, faces);
		Integer exif = cache.exifOf(oldHash);
		if (exif != null) {
			cache.putExif(newHash, exif.intValue());
		}
		if (cache.isSearched(oldHash)) {
			cache.putSearched(newHash);
		}
		if (!new HashCache(folder).storedHashByName().containsValue(oldHash)) {
			Set<String> keep = new HashSet<>(cache.hashes());
			keep.remove(oldHash);
			cache.retain(keep);
		}
		cache.flush();

		for (FaceCache.Face face : faces) {
			File from = FaceIndex.cropFile(image.toFile(), oldHash, face);
			File to = FaceIndex.cropFile(image.toFile(), newHash, face);
			if (from.isFile() && !to.exists()) {
				// A cache file: where it cannot be renamed, it is cut again when it is asked for.
				try {
					Files.move(from.toPath(), to.toPath());
				} catch (IOException ex) {
					// See above.
				}
			}
		}
		return true;
	}
}
