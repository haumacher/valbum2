/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Review probes of the folder picture (issue #110), composed with the moves of #47, the delete of
 * #109 and a listing sidecar an older build might have frozen entries into.
 */
@SuppressWarnings("javadoc")
public class TestFolderPictureProbe extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-folder-picture-probe");
		_servlet = new ImageServlet(_base.toFile());
	}

	@Override
	protected void tearDown() throws Exception {
		_servlet.destroy();
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	private void chosen() throws IOException {
		image("F/A/a.jpg");
		sidecar("F/A", "[\"AlbumInfo\",{\"title\":\"A\",\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},"
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3}]]}]");
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"A\"}]");
		Files.createDirectories(_base.resolve("G"));
		sidecar("G", "[\"ListingInfo\",{\"title\":\"G\"}]");
	}

	public void testTheChosenAlbumMovedAwayAndBackLeavesTheChoiceStanding() throws Exception {
		chosen();
		assertEquals("A/a.jpg", byName(listing("/"), "F").getIndexPicture().getImage());

		MoveResult away = action("move", "/F/", "G", "A");
		assertEquals("Refused: " + away.getOutcomes().get(0).getMessage(), "A",
			away.getOutcomes().get(0).getNewName());
		assertNull("The chosen child is elsewhere: no picture, no error.", byName(listing("/"), "F").getIndexPicture());
		assertTrue("The choice is not chased and not dropped.", read("F/index.json").contains("\"index\":\"A\""));

		MoveResult back = action("move", "/G/", "F", "A");
		assertEquals("A", back.getOutcomes().get(0).getNewName());
		assertEquals("The picture is back with the album.", "A/a.jpg",
			byName(listing("/"), "F").getIndexPicture().getImage());
	}

	public void testTheChosenAlbumDeletedToTheTrashIsNoPicture() throws Exception {
		chosen();

		MoveResult result = action("delete", "/F/", "", "A");
		assertEquals(DeleteService.trashed("A"), result.getOutcomes().get(0).getMessage());

		FolderInfo entry = byName(listing("/"), "F");
		assertNull(entry.getIndexPicture());
		assertEquals("Nothing reaches into the trash.", 0, listing("/F/").getFolders().size());
	}

	public void testAFrozenEntryOfAnOlderSidecarNeverOutlivesTheDisk() throws Exception {
		chosen();
		// An older build could have written the entries into the listing's sidecar; they say a
		// picture that is not the album's any more.
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"A\",\"folders\":["
			+ "{\"name\":\"A\",\"title\":\"Old\",\"indexPicture\":{\"image\":\"old.jpg\",\"scale\":1.0}}]}]");

		ListingInfo inner = listing("/F/");
		assertEquals(Arrays.asList("A"), inner.getFolders().stream().map(FolderInfo::getName)
			.collect(Collectors.toList()));
		assertEquals("The entry is rebuilt from the disk, not read from the file.", "a.jpg",
			inner.getFolders().get(0).getIndexPicture().getImage());
		assertEquals("a.jpg", byName(listing("/"), "F").getIndexPicture().getImage().substring(2));

		FakeResponse stored = put("/F/", write(inner));
		assertEquals(HttpServletResponse.SC_OK, stored.status());
		String file = read("F/index.json");
		assertTrue("The choice stays.", file.contains("\"index\":\"A\""));
		assertFalse("The entries do not: " + file, file.contains("\"folders\":[{"));
	}

	// --- Helpers. ---

	private static FolderInfo byName(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (name.equals(folder.getName())) {
				return folder;
			}
		}
		fail("No folder '" + name + "' in " + listing.getFolders());
		return null;
	}

	private ListingInfo listing(String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], new HashMap<>(), parameters),
			response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return (ListingInfo) Resource.readResource(reader(response.body()));
	}

	private MoveResult action(String action, String pathInfo, String target, String... names) throws Exception {
		String body = "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "application/json");
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request(pathInfo, "application/json",
			body.getBytes(StandardCharsets.UTF_8), headers, parameters), response.response());
		assertEquals("The " + action + " failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private FakeResponse put(String pathInfo, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(TestImageServletPut.request(pathInfo, "application/json",
			body.getBytes(StandardCharsets.UTF_8)), response.response());
		return response;
	}

	private static String write(Resource resource) throws IOException {
		java.io.StringWriter out = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json =
			new de.haumacher.msgbuf.json.JsonWriter(new de.haumacher.msgbuf.server.io.WriterAdapter(out))) {
			resource.writeTo(json);
		}
		return out.toString();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private void image(String path) throws IOException {
		Path file = _base.resolve(path);
		Files.createDirectories(file.getParent());
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", file.toFile());
	}

	private void sidecar(String folder, String json) throws IOException {
		Path dir = _base.resolve(folder);
		Files.createDirectories(dir);
		Files.write(dir.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String path) throws IOException {
		return new String(Files.readAllBytes(_base.resolve(path)), StandardCharsets.UTF_8);
	}
}
