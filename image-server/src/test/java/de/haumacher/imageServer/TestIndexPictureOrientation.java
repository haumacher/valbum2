/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the orientation a crop was made in, see
 * {@link ThumbnailInfo#getOrientation()} and issue #115.
 *
 * <p>
 * The crop of an index picture is measured in the frame the image is <em>displayed</em> in; which
 * frame that is has to travel with the crop, or the listing tile shows a rotated cover unrotated.
 * The field is stored, never derived: a crop written before it existed was made in the un-oriented
 * frame, which is what {@link Orientation#IDENTITY} says, so every old sidecar keeps its meaning.
 * </p>
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder with the request and response fakes
 * of {@link TestImageServletPut}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestIndexPictureOrientation extends TestCase {

	/** A crop of a landscape file an author turned a quarter to the left. */
	private static final String TURNED_COVER =
		"\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.3333333333333333,\"tx\":0.0,\"ty\":37.5,"
			+ "\"orientation\":\"ROT_L\"},";

	/** The same crop as a sidecar written before the field existed. */
	private static final String OLD_COVER =
		"\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.3333333333333333,\"tx\":0.0,\"ty\":37.5},";

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-index-orientation-test");
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

	public void testTheStoredOrientationIsAnsweredInTheAlbumAndInTheListing() throws Exception {
		album("A", TURNED_COVER, "ROT_L");

		assertEquals("The album answers the frame its crop was made in.",
			Orientation.ROT_L, cover(album("A")).getOrientation());
		assertEquals("The listing above answers the same frame; it is a copy of the album's crop.",
			Orientation.ROT_L, cover(listing(), "A").getOrientation());
	}

	public void testASidecarWithoutTheFieldReadsAsIdentity() throws Exception {
		album("A", OLD_COVER, "ROT_L");

		assertEquals("A crop written before the field was made in the un-oriented frame.",
			Orientation.IDENTITY, cover(album("A")).getOrientation());
		assertEquals(Orientation.IDENTITY, cover(listing(), "A").getOrientation());
		assertEquals("The crop itself is untouched.", 37.5, cover(album("A")).getTy(), 1e-9);
	}

	public void testTheOrientationSurvivesReadWriteRead() throws Exception {
		album("A", TURNED_COVER, "ROT_L");

		// Read, write back unchanged, read again: nothing is derived here and nothing is cleared.
		AlbumInfo read = album("A");
		FakeResponse stored = put("/A/", json(read));
		assertEquals(HttpServletResponse.SC_OK, stored.status());

		FolderResource onDisk = parse(contents("A"));
		assertEquals("The field reaches the disk; nothing clears it before a write.",
			Orientation.ROT_L, cover((AlbumInfo) onDisk).getOrientation());
		ThumbnailInfo again = cover(album("A"));
		assertEquals(Orientation.ROT_L, again.getOrientation());
		assertEquals(1.3333333333333333, again.getScale(), 1e-9);
		assertEquals(37.5, again.getTy(), 1e-9);
	}

	public void testTheSidecarLessFallbackFramesInTheFilesOwnFrame() throws Exception {
		// No sidecar at all: nobody ever stored a rotation, so the frame of the crop the server
		// computes here is the one its own rendition has.
		image("A/a.jpg", 8, 6);

		ThumbnailInfo fallback = cover(listing(), "A");
		assertEquals("a.jpg", fallback.getImage());
		assertEquals(Orientation.IDENTITY, fallback.getOrientation());
	}

	/**
	 * The cover the server computes for an image itself — the one a listing builds for a folder
	 * without a sidecar, the one the privacy filter puts in the place of a hidden cover, and the
	 * one a move leaves behind when the cover is carried away, see issue #115.
	 */
	public void testAComputedCoverIsFramedInTheFrameOfItsImage() {
		// An 8x6 file turned a quarter is a portrait picture and is framed as one.
		ThumbnailInfo turned = PrivacyFilter.thumbnail(ImagePart.create()
			.setName("a.jpg").setWidth(8).setHeight(6).setOrientation(Orientation.ROT_L));

		assertEquals("The frame the crop is measured in is the image's own.",
			Orientation.ROT_L, turned.getOrientation());
		assertEquals("A portrait picture fills the square by its aspect ratio.",
			4.0 / 3, turned.getScale(), 1e-9);
		assertEquals("...and is shifted so that its middle is what the square shows.",
			37.5, turned.getTy(), 1e-9);

		// The same file without a stored rotation is framed exactly as it always was.
		ThumbnailInfo upright = PrivacyFilter.thumbnail(ImagePart.create()
			.setName("a.jpg").setWidth(8).setHeight(6));
		assertEquals(Orientation.IDENTITY, upright.getOrientation());
		assertEquals(4.0 / 3, upright.getScale(), 1e-9);
		assertEquals(0.0, upright.getTy(), 1e-9);
	}

	// --- Helpers. ---

	/** Writes an album with the given cover and one image of the given orientation. */
	private void album(String folder, String cover, String orientation) throws IOException {
		sidecar(folder, "[\"AlbumInfo\",{\"title\":\"" + folder + "\"," + cover
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6,"
			+ "\"orientation\":\"" + orientation + "\"}]]}]");
	}

	private static ThumbnailInfo cover(AlbumInfo album) {
		ThumbnailInfo result = album.getIndexPicture();
		assertNotNull("Expected a cover on " + album.getTitle(), result);
		return result;
	}

	private static ThumbnailInfo cover(ListingInfo listing, String folderName) {
		for (FolderInfo folder : listing.getFolders()) {
			if (folder.getName().equals(folderName)) {
				ThumbnailInfo result = folder.getIndexPicture();
				assertNotNull("Expected a cover on the tile of " + folderName, result);
				return result;
			}
		}
		throw new AssertionError("No folder '" + folderName + "' in the listing.");
	}

	private AlbumInfo album(String folder) throws Exception {
		FolderResource resource = parse(getJson("/" + folder + "/"));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private ListingInfo listing() throws Exception {
		FolderResource resource = parse(getJson("/"));
		assertTrue("Expected a listing, got: " + resource, resource instanceof ListingInfo);
		return (ListingInfo) resource;
	}

	private static FolderResource parse(String body) throws IOException {
		return FolderResource.readFolderResource(new JsonReader(new ReaderAdapter(new StringReader(body))));
	}

	private static String json(FolderResource resource) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(
			new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			json.setIndent("  ");
			resource.writeTo(json);
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}

	private String getJson(String pathInfo) throws Exception {
		FakeResponse response = new FakeResponse();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		_servlet.doGet(
			TestImageServletPut.request(pathInfo, null, new byte[0], Collections.emptyMap(), parameters),
			response.response());
		assertEquals(HttpServletResponse.SC_OK, response.status());
		return response.body();
	}

	private FakeResponse put(String pathInfo, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(
			TestImageServletPut.request(pathInfo, "application/json", body.getBytes(StandardCharsets.UTF_8)),
			response.response());
		return response;
	}

	private String contents(String folder) throws IOException {
		return new String(Files.readAllBytes(_base.resolve(folder).resolve("index.json")),
			StandardCharsets.UTF_8);
	}

	private void sidecar(String folder, String contents) throws IOException {
		Path path = _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"), contents.getBytes(StandardCharsets.UTF_8));
	}

	private void image(String relativePath, int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.RED);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", buffer);
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		Files.write(path, buffer.toByteArray());
	}

}
