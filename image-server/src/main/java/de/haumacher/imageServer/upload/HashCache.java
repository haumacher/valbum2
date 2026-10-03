/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.upload;

import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The content hashes of the image and video files of a single album folder, and who contributed
 * them.
 *
 * <p>
 * An upload is idempotent: the server hashes what it received and stores it only if the target
 * folder does not hold those contents yet, see
 * {@link de.haumacher.imageServer.ImageServlet}. Hashing a whole folder for every upload would be
 * far too expensive, so the hashes are kept in a sidecar file {@value #FILE_NAME} beside the
 * images — the only file this server ever writes into an album folder besides
 * <code>index.json</code>. Originals are never touched.
 * </p>
 *
 * <p>
 * The sidecar is a cache, not a source of truth: an entry counts only while the file's size and
 * modification stamp still match what was recorded. A stale, missing or unreadable entry is
 * recomputed from the file itself, so a folder that is filled by other means (a file manager, a
 * sync tool) is picked up automatically.
 * </p>
 *
 * <p>
 * The file format is persisted data and therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"files":{"IMG_1.jpg":{"size":1234,"modified":1757000000000,"sha256":"&lt;64 hex chars&gt;",
 *   "contributor":"user:bob","contributorLabel":"bob"}}}
 * </pre>
 *
 * <p>
 * The two attribution fields of issue #53 were added without a version bump, because they are
 * additive in both directions: a file written before them reads with empty attribution, and a
 * build without them skips what it does not know (see {@link #readEntry(JsonReader)}). Attribution
 * is the one thing in this file that is <em>not</em> a cache — it is recorded once, when the
 * upload stores the file, and cannot be recomputed from the folder — so a {@link #refresh()} that
 * re-hashes a changed file keeps it: who put the photo here is not a question the contents answer.
 * </p>
 *
 * <p>
 * An upload through a share link records, beside who sent it, the limits of that link
 * (<code>"linkMinRating"</code>, <code>"linkMaxPrivacy"</code>, issue #214, additive in the same
 * way and written only for such an upload): the loader describes a photograph the album's
 * <code>index.json</code> does not list yet with its rating raised to that floor and its privacy
 * level capped at that level, so that the link it came through shows it, see
 * {@link de.haumacher.imageServer.Contributors#applyLinkLimits(java.util.List, File)}.
 * </p>
 *
 * <p>
 * An upload by a contact through a personal link also records the id of that link
 * (<code>"contributorLink"</code>, issue #203, additive in the same way), so that the owner of the
 * link is told how many photographs each person added through it, see
 * {@link de.haumacher.imageServer.Contributions}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class HashCache {

	private static final Logger LOG = Logger.getLogger(HashCache.class.getName());

	/** The name of the sidecar in the album folder it describes. */
	public static final String FILE_NAME = ".hashes.json";

	/** The version this build writes, see {@link HashCache}. */
	public static final int VERSION = 1;

	private static final String VERSION__PROP = "version";

	private static final String FILES__PROP = "files";

	private static final String SIZE__PROP = "size";

	private static final String MODIFIED__PROP = "modified";

	private static final String SHA256__PROP = "sha256";

	private static final String CONTRIBUTOR__PROP = "contributor";

	private static final String CONTRIBUTOR_LABEL__PROP = "contributorLabel";

	private static final String LINK_MIN_RATING__PROP = "linkMinRating";

	private static final String LINK_MAX_PRIVACY__PROP = "linkMaxPrivacy";

	private static final String LINK_LABEL__PROP = "linkLabel";

	private static final String CONTRIBUTOR_LINK__PROP = "contributorLink";

	private static final int BUFFER_SIZE = 64 * 1024;

	/** What was recorded for one file of the folder. */
	private static final class Entry {

		final long _size;

		final long _modified;

		final String _sha256;

		final Attribution _attribution;

		Entry(long size, long modified, String sha256, Attribution attribution) {
			_size = size;
			_modified = modified;
			_sha256 = sha256;
			_attribution = attribution;
		}

		/** Whether this entry still describes the given file. */
		boolean matches(File file) {
			return file.length() == _size && file.lastModified() == _modified;
		}

		/** The same entry for freshly measured contents, keeping who contributed the file. */
		Entry rehashed(long size, long modified, String sha256) {
			return new Entry(size, modified, sha256, _attribution);
		}
	}

	/**
	 * Who contributed one file, and how to show them, see issue #53.
	 *
	 * <p>
	 * The {@link de.haumacher.imageServer.auth.AuthService.Caller#subject() subject} of the caller
	 * that uploaded the file, and the label to show for it — the user's name, or the label the
	 * share link carried at that moment. The label is copied rather than looked up, so a link that
	 * was renamed or withdrawn still says who contributed.
	 * </p>
	 */
	public static final class Attribution {

		/** What is recorded for a file nobody uploaded through this server. */
		public static final Attribution NONE = new Attribution("", "");

		/** {@link #getMinRating()} of an upload that raises no rating: the lowest there is. */
		public static final int NO_RATING_FLOOR = Ratings.MIN;

		/** {@link #getMaxPrivacy()} of an upload that caps no privacy level: the highest there is. */
		public static final int NO_PRIVACY_CAP = Privacy.PRIVATE;

		private final String _contributor;

		private final String _label;

		private final int _minRating;

		private final int _maxPrivacy;

		private final String _linkLabel;

		private final String _link;

		/** Creates an {@link Attribution}; <code>null</code> is the empty string. */
		public Attribution(String contributor, String label) {
			this(contributor, label, NO_RATING_FLOOR, NO_PRIVACY_CAP);
		}

		/**
		 * Creates an {@link Attribution} of an upload through a share link, see issue #214.
		 *
		 * @param minRating
		 *        The lowest rating the link shows, {@link #NO_RATING_FLOOR} for none.
		 * @param maxPrivacy
		 *        The highest privacy level the link shows, {@link #NO_PRIVACY_CAP} for none.
		 */
		public Attribution(String contributor, String label, int minRating, int maxPrivacy) {
			this(contributor, label, minRating, maxPrivacy, "");
		}

		/**
		 * Creates an {@link Attribution} of an upload through a share link showing one label, see
		 * issue #213.
		 *
		 * @param linkLabel
		 *        The label whose photographs the link shows, the empty string for none.
		 */
		public Attribution(String contributor, String label, int minRating, int maxPrivacy, String linkLabel) {
			this(contributor, label, minRating, maxPrivacy, linkLabel, "");
		}

		/**
		 * Creates an {@link Attribution} of an upload by a contact through a personal share link,
		 * see issue #203.
		 *
		 * @param link
		 *        The id of the share link the contact came through, the empty string for none.
		 */
		public Attribution(String contributor, String label, int minRating, int maxPrivacy, String linkLabel,
				String link) {
			_link = link == null ? "" : link;
			_contributor = contributor == null ? "" : contributor;
			_label = label == null ? "" : label;
			_minRating = minRating;
			_maxPrivacy = maxPrivacy;
			_linkLabel = linkLabel == null ? "" : linkLabel;
		}

		/**
		 * The label whose photographs the share link the file came through shows, see issue #213.
		 *
		 * <p>
		 * A photograph first described from this file carries it, so that the link it came through
		 * shows it. The empty string for everything that did not come through such a link.
		 * </p>
		 */
		public String getLinkLabel() {
			return _linkLabel;
		}

		/**
		 * The id of the personal share link a contact uploaded the file through, see issue #203.
		 *
		 * <p>
		 * Recorded beside <code>contact:&lt;id&gt;</code>, so that the owner of a link is told how
		 * many photographs each of its recipients and visitors added through it. The empty string for
		 * everything else: an anonymous link's subject <code>token:&lt;id&gt;</code> names its link
		 * already, and a member uses none.
		 * </p>
		 */
		public String getLink() {
			return _link;
		}

		/** The subject of the uploader, the empty string if none was recorded. */
		public String getContributor() {
			return _contributor;
		}

		/** What to show as the contributor, the empty string if nothing was recorded. */
		public String getLabel() {
			return _label;
		}

		/**
		 * The lowest rating the share link the file came through shows, see issue #214.
		 *
		 * <p>
		 * A photograph first described from this file is rated at least this, so that the link it
		 * came through shows it. {@link #NO_RATING_FLOOR} for everything that did not come through
		 * a link.
		 * </p>
		 */
		public int getMinRating() {
			return _minRating;
		}

		/**
		 * The highest privacy level the share link the file came through shows, see issue #214;
		 * {@link #NO_PRIVACY_CAP} for everything that did not come through a link.
		 */
		public int getMaxPrivacy() {
			return _maxPrivacy;
		}

		/** Whether the upload was recorded with a limit of the link it came through. */
		public boolean hasLinkLimits() {
			return _minRating != NO_RATING_FLOOR || _maxPrivacy != NO_PRIVACY_CAP || !_linkLabel.isEmpty();
		}

		/** Whether anything at all was recorded. */
		public boolean isSet() {
			return !_contributor.isEmpty();
		}

		@Override
		public String toString() {
			return isSet() ? _contributor + " (" + _label + ")" : "<nobody>";
		}
	}

	private final File _folder;

	private final File _file;

	/** The recorded entries by file name, in the order they were read or added. */
	private Map<String, Entry> _entries = new LinkedHashMap<>();

	private boolean _dirty;

	/**
	 * Loads the {@link HashCache} of the given album folder.
	 *
	 * <p>
	 * Nothing is written and nothing is hashed until {@link #refresh()} (implied by every lookup)
	 * and {@link #flush()} are called.
	 * </p>
	 */
	public HashCache(File folder) {
		_folder = folder;
		_file = new File(folder, FILE_NAME);
		load();
	}

	/** The sidecar file this cache is persisted in. */
	public File getFile() {
		return _file;
	}

	/**
	 * The name of the file in this folder holding the contents with the given hash.
	 *
	 * @return <code>null</code> if this folder holds no such contents.
	 */
	public String nameOf(String sha256) throws IOException {
		return nameByHash().get(sha256);
	}

	/** The hashes of this folder's files by file name, up to date. */
	public Map<String, String> hashByName() throws IOException {
		refresh();
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, Entry> entry : _entries.entrySet()) {
			result.put(entry.getKey(), entry.getValue()._sha256);
		}
		return Collections.unmodifiableMap(result);
	}

	/**
	 * Records the hash of a file that was just stored in this folder, without an uploader.
	 *
	 * <p>
	 * Saves the freshly stored file from being hashed again by the next {@link #refresh()}.
	 * </p>
	 */
	public void put(File file, String sha256) {
		put(file, sha256, Attribution.NONE);
	}

	/**
	 * Records the hash of a file that was just stored in this folder and who put it there, see
	 * issue #53.
	 *
	 * <p>
	 * This is where attribution enters the server, and the only place: an upload stores the file
	 * and says who sent it, a move carries the record of the source folder into the target's.
	 * Nothing else ever writes it, and an upload of contents the folder already holds writes
	 * nothing at all, so the first contributor is the one that stays.
	 * </p>
	 */
	public void put(File file, String sha256, Attribution attribution) {
		_entries.put(file.getName(),
			new Entry(file.length(), file.lastModified(), sha256, attribution == null ? Attribution.NONE
				: attribution));
		_dirty = true;
	}

	/**
	 * Who contributed the file of the given name, {@link Attribution#NONE} if nothing is recorded.
	 *
	 * <p>
	 * Answered from the sidecar as it stands: nothing is hashed, nothing is written. A file the
	 * sidecar does not mention has no attribution — it was never uploaded through this server.
	 * </p>
	 */
	public Attribution attributionOf(String name) {
		Entry entry = _entries.get(name);
		return entry == null ? Attribution.NONE : entry._attribution;
	}

	/**
	 * Who contributed each file of the given folder, see {@link #attributionOf(String)}.
	 *
	 * <p>
	 * The read path of issue #53, and deliberately the cheap one: the sidecar is read and nothing
	 * else happens — no file is opened, no hash is computed and nothing is written back, so
	 * answering a listing or an album costs one small JSON file per folder. Only files with an
	 * attribution are in the result.
	 * </p>
	 */
	public static Map<String, Attribution> recorded(File folder) {
		Map<String, Attribution> result = new LinkedHashMap<>();
		File file = new File(folder, FILE_NAME);
		if (!file.exists()) {
			return result;
		}
		HashCache cache = new HashCache(folder);
		for (Map.Entry<String, Entry> entry : cache._entries.entrySet()) {
			Attribution attribution = entry.getValue()._attribution;
			if (attribution.isSet()) {
				result.put(entry.getKey(), attribution);
			}
		}
		return result;
	}

	/**
	 * Brings the cache in line with the folder: hashes new and changed files, forgets vanished
	 * ones.
	 */
	public void refresh() throws IOException {
		File[] files = _folder.listFiles(f -> f.isFile() && ResourceCache.isImage(f));
		if (files == null) {
			throw new IOException("Cannot list folder: " + _folder.getAbsolutePath());
		}

		Map<String, Entry> update = new LinkedHashMap<>();
		for (File file : files) {
			String name = file.getName();
			Entry entry = _entries.get(name);
			if (entry == null) {
				entry = new Entry(file.length(), file.lastModified(), sha256(file), Attribution.NONE);
				_dirty = true;
			} else if (!entry.matches(file)) {
				// The contents changed behind the server's back; who put the file here did not.
				entry = entry.rehashed(file.length(), file.lastModified(), sha256(file));
				_dirty = true;
			}
			update.put(name, entry);
		}
		if (update.size() != _entries.size()) {
			// Files were removed from the folder behind the server's back.
			_dirty = true;
		}
		_entries = update;
	}

	/** Writes the cache back to its sidecar file, if anything changed. */
	public void flush() throws IOException {
		if (!_dirty) {
			return;
		}
		store();
		_dirty = false;

		// This is the one place a hash sidecar is ever written — the upload, the move and the
		// lazy fill of a check all end here — so it is the one place the space-wide index of
		// issue #118 has to hear about, and no writer has to know that the index exists.
		HashIndex.sidecarWritten(_folder);
	}

	/**
	 * The hashes this sidecar records, by file name, exactly as they stand.
	 *
	 * <p>
	 * The cheap read of {@link #hashByName()}: nothing is opened, nothing is hashed and nothing is
	 * written, so a folder can be taken into the index of issue #118 for the price of one small
	 * JSON file. A file the sidecar does not mention is simply not in the result.
	 * </p>
	 */
	public Map<String, String> storedHashByName() {
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, Entry> entry : _entries.entrySet()) {
			result.put(entry.getKey(), entry.getValue()._sha256);
		}
		return Collections.unmodifiableMap(result);
	}

	/** The SHA-256 hash of the given file's contents, in lower-case hex. */
	public static String sha256(File file) throws IOException {
		MessageDigest digest = newDigest();
		byte[] buffer = new byte[BUFFER_SIZE];
		try (InputStream in = Files.newInputStream(file.toPath())) {
			int direct;
			while ((direct = in.read(buffer)) > 0) {
				digest.update(buffer, 0, direct);
			}
		}
		return hex(digest.digest());
	}

	/** The SHA-256 hash of the given bytes, in lower-case hex. */
	public static String sha256(byte[] contents) {
		return hex(newDigest().digest(contents));
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the platform.", ex);
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			result.append(Character.forDigit((b >> 4) & 0xF, 16));
			result.append(Character.forDigit(b & 0xF, 16));
		}
		return result.toString();
	}

	private void load() {
		if (!_file.exists()) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file.toPath()), StandardCharsets.UTF_8)) {
			_entries = readEntries(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A cache can always be rebuilt; a broken one must never stop an upload.
			LOG.log(Level.WARNING,
				"Rebuilding the unreadable hash cache '" + _file.getAbsolutePath() + "': " + ex.getMessage());
			_entries = new LinkedHashMap<>();
			_dirty = true;
		}
	}

	private static Map<String, Entry> readEntries(JsonReader in) throws IOException {
		Map<String, Entry> result = new LinkedHashMap<>();
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case FILES__PROP:
					in.beginObject();
					while (in.hasNext()) {
						String name = in.nextName();
						result.put(name, readEntry(in));
					}
					in.endObject();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();

		if (version > VERSION) {
			LOG.warning("The hash cache was written by a newer version (" + version + " > " + VERSION
				+ "); entries whose size and modification stamp still match are trusted.");
		}
		return result;
	}

	private static Entry readEntry(JsonReader in) throws IOException {
		long size = -1;
		long modified = -1;
		String sha256 = "";
		String contributor = "";
		String contributorLabel = "";
		int minRating = Attribution.NO_RATING_FLOOR;
		int maxPrivacy = Attribution.NO_PRIVACY_CAP;
		String linkLabel = "";
		String link = "";
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case SIZE__PROP:
					size = in.nextLong();
					break;
				case MODIFIED__PROP:
					modified = in.nextLong();
					break;
				case SHA256__PROP:
					sha256 = in.nextString();
					break;
				case CONTRIBUTOR__PROP:
					contributor = in.nextString();
					break;
				case CONTRIBUTOR_LABEL__PROP:
					contributorLabel = in.nextString();
					break;
				case LINK_MIN_RATING__PROP:
					minRating = in.nextInt();
					break;
				case LINK_MAX_PRIVACY__PROP:
					maxPrivacy = in.nextInt();
					break;
				case LINK_LABEL__PROP:
					linkLabel = in.nextString();
					break;
				case CONTRIBUTOR_LINK__PROP:
					link = in.nextString();
					break;
				default:
					// An entry written by a future version may carry more; it stays readable.
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return new Entry(size, modified, sha256, new Attribution(contributor, contributorLabel, minRating, maxPrivacy,
			linkLabel, link));
	}

	private void store() throws IOException {
		Path folder = _folder.toPath();
		Path tmpFile = Files.createTempFile(folder, "hashes", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(FILES__PROP);
				out.beginObject();
				for (Map.Entry<String, Entry> entry : _entries.entrySet()) {
					out.name(entry.getKey());
					out.beginObject();
					out.name(SIZE__PROP);
					out.value(entry.getValue()._size);
					out.name(MODIFIED__PROP);
					out.value(entry.getValue()._modified);
					out.name(SHA256__PROP);
					out.value(entry.getValue()._sha256);
					Attribution attribution = entry.getValue()._attribution;
					if (attribution.isSet()) {
						// Only what somebody actually contributed: a library that was never
						// uploaded to keeps the file it had before issue #53.
						out.name(CONTRIBUTOR__PROP);
						out.value(attribution.getContributor());
						out.name(CONTRIBUTOR_LABEL__PROP);
						out.value(attribution.getLabel());
					}
					if (attribution.getMinRating() != Attribution.NO_RATING_FLOOR) {
						// The floor of the link the file came through, see issue #214.
						out.name(LINK_MIN_RATING__PROP);
						out.value(attribution.getMinRating());
					}
					if (attribution.getMaxPrivacy() != Attribution.NO_PRIVACY_CAP) {
						out.name(LINK_MAX_PRIVACY__PROP);
						out.value(attribution.getMaxPrivacy());
					}
					if (!attribution.getLinkLabel().isEmpty()) {
						// The label of the link the file came through, see issue #213.
						out.name(LINK_LABEL__PROP);
						out.value(attribution.getLinkLabel());
					}
					if (!attribution.getLink().isEmpty()) {
						// The personal link a contact uploaded the file through, see issue #203.
						out.name(CONTRIBUTOR_LINK__PROP);
						out.value(attribution.getLink());
					}
					out.endObject();
				}
				out.endObject();
				out.endObject();
			}
		}
		Files.move(tmpFile, _file.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	/** A map of every hash in this folder to the name of the file holding it. */
	public Map<String, String> nameByHash() throws IOException {
		refresh();
		Map<String, String> result = new HashMap<>();
		for (Map.Entry<String, Entry> entry : _entries.entrySet()) {
			result.putIfAbsent(entry.getValue()._sha256, entry.getKey());
		}
		return result;
	}
}
