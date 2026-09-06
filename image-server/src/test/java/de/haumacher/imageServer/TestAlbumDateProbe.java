/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Placement;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Probe for issue #48, composing the placement rule with the spaces of #45: a member's root carries
 * the rule, an album she creates is filed into her year folder, her listing is ordered by date, and
 * the closed library refuses the anonymous caller's placement before anything is looked at.
 */
@SuppressWarnings("javadoc")
public class TestAlbumDateProbe extends TestCase {

	private static final String SECRET = "let-me-in";

	private static final String ALICE_TOKEN = "alice-token";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-album-date-probe");
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testAMembersRuleFilesHerAlbumsAndOrdersHerListing() throws Exception {
		migratedLibraryWithAlice();
		Files.createDirectories(_base.resolve("alice/Undated"));
		Files.write(_base.resolve("alice/Undated/index.json"),
			"[\"AlbumInfo\",{\"title\":\"Undated\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));
		Files.write(_base.resolve("alice/index.json"),
			("[\"ListingInfo\",{\"title\":\"Alice\",\"placement\":\"" + Placement.BY_YEAR.protocolName() + "\"}]")
				.getBytes(StandardCharsets.UTF_8));
		ImageServlet servlet = servlet();

		FakeResponse created = put(servlet, "/2021-03-05 Ski/",
			"[\"AlbumInfo\",{\"title\":\"Ski\",\"parts\":[]}]", ALICE_TOKEN);
		assertEquals(created.body(), HttpServletResponse.SC_OK, created.status());
		CreateResult result = CreateResult.readCreateResult(reader(created.body()));
		assertEquals("2021/2021-03-05 Ski", result.getPath());
		assertTrue(Files.isDirectory(_base.resolve("alice/2021/2021-03-05 Ski")));
		assertFalse("Nothing lands outside her space.", Files.exists(_base.resolve("2021")));
		assertFalse(Files.exists(_base.resolve("alice/2021-03-05 Ski")));

		ListingInfo root = listing(get(servlet, "/", "json", ALICE_TOKEN, null));
		List<String> names = new ArrayList<>();
		for (FolderInfo folder : root.getFolders()) {
			names.add(folder.getName());
		}
		assertEquals("The dated year folder first, the undated album after it.",
			List.of("2021", "Undated"), names);
		assertEquals(Placement.BY_YEAR, root.getPlacement());
		long jan1 = LocalDate.of(2021, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		assertEquals(jan1, root.getFolders().get(0).getEffectiveDate());
		assertEquals(0L, root.getFolders().get(1).getEffectiveDate());

		// The year folder is an ordinary folder without a rule of its own, holding the album.
		ListingInfo year = listing(get(servlet, "/2021/", "json", ALICE_TOKEN, null));
		assertEquals(Placement.NONE, year.getPlacement());
		assertEquals("2021-03-05 Ski", year.getFolders().get(0).getName());

		// Her view as the public keeps the rule and the dates; the closed library keeps the public out.
		ListingInfo asPublic = listing(get(servlet, "/", "json", ALICE_TOKEN, "public"));
		assertEquals(Placement.BY_YEAR, asPublic.getPlacement());
		assertEquals(jan1, asPublic.getFolders().get(0).getEffectiveDate());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, post(servlet, "/", "place", "", null).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(servlet, "/", "json", null, null).status());

		// A restarted server reads the same truth from disk.
		ListingInfo again = listing(get(servlet(), "/", "json", ALICE_TOKEN, null));
		assertEquals("2021", again.getFolders().get(0).getName());
		String sidecar = new String(Files.readAllBytes(_base.resolve("alice/2021/2021-03-05 Ski/index.json")),
			StandardCharsets.UTF_8);
		assertFalse("No derived date value is frozen into the sidecar.", sidecar.matches(".*\"effectiveDate\":[1-9].*"));
	}

	// --- Fixtures. ---

	private void migratedLibraryWithAlice() throws IOException {
		Files.createDirectories(_base.resolve("haui"));
		Files.createDirectories(_base.resolve("alice"));
		UserStore store = new UserStore(_base);
		User owner = store.nameOwner("haui");
		owner.setSpace("haui");
		User alice = new User("alice", Roles.MEMBER, "alice", Instant.now().toString());
		alice.addDevice(new Device("Alice's tablet", UserStore.hash(ALICE_TOKEN), Instant.now().toString()));
		store.addUser(alice);
		store.store();
	}

	private ImageServlet servlet() throws IOException {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
		_servlets.add(servlet);
		return servlet;
	}

	// --- Driving the servlet. ---

	private static ListingInfo listing(FakeResponse response) throws IOException {
		assertEquals("Expected a successful request, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected a listing, got: " + resource, resource instanceof ListingInfo);
		return (ListingInfo) resource;
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String type, String token, String viewAs)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		if (viewAs != null) {
			parameters.put("viewAs", viewAs);
		}
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private static FakeResponse put(ImageServlet servlet, String pathInfo, String body, String token)
			throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPut(request(pathInfo, "application/json", body, token, Collections.emptyMap()),
			response.response());
		return response;
	}

	private static FakeResponse post(ImageServlet servlet, String pathInfo, String action, String body, String token)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body, token, parameters), response.response());
		return response;
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
}
