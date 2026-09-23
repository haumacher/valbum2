/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Heading;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Probe of issue #158, composing the heading level with the copies an album is answered through:
 * the privacy filter of a share link (#51) and the flat answer of an inbox (#131).
 */
@SuppressWarnings("javadoc")
public class TestHeadingLevelProbe extends ShareTestCase {

	public void testAShareLinkIsAnsweredTheLevels() throws Exception {
		SharingFixture.album(_base, "2024/2024-07-01 Lake", "Lake",
			"[\"Heading\",{\"text\":\"Morning\",\"level\":1}],"
				+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3}],"
				+ "[\"Heading\",{\"text\":\"At the pier\",\"level\":2}],"
				+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"privacy\":2}],"
				+ "[\"Heading\",{\"text\":\"Old style\"}]",
			"a.jpg", "b.jpg");
		String link = created(share("/2024/2024-07-01 Lake/", SharingFixture.ALICE,
			shareBody("Lake", "", 1, -2, Rights.VIEW))).getToken();

		AlbumInfo answered = album(get("/", "json", link));
		List<Heading> headings = headings(answered);
		assertEquals("Every heading passes the filter, the private photograph does not.",
			Arrays.asList("Morning", "At the pier", "Old style"), texts(headings));
		assertEquals(1, headings.get(0).getLevel());
		assertEquals("The level rides through the filtered copy.", 2, headings.get(1).getLevel());
		assertEquals("An older heading without a level reads as it is stored.", 0, headings.get(2).getLevel());
		int images = 0;
		for (AlbumPart part : answered.getParts()) {
			if (part instanceof de.haumacher.imageServer.shared.model.ImagePart) {
				images++;
			}
		}
		assertEquals(1, images);
	}

	public void testAnInboxDropsItsHeadingsWhateverTheirLevel() throws Exception {
		Path folder = _base.resolve("Inbox");
		Files.createDirectories(folder);
		Files.write(folder.resolve("index.json"), ("[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ "[\"Heading\",{\"text\":\"Week\",\"level\":1}],"
			+ "[\"Heading\",{\"text\":\"Day\",\"level\":2}],"
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"date\":1000}]]}]")
			.getBytes(StandardCharsets.UTF_8));
		Files.write(folder.resolve("a.jpg"), new byte[] { 1 });

		AlbumInfo answered = album(get("/Inbox/", "json", SharingFixture.ALICE));
		assertTrue("A heading is an order, and an inbox has another one: none is answered.",
			headings(answered).isEmpty());
		String stored = Files.readString(folder.resolve("index.json"), StandardCharsets.UTF_8);
		assertTrue("The stored headings and their levels are untouched by the read.",
			stored.contains("\"text\":\"Day\",\"level\":2"));
	}

	private static List<Heading> headings(AlbumInfo album) {
		List<Heading> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof Heading) {
				result.add((Heading) part);
			}
		}
		return result;
	}

	private static List<String> texts(List<Heading> headings) {
		List<String> result = new ArrayList<>();
		for (Heading heading : headings) {
			result.add(heading.getText());
		}
		return result;
	}
}
