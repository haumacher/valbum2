/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.UserStore;
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
 * next sign-in must name them, not create a second admin.
 */
@SuppressWarnings("javadoc")
public class TestAdminNameProbe extends TestCase {

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
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		String seat = auth.issueSeatCode(null).getCode();
		try {
			auth.pair(PairRequest.create().setDeviceCode(seat).setDeviceName("Phone").setUserName("  "));
			fail("A nameless first sign-in must be refused.");
		} catch (Exception expected) {
			assertEquals(AuthService.NAME_REQUIRED, expected.getMessage());
		}
		assertEquals("A refused pairing signs nobody in.", 0,
			new UserStore(_base).getOwner().getDevices().size());
		// The same code, now with a name: it creates nothing, it *names* the seat that was there.
		String first =
			auth.pair(PairRequest.create().setDeviceCode(seat).setDeviceName("Phone").setUserName("haui"))
				.getToken();
		// A second device comes from a code of the first, and needs no name: the user has one.
		String code = Codes.forUser(auth, "haui");
		String second =
			auth.pair(PairRequest.create().setDeviceCode(code).setDeviceName("Tablet").setUserName(""))
				.getToken();
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
		// And a stranger's name is refused: a code says whom it signs in.
		try {
			auth.pair(PairRequest.create().setDeviceCode(Codes.forUser(auth, "haui")).setDeviceName("Laptop")
				.setUserName("eve"));
			fail("A code names one user only.");
		} catch (AuthService.PairRefused expected) {
			assertEquals(AuthService.DEVICE_CODE_OTHER_USER, expected.getMessage());
		}
	}
}
