/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Throwing away the files the server generated for one folder, see issue #98.
 *
 * <p>
 * A preview that was written by a build which could still crash mid-write is broken for ever:
 * nothing ever looks inside a cached file, it is fresh when it is newer than the original and than
 * {@link PreviewCache#LAST_UPDATE}. So the author needs a handle on the server's own cache — and
 * exactly that, nothing more.
 * </p>
 *
 * <p>
 * The rule is therefore a <em>name</em> rule and never "everything in
 * {@value PreviewCache#CACHE_DIRECTORY_NAME}": the server deletes what it can prove it wrote
 * itself, and leaves everything else where it is. A file the album's own tooling put beside the
 * previews survives, and so does anything a future version of this server keeps there under a name
 * this rule does not know. Outside the cache directory nothing is looked at at all: the originals
 * and the sidecars are not part of this.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class CacheRefresh {

	private static final Logger LOG = Logger.getLogger(CacheRefresh.class.getName());

	private CacheRefresh() {
		// Static use only.
	}

	/**
	 * Whether the given name in a {@value PreviewCache#CACHE_DIRECTORY_NAME} directory is a file
	 * the server generated.
	 *
	 * <p>
	 * The three things the server writes there, and the half-written name each of them is moved
	 * into place from:
	 * </p>
	 *
	 * <ul>
	 * <li>A preview, <code>preview-&lt;name&gt;[.&lt;type&gt;]</code>, see
	 * {@link PreviewCache#createPreview(File)}.</li>
	 * <li>A playback rendition, <code>video-&lt;name&gt;.mp4</code>, and a teaser,
	 * <code>teaser-&lt;name&gt;.mp4</code>, see {@link VideoRenditions}.</li>
	 * <li>The faces found in the photographs of this folder,
	 * {@value de.haumacher.imageServer.faces.FaceCache#FILE_NAME}, and the crop of one of them,
	 * <code>face-&lt;name&gt;-&lt;index&gt;.jpg</code>, see issue #124.</li>
	 * <li>Anything of those under its {@value PreviewCache#TMP_SUFFIX} name — a leftover of a
	 * server that was killed between writing and moving.</li>
	 * </ul>
	 *
	 * <p>
	 * The extension of a rendition is required, so that a file merely <em>beginning</em> with
	 * <code>video-</code> is not deleted for its name's sake.
	 * </p>
	 */
	public static boolean isGenerated(String name) {
		String plain = name.endsWith(PreviewCache.TMP_SUFFIX)
			? name.substring(0, name.length() - PreviewCache.TMP_SUFFIX.length())
			: name;
		if (plain.startsWith(PreviewCache.PREVIEW_PREFIX)) {
			return plain.length() > PreviewCache.PREVIEW_PREFIX.length();
		}
		if (plain.equals(de.haumacher.imageServer.faces.FaceCache.FILE_NAME)) {
			return true;
		}
		if (plain.startsWith(de.haumacher.imageServer.faces.FaceIndex.CROP_PREFIX)
			&& plain.toLowerCase(Locale.ROOT)
				.endsWith("." + de.haumacher.imageServer.faces.FaceIndex.CROP_EXTENSION)) {
			return true;
		}
		for (VideoRenditions.Kind kind : VideoRenditions.Kind.values()) {
			if (plain.startsWith(kind.prefix())
				&& plain.toLowerCase(Locale.ROOT).endsWith("." + VideoRenditions.Kind.extension())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Deletes the generated files of the given folder's cache directory.
	 *
	 * <p>
	 * Not recursive: the folder that was addressed, and not one below it. A listing folder has no
	 * previews of its own, and throwing away a whole tree of them is a different decision from
	 * repairing one album.
	 * </p>
	 *
	 * <p>
	 * The cache directory itself stays, empty; it is the server's, and the next preview would
	 * create it again anyway.
	 * </p>
	 *
	 * @param folder
	 *        The album folder, <em>not</em> its cache directory.
	 * @return How many files were deleted; zero when the folder has no cache directory.
	 */
	public static int refresh(File folder) {
		File cacheDir = cacheDir(folder);
		File[] contents = cacheDir.listFiles();
		if (contents == null) {
			// No cache directory at all, or it cannot be read: there is nothing to throw away.
			return 0;
		}
		int removed = 0;
		for (File file : contents) {
			if (!file.isFile() || !isGenerated(file.getName())) {
				continue;
			}
			if (file.delete()) {
				removed++;
			} else {
				LOG.log(Level.WARNING, "Cannot delete the cached file '" + file.getAbsolutePath() + "'.");
			}
		}
		LOG.info("Threw away " + removed + " generated files of '" + folder.getAbsolutePath() + "'.");
		return removed;
	}

	/** Where the generated files of the given folder live. */
	public static File cacheDir(File folder) {
		return new File(folder, PreviewCache.CACHE_DIRECTORY_NAME);
	}

}
