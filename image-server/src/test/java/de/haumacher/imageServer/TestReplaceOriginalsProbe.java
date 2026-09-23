/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.PresentFile;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Probe of issue #167, composing the replacement with the hash index of #118: after a run, a
 * device re-scanning its camera roll must be told that the original is in the library and must not
 * be told anything about the redacted copy's hash any more — on the server that runs next.
 */
@SuppressWarnings("javadoc")
public class TestReplaceOriginalsProbe extends TestCase {

	private static final String ALBUM = "2026/2026-09-20 Trip";

	private static final String NAME = "20260920_141011.jpg";

	private Path _base;

	private Path _incoming;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-replace-probe");
		_incoming = Files.createTempDirectory("valbum-replace-incoming");
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		for (Path dir : new Path[] { _base, _incoming }) {
			try (Stream<Path> files = Files.walk(dir)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testTheIndexOfTheNextServerKnowsTheOriginalAndForgetsTheCopy() throws Exception {
		byte[] picture = Redacted.picture(Color.ORANGE, false);
		Path album = _base.resolve(ALBUM);
		Files.createDirectories(album);
		Redacted.writeRedacted(album.resolve(NAME).toFile(), picture);
		Files.write(album.resolve("index.json"), ("[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[[\"ImagePart\",{"
			+ "\"kind\":\"IMAGE\",\"name\":\"" + NAME + "\",\"width\":40,\"height\":30}]]}]").getBytes(StandardCharsets.UTF_8));
		HashCache hashes = new HashCache(album.toFile());
		hashes.put(album.resolve(NAME).toFile(), HashCache.sha256(album.resolve(NAME).toFile()),
			new HashCache.Attribution("user:bob", "Bob"));
		hashes.flush();
		Files.createDirectories(_base.resolve("Other"));
		Redacted.writeOriginal(_incoming.resolve(NAME).toFile(), picture);
		byte[] redacted = Files.readAllBytes(album.resolve(NAME));
		byte[] original = Files.readAllBytes(_incoming.resolve(NAME));

		// The server before the run knows the redacted copy — in its album by name, and space-wide
		// once the start-up pass of #118 has read the sidecars (a test has to start it; Main does).
		ImageServlet before = servlet();
		assertEquals(Collections.singletonList(NAME), present(before, "/" + ALBUM + "/", redacted));
		assertEquals(Collections.emptyList(), present(before, "/" + ALBUM + "/", original));
		assertEquals(Collections.singletonList(ALBUM + "/" + NAME), present(before, "/Other/", redacted));
		assertEquals(Collections.emptyList(), present(before, "/Other/", original));
		before.destroy();
		_servlets.remove(before);

		ReplaceOriginals.Report report = ReplaceOriginals.run(_base, _incoming, null, false);
		assertEquals(report.getLines().toString(), 1, report.getReplaced());

		// The server that runs next reads the rewritten sidecar: the original is known where it
		// lies, the copy's hash is nobody's business any more.
		ImageServlet after = servlet();
		assertEquals(Collections.singletonList(NAME), present(after, "/" + ALBUM + "/", original));
		assertEquals(Collections.emptyList(), present(after, "/" + ALBUM + "/", redacted));
		assertEquals(Collections.singletonList(ALBUM + "/" + NAME), present(after, "/Other/", original));
		assertEquals("The redacted copy set aside under .valbum is not part of the library.",
			Collections.emptyList(), present(after, "/Other/", redacted));
	}

	/** A servlet whose start-up pass over the hashes (#118) has run to the end, as Main starts it. */
	private ImageServlet servlet() throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
		_servlets.add(servlet);
		servlet.startIndexing();
		for (int n = 0; n < 200; n++) {
			UploadCheckResult result = check(servlet, "/Other/", "0000");
			if (result.getIndexed() != null && result.getIndexed().getTotal() > 0
				&& result.getIndexed().getDone() == result.getIndexed().getTotal()) {
				return servlet;
			}
			Thread.sleep(50);
		}
		fail("The index pass did not finish.");
		return servlet;
	}

	private static List<String> present(ImageServlet servlet, String folder, byte[] contents) throws Exception {
		UploadCheckResult result = check(servlet, folder, HashCache.sha256(contents));
		List<String> names = new ArrayList<>();
		for (PresentFile file : result.getPresent()) {
			names.add(file.getName());
		}
		return names;
	}

	private static UploadCheckResult check(ImageServlet servlet, String folder, String hash) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "check");
		FakeResponse response = new FakeResponse();
		String body = "{\"hashes\":[{\"hash\":\"" + hash + "\"}]}";
		servlet.doPost(TestImageServletPut.request(folder, "application/json", body.getBytes(StandardCharsets.UTF_8),
			new HashMap<>(), parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return UploadCheckResult.readUploadCheckResult(
			new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
	}
}
