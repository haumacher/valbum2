/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ImageData;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
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
 * twice is reported and skipped (the author's assumption is that names are unique, and it is
 * checked, not trusted);</li>
 * <li>a file whose name the library does not hold is looked for by its content, because the phone
 * may have uploaded it under another name (<code>1000020572.jpg</code> for
 * <code>IMG_20260930_122228.jpg</code>): the library files of the same recording time and size (of
 * a video: the same duration and size) are its candidates, see {@link RecordingIndex}, an index
 * built for the run in memory and thrown away with it; the one candidate that is the same recording
 * is replaced <em>under the library's name</em>, so that the album's sidecar, the ratings, labels,
 * faces and collections keep pointing at it; two library files of the same recording are reported
 * and skipped, and so is a library file this run already replaced by a file of its own name;</li>
 * <li>the two must be the same recording, see {@link SameRecording}: the same JPEG scan data,
 * dimensions and recording time, or the same media data of a video; anything else is skipped with
 * the reason;</li>
 * <li>the redacted copy is <em>renamed</em> into
 * <code>&lt;space&gt;/.valbum/replaced/&lt;yyyyMMdd-HHmmss&gt;/&lt;album path&gt;/&lt;library name&gt;</code>
 * &mdash; never deleted &mdash; and the original moved into its place: a rename where the incoming
 * folder lies on the same file system, else a copy whose SHA-256 is checked before the incoming
 * file (and only the incoming file) is deleted;</li>
 * <li>the album's {@value HashCache#FILE_NAME} entry is rewritten with the new hash, the
 * attribution of issue #53 kept; the running server's hash index of issue #118 would pick the
 * folder up by the sidecar's stamp, and the next start reads it anyway;</li>
 * <li>every reference a collection of issue #221 holds to a redacted copy is moved over to the
 * original's hash and path, see {@link CollectionRewrite}; a dry run counts them;</li>
 * <li>the faces found in a redacted copy are handed over to the original, see {@link FaceMove};</li>
 * <li>once every file is in place, every touched album is re-read by the {@link Reanalysis} of
 * issue #161, which fills the position and the camera a part lacks from the stored sidecar and
 * never touches a stored date, rating, orientation, comment or tag.</li>
 * </ol>
 *
 * <p>
 * The stored face tags live in <code>index.json</code> by their box and need nothing; the
 * detections of <code>faces.json</code> and the crops are keyed by the content hash and are moved
 * to the original's, see {@link FaceMove}, so that a face marked by hand (issue #155) keeps feeding
 * the recognition.
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
		return run(basePath, incoming, forced, dryRun, now, new RecordingIndex.Reads());
	}

	/**
	 * The run, counting what reading the library for the content match costs.
	 *
	 * @param reads
	 *        Where the reads of the library are counted, see {@link RecordingIndex.Reads}.
	 */
	static Report run(Path basePath, Path incoming, SpaceMode forced, boolean dryRun, LocalDateTime now,
			RecordingIndex.Reads reads) throws Refused, IOException {
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
		Run state = new Run(basePath, spaces, run, dryRun, report, reads);
		String[] lines = new String[incomingFiles.size()];
		List<Integer> byContent = new ArrayList<>();

		// Names first: a file the library holds under its own name is matched by that name, see #167.
		for (int n = 0; n < incomingFiles.size(); n++) {
			Path source = incomingFiles.get(n);
			String name = source.getFileName().toString();
			if (Files.isDirectory(source)) {
				lines[n] = state.skip(name, "a folder (the incoming folder is not read recursively)");
				continue;
			}
			if (!ResourceCache.isImage(source.toFile())) {
				lines[n] = state.skip(name, "not a photograph or video");
				continue;
			}
			List<Path> found = library.get(name);
			if (found == null) {
				byContent.add(Integer.valueOf(n));
				continue;
			}
			if (found.size() > 1) {
				List<String> where = new ArrayList<>();
				for (Path path : found) {
					where.add(spell(basePath, path));
				}
				lines[n] = state.skip(name, "ambiguous (the library holds it " + found.size() + " times: "
					+ String.join(", ", where) + ")");
				continue;
			}
			Path target = found.get(0);
			String libraryPath = spell(basePath, target);

			String sha256;
			String redactedHash;
			try {
				sha256 = HashCache.sha256(source.toFile());
				redactedHash = HashCache.sha256(target.toFile());
				if (sha256.equals(redactedHash)) {
					lines[n] = state.skip(name, "the library already holds exactly this file (" + libraryPath + ")");
					continue;
				}
				String difference = SameRecording.difference(target.toFile(), source.toFile());
				if (difference != null) {
					lines[n] = state.skip(name, difference + ", not replacing " + libraryPath);
					continue;
				}
			} catch (IOException | RuntimeException ex) {
				lines[n] = state.skip(name, "unreadable (" + ex.getMessage() + ")");
				continue;
			}
			lines[n] = state.replace(source, target, redactedHash, sha256, "");
		}

		// Then the content, for the files the phone renamed on upload.
		RecordingIndex index = null;
		for (Integer position : byContent) {
			int n = position.intValue();
			Path source = incomingFiles.get(n);
			String name = source.getFileName().toString();
			if (!RecordingIndex.isKeyed(name)) {
				lines[n] = state.skip(name, "not in the library (and neither a JPEG nor an mp4 or QuickTime video, "
					+ "which could be found under another name)");
				continue;
			}
			RecordingIndex.Head head = RecordingIndex.head(source, state.zones(), new RecordingIndex.Reads());
			if (head.getProblem() != null) {
				lines[n] = state.skip(name, "not in the library (and " + head.getProblem()
					+ ", so it cannot be found under another name)");
				continue;
			}
			if (index == null) {
				index = RecordingIndex.build(state.libraryFiles(library), state::zoneOf, reads);
			}
			lines[n] = state.byContent(source, name, head, index);
		}

		for (String line : lines) {
			report._lines.add(line);
		}

		// The collections of #221 reference a photograph by its hash: every replacement, by name or
		// by content, moves the references to the redacted copy over to the original.
		int references = 0;
		int collections = 0;
		List<String> collectionProblems = new ArrayList<>();
		for (Map.Entry<Space, Map<String, CollectionRewrite.Replacement>> entry : state._replacements.entrySet()) {
			try {
				CollectionRewrite.Result rewritten =
					CollectionRewrite.rewrite(entry.getKey()._root, entry.getValue(), dryRun, collectionProblems);
				references += rewritten.getReferences();
				collections += rewritten.getCollections();
			} catch (IOException ex) {
				collectionProblems.add("the collections of '" + entry.getKey()._root + "' cannot be read ("
					+ ex.getMessage() + ")");
			}
		}
		Map<Space, Set<Path>> touched = state._touched;
		Set<Path> asideFolders = state._asideFolders;
		report._summary.add((dryRun ? "Would replace " : "Replaced ") + report._replaced + ", skipped "
			+ report._skipped + " of " + incomingFiles.size() + " file(s) in '" + incoming + "'.");
		if (index != null) {
			report._summary.add("Looked for the files not in the library under their names by their content: read "
				+ reads.getSidecars() + " album sidecar(s) and the headers of " + reads.getHeaderBytes().size()
				+ " library file(s) (" + reads.getHeaderBytes().values().stream().mapToLong(Long::longValue).sum()
				+ " bytes)" + (index.getProblems().isEmpty() ? "."
					: "; " + index.getProblems().size() + " library file(s) could not be read and cannot be found "
						+ "that way."));
		}
		// The faces found in a redacted copy are the original's: the picture is the same by proof.
		int faces = 0;
		List<String> faceProblems = new ArrayList<>();
		for (Replaced replaced : state._replacedFiles) {
			try {
				if (FaceMove.move(replaced.target(), replaced.redactedHash(), replaced.originalHash(), dryRun)) {
					faces++;
				}
			} catch (IOException | RuntimeException ex) {
				faceProblems.add(spell(basePath, replaced.target()) + " (" + ex.getMessage() + ")");
			}
		}

		if (report._replaced > 0) {
			report._summary.add((dryRun ? "Would move " : "Moved ") + "the faces found in " + faces
				+ " redacted cop(ies) to the originals." + (faceProblems.isEmpty() ? ""
					: " Not moved, to be found again by the next pass: " + String.join("; ", faceProblems)));
			report._summary.add((dryRun ? "Would update " : "Updated ") + references + " collection reference(s) in "
				+ collections + " collection(s) to the originals."
				+ (collectionProblems.isEmpty() ? "" : " Problems: " + String.join("; ", collectionProblems)));
		}
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

	/** One library file replaced, with the hash of the redacted copy and of the original. */
	private record Replaced(Path target, String redactedHash, String originalHash) {
	}

	/** What one run decided so far. */
	private static final class Run {
		final Path _basePath;

		final List<Space> _spaces;

		final String _run;

		final boolean _dryRun;

		final Report _report;

		/** The albums touched, by the space they lie in, in the order they were touched. */
		final Map<Space, Set<Path>> _touched = new LinkedHashMap<>();

		final Set<Path> _asideFolders = new LinkedHashSet<>();

		/**
		 * The replacements of this run (or that would be), by space and by the hash of the redacted
		 * copy, for {@link CollectionRewrite}.
		 */
		final Map<Space, Map<String, CollectionRewrite.Replacement>> _replacements = new LinkedHashMap<>();

		/** Every library file replaced in this run (or that would be), in the order of the report. */
		final List<Replaced> _replacedFiles = new ArrayList<>();

		/** The library files replaced in this run (or that would be), with the incoming name. */
		final Map<Path, String> _claimed = new LinkedHashMap<>();

		private final Map<Space, ZoneId> _zones = new LinkedHashMap<>();

		private final RecordingIndex.Reads _reads;

		Run(Path basePath, List<Space> spaces, String run, boolean dryRun, Report report, RecordingIndex.Reads reads) {
			_reads = reads;
			_basePath = basePath;
			_spaces = spaces;
			_run = run;
			_dryRun = dryRun;
			_report = report;
		}

		String skip(String name, String reason) {
			_report._skipped++;
			return "skipped " + name + ": " + reason;
		}

		/**
		 * Replaces the given library file by the given original, under the library's name.
		 *
		 * @param redactedHash
		 *        The hash of the library file, which the collections referencing it are moved off.
		 * @param how
		 *        What the report line says beyond the library path.
		 * @return The line of the report.
		 */
		String replace(Path source, Path target, String redactedHash, String sha256, String how) {
			String name = source.getFileName().toString();
			String libraryPath = spell(_basePath, target);
			Space space = spaceOf(_spaces, target);
			Path album = target.getParent();
			Path asideFolder = space._root.resolve(UserStore.DIRECTORY_NAME).resolve(REPLACED_DIRECTORY_NAME)
				.resolve(_run);
			// The redacted copy keeps the library's name, which the original takes over.
			Path aside = asideFolder.resolve(space._root.relativize(album)).resolve(target.getFileName().toString());
			CollectionRewrite.Replacement replacement =
				new CollectionRewrite.Replacement(sha256, space._root.relativize(target).toString().replace(File.separatorChar, '/'));
			if (_dryRun) {
				_report._replaced++;
				_asideFolders.add(asideFolder);
				_claimed.put(target, name);
				_replacements.computeIfAbsent(space, x -> new LinkedHashMap<>()).put(redactedHash, replacement);
				_replacedFiles.add(new Replaced(target, redactedHash, sha256));
				return "would replace " + libraryPath + how;
			}

			String problem = ReplaceOriginals.replace(source, target, aside, sha256);
			if (problem != null) {
				return skip(name, problem);
			}
			_report._replaced++;
			_asideFolders.add(asideFolder);
			_claimed.put(target, name);
			_replacements.computeIfAbsent(space, x -> new LinkedHashMap<>()).put(redactedHash, replacement);
			_replacedFiles.add(new Replaced(target, redactedHash, sha256));
			String hashProblem = rehash(target, sha256);
			_touched.computeIfAbsent(space, s -> new LinkedHashSet<>()).add(album);
			return "replaced " + libraryPath + how + (hashProblem == null ? ""
				: " (but its hash could not be recorded: " + hashProblem
					+ "; the server hashes it again and keeps the attribution)");
		}

		/**
		 * Finds the library file the given original is the same recording as, under another name,
		 * and replaces it.
		 *
		 * <p>
		 * The candidates are the library files that share a key with the original, see
		 * {@link RecordingIndex}, less those this run already replaced. Where there are several and
		 * the original embeds a thumbnail, the candidates that embed the same are compared first, and
		 * the others only if none of them is the same recording: two frames of a burst differ in
		 * their thumbnails, and an editor that regenerated a thumbnail must not hide a true match. A
		 * candidate is the same recording only if {@link SameRecording} says so.
		 * </p>
		 *
		 * @return The line of the report.
		 */
		String byContent(Path source, String name, RecordingIndex.Head head, RecordingIndex index) {
			List<Path> candidates = new ArrayList<>();
			List<String> taken = new ArrayList<>();
			for (Path candidate : index.candidates(head.getKeys())) {
				String by = _claimed.get(candidate);
				if (by != null) {
					taken.add(spell(_basePath, candidate) + " by " + by);
				} else {
					candidates.add(candidate);
				}
			}
			if (candidates.isEmpty()) {
				if (taken.isEmpty()) {
					return skip(name, "not in the library");
				}
				return skip(name, "not in the library (the file(s) of the same recording time and size are "
					+ "already replaced in this run: " + String.join(", ", taken) + ")");
			}

			String sha256;
			try {
				sha256 = HashCache.sha256(source.toFile());
			} catch (IOException ex) {
				return skip(name, "unreadable (" + ex.getMessage() + ")");
			}

			List<Path> preferred = new ArrayList<>();
			List<Path> others = new ArrayList<>();
			if (candidates.size() > 1 && head.getThumbnail() != null) {
				for (Path candidate : candidates) {
					RecordingIndex.Head candidateHead =
						RecordingIndex.head(candidate, zones(), new RecordingIndex.Reads());
					(head.getThumbnail().equals(candidateHead.getThumbnail()) ? preferred : others).add(candidate);
				}
			} else {
				others.addAll(candidates);
			}

			Comparison comparison = new Comparison();
			comparison.compare(preferred, source, _reads);
			if (comparison._same.isEmpty()) {
				comparison.compare(others, source, _reads);
			}

			if (comparison._same.isEmpty()) {
				List<String> other = new ArrayList<>();
				for (Path path : comparison._other) {
					other.add(spell(_basePath, path));
				}
				return skip(name, "not in the library (" + (other.isEmpty() ? ""
					: other.size() + " file(s) of the same recording time and size hold another recording: "
						+ String.join(", ", other))
					+ (other.isEmpty() || comparison._problems.isEmpty() ? "" : "; ")
					+ String.join("; ", comparison._problems) + ")");
			}
			if (comparison._same.size() > 1) {
				List<String> where = new ArrayList<>();
				for (Path path : comparison._same) {
					where.add(spell(_basePath, path));
				}
				return skip(name, "ambiguous (the library holds the same recording " + comparison._same.size()
					+ " times under other names: " + String.join(", ", where) + ")");
			}
			Path target = comparison._same.get(0);
			String redactedHash;
			try {
				redactedHash = HashCache.sha256(target.toFile());
			} catch (IOException ex) {
				return skip(name, "unreadable (" + ex.getMessage() + ")");
			}
			if (sha256.equals(redactedHash)) {
				return skip(name, "the library already holds exactly this file (" + spell(_basePath, target) + ")");
			}
			return replace(source, target, redactedHash, sha256, " with " + name + " (matched by content)");
		}

		/** The zones of every space of the run. */
		Collection<ZoneId> zones() {
			for (Space space : _spaces) {
				zone(space);
			}
			return new LinkedHashSet<>(_zones.values());
		}

		/** The zone of the space the given library file lies in. */
		ZoneId zoneOf(Path file) {
			return zone(spaceOf(_spaces, file));
		}

		private ZoneId zone(Space space) {
			return _zones.computeIfAbsent(space, s -> {
				Path root = s._root;
				try {
					return SpaceStore.load(root, root.getFileName() == null ? "" : root.getFileName().toString())
						.getZone();
				} catch (IOException ex) {
					return ZoneId.systemDefault();
				}
			});
		}

		/** Every photograph and video of the library. */
		List<Path> libraryFiles(Map<String, List<Path>> library) {
			List<Path> result = new ArrayList<>();
			for (List<Path> files : library.values()) {
				result.addAll(files);
			}
			return result;
		}
	}

	/** Which candidates hold the same recording as an original. */
	private static final class Comparison {
		final List<Path> _same = new ArrayList<>();

		final List<Path> _other = new ArrayList<>();

		final List<String> _problems = new ArrayList<>();

		void compare(List<Path> candidates, Path source, RecordingIndex.Reads reads) {
			for (Path candidate : candidates) {
				reads.compared(candidate);
				try {
					if (SameRecording.difference(candidate.toFile(), source.toFile()) == null) {
						_same.add(candidate);
					} else {
						_other.add(candidate);
					}
				} catch (IOException | RuntimeException ex) {
					_problems.add(candidate.getFileName() + " is unreadable (" + ex.getMessage() + ")");
				}
			}
		}
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
		int corrected = 0;
		int albums = 0;
		List<String> problems = new ArrayList<>();
		for (Map.Entry<Space, Set<Path>> entry : touched.entrySet()) {
			Path root = entry.getKey()._root;
			ResourceCache cache;
			try {
				// Dated in the zone of the space, as the server dates them, see issue #183.
				cache = new ResourceCache(ImageData.Analysis.NONE,
					SpaceStore.load(root, root.getFileName() == null ? "" : root.getFileName().toString()).getZone());
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
					corrected += result.getDatesCorrected();
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
		return "Filled the missing camera or position of " + filled + " photograph(s) and corrected the "
			+ "recording time read in the wrong zone of " + corrected + " in " + albums
			+ " album(s); every other date, rating, orientation, comment and tag stays as it was."
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
	 * the litter of another system ({@link LibraryFiles}) or the incoming folder, wherever it lies.
	 * </p>
	 */
	private static Map<String, List<Path>> library(List<Space> spaces, Path incoming) throws IOException {
		Map<String, List<Path>> result = new TreeMap<>();
		for (Space space : spaces) {
			Path root = space._root;
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					if (!dir.equals(root) && LibraryFiles.isIgnored(dir.getFileName().toString())) {
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
					if (attrs.isRegularFile() && !LibraryFiles.isIgnored(name) && ResourceCache.isImage(file.toFile())) {
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
