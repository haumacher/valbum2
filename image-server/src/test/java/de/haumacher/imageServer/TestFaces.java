/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detecting, grouping and answering the faces of an album, see issue #124.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaces extends FacesTestCase {

	// --- What is detected. ---

	/**
	 * Every portrait is looked at once, the boxes are inside the picture, and the two pictures of
	 * one person end up in one group while the third is a group of its own.
	 */
	public void testTheFacesAreFoundAndGrouped() throws Exception {
		createSpace(A_ONE, A_TWO, B, VIDEO);
		if (!detectorAvailable()) {
			return;
		}
		index();

		FaceCache cache = new FaceCache(album());
		assertEquals("One entry per photograph, and none for the video.", 3, cache.hashes().size());

		String clusterOne = onlyCluster(cache, A_ONE);
		String clusterTwo = onlyCluster(cache, A_TWO);
		String other = onlyCluster(cache, B);
		assertEquals("The same person twice is one group.", clusterOne, clusterTwo);
		assertFalse("Somebody else is a group of their own.", clusterOne.equals(other));
	}

	/** Every box lies inside the unit square and has a positive size. */
	public void testTheBoxesAreNormalised() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();

		FaceCache cache = new FaceCache(album());
		for (String hash : cache.hashes()) {
			for (FaceCache.Face face : cache.facesOf(hash)) {
				assertTrue("x=" + face.getX(), face.getX() >= 0 && face.getX() <= 1);
				assertTrue("y=" + face.getY(), face.getY() >= 0 && face.getY() <= 1);
				assertTrue("w=" + face.getW(), face.getW() > 0 && face.getX() + face.getW() <= 1.0001);
				assertTrue("h=" + face.getH(), face.getH() > 0 && face.getY() + face.getH() <= 1.0001);
				assertEquals(128, face.getEmbedding().length);
			}
		}
	}

	/** The cache says which model looked, so that another one re-detects by itself. */
	public void testTheModelIsStamped() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		index();

		String written = Files.readString(FaceCache.file(album()).toPath(), StandardCharsets.UTF_8);
		assertTrue(written, written.contains("\"model\":\"" + FaceDetection.MODEL + "\""));

		// A cache another model wrote is not read at all.
		Files.writeString(FaceCache.file(album()).toPath(),
			written.replace(FaceDetection.MODEL, "somebody-else/9"), StandardCharsets.UTF_8);
		assertTrue("A cache of another model counts for nothing.", new FaceCache(album()).isEmpty());
	}

	/** A photograph whose contents changed is looked at again; an unchanged album is not touched. */
	public void testAChangedFileIsLookedAtAgain() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		index();
		File cacheFile = FaceCache.file(album());
		assertEquals(1, new FaceCache(album()).hashes().size());

		// A second pass over an unchanged album writes nothing at all.
		long stamp = cacheFile.lastModified();
		assertTrue(cacheFile.setLastModified(stamp - 5000));
		stamp = cacheFile.lastModified();
		index();
		assertEquals("An unchanged album is not written again.", stamp, cacheFile.lastModified());

		// Different contents under the same name: a new hash, so a new entry.
		Files.copy(new File(PORTRAITS, B).toPath(), new File(album(), A_ONE).toPath(),
			java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		index();
		FaceCache after = new FaceCache(album());
		assertEquals("What the album no longer holds is forgotten.", 1, after.hashes().size());
		assertTrue("The new contents were looked at.", cacheFile.lastModified() > stamp);
	}

	/** Nothing but the album's own cache directory is ever written. */
	public void testNothingButTheCacheIsWritten() throws Exception {
		createSpace(A_ONE, A_TWO, B, VIDEO);
		if (!detectorAvailable()) {
			return;
		}
		// The hash sidecar is the one file beside the photographs the server keeps, and the index
		// fills it exactly as an upload would; everything else must be byte for byte the same.
		Map<String, String> before = hashes();
		index();
		Map<String, String> after = hashes();
		after.remove(album().toPath().relativize(album().toPath()).toString());
		for (Map.Entry<String, String> entry : before.entrySet()) {
			assertEquals("'" + entry.getKey() + "' was changed.", entry.getValue(),
				after.get(entry.getKey()));
		}
		assertTrue("The faces belong into the album's cache.", FaceCache.file(album()).isFile());
	}

	// --- The wire. ---

	/** A signed-in member is answered the boxes and the groups. */
	public void testAMemberIsAnsweredTheFaces() throws Exception {
		createSpace(A_ONE, A_TWO, B, VIDEO);
		if (!detectorAvailable()) {
			return;
		}
		index();

		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		assertFalse("Everything is indexed.", album.isFacesPending());
		ImagePart one = image(album, A_ONE);
		assertEquals(1, one.getFaces().size());
		FaceInfo face = one.getFaces().get(0);
		assertEquals(0, face.getIndex());
		assertFalse("A face of an indexed album is in a group.", face.getCluster().isEmpty());
		assertTrue(face.getW() > 0 && face.getH() > 0);
		assertEquals("A video has no faces.", 0, image(album, VIDEO).getFaces().size());
		assertEquals("The same person is in the same group.", face.getCluster(),
			image(album, A_TWO).getFaces().get(0).getCluster());
	}

	/** An anonymous visitor of an open space is answered the album, and not one face. */
	public void testAnAnonymousCallerIsAnsweredNoFace() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();

		AlbumInfo album = album("/" + ALBUM + "/", null);
		assertEquals("The album itself is answered as before.", 2, images(album).size());
		for (ImagePart image : images(album)) {
			assertEquals("An anonymous caller sees no face.", 0, image.getFaces().size());
		}
		assertFalse(album.isFacesPending());
	}

	/** A share link never shows a face, whoever made it. */
	public void testAShareBearerIsAnsweredNoFace() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		String token = shareToken();

		AlbumInfo album = album("/", token);
		assertEquals(2, images(album).size());
		for (ImagePart image : images(album)) {
			assertEquals("A share link shows no face.", 0, image.getFaces().size());
		}
	}

	/**
	 * The author previewing their own album as the public sees it is shown what the public is
	 * shown: no face, and no crop either (issue #46).
	 */
	public void testThePreviewOfThePublicViewShowsNoFace() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		assertEquals("The member sees the face.", 1,
			image(album("/" + ALBUM + "/", _adminToken), A_ONE).getFaces().size());

		AlbumInfo asPublic = album("/" + ALBUM + "/", _adminToken, Privacy.VIEW_AS_PUBLIC);
		assertEquals("The album itself is still answered.", 2, images(asPublic).size());
		for (ImagePart image : images(asPublic)) {
			assertEquals("A preview that showed a face the public never sees would be worth nothing.",
				0, image.getFaces().size());
		}
		assertFalse(asPublic.isFacesPending());

		assertEquals("And the crop is not there either.", 404,
			face("/" + ALBUM + "/" + A_ONE, "0", _adminToken, Privacy.VIEW_AS_PUBLIC).status());
	}

	/** Previewing as a member shows what a member is shown, faces included. */
	public void testThePreviewOfTheMemberViewStillShowsFaces() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();

		AlbumInfo asMember = album("/" + ALBUM + "/", _adminToken, Privacy.VIEW_AS_MEMBERS);
		assertEquals("A face is answered at the level of a member, so a member sees it.", 1,
			image(asMember, A_ONE).getFaces().size());

		FakeResponse crop = face("/" + ALBUM + "/" + A_ONE, "0", _adminToken, Privacy.VIEW_AS_MEMBERS);
		assertEquals(crop.body(), 200, crop.status());
		assertEquals("image/jpeg", crop.contentType());
	}

	/** The embeddings stay on the server; nothing on the wire ever mentions one. */
	public void testNoEmbeddingEverLeavesTheServer() throws Exception {
		createSpace(A_ONE, A_TWO, B);
		if (!detectorAvailable()) {
			return;
		}
		index();

		FakeResponse response = get("/" + ALBUM + "/", "json", _adminToken);
		assertEquals(200, response.status());
		assertFalse("An embedding must never be answered: " + response.body(),
			response.body().contains("embedding"));
		assertTrue("The cache does hold them.",
			Files.readString(FaceCache.file(album()).toPath(), StandardCharsets.UTF_8)
				.contains("\"embedding\""));
	}

	/** Writing the album back stores no face in the album's own sidecar. */
	public void testASidecarWriteCarriesNoFace() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();

		FakeResponse read = get("/" + ALBUM + "/", "json", _adminToken);
		assertTrue("The answer really carries a face.", read.body().contains("\"cluster\""));

		FakeResponse written = put("/" + ALBUM + "/", read.body(), _adminToken);
		assertEquals(written.body(), 200, written.status());

		String sidecar = Files.readString(new File(album(), "index.json").toPath(), StandardCharsets.UTF_8);
		assertFalse("A detection is not the author's statement: " + sidecar, sidecar.contains("\"cluster\""));
		assertFalse(sidecar.contains("\"embedding\""));
		assertFalse("And neither is how far the detector has got.", sidecar.contains("facesPending\":true"));
	}

	// --- The crops. ---

	/** A member gets the crop; a share bearer is told that there is nothing there. */
	public void testTheFaceCrop() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();

		FakeResponse crop = face("/" + ALBUM + "/" + A_ONE, "0", _adminToken);
		assertEquals(crop.body(), 200, crop.status());
		assertEquals("image/jpeg", crop.contentType());
		assertTrue("A JPEG has bytes.", crop.bodyBytes().length > 0);
		assertTrue("The crop is cached beside the preview.",
			FaceIndex.cropFile(new File(album(), A_ONE), 0).isFile());

		FakeResponse missing = face("/" + ALBUM + "/" + A_ONE, "7", _adminToken);
		assertEquals(404, missing.status());
		assertEquals(ImageServlet.FACE_NOT_FOUND, errorMessage(missing));

		FakeResponse refused = face("/" + A_ONE, "0", shareToken());
		assertEquals("A share bearer is never handed a face.", 404, refused.status());
	}

	/** What the crop does not exist for is not there, not even for the administrator. */
	public void testACropOfAnUnreadableIndex() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		index();

		Map<String, String> parameters = new HashMap<>();
		parameters.put("face", "not a number");
		FakeResponse response = get("/" + ALBUM + "/" + A_ONE, ImageServlet.FACE_TYPE, _adminToken,
			parameters);
		assertEquals(404, response.status());
	}

	// --- Refreshing. ---

	/** Refreshing the cache throws the detections and the crops away, and nothing else. */
	public void testRefreshingTheCacheThrowsThemAway() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		assertEquals(200, face("/" + ALBUM + "/" + A_ONE, "0", _adminToken).status());
		File cacheFile = FaceCache.file(album());
		File crop = FaceIndex.cropFile(new File(album(), A_ONE), 0);
		assertTrue(cacheFile.isFile());
		assertTrue(crop.isFile());
		Map<String, String> before = hashes();

		FakeResponse response = post("/" + ALBUM + "/", "refresh-cache", "{}", _adminToken);
		assertEquals(response.body(), 200, response.status());

		assertFalse("The detections are cache.", cacheFile.exists());
		assertFalse("And so are the crops.", crop.exists());
		assertEquals("Nothing outside the cache directory may be touched.", before, hashes());
	}

	/** The name rule knows the two files this feature writes, and nothing beside them. */
	public void testWhichNamesAreGenerated() {
		assertTrue(CacheRefresh.isGenerated(FaceCache.FILE_NAME));
		assertTrue(CacheRefresh.isGenerated(FaceCache.FILE_NAME + PreviewCache.TMP_SUFFIX));
		assertTrue(CacheRefresh.isGenerated("face-IMG_1.jpg-0.jpg"));
		assertTrue(CacheRefresh.isGenerated("face-IMG_1.jpg-0.jpg.tmp"));

		assertFalse("A crop is a JPEG or it is not a crop.", CacheRefresh.isGenerated("face-IMG_1.jpg-0"));
		assertFalse("A file merely called faces is not this one.", CacheRefresh.isGenerated("faces.txt"));
		assertFalse(CacheRefresh.isGenerated("index.json"));
	}

	// --- Switched off. ---

	/** A space that did not ask for faces has none: no file, no field, no thread. */
	public void testASpaceWithoutFaces() throws Exception {
		_faces = false;
		createSpace(A_ONE, A_TWO, B);

		assertFalse("Nothing is enabled.", _servlet.faces().isEnabled());
		assertFalse(_servlet.faces().isRequested());
		_servlet.faces().indexNow();
		_servlet.startIndexing();

		assertFalse("Nothing is written.", FaceCache.file(album()).exists());
		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		for (ImagePart image : images(album)) {
			assertEquals(0, image.getFaces().size());
		}
		assertFalse(album.isFacesPending());

		FakeResponse auth = get("/", "auth", _adminToken);
		assertFalse("The application is told that there is nothing to offer.",
			AuthInfo.readAuthInfo(reader(auth.body())).isFaces());

		assertEquals("A crop is not there either.", 404,
			face("/" + ALBUM + "/" + A_ONE, "0", _adminToken).status());
	}

	/** A space that asked for faces says so. */
	public void testASpaceWithFacesSaysSo() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}

		FakeResponse auth = get("/", "auth", _adminToken);
		assertTrue(AuthInfo.readAuthInfo(reader(auth.body())).isFaces());
	}

	/** The flag is off unless the file says the word. */
	public void testTheFlagIsOffUnlessItIsOn() throws Exception {
		Files.createDirectories(_base.resolve(de.haumacher.imageServer.auth.UserStore.DIRECTORY_NAME));
		Files.writeString(de.haumacher.imageServer.auth.SpaceStore.file(_base),
			"{\"version\":1,\"faces\":\"yes please\"}", StandardCharsets.UTF_8);
		assertFalse("An unknown value is off.",
			de.haumacher.imageServer.auth.SpaceStore.load(_base, "x").isFacesEnabled());

		Files.writeString(de.haumacher.imageServer.auth.SpaceStore.file(_base), "{}",
			StandardCharsets.UTF_8);
		assertFalse("A file that says nothing is off.",
			de.haumacher.imageServer.auth.SpaceStore.load(_base, "x").isFacesEnabled());

		Files.writeString(de.haumacher.imageServer.auth.SpaceStore.file(_base), "{\"faces\":\"on\"}",
			StandardCharsets.UTF_8);
		assertTrue(de.haumacher.imageServer.auth.SpaceStore.load(_base, "x").isFacesEnabled());
	}

	// --- Helpers. ---

	private FakeResponse face(String pathInfo, String index, String token) throws Exception {
		return face(pathInfo, index, token, null);
	}

	/** Asks for one face crop, optionally as somebody else would be answered it (issue #46). */
	private FakeResponse face(String pathInfo, String index, String token, String viewAs) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("face", index);
		if (viewAs != null) {
			parameters.put(Privacy.VIEW_AS_PARAMETER, viewAs);
		}
		return get(pathInfo, ImageServlet.FACE_TYPE, token, parameters);
	}

	/** A share link on the album, made by the administrator. */
	private String shareToken() throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "share",
			"{\"label\":\"Look\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,"
				+ "\"rights\":[{\"name\":\"view\"}]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return ShareLinkCreated.readShareLinkCreated(reader(response.body())).getToken();
	}

	/** The group of the one face of the given photograph. */
	private String onlyCluster(FaceCache cache, String name) throws Exception {
		String hash = new de.haumacher.imageServer.upload.HashCache(album()).storedHashByName().get(name);
		assertNotNull("No hash for '" + name + "'.", hash);
		List<FaceCache.Face> faces = cache.facesOf(hash);
		assertEquals("Expected exactly one face in '" + name + "'.", 1, faces.size());
		assertFalse("Every face of an indexed album is in a group.", faces.get(0).getCluster().isEmpty());
		return faces.get(0).getCluster();
	}
}
