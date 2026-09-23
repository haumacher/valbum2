/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ReanalyzeResult;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-reading the photo details of an album or a whole tree, see issue #161.
 *
 * <p>
 * A part a sidecar lists is never analysed again, so a library described before the camera and the
 * position existed carries neither; <code>?action=reanalyze</code> reads the headers again and
 * fills exactly what is missing, and nothing else.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestReanalyze extends ShareTestCase {

	private static final String TRIP = "Travel/2019/2019-06-01 Trip";

	private static final String OTHER = "Travel/2020/2020-07-01 Other";

	/** How exact a position has to come back: a millionth of a degree. */
	private static final double PRECISION = 1e-6;

	/** A date the author corrected by hand, which no file says. */
	private static final long CORRECTED = 1_000_000_000_000L;

	// --- What is filled, and what is never touched. ---

	public void testAnAlbumGainsCameraAndPositionWhileTheCorrectedDateStays() throws Exception {
		album(TRIP, part("north.jpg", "\"date\":" + CORRECTED + ",\"rating\":1,\"comment\":\"Kept\",\"privacy\":1,"
			+ "\"orientation\":\"ROT_180\""));
		TestGeoLocation.writeJpeg(file(TRIP, "north.jpg"), exif("Canon", "Canon EOS 5D", 48.123456, 8.654321));

		ReanalyzeResult result = reanalyze("/" + TRIP + "/", SharingFixture.ALICE);

		assertEquals(1, result.getExamined());
		assertEquals(1, result.getFilled());
		assertEquals(1, result.getAlbums());
		assertFalse(result.isRunning());

		ImagePart image = image(stored(TRIP), "north.jpg");
		assertEquals("Canon EOS 5D", image.getCamera());
		assertNotNull(image.getLocation());
		assertEquals(48.123456, image.getLocation().getLatitude(), PRECISION);
		assertEquals(8.654321, image.getLocation().getLongitude(), PRECISION);
		assertEquals("The date the author corrected stays.", CORRECTED, image.getDate());
		assertEquals(1, image.getRating());
		assertEquals("Kept", image.getComment());
		assertEquals(1, image.getPrivacy());
		assertEquals("ROT_180", image.getOrientation().name());

		// And what is answered is what was written.
		ImagePart answered = image(album(get("/" + TRIP + "/", "json", SharingFixture.ALICE)), "north.jpg");
		assertEquals("Canon EOS 5D", answered.getCamera());
		assertEquals(48.123456, answered.getLocation().getLatitude(), PRECISION);
	}

	public void testAZeroPositionGainsTheFilesRealOne() throws Exception {
		album(TRIP, part("north.jpg", "\"camera\":\"Own label\",\"location\":{\"latitude\":0.0,\"longitude\":0.0}"));
		TestGeoLocation.writeJpeg(file(TRIP, "north.jpg"), exif("Canon", "Canon EOS 5D", 48.123456, 8.654321));

		ReanalyzeResult result = reanalyze("/" + TRIP + "/", SharingFixture.ALICE);

		assertEquals(1, result.getFilled());
		ImagePart image = image(stored(TRIP), "north.jpg");
		assertEquals(48.123456, image.getLocation().getLatitude(), PRECISION);
		assertEquals("A stored camera is never replaced.", "Own label", image.getCamera());
	}

	public void testAStoredPositionIsNeverReplaced() throws Exception {
		album(TRIP, part("north.jpg", "\"location\":{\"latitude\":-33.918,\"longitude\":18.424}"));
		TestGeoLocation.writeJpeg(file(TRIP, "north.jpg"), exif("Canon", "Canon EOS 5D", 48.123456, 8.654321));

		ReanalyzeResult result = reanalyze("/" + TRIP + "/", SharingFixture.ALICE);

		assertEquals("The camera was missing.", 1, result.getFilled());
		ImagePart image = image(stored(TRIP), "north.jpg");
		assertEquals("Canon EOS 5D", image.getCamera());
		assertEquals("The stored position stays.", -33.918, image.getLocation().getLatitude(), PRECISION);
	}

	public void testNothingIsWrittenWhereNothingIsMissing() throws Exception {
		album(TRIP, part("full.jpg", "\"camera\":\"Canon EOS 5D\",\"location\":{\"latitude\":1.5,\"longitude\":2.5}")
			+ "," + part("silent.jpg", ""));
		TestGeoLocation.writeJpeg(file(TRIP, "full.jpg"), exif("Nikon", "D750", 10.0, 20.0));
		// A file that says nothing: missing in the sidecar, and missing in the file.
		TestGeoLocation.writeJpeg(file(TRIP, "silent.jpg"), null);
		Path sidecar = _base.resolve(TRIP).resolve("index.json");
		byte[] before = Files.readAllBytes(sidecar);
		long stamp = Files.getLastModifiedTime(sidecar).toMillis();
		List<String> entries = entries(_base.resolve(TRIP));

		ReanalyzeResult result = reanalyze("/" + TRIP + "/", SharingFixture.ALICE);

		assertEquals(2, result.getExamined());
		assertEquals(0, result.getFilled());
		assertEquals(0, result.getAlbums());
		assertTrue("The sidecar is not rewritten.", Arrays.equals(before, Files.readAllBytes(sidecar)));
		assertEquals(stamp, Files.getLastModifiedTime(sidecar).toMillis());
		assertEquals("Not even a backup is made.", entries, entries(_base.resolve(TRIP)));
	}

	public void testTheMembersOfAGroupAreFilledOneByOne() throws Exception {
		album(TRIP, "[\"ImageGroup\",{\"representative\":0,\"images\":[" + member("a.jpg") + ","
			+ member("b.jpg") + "]}]");
		TestGeoLocation.writeJpeg(file(TRIP, "a.jpg"), exif("Canon", "EOS", 1.0, 2.0));
		TestGeoLocation.writeJpeg(file(TRIP, "b.jpg"), exif("Canon", "EOS", 3.0, 4.0));

		ReanalyzeResult result = reanalyze("/" + TRIP + "/", SharingFixture.ALICE);

		assertEquals(2, result.getFilled());
		AlbumInfo album = stored(TRIP);
		assertTrue("The group stays a group.", album.getParts().get(0) instanceof ImageGroup);
		assertEquals(3.0, image(album, "b.jpg").getLocation().getLatitude(), PRECISION);
	}

	public void testAVideoIsReadFromItsContainer() throws Exception {
		File fixture = new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");
		assertTrue(fixture.isFile());
		album(TRIP, "[\"ImagePart\",{\"name\":\"clip.mp4\",\"kind\":\"VIDEO\",\"width\":640,\"height\":480,"
			+ "\"date\":" + CORRECTED + "}]," + part("north.jpg", ""));
		Files.copy(fixture.toPath(), _base.resolve(TRIP).resolve("clip.mp4"));
		TestGeoLocation.writeJpeg(file(TRIP, "north.jpg"), exif("Canon", "EOS", 1.0, 2.0));

		ReanalyzeResult result = reanalyze("/" + TRIP + "/", SharingFixture.ALICE);

		assertEquals("The video was looked at, and its container says nothing.", 2, result.getExamined());
		assertEquals(1, result.getFilled());
		ImagePart video = image(stored(TRIP), "clip.mp4");
		assertEquals(CORRECTED, video.getDate());
		assertNull(video.getLocation());
		assertEquals("", video.getCamera());
	}

	public void testReadWriteReadKeepsWhatWasFilled() throws Exception {
		album(TRIP, part("north.jpg", ""));
		TestGeoLocation.writeJpeg(file(TRIP, "north.jpg"), exif("Canon", "EOS", 48.5, 8.5));
		reanalyze("/" + TRIP + "/", SharingFixture.ALICE);

		AlbumInfo album = album(get("/" + TRIP + "/", "json", SharingFixture.ALICE));
		FakeResponse put = put("/" + TRIP + "/", json(album), SharingFixture.ALICE);
		assertEquals(body(put), HttpServletResponse.SC_OK, put.status());

		restartServer();
		ImagePart again = image(album(get("/" + TRIP + "/", "json", SharingFixture.ALICE)), "north.jpg");
		assertEquals("Canon EOS", again.getCamera());
		assertEquals(48.5, again.getLocation().getLatitude(), PRECISION);
	}

	// --- What is written: the stored sidecar, never an answer. ---

	/**
	 * An editor whose clearance hides a private photograph is answered the album without it; the
	 * sidecar written for them still holds it, filled like the rest and as private as it was.
	 */
	public void testWhatTheCallerIsNotShownStaysInTheSidecar() throws Exception {
		album(TRIP, part("open.jpg", "") + "," + part("secret.jpg", "\"privacy\":2"));
		TestGeoLocation.writeJpeg(file(TRIP, "open.jpg"), exif("Canon", "EOS", 1.0, 2.0));
		TestGeoLocation.writeJpeg(file(TRIP, "secret.jpg"), exif("Canon", "EOS", 3.0, 4.0));
		assertEquals("Carol is not shown the private photograph.", 1,
			album(get("/" + TRIP + "/", "json", SharingFixture.CAROL)).getParts().size());

		ReanalyzeResult result = reanalyze("/" + TRIP + "/", SharingFixture.CAROL);

		assertEquals(2, result.getFilled());
		AlbumInfo written = sidecarOnDisk(TRIP);
		assertEquals(Arrays.asList("open.jpg", "secret.jpg"), names(written));
		ImagePart secret = image(written, "secret.jpg");
		assertEquals(2, secret.getPrivacy());
		assertEquals(3.0, secret.getLocation().getLatitude(), PRECISION);
	}

	/**
	 * The stored order is written back as it is stored, even where it is not the order of the dates,
	 * and a file the sidecar does not list is not written into it as a side effect.
	 */
	public void testTheStoredFormIsWrittenAndNothingBeside() throws Exception {
		album(TRIP, part("late.jpg", "\"date\":3000") + "," + part("early.jpg", "\"date\":1000"));
		TestGeoLocation.writeJpeg(file(TRIP, "late.jpg"), exif("Canon", "EOS", 1.0, 2.0));
		TestGeoLocation.writeJpeg(file(TRIP, "early.jpg"), exif("Canon", "EOS", 3.0, 4.0));
		TestGeoLocation.writeJpeg(file(TRIP, "unlisted.jpg"), exif("Canon", "EOS", 5.0, 6.0));

		ReanalyzeResult result = reanalyze("/" + TRIP + "/", SharingFixture.ALICE);

		assertEquals("The unlisted file was looked at like every photograph of the album.", 3,
			result.getExamined());
		assertEquals(2, result.getFilled());
		assertEquals(Arrays.asList("late.jpg", "early.jpg"), names(sidecarOnDisk(TRIP)));
		assertEquals("And the album still shows it, analysed afresh.", 5.0,
			image(stored(TRIP), "unlisted.jpg").getLocation().getLatitude(), PRECISION);
	}

	// --- A tree. ---

	public void testAFolderOfFoldersIsWalkedIntoEveryAlbum() throws Exception {
		album(TRIP, part("a.jpg", ""));
		album(OTHER, part("b.jpg", "") + "," + part("c.jpg", "\"camera\":\"Kept\",\"location\":{\"latitude\":5,\"longitude\":6}"));
		TestGeoLocation.writeJpeg(file(TRIP, "a.jpg"), exif("Canon", "EOS", 1.0, 2.0));
		TestGeoLocation.writeJpeg(file(OTHER, "b.jpg"), exif("Nikon", "D750", 3.0, 4.0));
		TestGeoLocation.writeJpeg(file(OTHER, "c.jpg"), exif("Nikon", "D750", 7.0, 8.0));

		FakeResponse response = reanalyzeResponse("/Travel/", SharingFixture.CAROL);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		ReanalyzeResult result = ReanalyzeResult.readReanalyzeResult(reader(body(response)));

		assertEquals(3, result.getExamined());
		assertEquals(2, result.getFilled());
		assertEquals(2, result.getAlbums());
		assertFalse(result.isRunning());

		// The counts can be read back.
		FakeResponse progress = get("/Travel/", "reanalyze", SharingFixture.CAROL);
		assertEquals(body(progress), HttpServletResponse.SC_OK, progress.status());
		assertEquals(2, ReanalyzeResult.readReanalyzeResult(reader(body(progress))).getFilled());

		assertEquals(1.0, image(stored(TRIP), "a.jpg").getLocation().getLatitude(), PRECISION);
		assertEquals("Nikon D750", image(stored(OTHER), "b.jpg").getCamera());
		assertEquals(5.0, image(stored(OTHER), "c.jpg").getLocation().getLatitude(), PRECISION);
	}

	public void testALongRunAnswersWhatItHasAndGoesOn() throws Exception {
		album(TRIP, part("a.jpg", ""));
		TestGeoLocation.writeJpeg(file(TRIP, "a.jpg"), exif("Canon", "EOS", 1.0, 2.0));
		List<Runnable> queued = new ArrayList<>();
		servlet().reanalysis().useExecutor(queued::add);
		servlet().setReanalyzeWait(20);

		FakeResponse first = reanalyzeResponse("/Travel/", SharingFixture.ALICE);
		assertEquals(first.body(), HttpServletResponse.SC_ACCEPTED, first.status());
		ReanalyzeResult soFar = ReanalyzeResult.readReanalyzeResult(reader(first.body()));
		assertTrue(soFar.isRunning());
		assertEquals(0, soFar.getFilled());

		FakeResponse second = reanalyzeResponse("/Travel/", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_ACCEPTED, second.status());
		assertEquals("Asking again joins the run instead of starting a second one.", 1, queued.size());
		assertTrue(ReanalyzeResult.readReanalyzeResult(reader(body(get("/Travel/", "reanalyze",
			SharingFixture.ALICE)))).isRunning());

		queued.get(0).run();

		ReanalyzeResult done = ReanalyzeResult.readReanalyzeResult(reader(body(get("/Travel/", "reanalyze",
			SharingFixture.ALICE))));
		assertFalse(done.isRunning());
		assertEquals(1, done.getFilled());
		assertEquals(1, done.getExamined());
		assertEquals("Canon EOS", image(stored(TRIP), "a.jpg").getCamera());
	}

	public void testNothingReReadYetSaysSo() throws Exception {
		FakeResponse response = get("/" + SharingFixture.ZOO + "/", "reanalyze", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(ImageServlet.NO_REANALYSIS, errorMessage(response));
	}

	// --- Who may. ---

	public void testRights() throws Exception {
		String zoo = "/" + SharingFixture.ZOO + "/";
		String link = zooToken(Rights.VIEW, Rights.CONTRIBUTE);

		assertEquals("An editor may.", HttpServletResponse.SC_OK,
			reanalyzeResponse(zoo, SharingFixture.CAROL).status());
		assertEquals("The admin may.", HttpServletResponse.SC_OK,
			reanalyzeResponse(zoo, SharingFixture.ALICE).status());

		FakeResponse contributor = reanalyzeResponse(zoo, SharingFixture.BOB);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, contributor.status());
		assertEquals(ImageServlet.REANALYZE_REFUSED, errorMessage(contributor));

		FakeResponse viewer = reanalyzeResponse(zoo, SharingFixture.DAVE);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, viewer.status());
		assertEquals(ImageServlet.REANALYZE_REFUSED, errorMessage(viewer));

		FakeResponse anonymous = reanalyzeResponse(zoo, null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());

		FakeResponse share = reanalyzeResponse(zoo, link);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, share.status());
		assertEquals(ImageServlet.REANALYZE_SHARE_REFUSED, errorMessage(share));

		FakeResponse progress = get(zoo, "reanalyze", SharingFixture.DAVE);
		assertEquals("Reading the counts back needs the same right.", HttpServletResponse.SC_FORBIDDEN,
			progress.status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get(zoo, "reanalyze", link).status());
	}

	// --- Helpers. ---

	private ReanalyzeResult reanalyze(String pathInfo, String token) throws Exception {
		FakeResponse response = reanalyzeResponse(pathInfo, token);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return ReanalyzeResult.readReanalyzeResult(reader(body(response)));
	}

	private FakeResponse reanalyzeResponse(String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "reanalyze");
		return post(pathInfo, "{}", token, parameters);
	}

	/** One sidecar part of a 40x30 JPEG with the given further fields. */
	private static String part(String name, String more) {
		return "[\"ImagePart\",{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"width\":40,\"height\":30"
			+ (more.isEmpty() ? "" : "," + more) + "}]";
	}

	/** A member of a group, which the sidecar lists without a type tag. */
	private static String member(String name) {
		return "{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"width\":40,\"height\":30}";
	}

	/** Writes the sidecar of an album with the given parts, as an older build wrote it. */
	private void album(String path, String parts) throws Exception {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		Files.write(folder.resolve("index.json"),
			("[\"AlbumInfo\",{\"title\":\"" + folder.getFileName() + "\",\"parts\":[" + parts + "]}]")
				.getBytes(StandardCharsets.UTF_8));
	}

	private File file(String album, String name) {
		return _base.resolve(album).resolve(name).toFile();
	}

	/** The album as its sidecar on disk says, read by a fresh servlet. */
	private AlbumInfo stored(String path) throws Exception {
		restartServer();
		return album(get("/" + path + "/", "json", SharingFixture.ALICE));
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
			if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					if (member.getName().equals(name)) {
						return member;
					}
				}
			}
		}
		fail("No part '" + name + "' in the album.");
		return null;
	}

	/** The album as its sidecar file says, read without any server. */
	private AlbumInfo sidecarOnDisk(String path) throws Exception {
		return (AlbumInfo) de.haumacher.imageServer.shared.model.Resource.readResource(reader(
			Files.readString(_base.resolve(path).resolve("index.json"), StandardCharsets.UTF_8)));
	}

	/** The names of the plain parts of the given album, in their order. */
	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}

	private static List<String> entries(Path folder) throws Exception {
		List<String> result = new ArrayList<>();
		try (var files = Files.list(folder)) {
			files.map(p -> p.getFileName().toString()).sorted().forEach(result::add);
		}
		return result;
	}

	private static String json(AlbumInfo album) throws Exception {
		StringWriter out = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(out))) {
			album.writeTo(json);
		}
		return out.toString();
	}

	/**
	 * A big-endian TIFF block naming a camera and a northern, eastern position.
	 *
	 * <pre>
	 *  0  "MM", 42, IFD0 at 8
	 *  8  IFD0: Make, Model, GPS pointer (2 + 3 * 12 + 4 = 42 bytes)
	 * 50  the two strings, each NUL-terminated, padded to an even length
	 *  g  GPS IFD: 4 entries (54 bytes), then the 6 rationals (48 bytes)
	 * </pre>
	 */
	static byte[] exif(String make, String model, double latitude, double longitude) {
		byte[] makeBytes = (make + "\u0000").getBytes(StandardCharsets.US_ASCII);
		byte[] modelBytes = (model + "\u0000").getBytes(StandardCharsets.US_ASCII);
		int strings = 50;
		int modelAt = strings + makeBytes.length;
		int gpsIfd = modelAt + modelBytes.length;
		if (gpsIfd % 2 != 0) {
			gpsIfd++;
		}
		int data = gpsIfd + 2 + 4 * 12 + 4;

		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		out.writeBytes(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8 });
		writeShort(out, 3);
		ascii(out, 0x010F, makeBytes, strings);
		ascii(out, 0x0110, modelBytes, modelAt);
		entry(out, 0x8825, 4, 1, gpsIfd);
		writeInt(out, 0);
		out.writeBytes(makeBytes);
		out.writeBytes(modelBytes);
		while (out.size() < gpsIfd) {
			out.write(0);
		}
		writeShort(out, 4);
		letter(out, 0x0001, 'N');
		entry(out, 0x0002, 5, 3, data);
		letter(out, 0x0003, 'E');
		entry(out, 0x0004, 5, 3, data + 24);
		writeInt(out, 0);
		degrees(out, latitude);
		degrees(out, longitude);
		return out.toByteArray();
	}

	/** An ASCII entry: up to four bytes stand in the entry itself, a longer string is addressed. */
	private static void ascii(java.io.ByteArrayOutputStream out, int tag, byte[] value, int offset) {
		if (value.length > 4) {
			entry(out, tag, 2, value.length, offset);
			return;
		}
		writeShort(out, tag);
		writeShort(out, 2);
		writeInt(out, value.length);
		out.writeBytes(Arrays.copyOf(value, 4));
	}

	private static void entry(java.io.ByteArrayOutputStream out, int tag, int type, int count, int value) {
		writeShort(out, tag);
		writeShort(out, type);
		writeInt(out, count);
		writeInt(out, value);
	}

	private static void letter(java.io.ByteArrayOutputStream out, int tag, char letter) {
		writeShort(out, tag);
		writeShort(out, 2);
		writeInt(out, 2);
		out.write(letter);
		out.write(0);
		out.write(0);
		out.write(0);
	}

	/** Decimal degrees as the degrees/minutes/seconds rationals of one coordinate. */
	private static void degrees(java.io.ByteArrayOutputStream out, double value) {
		int deg = (int) Math.floor(value);
		double rest = (value - deg) * 60.0;
		int min = (int) Math.floor(rest);
		double sec = (rest - min) * 60.0;
		writeInt(out, deg);
		writeInt(out, 1);
		writeInt(out, min);
		writeInt(out, 1);
		writeInt(out, (int) Math.round(sec * 10000.0));
		writeInt(out, 10000);
	}

	private static void writeShort(java.io.ByteArrayOutputStream out, int value) {
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	private static void writeInt(java.io.ByteArrayOutputStream out, int value) {
		out.write((value >> 24) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}
}
