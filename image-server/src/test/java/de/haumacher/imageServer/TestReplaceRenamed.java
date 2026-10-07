/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Finding the photographs and videos a phone renamed on upload, see {@link ReplaceOriginals} and
 * {@link RecordingIndex}.
 */
@SuppressWarnings("javadoc")
public class TestReplaceRenamed extends TestCase {

	private static final String ALBUM = "2026/2026-09-30 Walk";

	/** The name the phone gave the upload. */
	private static final String UPLOADED = "1000020572.jpg";

	/** The name of the original the author downloaded. */
	private static final String ORIGINAL = "IMG_20260930_122228.jpg";

	private static final String PERSON = "Km9mQnQ7RgqLxP2hW1a4dQ";

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 9, 0, 0);

	private static final String RUN = "20261007-090000";

	private Path _root;

	private Path _base;

	private Path _incoming;

	private byte[] _picture;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_root = Files.createTempDirectory("valbum-renamed");
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

	public void testTheOriginalTakesTheLibrarysName() throws Exception {
		Path album = album(ALBUM);
		Path uploaded = album.resolve(UPLOADED);
		Redacted.writeRedacted(uploaded.toFile(), _picture);
		describe(album, uploaded);
		String redactedHash = HashCache.sha256(uploaded.toFile());
		Set<String> fieldsBefore = fields(album.resolve(HashCache.FILE_NAME));
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(ORIGINAL).toFile());
		RecordingIndex.Reads reads = new RecordingIndex.Reads();

		ReplaceOriginals.Report report = run(false, reads);

		assertEquals(List.of("replaced " + ALBUM + "/" + UPLOADED + " with " + ORIGINAL + " (matched by content)"),
			report.getLines());
		assertEquals(1, report.getReplaced());
		assertEquals("The original stands under the library's name.", originalHash,
			HashCache.sha256(uploaded.toFile()));
		assertFalse("The incoming name does not enter the album.", Files.exists(album.resolve(ORIGINAL)));
		assertFalse("The incoming file was moved.", Files.exists(_incoming.resolve(ORIGINAL)));
		Path aside = _base.resolve(".valbum/replaced/" + RUN + "/" + ALBUM + "/" + UPLOADED);
		assertTrue("The redacted copy is set aside under its name: " + aside, Files.isRegularFile(aside));
		assertEquals(redactedHash, HashCache.sha256(aside.toFile()));

		assertEquals("The photograph was keyed by its sidecar.", 1, reads.getFromSidecar());
		assertFalse("A photograph its sidecar lists is not opened in the key pass.",
			reads.getHeaderBytes().containsKey(uploaded));
		assertEquals(List.of(uploaded), reads.getCompared());

		// The sidecar entry, its rating and its tag keep pointing at the photograph.
		ImagePart image = storedImage(album, UPLOADED);
		assertNotNull("The position was filled.", image.getLocation());
		assertEquals(Redacted.LATITUDE, image.getLocation().getLatitude(), 1e-6);
		assertEquals(2, image.getRating());
		assertEquals(1, image.getTags().size());
		FaceTag tag = image.getTags().get(0);
		assertEquals(PERSON, tag.getPerson());

		// The hashes: the new one under the library's name, the attribution kept, through a round trip.
		HashCache hashes = new HashCache(album.toFile());
		assertEquals(originalHash, hashes.storedHashByName().get(UPLOADED));
		assertNull(hashes.storedHashByName().get(ORIGINAL));
		assertEquals("user:bob", hashes.attributionOf(UPLOADED).getContributor());
		hashes.put(uploaded.toFile(), hashes.hashByName().get(UPLOADED), hashes.attributionOf(UPLOADED));
		hashes.flush();
		HashCache again = new HashCache(album.toFile());
		assertEquals(originalHash, again.storedHashByName().get(UPLOADED));
		assertEquals("user:bob", again.attributionOf(UPLOADED).getContributor());
		assertEquals("Bob", again.attributionOf(UPLOADED).getLabel());
		assertEquals("No field is added to the hash sidecar.", fieldsBefore,
			fields(album.resolve(HashCache.FILE_NAME)));
	}

	/** A folder the server never listed has no sidecar: its photographs are read by their headers. */
	public void testAPhotographNoSidecarListsIsReadByItsHeader() throws Exception {
		Path album = album(ALBUM);
		Path uploaded = album.resolve(UPLOADED);
		Redacted.writeRedacted(uploaded.toFile(), _picture);
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(ORIGINAL).toFile());
		RecordingIndex.Reads reads = new RecordingIndex.Reads();

		ReplaceOriginals.Report report = run(false, reads);

		assertEquals(List.of("replaced " + ALBUM + "/" + UPLOADED + " with " + ORIGINAL + " (matched by content)"),
			report.getLines());
		assertEquals(originalHash, HashCache.sha256(uploaded.toFile()));
		assertEquals(0, reads.getSidecars());
		assertTrue(reads.getHeaderBytes().containsKey(uploaded));
	}

	public void testADryRunTouchesNothing() throws Exception {
		Path album = album(ALBUM);
		Redacted.writeRedacted(album.resolve(UPLOADED).toFile(), _picture);
		describe(album, album.resolve(UPLOADED));
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), _picture);
		Map<String, String> before = fingerprint();

		ReplaceOriginals.Report report = run(true, new RecordingIndex.Reads());

		assertEquals(List.of("would replace " + ALBUM + "/" + UPLOADED + " with " + ORIGINAL
			+ " (matched by content)"), report.getLines());
		assertEquals(1, report.getReplaced());
		assertEquals("A dry run changes nothing.", before, fingerprint());
		assertFalse(Files.exists(_base.resolve(".valbum")));
	}

	public void testARenamedVideo() throws Exception {
		Path album = album(ALBUM);
		Path uploaded = album.resolve("1000020573.mp4");
		Files.copy(TestSameRecording.VIDEO.toPath(), uploaded);
		TestSameRecording.appendFreeBox(uploaded.toFile(), "redacted");
		Files.copy(TestSameRecording.VIDEO.toPath(), _incoming.resolve("VID_20260930_122300.mp4"));
		// Another video of the same duration and size, with other media data.
		Path other = album.resolve("1000020574.mp4");
		Files.copy(TestSameRecording.VIDEO.toPath(), other);
		TestSameRecording.flipMediaByte(other.toFile());
		byte[] otherBytes = Files.readAllBytes(other);

		ReplaceOriginals.Report report = run(false, new RecordingIndex.Reads());

		assertEquals(List.of("replaced " + ALBUM + "/1000020573.mp4 with VID_20260930_122300.mp4 (matched by content)"),
			report.getLines());
		assertEquals(HashCache.sha256(TestSameRecording.VIDEO), HashCache.sha256(uploaded.toFile()));
		assertTrue(Files.isRegularFile(_base.resolve(".valbum/replaced/" + RUN + "/" + ALBUM + "/1000020573.mp4")));
		assertTrue("The other video is left alone.", Arrays.equals(otherBytes, Files.readAllBytes(other)));
	}

	/** Two frames of a burst share the second and the size: the scan data picks the right one. */
	public void testABurstReplacesOnlyTheRightFrame() throws Exception {
		Path album = album(ALBUM);
		byte[] second = Redacted.picture(Color.GREEN, false);
		Path frame1 = album.resolve("1000020580.jpg");
		Path frame2 = album.resolve("1000020581.jpg");
		Redacted.writeRedacted(frame1.toFile(), _picture);
		Redacted.writeRedacted(frame2.toFile(), second);
		describe(album, frame1, frame2);
		byte[] frame1Bytes = Files.readAllBytes(frame1);
		Redacted.writeOriginal(_incoming.resolve("IMG_20260930_122228_BURST2.jpg").toFile(), second);
		RecordingIndex.Reads reads = new RecordingIndex.Reads();

		ReplaceOriginals.Report report = run(false, reads);

		assertEquals(List.of("replaced " + ALBUM + "/1000020581.jpg with IMG_20260930_122228_BURST2.jpg "
			+ "(matched by content)"), report.getLines());
		assertTrue("The other frame is left alone.", Arrays.equals(frame1Bytes, Files.readAllBytes(frame1)));
		assertEquals("Without thumbnails, both frames are compared.", 2, reads.getCompared().size());
	}

	/** Where the frames embed thumbnails, the thumbnail cuts the candidates before any full read. */
	public void testTheThumbnailNarrowsABurst() throws Exception {
		Path album = album(ALBUM);
		byte[] second = Redacted.picture(Color.GREEN, false);
		byte[] thumbnail1 = Redacted.picture(Color.ORANGE, false);
		byte[] thumbnail2 = Redacted.picture(Color.CYAN, false);
		Path frame1 = album.resolve("1000020580.jpg");
		Path frame2 = album.resolve("1000020581.jpg");
		Redacted.writeRedacted(frame1.toFile(), _picture, thumbnail1);
		Redacted.writeRedacted(frame2.toFile(), second, thumbnail2);
		describe(album, frame1, frame2);
		Redacted.writeOriginal(_incoming.resolve("IMG_20260930_122228_BURST2.jpg").toFile(), second, thumbnail2);
		RecordingIndex.Reads reads = new RecordingIndex.Reads();

		ReplaceOriginals.Report report = run(false, reads);

		assertEquals(List.of("replaced " + ALBUM + "/1000020581.jpg with IMG_20260930_122228_BURST2.jpg "
			+ "(matched by content)"), report.getLines());
		assertEquals("Only the frame with the same thumbnail is read in full.", List.of(frame2), reads.getCompared());
	}

	/** An editor that regenerated the thumbnail must not hide the true match. */
	public void testARegeneratedThumbnailStillMatches() throws Exception {
		Path album = album(ALBUM);
		byte[] second = Redacted.picture(Color.GREEN, false);
		byte[] original = Redacted.picture(Color.ORANGE, false);
		byte[] regenerated = Redacted.picture(Color.PINK, false);
		Path frame1 = album.resolve("1000020580.jpg");
		Path frame2 = album.resolve("1000020581.jpg");
		// The wrong frame happens to embed the very thumbnail of the original: it is compared first,
		// and found to be another picture.
		Redacted.writeRedacted(frame1.toFile(), _picture, original);
		Redacted.writeRedacted(frame2.toFile(), second, regenerated);
		describe(album, frame1, frame2);
		byte[] frame1Bytes = Files.readAllBytes(frame1);
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), second, original);
		RecordingIndex.Reads reads = new RecordingIndex.Reads();

		ReplaceOriginals.Report report = run(false, reads);

		assertEquals(List.of("replaced " + ALBUM + "/1000020581.jpg with " + ORIGINAL + " (matched by content)"),
			report.getLines());
		assertEquals(List.of(frame1, frame2), reads.getCompared());
		assertTrue(Arrays.equals(frame1Bytes, Files.readAllBytes(frame1)));
	}

	/** The only candidate with a regenerated thumbnail is matched as well. */
	public void testADifferentThumbnailDoesNotExclude() throws Exception {
		Path album = album(ALBUM);
		Path uploaded = album.resolve(UPLOADED);
		Redacted.writeRedacted(uploaded.toFile(), _picture, Redacted.picture(Color.PINK, false));
		describe(album, uploaded);
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), _picture, Redacted.picture(Color.ORANGE, false));

		ReplaceOriginals.Report report = run(false, new RecordingIndex.Reads());

		assertEquals(List.of("replaced " + ALBUM + "/" + UPLOADED + " with " + ORIGINAL + " (matched by content)"),
			report.getLines());
	}

	public void testTwoCopiesOfTheSameRecordingAreAmbiguous() throws Exception {
		Path album = album(ALBUM);
		Path other = album("2026/Best of");
		Redacted.writeRedacted(album.resolve(UPLOADED).toFile(), _picture);
		Redacted.writeRedacted(other.resolve("best-1.jpg").toFile(), _picture);
		describe(album, album.resolve(UPLOADED));
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), _picture);
		Map<String, String> before = fingerprint();

		ReplaceOriginals.Report report = run(false, new RecordingIndex.Reads());

		assertEquals(1, report.getSkipped());
		String line = report.getLines().get(0);
		assertTrue(line, line.startsWith("skipped " + ORIGINAL + ": ambiguous (the library holds the same recording 2 "
			+ "times under other names: "));
		assertTrue(line, line.contains(ALBUM + "/" + UPLOADED));
		assertTrue(line, line.contains("2026/Best of/best-1.jpg"));
		assertEquals("Nothing was moved.", before, fingerprint());
	}

	/** A name the library holds is matched by that name; the same recording is not taken twice. */
	public void testTheNameComesFirst() throws Exception {
		Path album = album(ALBUM);
		Redacted.writeRedacted(album.resolve(UPLOADED).toFile(), _picture);
		describe(album, album.resolve(UPLOADED));
		Redacted.writeOriginal(_incoming.resolve(UPLOADED).toFile(), _picture);
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), _picture);

		ReplaceOriginals.Report report = run(false, new RecordingIndex.Reads());

		assertEquals(2, report.getLines().size());
		assertEquals("replaced " + ALBUM + "/" + UPLOADED, report.getLines().get(0));
		String line = report.getLines().get(1);
		assertTrue(line, line.startsWith("skipped " + ORIGINAL + ": not in the library (the file(s) of the same "
			+ "recording time and size are already replaced in this run: " + ALBUM + "/" + UPLOADED + " by "
			+ UPLOADED));
		assertTrue("The second original stays where it was.", Files.exists(_incoming.resolve(ORIGINAL)));
	}

	public void testAPictureOfAnotherTimeIsNotFound() throws Exception {
		Path album = album(ALBUM);
		Redacted.writeRedacted(album.resolve(UPLOADED).toFile(), _picture);
		describe(album, album.resolve(UPLOADED));
		Redacted.writeOriginalTakenAt(_incoming.resolve(ORIGINAL).toFile(), _picture, "2024:05:01 10:00:01");
		RecordingIndex.Reads reads = new RecordingIndex.Reads();

		ReplaceOriginals.Report report = run(false, reads);

		assertEquals(List.of("skipped " + ORIGINAL + ": not in the library"), report.getLines());
		assertTrue("No candidate, no full read.", reads.getCompared().isEmpty());
	}

	/**
	 * The key pass over a library of many folders reads every sidecar once and, of a photograph no
	 * sidecar lists and of every video, nothing but the header: no byte of a picture's scan data and
	 * no byte of a video's media data, and not even the large segments in front of the scan that
	 * carry no EXIF.
	 */
	public void testTheKeyPassReadsOnlyHeaders() throws Exception {
		int folders = 40;
		int perFolder = 3;
		byte[] bulky = new byte[60_000];
		List<Path> unlisted = new ArrayList<>();
		List<Path> videos = new ArrayList<>();
		int listed = 0;
		for (int f = 0; f < folders; f++) {
			Path album = album("2026/Folder " + f);
			List<Path> files = new ArrayList<>();
			for (int n = 0; n < perFolder; n++) {
				Path file = album.resolve("1000" + f + "" + n + ".jpg");
				// Every photograph of another second, none of the original's.
				Redacted.wrap(file.toFile(), Redacted.picture(new Color(f * 5, n * 40, 90), false),
					Redacted.tiff(String.format("2025:01:%02d 10:%02d:00", 1 + f % 28, f + n), false));
				withSegment(file, 0xE2, bulky);
				files.add(file);
			}
			if (f % 2 == 0) {
				describe(album, files.toArray(new Path[0]));
				listed += files.size();
			} else {
				unlisted.addAll(files);
			}
			if (f % 10 == 0) {
				Path video = album.resolve("1000" + f + "9.mp4");
				Files.copy(TestSameRecording.VIDEO.toPath(), video);
				videos.add(video);
			}
		}
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), Redacted.picture(Color.MAGENTA, false));
		RecordingIndex.Reads reads = new RecordingIndex.Reads();

		long start = System.nanoTime();
		ReplaceOriginals.Report report = run(true, reads);
		long millis = (System.nanoTime() - start) / 1_000_000;

		assertEquals(List.of("skipped " + ORIGINAL + ": not in the library"), report.getLines());
		assertEquals("Every sidecar is read once.", folders / 2, reads.getSidecars());
		assertEquals(listed, reads.getFromSidecar());
		Set<Path> opened = new TreeSet<>(reads.getHeaderBytes().keySet());
		Set<Path> expected = new TreeSet<>(unlisted);
		expected.addAll(videos);
		assertEquals("Only what no sidecar lists is opened.", expected, opened);
		for (Path file : unlisted) {
			byte[] bytes = Files.readAllBytes(file);
			int scanStart = SameRecording.scan(bytes)[0];
			long read = reads.getHeaderBytes().get(file).longValue();
			assertTrue(file + ": " + read + " bytes read, the scan starts at " + scanStart,
				read <= scanStart - bulky.length + 2);
		}
		for (Path video : videos) {
			long media;
			try (RandomAccessFile in = new RandomAccessFile(video.toFile(), "r")) {
				media = SameRecording.mdat(in).stream().mapToLong(range -> range[1]).sum();
			}
			long read = reads.getHeaderBytes().get(video).longValue();
			assertTrue(video + ": " + read + " bytes read of " + Files.size(video) + ", " + media + " of them media",
				read <= Files.size(video) - media + 12);
		}
		assertTrue("Nothing is compared without a candidate.", reads.getCompared().isEmpty());
		System.out.println("Key pass over " + folders + " folders (" + (folders * perFolder + videos.size())
			+ " files, " + reads.getSidecars() + " sidecars, " + opened.size() + " header reads, "
			+ reads.getHeaderBytes().values().stream().mapToLong(Long::longValue).sum() + " bytes): " + millis + " ms");
	}

	/** A face marked by hand (#155) survives the replacement with its embedding, under the new hash. */
	public void testAHandMarkedFaceMovesToTheOriginal() throws Exception {
		Path album = album(ALBUM);
		Path uploaded = album.resolve(UPLOADED);
		Redacted.writeRedacted(uploaded.toFile(), _picture);
		describe(album, uploaded);
		String redactedHash = HashCache.sha256(uploaded.toFile());
		float[] embedding = { 0.25f, -0.5f, 0.125f };
		FaceCache.Face marked = new FaceCache.Face(0.31, 0.12, 0.2, 0.27, 0.9, embedding, true, true);
		marked.setCluster("c7");
		FaceCache faces = new FaceCache(album.toFile());
		faces.put(redactedHash, List.of(marked));
		faces.putExif(redactedHash, 1);
		faces.putSearched(redactedHash);
		faces.flush();
		File crop = FaceIndex.cropFile(uploaded.toFile(), redactedHash, marked);
		Files.write(crop.toPath(), new byte[] { 1, 2, 3 });
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), _picture);
		String originalHash = HashCache.sha256(_incoming.resolve(ORIGINAL).toFile());

		ReplaceOriginals.Report report = run(false, new RecordingIndex.Reads());

		assertEquals(List.of("replaced " + ALBUM + "/" + UPLOADED + " with " + ORIGINAL + " (matched by content)"),
			report.getLines());
		String summary = String.join("\n", report.getSummary());
		assertTrue(summary, summary.contains("Moved the faces found in 1 redacted cop(ies) to the originals."));
		FaceCache after = new FaceCache(album.toFile());
		assertFalse("The redacted contents are gone from the album.", after.knows(redactedHash));
		List<FaceCache.Face> moved = after.facesOf(originalHash);
		assertEquals(1, moved.size());
		FaceCache.Face face = moved.get(0);
		assertTrue(face.isMarked());
		assertTrue(Arrays.equals(embedding, face.getEmbedding()));
		assertEquals("c7", face.getCluster());
		assertEquals(0.31, face.getX(), 1e-9);
		assertEquals(Integer.valueOf(1), after.exifOf(originalHash));
		assertTrue(after.isSearched(originalHash));
		assertFalse(crop.exists());
		assertTrue("The crop is found under the new hash.",
			FaceIndex.cropFile(uploaded.toFile(), originalHash, face).isFile());
		assertEquals(FaceIndex.cropFile(uploaded.toFile(), originalHash, face), FaceIndex.cropFile(uploaded.toFile(), 0));
	}

	public void testADryRunOnlyCountsTheFaces() throws Exception {
		Path album = album(ALBUM);
		Path uploaded = album.resolve(UPLOADED);
		Redacted.writeRedacted(uploaded.toFile(), _picture);
		describe(album, uploaded);
		FaceCache faces = new FaceCache(album.toFile());
		faces.put(HashCache.sha256(uploaded.toFile()),
			List.of(new FaceCache.Face(0.31, 0.12, 0.2, 0.27, 0.9, new float[] { 1f }, true, true)));
		faces.flush();
		Redacted.writeOriginal(_incoming.resolve(ORIGINAL).toFile(), _picture);
		Map<String, String> before = fingerprint();

		ReplaceOriginals.Report report = run(true, new RecordingIndex.Reads());

		String summary = String.join("\n", report.getSummary());
		assertTrue(summary, summary.contains("Would move the faces found in 1 redacted cop(ies) to the originals."));
		assertEquals(before, fingerprint());
	}

	// --- Helpers. ---

	private ReplaceOriginals.Report run(boolean dryRun, RecordingIndex.Reads reads) throws Exception {
		return ReplaceOriginals.run(_base, _incoming, null, dryRun, NOW, reads);
	}

	private Path album(String path) throws Exception {
		return Files.createDirectories(_base.resolve(path));
	}

	/**
	 * Writes the album's sidecar as the server would have written it for the given photographs (the
	 * date as the server reads it, a rating and a tag) and the hashes naming Bob as their uploader.
	 */
	private static void describe(Path album, Path... photos) throws Exception {
		StringBuilder parts = new StringBuilder();
		HashCache hashes = new HashCache(album.toFile());
		for (Path photo : photos) {
			ImageData data = ImageData.analyze(AlbumInfo.create(), photo.toFile());
			if (parts.length() > 0) {
				parts.append(',');
			}
			parts.append("[\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"" + photo.getFileName() + "\",\"date\":"
				+ data.getDate() + ",\"width\":" + data.getWidth() + ",\"height\":" + data.getHeight() + ","
				+ "\"orientation\":\"IDENTITY\",\"rating\":2,\"privacy\":0,\"comment\":\"\",\"camera\":\"\","
				+ "\"tags\":[{\"x\":0.31,\"y\":0.12,\"w\":0.2,\"h\":0.27,\"person\":\"" + PERSON
				+ "\",\"state\":\"CONFIRMED\"}]}]");
			File file = photo.toFile();
			hashes.put(file, HashCache.sha256(file), new HashCache.Attribution("user:bob", "Bob"));
		}
		String index = "[\"AlbumInfo\",{\"kind\":\"ALBUM\",\"title\":\"Walk\",\"parts\":[" + parts + "]}]";
		Files.write(album.resolve("index.json"), index.getBytes(StandardCharsets.UTF_8));
		hashes.flush();
	}

	/** Puts a marker segment with the given payload right behind the start of the image. */
	private static void withSegment(Path file, int marker, byte[] payload) throws Exception {
		byte[] jpeg = Files.readAllBytes(file);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		int length = payload.length + 2;
		out.write(0xFF);
		out.write(marker);
		out.write((length >> 8) & 0xFF);
		out.write(length & 0xFF);
		out.write(payload);
		out.write(jpeg, 2, jpeg.length - 2);
		Files.write(file, out.toByteArray());
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

	private static final Pattern FIELD = Pattern.compile("\"([A-Za-z_]+)\"\\s*:");

	/** The names of the fields the given JSON file uses. */
	private static Set<String> fields(Path file) throws Exception {
		Set<String> result = new TreeSet<>();
		Matcher matcher = FIELD.matcher(Files.readString(file));
		while (matcher.find()) {
			result.add(matcher.group(1));
		}
		return result;
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
