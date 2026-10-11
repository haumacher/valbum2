/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.PlaceCaption;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
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
 * Test case for the place shown as the caption of a photograph without a description:
 * {@link AlbumInfo#getPlaceCaption()} and {@link ImagePart#getPlaceCaption()}.
 *
 * <p>
 * Both are statements of whoever may edit the album, stored in its <code>index.json</code> and
 * written by the ordinary sidecar <code>PUT</code>. The servlet is driven headlessly on a temporary
 * base folder, exactly as {@link TestStarred} does.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPlaceCaption extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-place-caption-test");
		_servlet = new ImageServlet(_base.toFile());
	}

	@Override
	protected void tearDown() throws Exception {
		_servlet.destroy();
		_servlet = null;
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	/** A sidecar written before the fields reads as {@link PlaceCaption#UNSET}, album and photograph. */
	public void testAnOlderSidecarReadsUnset() throws Exception {
		image("A/a.jpg");
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\","
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3}]]}]");

		AlbumInfo album = get("/A/");
		assertEquals(PlaceCaption.UNSET, album.getPlaceCaption());
		assertEquals(PlaceCaption.UNSET, image(album, "a.jpg").getPlaceCaption());
	}

	/** Read, written and read again says the same thing, both fields included. */
	public void testReadWriteRead() throws Exception {
		image("A/a.jpg");
		image("A/b.jpg");
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"placeCaption\":\"HIDDEN\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"placeCaption\":\"SHOWN\"}],"
			+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3}]]}]");

		AlbumInfo album = get("/A/");
		assertEquals(PlaceCaption.HIDDEN, album.getPlaceCaption());
		assertEquals(PlaceCaption.SHOWN, image(album, "a.jpg").getPlaceCaption());
		assertEquals(PlaceCaption.UNSET, image(album, "b.jpg").getPlaceCaption());

		FakeResponse stored = put("/A/", write(album));
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());
		AlbumInfo again = get("/A/");
		assertEquals(write(album), write(again));

		// The model itself: what is written is what is read.
		AlbumInfo model = AlbumInfo.create().setPlaceCaption(PlaceCaption.HIDDEN)
			.addPart(ImagePart.create().setName("x.jpg").setPlaceCaption(PlaceCaption.SHOWN));
		AlbumInfo parsed = (AlbumInfo) Resource.readResource(reader(write(model)));
		assertEquals(write(model), write(parsed));
		assertEquals(PlaceCaption.HIDDEN, parsed.getPlaceCaption());
		assertEquals(PlaceCaption.SHOWN, image(parsed, "x.jpg").getPlaceCaption());
	}

	/** An album switched off by its properties reads back switched off, from the sidecar too. */
	public void testAnAlbumWrittenHiddenReadsHidden() throws Exception {
		image("A/a.jpg");
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\","
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3}]]}]");

		AlbumInfo read = get("/A/");
		read.setPlaceCaption(PlaceCaption.HIDDEN);
		image(read, "a.jpg").setPlaceCaption(PlaceCaption.SHOWN);
		FakeResponse stored = put("/A/", write(read));
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());

		AlbumInfo again = get("/A/");
		assertEquals(PlaceCaption.HIDDEN, again.getPlaceCaption());
		assertEquals(PlaceCaption.SHOWN, image(again, "a.jpg").getPlaceCaption());
		String sidecar = read("A/index.json");
		assertTrue(sidecar, sidecar.contains("\"placeCaption\":\"HIDDEN\""));
		assertTrue(sidecar, sidecar.contains("\"placeCaption\":\"SHOWN\""));

		read.setPlaceCaption(PlaceCaption.UNSET);
		assertEquals(HttpServletResponse.SC_OK, put("/A/", write(read)).status());
		assertEquals("Switched back on.", PlaceCaption.UNSET, get("/A/").getPlaceCaption());
	}

	/**
	 * An app that does not know the field writes the album back without it; what the author said
	 * is kept rather than silently reset.
	 */
	public void testAnOlderAppKeepsThePlaceCaptions() throws Exception {
		image("A/a.jpg");
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"placeCaption\":\"HIDDEN\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"placeCaption\":\"SHOWN\"}]]}]");
		get("/A/");

		FakeResponse stored = put("/A/", "[\"AlbumInfo\",{\"title\":\"A\",\"subTitle\":\"New\","
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"comment\":\"Hi\"}]]}]");
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());

		AlbumInfo again = get("/A/");
		assertEquals("The older app's edit is taken.", "New", again.getSubTitle());
		assertEquals("Hi", image(again, "a.jpg").getComment());
		assertEquals("The album's word is kept.", PlaceCaption.HIDDEN, again.getPlaceCaption());
		assertEquals("The photograph's word is kept.", PlaceCaption.SHOWN, image(again, "a.jpg").getPlaceCaption());
	}

	// --- Helpers. ---

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && name.equals(((ImagePart) part).getName())) {
				return (ImagePart) part;
			}
		}
		fail("No image '" + name + "'.");
		return null;
	}

	/** A tiny JPEG at the given (possibly nested) path below the base folder. */
	private void image(String path) throws IOException {
		Path file = _base.resolve(path);
		Files.createDirectories(file.getParent());
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", file.toFile());
	}

	private void sidecar(String folder, String json) throws IOException {
		Path dir = _base.resolve(folder);
		Files.createDirectories(dir);
		Files.write(dir.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String path) throws IOException {
		return new String(Files.readAllBytes(_base.resolve(path)), StandardCharsets.UTF_8);
	}

	private AlbumInfo get(String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(pathInfo, null, "", parameters), response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private FakeResponse put(String pathInfo, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(request(pathInfo, "application/json", body, new HashMap<>()), response.response());
		return response;
	}

	private static HttpServletRequest request(String pathInfo, String contentType, String body,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static String write(Resource resource) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			resource.writeTo(json);
		}
		return buffer.toString();
	}
}
