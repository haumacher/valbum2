/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Color;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Putting the originals in the place of the redacted uploads, see {@link ReplaceOriginals} and
 * issue #167.
 */
@SuppressWarnings("javadoc")
public class TestReplaceOriginals extends TestCase {

	private static final String ALBUM = "2024/2024-05-01 Trip";

	private static final String NAME = "IMG_1.jpg";

	/** A date the author corrected by hand, which no file says. */
	private static final long CORRECTED = 1_000_000_000_000L;

	private static final String PERSON = "Km9mQnQ7RgqLxP2hW1a4dQ";

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 23, 21, 5, 7);

	private static final String RUN = "20260923-210507";

	private Path _root;

	private Path _base;

	private Path _incoming;

	private byte[] _picture;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_root = Files.createTempDirectory("valbum-replace");
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

	public void testTheOriginalTakesThePlaceOfTheRedactedCopy() throws Exception {
		Path album = redactedAlbum(_base, ALBUM);
		String redactedHash = HashCache.sha256(album.resolve(NAME).toFile());
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(NAME).toFile());

		ReplaceOriginals.Report report = run(false);

		assertEquals(List.of("replaced " + ALBUM + "/" + NAME), report.getLines());
		assertEquals(1, report.getReplaced());
		assertEquals(0, report.getSkipped());
		assertEquals("The original stands in the album.", originalHash, HashCache.sha256(album.resolve(NAME).toFile()));
		assertFalse("The incoming file was moved.", Files.exists(_incoming.resolve(NAME)));

		Path aside = _base.resolve(".valbum/replaced/" + RUN + "/" + ALBUM + "/" + NAME);
		assertTrue("The redacted copy is set aside: " + aside, Files.isRegularFile(aside));
		assertEquals(redactedHash, HashCache.sha256(aside.toFile()));
		String summary = String.join("\n", report.getSummary());
		assertTrue(summary, summary.contains(_base.resolve(".valbum/replaced/" + RUN).toString()));

		HashCache hashes = new HashCache(album.toFile());
		assertEquals(originalHash, hashes.storedHashByName().get(NAME));
		assertEquals("user:bob", hashes.attributionOf(NAME).getContributor());
		assertEquals("Bob", hashes.attributionOf(NAME).getLabel());
		assertTrue("The stored entry matches the file.", hashes.hashByName().get(NAME).equals(originalHash));

		ImagePart image = storedImage(album, NAME);
		assertNotNull("The position was filled.", image.getLocation());
		assertEquals(Redacted.LATITUDE, image.getLocation().getLatitude(), 1e-6);
		assertEquals(Redacted.LONGITUDE, image.getLocation().getLongitude(), 1e-6);
		assertEquals("The camera was filled.", Redacted.MODEL, image.getCamera());
		assertEquals("The corrected date stays.", CORRECTED, image.getDate());
		assertEquals(2, image.getRating());
		assertEquals("Kept", image.getComment());
		assertEquals(1, image.getTags().size());
		FaceTag tag = image.getTags().get(0);
		assertEquals(PERSON, tag.getPerson());
		assertEquals(FaceState.CONFIRMED, tag.getState());
		assertEquals(0.31, tag.getX(), 1e-9);
	}

	public void testANameTheLibraryHoldsTwiceIsSkipped() throws Exception {
		redactedAlbum(_base, ALBUM);
		redactedAlbum(_base, "2024/2024-05-02 Again");
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), _picture);
		Map<String, String> before = fingerprint();

		ReplaceOriginals.Report report = run(false);

		assertEquals(1, report.getSkipped());
		String line = report.getLines().get(0);
		assertTrue(line, line.startsWith("skipped " + NAME + ": ambiguous"));
		assertTrue(line, line.contains(ALBUM + "/" + NAME));
		assertEquals("Nothing was moved.", before, fingerprint());
	}

	public void testAFileWhosePixelsDifferIsSkipped() throws Exception {
		redactedAlbum(_base, ALBUM);
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), Redacted.picture(new Color(201, 40, 40), false));
		Map<String, String> before = fingerprint();

		ReplaceOriginals.Report report = run(false);

		String line = report.getLines().get(0);
		assertTrue(line, line.startsWith("skipped " + NAME + ": the pixels differ"));
		assertEquals(before, fingerprint());
	}

	public void testANameNotInTheLibraryIsSkipped() throws Exception {
		redactedAlbum(_base, ALBUM);
		Redacted.writeOriginal(_incoming.resolve("IMG_2.jpg").toFile(), _picture);
		Files.writeString(_incoming.resolve("notes.txt"), "not a photograph");
		Files.createDirectories(_incoming.resolve("more"));
		Map<String, String> before = fingerprint();

		ReplaceOriginals.Report report = run(false);

		assertEquals(List.of(
			"skipped IMG_2.jpg: not in the library",
			"skipped more: a folder (the incoming folder is not read recursively)",
			"skipped notes.txt: not a photograph or video"), report.getLines());
		assertEquals(3, report.getSkipped());
		assertEquals(before, fingerprint());
	}

	public void testTheSameFileIsNotReplaced() throws Exception {
		Path album = redactedAlbum(_base, ALBUM);
		Files.copy(album.resolve(NAME), _incoming.resolve(NAME));
		Map<String, String> before = fingerprint();

		ReplaceOriginals.Report report = run(false);

		assertTrue(report.getLines().get(0), report.getLines().get(0).contains("already holds exactly this file"));
		assertEquals(before, fingerprint());
	}

	public void testADryRunTouchesNothing() throws Exception {
		redactedAlbum(_base, ALBUM);
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), _picture);
		Redacted.writeOriginal(_incoming.resolve("IMG_9.jpg").toFile(), _picture);
		Map<String, String> before = fingerprint();

		ReplaceOriginals.Report report = run(true);

		assertEquals(List.of("would replace " + ALBUM + "/" + NAME,
			"skipped IMG_9.jpg: not in the library"), report.getLines());
		assertEquals(1, report.getReplaced());
		String summary = String.join("\n", report.getSummary());
		assertTrue(summary, summary.contains("Dry run: nothing was changed."));
		assertEquals("A dry run changes nothing.", before, fingerprint());
		assertFalse(Files.exists(_base.resolve(".valbum")));
	}

	public void testTheRedactedCopyGoesToTheReplacedFolderOfItsSpace() throws Exception {
		for (String space : new String[] { "alice", "bob" }) {
			Files.createDirectories(_base.resolve(space).resolve(".valbum"));
			Files.writeString(_base.resolve(space).resolve(".valbum/space.json"),
				"{\"version\":1,\"name\":\"" + space + "\"}");
		}
		Path album = redactedAlbum(_base.resolve("bob"), ALBUM);
		// A name outside every space is not in the library of a multi-space server.
		redactedAlbum(_base, "loose");
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(NAME).toFile());

		ReplaceOriginals.Report report = run(false);

		assertEquals(List.of("replaced bob/" + ALBUM + "/" + NAME), report.getLines());
		assertEquals(originalHash, HashCache.sha256(album.resolve(NAME).toFile()));
		assertTrue(Files.isRegularFile(_base.resolve("bob/.valbum/replaced/" + RUN + "/" + ALBUM + "/" + NAME)));
		assertFalse(Files.exists(_base.resolve("alice/.valbum/replaced")));
		assertFalse(Files.exists(_base.resolve(".valbum")));
		assertNotNull(storedImage(album, NAME).getLocation());
	}

	public void testAnIncomingFolderInsideTheLibraryIsNotPartOfIt() throws Exception {
		Path album = redactedAlbum(_base, ALBUM);
		Path inside = _base.resolve("Downloads");
		Files.createDirectories(inside);
		Redacted.writeOriginal(inside.resolve(NAME).toFile(), _picture);
		String originalHash = HashCache.sha256(inside.resolve(NAME).toFile());

		ReplaceOriginals.Report report = ReplaceOriginals.run(_base, inside, null, false, NOW);

		assertEquals(List.of("replaced " + ALBUM + "/" + NAME), report.getLines());
		assertEquals(originalHash, HashCache.sha256(album.resolve(NAME).toFile()));
	}

	public void testAVideoWithTheSameMediaData() throws Exception {
		Path album = _base.resolve(ALBUM);
		Files.createDirectories(album);
		Path redacted = album.resolve("MVI_1.mp4");
		Files.copy(TestSameRecording.VIDEO.toPath(), redacted);
		TestSameRecording.appendFreeBox(redacted.toFile(), "redacted");
		Files.copy(TestSameRecording.VIDEO.toPath(), _incoming.resolve("MVI_1.mp4"));
		Path changed = _incoming.resolve("MVI_2.mp4");
		Files.copy(TestSameRecording.VIDEO.toPath(), changed);
		TestSameRecording.flipMediaByte(changed.toFile());
		Files.copy(TestSameRecording.VIDEO.toPath(), album.resolve("MVI_2.mp4"));

		ReplaceOriginals.Report report = run(false);

		assertEquals("replaced " + ALBUM + "/MVI_1.mp4", report.getLines().get(0));
		assertTrue(report.getLines().get(1), report.getLines().get(1).startsWith("skipped MVI_2.mp4: the recordings differ"));
		assertEquals(HashCache.sha256(TestSameRecording.VIDEO), HashCache.sha256(redacted.toFile()));
		assertTrue(Files.isRegularFile(_base.resolve(".valbum/replaced/" + RUN + "/" + ALBUM + "/MVI_1.mp4")));
		assertTrue("The changed one stays where it was.", Files.exists(changed));
	}

	/** Across file systems the original is copied, checked, and only then taken out of the incoming folder. */
	public void testTheCopyOfAnotherFileSystem() throws Exception {
		Path source = _incoming.resolve(NAME);
		Redacted.writeOriginal(source.toFile(), _picture);
		String hash = HashCache.sha256(source.toFile());
		Path target = Files.createDirectories(_base.resolve(ALBUM)).resolve(NAME);

		try {
			ReplaceOriginals.install(source, target, "0".repeat(64), false);
			fail("A copy that does not hold what was read must be refused.");
		} catch (java.io.IOException expected) {
			// The check of the copy.
		}
		assertTrue("The incoming file stays when the check fails.", Files.exists(source));
		assertFalse(Files.exists(target));
		try (Stream<Path> left = Files.list(target.getParent())) {
			assertEquals("No half-made copy is left.", 0, left.count());
		}

		ReplaceOriginals.install(source, target, hash, false);
		assertEquals(hash, HashCache.sha256(target.toFile()));
		assertFalse("The incoming file goes once the copy is checked.", Files.exists(source));
	}

	public void testAMissingFolderIsRefused() throws Exception {
		try {
			ReplaceOriginals.run(_base, _root.resolve("nowhere"), null, false, NOW);
			fail("A missing folder must be refused.");
		} catch (ReplaceOriginals.Refused expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("nowhere"));
		}
		assertEquals(1, Main.replaceOriginals(_base, _root.resolve("nowhere"), null, false));
		assertEquals(0, Main.replaceOriginals(_base, _incoming, null, true));
	}

	// --- Helpers. ---

	private ReplaceOriginals.Report run(boolean dryRun) throws Exception {
		return ReplaceOriginals.run(_base, _incoming, null, dryRun, NOW);
	}

	/**
	 * An album holding the redacted copy, its sidecar stating a corrected date, a rating, a comment
	 * and a tag and lacking the camera and the position, and its hashes naming Bob as the uploader.
	 */
	private Path redactedAlbum(Path space, String path) throws Exception {
		Path album = space.resolve(path);
		Files.createDirectories(album);
		Redacted.writeRedacted(album.resolve(NAME).toFile(), _picture);
		String index = "[\"AlbumInfo\",{\"kind\":\"ALBUM\",\"title\":\"Trip\",\"parts\":[[\"ImagePart\",{"
			+ "\"kind\":\"IMAGE\",\"name\":\"" + NAME + "\",\"date\":" + CORRECTED + ",\"width\":40,\"height\":30,"
			+ "\"orientation\":\"IDENTITY\",\"rating\":2,\"privacy\":0,\"comment\":\"Kept\",\"camera\":\"\","
			+ "\"tags\":[{\"x\":0.31,\"y\":0.12,\"w\":0.2,\"h\":0.27,\"person\":\"" + PERSON
			+ "\",\"state\":\"CONFIRMED\"}]}]]}]";
		Files.write(album.resolve("index.json"), index.getBytes(StandardCharsets.UTF_8));
		HashCache hashes = new HashCache(album.toFile());
		File file = album.resolve(NAME).toFile();
		hashes.put(file, HashCache.sha256(file), new HashCache.Attribution("user:bob", "Bob"));
		hashes.flush();
		return album;
	}

	private static ImagePart storedImage(Path album, String name) {
		FolderResource stored = ResourceCache.sidecar(album.toFile());
		assertTrue(stored instanceof AlbumInfo);
		for (AlbumPart part : ((AlbumInfo) stored).getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		fail("No part '" + name + "' in " + album);
		return null;
	}

	/** Every file below the test's root with its hash and its modification time. */
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
