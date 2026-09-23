/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.CacheRefresh;
import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.PreviewException;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.util.Orientations;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * Who is in the photographs of one space, see issue #124.
 *
 * <p>
 * One index per space, exactly like the {@link de.haumacher.imageServer.upload.HashIndex} of issue
 * #118, and switched on per space: nothing here happens at all unless that space's
 * <code>.valbum/space.json</code> says <code>faces: on</code>. Processing the biometrics of one's
 * family is the administrator's decision.
 * </p>
 *
 * <h2>What is looked at</h2>
 *
 * <p>
 * Photographs, never videos (issue #123 decided that, poster frame included), and always the
 * <em>preview</em> of {@link PreviewCache}, never the original: a 600 px raster is what a detector
 * wants anyway, the preview is usually already there, and a machine the size of a Raspberry Pi can
 * carry it. Where the preview is missing it is made through the ordinary path, taking one of the
 * preview permits of issue #69 exactly as a request for a thumbnail would, so that indexing cannot
 * outrun the machine.
 * </p>
 *
 * <p>
 * Where the preview shows a photograph smaller than it is, the original is asked twice more, in
 * pieces and never as a whole: around a face too small on the preview (issue #140), and — where the
 * preview shows no face at all — in its centre half at the preview's own size (issue #163).
 * </p>
 *
 * <h2>When it happens</h2>
 *
 * <p>
 * Never on the request path. One thread per space at {@link Thread#MIN_PRIORITY}, one photograph
 * at a time, like the transcoder of issue #74 and deliberately independent of it: work is queued
 * when an album is listed that is not fully indexed, and once at start-up over the whole space by
 * {@link #start()} — which only the wiring of a real server calls, never a test's constructor, the
 * rule of issue #118. A photograph the detector could not read is remembered for the lifetime of
 * the process, like a failed transcode, so that a broken file does not keep the machine busy.
 * </p>
 *
 * <h2>What is written</h2>
 *
 * <p>
 * The album's {@value PreviewCache#CACHE_DIRECTORY_NAME}, and nothing else, ever: the detections in
 * {@link FaceCache} and the crops of <code>?type=face</code> beside them. No original is opened for
 * writing and no sidecar of the album is touched — what goes into <code>index.json</code> is the
 * author's, and a detection is not.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class FaceIndex {

	private static final Logger LOG = Logger.getLogger(FaceIndex.class.getName());

	/** The name every cached face crop begins with, inside {@value PreviewCache#CACHE_DIRECTORY_NAME}. */
	public static final String CROP_PREFIX = "face-";

	/** The extension of every cached face crop. */
	public static final String CROP_EXTENSION = "jpg";

	/**
	 * What every crop name of issue #141 begins with, behind the photograph's own name.
	 *
	 * <p>
	 * Not a digit, so that such a name can never be read as one of issue #124's indices, and
	 * itself a hexadecimal digit, so that the whole token is one alphabet.
	 * </p>
	 */
	static final char CROP_TOKEN_MARK = 'f';

	/** How long a crop token is, the {@link #CROP_TOKEN_MARK} included. */
	static final int CROP_TOKEN_LENGTH = 13;

	/**
	 * How much space is left around a face in a crop, as a fraction of the box.
	 *
	 * <p>
	 * A box that sits exactly on the eyes and the chin is not a portrait; a quarter of the box on
	 * every side makes a crop somebody recognises a person in, which is what the editor of issue
	 * #126 shows.
	 * </p>
	 */
	static final double CROP_MARGIN = 0.25;

	/**
	 * How small a crop cut from the preview may be before it is cut from the original instead, in
	 * pixels of its shorter side, see issue #140.
	 *
	 * <p>
	 * The editor of issue #126 draws a crop on a tile of 96&nbsp;logical pixels, which on a phone
	 * is two to three hundred device pixels; below this a crop from the 600&nbsp;px preview is
	 * shown upscaled and blurred, of a face the file itself describes perfectly well.
	 * </p>
	 */
	static final int CROP_MIN_PIXELS = 256;

	/**
	 * How large a crop cut from the original is kept, in pixels of its shorter side.
	 *
	 * <p>
	 * A face 4000&nbsp;px across must not become a 4000&nbsp;px crop: this is a thumbnail of a
	 * face, cached beside the album, and twice what any tile shows is as much as it can need.
	 * </p>
	 */
	static final int CROP_MAX_PIXELS = 512;

	/**
	 * How large the region is that a marked face is looked for in, at least, as a fraction of the
	 * picture's long side, see issue #155.
	 *
	 * <p>
	 * A square of a fifth of the long side, centred on what was marked: on a 6000&nbsp;px photograph
	 * 1200&nbsp;px, which holds a whole head of anybody not standing in a crowd, so that a click in
	 * the middle of a face — which the app sends as a small box — gives the detector the face
	 * <em>and</em> the room around it that it needs to find one at all. A box drawn larger than that
	 * is looked at with its own size around it on every side, as a candidate of issue #140 is.
	 * </p>
	 */
	static final double SEARCH_MIN_FRACTION = 0.2;

	/**
	 * Where the second look of issue #163 looks, as a fraction of the picture: its centre half, from
	 * a quarter to three quarters on each axis.
	 *
	 * <p>
	 * Symmetric about the middle, so it is the same box in the raw raster of the file and in the
	 * picture as it is shown, whatever the EXIF orientation says.
	 * </p>
	 */
	static final double CENTRE_FROM = 0.25;

	/** The far edge of {@link #CENTRE_FROM}'s box. */
	static final double CENTRE_TO = 0.75;

	private final Path _root;

	private final boolean _enabled;

	/** The albums whose indexing is queued or running, by absolute path. */
	private final ConcurrentHashMap<String, Boolean> _queued = new ConcurrentHashMap<>();

	/**
	 * The photographs the detector could not read, by absolute path.
	 *
	 * <p>
	 * Remembered for the lifetime of the process, exactly like a failed transcode: a file that
	 * cannot be decoded will not become decodable, and without this every listing of its album
	 * would try it again.
	 * </p>
	 */
	private final ConcurrentHashMap<String, String> _failed = new ConcurrentHashMap<>();

	/**
	 * One lock per album folder, for the two writers of its {@link FaceCache}, see issue #155.
	 *
	 * <p>
	 * The walk reads an album's cache, describes photograph after photograph and writes it back at
	 * the end; a marked region is added on the request path in between. The walk therefore reads
	 * the file again under this lock before it writes, and keeps what was marked meanwhile, see
	 * {@link #keepMarked(List, List)}.
	 * </p>
	 */
	private final ConcurrentHashMap<String, Object> _locks = new ConcurrentHashMap<>();

	private Object lockOf(File folder) {
		return _locks.computeIfAbsent(folder.getAbsolutePath(), key -> new Object());
	}

	/**
	 * Who the people of this space look like, see issue #127.
	 *
	 * <p>
	 * It lives here because it lives on this walk: the prototypes are read from the very album
	 * caches this index writes, on the very thread that writes them, and there is one of each per
	 * space.
	 * </p>
	 */
	private final Recognition _recognition = new Recognition();

	/** Who the people of this space look like, see issue #127. */
	public Recognition recognition() {
		return _recognition;
	}

	private ExecutorService _indexer;

	private CountDownLatch _pass;

	private volatile boolean _stopped;

	/**
	 * Creates the index of one space.
	 *
	 * @param root
	 *        The root folder of the space.
	 * @param enabled
	 *        What the space's <code>space.json</code> says, see
	 *        {@link de.haumacher.imageServer.auth.SpaceStore.Config#isFacesEnabled()}. Nothing is
	 *        loaded, queued or written while this is <code>false</code>.
	 */
	public FaceIndex(Path root, boolean enabled) {
		_root = root.toAbsolutePath().normalize();
		_enabled = enabled;
	}

	/**
	 * Whether this space detects faces at all.
	 *
	 * <p>
	 * The space asked for it <em>and</em> the machine can do it: a platform whose OpenCV does not
	 * load says so in one line at start-up and the index stays off, see
	 * {@link FaceDetection#unavailability()}. What <code>?type=auth</code> answers as
	 * {@link de.haumacher.imageServer.shared.model.AuthInfo#isFaces()}.
	 * </p>
	 */
	public boolean isEnabled() {
		return _enabled && FaceDetection.unavailability() == null;
	}

	/** Whether the space asked for faces, whatever the machine can do. */
	public boolean isRequested() {
		return _enabled;
	}

	/**
	 * Why this space asked for faces and gets none, <code>null</code> when there is no such reason.
	 */
	public String unavailability() {
		return _enabled ? FaceDetection.unavailability() : null;
	}

	// --- The background pass. ---

	/**
	 * Starts detecting in the background, see {@link FaceIndex}.
	 *
	 * <p>
	 * Called once, by the wiring that starts a real server — never by a test that builds a servlet
	 * over a fixture and asks it one question, the rule of issue #118.
	 * </p>
	 */
	public synchronized void start() {
		if (!isEnabled() || _indexer != null) {
			return;
		}
		_indexer = executor();
		_pass = new CountDownLatch(1);
		CountDownLatch pass = _pass;
		try {
			_indexer.execute(() -> {
				try {
					indexNow();
				} finally {
					pass.countDown();
				}
			});
		} catch (RejectedExecutionException ex) {
			pass.countDown();
		}
	}

	private ExecutorService executor() {
		return Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "face-index " + _root.getFileName());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
	}

	/** Waits for the pass started by {@link #start()}; for the tests. */
	public boolean awaitPass(long timeoutMillis) throws InterruptedException {
		CountDownLatch pass;
		synchronized (this) {
			pass = _pass;
		}
		return pass == null || pass.await(timeoutMillis, TimeUnit.MILLISECONDS);
	}

	/** Waits until every album queued so far has been looked at; for the tests. */
	public boolean awaitQueue(long timeoutMillis) throws InterruptedException {
		ExecutorService indexer;
		synchronized (this) {
			indexer = _indexer;
		}
		if (indexer == null) {
			return true;
		}
		try {
			indexer.submit(() -> {
				// Nothing; being run at all is the answer.
			}).get(timeoutMillis, TimeUnit.MILLISECONDS);
			return true;
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
			return false;
		} catch (RejectedExecutionException ex) {
			return true;
		}
	}

	/** Walks the whole space in the calling thread; what {@link #start()} runs in the background. */
	public void indexNow() {
		if (!isEnabled()) {
			return;
		}
		for (File folder : tree(_root.toFile())) {
			if (Thread.currentThread().isInterrupted() || _stopped) {
				LOG.info("Face indexing of '" + _root + "' stopped.");
				return;
			}
			indexFolder(folder);
		}
	}

	/**
	 * Queues the given album for indexing, unless it is done or the queue already holds it.
	 *
	 * <p>
	 * What a listing of an album does. It never waits: the answer carries the faces that are known
	 * and {@link AlbumInfo#isFacesPending() says} that more may come, exactly as a request for a
	 * video rendition is answered <code>202</code> and comes back (issue #74).
	 * </p>
	 */
	public void queue(File folder) {
		if (!isEnabled() || _stopped) {
			return;
		}
		ExecutorService indexer;
		synchronized (this) {
			if (_indexer == null) {
				_indexer = executor();
			}
			indexer = _indexer;
		}
		String key = folder.getAbsolutePath();
		if (_queued.putIfAbsent(key, Boolean.TRUE) != null) {
			return;
		}
		try {
			indexer.execute(() -> {
				try {
					indexFolder(folder);
				} finally {
					_queued.remove(key);
				}
			});
		} catch (RejectedExecutionException ex) {
			// The server is stopping; the next start looks at this album.
			_queued.remove(key);
		}
	}

	/**
	 * Detects in every photograph of the given album that nothing is known about yet, then groups
	 * the album's faces.
	 */
	void indexFolder(File folder) {
		File[] images = folder.listFiles(f -> f.isFile() && ResourceCache.isImage(f));
		if (images == null || images.length == 0) {
			return;
		}
		java.util.Arrays.sort(images, (left, right) -> left.getName().compareTo(right.getName()));

		HashCache hashes = new HashCache(folder);
		Map<String, String> hashByName;
		try {
			hashByName = hashes.hashByName();
			hashes.flush();
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot hash '" + folder.getAbsolutePath() + "': " + ex.getMessage(), ex);
			return;
		}

		FaceCache cache = new FaceCache(folder);
		Set<String> present = new LinkedHashSet<>();
		boolean changed = false;
		boolean complete = true;
		for (File image : images) {
			if (Thread.currentThread().isInterrupted() || _stopped) {
				complete = false;
				break;
			}
			if (!isPhotograph(image)) {
				// A video is never handed to the detector, see issue #123.
				continue;
			}
			String hash = hashByName.get(image.getName());
			if (hash == null) {
				continue;
			}
			present.add(hash);
			if (_failed.containsKey(image.getAbsolutePath())) {
				continue;
			}
			if (cache.knows(hash) && !unrefined(image, cache.facesOf(hash))
				&& !unsearched(cache, image, hash)) {
				// Looked at, and nothing a second look could improve; a photograph whose faces were
				// described from the preview alone is looked at again, see issue #140, and so is one
				// in which nothing was found before the centre was looked at, see issue #163.
				rememberExif(cache, image, hash);
				continue;
			}
			try {
				// A face somebody pointed at is kept beside the new description, see issue #155.
				describe(cache, hash, image, cache.facesOf(hash));
				changed = true;
			} catch (IOException | RuntimeException ex) {
				String reason = FaceDetection.reason(ex);
				LOG.log(Level.WARNING,
					"Cannot look for faces in '" + image.getAbsolutePath() + "': " + reason, ex);
				_failed.put(image.getAbsolutePath(), reason);
			}
		}

		// What the album no longer holds is nobody's business; a hash that came back finds its
		// faces again, which is why the entries are keyed by contents and not by name. Only after a
		// pass that saw every photograph: a pass stopped half-way knows nothing of the rest, and
		// forgetting what it did not reach would make the next pass start the album over.
		if (complete) {
			cache.retain(present);
		}

		synchronized (lockOf(folder)) {
			// What was marked on the request path while this pass ran is in the file, not in what
			// this pass read at its start, see issue #155.
			FaceCache now = new FaceCache(folder);
			for (String hash : new ArrayList<>(cache.hashes())) {
				List<FaceCache.Face> ours = cache.facesOf(hash);
				List<FaceCache.Face> merged = keepMarked(ours, now.facesOf(hash));
				if (merged.size() != ours.size()) {
					cache.put(hash, merged);
					changed = true;
				}
			}
			if (changed) {
				List<FaceCache.Face> faces = cache.allFaces();
				Clustering.cluster(faces);
				cache.clustered();
			}
			try {
				cache.flush();
			} catch (IOException ex) {
				LOG.log(Level.WARNING,
					"Cannot write the face cache of '" + folder.getAbsolutePath() + "': " + ex.getMessage(), ex);
			}
		}

		// The one walk of issue #127 as well: whoever was confirmed in this album is now described
		// by numbers this pass has just made sure are there.
		_recognition.observe(folder);
	}

	/**
	 * The given faces, and behind them every face of the other list that somebody pointed at and
	 * that none of them is, see issue #155.
	 *
	 * <p>
	 * A face found in a marked region is not something a pass over the preview finds again, so it
	 * must not be lost when the photograph is described anew (the walk of issue #140 does that) or
	 * when a pass writes back a cache it read before the region was marked. Where the new
	 * description finds the same face itself — the overlap of {@link FaceTags#IOU_MATCH} — the new
	 * description stands.
	 * </p>
	 */
	static List<FaceCache.Face> keepMarked(List<FaceCache.Face> faces, List<FaceCache.Face> before) {
		List<FaceCache.Face> result = new ArrayList<>(faces);
		for (FaceCache.Face old : before) {
			if (!old.isMarked() || indexOfBox(result, old.getX(), old.getY(), old.getW(), old.getH()) >= 0) {
				continue;
			}
			result.add(old);
		}
		return result;
	}

	/** Which of the given faces is the given raw box, <code>-1</code> where none is. */
	static int indexOfBox(List<FaceCache.Face> faces, double x, double y, double w, double h) {
		int best = -1;
		double bestOverlap = FaceTags.IOU_MATCH;
		for (int n = 0; n < faces.size(); n++) {
			FaceCache.Face face = faces.get(n);
			double overlap = FaceTags.iou(x, y, w, h, face.getX(), face.getY(), face.getW(), face.getH());
			if (overlap > bestOverlap) {
				bestOverlap = overlap;
				best = n;
			}
		}
		return best;
	}

	/**
	 * Looks for a face where somebody marked one, and keeps what it finds, see issue #155.
	 *
	 * <p>
	 * What <code>?action=tag-faces</code> does with a box nobody decided about that meets none of
	 * the answered faces: a region of the <em>original</em> around the box is decoded — at least a
	 * square of {@link #SEARCH_MIN_FRACTION} of the long side, the box grown by its own size on every
	 * side where that is more, subsampled to {@link FaceDetection#MAX_INPUT} and never the whole
	 * raster (the memory rule of {@link Originals}) — turned upright, and handed to the detector,
	 * which answers the face containing the box's centre (see
	 * {@link FaceDetection#search(BufferedImage, double, double, double, double)}). That face, box
	 * and embedding, is stored in the album's {@link FaceCache} as a detection of this photograph
	 * like any other, {@link FaceCache.Face#isRefined() refined} and
	 * {@link FaceCache.Face#isMarked() marked}, and the album is clustered again: it has an index,
	 * a crop cut from the original, a suggestion of issue #127 and, once confirmed, is a prototype.
	 * </p>
	 *
	 * <p>
	 * A face the cache already holds — the detection somebody called no face and now marks again —
	 * is answered as it is, and nothing is added. A photograph nothing is known about yet is
	 * described first, exactly as the walk would describe it, so that the marked face is not the
	 * only one the walk would then believe it holds.
	 * </p>
	 *
	 * @param image
	 *        The photograph.
	 * @param x
	 *        The left edge of the marked box, a fraction of the raw raster, see {@link Faces}.
	 * @param y
	 *        Its top edge, likewise.
	 * @param w
	 *        Its width, likewise.
	 * @param h
	 *        Its height, likewise.
	 * @return The face that is now in the cache for this box, in the raw raster, or
	 *         <code>null</code> where nothing was found, this space looks for no faces or the
	 *         original cannot be looked at; the caller then keeps the box as it was marked.
	 */
	public FaceCache.Face mark(File image, double x, double y, double w, double h) {
		if (!isEnabled() || !isPhotograph(image) || _failed.containsKey(image.getAbsolutePath())) {
			return null;
		}
		File folder = image.getParentFile();
		synchronized (lockOf(folder)) {
			try {
				HashCache hashes = new HashCache(folder);
				String hash = hashes.hashByName().get(image.getName());
				hashes.flush();
				if (hash == null) {
					return null;
				}
				FaceCache cache = new FaceCache(folder);
				boolean changed = false;
				if (!cache.knows(hash)) {
					describe(cache, hash, image, Collections.emptyList());
					changed = true;
				}
				FaceCache.Face result = null;
				FaceCache.Face found = search(image, x, y, w, h);
				if (found != null) {
					List<FaceCache.Face> faces = new ArrayList<>(cache.facesOf(hash));
					int existing = indexOfBox(faces, found.getX(), found.getY(), found.getW(), found.getH());
					if (existing >= 0) {
						result = faces.get(existing);
					} else {
						faces.add(found);
						cache.put(hash, faces);
						result = found;
						changed = true;
					}
				}
				if (changed) {
					Clustering.cluster(cache.allFaces());
					cache.clustered();
					cache.flush();
				}
				return result;
			} catch (IOException | RuntimeException ex) {
				LOG.log(Level.WARNING, "Cannot look for the marked face in '" + image.getAbsolutePath() + "': "
					+ FaceDetection.reason(ex), ex);
				return null;
			}
		}
	}

	/** The face in the region of the original around the given raw box, see {@link #mark}. */
	private static FaceCache.Face search(File image, double x, double y, double w, double h)
			throws IOException {
		int[] raster = rasterOf(image);
		Orientation exif = exifOrientation(image);
		double rawWidth = raster[0];
		double rawHeight = raster[1];
		double left = x * rawWidth;
		double top = y * rawHeight;
		double width = w * rawWidth;
		double height = h * rawHeight;
		double centreX = left + width / 2;
		double centreY = top + height / 2;
		double least = Math.max(rawWidth, rawHeight) * SEARCH_MIN_FRACTION;
		double regionWidth = Math.max(3 * width, least);
		double regionHeight = Math.max(3 * height, least);
		Originals.Region region = Originals.decodeLongSide(image, centreX - regionWidth / 2,
			centreY - regionHeight / 2, centreX + regionWidth / 2, centreY + regionHeight / 2,
			FaceDetection.MAX_INPUT);
		BufferedImage raw = region.getImage();
		double pixelsX = raw.getWidth();
		double pixelsY = raw.getHeight();
		int sampling = region.getSampling();

		// The marked box as a fraction of the region, which is turned upright for the detector like
		// the preview is: a face lying on its side in the file is not what a detector is made for.
		double[] upright = Faces.toUpright(exif, (left - region.getLeft()) / sampling / pixelsX,
			(top - region.getTop()) / sampling / pixelsY, width / sampling / pixelsX,
			height / sampling / pixelsY);
		BufferedImage shown = Originals.upright(raw, exif);
		double shownX = shown.getWidth();
		double shownY = shown.getHeight();
		FaceDetection.Refined found = FaceDetection.search(shown, upright[0] * shownX, upright[1] * shownY,
			upright[2] * shownX, upright[3] * shownY);
		if (found == null) {
			return null;
		}

		// Back into the raw raster of the file, the one frame a box is stored in.
		double[] back = Faces.toRaw(exif, found.getX() / shownX, found.getY() / shownY,
			found.getW() / shownX, found.getH() / shownY);
		double foundLeft = unit(region.rawX(back[0] * pixelsX) / rawWidth);
		double foundTop = unit(region.rawY(back[1] * pixelsY) / rawHeight);
		double foundRight = unit(region.rawX((back[0] + back[2]) * pixelsX) / rawWidth);
		double foundBottom = unit(region.rawY((back[1] + back[3]) * pixelsY) / rawHeight);
		if (foundRight <= foundLeft || foundBottom <= foundTop) {
			return null;
		}
		return new FaceCache.Face(foundLeft, foundTop, foundRight - foundLeft, foundBottom - foundTop,
			FaceDetection.SCORE_THRESHOLD, found.getEmbedding(), true, true);
	}

	/**
	 * Describes the given photograph anew and records it in the given cache.
	 *
	 * <p>
	 * The one place a description is written: the faces, every face somebody pointed at before and
	 * that the new description does not find again (issue #155), the orientation (issue #142), and
	 * the marker of issue #163 — whatever the centre gave, because this description is as good as
	 * this build can make it and the walk must not come back for the centre again. The crops of the
	 * old faces name nobody any more, see issue #141.
	 * </p>
	 */
	private void describe(FaceCache cache, String hash, File image, List<FaceCache.Face> before)
			throws IOException {
		cache.put(hash, keepMarked(detect(image), before));
		cache.putSearched(hash);
		dropCrops(image);
		rememberExif(cache, image, hash);
	}

	/**
	 * Whether the given photograph was described without the second look of issue #163 and is worth
	 * that look now.
	 *
	 * <p>
	 * An entry holding a face is never looked at again for this — the centre is only ever asked where
	 * the preview found nobody — and neither is one that carries the marker. What is left is an entry
	 * without any face that a build before issue #163 wrote. Where the preview shows that photograph
	 * pixel for pixel, the centre has nothing to add: the marker is written straight away and the
	 * photograph is not described again. A file whose header cannot be read is left as it is, like
	 * {@link #unrefined(File, List)} leaves one.
	 * </p>
	 */
	private static boolean unsearched(FaceCache cache, File image, String hash) {
		if (!cache.facesOf(hash).isEmpty() || cache.isSearched(hash)) {
			return false;
		}
		try {
			if (downscaled(exifOrientation(image), rasterOf(image))) {
				return true;
			}
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.FINE, "Cannot measure '" + image.getAbsolutePath() + "'.", ex);
			return false;
		}
		cache.putSearched(hash);
		return false;
	}

	/**
	 * Writes down how the given photograph is turned for display, see issue #142.
	 *
	 * <p>
	 * The header is read only where nobody wrote it down yet, so an album that was indexed by this
	 * build costs nothing here and a library indexed before issue #142 heals on the one walk: the
	 * answer is built from the raw box turned upright, and that turn must not mean opening every
	 * file of an album on every read.
	 * </p>
	 */
	private static void rememberExif(FaceCache cache, File image, String hash) {
		if (cache.exifOf(hash) != null) {
			return;
		}
		cache.putExif(hash, Orientations.toCode(exifOrientation(image)));
	}

	/** The faces of one photograph, in the raw raster of its file, see {@link Faces}. */
	private List<FaceCache.Face> detect(File image) throws IOException {
		File preview;
		try {
			// The ordinary path, permit and all: indexing must never be cheaper than a request.
			preview = PreviewCache.createPreview(image);
		} catch (PreviewException ex) {
			throw new IOException("No preview of '" + image.getName() + "': " + ex.getMessage(), ex);
		}
		Orientation exif = exifOrientation(image);
		int[] raster = rasterOf(image);
		// A face too small on the preview is looked up in the file itself — but only where the file
		// really holds more than the preview shows, see issue #140.
		boolean detailed = downscaled(exif, raster);
		FaceDetection.Result found = FaceDetection.detect(preview,
			detailed ? candidate -> refine(image, preview, exif, raster, candidate) : null);
		double[] content = content(image, exif, found.getWidth(), found.getHeight());
		List<FaceDetection.Detected> faces = found.getFaces();
		if (faces.isEmpty() && detailed) {
			// Nobody on the preview, and the file holds more than it shows: its centre is asked
			// again at the same size, see issue #163.
			faces = centre(image, exif, raster, content);
		}
		List<FaceCache.Face> result = new ArrayList<>();
		for (FaceDetection.Detected face : faces) {
			// A face at the edge of the picture is reported reaching over it, and the picture may
			// not fill the whole preview; what is kept is the part that is really there.
			double left = unit((face.getX() - content[0]) / content[2]);
			double top = unit((face.getY() - content[1]) / content[3]);
			double right = unit((face.getX() + face.getW() - content[0]) / content[2]);
			double bottom = unit((face.getY() + face.getH() - content[1]) / content[3]);
			if (right <= left || bottom <= top) {
				// Entirely in the margin around the picture; there is no face of the picture here.
				continue;
			}
			// The preview is upright; the box is written in the raster of the file itself.
			double[] raw = Faces.toRaw(exif, left, top, right - left, bottom - top);
			// Described as well as this build can: looked up in the original, or large enough on the
			// preview for there to be nothing to look up (issue #140). A detector of a test's own says
			// nothing about it, so the size on the preview decides for it too.
			boolean refined = face.isRefined() || !detailed
				|| Math.min(face.getW(), face.getH()) >= FaceDetection.REFINE_PIXELS;
			result.add(new FaceCache.Face(raw[0], raw[1], raw[2], raw[3], face.getScore(),
				face.getEmbedding(), refined));
		}
		return result;
	}

	/**
	 * Looks at the original where the preview is too small for a face, see issue #140.
	 *
	 * <p>
	 * The one place the two frames meet. The candidate arrives in the pixels of the preview; the
	 * region is cut from the raw raster of the file, because that is the frame a box is stored in and
	 * the frame {@link Originals} reads in; and what comes back is answered in the pixels of the
	 * preview again, so that the conversion of {@link #detect(File)} stays the only one there is.
	 * </p>
	 *
	 * <p>
	 * The region is the box grown by its own size on every side — a face the preview placed a little
	 * off must still lie inside it, and the detector wants the head around the face to find one at
	 * all — subsampled so that it reaches the network at most {@link FaceDetection#MAX_INPUT} across,
	 * which is what bounds the memory of this: never the whole raster, see {@link Originals}.
	 * </p>
	 */
	private FaceDetection.Refined refine(File image, File preview, Orientation exif, int[] raster,
			FaceDetection.Detected candidate) throws IOException {
		int[] previewSize = rasterOf(preview);
		double[] content = content(image, exif, previewSize[0], previewSize[1]);

		// The preview's box, in the raw raster of the file.
		double[] raw = Faces.toRaw(exif,
			(candidate.getX() - content[0]) / content[2],
			(candidate.getY() - content[1]) / content[3],
			candidate.getW() / content[2],
			candidate.getH() / content[3]);
		FaceDetection.Refined better = refineRaw(image, raw[0] * raster[0], raw[1] * raster[1],
			raw[2] * raster[0], raw[3] * raster[1]);
		if (better == null) {
			return null;
		}

		// Back out: the answer speaks preview pixels.
		double[] upright = Faces.toUpright(exif, better.getX() / raster[0], better.getY() / raster[1],
			better.getW() / raster[0], better.getH() / raster[1]);
		return new FaceDetection.Refined(
			content[0] + upright[0] * content[2],
			content[1] + upright[1] * content[3],
			upright[2] * content[2],
			upright[3] * content[3],
			better.getEmbedding());
	}

	/**
	 * Looks at the original around the given box of its raw raster, see issue #140.
	 *
	 * @return The face found there, its box in the pixels of the raw raster, or <code>null</code>.
	 */
	private static FaceDetection.Refined refineRaw(File image, double left, double top, double width,
			double height) throws IOException {
		Originals.Region region = Originals.decodeLongSide(image, left - width, top - height,
			left + 2 * width, top + 2 * height, FaceDetection.MAX_INPUT);
		int sampling = region.getSampling();
		FaceDetection.Refined better = FaceDetection.refine(region.getImage(),
			(left - region.getLeft()) / sampling, (top - region.getTop()) / sampling,
			width / sampling, height / sampling);
		if (better == null) {
			return null;
		}
		// The region's pixels are raw pixels, subsampled.
		return new FaceDetection.Refined(region.rawX(better.getX()), region.rawY(better.getY()),
			better.getW() * sampling, better.getH() * sampling, better.getEmbedding());
	}

	/**
	 * The second look at a photograph whose preview showed no face, see issue #163.
	 *
	 * <p>
	 * Faces are mostly near the middle of a photograph, and a face too small for the preview is still
	 * a face of the file. So the centre half of the picture ({@link #CENTRE_FROM} to
	 * {@link #CENTRE_TO} on each axis) is read out of the original and handed to the detector <em>at
	 * the size the preview reached it at</em> — the long side of the picture on the preview, capped at
	 * {@link FaceDetection#MAX_INPUT} as the preview is — which shows the middle twice as fine for
	 * exactly the cost of the first pass: not a full-resolution pass, whose cost grows with the
	 * square of the file. Integer subsampling alone cannot hit that size, so the piece is read at
	 * least that large and scaled down ({@link Originals#decodeLongSideAtLeast},
	 * {@link Originals#fitLongSide}); the memory is bounded by four times a preview, never the whole
	 * raster (issue #68).
	 * </p>
	 *
	 * <p>
	 * The rules of the first pass hold on the centre as they stand, measured in its pixels: a
	 * candidate of fewer than {@link FaceDetection#MIN_FACE_PIXELS_REFINABLE} is dropped, one smaller
	 * than {@link FaceDetection#REFINE_PIXELS} is looked up in the original around its box exactly as
	 * issue #140 looks up a candidate of the preview (and one below
	 * {@link FaceDetection#MIN_FACE_PIXELS} kept only where that confirms it), and one of
	 * {@link FaceDetection#REFINE_PIXELS} or more is described from the centre itself — SFace's input
	 * is that large, so the file has nothing more to say about it, and it counts as
	 * {@link FaceCache.Face#isRefined() refined}.
	 * </p>
	 *
	 * <p>
	 * What comes back is answered in the pixels of the preview, so that {@link #detect(File)} goes on
	 * being the one place a box is converted into the raw raster it is stored in.
	 * </p>
	 *
	 * @return The faces of the centre, in preview pixels; empty where there are none or the original
	 *         cannot be read in pieces — the marker is written all the same, because a file no reader
	 *         can decode a region of today decodes none tomorrow either.
	 */
	private List<FaceDetection.Detected> centre(File image, Orientation exif, int[] raster, double[] content)
			throws IOException {
		int size = detectorSize(content);
		double rawWidth = raster[0];
		double rawHeight = raster[1];
		Originals.Region region;
		try {
			region = Originals.decodeLongSideAtLeast(image, CENTRE_FROM * rawWidth, CENTRE_FROM * rawHeight,
				CENTRE_TO * rawWidth, CENTRE_TO * rawHeight, size);
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.INFO, "Cannot look at the centre of '" + image.getAbsolutePath() + "': "
				+ FaceDetection.reason(ex));
			return Collections.emptyList();
		}
		BufferedImage raw = region.getImage();
		double pixelsX = raw.getWidth();
		double pixelsY = raw.getHeight();
		BufferedImage shown = Originals.upright(Originals.fitLongSide(raw, size), exif);
		double shownX = shown.getWidth();
		double shownY = shown.getHeight();

		// The region's frame and the raw raster, both ways: a fraction of what the detector was shown
		// is the same fraction of what was read, scaled or not.
		java.util.function.Function<double[], double[]> toRaw = box -> {
			double[] back = Faces.toRaw(exif, box[0] / shownX, box[1] / shownY, box[2] / shownX, box[3] / shownY);
			double left = region.rawX(back[0] * pixelsX);
			double top = region.rawY(back[1] * pixelsY);
			return new double[] { left, top, region.rawX((back[0] + back[2]) * pixelsX) - left,
				region.rawY((back[1] + back[3]) * pixelsY) - top };
		};
		java.util.function.Function<double[], double[]> fromRaw = box -> {
			double sampling = region.getSampling();
			double[] there = Faces.toUpright(exif, (box[0] - region.getLeft()) / sampling / pixelsX,
				(box[1] - region.getTop()) / sampling / pixelsY, box[2] / sampling / pixelsX,
				box[3] / sampling / pixelsY);
			return new double[] { there[0] * shownX, there[1] * shownY, there[2] * shownX, there[3] * shownY };
		};

		FaceDetection.Result found = FaceDetection.detectIn(shown, candidate -> {
			double[] box = toRaw.apply(
				new double[] { candidate.getX(), candidate.getY(), candidate.getW(), candidate.getH() });
			FaceDetection.Refined better = refineRaw(image, box[0], box[1], box[2], box[3]);
			if (better == null) {
				return null;
			}
			double[] back = fromRaw.apply(new double[] { better.getX(), better.getY(), better.getW(), better.getH() });
			return new FaceDetection.Refined(back[0], back[1], back[2], back[3], better.getEmbedding());
		});

		List<FaceDetection.Detected> result = new ArrayList<>();
		for (FaceDetection.Detected face : found.getFaces()) {
			double[] box = toRaw.apply(new double[] { face.getX(), face.getY(), face.getW(), face.getH() });
			double[] upright = Faces.toUpright(exif, box[0] / rawWidth, box[1] / rawHeight, box[2] / rawWidth,
				box[3] / rawHeight);
			result.add(new FaceDetection.Detected(
				content[0] + upright[0] * content[2],
				content[1] + upright[1] * content[3],
				upright[2] * content[2],
				upright[3] * content[3],
				face.getScore(), face.getEmbedding(), face.isRefined()));
		}
		return result;
	}

	/**
	 * The long side of the raster the detector is handed for a preview whose picture covers the given
	 * content box, see {@link #content(File, Orientation, int, int)} and issue #163.
	 *
	 * <p>
	 * The detector takes a preview as it stands, 900&nbsp;&times;&nbsp;600 for a photograph of three
	 * by two, and scales only a raster longer than {@link FaceDetection#MAX_INPUT} (a panorama) down
	 * to that; so that is the size the centre is shown at too.
	 * </p>
	 */
	static int detectorSize(double[] content) {
		return (int) Math.min(FaceDetection.MAX_INPUT, Math.round(Math.max(content[2], content[3])));
	}

	/**
	 * Whether a pass has something to gain in the given photograph, see issue #140.
	 *
	 * <p>
	 * A cache entry written before that issue says nothing about the resolution its faces were
	 * described at, and one written since says it per face. What is looked at again is a photograph
	 * holding a face that was <em>not</em> described at the best resolution this build knows and that
	 * is small enough on the preview for the original to know better — so a library of portraits is
	 * never re-detected, and a group photograph is, once.
	 * </p>
	 *
	 * <p>
	 * A file that cannot be read says nothing here and is left exactly as it is, which is what
	 * happens to a photograph whose original is gone.
	 * </p>
	 */
	private static boolean unrefined(File image, List<FaceCache.Face> faces) {
		boolean any = false;
		for (FaceCache.Face face : faces) {
			if (!face.isRefined()) {
				any = true;
				break;
			}
		}
		if (!any) {
			return false;
		}
		try {
			Orientation exif = exifOrientation(image);
			int[] raster = rasterOf(image);
			if (!downscaled(exif, raster)) {
				// The preview shows every pixel this file has; there is nothing to come back for.
				return false;
			}
			double[] content = previewContent(exif, raster);
			for (FaceCache.Face face : faces) {
				if (face.isRefined()) {
					continue;
				}
				double[] upright = Faces.toUpright(exif, face.getX(), face.getY(), face.getW(), face.getH());
				if (Math.min(upright[2] * content[0], upright[3] * content[1]) < FaceDetection.REFINE_PIXELS) {
					return true;
				}
			}
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.FINE, "Cannot measure the faces of '" + image.getAbsolutePath() + "'.", ex);
			return false;
		}
		return false;
	}

	/**
	 * How large the picture itself is drawn on its preview, as <code>{width, height}</code> in pixels,
	 * without reading the preview at all.
	 *
	 * <p>
	 * The size rule is {@link PreviewCache#previewBox(int, int)}'s, and nothing is ever scaled up, so
	 * the picture covers <code>min(preview, display)</code> on each axis — the very numbers
	 * {@link #content(File, Orientation, int, int)} measures on a preview that is there. This one is
	 * asked <em>before</em> deciding whether that preview is worth making at all.
	 * </p>
	 */
	private static double[] previewContent(Orientation exif, int[] raster) {
		double displayWidth = Orientations.width(exif, raster[0], raster[1]);
		double displayHeight = Orientations.height(exif, raster[0], raster[1]);
		int[] box = PreviewCache.previewBox((int) displayWidth, (int) displayHeight);
		return new double[] { Math.min(box[0], displayWidth), Math.min(box[1], displayHeight) };
	}

	/**
	 * Whether the preview shows the given photograph smaller than it is, see issue #140.
	 *
	 * <p>
	 * The condition of the whole second look: {@link PreviewCache} never scales a picture <em>up</em>,
	 * so a photograph that is no larger than the preview box is drawn pixel for pixel and the file has
	 * nothing more to say than the preview already says. Asking the detector again there would only
	 * mean asking it a differently framed question and getting a differently rounded answer for no
	 * gain, so such a face is described from the preview — and counts as described as well as this
	 * build can, which is what keeps a pass from coming back to it forever.
	 * </p>
	 */
	private static boolean downscaled(Orientation exif, int[] raster) {
		double displayWidth = Orientations.width(exif, raster[0], raster[1]);
		double displayHeight = Orientations.height(exif, raster[0], raster[1]);
		double[] content = previewContent(exif, raster);
		return content[0] < displayWidth - 0.5 || content[1] < displayHeight - 0.5;
	}

	/**
	 * Where the photograph itself lies inside its preview, as <code>{left, top, width, height}</code>
	 * in preview pixels.
	 *
	 * <p>
	 * Not always the whole preview: {@link PreviewCache} draws the picture into a canvas of the
	 * preview size and never scales it <em>up</em>, so a photograph that is smaller than the preview
	 * box sits centred in it with a margin all round (a 320&nbsp;&times;&nbsp;476 picture becomes an
	 * 807&nbsp;&times;&nbsp;1200 preview). A box normalised against the canvas instead of against
	 * the picture would then be wrong by that margin, which is why this is computed rather than
	 * assumed.
	 * </p>
	 *
	 * <p>
	 * The canvas has the aspect of the picture, and the scale is capped at one, so the picture
	 * covers <code>min(preview, display)</code> on each axis and is centred in what is left.
	 * </p>
	 */
	private static double[] content(File image, Orientation exif, int previewWidth, int previewHeight)
			throws IOException {
		int[] raw = rasterOf(image);
		double displayWidth = Orientations.width(exif, raw[0], raw[1]);
		double displayHeight = Orientations.height(exif, raw[0], raw[1]);
		double width = Math.min(previewWidth, displayWidth);
		double height = Math.min(previewHeight, displayHeight);
		return new double[] { (previewWidth - width) / 2, (previewHeight - height) / 2, width, height };
	}

	/** The size of the given file's own raster, before any orientation; a header read, no pixels. */
	static int[] rasterOf(File file) throws IOException {
		try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(file)) {
			if (in == null) {
				throw new IOException("Cannot open '" + file.getName() + "'.");
			}
			java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				throw new IOException("No image reader for '" + file.getName() + "'.");
			}
			javax.imageio.ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				return new int[] { reader.getWidth(0), reader.getHeight(0) };
			} finally {
				reader.dispose();
			}
		}
	}

	/**
	 * How the given file says its pixels are to be turned for display.
	 *
	 * <p>
	 * The very orientation {@link PreviewCache} applied when it made the preview, read the same
	 * way; a file that says nothing is {@link Orientation#IDENTITY}.
	 * </p>
	 */
	static Orientation exifOrientation(File file) {
		try {
			com.drew.metadata.Metadata metadata = com.drew.imaging.ImageMetadataReader.readMetadata(file);
			com.drew.metadata.exif.ExifIFD0Directory directory =
				metadata.getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class);
			if (directory != null
				&& directory.containsTag(com.drew.metadata.exif.ExifIFD0Directory.TAG_ORIENTATION)) {
				return Orientations.fromCode(
					directory.getInt(com.drew.metadata.exif.ExifIFD0Directory.TAG_ORIENTATION));
			}
		} catch (Exception ex) {
			LOG.log(Level.FINE, "No orientation in '" + file.getName() + "'.", ex);
		}
		return Orientation.IDENTITY;
	}

	/** Whether the given file is a photograph; a video is never looked at, see issue #123. */
	static boolean isPhotograph(File file) {
		String suffix = de.haumacher.util.servlet.Util.suffix(file.getName());
		return "jpg".equals(suffix) || "jpeg".equals(suffix) || "png".equals(suffix);
	}

	/** Whether the given part is a photograph the detector would look at. */
	public static boolean isPhotograph(ImagePart image) {
		return image.getKind() == ImageKind.IMAGE;
	}

	// --- The answer. ---

	/**
	 * The given album with the faces of its photographs, or the album itself when there are none.
	 *
	 * <p>
	 * A <em>copy</em>, never the cached album: what the {@link ResourceCache} holds is one object
	 * shared by every request and by the sidecar a move rewrites, and the faces are not for every
	 * request, see {@link Faces#maySee}. The parts are copied too — everywhere else in this server
	 * a per-caller copy of an album may share its parts, because nothing is ever changed inside
	 * one; here something is, so the copy goes one level deeper. It is made by writing each part
	 * out and reading it back, which cannot forget a field the model grows later.
	 * </p>
	 *
	 * <p>
	 * The cached album therefore never carries a face: a code path that forgets to ask
	 * {@link Faces#maySee} answers none, which is the right way round for the most personal thing
	 * this server knows.
	 * </p>
	 *
	 * <p>
	 * Since issue #125 this is also where a stored {@link de.haumacher.imageServer.shared.model.FaceTag
	 * tag} meets the detection it is about, see {@link FaceTags}. A tag is the album's own statement
	 * and is answered even where the detector is switched off or has not run: an album that carries
	 * one is copied and merged here whatever the index does.
	 * </p>
	 *
	 * @param people
	 *        The register a tag's person is resolved through, <code>null</code> when there is none.
	 */
	public AlbumInfo derive(AlbumInfo album, File folder, PeopleStore people) {
		boolean enabled = isEnabled();
		boolean tagged = tagged(album);
		if (!enabled && !tagged) {
			return album;
		}
		// The two small sidecars are read once for both questions: which faces are known, and
		// whether anything is still to come.
		FaceCache cache = enabled ? new FaceCache(folder) : null;
		Map<String, String> hashByName =
			enabled ? new HashCache(folder).storedHashByName() : Collections.emptyMap();
		Map<String, List<FaceCache.Face>> cached = enabled
			? cachedByName(cache, hashByName) : Collections.<String, List<FaceCache.Face>> emptyMap();
		Map<String, List<FaceInfo>> detected = facesByName(cached);
		boolean pending = enabled && pending(album, folder, cache, hashByName);
		// What was found and what was decided about it, in one list per photograph.
		Map<String, List<FaceInfo>> byName = merged(album, detected, people);
		if (enabled) {
			// The two sidecars are open anyway, so keeping the prototypes of issue #127 current
			// costs no file access at all: an album that was moved, renamed or written behind the
			// server's back is described afresh the moment somebody looks at it.
			_recognition.put(folder, Recognition.collect(album, cache, hashByName));
			suggest(byName, cached, people);
		}
		// Last of all, and only on the way out: the wire speaks the frame of the picture the app
		// draws on, see issue #142.
		upright(byName, folder, cache, hashByName);
		if (byName.isEmpty() && !pending) {
			return album;
		}

		AlbumInfo result = copyOf(album);
		if (byName.isEmpty()) {
			// Nothing to say about any image yet; the parts are the cached ones, as everywhere.
			result.setParts(album.getParts());
		} else {
			for (AlbumPart part : album.getParts()) {
				if (part instanceof ImagePart) {
					result.addPart(withFaces((ImagePart) part, byName));
				} else if (part instanceof ImageGroup) {
					ImageGroup group = (ImageGroup) part;
					ImageGroup copy = ImageGroup.create().setRepresentative(group.getRepresentative());
					for (ImagePart image : group.getImages()) {
						copy.addImage(withFaces(image, byName));
					}
					result.addPart(copy);
				} else {
					result.addPart(part);
				}
			}
		}
		result.setFacesPending(pending);
		return result;
	}

	/** The album's own statements, without its parts. */
	private static AlbumInfo copyOf(AlbumInfo album) {
		AlbumInfo result = AlbumInfo.create()
			.setKind(album.getKind())
			.setTitle(album.getTitle())
			.setSubTitle(album.getSubTitle())
			.setDate(album.getDate())
			.setEffectiveDate(album.getEffectiveDate());
		if (album.getIndexPicture() != null) {
			result.setIndexPicture(album.getIndexPicture());
		}
		return result;
	}

	/** A copy of the given image carrying the faces recorded for it, the image itself if none. */
	private static ImagePart withFaces(ImagePart image, Map<String, List<FaceInfo>> byName) {
		List<FaceInfo> faces = byName.get(image.getName());
		if (faces == null || faces.isEmpty()) {
			return image;
		}
		ImagePart copy = copyOf(image);
		copy.setFaces(faces);
		return copy;
	}

	/**
	 * What every photograph of the given album is answered as its faces, by file name.
	 *
	 * <p>
	 * The detections of the moment and the decisions the album stores, brought together by
	 * {@link FaceTags#merge(List, List, PeopleStore)}: the detected faces first, in their own order
	 * and carrying the person of the tag they match, and behind them every tag no detection matched.
	 * Only photographs that have something to say are in the map.
	 * </p>
	 *
	 * <p>
	 * The same numbering an answer carries, which is what a tagging request names a face by, see
	 * {@link de.haumacher.imageServer.shared.model.FaceAssignment#getFace()}.
	 * </p>
	 */
	public Map<String, List<FaceInfo>> answered(AlbumInfo album, File folder, PeopleStore people) {
		Map<String, List<FaceInfo>> result = new LinkedHashMap<>();
		for (Map.Entry<String, FaceTags.Answer> entry : answers(album, folder, people).entrySet()) {
			result.put(entry.getKey(), entry.getValue().getFaces());
		}
		return result;
	}

	/**
	 * The same, with what a tagging request needs to know besides the answered faces: which of
	 * them the detector found and which detections are hidden, see {@link FaceTags.Answer}.
	 */
	public Map<String, FaceTags.Answer> answers(AlbumInfo album, File folder, PeopleStore people) {
		// Deliberately without the suggestions of issue #127: this is the numbering a tagging
		// request names a face by, and a guess neither adds a face nor moves one.
		Map<String, List<FaceInfo>> detected = isEnabled()
			? facesByName(cachedByName(new FaceCache(folder), new HashCache(folder).storedHashByName()))
			: Collections.<String, List<FaceInfo>> emptyMap();
		Map<String, FaceTags.Answer> result = new LinkedHashMap<>();
		for (ImagePart image : imagesOf(album)) {
			List<FaceInfo> faces = detected.get(image.getName());
			if (faces == null) {
				faces = Collections.emptyList();
			}
			if (faces.isEmpty() && image.getTags().isEmpty()) {
				continue;
			}
			result.put(image.getName(), FaceTags.answer(faces, image.getTags(), people));
		}
		return result;
	}

	/**
	 * How each of the given photographs of the given album is turned for display, see issue #142.
	 *
	 * <p>
	 * The frame a client speaks in: it draws on the picture the EXIF orientation of the file was
	 * already applied to, so a box it hands back (issue #147) is turned into the raw raster of the
	 * file by exactly this, and by nothing else. Read out of the album's own {@link FaceCache}
	 * where it was written down when the photograph was described, and out of the file's header at
	 * most once per name otherwise.
	 * </p>
	 */
	public static Map<String, Orientation> displayOrientations(File folder, Collection<String> names) {
		FaceCache cache = new FaceCache(folder);
		Map<String, String> hashByName = new HashCache(folder).storedHashByName();
		Map<String, Orientation> result = new LinkedHashMap<>();
		for (String name : names) {
			result.put(name, exifOf(folder, name, cache, hashByName));
		}
		return result;
	}

	private static Map<String, List<FaceInfo>> merged(AlbumInfo album, Map<String, List<FaceInfo>> detected,
			PeopleStore people) {
		Map<String, List<FaceInfo>> result = new LinkedHashMap<>();
		for (ImagePart image : imagesOf(album)) {
			List<FaceInfo> faces = detected.get(image.getName());
			if (faces == null) {
				faces = Collections.emptyList();
			}
			if (faces.isEmpty() && image.getTags().isEmpty()) {
				continue;
			}
			result.put(image.getName(), FaceTags.merge(faces, image.getTags(), people));
		}
		return result;
	}

	/**
	 * Turns every answered box into the frame the picture is shown in, see issue #142.
	 *
	 * <p>
	 * A box is <em>stored</em> in the raw raster of the file (see {@link Faces}) — the one frame
	 * nothing can move — and the two things a box is stored in, the {@link FaceCache} and the
	 * {@link de.haumacher.imageServer.shared.model.FaceTag} of the album, are matched against each
	 * other there. What goes on the wire is another question: the client draws the box on the
	 * rendition of <code>?type=tn</code>, which is the picture <em>upright</em>, the EXIF
	 * orientation of the file already applied, and it turns it by nothing but
	 * {@link ImagePart#getOrientation()}. The file's own orientation is not on the wire and the
	 * client cannot know it, so a box of a portrait shot (EXIF&nbsp;6 or 8) landed nowhere near the
	 * face. It is applied here, once, in the one place an answer is built.
	 * </p>
	 *
	 * <p>
	 * Only here: {@link #answered(AlbumInfo, File, PeopleStore)} — the numbering
	 * <code>?action=tag-faces</code> names a face by, and the boxes it copies into the album's own
	 * statements — stays in the raw frame, and so does the crop of <code>?type=face</code>.
	 * </p>
	 */
	private static void upright(Map<String, List<FaceInfo>> byName, File folder, FaceCache cache,
			Map<String, String> hashByName) {
		for (Map.Entry<String, List<FaceInfo>> entry : byName.entrySet()) {
			Orientation exif = exifOf(folder, entry.getKey(), cache, hashByName);
			if (exif == Orientation.IDENTITY) {
				continue;
			}
			for (FaceInfo face : entry.getValue()) {
				double[] box = Faces.toUpright(exif, face.getX(), face.getY(), face.getW(), face.getH());
				face.setX(box[0]).setY(box[1]).setW(box[2]).setH(box[3]);
			}
		}
	}

	/**
	 * How the given photograph of the given album is turned for display, see issue #142.
	 *
	 * <p>
	 * Out of the album's {@link FaceCache}, where it was written down when the photograph was
	 * described, so that describing an album costs no file access here. A photograph the cache says
	 * nothing about — one described before issue #142, or one that carries a decision in an album
	 * this server never looked for faces in — is read from its header, once per answer, and the next
	 * walk writes it down.
	 * </p>
	 */
	private static Orientation exifOf(File folder, String name, FaceCache cache,
			Map<String, String> hashByName) {
		if (cache != null) {
			String hash = hashByName.get(name);
			if (hash != null) {
				Integer code = cache.exifOf(hash);
				if (code != null) {
					return Orientations.fromCode(code.intValue());
				}
			}
		}
		return exifOrientation(new File(folder, name));
	}

	/** Whether any photograph of the given album carries a decision about a face (issue #125). */
	public static boolean tagged(AlbumInfo album) {
		for (ImagePart image : imagesOf(album)) {
			if (!image.getTags().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** Every photograph of the given album, the members of a group included. */
	public static List<ImagePart> imagesOf(AlbumInfo album) {
		List<ImagePart> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add((ImagePart) part);
			} else if (part instanceof ImageGroup) {
				result.addAll(((ImageGroup) part).getImages());
			}
		}
		return result;
	}

	/**
	 * A copy of the given part, field for field.
	 *
	 * <p>
	 * Through the model's own reader and writer rather than by hand: a field added to
	 * {@link ImagePart} later is copied without anybody remembering this place. The transient
	 * fields are not copied, exactly as the copy of the
	 * {@link de.haumacher.imageServer.PrivacyFilter} does not compute them — such a copy is only
	 * ever serialised into the response.
	 * </p>
	 */
	static ImagePart copyOf(ImagePart image) {
		try {
			StringWriter buffer = new StringWriter();
			try (JsonWriter out = new JsonWriter(new WriterAdapter(buffer))) {
				image.writeContent(out);
			}
			ImagePart result = ImagePart.create();
			try (JsonReader in = new JsonReader(new ReaderAdapter(new StringReader(buffer.toString())))) {
				result.readContent(in);
			}
			return result;
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot copy the image '" + image.getName() + "'.", ex);
		}
	}

	/** What the detector found in every photograph of the given album, by file name. */
	private static Map<String, List<FaceCache.Face>> cachedByName(FaceCache cache,
			Map<String, String> hashByName) {
		if (cache.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, List<FaceCache.Face>> result = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : hashByName.entrySet()) {
			List<FaceCache.Face> faces = cache.facesOf(entry.getValue());
			if (faces.isEmpty()) {
				continue;
			}
			result.put(entry.getKey(), faces);
		}
		return result;
	}

	/** The faces of every photograph of the given album, by file name. */
	private static Map<String, List<FaceInfo>> facesByName(Map<String, List<FaceCache.Face>> cached) {
		if (cached.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, List<FaceInfo>> result = new LinkedHashMap<>();
		for (Map.Entry<String, List<FaceCache.Face>> entry : cached.entrySet()) {
			result.put(entry.getKey(), infos(entry.getValue()));
		}
		return result;
	}

	/** The given detections as the wire numbers them, still in the raw raster. */
	private static List<FaceInfo> infos(List<FaceCache.Face> faces) {
		List<FaceInfo> wire = new ArrayList<>(faces.size());
		for (int n = 0; n < faces.size(); n++) {
			FaceCache.Face face = faces.get(n);
			wire.add(FaceInfo.create()
				.setIndex(n)
				.setX(face.getX())
				.setY(face.getY())
				.setW(face.getW())
				.setH(face.getH())
				.setCluster(face.getCluster()));
		}
		return wire;
	}

	/**
	 * Writes a suggestion onto every face nobody has decided anything about, see issue #127.
	 *
	 * <p>
	 * Derived here and written nowhere: a suggestion is
	 * {@link FaceInfo#getPerson() somebody} with {@link FaceInfo#isConfirmed() confirmed} false and
	 * the state still {@link FaceState#UNDECIDED}, which is exactly the field pair issue #125 left
	 * free for it. Only the detected faces are looked at — they stand first and in their own order,
	 * see {@link FaceTags#merge} — and among those only the ones no decision reached: a face that
	 * is confirmed is that person rather than a guess, and one that was rejected or called no face
	 * at all keeps what somebody said about it, see {@link Recognition}.
	 * </p>
	 */
	private void suggest(Map<String, List<FaceInfo>> byName, Map<String, List<FaceCache.Face>> cached,
			PeopleStore people) {
		if (byName.isEmpty() || _recognition.isEmpty()) {
			return;
		}
		Recognition.Match match = _recognition.matcher(people);
		if (match.isEmpty()) {
			return;
		}
		for (Map.Entry<String, List<FaceInfo>> entry : byName.entrySet()) {
			List<FaceCache.Face> faces = cached.get(entry.getKey());
			if (faces == null || faces.isEmpty()) {
				continue;
			}
			for (FaceInfo face : entry.getValue()) {
				// By its number, which is its position in the cache for a detection: a hidden
				// detection is not answered, so the answered list is not the cache's (issue #155).
				int n = face.getIndex();
				if (n < 0 || n >= faces.size()) {
					// A stored tag no detection matched: no embedding, no guess.
					continue;
				}
				if (face.getState() != FaceState.UNDECIDED || !face.getPerson().isEmpty()) {
					continue;
				}
				String person = match.personOf(faces.get(n).getEmbedding());
				if (!person.isEmpty()) {
					face.setPerson(person);
					face.setConfirmed(false);
				}
			}
		}
	}

	/**
	 * Whether the given album still has photographs nobody has looked at, see
	 * {@link AlbumInfo#isFacesPending()}.
	 */
	public boolean pending(AlbumInfo album, File folder) {
		if (!isEnabled()) {
			return false;
		}
		return pending(album, folder, new FaceCache(folder), new HashCache(folder).storedHashByName());
	}

	private boolean pending(AlbumInfo album, File folder, FaceCache cache, Map<String, String> hashByName) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				if (missing((ImagePart) part, folder, cache, hashByName)) {
					return true;
				}
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					if (missing(image, folder, cache, hashByName)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private boolean missing(ImagePart image, File folder, FaceCache cache, Map<String, String> hashByName) {
		if (!isPhotograph(image)) {
			return false;
		}
		if (_failed.containsKey(new File(folder, image.getName()).getAbsolutePath())) {
			// Looked at and refused; coming back will not help, so nothing is pending.
			return false;
		}
		String hash = hashByName.get(image.getName());
		return hash == null || !cache.knows(hash);
	}

	// --- The crops. ---

	/** What a request for a face crop is answered with. */
	public static final class Crop {

		private final File _file;

		private final String _reason;

		Crop(File file, String reason) {
			_file = file;
			_reason = reason;
		}

		/** The JPEG to serve, <code>null</code> when there is none. */
		public File getFile() {
			return _file;
		}

		/** Why there is none, <code>null</code> when there is one. */
		public String getReason() {
			return _reason;
		}
	}

	/**
	 * The crop of one face of one photograph, made from the preview and cached beside it.
	 *
	 * <p>
	 * Written to a temporary name and moved into place, like every other generated file, so that
	 * nothing ever serves a half-written image.
	 * </p>
	 *
	 * @param image
	 *        The photograph the face was found in.
	 * @param index
	 *        Which of its faces, see {@link FaceInfo#getIndex()}.
	 */
	public Crop crop(File image, int index) {
		return crop(image, index, Collections.<FaceTag> emptyList());
	}

	/**
	 * The crop of one answered face of one photograph, see issue #155.
	 *
	 * <p>
	 * The numbering of the answer, see {@link FaceTags#answer(List, List, PeopleStore)}: a detection
	 * is cut by the detector's box, a stored tag no detection matches — a face somebody marked by
	 * hand, a decision the detector no longer finds — by the tag's own box, which is in the same raw
	 * raster. Both are cut the same way, from the original where the preview is too small, and
	 * cached under the name of issue #141, which is built from the content hash and the box and so
	 * knows nothing of which of the two a box came from. A face that is not answered — a detection
	 * somebody called no face — has no crop either.
	 * </p>
	 *
	 * @param tags
	 *        What the album stores about this photograph, see {@link ImagePart#getTags()}.
	 */
	public Crop crop(File image, int index, List<FaceTag> tags) {
		if (!isEnabled()) {
			return new Crop(null, "This library does not look for faces.");
		}
		File folder = image.getParentFile();
		String hash = new HashCache(folder).storedHashByName().get(image.getName());
		if (hash == null) {
			return new Crop(null, "Nothing is known about this image.");
		}
		List<FaceCache.Face> faces = new FaceCache(folder).facesOf(hash);
		FaceTags.Answer answer = FaceTags.answer(infos(faces), tags, null);
		FaceInfo answered = answer.byIndex(index);
		if (answered == null) {
			return new Crop(null, "There is no such face in this image.");
		}
		FaceCache.Face face = answer.isDetection(answered)
			? faces.get(index)
			: new FaceCache.Face(answered.getX(), answered.getY(), answered.getW(), answered.getH(), 0, null);
		File target = cropFile(image, hash, face);
		if (fresh(target, image, folder)) {
			return new Crop(target, null);
		}
		try {
			write(image, face, target);
			return new Crop(target, null);
		} catch (IOException | PreviewException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot cut the face out of '" + image.getAbsolutePath() + "': "
				+ ex.getMessage(), ex);
			return new Crop(null, "The face could not be cut out of this image.");
		}
	}

	/**
	 * Whether the given cached crop may still be served, see issue #141.
	 *
	 * <p>
	 * Its name already says which face it was cut for, so what is left to ask is whether anything it
	 * was cut <em>from</em> has moved on: the photograph itself, the preview rule
	 * ({@link PreviewCache#lastUpdate()}) — and the album's {@link FaceCache}, which is rewritten
	 * exactly when the faces of this album change. The last one is belt and braces for a library
	 * whose crops were cut under the naming of issue #124: those crops carry an index and are never
	 * addressed again, but a crop that happens to be spelled like one of ours would be, and a
	 * re-described album must never serve anything older than its own detections.
	 * </p>
	 */
	private static boolean fresh(File target, File image, File folder) {
		if (!target.isFile()) {
			return false;
		}
		long written = target.lastModified();
		return written >= image.lastModified() && written >= PreviewCache.lastUpdate()
			&& written >= FaceCache.file(folder).lastModified();
	}

	/**
	 * Where the crop of the given face of the given photograph is cached, see issue #141.
	 *
	 * <p>
	 * <b>A crop is named after the face it shows, never after a position in a list.</b> The name
	 * carries the photograph (so that the crops of one file can be found and thrown away together)
	 * and a {@link #cropToken(String, FaceCache.Face) token} of what identifies the face: the
	 * contents the detections are keyed by and the box that was cut out. Re-describing a photograph
	 * renumbers its faces — which is what issue #140's walk did to every library — and under a name
	 * built from an index the crop of the old face would go on being served under the new number,
	 * showing the wrong person beside a correct box. Under this name a renumbering simply misses the
	 * cache and the crop is cut anew; a photograph that is renamed or moved (issue #47, which
	 * abandons the cache directory) needs nothing at all.
	 * </p>
	 */
	public static File cropFile(File image, String hash, FaceCache.Face face) {
		return new File(CacheRefresh.cacheDir(image.getParentFile()),
			CROP_PREFIX + image.getName() + "-" + cropToken(hash, face) + "." + CROP_EXTENSION);
	}

	/**
	 * Where the crop of the <code>index</code>-th face of the given photograph is cached, looked up
	 * through the album's caches; <code>null</code> when there is no such face.
	 */
	public static File cropFile(File image, int index) {
		File folder = image.getParentFile();
		String hash = new HashCache(folder).storedHashByName().get(image.getName());
		if (hash == null) {
			return null;
		}
		List<FaceCache.Face> faces = new FaceCache(folder).facesOf(hash);
		if (index < 0 || index >= faces.size()) {
			return null;
		}
		return cropFile(image, hash, faces.get(index));
	}

	/**
	 * What tells one face of a photograph from another in a file name, see issue #141.
	 *
	 * <p>
	 * The contents of the file (the SHA-256 the {@link HashCache} beside the photographs knows and
	 * the {@link FaceCache} is keyed by) and the box in the raw raster, spelled to four decimals —
	 * a ten-thousandth of the picture, far below a pixel of anything shown, so that a box that is
	 * really the same face found again keeps its crop. Hexadecimal behind a leading
	 * <code>f</code>: no dash, so the photograph's own name stays readable in front of it, and
	 * never a number, so such a name can never be one issue #124 wrote.
	 * </p>
	 */
	static String cropToken(String hash, FaceCache.Face face) {
		String identity = hash + '|' + box(face.getX()) + ',' + box(face.getY()) + ','
			+ box(face.getW()) + ',' + box(face.getH());
		byte[] digest;
		try {
			digest = java.security.MessageDigest.getInstance("SHA-256")
				.digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException("No SHA-256 here.", ex);
		}
		StringBuilder result = new StringBuilder(CROP_TOKEN_LENGTH);
		result.append(CROP_TOKEN_MARK);
		for (int n = 0; result.length() < CROP_TOKEN_LENGTH; n++) {
			result.append(Character.forDigit((digest[n] >> 4) & 0xF, 16));
			result.append(Character.forDigit(digest[n] & 0xF, 16));
		}
		return result.toString();
	}

	/** One coordinate of a box, as it goes into a {@link #cropToken(String, FaceCache.Face)}. */
	private static String box(double value) {
		return String.format(java.util.Locale.ROOT, "%.4f", Double.valueOf(value));
	}

	/**
	 * Throws away every cached crop of the given photograph, see issue #141.
	 *
	 * <p>
	 * Called where a photograph is described again: its faces are renumbered and its boxes have
	 * moved, so what was cut from it says nothing any more. The crops of the old naming
	 * (<code>face-&lt;name&gt;-&lt;n&gt;.jpg</code>, which a library written before this build is
	 * full of) go in the same sweep — they would never be addressed again and must not lie there
	 * forever.
	 * </p>
	 *
	 * <p>
	 * Only names this server itself writes are ever touched: the prefix is this photograph's, what
	 * follows is an index or a token of ours, and {@link CacheRefresh#isGenerated(String)} — the one
	 * rule that says what the cache directory holds of the server's — has to name it too. A file
	 * somebody else put there stays, whatever it is called.
	 * </p>
	 *
	 * @return How many files were thrown away.
	 */
	static int dropCrops(File image) {
		File cacheDir = CacheRefresh.cacheDir(image.getParentFile());
		File[] files = cacheDir.listFiles();
		if (files == null) {
			return 0;
		}
		String prefix = CROP_PREFIX + image.getName() + "-";
		int dropped = 0;
		for (File file : files) {
			String name = file.getName();
			if (!file.isFile() || !name.startsWith(prefix) || !CacheRefresh.isGenerated(name)) {
				continue;
			}
			if (!isCropName(name.substring(prefix.length()))) {
				continue;
			}
			if (file.delete()) {
				dropped++;
			} else {
				LOG.log(Level.WARNING, "Cannot delete the stale crop '" + file.getAbsolutePath() + "'.");
			}
		}
		return dropped;
	}

	/**
	 * Whether what follows a photograph's name in a cache directory is a crop this server wrote.
	 *
	 * @param suffix
	 *        Everything behind <code>face-&lt;name&gt;-</code>, the extension included.
	 */
	private static boolean isCropName(String suffix) {
		String plain = suffix.endsWith(PreviewCache.TMP_SUFFIX)
			? suffix.substring(0, suffix.length() - PreviewCache.TMP_SUFFIX.length())
			: suffix;
		String dot = "." + CROP_EXTENSION;
		if (!plain.toLowerCase(java.util.Locale.ROOT).endsWith(dot)) {
			return false;
		}
		String key = plain.substring(0, plain.length() - dot.length());
		if (key.isEmpty()) {
			return false;
		}
		if (key.charAt(0) == CROP_TOKEN_MARK) {
			// A token of issue #141: the mark and hexadecimal.
			if (key.length() != CROP_TOKEN_LENGTH) {
				return false;
			}
			for (int n = 1; n < key.length(); n++) {
				if (Character.digit(key.charAt(n), 16) < 0) {
					return false;
				}
			}
			return true;
		}
		// An index of issue #124.
		for (int n = 0; n < key.length(); n++) {
			if (key.charAt(n) < '0' || key.charAt(n) > '9') {
				return false;
			}
		}
		return true;
	}

	private void write(File image, FaceCache.Face face, File target) throws IOException, PreviewException {
		Orientation exif = exifOrientation(image);
		BufferedImage crop = fromOriginal(image, face, exif);
		if (crop == null) {
			crop = fromPreview(image, face, exif);
		}
		store(crop, target);
	}

	/**
	 * The crop cut from the original, or <code>null</code> where the preview is good enough or the
	 * file cannot be region-decoded, see issue #140.
	 *
	 * <p>
	 * The box is stored in the raw raster of the file, so the region is the box plus its margin as it
	 * stands, and what is read is turned upright afterwards — the crop from the preview is upright
	 * and this one shows the same face at the same framing, only sharper.
	 * </p>
	 *
	 * <p>
	 * A file no reader can decode a region of (an exotic format, a reader that refuses a source
	 * region) is not a failure: it falls back to the preview with one line in the log, exactly as a
	 * machine without a detector serves albums without faces.
	 * </p>
	 */
	private BufferedImage fromOriginal(File image, FaceCache.Face face, Orientation exif) {
		try {
			int[] raster = rasterOf(image);
			if (!downscaled(exif, raster)) {
				// The preview is this photograph, pixel for pixel; cutting from the file gains nothing.
				return null;
			}
			double[] content = previewContent(exif, raster);
			double[] upright = Faces.toUpright(exif, face.getX(), face.getY(), face.getW(), face.getH());
			double previewWidth = upright[2] * (1 + 2 * CROP_MARGIN) * content[0];
			double previewHeight = upright[3] * (1 + 2 * CROP_MARGIN) * content[1];
			if (Math.min(previewWidth, previewHeight) >= CROP_MIN_PIXELS) {
				// The preview carries this face at a size the editor can show; issue #124's way.
				return null;
			}

			double left = face.getX() * raster[0];
			double top = face.getY() * raster[1];
			double width = face.getW() * raster[0];
			double height = face.getH() * raster[1];
			double marginX = width * CROP_MARGIN;
			double marginY = height * CROP_MARGIN;
			Originals.Region region = Originals.decodeShortSide(image, left - marginX, top - marginY,
				left + width + marginX, top + height + marginY, CROP_MAX_PIXELS);
			return bounded(Originals.upright(region.getImage(), exif));
		} catch (IOException | RuntimeException ex) {
			LOG.info("Cutting the face out of the preview of '" + image.getAbsolutePath()
				+ "': the original cannot be read in pieces (" + FaceDetection.reason(ex) + ").");
			return null;
		}
	}

	/** The given raster, brought down to {@link #CROP_MAX_PIXELS} on its shorter side. */
	private static BufferedImage bounded(BufferedImage crop) {
		int side = Math.min(crop.getWidth(), crop.getHeight());
		if (side <= CROP_MAX_PIXELS) {
			return crop;
		}
		double scale = ((double) CROP_MAX_PIXELS) / side;
		int width = Math.max(1, (int) Math.round(crop.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(crop.getHeight() * scale));
		BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D graphics = result.createGraphics();
		try {
			graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(crop, 0, 0, width, height, null);
		} finally {
			graphics.dispose();
		}
		return result;
	}

	/** The crop cut from the preview, which is issue #124's way and the fallback of issue #140. */
	private static BufferedImage fromPreview(File image, FaceCache.Face face, Orientation exif)
			throws IOException, PreviewException {
		File preview = PreviewCache.createPreview(image);
		BufferedImage source = ImageIO.read(preview);
		if (source == null) {
			throw new IOException("Cannot read the preview of '" + image.getName() + "'.");
		}
		// Back into the frame the preview is drawn in; the box is kept in the file's own raster.
		double[] box = Faces.toUpright(exif, face.getX(), face.getY(), face.getW(), face.getH());
		double[] content = content(image, exif, source.getWidth(), source.getHeight());
		double marginX = box[2] * CROP_MARGIN;
		double marginY = box[3] * CROP_MARGIN;
		int left = clamp((int) Math.floor(content[0] + (box[0] - marginX) * content[2]),
			0, source.getWidth() - 1);
		int top = clamp((int) Math.floor(content[1] + (box[1] - marginY) * content[3]),
			0, source.getHeight() - 1);
		int right = clamp((int) Math.ceil(content[0] + (box[0] + box[2] + marginX) * content[2]),
			left + 1, source.getWidth());
		int bottom = clamp((int) Math.ceil(content[1] + (box[1] + box[3] + marginY) * content[3]),
			top + 1, source.getHeight());

		return source.getSubimage(left, top, right - left, bottom - top);
	}

	/** Writes the given crop, through a temporary name, so that nothing serves a half-written file. */
	private static void store(BufferedImage crop, File target) throws IOException {
		File cacheDir = target.getParentFile();
		if (!cacheDir.exists()) {
			cacheDir.mkdirs();
		}
		File tmp = new File(cacheDir, target.getName() + PreviewCache.TMP_SUFFIX);
		try {
			if (!ImageIO.write(crop, CROP_EXTENSION, tmp)) {
				throw new IOException("No JPEG writer available.");
			}
			try {
				Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException | UnsupportedOperationException ex) {
				Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			tmp.delete();
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	/** The given fraction, brought into the unit interval. */
	private static double unit(double value) {
		return Math.max(0, Math.min(1, value));
	}

	// --- The end. ---

	/** Stops the background detection; what is done is in the album caches already. */
	public void shutdown() {
		_stopped = true;
		ExecutorService indexer;
		synchronized (this) {
			indexer = _indexer;
			_indexer = null;
		}
		if (indexer != null) {
			indexer.shutdownNow();
			try {
				indexer.awaitTermination(2, TimeUnit.SECONDS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Forgets that a photograph of the given folder ever failed, see issue #98.
	 *
	 * <p>
	 * And with it what that album contributed to the recognition of issue #127: the cache the
	 * embeddings were read from has just been thrown away, so the people confirmed there stop being
	 * recognised elsewhere until the album is indexed again. The decisions themselves are untouched
	 * — they are the album's, not the cache's.
	 * </p>
	 */
	public int forget(File folder) {
		_recognition.forget(folder);
		String prefix = folder.getAbsolutePath() + File.separator;
		int forgotten = 0;
		for (String key : new ArrayList<>(_failed.keySet())) {
			if (key.startsWith(prefix) && key.indexOf(File.separatorChar, prefix.length()) < 0
				&& _failed.remove(key) != null) {
				forgotten++;
			}
		}
		return forgotten;
	}

	/** The given folder and every folder below it that is not the server's own. */
	private static List<File> tree(File root) {
		List<File> result = new ArrayList<>();
		if (!root.isDirectory()) {
			return result;
		}
		Deque<File> pending = new ArrayDeque<>();
		pending.add(root);
		while (!pending.isEmpty()) {
			File folder = pending.removeFirst();
			result.add(folder);
			File[] children = folder.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
			if (children != null) {
				Collections.addAll(pending, children);
			}
		}
		return result;
	}

	/** Clears what {@link #derive(AlbumInfo, File)} put there, see {@code AlbumDate.clearDerived}. */
	public static boolean clear(FolderResource resource) {
		if (!(resource instanceof AlbumInfo)) {
			return false;
		}
		AlbumInfo album = (AlbumInfo) resource;
		boolean changed = false;
		if (album.isFacesPending()) {
			album.setFacesPending(false);
			changed = true;
		}
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				changed |= clear((ImagePart) part);
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					changed |= clear(image);
				}
			}
		}
		return changed;
	}

	private static boolean clear(ImagePart image) {
		if (image.getFaces().isEmpty()) {
			return false;
		}
		image.setFaces(Collections.emptyList());
		return true;
	}
}
