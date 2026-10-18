/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.RawPairs;
import de.haumacher.imageServer.pipeline.ReadOnlyFolders;
import de.haumacher.imageServer.raw.RawFile;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the background work found in the files of one folder, see issue #253.
 *
 * <p>
 * A request never lists a folder and never opens a photograph: an album is answered from its
 * <code>index.json</code> (the author's edits, and the parts an earlier build or an edit stored) and
 * from this record, which the background keeps beside the previews, in
 * <code>&lt;folder&gt;/{@value PreviewCache#CACHE_DIRECTORY_NAME}/{@value #FILE_NAME}</code>. It names
 * every media file of the folder with its size and modification time, and for each either
 * </p>
 * <ul>
 * <li>the {@link ImagePart} the analysis made of it (a file the <code>index.json</code> does not
 * list yet),</li>
 * <li>why it cannot be read &mdash; a <em>broken</em> file, see {@link ImagePart#getBroken()} &mdash;
 * with the version of the analysis that said so, or</li>
 * <li>nothing: a file the <code>index.json</code> lists (its stored part is the truth and is never
 * analysed again, which is what makes the face import of issue #129 run once per file), the raw
 * companion of a JPEG (issue #191), or a file only seen so far, not analysed yet.</li>
 * </ul>
 *
 * <pre>
 * {"version":1,"files":{
 *   "a.jpg":{"size":2048,"modified":1757000000000,"part":["ImagePart",{...}]},
 *   "x.mp4":{"size":4096,"modified":1757000001000,"failure":"moov atom not found","analysis":1},
 *   "b.jpg":{"size":1024,"modified":1757000002000}}}
 * </pre>
 *
 * <h2>Why a record of its own, and not the <code>index.json</code></h2>
 *
 * <p>
 * The <code>index.json</code> is the author's: a request writes it, and so does a move or an upload's
 * album edit, all under the servlet's own rule (see <code>ImageServlet#storeSidecar</code>). If the
 * background wrote it as well, an edit that read the album before the background wrote and stored it
 * afterwards would lose what the background found, for good: the background would hold the file as
 * done. This record has one writer per folder (under {@link #lock(File)}), is a cache in the strict
 * sense &mdash; deleting it costs an analysis, never a statement &mdash; and keeps what an
 * <code>index.json</code> must never hold: a failure. A failure is keyed by the file's size and
 * modification time, so a repaired or replaced file is analysed again; and it carries the version of
 * the analysis ({@link #ANALYSIS}), so that a later build that reads more formats tries every failure
 * of an earlier one again, also on unchanged files.
 * </p>
 *
 * <p>
 * The format is read leniently: an unknown field is skipped, a record that cannot be read at all is
 * treated as none (the folder is recorded afresh). An <code>index.json</code> written by an older
 * build keeps answering its parts before this record exists, see
 * {@link ResourceCache}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class AnalysisRecord {

	private static final Logger LOG = Logger.getLogger(AnalysisRecord.class.getName());

	/** The name of the record in the folder's cache directory. */
	public static final String FILE_NAME = "analysis.json";

	/** The version of the file format this build writes. */
	public static final int VERSION = 1;

	/**
	 * The version of the analysis itself: a failure recorded by a lower one is tried again on the
	 * unchanged file. Raise it when a build learns to read a file an earlier one could not.
	 */
	public static final int ANALYSIS = 1;

	private static final String VERSION__PROP = "version";

	private static final String FILES__PROP = "files";

	private static final String SIZE__PROP = "size";

	private static final String MODIFIED__PROP = "modified";

	private static final String PART__PROP = "part";

	private static final String FAILURE__PROP = "failure";

	private static final String ANALYSIS__PROP = "analysis";

	/**
	 * The records of folders the server cannot write into, kept for the life of the process, see
	 * {@link ReadOnlyFolders}; by absolute path, as the bytes the file would hold, so that every
	 * reader gets parts of its own.
	 */
	private static final Map<String, byte[]> IN_MEMORY = new ConcurrentHashMap<>();

	/** The locks a folder's record is updated under, by a hash of its path. */
	private static final Object[] LOCKS = new Object[64];

	static {
		for (int n = 0; n < LOCKS.length; n++) {
			LOCKS[n] = new Object();
		}
	}

	/** Makes a part out of a file, see {@link #update(File, Analyst, boolean)}. */
	public interface Analyst {

		/** The part describing the given file; throws where it cannot be read. */
		ImagePart analyze(File file) throws Exception;
	}

	/** What one file is recorded as. */
	public static final class Entry {

		final String _name;

		final long _size;

		final long _modified;

		final ImagePart _part;

		final String _failure;

		final int _analysis;

		Entry(String name, long size, long modified, ImagePart part, String failure, int analysis) {
			_name = name;
			_size = size;
			_modified = modified;
			_part = part;
			_failure = failure;
			_analysis = analysis;
		}

		/** The file's name. */
		public String getName() {
			return _name;
		}

		/** The file's size when it was recorded. */
		public long getSize() {
			return _size;
		}

		/** The file's modification time when it was recorded. */
		public long getModified() {
			return _modified;
		}

		/** The analysed part, owned by this record; <code>null</code> where there is none. */
		public ImagePart getPart() {
			return _part;
		}

		/** Why the file cannot be read, <code>null</code> where it can or was not tried. */
		public String getFailure() {
			return _failure;
		}

		/** Whether the file was analysed into a part. */
		public boolean isAnalysed() {
			return _part != null;
		}

		/** Whether the file could not be read. */
		public boolean isBroken() {
			return _failure != null;
		}

		boolean matches(long size, long modified) {
			return _size == size && _modified == modified;
		}

		/**
		 * The part an album answers for this file where it cannot be read: its name, its kind as its
		 * extension says, its size, its modification time as its date, and the reason.
		 */
		public ImagePart brokenPart() {
			ImagePart result = ImagePart.create();
			result.setName(_name);
			result.setKind(PreviewCache.isVideoName(_name)
				? ("mov".equals(de.haumacher.util.servlet.Util.suffix(_name)) ? ImageKind.QUICKTIME : ImageKind.VIDEO)
				: ImageKind.IMAGE);
			result.setDate(_modified);
			result.setFileSize(_size);
			result.setBroken(_failure);
			return result;
		}
	}

	/**
	 * A file of a folder that cannot be read, and why, see {@link Result#getBroken()}.
	 *
	 * @param name
	 *        The file's name.
	 * @param reason
	 *        Why it cannot be read.
	 */
	public record Broken(String name, String reason) {
		/** As the status of the background says it: <code>"name: reason"</code>. */
		@Override
		public String toString() {
			return name + ": " + reason;
		}
	}

	/** What {@link AnalysisRecord#update(File, Analyst, boolean)} did. */
	public static final class Result {

		/** Nothing recorded, and nothing to say. */
		static final Result NONE = new Result(false, false, Collections.emptyList(), Collections.emptyList(), 0);

		private final boolean _written;

		private final boolean _unwritable;

		private final List<Broken> _failures;

		private final List<Broken> _fresh;

		private final int _analysed;

		Result(boolean written, boolean unwritable, List<Broken> failures, List<Broken> fresh, int analysed) {
			_written = written;
			_unwritable = unwritable;
			_failures = failures;
			_fresh = fresh;
			_analysed = analysed;
		}

		/** Whether the record changed. */
		public boolean isWritten() {
			return _written;
		}

		/** Whether the record could not be stored and is held in memory, see {@link ReadOnlyFolders}. */
		public boolean isUnwritable() {
			return _unwritable;
		}

		/** Every file of the folder that cannot be read, <code>"name: reason"</code>. */
		public List<String> getFailures() {
			return names(_failures);
		}

		/** The ones of {@link #getFailures()} found in this update. */
		public List<String> getFresh() {
			return names(_fresh);
		}

		/** Every file of the folder that cannot be read, by its parts. */
		public List<Broken> getBroken() {
			return _failures;
		}

		/** The ones of {@link #getBroken()} found in this update. */
		public List<Broken> getFreshBroken() {
			return _fresh;
		}

		private static List<String> names(List<Broken> broken) {
			List<String> result = new ArrayList<>(broken.size());
			for (Broken each : broken) {
				result.add(each.toString());
			}
			return result;
		}

		/** How many files were analysed in this update. */
		public int getAnalysed() {
			return _analysed;
		}
	}

	private final Map<String, Entry> _entries;

	private AnalysisRecord(Map<String, Entry> entries) {
		_entries = entries;
	}

	/** The entries, by name. */
	public Collection<Entry> entries() {
		return Collections.unmodifiableCollection(_entries.values());
	}

	/** The entry of the given name, <code>null</code> for none. */
	public Entry get(String name) {
		return _entries.get(name);
	}

	/** Whether the folder holds no media file. */
	public boolean isEmpty() {
		return _entries.isEmpty();
	}

	/** The names of the files that cannot be read. */
	public Set<String> brokenNames() {
		Set<String> result = new HashSet<>();
		for (Entry entry : _entries.values()) {
			if (entry.isBroken()) {
				result.add(entry.getName());
			}
		}
		return result;
	}

	// --- Reading. ---

	/** Whether the given name is that of a record being written, a leftover of a crash. */
	public static boolean isTemporary(String name) {
		return name.startsWith(FILE_NAME + ".") && name.endsWith(PreviewCache.TMP_SUFFIX);
	}

	/** Where the record of the given folder is stored. */
	public static File fileOf(File folder) {
		return new File(new File(folder, PreviewCache.CACHE_DIRECTORY_NAME), FILE_NAME);
	}

	/**
	 * The record of the given folder, <code>null</code> where the background has not recorded it yet
	 * or the record cannot be read. A pure read: nothing is listed, opened or written.
	 */
	public static AnalysisRecord read(File folder) {
		byte[] memory = IN_MEMORY.get(key(folder));
		if (memory != null) {
			try {
				return parse(new ByteArrayInputStream(memory));
			} catch (IOException | RuntimeException ex) {
				IN_MEMORY.remove(key(folder));
			}
		}
		File file = fileOf(folder);
		if (!file.isFile()) {
			return null;
		}
		try (InputStream in = Files.newInputStream(file.toPath())) {
			return parse(in);
		} catch (IOException | RuntimeException ex) {
			// A cache can always be made again; a broken one is recorded afresh.
			LOG.log(Level.WARNING, "Recording afresh the unreadable '" + file + "': " + ex.getMessage());
			return null;
		}
	}

	/**
	 * A stamp that changes whenever the record of the given folder changes, for the cache's check
	 * of what it holds, see {@link ResourceCache}.
	 */
	static String stamp(File folder) {
		byte[] memory = IN_MEMORY.get(key(folder));
		if (memory != null) {
			return "m" + Arrays.hashCode(memory) + ":" + memory.length;
		}
		return Stamps.of(fileOf(folder));
	}

	/** The names of the files of the given folder recorded as unreadable; empty where none is. */
	public static Set<String> broken(File folder) {
		AnalysisRecord record = read(folder);
		return record == null ? Collections.emptySet() : record.brokenNames();
	}

	private static AnalysisRecord parse(InputStream in) throws IOException {
		JsonReader json = new JsonReader(new ReaderAdapter(new InputStreamReader(in, StandardCharsets.UTF_8)));
		Map<String, Entry> entries = new TreeMap<>();
		json.beginObject();
		while (json.hasNext()) {
			switch (json.nextName()) {
				case FILES__PROP:
					json.beginObject();
					while (json.hasNext()) {
						String name = json.nextName();
						Entry entry = readEntry(json, name);
						if (entry != null) {
							entries.put(name, entry);
						}
					}
					json.endObject();
					break;
				case VERSION__PROP:
				default:
					json.skipValue();
					break;
			}
		}
		json.endObject();
		return new AnalysisRecord(entries);
	}

	private static Entry readEntry(JsonReader json, String name) throws IOException {
		long size = -1;
		long modified = -1;
		ImagePart part = null;
		String failure = null;
		int analysis = 0;
		json.beginObject();
		while (json.hasNext()) {
			switch (json.nextName()) {
				case SIZE__PROP:
					size = json.nextLong();
					break;
				case MODIFIED__PROP:
					modified = json.nextLong();
					break;
				case PART__PROP:
					Resource resource = Resource.readResource(json);
					part = resource instanceof ImagePart ? (ImagePart) resource : null;
					break;
				case FAILURE__PROP:
					failure = json.nextString();
					break;
				case ANALYSIS__PROP:
					analysis = json.nextInt();
					break;
				default:
					json.skipValue();
					break;
			}
		}
		json.endObject();
		if (size < 0 || modified < 0) {
			return null;
		}
		if (part != null) {
			// What a record says of a file is its own name, whatever the part read says.
			part.setName(name);
		}
		return new Entry(name, size, modified, part, part == null ? failure : null, analysis);
	}

	// --- Writing. ---

	/**
	 * The lock the given folder's record is updated under: one writer at a time, see
	 * {@link AnalysisRecord}.
	 */
	static Object lock(File folder) {
		return LOCKS[Math.floorMod(key(folder).hashCode(), LOCKS.length)];
	}

	/**
	 * Brings the record of the given folder up to date with its files.
	 *
	 * <p>
	 * Lists the folder and looks at the size and modification time of every media file; a file
	 * whose size and time are recorded is not opened again (a failure only where a later
	 * {@link #ANALYSIS} came since). A file the <code>index.json</code> lists, and the raw companion
	 * of a JPEG, is recorded without being analysed. A file that is gone is forgotten.
	 * </p>
	 *
	 * @param analyst
	 *        Makes the part of a file.
	 * @param analyse
	 *        Whether new and changed files are analysed; <code>false</code> for the cheap look that
	 *        follows a sidecar write and for the discovery at start-up: what is new is recorded as
	 *        seen, and what changed keeps what was recorded until the background analyses it.
	 * @return What was done; {@link Result#NONE} for a collection, a saved search and a folder of
	 *         folders by its sidecar, which record nothing.
	 */
	public static Result update(File folder, Analyst analyst, boolean analyse) throws IOException {
		synchronized (lock(folder)) {
			return updateLocked(folder, analyst, analyse);
		}
	}

	private static Result updateLocked(File folder, Analyst analyst, boolean analyse) throws IOException {
		FolderResource sidecar = sidecarOf(folder);
		if (sidecar instanceof ListingInfo) {
			// A folder of folders by its owner's word: its files are never shown.
			return Result.NONE;
		}
		if (sidecar instanceof AlbumInfo && ((AlbumInfo) sidecar).getKind() == AlbumKind.SEARCH) {
			// Its parts are found, see issue #227. A collection is an album since issue #307: a
			// file in its folder is a photograph of it.
			return Result.NONE;
		}
		File[] files = folder.listFiles(ResourceCache.IMAGES);
		if (files == null) {
			throw new IOException("Cannot list the folder '" + folder + "'.");
		}
		Arrays.sort(files, Comparator.comparing(File::getName));
		Set<String> listed = listed(sidecar);
		// A part an album stores without a picture is one whose file was broken when it was
		// written: its file is looked at like a new one, see BrokenParts#hasNoPicture(ImagePart).
		Set<String> unpictured = unpictured(sidecar);
		Set<String> companionBases = new HashSet<>();
		for (File file : files) {
			if (RawPairs.isCompanionType(file.getName())) {
				companionBases.add(RawPairs.base(file.getName()));
			}
		}

		AnalysisRecord before = read(folder);
		Map<String, Entry> entries = new TreeMap<>();
		boolean changed = before == null;
		List<Broken> fresh = new ArrayList<>();
		int analysed = 0;
		for (File file : files) {
			String name = file.getName();
			long size = file.length();
			long modified = file.lastModified();
			Entry old = before == null ? null : before.get(name);
			boolean needed = (!listed.contains(name) || unpictured.contains(name))
				&& !(RawFile.isRawName(name) && companionBases.contains(RawPairs.base(name)));
			if (old != null && old.matches(size, modified) && (needed
				? old.isAnalysed() || (old.isBroken() && old._analysis >= ANALYSIS)
				// The sidecar's part is the truth for a file it lists: a failure is no more.
				: !old.isBroken())) {
				entries.put(name, old);
				continue;
			}
			if (!needed) {
				entries.put(name, new Entry(name, size, modified, null, null, 0));
				changed = true;
				continue;
			}
			if (!analyse) {
				if (old != null) {
					// Changed: what was recorded stands until the background analyses it again.
					entries.put(name, old);
				} else {
					entries.put(name, new Entry(name, size, modified, null, null, 0));
					changed = true;
				}
				continue;
			}
			changed = true;
			analysed++;
			try {
				ImagePart part = analyst.analyze(file);
				entries.put(name, new Entry(name, size, modified, plain(part), null, ANALYSIS));
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				if (old != null) {
					entries.put(name, old);
				}
				break;
			} catch (Exception ex) {
				String reason = reason(ex);
				entries.put(name, new Entry(name, size, modified, null, reason, ANALYSIS));
				fresh.add(new Broken(name, reason));
				LOG.log(Level.WARNING, "Cannot read '" + file + "', shown as a broken file: " + reason, ex);
			}
		}
		if (before != null) {
			for (String name : before._entries.keySet()) {
				if (!entries.containsKey(name)) {
					changed = true;
					if (!Thread.currentThread().isInterrupted()) {
						// Gone: what was made of it would otherwise be served for a new file of its name.
						PreviewCache.drop(new File(folder, name));
					}
				}
			}
			for (Entry entry : entries.values()) {
				Entry old = before.get(entry.getName());
				if (old != null && !old.matches(entry._size, entry._modified)) {
					// Changed: what was made of the file before describes another one, see issue #256.
					PreviewCache.drop(new File(folder, entry.getName()));
				}
			}
		}
		List<Broken> failures = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.isBroken()) {
				failures.add(new Broken(entry.getName(), entry.getFailure()));
			}
		}
		if (!changed) {
			return new Result(false, false, failures, fresh, analysed);
		}
		boolean unwritable = !store(folder, new AnalysisRecord(entries));
		announce(folder);
		return new Result(true, unwritable, failures, fresh, analysed);
	}

	/**
	 * Records the given files of the given folder, which were just stored there: an upload analyses
	 * what it brought at once, so that the album shows it with the next read, see issue #253.
	 */
	public static void add(File folder, Collection<File> files, Analyst analyst) throws IOException {
		synchronized (lock(folder)) {
			AnalysisRecord before = read(folder);
			if (before == null) {
				// The background has not seen the folder yet: what else it holds is seen now and
				// analysed by the background, only what was uploaded is analysed here.
				updateLocked(folder, analyst, false);
				before = read(folder);
				if (before == null) {
					return;
				}
			}
			Map<String, Entry> entries = new TreeMap<>(before._entries);
			FolderResource sidecar = sidecarOf(folder);
			Set<String> listed = listed(sidecar);
			listed.removeAll(unpictured(sidecar));
			for (File file : files) {
				if (!file.isFile()) {
					continue;
				}
				String name = file.getName();
				long size = file.length();
				long modified = file.lastModified();
				Entry old = entries.get(name);
				if (old != null && old.matches(size, modified) && (old.isAnalysed() || old.isBroken())) {
					continue;
				}
				if (old != null && !old.matches(size, modified)) {
					// Replaced: what was made of the file before describes another one, see issue #256.
					PreviewCache.drop(file);
				}
				if (listed.contains(name)) {
					entries.put(name, new Entry(name, size, modified, null, null, 0));
					continue;
				}
				try {
					entries.put(name, new Entry(name, size, modified, plain(analyst.analyze(file)), null, ANALYSIS));
				} catch (Exception ex) {
					String reason = reason(ex);
					entries.put(name, new Entry(name, size, modified, null, reason, ANALYSIS));
					LOG.log(Level.WARNING, "Cannot read '" + file + "', shown as a broken file: " + reason, ex);
				}
			}
			store(folder, new AnalysisRecord(entries));
			announce(folder);
		}
	}

	/**
	 * Hands what the record of one folder says about a file to the record of another, after the file
	 * was renamed from the one into the other (a move, see
	 * {@link de.haumacher.imageServer.MoveService}): a rename keeps a file's contents, size and
	 * time, so what was found out about it holds in its new folder -- that it cannot be read
	 * included -- and the background does not open it again there.
	 *
	 * <p>
	 * Nothing is handed over where the source folder holds no entry for the file or the target
	 * folder was never recorded (the background records it as a whole); the entry is taken out of
	 * the source's record either way. Each record is updated under its own lock, never both at
	 * once, so that two moves the other way round never wait for each other.
	 * </p>
	 *
	 * @param moved
	 *        The file in the target folder, under the name it has there.
	 */
	public static void carry(File sourceFolder, String name, File moved) {
		File targetFolder = moved.getParentFile();
		Entry entry = null;
		try {
			synchronized (lock(sourceFolder)) {
				AnalysisRecord source = read(sourceFolder);
				if (source != null) {
					Map<String, Entry> entries = new TreeMap<>(source._entries);
					entry = entries.remove(name);
					if (entry != null) {
						// Made of a file that is no more here, see issue #256.
						PreviewCache.drop(new File(sourceFolder, name));
						store(sourceFolder, new AnalysisRecord(entries));
						announce(sourceFolder);
					}
				}
			}
			if (entry == null) {
				return;
			}
			synchronized (lock(targetFolder)) {
				AnalysisRecord target = read(targetFolder);
				if (target == null) {
					return;
				}
				String newName = moved.getName();
				long size = moved.length();
				long modified = moved.lastModified();
				if (!entry.matches(size, modified)) {
					// Changed since it was recorded: the background looks at it again.
					return;
				}
				ImagePart part = entry._part == null ? null : plain(entry._part);
				if (part != null) {
					part.setName(newName);
				}
				Map<String, Entry> entries = new TreeMap<>(target._entries);
				entries.put(newName, new Entry(newName, size, modified, part, entry._failure, entry._analysis));
				store(targetFolder, new AnalysisRecord(entries));
				announce(targetFolder);
			}
		} catch (IOException | RuntimeException ex) {
			// The background records both folders after its quiet period.
			LOG.log(Level.WARNING, "Cannot carry the record of '" + name + "' from '" + sourceFolder + "' to '"
				+ moved + "': " + ex.getMessage(), ex);
		}
	}

	/**
	 * Makes the record of the given folder true to its files again without analysing anything,
	 * after the servlet wrote its <code>index.json</code>: a move, a delete or an edit changed which
	 * files the folder holds or which of them the sidecar lists. Does nothing for a folder the
	 * background has not recorded yet.
	 */
	public static void sidecarWritten(File folder) {
		try {
			synchronized (lock(folder)) {
				if (read(folder) == null) {
					return;
				}
				updateLocked(folder, file -> {
					throw new IllegalStateException("Nothing is analysed here.");
				}, false);
			}
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot update the analysis record of '" + folder + "': " + ex.getMessage(), ex);
		}
	}

	/** Tells who answers from the record that it changed: the album cache and the search. */
	private static void announce(File folder) {
		ResourceCache.recorded(folder);
		de.haumacher.imageServer.SearchIndex.sidecarWritten(folder);
	}

	/** Forgets what is held in memory for unwritable folders, for the tests. */
	static void clearMemory() {
		IN_MEMORY.clear();
	}

	/**
	 * The <code>index.json</code> of the given folder; <code>null</code> for none and for one that
	 * cannot be read, whose files are then all analysed: a broken sidecar never keeps a folder from
	 * being recorded.
	 */
	private static FolderResource sidecarOf(File folder) {
		try {
			return ResourceCache.sidecar(folder);
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot read the sidecar of '" + folder + "': " + ex.getMessage());
			return null;
		}
	}

	/** The names of the files the given sidecar lists, the companions its parts claim included. */
	private static Set<String> listed(FolderResource sidecar) {
		Set<String> result = new HashSet<>();
		if (!(sidecar instanceof AlbumInfo)) {
			return result;
		}
		for (AlbumPart part : ((AlbumInfo) sidecar).getParts()) {
			if (part instanceof ImagePart) {
				addListed(result, (ImagePart) part);
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					addListed(result, image);
				}
			}
		}
		return result;
	}

	/** The names of the parts the given sidecar stores without a picture, see {@link BrokenParts#hasNoPicture(ImagePart)}. */
	private static Set<String> unpictured(FolderResource sidecar) {
		Set<String> result = new HashSet<>();
		if (!(sidecar instanceof AlbumInfo)) {
			return result;
		}
		for (AlbumPart part : ((AlbumInfo) sidecar).getParts()) {
			if (part instanceof ImagePart) {
				if (BrokenParts.hasNoPicture((ImagePart) part)) {
					result.add(((ImagePart) part).getName());
				}
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					if (BrokenParts.hasNoPicture(image)) {
						result.add(image.getName());
					}
				}
			}
		}
		return result;
	}

	private static void addListed(Set<String> result, ImagePart image) {
		result.add(image.getName());
		if (image.getRaw() != null && !image.getRaw().isEmpty()) {
			result.add(image.getRaw());
		}
	}

	/** The analysed part as the record keeps it: what the file says, and nothing of an album. */
	private static ImagePart plain(ImagePart part) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			part.writeTo(json);
		}
		return (ImagePart) Resource.readResource(new JsonReader(new ReaderAdapter(
			new InputStreamReader(new ByteArrayInputStream(buffer.toByteArray()), StandardCharsets.UTF_8))));
	}

	private static String reason(Exception ex) {
		String message = ex.getMessage();
		return message == null || message.isEmpty() ? ex.getClass().getSimpleName() : message;
	}

	/**
	 * Stores the given record of the given folder.
	 *
	 * @return Whether it was stored on disk; <code>false</code> where the folder cannot be written
	 *         and the record is held in memory, see {@link ReadOnlyFolders}.
	 */
	private static boolean store(File folder, AnalysisRecord record) throws IOException {
		byte[] bytes = bytes(record);
		File file = fileOf(folder);
		try {
			File cacheDir = file.getParentFile();
			Files.createDirectories(cacheDir.toPath());
			Path tmp = Files.createTempFile(cacheDir.toPath(), FILE_NAME + ".", PreviewCache.TMP_SUFFIX);
			try {
				Files.write(tmp, bytes);
				try {
					Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ex) {
					Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(tmp);
			}
		} catch (IOException ex) {
			if (ReadOnlyFolders.writable(folder) && (!file.getParentFile().isDirectory()
				|| ReadOnlyFolders.writable(file.getParentFile()))) {
				throw ex;
			}
			IN_MEMORY.put(key(folder), bytes);
			ReadOnlyFolders.report(folder, "what its photographs were found to be (" + FILE_NAME + ")");
			return false;
		}
		if (IN_MEMORY.remove(key(folder)) != null) {
			ReadOnlyFolders.writtenAgain(folder);
		}
		return true;
	}

	private static byte[] bytes(AnalysisRecord record) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			json.beginObject();
			json.name(VERSION__PROP);
			json.value(VERSION);
			json.name(FILES__PROP);
			json.beginObject();
			for (Entry entry : record._entries.values()) {
				json.name(entry._name);
				json.beginObject();
				json.name(SIZE__PROP);
				json.value(entry._size);
				json.name(MODIFIED__PROP);
				json.value(entry._modified);
				if (entry._part != null) {
					json.name(PART__PROP);
					entry._part.writeTo(json);
				}
				if (entry._failure != null) {
					json.name(FAILURE__PROP);
					json.value(entry._failure);
					json.name(ANALYSIS__PROP);
					json.value(entry._analysis);
				}
				json.endObject();
			}
			json.endObject();
			json.endObject();
		}
		return buffer.toByteArray();
	}

	private static String key(File folder) {
		return folder.toPath().toAbsolutePath().normalize().toString();
	}
}
