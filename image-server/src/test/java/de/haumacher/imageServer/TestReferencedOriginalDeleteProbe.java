/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Probe for issue #307, package 2: a rescued photograph is rescued again when deleted where it was
 * kept, lands in a nested album, keeps its privacy, and never arrives rated as trash.
 */
@SuppressWarnings("javadoc")
public class TestReferencedOriginalDeleteProbe extends ShareTestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		SharingFixture.album(_base, "Other", "Other",
			"[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"date\":2000,\"comment\":\"Beach\"}]",
			"b.jpg");
		SharingFixture.album(_base, "Nest/Sub", "Sub",
			"[\"ImagePart\",{\"name\":\"s.jpg\",\"width\":4,\"height\":3,\"date\":1000}]", "s.jpg");
		SharingFixture.album(_base, "Third", "Third",
			"[\"ImagePart\",{\"name\":\"t.jpg\",\"width\":4,\"height\":3,\"date\":500}]", "t.jpg");
		restartServer();
	}

	public void testARescuedPhotoIsRescuedAgainAndNeverLost() throws Exception {
		byte[] bytes = Files.readAllBytes(_base.resolve("Other/b.jpg"));
		AlbumInfo other = album(get("/Other/", "json", SharingFixture.ALICE));
		image(other, "b.jpg").setPrivacy(Privacy.PRIVATE);
		ok(put("/Other/", write(other), SharingFixture.ALICE));

		ok(collect("/Other/", "Nest/Sub", "b.jpg"));
		ok(collect("/Other/", "Third", "b.jpg"));

		// Deleted where it lies: it goes to the nested album, the oldest reference.
		ok(action("/Other/", "delete", "b.jpg"));
		Path nested = _base.resolve("Nest/Sub/b.jpg");
		assertTrue(Arrays.equals(bytes, Files.readAllBytes(nested)));
		assertFalse(Files.exists(_base.resolve("Other/b.jpg")));
		ImagePart kept = image(stored("Nest/Sub"), "b.jpg");
		assertNull("A file now, no reference.", kept.getRef());
		assertEquals("Its privacy travels with it.", Privacy.PRIVATE, kept.getPrivacy());
		assertNull("Never shown to Carol through the album that keeps it.",
			Crops.findImage(album(get("/Nest/Sub/", "json", SharingFixture.CAROL)), "b.jpg"));
		ImagePart third = image(album(get("/Third/", "json", SharingFixture.ALICE)), "b.jpg");
		assertEquals("Nest/Sub/b.jpg", third.getRef().getPath());

		// Deleted again where it was kept: on to the last album that shows it.
		ok(action("/Nest/Sub/", "delete", "b.jpg"));
		assertTrue(Arrays.equals(bytes, Files.readAllBytes(_base.resolve("Third/b.jpg"))));
		assertNull(image(stored("Third"), "b.jpg").getRef());
		assertFalse(Files.exists(nested));

		// Now nobody shows it any more: deleted, it goes into the trash and is kept there.
		ok(action("/Third/", "delete", "b.jpg"));
		assertFalse(Files.exists(_base.resolve("Third/b.jpg")));
		assertTrue("Still restorable from the trash.", Files.walk(_base)
			.anyMatch(p -> p.getFileName().toString().equals("b.jpg") && contentEquals(p, bytes)));
	}

	public void testAPhotoAnOlderBuildTrashedArrivesUnrated() throws Exception {
		ok(collect("/Other/", "Third", "b.jpg"));
		Path index = _base.resolve("Other/index.json");
		String text = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
		Files.write(index, text.replace("\"name\":\"b.jpg\",", "\"name\":\"b.jpg\",\"rating\":-2,")
			.getBytes(StandardCharsets.UTF_8));
		restartServer();

		ok(action("/Other/", "delete", "b.jpg"));

		ImagePart kept = image(stored("Third"), "b.jpg");
		assertNull(kept.getRef());
		assertFalse("Not in the trash of the album that keeps it.", kept.getRating() == Ratings.TRASH);
		assertNotNull(Crops.findImage(album(get("/Third/", "json", SharingFixture.DAVE)), "b.jpg"));
	}

	// --- Helpers. ---

	private static boolean contentEquals(Path path, byte[] bytes) {
		try {
			return Arrays.equals(bytes, Files.readAllBytes(path));
		} catch (java.io.IOException ex) {
			return false;
		}
	}

	private static void ok(FakeResponse response) {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private FakeResponse collect(String pathInfo, String target, String name) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.COLLECT_ACTION);
		return post(pathInfo, "{\"target\":\"" + target + "\",\"names\":[{\"name\":\"" + name + "\"}]}",
			SharingFixture.ALICE, parameters);
	}

	private FakeResponse action(String pathInfo, String action, String name) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		return post(pathInfo, "{\"names\":[{\"name\":\"" + name + "\"}]}", SharingFixture.ALICE, parameters);
	}

	private AlbumInfo stored(String folder) throws Exception {
		return (AlbumInfo) Resource.readResource(reader(new String(
			Files.readAllBytes(_base.resolve(folder).resolve("index.json")), StandardCharsets.UTF_8)));
	}

	private static ImagePart image(AlbumInfo album, String name) {
		ImagePart result = Crops.findImage(album, name);
		assertNotNull("No part '" + name + "'", result);
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
