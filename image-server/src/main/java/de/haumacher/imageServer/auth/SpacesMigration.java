/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Turns a library into the space model of Phase 6, explicitly and reported (issue #82).
 *
 * <p>
 * Run as <code>--migrate-to-spaces</code>, like the per-user migration of issue #45: it refuses to
 * start the server, it is a rename-only step within one file system, and it never deletes
 * anything. What the space model cannot represent is <em>moved aside</em> into
 * <code>&lt;base&gt;/.valbum/{@value #RETIRED_DIRECTORY_NAME}/&lt;timestamp&gt;/</code> and named
 * in the printed report, so that an admin can re-create it as an invitation or a share link
 * instead of finding it silently gone.
 * </p>
 *
 * <p>
 * Two starting points, and the difference is what the user store says:
 * </p>
 * <ul>
 * <li>A library that was never migrated per user — nobody owns a folder below the base folder —
 * <em>is</em> the one space of a single-space server. Nothing moves, the user store stays where it
 * is, and the report says so.</li>
 * <li>A library migrated per user becomes a multi-space server: every user folder gets a
 * {@value SpaceStore#FILE_NAME} and a user store of its own holding that user as the space's admin
 * with their devices, so every token that worked keeps working — in that space, and nowhere
 * else.</li>
 * </ul>
 *
 * <p>
 * Share links are retired rather than carried. A link works today only together with the
 * <code>token:</code> grant it created, and grants are exactly what the space model does not have;
 * carrying half of the mechanism into a file that issue #84 rewrites would leave links that work
 * in this build and break in the next. Every link is named in the report with its label and its
 * folder, which is what somebody needs to share it again.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class SpacesMigration {

	/** The directory below <code>.valbum</code> that retired state is moved into. */
	public static final String RETIRED_DIRECTORY_NAME = "retired";

	/** The name of the folder within the retired directory holding sidecars found in the tree. */
	public static final String TREE_DIRECTORY_NAME = "tree";

	/** Thrown when the migration is not carried out; nothing was moved in that case. */
	public static class MigrationRefused extends Exception {

		/** Creates a {@link MigrationRefused}. */
		public MigrationRefused(String message) {
			super(message);
		}
	}

	/** The message a second migration is refused with. */
	public static String alreadyMigrated(String space) {
		return "This library is already a multi-space server: '" + space + "' carries a "
			+ SpaceStore.FILE_NAME + ". Nothing was moved.";
	}

	/** The message a migration into a space folder that already has users is refused with. */
	public static String spaceOccupied(String space) {
		return "The folder '" + space + "' already carries its own " + UserStore.FILE_NAME
			+ ". Nothing was moved.";
	}

	/** The message a migration of a user whose space folder is missing is refused with. */
	public static String spaceMissing(String name, String space) {
		return "The space folder '" + space + "' of user '" + name + "' does not exist. Nothing was moved.";
	}

	/** What the migration did, line by line, and which spaces exist afterwards. */
	public static final class Report {

		private final List<String> _lines = new ArrayList<>();

		private final List<String> _spaces = new ArrayList<>();

		void say(String line) {
			_lines.add(line);
		}

		void space(String segment) {
			_spaces.add(segment);
		}

		/** The report, one line each, in the order things happened. */
		public List<String> getLines() {
			return Collections.unmodifiableList(_lines);
		}

		/** The spaces this server hosts afterwards; empty for a single-space server. */
		public List<String> getSpaces() {
			return Collections.unmodifiableList(_spaces);
		}

		/** Whether a line containing the given text was reported; for the tests. */
		public boolean mentions(String text) {
			return _lines.stream().anyMatch(line -> line.contains(text));
		}

		@Override
		public String toString() {
			return String.join("\n", _lines);
		}
	}

	/**
	 * Carries out the migration.
	 *
	 * @param basePath
	 *        The served folder.
	 * @throws MigrationRefused
	 *         If the migration is not carried out; nothing was moved then.
	 */
	public static Report migrate(Path basePath) throws MigrationRefused, IOException {
		Report report = new Report();
		Path serverState = basePath.resolve(UserStore.DIRECTORY_NAME);

		for (String folder : subFolders(basePath)) {
			if (SpaceStore.isSpace(basePath.resolve(folder))) {
				throw new MigrationRefused(alreadyMigrated(folder));
			}
		}

		UserStore users = new UserStore(basePath);
		List<User> all = users.getUsers();
		List<User> spaced = new ArrayList<>();
		for (User user : all) {
			if (!user.getSpace().isEmpty()) {
				spaced.add(user);
			}
		}

		if (spaced.isEmpty()) {
			// Nothing owns a folder below the base: this library is the one space it always was.
			report.say("This library was never migrated per user, so it is one space: the base folder.");
			report.say("The server runs in single-space mode; " + UserStore.DIRECTORY_NAME + "/"
				+ UserStore.FILE_NAME + " stays where it is. Nothing was moved.");
			return report;
		}

		// Everything is checked before anything is written.
		for (User user : spaced) {
			Path root = basePath.resolve(user.getSpace());
			if (!Files.isDirectory(root)) {
				throw new MigrationRefused(spaceMissing(user.getName(), user.getSpace()));
			}
			if (Files.exists(root.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME))) {
				throw new MigrationRefused(spaceOccupied(user.getSpace()));
			}
		}

		for (User user : spaced) {
			Path root = basePath.resolve(user.getSpace());
			String name = user.getName().isEmpty() ? user.getSpace() : user.getName();
			SpaceStore.store(root, new SpaceStore.Config(name, SpaceStore.ANONYMOUS_NONE));

			// The admin of their own space, with everything in it, and their devices along.
			User admin = new User(name, Roles.ADMIN, "", user.getCreated().isEmpty()
				? Instant.now().toString() : user.getCreated(), Clearances.ALL, true);
			for (Device device : user.getDevices()) {
				admin.addDevice(new Device(device.getId(), device.getName(), device.getTokenHash(),
					device.getCreated()));
			}
			UserStore spaceUsers = new UserStore(root);
			spaceUsers.addUser(admin);
			spaceUsers.store();

			report.space(user.getSpace());
			report.say("Space '" + user.getSpace() + "': admin '" + name + "' with "
				+ user.getDevices().size() + " device(s); their tokens keep working in this space only.");
		}

		for (User user : all) {
			if (user.getSpace().isEmpty()) {
				report.say("User '" + (user.getName().isEmpty() ? "<unnamed>" : user.getName()) + "' (role "
					+ user.getRole() + ") owned no folder and has no space → invite them into one.");
			}
		}

		Path retired = serverState.resolve(RETIRED_DIRECTORY_NAME)
			.resolve(Instant.now().toString().replace(':', '-'));
		retireState(basePath, serverState, retired, report);
		retireTreeSidecars(basePath, retired, report);
		report.say("What was set aside is under " + UserStore.DIRECTORY_NAME + "/" + RETIRED_DIRECTORY_NAME
			+ "/; nothing was deleted.");
		return report;
	}

	/** Moves the server-wide state aside, naming what each file held. */
	private static void retireState(Path basePath, Path serverState, Path retired, Report report)
			throws IOException {
		retire(serverState.resolve(UserStore.FILE_NAME), retired, report,
			"the users of the whole server; every user is now the admin of their own space");
		retire(serverState.resolve("grants.json"), retired, report,
			"who was allowed into somebody else's albums; nothing crosses a space boundary any more "
				+ "→ invite them into that space, or share a link");
		retire(serverState.resolve("groups.json"), retired, report,
			"the named groups; a permission belongs to a user now → invite the members individually");
		retire(serverState.resolve(ShareStore.FILE_NAME), retired, report,
			"the share links; each worked together with a grant that cannot be carried → share again "
				+ "from within the space");
		retire(serverState.resolve(InvitationStore.FILE_NAME), retired, report,
			"the open invitations of the old server → invite again from within the space");
		retire(serverState.resolve("device-codes.json"), retired, report,
			"the short-lived device codes; they live ten minutes and are simply asked for again");
		retire(serverState.resolve(UserStore.GUESTS_DIRECTORY_NAME), retired, report,
			"the little roots of the guests; a guest is a user with a low clearance now → invite them "
				+ "into a space");
	}

	/** Moves the link and share sidecars found anywhere in the tree aside, each named. */
	private static void retireTreeSidecars(Path basePath, Path retired, Report report) throws IOException {
		List<Path> found = new ArrayList<>();
		collect(basePath, basePath, found);
		for (Path file : found) {
			Path relative = basePath.relativize(file);
			Path target = retired.resolve(TREE_DIRECTORY_NAME).resolve(relative);
			Files.createDirectories(target.getParent());
			Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
			report.say("Set aside '" + relative + "': an album shared into somebody's tree; links are "
				+ "not part of the space model → share a link instead.");
		}
	}

	private static final List<String> TREE_SIDECARS =
		Arrays.asList(".links.json", "share-registry.json");

	private static void collect(Path basePath, Path folder, List<Path> found) throws IOException {
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (Files.isDirectory(entry)) {
					if (folder.equals(basePath) && UserStore.UPLOAD_DIRECTORY_NAME.equals(name)) {
						continue;
					}
					if (folder.equals(basePath) && UserStore.DIRECTORY_NAME.equals(name)) {
						// The server's own state is retired by name, not by this walk.
						continue;
					}
					collect(basePath, entry, found);
				} else if (TREE_SIDECARS.contains(name)) {
					found.add(entry);
				}
			}
		}
	}

	/** Moves one file or folder aside, if it is there at all. */
	private static void retire(Path source, Path retired, Report report, String held) throws IOException {
		if (!Files.exists(source)) {
			return;
		}
		Files.createDirectories(retired);
		Path target = retired.resolve(source.getFileName().toString());
		Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		report.say("Set aside '" + source.getFileName() + "': " + held + ".");
	}

	private static List<String> subFolders(Path basePath) throws IOException {
		List<String> result = new ArrayList<>();
		if (!Files.isDirectory(basePath)) {
			return result;
		}
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(basePath)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (Files.isDirectory(entry) && !UserStore.isServerEntry(name)) {
					result.add(name);
				}
			}
		}
		Collections.sort(result);
		return result;
	}
}
