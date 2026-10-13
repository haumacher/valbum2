/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.upload.HashCache;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * The persisted format of the hash sidecar, across the attribution fields of issue #53.
 *
 * <p>
 * <code>.hashes.json</code> is written by one build and read by the next, so the shape this build
 * writes and the shape the build before issue #53 wrote must both read. The old shape is spelled
 * out here verbatim as {@link #OLD_FORMAT} rather than produced by the current writer, so that a
 * later change to the writer cannot quietly change what "the old format" means.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestHashCacheFormat extends TestCase {

	/**
	 * The sidecar exactly as the build before issue #53 wrote it, with three placeholders for the
	 * size, the modification stamp and the hash of the one file it describes.
	 */
	private static final String OLD_FORMAT =
		"{\"version\":1,\"files\":{\"old.jpg\":{\"size\":%d,\"modified\":%d,\"sha256\":\"%s\"}}}";

	private Path _folder;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_folder = Files.createTempDirectory("valbum-hashes-test");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_folder)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testASidecarWrittenBeforeIssue53Reads() throws Exception {
		File image = photo("old.jpg");
		String hash = HashCache.sha256(image);
		writeOldFormat(image, hash);

		HashCache cache = new HashCache(_folder.toFile());

		assertEquals("Every hash is intact and nothing is re-hashed.", hash, cache.hashByName().get("old.jpg"));
		assertEquals("A file stored before issue #53 has no contributor.", HashCache.Attribution.NONE.getContributor(),
			cache.attributionOf("old.jpg").getContributor());
		assertEquals("", cache.attributionOf("old.jpg").getLabel());
		assertFalse(cache.attributionOf("old.jpg").isSet());
		assertTrue("Nothing at all is recorded for such a folder.",
			HashCache.recorded(_folder.toFile()).isEmpty());
	}

	public void testAnOldSidecarStaysWithoutAttributionWhenItIsRewritten() throws Exception {
		File image = photo("old.jpg");
		writeOldFormat(image, HashCache.sha256(image));

		HashCache cache = new HashCache(_folder.toFile());
		photo("new.jpg");
		cache.refresh();
		cache.flush();

		assertFalse("Nobody contributed anything here, so nothing says anybody did.",
			contents().contains("contributor"));
	}

	public void testAttributionSurvivesARoundTrip() throws Exception {
		File image = photo("bobs.jpg");
		HashCache written = new HashCache(_folder.toFile());
		written.put(image, HashCache.sha256(image), new HashCache.Attribution("user:bob", "bob"));
		written.flush();

		HashCache read = new HashCache(_folder.toFile());

		assertEquals("user:bob", read.attributionOf("bobs.jpg").getContributor());
		assertEquals("bob", read.attributionOf("bobs.jpg").getLabel());
		Map<String, HashCache.Attribution> recorded = HashCache.recorded(_folder.toFile());
		assertEquals(1, recorded.size());
		assertEquals("user:bob", recorded.get("bobs.jpg").getContributor());
		assertEquals("bob", recorded.get("bobs.jpg").getLabel());
	}

	public void testTheLinkOfAContactsUploadSurvivesARoundTrip() throws Exception {
		File image = photo("petras.jpg");
		HashCache written = new HashCache(_folder.toFile());
		written.put(image, HashCache.sha256(image),
			new HashCache.Attribution("contact:p1", "Tante Petra", -2, 0, "", "link1"));
		written.flush();
		assertTrue(contents(), contents().contains("\"contributorLink\":\"link1\""));

		HashCache.Attribution read = HashCache.recorded(_folder.toFile()).get("petras.jpg");
		assertEquals("contact:p1", read.getContributor());
		assertEquals("link1", read.getLink());
	}

	public void testAMembersUploadRecordsNoLink() throws Exception {
		File image = photo("bobs.jpg");
		HashCache written = new HashCache(_folder.toFile());
		written.put(image, HashCache.sha256(image), new HashCache.Attribution("user:bob", "bob"));
		written.flush();
		assertFalse(contents(), contents().contains("contributorLink"));
		assertEquals("", new HashCache(_folder.toFile()).attributionOf("bobs.jpg").getLink());
	}

	public void testAContributorWithoutALabelIsRecordedAllTheSame() throws Exception {
		File image = photo("anon.jpg");
		HashCache written = new HashCache(_folder.toFile());
		written.put(image, HashCache.sha256(image), new HashCache.Attribution("anonymous", ""));
		written.flush();

		HashCache.Attribution read = new HashCache(_folder.toFile()).attributionOf("anon.jpg");

		assertEquals("anonymous", read.getContributor());
		assertEquals("", read.getLabel());
		assertTrue("A caller without a name is still a caller.", read.isSet());
	}

	public void testWhoPutAFileHereOutlivesTheContentsChangingBehindTheServersBack() throws Exception {
		File image = photo("bobs.jpg");
		HashCache written = new HashCache(_folder.toFile());
		written.put(image, HashCache.sha256(image), new HashCache.Attribution("user:bob", "bob"));
		written.flush();

		// A file manager overwrites the photo: other contents, other size, other stamp.
		Files.write(image.toPath(), new byte[] { 1, 2, 3 });
		HashCache cache = new HashCache(_folder.toFile());
		cache.refresh();

		assertEquals("The hash is recomputed from the file.", HashCache.sha256(image),
			cache.hashByName().get("bobs.jpg"));
		assertEquals("Who put the photo here is not a question the contents answer.", "user:bob",
			cache.attributionOf("bobs.jpg").getContributor());
	}

	public void testAFieldThisBuildDoesNotKnowIsDroppedDeliberately() throws Exception {
		File image = photo("old.jpg");
		String hash = HashCache.sha256(image);
		// A sidecar of some later build, carrying more than this one knows about.
		Files.write(_folder.resolve(HashCache.FILE_NAME),
			("{\"version\":1,\"whole\":\"file\",\"files\":{\"old.jpg\":{\"size\":" + image.length()
				+ ",\"modified\":" + image.lastModified() + ",\"sha256\":\"" + hash
				+ "\",\"camera\":\"leica\"}}}").getBytes(StandardCharsets.UTF_8));

		HashCache cache = new HashCache(_folder.toFile());
		assertEquals("What this build does know is read.", hash, cache.hashByName().get("old.jpg"));

		cache.put(image, hash, new HashCache.Attribution("user:bob", "bob"));
		cache.flush();

		String rewritten = contents();
		assertFalse("A field this build does not know is dropped, not kept: this file is a cache, and "
			+ "carrying unknown data forward would promise to maintain it.", rewritten.contains("camera"));
		assertFalse(rewritten.contains("whole"));
		assertTrue(rewritten.contains("\"contributor\":\"user:bob\""));
	}

	/**
	 * The sidecar as the builds of issues #203 to #267 wrote it: a contact's upload through a
	 * personal link, without the time of the upload and without a move, see issue #268.
	 */
	private static final String BEFORE_ISSUE_268 = "{\"version\":1,\"files\":{\"petras.jpg\":{\"size\":%d,"
		+ "\"modified\":%d,\"sha256\":\"%s\",\"contributor\":\"contact:p1\",\"contributorLabel\":\"Tante Petra\","
		+ "\"linkMaxPrivacy\":0,\"contributorLink\":\"link1\"}}}";

	public void testASidecarWrittenBeforeIssue268ReadsWithoutAnArrival() throws Exception {
		File image = photo("petras.jpg");
		String hash = HashCache.sha256(image);
		Files.write(_folder.resolve(HashCache.FILE_NAME), String.format(BEFORE_ISSUE_268,
			Long.valueOf(image.length()), Long.valueOf(image.lastModified()), hash).getBytes(StandardCharsets.UTF_8));

		HashCache cache = new HashCache(_folder.toFile());
		HashCache.Attribution read = cache.attributionOf("petras.jpg");

		assertEquals(hash, cache.hashByName().get("petras.jpg"));
		assertEquals("contact:p1", read.getContributor());
		assertEquals("Tante Petra", read.getLabel());
		assertEquals("link1", read.getLink());
		assertEquals(0, read.getUploaded());
		assertEquals("", read.getMover());
		assertEquals(0, read.getMoved());
		assertFalse("When it came is not known: it is in nobody's bell.", read.hasArrival());
		assertTrue(cache.storedArrivals().isEmpty());

		// Rewritten by this build, it stays what it was.
		photo("other.jpg");
		cache.refresh();
		cache.flush();
		assertFalse(contents(), contents().contains("uploaded"));
		assertFalse(contents(), contents().contains("moved"));
		assertEquals("contact:p1", new HashCache(_folder.toFile()).attributionOf("petras.jpg").getContributor());
	}

	public void testTheArrivalSurvivesARoundTrip() throws Exception {
		File uploaded = photo("uploaded.jpg");
		File moved = photo("moved.jpg");
		HashCache written = new HashCache(_folder.toFile());
		written.put(uploaded, HashCache.sha256(uploaded),
			new HashCache.Attribution("contact:p1", "Tante Petra").uploadedAt(1_000L));
		written.put(moved, HashCache.sha256(moved), new HashCache.Attribution("user:bob", "bob").uploadedAt(2_000L)
			.movedOutOfInbox("user:carol", "carol", 3_000L));
		written.flush();
		assertTrue(contents(), contents().contains("\"uploaded\":1000"));
		assertTrue(contents(), contents().contains("\"movedBy\":\"user:carol\""));

		HashCache read = new HashCache(_folder.toFile());
		HashCache.Attribution upload = read.attributionOf("uploaded.jpg");
		assertEquals(1_000L, upload.getUploaded());
		assertEquals("contact:p1", upload.arrivalActor());
		assertEquals("Tante Petra", upload.arrivalLabel());
		assertEquals(1_000L, upload.arrivalTime());
		assertFalse(upload.arrivedByMove());

		HashCache.Attribution move = read.attributionOf("moved.jpg");
		assertEquals("Who uploaded it stays.", "user:bob", move.getContributor());
		assertEquals(2_000L, move.getUploaded());
		assertEquals("The others saw it when it left the inbox.", "user:carol", move.arrivalActor());
		assertEquals("carol", move.arrivalLabel());
		assertEquals(3_000L, move.arrivalTime());
		assertTrue(move.arrivedByMove());
		assertEquals(2, read.storedArrivals().size());
	}

	/** Writes the sidecar in the shape of the build before issue #53. */
	private void writeOldFormat(File image, String hash) throws Exception {
		Files.write(_folder.resolve(HashCache.FILE_NAME),
			String.format(OLD_FORMAT, Long.valueOf(image.length()), Long.valueOf(image.lastModified()), hash)
				.getBytes(StandardCharsets.UTF_8));
	}

	private String contents() throws Exception {
		return new String(Files.readAllBytes(_folder.resolve(HashCache.FILE_NAME)), StandardCharsets.UTF_8);
	}

	/** A tiny JPEG of its own contents in the folder under test. */
	private File photo(String name) throws Exception {
		BufferedImage contents = new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR);
		contents.setRGB(0, 0, name.hashCode() & 0xFFFFFF);
		File file = _folder.resolve(name).toFile();
		ImageIO.write(contents, "jpg", file);
		return file;
	}
}
