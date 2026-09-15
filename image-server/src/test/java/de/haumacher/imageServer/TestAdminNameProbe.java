/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Probe for #86: a nameless admin of an older library is named once, keeps their device, and the
 * next sign-in with the secret must name them, not create a second admin.
 */
@SuppressWarnings("javadoc")
public class TestAdminNameProbe extends TestCase {

	private static final String SECRET = "let-me-in";

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		_base = Files.createTempDirectory("valbum-admin-name-probe");
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	public void testRefusalLeavesNothingBehind() throws Exception {
		AuthService auth = new AuthService(AuthMode.WRITES, SECRET, _base);
		try {
			auth.pair(PairRequest.create().setSecret(SECRET).setDeviceName("Phone").setUserName("  "));
			fail("A nameless first sign-in must be refused.");
		} catch (Exception expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("names the administrator"));
		}
		assertFalse("Nothing may be created by a refused pairing.", Files.exists(_base.resolve(".valbum").resolve("users.json")));
		// The next, named, sign-in creates the admin and a second device may then come nameless.
		String first = auth.pair(PairRequest.create().setSecret(SECRET).setDeviceName("Phone").setUserName("haui")).getToken();
		String second = auth.pair(PairRequest.create().setSecret(SECRET).setDeviceName("Tablet").setUserName("")).getToken();
		assertNotNull(first);
		assertNotNull(second);
		_servlet = new ImageServlet(_base.toFile(), auth);
		for (String t : new String[] { first, second }) {
			Map<String, String> headers = new HashMap<>();
			headers.put("Authorization", "Bearer " + t);
			Map<String, String> parameters = new HashMap<>();
			parameters.put("type", "auth");
			FakeResponse response = new FakeResponse();
			_servlet.doGet(request("/", null, new byte[0], headers, parameters), response.response());
			assertEquals(HttpServletResponse.SC_OK, response.status());
			AuthInfo info = AuthInfo.readAuthInfo(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
			assertEquals("haui", info.getUserName());
			assertEquals("admin", info.getRole());
		}
		// And a stranger's name is refused by the secret.
		try {
			auth.pair(PairRequest.create().setSecret(SECRET).setDeviceName("Laptop").setUserName("eve"));
			fail("The secret names one admin only.");
		} catch (Exception expected) {
			// as designed
		}
	}
}
