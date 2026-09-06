/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ListingInfo;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the date of an album (issue #48): what it is derived from, and that the derived one
 * is never written down.
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder with the request and response fakes
 * of {@link TestImageServletPut}, exactly as {@link TestPrivacy} does. The albums are described by
 * hand-written sidecars, so that a test says exactly what the album knows — an image whose name the
 * sidecar already carries is never analysed, which is how an image date is stated here without
 * writing EXIF data into a JPEG.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestAlbumDate extends TestCase {

	private static final String SECRET = "let-me-in";

	/** 2020-05-01, as the folder name <code>2020-05-01 Trip</code> states it. */
	private static final long MAY_FIRST = AlbumDate.ofFolderName("2020-05-01").millis();

	/** 1999-12-31, a date no folder name of this test carries. */
	private static final long NEW_YEARS_EVE = AlbumDate.ofFolderName("1999-12-31").millis();

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-date-test");
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

	// --- What a folder name says. ---

	public void testTheDateInAFolderName() {
		assertEquals(day(2020, 5, 1), AlbumDate.ofFolderName("2020-05-01 Trip").millis());
		assertEquals("An underscore separates as well.", day(2020, 5, 1),
			AlbumDate.ofFolderName("2020_05_01 Trip").millis());
		assertEquals("A dot separates as well.", day(2020, 5, 1),
			AlbumDate.ofFolderName("2020.05.01 Trip").millis());
		assertEquals("A blank separates as well.", day(2020, 5, 1),
			AlbumDate.ofFolderName("2020 05 01 Trip").millis());
		assertEquals("A year and a month are the first of that month.", day(2020, 5, 1),
			AlbumDate.ofFolderName("2020-05 Trip").millis());
		assertEquals("A bare year is January 1st.", day(2020, 1, 1),
			AlbumDate.ofFolderName("2020 Trip").millis());
		assertEquals("The date may be the whole name.", day(2020, 1, 1),
			AlbumDate.ofFolderName("2020").millis());
	}

	public void testWhatIsNoDateInAFolderName() {
		assertFalse(AlbumDate.ofFolderName("Trip").isSet());
		assertFalse("The date must lead the name.", AlbumDate.ofFolderName("Trip 2020").isSet());
		assertFalse("A longer number is no year.", AlbumDate.ofFolderName("20200501 Trip").isSet());
		assertFalse(AlbumDate.ofFolderName("").isSet());
		assertEquals("A month that is none leaves the year.", day(2020, 1, 1),
			AlbumDate.ofFolderName("2020-13 Trip").millis());
		assertEquals("A day that is none leaves the month.", day(2020, 2, 1),
			AlbumDate.ofFolderName("2020-02-31 Trip").millis());
	}

	public void testAYearAloneStatesNoMonth() {
		assertFalse("A folder named after a year says nothing about a month.",
			AlbumDate.ofFolderName("2020 Trip").isMonthKnown());
		assertTrue(AlbumDate.ofFolderName("2020-05 Trip").isMonthKnown());
		assertTrue("A date somebody picked is a day, month included.", AlbumDate.ofMillis(MAY_FIRST).isMonthKnown());
	}

	// --- What the server answers for an album. ---

	public void testTheExplicitDateWins() throws Exception {
		album("2020-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"date\":" + NEW_YEARS_EVE + ",\"parts\":["
			+ part("a.jpg", 0) + "]}]", "a.jpg");

		AlbumInfo album = album(get("/2020-05-01 Trip/"));
		assertEquals("The date the author set beats the folder name.", NEW_YEARS_EVE, album.getEffectiveDate());
		assertEquals(NEW_YEARS_EVE, album.getDate());
	}

	public void testTheFolderNameBeatsTheImages() throws Exception {
		album("2020-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ part("a.jpg", NEW_YEARS_EVE) + "]}]", "a.jpg");

		AlbumInfo album = album(get("/2020-05-01 Trip/"));
		assertEquals(MAY_FIRST, album.getEffectiveDate());
		assertEquals("Nothing was set explicitly.", 0L, album.getDate());
	}

	public void testTheEarliestImageDateIsTheLastResort() throws Exception {
		album("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ part("a.jpg", MAY_FIRST) + "," + part("b.jpg", NEW_YEARS_EVE) + "," + part("c.jpg", 0)
			+ "]}]", "a.jpg", "b.jpg", "c.jpg");

		assertEquals("The earliest image is when the album happened.", NEW_YEARS_EVE,
			album(get("/Trip/")).getEffectiveDate());
	}

	public void testAnAlbumNothingSaysADateAboutHasNone() throws Exception {
		album("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]", "a.jpg");

		assertEquals(0L, album(get("/Trip/")).getEffectiveDate());
	}

	// --- The derived date is answered, never kept. ---

	public void testAPutStripsTheDerivedDate() throws Exception {
		album("2020-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]",
			"a.jpg");

		// What a client sends back after reading: the derived date is in the album it holds.
		FakeResponse response = put("/2020-05-01 Trip/", "[\"AlbumInfo\",{\"title\":\"Trip\",\"effectiveDate\":"
			+ MAY_FIRST + ",\"parts\":[" + part("a.jpg", 0) + "]}]");
		assertEquals(HttpServletResponse.SC_OK, response.status());

		AlbumInfo stored = (AlbumInfo) sidecar("2020-05-01 Trip");
		assertEquals("A derived date is never frozen into a sidecar.", 0L, stored.getEffectiveDate());
		assertEquals(0L, stored.getDate());
		String text = raw("2020-05-01 Trip");
		assertTrue("The field is written unset, never with a value: " + text,
			text.contains("\"effectiveDate\":0"));

		assertEquals("It is derived again on the way out.", MAY_FIRST,
			album(get("/2020-05-01 Trip/")).getEffectiveDate());
	}

	public void testAnExplicitDateSurvivesAPut() throws Exception {
		album("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]", "a.jpg");

		put("/Trip/", "[\"AlbumInfo\",{\"title\":\"Trip\",\"date\":" + NEW_YEARS_EVE + ",\"parts\":["
			+ part("a.jpg", 0) + "]}]");

		assertEquals("The date the author set is what a sidecar stores.", NEW_YEARS_EVE,
			((AlbumInfo) sidecar("Trip")).getDate());
		assertEquals(NEW_YEARS_EVE, album(get("/Trip/")).getEffectiveDate());
	}

	public void testASidecarThatCarriesADerivedDateIsToleratedAndOverwritten() throws Exception {
		// A sidecar from a build that stored what it should not have; it is read, not complained
		// about, and answered with the date that is derived today.
		album("2020-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"effectiveDate\":" + NEW_YEARS_EVE
			+ ",\"parts\":[" + part("a.jpg", 0) + "]}]", "a.jpg");

		assertEquals(MAY_FIRST, album(get("/2020-05-01 Trip/")).getEffectiveDate());
	}

	// --- What a listing knows about a folder. ---

	public void testAListingReadsTheDateFromTheSidecarAndTheName() throws Exception {
		album("2020-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]",
			"a.jpg");
		album("Dated", "[\"AlbumInfo\",{\"title\":\"Dated\",\"date\":" + NEW_YEARS_EVE + ",\"parts\":["
			+ part("b.jpg", 0) + "]}]", "b.jpg");
		album("Photos", "[\"AlbumInfo\",{\"title\":\"Photos\",\"parts\":[" + part("c.jpg", MAY_FIRST) + "]}]",
			"c.jpg");

		ListingInfo listing = listing(get("/"));
		assertEquals("The date in the folder name.", MAY_FIRST, folder(listing, "2020-05-01 Trip").getEffectiveDate());
		assertEquals("The date in the sidecar.", NEW_YEARS_EVE, folder(listing, "Dated").getEffectiveDate());
		assertEquals("A listing never opens the images of the albums it shows.", 0L,
			folder(listing, "Photos").getEffectiveDate());
		assertEquals("The album itself knows more than its tile does.", MAY_FIRST,
			album(get("/Photos/")).getEffectiveDate());
	}

	// --- Nothing of this is lost on the way through the privacy filter. ---

	public void testAFilteredAnswerKeepsTheDatesAndTheRule() throws Exception {
		Files.write(_base.resolve("index.json"),
			("[\"ListingInfo\",{\"title\":\"Root\",\"placement\":\"BY_YEAR\"}]").getBytes(StandardCharsets.UTF_8));
		album("2020-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"date\":" + NEW_YEARS_EVE + ","
			+ "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0,\"ty\":0.0},\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"privacy\":2}],"
			+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3}]]}]", "a.jpg", "b.jpg");

		// An anonymous caller: the album is copied without its private image, the listing is copied
		// with another cover. Both copies must carry everything the originals said.
		AlbumInfo album = album(get("/2020-05-01 Trip/"));
		assertEquals(Collections.singletonList("b.jpg"), imageNames(album));
		assertEquals("The explicit date survives the filter.", NEW_YEARS_EVE, album.getDate());
		assertEquals("The derived date survives the filter.", NEW_YEARS_EVE, album.getEffectiveDate());

		ListingInfo listing = listing(get("/"));
		assertEquals("The folder's rule survives the filter.", Placement.BY_YEAR, listing.getPlacement());
		assertEquals("The tile keeps its date, or the listing would change its order.", NEW_YEARS_EVE,
			folder(listing, "2020-05-01 Trip").getEffectiveDate());
		assertEquals("The cover was really replaced, so this is a copy.", "b.jpg",
			folder(listing, "2020-05-01 Trip").getIndexPicture().getImage());
	}

	public void testTheOwnerSeesTheSameDates() throws Exception {
		album("2020-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]]}]", "a.jpg");

		assertEquals(MAY_FIRST, album(get("/2020-05-01 Trip/", signIn())).getEffectiveDate());
	}

	// --- Helpers. ---

	private static long day(int year, int month, int day) {
		return AlbumDate.ofFolderName(String.format("%04d-%02d-%02d", Integer.valueOf(year), Integer.valueOf(month),
			Integer.valueOf(day))).millis();
	}

	/** An <code>ImagePart</code> for a hand-written sidecar, with the date it claims. */
	private static String part(String name, long date) {
		return "[\"ImagePart\",{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"width\":4,\"height\":3,\"date\":"
			+ date + "}]";
	}

	/** An album folder with the given sidecar and a tiny JPEG for each of the given names. */
	private void album(String name, String index, String... images) throws IOException {
		Path folder = _base.resolve(name);
		Files.createDirectories(folder);
		Files.write(folder.resolve("index.json"), index.getBytes(StandardCharsets.UTF_8));
		for (String image : images) {
			ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", folder.resolve(image).toFile());
		}
	}

	/** The bytes of the given folder's sidecar. */
	private String raw(String folder) throws IOException {
		return new String(Files.readAllBytes(_base.resolve(folder).resolve("index.json")), StandardCharsets.UTF_8);
	}

	/** The sidecar of the given folder, parsed. */
	private FolderResource sidecar(String folder) throws IOException {
		File index = _base.resolve(folder).resolve("index.json").toFile();
		assertTrue("Expected a sidecar at " + index, index.exists());
		return FolderResource.readFolderResource(
			reader(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8)));
	}

	private static List<String> imageNames(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		album.getParts().forEach(part -> {
			if (part instanceof de.haumacher.imageServer.shared.model.ImagePart) {
				result.add(((de.haumacher.imageServer.shared.model.ImagePart) part).getName());
			}
		});
		return result;
	}

	private static FolderInfo folder(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (folder.getName().equals(name)) {
				return folder;
			}
		}
		fail("No folder '" + name + "' in the listing.");
		return null;
	}

	private static AlbumInfo album(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(body(response)));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static ListingInfo listing(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(body(response)));
		assertTrue("Expected a listing, got: " + resource, resource instanceof ListingInfo);
		return (ListingInfo) resource;
	}

	private static String body(FakeResponse response) {
		assertEquals("Expected a successful request, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		return response.body();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private FakeResponse get(String pathInfo) throws Exception {
		return get(pathInfo, null);
	}

	private FakeResponse get(String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		servlet().doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private FakeResponse put(String pathInfo, String body) throws Exception {
		// Signed in before the servlet exists: its authentication reads the user store once.
		String token = signIn();
		FakeResponse response = new FakeResponse();
		servlet().doPut(request(pathInfo, "application/json", body, token, Collections.emptyMap()),
			response.response());
		return response;
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

	/** Signs the library owner in and answers the token of their device. */
	private String signIn() throws Exception {
		PairResponse response = new AuthService(AuthMode.WRITES, SECRET, _base).pair(PairRequest.create()
			.setSecret(SECRET).setDeviceName("Phone").setUserName("haui"));
		return response.getToken();
	}

	private ImageServlet servlet() throws IOException {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
			_servlets.add(_servlet);
		}
		return _servlet;
	}
}
