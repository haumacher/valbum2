/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
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
 * Review probes of {@link FolderInfo#getKind()} (issue #133), composed with what the change did not
 * look at: a listing below the root, and the privacy filter of issue #46 rebuilding the entries for
 * a caller who may not see an album's only picture.
 */
@SuppressWarnings("javadoc")
public class TestFolderKindProbe extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-folder-kind-probe");
		_servlet = new ImageServlet(_base.toFile());
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	/** The kind is answered on every level, not only at the root. */
	public void testANestedListingNamesItsKindsToo() throws Exception {
		album("2026/2026-07-04 Lake");
		Files.createDirectories(_base.resolve("2026/Trips/2026-08-01 Hike"));
		album("2026/Trips/2026-08-01 Hike");

		ListingInfo year = listing("/2026/");
		assertEquals(FolderKind.ALBUM, byName(year, "2026-07-04 Lake").getKind());
		assertEquals(FolderKind.FOLDER, byName(year, "Trips").getKind());
		assertEquals("The folder of folders keeps no date to show, only its sort key: none for 'Trips'.",
			0L, byName(year, "Trips").getEffectiveDate());
	}

	/**
	 * A public caller is answered a rebuilt entry for an album whose index picture is private; the
	 * kind must ride along, else the app would treat the album as an undated folder.
	 */
	public void testTheKindSurvivesThePrivacyFilter() throws Exception {
		album("2026-05-01 Trip");
		sidecar("2026-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]],"
			+ "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0}}]");
		album("2026/2026-07-04 Lake");

		ListingInfo asPublic = listing("/", "public");
		FolderInfo trip = byName(asPublic, "2026-05-01 Trip");
		assertEquals(FolderKind.ALBUM, trip.getKind());
		assertNull("The private picture is not shown to a public caller.", trip.getIndexPicture());
		assertEquals(AlbumDate.ofFolderName("2026-05-01").millis(), trip.getEffectiveDate());
		assertEquals(FolderKind.FOLDER, byName(asPublic, "2026").getKind());

		ListingInfo asMember = listing("/", null);
		assertEquals(FolderKind.ALBUM, byName(asMember, "2026-05-01 Trip").getKind());
		assertEquals(FolderKind.FOLDER, byName(asMember, "2026").getKind());
	}

	// --- Helpers. ---

	private static FolderInfo byName(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (name.equals(folder.getName())) {
				return folder;
			}
		}
		fail("No folder '" + name + "' in " + listing.getFolders());
		return null;
	}

	private void album(String name) throws IOException {
		Path folder = _base.resolve(name);
		Files.createDirectories(folder);
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg",
			folder.resolve("a.jpg").toFile());
	}

	private void sidecar(String name, String json) throws IOException {
		Files.write(_base.resolve(name).resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	private ListingInfo listing(String pathInfo) throws Exception {
		return listing(pathInfo, null);
	}

	private ListingInfo listing(String pathInfo, String viewAs) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		if (viewAs != null) {
			parameters.put("viewAs", viewAs);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], new HashMap<>(), parameters),
			response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		Resource resource = Resource.readResource(
			new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		assertTrue("Expected a listing, got: " + resource, resource instanceof ListingInfo);
		return (ListingInfo) resource;
	}
}
