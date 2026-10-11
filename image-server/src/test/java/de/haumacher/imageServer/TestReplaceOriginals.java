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
import java.util.Arrays;
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

	/** The album of the author's report of issue #246. */
	private static final String SCHRANK = "2025/2025-11-06 Der neue Schrank";

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

	/** The author's report of issue #246: the original is unique, every copy of it is replaced. */
	public void testEveryLibraryFileOfTheNameIsReplaced() throws Exception {
		String name = "20251106_161645.jpg";
		Path schrank = redactedAlbum(_base, SCHRANK, name, _picture);
		Path inbox = redactedAlbum(_base, "Inbox", name, _picture);
		String redactedHash = HashCache.sha256(schrank.resolve(name).toFile());
		Redacted.writeOriginal(_incoming.resolve(name).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(name).toFile());

		ReplaceOriginals.Report report = run(false);

		assertEquals(List.of("replaced " + SCHRANK + "/" + name, "replaced Inbox/" + name), report.getLines());
		assertEquals(2, report.getReplaced());
		assertEquals(0, report.getSkipped());
		String summary = String.join("\n", report.getSummary());
		assertTrue(summary, summary.startsWith("Replaced 2 library file(s), skipped 0, for the 1 file(s) in "));
		assertFalse("The incoming file is gone.", Files.exists(_incoming.resolve(name)));
		for (Path album : List.of(schrank, inbox)) {
			assertEquals(album + " holds the original.", originalHash, HashCache.sha256(album.resolve(name).toFile()));
			Path aside = _base.resolve(".valbum/replaced/" + RUN + "/" + _base.relativize(album) + "/" + name);
			assertTrue("The redacted copy is set aside: " + aside, Files.isRegularFile(aside));
			assertEquals(redactedHash, HashCache.sha256(aside.toFile()));
			HashCache hashes = new HashCache(album.toFile());
			assertEquals(originalHash, hashes.storedHashByName().get(name));
			assertEquals("user:bob", hashes.attributionOf(name).getContributor());
			assertNotNull("The position was filled in " + album, storedImage(album, name).getLocation());
		}
	}

	/**
	 * Review probe of #246 composed with a second run: the original put into the incoming folder
	 * once more finds both copies holding it already, and nothing changes.
	 */
	public void testProbeEveryCopyReplacedOnceAndNeverAgain() throws Exception {
		String name = "20251106_161645.jpg";
		Path schrank = redactedAlbum(_base, SCHRANK, name, _picture);
		Path inbox = redactedAlbum(_base, "Inbox", name, _picture);
		Redacted.writeOriginal(_incoming.resolve(name).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(name).toFile());

		ReplaceOriginals.Report first = run(false);
		assertEquals(2, first.getReplaced());
		assertEquals(0, first.getSkipped());
		assertFalse(Files.exists(_incoming.resolve(name)));

		// The same original once more: both copies hold it already, nothing is touched.
		Redacted.writeOriginal(_incoming.resolve(name).toFile(), _picture);
		ReplaceOriginals.Report second = run(false);
		assertEquals(0, second.getReplaced());
		String lines = String.join("\n", second.getLines());
		assertTrue(lines, lines.contains("the library already holds exactly this file"));
		assertTrue("A skipped incoming file stays where it was.", Files.exists(_incoming.resolve(name)));
		for (Path album : List.of(schrank, inbox)) {
			assertEquals(originalHash, HashCache.sha256(album.resolve(name).toFile()));
			assertEquals(originalHash, new HashCache(album.toFile()).storedHashByName().get(name));
		}
		try (java.util.stream.Stream<Path> aside = Files.walk(_base.resolve(".valbum/replaced"))) {
			assertEquals("Each redacted copy is set aside once.", 2, aside.filter(Files::isRegularFile).count());
		}
	}

	public void testThreeCopiesAcrossTwoSpaces() throws Exception {
		for (String space : new String[] { "alice", "bob" }) {
			Files.createDirectories(_base.resolve(space).resolve(".valbum"));
			Files.writeString(_base.resolve(space).resolve(".valbum/space.json"),
				"{\"version\":1,\"name\":\"" + space + "\"}");
		}
		List<Path> albums = List.of(redactedAlbum(_base.resolve("alice"), "Shared", NAME, _picture),
			redactedAlbum(_base.resolve("bob"), ALBUM, NAME, _picture),
			redactedAlbum(_base.resolve("bob"), "Inbox", NAME, _picture));
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(NAME).toFile());

		ReplaceOriginals.Report report = run(false);

		assertEquals(List.of("replaced alice/Shared/" + NAME, "replaced bob/" + ALBUM + "/" + NAME,
			"replaced bob/Inbox/" + NAME), report.getLines());
		assertEquals(3, report.getReplaced());
		assertFalse(Files.exists(_incoming.resolve(NAME)));
		for (Path album : albums) {
			assertEquals(originalHash, HashCache.sha256(album.resolve(NAME).toFile()));
			assertEquals(originalHash, new HashCache(album.toFile()).storedHashByName().get(NAME));
		}
		assertTrue(Files.isRegularFile(_base.resolve("alice/.valbum/replaced/" + RUN + "/Shared/" + NAME)));
		assertTrue(Files.isRegularFile(_base.resolve("bob/.valbum/replaced/" + RUN + "/" + ALBUM + "/" + NAME)));
		assertTrue(Files.isRegularFile(_base.resolve("bob/.valbum/replaced/" + RUN + "/Inbox/" + NAME)));
	}

	/** Another recording under the name is skipped with the difference; the same one is replaced. */
	public void testAnotherRecordingOfTheNameIsSkippedTheSameReplaced() throws Exception {
		Path same = redactedAlbum(_base, ALBUM, NAME, _picture);
		Path other = redactedAlbum(_base, "Inbox", NAME, Redacted.picture(new Color(201, 40, 40), false));
		byte[] otherBytes = Files.readAllBytes(other.resolve(NAME));
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(NAME).toFile());

		ReplaceOriginals.Report report = run(false);

		assertEquals(2, report.getLines().size());
		assertEquals("replaced " + ALBUM + "/" + NAME, report.getLines().get(0));
		String line = report.getLines().get(1);
		assertTrue(line, line.startsWith("skipped " + NAME + ": the pixels differ"));
		assertTrue(line, line.endsWith(", not replacing Inbox/" + NAME));
		assertEquals(1, report.getReplaced());
		assertEquals(1, report.getSkipped());
		assertEquals(originalHash, HashCache.sha256(same.resolve(NAME).toFile()));
		assertTrue("The other recording is left alone.", Arrays.equals(otherBytes, Files.readAllBytes(other.resolve(NAME))));
		assertFalse(Files.exists(_base.resolve(".valbum/replaced/" + RUN + "/Inbox")));
		assertFalse("Every place of the original holds it.", Files.exists(_incoming.resolve(NAME)));
	}

	public void testACopyThatAlreadyHoldsTheOriginalIsSkippedTheOtherReplaced() throws Exception {
		Path redacted = redactedAlbum(_base, ALBUM, NAME, _picture);
		Path done = Files.createDirectories(_base.resolve("Inbox"));
		Redacted.writeOriginal(done.resolve(NAME).toFile(), _picture);
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(NAME).toFile());
		long stamp = Files.getLastModifiedTime(done.resolve(NAME)).toMillis();

		ReplaceOriginals.Report report = run(false);

		assertEquals(List.of("replaced " + ALBUM + "/" + NAME,
			"skipped " + NAME + ": the library already holds exactly this file (Inbox/" + NAME + ")"), report.getLines());
		assertEquals(originalHash, HashCache.sha256(redacted.resolve(NAME).toFile()));
		assertEquals("The copy holding the original is not touched.", stamp,
			Files.getLastModifiedTime(done.resolve(NAME)).toMillis());
	}

	public void testADryRunOfTwoCopiesTouchesNothing() throws Exception {
		String name = "20251106_161645.jpg";
		redactedAlbum(_base, SCHRANK, name, _picture);
		redactedAlbum(_base, "Inbox", name, _picture);
		Redacted.writeOriginal(_incoming.resolve(name).toFile(), _picture);
		Map<String, String> before = fingerprint();

		ReplaceOriginals.Report report = run(true);

		assertEquals(List.of("would replace " + SCHRANK + "/" + name, "would replace Inbox/" + name),
			report.getLines());
		assertEquals(2, report.getReplaced());
		String summary = String.join("\n", report.getSummary());
		assertTrue(summary, summary.startsWith("Would replace 2 library file(s), skipped 0, for the 1 file(s) in "));
		assertTrue(summary, summary.contains("Dry run: nothing was changed."));
		assertEquals("A dry run changes nothing.", before, fingerprint());
	}

	/**
	 * Across file systems the incoming file is copied into the first place and the original in
	 * the library into the second, each checked, and the incoming file goes only after both.
	 */
	public void testTwoCopiesAcrossFileSystems() throws Exception {
		Path first = redactedAlbum(_base, ALBUM, NAME, _picture);
		Path second = redactedAlbum(_base, "Inbox", NAME, _picture);
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(NAME).toFile());

		ReplaceOriginals.Report report = runCopying();

		assertEquals(List.of("replaced " + ALBUM + "/" + NAME, "replaced Inbox/" + NAME), report.getLines());
		assertEquals(originalHash, HashCache.sha256(first.resolve(NAME).toFile()));
		assertEquals(originalHash, HashCache.sha256(second.resolve(NAME).toFile()));
		assertFalse("The incoming file goes once every copy is checked.", Files.exists(_incoming.resolve(NAME)));
		for (Path album : List.of(first, second)) {
			try (Stream<Path> left = Files.list(album)) {
				assertTrue("No half-made copy is left.",
					left.noneMatch(p -> p.getFileName().toString().endsWith(".replacing")));
			}
		}
	}

	/** A copied original stays in the incoming folder while one of its places is not replaced. */
	public void testACopiedOriginalStaysWhileAPlaceIsNotReplaced() throws Exception {
		Path first = redactedAlbum(_base, ALBUM, NAME, _picture);
		Path second = redactedAlbum(_base, "Inbox", NAME, _picture);
		byte[] redacted = Files.readAllBytes(second.resolve(NAME));
		// The place the second redacted copy would be set aside at is taken.
		Files.createDirectories(_base.resolve(".valbum/replaced/" + RUN + "/Inbox/" + NAME + "/taken"));
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(NAME).toFile());

		ReplaceOriginals.Report report = runCopying();

		assertEquals(3, report.getLines().size());
		assertEquals("replaced " + ALBUM + "/" + NAME, report.getLines().get(0));
		String line = report.getLines().get(1);
		assertTrue(line, line.startsWith("skipped " + NAME + ": cannot set the redacted copy aside"));
		assertTrue(line, line.endsWith(", not replacing Inbox/" + NAME));
		assertTrue(report.getLines().get(2), report.getLines().get(2).startsWith("kept " + NAME + " in "));
		assertEquals(originalHash, HashCache.sha256(first.resolve(NAME).toFile()));
		assertTrue("The redacted copy stays where it was.", Arrays.equals(redacted, Files.readAllBytes(second.resolve(NAME))));
		assertTrue("The incoming file stays.", Files.exists(_incoming.resolve(NAME)));
		assertEquals(originalHash, HashCache.sha256(_incoming.resolve(NAME).toFile()));
	}

	/** The copy of the original in the library into a further place is checked as well. */
	public void testTheCopyOfTheLibraryOriginalIsChecked() throws Exception {
		Path from = Files.createDirectories(_base.resolve(ALBUM)).resolve(NAME);
		Redacted.writeOriginal(from.toFile(), _picture);
		String hash = HashCache.sha256(from.toFile());
		Path target = Files.createDirectories(_base.resolve("Inbox")).resolve(NAME);

		try {
			ReplaceOriginals.copyChecked(from, target, "0".repeat(64));
			fail("A copy that does not hold what was read must be refused.");
		} catch (java.io.IOException expected) {
			// The check of the copy.
		}
		assertEquals("The source stays as it was.", hash, HashCache.sha256(from.toFile()));
		assertFalse(Files.exists(target));
		try (Stream<Path> left = Files.list(target.getParent())) {
			assertEquals("No half-made copy is left.", 0, left.count());
		}

		ReplaceOriginals.copyChecked(from, target, hash);
		assertEquals(hash, HashCache.sha256(target.toFile()));
		assertTrue("The source is never deleted by a copy.", Files.exists(from));
	}

	/** A NAS's copy of the name below its litter folder is no second library file, see issue #173. */
	public void testTheLitterOfANasIsNoSecondCopy() throws Exception {
		Path album = redactedAlbum(_base, ALBUM);
		Path litter = album.resolve("@eaDir").resolve(NAME);
		Files.createDirectories(litter.getParent());
		Files.copy(album.resolve(NAME), litter);
		Files.createDirectories(_base.resolve("#recycle"));
		Files.copy(album.resolve(NAME), _base.resolve("#recycle").resolve(NAME));
		byte[] litterBytes = Files.readAllBytes(litter);
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), _picture);

		ReplaceOriginals.Report report = run(false);

		assertEquals(List.of("replaced " + ALBUM + "/" + NAME), report.getLines());
		assertTrue("The litter is left alone.", Arrays.equals(litterBytes, Files.readAllBytes(litter)));
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

	/** A run that copies every incoming file, as across file systems. */
	private ReplaceOriginals.Report runCopying() throws Exception {
		return ReplaceOriginals.run(_base, _incoming, null, false, NOW, new RecordingIndex.Reads(), false);
	}

	/**
	 * An album holding the redacted copy, its sidecar stating a corrected date, a rating, a comment
	 * and a tag and lacking the camera and the position, and its hashes naming Bob as the uploader.
	 */
	private Path redactedAlbum(Path space, String path) throws Exception {
		return redactedAlbum(space, path, NAME, _picture);
	}

	/** The same, holding the given picture's redacted copy under the given name. */
	private Path redactedAlbum(Path space, String path, String name, byte[] picture) throws Exception {
		Path album = space.resolve(path);
		Files.createDirectories(album);
		Redacted.writeRedacted(album.resolve(name).toFile(), picture);
		String index = "[\"AlbumInfo\",{\"kind\":\"ALBUM\",\"title\":\"Trip\",\"parts\":[[\"ImagePart\",{"
			+ "\"kind\":\"IMAGE\",\"name\":\"" + name + "\",\"date\":" + CORRECTED + ",\"width\":40,\"height\":30,"
			+ "\"orientation\":\"IDENTITY\",\"rating\":2,\"privacy\":0,\"comment\":\"Kept\",\"camera\":\"\","
			+ "\"tags\":[{\"x\":0.31,\"y\":0.12,\"w\":0.2,\"h\":0.27,\"person\":\"" + PERSON
			+ "\",\"state\":\"CONFIRMED\"}]}]]}]";
		Files.write(album.resolve("index.json"), index.getBytes(StandardCharsets.UTF_8));
		HashCache hashes = new HashCache(album.toFile());
		File file = album.resolve(name).toFile();
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
