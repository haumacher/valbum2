/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.PhotoRef;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.imageServer.upload.HashIndex;
import java.awt.Color;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The collections of issue #221 across a replacement of {@link ReplaceOriginals}, see
 * {@link CollectionRewrite}: a collection references a photograph by its content hash, which the
 * replacement changes.
 */
@SuppressWarnings("javadoc")
public class TestReplaceCollections extends TestCase {

	private static final File FIXTURE = new File("src/test/fixtures/collection/index.json");

	/** The reference of the fixture that is pointed at the redacted copy. */
	private static final String FIXTURE_HASH = "018993e6779266adc0e56f42369c37fa023294e1f80b9b3f532d90c489ea64f5";

	private static final String FIXTURE_PATH = "Old place/P3031375.JPG";

	/** A reference of the fixture to something else, which no run touches. */
	private static final String OTHER_HASH = "fb772afbbc795314d648cae29627cec11c201dc69c0026aeab73b5ba446ba189";

	private static final String ALBUM = "2026/2026-09-30 Walk";

	private static final String UPLOADED = "1000020572.jpg";

	private static final String ORIGINAL = "IMG_20260930_122228.jpg";

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 9, 0, 0);

	private Path _root;

	private Path _base;

	private Path _incoming;

	private byte[] _picture;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_root = Files.createTempDirectory("valbum-replace-collections");
		_base = _root.resolve("library");
		_incoming = _root.resolve("downloads");
		Files.createDirectories(_base);
		Files.createDirectories(_incoming);
		_picture = Redacted.picture(Color.RED, false);
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testAReplacementByNameMovesTheReferences() throws Exception {
		Path photo = redacted(UPLOADED);
		Redacted.writeOriginal(_incoming.resolve(UPLOADED).toFile(), _picture);

		checkMoved(photo, UPLOADED, "replaced " + ALBUM + "/" + UPLOADED);
	}

	public void testAReplacementByContentMovesTheReferences() throws Exception {
		Path photo = redacted(UPLOADED);
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), _picture);

		checkMoved(photo, ORIGINAL, "replaced " + ALBUM + "/" + UPLOADED + " with " + ORIGINAL + " (matched by content)");
	}

	private void checkMoved(Path photo, String incomingName, String line) throws Exception {
		String redactedHash = HashCache.sha256(photo.toFile());
		Path collection = collection("Best of", redactedHash, ALBUM + "/" + UPLOADED);
		String originalHash = HashCache.sha256(_incoming.resolve(incomingName).toFile());
		String before =
			new String(ImageServlet.sidecarOf(ResourceCache.sidecar(collection.toFile())), StandardCharsets.UTF_8);

		ReplaceOriginals.Report report = ReplaceOriginals.run(_base, _incoming, null, false, NOW);

		assertEquals(List.of(line), report.getLines());
		String summary = String.join("\n", report.getSummary());
		assertTrue(summary, summary.contains("Updated 1 collection reference(s) in 1 collection(s) to the originals."));

		// Nothing but the hash changed: the headings, the names, the labels, the cover, the other references.
		String after = new String(ImageServlet.sidecarOf(ResourceCache.sidecar(collection.toFile())),
			StandardCharsets.UTF_8);
		assertEquals(before.replace(redactedHash, originalHash), after);
		ImagePart reference = PhotoCollections.referenceNamed(stored(collection), "P3031375.JPG");
		assertEquals(originalHash, reference.getRef().getHash());
		assertEquals(ALBUM + "/" + UPLOADED, reference.getRef().getPath());
		assertEquals(List.of("Favourites"), labels(reference));
		assertEquals(OTHER_HASH, PhotoCollections.referenceNamed(stored(collection), "IMG_0415.JPG").getRef().getHash());

		// read -> write -> read is equal.
		ImageServlet.storeSidecar(collection.toFile(), ImageServlet.sidecarOf(ResourceCache.sidecar(collection.toFile())));
		assertEquals(after, new String(ImageServlet.sidecarOf(ResourceCache.sidecar(collection.toFile())),
			StandardCharsets.UTF_8));

		// The server that starts next finds the original at the library path.
		HashIndex index = new HashIndex(_base);
		try {
			index.indexNow();
			PhotoCollections collections = new PhotoCollections(_base, index);
			PathInfo found = collections.locate(reference.getRef());
			assertNotNull("The reference resolves.", found);
			assertEquals(ALBUM + "/" + UPLOADED, collections.relative(found));
			assertNull("Without the rewrite, the reference would be missing.",
				collections.locate(PhotoRef.create().setHash(redactedHash).setPath(ALBUM + "/" + UPLOADED)));
		} finally {
			index.shutdown();
		}
	}

	public void testADryRunOnlyCounts() throws Exception {
		Path photo = redacted(UPLOADED);
		collection("Best of", HashCache.sha256(photo.toFile()), ALBUM + "/" + UPLOADED);
		collection("Second", HashCache.sha256(photo.toFile()), "somewhere/else.jpg");
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), _picture);
		Map<String, String> before = fingerprint();

		ReplaceOriginals.Report report = ReplaceOriginals.run(_base, _incoming, null, true, NOW);

		String summary = String.join("\n", report.getSummary());
		assertTrue(summary, summary.contains("Would update 2 collection reference(s) in 2 collection(s)"));
		assertEquals("A dry run changes nothing.", before, fingerprint());
	}

	/** A reference that names another copy of the redacted file by its path still finds that copy. */
	public void testAReferenceToAnotherCopyStays() throws Exception {
		Path photo = redacted(UPLOADED);
		String redactedHash = HashCache.sha256(photo.toFile());
		Path copyAlbum = Files.createDirectories(_base.resolve("Copies"));
		Files.copy(photo, copyAlbum.resolve("copy.jpg"));
		HashCache hashes = new HashCache(copyAlbum.toFile());
		hashes.put(copyAlbum.resolve("copy.jpg").toFile(), redactedHash);
		hashes.flush();
		Path collection = collection("Best of", redactedHash, "Copies/copy.jpg");
		Redacted.writeOriginal(_incoming.resolve(UPLOADED).toFile(), _picture);
		byte[] stored = Files.readAllBytes(collection.resolve("index.json"));

		ReplaceOriginals.Report report = ReplaceOriginals.run(_base, _incoming, null, false, NOW);

		assertEquals(List.of("replaced " + ALBUM + "/" + UPLOADED), report.getLines());
		assertTrue(String.join("\n", report.getSummary()),
			String.join("\n", report.getSummary()).contains("Updated 0 collection reference(s)"));
		assertTrue("The collection is not written.",
			Arrays.equals(stored, Files.readAllBytes(collection.resolve("index.json"))));
	}

	// --- Helpers. ---

	/** The redacted copy in its album, the hashes naming Bob as its uploader. */
	private Path redacted(String name) throws Exception {
		Path album = Files.createDirectories(_base.resolve(ALBUM));
		Path photo = album.resolve(name);
		Redacted.writeRedacted(photo.toFile(), _picture);
		HashCache hashes = new HashCache(album.toFile());
		hashes.put(photo.toFile(), HashCache.sha256(photo.toFile()), new HashCache.Attribution("user:bob", "Bob"));
		hashes.flush();
		return photo;
	}

	/**
	 * A collection in the stored form of the fixture, its labelled reference pointed at the given
	 * contents and path.
	 */
	private Path collection(String name, String hash, String path) throws Exception {
		Path folder = Files.createDirectories(_base.resolve(name));
		String json = Files.readString(FIXTURE.toPath()).replace(FIXTURE_HASH, hash).replace(FIXTURE_PATH, path);
		Files.writeString(folder.resolve("index.json"), json);
		return folder;
	}

	private static AlbumInfo stored(Path collection) {
		FolderResource resource = ResourceCache.sidecar(collection.toFile());
		assertTrue(PhotoCollections.isCollection(resource));
		return (AlbumInfo) resource;
	}

	private static List<String> labels(ImagePart image) {
		List<String> result = new ArrayList<>();
		for (LabelName label : image.getLabels()) {
			result.add(label.getName());
		}
		return result;
	}

	private Map<String, String> fingerprint() throws Exception {
		try (Stream<Path> files = Files.walk(_root)) {
			return files.collect(Collectors.toMap(p -> _root.relativize(p).toString(), p -> {
				try {
					return Files.isDirectory(p) ? "dir"
						: HashCache.sha256(p.toFile()) + "@" + Files.getLastModifiedTime(p).toMillis();
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			}, (a, b) -> a, TreeMap::new));
		}
	}
}
