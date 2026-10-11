/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
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
 * Review probe of the place caption composed with the guard for older apps and with groups: a
 * current app taking a photograph back to "as the album says" is obeyed, and an older app writing
 * a group keeps what its members said.
 */
@SuppressWarnings("javadoc")
public class TestPlaceCaptionProbe extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-place-caption-probe");
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

	/** A current app always writes the field: taking the only word back to UNSET is obeyed. */
	public void testACurrentAppTakesAWordBack() throws Exception {
		image("A/a.jpg");
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"placeCaption\":\"HIDDEN\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"placeCaption\":\"SHOWN\"}]]}]");
		AlbumInfo album = get("/A/");
		album.setPlaceCaption(PlaceCaption.UNSET);
		image(album, "a.jpg").setPlaceCaption(PlaceCaption.UNSET);

		FakeResponse stored = put("/A/", write(album));
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());

		AlbumInfo again = get("/A/");
		assertEquals(PlaceCaption.UNSET, again.getPlaceCaption());
		assertEquals(PlaceCaption.UNSET, image(again, "a.jpg").getPlaceCaption());
		assertTrue("Nothing of the old words survives in the sidecar.",
			!read("A/index.json").contains("HIDDEN") && !read("A/index.json").contains("SHOWN"));
	}

	/** An older app writing an album with a group keeps the words of the group's members. */
	public void testAnOlderAppKeepsTheWordsOfAGroup() throws Exception {
		image("B/a.jpg");
		image("B/b.jpg");
		sidecar("B", "[\"AlbumInfo\",{\"title\":\"B\",\"parts\":["
			+ "[\"ImageGroup\",{\"representative\":1,\"images\":["
			+ "{\"name\":\"a.jpg\",\"width\":4,\"height\":3},"
			+ "{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"placeCaption\":\"HIDDEN\"}]}]]}]");
		get("/B/");

		FakeResponse stored = put("/B/", "[\"AlbumInfo\",{\"title\":\"B\",\"subTitle\":\"Old\",\"parts\":["
			+ "[\"ImageGroup\",{\"representative\":0,\"images\":["
			+ "{\"name\":\"a.jpg\",\"width\":4,\"height\":3},"
			+ "{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"comment\":\"Hi\"}]}]]}]");
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());

		AlbumInfo again = get("/B/");
		assertEquals("Old", again.getSubTitle());
		ImageGroup group = (ImageGroup) again.getParts().get(0);
		assertEquals("The older app's edit of the group is taken.", 0, group.getRepresentative());
		assertEquals(PlaceCaption.UNSET, group.getImages().get(0).getPlaceCaption());
		assertEquals("Hi", group.getImages().get(1).getComment());
		assertEquals("The member's word is kept.", PlaceCaption.HIDDEN, group.getImages().get(1).getPlaceCaption());
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
