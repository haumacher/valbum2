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
 * The library is made of vectors written for the test, without an embedder, derived from the
 * built-in cuts so that what follows holds whatever they are: each photograph's cosine to the query
 * "sunset" (the first axis) falls with its number, A's first (0 to 119), then B's (120 to 159), and
 * the four last of B fall below {@link ContentSearch#THRESHOLD}, everything else lies above it. The
 * vectors lie on a cone around the first axis (their angle to it grows slowly with the number, see
 * {@link #polar(int)}), turning around it with the number (see {@link #azimuth(int)}). In A, the
 * twenty nearest are private, the next is in the trash, every fifth from 40 on is labelled
 * "flowers", and photograph <code>n</code> of the library was taken at <code>DATE + n</code>.
 * </p>
 *
 * <p>
 * "More like this" (package 5) compares with the picture of a photograph of this library: the
 * further apart their numbers, the further the pictures of two photographs turn apart around the
 * first axis, so that their cosine falls with the distance of their numbers, and those up to
 * <code>b009</code> are like <code>a000</code> above {@link ContentSearch#SIMILAR_THRESHOLD}, the
 * others below it. That needs the cone to be wide enough: two photographs above the cut of a text
 * search must be able to lie further apart than the cut of "More like this", which holds for a
 * {@link ContentSearch#THRESHOLD} up to about <code>0.76</code> (with a
 * {@link ContentSearch#SIMILAR_THRESHOLD} of <code>0.3</code>); above, the fixture refuses to be
 * built.
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

	/** The number of the last photograph of the library above the built-in cut of a text search. */
	private static final int LAST_ABOVE = 155;

	/** Every photograph above the cut: those up to {@link #LAST_ABOVE} of the library, less the trash. */
	private static final int ABOVE = LAST_ABOVE + 1 - 1;

	/** The number of the last photograph of the library like <code>a000</code>: <code>b009</code>. */
	private static final int LAST_LIKE_A0 = IN_A + 9;

	/**
	 * The built-in cut of a text search as the server answers it: as it is written, not as the float
	 * holds it, see {@link ContentSearch#cut(double, double, float)}.
	 */
	private static final double CUT = Double.parseDouble(Float.toString(ContentSearch.THRESHOLD));

	/** The built-in cut of "More like this" as the server answers it, see {@link #CUT}. */
	private static final double SIMILAR_CUT = Double.parseDouble(Float.toString(ContentSearch.SIMILAR_THRESHOLD));

	/** The angle to the first axis of a photograph scoring exactly {@link #CUT}. */
	private static final double CUT_ANGLE = Math.acos(CUT);

	/**
	 * How much the angle to the first axis grows from one photograph to the next: the cone is narrow,
	 * <code>a000</code> lies at nine tenths of {@link #CUT_ANGLE}, so that every photograph above the
	 * cut is far enough from the first axis to turn away from another one.
	 */
	private static final double POLAR_STEP = CUT_ANGLE / 10 / (LAST_ABOVE + 0.5);

	/** How far a photograph turns around the first axis from one number to the next, see {@link #turn()}. */
	private static final double TURN = turn();

	/** A cut of a text search above the built-in one: in the middle of A. */
	private static final double HIGHER = between(77);

	/** A cut of a text search below the built-in one: all but the very last photograph. */
	private static final double LOWER = between(IN_A + IN_B - 2);

	private static final File FIXTURE = new File("src/test/fixtures/search-content/index.json");

	private static final File LATER = new File("src/test/fixtures/search-content/later.json");

	private static final File SIMILAR = new File("src/test/fixtures/search-content/similar.json");

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
		assertEquals("Its own cosine.", sunset(0), images.get(0).getScore(), 1e-4);
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
		assertTrue(all.contains(B + "/" + name("b", LAST_ABOVE - IN_A)));
		assertFalse("Below the cut.", all.contains(B + "/" + name("b", LAST_ABOVE + 1 - IN_A)));
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
		assertTrue("The text is stored: " + stored, stored.contains("[\"SearchContent\",{\"text\":\"sunset\",\"minSimilarity\":0.0}]"));
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

	// --- More like this (package 5). ---

	/**
	 * The cosine of the pictures of the photographs of the given numbers, see {@link #vector(int)}:
	 * the spherical law of cosines.
	 */
	private static double cosine(int a, int b) {
		return Math.cos(polar(a)) * Math.cos(polar(b))
			+ Math.sin(polar(a)) * Math.sin(polar(b)) * Math.cos(azimuth(a) - azimuth(b));
	}

	/**
	 * The turn from one number to the next that puts the cut of "More like this" of <code>a000</code>
	 * midway between {@link #LAST_LIKE_A0} and the one after; at most half a turn over the library, so
	 * that the cosine to <code>a000</code> falls with the number.
	 */
	private static double turn() {
		double low = 0;
		double high = Math.PI / (IN_A + IN_B - 1);
		if (midLike(high) > SIMILAR_CUT) {
			throw new AssertionError("No fixture for a cut of a text search of " + CUT + " and of More like this of "
				+ SIMILAR_CUT + ": the photographs above the first lie too close to each other.");
		}
		for (int n = 0; n < 100; n++) {
			double middle = (low + high) / 2;
			if (midLike(middle) > SIMILAR_CUT) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return (low + high) / 2;
	}

	/** The cosine to <code>a000</code> midway between {@link #LAST_LIKE_A0} and the next, at the given turn. */
	private static double midLike(double turn) {
		return (like(LAST_LIKE_A0, turn) + like(LAST_LIKE_A0 + 1, turn)) / 2;
	}

	private static double like(int global, double turn) {
		return Math.cos(polar(0)) * Math.cos(polar(global))
			+ Math.sin(polar(0)) * Math.sin(polar(global)) * Math.cos(global * turn);
	}

	/** Every photograph "like" the first one of A for an editor: up to b009, less itself and the trash. */
	private static final int LIKE_A0 = LAST_LIKE_A0 + 1 - 2;

	public void testMoreLikeThisAnswersTheNearestFirstWithoutThePhotoItself() throws Exception {
		library(SpaceStore.CONTENT_ON);
		AlbumInfo answer = found(search("/", similar(A + "/" + name("a", 0)), ADMIN, null));
		List<String> names = names(answer);
		assertEquals("A page: " + names, ContentSearch.PAGE, names.size());
		assertEquals("The nearest first.", A + "/" + name("a", 1), names.get(0));
		assertFalse("Never the photo itself.", names.contains(A + "/" + name("a", 0)));
		assertFalse("Never the trash.", names.contains(A + "/" + name("a", 20)));
		assertEquals(de.haumacher.imageServer.shared.model.SearchProblem.NONE, answer.getSearchProblem());
		List<ImagePart> images = images(answer);
		assertEquals("Its picture's cosine.", cosine(0, 1), images.get(0).getScore(), 1e-4);
		for (int n = 0; n < images.size(); n++) {
			assertTrue("Above the cut: " + images.get(n).getName(),
				images.get(n).getScore() >= ContentSearch.SIMILAR_THRESHOLD);
			if (n > 0) {
				assertTrue("Nearest first: " + names, images.get(n - 1).getScore() >= images.get(n).getScore());
			}
		}
		assertTrue("No embedder is asked.", _embedder._asked.isEmpty());

		List<String> all = names(found(search("/", similar(A + "/" + name("a", 0)), ADMIN, "400")));
		assertEquals("Everything above its own cut: " + all, LIKE_A0, all.size());
		assertTrue(all.contains(B + "/" + name("b", LAST_LIKE_A0 - IN_A)));
		assertFalse("Below the cut.", all.contains(B + "/" + name("b", LAST_LIKE_A0 + 1 - IN_A)));
		assertEquals("A page is the start of the next.", names, all.subList(0, ContentSearch.PAGE));
	}

	public void testMoreLikeThisIsNarrowedByTheOtherCriteria() throws Exception {
		library(SpaceStore.CONTENT_ON);
		SearchQuery flowers = query(similarTo(A + "/" + name("a", 0)), SearchLabel.create().setLabel("flowers"));
		List<String> expected = new ArrayList<>();
		for (int n = 40; n < IN_A; n += 5) {
			expected.add(A + "/" + name("a", n));
		}
		assertEquals("Only what carries the label, nearest first.", expected, names(found(search("/", flowers, ADMIN, null))));

		List<String> inB = names(found(search("/" + B + "/", similar(A + "/" + name("a", 0)), ADMIN, null)));
		List<String> below = new ArrayList<>();
		for (int n = 0; n <= LAST_LIKE_A0 - IN_A; n++) {
			below.add(name("b", n));
		}
		assertEquals("Only what lies below the folder searched, though the photo lies elsewhere.", below, inB);

		assertEquals("A guest is answered what they may see, and still k.", 10,
			names(found(search("/", similar(A + "/" + name("a", 30)), GUEST, "10"))).size());
		for (String name : names(found(search("/", similar(A + "/" + name("a", 30)), GUEST, null)))) {
			assertFalse("Nothing private: " + name, name.startsWith(A + "/") && number(name) <= 20);
		}
	}

	public void testAPhotoTheCallerMayNotSeeIsAnsweredAsOneThatIsNotThere() throws Exception {
		library(SpaceStore.CONTENT_ON);
		FakeResponse hidden = search("/", similar(A + "/" + name("a", 5)), GUEST, null);
		FakeResponse gone = search("/", similar(A + "/" + "zzz.jpg"), GUEST, null);
		FakeResponse outside = search("/", similar("../" + name("a", 5)), GUEST, null);
		AlbumInfo answer = found(hidden);
		assertTrue("Nothing found.", names(answer).isEmpty());
		assertEquals(de.haumacher.imageServer.shared.model.SearchProblem.PHOTO_NOT_FOUND, answer.getSearchProblem());
		assertEquals("Not a word of where it lies.", "",
			PhotoSearch.content(answer.getQuery()).similar().getPath());
		assertEquals("A private photo is answered as one that is not there: no oracle.", gone.body(),
			hidden.body());
		assertEquals(gone.body(), outside.body());

		// The editor sees it.
		assertFalse(names(found(search("/", similar(A + "/" + name("a", 5)), ADMIN, null))).isEmpty());
	}

	public void testALinkComparesWithWhatTheLinkSeesAlone() throws Exception {
		library(SpaceStore.CONTENT_ON);
		de.haumacher.imageServer.auth.ShareStore shares = new de.haumacher.imageServer.auth.ShareStore(_base);
		String link = shares.create("haui", "Lib", "", "", Privacy.PUBLIC, Ratings.MIN,
			Arrays.asList(de.haumacher.imageServer.auth.Rights.VIEW)).getToken();
		savedSearch("Lib/Hidden", similarTo(A + "/" + name("a", 5)));
		savedSearch("Lib/Near", similarTo(A + "/" + name("a", 30)));
		restart();

		AlbumInfo hidden = found(get("/Hidden/", link));
		assertTrue("Nothing found through the link.", names(hidden).isEmpty());
		assertEquals(de.haumacher.imageServer.shared.model.SearchProblem.PHOTO_NOT_FOUND, hidden.getSearchProblem());
		assertEquals("", PhotoSearch.content(hidden.getQuery()).similar().getPath());

		AlbumInfo near = found(get("/Near/", link));
		assertFalse(names(near).isEmpty());
		for (String name : names(near)) {
			assertFalse("Nothing private: " + name, name.startsWith("A/") && number(name) <= 20);
		}
		assertEquals("Where the photo lies is no business of a link.", "",
			PhotoSearch.content(near.getQuery()).similar().getPath());
		assertEquals("The editor is answered where it lies.", A + "/" + name("a", 30),
			PhotoSearch.content(found(get("/Lib/Near/", ADMIN)).getQuery()).similar().getPath());
		assertEquals("A search is still for the members.", HttpServletResponse.SC_FORBIDDEN,
			search("/", similar(A + "/" + name("a", 30)), link, null).status());
	}

	public void testAPhotoWithoutAVectorIsNotDescribedYet() throws Exception {
		library(SpaceStore.CONTENT_ON);
		album("Lib/C", "c", 1, 1000);
		_servlet.searchIndex().build();
		AlbumInfo answer = found(search("/", similar("Lib/C/" + name("c", 0)), ADMIN, null));
		assertTrue(names(answer).isEmpty());
		assertEquals(de.haumacher.imageServer.shared.model.SearchProblem.PHOTO_NOT_DESCRIBED,
			answer.getSearchProblem());
		assertEquals("The editor's reference is kept.", "Lib/C/" + name("c", 0),
			PhotoSearch.content(answer.getQuery()).similar().getPath());
	}

	public void testMoreLikeThisNeedsNoEmbedder() throws Exception {
		library(SpaceStore.CONTENT_ON);
		_embedder._problem = "the embedder service is starting";
		assertEquals(LIKE_A0, names(found(search("/", similar(A + "/" + name("a", 0)), ADMIN, "400"))).size());
		_servlet.setEmbedder(null);
		assertEquals(LIKE_A0, names(found(search("/", similar(A + "/" + name("a", 0)), ADMIN, "400"))).size());
		assertTrue(_embedder._asked.isEmpty());
	}

	public void testMoreLikeThisIsRefusedWhereItCannotRank() throws Exception {
		library(SpaceStore.CONTENT_ON);
		String photo = A + "/" + name("a", 0);
		assertRefused(ContentSearch.TEXT_AND_PHOTO,
			search("/", query(SearchContent.create().setText(SUNSET), similarTo(photo)), ADMIN, null));
		assertRefused(ContentSearch.COMBINED, search("/", query(similarTo(photo), similarTo(photo)), ADMIN, null));
		assertRefused(ContentSearch.COMBINED,
			search("/", query(SearchNot.create().setCriterion(similarTo(photo))), ADMIN, null));
		SearchQuery alone = SearchQuery.create().setVersion(1).setRoot(similarTo(photo));
		assertEquals("Alone as the root it is answered.", ContentSearch.PAGE,
			names(found(search("/", alone, ADMIN, null))).size());
	}

	public void testMoreLikeThisInASpaceWithoutContentSearchIsRefused() throws Exception {
		library(SpaceStore.CONTENT_OFF);
		FakeResponse response = search("/", similar(A + "/" + name("a", 0)), ADMIN, null);
		assertEquals(HttpServletResponse.SC_CONFLICT, response.status());
		assertEquals(ContentSearch.SWITCHED_OFF, errorMessage(response));
	}

	public void testTheStoredFormOfMoreLikeThisReadsWritesAndReadsAgain() throws Exception {
		AlbumInfo read = (AlbumInfo) Resource.readResource(reader(Files.readString(SIMILAR.toPath())));
		assertNull("Every criterion of the fixture is known.", PhotoSearch.refusal(read.getQuery()));
		PhotoSearch.ContentQuery content = PhotoSearch.content(read.getQuery());
		assertNull(content.text());
		assertEquals("Lib/A/a000.jpg", content.similar().getPath());
		assertEquals(64, content.similar().getHash().length());
		assertEquals("The other narrows.", 1, ((SearchAnd) content.filter().getRoot()).getCriteria().size());
		String written = write(read);
		AlbumInfo again = (AlbumInfo) Resource.readResource(reader(written));
		assertEquals(written, write(again));
		assertEquals(PhotoSearch.content(read.getQuery()).similar().getHash(),
			PhotoSearch.content(again.getQuery()).similar().getHash());
	}

	public void testASavedMoreLikeThisStoresTheReferenceAndAnswersWhoMaySeeIt() throws Exception {
		library(SpaceStore.CONTENT_ON);
		File folder = _base.resolve(A).toFile();
		// Contents of its own: the photos of the library are alike to the byte, and the content
		// hash is the identity of a photo.
		BufferedImage own = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
		own.setRGB(3, 3, 0xffffff);
		assertTrue(ImageIO.write(own, "jpg", new File(folder, name("a", 0))));
		de.haumacher.imageServer.upload.HashCache hashes = new de.haumacher.imageServer.upload.HashCache(folder);
		hashes.hashByName();
		hashes.flush();
		String photo = A + "/" + name("a", 0);
		FakeResponse created = put("/Lib/Like/", write(AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle("Like")
			.setQuery(query(similarTo(photo), SearchLabel.create().setLabel("flowers")))), ADMIN);
		assertEquals(created.body(), HttpServletResponse.SC_OK, created.status());
		String stored = Files.readString(_base.resolve("Lib/Like/index.json"));
		assertFalse("Never a vector, never a score: " + stored, stored.contains("score"));
		PhotoSearch.ContentQuery content = PhotoSearch.content(stored("Lib/Like").getQuery());
		assertEquals("The reference is stored.", photo, content.similar().getPath());
		assertEquals("Anchored by the content hash.", de.haumacher.imageServer.upload.HashCache.sha256(
			_base.resolve(photo).toFile()), content.similar().getHash());
		assertEquals("The most similar is its picture.", "A/" + name("a", 40), stored("Lib/Like").getIndexPicture().getImage());

		AlbumInfo opened = found(get("/Lib/Like/", ADMIN));
		List<String> expected = new ArrayList<>();
		for (int n = 40; n < IN_A; n += 5) {
			expected.add("A/" + name("a", n));
		}
		assertEquals("Nearest first.", expected, names(opened));
		assertEquals(photo, PhotoSearch.content(opened.getQuery()).similar().getPath());
		assertEquals("A photo it finds is served through it.", HttpServletResponse.SC_OK,
			get("/Lib/Like/A/" + name("a", 45), ADMIN).status());
		assertEquals("The photo itself is none of them.", HttpServletResponse.SC_NOT_FOUND,
			get("/Lib/Like/" + "A/" + name("a", 0), ADMIN).status());

		// The guest may not see the photo it compares with: the search opens, finds nothing, says
		// why, and gives away nothing of the photo.
		AlbumInfo guest = found(get("/Lib/Like/", GUEST));
		assertTrue(names(guest).isEmpty());
		assertEquals(de.haumacher.imageServer.shared.model.SearchProblem.PHOTO_NOT_FOUND, guest.getSearchProblem());
		de.haumacher.imageServer.shared.model.PhotoRef answered = PhotoSearch.content(guest.getQuery()).similar();
		assertEquals("", answered.getPath());
		assertEquals("", answered.getHash());
		assertEquals("Nor is a photo it would find served through it.", HttpServletResponse.SC_NOT_FOUND,
			get("/Lib/Like/A/" + name("a", 45), GUEST).status());

		// Written back with the reference it was answered without, the stored one stays.
		FakeResponse renamed = put("/Lib/Like/", write(AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle("Like")
			.setSubTitle("Flowers").setQuery(guest.getQuery())), ADMIN);
		assertEquals(renamed.body(), HttpServletResponse.SC_OK, renamed.status());
		assertEquals("Flowers", stored("Lib/Like").getSubTitle());
		assertEquals(photo, PhotoSearch.content(stored("Lib/Like").getQuery()).similar().getPath());

		// Once the photo is gone, the search says so.
		Files.delete(_base.resolve(photo));
		_servlet.searchIndex().build();
		AlbumInfo gone = found(get("/Lib/Like/", ADMIN));
		assertTrue(names(gone).isEmpty());
		assertEquals(de.haumacher.imageServer.shared.model.SearchProblem.PHOTO_NOT_FOUND, gone.getSearchProblem());
		assertFalse("Never stored.", Files.readString(_base.resolve("Lib/Like/index.json")).contains("PHOTO_NOT"));
	}

	/** A saved "More like this" follows its photo when the photo is moved to another album. */
	public void testASavedMoreLikeThisFollowsItsPhotoThroughAMove() throws Exception {
		library(SpaceStore.CONTENT_ON);
		File folder = _base.resolve(A).toFile();
		BufferedImage own = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
		own.setRGB(5, 7, 0xff00ff);
		assertTrue(ImageIO.write(own, "jpg", new File(folder, name("a", 50))));
		de.haumacher.imageServer.upload.HashCache hashes = new de.haumacher.imageServer.upload.HashCache(folder);
		hashes.hashByName();
		hashes.flush();
		String photo = A + "/" + name("a", 50);
		FakeResponse created = put("/Lib/Like/", write(AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle("Like")
			.setQuery(similar(photo))), ADMIN);
		assertEquals(created.body(), HttpServletResponse.SC_OK, created.status());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse move = new FakeResponse();
		_servlet.doPost(request("/" + A + "/",
			"{\"target\":\"" + B + "\",\"names\":[{\"name\":\"" + name("a", 50) + "\"}]}", ADMIN, parameters),
			move.response());
		assertEquals(move.body(), HttpServletResponse.SC_OK, move.status());
		assertTrue(_base.resolve(B).resolve(name("a", 50)).toFile().exists());
		_servlet.searchIndex().build();

		AlbumInfo opened = found(get("/Lib/Like/", ADMIN));
		assertFalse("The photo is still there, only elsewhere.",
			opened.getSearchProblem() == de.haumacher.imageServer.shared.model.SearchProblem.PHOTO_NOT_FOUND);
		assertEquals("It is found where it lies now.", B + "/" + name("a", 50),
			PhotoSearch.content(opened.getQuery()).similar().getPath());
	}

	// --- The cut of a query and of the space (issue #302). ---

	/** The score of the photograph of the given number for {@link #SUNSET}, see {@link #vector(int)}. */
	private static double sunset(int global) {
		return Math.cos(polar(global));
	}

	/** A cut of a text search midway between the scores of the photograph of the given number and the next. */
	private static double between(int global) {
		return (sunset(global) + sunset(global + 1)) / 2;
	}

	/** How many photographs an editor is answered for {@link #SUNSET} at the given cut: the trash never. */
	private static int sunsetAbove(double cut) {
		int result = 0;
		for (int n = 0; n < IN_A + IN_B; n++) {
			if (n != 20 && sunset(n) >= cut) {
				result++;
			}
		}
		return result;
	}

	private static SearchQuery content(String text, double minSimilarity) {
		return query(SearchContent.create().setText(text).setMinSimilarity(minSimilarity));
	}

	public void testTheCutOfAQueryOverridesTheDefault() throws Exception {
		library(SpaceStore.CONTENT_ON);
		List<ImagePart> higher = images(found(search("/", content(SUNSET, HIGHER), ADMIN, "400")));
		assertEquals("Fewer than at the default.", sunsetAbove(HIGHER), higher.size());
		assertTrue(higher.size() < ABOVE);
		for (ImagePart image : higher) {
			assertTrue("At least the cut: " + image.getName(), image.getScore() >= HIGHER - 1e-6);
		}
		List<String> lower = names(found(search("/", content(SUNSET, LOWER), ADMIN, "400")));
		assertEquals("More than at the default.", sunsetAbove(LOWER), lower.size());
		assertTrue(lower.size() > ABOVE);
		assertTrue("What the default cut leaves out.", lower.contains(B + "/" + name("b", LAST_ABOVE + 1 - IN_A)));

		List<ImagePart> like = images(found(search("/",
			query(similarTo(A + "/" + name("a", 0)).setMinSimilarity(0.9)), ADMIN, "400")));
		assertFalse(like.isEmpty());
		assertTrue("Fewer than at the default.", like.size() < LIKE_A0);
		for (ImagePart image : like) {
			assertTrue("At least the cut: " + image.getName(), image.getScore() >= 0.9 - 1e-6);
		}
	}

	public void testACutOfAQueryIsClampedAndNoneIsTheDefault() throws Exception {
		library(SpaceStore.CONTENT_ON);
		assertEquals("Above one is one: nothing is that alike.", 0,
			names(found(search("/", content(SUNSET, 5), ADMIN, "400"))).size());
		assertEquals("A negative cut is none.", ABOVE, names(found(search("/", content(SUNSET, -0.3), ADMIN, "400"))).size());
		assertEquals("Not a number is none.", CUT, ContentSearch.cut(Double.NaN, 0, ContentSearch.THRESHOLD), 0.0);
		assertEquals("The built-in cut as it is written, not as the float holds it.", 0.7,
			ContentSearch.cut(0, 0, 0.7f), 0.0);
		assertEquals("None is the default.", ABOVE, names(found(search("/", content(SUNSET, 0), ADMIN, "400"))).size());
	}

	public void testAQueryWithoutACutIsAnsweredWithTheCutOfTheSpace() throws Exception {
		library(SpaceStore.CONTENT_ON, new SpaceStore.Thresholds(HIGHER, 0.9));
		SearchOptions options = options("/");
		assertEquals(HIGHER, options.getContentThreshold());
		assertEquals(0.9, options.getSimilarThreshold());
		assertEquals("The space's cut.", sunsetAbove(HIGHER), names(found(search("/", content(SUNSET), ADMIN, "400"))).size());
		assertEquals("The query's cut comes first.", sunsetAbove(LOWER),
			names(found(search("/", content(SUNSET, LOWER), ADMIN, "400"))).size());
		for (ImagePart image : images(found(search("/", similar(A + "/" + name("a", 0)), ADMIN, "400")))) {
			assertTrue("The space's cut of More like this: " + image.getName(), image.getScore() >= 0.9 - 1e-6);
		}
	}

	/**
	 * Probe: the administrator changes the space's cut of "More like this" in the settings while the
	 * server runs; the next search answers with it, and a saved search without a cut of its own follows.
	 */
	public void testTheCutOfTheSpaceChangedInTheSettingsHoldsAtOnce() throws Exception {
		library(SpaceStore.CONTENT_ON);
		savedSearch("Lib/Like", similarTo(A + "/" + name("a", 0)));
		_servlet.searchIndex().build();
		int before = names(found(get("/Lib/Like/", ADMIN))).size();

		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "space-settings");
		FakeResponse read = new FakeResponse();
		_servlet.doGet(request("/", "", ADMIN, parameters), read.response());
		de.haumacher.imageServer.shared.model.SpaceSettings settings = de.haumacher.imageServer.shared.model.SpaceSettings
			.readSpaceSettings(reader(read.body()));
		settings.getThresholds().setSimilar(0.9);
		parameters = new HashMap<>();
		parameters.put("action", "space-settings");
		FakeResponse saved = new FakeResponse();
		StringWriter body = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(body))) {
			settings.writeTo(json);
		}
		_servlet.doPost(request("/", body.toString(), ADMIN, parameters), saved.response());
		assertEquals(saved.body(), HttpServletResponse.SC_OK, saved.status());

		List<ImagePart> live = images(found(search("/", similar(A + "/" + name("a", 0)), ADMIN, "400")));
		assertFalse(live.isEmpty());
		for (ImagePart image : live) {
			assertTrue("At once, no restart: " + image.getScore(), image.getScore() >= 0.9 - 1e-6);
		}
		int after = names(found(get("/Lib/Like/", ADMIN))).size();
		assertTrue("The saved search follows the space: " + before + " -> " + after, after < before);
	}

	public void testASpaceWithoutACutAnswersTheBuiltInOne() throws Exception {
		library(SpaceStore.CONTENT_ON);
		SearchOptions options = options("/");
		assertEquals(CUT, options.getContentThreshold(), 1e-12);
		assertEquals(SIMILAR_CUT, options.getSimilarThreshold(), 1e-12);
		assertEquals(ABOVE, names(found(search("/", content(SUNSET), ADMIN, "400"))).size());
		assertEquals(LIKE_A0, names(found(search("/", similar(A + "/" + name("a", 0)), ADMIN, "400"))).size());
	}

	public void testAQueryStatingACutIsVersionTwoAndOneWithoutStaysOne() throws Exception {
		assertEquals(PhotoSearch.FIRST_VERSION, PhotoSearch.normalized(content(SUNSET)).getVersion());
		assertEquals(PhotoSearch.VERSION, PhotoSearch.normalized(content(SUNSET, 0.4)).getVersion());
		assertEquals(PhotoSearch.VERSION, PhotoSearch.normalized(similar("Lib/A/a000.jpg").setVersion(0)
			.setRoot(similarTo("Lib/A/a000.jpg").setMinSimilarity(0.7))).getVersion());
		assertNull(PhotoSearch.refusal(PhotoSearch.normalized(content(SUNSET, 0.4))));
		assertEquals("A build of version two refuses what it does not know.", PhotoSearch.NEWER,
			PhotoSearch.refusal(content(SUNSET).setVersion(PhotoSearch.VERSION + 1)));
	}

	public void testASavedSearchStoresItsCutAndAnswersWithIt() throws Exception {
		library(SpaceStore.CONTENT_ON);
		FakeResponse created = put("/Lib/Sunset/", write(AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle("Sunset")
			.setQuery(content(SUNSET, HIGHER))), ADMIN);
		assertEquals(created.body(), HttpServletResponse.SC_OK, created.status());
		SearchQuery stored = stored("Lib/Sunset").getQuery();
		assertEquals("Stated: an older build refuses it rather than answering its own cut.", PhotoSearch.VERSION,
			stored.getVersion());
		assertEquals(HIGHER, PhotoSearch.content(stored).minSimilarity());

		AlbumInfo opened = found(get("/Lib/Sunset/", ADMIN));
		assertEquals("Answered with its cut.", sunsetAbove(HIGHER), names(opened).size());
		assertEquals("The answer says its cut.", HIGHER, PhotoSearch.content(opened.getQuery()).minSimilarity());

		String written = write(stored("Lib/Sunset"));
		assertEquals("Read, written, read: the same.", written,
			write(Resource.readResource(reader(written))));

		// "More like this" keeps its cut through the anchoring of its photograph.
		FakeResponse like = put("/Lib/Like/", write(AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle("Like")
			.setQuery(query(similarTo(A + "/" + name("a", 0)).setMinSimilarity(0.9)))), ADMIN);
		assertEquals(like.body(), HttpServletResponse.SC_OK, like.status());
		assertEquals(0.9, PhotoSearch.content(stored("Lib/Like").getQuery()).minSimilarity());
		AlbumInfo likeOpened = found(get("/Lib/Like/", ADMIN));
		assertEquals(0.9, PhotoSearch.content(likeOpened.getQuery()).minSimilarity());
		for (ImagePart image : images(likeOpened)) {
			assertTrue(image.getName(), image.getScore() >= 0.9 - 1e-6);
		}
	}

	private void savedSearch(String folder, SearchCriterion... criteria) throws IOException {
		Files.createDirectories(_base.resolve(folder));
		Files.writeString(_base.resolve(folder).resolve("index.json"),
			write(AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle("Like").setQuery(query(criteria))));
	}

	private void restart() throws Exception {
		_servlet.destroy();
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), auth, "", SpaceStore.load(_base, ""));
		_servlet.init();
		_servlet.setEmbedder(_embedder);
		_servlet.searchIndex().build();
		_servlet.vectors().fill();
	}

	private static de.haumacher.imageServer.shared.model.SearchSimilar similarTo(String path) {
		return de.haumacher.imageServer.shared.model.SearchSimilar.create()
			.setRef(de.haumacher.imageServer.shared.model.PhotoRef.create().setPath(path));
	}

	private static SearchQuery similar(String path) {
		return query(similarTo(path));
	}

	// --- The library. ---

	private void library(String content) throws Exception {
		library(content, SpaceStore.Thresholds.NONE);
	}

	private void library(String content, SpaceStore.Thresholds thresholds) throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Content", SpaceStore.ANONYMOUS_PUBLIC, "", SpaceStore.FACES_OFF,
			"", "", content, thresholds));
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

	/**
	 * The vector of the photograph of the given number: its cosine to the first axis falls with it,
	 * and it turns around the first axis with it, see {@link TestContentQuery}.
	 */
	private static float[] vector(int global) {
		float[] result = new float[Embedder.DIMENSION];
		result[0] = (float) Math.cos(polar(global));
		result[1] = (float) (Math.sin(polar(global)) * Math.cos(azimuth(global)));
		result[2] = (float) (Math.sin(polar(global)) * Math.sin(azimuth(global)));
		return result;
	}

	/**
	 * The angle of the photograph of the given number to the first axis: {@link #CUT_ANGLE} midway
	 * between {@link #LAST_ABOVE} and the next.
	 */
	private static double polar(int global) {
		return CUT_ANGLE + (global - LAST_ABOVE - 0.5) * POLAR_STEP;
	}

	/** How far the photograph of the given number is turned around the first axis. */
	private static double azimuth(int global) {
		return global * TURN;
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
