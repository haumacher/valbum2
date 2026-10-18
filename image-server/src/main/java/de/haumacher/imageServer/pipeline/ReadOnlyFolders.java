/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import de.haumacher.imageServer.cache.ResourceCache;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * the fix. Since issue #305 the status page of the administrator names every such folder, by its
 * path below the space and on the server, with how many photos it holds and what could not be
 * stored there ({@link #below(Path)}); this is what keeps that.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class ReadOnlyFolders {

	private static final Logger LOG = Logger.getLogger(ReadOnlyFolders.class.getName());

	/** The folders found not writable in this process, by absolute normalised path. */
	private static final Map<String, Found> FOUND = new ConcurrentHashMap<>();

	/**
	 * What is known about a folder found not writable, see {@link ReadOnlyFolders#below(Path)}.
	 *
	 * @param path
	 *        The folder, absolute and normalised.
	 * @param photos
	 *        How many photos and videos it held when it was found.
	 * @param what
	 *        What could not be stored there, in the order it was found.
	 */
	public record Found(Path path, int photos, List<String> what) {
		// A triple.
	}

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
		return FOUND.containsKey(key(folder));
	}

	/**
	 * Every folder found not writable in this process at or below the given root, sorted by path.
	 */
	public static List<Found> below(Path root) {
		Path base = root.toAbsolutePath().normalize();
		List<Found> result = new ArrayList<>();
		for (Found found : FOUND.values()) {
			if (found.path().startsWith(base)) {
				result.add(found);
			}
		}
		result.sort(Comparator.comparing(found -> found.path().toString()));
		return result;
	}

	/**
	 * Remembers that the given folder cannot be written and says so, the first time only.
	 *
	 * @param what
	 *        What could not be stored there, for the message: "its photo hashes", "its faces".
	 */
	public static void report(File folder, String what) {
		String key = key(folder);
		boolean[] first = { false };
		FOUND.compute(key, (k, before) -> {
			if (before == null) {
				first[0] = true;
				return new Found(Path.of(k), photos(folder), Collections.singletonList(what));
			}
			if (before.what().contains(what)) {
				return before;
			}
			Set<String> all = new LinkedHashSet<>(before.what());
			all.add(what);
			return new Found(before.path(), before.photos(), List.copyOf(all));
		});
		if (!first[0]) {
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

	/** How many photos and videos the given folder holds; counted once, when it is found. */
	private static int photos(File folder) {
		File[] files = folder.listFiles(file -> file.isFile() && ResourceCache.isImage(file));
		return files == null ? 0 : files.length;
	}

	private static String key(File folder) {
		return folder.toPath().toAbsolutePath().normalize().toString();
	}
}
