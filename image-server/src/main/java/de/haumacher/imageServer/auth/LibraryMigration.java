/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.auth.UserStore.User;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Moves a library that sits at the base folder into the space folder of its owner (issue #45).
 *
 * <p>
 * This is the <em>one</em> place in this server that moves a user's files, and it runs only when it
 * is asked for explicitly (<code>--migrate-to-user &lt;name&gt;</code>). It is a rename-only move of
 * whole entries within one file system: nothing is copied, nothing is resized, nothing is deleted,
 * and the sidecars (<code>index.json</code>, <code>.hashes.json</code>, preview caches) ride along
 * inside the folders they belong to. The server's own state (<code>.valbum</code>) and the upload
 * staging area (<code>.upload</code>) stay at the base folder.
 * </p>
 *
 * <p>
 * Everything is checked before anything is moved: a refused migration leaves the tree and the user
 * store exactly as they were.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class LibraryMigration {

	/** Thrown when the migration is not carried out; nothing was moved in that case. */
	public static class MigrationRefused extends Exception {

		/** Creates a {@link MigrationRefused}. */
		public MigrationRefused(String message) {
			super(message);
		}
	}

	/** The message a second migration is refused with. */
	public static String alreadyMigrated(String space) {
		return "This library was already migrated: the owner's photos are in '" + space
			+ "'. Nothing was moved.";
	}

	/** The message a migration under a name other than the owner's is refused with. */
	public static String nameMismatch(String ownerName, String requested) {
		return "The library owner is named '" + ownerName + "', not '" + requested
			+ "'. Migrate to '" + ownerName + "' or rename the owner first. Nothing was moved.";
	}

	/** The message a migration into an occupied folder is refused with. */
	public static String targetNotEmpty(String name) {
		return "The folder '" + name + "' already exists and is not empty. Nothing was moved.";
	}

	/** The message a migration into an existing file is refused with. */
	public static String targetNotAFolder(String name) {
		return "'" + name + "' exists and is not a folder. Nothing was moved.";
	}

	/** The message a migration is refused with that would overwrite something. */
	public static String wouldOverwrite(String entry, String name) {
		return "Moving '" + entry + "' would overwrite an entry of the same name in '" + name
			+ "'. Nothing was moved.";
	}

	/**
	 * Moves everything below the given base folder into a folder named after the library owner.
	 *
	 * <p>
	 * The owner is named (or created and named) by this call, and its
	 * {@link User#getSpace() space} is set to the new folder, so that its next request is resolved
	 * there. There is no {@link System#exit(int)} in here: the caller decides what to do with the
	 * outcome.
	 * </p>
	 *
	 * @param basePath
	 *        The served folder.
	 * @param userName
	 *        The name of the owner, which becomes the name of the folder.
	 * @return The names of the entries that were moved, sorted.
	 * @throws MigrationRefused
	 *         If the migration is not carried out; nothing was moved and the user store is
	 *         unchanged in that case.
	 */
	public static List<String> migrate(Path basePath, String userName) throws MigrationRefused, IOException {
		String name;
		try {
			name = UserStore.checkUserName(userName);
		} catch (IllegalArgumentException ex) {
			throw new MigrationRefused(ex.getMessage() + " Nothing was moved.");
		}

		UserStore users = new UserStore(basePath);
		User owner = users.getOwner();
		if (owner != null) {
			if (!owner.getSpace().isEmpty()) {
				throw new MigrationRefused(alreadyMigrated(owner.getSpace()));
			}
			if (!owner.getName().isEmpty() && !owner.getName().equals(name)) {
				throw new MigrationRefused(nameMismatch(owner.getName(), name));
			}
		}

		Path target = basePath.resolve(name);
		if (Files.exists(target)) {
			if (!Files.isDirectory(target)) {
				throw new MigrationRefused(targetNotAFolder(name));
			}
			if (!isEmpty(target)) {
				throw new MigrationRefused(targetNotEmpty(name));
			}
		}

		List<String> moved = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(basePath)) {
			for (Path entry : entries) {
				String entryName = entry.getFileName().toString();
				if (UserStore.isServerEntry(entryName) || entryName.equals(name)) {
					continue;
				}
				if (Files.exists(target.resolve(entryName))) {
					throw new MigrationRefused(wouldOverwrite(entryName, name));
				}
				moved.add(entryName);
			}
		}
		Collections.sort(moved);

		Files.createDirectories(target);
		for (String entryName : moved) {
			// A rename within one file system: no copy fallback, no partial file, no lost original.
			Files.move(basePath.resolve(entryName), target.resolve(entryName));
		}

		if (owner == null) {
			owner = users.createOwner();
		}
		owner.setName(name);
		owner.setSpace(name);
		users.store();

		return moved;
	}

	private static boolean isEmpty(Path directory) throws IOException {
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			return !entries.iterator().hasNext();
		}
	}
}
