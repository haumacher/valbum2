/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.PlaceInfo;
import de.haumacher.imageServer.shared.model.PlaceKind;
import de.haumacher.imageServer.shared.model.PlaceTag;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link ContentCache} and {@link ContentText}, see issue #257.
 */
@SuppressWarnings("javadoc")
public class TestContentCache extends TestCase {

	private static final String HASH_A = "a".repeat(64);

	private static final String HASH_B = "b".repeat(64);

	private static final String IMAGE = "fake@0000001/256/fp32/1";

	private static final String TEXT = ContentText.stamp(IMAGE);

	private Path _folder;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_folder = Files.createTempDirectory("valbum-content-cache");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_folder)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	private static float[] vector(int seed) {
		float[] result = new float[Embedder.DIMENSION];
		for (int n = 0; n < result.length; n++) {
			result[n] = (float) Math.sin(seed * 1000 + n);
		}
		return result;
	}

	private File folder() {
		return _folder.toFile();
	}

	private void fill() throws Exception {
		ContentCache.update(folder(), IMAGE, TEXT, cache -> {
			ContentCache.ImageKey a = new ContentCache.ImageKey(HASH_A, 0);
			ContentCache.ImageKey b = new ContentCache.ImageKey(HASH_B, 1500);
			cache.putImage(a, vector(1));
			cache.putImage(b, vector(2));
			cache.putText(HASH_A, vector(3));
			cache.putPhoto("a.jpg", new ContentCache.Photo(a, HASH_A));
			cache.putPhoto("copy of a.jpg", new ContentCache.Photo(a, HASH_A));
			cache.putPhoto("clip.mp4", new ContentCache.Photo(b, HASH_A));
		});
	}

	public void testReadWriteReadIsEqual() throws Exception {
		fill();
		ContentCache first = ContentCache.read(folder());
		assertEquals(IMAGE, first.getImageStamp());
		assertEquals(TEXT, first.getTextStamp());
		assertEquals(3, first.photos().size());
		assertTrue(Arrays.equals(vector(2), first.image(new ContentCache.ImageKey(HASH_B, 1500))));
		assertTrue(Arrays.equals(vector(3), first.text(HASH_A)));
		assertEquals(1, first.textCount());

		byte[] bytes = Files.readAllBytes(ContentCache.file(folder()).toPath());
		first.write(folder());
		assertTrue("Written again, the same bytes.",
			Arrays.equals(bytes, Files.readAllBytes(ContentCache.file(folder()).toPath())));
		assertEquals(first, ContentCache.read(folder()));
		assertEquals(first, ContentCache.parse(first.toBytes()));
		// Two image vectors, one text vector and three names: the size the class comment promises.
		int vector = 32 + Embedder.DIMENSION * 4;
		assertTrue("Compact: " + bytes.length, bytes.length < 3 * (vector + 8) + 3 * 96 + 200);
	}

	public void testAnotherStampIsNotRead() throws Exception {
		fill();
		ContentCache otherModel = ContentCache.read(folder(), "fake@0000002/256/fp32/1", TEXT);
		assertTrue(otherModel.imageKeys().isEmpty());
		assertTrue(otherModel.photos().isEmpty());

		ContentCache otherTemplate = ContentCache.read(folder(), IMAGE, IMAGE + "/text99");
		assertEquals("The pictures are kept for a new text template.", 2, otherTemplate.imageKeys().size());
		assertEquals(0, otherTemplate.textCount());
		assertTrue(otherTemplate.photos().isEmpty());
	}

	public void testAForeignOrBrokenFileIsIgnored() throws Exception {
		fill();
		Path file = ContentCache.file(folder()).toPath();
		byte[] good = Files.readAllBytes(file);

		for (byte[] bad : List.of("{\"version\":1}".getBytes(StandardCharsets.UTF_8), new byte[0],
			Arrays.copyOf(good, good.length - 7), flipped(good, good.length / 2), version(good, 99))) {
			Files.write(file, bad);
			ContentCache read = ContentCache.read(folder());
			assertEquals("", read.getImageStamp());
			assertTrue(read.photos().isEmpty());
			// And written anew by the next change, never fatal.
			ContentCache.update(folder(), IMAGE, TEXT,
				cache -> cache.putImage(new ContentCache.ImageKey(HASH_A, 0), vector(1)));
			assertEquals(IMAGE, ContentCache.read(folder()).getImageStamp());
		}
	}

	private static byte[] flipped(byte[] contents, int index) {
		byte[] result = contents.clone();
		result[index] ^= 0x55;
		return result;
	}

	private static byte[] version(byte[] contents, int version) {
		byte[] result = contents.clone();
		result[4] = (byte) version;
		return result;
	}

	public void testRetainKeepsWhatTheAlbumHolds() throws Exception {
		fill();
		ContentCache.update(folder(), IMAGE, TEXT, cache -> cache.retain(List.of("copy of a.jpg"), List.of(HASH_A)));
		ContentCache read = ContentCache.read(folder());
		assertEquals(List.of("copy of a.jpg"), List.copyOf(read.photos().keySet()));
		assertEquals(1, read.imageKeys().size());
		assertEquals(1, read.textCount());
	}

	public void testCarryTakesEveryPosterOfTheContents() throws Exception {
		fill();
		Path target = Files.createDirectories(_folder.resolve("target"));
		ContentCache.carry(folder(), HASH_B, target.toFile());
		ContentCache carried = ContentCache.read(target.toFile());
		assertEquals(IMAGE, carried.getImageStamp());
		assertTrue(Arrays.equals(vector(2), carried.image(new ContentCache.ImageKey(HASH_B, 1500))));
		assertNull(carried.image(new ContentCache.ImageKey(HASH_A, 0)));

		ContentCache.carry(folder(), "c".repeat(64), target.toFile());
		assertEquals("Nothing of other contents.", 1, ContentCache.read(target.toFile()).imageKeys().size());
	}

	public void testTheTextNamesAlbumPlacesMonthDescriptionAndLabels() {
		AlbumInfo album = AlbumInfo.create().setTitle("2025-10 Italien");
		ImagePart image = ImagePart.create().setName("p.jpg").setDate(1760000000000L).setComment("Piazza; at noon")
			.addLabel(LabelName.create().setName("holiday")).addLabel(LabelName.create().setName("architecture"))
			.setPlaces(PlaceInfo.create()
				.addTag(PlaceTag.create().setName("Italy").setKind(PlaceKind.COUNTRY))
				.addTag(PlaceTag.create().setName("Tuscany").setKind(PlaceKind.ADM_1))
				.addTag(PlaceTag.create().setName("Pisa").setKind(PlaceKind.PLACE))
				.addTag(PlaceTag.create().setName("Leaning Tower").setKind(PlaceKind.FEATURE)));
		ContentText.Text text = ContentText.of(album, "folder", image);
		assertEquals("title: 2025-10 Italien | text: Leaning Tower, Pisa, Tuscany, Italy; 2025-10 Italien, "
			+ "October 2025; Piazza, at noon; holiday, architecture", text.document());

		ContentText.Text bare = ContentText.of(AlbumInfo.create(), "Undated", ImagePart.create().setName("u.jpg"));
		assertEquals("The folder names an album without a title.", "title: Undated | text: Undated", bare.document());
	}
}
