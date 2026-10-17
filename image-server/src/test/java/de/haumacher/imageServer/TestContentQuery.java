/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.content.ContentCache;
import de.haumacher.imageServer.content.ContentIndex;
import de.haumacher.imageServer.content.ContentSearch;
import de.haumacher.imageServer.content.ContentText;
import de.haumacher.imageServer.content.Embedder;
import de.haumacher.imageServer.content.EmbedderUnavailableException;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchContent;
import de.haumacher.imageServer.shared.model.SearchCriterion;
import de.haumacher.imageServer.shared.model.SearchDate;
import de.haumacher.imageServer.shared.model.SearchLabel;
import de.haumacher.imageServer.shared.model.SearchNot;
import de.haumacher.imageServer.shared.model.SearchOptions;
import de.haumacher.imageServer.shared.model.SearchOr;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the content criterion of the search, see issue #257 (package 4): a text ranks the
 * photographs by similarity, the other criteria narrow, a guest is answered what they may see, a
 * search that cannot be answered says why, and a saved search stores the text.
 *
 * <p>
 * The library is made of vectors written for the test, without an embedder: each photograph's
 * cosine to the query "sunset" (the first axis) falls with its number, A's first (0 to 119), then
 * B's (120 to 159), and the four last of B fall below {@link ContentSearch#THRESHOLD}. In A, the
 * twenty nearest are private, the next is in the trash, every fifth from 40 on is labelled
 * "flowers", and photograph <code>n</code> of the library was taken at <code>DATE + n</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestContentQuery extends TestCase {

	private static final String STAMP = "fake@0000001/256/fp32/1";

	private static final String A = "Lib/A";

	private static final String B = "Lib/B";

	private static final int IN_A = 120;

	private static final int IN_B = 40;

	private static final long DATE = 1_600_000_000_000L;

	private static final String ADMIN = "admin-token";

	private static final String GUEST = "guest-token";

	/** The query whose vector is the first axis. */
	private static final String SUNSET = "sunset";

	/** A query whose vector is orthogonal to every photograph's. */
	private static final String UNRELATED = "unrelated";

	/** Every photograph above the cut: those up to 155 of the library, less the trash. */
	private static final int ABOVE = 156 - 1;

	private static final File FIXTURE = new File("src/test/fixtures/search-content/index.json");

	private static final File LATER = new File("src/test/fixtures/search-content/later.json");

	private Path _base;

	private ImageServlet _servlet;

	private QueryEmbedder _embedder;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-content-query");
		_embedder = new QueryEmbedder();
		ContentSearch.forget();
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		ContentSearch.forget();
		super.tearDown();
	}

	// --- The order. ---

	public void testAContentQueryAnswersInSimilarityOrder() throws Exception {
		library(SpaceStore.CONTENT_ON);
		AlbumInfo answer = found(search("/", content(SUNSET), ADMIN, null));
		List<String> names = names(answer);
		assertEquals("A page: " + names, ContentSearch.PAGE, names.size());
		assertEquals("The nearest first.", A + "/" + name("a", 0), names.get(0));
		assertFalse("Never the trash.", names.contains(A + "/" + name("a", 20)));
		List<ImagePart> images = images(answer);
		for (int n = 0; n < images.size(); n++) {
			ImagePart image = images.get(n);
			assertTrue("Every answer has its score: " + image.getName(), image.getScore() >= ContentSearch.THRESHOLD);
			if (n > 0) {
				assertTrue("Nearest first: " + names, images.get(n - 1).getScore() >= image.getScore());
			}
		}
		assertEquals("Its own cosine.", Math.cos(Math.PI / 2 / (IN_A + IN_B + 2)), images.get(0).getScore(), 1e-4);
		assertEquals("The query is the text, prefixed as a query.", Arrays.asList(SUNSET), _embedder._asked);

		// A conventional search answers no score.
		AlbumInfo conventional = found(search("/", query(SearchLabel.create().setLabel("flowers")), ADMIN, null));
		assertFalse(images(conventional).isEmpty());
		for (ImagePart image : images(conventional)) {
			assertEquals(0.0, image.getScore());
		}
	}

	/**
	 * Probe: a content search started in a folder ranks the photos below it alone, however near the
	 * photos beside it lie, and answers names relative to that folder.
	 */
	public void testAFolderIsTheScopeOfAContentSearch() throws Exception {
		library(SpaceStore.CONTENT_ON);
		AlbumInfo answer = found(search("/" + B + "/", content(SUNSET), ADMIN, null));
		List<String> names = names(answer);
		assertFalse("Something below the folder is found.", names.isEmpty());
		for (String name : names) {
			assertTrue("Below " + B + ": " + name, name.startsWith("b"));
		}
		List<ImagePart> images = images(answer);
		for (int n = 1; n < images.size(); n++) {
			assertTrue("Nearest first.", images.get(n - 1).getScore() >= images.get(n).getScore());
		}
	}

	public void testMoreIsAPageOfMoreAndTheCutHolds() throws Exception {
		library(SpaceStore.CONTENT_ON);
		List<String> ten = names(found(search("/", content(SUNSET), ADMIN, "10")));
		assertEquals(10, ten.size());
		List<String> all = names(found(search("/", content(SUNSET), ADMIN, "400")));
		assertEquals("Everything above the cut, and nothing below it: " + all, ABOVE, all.size());
		assertEquals("A page is the start of the next.", ten, all.subList(0, 10));
		assertTrue(all.contains(B + "/" + name("b", 35)));
		assertFalse("Below the cut.", all.contains(B + "/" + name("b", 36)));
		assertEquals("Never more than the most.", ABOVE, names(found(search("/", content(SUNSET), ADMIN, "99999"))).size());
		assertEquals("Nonsense is a page.", ContentSearch.PAGE,
			names(found(search("/", content(SUNSET), ADMIN, "many"))).size());
		assertEquals("The query was embedded once.", 1, _embedder._asked.size());
	}

	public void testTheCutLeavesOutUnrelatedPhotos() throws Exception {
		library(SpaceStore.CONTENT_ON);
		FakeResponse response = search("/", content(UNRELATED), ADMIN, null);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertTrue("Nothing is near: nothing is found.", names(found(response)).isEmpty());
	}

	// --- The other criteria narrow. ---

	public void testADateNarrowsWhatIsRanked() throws Exception {
		library(SpaceStore.CONTENT_ON);
		SearchQuery query = query(SearchContent.create().setText(SUNSET),
			SearchDate.create().setFrom(DATE + 50).setTo(DATE + 60));
		List<String> names = names(found(search("/", query, ADMIN, "5")));
		List<String> expected = new ArrayList<>();
		for (int n = 50; n < 55; n++) {
			expected.add(A + "/" + name("a", n));
		}
		assertEquals("k matches, nearest first, inside the span alone.", expected, names);
		assertEquals(10, names(found(search("/", query, ADMIN, null))).size());
	}

	public void testALabelNarrowsWhatIsRanked() throws Exception {
		library(SpaceStore.CONTENT_ON);
		List<String> names = names(found(search("/", query(SearchLabel.create().setLabel("flowers"),
			SearchContent.create().setText(SUNSET)), ADMIN, null)));
		List<String> expected = new ArrayList<>();
		for (int n = 40; n < IN_A; n += 5) {
			expected.add(A + "/" + name("a", n));
		}
		assertEquals(expected, names);
	}

	public void testAGuestIsAnsweredNoPrivatePhotoAndStillK() throws Exception {
		library(SpaceStore.CONTENT_ON);
		List<String> names = names(found(search("/", content(SUNSET), GUEST, "10")));
		assertEquals("k answers all the same: " + names, 10, names.size());
		for (String name : names) {
			assertFalse("Nothing private: " + name, name.startsWith(A + "/") && number(name) <= 20);
		}
		assertEquals("The nearest the guest may see.", A + "/" + name("a", 21), names.get(0));
	}

	// --- Refusals. ---

	public void testContentSwitchedOffIsRefusedWithTheReason() throws Exception {
		library(SpaceStore.CONTENT_OFF);
		FakeResponse response = search("/", content(SUNSET), ADMIN, null);
		assertEquals(HttpServletResponse.SC_CONFLICT, response.status());
		assertEquals(ContentSearch.SWITCHED_OFF, errorMessage(response));
		assertTrue("Nothing was embedded.", _embedder._asked.isEmpty());
		assertFalse("Not offered.", options("/").isContent());
	}

	public void testTheSearchOptionsSayContentSearchIsOn() throws Exception {
		library(SpaceStore.CONTENT_ON);
		assertTrue(options("/").isContent());
	}

	public void testAnEmbedderThatCannotAnswerIsRefusedWithItsReason() throws Exception {
		library(SpaceStore.CONTENT_ON);
		_embedder._problem = "the embedder service is starting";
		FakeResponse response = search("/", content(SUNSET), ADMIN, null);
		assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, response.status());
		assertEquals(ContentSearch.UNAVAILABLE + "the embedder service is starting.", errorMessage(response));

		_servlet.setEmbedder(null);
		response = search("/", content(SUNSET), ADMIN, null);
		assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, response.status());
		assertEquals(ContentSearch.UNAVAILABLE + ContentIndex.NO_EMBEDDER + ".", errorMessage(response));
	}

	public void testAnotherModelThanTheIndexHoldsIsRefused() throws Exception {
		library(SpaceStore.CONTENT_ON);
		_embedder._stamp = "fake@0000002/256/fp32/1";
		FakeResponse response = search("/", content(SUNSET), ADMIN, null);
		assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, response.status());
		assertEquals(ContentSearch.UNAVAILABLE + ContentSearch.OTHER_MODEL + ".", errorMessage(response));
	}

	public void testContentBelowAnOrOrANotIsRefused() throws Exception {
		library(SpaceStore.CONTENT_ON);
		SearchCriterion sunset = SearchContent.create().setText(SUNSET);
		SearchQuery or = SearchQuery.create().setVersion(1).setRoot(SearchOr.create()
			.setCriteria(new ArrayList<>(Arrays.asList(sunset, SearchLabel.create().setLabel("flowers")))));
		assertRefused(ContentSearch.COMBINED, search("/", or, ADMIN, null));
		assertRefused(ContentSearch.COMBINED,
			search("/", query(SearchNot.create().setCriterion(SearchContent.create().setText(SUNSET))), ADMIN, null));
		assertRefused(ContentSearch.COMBINED, search("/",
			query(SearchContent.create().setText(SUNSET), SearchContent.create().setText("sea")), ADMIN, null));
		assertRefused(ContentSearch.COMBINED,
			search("/", query(SearchAnd.create().setCriteria(new ArrayList<>(Arrays.asList(sunset)))), ADMIN, null));
		assertRefused(ContentSearch.EMPTY, search("/", content("  "), ADMIN, null));
		assertTrue("Nothing was embedded.", _embedder._asked.isEmpty());

		// Alone as the root it is answered.
		SearchQuery alone = SearchQuery.create().setVersion(1).setRoot(SearchContent.create().setText(SUNSET));
		assertEquals(ContentSearch.PAGE, names(found(search("/", alone, ADMIN, null))).size());
	}

	private void assertRefused(String message, FakeResponse response) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(message, errorMessage(response));
	}

	// --- Saved searches. ---

	public void testTheStoredFormReadsWritesAndReadsAgain() throws Exception {
		AlbumInfo read = (AlbumInfo) Resource.readResource(reader(Files.readString(FIXTURE.toPath())));
		assertNull("Every criterion of the fixture is known.", PhotoSearch.refusal(read.getQuery()));
		PhotoSearch.ContentQuery content = PhotoSearch.content(read.getQuery());
		assertEquals("sunset over the sea", content.text());
		assertEquals("The others narrow.", 2, ((SearchAnd) content.filter().getRoot()).getCriteria().size());
		assertEquals("The query is never changed.", 3, ((SearchAnd) read.getQuery().getRoot()).getCriteria().size());
		String written = write(read);
		AlbumInfo again = (AlbumInfo) Resource.readResource(reader(written));
		assertEquals(written, write(again));
		assertEquals(1, again.getQuery().getVersion());
	}

	public void testACriterionOfALaterBuildNextToTheContentIsRefusedVisibly() throws Exception {
		AlbumInfo read = (AlbumInfo) Resource.readResource(reader(Files.readString(LATER.toPath())));
		assertEquals(PhotoSearch.NEWER, PhotoSearch.refusal(read.getQuery()));

		library(SpaceStore.CONTENT_ON);
		Files.createDirectories(_base.resolve("Lib/Later"));
		Files.copy(LATER.toPath(), _base.resolve("Lib/Later/index.json"));
		FakeResponse response = get("/Lib/Later/", ADMIN);
		assertEquals(HttpServletResponse.SC_NOT_IMPLEMENTED, response.status());
		assertEquals(PhotoSearch.NEWER, errorMessage(response));
		assertTrue("Never answered without what it does not know.", _embedder._asked.isEmpty());
	}

	public void testTheFixtureOpensInSimilarityOrder() throws Exception {
		library(SpaceStore.CONTENT_ON);
		Files.createDirectories(_base.resolve("Lib/Sunsets"));
		Files.copy(FIXTURE.toPath(), _base.resolve("Lib/Sunsets/index.json"));
		_embedder._queries.put("sunset over the sea", axis(0));
		AlbumInfo answer = found(get("/Lib/Sunsets/", ADMIN));
		List<String> expected = new ArrayList<>();
		for (int n = 50; n < 60; n++) {
			expected.add("A/" + name("a", n));
		}
		assertEquals("Inside the span, nearest first.", expected, names(answer));
		assertEquals("A/" + name("a", 50), answer.getIndexPicture().getImage());
	}

	public void testASavedContentSearchStoresTheTextAndItsMostSimilarPhoto() throws Exception {
		library(SpaceStore.CONTENT_ON);
		FakeResponse created = put("/Lib/Sunset/", write(AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle("Sunset")
			.setQuery(query(SearchContent.create().setText(SUNSET)))), ADMIN);
		assertEquals(created.body(), HttpServletResponse.SC_OK, created.status());
		String stored = Files.readString(_base.resolve("Lib/Sunset/index.json"));
		assertTrue("The text is stored: " + stored, stored.contains("[\"SearchContent\",{\"text\":\"sunset\"}]"));
		assertFalse("Never a vector, never a score: " + stored, stored.contains("score"));
		AlbumInfo sidecar = (AlbumInfo) Resource.readResource(reader(stored));
		assertEquals("The most similar is its picture.", "A/" + name("a", 0), sidecar.getIndexPicture().getImage());

		AlbumInfo opened = found(get("/Lib/Sunset/", ADMIN));
		List<String> names = names(opened);
		assertEquals("A saved search answers the most.", ABOVE, names.size());
		assertEquals("A/" + name("a", 0), names.get(0));
		for (int n = 1; n < names.size(); n++) {
			assertTrue("Nearest first.", images(opened).get(n - 1).getScore() >= images(opened).get(n).getScore());
		}

		assertEquals("A photo it finds is served through it.", HttpServletResponse.SC_OK,
			get("/Lib/Sunset/A/" + name("a", 3), ADMIN).status());
		FakeResponse below = get("/Lib/Sunset/B/" + name("b", 39), ADMIN);
		assertEquals("One below the cut is not.", HttpServletResponse.SC_NOT_FOUND, below.status());
		assertEquals(PhotoSearch.NO_MATCH, errorMessage(below));

		_embedder._problem = "the embedder service is starting";
		assertEquals("A query embedded once is kept.", ABOVE, names(found(get("/Lib/Sunset/", ADMIN))).size());
		ContentSearch.forget();
		FakeResponse down = get("/Lib/Sunset/", ADMIN);
		assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, down.status());
		assertEquals(ContentSearch.UNAVAILABLE + "the embedder service is starting.", errorMessage(down));
	}

	public void testASavedContentSearchCreatedWhileTheEmbedderIsDownGainsItsPictureOnOpening() throws Exception {
		library(SpaceStore.CONTENT_ON);
		_embedder._problem = "the embedder service is starting";
		FakeResponse created = put("/Lib/Sunset/", write(AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle("Sunset")
			.setQuery(query(SearchContent.create().setText(SUNSET)))), ADMIN);
		assertEquals("Created all the same: " + created.body(), HttpServletResponse.SC_OK, created.status());
		assertNull(stored("Lib/Sunset").getIndexPicture());

		// Its own statements are written while the embedder is down, the query kept.
		FakeResponse renamed = put("/Lib/Sunset/", write(AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle("Sunset")
			.setSubTitle("Evenings").setQuery(query(SearchContent.create().setText(SUNSET)))), ADMIN);
		assertEquals(renamed.body(), HttpServletResponse.SC_OK, renamed.status());
		assertEquals("Evenings", stored("Lib/Sunset").getSubTitle());
		assertTrue(stored("Lib/Sunset").getQuery().getRoot() instanceof SearchAnd);

		_embedder._problem = null;
		found(get("/Lib/Sunset/", ADMIN));
		assertEquals("An editor's opening stores the most similar.", "A/" + name("a", 0),
			stored("Lib/Sunset").getIndexPicture().getImage());
	}

	// --- The library. ---

	private void library(String content) throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Content", SpaceStore.ANONYMOUS_PUBLIC, "", SpaceStore.FACES_OFF,
			"", "", content));
		UserStore users = new UserStore(_base);
		UserStore.User admin = new UserStore.User("haui", Roles.ADMIN, "", Instant.now().toString());
		admin.addDevice(new UserStore.Device("Phone", UserStore.hash(ADMIN), Instant.now().toString()));
		users.addUser(admin);
		UserStore.User guest = new UserStore.User("gus", Roles.VIEW, "", Instant.now().toString(), Clearances.PUBLIC,
			false);
		guest.addDevice(new UserStore.Device("Gus's phone", UserStore.hash(GUEST), Instant.now().toString()));
		users.addUser(guest);
		users.nameOwner("haui");
		users.store();
		album(A, "a", IN_A, 0);
		album(B, "b", IN_B, IN_A);
		writeCache(A, "a", IN_A, 0);
		writeCache(B, "b", IN_B, IN_A);
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), auth, "", SpaceStore.load(_base, ""));
		_servlet.init();
		_servlet.setEmbedder(_embedder);
		_servlet.searchIndex().build();
		_servlet.vectors().fill();
		assertEquals(IN_A + IN_B, _servlet.spaceIndex().vectors().size());
	}

	private void album(String path, String prefix, int count, int first) throws IOException {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		StringBuilder parts = new StringBuilder();
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
		for (int n = 0; n < count; n++) {
			String name = name(prefix, n);
			assertTrue(ImageIO.write(image, "jpg", folder.resolve(name).toFile()));
			boolean inA = first == 0;
			int privacy = inA && n < 20 ? Privacy.PRIVATE : Privacy.PUBLIC;
			int rating = inA && n == 20 ? Ratings.TRASH : 0;
			boolean flowers = inA && n >= 40 && n % 5 == 0;
			if (parts.length() > 0) {
				parts.append(',');
			}
			parts.append("[\"ImagePart\",{\"name\":\"").append(name).append("\",\"width\":16,\"height\":16,\"privacy\":")
				.append(privacy).append(",\"rating\":").append(rating).append(",\"date\":").append(DATE + first + n);
			if (flowers) {
				parts.append(",\"labels\":[{\"name\":\"flowers\"}]");
			}
			parts.append("}]");
		}
		Path tmp = folder.resolve("index.json.tmp");
		Files.writeString(tmp, "[\"AlbumInfo\",{\"title\":\"" + prefix + "\",\"parts\":[" + parts + "]}]");
		Files.move(tmp, folder.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
	}

	private void writeCache(String path, String prefix, int count, int first) throws IOException {
		ContentCache.update(_base.resolve(path).toFile(), STAMP, ContentText.stamp(STAMP), cache -> {
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

	private static int number(String found) {
		String name = found.substring(found.lastIndexOf('/') + 1);
		return Integer.parseInt(name.substring(1, 4));
	}

	/** The vector of the photograph of the given number: its cosine to the first axis falls with it. */
	private static float[] vector(int global) {
		float[] result = new float[Embedder.DIMENSION];
		double angle = (global + 1) * (Math.PI / 2 / (IN_A + IN_B + 2));
		result[0] = (float) Math.cos(angle);
		result[1 + global % 255] = (float) Math.sin(angle);
		return result;
	}

	private static float[] axis(int n) {
		float[] result = new float[Embedder.DIMENSION];
		result[n] = 1;
		return result;
	}

	/** An embedder answering the vectors of the test's queries, see {@link TestContentQuery}. */
	static final class QueryEmbedder implements Embedder {

		volatile String _stamp = STAMP;

		volatile String _problem;

		final List<String> _asked = new java.util.concurrent.CopyOnWriteArrayList<>();

		final Map<String, float[]> _queries = new java.util.concurrent.ConcurrentHashMap<>();

		QueryEmbedder() {
			_queries.put(SUNSET, axis(0));
			// No photograph has a part along the last axes.
			_queries.put(UNRELATED, axis(250));
			_queries.put("sea", axis(251));
		}

		@Override
		public float[] embedText(String text, TextKind kind) throws IOException {
			if (_problem != null) {
				throw new EmbedderUnavailableException(_problem);
			}
			assertEquals(TextKind.QUERY, kind);
			_asked.add(text);
			float[] result = _queries.get(text);
			assertNotNull("A query of the test: " + text, result);
			return result.clone();
		}

		@Override
		public float[] embedDocument(String title, String text) throws IOException {
			throw new IOException("Not asked in this test.");
		}

		@Override
		public float[] embedImage(byte[] image) throws IOException {
			throw new IOException("Not asked in this test.");
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
	}

	// --- Asking. ---

	private static SearchQuery content(String text) {
		return query(SearchContent.create().setText(text));
	}

	private static SearchQuery query(SearchCriterion... criteria) {
		return SearchQuery.create().setVersion(1)
			.setRoot(SearchAnd.create().setCriteria(new ArrayList<>(Arrays.asList(criteria))));
	}

	private FakeResponse search(String path, SearchQuery query, String token, String count) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.SEARCH_ACTION);
		if (count != null) {
			parameters.put(ImageServlet.SEARCH_COUNT_PARAM, count);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doPost(request(path, write(query), token, parameters), response.response());
		return response;
	}

	private FakeResponse get(String path, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(path, "", token, parameters), response.response());
		return response;
	}

	private FakeResponse put(String path, String body, String token) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(request(path, body, token, new HashMap<>()), response.response());
		return response;
	}

	private SearchOptions options(String path) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", ImageServlet.SEARCH_OPTIONS_TYPE);
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(path, "", ADMIN, parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return SearchOptions.readSearchOptions(reader(response.body()));
	}

	private static jakarta.servlet.http.HttpServletRequest request(String path, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer " + token);
		return TestImageServletPut.request(path, "application/json", body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	private AlbumInfo stored(String folder) throws IOException {
		return (AlbumInfo) Resource.readResource(reader(Files.readString(_base.resolve(folder).resolve("index.json"))));
	}

	private static AlbumInfo found(FakeResponse response) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue(response.body(), resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static List<ImagePart> images(AlbumInfo album) {
		List<ImagePart> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add((ImagePart) part);
			}
		}
		return result;
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (ImagePart image : images(album)) {
			result.add(image.getName());
		}
		return result;
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an error, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String json) {
		return new JsonReader(new ReaderAdapter(new StringReader(json)));
	}

	private static String write(Object object) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			if (object instanceof Resource) {
				((Resource) object).writeTo(json);
			} else {
				((SearchQuery) object).writeTo(json);
			}
		}
		return buffer.toString();
	}
}
