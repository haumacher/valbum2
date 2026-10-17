/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.cache.AnalysisRecord;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * The number of photographs a listing tile says its folder holds, see issue #286: every album
 * counts, what the sidecar does not mention included, and a caller is told only what they may see.
 */
@SuppressWarnings("javadoc")
public class TestFolderImageCount extends TestCase {

	/** Three public photographs and a private one. */
	private static final String MIXED_JSON = "[\"AlbumInfo\",{\"title\":\"Mixed\",\"parts\":["
		+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3}],"
		+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3}],"
		+ "[\"ImageGroup\",{\"representative\":0,\"images\":["
		+ "{\"name\":\"c.jpg\",\"width\":4,\"height\":3},"
		+ "{\"name\":\"p.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]}]"
		+ "]}]";

	/** Mentions one of the three photographs of its folder. */
	private static final String PARTIAL_JSON = "[\"AlbumInfo\",{\"title\":\"Partial\",\"parts\":["
		+ "[\"ImagePart\",{\"name\":\"x.jpg\",\"width\":4,\"height\":3,\"rating\":2}]"
		+ "]}]";

	/** Rated photographs: one rejected, one plain, two good ones. */
	private static final String RATED_JSON = "[\"AlbumInfo\",{\"title\":\"Rated\",\"parts\":["
		+ "[\"ImagePart\",{\"name\":\"r0.jpg\",\"width\":4,\"height\":3,\"rating\":-1}],"
		+ "[\"ImagePart\",{\"name\":\"r1.jpg\",\"width\":4,\"height\":3}],"
		+ "[\"ImagePart\",{\"name\":\"r2.jpg\",\"width\":4,\"height\":3,\"rating\":1}],"
		+ "[\"ImagePart\",{\"name\":\"r3.jpg\",\"width\":4,\"height\":3,\"rating\":2}]"
		+ "]}]";

	private Path _base;

	private ImageServlet _servlet;

	/** The device token of the library's owner. */
	private String _owner;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-folder-count");
		album("Mixed", MIXED_JSON, "a.jpg", "b.jpg", "c.jpg", "p.jpg");
		album("Partial", PARTIAL_JSON, "x.jpg", "y.jpg", "z.jpg");
		album("Plain", null, "q1.jpg", "q2.jpg");
		album("Rated", RATED_JSON, "r0.jpg", "r1.jpg", "r2.jpg", "r3.jpg");
		album("Years/2020", null, "old.jpg");
		Files.write(_base.resolve("Years/index.json"),
			"[\"ListingInfo\",{\"title\":\"Years\"}]".getBytes(StandardCharsets.UTF_8));
		// Before the servlet is made: it reads the users once.
		_owner = Codes.signInAdmin(new AuthService(AuthMode.WRITES, _base), "Phone", "haui").getToken();
		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, _base));
		for (String folder : new String[] { "Mixed", "Partial", "Plain", "Rated", "Years/2020", "Years" }) {
			// What the background does for every folder: the record a listing reads.
			AnalysisRecord.update(_base.resolve(folder).toFile(), _servlet.cache().analyst(), true);
		}
	}

	@Override
	protected void tearDown() throws Exception {
		_servlet.destroy();
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testTheOwnerIsToldEveryPhotographAndAGuestThePublicOnes() throws Exception {
		assertEquals(4, entry(_owner, "Mixed").getImageCount());
		assertEquals("A guest never learns of the private photograph.", 3, entry(null, "Mixed").getImageCount());
	}

	public void testAPhotographTheSidecarDoesNotMentionCounts() throws Exception {
		assertEquals(3, entry(_owner, "Partial").getImageCount());
		assertEquals("Never edited, so public.", 3, entry(null, "Partial").getImageCount());
		assertEquals("Without any sidecar.", 2, entry(null, "Plain").getImageCount());
	}

	public void testAFolderOfFoldersCountsNothing() throws Exception {
		FolderInfo years = entry(_owner, "Years");
		assertEquals(FolderKind.FOLDER, years.getKind());
		assertEquals(0, years.getImageCount());
		assertEquals(0, entry(null, "Years").getImageCount());
	}

	public void testARatingFloorCountsWhatItShows() throws Exception {
		PrivacyFilter filter = new PrivacyFilter(_servlet.cache());
		PathInfo rated = new PathInfo(_base, Path.of("Rated"));
		FolderInfo tile = ResourceCache.folderInfo(rated.toFile());
		assertEquals(4, tile.getImageCount());

		assertEquals(4, filter.filterEntry(tile, rated, Privacy.PRIVATE, Ratings.MIN).getImageCount());
		assertEquals("The rejected one is gone.", 3, filter.filterEntry(tile, rated, Privacy.PUBLIC, 0).getImageCount());
		assertEquals(2, filter.filterEntry(tile, rated, Privacy.PUBLIC, 1).getImageCount());
		assertEquals(1, filter.filterEntry(tile, rated, Privacy.PRIVATE, 2).getImageCount());
		assertEquals("A label nothing carries shows nothing.", 0,
			filter.filterEntry(tile, rated, Privacy.PRIVATE, Ratings.MIN, "Grandma").getImageCount());
		assertEquals("The cached tile is never touched.", 4, tile.getImageCount());
	}

	/**
	 * Probe: whoever asks, a tile's count is the number of photographs that caller is shown on
	 * opening the album — in a nested listing too.
	 */
	public void testTheTileCountIsWhatOpeningTheAlbumShows() throws Exception {
		Files.write(_base.resolve("Years/2020/index.json"),
			("[\"AlbumInfo\",{\"title\":\"2020\",\"parts\":["
				+ "[\"ImagePart\",{\"name\":\"old.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]]}]")
				.getBytes(StandardCharsets.UTF_8));
		for (String token : new String[] { _owner, null }) {
			for (String name : new String[] { "Mixed", "Partial", "Plain", "Rated" }) {
				assertEquals(name + " as " + (token == null ? "guest" : "owner"), shown(token, name + "/"),
					entry(token, name).getImageCount());
			}
		}
		assertEquals(1, nested(_owner).getImageCount());
		assertEquals("The private one is not counted for a guest down the tree either.", 0,
			nested(null).getImageCount());
	}

	private FolderInfo nested(String token) throws Exception {
		Resource resource = get(token, "/Years/");
		for (FolderInfo folder : ((ListingInfo) resource).getFolders()) {
			if (folder.getName().equals("2020")) {
				return folder;
			}
		}
		fail("No 2020.");
		return null;
	}

	private int shown(String token, String path) throws Exception {
		Resource resource = get(token, "/" + path);
		assertTrue(resource instanceof de.haumacher.imageServer.shared.model.AlbumInfo);
		int result = 0;
		for (de.haumacher.imageServer.shared.model.AlbumPart part : ((de.haumacher.imageServer.shared.model.AlbumInfo) resource).getParts()) {
			if (part instanceof de.haumacher.imageServer.shared.model.ImagePart) {
				result++;
			} else if (part instanceof de.haumacher.imageServer.shared.model.ImageGroup) {
				result += ((de.haumacher.imageServer.shared.model.ImageGroup) part).getImages().size();
			}
		}
		return result;
	}

	private Resource get(String token, String path) throws Exception {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(path, null, new byte[0], headers, parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
	}

	// --- Helpers. ---

	private void album(String name, String index, String... images) throws Exception {
		Path folder = _base.resolve(name);
		Files.createDirectories(folder);
		if (index != null) {
			Files.write(folder.resolve("index.json"), index.getBytes(StandardCharsets.UTF_8));
		}
		int seed = 0;
		for (String image : images) {
			BufferedImage picture = new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR);
			// Distinct pixels, so that no two files are the same photograph.
			picture.setRGB(0, 0, (name.hashCode() * 31 + seed++) & 0xFFFFFF);
			ImageIO.write(picture, "jpg", folder.resolve(image).toFile());
		}
	}

	/** The tile of the given folder in the root listing, as the caller of the given token sees it. */
	private FolderInfo entry(String token, String name) throws Exception {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request("/", null, new byte[0], headers, parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		Resource resource =
			Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		assertTrue(response.body(), resource instanceof ListingInfo);
		for (FolderInfo folder : ((ListingInfo) resource).getFolders()) {
			if (folder.getName().equals(name)) {
				return folder;
			}
		}
		fail("No folder '" + name + "' in: " + response.body());
		return null;
	}
}
