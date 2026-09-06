/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.Placement;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the placement rule of a folder (issue #48): where a created, a moved and an
 * already-present album is filed.
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder with the request and response fakes
 * of {@link TestImageServletPut}, exactly as {@link TestImageServletMove} does.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPlacement extends TestCase {

	private static final String SECRET = "let-me-in";

	private static final String ALBUM_JSON = "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[]}]";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private ImageServlet _servlet;

	private String _token;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-placement-test");
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		_servlet = null;
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- What a rule answers. ---

	public void testWhereEachRuleFiles() {
		Path folder = Paths.get("/library");
		AlbumDate day = AlbumDate.ofFolderName("2020-05-01");
		AlbumDate year = AlbumDate.ofFolderName("2020");

		assertNull("A folder without a rule files nothing.",
			PlacementRule.of(Placement.NONE).placementFor(folder, day));
		assertEquals(folder.resolve("2020"), PlacementRule.of(Placement.BY_YEAR).placementFor(folder, day));
		assertEquals(folder.resolve("2020").resolve("2020-05"),
			PlacementRule.of(Placement.BY_YEAR_MONTH).placementFor(folder, day));
		assertEquals("A month folder names its year, too.", "2020-05", PlacementRule.monthName(day));

		assertEquals("A date known to the year alone states no month.", folder.resolve("2020"),
			PlacementRule.of(Placement.BY_YEAR_MONTH).placementFor(folder, year));
		assertNull("Nothing is filed that has no date.",
			PlacementRule.of(Placement.BY_YEAR).placementFor(folder, AlbumDate.NONE));
	}

	public void testWhatIsAlreadyAPlacementFolder() {
		assertTrue(PlacementRule.isPlacementFolder("2020"));
		assertTrue(PlacementRule.isPlacementFolder("2020-05"));
		assertFalse(PlacementRule.isPlacementFolder("2020-05-01"));
		assertFalse(PlacementRule.isPlacementFolder("2020 Trip"));
		assertFalse(PlacementRule.isPlacementFolder("Trip"));
	}

	// --- An album created in a folder with a rule. ---

	public void testAnAlbumIsCreatedInItsYearFolder() throws Exception {
		rule(Placement.BY_YEAR);

		CreateResult result = create("/2020-05-01 Trip", ALBUM_JSON);

		assertEquals("2020/2020-05-01 Trip", result.getPath());
		assertEquals(PlacementRule.filedIn("2020"), result.getMessage());
		assertTrue(_base.resolve("2020/2020-05-01 Trip/index.json").toFile().exists());
		assertFalse("Nothing is left where it was asked for.", _base.resolve("2020-05-01 Trip").toFile().exists());
	}

	public void testAnAlbumIsCreatedInItsMonthFolder() throws Exception {
		rule(Placement.BY_YEAR_MONTH);

		CreateResult result = create("/2020-05-01 Trip", ALBUM_JSON);

		assertEquals("2020/2020-05/2020-05-01 Trip", result.getPath());
		assertEquals(PlacementRule.filedIn("2020/2020-05"), result.getMessage());
		assertTrue(_base.resolve("2020/2020-05/2020-05-01 Trip/index.json").toFile().exists());
	}

	public void testTheDateOfTheRequestFilesTheAlbum() throws Exception {
		rule(Placement.BY_YEAR);
		long date = AlbumDate.ofFolderName("1999-12-31").millis();

		CreateResult result = create("/Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"date\":" + date + ",\"parts\":[]}]");

		assertEquals("The date the dialog set files the album, whatever the folder is named.", "1999/Trip",
			result.getPath());
		assertTrue(_base.resolve("1999/Trip/index.json").toFile().exists());
	}

	public void testAnAlbumWithoutADateStaysWhereItWasAsked() throws Exception {
		rule(Placement.BY_YEAR);

		CreateResult result = create("/Trip", ALBUM_JSON);

		assertEquals("Trip", result.getPath());
		assertEquals("Nothing happened to it, so nothing is said.", "", result.getMessage());
		assertTrue(_base.resolve("Trip/index.json").toFile().exists());
	}

	public void testAnAlbumCreatedInAFolderWithoutARule() throws Exception {
		CreateResult result = create("/2020-05-01 Trip", ALBUM_JSON);

		assertEquals("2020-05-01 Trip", result.getPath());
		assertEquals("", result.getMessage());
		assertTrue(_base.resolve("2020-05-01 Trip/index.json").toFile().exists());
	}

	public void testACreatedAlbumShowsUpInTheListing() throws Exception {
		rule(Placement.BY_YEAR);
		create("/2020-05-01 Trip", ALBUM_JSON);

		assertTrue("The year folder is in the listing at once.", json("/").contains("\"name\":\"2020\""));
		assertTrue(json("/2020/").contains("\"name\":\"2020-05-01 Trip\""));
	}

	public void testACreationOntoATakenNameIsRefused() throws Exception {
		rule(Placement.BY_YEAR);
		Files.createDirectories(_base.resolve("2020/2020-05-01 Trip"));

		FakeResponse response = createResponse("/2020-05-01 Trip", ALBUM_JSON);

		assertEquals(HttpServletResponse.SC_CONFLICT, response.status());
		assertEquals(MoveService.nameTaken("2020-05-01 Trip"), errorMessage(response));
		assertFalse("Nothing is overwritten.",
			_base.resolve("2020/2020-05-01 Trip/index.json").toFile().exists());
	}

	// --- An album moved into a folder with a rule. ---

	public void testAMovedFolderIsFiledByTheTargetsRule() throws Exception {
		album("Inbox/2021-03-07 Ski");
		Files.createDirectories(_base.resolve("Archive"));
		rule("Archive", Placement.BY_YEAR_MONTH);

		MoveResult result = move("/Inbox/", "Archive", "2021-03-07 Ski");

		assertEquals("The outcome names the path below the target, not just the name.",
			Collections.singletonList("2021/2021-03/2021-03-07 Ski"), newNames(result));
		assertTrue(_base.resolve("Archive/2021/2021-03/2021-03-07 Ski/a.jpg").toFile().exists());
		assertFalse(_base.resolve("Inbox/2021-03-07 Ski").toFile().exists());
	}

	public void testAMovedFolderWithoutADateLandsWhereItWasSent() throws Exception {
		album("Inbox/Ski");
		Files.createDirectories(_base.resolve("Archive"));
		rule("Archive", Placement.BY_YEAR);

		MoveResult result = move("/Inbox/", "Archive", "Ski");

		assertEquals(Collections.singletonList("Ski"), newNames(result));
		assertTrue(_base.resolve("Archive/Ski/a.jpg").toFile().exists());
	}

	public void testAMovedYearFolderIsNotFiledAgain() throws Exception {
		album("Inbox/2021/Ski");
		Files.createDirectories(_base.resolve("Archive"));
		rule("Archive", Placement.BY_YEAR);

		MoveResult result = move("/Inbox/", "Archive", "2021");

		assertEquals("A year folder is where it belongs.", Collections.singletonList("2021"), newNames(result));
		assertTrue(_base.resolve("Archive/2021/Ski/a.jpg").toFile().exists());
	}

	public void testAMoveOntoATakenNameInTheYearFolderIsRefused() throws Exception {
		album("Inbox/2021-03-07 Ski");
		Files.createDirectories(_base.resolve("Archive/2021/2021-03-07 Ski"));
		rule("Archive", Placement.BY_YEAR);

		MoveResult result = move("/Inbox/", "Archive", "2021-03-07 Ski");

		assertEquals(MoveService.nameTaken("2021-03-07 Ski"), result.getOutcomes().get(0).getMessage());
		assertTrue("Nothing may have moved.", _base.resolve("Inbox/2021-03-07 Ski/a.jpg").toFile().exists());
	}

	// --- Applying the rule to what is already there. ---

	public void testApplyOnceReorganisesAFlatFolder() throws Exception {
		rule(Placement.BY_YEAR);
		album("2018-03-04 Ski");
		album("2019 Summer");
		album("Notes");
		album("2020/Already there");

		MoveResult result = place("/");

		assertEquals(Arrays.asList("2018-03-04 Ski", "2019 Summer", "Notes"), names(result));
		assertEquals(Arrays.asList("2018/2018-03-04 Ski", "2019/2019 Summer", ""), newNames(result));
		assertEquals("A folder nothing dates is kept, and says so.", MoveService.KEPT_NO_DATE,
			result.getOutcomes().get(2).getMessage());

		assertTrue(_base.resolve("2018/2018-03-04 Ski/a.jpg").toFile().exists());
		assertTrue(_base.resolve("2019/2019 Summer/a.jpg").toFile().exists());
		assertTrue("The year folder that was already there is untouched.",
			_base.resolve("2020/Already there/a.jpg").toFile().exists());
		assertTrue("What was kept is still where it was.", _base.resolve("Notes/a.jpg").toFile().exists());
	}

	public void testApplyOnceIsIdempotent() throws Exception {
		rule(Placement.BY_YEAR);
		album("2018-03-04 Ski");

		place("/");
		MoveResult again = place("/");

		assertEquals("Nothing but year folders is left, and those are passed over.",
			Collections.emptyList(), names(again));
		assertTrue(_base.resolve("2018/2018-03-04 Ski/a.jpg").toFile().exists());
	}

	public void testApplyOnceRefusesATakenName() throws Exception {
		rule(Placement.BY_YEAR);
		album("2018-03-04 Ski");
		album("2018/2018-03-04 Ski");

		MoveResult result = place("/");

		assertEquals(Collections.singletonList("2018-03-04 Ski"), names(result));
		assertEquals(MoveService.nameTaken("2018-03-04 Ski"), result.getOutcomes().get(0).getMessage());
		assertTrue("Nothing is overwritten.", _base.resolve("2018-03-04 Ski/a.jpg").toFile().exists());
	}

	public void testApplyOnceOnAFolderWithoutARule() throws Exception {
		album("2018-03-04 Ski");
		album("Notes");

		MoveResult result = place("/");

		assertEquals(Arrays.asList("2018-03-04 Ski", "Notes"), names(result));
		assertEquals("A folder that files nothing says so, for every child; it is no silent no-op.",
			Arrays.asList(MoveService.NO_RULE, MoveService.NO_RULE), messages(result));
		assertTrue(_base.resolve("2018-03-04 Ski/a.jpg").toFile().exists());
	}

	public void testApplyOnceInASubFolder() throws Exception {
		Files.createDirectories(_base.resolve("Archive"));
		rule("Archive", Placement.BY_YEAR_MONTH);
		album("Archive/2018-03-04 Ski");

		MoveResult result = place("/Archive/");

		assertEquals(Collections.singletonList("2018/2018-03/2018-03-04 Ski"), newNames(result));
		assertTrue(_base.resolve("Archive/2018/2018-03/2018-03-04 Ski/a.jpg").toFile().exists());
	}

	public void testAnAnonymousApplyOnceIsRefused() throws Exception {
		rule(Placement.BY_YEAR);
		album("2018-03-04 Ski");

		FakeResponse response = placeResponse("/", null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals("Bearer", response.header("WWW-Authenticate"));
		assertFalse("Nothing may have moved.", _base.resolve("2018").toFile().exists());
		assertTrue(_base.resolve("2018-03-04 Ski/a.jpg").toFile().exists());
	}

	public void testApplyOnceOnAFolderThatIsNotThere() throws Exception {
		FakeResponse response = placeResponse("/nowhere/", token());

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(MoveService.SOURCE_MISSING, errorMessage(response));
	}

	// --- Helpers. ---

	/** Gives the base folder the given placement rule. */
	private void rule(Placement placement) throws IOException {
		rule("", placement);
	}

	/** Gives the given folder the given placement rule, as its folder editor would. */
	private void rule(String folder, Placement placement) throws IOException {
		Path path = folder.isEmpty() ? _base : _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"),
			("[\"ListingInfo\",{\"title\":\"Folder\",\"placement\":\"" + placement.protocolName() + "\"}]")
				.getBytes(StandardCharsets.UTF_8));
	}

	/** An album folder of the given path with one tiny JPEG in it. */
	private void album(String path) throws IOException {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg",
			folder.resolve("a.jpg").toFile());
	}

	private CreateResult create(String pathInfo, String body) throws Exception {
		FakeResponse response = createResponse(pathInfo, body);
		assertEquals("The creation failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return CreateResult.readCreateResult(reader(response.body()));
	}

	private FakeResponse createResponse(String pathInfo, String body) throws Exception {
		String token = token();
		FakeResponse response = new FakeResponse();
		servlet().doPut(request(pathInfo, "application/json", body, token, Collections.emptyMap()),
			response.response());
		return response;
	}

	private MoveResult move(String pathInfo, String target, String... names) throws Exception {
		String token = token();
		String body = "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse response = new FakeResponse();
		servlet().doPost(request(pathInfo, "application/json", body, token, parameters), response.response());
		assertEquals("The move failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private MoveResult place(String pathInfo) throws Exception {
		FakeResponse response = placeResponse(pathInfo, token());
		assertEquals("Applying the rule failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private FakeResponse placeResponse(String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "place");
		FakeResponse response = new FakeResponse();
		servlet().doPost(request(pathInfo, "application/json", "", token, parameters), response.response());
		return response;
	}

	private String json(String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		servlet().doGet(request(pathInfo, null, "", null, parameters), response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return response.body();
	}

	private static List<String> names(MoveResult result) {
		return result.getOutcomes().stream().map(MoveOutcome::getName).collect(Collectors.toList());
	}

	private static List<String> newNames(MoveResult result) {
		return result.getOutcomes().stream().map(MoveOutcome::getNewName).collect(Collectors.toList());
	}

	private static List<String> messages(MoveResult result) {
		return result.getOutcomes().stream().map(MoveOutcome::getMessage).collect(Collectors.toList());
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static HttpServletRequest request(String pathInfo, String contentType, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	/** The token of the signed-in library owner; the sign-in happens before the servlet exists. */
	private String token() throws Exception {
		if (_token == null) {
			PairResponse response = new AuthService(AuthMode.WRITES, SECRET, _base).pair(PairRequest.create()
				.setSecret(SECRET).setDeviceName("Phone").setUserName("haui"));
			_token = response.getToken();
		}
		return _token;
	}

	private ImageServlet servlet() throws IOException {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
			_servlets.add(_servlet);
		}
		return _servlet;
	}
}
