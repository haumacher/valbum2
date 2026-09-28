/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which names in a folder tree belong to the library, see issue #173.
 *
 * <p>
 * The one place that says so. A name starting with a dot is the server's own business (the user
 * store, the sidecars, the preview cache, the trash) or somebody else's hidden file, and the two
 * fixed lists name what a NAS or a desktop puts into the folders it sees: {@link #GENERATED} the
 * files it generates (Synology's <code>@eaDir</code> with its own thumbnails, Windows'
 * <code>Thumbs.db</code>), {@link #HELD} the folders in which it keeps the user's own files aside
 * (a share's recycle bin, its snapshots). None of them is ever a photograph, an album or a folder
 * of the library: a listing does not show it, no album holds it, no index hashes it, no walk goes
 * into it and no address reaches it, see {@link #isIgnored(String)}.
 * </p>
 *
 * <p>
 * The lists are constants and not a setting: they name what known systems write, and a folder a
 * user made is never on them. They are compared ignoring case, because the file systems these
 * names come from (SMB shares, NTFS, APFS) mostly do.
 * </p>
 *
 * <p>
 * The two lists differ in one respect only, the delete of issue #109: a {@link #isGenerated(String)
 * generated} name is removed together with a folder that holds no picture, while a
 * {@link #isHeld(String) held} one saves the folder like any foreign file, because it holds
 * somebody's files. See {@link DeleteService}.
 * </p>
 */
public final class LibraryFiles {

	/**
	 * The names of what other systems generate in the folders of a library: files nobody made and
	 * nobody wants back.
	 *
	 * <ul>
	 * <li><code>@eaDir</code>: Synology DSM's thumbnails (<code>SYNOPHOTO_THUMB_*.jpg</code>) and
	 * extended-attribute streams, in every folder of a shared folder.</li>
	 * <li><code>.@__thumb</code>: QNAP's thumbnails.</li>
	 * <li><code>Thumbs.db</code>, <code>desktop.ini</code>: Windows Explorer.</li>
	 * <li><code>.DS_Store</code>: the macOS Finder.</li>
	 * </ul>
	 */
	public static final List<String> GENERATED =
		Collections.unmodifiableList(List.of("@eaDir", ".@__thumb", "Thumbs.db", "desktop.ini", ".DS_Store"));

	/**
	 * The names of the folders in which a NAS keeps the user's own files aside: never part of the
	 * library, never deleted by it.
	 *
	 * <ul>
	 * <li><code>#recycle</code>: Synology's recycle bin of a share.</li>
	 * <li><code>#snapshot</code>: Synology's snapshot view of a share.</li>
	 * <li><code>@Recently-Snapshot</code>: QNAP's snapshot view.</li>
	 * </ul>
	 */
	public static final List<String> HELD =
		Collections.unmodifiableList(List.of("#recycle", "#snapshot", "@Recently-Snapshot"));

	private static final Set<String> GENERATED_KEYS =
		GENERATED.stream().map(LibraryFiles::key).collect(Collectors.toUnmodifiableSet());

	private static final Set<String> HELD_KEYS =
		HELD.stream().map(LibraryFiles::key).collect(Collectors.toUnmodifiableSet());

	private LibraryFiles() {
		// Static utility.
	}

	/**
	 * Whether a file or folder of the given name is no part of the library.
	 *
	 * @param name
	 *        A single name, never a path.
	 * @return Whether the name starts with a dot or is one of the {@link #GENERATED} or
	 *         {@link #HELD} names.
	 */
	public static boolean isIgnored(String name) {
		if (name == null) {
			return false;
		}
		return name.startsWith(".") || isGenerated(name) || isHeld(name);
	}

	/** Whether the given file or folder is no part of the library, see {@link #isIgnored(String)}. */
	public static boolean isIgnored(File file) {
		return isIgnored(file.getName());
	}

	/**
	 * Whether the given name is one of the fixed {@link #GENERATED} names, ignoring case.
	 *
	 * <p>
	 * Narrower than {@link #isIgnored(String)}: an arbitrary dot file is hidden from the library but
	 * is somebody's file all the same, and a {@link #HELD} folder holds somebody's files, while a
	 * generated name is known to be written by another system and is therefore removed together
	 * with a folder the delete of issue #109 removes.
	 * </p>
	 */
	public static boolean isGenerated(String name) {
		return name != null && GENERATED_KEYS.contains(key(name));
	}

	/** Whether the given name is one of the fixed {@link #HELD} names, ignoring case. */
	public static boolean isHeld(String name) {
		return name != null && HELD_KEYS.contains(key(name));
	}

	/**
	 * Whether the given path, relative to a space root with <code>/</code> as separator, lies in the
	 * library: no segment of it is {@link #isIgnored(String) ignored} (and none is empty). The root
	 * itself, the empty path, does.
	 *
	 * <p>
	 * What a store written by an earlier build is asked before a path it recorded is answered: that
	 * build did not know every name that is no part of the library now (issue #173), and a path
	 * into <code>@eaDir</code> it wrote down must lead nowhere.
	 * </p>
	 */
	public static boolean isLibraryPath(String path) {
		if (path == null) {
			return false;
		}
		if (path.isEmpty()) {
			return true;
		}
		for (String segment : path.split("/", -1)) {
			if (segment.isEmpty() || isIgnored(segment)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The longest leading part of the given path (relative, <code>/</code> as separator) that lies
	 * in the library, see {@link #isLibraryPath(String)}: the path itself where it does, the empty
	 * path (the root) where already its first segment does not.
	 */
	public static String libraryPrefix(String path) {
		if (path == null || path.isEmpty()) {
			return "";
		}
		String[] segments = path.split("/", -1);
		int end = 0;
		while (end < segments.length && !segments[end].isEmpty() && !isIgnored(segments[end])) {
			end++;
		}
		return end == segments.length ? path : String.join("/", java.util.Arrays.copyOf(segments, end));
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
