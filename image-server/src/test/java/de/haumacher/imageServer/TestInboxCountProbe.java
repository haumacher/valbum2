/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.FolderInfo;
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

/** Review probe of the inbox count (issue #137), composed with the single-image delete of #131. */
@SuppressWarnings("javadoc")
public class TestInboxCountProbe extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-inbox-count-probe");
		_servlet = new ImageServlet(_base.toFile());
		_servlet.init();
	}

	@Override
	protected void tearDown() throws Exception {
		_servlet.destroy();
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testTheCountFollowsWhatIsThrownAway() throws Exception {
		for (String name : new String[] { "a.jpg", "b.jpg", "c.jpg" }) {
			Path file = _base.resolve("Inbox").resolve(name);
			Files.createDirectories(file.getParent());
			ImageIO.write(new BufferedImage(8, 6, BufferedImage.TYPE_3BYTE_BGR), "jpg", file.toFile());
		}
		Files.write(_base.resolve("Inbox/index.json"), ("[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6}],"
			+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6}],"
			+ "[\"ImagePart\",{\"name\":\"c.jpg\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6}]]}]")
			.getBytes(StandardCharsets.UTF_8));

		assertEquals(3, inbox().getImageCount());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "application/json");
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request("/Inbox/", "application/json",
			"{\"target\":\"\",\"names\":[{\"name\":\"b.jpg\"}]}".getBytes(StandardCharsets.UTF_8), headers, parameters),
			response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());

		assertEquals("One went to the trash: the tile says two.", 2, inbox().getImageCount());
	}

	private FolderInfo inbox() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request("/", null, new byte[0], new HashMap<>(), parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		ListingInfo listing = (ListingInfo) Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		for (FolderInfo folder : listing.getFolders()) {
			if ("Inbox".equals(folder.getName())) {
				return folder;
			}
		}
		fail("No inbox in " + listing.getFolders());
		return null;
	}
}
