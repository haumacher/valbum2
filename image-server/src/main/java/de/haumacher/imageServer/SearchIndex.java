/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.PhotoSearch.Match;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.cache.BrokenParts;
import de.haumacher.imageServer.faces.PeopleStore;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.index.TextAnalysis;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.PlaceTag;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchCamera;
import de.haumacher.imageServer.shared.model.SearchCriterion;
import de.haumacher.imageServer.shared.model.SearchDate;
import de.haumacher.imageServer.shared.model.SearchFolder;
import de.haumacher.imageServer.shared.model.SearchLabel;
import de.haumacher.imageServer.shared.model.SearchMedia;
import de.haumacher.imageServer.shared.model.SearchNot;
import de.haumacher.imageServer.shared.model.SearchOr;
import de.haumacher.imageServer.shared.model.SearchPerson;
import de.haumacher.imageServer.shared.model.SearchPlace;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.imageServer.shared.model.SearchRating;
import de.haumacher.imageServer.shared.model.SearchText;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;

/**
 * What the search of issue #227 asks of every photograph of one space, kept in the index of the
 * space (issue #264) so that a query never reads a sidecar, nor holds the library in the heap.
 *
 * <h2>Where what lives</h2>
 *
 * <p>
 * Per photograph a row in the H2 database of the {@link SpaceIndex}: its album, its name, its
 * date, rating, privacy, camera and kind, and in tables of their own the persons of its confirmed
 * faces, its labels and the GeoNames ids of its places, each with an index on its value. Its words
 * &mdash; the title and subtitle of its album, its description, its labels and the names of its
 * places &mdash; are a document of the Lucene index beside it, keyed by the row's id, analysed for
 * German and English ({@link TextAnalysis}). One store per criterion: the text criterion asks
 * Lucene, every other one H2.
 * </p>
 *
 * <h2>A query</h2>
 *
 * <p>
 * The criteria form a free tree of "all", "any" and "not", text and structure mixed. Each leaf is
 * answered by one indexed lookup (a person, a label, a place, a date range, ... in H2, the words in
 * Lucene) as a set of row ids, and the tree is combined on those sets; "not" is the scope less its
 * criterion. The scope &mdash; the folder searched, less the trash, and less what the caller may
 * not see by their clearance, a link's rating floor and label &mdash; is one more such set, so
 * that a result is not emptied afterwards by photographs it may not show. Only the rows of the
 * result are read: their folder, name and date. What the caller is shown of each is still decided
 * by its album ({@link PrivacyFilter}), as for every view; the index only picks.
 * </p>
 *
 * <h2>The temporary truth</h2>
 *
 * <p>
 * A query answers what the index holds (issue #256): it compares no stamp and reads no folder.
 * </p>
 *
 * <ul>
 * <li><b>The server's own edits</b> write through: every sidecar the server writes, and every
 * record of the background, tells the index of its space ({@link #sidecarWritten(File)}); the
 * request that wrote reads the folder into the index before it returns
 * ({@link #writeThrough()}), and so does every search before it asks anything. A move, a
 * delete, a purge or a rename of folders reads the trees again ({@link #treeChanged(File)}).</li>
 * <li><b>Everything else</b> &mdash; a file copied in, a folder removed by hand &mdash; waits for
 * the background: this is a step of the {@link FolderPipeline}, behind the places of issue #234,
 * which runs on every folder the background visits (the walk at every start, the watch, the
 * sweep). It compares the stamps of the folder, of its <code>index.json</code> and of its
 * analysis record with what the index read, and the epoch of the gazetteer where a photograph
 * waited for its places, and reads the folder again where any differs; a folder that is gone goes
 * with everything below it ({@link #gone(String)}, {@link #retain(Set)}).</li>
 * <li><b>A servlet without a background</b> (a test's, an offline tool's) does that work on the
 * request, before a search, for the folders it searches: there is nobody else to do it, as for
 * the analysis record ({@link de.haumacher.imageServer.cache.ResourceCache#setBackground}).</li>
 * </ul>
 *
 * <p>
 * Nothing is rebuilt after a restart: the rows and documents are on disk with the stamps they were
 * read at, and the walk of the background reads only what changed while the server was down. A
 * process killed before Lucene committed loses the text of its last writes; the albums of those
 * writes are read again ({@link SpaceIndex#textSince()}).
 * </p>
 */
public final class SearchIndex implements FolderPipeline.Step {

	private static final Logger LOG = Logger.getLogger(SearchIndex.class.getName());

	/** The name of the step in the pipeline's record. */
	public static final String NAME = "search";

	/** The field of a document that names its row, for an update. */
	static final String KEY = "key";

	/** The field of a document that names the row of its album, for a folder that goes. */
	static final String ALBUM = "album";

	/** The doc value of a document that holds the id of its row, for an answer. */
	static final String PHOTO = "photo";

	/** The indexes of the spaces this process serves, see {@link #sidecarWritten(File)}. */
	private static final List<SearchIndex> INSTANCES = new CopyOnWriteArrayList<>();

	/**
	 * Tells the index of the space the given folder lies in that its sidecar or its analysis record
	 * was just written, see the class comment. A folder outside every served space is ignored.
	 */
	public static void sidecarWritten(File folder) {
		for (SearchIndex index : INSTANCES) {
			String path = index.relative(folder);
			if (path != null) {
				index._stale.add(path);
			}
		}
	}

	/**
	 * What a caller may be shown at all, by the photograph's own fields, see
	 * {@link PrivacyFilter#visible(ImagePart, int, int, String)}: the scope of a query is narrowed
	 * by it, so that a result is not emptied by photographs its album then hides. The trash is
	 * never found, whoever asks.
	 */
	public static final class Visibility {

		/** Everything but the trash. */
		public static final Visibility ALL = new Visibility(Privacy.PRIVATE, Ratings.MIN, "");

		final int _clearance;

		final int _minRating;

		final String _label;

		/**
		 * @param clearance
		 *        The highest privacy shown.
		 * @param minRating
		 *        The lowest rating shown; never below the trash.
		 * @param label
		 *        The one label whose photographs are shown, the empty string for all.
		 */
		public Visibility(int clearance, int minRating, String label) {
			_clearance = clearance;
			_minRating = Ratings.withoutTrash(minRating);
			_label = label == null ? "" : label;
		}
	}

	/** The persons, labels and cameras of some photographs, see {@link #facets(Collection)}. */
	public static final class Facets {

		/** The person ids of their confirmed faces, as stored (merges not resolved). */
		public final Set<String> _persons = new TreeSet<>();

		/** Their labels, sorted. */
		public final Set<String> _labels = new TreeSet<>();

		/** Their cameras, sorted; none empty. */
		public final Set<String> _cameras = new TreeSet<>();
	}

	/** What the search asks of one photograph, as read from its album. */
	static final class Row {
		final String _name;

		final long _date;

		final int _rating;

		final int _privacy;

		final String _camera;

		final boolean _video;

		final Set<String> _persons;

		final Set<String> _labels;

		final Set<Integer> _places;

		final String _text;

		final long _rowHash;

		final long _textHash;

		Row(ImagePart image, String albumText) {
			_name = image.getName();
			_date = image.getDate();
			_rating = image.getRating();
			_privacy = image.getPrivacy();
			_camera = image.getCamera() == null ? "" : image.getCamera();
			_video = image.getKind() != ImageKind.IMAGE;
			_persons = new LinkedHashSet<>();
			for (FaceTag tag : image.getTags()) {
				if (tag.getState() == FaceState.CONFIRMED && !tag.getPerson().isEmpty()) {
					_persons.add(tag.getPerson());
				}
			}
			_labels = new LinkedHashSet<>();
			for (LabelName label : image.getLabels()) {
				_labels.add(label.getName());
			}
			_places = new LinkedHashSet<>();
			StringBuilder text = new StringBuilder(albumText);
			text.append('\n').append(image.getComment() == null ? "" : image.getComment());
			for (String label : _labels) {
				text.append('\n').append(label);
			}
			if (image.getPlaces() != null) {
				for (PlaceTag tag : image.getPlaces().getTags()) {
					_places.add(Integer.valueOf(tag.getGeonameId()));
					if (tag.getName() != null && !tag.getName().isEmpty()) {
						text.append('\n').append(tag.getName());
					}
				}
			}
			_text = text.toString();
			_rowHash = hash(_name + '\u0000' + _date + '\u0000' + _rating + '\u0000' + _privacy + '\u0000' + _camera
				+ '\u0000' + _video + '\u0000' + _persons + '\u0000' + _labels + '\u0000' + _places);
			_textHash = hash(_text);
		}
	}

	/** What one folder holds for the search. */
	private static final class Content {
		static final Content NOTHING = new Content(false, -1, new ArrayList<>());

		static final Content CLOSED = new Content(true, -1, new ArrayList<>());

		/** A collection or a saved search: nothing of its own, and nothing below it is searched. */
		final boolean _closed;

		/** The places epoch a photograph waiting for its places was read at, <code>-1</code> for none. */
		final long _waitingEpoch;

		final List<Row> _rows;

		Content(boolean closed, long waitingEpoch, List<Row> rows) {
			_closed = closed;
			_waitingEpoch = waitingEpoch;
			_rows = rows;
		}
	}

	/** The stamps of a folder the index compares, see the class comment. */
	private static final class Stamps {
		final long _folder;

		final long _sidecar;

		final long _record;

		Stamps(File dir) {
			_folder = dir.lastModified();
			_sidecar = new File(dir, "index.json").lastModified();
			_record = de.haumacher.imageServer.cache.AnalysisRecord.fileOf(dir).lastModified();
		}

		Stamps(long folder, long sidecar, long record) {
			_folder = folder;
			_sidecar = sidecar;
			_record = record;
		}

		boolean same(Stamps other) {
			return _folder == other._folder && _sidecar == other._sidecar && _record == other._record;
		}
	}

	/** What the index read of a folder. */
	private static final class Known {
		final long _id;

		final Stamps _stamps;

		final long _waitingEpoch;

		final boolean _closed;

		Known(long id, Stamps stamps, long waitingEpoch, boolean closed) {
			_id = id;
			_stamps = stamps;
			_waitingEpoch = waitingEpoch;
			_closed = closed;
		}
	}

	private final Path _basePath;

	private final Path _root;

	private final SpaceIndex _store;

	private final Function<PathInfo, Resource> _albums;

	private final LongSupplier _placesEpoch;

	private final BooleanSupplier _background;

	/** Whether a search does the background's work first, <code>null</code> to follow {@link #_background}. */
	private volatile Boolean _syncOnRequest;

	/** The folders the server wrote since they were read, see {@link #sidecarWritten(File)}. */
	private final Set<String> _stale = ConcurrentHashMap.newKeySet();

	/** How many folders were read into the index, for the tests. */
	private final AtomicInteger _reads = new AtomicInteger();

	/**
	 * Creates the search index of the space at the given root in the given {@link SpaceIndex}.
	 *
	 * @param albums
	 *        A folder as the cache answers it.
	 * @param placesEpoch
	 *        The epoch of the gazetteer, see <code>Places#epoch()</code>, <code>-1</code> without one.
	 * @param background
	 *        Whether a background keeps the index current; where none does, a search does its work
	 *        first, see the class comment.
	 */
	public SearchIndex(Path basePath, SpaceIndex store, Function<PathInfo, Resource> albums, LongSupplier placesEpoch,
			BooleanSupplier background) {
		_basePath = basePath;
		_root = basePath.toAbsolutePath().normalize();
		_store = store;
		_albums = albums;
		_placesEpoch = placesEpoch;
		_background = background;
		INSTANCES.add(this);
		recoverText();
	}

	/** Stops hearing of sidecar writes. */
	public void close() {
		INSTANCES.remove(this);
	}

	/**
	 * Lets a search do the background's work first, or not, whatever the cache says; <code>null</code>
	 * to follow it again. For a test that holds the index to what the server wrote through.
	 */
	void setSyncOnRequest(Boolean sync) {
		_syncOnRequest = sync;
	}

	/** How many folders and photographs the index holds, <code>{folders, photos}</code>. */
	public int[] size() {
		return _store.read(connection -> {
			int[] result = new int[2];
			try (Statement statement = connection.createStatement()) {
				try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM albums")) {
					rows.next();
					result[0] = rows.getInt(1);
				}
				try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM photos")) {
					rows.next();
					result[1] = rows.getInt(1);
				}
			}
			return result;
		}, new int[2]);
	}

	/** How many folders were read into this index since it was created, for the tests. */
	public int reads() {
		return _reads.get();
	}

	/**
	 * The names of the photographs the index holds of the folder at the given path (relative to the
	 * space root), <code>null</code> for a folder it does not know; for the tests.
	 */
	List<String> photosOf(String path) {
		return _store.read(connection -> {
			Known known = known(connection, path);
			if (known == null) {
				return null;
			}
			List<String> result = new ArrayList<>();
			try (PreparedStatement select = connection.prepareStatement("SELECT name FROM photos WHERE album = ? ORDER BY name")) {
				select.setLong(1, known._id);
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						result.add(rows.getString(1));
					}
				}
			}
			return result;
		}, null);
	}

	/**
	 * Marks the albums whose text the text index lost (a process killed before Lucene committed,
	 * a text index rebuilt) to be read again, see {@link SpaceIndex#textSince()}.
	 */
	private void recoverText() {
		long since = _store.textSince();
		_store.write(connection -> {
			try (PreparedStatement photos = connection.prepareStatement(
				"UPDATE photo_hashes SET text_hash = 0 WHERE photo IN (SELECT p.id FROM photos p JOIN albums a "
					+ "ON a.id = p.album WHERE a.text_seq > ?)")) {
				photos.setLong(1, since);
				photos.executeUpdate();
			}
			try (PreparedStatement albums =
				connection.prepareStatement("UPDATE albums SET folder_stamp = -1 WHERE text_seq > ?")) {
				albums.setLong(1, since);
				int lost = albums.executeUpdate();
				if (lost > 0) {
					LOG.info("The text of " + lost + " album(s) of '" + _root + "' was not committed; they are read again.");
				}
			}
		});
	}

	// --- The pipeline step. ---

	@Override
	public String name() {
		return NAME;
	}

	/** Its own check is cheap: three stamps. */
	@Override
	public boolean trustsRecord() {
		return false;
	}

	@Override
	public FolderPipeline.Outcome run(File folder) {
		writeThrough();
		String path = relative(folder);
		if (path != null) {
			update(path, false);
		}
		return FolderPipeline.Outcome.DONE;
	}

	@Override
	public void gone(String path) {
		removeTree(path);
	}

	@Override
	public void retain(Set<String> paths) {
		List<String> drop = new ArrayList<>();
		for (String path : knownBelow("")) {
			if (!fileOf(path).isDirectory() || (!paths.contains(path) && !LibraryFiles.isLibraryPath(path))) {
				drop.add(path);
			}
		}
		for (String path : drop) {
			removeTree(path);
		}
	}

	// --- Keeping current. ---

	/**
	 * Reads every folder the server wrote since the last call into the index, see
	 * {@link #sidecarWritten(File)}: what the request that wrote calls before it returns, and every
	 * search before it asks.
	 */
	public void writeThrough() {
		if (_stale.isEmpty()) {
			return;
		}
		for (String path : new ArrayList<>(_stale)) {
			update(path, true);
		}
	}

	/**
	 * Reads the given folder and everything below it again, and forgets what is gone of it: a move,
	 * a delete, a purge or a rename rearranged a tree, see the class comment.
	 */
	public void treeChanged(File folder) {
		String path = relative(folder);
		if (path != null) {
			sync(path, true);
		}
	}

	/** Brings every folder of the space up to date at once, see {@link #sync(String, boolean)}. */
	public void build() {
		long start = System.nanoTime();
		writeThrough();
		sync("", false);
		int[] size = size();
		LOG.info("Indexed " + size[1] + " photographs in " + size[0] + " folders for searching in "
			+ (System.nanoTime() - start) / 1_000_000 + " ms.");
	}

	/**
	 * Brings the folder at the given path and everything below it up to date: reads what changed
	 * (or everything, where forced), and forgets what is gone. The background's work, done where
	 * nobody else does it.
	 */
	private void sync(String path, boolean force) {
		File dir = fileOf(path);
		if (!dir.isDirectory()) {
			removeTree(path);
			return;
		}
		Set<String> seen = new HashSet<>();
		walk(path, dir, force, seen);
		for (String known : knownBelow(path)) {
			if (!seen.contains(known)) {
				removeTree(known);
			}
		}
	}

	private void walk(String path, File dir, boolean force, Set<String> seen) {
		seen.add(path);
		if (update(path, force)) {
			// A collection or a saved search: nothing below it is searched.
			return;
		}
		File[] children = dir.listFiles(File::isDirectory);
		if (children == null) {
			return;
		}
		for (File child : children) {
			if (PhotoSearch.isLibraryEntry(child.getName())) {
				walk(path.isEmpty() ? child.getName() : path + "/" + child.getName(), child, force, seen);
			}
		}
	}

	/**
	 * Reads the folder at the given path into the index where it changed since it was read (or
	 * always, where forced); forgets it where it is gone.
	 *
	 * @return Whether it is a collection or a saved search, below which nothing is searched.
	 */
	private boolean update(String path, boolean force) {
		File dir = fileOf(path);
		if (!dir.isDirectory()) {
			_stale.remove(path);
			removeTree(path);
			return true;
		}
		if (!force && !_stale.contains(path)) {
			Known known = _store.read(connection -> known(connection, path), null);
			if (known != null && current(known, new Stamps(dir))) {
				return known._closed;
			}
		}
		return read(path, dir);
	}

	/** Whether the given folder, read as the given record says, is what it holds now. */
	private boolean current(Known known, Stamps now) {
		if (!known._stamps.same(now)) {
			return false;
		}
		// A photograph waiting for its places has them once the gazetteer moved on, see issue #234.
		return known._waitingEpoch < 0 || known._waitingEpoch == _placesEpoch.getAsLong();
	}

	/** Reads the folder at the given path into the index. */
	private synchronized boolean read(String path, File dir) {
		_stale.remove(path);
		Stamps stamps = new Stamps(dir);
		Content content = load(path, dir);
		for (int attempt = 0; attempt < 2; attempt++) {
			// Reading the folder may have recorded it (a servlet without a background): the stamps
			// are those of what was read.
			Stamps after = new Stamps(dir);
			if (after.same(stamps)) {
				break;
			}
			stamps = after;
			content = load(path, dir);
		}
		_reads.incrementAndGet();
		Stamps read = stamps;
		Content found = content;
		if (!_store.writeText((connection, sequence) -> store(connection, sequence, path, read, found))) {
			invalidate(path);
		}
		return content._closed;
	}

	/** What the folder at the given path holds now. */
	private Content load(String path, File dir) {
		File[] entries = dir.listFiles();
		boolean holdsFiles = false;
		if (entries != null) {
			for (File entry : entries) {
				if (PhotoSearch.isLibraryEntry(entry.getName()) && !entry.isDirectory()) {
					holdsFiles = true;
					break;
				}
			}
		}
		AlbumInfo album;
		try {
			album = PhotoSearch.albumAt(pathOf(path), holdsFiles, _albums);
		} catch (RuntimeException ex) {
			LOG.warning("Cannot index '" + dir + "': " + ex.getMessage());
			album = null;
		}
		if (album == null) {
			return Content.NOTHING;
		}
		if (PhotoSearch.holdsNoFiles(album)) {
			return Content.CLOSED;
		}
		String albumText = (album.getTitle() == null ? "" : album.getTitle()) + "\n"
			+ (album.getSubTitle() == null ? "" : album.getSubTitle());
		List<Row> rows = new ArrayList<>();
		Set<String> names = new HashSet<>();
		boolean waiting = false;
		for (ImagePart image : PhotoSearch.images(album)) {
			// A file that cannot be read is no photograph to find, see issue #253.
			if (BrokenParts.isBroken(image) || !names.add(image.getName())) {
				continue;
			}
			rows.add(new Row(image, albumText));
			waiting |= image.getPlaces() != null && !image.getPlaces().getPending().isEmpty();
		}
		return new Content(false, waiting ? _placesEpoch.getAsLong() : -1, rows);
	}

	/** Writes what the folder at the given path holds into the index, as one transaction. */
	private SpaceIndex.TextChange store(Connection connection, long sequence, String path, Stamps stamps,
			Content content) throws SQLException {
		Known known = known(connection, path);
		long album;
		if (known == null) {
			try (PreparedStatement insert = connection.prepareStatement(
				"INSERT INTO albums (path, folder_stamp, sidecar_stamp, record_stamp, waiting_epoch, closed, text_seq) "
					+ "VALUES (?, ?, ?, ?, ?, ?, 0)",
				Statement.RETURN_GENERATED_KEYS)) {
				insert.setString(1, path);
				setStamps(insert, 2, stamps, content);
				insert.executeUpdate();
				try (ResultSet keys = insert.getGeneratedKeys()) {
					keys.next();
					album = keys.getLong(1);
				}
			}
		} else {
			album = known._id;
			try (PreparedStatement update = connection.prepareStatement("UPDATE albums SET folder_stamp = ?, "
				+ "sidecar_stamp = ?, record_stamp = ?, waiting_epoch = ?, closed = ? WHERE id = ?")) {
				setStamps(update, 1, stamps, content);
				update.setLong(6, album);
				update.executeUpdate();
			}
		}

		Map<String, long[]> existing = new HashMap<>();
		try (PreparedStatement select =
			connection.prepareStatement("SELECT p.name, p.id, h.row_hash, h.text_hash FROM photos p "
				+ "JOIN photo_hashes h ON h.photo = p.id WHERE p.album = ?")) {
			select.setLong(1, album);
			try (ResultSet rows = select.executeQuery()) {
				while (rows.next()) {
					existing.put(rows.getString(1), new long[] { rows.getLong(2), rows.getLong(3), rows.getLong(4) });
				}
			}
		}

		List<Long> removed = new ArrayList<>();
		Map<Long, String> texts = new HashMap<>();
		try (PreparedStatement insert = connection.prepareStatement(
			"INSERT INTO photos (album, name, taken, rating, privacy, camera, video) VALUES (?, ?, ?, ?, ?, ?, ?)",
			Statement.RETURN_GENERATED_KEYS);
				PreparedStatement update = connection.prepareStatement("UPDATE photos SET taken = ?, rating = ?, "
					+ "privacy = ?, camera = ?, video = ? WHERE id = ?");
				PreparedStatement hashes = connection.prepareStatement(
					"MERGE INTO photo_hashes (photo, row_hash, text_hash) KEY (photo) VALUES (?, ?, ?)");
				PreparedStatement persons = connection.prepareStatement("INSERT INTO photo_persons (photo, person) VALUES (?, ?)");
				PreparedStatement labels = connection.prepareStatement("INSERT INTO photo_labels (photo, label) VALUES (?, ?)");
				PreparedStatement places = connection.prepareStatement("INSERT INTO photo_places (photo, place) VALUES (?, ?)")) {
			Set<String> present = new HashSet<>();
			boolean details = false;
			for (Row row : content._rows) {
				present.add(row._name);
				long[] before = existing.get(row._name);
				long id;
				if (before == null) {
					insert.setLong(1, album);
					insert.setString(2, row._name);
					setFields(insert, 3, row);
					insert.executeUpdate();
					try (ResultSet keys = insert.getGeneratedKeys()) {
						keys.next();
						id = keys.getLong(1);
					}
					setHashes(hashes, id, row);
				} else {
					id = before[0];
					if (before[1] == row._rowHash && before[2] == row._textHash) {
						continue;
					}
					setFields(update, 1, row);
					update.setLong(6, id);
					update.executeUpdate();
					setHashes(hashes, id, row);
					if (before[1] == row._rowHash) {
						texts.put(Long.valueOf(id), row._text);
						continue;
					}
					deleteDetails(connection, id);
				}
				if (before == null || before[2] != row._textHash) {
					texts.put(Long.valueOf(id), row._text);
				}
				for (String person : row._persons) {
					persons.setLong(1, id);
					persons.setString(2, person);
					persons.addBatch();
				}
				for (String label : row._labels) {
					labels.setLong(1, id);
					labels.setString(2, label);
					labels.addBatch();
				}
				for (Integer place : row._places) {
					places.setLong(1, id);
					places.setInt(2, place.intValue());
					places.addBatch();
				}
				details = true;
			}
			if (details) {
				persons.executeBatch();
				labels.executeBatch();
				places.executeBatch();
			}
			for (Map.Entry<String, long[]> entry : existing.entrySet()) {
				if (!present.contains(entry.getKey())) {
					removed.add(Long.valueOf(entry.getValue()[0]));
				}
			}
		}
		if (!removed.isEmpty()) {
			try (PreparedStatement delete = connection.prepareStatement("DELETE FROM photos WHERE id = ?")) {
				for (Long id : removed) {
					delete.setLong(1, id.longValue());
					delete.addBatch();
				}
				delete.executeBatch();
			}
		}
		if (removed.isEmpty() && texts.isEmpty()) {
			return null;
		}
		try (PreparedStatement seq = connection.prepareStatement("UPDATE albums SET text_seq = ? WHERE id = ?")) {
			seq.setLong(1, sequence);
			seq.setLong(2, album);
			seq.executeUpdate();
		}
		String albumKey = Long.toString(album);
		return writer -> {
			for (Long id : removed) {
				writer.deleteDocuments(new Term(KEY, id.toString()));
			}
			for (Map.Entry<Long, String> entry : texts.entrySet()) {
				long id = entry.getKey().longValue();
				Document document = new Document();
				document.add(new StringField(KEY, Long.toString(id), Field.Store.NO));
				document.add(new StringField(ALBUM, albumKey, Field.Store.NO));
				document.add(new NumericDocValuesField(PHOTO, id));
				document.add(new TextField(TextAnalysis.FOLDED, entry.getValue(), Field.Store.NO));
				document.add(new TextField(TextAnalysis.ENGLISH, entry.getValue(), Field.Store.NO));
				document.add(new TextField(TextAnalysis.GERMAN, entry.getValue(), Field.Store.NO));
				writer.updateDocument(new Term(KEY, Long.toString(id)), document);
			}
		};
	}

	private static void setStamps(PreparedStatement statement, int first, Stamps stamps, Content content)
			throws SQLException {
		statement.setLong(first, stamps._folder);
		statement.setLong(first + 1, stamps._sidecar);
		statement.setLong(first + 2, stamps._record);
		statement.setLong(first + 3, content._waitingEpoch);
		statement.setBoolean(first + 4, content._closed);
	}

	private static void setFields(PreparedStatement statement, int first, Row row) throws SQLException {
		statement.setLong(first, row._date);
		statement.setInt(first + 1, row._rating);
		statement.setInt(first + 2, row._privacy);
		statement.setString(first + 3, row._camera);
		statement.setBoolean(first + 4, row._video);
	}

	private static void setHashes(PreparedStatement statement, long id, Row row) throws SQLException {
		statement.setLong(1, id);
		statement.setLong(2, row._rowHash);
		statement.setLong(3, row._textHash);
		statement.executeUpdate();
	}

	private static void deleteDetails(Connection connection, long photo) throws SQLException {
		for (String table : new String[] { "photo_persons", "photo_labels", "photo_places" }) {
			// The names are ours, never a user's.
			try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE photo = ?")) {
				delete.setLong(1, photo);
				delete.executeUpdate();
			}
		}
	}

	/**
	 * Lets the folder at the given path be read again, its text included: a write of it reached H2
	 * but not Lucene, or nothing at all.
	 */
	private void invalidate(String path) {
		_stale.add(path);
		_store.write(connection -> {
			try (PreparedStatement photos = connection.prepareStatement(
				"UPDATE photo_hashes SET text_hash = 0 WHERE photo IN (SELECT p.id FROM photos p JOIN albums a "
					+ "ON a.id = p.album WHERE a.path = ?)")) {
				photos.setString(1, path);
				photos.executeUpdate();
			}
			try (PreparedStatement albums = connection.prepareStatement("UPDATE albums SET folder_stamp = -1 WHERE path = ?")) {
				albums.setString(1, path);
				albums.executeUpdate();
			}
		});
	}

	/** Forgets the folder at the given path and everything below it. */
	private synchronized void removeTree(String path) {
		_store.writeText((connection, sequence) -> {
			List<Long> albums = new ArrayList<>();
			try (PreparedStatement select = treeStatement(connection, "SELECT id FROM albums", path)) {
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						albums.add(Long.valueOf(rows.getLong(1)));
					}
				}
			}
			if (albums.isEmpty()) {
				return null;
			}
			try (PreparedStatement delete = connection.prepareStatement("DELETE FROM albums WHERE id = ?")) {
				for (Long album : albums) {
					delete.setLong(1, album.longValue());
					delete.addBatch();
				}
				delete.executeBatch();
			}
			return writer -> {
				for (Long album : albums) {
					writer.deleteDocuments(new Term(ALBUM, album.toString()));
				}
			};
		});
	}

	/**
	 * The given selection of the albums at or below the given path, its parameters set; it ends
	 * with its condition.
	 */
	private static PreparedStatement treeStatement(Connection connection, String select, String path)
			throws SQLException {
		if (path.isEmpty()) {
			return connection.prepareStatement(select);
		}
		PreparedStatement result = connection.prepareStatement(select + " WHERE path = ? OR path LIKE ? ESCAPE '\\'");
		result.setString(1, path);
		result.setString(2, likePrefix(path));
		return result;
	}

	/** A <code>LIKE</code> pattern of everything below the given path. */
	private static String likePrefix(String path) {
		return path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%";
	}

	/** The paths of the folders the index knows at or below the given one. */
	private List<String> knownBelow(String path) {
		return _store.read(connection -> {
			List<String> result = new ArrayList<>();
			try (PreparedStatement select = treeStatement(connection, "SELECT path FROM albums", path);
					ResultSet rows = select.executeQuery()) {
				while (rows.next()) {
					result.add(rows.getString(1));
				}
			}
			return result;
		}, new ArrayList<>());
	}

	private static Known known(Connection connection, String path) throws SQLException {
		try (PreparedStatement select = connection.prepareStatement("SELECT id, folder_stamp, sidecar_stamp, "
			+ "record_stamp, waiting_epoch, closed FROM albums WHERE path = ?")) {
			select.setString(1, path);
			try (ResultSet rows = select.executeQuery()) {
				if (!rows.next()) {
					return null;
				}
				return new Known(rows.getLong(1), new Stamps(rows.getLong(2), rows.getLong(3), rows.getLong(4)),
					rows.getLong(5), rows.getBoolean(6));
			}
		}
	}

	// --- Searching. ---

	/** Whether a search does the background's work first, see the class comment. */
	private boolean syncsOnRequest() {
		Boolean sync = _syncOnRequest;
		return sync != null ? sync.booleanValue() : !_background.getAsBoolean();
	}

	/**
	 * Every photograph below the given folder the given query matches and the given visibility
	 * shows, the trash left out, by the date it was taken (one without a date last), then by its
	 * path, see {@link PhotoSearch#find}.
	 *
	 * @param query
	 *        What to look for, must have passed {@link PhotoSearch#refusal(SearchQuery)};
	 *        <code>null</code> for everything.
	 * @param people
	 *        The register of the space, through whose merges persons are compared; <code>null</code>
	 *        compares the ids as they stand.
	 */
	public List<Match> find(PathInfo scope, SearchQuery query, PeopleStore people, Visibility visibility) {
		String prefix = relative(scope.toFile());
		if (prefix == null) {
			return new ArrayList<>();
		}
		writeThrough();
		if (syncsOnRequest()) {
			sync(prefix, false);
		}
		SearchCriterion root = query == null ? null : query.getRoot();
		List<Match> result = _store.read(connection -> {
			Evaluation evaluation = new Evaluation(connection, people, -1);
			BitSet scoped = scope(evaluation, prefix, visibility);
			BitSet found = root == null ? (BitSet) scoped.clone() : evaluation.eval(root, scoped);
			found.and(scoped);
			return evaluation.matches(found, prefix, scoped.cardinality());
		}, new ArrayList<>());
		_store.trimCache();
		result.sort(PhotoSearch.ORDER);
		return result;
	}

	/**
	 * Whether the photograph of the given name in the folder at the given path matches the given
	 * query, see {@link PhotoSearch#matching}; the trash never does.
	 */
	public boolean matches(PathInfo folder, String name, SearchQuery query, PeopleStore people) {
		String path = relative(folder.toFile());
		if (path == null) {
			return false;
		}
		writeThrough();
		if (syncsOnRequest()) {
			update(path, false);
		}
		SearchCriterion root = query == null ? null : query.getRoot();
		return _store.read(connection -> {
			long id = -1;
			try (PreparedStatement select = connection.prepareStatement(
				"SELECT p.id FROM photos p JOIN albums a ON a.id = p.album WHERE a.path = ? AND p.name = ?")) {
				select.setString(1, path);
				select.setString(2, name);
				try (ResultSet rows = select.executeQuery()) {
					if (rows.next()) {
						id = rows.getLong(1);
					}
				}
			}
			if (id < 0 || id > Integer.MAX_VALUE) {
				return Boolean.FALSE;
			}
			Evaluation evaluation = new Evaluation(connection, people, id);
			BitSet scoped = evaluation.scope(path, Visibility.ALL);
			BitSet found = root == null ? scoped : evaluation.eval(root, scoped);
			found.and(scoped);
			return Boolean.valueOf(found.get((int) id));
		}, Boolean.FALSE).booleanValue();
	}

	/**
	 * The rows below the given path the given visibility shows, see {@link Evaluation#scope}: what
	 * every query of a folder asks first, kept while the index does not change. Never changed by the
	 * caller.
	 */
	private BitSet scope(Evaluation evaluation, String prefix, Visibility visibility) throws SQLException {
		String key = prefix + '\u0000' + visibility._clearance + '\u0000' + visibility._minRating + '\u0000'
			+ visibility._label;
		// Read before the rows: a write in between makes the next query compute again.
		long version = _store.version();
		synchronized (_scopes) {
			Scope known = _scopes.get(key);
			if (known != null && known._version == version) {
				return known._rows;
			}
		}
		BitSet result = evaluation.scope(prefix, visibility);
		synchronized (_scopes) {
			_scopes.put(key, new Scope(version, result));
		}
		return result;
	}

	/** A scope computed at a version of the index, see {@link #scope(Evaluation, String, Visibility)}. */
	private static final class Scope {
		final long _version;

		final BitSet _rows;

		Scope(long version, BitSet rows) {
			_version = version;
			_rows = rows;
		}
	}

	/** The scopes of the last queries, see {@link #scope(Evaluation, String, Visibility)}; a few bits per photograph each. */
	private final Map<String, Scope> _scopes = new java.util.LinkedHashMap<>(16, 0.75f, true) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Scope> eldest) {
			return size() > MAX_SCOPES;
		}
	};

	/** How many scopes {@link #_scopes} keeps. */
	private static final int MAX_SCOPES = 8;

	/** The persons, labels and cameras of the photographs of the given rows, see {@link Match#getId()}. */
	public Facets facets(Collection<Long> ids) {
		Facets result = new Facets();
		if (ids.isEmpty()) {
			return result;
		}
		return _store.read(connection -> {
			collect(connection, "SELECT DISTINCT person FROM photo_persons WHERE photo IN ", ids, result._persons);
			collect(connection, "SELECT DISTINCT label FROM photo_labels WHERE photo IN ", ids, result._labels);
			collect(connection, "SELECT DISTINCT camera FROM photos WHERE camera <> '' AND id IN ", ids, result._cameras);
			return result;
		}, result);
	}

	/** Runs the given selection ending in <code>IN</code> for the given ids, a chunk at a time. */
	private static void collect(Connection connection, String sql, Collection<Long> ids, Set<String> into)
			throws SQLException {
		StringBuilder statement = new StringBuilder(sql).append("(?");
		for (int n = 1; n < CHUNK; n++) {
			statement.append(", ?");
		}
		statement.append(")");
		try (PreparedStatement select = connection.prepareStatement(statement.toString())) {
			java.util.Iterator<Long> each = ids.iterator();
			while (each.hasNext()) {
				long last = 0;
				for (int n = 0; n < CHUNK; n++) {
					// The last chunk filled up with its last id.
					if (each.hasNext()) {
						last = each.next().longValue();
					}
					select.setLong(n + 1, last);
				}
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						into.add(rows.getString(1));
					}
				}
			}
		}
	}

	/** One query asked of the index, see the class comment. */
	private final class Evaluation {
		private final Connection _connection;

		private final PeopleStore _people;

		/** The one row asked about, <code>-1</code> for all. */
		private final long _only;

		Evaluation(Connection connection, PeopleStore people, long only) {
			_connection = connection;
			_people = people;
			_only = only;
		}

		/** The rows below the given path that the given visibility shows, the trash never. */
		BitSet scope(String path, Visibility visibility) throws SQLException {
			StringBuilder sql = new StringBuilder("SELECT p.id FROM photos p");
			List<Object> parameters = new ArrayList<>();
			if (!path.isEmpty()) {
				sql.append(" JOIN albums a ON a.id = p.album WHERE (a.path = ? OR a.path LIKE ? ESCAPE '\\') AND");
				parameters.add(path);
				parameters.add(likePrefix(path));
			} else {
				sql.append(" WHERE");
			}
			sql.append(" p.rating >= ? AND p.privacy <= ?");
			parameters.add(Integer.valueOf(visibility._minRating));
			parameters.add(Integer.valueOf(visibility._clearance));
			if (!visibility._label.isEmpty()) {
				sql.append(" AND EXISTS (SELECT 1 FROM photo_labels l WHERE l.photo = p.id AND l.label = ?)");
				parameters.add(visibility._label);
			}
			return ids(sql, "p.id", parameters);
		}

		/** The rows the given criterion matches, among the given scope. */
		BitSet eval(SearchCriterion criterion, BitSet scope) throws SQLException {
			if (criterion == null) {
				// Never reached for a query that passed refusal(SearchQuery): nothing.
				return new BitSet();
			}
			if (criterion instanceof SearchAnd) {
				BitSet result = (BitSet) scope.clone();
				for (SearchCriterion each : ((SearchAnd) criterion).getCriteria()) {
					if (result.isEmpty()) {
						break;
					}
					result.and(eval(each, scope));
				}
				return result;
			}
			if (criterion instanceof SearchOr) {
				BitSet result = new BitSet();
				for (SearchCriterion each : ((SearchOr) criterion).getCriteria()) {
					result.or(eval(each, scope));
				}
				return result;
			}
			if (criterion instanceof SearchNot) {
				BitSet result = (BitSet) scope.clone();
				result.andNot(eval(((SearchNot) criterion).getCriterion(), scope));
				return result;
			}
			if (criterion instanceof SearchPerson) {
				// Every id that names the person: the survivor and everybody merged into them.
				Set<String> ids = PhotoSearch.idsOf(((SearchPerson) criterion).getPerson(), _people);
				if (ids.isEmpty()) {
					return new BitSet();
				}
				StringBuilder sql = new StringBuilder("SELECT photo FROM photo_persons WHERE person IN (");
				List<Object> parameters = new ArrayList<>();
				for (String id : ids) {
					sql.append(parameters.isEmpty() ? "?" : ", ?");
					parameters.add(id);
				}
				sql.append(")");
				return ids(sql, "photo", parameters);
			}
			if (criterion instanceof SearchDate) {
				long from = ((SearchDate) criterion).getFrom();
				long to = ((SearchDate) criterion).getTo();
				StringBuilder sql = new StringBuilder("SELECT id FROM photos WHERE taken <> 0");
				List<Object> parameters = new ArrayList<>();
				if (from != 0) {
					sql.append(" AND taken >= ?");
					parameters.add(Long.valueOf(from));
				}
				if (to != 0) {
					sql.append(" AND taken < ?");
					parameters.add(Long.valueOf(to));
				}
				return ids(sql, "id", parameters);
			}
			if (criterion instanceof SearchPlace) {
				return ids(new StringBuilder("SELECT photo FROM photo_places WHERE place = ?"), "photo",
					List.of(Integer.valueOf(((SearchPlace) criterion).getGeonameId())));
			}
			if (criterion instanceof SearchLabel) {
				return ids(new StringBuilder("SELECT photo FROM photo_labels WHERE label = ?"), "photo",
					List.of(((SearchLabel) criterion).getLabel()));
			}
			if (criterion instanceof SearchRating) {
				return ids(new StringBuilder("SELECT id FROM photos WHERE rating >= ?"), "id",
					List.of(Integer.valueOf(((SearchRating) criterion).getMin())));
			}
			if (criterion instanceof SearchMedia) {
				return ids(new StringBuilder("SELECT id FROM photos WHERE video = ?"), "id",
					List.of(Boolean.valueOf(((SearchMedia) criterion).isVideo())));
			}
			if (criterion instanceof SearchCamera) {
				String camera = ((SearchCamera) criterion).getCamera();
				if (camera.isEmpty()) {
					return new BitSet();
				}
				return ids(new StringBuilder("SELECT id FROM photos WHERE camera = ?"), "id", List.of(camera));
			}
			if (criterion instanceof SearchFolder) {
				String path = PhotoSearch.trimSlashes(((SearchFolder) criterion).getPath());
				if (path.isEmpty()) {
					return (BitSet) scope.clone();
				}
				return ids(new StringBuilder("SELECT p.id FROM photos p JOIN albums a ON a.id = p.album "
					+ "WHERE (a.path = ? OR a.path LIKE ? ESCAPE '\\')"), "p.id", List.of(path, likePrefix(path)));
			}
			if (criterion instanceof SearchText) {
				return text(((SearchText) criterion).getText(), scope);
			}
			// Never reached for a query that passed refusal(SearchQuery).
			return new BitSet();
		}

		/** The ids the given selection answers, of the one row asked about where there is one. */
		private BitSet ids(StringBuilder sql, String idColumn, List<Object> parameters) throws SQLException {
			List<Object> all = new ArrayList<>(parameters);
			if (_only >= 0) {
				sql.append(sql.indexOf(" WHERE ") >= 0 ? " AND " : " WHERE ").append(idColumn).append(" = ?");
				all.add(Long.valueOf(_only));
			}
			BitSet result = new BitSet();
			try (PreparedStatement select = _connection.prepareStatement(sql.toString())) {
				for (int n = 0; n < all.size(); n++) {
					select.setObject(n + 1, all.get(n));
				}
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						long id = rows.getLong(1);
						if (id <= Integer.MAX_VALUE) {
							result.set((int) id);
						}
					}
				}
			}
			return result;
		}

		/**
		 * The rows whose words hold every word of the given text, see {@link TextAnalysis}: each
		 * word as the start of a word as written, or as an English or a German stem.
		 */
		private BitSet text(String text, BitSet scope) {
			List<String> words = TextAnalysis.words(TextAnalysis.FOLDED, text);
			if (words.isEmpty()) {
				// Nothing typed is no criterion; something that holds no word finds nothing.
				return text == null || text.trim().isEmpty() ? (BitSet) scope.clone() : new BitSet();
			}
			BooleanQuery.Builder all = new BooleanQuery.Builder();
			for (String word : words) {
				BooleanQuery.Builder any = new BooleanQuery.Builder();
				any.add(new PrefixQuery(new Term(TextAnalysis.FOLDED, word)), BooleanClause.Occur.SHOULD);
				String english = TextAnalysis.word(TextAnalysis.ENGLISH, word);
				if (english != null) {
					any.add(new TermQuery(new Term(TextAnalysis.ENGLISH, english)), BooleanClause.Occur.SHOULD);
				}
				String german = TextAnalysis.word(TextAnalysis.GERMAN, word);
				if (german != null) {
					any.add(new TermQuery(new Term(TextAnalysis.GERMAN, german)), BooleanClause.Occur.SHOULD);
				}
				all.add(any.build(), BooleanClause.Occur.MUST);
			}
			if (_only >= 0) {
				all.add(new TermQuery(new Term(KEY, Long.toString(_only))), BooleanClause.Occur.FILTER);
			}
			Query query = all.build();
			return _store.searchText(searcher -> collect(searcher, query), new BitSet());
		}

		/**
		 * The matches of the given rows, named below the folder at the given path, which holds the
		 * given number of rows: a large part of them is read by a walk over the folder's rows, a
		 * few by their ids.
		 */
		List<Match> matches(BitSet rows, String prefix, int inScope) throws SQLException {
			List<Match> result = new ArrayList<>(rows.cardinality());
			if (rows.isEmpty()) {
				return result;
			}
			Map<Long, String> albums = new HashMap<>();
			Map<String, PathInfo> folders = new HashMap<>();
			if (rows.cardinality() > CHUNK && rows.cardinality() > inScope / 4) {
				try (PreparedStatement select = treeStatement(_connection, "SELECT id, path FROM albums", prefix);
						ResultSet found = select.executeQuery()) {
					while (found.next()) {
						albums.put(Long.valueOf(found.getLong(1)), found.getString(2));
					}
				}
				try (PreparedStatement select = _connection.prepareStatement("SELECT id, album, name, taken FROM photos");
						ResultSet found = select.executeQuery()) {
					while (found.next()) {
						long id = found.getLong(1);
						if (id > Integer.MAX_VALUE || !rows.get((int) id)) {
							continue;
						}
						String path = albums.get(Long.valueOf(found.getLong(2)));
						if (path != null) {
							add(result, folders, path, prefix, id, found.getString(3), found.getLong(4));
						}
					}
				}
				return result;
			}
			StringBuilder sql = new StringBuilder("SELECT id, album, name, taken FROM photos WHERE id IN (?");
			for (int n = 1; n < CHUNK; n++) {
				sql.append(", ?");
			}
			sql.append(")");
			try (PreparedStatement select = _connection.prepareStatement(sql.toString());
					PreparedStatement album = _connection.prepareStatement("SELECT path FROM albums WHERE id = ?")) {
				int id = rows.nextSetBit(0);
				while (id >= 0) {
					// A chunk of ids, the last one filled up with its last id.
					int last = id;
					for (int n = 0; n < CHUNK; n++) {
						if (id >= 0) {
							last = id;
							id = rows.nextSetBit(id + 1);
						}
						select.setLong(n + 1, last);
					}
					try (ResultSet found = select.executeQuery()) {
						while (found.next()) {
							Long albumId = Long.valueOf(found.getLong(2));
							String path = albums.get(albumId);
							if (path == null) {
								album.setLong(1, albumId.longValue());
								try (ResultSet paths = album.executeQuery()) {
									path = paths.next() ? paths.getString(1) : null;
								}
								if (path == null) {
									continue;
								}
								albums.put(albumId, path);
							}
							add(result, folders, path, prefix, found.getLong(1), found.getString(3), found.getLong(4));
						}
					}
				}
			}
			return result;
		}
	}

	/** Adds the match of a row in the folder at the given path, named below the given prefix. */
	private void add(List<Match> result, Map<String, PathInfo> folders, String path, String prefix, long id,
			String name, long date) {
		PathInfo folder = folders.computeIfAbsent(path, this::pathOf);
		String below = path.equals(prefix) ? "" : (prefix.isEmpty() ? path : path.substring(prefix.length() + 1)) + "/";
		result.add(new Match(id, folder, below + name, name, date));
	}

	/** How many rows {@link Evaluation#matches} reads by their ids with one statement. */
	private static final int CHUNK = 512;

	/** The rows of the documents the given query finds. */
	private static BitSet collect(IndexSearcher searcher, Query query) throws IOException {
		BitSet result = new BitSet();
		Weight weight = searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1f);
		for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
			Scorer scorer = weight.scorer(leaf);
			if (scorer == null) {
				continue;
			}
			Bits live = leaf.reader().getLiveDocs();
			NumericDocValues photos = leaf.reader().getNumericDocValues(PHOTO);
			if (photos == null) {
				continue;
			}
			DocIdSetIterator docs = scorer.iterator();
			for (int doc = docs.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = docs.nextDoc()) {
				if ((live == null || live.get(doc)) && photos.advanceExact(doc)) {
					long id = photos.longValue();
					if (id >= 0 && id <= Integer.MAX_VALUE) {
						result.set((int) id);
					}
				}
			}
		}
		return result;
	}

	// --- Coordinates. ---

	/** The given folder relative to the space root, <code>null</code> outside it. */
	String relative(File folder) {
		Path path = folder.toPath().toAbsolutePath().normalize();
		if (!path.startsWith(_root)) {
			return null;
		}
		return _root.relativize(path).toString().replace(File.separatorChar, '/');
	}

	private File fileOf(String path) {
		return path.isEmpty() ? _root.toFile() : _root.resolve(path).toFile();
	}

	private PathInfo pathOf(String path) {
		return path.isEmpty() ? new PathInfo(_basePath) : new PathInfo(_basePath, Paths.get(path));
	}

	/** A 64-bit hash of the given text that is never <code>0</code>, which marks a text to be written again. */
	static long hash(String text) {
		long result = 0xcbf29ce484222325L;
		for (int n = 0; n < text.length(); n++) {
			result ^= text.charAt(n);
			result *= 0x100000001b3L;
		}
		return result == 0 ? 1 : result;
	}
}
