/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Probe for issue #307: a reference taken from a reference points at the original, follows it
 * through a move, and a privacy raised on the original after the fact is honoured in every album
 * that references it.
 */
@SuppressWarnings("javadoc")
public class TestAlbumReferencesProbe extends ShareTestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		SharingFixture.album(_base, "Trip", "Trip",
			"[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"date\":1000}]", "a.jpg");
		SharingFixture.album(_base, "Other", "Other",
			"[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"date\":2000,\"comment\":\"Beach\"}],"
				+ "[\"ImagePart\",{\"name\":\"d.jpg\",\"width\":4,\"height\":3,\"date\":4000}]",
			"b.jpg", "d.jpg");
		SharingFixture.album(_base, "Third", "Third",
			"[\"ImagePart\",{\"name\":\"t.jpg\",\"width\":4,\"height\":3,\"date\":500}]", "t.jpg");
		restartServer();
	}

	public void testAReferenceOfAReferencePointsAtTheOriginalAndFollowsIt() throws Exception {
		assertOk(collect("/Other/", "Trip", "b.jpg"));
		assertOk(collect("/Trip/", "Third", "b.jpg"));

		ImagePart stored = find(stored("Third"), "b.jpg");
		assertNotNull("Third holds the reference.", stored.getRef());
		assertEquals("Never a reference to a reference.", "Other/b.jpg", stored.getRef().getPath());

		// Round trip of the referencing album keeps the reference unchanged.
		AlbumInfo third = album(get("/Third/", "json", SharingFixture.ALICE));
		assertOk(put("/Third/", write(third), SharingFixture.ALICE));
		assertEquals("Other/b.jpg", find(stored("Third"), "b.jpg").getRef().getPath());

		// The original moves; both references follow, and the chain never breaks.
		assertOk(move("/Other/", SharingFixture.PUBLIC, SharingFixture.ALICE, "b.jpg"));
		for (String album : new String[] { "/Trip/", "/Third/" }) {
			ImagePart b = find(album(get(album, "json", SharingFixture.ALICE)), "b.jpg");
			assertEquals(album, SharingFixture.PUBLIC + "/b.jpg", b.getRef().getPath());
			assertFalse(album, b.isMissing());
			assertEquals(album, "Beach", b.getComment());
			assertEquals(album, HttpServletResponse.SC_OK, get(album + "b.jpg", "tn", SharingFixture.ALICE).status());
		}
		assertTrue(Files.exists(_base.resolve(SharingFixture.PUBLIC).resolve("b.jpg")));
	}

	public void testAPrivacyRaisedLaterHidesTheReferenceEverywhere() throws Exception {
		assertOk(collect("/Other/", "Trip", "d.jpg"));
		assertNotNull("Carol sees it while it is public.",
			findOrNull(album(get("/Trip/", "json", SharingFixture.CAROL)), "d.jpg"));

		AlbumInfo other = album(get("/Other/", "json", SharingFixture.ALICE));
		find(other, "d.jpg").setPrivacy(Privacy.PRIVATE);
		assertOk(put("/Other/", write(other), SharingFixture.ALICE));

		assertNull("Private now, so gone from Carol's view of the referencing album.",
			findOrNull(album(get("/Trip/", "json", SharingFixture.CAROL)), "d.jpg"));
		assertFalse("Nor reachable through the referencing album.",
			get("/Trip/d.jpg", null, SharingFixture.CAROL).status() == HttpServletResponse.SC_OK);
		assertFalse(get("/Trip/d.jpg", "tn", SharingFixture.CAROL).status() == HttpServletResponse.SC_OK);
		assertNotNull("Alice still sees it.", findOrNull(album(get("/Trip/", "json", SharingFixture.ALICE)), "d.jpg"));

		// Carol saving the album she cannot fully see must not drop Alice's reference.
		AlbumInfo carols = album(get("/Trip/", "json", SharingFixture.CAROL));
		assertOk(put("/Trip/", write(carols), SharingFixture.CAROL));
		assertNotNull("The hidden reference survives Carol's save.", findOrNull(stored("Trip"), "d.jpg"));
		assertEquals(Privacy.PRIVATE, find(stored("Other"), "d.jpg").getPrivacy());
	}

	// --- Helpers. ---

	private static void assertOk(FakeResponse response) {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private FakeResponse collect(String pathInfo, String target, String... names) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.COLLECT_ACTION);
		StringBuilder body = new StringBuilder("{\"target\":\"").append(target).append("\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		return post(pathInfo, body.append("]}").toString(), SharingFixture.ALICE, parameters);
	}

	private AlbumInfo stored(String folder) throws Exception {
		return (AlbumInfo) Resource.readResource(reader(new String(
			Files.readAllBytes(_base.resolve(folder).resolve("index.json")), StandardCharsets.UTF_8)));
	}

	private static ImagePart find(AlbumInfo album, String name) {
		ImagePart result = findOrNull(album, name);
		assertNotNull("No part '" + name + "' in " + names(album), result);
		return result;
	}

	private static ImagePart findOrNull(AlbumInfo album, String name) {
		return Crops.findImage(album, name);
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}

	private static String write(Resource resource) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			resource.writeTo(json);
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}
}
