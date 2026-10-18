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
 * faces, its labels and the GeoNames ids of its places, each with an index on its value, and per
 * place the tag it was read with, so that the options of the search view need no album
 * ({@link #facets(Collection)}). Its words
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
 * <p>
 * The sets of the scopes and of the single criteria asked last are kept until the index changes
 * (issue #276): a query is refined one criterion at a time, and the others are asked again.
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

	/** The persons, labels, cameras and places of some photographs, see {@link #facets(Collection)}. */
	public static final class Facets {

		/** The person ids of their confirmed faces, as stored (merges not resolved). */
		public final Set<String> _persons = new TreeSet<>();

		/** Their labels, sorted. */
		public final Set<String> _labels = new TreeSet<>();

		/** Their cameras, sorted; none empty. */
		public final Set<String> _cameras = new TreeSet<>();

		/**
		 * Their places by GeoNames id, each tag as a photograph was last read with it (in the
		 * gazetteer's own names, not localized), see the table <code>places</code>.
		 */
		public final Map<Integer, PlaceTag> _places = new java.util.TreeMap<>();
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

		/** The tags of {@link #_places}, as the photograph names them, see the table <code>places</code>. */
		final List<PlaceTag> _placeTags;

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
			_placeTags = new ArrayList<>();
			StringBuilder places = new StringBuilder();
			StringBuilder text = new StringBuilder(albumText);
			text.append('\n').append(image.getComment() == null ? "" : image.getComment());
			for (String label : _labels) {
				text.append('\n').append(label);
			}
			if (image.getPlaces() != null) {
				for (PlaceTag tag : image.getPlaces().getTags()) {
					if (_places.add(Integer.valueOf(tag.getGeonameId()))) {
						_placeTags.add(tag);
						places.append(tag.getGeonameId()).append('\u0001').append(tag.getName()).append('\u0001')
							.append(tag.getKind()).append('\u0001').append(tag.getFeatureCode()).append('\u0001')
							.append(tag.getCountry()).append('\u0002');
					}
					if (tag.getName() != null && !tag.getName().isEmpty()) {
						text.append('\n').append(tag.getName());
					}
				}
			}
			_text = text.toString();
			_rowHash = hash(_name + '\u0000' + _date + '\u0000' + _rating + '\u0000' + _privacy + '\u0000' + _camera
				+ '\u0000' + _video + '\u0000' + _persons + '\u0000' + _labels + '\u0000' + places);
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
		// What the index read of every folder of the tree, asked once rather than once per folder:
		// a servlet without a background walks the tree before every search.
		Map<String, Known> known = force ? null : _store.read(connection -> knownTree(connection, path), null);
		Set<String> seen = new HashSet<>();
		walk(path, dir, force, seen, known);
		for (String below : known == null ? knownBelow(path) : known.keySet()) {
			if (!seen.contains(below)) {
				removeTree(below);
			}
		}
	}

	/**
	 * @param known
	 *        What the index read of the folders of the tree, by their paths; <code>null</code> to
	 *        ask it for each folder.
	 */
	private void walk(String path, File dir, boolean force, Set<String> seen, Map<String, Known> known) {
		seen.add(path);
		if (update(path, force, known)) {
			// A collection or a saved search: nothing below it is searched.
			return;
		}
		File[] children = dir.listFiles(File::isDirectory);
		if (children == null) {
			return;
		}
		for (File child : children) {
			if (PhotoSearch.isLibraryEntry(child.getName())) {
				walk(path.isEmpty() ? child.getName() : path + "/" + child.getName(), child, force, seen, known);
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
		return update(path, force, null);
	}

	/**
	 * {@link #update(String, boolean)} with what the index read of the folders of a tree, by their
	 * paths, <code>null</code> to ask it.
	 */
	private boolean update(String path, boolean force, Map<String, Known> tree) {
		File dir = fileOf(path);
		if (!dir.isDirectory()) {
			_stale.remove(path);
			removeTree(path);
			return true;
		}
		if (!force && !_stale.contains(path)) {
			Known known = tree != null ? tree.get(path) : _store.read(connection -> known(connection, path), null);
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
				PreparedStatement places = connection.prepareStatement("INSERT INTO photo_places (photo, place) VALUES (?, ?)");
				PreparedStatement placeTags = connection.prepareStatement(
					"MERGE INTO places (id, name, kind, feature, country) KEY (id) VALUES (?, ?, ?, ?, ?)")) {
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
				for (PlaceTag tag : row._placeTags) {
					// The tag as it was read last: the same id is the same place, whoever names it.
					placeTags.setInt(1, tag.getGeonameId());
					placeTags.setString(2, nonNull(tag.getName()));
					placeTags.setString(3, tag.getKind().name());
					placeTags.setString(4, nonNull(tag.getFeatureCode()));
					placeTags.setString(5, nonNull(tag.getCountry()));
					placeTags.addBatch();
				}
				details = true;
			}
			if (details) {
				persons.executeBatch();
				labels.executeBatch();
				places.executeBatch();
				placeTags.executeBatch();
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

	private static String nonNull(String value) {
		return value == null ? "" : value;
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

	/** What the index read of every folder at or below the given path, by its path. */
	private static Map<String, Known> knownTree(Connection connection, String path) throws SQLException {
		Map<String, Known> result = new HashMap<>();
		try (PreparedStatement select = treeStatement(connection, "SELECT path, id, folder_stamp, sidecar_stamp, "
			+ "record_stamp, waiting_epoch, closed FROM albums", path); ResultSet rows = select.executeQuery()) {
			while (rows.next()) {
				result.put(rows.getString(1), new Known(rows.getLong(2),
					new Stamps(rows.getLong(3), rows.getLong(4), rows.getLong(5)), rows.getLong(6), rows.getBoolean(7)));
			}
		}
		return result;
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
		// Read before the rows: a write in between makes the next query compute again.
		long version = _store.version();
		List<Match> result = _store.read(connection -> {
			Evaluation evaluation = new Evaluation(connection, people, -1, version);
			BitSet scoped = scope(evaluation, prefix, visibility);
			BitSet found = (BitSet) scoped.clone();
			if (root != null) {
				found.and(evaluation.eval(root, scoped));
			}
			return evaluation.matches(found, prefix);
		}, new ArrayList<>());
		_store.trimCache();
		result.sort(PhotoSearch.ORDER);
		return result;
	}

	/** A photograph found near a vector, see {@link #nearest}: where it lies, and how near. */
	public record Scored(Match match, float score) {
		// A pair.
	}

	/**
	 * The <code>k</code> photographs below the given folder whose vectors of the content search
	 * (issue #257) are nearest to the given one, among those the given visibility shows, the trash
	 * left out; the nearest first, see {@link de.haumacher.imageServer.index.VectorIndex#nearest}.
	 *
	 * <p>
	 * What the caller may not see is not looked for: the very rows a query of
	 * {@link #find(PathInfo, SearchQuery, PeopleStore, Visibility)} is cut by (the folder, the trash,
	 * the clearance, a link's rating floor and label) are the filter <em>inside</em> the vector
	 * query, so that <code>k</code> are found as long as <code>k</code> may be. What each is shown as
	 * is still decided by its album, as for every answer of the search
	 * (<code>ImageServlet#nearestAnswers</code>).
	 * </p>
	 *
	 * @param vector
	 *        A vector of the model the index holds ({@link de.haumacher.imageServer.index.VectorIndex#stamp()}).
	 * @param imageWeight
	 *        The weight of the picture's vector, <code>0</code> to leave it out.
	 * @param textWeight
	 *        The weight of the text's vector, <code>0</code> to leave it out.
	 * @return Nothing where the space holds no vectors, or none of that many dimensions.
	 */
	public List<Scored> nearest(PathInfo scope, float[] vector, float imageWeight, float textWeight, int k,
			Visibility visibility) {
		return nearest(scope, vector, imageWeight, textWeight, k, visibility, path -> true, null, null, -1);
	}

	/**
	 * {@link #nearest(PathInfo, float[], float, float, int, Visibility)} among the photographs of
	 * the albums the given test passes alone: what the caller is shown nothing of by the album
	 * itself (an inbox they may not see, a folder they may not view) is left out inside the vector
	 * query too, so that it takes none of the <code>k</code> places.
	 *
	 * @param folders
	 *        Whether the photographs of the album at the given path (relative to the space root)
	 *        may be found at all; asked once per album below the scope.
	 */
	public List<Scored> nearest(PathInfo scope, float[] vector, float imageWeight, float textWeight, int k,
			Visibility visibility, java.util.function.Predicate<String> folders) {
		return nearest(scope, vector, imageWeight, textWeight, k, visibility, folders, null, null, -1);
	}

	/**
	 * {@link #nearest(PathInfo, float[], float, float, int, Visibility, java.util.function.Predicate)}
	 * among the photographs that match the given query alone: the conventional criteria of a content
	 * search (issue #257) narrow what is ranked <em>inside</em> the vector query, exactly as the
	 * visibility does, so that <code>k</code> matches are found as long as <code>k</code> match.
	 *
	 * @param filter
	 *        The criteria every photograph found must match, evaluated as {@link #find} evaluates
	 *        them; must hold no content criterion; <code>null</code> for none.
	 */
	public List<Scored> nearest(PathInfo scope, float[] vector, float imageWeight, float textWeight, int k,
			Visibility visibility, java.util.function.Predicate<String> folders, SearchQuery filter, PeopleStore people) {
		return nearest(scope, vector, imageWeight, textWeight, k, visibility, folders, filter, people, -1);
	}

	/**
	 * {@link #nearest(PathInfo, float[], float, float, int, Visibility, java.util.function.Predicate, SearchQuery, PeopleStore)}
	 * without the photograph of the given row: "More like this" (issue #257), whose photograph is no
	 * answer of its own.
	 *
	 * @param without
	 *        The {@link Reference#id() row} of the photograph left out, <code>-1</code> for none.
	 */
	public List<Scored> nearest(PathInfo scope, float[] vector, float imageWeight, float textWeight, int k,
			Visibility visibility, java.util.function.Predicate<String> folders, SearchQuery filter, PeopleStore people,
			long without) {
		return rank(scope, vector, imageWeight, textWeight, k, visibility, folders, filter, people, without);
	}

	/**
	 * A photograph as the index holds it for "More like this" (issue #257), see
	 * {@link SearchIndex#reference(PathInfo, String)}.
	 *
	 * @param id
	 *        Its row, the key of its vectors.
	 * @param image
	 *        The vector of its picture, <code>null</code> where it has none (yet).
	 */
	public record Reference(long id, float[] image) {
		// A pair.
	}

	/**
	 * The photograph of the given name in the given folder as the index holds it for "More like
	 * this" (issue #257): its row and the vector of its picture; <code>null</code> where the index
	 * holds no row of it (yet) or the space has no vector index. Whether the caller may see it is
	 * not asked here.
	 */
	public Reference reference(PathInfo folder, String name) {
		String path = relative(folder.toFile());
		de.haumacher.imageServer.index.VectorIndex vectors = _store.vectors();
		if (path == null || vectors == null) {
			return null;
		}
		writeThrough();
		if (syncsOnRequest()) {
			sync(path, false);
		}
		Map<String, Long> ids = photoIds(_store, path);
		Long id = ids == null ? null : ids.get(name);
		if (id == null) {
			return null;
		}
		try {
			return new Reference(id.longValue(),
				vectors.vector(id.longValue(), de.haumacher.imageServer.index.VectorIndex.IMAGE));
		} catch (IOException ex) {
			LOG.warning("Cannot read the vector index of '" + _root + "': " + ex.getMessage());
			return new Reference(id.longValue(), null);
		}
	}

	/**
	 * The score of the photograph of the given name in the given folder to the given vector, as
	 * {@link #nearest} scores it: <code>imageWeight · cos(q, v_img) + textWeight · cos(q, v_txt)</code>;
	 * <code>null</code> where the photograph has no vectors (yet), or none of that many dimensions.
	 */
	public Float score(PathInfo folder, String name, float[] vector, float imageWeight, float textWeight) {
		String path = relative(folder.toFile());
		de.haumacher.imageServer.index.VectorIndex vectors = _store.vectors();
		if (path == null || vectors == null || vector == null) {
			return null;
		}
		writeThrough();
		Map<String, Long> ids = photoIds(_store, path);
		Long id = ids == null ? null : ids.get(name);
		if (id == null) {
			return null;
		}
		try {
			float result = 0;
			// A vector of weight zero is not asked for: "More like this" compares the pictures alone.
			if (imageWeight != 0) {
				float[] image = vectors.vector(id.longValue(), de.haumacher.imageServer.index.VectorIndex.IMAGE);
				if (image == null || image.length != vector.length) {
					return null;
				}
				result += imageWeight * cosine(vector, image);
			}
			if (textWeight != 0) {
				float[] text = vectors.vector(id.longValue(), de.haumacher.imageServer.index.VectorIndex.TEXT);
				if (text == null || text.length != vector.length) {
					return null;
				}
				result += textWeight * cosine(vector, text);
			}
			return Float.valueOf(result);
		} catch (IOException ex) {
			LOG.warning("Cannot read the vector index of '" + _root + "': " + ex.getMessage());
			return null;
		}
	}

	/** The cosine of the given vectors of equal length, <code>0</code> where one of them is zero. */
	private static float cosine(float[] a, float[] b) {
		double dot = 0;
		double na = 0;
		double nb = 0;
		for (int n = 0; n < a.length; n++) {
			dot += a[n] * b[n];
			na += a[n] * a[n];
			nb += b[n] * b[n];
		}
		return na == 0 || nb == 0 ? 0 : (float) (dot / Math.sqrt(na * nb));
	}

	/**
	 * The stamp of the vectors the index holds, see
	 * {@link de.haumacher.imageServer.index.VectorIndex#stamp()}: empty before any, <code>null</code>
	 * where the space has no vector index.
	 */
	public String vectorStamp() {
		de.haumacher.imageServer.index.VectorIndex vectors = _store.vectors();
		return vectors == null ? null : vectors.stamp();
	}

	/**
	 * The <code>k</code> photographs that look most like the given one, see
	 * {@link #nearest(PathInfo, float[], float, float, int, Visibility)}: "more like this" (issue
	 * #257), by the picture's vector alone, the photograph itself left out.
	 *
	 * @return Nothing where the photograph has no vector (yet).
	 */
	public List<Scored> similarTo(PathInfo folder, String name, PathInfo scope, int k, Visibility visibility) {
		String path = relative(folder.toFile());
		de.haumacher.imageServer.index.VectorIndex vectors = _store.vectors();
		if (path == null || vectors == null) {
			return new ArrayList<>();
		}
		writeThrough();
		Map<String, Long> ids = photoIds(_store, path);
		Long id = ids == null ? null : ids.get(name);
		if (id == null) {
			return new ArrayList<>();
		}
		float[] vector;
		try {
			vector = vectors.vector(id.longValue(), de.haumacher.imageServer.index.VectorIndex.IMAGE);
		} catch (IOException ex) {
			LOG.warning("Cannot read the vector index of '" + _root + "': " + ex.getMessage());
			return new ArrayList<>();
		}
		if (vector == null) {
			return new ArrayList<>();
		}
		return rank(scope, vector, 1, 0, k, visibility, album -> true, null, null, id.longValue());
	}

	private List<Scored> rank(PathInfo scope, float[] vector, float imageWeight, float textWeight, int k,
			Visibility visibility, java.util.function.Predicate<String> folders, SearchQuery filter, PeopleStore people,
			long without) {
		String prefix = relative(scope.toFile());
		de.haumacher.imageServer.index.VectorIndex vectors = _store.vectors();
		if (prefix == null || vectors == null || k <= 0) {
			return new ArrayList<>();
		}
		writeThrough();
		if (syncsOnRequest()) {
			sync(prefix, false);
		}
		long version = _store.version();
		List<Scored> result = _store.read(connection -> {
			Evaluation evaluation = new Evaluation(connection, people, -1, version);
			BitSet scoped = scope(evaluation, prefix, visibility);
			BitSet allowed = withoutFolders(connection, scoped, prefix, folders);
			SearchCriterion root = filter == null ? null : filter.getRoot();
			if (root != null && !(root instanceof SearchAnd && ((SearchAnd) root).getCriteria().isEmpty())) {
				// The criteria narrow inside the vector query, as the visibility does.
				BitSet matching = evaluation.eval(root, scoped);
				allowed = (BitSet) allowed.clone();
				allowed.and(matching);
			}
			if (without >= 0 && without <= Integer.MAX_VALUE && allowed.get((int) without)) {
				allowed = (BitSet) allowed.clone();
				allowed.clear((int) without);
			}
			List<de.haumacher.imageServer.index.VectorIndex.Hit> hits;
			try {
				hits = vectors.nearest(vector, imageWeight, textWeight, k, allowed);
			} catch (IOException ex) {
				LOG.warning("Cannot read the vector index of '" + _root + "': " + ex.getMessage());
				return new ArrayList<Scored>();
			}
			BitSet found = new BitSet();
			for (de.haumacher.imageServer.index.VectorIndex.Hit hit : hits) {
				if (hit.photo() >= 0 && hit.photo() <= Integer.MAX_VALUE) {
					found.set((int) hit.photo());
				}
			}
			Map<Long, Match> byId = new HashMap<>();
			for (Match match : evaluation.matches(found, prefix)) {
				byId.put(Long.valueOf(match.getId()), match);
			}
			List<Scored> scored = new ArrayList<>(hits.size());
			for (de.haumacher.imageServer.index.VectorIndex.Hit hit : hits) {
				Match match = byId.get(Long.valueOf(hit.photo()));
				if (match != null) {
					scored.add(new Scored(match, hit.score()));
				}
			}
			return scored;
		}, new ArrayList<>());
		_store.trimCache();
		return result;
	}

	/**
	 * The given rows less those of the albums below the given path the given test does not pass;
	 * the given set itself where it passes every one. Never changes the given set.
	 */
	private static BitSet withoutFolders(Connection connection, BitSet rows, String prefix,
			java.util.function.Predicate<String> folders) throws SQLException {
		List<Long> excluded = new ArrayList<>();
		try (PreparedStatement select = treeStatement(connection, "SELECT id, path FROM albums", prefix);
				ResultSet albums = select.executeQuery()) {
			while (albums.next()) {
				if (!folders.test(albums.getString(2))) {
					excluded.add(Long.valueOf(albums.getLong(1)));
				}
			}
		}
		if (excluded.isEmpty()) {
			return rows;
		}
		BitSet result = (BitSet) rows.clone();
		collect(connection, "SELECT p.id FROM TABLE(x BIGINT = ?) t JOIN photos p ON p.album = t.x",
			excluded.toArray(new Long[excluded.size()]), found -> {
				long id = found.getLong(1);
				if (id >= 0 && id <= Integer.MAX_VALUE) {
					result.clear((int) id);
				}
			});
		return result;
	}

	/**
	 * The ids of the rows of the photographs of the folder at the given path (relative to the space
	 * root) in the given index, by name: the keys of the vectors of the content search (issue #264,
	 * slice 4). Empty for a folder the search did not read; <code>null</code> where the index cannot
	 * be read.
	 */
	public static Map<String, Long> photoIds(SpaceIndex store, String path) {
		return store.read(connection -> {
			Map<String, Long> result = new HashMap<>();
			try (PreparedStatement select = connection.prepareStatement(
				"SELECT p.name, p.id FROM photos p JOIN albums a ON a.id = p.album WHERE a.path = ?")) {
				select.setString(1, path);
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						result.put(rows.getString(1), Long.valueOf(rows.getLong(2)));
					}
				}
			}
			return result;
		}, null);
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
			Evaluation evaluation = new Evaluation(connection, people, id, 0);
			BitSet scoped = evaluation.scope(path, Visibility.ALL);
			return Boolean.valueOf(scoped.get((int) id) && (root == null || evaluation.eval(root, scoped).get((int) id)));
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
		return cached(_scopes, key, evaluation._version, () -> evaluation.scope(prefix, visibility));
	}

	/** What computes a set of rows. */
	private interface Rows {
		BitSet compute() throws SQLException;
	}

	/**
	 * The rows of the given key in the given cache where they were computed at the given version of
	 * the index, computed and kept otherwise. Never changed by the caller.
	 */
	private static BitSet cached(Map<String, Cached> cache, String key, long version, Rows rows) throws SQLException {
		synchronized (cache) {
			Cached known = cache.get(key);
			if (known != null && known._version == version) {
				return known._rows;
			}
		}
		BitSet result = rows.compute();
		synchronized (cache) {
			cache.put(key, new Cached(version, result));
		}
		return result;
	}

	/** Rows computed at a version of the index, see {@link #cached(Map, String, long, Rows)}. */
	private static final class Cached {
		final long _version;

		final BitSet _rows;

		Cached(long version, BitSet rows) {
			_version = version;
			_rows = rows;
		}
	}

	/** The scopes of the last queries, see {@link #scope(Evaluation, String, Visibility)}; a few bits per photograph each. */
	private final Map<String, Cached> _scopes = lru(8);

	/**
	 * The rows of the last criteria asked (a person, a label, a date, a text, ...), see
	 * {@link Evaluation#eval}: a query is refined one criterion at a time, and the others are asked
	 * again. A bit per photograph each, as a scope.
	 */
	private final Map<String, Cached> _leaves = lru(32);

	private static Map<String, Cached> lru(int size) {
		return new java.util.LinkedHashMap<>(16, 0.75f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
				return size() > size;
			}
		};
	}

	/**
	 * The persons, labels, cameras and places of the photographs of the given rows, see
	 * {@link Match#getId()}: read off the index alone, no album asked.
	 */
	public Facets facets(Collection<Long> ids) {
		Facets result = new Facets();
		if (ids.isEmpty()) {
			return result;
		}
		return _store.read(connection -> {
			Long[] all = ids.toArray(new Long[ids.size()]);
			collect(connection, "SELECT DISTINCT f.person FROM TABLE(x BIGINT = ?) t JOIN photo_persons f ON f.photo = t.x",
				all, rows -> result._persons.add(rows.getString(1)));
			collect(connection, "SELECT DISTINCT f.label FROM TABLE(x BIGINT = ?) t JOIN photo_labels f ON f.photo = t.x",
				all, rows -> result._labels.add(rows.getString(1)));
			collect(connection, "SELECT DISTINCT p.camera FROM TABLE(x BIGINT = ?) t JOIN photos p ON p.id = t.x "
				+ "WHERE p.camera <> ''", all, rows -> result._cameras.add(rows.getString(1)));
			collect(connection, "SELECT DISTINCT l.id, l.name, l.kind, l.feature, l.country FROM TABLE(x BIGINT = ?) t "
				+ "JOIN photo_places f ON f.photo = t.x JOIN places l ON l.id = f.place", all, rows -> {
					Integer id = Integer.valueOf(rows.getInt(1));
					if (!result._places.containsKey(id)) {
						result._places.put(id, PlaceTag.create().setGeonameId(id.intValue()).setName(rows.getString(2))
							.setKind(de.haumacher.imageServer.shared.model.PlaceKind.valueOf(rows.getString(3)))
							.setFeatureCode(rows.getString(4)).setCountry(rows.getString(5)));
					}
				});
			return result;
		}, result);
	}

	/** What is taken of a row of a facet, see {@link SearchIndex#collect}. */
	private interface Collector {
		void accept(ResultSet rows) throws SQLException;
	}

	/**
	 * Runs the given selection, which joins its one array parameter as the table of ids (see the
	 * class comment of {@link SpaceIndex}: an <code>IN</code> list re-checks itself for every row),
	 * for the given ids, a chunk at a time.
	 */
	private static void collect(Connection connection, String sql, Long[] ids, Collector into) throws SQLException {
		try (PreparedStatement select = connection.prepareStatement(sql)) {
			for (int first = 0; first < ids.length; first += CHUNK) {
				select.setObject(1, first == 0 && ids.length <= CHUNK ? ids
					: java.util.Arrays.copyOfRange(ids, first, Math.min(ids.length, first + CHUNK)));
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						into.accept(rows);
					}
				}
			}
		}
	}

	/**
	 * Reads again those of the given folders below the given one that changed since the index read
	 * them (their stamps, or a gazetteer that moved on while a photograph waited for its places),
	 * and forgets those that are gone: the background's work, done now for the folders an answer
	 * is about to be read off the index alone, so that it is what their albums hold, see
	 * <code>ImageServlet#serveSearchOptions</code>. Only a changed folder is read; the others cost
	 * three stamps each.
	 *
	 * @return Whether any folder was read again or forgotten, so that what was found before is to
	 *         be asked again.
	 */
	public boolean refresh(PathInfo scope, Collection<PathInfo> folders) {
		String prefix = relative(scope.toFile());
		if (prefix == null || folders.isEmpty()) {
			return false;
		}
		Map<String, Known> known = _store.read(connection -> knownTree(connection, prefix), null);
		boolean changed = false;
		for (PathInfo folder : folders) {
			String path = relative(folder.toFile());
			if (path == null) {
				continue;
			}
			File dir = folder.toFile();
			Known read = known == null ? null : known.get(path);
			if (!dir.isDirectory() || _stale.contains(path) || read == null || !current(read, new Stamps(dir))) {
				update(path, false);
				changed = true;
			}
		}
		return changed;
	}

	/** One query asked of the index, see the class comment. */
	private final class Evaluation {
		private final Connection _connection;

		private final PeopleStore _people;

		/** The one row asked about, <code>-1</code> for all. */
		private final long _only;

		/** The version of the index read before the first row, see {@link SearchIndex#cached}. */
		final long _version;

		Evaluation(Connection connection, PeopleStore people, long only, long version) {
			_connection = connection;
			_people = people;
			_only = only;
			_version = version;
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

		/**
		 * The rows the given criterion matches, among the given scope (a superset of them, where
		 * the scope does not matter). Never changed by the caller: it may be kept for the next
		 * query, see {@link SearchIndex#_leaves}.
		 */
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
			String key = leafKey(criterion);
			if (key == null || _only >= 0) {
				return leaf(criterion, scope);
			}
			return cached(_leaves, key, _version, () -> leaf(criterion, scope));
		}

		/**
		 * What tells the rows of the given criterion apart from those of every other, as far as
		 * they do not depend on the scope; <code>null</code> for one that is not kept.
		 */
		private String leafKey(SearchCriterion criterion) {
			if (criterion instanceof SearchPerson) {
				return "person\u0000" + String.join("\u0000",
					new TreeSet<>(PhotoSearch.idsOf(((SearchPerson) criterion).getPerson(), _people)));
			}
			if (criterion instanceof SearchDate) {
				return "date\u0000" + ((SearchDate) criterion).getFrom() + "\u0000" + ((SearchDate) criterion).getTo();
			}
			if (criterion instanceof SearchPlace) {
				return "place\u0000" + ((SearchPlace) criterion).getGeonameId();
			}
			if (criterion instanceof SearchLabel) {
				return "label\u0000" + ((SearchLabel) criterion).getLabel();
			}
			if (criterion instanceof SearchRating) {
				return "rating\u0000" + ((SearchRating) criterion).getMin();
			}
			if (criterion instanceof SearchMedia) {
				return "media\u0000" + ((SearchMedia) criterion).isVideo();
			}
			if (criterion instanceof SearchCamera) {
				return "camera\u0000" + ((SearchCamera) criterion).getCamera();
			}
			if (criterion instanceof SearchFolder) {
				String path = PhotoSearch.trimSlashes(((SearchFolder) criterion).getPath());
				// The folder searched is the scope itself.
				return path.isEmpty() ? null : "folder\u0000" + path;
			}
			if (criterion instanceof SearchText) {
				String text = ((SearchText) criterion).getText();
				// Nothing typed is the scope itself.
				return text == null || text.trim().isEmpty() ? null : "text\u0000" + text;
			}
			return null;
		}

		/** {@link #eval(SearchCriterion, BitSet)} of a criterion that is no "all", "any" or "not". */
		private BitSet leaf(SearchCriterion criterion, BitSet scope) throws SQLException {
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
		 * The matches of the given rows, named below the folder at the given path: a dense set by a
		 * walk over the range of their ids, a sparse one joined with its ids.
		 */
		List<Match> matches(BitSet rows, String prefix) throws SQLException {
			int count = rows.cardinality();
			List<Match> result = new ArrayList<>(count);
			if (count == 0) {
				return result;
			}
			int first = rows.nextSetBit(0);
			int last = rows.length() - 1;
			if ((long) last - first + 1 <= (long) DENSE * count) {
				Map<Long, Folder> byId = new HashMap<>();
				long max = 0;
				try (PreparedStatement select = treeStatement(_connection, "SELECT id, path FROM albums", prefix);
						ResultSet found = select.executeQuery()) {
					while (found.next()) {
						long album = found.getLong(1);
						byId.put(Long.valueOf(album), folder(found.getString(2), prefix));
						max = Math.max(max, album);
					}
				}
				// By the id of the album, a walk asks once per row: an array where the ids are not
				// spread much wider than the albums are many, as they are numbered as read.
				Folder[] folders = null;
				if (max < 4L * byId.size() + 1024) {
					folders = new Folder[(int) max + 1];
					for (Map.Entry<Long, Folder> entry : byId.entrySet()) {
						folders[entry.getKey().intValue()] = entry.getValue();
					}
				}
				try (PreparedStatement select =
					_connection.prepareStatement("SELECT id, album, name, taken FROM photos WHERE id BETWEEN ? AND ?")) {
					select.setLong(1, first);
					select.setLong(2, last);
					try (ResultSet found = select.executeQuery()) {
						while (found.next()) {
							long id = found.getLong(1);
							if (!rows.get((int) id)) {
								continue;
							}
							long album = found.getLong(2);
							Folder folder = folders == null ? byId.get(Long.valueOf(album))
								: album >= 0 && album < folders.length ? folders[(int) album] : null;
							if (folder != null) {
								result.add(new Match(id, folder._path, folder._below, found.getString(3), found.getLong(4)));
							}
						}
					}
				}
				return result;
			}
			Map<String, Folder> folders = new HashMap<>();
			try (PreparedStatement select = _connection.prepareStatement("SELECT p.id, a.path, p.name, p.taken "
				+ "FROM TABLE(x BIGINT = ?) t JOIN photos p ON p.id = t.x JOIN albums a ON a.id = p.album")) {
				int id = first;
				while (id >= 0) {
					Long[] chunk = new Long[Math.min(CHUNK, count - result.size())];
					int size = 0;
					for (; size < chunk.length && id >= 0; id = rows.nextSetBit(id + 1)) {
						chunk[size++] = Long.valueOf(id);
					}
					select.setObject(1, size == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, size));
					try (ResultSet found = select.executeQuery()) {
						while (found.next()) {
							Folder folder = folders.computeIfAbsent(found.getString(2), path -> folder(path, prefix));
							result.add(new Match(found.getLong(1), folder._path, folder._below, found.getString(3),
								found.getLong(4)));
						}
					}
				}
			}
			return result;
		}
	}

	/** An album of matches: where it lies, and its path below the folder searched. */
	private static final class Folder {
		final PathInfo _path;

		final String _below;

		Folder(PathInfo path, String below) {
			_path = path;
			_below = below;
		}
	}

	/** The album at the given path, named below the given prefix. */
	private Folder folder(String path, String prefix) {
		String below = path.equals(prefix) ? "" : (prefix.isEmpty() ? path : path.substring(prefix.length() + 1)) + "/";
		return new Folder(pathOf(path), below);
	}

	/**
	 * How many ids a set may span per row it holds to be read by a walk over that span rather than
	 * joined with its ids: a row read by its id costs about as much as five read in a walk.
	 */
	private static final int DENSE = 5;

	/**
	 * How many ids {@link Evaluation#matches} joins with one statement; an array parameter holds at
	 * most 65 536 values.
	 */
	private static final int CHUNK = 32768;


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
	public static long hash(String text) {
		long result = 0xcbf29ce484222325L;
		for (int n = 0; n < text.length(); n++) {
			result ^= text.charAt(n);
			result *= 0x100000001b3L;
		}
		return result == 0 ? 1 : result;
	}
}
