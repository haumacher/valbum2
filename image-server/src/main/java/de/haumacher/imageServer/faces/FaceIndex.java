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
	 * How much space is left around a face in a crop, as a fraction of the box.
	 *
	 * <p>
	 * A box that sits exactly on the eyes and the chin is not a portrait; a quarter of the box on
	 * every side makes a crop somebody recognises a person in, which is what the editor of issue
	 * #126 shows.
	 * </p>
	 */
	static final double CROP_MARGIN = 0.25;

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
		for (File image : images) {
			if (Thread.currentThread().isInterrupted() || _stopped) {
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
			if (cache.knows(hash) || _failed.containsKey(image.getAbsolutePath())) {
				continue;
			}
			try {
				cache.put(hash, detect(image));
				changed = true;
			} catch (IOException | RuntimeException ex) {
				String reason = FaceDetection.reason(ex);
				LOG.log(Level.WARNING,
					"Cannot look for faces in '" + image.getAbsolutePath() + "': " + reason, ex);
				_failed.put(image.getAbsolutePath(), reason);
			}
		}

		// What the album no longer holds is nobody's business; a hash that came back finds its
		// faces again, which is why the entries are keyed by contents and not by name.
		cache.retain(present);

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

		// The one walk of issue #127 as well: whoever was confirmed in this album is now described
		// by numbers this pass has just made sure are there.
		_recognition.observe(folder);
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
		FaceDetection.Result found = FaceDetection.detect(preview);
		double[] content = content(image, exif, found.getWidth(), found.getHeight());
		List<FaceCache.Face> result = new ArrayList<>();
		for (FaceDetection.Detected face : found.getFaces()) {
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
			result.add(new FaceCache.Face(raw[0], raw[1], raw[2], raw[3], face.getScore(),
				face.getEmbedding()));
		}
		return result;
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
	static boolean isPhotograph(ImagePart image) {
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
		// Deliberately without the suggestions of issue #127: this is the numbering a tagging
		// request names a face by, and a guess neither adds a face nor moves one.
		Map<String, List<FaceInfo>> detected = isEnabled()
			? facesByName(cachedByName(new FaceCache(folder), new HashCache(folder).storedHashByName()))
			: Collections.<String, List<FaceInfo>> emptyMap();
		return merged(album, detected, people);
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
			List<FaceCache.Face> faces = entry.getValue();
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
			result.put(entry.getKey(), wire);
		}
		return result;
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
			List<FaceInfo> answered = entry.getValue();
			for (int n = 0, size = Math.min(faces.size(), answered.size()); n < size; n++) {
				FaceInfo face = answered.get(n);
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
		if (!isEnabled()) {
			return new Crop(null, "This library does not look for faces.");
		}
		File folder = image.getParentFile();
		String hash = new HashCache(folder).storedHashByName().get(image.getName());
		if (hash == null) {
			return new Crop(null, "Nothing is known about this image.");
		}
		List<FaceCache.Face> faces = new FaceCache(folder).facesOf(hash);
		if (index < 0 || index >= faces.size()) {
			return new Crop(null, "There is no such face in this image.");
		}
		File target = cropFile(image, index);
		if (target.isFile() && target.lastModified() >= image.lastModified()
			&& target.lastModified() >= PreviewCache.lastUpdate()) {
			return new Crop(target, null);
		}
		try {
			write(image, faces.get(index), target);
			return new Crop(target, null);
		} catch (IOException | PreviewException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot cut the face out of '" + image.getAbsolutePath() + "': "
				+ ex.getMessage(), ex);
			return new Crop(null, "The face could not be cut out of this image.");
		}
	}

	/** Where the crop of one face of the given photograph is cached. */
	public static File cropFile(File image, int index) {
		return new File(CacheRefresh.cacheDir(image.getParentFile()),
			CROP_PREFIX + image.getName() + "-" + index + "." + CROP_EXTENSION);
	}

	private void write(File image, FaceCache.Face face, File target) throws IOException, PreviewException {
		File preview = PreviewCache.createPreview(image);
		BufferedImage source = ImageIO.read(preview);
		if (source == null) {
			throw new IOException("Cannot read the preview of '" + image.getName() + "'.");
		}
		Orientation exif = exifOrientation(image);
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

		BufferedImage crop = source.getSubimage(left, top, right - left, bottom - top);
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
