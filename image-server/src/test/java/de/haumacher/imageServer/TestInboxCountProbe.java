/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
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

		assertEquals(3, inboxCount());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "application/json");
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request("/Inbox/", "application/json",
			"{\"target\":\"\",\"names\":[{\"name\":\"b.jpg\"}]}".getBytes(StandardCharsets.UTF_8), headers, parameters),
			response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());

		assertEquals("One went to the trash: the badge says two.", 2, inboxCount());
	}

	/** How many photographs ?type=auth says wait in the inbox, see issue #226. */
	private int inboxCount() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "auth");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request("/", null, new byte[0], new HashMap<>(), parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return de.haumacher.imageServer.shared.model.AuthInfo.readAuthInfo(
			new JsonReader(new ReaderAdapter(new StringReader(response.body())))).getInboxCount();
	}
}
