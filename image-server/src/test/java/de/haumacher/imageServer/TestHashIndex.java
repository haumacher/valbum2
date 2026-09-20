/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.IndexProgress;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.PresentFile;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.imageServer.upload.HashIndex;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Test case for the space-wide hash index of issue #118.
 *
 * <p>
 * The gap it closes: a photo that was synced into the inbox and then moved into an album was
 * unknown to the inbox again, so a re-scanning app uploaded it a second time. The index knows
 * where every content of the space is, and <code>?action=check</code> answers from it.
 * </p>
 *
 * <p>
 * The background pass is driven synchronously here ({@link HashIndex#indexNow()}), except where
 * the point is the thread itself; a servlet built by a test never starts one by itself, see
 * {@link ImageServlet#startIndexing()}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestHashIndex extends ShareTestCase {

	private static final String INBOX = "/" + SharingFixture.CAROLS_ALBUM + "/";

	private static final String PUBLIC = "/" + SharingFixture.PUBLIC + "/";

	private static final String OPEN = SharingFixture.PUBLIC + "/open.jpg";

	// --- What the index answers. ---

	public void testAPhotoOfAnotherAlbumIsPresentWithItsPath() throws Exception {
		index();

		PresentFile present = single(check(INBOX, SharingFixture.ALICE, hashOf(OPEN)));

		assertEquals("The check answers for the whole space, by the path the photo is at.",
			SharingFixture.PUBLIC + "/open.jpg", present.getName());
		assertEquals(hashOf(OPEN), present.getHash());
	}

	public void testTheFoldersOwnPhotoKeepsItsBareName() throws Exception {
		index();

		PresentFile present = single(check(PUBLIC, SharingFixture.ALICE, hashOf(OPEN)));

		assertEquals("A photo of the addressed folder is named as it always was.", "open.jpg",
			present.getName());
	}

	public void testWhileNothingIsIndexedOnlyTheFolderItselfAnswers() throws Exception {
		UploadCheckResult result = check(INBOX, SharingFixture.ALICE, hashOf(OPEN));

		assertEquals("Nothing is indexed yet, so the other album is unknown.",
			Collections.emptyList(), result.getPresent());
		IndexProgress progress = result.getIndexed();
		assertNotNull("The answer must say how far the index has got.", progress);
		assertTrue("An unfinished index must never read as a finished one: " + progress.getDone() + "/"
			+ progress.getTotal(), progress.getDone() < progress.getTotal());
	}

	public void testAFinishedIndexSaysSo() throws Exception {
		index();

		IndexProgress progress = check(INBOX, SharingFixture.ALICE, hashOf(OPEN)).getIndexed();

		assertEquals(progress.getTotal(), progress.getDone());
		assertEquals("The folders holding images: the zoo, Public, Private and the inbox.",
			4, progress.getTotal());
	}

	// --- The file. ---

	public void testTheIndexFileIsWrittenAndReloadsToTheSameMap() throws Exception {
		index();
		servlet().index().flush();

		File file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(HashIndex.FILE_NAME).toFile();
		assertTrue("The index must be persisted: " + file, file.isFile());
		String written = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		assertTrue("The index must be versioned: " + written, written.contains("\"version\":1"));

		HashIndex reloaded = new HashIndex(_base);
		try {
			assertEquals(SharingFixture.PUBLIC + "/open.jpg", reloaded.pathOf(hashOf(OPEN)));
			assertEquals(SharingFixture.CAROLS_ALBUM + "/carols.jpg",
				reloaded.pathOf(hashOf(SharingFixture.CAROLS_ALBUM + "/carols.jpg")));
		} finally {
			reloaded.shutdown();
		}
	}

	public void testASidecarChangedAfterTheIndexWasWrittenIsReadAgain() throws Exception {
		index();
		servlet().index().flush();

		// A sidecar rewritten behind the server's back, claiming contents nobody ever stored.
		String invented = "b".repeat(64);
		File photo = _base.resolve(OPEN).toFile();
		Path sidecar = _base.resolve(SharingFixture.PUBLIC).resolve(HashCache.FILE_NAME);
		Files.write(sidecar, ("{\"version\":1,\"files\":{\"open.jpg\":{\"size\":" + photo.length()
			+ ",\"modified\":" + photo.lastModified() + ",\"sha256\":\"" + invented + "\"}}}")
				.getBytes(StandardCharsets.UTF_8));
		assertTrue(sidecar.toFile().setLastModified(sidecar.toFile().lastModified() + 10_000));

		HashIndex restarted = new HashIndex(_base);
		try {
			restarted.indexNow();
			assertEquals("A folder whose sidecar changed is read again.",
				SharingFixture.PUBLIC + "/open.jpg", restarted.pathOf(invented));
		} finally {
			restarted.shutdown();
		}
	}

	public void testTheBackgroundPassFinishesOnItsOwn() throws Exception {
		servlet().startIndexing();

		assertTrue("The background pass must finish.", servlet().index().awaitPass(30_000));
		assertTrue(servlet().index().isComplete());
		assertEquals(SharingFixture.PUBLIC + "/open.jpg", servlet().index().pathOf(hashOf(OPEN)));
	}

	// --- Staying current. ---

	public void testAnUploadedPhotoIsFoundAtOnce() throws Exception {
		index();
		byte[] photo = photo("fresh");

		assertEquals(HttpServletResponse.SC_OK,
			upload(INBOX, SharingFixture.ALICE, "fresh.jpg", photo).status());

		assertEquals("The upload wrote the sidecar, so the index knows it.",
			SharingFixture.CAROLS_ALBUM + "/fresh.jpg",
			single(check(PUBLIC, SharingFixture.ALICE, HashCache.sha256(photo))).getName());
	}

	public void testAMovedPhotoIsFoundAtItsNewPlace() throws Exception {
		index();
		String hash = hashOf(SharingFixture.CAROLS_ALBUM + "/carols.jpg");

		assertEquals(HttpServletResponse.SC_OK,
			move(INBOX, SharingFixture.PUBLIC, SharingFixture.ALICE, "carols.jpg").status());

		assertEquals("A photo that moved is present where it went, not where it was.",
			SharingFixture.PUBLIC + "/carols.jpg",
			single(check(INBOX, SharingFixture.ALICE, hash)).getName());
	}

	public void testADeletedAlbumIsNotOfferedAnyMore() throws Exception {
		index();
		String hash = hashOf(OPEN);

		// A photo is deleted by deleting the album it is in, see issue #109; what goes into the
		// trash lies below the space's own folder, which the index never looks at.
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");
		FakeResponse deleted = post("/", "{\"target\":\"\",\"names\":[{\"name\":\"" + SharingFixture.PUBLIC
			+ "\"}]}", SharingFixture.ALICE, parameters);
		assertEquals(body(deleted), HttpServletResponse.SC_OK, deleted.status());
		assertFalse(Files.exists(_base.resolve(OPEN)));

		assertEquals("What went into the trash is no answer any more.",
			Collections.emptyList(), check(INBOX, SharingFixture.ALICE, hash).getPresent());
	}

	public void testAPhotoThatVanishedBehindTheServersBackIsForgotten() throws Exception {
		index();
		String hash = hashOf(OPEN);
		Files.delete(_base.resolve(OPEN));

		assertEquals("An index entry whose file is gone is never an answer.",
			Collections.emptyList(), check(INBOX, SharingFixture.ALICE, hash).getPresent());
	}

	// --- The share link. ---

	public void testAShareLinkNeverLooksOutsideItsFolder() throws Exception {
		index();
		String link = shareToken();

		UploadCheckResult result =
			check("/", link, hashOf(OPEN), hashOf(SharingFixture.ZOO + "/public.jpg"));

		assertEquals("A link sees no more of the space than its folder.",
			Collections.singletonList("public.jpg"), names(result));
		assertNull("A link is told nothing about the space's index either.", result.getIndexed());
	}

	// --- The sweep. ---

	public void testTheSweepSetsAsideExactlyTheDuplicates() throws Exception {
		byte[] open = Files.readAllBytes(_base.resolve(OPEN));
		assertEquals(HttpServletResponse.SC_OK,
			upload(INBOX, SharingFixture.ALICE, "copy.jpg", open).status());
		index();

		MoveResult result = sweep(INBOX, SharingFixture.ALICE);

		assertEquals("Only the photo that is elsewhere too is named.", 1, result.getOutcomes().size());
		MoveOutcome outcome = result.getOutcomes().get(0);
		assertEquals("copy.jpg", outcome.getName());
		assertTrue("The message must name where the photo already is: " + outcome.getMessage(),
			outcome.getMessage().contains(SharingFixture.PUBLIC + "/open.jpg"));

		assertFalse("The duplicate must have left the album.",
			Files.exists(_base.resolve(SharingFixture.CAROLS_ALBUM).resolve("copy.jpg")));
		assertTrue("The photo that is nowhere else must stay.",
			Files.exists(_base.resolve(SharingFixture.CAROLS_ALBUM).resolve("carols.jpg")));
		assertTrue("The original must be untouched.",
			Arrays.equals(open, Files.readAllBytes(_base.resolve(OPEN))));

		List<String> aside = entries(_base.resolve(UserStore.DIRECTORY_NAME).resolve("duplicates"));
		assertEquals("Nothing is deleted: the file is set aside under its hash.",
			Collections.singletonList(HashCache.sha256(open) + "-copy.jpg"), aside);

		restartServer();
		AlbumInfo album = album(get(INBOX, "json", SharingFixture.ALICE));
		assertFalse("The album's sidecar must have lost the part.", names(album).contains("copy.jpg"));
		assertTrue(names(album).contains("carols.jpg"));
	}

	public void testTheSweepFindsNothingWhereNothingIsDuplicated() throws Exception {
		index();

		assertEquals(Collections.emptyList(), sweep(INBOX, SharingFixture.ALICE).getOutcomes());
	}

	public void testTwoCopiesInOneAlbumAreNotDuplicatesOfTheSpace() throws Exception {
		// The same contents twice in the very album that is swept: the question is whether the
		// library has the photo *elsewhere*, and it has not.
		byte[] photo = photo("twin");
		assertEquals(HttpServletResponse.SC_OK,
			upload(INBOX, SharingFixture.ALICE, "twin.jpg", photo).status());
		Files.copy(_base.resolve(SharingFixture.CAROLS_ALBUM).resolve("twin.jpg"),
			_base.resolve(SharingFixture.CAROLS_ALBUM).resolve("twin-2.jpg"));
		index();

		assertEquals(Collections.emptyList(), sweep(INBOX, SharingFixture.ALICE).getOutcomes());
		assertTrue(Files.exists(_base.resolve(SharingFixture.CAROLS_ALBUM).resolve("twin-2.jpg")));
	}

	public void testTheSweepNeedsTheEditRight() throws Exception {
		index();

		FakeResponse response = sweepResponse(INBOX, SharingFixture.BOB);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(ImageServlet.DUPLICATES_REFUSED, errorMessage(response));
	}

	public void testAShareLinkMaySweepNothing() throws Exception {
		index();

		FakeResponse response = sweepResponse("/", shareToken());

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
	}

	// --- What indexing is allowed to change. ---

	public void testIndexingWritesNothingButSidecarsAndItsOwnFile() throws Exception {
		String before = fingerprintOriginals(_base);

		index();

		assertEquals("Indexing must touch no original and no album sidecar.", before,
			fingerprintOriginals(_base));
		assertTrue("Every album holding images gets its hashes written, once.",
			Files.exists(_base.resolve(SharingFixture.PUBLIC).resolve(HashCache.FILE_NAME)));
	}

	// --- Helpers. ---

	/** Runs the whole background pass of the servlet's space in this thread. */
	private void index() throws Exception {
		servlet().index().indexNow();
	}

	private UploadCheckResult check(String pathInfo, String token, String... hashes) throws Exception {
		FakeResponse response = checkResponse(pathInfo, token, hashes);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return UploadCheckResult.readUploadCheckResult(reader(body(response)));
	}

	private FakeResponse checkResponse(String pathInfo, String token, String... hashes) throws Exception {
		StringBuilder body = new StringBuilder("{\"hashes\":[");
		for (int n = 0; n < hashes.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"hash\":\"").append(hashes[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "check");
		return post(pathInfo, body.toString(), token, parameters);
	}

	private MoveResult sweep(String pathInfo, String token) throws Exception {
		FakeResponse response = sweepResponse(pathInfo, token);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(body(response)));
	}

	private FakeResponse sweepResponse(String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "find-duplicates");
		return post(pathInfo, "{}", token, parameters);
	}

	/** A share link on the zoo album, with the right to contribute to it. */
	private String shareToken() throws Exception {
		FakeResponse response = share("/" + SharingFixture.ZOO + "/", SharingFixture.ALICE,
			shareBody("Grandma", "", 1, 0, Rights.VIEW, Rights.CONTRIBUTE));
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return ShareLinkCreated.readShareLinkCreated(reader(body(response))).getToken();
	}

	private String hashOf(String path) throws Exception {
		return HashCache.sha256(_base.resolve(path).toFile());
	}

	private static PresentFile single(UploadCheckResult result) {
		assertEquals("Expected exactly one present file: " + names(result), 1, result.getPresent().size());
		return result.getPresent().get(0);
	}

	private static List<String> names(UploadCheckResult result) {
		List<String> names = new ArrayList<>();
		for (PresentFile file : result.getPresent()) {
			names.add(file.getName());
		}
		return names;
	}

	private static List<String> names(AlbumInfo album) {
		List<String> names = new ArrayList<>();
		album.getParts().forEach(part -> {
			if (part instanceof ImagePart) {
				names.add(((ImagePart) part).getName());
			}
		});
		return names;
	}

	private static List<String> entries(Path folder) throws Exception {
		List<String> result = new ArrayList<>();
		if (!Files.isDirectory(folder)) {
			return result;
		}
		try (Stream<Path> files = Files.list(folder)) {
			files.map(path -> path.getFileName().toString()).sorted().forEach(result::add);
		}
		return result;
	}

	/** A tiny JPEG with contents of its own, so that no upload finds a duplicate. */
	private static byte[] photo(String seed) throws Exception {
		java.util.Random random = new java.util.Random(("index/" + seed).hashCode());
		java.awt.image.BufferedImage image =
			new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	/**
	 * A fingerprint of everything in the library that indexing must not touch.
	 *
	 * <p>
	 * Everything but the hash sidecars — which indexing writes, that being its whole job — and the
	 * server's own folder, which holds the index file.
	 * </p>
	 */
	private static String fingerprintOriginals(Path root) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile).forEach(files::add);
		}
		files.sort(Comparator.comparing(Path::toString));
		for (Path file : files) {
			String relative = root.relativize(file).toString();
			if (relative.endsWith(HashCache.FILE_NAME) || relative.startsWith(UserStore.DIRECTORY_NAME)) {
				continue;
			}
			digest.update(relative.getBytes(StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		StringBuilder result = new StringBuilder();
		for (byte b : digest.digest()) {
			result.append(String.format("%02x", Byte.valueOf(b)));
		}
		return result.toString();
	}
	/** Probe: an album deleted into the trash (#109) leaves the index — its photo is no longer "present". */
	public void testProbeADeletedAlbumLeavesTheIndex() throws Exception {
		index();
		String hash = hashOf(OPEN);
		assertEquals(1, check(INBOX, SharingFixture.ALICE, hash).getPresent().size());
		int slash = SharingFixture.PUBLIC.lastIndexOf('/');
		String parent = slash < 0 ? "/" : "/" + SharingFixture.PUBLIC.substring(0, slash) + "/";
		String name = slash < 0 ? SharingFixture.PUBLIC : SharingFixture.PUBLIC.substring(slash + 1);
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");
		FakeResponse deleted = post(parent, "{\"target\":\"\",\"names\":[{\"name\":\"" + name + "\"}]}",
			SharingFixture.ALICE, parameters);
		assertEquals(body(deleted), HttpServletResponse.SC_OK, deleted.status());

		assertTrue("A photo that went to the trash with its album is not present in the space any more.",
			check(INBOX, SharingFixture.ALICE, hash).getPresent().isEmpty());
	}

	/** Probe: the sweep sets aside a duplicate that is a member of a group, and the group of one that remains becomes a plain image. */
	public void testProbeTheSweepDetachesAGroupMember() throws Exception {
		byte[] open = Files.readAllBytes(_base.resolve(OPEN));
		assertEquals(HttpServletResponse.SC_OK,
			upload(INBOX, SharingFixture.ALICE, "copy.jpg", open).status());
		AlbumInfo album = album(get(INBOX, "json", SharingFixture.ALICE));
		ImagePart copy = null, carols = null;
		for (AlbumPart part : new java.util.ArrayList<>(album.getParts())) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals("copy.jpg")) copy = (ImagePart) part;
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals("carols.jpg")) carols = (ImagePart) part;
		}
		assertNotNull(copy); assertNotNull(carols);
		album.removePart(copy); album.removePart(carols);
		ImageGroup group = ImageGroup.create().setRepresentative(0);
		group.addImage(copy); group.addImage(carols);
		album.addPart(group);
		java.io.StringWriter out = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json = new de.haumacher.msgbuf.json.JsonWriter(new WriterAdapter(out))) {
			album.writeTo(json);
		}
		FakeResponse stored = put(INBOX, out.toString(), SharingFixture.ALICE);
		assertEquals(body(stored), HttpServletResponse.SC_OK, stored.status());
		index();

		MoveResult result = sweep(INBOX, SharingFixture.ALICE);

		assertEquals(1, result.getOutcomes().size());
		assertEquals("copy.jpg", result.getOutcomes().get(0).getName());
		restartServer();
		AlbumInfo after = album(get(INBOX, "json", SharingFixture.ALICE));
		assertEquals("The group of one is replaced by its image.", 1, after.getParts().size());
		assertTrue(after.getParts().get(0) instanceof ImagePart);
		assertEquals("carols.jpg", ((ImagePart) after.getParts().get(0)).getName());
	}

}
