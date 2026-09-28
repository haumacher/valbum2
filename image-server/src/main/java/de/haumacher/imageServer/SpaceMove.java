/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.SpaceCreation.Refused;
import de.haumacher.imageServer.SpaceCreation.Report;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.SpaceAlias;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.upload.HashCache;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Moving a single-space library into a space of its own: <code>--move-into-space &lt;folder&gt;</code>
 * (issue #177).
 *
 * <p>
 * A library of the current model is one space already: its <code>.valbum</code> holds the users
 * with their devices and permissions, the share links, the device codes and invitations, the
 * register of people, the <code>space.json</code> and the hash index of that one space. Moving it
 * into <code>&lt;base&gt;/&lt;folder&gt;/</code> is therefore nothing but renames: every album,
 * folder and photo at the base folder's root, the root's own sidecars and preview cache, and the
 * whole <code>.valbum</code> go into the new folder, where they mean exactly what they meant
 * before, because every path they store is relative to the space root. Nothing is copied, nothing
 * deleted, nothing retired.
 * </p>
 *
 * <p>
 * What stays at the base folder is what is no part of the library ({@link LibraryFiles#isIgnored(String)}:
 * another system's hidden or generated files, a NAS's recycle bin and snapshots) and the upload
 * staging area <code>.upload</code> of the old address, which holds nothing between two uploads;
 * a multi-space server keeps its staging area per space. Then the job writes the space's
 * <code>space.json</code> and the {@link SpaceAlias marker} that keeps the old addresses answering
 * as the new space.
 * </p>
 *
 * <p>
 * Everything is checked before anything is moved; a refusal moves and writes nothing.
 * </p>
 */
public final class SpaceMove {

	/** The server's own entries at a space root that are hidden and belong to the library all the same. */
	private static final Set<String> SERVER_ENTRIES =
		Set.of(UserStore.DIRECTORY_NAME, PreviewCache.CACHE_DIRECTORY_NAME, HashCache.FILE_NAME);

	/** The refusal of a server forced into single-space mode. */
	public static final String FORCED_SINGLE = "--spaces single serves the base folder as the one space and "
		+ "never a folder below it; the library moved into a space would not be served. Nothing was moved.";

	private SpaceMove() {
		// Static utility.
	}

	/** The refusal of a target folder that holds something. */
	public static String targetNotEmpty(String folder) {
		return "The folder '" + folder + "' exists and is not empty; the library moves only into a new or "
			+ "empty folder. Nothing was moved.";
	}

	/** The refusal of a base folder that is a multi-space server already. */
	public static String alreadyMulti(List<String> spaces) {
		return "The base folder is a server of several spaces already (" + String.join(", ", spaces)
			+ "); there is no single-space library to move. Nothing was moved.";
	}

	/** The refusal of a library that was moved already. */
	public static String alreadyMoved(String space) {
		return "This library was moved into the space '" + space + "' already (" + UserStore.DIRECTORY_NAME + "/"
			+ SpaceAlias.FILE_NAME + "). Nothing was moved.";
	}

	/** The refusal of a library split per user before spaces existed. */
	public static String perUser(String user, String folder) {
		return "This library was split per user before spaces existed (user '" + user + "' owns the folder '"
			+ folder + "'); migrate-to-spaces turns it into spaces. Nothing was moved.";
	}

	/** The refusal of a rename that would cross file systems. */
	public static String crossesFileSystems(String entry, String folder) {
		return "'" + entry + "' lies on another file system than '" + folder + "', and the move only renames: "
			+ "it never copies a photo. Nothing was moved.";
	}

	/**
	 * Moves the library of the base folder into the space <code>folder</code>.
	 *
	 * @param basePath
	 *        The served folder, a single-space library.
	 * @param folder
	 *        The name of the new space's folder directly below the base folder.
	 * @param name
	 *        The name to show for the space; empty or <code>null</code> to keep what the library's
	 *        <code>space.json</code> says.
	 * @param auth
	 *        The server's <code>--auth</code>, which decides whether visitors who are not signed
	 *        in see the public photos of a single-space library; the space keeps that.
	 * @param mode
	 *        What <code>--spaces</code> says; <code>null</code> for <code>auto</code>.
	 * @throws Refused
	 *         If the library is not moved; nothing was moved or written then.
	 */
	public static Report move(Path basePath, String folder, String name, AuthMode auth, SpaceMode mode)
			throws Refused, IOException {
		if (mode == SpaceMode.SINGLE) {
			throw new Refused(FORCED_SINGLE);
		}
		if (!Files.isDirectory(basePath)) {
			throw new Refused("The base folder '" + basePath + "' does not exist. Nothing was moved.");
		}
		if (folder == null || !FolderNames.isLegal(folder) || !folder.equals(folder.trim())) {
			throw new Refused(SpaceCreation.illegalName(folder));
		}
		if (SpaceCreation.RESERVED.contains(folder)) {
			throw new Refused(SpaceCreation.reservedName(folder));
		}

		String moved = readMarker(basePath);
		if (moved != null) {
			throw new Refused(alreadyMoved(moved));
		}
		List<String> spaces = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(basePath)) {
			for (Path entry : entries) {
				if (Files.isDirectory(entry) && SpaceStore.isSpace(entry)) {
					spaces.add(entry.getFileName().toString());
				}
			}
		}
		if (!spaces.isEmpty()) {
			Collections.sort(spaces);
			throw new Refused(alreadyMulti(spaces));
		}
		List<UserStore.User> users;
		try {
			users = UserStore.read(basePath);
		} catch (IOException ex) {
			throw new Refused("Cannot read the user store: " + ex.getMessage() + " Nothing was moved.");
		}
		for (UserStore.User user : users) {
			if (!user.getSpace().isEmpty()) {
				throw new Refused(perUser(user.getName(), user.getSpace()));
			}
		}
		String anonymous;
		try {
			SpaceStore.Config config = SpaceStore.load(basePath, folder);
			// A single-space library answers visitors who are not signed in by the server's --auth
			// alone; a space answers them by its own space.json, see Spaces#authModeOf.
			anonymous = auth == AuthMode.WRITES ? SpaceStore.ANONYMOUS_PUBLIC : config.getAnonymous();
		} catch (IOException ex) {
			throw new Refused(ex.getMessage() + " Nothing was moved.");
		}

		Path target = basePath.resolve(folder);
		boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
		if (exists) {
			if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
				throw new Refused(SpaceCreation.notAFolder(folder));
			}
			try (DirectoryStream<Path> entries = Files.newDirectoryStream(target)) {
				if (entries.iterator().hasNext()) {
					throw new Refused(targetNotEmpty(folder));
				}
			}
		}

		// The whole list first.
		List<String> moving = new ArrayList<>();
		List<String> staying = new ArrayList<>();
		int folders = 0;
		int photos = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(basePath)) {
			for (Path entry : entries) {
				String entryName = entry.getFileName().toString();
				if (entryName.equals(folder)) {
					continue;
				}
				if (SERVER_ENTRIES.contains(entryName) || !LibraryFiles.isIgnored(entryName)) {
					moving.add(entryName);
					if (!SERVER_ENTRIES.contains(entryName)) {
						if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
							folders++;
						} else if (ResourceCache.isImage(entry.toFile())) {
							photos++;
						}
					}
				} else {
					staying.add(entryName);
				}
			}
		}
		Collections.sort(moving);
		Collections.sort(staying);

		FileStore store = Files.getFileStore(exists ? target : basePath);
		for (String entryName : moving) {
			Path entry = basePath.resolve(entryName);
			// A symbolic link is renamed itself, wherever it points to.
			if (!Files.isSymbolicLink(entry) && !store.equals(Files.getFileStore(entry))) {
				throw new Refused(crossesFileSystems(entryName, folder));
			}
		}
		if (exists && !store.equals(Files.getFileStore(basePath))) {
			throw new Refused(crossesFileSystems(folder, "the base folder"));
		}

		// Now the renames.
		if (!exists) {
			Files.createDirectory(target);
		}
		List<String> done = new ArrayList<>();
		for (String entryName : moving) {
			try {
				// Atomic: a rename within one file system, never a copy.
				Files.move(basePath.resolve(entryName), target.resolve(entryName), StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException ex) {
				throw new IOException("Moving '" + entryName + "' into '" + folder + "' failed: " + ex.getMessage()
					+ (done.isEmpty() ? " Nothing was moved."
						: " Moved before: " + String.join(", ", done) + "; move them back to the base folder "
							+ "by hand to undo."),
					ex);
			}
			done.add(entryName);
		}
		SpaceStore.rewrite(target, name, anonymous);
		SpaceAlias.write(basePath, folder, Instant.now());

		SpaceStore.Config written = SpaceStore.load(target, folder);
		Report report = new Report();
		report.say("Moved " + folders + " folder(s) and " + photos + " photo(s) at the root into '" + folder
			+ "'" + (moving.contains(UserStore.DIRECTORY_NAME)
				? ", with the library's own " + UserStore.DIRECTORY_NAME + ": the users with their devices and "
					+ "permissions, the share links, the invitations, the people and the hash index."
				: "."));
		report.say("Renamed: " + String.join(", ", moving) + ".");
		boolean upload = staying.remove(UserStore.UPLOAD_DIRECTORY_NAME);
		if (!staying.isEmpty()) {
			report.say("Left at the base folder, as no part of the library: " + String.join(", ", staying) + ".");
		}
		if (upload) {
			report.say("Left at the base folder: " + UserStore.UPLOAD_DIRECTORY_NAME + ", the upload staging area of "
				+ "the old address (empty between uploads; the space has its own).");
		}
		report.say("Wrote " + folder + "/" + UserStore.DIRECTORY_NAME + "/" + SpaceStore.FILE_NAME + ": name '"
			+ written.getName() + "', anonymous " + written.getAnonymous() + ", faces " + written.getFaces() + ".");
		if (auth == AuthMode.WRITES) {
			report.say("Visitors who are not signed in keep seeing the public photos, as --auth writes showed them "
				+ "(anonymous public).");
		}
		report.say("Wrote " + UserStore.DIRECTORY_NAME + "/" + SpaceAlias.FILE_NAME + ": the old addresses "
			+ "<context>/, <context>/data/, <context>/s/<token>/ and <context>/i/<token>/ keep answering as the "
			+ "space '" + folder + "', so every signed-in device, share link and invitation keeps working. "
			+ "Delete that file to end it.");
		report.say("New addresses: <context>/" + folder + "/ (the application) and <context>/" + folder
			+ "/data/ (its albums); share links and invitations made from now on are spelled with them.");
		report.say("Nothing was copied or deleted. The server serves the space from its next start.");
		return report;
	}

	private static String readMarker(Path basePath) throws Refused {
		try {
			return SpaceAlias.read(basePath);
		} catch (IOException ex) {
			throw new Refused(ex.getMessage() + " Nothing was moved.");
		}
	}
}
