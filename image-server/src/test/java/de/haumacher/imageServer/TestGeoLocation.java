/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.GeoLocation;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
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
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for where a photo was taken, see issue #112.
 *
 * <p>
 * The position is read when the image is analysed and stored in the sidecar like the date and the
 * camera of issue #78, so this also pins what happens to an album written before the field existed.
 * The map a position is shown on is a property of the space, answered by <code>?type=auth</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestGeoLocation extends TestCase {

	private static final String ALBUM = "2005-08-24 Trip";

	/** How exact a position has to come back: a millionth of a degree, about ten centimetres. */
	private static final double PRECISION = 1e-6;

	private Path _base;

	private File _album;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-geo");
		_album = new File(_base.toFile(), ALBUM);
		assertTrue(_album.mkdirs());
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		_servlet = null;
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	/** What the GPS tags of a photo say, as the analysis reads them. */
	public void testAnalyzeReadsTheGpsPosition() throws Exception {
		GeoLocation north = analyzed("north.jpg", gps("N", 48, 7, 24.4416, "E", 8, 39, 15.5556));
		assertNotNull("A photo with a GPS IFD carries its position.", north);
		assertEquals(48.123456, north.getLatitude(), PRECISION);
		assertEquals(8.654321, north.getLongitude(), PRECISION);
	}

	/** The reference letters are what makes a position southern and western. */
	public void testAnalyzeAppliesTheReferenceLetters() throws Exception {
		GeoLocation south = analyzed("south.jpg", gps("S", 33, 55, 4.8, "W", 18, 25, 26.4));
		assertNotNull(south);
		assertEquals(-33.918000, south.getLatitude(), PRECISION);
		assertEquals(-18.424000, south.getLongitude(), PRECISION);
	}

	/**
	 * A position at the origin is no position, see issue #161.
	 *
	 * <p>
	 * A camera with geotagging switched on and no fix writes a GPS IFD of zeroes; taken for a place,
	 * that put every such photograph into the Gulf of Guinea.
	 * </p>
	 */
	public void testTheOriginIsNoPlace() throws Exception {
		assertNull("0/0 is what a camera without a fix writes.",
			analyzed("origin.jpg", gps("N", 0, 0, 0, "E", 0, 0, 0)));
	}

	/** A zero GPS IFD does not hide the position the XMP of the same file states. */
	public void testAZeroGpsIfdFallsBackToTheXmp() throws Exception {
		File plain = new File(_album, "plain.jpg");
		writeJpeg(plain, gps("N", 0, 0, 0, "E", 0, 0, 0));
		File file = new File(_album, "xmp.jpg");
		Xmp.writePacket(plain, file, xmpGps("48,7.40736N", "8,39.25926E"));

		GeoLocation position = ImageData.analyze(AlbumInfo.create(), file).getLocation();
		assertNotNull(position);
		assertEquals(48.123456, position.getLatitude(), PRECISION);
		assertEquals(8.654321, position.getLongitude(), PRECISION);
	}

	/** A file whose GPS IFD says nothing is asked for its XMP position, see issue #161. */
	public void testAnalyzeReadsTheXmpPosition() throws Exception {
		File plain = new File(_album, "plain.jpg");
		writeJpeg(plain, null);
		File file = new File(_album, "xmp.jpg");
		Xmp.writePacket(plain, file, xmpGps("33,55,4.8S", "18,25,26.4W"));

		GeoLocation position = ImageData.analyze(AlbumInfo.create(), file).getLocation();
		assertNotNull(position);
		assertEquals(-33.918, position.getLatitude(), PRECISION);
		assertEquals(-18.424, position.getLongitude(), PRECISION);
	}

	/** The GPS IFD wins where both say something. */
	public void testTheGpsIfdWinsOverTheXmp() throws Exception {
		File plain = new File(_album, "plain.jpg");
		writeJpeg(plain, gps("N", 48, 7, 24.4416, "E", 8, 39, 15.5556));
		File file = new File(_album, "xmp.jpg");
		Xmp.writePacket(plain, file, xmpGps("10,0.0S", "20,0.0W"));

		assertEquals(48.123456, ImageData.analyze(AlbumInfo.create(), file).getLocation().getLatitude(), PRECISION);
	}

	/** A malformed XMP position is no position, and the analysis does not fail over it. */
	public void testAMalformedXmpPositionIsNone() throws Exception {
		File plain = new File(_album, "plain.jpg");
		writeJpeg(plain, null);
		File file = new File(_album, "xmp.jpg");
		Xmp.writePacket(plain, file, xmpGps("somewhere", "8,39.25926E"));
		assertNull(ImageData.analyze(AlbumInfo.create(), file).getLocation());

		File broken = new File(_album, "broken.jpg");
		Xmp.writePacket(plain, broken, "<x:xmpmeta this is not XML");
		ImagePart analysed = ImageData.analyze(AlbumInfo.create(), broken);
		assertNull("A broken packet is no position.", analysed.getLocation());
	}

	/** The two XMP spellings of a coordinate, and what is refused. */
	public void testXmpCoordinates() throws Exception {
		GeoLocation minutes = ImageData.xmpPosition("48,7.40736N", "8,39.25926E");
		assertEquals(48.123456, minutes.getLatitude(), PRECISION);
		assertEquals(8.654321, minutes.getLongitude(), PRECISION);

		GeoLocation seconds = ImageData.xmpPosition("33,55,4.8S", "18,25,26.4W");
		assertEquals(-33.918, seconds.getLatitude(), PRECISION);
		assertEquals(-18.424, seconds.getLongitude(), PRECISION);

		assertNotNull("Lower case letters are read too.", ImageData.xmpPosition("1,0.5n", "2,0.5e"));
		assertNull("Zeroes are no position.", ImageData.xmpPosition("0,0.0N", "0,0.0E"));
		assertNull("A swapped pair.", ImageData.xmpPosition("8,39.25926E", "48,7.40736N"));
		assertNull("Beyond the pole.", ImageData.xmpPosition("91,0.0N", "8,0.0E"));
		assertNull("Sixty minutes.", ImageData.xmpPosition("48,60.0N", "8,0.0E"));
		assertNull("No letter.", ImageData.xmpPosition("48,7.4", "8,39.2E"));
		assertNull("Decimal degrees are not the XMP form.", ImageData.xmpPosition("48.123N", "8.654E"));
		assertNull(ImageData.xmpPosition(null, "8,39.2E"));
	}

	/**
	 * A <code>0/0</code> an older build stored is read as no position, and the next ordinary write
	 * omits it, see issue #161.
	 */
	public void testAStoredZeroIsReadAsNone() throws Exception {
		writeJpeg(new File(_album, "north.jpg"), null);
		String index = "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[[\"ImagePart\",{\"name\":\"north.jpg\","
			+ "\"kind\":\"IMAGE\",\"width\":40,\"height\":30,\"date\":1124884800000,"
			+ "\"location\":{\"latitude\":0.0,\"longitude\":0.0}}]]}]";
		Path sidecar = _album.toPath().resolve("index.json");
		Files.write(sidecar, index.getBytes(StandardCharsets.UTF_8));

		AlbumInfo album = album();
		assertNull("The zeroes of an older build are no position.", location(album, "north.jpg"));
		assertEquals("Reading must not rewrite a sidecar.", index,
			new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8));

		assertEquals(HttpServletResponse.SC_OK, put(json(album)).status());
		String written = new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8);
		assertFalse("The next write omits it: " + written, written.contains("\"location\""));
		assertNull(location(album(), "north.jpg"));
	}

	/** A photo whose EXIF data says nowhere carries no position. */
	public void testAnalyzeWithoutGps() throws Exception {
		assertNull("EXIF data without a GPS IFD.", analyzed("plain.jpg", exifWithoutGps()));
		assertNull("No EXIF data at all.", analyzed("bare.jpg", null));
	}

	/**
	 * What a QuickTime container states in ISO 6709, as the analysis reads it.
	 *
	 * <p>
	 * The one thing metadata-extractor hands out for a video that knows where it was recorded: an
	 * mp4's own <code>location</code> atom never reaches a directory of the library at all, which
	 * is why a video says where it was only when the container says so in Apple's spelling.
	 * </p>
	 */
	public void testQuickTimeIso6709() throws Exception {
		GeoLocation position = ImageData.iso6709("+48.123456+008.654321+123.456/");
		assertNotNull(position);
		assertEquals(48.123456, position.getLatitude(), PRECISION);
		assertEquals(8.654321, position.getLongitude(), PRECISION);

		GeoLocation south = ImageData.iso6709("-33.918861+018.423300/");
		assertNotNull(south);
		assertEquals(-33.918861, south.getLatitude(), PRECISION);
		assertEquals(18.4233, south.getLongitude(), PRECISION);

		GeoLocation origin = ImageData.iso6709("+00.0000+000.0000/");
		assertNotNull("The parser reads the origin; the analysis drops it (issue #161).", origin);
		assertEquals(0.0, origin.getLatitude(), PRECISION);
	}

	/**
	 * The degrees-and-minutes spelling of ISO 6709 is refused, not guessed at.
	 *
	 * <p>
	 * <code>+4807.41+00834.49/</code> looks exactly like decimal degrees and means something else;
	 * a wrong place on a map is worse than no place at all.
	 * </p>
	 */
	public void testIso6709ThatIsNotDecimalDegrees() throws Exception {
		assertNull("Degrees and minutes, not decimal degrees.",
			ImageData.iso6709("+4807.41+00834.49/"));
		assertNull("A longitude beyond the globe.", ImageData.iso6709("+48.12+1008.65/"));
		assertNull("Nothing at all.", ImageData.iso6709(null));
		assertNull("Not a position.", ImageData.iso6709("somewhere nice"));
		assertNull("No sign, so no ISO 6709.", ImageData.iso6709("48.123456,8.654321"));
	}

	/** A video whose container says nowhere carries no position, and nothing fails over it. */
	public void testVideoWithoutPosition() throws Exception {
		File fixture = new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");
		assertTrue("Missing fixture: " + fixture.getAbsolutePath(), fixture.exists());
		File video = new File(_album, "video.mp4");
		Files.copy(fixture.toPath(), video.toPath());

		assertNull(ImageData.analyze(AlbumInfo.create(), video).getLocation());
	}

	/** An album analysed afresh carries the position in what the server answers and writes. */
	public void testFreshAlbumCarriesTheLocation() throws Exception {
		writeJpeg(new File(_album, "north.jpg"), gps("N", 48, 7, 24.4416, "E", 8, 39, 15.5556));

		AlbumInfo album = album();
		GeoLocation location = location(album, "north.jpg");
		assertNotNull(location);
		assertEquals(48.123456, location.getLatitude(), PRECISION);

		// What a client stores back keeps the position: it is a statement about the file, not a
		// derived field that is cleared before a write.
		String body = json(album);
		assertTrue(body, body.contains("\"location\":"));
		assertEquals(HttpServletResponse.SC_OK, put(body).status());

		String stored = new String(Files.readAllBytes(_album.toPath().resolve("index.json")),
			StandardCharsets.UTF_8);
		assertTrue(stored, stored.contains("\"location\":"));

		// Read again: the round trip read -> write -> read keeps what the file said.
		GeoLocation again = location(album(), "north.jpg");
		assertNotNull(again);
		assertEquals(48.123456, again.getLatitude(), PRECISION);
		assertEquals(8.654321, again.getLongitude(), PRECISION);
	}

	/**
	 * A sidecar written before this field existed is left exactly as it is: its parts are not
	 * analysed again, so they simply have no position.
	 */
	public void testSidecarWithoutTheFieldIsLeftAlone() throws Exception {
		writeJpeg(new File(_album, "north.jpg"), gps("N", 48, 7, 24.4416, "E", 8, 39, 15.5556));
		String index = "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[[\"ImagePart\",{\"name\":\"north.jpg\","
			+ "\"kind\":\"IMAGE\",\"width\":40,\"height\":30,\"date\":1124884800000}]]}]";
		Path sidecar = _album.toPath().resolve("index.json");
		Files.write(sidecar, index.getBytes(StandardCharsets.UTF_8));

		assertNull("A part a sidecar lists is not analysed again.", location(album(), "north.jpg"));
		assertEquals("Reading must not rewrite a sidecar.", index,
			new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8));
	}

	/** A sidecar that carries the field answers it, and a store keeps it. */
	public void testSidecarWithTheFieldRoundTrips() throws Exception {
		writeJpeg(new File(_album, "north.jpg"), gps("N", 48, 7, 24.4416, "E", 8, 39, 15.5556));
		String index = "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[[\"ImagePart\",{\"name\":\"north.jpg\","
			+ "\"kind\":\"IMAGE\",\"width\":40,\"height\":30,\"date\":1124884800000,"
			+ "\"location\":{\"latitude\":-33.918,\"longitude\":18.424}}]]}]";
		Path sidecar = _album.toPath().resolve("index.json");
		Files.write(sidecar, index.getBytes(StandardCharsets.UTF_8));

		AlbumInfo album = album();
		GeoLocation stored = location(album, "north.jpg");
		assertNotNull("The stored position is what is answered, not the file's.", stored);
		assertEquals(-33.918, stored.getLatitude(), PRECISION);
		assertEquals(18.424, stored.getLongitude(), PRECISION);

		assertEquals(HttpServletResponse.SC_OK, put(json(album)).status());
		String written = new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8);
		assertTrue(written, written.contains("\"latitude\":-33.918"));
		assertTrue(written, written.contains("\"longitude\":18.424"));
	}

	/** A space that names no map is answered the default one. */
	public void testDefaultMapUrl() throws Exception {
		assertEquals(SpaceStore.DEFAULT_MAP_URL, authInfo().getMapUrl());
		assertEquals("https://www.google.com/maps?q={lat},{lon}", authInfo().getMapUrl());
	}

	/** A space that names a map of its own is answered that one. */
	public void testSpaceMapUrl() throws Exception {
		String osm = "https://www.openstreetmap.org/?mlat={lat}&mlon={lon}#map=15/{lat}/{lon}";
		writeSpaceJson("{\"version\":1,\"name\":\"Alice\",\"anonymous\":\"public\",\"mapUrl\":\"" + osm + "\"}");

		assertEquals(osm, authInfo().getMapUrl());
	}

	/** A <code>space.json</code> that says nothing about a map reads exactly as it did. */
	public void testSpaceJsonWithoutTheFieldReadsUnchanged() throws Exception {
		writeSpaceJson("{\"version\":1,\"name\":\"Alice\",\"anonymous\":\"public\"}");

		SpaceStore.Config config = SpaceStore.load(_base, "folder");
		assertEquals("Alice", config.getName());
		assertEquals(SpaceStore.ANONYMOUS_PUBLIC, config.getAnonymous());
		assertTrue(config.isAnonymousAllowed());
		assertEquals("A space that names no map has the default one.",
			SpaceStore.DEFAULT_MAP_URL, config.getMapUrl());
	}

	/** A blank map template is no template: the default stands. */
	public void testBlankMapUrlIsTheDefault() throws Exception {
		writeSpaceJson("{\"version\":1,\"name\":\"Alice\",\"mapUrl\":\"   \"}");

		assertEquals(SpaceStore.DEFAULT_MAP_URL, SpaceStore.load(_base, "folder").getMapUrl());
	}

	// --- Helpers. ---

	/** An XMP packet stating the given GPS coordinates, see issue #161. */
	private static String xmpGps(String latitude, String longitude) throws Exception {
		com.adobe.internal.xmp.XMPMeta meta = com.adobe.internal.xmp.XMPMetaFactory.create();
		meta.setProperty(ImageData.XMP_EXIF, "GPSLatitude", latitude);
		meta.setProperty(ImageData.XMP_EXIF, "GPSLongitude", longitude);
		return com.adobe.internal.xmp.XMPMetaFactory.serializeToString(meta,
			new com.adobe.internal.xmp.options.SerializeOptions().setUseCompactFormat(true));
	}

	/** The position of the given image in the given album. */
	private static GeoLocation location(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return ((ImagePart) part).getLocation();
			}
		}
		fail("No part '" + name + "' in the album.");
		return null;
	}

	/** The position the analysis reads from a JPEG written with the given EXIF data. */
	private GeoLocation analyzed(String name, byte[] tiff) throws Exception {
		File file = new File(_album, name);
		writeJpeg(file, tiff);
		return ImageData.analyze(AlbumInfo.create(), file).getLocation();
	}

	private void writeSpaceJson(String contents) throws IOException {
		Path file = SpaceStore.file(_base);
		Files.createDirectories(file.getParent());
		Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
	}

	private ImageServlet servlet() throws IOException {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
			_servlets.add(_servlet);
		}
		return _servlet;
	}

	private AuthInfo authInfo() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "auth");
		FakeResponse response = new FakeResponse();
		servlet().doGet(request("/", null, new byte[0], Map.of(), parameters), response.response());
		assertEquals("Unexpected answer: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return AuthInfo.readAuthInfo(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
	}

	private AlbumInfo album() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		servlet().doGet(request("/" + ALBUM + "/", null, new byte[0], Map.of(), parameters),
			response.response());
		assertEquals("Unexpected answer: " + response.body(), HttpServletResponse.SC_OK, response.status());
		Resource resource = Resource.readResource(
			new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private FakeResponse put(String body) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet().doPut(request("/" + ALBUM + "/", "application/json",
			body.getBytes(StandardCharsets.UTF_8), Map.of(), Collections.emptyMap()), response.response());
		return response;
	}

	private static String json(AlbumInfo album) throws IOException {
		StringWriter out = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(out))) {
			album.writeTo(json);
		}
		return out.toString();
	}

	/** A tiny JPEG carrying the given EXIF data, or none at all. */
	static void writeJpeg(File file, byte[] tiff) throws Exception {
		BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(200, 40, 40));
		g.fillRect(0, 0, 40, 30);
		g.dispose();

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		assertTrue("Cannot write a JPEG.", ImageIO.write(image, "jpg", buffer));
		byte[] jpeg = buffer.toByteArray();
		if (tiff == null) {
			Files.write(file.toPath(), jpeg);
			return;
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		// APP1 with the Exif preamble; the length counts itself, the preamble and the TIFF block.
		int length = 2 + 6 + tiff.length;
		out.write(0xFF);
		out.write(0xE1);
		out.write((length >> 8) & 0xFF);
		out.write(length & 0xFF);
		out.write(new byte[] { 'E', 'x', 'i', 'f', 0, 0 });
		out.write(tiff);
		out.write(jpeg, 2, jpeg.length - 2);

		Files.write(file.toPath(), out.toByteArray());
	}

	/**
	 * A big-endian TIFF block whose IFD0 carries one orientation tag and nothing else.
	 *
	 * <p>
	 * EXIF data that says something, but nothing about a place.
	 * </p>
	 */
	private static byte[] exifWithoutGps() throws IOException {
		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8 });
		writeShort(tiff, 1);
		// Orientation, SHORT, one value, the identity, left aligned in the entry itself.
		writeShort(tiff, 0x0112);
		writeShort(tiff, 3);
		writeInt(tiff, 1);
		writeShort(tiff, 1);
		writeShort(tiff, 0);
		writeInt(tiff, 0);
		return tiff.toByteArray();
	}

	/**
	 * A big-endian TIFF block carrying a GPS IFD with the given position.
	 *
	 * <p>
	 * Hand-built, because nothing on the class path writes EXIF and a fixture must never be a real
	 * photo — the same way {@link TestCamera} builds its make and model. The layout is fixed, so
	 * every offset below is a constant:
	 * </p>
	 *
	 * <pre>
	 *  0  "MM", 0x002A, 8              the big-endian TIFF header, IFD0 at 8
	 *  8  IFD0: 1 entry, next = 0      the entry is tag 0x8825, LONG, the GPS IFD at 26
	 * 26  GPS IFD: 4 entries, next = 0 latitude reference and value, longitude reference and value
	 * 80  6 RATIONALs                  degrees, minutes and seconds of the latitude, then of the
	 *                                  longitude; each one two big-endian 32-bit numbers
	 * </pre>
	 *
	 * <p>
	 * The reference letters ride inside their entries (two ASCII bytes fit into the four an entry
	 * holds); the three rationals of a coordinate are 24 bytes and therefore have to be addressed.
	 * </p>
	 *
	 * @param latRef
	 *        <code>"N"</code> or <code>"S"</code>, which is what makes the latitude signed.
	 * @param lonRef
	 *        <code>"E"</code> or <code>"W"</code>.
	 */
	private static byte[] gps(String latRef, int latDeg, int latMin, double latSec,
			String lonRef, int lonDeg, int lonMin, double lonSec) throws IOException {
		int gpsIfd = 8 + 2 + 12 + 4;
		int data = gpsIfd + 2 + 4 * 12 + 4;

		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8 });

		// IFD0 with the pointer to the GPS IFD and nothing else.
		writeShort(tiff, 1);
		writeShort(tiff, 0x8825);
		writeShort(tiff, 4);
		writeInt(tiff, 1);
		writeInt(tiff, gpsIfd);
		writeInt(tiff, 0);

		// The GPS IFD: reference, value, reference, value.
		writeShort(tiff, 4);
		writeAscii(tiff, 0x0001, latRef);
		writeCoordinate(tiff, 0x0002, data);
		writeAscii(tiff, 0x0003, lonRef);
		writeCoordinate(tiff, 0x0004, data + 24);
		writeInt(tiff, 0);

		writeDegrees(tiff, latDeg, latMin, latSec);
		writeDegrees(tiff, lonDeg, lonMin, lonSec);
		return tiff.toByteArray();
	}

	/** One ASCII entry of a single letter, which fits into the entry itself. */
	private static void writeAscii(ByteArrayOutputStream out, int tag, String letter) {
		writeShort(out, tag);
		writeShort(out, 2);
		writeInt(out, 2);
		out.write(letter.charAt(0));
		out.write(0);
		out.write(0);
		out.write(0);
	}

	/** One entry of three RATIONALs, which never fit into an entry and are addressed. */
	private static void writeCoordinate(ByteArrayOutputStream out, int tag, int offset) {
		writeShort(out, tag);
		writeShort(out, 5);
		writeInt(out, 3);
		writeInt(out, offset);
	}

	/** Degrees, minutes and seconds as the three RATIONALs of one coordinate. */
	private static void writeDegrees(ByteArrayOutputStream out, int degrees, int minutes, double seconds) {
		writeInt(out, degrees);
		writeInt(out, 1);
		writeInt(out, minutes);
		writeInt(out, 1);
		// A tenth of a millisecond of arc is finer than any camera says it, and exact in a
		// rational: the seconds are written as ten-thousandths.
		writeInt(out, (int) Math.round(seconds * 10000.0));
		writeInt(out, 10000);
	}

	private static void writeShort(ByteArrayOutputStream out, int value) {
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	private static void writeInt(ByteArrayOutputStream out, int value) {
		out.write((value >> 24) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	/** Probe: a stored position travels with the image on a move (#47), and the target's sidecar holds it. */
	public void testProbeAMoveCarriesThePositionAlong() throws Exception {
		writeJpeg(new File(_album, "north.jpg"), gps("N", 48, 7, 24.4416, "E", 8, 39, 15.5556));
		AlbumInfo album = album();
		assertNotNull(location(album, "north.jpg"));
		assertEquals(HttpServletResponse.SC_OK, put(json(album)).status());
		Path target = _base.resolve("Target");
		Files.createDirectories(target);
		Files.write(target.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"Target\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse response = new FakeResponse();
		servlet().doPost(request("/" + ALBUM + "/", "application/json",
			"{\"target\":\"Target\",\"names\":[{\"name\":\"north.jpg\"}]}".getBytes(StandardCharsets.UTF_8),
			Map.of(), parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());

		String moved = new String(Files.readAllBytes(target.resolve("index.json")), StandardCharsets.UTF_8);
		assertTrue("The position rides along in the target's sidecar: " + moved, moved.contains("\"location\""));
		assertTrue(moved.contains("\"latitude\":48.12"));
		String source = new String(Files.readAllBytes(_album.toPath().resolve("index.json")), StandardCharsets.UTF_8);
		assertFalse("The source has no part left that carries it.", source.contains("\"location\""));
	}

}
