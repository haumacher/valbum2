/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The folders of the library the server cannot write its sidecars and its cache into, see issue
 * #235.
 *
 * <p>
 * A library copied in by hand as another user than the service's is readable and not writable:
 * every photograph shows, but no <code>.hashes.json</code>, no <code>.vacache</code> can be stored
 * beside it. What could not be stored is then kept in memory for the life of the process, and the
 * folder must not be worked on again and again for nothing. This is where that is remembered, and
 * where it is said &mdash; once per folder and process, as one WARNING that names the folder and
 * the fix. There is no admin-facing diagnostics channel the server could put this into, so the log
 * is the channel.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class ReadOnlyFolders {

	private static final Logger LOG = Logger.getLogger(ReadOnlyFolders.class.getName());

	/** The folders found not writable in this process, by absolute normalised path. */
	private static final Set<String> FOUND = ConcurrentHashMap.newKeySet();

	private ReadOnlyFolders() {
		// Static use only.
	}

	/** The logger the WARNING goes to, for the tests. */
	public static Logger logger() {
		return LOG;
	}

	/**
	 * Whether the server's user can write into the given folder.
	 *
	 * <p>
	 * Asked of the operating system, so it is <code>true</code> for root whatever the mode bits
	 * say.
	 * </p>
	 */
	public static boolean writable(File folder) {
		return Files.isWritable(folder.toPath());
	}

	/** Whether the given folder was found not writable in this process. */
	public static boolean isReadOnly(File folder) {
		return FOUND.contains(key(folder));
	}

	/**
	 * Remembers that the given folder cannot be written and says so, the first time only.
	 *
	 * @param what
	 *        What could not be stored there, for the message: "its photo hashes", "its faces".
	 */
	public static void report(File folder, String what) {
		if (!FOUND.add(key(folder))) {
			return;
		}
		LOG.warning("The folder '" + folder.getAbsolutePath() + "' is not writable by the server's user '"
			+ System.getProperty("user.name") + "': " + what
			+ " cannot be stored there and are kept in memory until the server stops. Nothing is retried"
			+ " for this folder until it changes. Fix: give the library to the service user,"
			+ " e.g. 'chown -R valbum:valbum <library>', and restart.");
	}

	/** Forgets that the given folder was not writable: it was written after all. */
	public static void writtenAgain(File folder) {
		FOUND.remove(key(folder));
	}

	private static String key(File folder) {
		return folder.toPath().toAbsolutePath().normalize().toString();
	}
}
