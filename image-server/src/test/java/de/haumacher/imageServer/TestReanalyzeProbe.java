/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ReanalyzeResult;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Probe of issue #161, composing the re-reading of metadata with what a sidecar may carry besides:
 * a photograph in the trash (#152) with a face tag (#125), and an inbox whose stored arrangement
 * is not the order it is answered in (#131).
 */
@SuppressWarnings("javadoc")
public class TestReanalyzeProbe extends ShareTestCase {

	private static final String TRIP = "Travel/2019/2019-06-01 Trip";

	private static final String INBOX = "Inbox";

	private static final double PRECISION = 1e-6;

	/** A trashed, tagged photograph is filled like any other and loses neither its rating nor its tag. */
	public void testATrashedTaggedPhotographIsFilledAndKeepsItsStatements() throws Exception {
		album(TRIP, "[\"ImagePart\",{\"name\":\"gone.jpg\",\"kind\":\"IMAGE\",\"width\":40,\"height\":30,"
			+ "\"rating\":-2,\"tags\":[{\"x\":0.1,\"y\":0.1,\"w\":0.2,\"h\":0.2,\"person\":\"p-1\",\"state\":\"CONFIRMED\"}]}]");
		TestGeoLocation.writeJpeg(file(TRIP, "gone.jpg"), TestReanalyze.exif("Nikon", "NIKON D750", 52.5, 13.4));

		ReanalyzeResult result = reanalyze("/" + TRIP + "/", SharingFixture.ALICE);
		assertEquals(1, result.getFilled());

		ImagePart image = image(stored(TRIP), "gone.jpg");
		assertEquals("NIKON D750", image.getCamera());
		assertEquals(52.5, image.getLocation().getLatitude(), PRECISION);
		assertEquals("The trash rating is a statement and stays.", -2, image.getRating());
		assertEquals("The tag is a statement and stays.", 1, image.getTags().size());
		assertEquals(FaceState.CONFIRMED, image.getTags().get(0).getState());
		assertEquals("p-1", image.getTags().get(0).getPerson());
	}

	/**
	 * An inbox is answered flat and by date (#131), but its sidecar keeps the author's arrangement;
	 * a re-read writes the sidecar and must write the stored arrangement, not the answered one.
	 */
	public void testAnInboxKeepsItsStoredArrangement() throws Exception {
		Path folder = _base.resolve(INBOX);
		Files.createDirectories(folder);
		String contents = "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"later.jpg\",\"kind\":\"IMAGE\",\"width\":40,\"height\":30,\"date\":2000}],"
			+ "[\"ImageGroup\",{\"representative\":0,\"images\":["
			+ "{\"name\":\"g1.jpg\",\"kind\":\"IMAGE\",\"width\":40,\"height\":30,\"date\":1000},"
			+ "{\"name\":\"g2.jpg\",\"kind\":\"IMAGE\",\"width\":40,\"height\":30,\"date\":3000}]}]"
			+ "]}]";
		Files.write(folder.resolve("index.json"), contents.getBytes(StandardCharsets.UTF_8));
		for (String name : Arrays.asList("later.jpg", "g1.jpg", "g2.jpg")) {
			TestGeoLocation.writeJpeg(file(INBOX, name), TestReanalyze.exif("Sony", "ILCE-7M3", 48.1, 11.5));
		}

		ReanalyzeResult result = reanalyze("/" + INBOX + "/", SharingFixture.ALICE);
		assertEquals("The group's members are filled one by one.", 3, result.getFilled());

		AlbumInfo written = (AlbumInfo) de.haumacher.imageServer.shared.model.Resource
			.readResource(reader(Files.readString(folder.resolve("index.json"), StandardCharsets.UTF_8)));
		assertEquals(AlbumKind.INBOX, written.getKind());
		// The share fixture already holds carols.jpg in this folder, which the sidecar does not
		// list: the loader shows it, but a re-read never writes what the author did not list.
		assertEquals("The stored arrangement, not the flat answer, and nothing the sidecar did not list.", 2,
			written.getParts().size());
		assertTrue(written.getParts().get(1) instanceof ImageGroup);
		assertEquals("later.jpg", ((ImagePart) written.getParts().get(0)).getName());
		// The #78 label: the make stays where the model does not already begin with it.
		assertEquals("Sony ILCE-7M3", ((ImagePart) written.getParts().get(0)).getCamera());
		assertEquals("Sony ILCE-7M3", ((ImageGroup) written.getParts().get(1)).getImages().get(1).getCamera());

		// And the inbox is still answered flat, newest first.
		AlbumInfo answered = album(get("/" + INBOX + "/", "json", SharingFixture.ALICE));
		List<String> shown = names(answered);
		shown.remove("carols.jpg");
		assertEquals(Arrays.asList("g2.jpg", "later.jpg", "g1.jpg"), shown);
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new java.util.ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}

	private ReanalyzeResult reanalyze(String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "reanalyze");
		FakeResponse response = post(pathInfo, "{}", token, parameters);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return ReanalyzeResult.readReanalyzeResult(reader(body(response)));
	}

	private void album(String path, String parts) throws Exception {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		Files.write(folder.resolve("index.json"),
			("[\"AlbumInfo\",{\"title\":\"" + folder.getFileName() + "\",\"parts\":[" + parts + "]}]")
				.getBytes(StandardCharsets.UTF_8));
	}

	private File file(String album, String name) {
		return _base.resolve(album).resolve(name).toFile();
	}

	private AlbumInfo stored(String path) throws Exception {
		restartServer();
		return album(get("/" + path + "/", "json", SharingFixture.ALICE));
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
			if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					if (member.getName().equals(name)) {
						return member;
					}
				}
			}
		}
		fail("No part '" + name + "' in the album.");
		return null;
	}
}
