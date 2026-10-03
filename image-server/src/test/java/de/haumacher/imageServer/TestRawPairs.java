/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import junit.framework.TestCase;

/**
 * Test case for {@link RawPairs}, the rules by which a raw and the JPEG of its name are one
 * photograph, see issue #191.
 */
@SuppressWarnings("javadoc")
public class TestRawPairs extends TestCase {

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-pairs");
	}

	@Override
	protected void tearDown() throws Exception {
		try (java.util.stream.Stream<Path> files = Files.list(_dir)) {
			files.forEach(p -> p.toFile().delete());
		}
		Files.delete(_dir);
		super.tearDown();
	}

	public void testAStatedCompanionThatIsNoneIsAnsweredAsNone() throws Exception {
		File[] files = files("a.jpg", "b.jpg", "b.cr2");
		AlbumInfo album = AlbumInfo.create()
			.addPart(ImagePart.create().setName("a.jpg").setRaw("../x.cr2"))
			.addPart(ImagePart.create().setName("b.jpg").setRaw("gone.cr2"));
		RawPairs.Plan plan = RawPairs.reconcile(album, files, file -> {
			fail("Nothing to analyse.");
			return null;
		});
		assertEquals("", part(album, 0).getRaw());
		// The real companion of b.jpg is found by its name instead.
		assertEquals("b.cr2", part(album, 1).getRaw());
		assertTrue(plan.skips("b.cr2"));
		assertFalse(plan.skips("a.jpg"));
	}

	public void testARawOfItsOwnStaysAPhotograph() throws Exception {
		File[] files = files("a.jpg", "b.dng");
		AlbumInfo album = AlbumInfo.create().addPart(ImagePart.create().setName("a.jpg"));
		RawPairs.Plan plan = RawPairs.reconcile(album, files, file -> null);
		assertFalse(plan.skips("b.dng"));
		assertEquals("", part(album, 0).getRaw());
	}

	public void testTwoNewFilesArePlannedAsOne() throws Exception {
		RawPairs.Plan plan = RawPairs.reconcile(AlbumInfo.create(), files("IMG_1.JPG", "IMG_1.CR2"), file -> null);
		assertTrue(plan.skips("IMG_1.CR2"));
		assertFalse(plan.skips("IMG_1.JPG"));
		assertEquals("IMG_1.CR2", plan.rawFor("IMG_1.JPG"));
	}

	public void testOneJpegTakesOneRaw() throws Exception {
		RawPairs.Plan plan = RawPairs.reconcile(AlbumInfo.create(), files("IMG_1.JPG", "IMG_1.CR2", "IMG_1.DNG"),
			file -> null);
		assertEquals("IMG_1.CR2", plan.rawFor("IMG_1.JPG"));
		assertTrue(plan.skips("IMG_1.CR2"));
		assertFalse("The second raw is a photograph of its own.", plan.skips("IMG_1.DNG"));
	}

	public void testAPhotographLeavingKeepsItsRawAsThePhotograph() {
		AlbumInfo album = AlbumInfo.create()
			.addPart(ImagePart.create().setName("IMG_1.JPG").setRaw("IMG_1.CR2").setRating(2))
			.addPart(ImagePart.create().setName("IMG_2.JPG").setRaw("IMG_2.CR2"))
			.addPart(ImagePart.create().setName("IMG_3.JPG"))
			.setIndexPicture(ThumbnailInfo.create().setImage("IMG_1.JPG"));
		de.haumacher.imageServer.shared.util.UpdateTransient.updateTransient(album);

		// The JPEG of IMG_1 was a duplicate of the space: the photograph is its raw now, edits kept.
		RawPairs.fileLeft(album, "IMG_1.JPG");
		assertEquals("IMG_1.CR2", part(album, 0).getName());
		assertEquals("", part(album, 0).getRaw());
		assertEquals(2, part(album, 0).getRating());
		assertEquals("IMG_1.CR2", album.getIndexPicture().getImage());

		// The raw of IMG_2 was the duplicate: the photograph stays, without a companion.
		RawPairs.fileLeft(album, "IMG_2.CR2");
		assertEquals("IMG_2.JPG", part(album, 1).getName());
		assertEquals("", part(album, 1).getRaw());

		// A photograph without a raw leaves the album.
		RawPairs.fileLeft(album, "IMG_3.JPG");
		assertEquals(2, album.getParts().size());
	}

	public void testAMergeInsideAGroupLeavesTheGroupAsTheMoveDoes() throws Exception {
		File[] files = files("IMG_1.JPG", "IMG_1.CR2", "IMG_2.JPG");
		ImagePart raw = ImagePart.create().setName("IMG_1.CR2").setRating(1);
		ImagePart other = ImagePart.create().setName("IMG_2.JPG");
		AlbumInfo album = AlbumInfo.create().addPart(ImagePart.create().setName("IMG_1.JPG"))
			.addPart(ImageGroup.create().addImage(raw).addImage(other));
		RawPairs.reconcile(album, files, file -> null);
		assertEquals(2, album.getParts().size());
		assertEquals("IMG_1.CR2", part(album, 0).getRaw());
		assertEquals(1, part(album, 0).getRating());
		assertEquals("A group of one is no group.", "IMG_2.JPG", part(album, 1).getName());
	}

	private File[] files(String... names) throws Exception {
		File[] result = new File[names.length];
		for (int n = 0; n < names.length; n++) {
			Path file = _dir.resolve(names[n]);
			Files.write(file, new byte[] { 1 });
			result[n] = file.toFile();
		}
		return result;
	}

	private static ImagePart part(AlbumInfo album, int index) {
		return (ImagePart) album.getParts().get(index);
	}
}
