/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.CatchUpStatus;
import de.haumacher.imageServer.shared.model.CatchUpStep;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the background catch-up of issue #236: a library brought up to date before anybody
 * opens it.
 *
 * <p>
 * "My library contains hundreds or even thousands of albums &mdash; and if the first visit requires
 * thumbnail creation and face indexing, each first visit is a pain."
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestCatchUp extends TestCase {

	private static final long TIMEOUT = 180_000;

	private static final String NEW = "2023-09-01 New";

	private static final String SKI = "Trips/2021-06-01 Ski";

	private static final String OLD = "2019-03-01 Old";

	private static final String UNDATED = "Undated";

	/** A detector that counts what it is handed and finds one face in the middle. */
	private static final class Counting implements FaceDetection.Detector {

		final Map<String, AtomicInteger> _seen = new ConcurrentHashMap<>();

		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			String album = preview.getParentFile().getParentFile().getName();
			_seen.computeIfAbsent(album + "/" + preview.getName(), x -> new AtomicInteger()).incrementAndGet();
			BufferedImage image = ImageIO.read(preview);
			List<FaceDetection.Detected> faces = new ArrayList<>();
			faces.add(new FaceDetection.Detected(image.getWidth() / 3.0, image.getHeight() / 3.0,
				image.getWidth() / 3.0, image.getHeight() / 3.0, 0.9, new float[128]));
			return new FaceDetection.Result(image.getWidth(), image.getHeight(), faces);
		}

		int total() {
			int result = 0;
			for (AtomicInteger count : _seen.values()) {
				result += count.get();
			}
			return result;
		}
	}

	private Path _base;

	private ImageServlet _servlet;

	private Counting _detector;

	private String _adminToken;

	private final List<String> _transcodes = new CopyOnWriteArrayList<>();

	/** The log message after which a request arrives, see {@link #_transcodeLog}. */
	private volatile String _stopAfter;

	private final CountDownLatch _stopped = new CountDownLatch(1);

	private final Handler _transcodeLog = new Handler() {
		@Override
		public void publish(LogRecord record) {
			String message = record.getMessage();
			if (message != null && message.startsWith("Transcoding the ")) {
				_transcodes.add(message);
			}
			if (message != null && _stopAfter != null && message.startsWith(_stopAfter)) {
				// A request arrives the moment this rendition is there: the background stops before
				// its next unit, deterministically.
				Background.requestStarted();
				_stopped.countDown();
			}
		}

		@Override
		public void flush() {
			// Nothing kept.
		}

		@Override
		public void close() {
			// Nothing kept.
		}
	};

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-catch-up");
		_detector = new Counting();
		FaceDetection.setDetector(_detector);
		Background.setQuietMillis(300);
		Logger.getLogger(VideoRenditions.class.getName()).addHandler(_transcodeLog);
	}

	@Override
	protected void tearDown() throws Exception {
		Logger.getLogger(VideoRenditions.class.getName()).removeHandler(_transcodeLog);
		Background.setQuietMillis(Background.QUIET_MILLIS);
		PreviewCache.setHook(null);
		FaceDetection.setDetector(null);
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	// --- The library. ---

	/** Four albums, the newest with a video and a cropped photo, one below a folder that shows it. */
	private void library(boolean faces) throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Catch-up", SpaceStore.ANONYMOUS_PUBLIC, "",
			faces ? SpaceStore.FACES_ON : SpaceStore.FACES_OFF));

		photo(OLD, "a1.jpg", Color.RED);
		photo(OLD, "a2.jpg", Color.GREEN);

		photo(NEW, "n1.jpg", Color.BLUE);
		photo(NEW, "n2.jpg", Color.YELLOW);
		test.de.haumacher.valbum.GenerateTestAlbum.recordTinyVideo(_base.resolve(NEW).resolve("clip.mp4").toFile());
		sidecar(NEW, "[\"AlbumInfo\",{\"title\":\"New\",\"indexPicture\":{\"image\":\"n2.jpg\",\"scale\":1.0},"
			+ "\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"n1.jpg\",\"width\":800,\"height\":600}],"
			+ "[\"ImagePart\",{\"name\":\"n2.jpg\",\"width\":800,\"height\":600,"
			+ "\"crop\":{\"x\":0.25,\"y\":0.25,\"w\":0.5,\"h\":0.5}}],"
			+ "[\"ImagePart\",{\"name\":\"clip.mp4\",\"kind\":\"VIDEO\",\"width\":160,\"height\":120}]]}]");

		photo(SKI, "s1.jpg", Color.CYAN);
		sidecar(SKI, "[\"AlbumInfo\",{\"title\":\"Ski\",\"indexPicture\":{\"image\":\"s1.jpg\",\"scale\":1.0},"
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"s1.jpg\",\"width\":800,\"height\":600,"
			+ "\"crop\":{\"x\":0.1,\"y\":0.2,\"w\":0.6,\"h\":0.5}}]]}]");
		sidecar("Trips", "[\"ListingInfo\",{\"title\":\"Trips\",\"index\":\"2021-06-01 Ski\"}]");

		photo(UNDATED, "u1.jpg", Color.MAGENTA);

		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		store.store();
	}

	private void photo(String album, String name, Color color) throws IOException {
		Path folder = _base.resolve(album);
		Files.createDirectories(folder);
		BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 800, 600);
		g.setColor(Color.BLACK);
		g.drawString(album + "/" + name, 20, 300);
		g.dispose();
		assertTrue(ImageIO.write(image, "jpg", folder.resolve(name).toFile()));
	}

	private void sidecar(String folder, String json) throws IOException {
		Files.createDirectories(_base.resolve(folder));
		Files.write(_base.resolve(folder).resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	private ImageServlet start() throws Exception {
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), auth, "", SpaceStore.load(_base, ""));
		_servlet.init();
		_adminToken = Codes.signInAdmin(auth, "Phone", "haui").getToken();
		pipeline().setQuietMillis(200);
		// Nobody browses in these tests: what is changed by hand is found by the sweep.
		pipeline().setSweepMillis(300);
		return _servlet;
	}

	private FolderPipeline pipeline() {
		return _servlet.index().pipeline();
	}

	private void awaitCatchUp() throws Exception {
		assertTrue("The hash pass ends.", _servlet.index().awaitPass(TIMEOUT));
		assertTrue("The catch-up ends.", pipeline().awaitIdle(TIMEOUT));
	}

	private File file(String album, String name) {
		return _base.resolve(album).resolve(name).toFile();
	}

	private static final String[] PHOTOS = {
		OLD + "/a1.jpg", OLD + "/a2.jpg", NEW + "/n1.jpg", NEW + "/n2.jpg", SKI + "/s1.jpg", UNDATED + "/u1.jpg" };

	/** Every file name below the space but the server's own, without what the server may write. */
	private Set<String> userFiles() throws IOException {
		Set<String> result = new TreeSet<>();
		try (Stream<Path> files = Files.walk(_base)) {
			files.filter(Files::isRegularFile).forEach(file -> {
				String path = _base.relativize(file).toString().replace(File.separatorChar, '/');
				if (!path.startsWith(UserStore.DIRECTORY_NAME + "/")) {
					result.add(path);
				}
			});
		}
		return result;
	}

	// --- The tests. ---

	public void testAFreshLibraryIsBroughtUpToDateWithoutARequest() throws Exception {
		library(true);
		Set<String> before = userFiles();
		start();
		_servlet.startIndexing();
		awaitCatchUp();

		for (String photo : PHOTOS) {
			File original = _base.resolve(photo).toFile();
			File preview = PreviewCache.previewFile(original);
			assertTrue("Every photo has its preview: " + photo, preview.isFile());
			assertTrue("And its record: " + photo, PreviewCache.recordOf(preview).isFile());
			String album = photo.substring(0, photo.lastIndexOf('/'));
			assertEquals("The detector looked at every photo once: " + photo, 1,
				_detector._seen.get(album.substring(album.lastIndexOf('/') + 1) + "/" + preview.getName()).get());
		}
		assertEquals("A video is never handed to the detector.", PHOTOS.length, _detector.total());
		for (String album : new String[] { OLD, NEW, SKI, UNDATED }) {
			assertTrue("Faces are stored: " + album, FaceCache.file(_base.resolve(album).toFile()).isFile());
		}

		File cropped = file(NEW, "n2.jpg");
		assertTrue("The cut preview of a cropped photo.", PreviewCache.croppedPreviewFile(cropped,
			Crops.renditionRegion(Crops.findImage((AlbumInfo) ResourceCache.sidecar(cropped.getParentFile()), "n2.jpg")))
			.isFile());

		// The tile of every album and of the folder above one, exactly as the listing asks for them.
		for (String folder : new String[] { OLD, NEW, SKI, "Trips", UNDATED }) {
			File dir = _base.resolve(folder).toFile();
			ThumbnailInfo tile = ResourceCache.tilePicture(dir);
			assertNotNull("A tile: " + folder, tile);
			File image = new File(dir, tile.getImage());
			assertTrue("The tile's picture: " + folder, PreviewCache.previewFile(image).isFile());
			if (tile.getCrop() != null) {
				double[] region = { tile.getCrop().getX(), tile.getCrop().getY(), tile.getCrop().getW(),
					tile.getCrop().getH() };
				assertTrue("The tile's cut: " + folder, PreviewCache.croppedPreviewFile(image, region).isFile());
			}
		}
		assertNotNull("The folder shows the album it chose, cut.",
			ResourceCache.tilePicture(_base.resolve("Trips").toFile()).getCrop());

		File video = file(NEW, "clip.mp4");
		assertTrue("The poster frame.", PreviewCache.previewFile(video).isFile());
		for (VideoRenditions.Kind kind : VideoRenditions.Kind.values()) {
			File rendition = VideoRenditions.file(video, kind);
			assertTrue("The video's " + kind + " rendition.", rendition.isFile() && rendition.length() > 0);
			assertTrue("Recorded against its original.", PreviewCache.recordOf(rendition).isFile());
		}

		Set<String> after = userFiles();
		for (String path : after) {
			if (before.contains(path)) {
				continue;
			}
			String name = path.substring(path.lastIndexOf('/') + 1);
			assertTrue("Nothing but the server's sidecars beside a photo: " + path,
				path.contains("/" + PreviewCache.CACHE_DIRECTORY_NAME + "/")
					|| name.equals(de.haumacher.imageServer.upload.HashCache.FILE_NAME));
		}
		assertTrue("Every original is still there.", after.containsAll(before));

		CatchUpStatus status = status(_adminToken);
		assertEquals("Four albums; the folder above one is none.", 4, status.getAlbumsTotal());
		assertEquals(4, status.getAlbumsDone());
		assertEquals(0, status.getVideosRemaining());
		assertEquals(CatchUpStep.IDLE, status.getStep());
		assertEquals("Nothing failed: " + status.getFailure(), "", status.getFailure());
	}

	/**
	 * Probe: an album moved by hand on the disk (#235's case) after the pass began is brought up to
	 * date where it is now, a photo added to it afterwards too, and nothing fails over its old path.
	 */
	public void testProbeAnAlbumMovedByHandIsFinishedWhereItNowIs() throws Exception {
		library(false);
		start();
		_servlet.startIndexing();
		Path moved = _base.resolve("Archive").resolve("2019-03-01 Old");
		Files.createDirectories(moved.getParent());
		Files.move(_base.resolve(OLD), moved);
		awaitCatchUp();
		photo("Archive/2019-03-01 Old", "a3.jpg", Color.ORANGE);
		long end = System.currentTimeMillis() + TIMEOUT;
		File added = moved.resolve("a3.jpg").toFile();
		while (!PreviewCache.previewFile(added).isFile() && System.currentTimeMillis() < end) {
			Thread.sleep(100);
		}
		awaitCatchUp();
		for (String name : new String[] { "a1.jpg", "a2.jpg", "a3.jpg" }) {
			assertTrue("Previewed where it is now: " + name,
				PreviewCache.previewFile(moved.resolve(name).toFile()).isFile());
		}
		assertFalse("Nothing is recreated at the old place.", Files.exists(_base.resolve(OLD)));
		CatchUpStatus status = status(_adminToken);
		assertEquals("Nothing failed: " + status.getFailure() + " in " + status.getFailureFolder(), "",
			status.getFailure());
	}

	public void testTheNewestAlbumsComeFirst() throws Exception {
		library(false);
		start();
		List<String> order = new CopyOnWriteArrayList<>();
		pipeline().addStep(new FolderPipeline.Step() {
			@Override
			public String name() {
				return "order";
			}

			@Override
			public FolderPipeline.Outcome run(File folder) {
				order.add(_base.relativize(folder.toPath()).toString().replace(File.separatorChar, '/'));
				return FolderPipeline.Outcome.DONE;
			}
		});
		_servlet.startIndexing();
		awaitCatchUp();
		assertEquals("By the album's date, descending; undated last.", Arrays.asList(NEW, SKI, OLD, UNDATED), order);
	}

	public void testAStoppedPassResumesWithoutRedoingAnything() throws Exception {
		library(true);
		Path album = _base.resolve(NEW);
		test.de.haumacher.valbum.GenerateTestAlbum.recordTinyVideo(album.resolve("clip-2.mp4").toFile());
		test.de.haumacher.valbum.GenerateTestAlbum.recordTinyVideo(album.resolve("clip-3.mp4").toFile());
		_stopAfter = "The teaser rendition of 'clip-2.mp4' is ready";
		start();
		_servlet.startIndexing();

		// The first unit of the videos done, the server stops before the next one.
		File first = VideoRenditions.file(file(NEW, "clip-2.mp4"), VideoRenditions.Kind.TEASER);
		try {
			assertTrue("The first teaser is made.", _stopped.await(TIMEOUT, TimeUnit.MILLISECONDS));
			assertTrue(first.isFile());
			// The background reaches its next unit and waits there.
			Thread.sleep(300);
			Map<String, Long> previews = new HashMap<>();
			for (String photo : PHOTOS) {
				File preview = PreviewCache.previewFile(_base.resolve(photo).toFile());
				assertTrue("The photos are done before any video: " + photo, preview.isFile());
				previews.put(photo, Long.valueOf(preview.lastModified()));
			}
			int detections = _detector.total();
			_servlet.destroy();
			_servlet = null;

			String record = new String(Files.readAllBytes(_base.resolve(UserStore.DIRECTORY_NAME)
				.resolve(FolderPipeline.FILE_NAME)), StandardCharsets.UTF_8);
			assertTrue("The finished unit is recorded: " + record, record.contains("\"teaser:clip-2.mp4\""));
			int transcodes = _transcodes.size();
			assertEquals("The first unit only: " + _transcodes, 1, transcodes);
			_stopAfter = null;
			Background.requestEnded();

			start();
			_servlet.startIndexing();
			awaitCatchUp();

			for (String photo : PHOTOS) {
				assertEquals("No preview made again: " + photo, previews.get(photo),
					Long.valueOf(PreviewCache.previewFile(_base.resolve(photo).toFile()).lastModified()));
			}
			assertEquals("No photo looked at again for faces.", detections, _detector.total());
			for (String name : new String[] { "clip.mp4", "clip-2.mp4", "clip-3.mp4" }) {
				for (VideoRenditions.Kind kind : VideoRenditions.Kind.values()) {
					assertTrue("Nothing lost: " + kind + " of " + name, VideoRenditions.file(file(NEW, name), kind).isFile());
				}
			}
			Map<String, Integer> counts = new HashMap<>();
			for (String transcode : _transcodes) {
				counts.merge(transcode, 1, Integer::sum);
			}
			assertEquals("Six renditions, each made once: " + _transcodes, 6, counts.size());
			for (Map.Entry<String, Integer> count : counts.entrySet()) {
				assertEquals("Nothing transcoded twice: " + count.getKey(), Integer.valueOf(1), count.getValue());
			}
		} finally {
			if (Background.busy()) {
				Background.requestEnded();
			}
		}
	}

	public void testRequestsGoFirst() throws Exception {
		library(false);
		start();
		File asked = file(OLD, "a1.jpg");
		CountDownLatch inRequest = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<String> made = new CopyOnWriteArrayList<>();
		PreviewCache.setHook(new PreviewCache.Hook() {
			@Override
			public void permitAcquired(File file) {
				// Nothing.
			}

			@Override
			public void generationStarted(File file) {
				made.add(file.getName());
				if (file.equals(asked)) {
					inRequest.countDown();
					try {
						release.await(TIMEOUT, TimeUnit.MILLISECONDS);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				}
			}

			@Override
			public void generationFinished(File file) {
				// Nothing.
			}
		});

		// A thumbnail request, held in the middle of its work.
		List<FakeResponse> answers = new CopyOnWriteArrayList<>();
		Thread request = new Thread(() -> {
			try {
				answers.add(get("/" + OLD + "/a1.jpg", "tn", _adminToken));
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		});
		request.start();
		assertTrue(inRequest.await(TIMEOUT, TimeUnit.MILLISECONDS));

		_servlet.startIndexing();
		assertTrue(_servlet.index().awaitPass(TIMEOUT));
		Thread.sleep(1500);
		assertEquals("Nothing made in the background while the request is served.", Arrays.asList("a1.jpg"), made);
		assertTrue("The admin sees why.", status(_adminToken).isYielding());

		release.countDown();
		request.join(TIMEOUT);
		assertEquals(200, answers.get(0).status());
		assertTrue(pipeline().awaitIdle(TIMEOUT));
		for (String photo : PHOTOS) {
			assertTrue("Then the background goes on: " + photo,
				PreviewCache.previewFile(_base.resolve(photo).toFile()).isFile());
		}
		assertEquals("The asked one is not made twice.", 1, made.stream().filter("a1.jpg"::equals).count());
	}

	public void testAnAlbumUnderAReadOnlyFolderIsSaidOnceAndNotRetried() throws Exception {
		library(false);
		File old = _base.resolve(OLD).toFile();
		assertTrue(old.setWritable(false, false));
		try {
			if (old.canWrite()) {
				// Running as root: the mode is ignored, nothing to test.
				return;
			}
			start();
			_servlet.startIndexing();
			awaitCatchUp();
			assertFalse("Nothing could be stored there.",
				PreviewCache.previewFile(new File(old, "a1.jpg")).exists());
			assertTrue(de.haumacher.imageServer.pipeline.ReadOnlyFolders.isReadOnly(old));
			int before = _transcodes.size();

			// Noticed again: not worked on again while it is unchanged.
			pipeline().process(old);
			assertFalse(PreviewCache.previewFile(new File(old, "a1.jpg")).exists());
			assertEquals(before, _transcodes.size());
		} finally {
			old.setWritable(true, false);
			de.haumacher.imageServer.pipeline.ReadOnlyFolders.writtenAgain(old);
		}
	}

	// --- The status. ---

	private CatchUpStatus status(String token) throws Exception {
		FakeResponse response = get("/", ImageServlet.CATCH_UP_TYPE, token);
		assertEquals(response.body(), 200, response.status());
		return CatchUpStatus.readCatchUpStatus(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
	}

	private FakeResponse get(String pathInfo, String type, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}
}
