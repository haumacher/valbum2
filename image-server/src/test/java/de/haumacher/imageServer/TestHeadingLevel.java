/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the level of a {@link Heading}, see issue #158.
 *
 * <p>
 * The level is stored in the album's sidecar like the text: a PUT keeps it, a GET answers it, and a
 * sidecar written before the field existed reads unchanged, its headings answering level
 * <code>0</code>, which means a section exactly like <code>1</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestHeadingLevel extends TestCase {

	private static final String ALBUM_WITH_LEVELS = "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
		+ "[\"Heading\",{\"text\":\"May\",\"level\":1}],"
		+ "[\"Heading\",{\"text\":\"Monday\",\"level\":2}]"
		+ "]}]";

	/** A sidecar as a server before issue #158 wrote it: no level anywhere. */
	private static final String OLD_ALBUM = "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
		+ "[\"Heading\",{\"text\":\"Before\"}]"
		+ "]}]";

	private Path _base;

	private ImageServlet _servlet;

	private File _album;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-heading-test");
		_servlet = new ImageServlet(_base.toFile());
		_album = new File(_base.toFile(), "Trip");
		assertTrue(_album.mkdir());
	}

	@Override
	protected void tearDown() throws Exception {
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testTheLevelSurvivesTheRoundTrip() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, put(ALBUM_WITH_LEVELS).status());
		String stored = read(new File(_album, "index.json"));
		assertTrue(stored, stored.contains("\"level\":2"));

		List<Heading> headings = headings(get());
		assertEquals(2, headings.size());
		assertEquals("May", headings.get(0).getText());
		assertEquals(1, headings.get(0).getLevel());
		assertEquals("Monday", headings.get(1).getText());
		assertEquals(2, headings.get(1).getLevel());

		// What was answered, written back, is answered again unchanged.
		assertEquals(HttpServletResponse.SC_OK, put(write(get())).status());
		headings = headings(get());
		assertEquals(1, headings.get(0).getLevel());
		assertEquals(2, headings.get(1).getLevel());
	}

	public void testAnOlderSidecarReadsAsASection() throws Exception {
		Files.write(new File(_album, "index.json").toPath(), OLD_ALBUM.getBytes(StandardCharsets.UTF_8));

		List<Heading> headings = headings(get());
		assertEquals(1, headings.size());
		assertEquals("Before", headings.get(0).getText());
		assertEquals("No level stored is the section of every older heading.", 0, headings.get(0).getLevel());

		// Written back, the heading carries the value it was read with, which means the same.
		assertEquals(HttpServletResponse.SC_OK, put(write(get())).status());
		headings = headings(get());
		assertEquals("Before", headings.get(0).getText());
		assertEquals(0, headings.get(0).getLevel());
	}

	private static List<Heading> headings(AlbumInfo album) {
		return album.getParts().stream()
			.filter(Heading.class::isInstance)
			.map(Heading.class::cast)
			.toList();
	}

	private AlbumInfo get() throws Exception {
		FakeResponse response = new FakeResponse();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		_servlet.doGet(TestImageServletPut.request("/Trip/", null, new byte[0], Collections.emptyMap(), parameters),
			response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		Resource resource = Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		assertTrue(resource instanceof AlbumInfo);
		AlbumInfo album = (AlbumInfo) resource;
		for (AlbumPart part : album.getParts()) {
			assertTrue(part instanceof Heading);
		}
		return album;
	}

	private static String write(AlbumInfo album) throws Exception {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			album.writeTo(json);
		}
		return buffer.toString();
	}

	private FakeResponse put(String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(TestImageServletPut.request("/Trip/", "application/json", body.getBytes(StandardCharsets.UTF_8)),
			response.response());
		return response;
	}

	private static String read(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}
}
