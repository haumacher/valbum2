/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.PresentFile;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
 * Probe for issue #252: the user's scenario, photos moved into an album that holds a cut-off recording.
 *
 *
 */
@SuppressWarnings("javadoc")
public class TestUnreadableFileProbe extends TestCase {

	private static final String SECRET = "let-me-in";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-unreadable-probe");
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testMovingPhotosIntoAnAlbumWithACutOffRecording() throws Exception {
		write("2025/A/x.jpg", jpeg(6, 4, Color.RED));
		write("2025/B/y.jpg", jpeg(4, 6, Color.BLUE));
		write("2025/B/20250903_185939.mp4", LargeMp4.truncated());
		String owner = signIn();
		ImageServlet servlet = servlet();

		MoveResult result = move(servlet, "/2025/A/", "2025/B", owner, "x.jpg");
		assertEquals("", result.getOutcomes().get(0).getMessage());

		AlbumInfo target = album(get(servlet, "/2025/B/", "json", owner));
		assertEquals(Arrays.asList("y.jpg", "x.jpg"), names(target));
		String sidecar = new String(Files.readAllBytes(_base.resolve("2025/B/index.json")), StandardCharsets.UTF_8);
		assertFalse(sidecar, sidecar.contains("20250903_185939"));

		FakeResponse listing = get(servlet, "/2025/", "json", owner);
		assertEquals(listing.body(), HttpServletResponse.SC_OK, listing.status());

		// Moving the broken recording itself is answered, not a 500: moved or refused with a reason.
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request("/2025/B/", "application/json",
			"{\"target\":\"2025/A\",\"names\":[{\"name\":\"20250903_185939.mp4\"}]}", owner, parameters),
			response.response());
		assertTrue(response.status() + ": " + response.body(), response.status() < 500);
		assertTrue("The original is somewhere, untouched.",
			Files.exists(_base.resolve("2025/B/20250903_185939.mp4"))
				|| Files.exists(_base.resolve("2025/A/20250903_185939.mp4")));

		// A fresh server reads the same truth from disk.
		ImageServlet restarted = servlet();
		assertEquals(200, get(restarted, "/2025/", "json", owner).status());
		assertTrue(names(album(get(restarted, "/2025/B/", "json", owner))).contains("x.jpg"));
	}

	private List<String> present(ImageServlet servlet, String folder, String token, byte[] contents) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "check");
		FakeResponse response = new FakeResponse();
		String body = "{\"hashes\":[{\"hash\":\"" + HashCache.sha256(contents) + "\"}]}";
		servlet.doPost(request(folder, "application/json", body, token, parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		UploadCheckResult result = UploadCheckResult.readUploadCheckResult(reader(response.body()));
		List<String> names = new ArrayList<>();
		for (PresentFile file : result.getPresent()) {
			names.add(file.getName());
		}
		return names;
	}

	private static MoveResult move(ImageServlet servlet, String pathInfo, String target, String token,
			String... names) throws Exception {
		String body = "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body, token, parameters), response.response());
		assertEquals("The move failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private ImageServlet servlet() throws IOException {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, _base));
		_servlets.add(servlet);
		return servlet;
	}

	private String signIn() throws Exception {
		return Codes.signInAdmin(new AuthService(AuthMode.WRITES, _base), "Phone", "haui").getToken();
	}

	private void write(String relativePath, byte[] contents) throws IOException {
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		Files.write(path, contents);
	}

	private static byte[] jpeg(int width, int height, Color color) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	private static List<String> names(AlbumInfo album) {
		List<String> names = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				names.add(((ImagePart) part).getName());
			}
		}
		return names;
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		fail("No image '" + name + "' in the album.");
		return null;
	}

	private static AlbumInfo album(FakeResponse response) throws IOException {
		assertEquals("Expected a successful request, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String type, String token)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private static HttpServletRequest request(String pathInfo, String contentType, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}
}
