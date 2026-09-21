/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.Clustering;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The file the detections of an album are kept in, and the grouping written into it, see issue
 * #124.
 *
 * <p>
 * No detector and no pictures: this is about the format, which is persisted data, and about the
 * clustering, which is arithmetic.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFaceCacheFormat extends TestCase {

	private Path _folder;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_folder = Files.createTempDirectory("valbum-face-cache");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_folder)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	/** What is written is what is read back, boxes, scores, embeddings and groups alike. */
	public void testTheRoundTrip() throws Exception {
		FaceCache written = new FaceCache(_folder.toFile());
		FaceCache.Face one = new FaceCache.Face(0.125, 0.25, 0.5, 0.375, 0.98, numbers(1f, -2.5f, 3.25f));
		one.setCluster("c1");
		FaceCache.Face two = new FaceCache.Face(0.0, 0.0, 1.0, 1.0, 0.5, numbers(0f));
		two.setCluster("c2");
		written.put("aaa", List.of(one, two));
		written.put("bbb", List.of());
		written.flush();

		FaceCache read = new FaceCache(_folder.toFile());
		assertEquals(Set.of("aaa", "bbb"), read.hashes());
		assertEquals("A photograph with no face in it is a result too.", 0, read.facesOf("bbb").size());

		List<FaceCache.Face> faces = read.facesOf("aaa");
		assertEquals(2, faces.size());
		assertEquals(0.125, faces.get(0).getX(), 1e-9);
		assertEquals(0.25, faces.get(0).getY(), 1e-9);
		assertEquals(0.5, faces.get(0).getW(), 1e-9);
		assertEquals(0.375, faces.get(0).getH(), 1e-9);
		assertEquals(0.98, faces.get(0).getScore(), 1e-9);
		assertEquals("c1", faces.get(0).getCluster());
		assertEquals("c2", faces.get(1).getCluster());
		assertEquals(3, faces.get(0).getEmbedding().length);
		assertEquals(-2.5f, faces.get(0).getEmbedding()[1], 0f);
	}

	/** The file says which model wrote it, and one of another model counts for nothing. */
	public void testTheModelStampIsTheOnlyInvalidation() throws Exception {
		FaceCache written = new FaceCache(_folder.toFile());
		written.put("aaa", List.of(new FaceCache.Face(0, 0, 1, 1, 1, numbers(1f))));
		written.flush();

		Path file = FaceCache.file(_folder.toFile()).toPath();
		String json = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(json, json.contains("\"model\":\"" + FaceDetection.MODEL + "\""));
		assertTrue(json, json.contains("\"version\":1"));

		Files.writeString(file, json.replace(FaceDetection.MODEL, "another/2"), StandardCharsets.UTF_8);
		assertTrue("Another model's findings are not this build's.",
			new FaceCache(_folder.toFile()).isEmpty());
	}

	/** A file that cannot be read is simply rebuilt; it never stops an album from loading. */
	public void testABrokenFileIsRebuilt() throws Exception {
		Files.createDirectories(CacheRefresh.cacheDir(_folder.toFile()).toPath());
		Files.writeString(FaceCache.file(_folder.toFile()).toPath(), "{not json at all",
			StandardCharsets.UTF_8);

		assertTrue(new FaceCache(_folder.toFile()).isEmpty());
	}

	/** What the album no longer holds is dropped. */
	public void testWhatIsGoneIsForgotten() throws Exception {
		FaceCache cache = new FaceCache(_folder.toFile());
		cache.put("aaa", List.of());
		cache.put("bbb", List.of());
		cache.retain(Set.of("aaa"));
		cache.flush();

		assertEquals(Set.of("aaa"), new FaceCache(_folder.toFile()).hashes());
	}

	// --- The grouping. ---

	/** Two faces that are alike are one group; a third that is not is a group of its own. */
	public void testTwoAlikeAndOneApart() {
		List<FaceCache.Face> faces = new ArrayList<>();
		faces.add(face(1f, 0f, 0f));
		faces.add(face(0.98f, 0.2f, 0f));
		faces.add(face(0f, 0f, 1f));

		Clustering.cluster(faces);

		assertEquals(faces.get(0).getCluster(), faces.get(1).getCluster());
		assertFalse(faces.get(0).getCluster().equals(faces.get(2).getCluster()));
		assertEquals("The larger group is named first.", "c1", faces.get(0).getCluster());
		assertEquals("c2", faces.get(2).getCluster());
	}

	/** A face without an embedding joins nobody. */
	public void testAFaceWithoutAnEmbeddingStandsAlone() {
		List<FaceCache.Face> faces = new ArrayList<>();
		faces.add(face(1f, 0f, 0f));
		faces.add(face(1f, 0f, 0f));
		faces.add(new FaceCache.Face(0, 0, 1, 1, 1, new float[0]));

		Clustering.cluster(faces);

		assertEquals(faces.get(0).getCluster(), faces.get(1).getCluster());
		assertFalse(faces.get(2).getCluster().equals(faces.get(0).getCluster()));
	}

	/** Every face of an empty album is grouped without anything blowing up. */
	public void testNothingToGroup() {
		Clustering.cluster(new ArrayList<>());
	}

	/** The similarity is the cosine, and nothing compares to nothing. */
	public void testTheSimilarity() {
		assertEquals(1.0, Clustering.cosine(numbers(1f, 2f), numbers(2f, 4f)), 1e-9);
		assertEquals(0.0, Clustering.cosine(numbers(1f, 0f), numbers(0f, 1f)), 1e-9);
		assertEquals(-1.0, Clustering.cosine(numbers(1f, 0f), new float[0]), 0.0);
		assertEquals(-1.0, Clustering.cosine(numbers(0f, 0f), numbers(1f, 1f)), 0.0);
	}

	private static FaceCache.Face face(float... embedding) {
		return new FaceCache.Face(0, 0, 0.5, 0.5, 1, embedding);
	}

	private static float[] numbers(float... values) {
		return values;
	}
}
