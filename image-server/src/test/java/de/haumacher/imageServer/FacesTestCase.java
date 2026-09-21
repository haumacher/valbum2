/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The library the face tests of issue #124 are driven with.
 *
 * <p>
 * A space of its own with one album of public-domain portraits (see
 * <code>src/test/fixtures/faces/README.md</code>) and one video, because the face index is the one
 * feature of this server that needs pictures with faces in them and the album fixture deliberately
 * holds no photograph of a real person.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public abstract class FacesTestCase extends TestCase {

	/** Where the portraits are. */
	protected static final File PORTRAITS = new File("src/test/fixtures/faces");

	/** The album of the test space the portraits are put into. */
	protected static final String ALBUM = "2024-05-01 Portraits";

	/** The same person, twice. */
	protected static final String A_ONE = "portrait-a-1.jpg";

	/** The same person, twice. */
	protected static final String A_TWO = "portrait-a-2.jpg";

	/** Somebody else. */
	protected static final String B = "portrait-b.jpg";

	/** A third person. */
	protected static final String C = "portrait-c.jpg";

	/** The video that must never be handed to the detector. */
	protected static final String VIDEO = "video-1.mp4";

	protected Path _base;

	protected ImageServlet _servlet;

	protected AuthService _auth;

	/** The token of the administrator "haui". */
	protected String _adminToken;

	/** Whether this space asks for faces. */
	protected boolean _faces = true;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-faces");
	}

	@Override
	protected void tearDown() throws Exception {
		FaceDetection.setDetector(null);
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	/**
	 * Whether this machine can detect faces at all.
	 *
	 * <p>
	 * A build machine without a loadable OpenCV is not a failure of this feature — the server says
	 * so in one line and serves albums without faces, which is the whole point of
	 * {@link FaceDetection#unavailability()}. Such a machine skips the tests that need a detector,
	 * loudly.
	 * </p>
	 */
	protected boolean detectorAvailable() {
		String unavailable = FaceDetection.unavailability();
		if (unavailable != null) {
			System.out.println("Skipping " + getName() + ": no face detection here (" + unavailable + ").");
			return false;
		}
		return true;
	}

	// --- The library. ---

	/** Builds the space, the album and the administrator, and starts a servlet over it. */
	protected void createSpace(String... images) throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Faces", SpaceStore.ANONYMOUS_PUBLIC, "",
			_faces ? SpaceStore.FACES_ON : SpaceStore.FACES_OFF));

		Path album = _base.resolve(ALBUM);
		Files.createDirectories(album);
		for (String image : images) {
			if (VIDEO.equals(image)) {
				test.de.haumacher.valbum.GenerateTestAlbum.recordTinyVideo(album.resolve(VIDEO).toFile());
			} else {
				Files.copy(new File(PORTRAITS, image).toPath(), album.resolve(image),
					StandardCopyOption.REPLACE_EXISTING);
			}
		}

		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		extraUsers(store);
		store.store();

		_auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), _auth, "", SpaceStore.load(_base, ""));
		_servlet.init();
		_adminToken = Codes.signInAdmin(_auth, "Phone", "haui").getToken();
	}

	/**
	 * Adds further users to the space before it is written, for a test that needs somebody besides
	 * the administrator (issue #125).
	 *
	 * <p>
	 * Called while the store is being built, because the {@link AuthService} reads it once: a user
	 * added afterwards would not be known to the servlet under test.
	 * </p>
	 */
	protected void extraUsers(UserStore store) {
		// Nobody but the administrator, unless a test says otherwise.
	}

	/** The album folder of the test space. */
	protected File album() {
		return _base.resolve(ALBUM).toFile();
	}

	/** Detects in the whole space, in this thread, and waits for whatever a request queued. */
	protected void index() throws Exception {
		_servlet.faces().indexNow();
		assertTrue("The face index did not run empty.", _servlet.faces().awaitQueue(60000));
	}

	// --- Requests. ---

	protected FakeResponse get(String pathInfo, String type, String token) throws Exception {
		return get(pathInfo, type, token, new HashMap<>());
	}

	protected FakeResponse get(String pathInfo, String type, String token, Map<String, String> more)
			throws Exception {
		Map<String, String> parameters = new HashMap<>(more);
		parameters.put("type", type);
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}

	protected FakeResponse post(String pathInfo, String action, String body, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request(pathInfo, "application/json",
			body.getBytes(StandardCharsets.UTF_8), headers, parameters), response.response());
		return response;
	}

	protected FakeResponse put(String pathInfo, String body, String token) throws Exception {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doPut(TestImageServletPut.request(pathInfo, "application/json",
			body.getBytes(StandardCharsets.UTF_8), headers, new HashMap<>()), response.response());
		return response;
	}

	/** The album at the given path, as the given caller is answered it. */
	protected AlbumInfo album(String pathInfo, String token) throws Exception {
		return album(pathInfo, token, null);
	}

	/**
	 * The album as the given caller is answered it while asking to be shown as somebody else.
	 *
	 * @param viewAs
	 *        <code>public</code> or <code>members</code> (issue #46), <code>null</code> to ask as
	 *        oneself.
	 */
	protected AlbumInfo album(String pathInfo, String token, String viewAs) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		if (viewAs != null) {
			parameters.put(de.haumacher.imageServer.auth.Privacy.VIEW_AS_PARAMETER, viewAs);
		}
		FakeResponse response = get(pathInfo, "json", token, parameters);
		assertEquals(response.body(), 200, response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	/** The image of the given name in the album, or a failure. */
	protected static ImagePart image(AlbumInfo album, String name) {
		for (ImagePart image : images(album)) {
			if (image.getName().equals(name)) {
				return image;
			}
		}
		fail("No image '" + name + "' in " + album.getParts());
		return null;
	}

	/** Every image of the album, groups unpacked. */
	protected static List<ImagePart> images(FolderResource resource) {
		List<ImagePart> result = new ArrayList<>();
		if (!(resource instanceof AlbumInfo)) {
			return result;
		}
		for (AlbumPart part : ((AlbumInfo) resource).getParts()) {
			if (part instanceof ImagePart) {
				result.add((ImagePart) part);
			} else if (part instanceof ImageGroup) {
				result.addAll(((ImageGroup) part).getImages());
			}
		}
		return result;
	}

	protected static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an error, got: " + resource, resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	protected static JsonReader reader(String json) {
		return new JsonReader(new ReaderAdapter(new StringReader(json)));
	}

	/** The SHA-256 of every file of the tree that is not inside a cache directory, by path. */
	protected Map<String, String> hashes() throws Exception {
		Map<String, String> result = new LinkedHashMap<>();
		try (Stream<Path> files = Files.walk(_base)) {
			for (Path path : (Iterable<Path>) files.sorted()::iterator) {
				String relative = _base.relativize(path).toString();
				if (relative.contains(PreviewCache.CACHE_DIRECTORY_NAME) || Files.isDirectory(path)) {
					continue;
				}
				result.put(relative, hash(path));
			}
		}
		return result;
	}

	private static String hash(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		StringBuilder buffer = new StringBuilder();
		for (byte b : digest.digest(Files.readAllBytes(file))) {
			buffer.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return buffer.toString();
	}
}
