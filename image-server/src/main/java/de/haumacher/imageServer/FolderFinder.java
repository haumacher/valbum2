/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.AnalysisRecord;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.FolderMatch;
import de.haumacher.imageServer.shared.model.FolderMatches;
import java.io.File;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The search field of the folder picker, see issue #261: the albums and folders below a folder whose
 * title, folder name or date matches what was typed.
 *
 * <p>
 * Every folder is described exactly as the listing above it describes its tile
 * ({@link ResourceCache#folderInfo(File)}): from its sidecar, its name and what the background
 * recorded of it, and <b>no image is opened</b>. A folder the background has not recorded yet is
 * described by its name, so nothing here waits for an index and nothing false is said: a folder
 * without a sidecar and without a record holding no file is a folder of folders, not an album.
 * </p>
 *
 * <p>
 * The descriptions are kept in memory and read again where the folder, its <code>index.json</code>
 * or its analysis record changed since, compared by their modification times; nothing is written,
 * and a query costs a few file times per folder of the space.
 * </p>
 *
 * <h2>Matching</h2>
 *
 * <p>
 * The text is cut into words at blanks, compared ignoring case and accents. Every word must match
 * the folder: its title, its subtitle, its folder name or &mdash; for a folder that happened on a
 * day &mdash; its date written <code>yyyy-MM-dd</code> (so <code>2024</code> and
 * <code>2024-05</code> find May 2024), or the names and titles of the folders above it (so
 * <code>2024 Rom</code> finds the album <code>Rom</code> in the folder <code>2024</code>).
 * </p>
 *
 * <h2>Order</h2>
 *
 * <p>
 * The folder's weakest word decides how well it matches: the start of a word of its own (or of its
 * date) before a piece inside one, and both before a word that only a folder above it carries. Equal
 * matches are ordered newest first, the undated behind them, then by path.
 * </p>
 */
public final class FolderFinder {

	/** How many matches are answered where the request does not say. */
	public static final int DEFAULT_LIMIT = 50;

	/** How many matches are answered at most, whatever the request says. */
	public static final int MAX_LIMIT = 200;

	/** A word starts a word of the folder's own, or its date. */
	static final int PREFIX = 3;

	/** A word is a piece of the folder's own title, name or date. */
	static final int INSIDE = 2;

	/** A word matches only a folder above. */
	static final int ABOVE = 1;

	/** No match. */
	static final int NONE = 0;

	private static final Pattern MARKS = Pattern.compile("\\p{M}+");

	private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

	private static final Pattern BLANKS = Pattern.compile("\\s+");

	private static final String[] NO_NAMES = new String[0];

	/** What is known of one folder, see the class comment. */
	private static final class Known {
		final long _folderStamp;

		final long _sidecarStamp;

		final long _recordStamp;

		/** The tile, without a picture. */
		final FolderInfo _tile;

		/** The subfolders that are part of the library, sorted. */
		final String[] _children;

		/** What the folder's own words are matched against, normalised. */
		final String _own;

		/** The words of {@link #_own}. */
		final String[] _words;

		/** The date, <code>yyyy-MM-dd</code>, empty for a folder that did not happen on a day. */
		final String _date;

		Known(long folderStamp, long sidecarStamp, long recordStamp, FolderInfo tile, String[] children) {
			_folderStamp = folderStamp;
			_sidecarStamp = sidecarStamp;
			_recordStamp = recordStamp;
			_tile = tile;
			_children = children;
			_own = normalize(tile.getTitle() + " " + tile.getSubTitle() + " " + tile.getName());
			_words = words(_own);
			_date = hasDate(tile) ? LocalDate.ofInstant(Instant.ofEpochMilli(tile.getEffectiveDate()),
				ZoneId.systemDefault()).toString() : "";
		}

		/** Whether anything below this folder is a folder of the library: not below a collection or a saved search. */
		boolean descends() {
			FolderKind kind = _tile.getKind();
			return kind != FolderKind.COLLECTION && kind != FolderKind.SEARCH;
		}
	}

	/** By the absolute path of the folder. */
	private final Map<String, Known> _known = new ConcurrentHashMap<>();

	/**
	 * The folders below the given folder that match the given text, the best first.
	 *
	 * @param scope
	 *        The folder to look below; itself is no match.
	 * @param text
	 *        What was typed; blank for nothing.
	 * @param limit
	 *        How many matches to answer, see {@link #MAX_LIMIT}.
	 * @param visible
	 *        Whether the caller may see the folder at the given path: a folder they may not see is
	 *        no match, and nothing of it or below it is answered.
	 */
	public FolderMatches find(PathInfo scope, String text, int limit, Predicate<PathInfo> visible) {
		FolderMatches result = FolderMatches.create();
		String[] needles = needles(text);
		if (needles.length == 0) {
			return result;
		}
		List<Found> found = new ArrayList<>();
		Known root = known(scope.toFile());
		if (root != null) {
			walk(scope, "", root, "", needles, visible, found);
		}
		found.sort(BEST_FIRST);
		int max = Math.max(1, Math.min(limit, MAX_LIMIT));
		for (int n = 0, size = Math.min(max, found.size()); n < size; n++) {
			Found match = found.get(n);
			result.addFolder(FolderMatch.create().setPath(match._path).setFolder(copy(match._known._tile)));
		}
		result.setMore(found.size() > max);
		return result;
	}

	private static final class Found {
		final String _path;

		final Known _known;

		final int _rank;

		Found(String path, Known known, int rank) {
			_path = path;
			_known = known;
			_rank = rank;
		}
	}

	/** See the class comment: the better match, then the newer, the undated last, then by path. */
	static final Comparator<Found> BEST_FIRST = (a, b) -> {
		if (a._rank != b._rank) {
			return Integer.compare(b._rank, a._rank);
		}
		long da = a._known._tile.getEffectiveDate();
		long db = b._known._tile.getEffectiveDate();
		if (da != db) {
			if (da == 0) {
				return 1;
			}
			if (db == 0) {
				return -1;
			}
			return Long.compare(db, da);
		}
		return String.CASE_INSENSITIVE_ORDER.compare(a._path, b._path);
	};

	private void walk(PathInfo folder, String prefix, Known known, String above, String[] needles,
			Predicate<PathInfo> visible, List<Found> found) {
		for (String name : known._children) {
			PathInfo child = folder.child(name);
			File dir = child.toFile();
			if (Inboxes.isInbox(dir)) {
				// The inbox is never a tile of a listing, and not a folder to find either: it is
				// reached through ?type=auth, see issue #226.
				continue;
			}
			Known description = known(dir);
			if (description == null) {
				continue;
			}
			if (!visible.test(child)) {
				// Nothing of a folder the caller may not see is answered: not the folder, and not
				// what lies below it, whose path would name it.
				continue;
			}
			String path = prefix + name;
			int rank = rank(description, above, needles);
			if (rank > NONE) {
				found.add(new Found(path, description, rank));
			}
			if (description.descends()) {
				walk(child, path + "/", description, above + " " + description._own, needles, visible, found);
			}
		}
	}

	/** How well the given folder matches, see the class comment; {@link #NONE} for no match. */
	static int rank(Known folder, String above, String[] needles) {
		int result = PREFIX;
		for (String needle : needles) {
			int rank = rankOf(folder, above, needle);
			if (rank == NONE) {
				return NONE;
			}
			result = Math.min(result, rank);
		}
		return result;
	}

	private static int rankOf(Known folder, String above, String needle) {
		if (folder._date.startsWith(needle)) {
			return PREFIX;
		}
		for (String word : folder._words) {
			if (word.startsWith(needle)) {
				return PREFIX;
			}
		}
		if (folder._own.contains(needle) || folder._date.contains(needle)) {
			return INSIDE;
		}
		if (above.contains(needle)) {
			return ABOVE;
		}
		return NONE;
	}

	/** The words of the given text, normalised, see {@link #normalize(String)}. */
	static String[] needles(String text) {
		if (text == null) {
			return NO_NAMES;
		}
		String normal = normalize(text).trim();
		return normal.isEmpty() ? NO_NAMES : BLANKS.split(normal);
	}

	/** The given text in lower case and without accents: <code>Zürich</code> is <code>zurich</code>. */
	static String normalize(String text) {
		return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
			.toLowerCase(Locale.ROOT);
	}

	private static String[] words(String text) {
		return Arrays.stream(NON_WORD.split(text)).filter(word -> !word.isEmpty()).toArray(String[]::new);
	}

	/** Whether the tile happened on a day, as the app decides it (<code>folderHasDate</code>). */
	private static boolean hasDate(FolderInfo tile) {
		FolderKind kind = tile.getKind();
		return (kind == FolderKind.ALBUM || kind == FolderKind.COLLECTION || kind == FolderKind.SEARCH)
			&& tile.getEffectiveDate() > 0;
	}

	/** The answered tile: the caller's own copy, never the one kept here. */
	private static FolderInfo copy(FolderInfo tile) {
		return FolderInfo.create().setName(tile.getName()).setTitle(tile.getTitle()).setSubTitle(tile.getSubTitle())
			.setEffectiveDate(tile.getEffectiveDate()).setKind(tile.getKind()).setStarred(tile.isStarred());
	}

	/**
	 * What is known of the given folder, read again where it changed, <code>null</code> where it is
	 * no folder (any more).
	 */
	private Known known(File dir) {
		String key = dir.getAbsolutePath();
		long folderStamp = dir.lastModified();
		if (folderStamp == 0L || !dir.isDirectory()) {
			forget(key);
			return null;
		}
		long sidecarStamp = new File(dir, "index.json").lastModified();
		long recordStamp = AnalysisRecord.fileOf(dir).lastModified();
		Known before = _known.get(key);
		if (before != null && before._folderStamp == folderStamp && before._sidecarStamp == sidecarStamp
			&& before._recordStamp == recordStamp) {
			return before;
		}
		Known now = read(dir, folderStamp, sidecarStamp, recordStamp);
		if (before != null) {
			// A subfolder that is gone is forgotten with everything below it.
			List<String> kept = Arrays.asList(now._children);
			for (String child : before._children) {
				if (!kept.contains(child)) {
					forget(new File(dir, child).getAbsolutePath());
				}
			}
		}
		_known.put(key, now);
		return now;
	}

	private void forget(String key) {
		if (_known.remove(key) == null) {
			return;
		}
		String below = key + File.separator;
		_known.keySet().removeIf(other -> other.startsWith(below));
	}

	private static Known read(File dir, long folderStamp, long sidecarStamp, long recordStamp) {
		File[] entries = dir.listFiles();
		List<String> children = new ArrayList<>();
		boolean holdsFiles = false;
		if (entries != null) {
			for (File entry : entries) {
				if (!PhotoSearch.isLibraryEntry(entry.getName())) {
					continue;
				}
				if (entry.isDirectory()) {
					children.add(entry.getName());
				} else {
					holdsFiles = true;
				}
			}
		}
		children.sort(null);
		FolderInfo tile = ResourceCache.folderInfo(dir);
		if (tile.getKind() == FolderKind.ALBUM && !holdsFiles && sidecarStamp == 0L
			&& AnalysisRecord.read(dir) == null) {
			// Not recorded yet and holding no file: a folder of folders, which the tile of a listing
			// only learns from the background's record, see ResourceCache#folderInfo(File).
			tile = FolderInfo.create().setName(tile.getName()).setTitle(tile.getTitle())
				.setSubTitle(tile.getSubTitle()).setEffectiveDate(tile.getEffectiveDate()).setKind(FolderKind.FOLDER);
		}
		return new Known(folderStamp, sidecarStamp, recordStamp, tile, children.toArray(NO_NAMES));
	}
}
