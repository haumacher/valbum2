/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.upload.HashCache;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Test case for a selection of originals downloaded as one archive, see issue #164.
 *
 * <p>
 * <code>POST &lt;album&gt;/?action=zip</code> with the names of a {@link
 * de.haumacher.imageServer.shared.model.MoveRequest}: every named photograph is asked what its
 * original would be asked, and a refusal is answered before the first byte of the archive.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestZipDownload extends ShareTestCase {

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	/** The inbox of the space, see issue #226. */
	private static final String INBOX = de.haumacher.imageServer.auth.SpaceStore.DEFAULT_INBOX;

	public void testTwoImagesAreZippedByteForByte() throws Exception {
		String before = albumFingerprint("alice");

		FakeResponse response = zip(ZOO, SharingFixture.ALICE, "public.jpg", "members.jpg");

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertEquals("application/zip", response.contentType());
		assertEquals("attachment; filename=\"2024-05-01 Zoo.zip\"; filename*=UTF-8''2024-05-01%20Zoo.zip",
			response.header("Content-Disposition"));
		assertEquals("no-store", response.header("Cache-Control"));
		assertEquals("Authorization, " + de.haumacher.imageServer.auth.AuthService.CONTACT_HEADER, response.header("Vary"));

		Map<String, byte[]> entries = entries(response);
		assertEquals(Arrays.asList("public.jpg", "members.jpg"), Arrays.asList(entries.keySet().toArray()));
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			assertTrue("The entry is the original, byte for byte: " + entry.getKey(),
				Arrays.equals(original(entry.getKey()), entry.getValue()));
		}
		assertEquals("Nothing was written beside the originals.", before, albumFingerprint("alice"));
	}

	public void testANameTwiceIsOneEntry() throws Exception {
		FakeResponse response = zip(ZOO, SharingFixture.ALICE, "public.jpg", "public.jpg");

		assertEquals(Arrays.asList("public.jpg"), Arrays.asList(entries(response).keySet().toArray()));
	}

	public void testAPrivateImageIsRefusedForANonPrivateMember() throws Exception {
		FakeResponse response = zip(ZOO, SharingFixture.CAROL, "public.jpg", "private.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(response));
	}

	public void testALinkWithARatingLimitRefusesAPartBelowIt() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		FakeResponse response = zip("/", token, "public.jpg", REJECTED);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(ImageServlet.RATING_REFUSED, errorMessage(response));
	}

	public void testALinkWithDownloadIsAnswered() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		FakeResponse response = zip("/", token, "public.jpg");

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertTrue(Arrays.equals(original("public.jpg"), entries(response).get("public.jpg")));
		assertEquals("The archive is named by the shared folder, not by the link's root.",
			"attachment; filename=\"2024-05-01 Zoo.zip\"; filename*=UTF-8''2024-05-01%20Zoo.zip",
			response.header("Content-Disposition"));
	}

	public void testALinkShowsNoMembersImage() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		FakeResponse response = zip("/", token, "members.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(response));
	}

	public void testACallerWithoutDownloadIsRefused() throws Exception {
		String token = zooToken(Rights.VIEW);

		FakeResponse response = zip("/", token, "public.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.DOWNLOAD_REFUSED, errorMessage(response));
	}

	public void testAnUnknownNameIsNotFound() throws Exception {
		FakeResponse response = zip(ZOO, SharingFixture.ALICE, "public.jpg", "nowhere.jpg");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(ImageServlet.notInAlbum("nowhere.jpg"), errorMessage(response));
	}

	public void testANameIsNeverAnAddress() throws Exception {
		for (String name : Arrays.asList("../../Private/secret.jpg", ".hashes.json", "index.json", "")) {
			FakeResponse response = zip(ZOO, SharingFixture.ALICE, name);
			assertEquals(name, HttpServletResponse.SC_NOT_FOUND, response.status());
			assertEquals(ImageServlet.notInAlbum(name), errorMessage(response));
		}
	}

	public void testNothingNamedIsABadRequest() throws Exception {
		FakeResponse response = zip(ZOO, SharingFixture.ALICE);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ImageServlet.ZIP_EMPTY, errorMessage(response));
	}

	public void testAnUnreadableBodyIsABadRequest() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		FakeResponse response = post(ZOO, "not json", SharingFixture.ALICE, parameters);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ImageServlet.ZIP_UNREADABLE, errorMessage(response));
	}

	public void testAnInboxIsConfinedAsItIsListed() throws Exception {
		inbox();

		FakeResponse all = zip("/" + INBOX + "/", SharingFixture.ALICE, "bobs.jpg", "carols.jpg");
		assertEquals("Whoever may edit the inbox sees all of it.", HttpServletResponse.SC_OK, all.status());
		assertEquals(2, entries(all).size());

		FakeResponse own = zip("/" + INBOX + "/", SharingFixture.BOB, "bobs.jpg");
		assertEquals("A contributor downloads their own contribution.", HttpServletResponse.SC_OK, own.status());

		FakeResponse foreign = zip("/" + INBOX + "/", SharingFixture.BOB, "bobs.jpg", "carols.jpg");
		assertEquals("Somebody else's photograph is not there for a contributor.",
			HttpServletResponse.SC_NOT_FOUND, foreign.status());
		assertEquals(ImageServlet.notInAlbum("carols.jpg"), errorMessage(foreign));

		FakeResponse viewer = zip("/" + INBOX + "/", SharingFixture.DAVE, "bobs.jpg");
		assertEquals("A viewer is not shown an inbox at all.", HttpServletResponse.SC_NOT_FOUND, viewer.status());
		assertEquals(Inboxes.NOT_FOUND, errorMessage(viewer));
	}

	// --- Paths below a plain folder, issue #243. ---

	public void testPathsOfTwoAlbumsAreOneArchive() throws Exception {
		park();
		String before = albumFingerprint("alice");

		FakeResponse response = zip(YEAR, SharingFixture.ALICE, "2024-05-01 Zoo/public.jpg",
			"2024-06-01 Park/public.jpg", "2024-06-01 Park/IMG_1.jpg");

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertEquals("attachment; filename=\"2024.zip\"; filename*=UTF-8''2024.zip",
			response.header("Content-Disposition"));
		Map<String, byte[]> entries = entries(response);
		assertEquals("Each entry keeps its path: the two albums' public.jpg do not collide.",
			Arrays.asList("2024-05-01 Zoo/public.jpg", "2024-06-01 Park/public.jpg", "2024-06-01 Park/IMG_1.jpg",
				"2024-06-01 Park/IMG_1.CR2"),
			Arrays.asList(entries.keySet().toArray()));
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			assertTrue("The entry is the original, byte for byte: " + entry.getKey(),
				Arrays.equals(Files.readAllBytes(_base.resolve(SharingFixture.YEAR).resolve(entry.getKey())),
					entry.getValue()));
		}
		assertEquals("Nothing was written beside the originals.", before, albumFingerprint("alice"));
	}

	public void testAPathIsAskedWhatItsOwnAlbumAsks() throws Exception {
		park();

		FakeResponse path = zip(YEAR, SharingFixture.CAROL, "2024-05-01 Zoo/public.jpg", "2024-06-01 Park/private.jpg");
		FakeResponse plain = zip("/" + PARK + "/", SharingFixture.CAROL, "private.jpg");
		assertEquals("A photograph above the clearance refuses the whole download.", HttpServletResponse.SC_FORBIDDEN,
			path.status());
		assertEquals(plain.status(), path.status());
		assertEquals("The sentence a plain name gets.", errorMessage(plain), errorMessage(path));
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(path));
	}

	public void testAPathIntoAnInboxIsConfinedAsItIsListed() throws Exception {
		inbox();

		FakeResponse own = zip("/", SharingFixture.BOB, INBOX + "/bobs.jpg");
		assertEquals(own.body(), HttpServletResponse.SC_OK, own.status());
		assertEquals(Arrays.asList(INBOX + "/bobs.jpg"), Arrays.asList(entries(own).keySet().toArray()));

		FakeResponse foreign = zip("/", SharingFixture.BOB, INBOX + "/bobs.jpg", INBOX + "/carols.jpg");
		assertEquals("Somebody else's photograph is not there for a contributor.", HttpServletResponse.SC_NOT_FOUND,
			foreign.status());
		assertEquals(ImageServlet.notInAlbum(INBOX + "/carols.jpg"), errorMessage(foreign));

		FakeResponse viewer = zip("/", SharingFixture.DAVE, INBOX + "/bobs.jpg");
		assertEquals("A viewer is not shown an inbox at all.", HttpServletResponse.SC_NOT_FOUND, viewer.status());
		assertEquals(ImageServlet.notInAlbum(INBOX + "/bobs.jpg"), errorMessage(viewer));
	}

	public void testAPathIsNeverAnAddress() throws Exception {
		park();
		for (String name : Arrays.asList("2024-05-01 Zoo/../../Private/secret.jpg", "../Private/secret.jpg",
			"2024-05-01 Zoo/.hashes.json", "2024-05-01 Zoo/index.json", "/2024-05-01 Zoo/public.jpg",
			"2024-05-01 Zoo//public.jpg", "2024-05-01 Zoo/", ".hidden/public.jpg", "2024-05-01 Zoo/nowhere.jpg")) {
			FakeResponse response = zip(YEAR, SharingFixture.ALICE, "2024-06-01 Park/public.jpg", name);
			assertEquals(name, HttpServletResponse.SC_NOT_FOUND, response.status());
			assertEquals(ImageServlet.notInAlbum(name), errorMessage(response));
		}
		// A backslash, spelt as JSON spells it.
		FakeResponse backslash = zip(YEAR, SharingFixture.ALICE, "2024-05-01 Zoo\\\\public.jpg");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, backslash.status());
		assertEquals(ImageServlet.notInAlbum("2024-05-01 Zoo\\public.jpg"), errorMessage(backslash));
		FakeResponse slashAndBackslash = zip(YEAR, SharingFixture.ALICE, "2024-06-01 Park/..\\\\2024-05-01 Zoo/public.jpg");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, slashAndBackslash.status());
	}

	// --- Helpers. ---

	private static final String YEAR = "/" + SharingFixture.YEAR + "/";

	/** A second album below the year, beside the zoo. */
	private static final String PARK = SharingFixture.YEAR + "/2024-06-01 Park";

	/**
	 * A second album below the year: a photograph named as one of the zoo, a private one, and one
	 * with its raw companion (issue #191).
	 */
	private void park() throws Exception {
		SharingFixture.album(_base, PARK, "Park",
			"[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3}],"
				+ "[\"ImagePart\",{\"name\":\"private.jpg\",\"width\":4,\"height\":3,\"privacy\":2}],"
				+ "[\"ImagePart\",{\"name\":\"IMG_1.jpg\",\"width\":4,\"height\":3,\"raw\":\"IMG_1.CR2\"}]",
			"public.jpg", "private.jpg", "IMG_1.jpg");
		Files.write(_base.resolve(PARK).resolve("IMG_1.CR2"), "raw bytes".getBytes(StandardCharsets.UTF_8));
		restartServer();
	}

	private FakeResponse zip(String pathInfo, String token, String... names) throws Exception {
		StringBuilder body = new StringBuilder("{\"target\":\"\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		return post(pathInfo, body.toString(), token, parameters);
	}

	private static Map<String, byte[]> entries(FakeResponse response) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		Map<String, byte[]> result = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.bodyBytes()))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				result.put(entry.getName(), zip.readAllBytes());
			}
		}
		return result;
	}

	private byte[] original(String name) throws IOException {
		return Files.readAllBytes(_base.resolve(SharingFixture.ZOO).resolve(name));
	}

	/** An inbox holding one photograph of bob's and one of carol's. */
	private void inbox() throws Exception {
		SharingFixture.album(_base, INBOX, INBOX,
			"[\"ImagePart\",{\"name\":\"bobs.jpg\",\"width\":4,\"height\":3}],"
				+ "[\"ImagePart\",{\"name\":\"carols.jpg\",\"width\":4,\"height\":3}]",
			"bobs.jpg", "carols.jpg");
		Path folder = _base.resolve(INBOX);
		String sidecar = new String(Files.readAllBytes(folder.resolve("index.json")), StandardCharsets.UTF_8);
		Files.write(folder.resolve("index.json"),
			sidecar.replace("{\"title\"", "{\"kind\":\"INBOX\",\"title\"").getBytes(StandardCharsets.UTF_8));
		StringBuilder json = new StringBuilder("{\"version\":1,\"files\":{");
		String[][] contributors = { { "bobs.jpg", "user:bob" }, { "carols.jpg", "user:carol" } };
		for (int n = 0; n < contributors.length; n++) {
			File file = folder.resolve(contributors[n][0]).toFile();
			json.append(n == 0 ? "" : ",").append("\"").append(contributors[n][0]).append("\":{\"size\":")
				.append(file.length()).append(",\"modified\":").append(file.lastModified())
				.append(",\"sha256\":\"").append(HashCache.sha256(file)).append("\",\"contributor\":\"")
				.append(contributors[n][1]).append("\",\"contributorLabel\":\"")
				.append(contributors[n][1].substring(5)).append("\"}");
		}
		json.append("}}");
		Files.write(folder.resolve(".hashes.json"), json.toString().getBytes(StandardCharsets.UTF_8));
		restartServer();
	}
}
