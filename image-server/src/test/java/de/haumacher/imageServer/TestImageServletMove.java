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
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for moving images, albums and folders between folders (issue #47).
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder with the request and response fakes
 * of {@link TestImageServletPut}. The images are real (tiny) JPEGs drawn here, each with different
 * pixels, so that the content hashes the move compares really differ; the albums are described by
 * hand-written sidecars, so that a test says exactly what the album knows.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageServletMove extends TestCase {

	private static final String SECRET = "let-me-in";

	private static final String ALICE_TOKEN = "alice-token";

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-move-test");
		_servlet = servlet(AuthMode.OFF);
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- A single image, with everything that is known about it. ---

	public void testMoveOneImage() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		image("A/b.jpg", 8, 6, Color.GREEN);
		image("B/c.jpg", 8, 6, Color.BLUE);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":["
			+ part("a.jpg", "\"rating\":2,\"privacy\":1,\"comment\":\"a comment\",\"orientation\":\"ROT_180\"")
			+ "," + part("b.jpg", "") + "]}]");
		sidecar("B", "[\"AlbumInfo\",{\"title\":\"B\",\"parts\":[" + part("c.jpg", "") + "]}]");
		// The hash caches exist before the move, as they would after an upload.
		hashes("A");
		hashes("B");

		MoveResult result = move("/A/", "B", "a.jpg");

		assertEquals(Collections.singletonList("a.jpg"), newNames(result));
		assertEquals("", result.getOutcomes().get(0).getMessage());

		assertFalse("The file must be gone from the source.", _base.resolve("A/a.jpg").toFile().exists());
		assertTrue("The file must be at the target.", _base.resolve("B/a.jpg").toFile().exists());

		assertEquals(Collections.singletonList("b.jpg"), imageParts(album("A")));
		assertEquals(Arrays.asList("c.jpg", "a.jpg"), imageParts(album("B")));

		ImagePart moved = image(album("B"), "a.jpg");
		assertEquals("The rating travels with the image.", 2, moved.getRating());
		assertEquals("The privacy level travels with the image.", 1, moved.getPrivacy());
		assertEquals("The comment travels with the image.", "a comment", moved.getComment());
		assertEquals("The orientation travels with the image.", Orientation.ROT_180, moved.getOrientation());
		assertTrue("The dimensions travel with the image.", moved.getWidth() > 0 && moved.getHeight() > 0);

		assertFalse("The hash entry must leave the source folder.", hashes("A").containsKey("a.jpg"));
		assertEquals("The hash entry must arrive at the target folder.",
			HashCache.sha256(_base.resolve("B/a.jpg").toFile()), hashes("B").get("a.jpg"));
	}

	public void testAnImageMovesIntoAFolderThatHeldNone() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "\"rating\":1") + "]}]");
		Files.createDirectories(_base.resolve("B"));

		MoveResult result = move("/A/", "B", "a.jpg");

		assertEquals(Collections.singletonList("a.jpg"), newNames(result));
		assertTrue("The target folder gets a sidecar of its own.", _base.resolve("B/index.json").toFile().exists());
		assertEquals(Collections.singletonList("a.jpg"), imageParts(album("B")));
		assertEquals(1, image(album("B"), "a.jpg").getRating());
	}

	public void testTheSpaceRootIsAValidTarget() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "") + "]}]");

		MoveResult result = move("/A/", "", "a.jpg");

		assertEquals(Collections.singletonList("a.jpg"), newNames(result));
		assertTrue(_base.resolve("a.jpg").toFile().exists());
	}

	// --- Groups. ---

	public void testMovingTheRepresentativeMovesTheWholeGroup() throws Exception {
		image("A/g1.jpg", 8, 6, Color.RED);
		image("A/g2.jpg", 8, 6, Color.GREEN);
		image("A/g3.jpg", 8, 6, Color.BLUE);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + group(1, "g1.jpg", "g2.jpg", "g3.jpg") + "]}]");
		Files.createDirectories(_base.resolve("B"));

		MoveResult result = move("/A/", "B", "g2.jpg");

		assertEquals(Collections.singletonList("g2.jpg"), newNames(result));
		for (String name : Arrays.asList("g1.jpg", "g2.jpg", "g3.jpg")) {
			assertFalse(name + " must have left the source.", _base.resolve("A/" + name).toFile().exists());
			assertTrue(name + " must be at the target.", _base.resolve("B/" + name).toFile().exists());
		}

		assertEquals("The source album is empty.", Collections.emptyList(), album("A").getParts());

		List<AlbumPart> parts = album("B").getParts();
		assertEquals("The group arrives as one part.", 1, parts.size());
		ImageGroup moved = (ImageGroup) parts.get(0);
		assertEquals(Arrays.asList("g1.jpg", "g2.jpg", "g3.jpg"), names(moved.getImages()));
		assertEquals("The representative is the same image.", "g2.jpg",
			moved.getImages().get(moved.getRepresentative()).getName());
	}

	public void testMovingAMemberLeavesASmallerGroup() throws Exception {
		image("A/g1.jpg", 8, 6, Color.RED);
		image("A/g2.jpg", 8, 6, Color.GREEN);
		image("A/g3.jpg", 8, 6, Color.BLUE);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + group(1, "g1.jpg", "g2.jpg", "g3.jpg") + "]}]");
		Files.createDirectories(_base.resolve("B"));

		MoveResult result = move("/A/", "B", "g3.jpg");

		assertEquals(Collections.singletonList("g3.jpg"), newNames(result));
		assertTrue("Only the named member moves.", _base.resolve("A/g1.jpg").toFile().exists());

		List<AlbumPart> parts = album("A").getParts();
		assertEquals(1, parts.size());
		ImageGroup rest = (ImageGroup) parts.get(0);
		assertEquals(Arrays.asList("g1.jpg", "g2.jpg"), names(rest.getImages()));
		assertEquals("The representative stays the same image.", "g2.jpg",
			rest.getImages().get(rest.getRepresentative()).getName());

		assertEquals("A member arrives as a plain image.", Collections.singletonList("g3.jpg"),
			imageParts(album("B")));
		assertTrue(album("B").getParts().get(0) instanceof ImagePart);
	}

	public void testAGroupOfOneBecomesAPlainImage() throws Exception {
		image("A/g1.jpg", 8, 6, Color.RED);
		image("A/g2.jpg", 8, 6, Color.GREEN);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + group(0, "g1.jpg", "g2.jpg") + "]}]");
		Files.createDirectories(_base.resolve("B"));

		move("/A/", "B", "g2.jpg");

		List<AlbumPart> parts = album("A").getParts();
		assertEquals(1, parts.size());
		assertTrue("A group of one is not a group: " + parts.get(0), parts.get(0) instanceof ImagePart);
		assertEquals("g1.jpg", ((ImagePart) parts.get(0)).getName());
	}

	// --- Conflicts at the target. ---

	public void testSameNameDifferentContentIsRenamed() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		image("B/a.jpg", 12, 9, Color.BLUE);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "\"rating\":2") + "]}]");
		sidecar("B", "[\"AlbumInfo\",{\"title\":\"B\",\"parts\":[" + part("a.jpg", "") + "]}]");

		MoveResult result = move("/A/", "B", "a.jpg");

		assertEquals("Renamed exactly as a colliding upload is.", Collections.singletonList("a-2.jpg"),
			newNames(result));
		assertEquals("", result.getOutcomes().get(0).getMessage());
		assertTrue("The file that was there must survive untouched.", _base.resolve("B/a.jpg").toFile().exists());
		assertTrue(_base.resolve("B/a-2.jpg").toFile().exists());
		assertEquals(Arrays.asList("a.jpg", "a-2.jpg"), imageParts(album("B")));
		assertEquals("The part is renamed with the file.", 2, image(album("B"), "a-2.jpg").getRating());
	}

	public void testSameContentAtTheTargetIsSetAside() throws Exception {
		byte[] contents = jpeg(8, 6, Color.RED);
		write("A/a.jpg", contents);
		write("B/twin.jpg", contents);
		image("A/other.jpg", 8, 6, Color.GREEN);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "") + "," + part("other.jpg", "")
			+ "]}]");
		sidecar("B", "[\"AlbumInfo\",{\"title\":\"B\",\"parts\":[" + part("twin.jpg", "") + "]}]");

		MoveResult result = move("/A/", "B", "a.jpg");

		MoveOutcome outcome = result.getOutcomes().get(0);
		assertEquals("The answer names the file that already holds the contents.", "twin.jpg", outcome.getNewName());
		assertEquals(MoveService.duplicate("twin.jpg"), outcome.getMessage());

		assertFalse("The source album must not keep it.", _base.resolve("A/a.jpg").toFile().exists());
		assertEquals(Collections.singletonList("other.jpg"), imageParts(album("A")));
		assertEquals("The target must be untouched.", Collections.singletonList("twin.jpg"), imageParts(album("B")));
		assertFalse("Nothing is copied to the target.", _base.resolve("B/a.jpg").toFile().exists());

		File aside = new File(_base.resolve(UserStore.DIRECTORY_NAME).resolve(MoveService.DUPLICATES_FOLDER).toFile(),
			HashCache.sha256(contents) + "-a.jpg");
		assertTrue("The bytes must still exist: " + aside, aside.exists());
		assertTrue("The server never deletes an original.",
			Arrays.equals(contents, Files.readAllBytes(aside.toPath())));
	}

	// --- Albums and folders. ---

	public void testMoveAnAlbumFolder() throws Exception {
		image("A/Trip/a.jpg", 8, 6, Color.RED);
		sidecar("A/Trip", "[\"AlbumInfo\",{\"title\":\"The trip\",\"parts\":[" + part("a.jpg", "\"rating\":2")
			+ "]}]");
		Files.createDirectories(_base.resolve("B"));

		MoveResult result = move("/A/", "B", "Trip");

		assertEquals(Collections.singletonList("Trip"), newNames(result));
		assertFalse(_base.resolve("A/Trip").toFile().exists());
		assertTrue(_base.resolve("B/Trip/a.jpg").toFile().exists());
		assertEquals("The sidecar rides along.", "The trip", album("B/Trip").getTitle());
		assertEquals(2, image(album("B/Trip"), "a.jpg").getRating());

		ListingInfo target = (ListingInfo) Resource.readResource(reader(getJson("/B/")));
		assertEquals(Collections.singletonList("Trip"),
			target.getFolders().stream().map(f -> f.getName()).collect(Collectors.toList()));
		ListingInfo source = (ListingInfo) Resource.readResource(reader(getJson("/A/")));
		assertEquals("The source listing no longer shows it.", Collections.emptyList(), source.getFolders());
	}

	public void testAFolderIsNotMovedIntoItself() throws Exception {
		Files.createDirectories(_base.resolve("A/Trip/Inner"));
		image("A/Trip/Inner/a.jpg", 8, 6, Color.RED);

		MoveResult refusedItself = move("/A/", "A/Trip", "Trip");
		assertEquals(MoveService.intoItself("Trip"), refusedItself.getOutcomes().get(0).getMessage());
		assertEquals("", refusedItself.getOutcomes().get(0).getNewName());

		MoveResult refusedBelow = move("/A/", "A/Trip/Inner", "Trip");
		assertEquals(MoveService.intoItself("Trip"), refusedBelow.getOutcomes().get(0).getMessage());

		assertTrue("Nothing may have moved.", _base.resolve("A/Trip/Inner/a.jpg").toFile().exists());
	}

	public void testATakenFolderNameIsRefused() throws Exception {
		image("A/Trip/a.jpg", 8, 6, Color.RED);
		Files.createDirectories(_base.resolve("B/Trip"));

		MoveResult result = move("/A/", "B", "Trip");

		assertEquals(MoveService.nameTaken("Trip"), result.getOutcomes().get(0).getMessage());
		assertEquals("", result.getOutcomes().get(0).getNewName());
		assertTrue("Nothing is overwritten.", _base.resolve("A/Trip/a.jpg").toFile().exists());
		assertEquals(Collections.emptyList(), Arrays.asList(_base.resolve("B/Trip").toFile().list()));
	}

	public void testAGroupWhoseMemberIsAlreadyAtTheTarget() throws Exception {
		byte[] shared = jpeg(8, 6, Color.RED);
		write("A/g1.jpg", shared);
		image("A/g2.jpg", 8, 6, Color.GREEN);
		write("B/twin.jpg", shared);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + group(1, "g1.jpg", "g2.jpg") + "]}]");
		sidecar("B", "[\"AlbumInfo\",{\"title\":\"B\",\"parts\":[" + part("twin.jpg", "") + "]}]");

		MoveResult result = move("/A/", "B", "g2.jpg");

		assertEquals("g2.jpg", result.getOutcomes().get(0).getNewName());
		assertEquals(MoveService.membersSetAside(1), result.getOutcomes().get(0).getMessage());
		assertEquals("A group left with one image is a plain image.", Arrays.asList("twin.jpg", "g2.jpg"),
			imageParts(album("B")));
		assertTrue(album("B").getParts().get(1) instanceof ImagePart);
		assertEquals("The source album is empty.", Collections.emptyList(), album("A").getParts());
		assertTrue("The duplicate is kept, never deleted.",
			new File(_base.resolve(UserStore.DIRECTORY_NAME).resolve(MoveService.DUPLICATES_FOLDER).toFile(),
				HashCache.sha256(shared) + "-g1.jpg").exists());
	}

	// --- Things that are not album entries. ---

	public void testWhatCannotBeMoved() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "") + "]}]");
		Files.createDirectories(_base.resolve("B"));
		Files.write(_base.resolve("A/notes.txt"), "text".getBytes(StandardCharsets.UTF_8));

		MoveResult result = move("/A/", "B", "notes.txt", "index.json", "../B", "a.jpg", "a.jpg");

		assertEquals(MoveService.notAnEntry("notes.txt"), result.getOutcomes().get(0).getMessage());
		assertEquals("The server's own files are no album entries.", MoveService.notAnEntry("index.json"),
			result.getOutcomes().get(1).getMessage());
		assertEquals("A name is a name, never a path.", MoveService.notAnEntry("../B"),
			result.getOutcomes().get(2).getMessage());
		assertEquals("a.jpg", result.getOutcomes().get(3).getNewName());
		assertEquals(MoveService.namedTwice("a.jpg"), result.getOutcomes().get(4).getMessage());
		assertTrue(_base.resolve("A/index.json").toFile().exists());
		assertTrue(_base.resolve("A/notes.txt").toFile().exists());
	}

	public void testAnImageIsNotMovedIntoAFolderOfFolders() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "") + "]}]");
		sidecar("B", "[\"ListingInfo\",{\"title\":\"B\"}]");

		MoveResult result = move("/A/", "B", "a.jpg");

		assertEquals(MoveService.notAnAlbum("a.jpg"), result.getOutcomes().get(0).getMessage());
		assertTrue("Nothing may have moved.", _base.resolve("A/a.jpg").toFile().exists());
		assertTrue("The sidecar of the target must be untouched.", resource("B") instanceof ListingInfo);
	}

	public void testAMissingSourceRefusesTheWholeRequest() throws Exception {
		Files.createDirectories(_base.resolve("B"));

		FakeResponse response = moveResponse(_servlet, "/nowhere/", "B", null, "a.jpg");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(MoveService.SOURCE_MISSING, errorMessage(response));
	}

	public void testATargetThatIsNotAFolderRefusesTheWholeRequest() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		image("b.jpg", 8, 6, Color.GREEN);

		FakeResponse response = moveResponse(_servlet, "/A/", "b.jpg", null, "a.jpg");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(MoveService.TARGET_NOT_A_FOLDER, errorMessage(response));
	}

	// --- The cover of the source album. ---

	public void testTheIndexPictureIsRepaired() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		image("A/b.jpg", 8, 6, Color.GREEN);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\","
			+ "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0,\"tx\":0.0,\"ty\":0.0},"
			+ "\"parts\":[" + part("a.jpg", "") + "," + part("b.jpg", "") + "]}]");
		Files.createDirectories(_base.resolve("B"));

		move("/A/", "B", "a.jpg");

		assertEquals("The cover follows the first remaining image.", "b.jpg", album("A").getIndexPicture().getImage());
	}

	public void testTheIndexPictureIsClearedWithTheLastImage() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\","
			+ "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0,\"tx\":0.0,\"ty\":0.0},"
			+ "\"parts\":[" + part("a.jpg", "") + "]}]");
		Files.createDirectories(_base.resolve("B"));

		move("/A/", "B", "a.jpg");

		assertNull("An album without images has no cover.", album("A").getIndexPicture());
	}

	// --- Users, spaces and refusals. ---

	public void testAMemberMovesInsideTheirSpace() throws Exception {
		member();
		image("alice/A/a.jpg", 8, 6, Color.RED);
		sidecar("alice/A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "") + "]}]");
		Files.createDirectories(_base.resolve("alice/B"));

		ImageServlet servlet = servlet(AuthMode.WRITES);
		try {
			MoveResult result = move(servlet, "/A/", "B", ALICE_TOKEN, "a.jpg");

			assertEquals(Collections.singletonList("a.jpg"), newNames(result));
			assertTrue(_base.resolve("alice/B/a.jpg").toFile().exists());
		} finally {
			servlet.destroy();
		}
	}

	public void testATargetOutsideTheSpaceIsRefused() throws Exception {
		member();
		image("alice/A/a.jpg", 8, 6, Color.RED);
		sidecar("alice/A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "") + "]}]");
		Files.createDirectories(_base.resolve("haui/Shared"));

		ImageServlet servlet = servlet(AuthMode.WRITES);
		try {
			FakeResponse response = moveResponse(servlet, "/A/", "../haui/Shared", ALICE_TOKEN, "a.jpg");

			assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
			assertEquals(MoveService.TARGET_ESCAPED, errorMessage(response));
			assertTrue("Nothing may have moved.", _base.resolve("alice/A/a.jpg").toFile().exists());
			assertFalse(_base.resolve("haui/Shared/a.jpg").toFile().exists());
		} finally {
			servlet.destroy();
		}
	}

	public void testAnAnonymousMoveIsRefused() throws Exception {
		member();
		image("haui/A/a.jpg", 8, 6, Color.RED);

		ImageServlet servlet = servlet(AuthMode.WRITES);
		try {
			FakeResponse response = moveResponse(servlet, "/A/", "B", null, "a.jpg");

			assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
			assertEquals("A move is a write and is refused like every other one.",
				AuthService.LIBRARY_REFUSED, errorMessage(response));
			assertEquals("Bearer", response.header("WWW-Authenticate"));
			assertTrue(_base.resolve("haui/A/a.jpg").toFile().exists());
		} finally {
			servlet.destroy();
		}
	}

	public void testAnUnknownNameIsReportedAndTheOthersMove() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		image("A/b.jpg", 8, 6, Color.GREEN);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "") + "," + part("b.jpg", "")
			+ "]}]");
		Files.createDirectories(_base.resolve("B"));

		MoveResult result = move("/A/", "B", "a.jpg", "nowhere.jpg", "b.jpg");

		assertEquals(Arrays.asList("a.jpg", "", "b.jpg"), newNames(result));
		assertEquals(MoveService.notFound("nowhere.jpg"), result.getOutcomes().get(1).getMessage());
		assertEquals(Arrays.asList("a.jpg", "b.jpg"), imageParts(album("B")));
		assertEquals(Collections.emptyList(), album("A").getParts());
	}

	public void testAMissingTargetRefusesTheWholeRequest() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);

		FakeResponse response = moveResponse(_servlet, "/A/", "nowhere", null, "a.jpg");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(MoveService.TARGET_MISSING, errorMessage(response));
		assertTrue(_base.resolve("A/a.jpg").toFile().exists());
	}

	public void testMovingIntoTheSourceFolderIsRefused() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);

		FakeResponse response = moveResponse(_servlet, "/A/", "A", null, "a.jpg");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(MoveService.SAME_FOLDER, errorMessage(response));
	}

	public void testAnUnreadableRequestIsRefused() throws Exception {
		FakeResponse response = new FakeResponse();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		_servlet.doPost(request("/", "application/json", "not JSON at all", null, parameters), response.response());

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(MoveService.MOVE_UNREADABLE, errorMessage(response));
	}

	public void testAFailedRenameStopsTheRequestAndSaysSo() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		image("A/b.jpg", 8, 6, Color.GREEN);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "") + "," + part("b.jpg", "")
			+ "]}]");
		File target = _base.resolve("B").toFile();
		Files.createDirectories(target.toPath());
		assertTrue(target.setWritable(false));
		try {
			try {
				if (new File(target, "probe").createNewFile()) {
					// A super user is not stopped by a read-only folder; nothing to test here.
					return;
				}
			} catch (IOException expected) {
				// The folder really is read-only, which is what this test needs.
			}

			MoveResult result = move("/A/", "B", "a.jpg", "b.jpg");

			assertTrue("Expected the reason of the failure: " + result.getOutcomes().get(0).getMessage(),
				result.getOutcomes().get(0).getMessage().startsWith(MoveService.failed("")));
			assertEquals("The entries behind a failure are named, never silently dropped.",
				MoveService.ABANDONED, result.getOutcomes().get(1).getMessage());
			assertTrue("Nothing may have moved.", _base.resolve("A/a.jpg").toFile().exists());
			assertEquals("The sidecar describes exactly what did move.", Arrays.asList("a.jpg", "b.jpg"),
				imageParts(album("A")));
		} finally {
			target.setWritable(true);
		}
	}

	// --- Read, move, read again. ---

	public void testTheRoundTripIsConsistentAndTheSecondRequestMovesNothing() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		image("A/b.jpg", 8, 6, Color.GREEN);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "\"rating\":2") + ","
			+ part("b.jpg", "") + "]}]");
		Files.createDirectories(_base.resolve("B"));

		// Reading first fills the cache; the move must not leave anything stale behind.
		assertTrue(getJson("/A/").contains("a.jpg"));
		assertNotNull(getJson("/B/"));

		move("/A/", "B", "a.jpg");

		assertEquals("What the GET answers is what the sidecar says.", read(_base.resolve("A/index.json")),
			getJson("/A/"));
		assertEquals(read(_base.resolve("B/index.json")), getJson("/B/"));

		MoveResult again = move("/A/", "B", "a.jpg");
		assertEquals(MoveService.notFound("a.jpg"), again.getOutcomes().get(0).getMessage());
		assertEquals("", again.getOutcomes().get(0).getNewName());
		assertEquals("Nothing may have moved a second time.", Collections.singletonList("a.jpg"), imageParts(album("B")));
	}

	// --- An album written before the privacy level existed. ---

	public void testAnOldSidecarWithoutRatingAndPrivacy() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6}]]}]");
		Files.createDirectories(_base.resolve("B"));

		MoveResult result = move("/A/", "B", "a.jpg");

		assertEquals(Collections.singletonList("a.jpg"), newNames(result));
		ImagePart moved = image(album("B"), "a.jpg");
		assertEquals("A missing rating stays the default.", 0, moved.getRating());
		assertEquals("A missing privacy level stays the default.", 0, moved.getPrivacy());
		assertEquals(Orientation.IDENTITY, moved.getOrientation());
		assertTrue("The target sidecar must be a valid album.", resource("B") instanceof AlbumInfo);
	}

	// --- Helpers. ---

	private ImageServlet servlet(AuthMode mode) throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(mode, SECRET, _base));
		servlet.init();
		return servlet;
	}

	/** A library with a named owner "haui" and a member "alice", each with a space of their own. */
	private void member() throws IOException {
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

	private MoveResult move(String pathInfo, String target, String... names) throws Exception {
		return move(_servlet, pathInfo, target, null, names);
	}

	private MoveResult move(ImageServlet servlet, String pathInfo, String target, String token, String... names)
			throws Exception {
		FakeResponse response = moveResponse(servlet, pathInfo, target, token, names);
		assertEquals("The move failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private static FakeResponse moveResponse(ImageServlet servlet, String pathInfo, String target, String token,
			String... names) throws Exception {
		String body = "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body, token, parameters), response.response());
		return response;
	}

	private String getJson(String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(pathInfo, null, "", null, parameters), response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return response.body();
	}

	private static HttpServletRequest request(String pathInfo, String contentType, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	/** The sidecar of the given folder, parsed. */
	private FolderResource resource(String folder) throws IOException {
		File index = _base.resolve(folder.isEmpty() ? "index.json" : folder + "/index.json").toFile();
		assertTrue("Expected a sidecar at " + index, index.exists());
		return FolderResource.readFolderResource(reader(read(index.toPath())));
	}

	/** The album the given folder's sidecar describes. */
	private AlbumInfo album(String folder) throws IOException {
		FolderResource resource = resource(folder);
		assertTrue("Expected an album in " + folder + ", got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
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
		throw new AssertionError("No image '" + name + "' in " + album.getTitle());
	}

	/** The names of the images the given album's parts show, in the stored order. */
	private static List<String> imageParts(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			} else if (part instanceof ImageGroup) {
				result.addAll(names(((ImageGroup) part).getImages()));
			}
		}
		return result;
	}

	private static List<String> names(List<ImagePart> images) {
		return images.stream().map(ImagePart::getName).collect(Collectors.toList());
	}

	private static List<String> newNames(MoveResult result) {
		return result.getOutcomes().stream().map(MoveOutcome::getNewName).collect(Collectors.toList());
	}

	/** The hashes recorded in the given folder's <code>.hashes.json</code>, brought up to date. */
	private Map<String, String> hashes(String folder) throws IOException {
		HashCache cache = new HashCache(_base.resolve(folder).toFile());
		Map<String, String> result = cache.hashByName();
		cache.flush();
		return result;
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

	/** An <code>ImagePart</code> for a hand-written sidecar. */
	private static String part(String name, String extra) {
		return "[\"ImagePart\",{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6"
			+ (extra.isEmpty() ? "" : "," + extra) + "}]";
	}

	/** An <code>ImageGroup</code> for a hand-written sidecar. */
	private static String group(int representative, String... names) {
		return "[\"ImageGroup\",{\"representative\":" + representative + ",\"images\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6}")
				.collect(Collectors.joining(","))
			+ "]}]";
	}

	private void sidecar(String folder, String contents) throws IOException {
		Path path = _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"), contents.getBytes(StandardCharsets.UTF_8));
	}

	private void image(String relativePath, int width, int height, Color color) throws IOException {
		write(relativePath, jpeg(width, height, color));
	}

	private void write(String relativePath, byte[] contents) throws IOException {
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		Files.write(path, contents);
	}

	/** A real (tiny) JPEG; different sizes and colours give different content hashes. */
	private static byte[] jpeg(int width, int height, Color color) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", buffer);
		return buffer.toByteArray();
	}

}
