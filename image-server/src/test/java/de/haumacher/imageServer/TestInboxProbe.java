/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Review probes of the inbox (issue #135), composed with the groups of an album: an inbox is answered
 * flat, so whoever acts on one of its photographs acts on that one photograph — never on a group the
 * sidecar still remembers behind it.
 */
@SuppressWarnings("javadoc")
public class TestInboxProbe extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-inbox-probe");
		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
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

	/** The inbox that remembers a group of two behind its flat answer. */
	private void groupedInbox() throws IOException {
		image("Inbox/a.jpg", Color.RED);
		image("Inbox/b1.jpg", Color.GREEN);
		image("Inbox/b2.jpg", Color.BLUE);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", day("2026-03-01")) + ","
			+ group(image("b1.jpg", day("2026-02-01")), image("b2.jpg", day("2026-03-03")))
			+ "]}]");
	}

	public void testDeletingTheRepresentativeOfARememberedGroupDeletesThatPhotographOnly() throws Exception {
		groupedInbox();
		assertEquals(Arrays.asList("b2.jpg", "a.jpg", "b1.jpg"), imageNames(album("/Inbox/")));

		MoveResult result = delete("/Inbox/", "b1.jpg");

		assertEquals(1, result.getOutcomes().size());
		assertEquals(DeleteService.trashed("b1.jpg"), result.getOutcomes().get(0).getMessage());
		assertTrue(_base.resolve("Inbox/b2.jpg").toFile().isFile());
		assertTrue(new File(trash(), "Inbox/b1.jpg").isFile());
		assertFalse("The other member of the remembered group is nobody's business here.",
			new File(trash(), "Inbox/b2.jpg").exists());
		assertEquals(Arrays.asList("b2.jpg", "a.jpg"), imageNames(album("/Inbox/")));
	}

	public void testMovingTheRepresentativeOfARememberedGroupMovesThatPhotographOnly() throws Exception {
		groupedInbox();
		image("Trip/t.jpg", Color.YELLOW);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("t.jpg", day("2026-01-01")) + "]}]");

		MoveResult result = move("/Inbox/", "Trip", "b1.jpg");

		assertEquals(1, result.getOutcomes().size());
		assertEquals("Refused: " + result.getOutcomes().get(0).getMessage(), "b1.jpg",
			result.getOutcomes().get(0).getNewName());
		assertTrue(_base.resolve("Trip/b1.jpg").toFile().isFile());
		assertFalse("The other member of the remembered group stays in the inbox.",
			_base.resolve("Trip/b2.jpg").toFile().exists());
		assertTrue(_base.resolve("Inbox/b2.jpg").toFile().isFile());
		assertEquals(Arrays.asList("b2.jpg", "a.jpg"), imageNames(album("/Inbox/")));
		assertEquals("The target album lists what arrived, as one photograph.",
			Arrays.asList("t.jpg", "b1.jpg"), imageNames(album("/Trip/")));
		for (AlbumPart part : album("/Trip/").getParts()) {
			assertFalse("No group arrives that the mover never saw.", part instanceof ImageGroup);
		}
	}

	// --- Helpers. ---

	private File trash() {
		return _base.resolve(".valbum").resolve(DeleteService.TRASH_FOLDER).toFile();
	}

	private AlbumInfo album(String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], new HashMap<>(), parameters),
			response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private MoveResult delete(String pathInfo, String... names) throws Exception {
		return action("delete", pathInfo, "", names);
	}

	private MoveResult move(String pathInfo, String target, String... names) throws Exception {
		return action("move", pathInfo, target, names);
	}

	private MoveResult action(String action, String pathInfo, String target, String... names) throws Exception {
		String body = "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "application/json");
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request(pathInfo, "application/json",
			body.getBytes(StandardCharsets.UTF_8), headers, parameters), response.response());
		assertEquals("The " + action + " failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static List<String> imageNames(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					result.add(image.getName());
				}
			}
		}
		return result;
	}

	private static String part(String name, long date) {
		return "[\"ImagePart\"," + image(name, date) + "]";
	}

	private static String image(String name, long date) {
		return "{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"date\":" + date + ",\"width\":8,\"height\":6}";
	}

	private static String group(String... images) {
		return "[\"ImageGroup\",{\"representative\":0,\"images\":[" + String.join(",", images) + "]}]";
	}

	private static long day(String isoDate) {
		return java.time.LocalDate.parse(isoDate).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
	}

	private void sidecar(String folder, String contents) throws IOException {
		Path path = _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"), contents.getBytes(StandardCharsets.UTF_8));
	}

	private void image(String relativePath, Color color) throws IOException {
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		BufferedImage img = new BufferedImage(8, 6, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = img.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 8, 6);
		g.dispose();
		ImageIO.write(img, "jpg", path.toFile());
	}
}
