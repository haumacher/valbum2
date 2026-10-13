/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the place a moved photograph takes in the target album, see issue #262.
 *
 * <p>
 * Every move goes through the servlet's <code>?action=move</code> — the one endpoint the album, the
 * inbox and the viewer all move with — and every expectation is read back from the target's sidecar
 * on disk, so that the order is the one that was stored, not one held in memory.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestMoveByDate extends TestCase {

	private static final long DAY_1 = Instant.parse("2024-05-01T00:00:00Z").toEpochMilli();

	private static final long DAY_2 = Instant.parse("2024-05-02T00:00:00Z").toEpochMilli();

	private static final long HOUR = 60 * 60 * 1000L;

	private Path _base;

	private ImageServlet _servlet;

	private int _color;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-move-by-date");
		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
		_servlet.init();
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

	public void testBetweenTwoPhotographs() throws Exception {
		album("A", photo("x.jpg", DAY_1 + 13 * HOUR));
		album("B", photo("a.jpg", DAY_1 + 10 * HOUR), photo("b.jpg", DAY_1 + 12 * HOUR),
			photo("c.jpg", DAY_1 + 14 * HOUR));

		move("A", "B", "x.jpg");

		assertEquals(Arrays.asList("a.jpg", "b.jpg", "x.jpg", "c.jpg"), order("B"));
	}

	public void testTheEveningOfDayOneLandsBeforeTheHeadingOfDayTwo() throws Exception {
		album("A", photo("x.jpg", DAY_1 + 20 * HOUR));
		album("B", heading("Day 1"), photo("a.jpg", DAY_1 + 10 * HOUR), photo("b.jpg", DAY_1 + 12 * HOUR),
			heading("Day 2"), photo("c.jpg", DAY_2 + 10 * HOUR));

		move("A", "B", "x.jpg");

		assertEquals(Arrays.asList("#Day 1", "a.jpg", "b.jpg", "x.jpg", "#Day 2", "c.jpg"), order("B"));
	}

	public void testEarlierThanEverythingLandsBehindTheLeadingHeading() throws Exception {
		album("A", photo("x.jpg", DAY_1 + 8 * HOUR));
		album("B", heading("Day 1"), photo("a.jpg", DAY_1 + 10 * HOUR), heading("Day 2"),
			photo("c.jpg", DAY_2 + 10 * HOUR));

		move("A", "B", "x.jpg");

		assertEquals(Arrays.asList("#Day 1", "x.jpg", "a.jpg", "#Day 2", "c.jpg"), order("B"));
	}

	public void testLaterThanEverythingLandsAtTheEnd() throws Exception {
		album("A", photo("x.jpg", DAY_2 + 20 * HOUR));
		album("B", heading("Day 1"), photo("a.jpg", DAY_1 + 10 * HOUR), heading("Day 2"),
			photo("c.jpg", DAY_2 + 10 * HOUR));

		move("A", "B", "x.jpg");

		assertEquals(Arrays.asList("#Day 1", "a.jpg", "#Day 2", "c.jpg", "x.jpg"), order("B"));
	}

	public void testAnUndatedPhotographLandsAtTheEnd() throws Exception {
		album("A", photo("x.jpg", 0));
		album("B", photo("a.jpg", DAY_1 + 10 * HOUR), photo("c.jpg", DAY_2 + 10 * HOUR));

		move("A", "B", "x.jpg");

		assertEquals(Arrays.asList("a.jpg", "c.jpg", "x.jpg"), order("B"));
	}

	public void testAnEqualDateKeepsTheExistingPhotographFirst() throws Exception {
		album("A", photo("x.jpg", DAY_1 + 12 * HOUR));
		album("B", photo("a.jpg", DAY_1 + 10 * HOUR), photo("b.jpg", DAY_1 + 12 * HOUR),
			photo("c.jpg", DAY_1 + 14 * HOUR));

		move("A", "B", "x.jpg");

		assertEquals(Arrays.asList("a.jpg", "b.jpg", "x.jpg", "c.jpg"), order("B"));
	}

	public void testMovedPhotographsOfTheSameDateKeepTheOrderAsked() throws Exception {
		album("A", photo("x.jpg", DAY_1 + 12 * HOUR), photo("y.jpg", DAY_1 + 12 * HOUR));
		album("B", photo("a.jpg", DAY_1 + 10 * HOUR), photo("c.jpg", DAY_1 + 14 * HOUR));

		move("A", "B", "y.jpg", "x.jpg");

		assertEquals(Arrays.asList("a.jpg", "y.jpg", "x.jpg", "c.jpg"), order("B"));
	}

	public void testSeveralPhotographsInterleave() throws Exception {
		album("A", photo("w.jpg", DAY_1 + 8 * HOUR), photo("x.jpg", DAY_1 + 11 * HOUR),
			photo("y.jpg", DAY_1 + 20 * HOUR), photo("z.jpg", DAY_2 + 20 * HOUR), photo("v.jpg", DAY_1 + 9 * HOUR));
		album("B", heading("Day 1"), photo("a.jpg", DAY_1 + 10 * HOUR), photo("b.jpg", DAY_1 + 12 * HOUR),
			heading("Day 2"), photo("c.jpg", DAY_2 + 10 * HOUR));

		// Asked in an order that is not the order of the dates.
		move("A", "B", "y.jpg", "w.jpg", "z.jpg", "x.jpg", "v.jpg");

		assertEquals(Arrays.asList("#Day 1", "w.jpg", "v.jpg", "a.jpg", "x.jpg", "b.jpg", "y.jpg", "#Day 2",
			"c.jpg", "z.jpg"), order("B"));
	}

	public void testAGroupIsPlacedByItsRepresentative() throws Exception {
		image("A/g1.jpg");
		image("A/g2.jpg");
		// The first member is the earliest, the representative is the one of the afternoon.
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[[\"ImageGroup\",{\"representative\":1,\"images\":["
			+ member("g1.jpg", DAY_1 + 8 * HOUR) + "," + member("g2.jpg", DAY_1 + 13 * HOUR) + "]}]]}]");
		album("B", photo("a.jpg", DAY_1 + 10 * HOUR), photo("b.jpg", DAY_1 + 12 * HOUR),
			photo("c.jpg", DAY_1 + 14 * HOUR));

		move("A", "B", "g2.jpg");

		List<AlbumPart> parts = read("B").getParts();
		assertEquals(4, parts.size());
		assertTrue("The group arrives as one part, behind b.", parts.get(2) instanceof ImageGroup);
		assertEquals(Arrays.asList("a.jpg", "b.jpg", "g1.jpg", "g2.jpg", "c.jpg"), order("B"));
	}

	public void testARawPairStaysTogether() throws Exception {
		image("A/x.jpg");
		write("A/x.cr2", "raw sensor data".getBytes(StandardCharsets.UTF_8));
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[[\"ImagePart\",{\"name\":\"x.jpg\",\"raw\":\"x.cr2\","
			+ "\"kind\":\"IMAGE\",\"width\":8,\"height\":6,\"date\":" + (DAY_1 + 11 * HOUR) + "}]]}]");
		album("B", photo("a.jpg", DAY_1 + 10 * HOUR), photo("c.jpg", DAY_1 + 14 * HOUR));

		move("A", "B", "x.jpg");

		assertEquals(Arrays.asList("a.jpg", "x.jpg", "c.jpg"), order("B"));
		ImagePart moved = (ImagePart) read("B").getParts().get(1);
		assertEquals("The raw companion travels with its photograph.", "x.cr2", moved.getRaw());
		assertTrue(_base.resolve("B/x.cr2").toFile().isFile());
	}

	public void testATargetWithoutPhotographsTakesThemAtTheEnd() throws Exception {
		album("A", photo("x.jpg", DAY_1 + 13 * HOUR), photo("y.jpg", DAY_1 + 9 * HOUR));
		album("B", heading("Day 1"));

		move("A", "B", "x.jpg", "y.jpg");

		assertEquals(Arrays.asList("#Day 1", "y.jpg", "x.jpg"), order("B"));
	}

	public void testAnEmptyTargetFolderTakesThemByDate() throws Exception {
		album("A", photo("x.jpg", DAY_1 + 13 * HOUR), photo("y.jpg", DAY_1 + 9 * HOUR));
		Files.createDirectories(_base.resolve("B"));

		move("A", "B", "x.jpg", "y.jpg");

		assertEquals(Arrays.asList("y.jpg", "x.jpg"), order("B"));
	}

	public void testATargetOfUndatedPhotographsTakesThemAtTheEnd() throws Exception {
		album("A", photo("x.jpg", DAY_1 + 13 * HOUR));
		album("B", photo("a.jpg", 0), photo("b.jpg", 0));

		move("A", "B", "x.jpg");

		assertEquals(Arrays.asList("a.jpg", "b.jpg", "x.jpg"), order("B"));
	}

	public void testAnAlbumSortedByHandIsNotResorted() throws Exception {
		album("A", photo("x.jpg", DAY_1 + 13 * HOUR));
		// The author put the late photograph first, as a title picture.
		album("B", photo("c.jpg", DAY_1 + 14 * HOUR), photo("a.jpg", DAY_1 + 10 * HOUR),
			photo("b.jpg", DAY_1 + 12 * HOUR), photo("d.jpg", DAY_1 + 16 * HOUR));

		move("A", "B", "x.jpg");

		assertEquals(Arrays.asList("c.jpg", "a.jpg", "b.jpg", "x.jpg", "d.jpg"), order("B"));
	}

	public void testATargetWithoutSidecarIsOrderedByItsAnalysis() throws Exception {
		album("A", photo("x.jpg", DAY_1 + 13 * HOUR));
		image("B/a.jpg");
		image("B/c.jpg");
		_base.resolve("B/a.jpg").toFile().setLastModified(DAY_1 + 10 * HOUR);
		_base.resolve("B/c.jpg").toFile().setLastModified(DAY_1 + 14 * HOUR);
		// What the background finds the folder to be: its order is derived from the analysis.
		de.haumacher.imageServer.cache.AnalysisRecord.update(_base.resolve("B").toFile(), _servlet.cache().analyst(), true);

		move("A", "B", "x.jpg");

		assertEquals(Arrays.asList("a.jpg", "x.jpg", "c.jpg"), order("B"));
	}

	/**
	 * Probe: photographs moved out of an album sorted by date and back again find their old places,
	 * headings and a group included.
	 */
	public void testProbeAMoveThereAndBackRestoresTheOrder() throws Exception {
		image("A/g1.jpg");
		image("A/g2.jpg");
		for (String name : new String[] { "a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg" }) {
			image("A/" + name);
		}
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + heading("Day 1") + ","
			+ photo("a.jpg", DAY_1 + 9 * HOUR) + "," + photo("b.jpg", DAY_1 + 11 * HOUR) + ","
			+ "[\"ImageGroup\",{\"representative\":0,\"images\":[" + member("g1.jpg", DAY_1 + 15 * HOUR) + ","
			+ member("g2.jpg", DAY_1 + 16 * HOUR) + "]}]," + photo("c.jpg", DAY_1 + 21 * HOUR) + ","
			+ heading("Day 2") + "," + photo("d.jpg", DAY_2 + 8 * HOUR) + "," + photo("e.jpg", DAY_2 + 9 * HOUR)
			+ "]}]");
		album("B", photo("z.jpg", DAY_2 + 23 * HOUR));
		List<String> before = order("A");

		move("A", "B", "e.jpg", "b.jpg", "g1.jpg", "c.jpg");
		assertEquals(Arrays.asList("b.jpg", "g1.jpg", "g2.jpg", "c.jpg", "e.jpg", "z.jpg"), order("B"));

		move("B", "A", "c.jpg", "g1.jpg", "e.jpg", "b.jpg");
		assertEquals(before, order("A"));
	}

	// --- Helpers. ---

	private void move(String source, String target, String... names) throws Exception {
		String body = "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request("/" + source + "/", "application/json",
			body.getBytes(StandardCharsets.UTF_8), Collections.singletonMap("Content-Type", "application/json"),
			parameters), response.response());
		assertEquals("The move failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		MoveResult result = MoveResult.readMoveResult(reader(response.body()));
		for (MoveOutcome outcome : result.getOutcomes()) {
			assertEquals("Moving '" + outcome.getName() + "' failed.", "", outcome.getMessage());
		}
	}

	/** Writes an album sidecar with the given parts and an image file for every photograph. */
	private void album(String folder, String... parts) throws IOException {
		for (String part : parts) {
			int start = part.indexOf("\"name\":\"");
			if (start >= 0) {
				start += 8;
				image(folder + "/" + part.substring(start, part.indexOf('"', start)));
			}
		}
		sidecar(folder, "[\"AlbumInfo\",{\"title\":\"" + folder + "\",\"parts\":[" + String.join(",", parts) + "]}]");
	}

	private static String photo(String name, long date) {
		return "[\"ImagePart\"," + member(name, date) + "]";
	}

	private static String member(String name, long date) {
		return "{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6"
			+ (date == 0 ? "" : ",\"date\":" + date) + "}";
	}

	private static String heading(String text) {
		return "[\"Heading\",{\"text\":\"" + text + "\"}]";
	}

	/** The order the target's sidecar on disk stores: image names, a heading as <code>#text</code>. */
	private List<String> order(String folder) throws IOException {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : read(folder).getParts()) {
			if (part instanceof Heading) {
				result.add("#" + ((Heading) part).getText());
			} else if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					result.add(image.getName());
				}
			}
		}
		return result;
	}

	private AlbumInfo read(String folder) throws IOException {
		Path index = _base.resolve(folder).resolve("index.json");
		assertTrue("Expected a sidecar at " + index, index.toFile().exists());
		FolderResource resource = FolderResource.readFolderResource(
			reader(new String(Files.readAllBytes(index), StandardCharsets.UTF_8)));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private void sidecar(String folder, String contents) throws IOException {
		Path path = _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"), contents.getBytes(StandardCharsets.UTF_8));
	}

	/** A real (tiny) JPEG, each one of different pixels, so that no two hash the same. */
	private void image(String relativePath) throws IOException {
		int n = _color++;
		BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(new Color((n * 37) % 256, (n * 91) % 256, (n * 151 + 40) % 256));
		graphics.fillRect(0, 0, 8, 6);
		graphics.setColor(Color.WHITE);
		graphics.fillRect(n % 8, 0, 1, 1 + n % 6);
		graphics.dispose();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", buffer);
		write(relativePath, buffer.toByteArray());
	}

	private void write(String relativePath, byte[] contents) throws IOException {
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		Files.write(path, contents);
	}
}
