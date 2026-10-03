/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Settles the one inbox of a space, once, see issue #226.
 *
 * <p>
 * Before issue #226 any album could be flagged as an inbox ({@link AlbumKind#INBOX} in its
 * <code>index.json</code>), and the camera-roll sync of each phone used the one it had stored. Now a
 * space has exactly one inbox, the folder its <code>space.json</code> names. A space whose file
 * names none yet is settled at start-up by {@link #settle(Path, String)}:
 * </p>
 * <ol>
 * <li>the albums of the space flagged as an inbox are found (the stored flag, read from every
 * <code>index.json</code> of the space, ignored folders and other spaces left out);</li>
 * <li>the inbox is the flagged album at the space root named {@link SpaceStore#DEFAULT_INBOX}, else
 * the only flagged album, else &mdash; several flagged and none of them that one &mdash; the one
 * whose sidecar was written last, which is the one a sync filled most recently; where nothing is
 * flagged it is {@link SpaceStore#DEFAULT_INBOX}, created by the first upload into it;</li>
 * <li>the decision is written into <code>space.json</code> ({@link SpaceStore#storeInbox(Path,
 * String)}), so that it is made once and not made again, differently, at the next start;</li>
 * <li>every other flagged album reads as an ordinary album from now on &mdash; the loader answers
 * the kind from the place, see {@link Inboxes#kindOf(java.io.File)} &mdash; and loses the word on
 * its next ordinary write. Nothing is rewritten here and no photograph is touched; one line names
 * them.</li>
 * </ol>
 *
 * <p>
 * The server cannot know the inbox path a phone stored, and needs not: the app reads the inbox from
 * <code>?type=auth</code> since #226 and ignores what it stored.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class InboxMigration {

	private static final Logger LOG = Logger.getLogger(InboxMigration.class.getName());

	private InboxMigration() {
		// Static utility.
	}

	/** An album an older build flagged as an inbox. */
	public static final class Flagged {
		private final String _path;

		private final long _written;

		/**
		 * Creates a {@link Flagged}.
		 *
		 * @param path
		 *        The path of the album relative to the space root, segments separated by
		 *        <code>/</code>.
		 * @param written
		 *        When its <code>index.json</code> was last written, in milliseconds.
		 */
		public Flagged(String path, long written) {
			_path = path;
			_written = written;
		}

		/** See {@link #Flagged(String, long)}. */
		public String getPath() {
			return _path;
		}

		/** See {@link #Flagged(String, long)}. */
		public long getWritten() {
			return _written;
		}

		@Override
		public String toString() {
			return _path;
		}
	}

	/**
	 * Settles the inbox of every given space that has none in its <code>space.json</code> yet, and
	 * hands the decision to the space's configuration.
	 *
	 * @return The lines to print at start-up, one per space that was settled.
	 */
	public static List<String> settleAll(Spaces spaces) {
		List<String> lines = new ArrayList<>();
		for (Spaces.Space space : spaces.getSpaces()) {
			if (space.getConfig().hasStoredInbox()) {
				continue;
			}
			String name = space.getSegment().isEmpty() ? "This library" : "Space '" + space.getSegment() + "'";
			Decision decision;
			try {
				decision = settle(space.getRoot(), space.getConfig().getInbox());
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Cannot settle the inbox of " + space + ": " + ex.getMessage(), ex);
				lines.add(name + ": cannot settle the inbox (" + ex.getMessage() + "); it is '"
					+ space.getConfig().getInbox() + "' for now, and the next start tries again.");
				continue;
			}
			space.settleInbox(decision.getInbox());
			lines.add(name + ": " + decision.describe());
		}
		return lines;
	}

	/** What {@link #settle(Path, String)} decided. */
	public static final class Decision {
		private final String _inbox;

		private final List<Flagged> _demoted;

		private final String _problem;

		Decision(String inbox, List<Flagged> demoted, String problem) {
			_inbox = inbox;
			_demoted = demoted;
			_problem = problem;
		}

		/** The path of the inbox relative to the space root. */
		public String getInbox() {
			return _inbox;
		}

		/** The flagged albums that are ordinary albums from now on. */
		public List<Flagged> getDemoted() {
			return _demoted;
		}

		/** The line printed at start-up. */
		public String describe() {
			StringBuilder result = new StringBuilder("the inbox is '" + _inbox + "'");
			if (_problem != null) {
				result.append(" (not written into space.json: ").append(_problem).append(")");
			} else {
				result.append(" (written into .valbum/space.json)");
			}
			if (_demoted.isEmpty()) {
				result.append('.');
			} else {
				result.append("; ").append(_demoted.size())
					.append(_demoted.size() == 1 ? " album an older version flagged as an inbox is"
						: " albums an older version flagged as an inbox are")
					.append(" an ordinary album now, its photographs untouched: ")
					.append(_demoted.stream().map(Flagged::getPath).collect(Collectors.joining(", ")))
					.append('.');
			}
			return result.toString();
		}
	}

	/**
	 * Settles the inbox of the space at the given root and writes it into its
	 * <code>space.json</code>.
	 *
	 * @param defaultInbox
	 *        The inbox where nothing is flagged, see {@link SpaceStore.Config#getInbox()}.
	 * @throws IOException
	 *         If the space cannot be read; nothing is written then.
	 */
	public static Decision settle(Path spaceRoot, String defaultInbox) throws IOException {
		List<Flagged> flagged = flagged(spaceRoot);
		String inbox = choose(flagged, defaultInbox);
		List<Flagged> demoted = new ArrayList<>();
		for (Flagged album : flagged) {
			if (!album.getPath().equals(inbox)) {
				demoted.add(album);
			}
		}
		String problem = null;
		try {
			SpaceStore.storeInbox(spaceRoot, inbox);
		} catch (IOException | IllegalArgumentException ex) {
			LOG.log(Level.WARNING, "Cannot write the inbox into the space.json of '" + spaceRoot + "': "
				+ ex.getMessage(), ex);
			problem = ex.getMessage();
		}
		Decision result = new Decision(inbox, demoted, problem);
		LOG.info("The inbox of '" + spaceRoot + "': " + result.describe());
		return result;
	}

	/**
	 * Which of the given flagged albums is the inbox, see {@link InboxMigration}.
	 *
	 * @param defaultInbox
	 *        The inbox where nothing is flagged, and the one preferred among several.
	 */
	public static String choose(List<Flagged> flagged, String defaultInbox) {
		if (flagged.isEmpty()) {
			return defaultInbox;
		}
		for (Flagged album : flagged) {
			if (album.getPath().equals(defaultInbox)) {
				return album.getPath();
			}
		}
		if (flagged.size() == 1) {
			return flagged.get(0).getPath();
		}
		return flagged.stream()
			.max(Comparator.comparingLong(Flagged::getWritten)
				// The same answer on every start, should two have been written in one moment.
				.thenComparing(Comparator.comparing(Flagged::getPath).reversed()))
			.get().getPath();
	}

	/**
	 * Every album below the given space root whose <code>index.json</code> says
	 * {@link AlbumKind#INBOX}, as it is stored.
	 *
	 * <p>
	 * Folders the library ignores (issue #173) and further spaces below are not looked into; a
	 * sidecar that cannot be read is no flag.
	 * </p>
	 */
	public static List<Flagged> flagged(Path spaceRoot) throws IOException {
		List<Flagged> result = new ArrayList<>();
		if (!Files.isDirectory(spaceRoot)) {
			return result;
		}
		Files.walkFileTree(spaceRoot, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(spaceRoot)) {
					if (LibraryFiles.isIgnored(dir.getFileName().toString()) || SpaceStore.isSpace(dir)) {
						return FileVisitResult.SKIP_SUBTREE;
					}
					Path sidecar = dir.resolve("index.json");
					if (Files.isRegularFile(sidecar) && flaggedInbox(sidecar)) {
						long written;
						try {
							written = Files.getLastModifiedTime(sidecar).toMillis();
						} catch (IOException ex) {
							written = 0L;
						}
						result.add(new Flagged(spaceRoot.relativize(dir).toString().replace('\\', '/'), written));
					}
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				return FileVisitResult.CONTINUE;
			}
		});
		result.sort(Comparator.comparing(Flagged::getPath));
		return result;
	}

	/** Whether the given sidecar says {@link AlbumKind#INBOX}, as it is stored. */
	private static boolean flaggedInbox(Path sidecar) {
		try (Reader reader = new InputStreamReader(Files.newInputStream(sidecar), StandardCharsets.UTF_8);
				JsonReader in = new JsonReader(new ReaderAdapter(reader))) {
			FolderResource resource = FolderResource.readFolderResource(in);
			return resource instanceof AlbumInfo && ((AlbumInfo) resource).getKind() == AlbumKind.INBOX;
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.FINE, "Cannot read '" + sidecar + "': " + ex.getMessage(), ex);
			return false;
		}
	}
}
