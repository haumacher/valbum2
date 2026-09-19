/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Date;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Probe for the name date of issue #102, composed with the real fixture album.
 *
 * <p>
 * The table of {@link TestNameDate} says what the rule reads; this says what the rule is worth: a
 * file of the fixture album, copied byte for byte under a name that carries its recording time,
 * is dated by that name, while the same bytes under their old name keep the date they had. And a
 * photo that says its own recording time keeps it, whatever its name claims — the name is the last
 * word before the modification time, never the first.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestNameDateProbe extends TestCase {

	/** The fixture album, copied before anything is written. */
	private static final File FIXTURE = new File("src/test/fixtures/test-album");

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-name-date-probe");
		copy(FIXTURE.toPath(), _base);
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

	/**
	 * The fixture video under a name that carries a date is dated by it; the same bytes under
	 * their own name keep the modification time, exactly as before issue #102.
	 */
	public void testTheSameBytesUnderTwoNames() throws Exception {
		File album = new File(_base.toFile(), "2005-08-24 Blumen und Fliegen");
		File original = new File(album, "MVI_0450.mp4");
		assertTrue("Missing fixture video: " + original.getAbsolutePath(), original.exists());
		File named = new File(album, "VID_20240315_142233.mp4");
		Files.copy(original.toPath(), named.toPath(), StandardCopyOption.COPY_ATTRIBUTES);

		ImagePart byName = analyze(named);
		assertEquals(ImageKind.VIDEO, byName.getKind());
		assertEquals("The copy is dated by its name.",
			TestNameDate.local("2024-03-15T14:22:33").getTime(), byName.getDate());

		ImagePart byMtime = analyze(original);
		assertEquals("The fixture keeps the date it always had.", original.lastModified(), byMtime.getDate());
		assertFalse("The two names must really differ in date.", byName.getDate() == byMtime.getDate());
	}

	/**
	 * A photo that carries an EXIF recording time keeps it, even when its name says another date.
	 *
	 * <p>
	 * The fixture album's own photos carry no EXIF date the reader hands out, so the photo is
	 * written here the way {@link TestVideoDate} writes one: a JPEG whose only metadata is a
	 * <code>DateTimeOriginal</code>.
	 * </p>
	 */
	public void testExifBeatsTheName() throws Exception {
		File album = new File(_base.toFile(), "2005-08-24 Blumen und Fliegen");
		File photo = new File(album, "IMG_20240315_142233.jpg");
		TestVideoDate.writeJpegWithDateOriginal(photo, "2005:08:24 10:00:00");

		ImagePart part = analyze(photo);
		assertEquals("The file's own recording time wins over its name.",
			TestVideoDate.exifMillis("2005:08:24 10:00:00"), part.getDate());
	}

	/**
	 * A photo without any metadata date is dated by its name — a JPEG is the same case as a video
	 * here, which is why the step sits in the common chain and not in the video branch.
	 */
	public void testAPhotoWithoutExifTakesItsName() throws Exception {
		File album = new File(_base.toFile(), "generated");
		File plain = new File(album, "image-0.jpg");
		assertTrue("Missing fixture photo: " + plain.getAbsolutePath(), plain.exists());
		assertNull("The generated photos must carry no name date of their own.",
			ImageData.nameDate(plain.getName()));
		long mtimeDate = analyze(plain).getDate();
		assertEquals("Without EXIF and without a name date the mtime stands.",
			plain.lastModified(), mtimeDate);

		File named = new File(album, "PXL_20240315_142233123.jpg");
		Files.copy(plain.toPath(), named.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
		assertEquals("The name is read for a photo as well as for a video.",
			TestNameDate.local("2024-03-15T14:22:33").getTime(), analyze(named).getDate());
	}

	/** Every name of the shared table, read through the public function once more. */
	public void testTheTableIsTheRuleTheServerRuns() throws Exception {
		for (TestNameDate.Row row : TestNameDate.rows()) {
			Date read = ImageData.nameDate(row.getName());
			assertEquals(row.getName(), row.getDate() == null ? null : TestNameDate.local(row.getDate()), read);
		}
	}

	private static ImagePart analyze(File file) throws Exception {
		return ImageData.analyze(AlbumInfo.create(), file);
	}

	/** Copies the fixture tree, because a test never writes into the fixtures. */
	private static void copy(Path from, Path to) throws Exception {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path source : (Iterable<Path>) files::iterator) {
				Path target = to.resolve(from.relativize(source).toString());
				if (Files.isDirectory(source)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}

}
