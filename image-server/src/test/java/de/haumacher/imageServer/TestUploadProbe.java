/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.shared.model.UploadResult;
import de.haumacher.imageServer.shared.model.UploadedFile;
import de.haumacher.imageServer.upload.HashCache;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Review probe of the idempotent upload (issue #29) composed with authentication, a video, a
 * pre-existing photo without a hash cache, and an album save between two uploads.
 */
@SuppressWarnings("javadoc")
public class TestUploadProbe extends TestCase {

	private static final String BOUNDARY = "probe-boundary";

	private static final byte[] RED = "red pixels".getBytes(StandardCharsets.UTF_8);

	private static final byte[] CLIP = "moving pictures".getBytes(StandardCharsets.UTF_8);

	private static final String FOLDER = "/2020 Trip/";

	private Path _base;

	private Path _folder;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-upload-probe");
		_folder = _base.resolve("2020 Trip");
		Files.createDirectories(_folder);
		Files.write(_folder.resolve("old.jpg"), RED);
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testCheckFindsAPhotoThatWasNeverUploaded() throws Exception {
		ImageServlet servlet = servlet();
		UploadCheckResult result = check(servlet, null, HashCache.sha256(_folder.resolve("old.jpg").toFile()), "00");
		assertEquals(1, result.getPresent().size());
		assertEquals("old.jpg", result.getPresent().get(0).getName());
	}

	public void testPairedUploadWithVideoSurvivesAnAlbumSave() throws Exception {
		ImageServlet servlet = servlet();
		String token = pair(servlet);

		UploadResult first = upload(servlet, token, files("Pic 1.jpg", RED, "clip.mp4", CLIP));
		assertEquals(Arrays.asList("present", "stored"), states(first));
		assertEquals("old.jpg", first.getFiles().get(0).getStoredAs());
		assertEquals("clip.mp4", first.getFiles().get(1).getStoredAs());

		// The album author edits the album in between: a sidecar save into the same folder.
		FakeResponse saved = new FakeResponse();
		servlet.doPut(request(FOLDER, "application/json",
			"[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8), token,
			Collections.emptyMap()), saved.response());
		assertEquals(saved.body(), HttpServletResponse.SC_OK, saved.status());

		UploadResult again = upload(servlet, token, files("Pic 1.jpg", RED, "clip.mp4", CLIP));
		assertEquals(Arrays.asList("present", "present"), states(again));

		assertEquals(Arrays.asList(".hashes.json", "clip.mp4", "index.json", "old.jpg"), entries(_folder));
		assertTrue(Arrays.equals(RED, Files.readAllBytes(_folder.resolve("old.jpg"))));

		// The folder's JSON never lists a sidecar. (The probe's files are not real media, so
		// the server cannot analyze them into parts; the negative assertion is what counts.)
		FakeResponse json = new FakeResponse();
		servlet.doGet(request(FOLDER, null, new byte[0], null, parameters("type", "json")), json.response());
		assertEquals(HttpServletResponse.SC_OK, json.status());
		assertFalse(json.body(), json.body().contains(".hashes.json"));
		assertFalse(json.body(), json.body().contains("index.json"));

		// An anonymous retry is refused as a write, and stores nothing.
		FakeResponse anonymous = new FakeResponse();
		servlet.doPut(request(FOLDER, "multipart/form-data; boundary=" + BOUNDARY,
			multipart(files("clip.mp4", CLIP)), null, Collections.emptyMap()), anonymous.response());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());
		assertEquals(Arrays.asList(".hashes.json", "clip.mp4", "index.json", "old.jpg"), entries(_folder));
	}

	// --- Helpers. ---

	private ImageServlet servlet() throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, "s3cret", _base));
		servlet.init();
		return servlet;
	}

	private String pair(ImageServlet servlet) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPost(request("/", "application/json",
			"{\"secret\":\"s3cret\",\"deviceName\":\"Phone\"}".getBytes(StandardCharsets.UTF_8), null,
			parameters("action", "pair")), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return PairResponse.readPairResponse(reader(response.body())).getToken();
	}

	private static LinkedHashMap<String, byte[]> files(Object... nameAndContents) {
		LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
		for (int n = 0; n < nameAndContents.length; n += 2) {
			result.put((String) nameAndContents[n], (byte[]) nameAndContents[n + 1]);
		}
		return result;
	}

	private UploadResult upload(ImageServlet servlet, String token, LinkedHashMap<String, byte[]> files)
			throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPut(request(FOLDER, "multipart/form-data; boundary=" + BOUNDARY, multipart(files), token,
			Collections.emptyMap()), response.response());
		assertEquals("Upload failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return UploadResult.readUploadResult(reader(response.body()));
	}

	private UploadCheckResult check(ImageServlet servlet, String token, String... hashes) throws Exception {
		String body = "{\"hashes\":["
			+ Arrays.stream(hashes).map(h -> "{\"hash\":\"" + h + "\"}").collect(Collectors.joining(","))
			+ "]}";
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(FOLDER, "application/json", body.getBytes(StandardCharsets.UTF_8), token,
			parameters("action", "check")), response.response());
		assertEquals("Check failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return UploadCheckResult.readUploadCheckResult(reader(response.body()));
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

	private static Map<String, String> parameters(String name, String value) {
		Map<String, String> result = new HashMap<>();
		result.put(name, value);
		return result;
	}

	private static HttpServletRequest request(String pathInfo, String contentType, byte[] body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body, headers, parameters);
	}

	private static List<String> states(UploadResult result) {
		return result.getFiles().stream().map(UploadedFile::getStatus).collect(Collectors.toList());
	}

	private static List<String> entries(Path directory) {
		String[] names = directory.toFile().list();
		return names == null ? Collections.emptyList() : Arrays.stream(names).sorted().collect(Collectors.toList());
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}
}
