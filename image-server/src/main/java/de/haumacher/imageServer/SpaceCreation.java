/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Creating a space on disk, see issue #175: <code>--create-space &lt;folder&gt;</code>.
 *
 * <p>
 * A space is a folder directly below the base folder that carries
 * <code>.valbum/{@value SpaceStore#FILE_NAME}</code> (issue #82). Until this command the file was
 * typed by hand, and a typo read as "closed" without a word. The command writes it through
 * {@link SpaceStore#create(Path, String, String, String)}, so the file is always one the server
 * reads back as it was written, and it runs like the migrations and
 * <code>--replace-originals</code>: the server is not started, a running one picks the space up at
 * its next start, and that start prints the sign-in code of the new space's administrator (issue
 * #89, {@link Main#reportSpace}).
 * </p>
 *
 * <p>
 * It creates <code>&lt;base&gt;/&lt;folder&gt;/.valbum/space.json</code> and, where it is
 * missing, the folder itself, and nothing else: no album is moved and no original is touched. An
 * existing folder is accepted, empty or holding albums, and then <em>becomes</em> the space with
 * everything in it.
 * </p>
 *
 * <p>
 * Everything is checked before anything is written; a refusal writes nothing. Refused are:
 * </p>
 * <ul>
 * <li>a server forced into single-space mode ({@link #FORCED_SINGLE}), which would never serve
 * the space;</li>
 * <li>a folder name no folder of the library may have ({@link FolderNames#isLegal(String)}, which
 * includes the names of issue #173) and the names a multi-space server already answers at its
 * context root ({@link #RESERVED});</li>
 * <li>a folder that is a space already, and a file of that name;</li>
 * <li>a base folder that is served as one space and has content of its own — an album, a photo, or
 * a user store beyond the unclaimed seat of its first start ({@link #isClaimed(Path)}) at its
 * root. Under <code>--spaces auto</code> the first space flips the server to
 * multi-space mode, and every album at the root would vanish from its address; the library has to
 * be moved into a space first, see {@link #singleLibrary(List, boolean)}.</li>
 * </ul>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class SpaceCreation {

	/**
	 * The first path segments a multi-space server answers at its context root without a space:
	 * the data root, the two session prefixes, and the folders of the web application, whose files
	 * a space of the same name would shadow.
	 */
	public static final Set<String> RESERVED = Set.of("data", ShareStore.URL_SEGMENT, InvitationStore.URL_SEGMENT,
		"assets", "canvaskit", "icons");

	/** The refusal of a server forced into single-space mode. */
	public static final String FORCED_SINGLE = "--spaces single serves the base folder as the one space and "
		+ "never a folder below it; a space created now would not be served. Nothing was written.";

	/** Thrown when the space is not created; nothing was written then. */
	public static final class Refused extends Exception {
		private static final long serialVersionUID = 1L;

		Refused(String message) {
			super(message);
		}
	}

	/** What the command did, line by line. */
	public static final class Report {
		private final List<String> _lines = new ArrayList<>();

		void say(String line) {
			_lines.add(line);
		}

		/** The report, one line each. */
		public List<String> getLines() {
			return Collections.unmodifiableList(_lines);
		}

		@Override
		public String toString() {
			return String.join("\n", _lines);
		}
	}

	private SpaceCreation() {
		// Static utility.
	}

	/** The refusal of a folder name no folder of the library may have. */
	public static String illegalName(String folder) {
		return "'" + folder + "' cannot be the name of a space folder: a single folder name that does not "
			+ "start with a dot and is none of the names a NAS or a desktop writes. Nothing was written.";
	}

	/** The refusal of a name the server answers at its context root. */
	public static String reservedName(String folder) {
		return "'" + folder + "' cannot be the name of a space: the server answers '<context>/" + folder
			+ "/' itself. Nothing was written.";
	}

	/** The refusal of a folder that is a space already. */
	public static String alreadyASpace(String folder) {
		return "'" + folder + "' is a space already (it carries " + UserStore.DIRECTORY_NAME + "/"
			+ SpaceStore.FILE_NAME + "). Nothing was written.";
	}

	/** The refusal of a name that is a file. */
	public static String notAFolder(String folder) {
		return "'" + folder + "' exists and is not a folder. Nothing was written.";
	}

	/**
	 * The refusal of a base folder that is a single-space library with content at its root.
	 *
	 * <p>
	 * The way there is {@link SpaceMove}, which carries the library with everything it knows into a
	 * space of its own and keeps its old addresses answering (issue #177).
	 * </p>
	 *
	 * @param found
	 *        What stands at the root, a few names.
	 * @param users
	 *        Whether the base folder carries a user store.
	 */
	public static String singleLibrary(List<String> found, boolean users) {
		StringBuilder what = new StringBuilder();
		if (!found.isEmpty()) {
			what.append(found.size() == 1 ? "the entry " : "the entries ");
			int shown = Math.min(3, found.size());
			for (int n = 0; n < shown; n++) {
				what.append(n == 0 ? "" : ", ").append('\'').append(found.get(n)).append('\'');
			}
			if (found.size() > shown) {
				what.append(" and ").append(found.size() - shown).append(" more");
			}
		}
		if (users) {
			what.append(what.length() == 0 ? "" : " and ").append("its user store (")
				.append(UserStore.DIRECTORY_NAME).append('/').append(UserStore.FILE_NAME).append(')');
		}
		return "The base folder is a single-space library with " + what + " at its root; a first space "
			+ "would switch the server to multi-space mode and take them off their addresses. Move the "
			+ "library into a space of its own first: valbum-admin move-into-space <folder>. "
			+ "Nothing was written.";
	}

	/**
	 * Creates the space.
	 *
	 * @param basePath
	 *        The served folder.
	 * @param folder
	 *        The name of the space's folder directly below the base folder, which is also the first
	 *        segment of the space's addresses.
	 * @param name
	 *        The name to show for the space; empty or <code>null</code> for the folder's own.
	 * @param anonymous
	 *        {@link SpaceStore#ANONYMOUS_NONE} or {@link SpaceStore#ANONYMOUS_PUBLIC}.
	 * @param faces
	 *        {@link SpaceStore#FACES_OFF} or {@link SpaceStore#FACES_ON}.
	 * @param mode
	 *        What <code>--spaces</code> says; <code>null</code> for <code>auto</code>.
	 * @throws Refused
	 *         If the space is not created; nothing was written then.
	 */
	public static Report create(Path basePath, String folder, String name, String anonymous, String faces,
			SpaceMode mode) throws Refused, IOException {
		return create(basePath, folder, name, anonymous, faces, null, mode);
	}

	/**
	 * Creates the space, see {@link #create(Path, String, String, String, String, SpaceMode)}.
	 *
	 * @param timeZone
	 *        The zone the photographs of the space are dated in where they do not say theirs (issue
	 *        #183), an id {@link SpaceStore#zoneOf(String)} knows; <code>null</code> or empty for the
	 *        server's zone. An unknown id is refused before anything is written.
	 */
	public static Report create(Path basePath, String folder, String name, String anonymous, String faces,
			String timeZone, SpaceMode mode) throws Refused, IOException {
		if (mode == SpaceMode.SINGLE) {
			throw new Refused(FORCED_SINGLE);
		}
		if (timeZone != null && !timeZone.trim().isEmpty() && SpaceStore.zoneOf(timeZone) == null) {
			throw new Refused(SpaceStore.unknownTimeZone(timeZone) + " Nothing was written.");
		}
		if (!Files.isDirectory(basePath)) {
			throw new Refused("The base folder '" + basePath + "' does not exist. Nothing was written.");
		}
		if (folder == null || !FolderNames.isLegal(folder) || !folder.equals(folder.trim())) {
			throw new Refused(illegalName(folder));
		}
		if (RESERVED.contains(folder)) {
			throw new Refused(reservedName(folder));
		}

		Path root = basePath.resolve(folder);
		boolean exists = Files.exists(root, LinkOption.NOFOLLOW_LINKS);
		if (exists && !Files.isDirectory(root)) {
			throw new Refused(notAFolder(folder));
		}
		if (SpaceStore.isSpace(root)) {
			throw new Refused(alreadyASpace(folder));
		}

		List<String> spaces = new ArrayList<>();
		List<String> content = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(basePath)) {
			for (Path entry : entries) {
				String entryName = entry.getFileName().toString();
				if (LibraryFiles.isIgnored(entryName)) {
					continue;
				}
				if (Files.isDirectory(entry)) {
					if (SpaceStore.isSpace(entry)) {
						spaces.add(entryName);
					} else if (!entryName.equals(folder)) {
						content.add(entryName);
					}
				} else if (ResourceCache.isImage(entry.toFile())) {
					content.add(entryName);
				}
			}
		}
		Collections.sort(spaces);
		Collections.sort(content);
		boolean users = isClaimed(basePath);
		// Only 'auto' flips: a forced multi-space server serves no root album already.
		if (mode == null && spaces.isEmpty() && (!content.isEmpty() || users)) {
			throw new Refused(singleLibrary(content, users));
		}

		String found = exists ? describe(root, folder) : null;

		try {
			SpaceStore.create(root, name, anonymous, faces, timeZone);
		} catch (FileAlreadyExistsException ex) {
			throw new Refused(alreadyASpace(folder));
		}
		SpaceStore.Config written = SpaceStore.load(root, folder);

		Report report = new Report();
		report.say(exists ? found : "Created the folder '" + folder + "'.");
		report.say("Wrote " + folder + "/" + UserStore.DIRECTORY_NAME + "/" + SpaceStore.FILE_NAME + ": name '"
			+ written.getName() + "', anonymous " + written.getAnonymous() + ", faces " + written.getFaces()
			+ ", time zone " + (written.getTimeZone().isEmpty() ? "the server's (" + java.time.ZoneId.systemDefault() + ")"
				: written.getTimeZone()) + ".");
		if (Files.exists(root.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME))) {
			report.say("The folder carries a user store of its own already; its users and devices are the "
				+ "space's.");
		}
		if (mode == SpaceMode.MULTI && !content.isEmpty()) {
			report.say("Not served by this multi-space server, as before: " + String.join(", ", content)
				+ " (outside every space).");
		}
		if (spaces.isEmpty()) {
			report.say("This is the first space: the server switches to multi-space mode at its next start.");
			if (Files.exists(basePath.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME))) {
				report.say("The base folder's own administrator seat, which nobody claimed, stays in "
					+ UserStore.DIRECTORY_NAME + "/ unused: no address reaches it in multi-space mode.");
			}
		} else {
			report.say("The other space(s): " + String.join(", ", spaces) + ".");
		}
		report.say("Addresses: <context>/" + folder + "/ (the application) and <context>/" + folder
			+ "/data/ (its albums).");
		report.say("The sign-in code for the administrator of '" + folder + "' appears in the server's log "
			+ "(journalctl -u valbum, or the container log) when the server starts, which valbum-admin does "
			+ "next; it is valid " + DeviceCodeStore.LIFETIME_MINUTES + " minutes, and --admin-code fixes it.");
		return report;
	}

	/**
	 * Whether the stores in the base folder's <code>.valbum</code> hold anything a first start of a
	 * fresh library did not write there itself.
	 *
	 * <p>
	 * Every first start writes the seat of the space's administrator (issue #89): a
	 * <code>users.json</code> with one user who has no name, no device and no invitation, and a
	 * <code>device-codes.json</code> with the seat codes the server issued for them. That is an
	 * empty library, and a first space may be created beside it; the seat is left where it is, where
	 * no address of a multi-space server reaches it. Anything more is somebody's: a named user, a
	 * device, a pending invitation, a code a device issued, share links, an older store - and
	 * refuses, as does a store that cannot be read.
	 * </p>
	 */
	static boolean isClaimed(Path basePath) {
		Path state = basePath.resolve(UserStore.DIRECTORY_NAME);
		for (String store : List.of(UserStore.LEGACY_FILE_NAME, ShareStore.FILE_NAME, InvitationStore.FILE_NAME)) {
			if (Files.exists(state.resolve(store))) {
				return true;
			}
		}
		try {
			List<UserStore.User> users = UserStore.read(basePath);
			if (users.size() > 1) {
				return true;
			}
			for (UserStore.User user : users) {
				if (!user.getName().isEmpty() || !user.getDevices().isEmpty() || !user.getInvitation().isEmpty()
					|| !user.getInvitedBy().isEmpty()) {
					return true;
				}
			}
			for (DeviceCodeStore.Code code : DeviceCodeStore.read(basePath)) {
				if (!DeviceCodeStore.SERVER_ISSUER.equals(code.getIssuedBy()) || !code.getUser().isEmpty()
					|| code.isBackup() || code.isInvitation()) {
					return true;
				}
			}
			return false;
		} catch (IOException ex) {
			return true;
		}
	}

	/** What an existing folder that becomes the space holds, in one line. */
	private static String describe(Path root, String folder) throws IOException {
		int folders = 0;
		int photos = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
			for (Path entry : entries) {
				if (LibraryFiles.isIgnored(entry.getFileName().toString())) {
					continue;
				}
				if (Files.isDirectory(entry)) {
					folders++;
				} else if (ResourceCache.isImage(entry.toFile())) {
					photos++;
				}
			}
		}
		if (folders == 0 && photos == 0) {
			return "The folder '" + folder + "' exists and is empty; it becomes the space.";
		}
		return "The folder '" + folder + "' exists and holds " + folders + " folder(s) and " + photos
			+ " photo(s) directly inside; it becomes the space with everything in it, nothing moved.";
	}
}
