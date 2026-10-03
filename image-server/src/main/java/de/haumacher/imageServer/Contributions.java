/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.upload.HashCache;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * How many photographs each contributor added that a space still holds, see issue #203.
 *
 * <p>
 * Counted from the stored truth, the attributions of the <code>.hashes.json</code> sidecars of
 * issue #53: an upload records <code>contact:&lt;id&gt;</code> and, since issue #203, the personal
 * link it came through. So a photograph that was moved to another album still counts (its
 * attribution rides along), one that was deleted into the trash or purged does not, and nothing is
 * kept that could disagree with the library. The count per contact is space-wide; the count per
 * link is that of the uploads recorded with that link &mdash; an upload of an earlier build, which
 * recorded no link, counts for the contact only.
 * </p>
 *
 * <p>
 * Every folder of the space is visited (dot-folders and what {@link LibraryFiles#isIgnored(String)}
 * names left out), but a sidecar is only read again when its modification stamp or size changed,
 * so asking twice costs one directory walk.
 * </p>
 */
public class Contributions {

	private static final Logger LOG = Logger.getLogger(Contributions.class.getName());

	private final Path _root;

	/** What one sidecar said, by its folder. */
	private final Map<Path, Folder> _folders = new ConcurrentHashMap<>();

	/** The counts of one sidecar, valid while its stamp and size are unchanged. */
	private static final class Folder {
		final long _modified;

		final long _size;

		final Map<String, Integer> _counts;

		Folder(long modified, long size, Map<String, Integer> counts) {
			_modified = modified;
			_size = size;
			_counts = counts;
		}
	}

	/** The counts of one walk, see {@link Contributions#count()}. */
	public static final class Counts {

		/** Nothing counted. */
		public static final Counts NONE = new Counts(Collections.emptyMap());

		private final Map<String, Integer> _counts;

		Counts(Map<String, Integer> counts) {
			_counts = counts;
		}

		/** How many photographs the given subject added, through whatever link. */
		public int of(String subject) {
			return _counts.getOrDefault(subject, 0);
		}

		/** How many photographs the given subject added through the share link of the given id. */
		public int of(String subject, String link) {
			return _counts.getOrDefault(key(subject, link), 0);
		}
	}

	/** Creates the {@link Contributions} of the space rooted at the given folder. */
	public Contributions(Path root) {
		_root = root;
	}

	/** Counts what every contributor of the space added. */
	public Counts count() {
		Map<String, Integer> result = new HashMap<>();
		if (_root == null || !Files.isDirectory(_root)) {
			return Counts.NONE;
		}
		java.util.Set<Path> seen = new java.util.HashSet<>();
		try {
			Files.walkFileTree(_root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					if (!dir.equals(_root) && LibraryFiles.isIgnored(dir.getFileName().toString())) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					seen.add(dir);
					Folder folder = folder(dir);
					if (folder != null) {
						folder._counts.forEach((key, count) -> result.merge(key, count, Integer::sum));
					}
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
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot count the contributions below '" + _root + "': " + ex.getMessage());
		}
		_folders.keySet().retainAll(seen);
		return new Counts(result);
	}

	private Folder folder(Path dir) {
		File sidecar = dir.resolve(HashCache.FILE_NAME).toFile();
		if (!sidecar.isFile()) {
			_folders.remove(dir);
			return null;
		}
		long modified = sidecar.lastModified();
		long size = sidecar.length();
		Folder known = _folders.get(dir);
		if (known != null && known._modified == modified && known._size == size) {
			return known;
		}
		Map<String, Integer> counts = new HashMap<>();
		for (HashCache.Attribution attribution : HashCache.recorded(dir.toFile()).values()) {
			String subject = attribution.getContributor();
			counts.merge(subject, 1, Integer::sum);
			if (!attribution.getLink().isEmpty()) {
				counts.merge(key(subject, attribution.getLink()), 1, Integer::sum);
			}
		}
		Folder result = new Folder(modified, size, counts);
		_folders.put(dir, result);
		return result;
	}

	static String key(String subject, String link) {
		return subject + '\n' + link;
	}
}
