/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the camera an image was taken with, see issue #78.
 *
 * <p>
 * The label is read when the image is analysed and stored in the sidecar like the date, so this
 * also pins what happens to an album written before the field existed.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestCamera extends TestCase {

	/** EXIF <code>Make</code>. */
	private static final int TAG_MAKE = 0x010F;

	/** EXIF <code>Model</code>. */
	private static final int TAG_MODEL = 0x0110;

	/** EXIF <code>Orientation</code>, an IFD0 tag that is not a camera. */
	private static final int TAG_ORIENTATION = 0x0112;

	private static final String ALBUM = "2005-08-24 Trip";

	private Path _base;

	private File _album;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-camera");
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

	/** The rule that turns a make and a model into one label. */
	public void testLabelRule() {
		assertEquals("A make the model repeats is said once.",
			"Canon EOS 5D", ImageData.cameraLabel("Canon", "Canon EOS 5D"));
		assertEquals("A phone says what it is the same way.",
			"SAMSUNG SM-G991B", ImageData.cameraLabel("SAMSUNG", "SM-G991B"));
		assertEquals("A make the model does not repeat literally stays in front of it.",
			"NIKON CORPORATION NIKON D750", ImageData.cameraLabel("NIKON CORPORATION", "NIKON D750"));
		assertEquals("Only the make.", "Canon", ImageData.cameraLabel("Canon", ""));
		assertEquals("Only the model.", "EOS 5D", ImageData.cameraLabel("", "EOS 5D"));
		assertEquals("Neither.", "", ImageData.cameraLabel("", ""));
		assertEquals("Neither, and nothing at all.", "", ImageData.cameraLabel(null, null));

		assertEquals("EXIF padding is not part of the label.",
			"Canon EOS 5D", ImageData.cameraLabel("  Canon  ", "  Canon EOS 5D  "));
		assertEquals("Runs of blanks collapse.",
			"Canon EOS 5D", ImageData.cameraLabel("Canon", "Canon  EOS   5D"));
		assertEquals("The repeated make is matched whatever its case.",
			"canon EOS 5D", ImageData.cameraLabel("Canon", "canon EOS 5D"));
		assertEquals("The make must be repeated as a whole word.",
			"Can CanonEOS", ImageData.cameraLabel("Can", "CanonEOS"));
		assertEquals("A model that is exactly the make is said once.",
			"Canon", ImageData.cameraLabel("Canon", "Canon"));
	}

	/** What the EXIF data of a photo says, as the analysis reads it. */
	public void testAnalyzeReadsTheExifCamera() throws Exception {
		assertEquals("Canon EOS 5D", analyzed("canon.jpg", exif(TAG_MAKE, "Canon", TAG_MODEL, "Canon EOS 5D")));
		assertEquals("SAMSUNG SM-G991B",
			analyzed("phone.jpg", exif(TAG_MAKE, "SAMSUNG", TAG_MODEL, "SM-G991B")));
		assertEquals("NIKON CORPORATION NIKON D750",
			analyzed("nikon.jpg", exif(TAG_MAKE, "NIKON CORPORATION", TAG_MODEL, "NIKON D750")));
		assertEquals("Only a make.", "Canon", analyzed("make.jpg", exif(TAG_MAKE, "Canon")));
		assertEquals("Only a model.", "EOS 5D", analyzed("model.jpg", exif(TAG_MODEL, "EOS 5D")));
		assertEquals("EXIF data that names no camera.", "",
			analyzed("orientation.jpg", exif(TAG_ORIENTATION, null)));
		assertEquals("No EXIF data at all.", "", analyzed("plain.jpg", null));
	}

	/** A video whose container names no camera carries no label, and nothing fails over it. */
	public void testVideoWithoutCamera() throws Exception {
		File fixture = new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");
		assertTrue("Missing fixture: " + fixture.getAbsolutePath(), fixture.exists());
		File video = new File(_album, "video.mp4");
		Files.copy(fixture.toPath(), video.toPath());

		ImagePart part = ImageData.analyze(AlbumInfo.create(), video);
		assertEquals("", part.getCamera());
	}

	/** An album analysed afresh carries the camera in what the server answers and writes. */
	public void testFreshAlbumCarriesTheCamera() throws Exception {
		writeJpeg(new File(_album, "canon.jpg"), exif(TAG_MAKE, "Canon", TAG_MODEL, "Canon EOS 5D"));

		AlbumInfo album = album();
		assertEquals("Canon EOS 5D", camera(album, "canon.jpg"));

		// What a client stores back keeps the camera: it is a statement about the file, not a
		// derived field that is cleared before a write.
		String body = json(album);
		assertTrue(body, body.contains("\"camera\":\"Canon EOS 5D\""));
		assertEquals(HttpServletResponse.SC_OK, put(body).status());
		String stored = new String(Files.readAllBytes(_album.toPath().resolve("index.json")),
			StandardCharsets.UTF_8);
		assertTrue(stored, stored.contains("\"camera\":\"Canon EOS 5D\""));
	}

	/**
	 * A sidecar written before this field existed is left exactly as it is: its parts are not
	 * analysed again, so they simply have no camera.
	 */
	public void testSidecarWithoutTheFieldIsLeftAlone() throws Exception {
		writeJpeg(new File(_album, "canon.jpg"), exif(TAG_MAKE, "Canon", TAG_MODEL, "Canon EOS 5D"));
		String index = "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[[\"ImagePart\",{\"name\":\"canon.jpg\","
			+ "\"kind\":\"IMAGE\",\"width\":40,\"height\":30,\"date\":1124884800000}]]}]";
		Path sidecar = _album.toPath().resolve("index.json");
		Files.write(sidecar, index.getBytes(StandardCharsets.UTF_8));

		AlbumInfo album = album();
		assertEquals("A part a sidecar lists is not analysed again.", "", camera(album, "canon.jpg"));
		assertEquals("Reading must not rewrite a sidecar.", index,
			new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8));
	}

	/** A sidecar that carries the field answers it, and a store keeps it. */
	public void testSidecarWithTheFieldRoundTrips() throws Exception {
		writeJpeg(new File(_album, "canon.jpg"), exif(TAG_MAKE, "Canon", TAG_MODEL, "Canon EOS 5D"));
		String index = "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[[\"ImagePart\",{\"name\":\"canon.jpg\","
			+ "\"kind\":\"IMAGE\",\"width\":40,\"height\":30,\"date\":1124884800000,"
			+ "\"camera\":\"Olympus OM-1\"}]]}]";
		Path sidecar = _album.toPath().resolve("index.json");
		Files.write(sidecar, index.getBytes(StandardCharsets.UTF_8));

		AlbumInfo album = album();
		assertEquals("The stored label is what is answered.", "Olympus OM-1", camera(album, "canon.jpg"));

		assertEquals(HttpServletResponse.SC_OK, put(json(album)).status());
		String stored = new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8);
		assertTrue(stored, stored.contains("\"camera\":\"Olympus OM-1\""));
	}

	/** The camera of the given image in the given album. */
	private static String camera(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return ((ImagePart) part).getCamera();
			}
		}
		fail("No part '" + name + "' in the album.");
		return null;
	}

	/** The camera the analysis reads from a JPEG written with the given EXIF data. */
	private String analyzed(String name, byte[] tiff) throws Exception {
		File file = new File(_album, name);
		writeJpeg(file, tiff);
		return ImageData.analyze(AlbumInfo.create(), file).getCamera();
	}

	private ImageServlet servlet() throws IOException {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
			_servlets.add(_servlet);
		}
		return _servlet;
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
		java.io.StringWriter out = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json =
			new de.haumacher.msgbuf.json.JsonWriter(new de.haumacher.msgbuf.server.io.WriterAdapter(out))) {
			album.writeTo(json);
		}
		return out.toString();
	}

	/** A tiny JPEG carrying the given EXIF data, or none at all. */
	private static void writeJpeg(File file, byte[] tiff) throws Exception {
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

	private static byte[] exif(int tag, String value) throws IOException {
		Map<Integer, String> tags = new LinkedHashMap<>();
		tags.put(Integer.valueOf(tag), value);
		return tiff(tags);
	}

	private static byte[] exif(int tag1, String value1, int tag2, String value2) throws IOException {
		Map<Integer, String> tags = new LinkedHashMap<>();
		tags.put(Integer.valueOf(tag1), value1);
		tags.put(Integer.valueOf(tag2), value2);
		return tiff(tags);
	}

	/**
	 * A big-endian TIFF block with one IFD0 carrying the given tags.
	 *
	 * <p>
	 * A <code>null</code> value makes a SHORT tag of value 1 (the identity orientation); a string
	 * makes an ASCII tag, in the entry itself while it fits and behind the directory otherwise.
	 * Hand-built because nothing on the class path writes EXIF, as in
	 * {@link TestPreviewCache} and {@link TestVideoDate}.
	 * </p>
	 */
	private static byte[] tiff(Map<Integer, String> tags) throws IOException {
		int count = tags.size();
		int dataOffset = 8 + 2 + 12 * count + 4;

		ByteArrayOutputStream entries = new ByteArrayOutputStream();
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		for (Map.Entry<Integer, String> tag : tags.entrySet()) {
			writeShort(entries, tag.getKey().intValue());
			String value = tag.getValue();
			if (value == null) {
				// SHORT, one value, in the entry itself, left aligned.
				writeShort(entries, 3);
				writeInt(entries, 1);
				writeShort(entries, 1);
				writeShort(entries, 0);
				continue;
			}
			byte[] ascii = value.getBytes(StandardCharsets.US_ASCII);
			int size = ascii.length + 1;
			writeShort(entries, 2);
			writeInt(entries, size);
			if (size <= 4) {
				entries.write(ascii);
				for (int n = ascii.length; n < 4; n++) {
					entries.write(0);
				}
			} else {
				writeInt(entries, dataOffset + data.size());
				data.write(ascii);
				data.write(0);
			}
		}

		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8 });
		writeShort(tiff, count);
		entries.writeTo(tiff);
		writeInt(tiff, 0);
		data.writeTo(tiff);
		return tiff.toByteArray();
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

}
