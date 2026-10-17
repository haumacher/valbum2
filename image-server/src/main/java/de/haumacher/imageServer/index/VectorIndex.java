/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.index;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * The vectors of the content search of a space (issue #257) in a Lucene HNSW index, in
 * <code>.valbum/index/{@value #DIRECTORY_NAME}/</code> beside the H2 database and the text index of
 * the {@link SpaceIndex}, see issue #264 (slice 4).
 *
 * <h2>Layout</h2>
 *
 * <p>
 * One document per photograph that has both its vectors, keyed by the id of its row in H2 (the
 * table <code>photos</code> of the search), with <code>{@value #IMAGE}</code> and
 * <code>{@value #TEXT}</code> as {@link KnnFloatVectorField}s (dot product of normalised vectors,
 * which is their cosine), the id as a doc value (for the filter and the answer),
 * the path of its album (for a tree that goes), and a hash of what it was made of (for an album
 * fed again, which changes only what changed). Per album one more document, its <b>marker</b>,
 * which holds no vector: the stamp of what the album was fed from, committed together with its
 * vectors, so that a process killed before a commit loses both and feeds the album again.
 * </p>
 *
 * <p>
 * Who may see a photograph is not in here: a query is filtered by the ids of the rows the caller's
 * {@link de.haumacher.imageServer.SearchIndex.Visibility} and folder leave, the very set the other
 * criteria of the search are cut by, inside the vector query ({@link #nearest}); a rating or a
 * privacy changed is thus felt without the vectors being touched.
 * </p>
 *
 * <h2>Why a folder of its own</h2>
 *
 * <p>
 * Lucene updates a document only as a whole: vectors in the documents of the text would have every
 * changed description read the album's vectors, and every vector the album's text. And the two are
 * stamped differently: the text with the schema of H2, the vectors also with the model that made
 * them, whose change drops every vector and keeps every word. A folder of its own came into an
 * index of the build before without rewriting a single file of the text.
 * </p>
 *
 * <h2>Stamped</h2>
 *
 * <p>
 * Every commit carries {@link #FORMAT}, the generation of the H2 database the ids are of (another
 * one empties this index: its ids would name other photographs), and the stamp of the vectors it
 * holds ({@link #stamp()}: the embedder's model and the stamp of the texts, see
 * <code>ContentText#stamp</code>). A change of the stamp drops every vector ({@link #reset}).
 * Committed at most every {@link #COMMIT_MILLIS}, like the text, and when closed.
 * </p>
 *
 * <h2>HNSW</h2>
 *
 * <p>
 * Lucene's default format (<code>Lucene99HnswVectorsFormat</code>): 16 neighbours per node, a beam
 * of 100 while building. A custom one would have to be registered with Lucene's SPI by name to be
 * read again (and the server JAR merges Lucene's service files, see
 * <code>faq/search-index.md</code>), and at 256 dimensions and some 100 000 photographs the default
 * finds the true nearest ten nearly always; a larger graph costs memory for little.
 * </p>
 */
public final class VectorIndex {

	private static final Logger LOG = Logger.getLogger(VectorIndex.class.getName());

	/** The folder below the {@link SpaceIndex#getDirectory() index folder} the vectors are kept in. */
	public static final String DIRECTORY_NAME = "vectors";

	/** The field of the image vector of a photograph. */
	public static final String IMAGE = "v_img";

	/** The field of the text vector of a photograph. */
	public static final String TEXT = "v_txt";

	/** The layout of the documents this build writes; an index of another one is emptied. */
	public static final int FORMAT = 1;

	/** How long after a commit the next one waits at least. */
	static final long COMMIT_MILLIS = 2000;

	/** The Lucene RAM buffer when nobody set one, in bytes. */
	static final long DEFAULT_BUFFER = 4L * 1024 * 1024;

	private static final String KEY = "key";

	private static final String ALBUM = "album";

	private static final String PHOTO = "photo";

	private static final String HASH = "hash";

	private static final String MARKER = "marker";

	private static final String MARKER_STAMP = "stamp";

	private static final String FORMAT_KEY = "format";

	private static final String GENERATION_KEY = "generation";

	private static final String STAMP_KEY = "stamp";

	/** The open indexes of this process, by folder. */
	private static final Map<Path, VectorIndex> OPEN = new HashMap<>();

	/** A photograph with its vectors, as {@link #write} adds it. */
	public record Entry(long photo, long hash, float[] image, float[] text) {
		// A tuple.
	}

	/** A photograph found near a query, with how near: the weighted sum of its cosines. */
	public record Hit(long photo, float score) {
		// A pair.
	}

	private final Path _folder;

	private final String _label;

	private Directory _directory;

	private IndexWriter _writer;

	private SearcherManager _searchers;

	private int _references;

	private boolean _persistent;

	private String _generation = "";

	private String _stamp = "";

	private boolean _dirty;

	private long _lastCommit;

	private VectorIndex(Path folder, String label) {
		_folder = folder;
		_label = label;
	}

	/**
	 * The vector index in the given folder, opened, created or rebuilt where needed; never fails,
	 * see {@link SpaceIndex}. One per folder and process, counted, like the text index.
	 *
	 * @param folder
	 *        Where the index lives, <code>null</code> for one in memory.
	 */
	static VectorIndex acquire(Path folder, String label) {
		if (folder == null) {
			VectorIndex result = new VectorIndex(null, label);
			result.openInMemory();
			result._references = 1;
			return result;
		}
		Path key = folder.toAbsolutePath().normalize();
		synchronized (OPEN) {
			VectorIndex result = OPEN.get(key);
			if (result == null) {
				result = new VectorIndex(key, label);
				result.open();
				if (result._persistent) {
					OPEN.put(key, result);
				}
			}
			result._references++;
			return result;
		}
	}

	/** Whether this index is kept on disk. */
	public synchronized boolean isPersistent() {
		return _persistent;
	}

	/**
	 * Ties this index to the H2 database of the given generation; an index of another generation or
	 * {@link #FORMAT} is emptied, see {@link VectorIndex}.
	 *
	 * @return Whether what it holds was kept.
	 */
	synchronized boolean attach(String generation) {
		if (_writer == null) {
			return false;
		}
		Map<String, String> data = commitData();
		if (generation.equals(_generation)
			|| (generation.equals(data.get(GENERATION_KEY)) && String.valueOf(FORMAT).equals(data.get(FORMAT_KEY)))) {
			_generation = generation;
			_stamp = data.getOrDefault(STAMP_KEY, _stamp);
			return true;
		}
		try {
			if (!data.isEmpty()) {
				LOG.info("The vector index of '" + _label + "' belongs to another index; it is filled again.");
			}
			_writer.deleteAll();
			_generation = generation;
			_stamp = "";
			commit();
			_searchers.maybeRefreshBlocking();
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot empty the vector index of '" + _label + "': " + ex.getMessage(), ex);
		}
		return false;
	}

	/**
	 * The stamp of the vectors this index holds, the empty string before any: what made them (the
	 * embedder's model) and the texts they describe, see {@link VectorIndex}.
	 */
	public synchronized String stamp() {
		return _stamp;
	}

	/**
	 * Drops every vector, and holds those of the given stamp from now on: a new model is not
	 * comparable to the old one in a single number.
	 */
	public synchronized void reset(String stamp) {
		if (_writer == null) {
			return;
		}
		try {
			_writer.deleteAll();
			_stamp = stamp;
			commit();
			_searchers.maybeRefreshBlocking();
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot empty the vector index of '" + _label + "': " + ex.getMessage(), ex);
		}
	}

	/** Lets Lucene buffer the given number of bytes of new documents before it writes a segment. */
	synchronized void setBuffer(long bytes) {
		if (_writer != null) {
			_writer.getConfig().setRAMBufferSizeMB(Math.max(1.0, bytes / (1024.0 * 1024.0)));
		}
	}

	// --- Feeding. ---

	/**
	 * The stamp the album at the given path was last fed with, see {@link #write}; <code>0</code>
	 * for an album never fed (or whose feed was not committed before a kill).
	 */
	public long marker(String path) {
		try {
			return search(searcher -> {
				TopDocs found = searcher.search(new TermQuery(new Term(MARKER, path)), 1);
				if (found.scoreDocs.length == 0) {
					return Long.valueOf(0);
				}
				int doc = found.scoreDocs[0].doc;
				List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
				LeafReaderContext leaf = leaves.get(ReaderUtil.subIndex(doc, leaves));
				NumericDocValues stamps = leaf.reader().getNumericDocValues(MARKER_STAMP);
				return Long.valueOf(stamps != null && stamps.advanceExact(doc - leaf.docBase) ? stamps.longValue() : 0);
			}).longValue();
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot read the vector index of '" + _label + "': " + ex.getMessage(), ex);
			return 0;
		}
	}

	/** The photographs this index holds of the album at the given path, each with its hash. */
	public Map<Long, Long> photos(String path) throws IOException {
		return search(searcher -> {
			Map<Long, Long> result = new HashMap<>();
			Query query = new TermQuery(new Term(ALBUM, path));
			Weight weight = searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1f);
			for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
				Scorer scorer = weight.scorer(leaf);
				if (scorer == null) {
					continue;
				}
				Bits live = leaf.reader().getLiveDocs();
				NumericDocValues photos = leaf.reader().getNumericDocValues(PHOTO);
				NumericDocValues hashes = leaf.reader().getNumericDocValues(HASH);
				DocIdSetIterator docs = scorer.iterator();
				for (int doc = docs.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = docs.nextDoc()) {
					if ((live == null || live.get(doc)) && photos != null && photos.advanceExact(doc)) {
						long hash = hashes != null && hashes.advanceExact(doc) ? hashes.longValue() : 0;
						result.put(Long.valueOf(photos.longValue()), Long.valueOf(hash));
					}
				}
			}
			return result;
		});
	}

	/**
	 * Feeds the album at the given path: the photographs of the given ids go, the given entries are
	 * added (or replace those of their ids), and its marker is set to the given stamp, all in the same
	 * commit. Searchable when this returns.
	 */
	public synchronized void write(String path, long stamp, Collection<Long> removed, Collection<Entry> added)
			throws IOException {
		if (_writer == null) {
			throw new IOException("The vector index of '" + _label + "' is not open.");
		}
		for (Long id : removed) {
			_writer.deleteDocuments(new Term(KEY, id.toString()));
		}
		for (Entry entry : added) {
			String key = Long.toString(entry.photo());
			float[] image = entry.image() == null ? null : normalized(entry.image());
			float[] text = entry.text() == null ? null : normalized(entry.text());
			if (image == null || text == null) {
				// A vector of nothing points nowhere: no photograph to find by it.
				_writer.deleteDocuments(new Term(KEY, key));
				continue;
			}
			Document document = new Document();
			document.add(new StringField(KEY, key, Field.Store.NO));
			document.add(new StringField(ALBUM, path, Field.Store.NO));
			document.add(new NumericDocValuesField(PHOTO, entry.photo()));
			document.add(new NumericDocValuesField(HASH, entry.hash()));
			document.add(new KnnFloatVectorField(IMAGE, image, VectorSimilarityFunction.DOT_PRODUCT));
			document.add(new KnnFloatVectorField(TEXT, text, VectorSimilarityFunction.DOT_PRODUCT));
			_writer.updateDocument(new Term(KEY, key), document);
		}
		Document marker = new Document();
		marker.add(new StringField(MARKER, path, Field.Store.NO));
		marker.add(new NumericDocValuesField(MARKER_STAMP, stamp));
		_writer.updateDocument(new Term(MARKER, path), marker);
		changed();
	}

	/** Forgets the album at the given path and every album below it, its vectors and its marker. */
	public synchronized void removeTree(String path) {
		if (_writer == null) {
			return;
		}
		try {
			if (path.isEmpty()) {
				_writer.deleteDocuments(new MatchAllDocsQuery());
			} else {
				_writer.deleteDocuments(new Term(ALBUM, path), new Term(MARKER, path));
				_writer.deleteDocuments(new PrefixQuery(new Term(ALBUM, path + "/")),
					new PrefixQuery(new Term(MARKER, path + "/")));
			}
			changed();
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot write the vector index of '" + _label + "': " + ex.getMessage(), ex);
		}
	}

	/** The paths of every album this index holds a marker of, sorted. */
	public List<String> albums() throws IOException {
		return albums("");
	}

	/** The paths of the albums at or below the given path this index holds a marker of, sorted. */
	public List<String> albums(String path) throws IOException {
		String below = path + "/";
		BytesRef end = new BytesRef(below);
		return search(searcher -> {
			List<String> result = new ArrayList<>();
			Terms terms = MultiTerms.getTerms(searcher.getIndexReader(), MARKER);
			if (terms == null) {
				return result;
			}
			TermsEnum each = terms.iterator();
			if (each.seekCeil(new BytesRef(path)) == TermsEnum.SeekStatus.END) {
				return result;
			}
			for (BytesRef term = each.term(); term != null; term = each.next()) {
				String known = term.utf8ToString();
				if (!path.isEmpty() && !known.equals(path) && !known.startsWith(below)) {
					if (term.compareTo(end) > 0) {
						// Past everything below: "a b" sorts between "a" and "a/".
						break;
					}
					continue;
				}
				// A term outlives its documents until a merge: only a marker still there counts.
				if (searcher.count(new TermQuery(new Term(MARKER, BytesRef.deepCopyOf(term)))) > 0) {
					result.add(known);
				}
			}
			return result;
		});
	}

	private void changed() throws IOException {
		_dirty = true;
		_searchers.maybeRefreshBlocking();
		if (System.currentTimeMillis() - _lastCommit >= COMMIT_MILLIS) {
			commit();
		}
	}

	/** Commits what was written, if anything; what a test does before it kills a process. */
	public synchronized void flush() {
		if (_writer == null || !_dirty) {
			return;
		}
		try {
			commit();
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot commit the vector index of '" + _label + "': " + ex.getMessage(), ex);
		}
	}

	// --- Asking. ---

	/** How many photographs this index holds vectors of. */
	public int size() {
		try {
			return search(searcher -> Integer.valueOf(searcher.count(new FieldExistsQuery(PHOTO)))).intValue();
		} catch (IOException ex) {
			return 0;
		}
	}

	/** The number of dimensions of the vectors held, <code>0</code> for none. */
	public int dimension() {
		try {
			return search(searcher -> {
				FieldInfo info = FieldInfos.getMergedFieldInfos(searcher.getIndexReader()).fieldInfo(IMAGE);
				return Integer.valueOf(info == null ? 0 : info.getVectorDimension());
			}).intValue();
		} catch (IOException ex) {
			return 0;
		}
	}

	/** The vector of the given field of the photograph of the given id, <code>null</code> for none. */
	public float[] vector(long photo, String field) throws IOException {
		return search(searcher -> {
			TopDocs found = searcher.search(new TermQuery(new Term(KEY, Long.toString(photo))), 1);
			if (found.scoreDocs.length == 0) {
				return null;
			}
			Map<Integer, float[]> vectors = vectors(searcher, List.of(Integer.valueOf(found.scoreDocs[0].doc)), field);
			return vectors.get(Integer.valueOf(found.scoreDocs[0].doc));
		});
	}

	/**
	 * The <code>k</code> photographs nearest to the given query among those of the given ids, the
	 * nearest first.
	 *
	 * <p>
	 * <b>Filtered inside the query.</b> The ids are the filter of each {@link KnnFloatVectorQuery}:
	 * the graph is walked through the photographs the filter accepts alone (or every accepted one
	 * is compared, where they are few), so that <code>k</code> photographs are found as long as
	 * <code>k</code> are allowed &mdash; never the nearest of all, cut down afterwards to the few
	 * the caller may see.
	 * </p>
	 *
	 * <p>
	 * With both weights, each field finds its <code>k</code> nearest, and every photograph found by
	 * either is scored by both: <code>imageWeight · cos(q, v_img) + textWeight · cos(q, v_txt)</code>,
	 * exactly. A photograph that is near by the weighted sum and among the nearest of neither field
	 * is missed; the weights are to be calibrated anyway (issue #257).
	 * </p>
	 *
	 * @param query
	 *        A vector of the model this index holds ({@link #stamp()}); normalised here. One of
	 *        another number of dimensions finds nothing.
	 * @param imageWeight
	 *        The weight of {@link #IMAGE}, <code>0</code> to leave it out.
	 * @param textWeight
	 *        The weight of {@link #TEXT}, <code>0</code> to leave it out.
	 * @param allowed
	 *        The ids of the photographs that may be found; none found outside them.
	 */
	public List<Hit> nearest(float[] query, float imageWeight, float textWeight, int k, BitSet allowed)
			throws IOException {
		List<Hit> result = new ArrayList<>();
		if (query == null || k <= 0 || allowed.isEmpty() || (imageWeight <= 0 && textWeight <= 0)) {
			return result;
		}
		float[] target = normalized(query);
		if (target == null) {
			return result;
		}
		Query filter = new Allowed(allowed);
		return search(searcher -> {
			FieldInfo info = FieldInfos.getMergedFieldInfos(searcher.getIndexReader()).fieldInfo(IMAGE);
			if (info == null || info.getVectorDimension() != target.length) {
				return result;
			}
			LinkedHashMap<Integer, float[]> candidates = new LinkedHashMap<>();
			for (String field : new String[] { IMAGE, TEXT }) {
				if ((IMAGE.equals(field) ? imageWeight : textWeight) <= 0) {
					continue;
				}
				TopDocs found = searcher.search(new KnnFloatVectorQuery(field, target, k, filter), k);
				for (ScoreDoc doc : found.scoreDocs) {
					candidates.put(Integer.valueOf(doc.doc), null);
				}
			}
			List<Integer> docs = new ArrayList<>(candidates.keySet());
			Map<Integer, Float> scores = new HashMap<>();
			for (String field : new String[] { IMAGE, TEXT }) {
				float weight = IMAGE.equals(field) ? imageWeight : textWeight;
				if (weight <= 0) {
					continue;
				}
				for (Map.Entry<Integer, float[]> entry : vectors(searcher, docs, field).entrySet()) {
					scores.merge(entry.getKey(), Float.valueOf(weight * dot(target, entry.getValue())), Float::sum);
				}
			}
			Map<Integer, Long> photos = photoIds(searcher, docs);
			for (Integer doc : docs) {
				Long photo = photos.get(doc);
				Float score = scores.get(doc);
				if (photo != null && score != null) {
					result.add(new Hit(photo.longValue(), score.floatValue()));
				}
			}
			result.sort(Comparator.comparingDouble((Hit hit) -> -hit.score()).thenComparingLong(Hit::photo));
			return result.size() > k ? new ArrayList<>(result.subList(0, k)) : result;
		});
	}

	/**
	 * The documents of the photographs of the given ids: the filter of a vector query, see
	 * {@link VectorIndex#nearest}. Read off the doc values of each segment, one look per document;
	 * a set of points of the ids (<code>LongPoint.newSetQuery</code>) was tried and costs all the ids
	 * in every segment: 600 ms for 100 000 allowed in 81 segments, against a few here.
	 */
	private static final class Allowed extends Query {

		private final BitSet _ids;

		Allowed(BitSet ids) {
			_ids = ids;
		}

		@Override
		public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
			return new ConstantScoreWeight(this, boost) {
				@Override
				public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
					NumericDocValues photos = context.reader().getNumericDocValues(PHOTO);
					if (photos == null) {
						return null;
					}
					TwoPhaseIterator matching = new TwoPhaseIterator(photos) {
						@Override
						public boolean matches() throws IOException {
							long id = photos.longValue();
							return id >= 0 && id <= Integer.MAX_VALUE && _ids.get((int) id);
						}

						@Override
						public float matchCost() {
							return 1;
						}
					};
					return new DefaultScorerSupplier(new ConstantScoreScorer(score(), scoreMode, matching));
				}

				@Override
				public boolean isCacheable(LeafReaderContext context) {
					// Asked once: the set is the caller's.
					return false;
				}
			};
		}

		@Override
		public String toString(String field) {
			return "allowed(" + _ids.cardinality() + ")";
		}

		@Override
		public void visit(QueryVisitor visitor) {
			visitor.visitLeaf(this);
		}

		@Override
		public boolean equals(Object other) {
			return this == other;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this);
		}
	}

	/** The vectors of the given field of the given documents, by document. */
	private static Map<Integer, float[]> vectors(IndexSearcher searcher, List<Integer> docs, String field)
			throws IOException {
		Map<Integer, float[]> result = new HashMap<>();
		List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
		for (Map.Entry<Integer, List<Integer>> byLeaf : byLeaf(leaves, docs).entrySet()) {
			LeafReaderContext leaf = leaves.get(byLeaf.getKey().intValue());
			FloatVectorValues values = leaf.reader().getFloatVectorValues(field);
			if (values == null) {
				continue;
			}
			KnnVectorValues.DocIndexIterator iterator = values.iterator();
			for (Integer doc : byLeaf.getValue()) {
				int local = doc.intValue() - leaf.docBase;
				int at = iterator.docID() >= local ? iterator.docID() : iterator.advance(local);
				if (at == local) {
					result.put(doc, values.vectorValue(iterator.index()).clone());
				}
			}
		}
		return result;
	}

	/** The ids of the photographs of the given documents, by document. */
	private static Map<Integer, Long> photoIds(IndexSearcher searcher, List<Integer> docs) throws IOException {
		Map<Integer, Long> result = new HashMap<>();
		List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
		for (Map.Entry<Integer, List<Integer>> byLeaf : byLeaf(leaves, docs).entrySet()) {
			LeafReaderContext leaf = leaves.get(byLeaf.getKey().intValue());
			NumericDocValues photos = leaf.reader().getNumericDocValues(PHOTO);
			if (photos == null) {
				continue;
			}
			for (Integer doc : byLeaf.getValue()) {
				if (photos.advanceExact(doc.intValue() - leaf.docBase)) {
					result.put(doc, Long.valueOf(photos.longValue()));
				}
			}
		}
		return result;
	}

	/** The given documents by the leaf they are in, each list in ascending order. */
	private static Map<Integer, List<Integer>> byLeaf(List<LeafReaderContext> leaves, List<Integer> docs) {
		Map<Integer, List<Integer>> result = new TreeMap<>();
		List<Integer> sorted = new ArrayList<>(docs);
		sorted.sort(null);
		for (Integer doc : sorted) {
			result.computeIfAbsent(Integer.valueOf(ReaderUtil.subIndex(doc.intValue(), leaves)), key -> new ArrayList<>())
				.add(doc);
		}
		return result;
	}

	private static float dot(float[] left, float[] right) {
		if (right == null || right.length != left.length) {
			return 0;
		}
		double result = 0;
		for (int n = 0; n < left.length; n++) {
			result += left[n] * right[n];
		}
		return (float) result;
	}

	/** A copy of the given vector of length 1, <code>null</code> for a vector of none. */
	static float[] normalized(float[] vector) {
		double sum = 0;
		for (float value : vector) {
			sum += value * value;
		}
		if (!(sum > 0) || Double.isInfinite(sum)) {
			return null;
		}
		double length = Math.sqrt(sum);
		float[] result = new float[vector.length];
		for (int n = 0; n < vector.length; n++) {
			result[n] = (float) (vector[n] / length);
		}
		return result;
	}

	/** Reads the index. */
	private interface Read<T> {
		T run(IndexSearcher searcher) throws IOException;
	}

	private <T> T search(Read<T> read) throws IOException {
		SearcherManager searchers;
		synchronized (this) {
			searchers = _searchers;
		}
		if (searchers == null) {
			throw new IOException("The vector index of '" + _label + "' is not open.");
		}
		IndexSearcher searcher = searchers.acquire();
		try {
			return read.run(searcher);
		} finally {
			searchers.release(searcher);
		}
	}

	/** Gives back one reference; the last closes the index, committing what was written. */
	void release() {
		if (_folder != null) {
			synchronized (OPEN) {
				if (--_references > 0) {
					return;
				}
				OPEN.remove(_folder);
			}
		}
		synchronized (this) {
			flush();
			disconnect();
		}
	}

	// --- Opening. ---

	private void open() {
		try {
			connect(FSDirectory.open(_folder));
			_persistent = true;
			return;
		} catch (LockObtainFailedException ex) {
			LOG.warning("The vector index of '" + _label + "' is used by another process (" + ex.getMessage()
				+ "); this server keeps its own in memory.");
			disconnect();
			openInMemory();
			return;
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "The vector index of '" + _label + "' cannot be opened (" + ex.getMessage()
				+ "); it is deleted and filled again.", ex);
			disconnect();
		}
		try {
			deleteFiles();
			connect(FSDirectory.open(_folder));
			_persistent = true;
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "The vector index of '" + _label + "' cannot be created (" + ex.getMessage()
				+ "); this server keeps it in memory.", ex);
			disconnect();
			openInMemory();
		}
	}

	private void openInMemory() {
		try {
			connect(new ByteBuffersDirectory());
			_persistent = false;
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.SEVERE, "The vector index of '" + _label + "' cannot be opened at all: " + ex.getMessage(), ex);
			disconnect();
		}
	}

	private void connect(Directory directory) throws IOException {
		_directory = directory;
		IndexWriterConfig config = new IndexWriterConfig();
		config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
		config.setRAMBufferSizeMB(DEFAULT_BUFFER / (1024.0 * 1024.0));
		config.setCommitOnClose(false);
		_writer = new IndexWriter(directory, config);
		_searchers = new SearcherManager(_writer, null);
		_stamp = commitData().getOrDefault(STAMP_KEY, "");
		_lastCommit = System.currentTimeMillis();
	}

	private void disconnect() {
		if (_searchers != null) {
			try {
				_searchers.close();
			} catch (IOException ex) {
				LOG.log(Level.FINE, "Closing the searchers failed: " + ex.getMessage(), ex);
			}
			_searchers = null;
		}
		if (_writer != null) {
			try {
				_writer.close();
			} catch (IOException | RuntimeException ex) {
				LOG.log(Level.FINE, "Closing the vector writer failed: " + ex.getMessage(), ex);
			}
			_writer = null;
		}
		if (_directory != null) {
			try {
				_directory.close();
			} catch (IOException ex) {
				LOG.log(Level.FINE, "Closing the vector folder failed: " + ex.getMessage(), ex);
			}
			_directory = null;
		}
	}

	private void deleteFiles() throws IOException {
		if (!Files.isDirectory(_folder)) {
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(_folder)) {
			for (Path file : files) {
				Files.deleteIfExists(file);
			}
		}
	}

	private Map<String, String> commitData() {
		Map<String, String> result = new HashMap<>();
		Iterable<Map.Entry<String, String>> data = _writer.getLiveCommitData();
		if (data != null) {
			for (Map.Entry<String, String> entry : data) {
				result.put(entry.getKey(), entry.getValue());
			}
		}
		return result;
	}

	private void commit() throws IOException {
		Map<String, String> data = new HashMap<>();
		data.put(FORMAT_KEY, String.valueOf(FORMAT));
		data.put(GENERATION_KEY, _generation);
		data.put(STAMP_KEY, _stamp);
		_writer.setLiveCommitData(data.entrySet());
		_writer.commit();
		_dirty = false;
		_lastCommit = System.currentTimeMillis();
	}
}
