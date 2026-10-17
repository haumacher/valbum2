/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.content.ContentCache;
import de.haumacher.imageServer.content.ContentIndex;
import de.haumacher.imageServer.content.ContentText;
import de.haumacher.imageServer.content.Embedder;
import de.haumacher.imageServer.content.EmbedderUnavailableException;
import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.CatchUpStatus;
import de.haumacher.imageServer.shared.model.CatchUpStep;
import de.haumacher.imageServer.shared.model.ContentSwitch;
import de.haumacher.imageServer.shared.model.SpaceSettings;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the content search of issue #257 in the background: the vectors of every photo in
 * each album's cache, made by the embedder of the server, switched on per space.
 */
@SuppressWarnings("javadoc")
public class TestContentSearch extends TestCase {

	private static final long TIMEOUT = 180_000;

	private static final String NEW = "2023-09-01 New";

	private static final String OLD = "2019-03-01 Old";

	private static final String STAMP = "fake@0000001/256/fp32/1";

	/** The media of the library, each embedded once. */
	private static final String[] MEDIA = { OLD + "/a1.jpg", OLD + "/a2.jpg", NEW + "/n1.jpg", NEW + "/n2.jpg",
		NEW + "/clip.mp4" };

	/** The moment the author chose for the poster of the video, see issue #285. */
	private static final long POSTER = 1000;

	/**
	 * An embedder that counts what it is handed and answers vectors made of it: the same input, the
	 * same vector.
	 */
	static final class FakeEmbedder implements Embedder {

		volatile String _stamp = STAMP;

		volatile String _problem;

		/** Where a picture's embedding waits until it is let go, <code>null</code> for no wait. */
		volatile CountDownLatch _hold;

		final CountDownLatch _holding = new CountDownLatch(1);

		final List<byte[]> _images = new CopyOnWriteArrayList<>();

		final List<String> _texts = new CopyOnWriteArrayList<>();

		@Override
		public float[] embedText(String text, TextKind kind) throws IOException {
			return embedDocument(null, text);
		}

		@Override
		public float[] embedDocument(String title, String text) throws IOException {
			refuse();
			String document = Embedder.document(title, text);
			_texts.add(document);
			return vector(_stamp + document);
		}

		@Override
		public float[] embedImage(byte[] image) throws IOException {
			refuse();
			CountDownLatch hold = _hold;
			if (hold != null) {
				_holding.countDown();
				try {
					hold.await(TIMEOUT, TimeUnit.MILLISECONDS);
				} catch (InterruptedException ex) {
					throw new IOException(ex);
				}
			}
			_images.add(image);
			return vector(_stamp + Arrays.hashCode(image));
		}

		private void refuse() throws EmbedderUnavailableException {
			String problem = _problem;
			if (problem != null) {
				throw new EmbedderUnavailableException(problem);
			}
		}

		@Override
		public String stamp() {
			return _stamp;
		}

		@Override
		public String unavailability() {
			return _problem;
		}

		@Override
		public boolean awaitAvailable(Duration timeout) {
			return _problem == null;
		}

		@Override
		public void close() {
			// Nothing started.
		}

		static float[] vector(String seed) {
			Random random = new Random(seed.hashCode());
			float[] result = new float[DIMENSION];
			double sum = 0;
			for (int n = 0; n < DIMENSION; n++) {
				result[n] = (float) random.nextGaussian();
				sum += result[n] * result[n];
			}
			for (int n = 0; n < DIMENSION; n++) {
				result[n] /= (float) Math.sqrt(sum);
			}
			return result;
		}
	}

	private Path _base;

	private ImageServlet _servlet;

	private FakeEmbedder _embedder;

	private String _adminToken;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-content");
		_embedder = new FakeEmbedder();
		Background.setQuietMillis(100);
	}

	@Override
	protected void tearDown() throws Exception {
		Background.setQuietMillis(Background.QUIET_MILLIS);
		CountDownLatch hold = _embedder._hold;
		if (hold != null) {
			hold.countDown();
		}
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

	/** Two albums, the newer with a video whose poster is chosen and a description. */
	private void library(boolean content) throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Content", SpaceStore.ANONYMOUS_PUBLIC, "", SpaceStore.FACES_OFF,
			"", "", content ? SpaceStore.CONTENT_ON : SpaceStore.CONTENT_OFF));
		photo(OLD, "a1.jpg", Color.RED);
		photo(OLD, "a2.jpg", Color.GREEN);
		photo(NEW, "n1.jpg", Color.BLUE);
		photo(NEW, "n2.jpg", Color.YELLOW);
		test.de.haumacher.valbum.GenerateTestAlbum.recordTinyVideo(_base.resolve(NEW).resolve("clip.mp4").toFile());
		newSidecar("A walk in the park");
		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		store.store();
	}

	private void newSidecar(String description) throws IOException {
		sidecar(NEW, "[\"AlbumInfo\",{\"title\":\"New\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"n1.jpg\",\"width\":800,\"height\":600,\"comment\":\"" + description + "\"}],"
			+ "[\"ImagePart\",{\"name\":\"n2.jpg\",\"width\":800,\"height\":600,"
			+ "\"labels\":[{\"name\":\"flowers\"}]}],"
			+ "[\"ImagePart\",{\"name\":\"clip.mp4\",\"kind\":\"VIDEO\",\"width\":160,\"height\":120,"
			+ "\"poster\":{\"millis\":" + POSTER + "}}]]}]");
	}

	private void photo(String album, String name, Color color) throws IOException {
		Path folder = _base.resolve(album);
		Files.createDirectories(folder);
		BufferedImage image = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 1600, 1200);
		g.setColor(Color.BLACK);
		g.drawString(album + "/" + name, 20, 300);
		g.dispose();
		assertTrue(ImageIO.write(image, "jpg", folder.resolve(name).toFile()));
	}

	private void sidecar(String folder, String json) throws IOException {
		Files.createDirectories(_base.resolve(folder));
		Path tmp = _base.resolve(folder).resolve("index.json.tmp");
		Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
		Files.move(tmp, _base.resolve(folder).resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
	}

	private void start() throws Exception {
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), auth, "", SpaceStore.load(_base, ""));
		_servlet.init();
		_servlet.setEmbedder(_embedder);
		_servlet.content().setRetryMillis(200, 400);
		_adminToken = Codes.signInAdmin(auth, "Phone", "haui").getToken();
		pipeline().setQuietMillis(100);
		pipeline().setSweepMillis(300);
		_servlet.startIndexing();
	}

	private FolderPipeline pipeline() {
		return _servlet.index().pipeline();
	}

	private void awaitCatchUp() throws Exception {
		assertTrue("The hash pass ends.", _servlet.index().awaitPass(TIMEOUT));
		assertTrue("The catch-up ends.", pipeline().awaitIdle(TIMEOUT));
	}

	/** Waits until every medium of the library is described, then for the background to rest. */
	private void awaitDescribed() throws Exception {
		long end = System.currentTimeMillis() + TIMEOUT;
		while (!allDescribed()) {
			assertTrue("Every photo is described in time.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		awaitCatchUp();
	}

	private boolean allDescribed() {
		for (String medium : MEDIA) {
			File file = _base.resolve(medium).toFile();
			if (!ContentCache.read(file.getParentFile()).photos().containsKey(file.getName())) {
				return false;
			}
		}
		return true;
	}

	private File folder(String album) {
		return _base.resolve(album).toFile();
	}

	private void walk() {
		pipeline().execute(() -> pipeline().catchUp(FolderPipeline.newestFirst(FolderPipeline.tree(_base.toFile()))));
	}

	private List<String> stepNames() {
		List<String> result = new ArrayList<>();
		for (FolderPipeline.Step step : pipeline().steps()) {
			result.add(step.name());
		}
		return result;
	}

	// --- The tests. ---

	/**
	 * Switched on in the app, the step joins the running background and describes every photo and
	 * video once, from its preview (the video from its poster at the chosen moment), never from the
	 * original; a second pass describes nothing.
	 */
	public void testSwitchedOnEveryPhotoIsDescribedOnceFromItsPreview() throws Exception {
		library(false);
		start();
		awaitCatchUp();
		assertFalse("Off: no step.", stepNames().contains(ContentIndex.STEP));
		assertEquals("Off: nothing sent.", 0, _embedder._images.size() + _embedder._texts.size());
		for (String medium : MEDIA) {
			assertFalse("Off: nothing written.", ContentCache.file(_base.resolve(medium).toFile().getParentFile()).exists());
		}

		SpaceSettings settings = settings(get("space-settings"));
		assertEquals(ContentSwitch.OFF, settings.getContent());
		assertEquals("The fake embedder answers.", "", settings.getContentUnavailable());
		FakeResponse on = saveSettings(settings.setContent(ContentSwitch.ON));
		assertEquals(ContentSwitch.ON, settings(on).getContent());
		assertEquals(SpaceStore.CONTENT_ON, SpaceStore.load(_base, "").getContent());
		assertTrue(stepNames().toString(), stepNames().contains(ContentIndex.STEP));
		assertEquals("Before the videos: " + stepNames(), stepNames().indexOf(CatchUp.VIDEOS) - 1,
			stepNames().indexOf(ContentIndex.STEP));
		awaitDescribed();

		assertEquals("Every medium sent once.", MEDIA.length, _embedder._images.size());
		Set<String> texts = new HashSet<>(_embedder._texts);
		assertEquals("Every text sent once.", texts.size(), _embedder._texts.size());

		Map<String, byte[]> previews = new HashMap<>();
		for (String medium : MEDIA) {
			File file = _base.resolve(medium).toFile();
			File preview = medium.endsWith(".mp4") ? PreviewCache.posterFile(file, POSTER) : PreviewCache.previewFile(file);
			assertTrue("The preview is there: " + preview, preview.isFile());
			byte[] sent = Files.readAllBytes(preview.toPath());
			previews.put(medium, sent);
			assertTrue("The preview is smaller than the original: " + medium,
				medium.endsWith(".mp4") || sent.length < file.length());
		}
		for (byte[] sent : _embedder._images) {
			String which = null;
			for (Map.Entry<String, byte[]> entry : previews.entrySet()) {
				if (Arrays.equals(entry.getValue(), sent)) {
					which = entry.getKey();
				}
			}
			assertNotNull("What was sent is a preview, never an original.", which);
			assertFalse("Not the original: " + which,
				Arrays.equals(sent, Files.readAllBytes(_base.resolve(which))));
		}
		byte[] firstFrame = Files.readAllBytes(PreviewCache.previewFile(_base.resolve(NEW + "/clip.mp4").toFile()).toPath());
		for (byte[] sent : _embedder._images) {
			assertFalse("The video is described by the poster chosen, not its first frame.",
				Arrays.equals(sent, firstFrame));
		}

		ContentCache cache = ContentCache.read(folder(NEW));
		assertEquals(STAMP, cache.getImageStamp());
		assertEquals(ContentText.stamp(STAMP), cache.getTextStamp());
		ContentCache.Photo clip = cache.photos().get("clip.mp4");
		assertEquals("The poster moment is part of the key.", POSTER, clip.image().poster());
		assertTrue("The vector is the embedder's.", Arrays.equals(
			FakeEmbedder.vector(STAMP + Arrays.hashCode(previews.get(NEW + "/clip.mp4"))), cache.image(clip.image())));
		ContentCache.Photo n1 = cache.photos().get("n1.jpg");
		boolean described = false;
		for (String text : _embedder._texts) {
			if (ContentCache.textHash(text).equals(n1.text())) {
				described = true;
				assertTrue(text, text.startsWith("title: New | text: New"));
				assertTrue("The description is part of the text: " + text, text.contains("A walk in the park"));
			}
		}
		assertTrue(described);

		CatchUpStatus status = status();
		assertEquals(0, status.getContentRemaining());
		assertEquals("Nothing failed: " + status.getFailure(), "", status.getFailure());

		int images = _embedder._images.size();
		int sentTexts = _embedder._texts.size();
		walk();
		awaitCatchUp();
		assertEquals("A second pass sends nothing.", images, _embedder._images.size());
		assertEquals(sentTexts, _embedder._texts.size());
	}

	/**
	 * A photo renamed by hand and one moved into another album by the server keep their vectors:
	 * nothing is sent again, and the old album forgets them.
	 */
	public void testAMovedPhotoKeepsItsVector() throws Exception {
		library(true);
		start();
		awaitDescribed();
		int images = _embedder._images.size();

		Files.move(_base.resolve(OLD).resolve("a1.jpg"), _base.resolve(OLD).resolve("renamed.jpg"));
		pipeline().notice(folder(OLD), false);
		long end = System.currentTimeMillis() + TIMEOUT;
		while (!ContentCache.read(folder(OLD)).photos().containsKey("renamed.jpg")) {
			assertTrue("The renamed photo is named in time.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		awaitCatchUp();
		assertEquals("A rename sends no picture.", images, _embedder._images.size());
		assertFalse(ContentCache.read(folder(OLD)).photos().containsKey("a1.jpg"));

		new MoveService(_servlet.cache()).move(new PathInfo(_base, Path.of(OLD)), new PathInfo(_base, Path.of(NEW)),
			List.of("a2.jpg"));
		pipeline().notice(folder(OLD), false);
		pipeline().notice(folder(NEW), false);
		end = System.currentTimeMillis() + TIMEOUT;
		while (!ContentCache.read(folder(NEW)).photos().containsKey("a2.jpg")) {
			assertTrue("The moved photo is described in its new album in time.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		awaitCatchUp();
		assertEquals("A move sends no picture.", images, _embedder._images.size());
		assertFalse("The old album forgets it.", ContentCache.read(folder(OLD)).photos().containsKey("a2.jpg"));
		assertEquals(1, ContentCache.read(folder(OLD)).imageKeys().size());
	}

	/** A changed description is a new text: its text is sent again, its picture is not. */
	public void testAChangedDescriptionSendsTheTextAlone() throws Exception {
		library(true);
		start();
		awaitDescribed();
		int images = _embedder._images.size();
		int texts = _embedder._texts.size();
		String before = ContentCache.read(folder(NEW)).photos().get("n1.jpg").text();

		newSidecar("Rain in the park");
		pipeline().notice(folder(NEW), false);
		long end = System.currentTimeMillis() + TIMEOUT;
		while (ContentCache.read(folder(NEW)).photos().get("n1.jpg").text().equals(before)) {
			assertTrue("The new text is described in time.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		awaitCatchUp();
		assertEquals("No picture sent again.", images, _embedder._images.size());
		assertEquals("One text sent.", texts + 1, _embedder._texts.size());
		assertTrue(_embedder._texts.get(texts), _embedder._texts.get(texts).contains("Rain in the park"));
		assertNull("The old text is forgotten.", ContentCache.read(folder(NEW)).text(before));
	}

	/** Another model of the embedder describes every photo again. */
	public void testANewModelDescribesEverythingAgain() throws Exception {
		library(true);
		start();
		awaitDescribed();
		int images = _embedder._images.size();

		_embedder._stamp = "fake@0000002/256/fp32/1";
		_servlet.content().setRecheckMillis(0);
		pipeline().notice(folder(OLD), false);
		long end = System.currentTimeMillis() + TIMEOUT;
		while (!allStamped("fake@0000002/256/fp32/1")) {
			assertTrue("Everything is described anew in time.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		awaitCatchUp();
		assertEquals("Every medium sent again, once.", 2 * images, _embedder._images.size());
	}

	private boolean allStamped(String stamp) {
		for (String medium : MEDIA) {
			File file = _base.resolve(medium).toFile();
			ContentCache cache = ContentCache.read(file.getParentFile());
			if (!stamp.equals(cache.getImageStamp()) || !cache.photos().containsKey(file.getName())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * An embedder that cannot answer holds nothing up and fails nothing: no album is marked done,
	 * the reason is shown to the administrator, and it is asked again until it answers.
	 */
	public void testAnEmbedderThatCannotAnswerIsWaitedFor() throws Exception {
		_embedder._problem = "the embedder service at http://pi:8091/ is downloading the model";
		library(true);
		start();
		awaitCatchUp();
		for (String album : new String[] { OLD, NEW }) {
			assertFalse("Not done: " + album, pipeline().isDone(folder(album), ContentIndex.STEP));
			assertTrue("Nothing written: " + album, ContentCache.read(folder(album)).photos().isEmpty());
		}
		assertTrue("Every album waits: " + _servlet.content().waiting(),
			_servlet.content().waiting().size() == 2);
		assertEquals("The reason is shown.", _embedder._problem, settings(get("space-settings")).getContentUnavailable());
		CatchUpStatus status = status();
		assertEquals("Nothing failed per photo: " + status.getFailure(), "", status.getFailure());
		assertTrue("Previews are not held up.", PreviewCache.previewFile(_base.resolve(OLD + "/a1.jpg").toFile()).isFile());

		_embedder._problem = null;
		awaitDescribed();
		assertEquals(MEDIA.length, _embedder._images.size());
		assertEquals("", settings(get("space-settings")).getContentUnavailable());
		assertTrue(_servlet.content().waiting().isEmpty());
		for (String album : new String[] { OLD, NEW }) {
			assertTrue("Done: " + album, pipeline().isDone(folder(album), ContentIndex.STEP));
		}
	}

	/** The status says what the content search does while it describes a photo. */
	public void testTheStatusCountsWhatWaitsForADescription() throws Exception {
		library(true);
		_embedder._hold = new CountDownLatch(1);
		start();
		assertTrue(_embedder._holding.await(TIMEOUT, TimeUnit.MILLISECONDS));
		CatchUpStatus status = status();
		assertEquals(CatchUpStep.CONTENT, status.getStep());
		assertTrue("Waiting: " + status.getContentRemaining(), status.getContentRemaining() >= 1);
		assertTrue("A photo is no video: " + status.getVideosRemaining(), status.getVideosRemaining() <= 1);
		_embedder._hold.countDown();
		awaitDescribed();
		assertEquals(0, status().getContentRemaining());
	}

	/**
	 * An upload goes first while a photo is being described: the description is put aside, the
	 * thumbnail of the upload is made meanwhile, and the description is taken up where it was, not
	 * sent again.
	 */
	public void testAnUploadIsNotHeldUpByADescription() throws Exception {
		library(true);
		_embedder._hold = new CountDownLatch(1);
		start();
		assertTrue(_embedder._holding.await(TIMEOUT, TimeUnit.MILLISECONDS));

		photo(NEW, "n3.jpg", Color.ORANGE);
		File upload = _base.resolve(NEW + "/n3.jpg").toFile();
		FolderPipeline.uploaded(folder(NEW), List.of(upload));
		long end = System.currentTimeMillis() + TIMEOUT;
		while (!PreviewCache.previewFile(upload).isFile()) {
			assertTrue("The upload's thumbnail is made while a description waits.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		assertEquals("Still describing.", 0, _embedder._images.size());

		_embedder._hold.countDown();
		awaitDescribed();
		end = System.currentTimeMillis() + TIMEOUT;
		while (!ContentCache.read(folder(NEW)).photos().containsKey("n3.jpg")) {
			assertTrue("The upload is described too.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		awaitCatchUp();
		assertEquals("Every picture sent once, the one put aside too.", MEDIA.length + 1, _embedder._images.size());
	}

	/** Switched off, the step leaves the background, and every vector stays where it is. */
	/**
	 * Probe: switched on, off and on again, and saved unchanged while on, the step stands in the
	 * pipeline once, and no photo is described twice.
	 */
	public void testSwitchedBackAndForthTheStepIsInThePipelineOnce() throws Exception {
		library(false);
		start();
		awaitCatchUp();
		FakeResponse on = saveSettings(settings(get("space-settings")).setContent(ContentSwitch.ON));
		on = saveSettings(settings(on).setContent(ContentSwitch.ON));
		awaitDescribed();
		int images = _embedder._images.size();
		FakeResponse off = saveSettings(settings(on).setContent(ContentSwitch.OFF));
		on = saveSettings(settings(off).setContent(ContentSwitch.ON));
		awaitCatchUp();
		assertEquals(stepNames().toString(), 1, java.util.Collections.frequency(stepNames(), ContentIndex.STEP));
		assertEquals("Nothing described twice.", images, _embedder._images.size());
	}

	public void testSwitchedOffTheStepLeavesAndTheVectorsStay() throws Exception {
		library(true);
		start();
		awaitDescribed();
		byte[] before = Files.readAllBytes(ContentCache.file(folder(NEW)).toPath());
		int images = _embedder._images.size();

		FakeResponse off = saveSettings(settings(get("space-settings")).setContent(ContentSwitch.OFF));
		assertEquals(ContentSwitch.OFF, settings(off).getContent());
		assertFalse(stepNames().contains(ContentIndex.STEP));
		assertFalse(_servlet.content().isEnabled());

		photo(NEW, "n3.jpg", Color.ORANGE);
		pipeline().notice(folder(NEW), false);
		walk();
		awaitCatchUp();
		assertTrue("The vectors stay.", Arrays.equals(before, Files.readAllBytes(ContentCache.file(folder(NEW)).toPath())));
		assertEquals("Nothing sent while off.", images, _embedder._images.size());
		assertTrue("The new photo has its preview all the same.",
			PreviewCache.previewFile(_base.resolve(NEW + "/n3.jpg").toFile()).isFile());
	}

	/** A server without an embedder says so, and a refresh of an album's cache throws the vectors away. */
	public void testWithoutAnEmbedderTheSettingsSaySo() throws Exception {
		library(false);
		start();
		_servlet.setEmbedder(null);
		assertEquals(ContentIndex.NO_EMBEDDER, settings(get("space-settings")).getContentUnavailable());
		assertTrue(CacheRefresh.isGenerated(ContentCache.FILE_NAME));
		assertTrue(CacheRefresh.isGenerated(ContentCache.FILE_NAME + PreviewCache.TMP_SUFFIX));
	}

	// --- The protocol. ---

	private CatchUpStatus status() throws Exception {
		FakeResponse response = get(ImageServlet.CATCH_UP_TYPE);
		assertEquals(response.body(), 200, response.status());
		return CatchUpStatus.readCatchUpStatus(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
	}

	private static SpaceSettings settings(FakeResponse response) throws Exception {
		assertEquals(response.body(), 200, response.status());
		return SpaceSettings.readSpaceSettings(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
	}

	private FakeResponse saveSettings(SpaceSettings settings) throws Exception {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			settings.writeTo(json);
		}
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "space-settings");
		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer " + _adminToken);
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request("/", "application/json",
			buffer.toString().getBytes(StandardCharsets.UTF_8), headers, parameters), response.response());
		assertEquals(response.body(), 200, response.status());
		return response;
	}

	private FakeResponse get(String type) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer " + _adminToken);
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request("/", null, new byte[0], headers, parameters), response.response());
		return response;
	}
}
