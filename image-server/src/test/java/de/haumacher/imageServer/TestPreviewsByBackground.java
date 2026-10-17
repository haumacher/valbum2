/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.cache.AnalysisRecord;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.coded.CodedPictures;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Test case for issue #256: a request for a generated file serves what the background made, or
 * answers "not there yet"; it never makes one and never looks at the original. Freshness is the
 * background's: an original whose size or modification time no longer is the recorded one has its
 * generated files made again, with no <code>*.source</code> record involved.
 *
 * <p>
 * The servlet runs as a server does, with a background ({@link de.haumacher.imageServer.cache.ResourceCache#setBackground(boolean)}),
 * whose work the tests drive by hand, see {@link #background(String)}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPreviewsByBackground extends SpaceTestCase {

	private static final String TOKEN = SharingFixture.ALICE;

	@Override
	protected ImageServlet servlet() throws Exception {
		ImageServlet result = super.servlet();
		result.cache().setBackground(true);
		return result;
	}

	/**
	 * A thumbnail asked for before the background made it answers "not there yet", writes nothing
	 * and decodes nothing; after the background's previews step it is the picture.
	 */
	public void testAMissingPreviewIsNotThereYetAndNothingIsMade() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Trip"));
		File photo = jpeg(folder.resolve("a.jpg"), Color.RED, 0);
		servlet();
		record("Library/Trip");

		long made = PreviewCache.made();
		long analyses = ImageData.analyses();
		FakeResponse answer = get("/Library/Trip/a.jpg", "tn", TOKEN);
		assertEquals(HttpServletResponse.SC_ACCEPTED, answer.status());
		assertEquals(ImageServlet.PREVIEW_PENDING, errorMessage(answer));
		assertNotNull("When to come back.", answer.header("Retry-After"));
		assertFalse("Nothing is made by a request.", PreviewCache.previewFile(photo).exists());
		assertEquals("Nothing decoded.", made, PreviewCache.made());
		assertEquals("Nothing analysed.", analyses, ImageData.analyses());
		assertEquals("Asked again: still nothing made.", HttpServletResponse.SC_ACCEPTED,
			get("/Library/Trip/a.jpg", "tn", TOKEN).status());
		assertEquals(made, PreviewCache.made());

		background("Library/Trip");
		answer = get("/Library/Trip/a.jpg", "tn", TOKEN);
		assertEquals(HttpServletResponse.SC_OK, answer.status());
		assertColor(answer.bodyBytes(), Color.RED);
	}

	/**
	 * What the background could not make is answered as the failure it is, not as "not there yet":
	 * coming back would not help.
	 */
	public void testWhatTheBackgroundCouldNotMakeIsAFailure() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Odd"));
		jpeg(folder.resolve("a.jpg"), Color.RED, 0);
		servlet();
		record("Library/Odd");
		// Recorded as readable, but its pixels are cut off before the previews step reads it.
		File photo = folder.resolve("a.jpg").toFile();
		Files.write(photo.toPath(), Arrays.copyOf(Files.readAllBytes(photo.toPath()), 200));
		new CatchUp.Previews(pipeline()).run(folder.toFile());
		assertNotNull("The background tried.", PreviewCache.failure(PreviewCache.previewFile(photo)));
		FakeResponse answer = get("/Library/Odd/a.jpg", "tn", TOKEN);
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, answer.status());
	}

	/**
	 * <code>cp -p</code> over an original: new contents, the size different and the old stamp set
	 * again — or the size the same and a stamp older than the preview. The background finds the
	 * change against its record and makes the preview again; there is no <code>.source</code> file.
	 */
	public void testAnOriginalReplacedInPlaceIsMadeAgain() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Copy"));
		File photo = jpeg(folder.resolve("x.jpg"), Color.RED, 0);
		servlet();
		background("Library/Copy");
		File preview = PreviewCache.previewFile(photo);
		assertColor(get("/Library/Copy/x.jpg", "tn", TOKEN).bodyBytes(), Color.RED);
		assertNoRecords(folder);

		// Another size, the very stamp of before.
		long before = photo.lastModified();
		jpeg(folder.resolve("x.jpg"), Color.BLUE, (int) photo.length() + 1000);
		assertTrue(photo.setLastModified(before));
		assertEquals("Nothing serves the change before the background saw it.", HttpServletResponse.SC_OK,
			get("/Library/Copy/x.jpg", "tn", TOKEN).status());
		background("Library/Copy");
		assertColor(get("/Library/Copy/x.jpg", "tn", TOKEN).bodyBytes(), Color.BLUE);

		// The same size, a stamp older than the preview.
		long size = photo.length();
		jpeg(folder.resolve("x.jpg"), Color.GREEN, (int) size);
		assertEquals(size, photo.length());
		assertTrue(photo.setLastModified(preview.lastModified() - 3_600_000));
		background("Library/Copy");
		assertColor(get("/Library/Copy/x.jpg", "tn", TOKEN).bodyBytes(), Color.GREEN);
		assertNoRecords(folder);
	}

	/** A file taken away and another one of its name put in its place gets its own preview. */
	public void testAFileOfTheNameOfAGoneOneGetsItsOwnPreview() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Gone"));
		File photo = jpeg(folder.resolve("x.jpg"), Color.RED, 0);
		jpeg(folder.resolve("y.jpg"), Color.RED, 0);
		servlet();
		background("Library/Gone");
		long stamp = photo.lastModified();
		Files.move(photo.toPath(), _base.resolve("x.jpg"));
		background("Library/Gone");
		assertFalse("Made of a file that is no more.", PreviewCache.previewFile(photo).exists());

		jpeg(folder.resolve("x.jpg"), Color.BLUE, 0);
		assertTrue(photo.setLastModified(stamp));
		background("Library/Gone");
		assertColor(get("/Library/Gone/x.jpg", "tn", TOKEN).bodyBytes(), Color.BLUE);
	}

	/**
	 * A library written by the build before issue #256: previews with the <code>.source</code>
	 * record of their original beside each (the very format and names that build wrote), with and
	 * without the analysis record of issue #253. The first background pass makes nothing again and
	 * leaves no record behind.
	 */
	public void testAnUpgradeMakesNothingAgainAndRemovesTheRecords() throws Exception {
		Path recorded = Files.createDirectories(_base.resolve("Library/Recorded"));
		Path fresh = Files.createDirectories(_base.resolve("Library/Fresh"));
		List<File> photos = new ArrayList<>();
		for (Path folder : new Path[] { recorded, fresh }) {
			photos.add(jpeg(folder.resolve("a.jpg"), Color.RED, 0));
			photos.add(jpeg(folder.resolve("b.jpg"), Color.GREEN, 0));
			Files.write(folder.resolve("index.json"), ("[\"AlbumInfo\",{\"title\":\"T\",\"parts\":["
				+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":160,\"height\":120,"
				+ "\"crop\":{\"x\":0.25,\"y\":0.25,\"w\":0.5,\"h\":0.5}}]]}]").getBytes(StandardCharsets.UTF_8));
		}
		servlet();
		// What the build before wrote: every preview, made, with its record.
		for (File photo : photos) {
			oldBuild(photo, PreviewCache.createPreview(photo));
		}
		for (File photo : new File[] { photos.get(0), photos.get(2) }) {
			oldBuild(photo, PreviewCache.createPreview(photo, new double[] { 0.25, 0.25, 0.5, 0.5 }));
		}
		// One folder was recorded by that build too, the other was not.
		record("Library/Recorded");
		assertTrue(AnalysisRecord.fileOf(recorded.toFile()).isFile());
		assertFalse(AnalysisRecord.fileOf(fresh.toFile()).isFile());

		Map<File, Long> stamps = new LinkedHashMap<>();
		try (Stream<Path> files = Files.walk(_base.resolve("Library"))) {
			files.filter(p -> p.getFileName().toString().startsWith(PreviewCache.PREVIEW_PREFIX)
				&& !p.getFileName().toString().endsWith(PreviewCache.SOURCE_SUFFIX))
				.forEach(p -> stamps.put(p.toFile(), Long.valueOf(p.toFile().lastModified())));
		}
		assertEquals(6, stamps.size());
		assertEquals(6, records().size());

		long made = PreviewCache.made();
		background("Library/Recorded");
		background("Library/Fresh");
		assertEquals("Nothing made again.", made, PreviewCache.made());
		for (Map.Entry<File, Long> entry : stamps.entrySet()) {
			assertEquals("Kept: " + entry.getKey(), entry.getValue().longValue(), entry.getKey().lastModified());
		}
		assertEquals("The records are gone.", new ArrayList<>(), records());
		assertEquals(HttpServletResponse.SC_OK, get("/Library/Fresh/b.jpg", "tn", TOKEN).status());
	}

	/** One kind of generated file a request may ask for. */
	private static final class Kind {
		final String _name;

		final String _path;

		final Map<String, String> _parameters;

		Kind(String name, String path, String... parameters) {
			_name = name;
			_path = path;
			_parameters = new HashMap<>();
			for (int n = 0; n < parameters.length; n += 2) {
				_parameters.put(parameters[n], parameters[n + 1]);
			}
		}
	}

	/**
	 * Every kind of generated file a request may ask for is made by the background: before, the
	 * request is answered "not there yet" and nothing is decoded; after, the file. The face crop is
	 * the background's too, see <code>TestCatchUp</code>, which runs with a detector.
	 */
	public void testEveryKindIsMadeByTheBackground() throws Exception {
		Path album = Files.createDirectories(_base.resolve("Library/Kinds"));
		jpeg(album.resolve("plain.jpg"), Color.RED, 0);
		Files.copy(Path.of("src/test/fixtures/video/clip.m4v"), album.resolve("clip.m4v"));
		boolean jxl = CodedPictures.unavailability("x.jxl") == null;
		if (jxl) {
			Files.copy(Path.of("src/test/fixtures/jxl/lossy.jxl"), album.resolve("coded.jxl"));
		}
		// A starred tile shows its picture large.
		Files.write(album.resolve("index.json"), ("[\"AlbumInfo\",{\"title\":\"Kinds\",\"starred\":true,"
			+ "\"indexPicture\":{\"image\":\"plain.jpg\",\"scale\":1.0}}]").getBytes(StandardCharsets.UTF_8));
		// A starred tile of a cropped picture shows its cut large.
		Path cut = Files.createDirectories(_base.resolve("Library/Cut"));
		jpeg(cut.resolve("cut.jpg"), Color.GREEN, 0);
		Files.write(cut.resolve("index.json"), ("[\"AlbumInfo\",{\"title\":\"Cut\",\"starred\":true,"
			+ "\"indexPicture\":{\"image\":\"cut.jpg\",\"scale\":1.0},\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"cut.jpg\",\"width\":1600,\"height\":1200,"
			+ "\"crop\":{\"x\":0.25,\"y\":0.25,\"w\":0.5,\"h\":0.5}}]]}]").getBytes(StandardCharsets.UTF_8));
		// A video shown by the poster its author chose, see issue #285.
		Path poster = Files.createDirectories(_base.resolve("Library/Poster"));
		Files.copy(Path.of("src/test/fixtures/video/clip.m4v"), poster.resolve("clip.m4v"));
		Files.write(poster.resolve("index.json"), ("[\"AlbumInfo\",{\"title\":\"Poster\","
			+ "\"indexPicture\":{\"image\":\"clip.m4v\",\"scale\":1.0},\"parts\":["
			+ "[\"ImagePart\",{\"kind\":\"VIDEO\",\"name\":\"clip.m4v\",\"poster\":{\"millis\":500}}]]}]")
			.getBytes(StandardCharsets.UTF_8));

		List<Kind> kinds = new ArrayList<>(Arrays.asList(
			new Kind("preview", "/Library/Kinds/plain.jpg", "type", "tn"),
			new Kind("cut preview", "/Library/Cut/cut.jpg", "type", "tn", "crop", "0.25,0.25,0.5,0.5"),
			new Kind("large preview", "/Library/Kinds/plain.jpg", "type", "tn", "size", "large"),
			new Kind("large cut preview", "/Library/Cut/cut.jpg", "type", "tn", "size", "large", "crop",
				"0.25,0.25,0.5,0.5"),
			new Kind("video poster", "/Library/Kinds/clip.m4v", "type", "tn"),
			new Kind("chosen video poster", "/Library/Poster/clip.m4v", "type", "tn", "poster", "500")));
		if (jxl) {
			kinds.add(new Kind("preview of a coded picture", "/Library/Kinds/coded.jxl", "type", "tn"));
			kinds.add(new Kind("display rendition", "/Library/Kinds/coded.jxl", "type", "display"));
		} else {
			System.out.println("No display rendition in " + getName() + ": " + CodedPictures.unavailability("x.jxl"));
		}

		servlet();
		record("Library/Kinds");
		record("Library/Cut");
		record("Library/Poster");
		long made = PreviewCache.made();
		long analyses = ImageData.analyses();
		for (Kind kind : kinds) {
			assertEquals("Not there yet: " + kind._name, HttpServletResponse.SC_ACCEPTED,
				getWithParameters(kind._path, TOKEN, kind._parameters).status());
		}
		assertEquals("No request decoded anything.", made, PreviewCache.made());
		assertEquals(analyses, ImageData.analyses());

		background("Library/Kinds");
		background("Library/Cut");
		background("Library/Poster");
		for (Kind kind : kinds) {
			FakeResponse answer = getWithParameters(kind._path, TOKEN, kind._parameters);
			assertEquals("Made by the background: " + kind._name, HttpServletResponse.SC_OK, answer.status());
			assertNotNull("A picture: " + kind._name, ImageIO.read(new ByteArrayInputStream(answer.bodyBytes())));
		}
		long all = PreviewCache.made();
		for (Kind kind : kinds) {
			getWithParameters(kind._path, TOKEN, kind._parameters);
		}
		assertEquals("Served, never made by a request.", all, PreviewCache.made());
	}

	// --- Helpers. ---

	private FolderPipeline pipeline() throws Exception {
		return servlet().index().pipeline();
	}

	/** The analysis of the given folder, as the background runs it. */
	private void record(String path) throws Exception {
		pipeline().process(_base.resolve(path).toFile(), FolderPipeline.Stage.RECORD);
		assertNotNull("Recorded.", AnalysisRecord.read(_base.resolve(path).toFile()));
	}

	/** What the background of a running server does for the given folder: its analysis, previews and tile. */
	private void background(String path) throws Exception {
		record(path);
		File folder = _base.resolve(path).toFile();
		assertEquals(FolderPipeline.Outcome.DONE, new CatchUp.Previews(pipeline()).run(folder));
		assertEquals(FolderPipeline.Outcome.DONE, new CatchUp.Cover(pipeline(), _base).run(folder));
	}

	/** Writes the record the build of issue #235 wrote beside the given generated file. */
	private static void oldBuild(File original, File generated) throws Exception {
		Files.write(new File(generated.getParentFile(), generated.getName() + PreviewCache.SOURCE_SUFFIX).toPath(),
			("{\"size\":" + original.length() + ",\"modified\":" + original.lastModified() + "}")
				.getBytes(StandardCharsets.UTF_8));
	}

	private List<String> records() throws Exception {
		List<String> result = new ArrayList<>();
		try (Stream<Path> files = Files.walk(_base)) {
			files.filter(p -> p.getFileName().toString().endsWith(PreviewCache.SOURCE_SUFFIX))
				.forEach(p -> result.add(_base.relativize(p).toString()));
		}
		return result;
	}

	private static void assertNoRecords(Path folder) throws Exception {
		try (Stream<Path> files = Files.walk(folder)) {
			assertFalse("No record of the original is written.",
				files.anyMatch(p -> p.getFileName().toString().endsWith(PreviewCache.SOURCE_SUFFIX)));
		}
	}

	/**
	 * Writes a 1600x1200 JPEG of the given colour, padded with zeros behind its end to the given
	 * size where that is not 0 (a reader stops at the end of the image).
	 */
	private static File jpeg(Path file, Color color, int size) throws Exception {
		BufferedImage image = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, "jpg", out));
		byte[] bytes = out.toByteArray();
		if (size > 0) {
			assertTrue("Fits: " + bytes.length, bytes.length <= size);
			bytes = Arrays.copyOf(bytes, size);
		}
		Files.write(file, bytes);
		return file.toFile();
	}

	private static void assertColor(byte[] picture, Color expected) throws Exception {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(picture));
		assertNotNull("A picture.", image);
		Color c = new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
		boolean same = Math.abs(c.getRed() - expected.getRed()) < 40
			&& Math.abs(c.getGreen() - expected.getGreen()) < 40
			&& Math.abs(c.getBlue() - expected.getBlue()) < 40;
		assertTrue("Expected " + expected + " but got " + c, same);
	}
}
