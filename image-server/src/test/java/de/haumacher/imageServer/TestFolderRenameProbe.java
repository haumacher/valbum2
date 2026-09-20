/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestFolderNames.Composition;
import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.ErrorInfo;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Adversarial probes for the folder rename of issue #130.
 *
 * <p>
 * What {@link TestFolderRename} shows working, this one tries to break: a rename that only changes
 * the spelling of a name, a name a <em>file</em> holds, a chain of renames, a title from the shared
 * table driven through the servlet, and the backup of the sidecar a second write leaves behind.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFolderRenameProbe extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-rename-probe");
		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
		_servlet.init();
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		if (_base != null && Files.exists(_base)) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	/** Probe: a rename that only changes the spelling of the name is no clash with itself. */
	public void testProbeACaseOnlyRenameIsNotANameThatIsTaken() throws Exception {
		sidecar("A/trip", album("trip", 0L));

		CreateResult result = put("/A/trip/", album("Trip", 0L));

		assertEquals("A/Trip", result.getPath());
		assertTrue(_base.resolve("A/Trip/index.json").toFile().isFile());
	}

	/** Probe: a name a plain file already holds is a name that is taken. */
	public void testProbeAFileOfThatNameIsARefusal() throws Exception {
		sidecar("A/Trip", album("Trip", 0L));
		write("A/Journey", "not a folder".getBytes(StandardCharsets.UTF_8));

		FakeResponse response = putResponse("/A/Trip/", album("Journey", 0L));

		assertEquals(HttpServletResponse.SC_CONFLICT, response.status());
		assertEquals(MoveService.nameTaken("Journey"), errorMessage(response));
		assertEquals("The file was not overwritten.", "not a folder", read(_base.resolve("A/Journey")));
	}

	/** Probe: renaming the same album three times in a row leaves exactly one folder. */
	public void testProbeAChainOfRenamesLeavesOneFolder() throws Exception {
		sidecar("A/One", album("One", 0L));

		put("/A/One/", album("Two", 0L));
		put("/A/Two/", album("Three", 0L));
		CreateResult result = put("/A/Three/", album("Four", day(2002, 3, 4)));

		assertEquals("A/2002-03-04 Four", result.getPath());
		String[] children = _base.resolve("A").toFile().list();
		assertNotNull(children);
		assertEquals("Exactly one folder is left: " + String.join(", ", children), 1, children.length);
	}

	/** Probe: the backup of the previous sidecar lands in the folder the album now has. */
	public void testProbeTheBackupLandsInTheRenamedFolder() throws Exception {
		sidecar("A/Trip", album("Trip", 0L));

		put("/A/Trip/", album("Journey", 0L));

		String[] names = _base.resolve("A/Journey").toFile().list((dir, name) -> name.startsWith("index.json."));
		assertNotNull(names);
		assertEquals("The backup of a renamed album is beside the album, not beside nothing.", 1, names.length);
		assertFalse(_base.resolve("A/Trip").toFile().exists());
	}

	/** Probe: every title of the shared table names its folder exactly as the table says. */
	public void testProbeTheSharedTableDrivenThroughTheServlet() throws Exception {
		List<String> seen = new ArrayList<>();
		for (Composition row : TestFolderNames.compositions()) {
			if (row.getTitle().trim().isEmpty() || seen.contains(row.getName())) {
				// A folder without a title composes no name at all and keeps the one it has, and a
				// name an earlier row already took would be a clash and not a rename.
				continue;
			}
			seen.add(row.getName());
			String folder = "T" + seen.size();
			sidecar(folder, album("start " + seen.size(), 0L));

			CreateResult result = put("/" + folder + "/", album(row.getTitle(), row.millis()));

			assertEquals("title '" + row.getTitle() + "'", row.getName(), result.getPath());
		}
		assertTrue("The table must have driven something.", seen.size() > 5);
	}

	/** Probe: an album at the top of a space is renamed like any other. */
	public void testProbeAnAlbumAtTheTopIsRenamedToo() throws Exception {
		sidecar("Trip", album("Trip", 0L));

		CreateResult result = put("/Trip/", album("Journey", 0L));

		assertEquals("Journey", result.getPath());
	}

	// --- Helpers. ---

	private static long day(int year, int month, int dayOfMonth) {
		return LocalDate.of(year, month, dayOfMonth).atStartOfDay(ZoneId.systemDefault()).toInstant()
			.toEpochMilli();
	}

	private static String album(String title, long date) {
		return "[\"AlbumInfo\",{\"title\":\"" + title.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
			+ (date == 0L ? "" : ",\"date\":" + date) + ",\"parts\":[]}]";
	}

	private void sidecar(String folder, String contents) throws IOException {
		Path path = _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"), contents.getBytes(StandardCharsets.UTF_8));
	}

	private void write(String relativePath, byte[] contents) throws IOException {
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		Files.write(path, contents);
	}

	private CreateResult put(String pathInfo, String body) throws Exception {
		FakeResponse response = putResponse(pathInfo, body);
		assertEquals("The write failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return CreateResult.readCreateResult(reader(response.body()));
	}

	private FakeResponse putResponse(String pathInfo, String body) throws Exception {
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "application/json");
		HttpServletRequest request = TestImageServletPut.request(pathInfo, "application/json",
			body.getBytes(StandardCharsets.UTF_8), headers, new HashMap<>());
		FakeResponse response = new FakeResponse();
		_servlet.doPut(request, response.response());
		return response;
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	/** Keeps the import honest: an album really is what is written here. */
	public void testProbeTheFixtureIsAnAlbum() throws Exception {
		Resource resource = Resource.readResource(reader(album("Trip", 0L)));
		assertTrue(resource instanceof AlbumInfo);
	}

}
