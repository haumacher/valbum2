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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for what a {@link FolderInfo} says it is (issue #133).
 *
 * <p>
 * A folder of folders has no date; only an album has one. The listing entry therefore names its
 * {@link FolderInfo#getKind() kind}, and the app shows the date of an album only — while
 * {@link FolderInfo#getEffectiveDate()} stays what it always was, the key the listing is sorted by,
 * so a folder named <code>2026</code> keeps sorting with the year it names.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFolderKind extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-folder-kind-test");
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

	/**
	 * The user's own report: the folder <code>2026</code> was shown as "2026 - Jan 1 2026".
	 *
	 * <p>
	 * It is a folder, the album beside it is an album, and both keep the sort key and the place in
	 * the listing they had before this change.
	 * </p>
	 */
	public void testAYearFolderIsAFolderAndTheAlbumBesideItIsAnAlbum() throws Exception {
		// A folder of folders: nothing but a subfolder that holds an album.
		album("2026/2026-07-04 Lake");
		album("2026-05-01 Trip");

		ListingInfo listing = listing("/");

		assertEquals("The order of issue #48 is untouched: the newest first.",
			Arrays.asList("2026-05-01 Trip", "2026"), names(listing));
		assertEquals(Arrays.asList(FolderKind.ALBUM, FolderKind.FOLDER), kinds(listing));

		assertEquals("The sort key of the year folder is January 1st, exactly as before.",
			AlbumDate.ofFolderName("2026").millis(), byName(listing, "2026").getEffectiveDate());
		assertEquals(AlbumDate.ofFolderName("2026-05-01").millis(),
			byName(listing, "2026-05-01 Trip").getEffectiveDate());
	}

	/** A folder without a sidecar that holds images is an album -- the rule the server opens it by. */
	public void testASidecarLessFolderHoldingImagesIsAnAlbum() throws Exception {
		album("Inbox");

		assertEquals(FolderKind.ALBUM, byName(listing("/"), "Inbox").getKind());
	}

	/** A folder without a sidecar that holds nothing at all is a folder of folders. */
	public void testAnEmptySidecarLessFolderIsAFolder() throws Exception {
		Files.createDirectories(_base.resolve("Empty"));

		assertEquals(FolderKind.FOLDER, byName(listing("/"), "Empty").getKind());
	}

	/** The folder's own sidecar decides, whatever lies beside it. */
	public void testTheSidecarSaysWhatTheFolderIs() throws Exception {
		// A listing sidecar, and an image in the folder all the same: what the owner wrote wins.
		album("Stated");
		sidecar("Stated", "[\"ListingInfo\",{\"title\":\"Stated\"}]");
		// An album sidecar in a folder holding no image at all.
		Files.createDirectories(_base.resolve("Planned"));
		sidecar("Planned", "[\"AlbumInfo\",{\"title\":\"Planned\",\"parts\":[]}]");

		ListingInfo listing = listing("/");
		assertEquals(FolderKind.FOLDER, byName(listing, "Stated").getKind());
		assertEquals(FolderKind.ALBUM, byName(listing, "Planned").getKind());
	}

	/**
	 * The kind is derived, so a round trip through a stored sidecar never freezes it.
	 *
	 * <p>
	 * What is written carries {@link FolderKind#ALBUM}, the neutral value of this field -- the one
	 * a reader of a file written before it existed sees, and the one that states nothing. A folder
	 * that happens to be a folder of folders today is never written down as one, because tomorrow
	 * it may hold photographs.
	 * </p>
	 */
	public void testTheKindIsNeverWrittenIntoASidecar() throws Exception {
		album("2026/2026-07-04 Lake");
		album("2026-05-01 Trip");

		ListingInfo first = listing("/");
		List<FolderKind> before = kinds(first);

		// The listing as it was read, written back as the app writes it.
		FakeResponse stored = put("/", writeResource(first));
		assertEquals(HttpServletResponse.SC_OK, stored.status());

		String index = new String(Files.readAllBytes(_base.resolve("index.json")), StandardCharsets.UTF_8);
		assertFalse("A derived kind must never be frozen into a sidecar, found: " + index,
			index.contains("\"kind\":\"FOLDER\""));
		assertFalse("A derived date must never be frozen into a sidecar either, found: " + index,
			index.contains("\"effectiveDate\":" + AlbumDate.ofFolderName("2026").millis()));

		ListingInfo again = listing("/");
		assertEquals("Read, written and read again says the same thing.", names(first), names(again));
		assertEquals(before, kinds(again));
		assertEquals(Arrays.asList(FolderKind.ALBUM, FolderKind.FOLDER), kinds(again));
	}

	// --- Helpers. ---

	private static List<String> names(ListingInfo listing) {
		return listing.getFolders().stream().map(FolderInfo::getName).collect(Collectors.toList());
	}

	private static List<FolderKind> kinds(ListingInfo listing) {
		return listing.getFolders().stream().map(FolderInfo::getKind).collect(Collectors.toList());
	}

	private static FolderInfo byName(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (name.equals(folder.getName())) {
				return folder;
			}
		}
		fail("No folder '" + name + "' in " + names(listing));
		return null;
	}

	private static String writeResource(Resource resource) throws IOException {
		java.io.StringWriter out = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json =
			new de.haumacher.msgbuf.json.JsonWriter(new de.haumacher.msgbuf.server.io.WriterAdapter(out))) {
			resource.writeTo(json);
		}
		return out.toString();
	}

	/** An album folder of the given (possibly nested) name with one tiny JPEG in it. */
	private void album(String name) throws IOException {
		Path folder = _base.resolve(name);
		Files.createDirectories(folder);
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg",
			folder.resolve("a.jpg").toFile());
	}

	private void sidecar(String name, String json) throws IOException {
		Files.write(_base.resolve(name).resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	private FakeResponse put(String pathInfo, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(TestImageServletPut.request(pathInfo, "application/json",
			body.getBytes(StandardCharsets.UTF_8)), response.response());
		return response;
	}

	private ListingInfo listing(String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
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
