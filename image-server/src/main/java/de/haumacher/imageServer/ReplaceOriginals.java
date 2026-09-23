/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.ReanalyzeResult;
import de.haumacher.imageServer.upload.HashCache;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Putting the originals in the place of the redacted copies a phone uploaded, see issue #167.
 *
 * <p>
 * Before issue #166, the camera-roll sync of an Android phone uploaded every photograph with its
 * GPS tags zero-filled: the system redacts the position of a file handed to an app that did not ask
 * for the media location, and leaves the compressed picture untouched. The author downloads the
 * originals into a folder on the server and runs
 * <code>--replace-originals &lt;folder&gt;</code>, with the server not running, like the migrations.
 * For every file of that folder (not recursive):
 * </p>
 *
 * <ol>
 * <li>the library file of the same name is looked up below every space; a name the library holds
 * twice or not at all is reported and skipped (the author's assumption is that names are unique,
 * and it is checked, not trusted);</li>
 * <li>the two must be the same recording, see {@link SameRecording}: the same JPEG scan data,
 * dimensions and recording time, or the same media data of a video; anything else is skipped with
 * the reason;</li>
 * <li>the redacted copy is <em>renamed</em> into
 * <code>&lt;space&gt;/.valbum/replaced/&lt;yyyyMMdd-HHmmss&gt;/&lt;album path&gt;/&lt;name&gt;</code>
 * &mdash; never deleted &mdash; and the original moved into its place: a rename where the incoming
 * folder lies on the same file system, else a copy whose SHA-256 is checked before the incoming
 * file (and only the incoming file) is deleted;</li>
 * <li>the album's {@value HashCache#FILE_NAME} entry is rewritten with the new hash, the
 * attribution of issue #53 kept; the running server's hash index of issue #118 would pick the
 * folder up by the sidecar's stamp, and the next start reads it anyway;</li>
 * <li>once every file is in place, every touched album is re-read by the {@link Reanalysis} of
 * issue #161, which fills the position and the camera a part lacks from the stored sidecar and
 * never touches a stored date, rating, orientation, comment or tag.</li>
 * </ol>
 *
 * <p>
 * Faces need nothing: <code>faces.json</code> and the crops are keyed by the content hash, so the
 * photograph is simply described again, and the stored tags live in <code>index.json</code> by
 * their box.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class ReplaceOriginals {

	/** The folder below {@value UserStore#DIRECTORY_NAME} the redacted copies are set aside in. */
	public static final String REPLACED_DIRECTORY_NAME = "replaced";

	/** How the folder of one run is named. */
	static final DateTimeFormatter RUN_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private ReplaceOriginals() {
		// Static utility.
	}

	/** A run that cannot start; nothing was touched. */
	public static final class Refused extends Exception {
		private static final long serialVersionUID = 1L;

		Refused(String message) {
			super(message);
		}
	}

	/** What a run did, or would do. */
	public static final class Report {
		private final List<String> _lines = new ArrayList<>();

		private final List<String> _summary = new ArrayList<>();

		private int _replaced;

		private int _skipped;

		/** One line per file of the incoming folder, in the order of their names. */
		public List<String> getLines() {
			return Collections.unmodifiableList(_lines);
		}

		/** The totals and where the redacted copies went. */
		public List<String> getSummary() {
			return Collections.unmodifiableList(_summary);
		}

		/** How many files were (or would be) replaced. */
		public int getReplaced() {
			return _replaced;
		}

		/** How many files were skipped. */
		public int getSkipped() {
			return _skipped;
		}

		void skip(String name, String reason) {
			_skipped++;
			_lines.add("skipped " + name + ": " + reason);
		}
	}

	/** One space, by its root folder. */
	private static final class Space {
		final Path _root;

		Space(Path root) {
			_root = root;
		}
	}

	/**
	 * Replaces the redacted copies of the library by the originals in the given folder.
	 *
	 * @param basePath
	 *        The base folder the server serves.
	 * @param incoming
	 *        The folder holding the originals.
	 * @param forced
	 *        The space mode of <code>--spaces</code>, <code>null</code> to decide as the server
	 *        does.
	 * @param dryRun
	 *        Whether to say what would happen and touch nothing.
	 */
	public static Report run(Path basePath, Path incoming, SpaceMode forced, boolean dryRun)
			throws Refused, IOException {
		return run(basePath, incoming, forced, dryRun, LocalDateTime.now());
	}

	static Report run(Path basePath, Path incoming, SpaceMode forced, boolean dryRun, LocalDateTime now)
			throws Refused, IOException {
		basePath = basePath.toAbsolutePath().normalize();
		if (incoming == null || !Files.isDirectory(incoming)) {
			throw new Refused("'" + incoming + "' is not a folder.");
		}
		if (!Files.isReadable(incoming)) {
			throw new Refused("'" + incoming + "' cannot be read.");
		}
		if (!Files.isDirectory(basePath)) {
			throw new Refused("The base folder '" + basePath + "' is not a folder.");
		}
		List<Path> incomingFiles = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(incoming)) {
			for (Path entry : entries) {
				incomingFiles.add(entry);
			}
		} catch (IOException ex) {
			throw new Refused("'" + incoming + "' cannot be read: " + ex.getMessage());
		}
		incomingFiles.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));

		List<Space> spaces = spaces(basePath, forced);
		Path incomingReal = incoming.toRealPath();
		Map<String, List<Path>> library = library(spaces, incomingReal);

		String run = RUN_FORMAT.format(now);
		Report report = new Report();
		// The albums touched, by the space they lie in, in the order they were touched.
		Map<Space, Set<Path>> touched = new LinkedHashMap<>();
		Set<Path> asideFolders = new LinkedHashSet<>();

		for (Path source : incomingFiles) {
			String name = source.getFileName().toString();
			if (Files.isDirectory(source)) {
				report.skip(name, "a folder (the incoming folder is not read recursively)");
				continue;
			}
			if (!ResourceCache.isImage(source.toFile())) {
				report.skip(name, "not a photograph or video");
				continue;
			}
			List<Path> found = library.get(name);
			if (found == null) {
				report.skip(name, "not in the library");
				continue;
			}
			if (found.size() > 1) {
				List<String> where = new ArrayList<>();
				for (Path path : found) {
					where.add(spell(basePath, path));
				}
				report.skip(name, "ambiguous (the library holds it " + found.size() + " times: "
					+ String.join(", ", where) + ")");
				continue;
			}
			Path target = found.get(0);
			String libraryPath = spell(basePath, target);

			String sha256;
			try {
				sha256 = HashCache.sha256(source.toFile());
				if (sha256.equals(HashCache.sha256(target.toFile()))) {
					report.skip(name, "the library already holds exactly this file (" + libraryPath + ")");
					continue;
				}
				String difference = SameRecording.difference(target.toFile(), source.toFile());
				if (difference != null) {
					report.skip(name, difference + ", not replacing " + libraryPath);
					continue;
				}
			} catch (IOException | RuntimeException ex) {
				report.skip(name, "unreadable (" + ex.getMessage() + ")");
				continue;
			}

			Space space = spaceOf(spaces, target);
			Path album = target.getParent();
			Path asideFolder = space._root.resolve(UserStore.DIRECTORY_NAME).resolve(REPLACED_DIRECTORY_NAME)
				.resolve(run);
			Path aside = asideFolder.resolve(space._root.relativize(album)).resolve(name);
			if (dryRun) {
				report._replaced++;
				report._lines.add("would replace " + libraryPath);
				asideFolders.add(asideFolder);
				continue;
			}

			String problem = replace(source, target, aside, sha256);
			if (problem != null) {
				report.skip(name, problem);
				continue;
			}
			report._replaced++;
			asideFolders.add(asideFolder);
			String hashProblem = rehash(target, sha256);
			report._lines.add("replaced " + libraryPath + (hashProblem == null ? ""
				: " (but its hash could not be recorded: " + hashProblem
					+ "; the server hashes it again and keeps the attribution)"));
			touched.computeIfAbsent(space, s -> new LinkedHashSet<>()).add(album);
		}

		report._summary.add((dryRun ? "Would replace " : "Replaced ") + report._replaced + ", skipped "
			+ report._skipped + " of " + incomingFiles.size() + " file(s) in '" + incoming + "'.");
		for (Path folder : asideFolders) {
			report._summary.add((dryRun ? "The redacted copies would be set aside in '"
				: "The redacted copies were set aside in '") + folder + "'.");
		}
		if (dryRun) {
			report._summary.add("Dry run: nothing was changed.");
			return report;
		}
		if (report._replaced > 0) {
			report._summary.add(reanalyze(touched));
			report._summary.add("The hash index takes the new hashes up when the server starts next.");
		}
		return report;
	}

	/**
	 * Sets the redacted copy aside and moves the original into its place.
	 *
	 * @return <code>null</code> on success, else why nothing was replaced; the library is then as
	 *         it was.
	 */
	private static String replace(Path source, Path target, Path aside, String sha256) {
		try {
			Files.createDirectories(aside.getParent());
			// A rename within the space, never a copy: the redacted copy is kept, and kept whole.
			Files.move(target, aside, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ex) {
			return "cannot set the redacted copy aside (" + ex + ")";
		}
		try {
			install(source, target, sha256, true);
			return null;
		} catch (IOException ex) {
			try {
				Files.move(aside, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException back) {
				return "cannot move the original into place (" + ex + "), and the redacted copy could not be put "
					+ "back from '" + aside + "' (" + back + ")";
			}
			return "cannot move the original into place (" + ex + "); the redacted copy was put back";
		}
	}

	/**
	 * Moves the original to where the redacted copy was; see {@link #replace}.
	 *
	 * @param rename
	 *        Whether to try a rename first; <code>false</code> copies right away, which is what
	 *        happens when the incoming folder lies on another file system (and what a test asks
	 *        for).
	 */
	static void install(Path source, Path target, String sha256, boolean rename) throws IOException {
		if (rename) {
			try {
				Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
				return;
			} catch (AtomicMoveNotSupportedException ex) {
				// Another file system: copied and checked below.
			}
		}
		// A hidden name, which neither the server nor this run takes for a photograph.
		Path copy = target.resolveSibling("." + target.getFileName() + ".replacing");
		try {
			Files.copy(source, copy, StandardCopyOption.COPY_ATTRIBUTES);
			String copied = HashCache.sha256(copy.toFile());
			if (!copied.equals(sha256)) {
				throw new IOException("the copy of '" + source + "' does not hold what was read");
			}
			Files.move(copy, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ex) {
			Files.deleteIfExists(copy);
			throw ex;
		}
		// Only now, the original standing in the library and checked, the incoming file goes.
		Files.delete(source);
	}

	/**
	 * Records the new hash of the given file in its album's sidecar, keeping who contributed it.
	 *
	 * @return <code>null</code> on success, else the problem.
	 */
	private static String rehash(Path target, String sha256) {
		try {
			HashCache hashes = new HashCache(target.getParent().toFile());
			String name = target.getFileName().toString();
			hashes.put(target.toFile(), sha256, hashes.attributionOf(name));
			hashes.flush();
			return null;
		} catch (IOException ex) {
			return ex.getMessage();
		}
	}

	/** Re-reads every touched album, see {@link Reanalysis}; the summary line. */
	private static String reanalyze(Map<Space, Set<Path>> touched) {
		int filled = 0;
		int albums = 0;
		List<String> problems = new ArrayList<>();
		for (Map.Entry<Space, Set<Path>> entry : touched.entrySet()) {
			Path root = entry.getKey()._root;
			ResourceCache cache;
			try {
				cache = new ResourceCache();
			} catch (IOException ex) {
				problems.add(ex.getMessage());
				continue;
			}
			try {
				Reanalysis reanalysis = new Reanalysis(cache, root.getFileName() == null ? "" : root.getFileName().toString());
				for (Path album : entry.getValue()) {
					Path relative = root.relativize(album);
					PathInfo path = relative.toString().isEmpty() ? new PathInfo(root) : new PathInfo(root, relative);
					ReanalyzeResult result = reanalysis.now(path);
					filled += result.getFilled();
					albums += result.getAlbums();
				}
				reanalysis.shutdown();
			} finally {
				try {
					cache.close();
				} catch (IOException ex) {
					problems.add(ex.getMessage());
				}
			}
		}
		return "Filled the missing camera or position of " + filled + " photograph(s) in " + albums
			+ " album(s); every date, rating, orientation, comment and tag stays as it was."
			+ (problems.isEmpty() ? "" : " Problems: " + String.join("; ", problems));
	}

	/** The spaces of the server, decided as {@link de.haumacher.imageServer.auth.Spaces} does. */
	private static List<Space> spaces(Path basePath, SpaceMode forced) throws IOException {
		List<Path> roots = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(basePath)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (UserStore.isServerEntry(name) || !Files.isDirectory(entry)) {
					continue;
				}
				if (SpaceStore.isSpace(entry)) {
					roots.add(entry);
				}
			}
		}
		Collections.sort(roots);
		SpaceMode mode = forced != null ? forced : (roots.isEmpty() ? SpaceMode.SINGLE : SpaceMode.MULTI);
		List<Space> result = new ArrayList<>();
		if (mode == SpaceMode.SINGLE) {
			result.add(new Space(basePath));
		} else {
			for (Path root : roots) {
				result.add(new Space(root));
			}
		}
		return result;
	}

	private static Space spaceOf(List<Space> spaces, Path file) {
		for (Space space : spaces) {
			if (file.startsWith(space._root)) {
				return space;
			}
		}
		throw new IllegalStateException("'" + file + "' lies in no space.");
	}

	/**
	 * Every photograph and video of the library by its name.
	 *
	 * <p>
	 * A folder whose name starts with a dot is the server's own (the cache, the trash, the
	 * duplicates, the copies set aside by an earlier run) and is never looked into, and neither is
	 * the incoming folder, wherever it lies.
	 * </p>
	 */
	private static Map<String, List<Path>> library(List<Space> spaces, Path incoming) throws IOException {
		Map<String, List<Path>> result = new TreeMap<>();
		for (Space space : spaces) {
			Path root = space._root;
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					if (!dir.equals(root) && dir.getFileName().toString().startsWith(".")) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					if (dir.toRealPath().equals(incoming)) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					String name = file.getFileName().toString();
					if (attrs.isRegularFile() && !name.startsWith(".") && ResourceCache.isImage(file.toFile())) {
						result.computeIfAbsent(name, n -> new ArrayList<>()).add(file);
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
					// An unreadable corner of the library holds nothing this run could match.
					return FileVisitResult.CONTINUE;
				}
			});
		}
		return result;
	}

	/** The given library file as a path below the base folder, with forward slashes. */
	private static String spell(Path basePath, Path file) {
		return basePath.relativize(file).toString().replace(File.separatorChar, '/');
	}
}
