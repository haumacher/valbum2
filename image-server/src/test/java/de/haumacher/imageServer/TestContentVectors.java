/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestContentSearch.FakeEmbedder;
import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.content.ContentCache;
import de.haumacher.imageServer.content.ContentText;
import de.haumacher.imageServer.content.ContentVectors;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.index.VectorIndex;
import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the vector index of the content search, see issues #257 and #264 (slice 4): fed
 * from the albums' caches, kept current by the background and by the server's own moves and
 * deletes, and asked with what the caller may see filtered inside the vector query.
 */
@SuppressWarnings("javadoc")
public class TestContentVectors extends TestCase {

	private static final long TIMEOUT = 180_000;

	private static final String NEW = "2023-09-01 New";

	private static final String OLD = "2019-03-01 Old";

	private static final String STAMP = "fake@0000001/256/fp32/1";

	private static final String[] MEDIA = { OLD + "/a1.jpg", OLD + "/a2.jpg", NEW + "/n1.jpg", NEW + "/n2.jpg",
		NEW + "/clip.mp4" };

	/** The albums of the synthetic library, see {@link #synthetic()}. */
	private static final String A = "Lib/A";

	private static final String B = "Lib/B";

	private static final int IN_A = 120;

	private static final int IN_B = 40;

	private static final int K = 10;

	private Path _base;

	private ImageServlet _servlet;

	private FakeEmbedder _embedder;

	private String _adminToken;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-vectors");
		_embedder = new FakeEmbedder();
		Background.setQuietMillis(100);
	}

	@Override
	protected void tearDown() throws Exception {
		Background.setQuietMillis(Background.QUIET_MILLIS);
		stop();
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	// --- Fed by the background. ---

	/**
	 * Content switched on, every photo described by the embedder: the background feeds the index,
	 * and the vector of each photo finds that photo first, by its picture, by its text and by both.
	 */
	public void testTheFillFindsEveryPhotoByItsOwnVector() throws Exception {
		library();
		start(true);
		awaitIndexed(MEDIA.length);
		assertEquals(ContentVectors.class.getSimpleName(), STAMP + "\n" + ContentText.stamp(STAMP), vectors().stamp());
		assertEquals(256, vectors().dimension());
		for (String medium : MEDIA) {
			File file = _base.resolve(medium).toFile();
			ContentCache cache = ContentCache.read(file.getParentFile());
			ContentCache.Photo photo = cache.photos().get(file.getName());
			float[] image = cache.image(photo.image());
			float[] text = cache.text(photo.text());
			assertEquals(medium, medium, first(nearest("", image, 1, 0, 3)));
			// The photos of an album without a description of their own share their text.
			assertTrue(medium, nearestAll("", text, 0, 1).contains(medium));
			assertEquals(medium, medium, first(nearest("", image, 1, 1, 3)));
			List<SearchIndex.Scored> found = nearest("", image, 1, 0, 3);
			assertEquals("Its own cosine is one.", 1.0, found.get(0).score(), 1e-4);
			assertTrue(found.get(1).score() < found.get(0).score());
		}
		assertEquals("Each search is k long.", 3, nearest("", vectorOf(MEDIA[0]), 1, 1, 3).size());
		assertEquals("Not more than there are.", MEDIA.length, nearest("", vectorOf(MEDIA[0]), 1, 1, 50).size());

		List<SearchIndex.Scored> similar = _servlet.searchIndex().similarTo(path(NEW), "n1.jpg", path(""), 10,
			SearchIndex.Visibility.ALL);
		assertEquals("More like this leaves the photo itself out.", MEDIA.length - 1, similar.size());
		for (SearchIndex.Scored each : similar) {
			assertFalse((NEW + "/n1.jpg").equals(each.match().getRelative()));
		}

		// The walk of the start leaves out the root folder, which a walk by hand visits once.
		walk();
		awaitCatchUp();
		int reads = _servlet.vectors().reads();
		walk();
		awaitCatchUp();
		assertEquals("Another pass reads no album.", reads, _servlet.vectors().reads());
	}

	/**
	 * A photo moved by the server is found at its new path; one deleted is gone at once, in the same
	 * request.
	 */
	public void testAMovedPhotoIsFoundWhereItIsAndADeletedOneIsGone() throws Exception {
		library();
		start(true);
		awaitIndexed(MEDIA.length);
		float[] a2 = vectorOf(OLD + "/a2.jpg");
		float[] n1 = vectorOf(NEW + "/n1.jpg");

		FakeResponse moved = post("/" + OLD + "/", "move",
			"{\"target\":\"" + NEW + "\",\"names\":[{\"name\":\"a2.jpg\"}]}");
		assertEquals(moved.body(), 200, moved.status());
		assertFalse("The old path is gone with the request.",
			(OLD + "/a2.jpg").equals(first(nearest("", a2, 1, 0, 1))));
		long end = System.currentTimeMillis() + TIMEOUT;
		while (!(NEW + "/a2.jpg").equals(first(nearest("", a2, 1, 0, 1)))) {
			assertTrue("The moved photo is found at its new path in time: " + first(nearest("", a2, 1, 0, 1)),
				System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		awaitCatchUp();
		assertEquals(NEW + "/a2.jpg", first(nearest("", a2, 1, 0, 1)));
		assertEquals(NEW + "/a2.jpg", first(nearest(NEW, a2, 1, 0, 1)));
		assertTrue("Nothing is found in the old album of it.", names(nearest(OLD, a2, 1, 0, 10)).equals(List.of(OLD + "/a1.jpg")));

		FakeResponse deleted = post("/" + NEW + "/", "delete", "{\"target\":\"\",\"names\":[{\"name\":\"n1.jpg\"}]}");
		assertEquals(deleted.body(), 200, deleted.status());
		assertFalse("A deleted photo is gone at once.", names(nearest("", n1, 1, 1, 10)).contains(NEW + "/n1.jpg"));
		awaitCatchUp();
		assertFalse("And stays gone.", names(nearest("", n1, 1, 1, 10)).contains(NEW + "/n1.jpg"));
	}

	/** Another model drops every vector, and the index is filled again with the new ones. */
	public void testANewModelDropsAndRefills() throws Exception {
		library();
		start(true);
		awaitIndexed(MEDIA.length);
		float[] before = vectorOf(NEW + "/n2.jpg");

		String other = "fake@0000002/256/fp32/1";
		_embedder._stamp = other;
		_servlet.content().setRecheckMillis(0);
		pipeline().notice(folder(OLD), false);
		long end = System.currentTimeMillis() + TIMEOUT;
		while (!vectors().stamp().startsWith(other + "\n") || vectors().size() < MEDIA.length || !allStamped(other)) {
			assertTrue("Filled with the new model in time: " + vectors().size(), System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		awaitCatchUp();
		assertEquals(MEDIA.length, vectors().size());
		float[] after = vectorOf(NEW + "/n2.jpg");
		assertFalse("Another model, another vector.", java.util.Arrays.equals(before, after));
		for (String medium : MEDIA) {
			assertEquals(medium, medium, first(nearest("", vectorOf(medium), 1, 1, 1)));
		}
		long id = idOf(NEW, "n2.jpg");
		assertTrue("The index holds the new vector.",
			cosine(after, vectors().vector(id, VectorIndex.IMAGE)) > 0.9999);
	}

	/** A restart answers from disk: nothing is read again, and the search finds the same. */
	public void testARestartAnswersWithoutRefilling() throws Exception {
		library();
		start(true);
		awaitIndexed(MEDIA.length);
		List<String> before = names(nearest("", vectorOf(OLD + "/a1.jpg"), 1, 1, 5));
		stop();

		start(false);
		assertEquals("Answered at once, before anything is read.", before,
			names(nearest("", vectorOf(OLD + "/a1.jpg"), 1, 1, 5)));
		pipeline().setQuietMillis(100);
		_servlet.startIndexing();
		awaitCatchUp();
		assertEquals("The walk of the start reads no album again.", 0, _servlet.vectors().reads());
		assertEquals(before, names(nearest("", vectorOf(OLD + "/a1.jpg"), 1, 1, 5)));
	}

	// --- Filtered inside the query. ---

	/**
	 * The photographs nearest to the query are private: a guest is not answered fewer, but the
	 * nearest ten of those they may see. A filter applied to the nearest ten of all would have left
	 * them none.
	 */
	public void testWhatTheCallerMayNotSeeIsFilteredInsideTheQuery() throws Exception {
		synthetic();
		float[] query = target();

		List<String> all = names(nearest("", query, 1, 1, K, SearchIndex.Visibility.ALL));
		assertEquals(K, all.size());
		assertEquals("The nearest are the private ones.", A + "/" + name("a", 0), all.get(0));
		for (String found : all) {
			assertTrue(found, number(found) < 20);
		}

		List<String> guest = names(nearest("", query, 1, 1, K, new SearchIndex.Visibility(Privacy.PUBLIC, Ratings.MIN, "")));
		assertEquals("k answers all the same: " + guest, K, guest.size());
		for (String found : guest) {
			assertFalse("Nothing private: " + found, isPrivate(found));
			assertFalse("Never the trash: " + found, isTrash(found));
		}
		assertEquals("The nearest a guest may see.", A + "/" + name("a", 21), guest.get(0));

		List<String> members = names(nearest("", query, 1, 1, K, new SearchIndex.Visibility(Privacy.MEMBERS, Ratings.MIN, "")));
		assertEquals(K, members.size());
		assertFalse(members.stream().anyMatch(TestContentVectors::isPrivate));

		List<String> rated = names(nearest("", query, 1, 1, K, new SearchIndex.Visibility(Privacy.PRIVATE, 1, "")));
		assertEquals("A rating floor, k answers: " + rated, K, rated.size());
		for (String found : rated) {
			assertTrue("Rated: " + found, isRated(found));
		}

		List<String> labelled = names(nearest("", query, 1, 1, K, new SearchIndex.Visibility(Privacy.PRIVATE, Ratings.MIN,
			"flowers")));
		assertEquals("A link's label, k answers: " + labelled, K, labelled.size());
		for (String found : labelled) {
			assertTrue("Labelled: " + found, isLabelled(found));
		}

		for (int k : new int[] { 1, K, 200 }) {
			assertFalse("The trash is never found.",
				names(nearest("", query, 1, 1, k, SearchIndex.Visibility.ALL)).stream().anyMatch(TestContentVectors::isTrash));
		}
		assertEquals("All there are, less the trash.", IN_A + IN_B - 1, nearest("", query, 1, 1, 500,
			SearchIndex.Visibility.ALL).size());
	}

	/**
	 * Who may see a photograph is the search's, not the vectors': made private, it is not found by
	 * a guest any more, and not one vector was written for it.
	 */
	public void testAPrivacyChangedIsFeltWithoutTouchingTheVectors() throws Exception {
		synthetic();
		SearchIndex.Visibility guest = new SearchIndex.Visibility(Privacy.PUBLIC, Ratings.MIN, "");
		assertEquals(A + "/" + name("a", 21), first(nearest("", target(), 1, 1, K, guest)));
		int reads = _servlet.vectors().reads();

		Path sidecar = _base.resolve(A).resolve("index.json");
		String json = new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8);
		String before = "\"name\":\"" + name("a", 21) + "\",\"width\":16,\"height\":16,\"privacy\":0";
		assertTrue(json.contains(before));
		sidecar(A, json.replace(before, before.substring(0, before.length() - 1) + Privacy.PRIVATE));
		_servlet.searchIndex().build();

		List<String> found = names(nearest("", target(), 1, 1, K, guest));
		assertEquals(K, found.size());
		assertEquals(A + "/" + name("a", 22), found.get(0));
		assertFalse(found.contains(A + "/" + name("a", 21)));
		_servlet.vectors().fill();
		assertEquals("No album fed again.", reads, _servlet.vectors().reads());
	}

	/** A search within a folder finds only what lies below it. */
	public void testAFolderIsTheScope() throws Exception {
		synthetic();
		float[] query = target();
		List<String> inB = names(nearest(B, query, 1, 1, K, SearchIndex.Visibility.ALL));
		assertEquals(K, inB.size());
		for (String found : inB) {
			assertTrue("Below the folder: " + found, found.startsWith(B + "/"));
		}
		assertEquals("The nearest of B.", B + "/" + name("b", 0), inB.get(0));
		assertEquals(K, nearest("Lib", query, 1, 1, K, SearchIndex.Visibility.ALL).size());
		assertEquals(0, nearest("Elsewhere", query, 1, 1, K, SearchIndex.Visibility.ALL).size());
	}

	/** The answer a caller is given is still the album's: an anonymous visitor sees nothing private. */
	public void testTheAnswersAreCheckedByTheirAlbums() throws Exception {
		synthetic();
		List<ImageServlet.ScoredPart> answers = _servlet.nearestAnswers(path(""), target(), 1, 1, K,
			AuthService.Caller.ANONYMOUS, Privacy.PRIVATE);
		assertEquals(K, answers.size());
		assertEquals(A + "/" + name("a", 21), answers.get(0).part().getName());
		for (ImageServlet.ScoredPart answer : answers) {
			assertFalse(answer.part().getName(), isPrivate(answer.part().getName()));
		}
		for (int n = 1; n < answers.size(); n++) {
			assertTrue("Nearest first.", answers.get(n - 1).score() >= answers.get(n).score());
		}
	}

	/**
	 * Probe: the photos of the inbox are never a nearest answer to a caller the inbox shows nothing,
	 * however near they lie.
	 */
	public void testTheInboxIsNoNearestAnswerToAVisitor() throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Vectors", SpaceStore.ANONYMOUS_PUBLIC, "", SpaceStore.FACES_OFF,
			"", B, SpaceStore.CONTENT_OFF));
		album(A, "a", IN_A, 0);
		album(B, "b", IN_B, IN_A);
		writeCache(A, "a", IN_A, 0, STAMP);
		writeCache(B, "b", IN_B, IN_A, STAMP);
		start(false);
		_servlet.searchIndex().build();
		_servlet.vectors().fill();
		// The query is a photo of the inbox itself: its own vector is the nearest there is.
		float[] query = vector(IN_A + 3);
		List<ImageServlet.ScoredPart> answers = _servlet.nearestAnswers(path(""), query, 1, 1, K,
			AuthService.Caller.ANONYMOUS, Privacy.PRIVATE);
		assertEquals("Still k answers, from outside the inbox.", K, answers.size());
		for (ImageServlet.ScoredPart answer : answers) {
			assertFalse(answer.part().getName(), answer.part().getName().startsWith(B + "/"));
		}
	}

	/**
	 * A contributor is shown their own photographs of the inbox and nobody else's, which the index
	 * cannot tell apart: the query asks for more until k are answered.
	 */
	public void testAContributorInTheInboxIsAnsweredKOfTheirOwn() throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Vectors", SpaceStore.ANONYMOUS_PUBLIC, "", SpaceStore.FACES_OFF,
			"", B, SpaceStore.CONTENT_OFF));
		UserStore users = new UserStore(_base);
		UserStore.User bob = new UserStore.User("bob", de.haumacher.imageServer.auth.Roles.CONTRIBUTE, "",
			java.time.Instant.now().toString(), de.haumacher.imageServer.auth.Clearances.ALL, false);
		bob.addDevice(new UserStore.Device("Bob's phone", UserStore.hash("bob-token"), java.time.Instant.now().toString()));
		users.addUser(bob);
		users.store();
		album(A, "a", IN_A, 0);
		album(B, "b", IN_B, IN_A);
		writeCache(A, "a", IN_A, 0, STAMP);
		writeCache(B, "b", IN_B, IN_A, STAMP);
		start(false);
		_servlet.searchIndex().build();
		_servlet.vectors().fill();

		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer bob-token");
		AuthService.Caller caller = _servlet.auth().caller(TestImageServletPut.request("/", null, new byte[0], headers,
			new HashMap<>()));
		assertTrue("Bob is signed in.", caller.isPaired());
		// A query near the first 24 photos of the inbox alone (their own directions, see vector()),
		// half of them Bob's: the ten nearest of all are half somebody else's.
		float[] query = new float[256];
		for (int n = 0; n < 24; n++) {
			query[1 + (IN_A + n) % 255] = 1;
		}
		List<ImageServlet.ScoredPart> answers = _servlet.nearestAnswers(path(""), query, 1, 1, K, caller,
			Privacy.PRIVATE);
		assertEquals("k answers all the same.", K, answers.size());
		List<String> names = new ArrayList<>();
		for (ImageServlet.ScoredPart answer : answers) {
			names.add(answer.part().getName());
		}
		for (String name : names) {
			assertTrue("Bob's own of the inbox alone: " + names,
				name.startsWith(B + "/") && number(name) % 2 == 0 && number(name) < 24);
		}
	}

	/** A caller who may view nothing is answered nothing, without the index being asked. */
	public void testACallerWhoMayViewNothingIsAnsweredNothing() throws Exception {
		synthetic();
		assertTrue(_servlet.nearestAnswers(path(""), target(), 1, 1, K, AuthService.Caller.INVALID, Privacy.PRIVATE)
			.isEmpty());
		assertEquals("Anonymous in a public space is.", K,
			_servlet.nearestAnswers(path(""), target(), 1, 1, K, AuthService.Caller.ANONYMOUS, Privacy.PRIVATE).size());
	}

	/** A query of another number of dimensions finds nothing, and so does a space without vectors. */
	public void testAForeignQueryFindsNothing() throws Exception {
		synthetic();
		assertEquals(0, nearest("", new float[] { 1, 0, 0 }, 1, 1, K, SearchIndex.Visibility.ALL).size());
		assertEquals(0, nearest("", target(), 0, 0, K, SearchIndex.Visibility.ALL).size());
	}

	/** Vectors of a model other than the one the index holds are not taken. */
	public void testVectorsOfAnotherModelAreNotTaken() throws Exception {
		synthetic();
		int size = vectors().size();
		writeCache(B, "b", IN_B, IN_A, "other@1/256/fp32/1");
		_servlet.vectors().refresh(folder(B));
		assertEquals("B is of another model now.", size - IN_B, vectors().size());
		assertEquals(0, nearest(B, target(), 1, 1, K, SearchIndex.Visibility.ALL).size());
	}

	// --- On disk. ---

	/**
	 * An index of the current build without vectors, as the build before wrote it, is not rebuilt:
	 * the read marks and the search stay, nothing of the search is read again, and the fill puts the
	 * vectors beside it.
	 */
	public void testAnIndexOfTheBuildBeforeGainsTheVectorsInPlace() throws Exception {
		synthetic();
		markRead();
		int[] search = _servlet.searchIndex().size();
		long created = _servlet.spaceIndex().created();
		List<String> nearest = names(nearest("", target(), 1, 1, K, SearchIndex.Visibility.ALL));
		stop();
		deleteVectors();

		start(false);
		SpaceIndex store = _servlet.spaceIndex();
		assertFalse("Not rebuilt.", store.isFresh());
		assertEquals(created, store.created());
		assertEquals(String.valueOf(SpaceIndex.MIGRATION), meta(store, SpaceIndex.MIGRATION_KEY));
		assertEquals("The read marks stay.", 1, count(store, "arrival_marks"));
		assertEquals("And so does the search.", search[1], _servlet.searchIndex().size()[1]);
		assertEquals("No vector yet.", 0, vectors().size());

		_servlet.vectors().fill();
		assertEquals(IN_A + IN_B, vectors().size());
		assertEquals(nearest, names(nearest("", target(), 1, 1, K, SearchIndex.Visibility.ALL)));
		assertEquals("The search read nothing again.", 0, _servlet.searchIndex().reads());
		assertEquals(search[1], _servlet.searchIndex().find(path(""), null, null, SearchIndex.Visibility.ALL).size() + 1);
	}

	/** A process killed while it fills: the next start takes up where it left off. */
	public void testAKilledFillIsResumed() throws Exception {
		synthetic();
		markRead();
		stop();
		deleteVectors();

		List<String> command = new ArrayList<>();
		command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(KilledVectorFill.class.getName());
		command.add(_base.toString());
		command.add(_base.resolve(A).toString());
		command.add(_base.resolve(B).toString());
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue("The fill did not end: " + output, process.waitFor(60, TimeUnit.SECONDS));
		assertEquals(output, KilledVectorFill.KILLED, process.exitValue());

		start(false);
		assertFalse("Not rebuilt: " + output, _servlet.spaceIndex().isFresh());
		assertEquals(1, count(_servlet.spaceIndex(), "arrival_marks"));
		assertEquals("A was fed before the kill, B was not committed: " + output, IN_A, vectors().size());
		assertFalse("A is not read again.", _servlet.vectors().refresh(folder(A)));
		_servlet.vectors().fill();
		assertEquals(IN_A + IN_B, vectors().size());
		assertEquals(B + "/" + name("b", 0), first(nearest(B, target(), 1, 1, 1, SearchIndex.Visibility.ALL)));
	}

	/** A folder removed by hand goes from the index with the background's next look. */
	public void testAFolderGoneByHandGoes() throws Exception {
		synthetic();
		try (Stream<Path> files = Files.walk(_base.resolve(B))) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		_servlet.searchIndex().build();
		_servlet.vectors().fill();
		assertEquals(IN_A, vectors().size());
		assertTrue(vectors().albums().stream().noneMatch(path -> path.startsWith(B)));
	}

	// --- The library fed by the background. ---

	private void library() throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Content", SpaceStore.ANONYMOUS_PUBLIC, "", SpaceStore.FACES_OFF,
			"", "", SpaceStore.CONTENT_ON));
		photo(OLD, "a1.jpg", Color.RED);
		photo(OLD, "a2.jpg", Color.GREEN);
		photo(NEW, "n1.jpg", Color.BLUE);
		photo(NEW, "n2.jpg", Color.YELLOW);
		test.de.haumacher.valbum.GenerateTestAlbum.recordTinyVideo(_base.resolve(NEW).resolve("clip.mp4").toFile());
		sidecar(NEW, "[\"AlbumInfo\",{\"title\":\"New\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"n1.jpg\",\"width\":800,\"height\":600,\"comment\":\"A walk\"}],"
			+ "[\"ImagePart\",{\"name\":\"n2.jpg\",\"width\":800,\"height\":600,"
			+ "\"labels\":[{\"name\":\"flowers\"}]}],"
			+ "[\"ImagePart\",{\"name\":\"clip.mp4\",\"kind\":\"VIDEO\",\"width\":160,\"height\":120}]]}]");
		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		store.store();
	}

	private void photo(String album, String name, Color color) throws IOException {
		Path folder = _base.resolve(album);
		Files.createDirectories(folder);
		BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 400, 300);
		g.setColor(Color.BLACK);
		g.drawString(album + "/" + name, 20, 100);
		g.dispose();
		assertTrue(ImageIO.write(image, "jpg", folder.resolve(name).toFile()));
	}

	private void sidecar(String folder, String json) throws IOException {
		Files.createDirectories(_base.resolve(folder));
		Path tmp = _base.resolve(folder).resolve("index.json.tmp");
		Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
		Files.move(tmp, _base.resolve(folder).resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
	}

	private void start(boolean background) throws Exception {
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), auth, "", SpaceStore.load(_base, ""));
		_servlet.init();
		_servlet.setEmbedder(_embedder);
		_servlet.content().setRetryMillis(200, 400);
		if (new UserStore(_base).getOwner() != null) {
			_adminToken = Codes.signInAdmin(auth, "Phone", "haui").getToken();
		}
		if (background) {
			pipeline().setQuietMillis(100);
			pipeline().setSweepMillis(300);
			_servlet.startIndexing();
		}
	}

	private void stop() {
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
	}

	private FolderPipeline pipeline() {
		return _servlet.index().pipeline();
	}

	private void awaitCatchUp() throws Exception {
		assertTrue("The hash pass ends.", _servlet.index().awaitPass(TIMEOUT));
		assertTrue("The catch-up ends.", pipeline().awaitIdle(TIMEOUT));
	}

	/** Waits until the index holds the given number of photographs, then for the background to rest. */
	private void awaitIndexed(int count) throws Exception {
		long end = System.currentTimeMillis() + TIMEOUT;
		while (vectors().size() < count) {
			assertTrue("Every photo is in the vector index in time: " + vectors().size(), System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		awaitCatchUp();
		assertEquals(count, vectors().size());
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

	private void walk() {
		pipeline().execute(() -> pipeline().catchUp(FolderPipeline.newestFirst(FolderPipeline.tree(_base.toFile()))));
	}

	private FakeResponse post(String pathInfo, String action, String body) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer " + _adminToken);
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request(pathInfo, "application/json", body.getBytes(StandardCharsets.UTF_8),
			headers, parameters), response.response());
		return response;
	}

	/** The image vector of the given medium as its album's cache holds it. */
	private static float[] vectorOf(Path base, String medium) {
		File file = base.resolve(medium).toFile();
		ContentCache cache = ContentCache.read(file.getParentFile());
		ContentCache.Photo photo = cache.photos().get(file.getName());
		assertNotNull("Described: " + medium, photo);
		return cache.image(photo.image());
	}

	private float[] vectorOf(String medium) {
		return vectorOf(_base, medium);
	}

	// --- The synthetic library. ---

	/**
	 * Two albums below <code>Lib</code>, described by vectors made for the test (no embedder): each
	 * photograph's cosine to {@link #target()} falls with its number, A's first (0 to 119), then B's
	 * (120 to 159). In A, the twenty nearest are private, the next is in the trash, every third from
	 * 30 on is rated, and every fifth from 40 on is labelled "flowers". Read into the search and the
	 * vector index on the spot, with no background.
	 */
	private void synthetic() throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Vectors", SpaceStore.ANONYMOUS_PUBLIC, "", SpaceStore.FACES_OFF,
			"", "", SpaceStore.CONTENT_OFF));
		album(A, "a", IN_A, 0);
		album(B, "b", IN_B, IN_A);
		writeCache(A, "a", IN_A, 0, STAMP);
		writeCache(B, "b", IN_B, IN_A, STAMP);
		Files.createDirectories(_base.resolve("Elsewhere"));
		start(false);
		_servlet.searchIndex().build();
		_servlet.vectors().fill();
		assertEquals(IN_A + IN_B, vectors().size());
	}

	private void album(String path, String prefix, int count, int first) throws IOException {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		StringBuilder parts = new StringBuilder();
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
		for (int n = 0; n < count; n++) {
			String name = name(prefix, n);
			assertTrue(ImageIO.write(image, "jpg", folder.resolve(name).toFile()));
			int global = first + n;
			boolean inA = first == 0;
			int privacy = inA && n < 20 ? Privacy.PRIVATE : Privacy.PUBLIC;
			int rating = inA && n == 20 ? Ratings.TRASH : (inA && n >= 30 && n % 3 == 0 ? 1 : 0);
			boolean flowers = inA && n >= 40 && n % 5 == 0;
			if (parts.length() > 0) {
				parts.append(',');
			}
			parts.append("[\"ImagePart\",{\"name\":\"").append(name).append("\",\"width\":16,\"height\":16,\"privacy\":")
				.append(privacy).append(",\"rating\":").append(rating).append(",\"date\":").append(1_600_000_000_000L + global);
			if (flowers) {
				parts.append(",\"labels\":[{\"name\":\"flowers\"}]");
			}

			parts.append("}]");
		}
		sidecar(path, "[\"AlbumInfo\",{\"title\":\"" + prefix + "\",\"parts\":[" + parts + "]}]");
		if (first != 0) {
			// B's photos were brought by Bob (the even ones) and by somebody else, recorded the way
			// an upload records it.
			de.haumacher.imageServer.upload.HashCache hashes = new de.haumacher.imageServer.upload.HashCache(folder.toFile());
			for (int n = 0; n < count; n++) {
				File file = folder.resolve(name(prefix, n)).toFile();
				String who = n % 2 == 0 ? "bob" : "other";
				hashes.put(file, de.haumacher.imageServer.upload.HashCache.sha256(file),
					new de.haumacher.imageServer.upload.HashCache.Attribution("user:" + who, who));
			}
			hashes.flush();
		}
	}

	/** Writes the content cache of the given album, of the given model, see {@link #synthetic()}. */
	private void writeCache(String path, String prefix, int count, int first, String stamp) throws IOException {
		Files.deleteIfExists(ContentCache.file(_base.resolve(path).toFile()).toPath());
		ContentCache.update(_base.resolve(path).toFile(), stamp, ContentText.stamp(stamp), cache -> {
			for (int n = 0; n < count; n++) {
				String name = name(prefix, n);
				float[] vector = vector(first + n);
				ContentCache.ImageKey key = new ContentCache.ImageKey(ContentCache.textHash(path + "/" + name), 0);
				String text = ContentCache.textHash("text of " + path + "/" + name);
				cache.putImage(key, vector);
				cache.putText(text, vector);
				cache.putPhoto(name, new ContentCache.Photo(key, text));
			}
		});
	}

	private static String name(String prefix, int n) {
		return prefix + String.format("%03d", Integer.valueOf(n)) + ".jpg";
	}

	/** The query every synthetic photograph is compared with. */
	private static float[] target() {
		float[] result = new float[256];
		result[0] = 1;
		return result;
	}

	/** The vector of the synthetic photograph of the given number: its cosine to the target falls with it. */
	private static float[] vector(int global) {
		float[] result = new float[256];
		double angle = (global + 1) * (Math.PI / 2 / (IN_A + IN_B + 2));
		result[0] = (float) Math.cos(angle);
		result[1 + global % 255] = (float) Math.sin(angle);
		return result;
	}

	private static int number(String found) {
		String name = found.substring(found.lastIndexOf('/') + 1);
		return Integer.parseInt(name.substring(1, 4));
	}

	private static boolean inA(String found) {
		return found.startsWith(A + "/");
	}

	private static boolean isPrivate(String found) {
		return inA(found) && number(found) < 20;
	}

	private static boolean isTrash(String found) {
		return inA(found) && number(found) == 20;
	}

	private static boolean isRated(String found) {
		int n = number(found);
		return inA(found) && n >= 30 && n % 3 == 0;
	}

	private static boolean isLabelled(String found) {
		int n = number(found);
		return inA(found) && n >= 40 && n % 5 == 0;
	}

	// --- Asking. ---

	private VectorIndex vectors() {
		return _servlet.spaceIndex().vectors();
	}

	private List<SearchIndex.Scored> nearest(String scope, float[] vector, float image, float text, int k) {
		return nearest(scope, vector, image, text, k, SearchIndex.Visibility.ALL);
	}

	private List<SearchIndex.Scored> nearest(String scope, float[] vector, float image, float text, int k,
			SearchIndex.Visibility visibility) {
		return _servlet.searchIndex().nearest(path(scope), vector, image, text, k, visibility);
	}

	private PathInfo path(String path) {
		return path.isEmpty() ? new PathInfo(_base) : new PathInfo(_base, Path.of(path));
	}

	private File folder(String path) {
		return _base.resolve(path).toFile();
	}

	private long idOf(String path, String name) {
		return SearchIndex.photoIds(_servlet.spaceIndex(), path).get(name).longValue();
	}

	/** Every photograph as near to the given vector as the nearest. */
	private List<String> nearestAll(String scope, float[] vector, float image, float text) {
		List<SearchIndex.Scored> found = nearest(scope, vector, image, text, 50);
		List<String> result = new ArrayList<>();
		for (SearchIndex.Scored each : found) {
			if (each.score() >= found.get(0).score() - 1e-5) {
				result.add(path(each.match()));
			}
		}
		return result;
	}

	private static String first(List<SearchIndex.Scored> found) {
		return found.isEmpty() ? null : path(found.get(0).match());
	}

	private static String path(PhotoSearch.Match match) {
		String folder = match.getFolder().relativePath();
		return folder.isEmpty() ? match.getName() : folder + "/" + match.getName();
	}

	private static List<String> names(List<SearchIndex.Scored> found) {
		List<String> result = new ArrayList<>();
		for (SearchIndex.Scored each : found) {
			result.add(path(each.match()));
		}
		return result;
	}

	private static double cosine(float[] left, float[] right) {
		double dot = 0;
		double l = 0;
		double r = 0;
		for (int n = 0; n < left.length; n++) {
			dot += left[n] * right[n];
			l += left[n] * left[n];
			r += right[n] * right[n];
		}
		return dot / Math.sqrt(l * r);
	}

	// --- The index on disk. ---

	private void deleteVectors() throws IOException {
		Path vectors = SpaceIndex.directoryOf(_base).resolve(VectorIndex.DIRECTORY_NAME);
		if (!Files.isDirectory(vectors)) {
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(vectors)) {
			for (Path file : files) {
				Files.delete(file);
			}
		}
		Files.delete(vectors);
	}

	private void markRead() {
		assertTrue(_servlet.spaceIndex().write(connection -> {
			try (PreparedStatement insert = connection.prepareStatement("INSERT INTO arrival_marks "
				+ "(member, folder, actor, first_at, last_at, marked) VALUES (?, ?, ?, ?, ?, ?)")) {
				insert.setString(1, "haui");
				insert.setString(2, A);
				insert.setString(3, "haui");
				insert.setLong(4, 1);
				insert.setLong(5, 2);
				insert.setLong(6, 3);
				insert.executeUpdate();
			}
		}));
	}

	private static int count(SpaceIndex store, String table) {
		return store.read(connection -> {
			try (Statement statement = connection.createStatement();
					ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
				rows.next();
				return Integer.valueOf(rows.getInt(1));
			}
		}, Integer.valueOf(-1)).intValue();
	}

	private static String meta(SpaceIndex store, String name) {
		return store.read(connection -> {
			try (PreparedStatement select = connection.prepareStatement("SELECT val FROM meta WHERE name = ?")) {
				select.setString(1, name);
				try (ResultSet rows = select.executeQuery()) {
					return rows.next() ? rows.getString(1) : null;
				}
			}
		}, null);
	}
}
