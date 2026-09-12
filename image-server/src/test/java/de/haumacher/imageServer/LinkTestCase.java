/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.links.LinkStore;
import de.haumacher.imageServer.links.ShareRegistry;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.RightName;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The library and the request fakes the link tests of issue #50 are driven with.
 *
 * <p>
 * The {@link SharingFixture} of issue #49 unchanged — alice's zoo album shared with bob (view,
 * download) and with the group <code>family</code> (contribute), dave holding nothing, eve a guest
 * — plus what a link needs: a folder <code>Trips</code> in bob's space to move a link into.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public abstract class LinkTestCase extends TestCase {

	private static final String BOUNDARY = "----valbumLinkTestBoundary";

	/** The folder in bob's space a link is moved into. */
	protected static final String TRIPS = "Trips";

	protected Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-links-test");
		SharingFixture.create(_base);
		Files.createDirectories(_base.resolve("bob").resolve(TRIPS));
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		_servlet = null;
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- The library. ---

	/** The link store of the folder at the given path below the base folder. */
	protected LinkStore links(String path) {
		return new LinkStore(_base.resolve(path).toFile());
	}

	/** The share registry of the space of the given user. */
	protected ShareRegistry registry(String user) {
		return new ShareRegistry(_base.resolve(user));
	}

	/** Records a link in the folder at the given path below the base folder. */
	protected void link(String folder, String name, String owner, String target) throws IOException {
		Files.createDirectories(_base.resolve(folder));
		links(folder).put(name, owner, target);
	}

	/** Makes the folder at the given path below the base folder file what lands in it by year. */
	protected void filedByYear(String path) throws IOException {
		Files.createDirectories(_base.resolve(path));
		Files.write(_base.resolve(path).resolve("index.json"),
			"[\"ListingInfo\",{\"title\":\"Filed\",\"placement\":\"BY_YEAR\"}]".getBytes(StandardCharsets.UTF_8));
	}

	/** A fingerprint of every file below the given path: name, size and contents. */
	protected static String fingerprint(Path root) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile).forEach(files::add);
		}
		files.sort(Comparator.comparing(Path::toString));
		for (Path file : files) {
			digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		StringBuilder result = new StringBuilder();
		for (byte b : digest.digest()) {
			result.append(String.format("%02x", Byte.valueOf(b)));
		}
		return result.toString();
	}

	// --- Helpers: the servlet. ---

	protected ImageServlet servlet() throws Exception {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SharingFixture.SECRET, _base));
			_servlet.init();
			_servlets.add(_servlet);
		}
		return _servlet;
	}

	/** A fresh servlet, so that nothing of the cache survives a change made behind its back. */
	protected void restartServer() throws Exception {
		_servlet = null;
	}

	protected FakeResponse get(String pathInfo, String type, String token) throws Exception {
		return get(pathInfo, type, token, null);
	}

	protected FakeResponse get(String pathInfo, String type, String token, String viewAs) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		if (viewAs != null) {
			parameters.put("viewAs", viewAs);
		}
		FakeResponse response = new FakeResponse();
		servlet().doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	protected FakeResponse put(String pathInfo, String body, String token) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet().doPut(request(pathInfo, "application/json", body, token, Collections.emptyMap()),
			response.response());
		return response;
	}

	protected FakeResponse post(String pathInfo, String body, String token, Map<String, String> parameters)
			throws Exception {
		FakeResponse response = new FakeResponse();
		servlet().doPost(request(pathInfo, "application/json", body, token, parameters), response.response());
		return response;
	}

	protected FakeResponse upload(String pathInfo, String token, String fileName) throws Exception {
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		files.put(fileName, ("pixels of " + fileName).getBytes(StandardCharsets.UTF_8));
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		servlet().doPut(TestImageServletPut.request(pathInfo, "multipart/form-data; boundary=" + BOUNDARY,
			multipart(files), headers, Collections.emptyMap()), response.response());
		return response;
	}

	protected FakeResponse move(String pathInfo, String target, String token, String... names) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		return post(pathInfo, moveBody(target, names), token, parameters);
	}

	protected FakeResponse unlink(String pathInfo, String token, String... names) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "unlink");
		return post(pathInfo, moveBody("", names), token, parameters);
	}

	protected FakeResponse place(String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "place");
		return post(pathInfo, "{}", token, parameters);
	}

	protected FakeResponse grant(String pathInfo, String token, String action, String subject, String right)
			throws Exception {
		StringBuilder body = new StringBuilder("{\"subject\":\"").append(subject).append("\"");
		if (right != null) {
			body.append(",\"rights\":[{\"name\":\"").append(right).append("\"}]");
		}
		body.append("}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		return post(pathInfo, body.toString(), token, parameters);
	}

	private static String moveBody(String target, String... names) {
		StringBuilder body = new StringBuilder("{\"target\":\"").append(target).append("\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		body.append("]}");
		return body.toString();
	}

	private static HttpServletRequest request(String pathInfo, String contentType, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	private static byte[] multipart(LinkedHashMap<String, byte[]> files) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Map.Entry<String, byte[]> file : files.entrySet()) {
			out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
			out.write(("Content-Disposition: form-data; name=\"" + file.getKey() + "\"; filename=\""
				+ file.getKey() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
			out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
			out.write(file.getValue());
			out.write("\r\n".getBytes(StandardCharsets.UTF_8));
		}
		out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	// --- Helpers: reading the answers. ---

	protected static List<String> rightsOf(FolderResource folder) {
		List<String> result = new ArrayList<>();
		for (RightName right : folder.getRights()) {
			result.add(right.getName());
		}
		return result;
	}

	protected static FolderResource folder(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(body(response)));
		assertTrue("Expected a folder, got: " + resource, resource instanceof FolderResource);
		return (FolderResource) resource;
	}

	protected static AlbumInfo album(FakeResponse response) throws IOException {
		FolderResource folder = folder(response);
		assertTrue("Expected an album, got: " + folder, folder instanceof AlbumInfo);
		return (AlbumInfo) folder;
	}

	protected static ListingInfo listing(FakeResponse response) throws IOException {
		FolderResource folder = folder(response);
		assertTrue("Expected a listing, got: " + folder, folder instanceof ListingInfo);
		return (ListingInfo) folder;
	}

	/** The names of the entries of a listing, in the order they are answered. */
	protected static List<String> entryNames(ListingInfo listing) {
		List<String> result = new ArrayList<>();
		for (FolderInfo folder : listing.getFolders()) {
			result.add(folder.getName());
		}
		return result;
	}

	/** The entry of the given name, <code>null</code> if the listing has none. */
	protected static FolderInfo entry(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (folder.getName().equals(name)) {
				return folder;
			}
		}
		return null;
	}

	protected static MoveResult moveResult(FakeResponse response) throws IOException {
		return MoveResult.readMoveResult(reader(body(response)));
	}

	/** The outcome of the given name in a move or unlink answer. */
	protected static MoveOutcome outcome(MoveResult result, String name) {
		for (MoveOutcome outcome : result.getOutcomes()) {
			if (outcome.getName().equals(name)) {
				return outcome;
			}
		}
		fail("No outcome for '" + name + "' in " + result.getOutcomes());
		return null;
	}

	protected static String body(FakeResponse response) {
		assertEquals("Expected a successful request, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		return response.body();
	}

	protected static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	protected static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}
}
