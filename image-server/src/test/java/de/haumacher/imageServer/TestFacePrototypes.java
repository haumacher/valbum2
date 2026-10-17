/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Person;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The prototypes of the face recognition kept in the index of the space, see issues #127 and #264:
 * a restart knows at once whom the people look like, without an album being listed or walked.
 *
 * <p>
 * Driven with the synthetic faces of {@link TestFaceRecognition}: one face per photograph, whose
 * numbers depend on the portrait alone.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFacePrototypes extends FacesTestCase {

	private static final String ELSEWHERE = "2024-06-01 Elsewhere";

	private static final String AGAIN = "2024-07-01 Again";

	/** The album of the new photographs, which nobody listed before. */
	private static final String NEW = "2024-08-01 New";

	private static final int DIMENSIONS = 128;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		FaceDetection.setDetector(preview -> {
			String name = preview.getName();
			if (name.startsWith(PreviewCache.PREVIEW_PREFIX)) {
				name = name.substring(PreviewCache.PREVIEW_PREFIX.length());
			}
			return new FaceDetection.Result(800, 600, Collections.singletonList(
				new FaceDetection.Detected(300, 200, 200, 200, 0.99, embeddingOf(name))));
		});
	}

	// --- The restart gap. ---

	/**
	 * People confirmed in several albums are recognised in a new photograph right after a restart,
	 * with no other album listed: what was suggested before the restart is suggested after it.
	 */
	public void testARestartSuggestsAtOnceWithoutListingTheAlbums() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, B);
		index();
		Person anna = created("Anna");
		Person bob = created("Bob");
		confirm(ALBUM, A_ONE, anna);
		confirm(ELSEWHERE, B, bob);

		// Before the restart, a new album: both are recognised.
		File before = addAlbum(NEW + " 1", A_TWO, B);
		_servlet.faces().indexFolder(before, false);
		assertEquals(anna.getId(), suggestion(face(NEW + " 1", A_TWO)));
		assertEquals(bob.getId(), suggestion(face(NEW + " 1", B)));

		restart();
		assertEquals("Read from the index of the space, before anything is listed or walked.", 2,
			_servlet.faces().recognition().size());

		// Only the new album is looked at and listed.
		File after = addAlbum(NEW + " 2", A_TWO, B);
		_servlet.faces().indexFolder(after, false);
		assertEquals("The same suggestions as before the restart.", anna.getId(), suggestion(face(NEW + " 2", A_TWO)));
		assertEquals(bob.getId(), suggestion(face(NEW + " 2", B)));
		assertEquals("No album but the new one was read for its prototypes.", 1, _servlet.faces().recognition().reads());
	}

	// --- Kept current. ---

	/** A confirmation is kept, a rejection takes it back, and so does a cache thrown away. */
	public void testTheIndexFollowsEveryDecision() throws Exception {
		createSpace(A_ONE);
		addAlbum(NEW, A_TWO);
		index();
		Person anna = created("Anna");

		confirm(ALBUM, A_ONE, anna);
		assertEquals(Collections.singletonList(anna.getId()), storedPersons());
		restart();
		assertEquals("A confirmation survives a restart.", anna.getId(), suggestion(face(NEW, A_TWO)));

		tag(ALBUM, assignment(A_ONE, 0, anna.getId(), "REJECTED"));
		assertEquals("A rejection takes the prototype with it.", Collections.emptyList(), storedPersons());
		restart();
		assertEquals("", suggestion(face(NEW, A_TWO)));

		confirm(ALBUM, A_ONE, anna);
		assertEquals(Collections.singletonList(anna.getId()), storedPersons());
		tag(ALBUM, assignment(A_ONE, 0, "", "UNDECIDED"));
		assertEquals("A decision forgotten takes it too.", Collections.emptyList(), storedPersons());

		confirm(ALBUM, A_ONE, anna);
		assertEquals(200, post("/" + ALBUM + "/", "refresh-cache", "{}", _adminToken).status());
		assertEquals("The cache it was read from is gone.", Collections.emptyList(), storedPersons());
		restart();
		assertEquals("And stays gone across a restart.", "", suggestion(face(NEW, A_TWO)));
	}

	/**
	 * Two people the numbers cannot tell apart are no suggestion; merged, they are one person, whose
	 * name is whatever they are called now.
	 */
	public void testAMergeAndARenameAreFeltAcrossARestart() throws Exception {
		createSpace(A_ONE);
		addAlbum(AGAIN, A_ONE);
		addAlbum(NEW, A_TWO);
		index();
		Person anna = created("Anna");
		Person annie = created("Annie");
		confirm(ALBUM, A_ONE, anna);
		confirm(AGAIN, A_ONE, annie);
		restart();
		assertEquals("Anna and Annie look the same: nobody is suggested.", "", suggestion(face(NEW, A_TWO)));

		assertEquals(200, post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + annie.getId() + "\"}", _adminToken).status());
		restart();
		assertEquals("Merged, both faces describe the survivor.", anna.getId(), suggestion(face(NEW, A_TWO)));

		assertEquals(200, post("/", "rename-person",
			"{\"id\":\"" + anna.getId() + "\",\"name\":\"Anne\"}", _adminToken).status());
		restart();
		assertEquals(anna.getId(), suggestion(face(NEW, A_TWO)));
		assertEquals("The name follows the person.", "Anne", _servlet.people().resolve(anna.getId()).getName());
	}

	/** An album moved takes its prototypes along; one deleted takes them away. */
	public void testAMoveAndADeleteAreInTheIndexAtOnce() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_ONE);
		addAlbum(NEW, A_TWO);
		index();
		Person anna = created("Anna");
		confirm(ELSEWHERE, A_ONE, anna);
		assertEquals(Collections.singletonList(ELSEWHERE), storedAlbums(anna.getId()));

		java.nio.file.Files.createDirectories(_base.resolve("Archive"));
		FakeResponse moved = post("/", "move",
			"{\"target\":\"Archive\",\"names\":[{\"name\":\"" + ELSEWHERE + "\"}]}", _adminToken);
		assertEquals(moved.body(), 200, moved.status());
		assertEquals("The prototype moved with its album.",
			Collections.singletonList("Archive/" + ELSEWHERE), storedAlbums(anna.getId()));
		restart();
		assertEquals(anna.getId(), suggestion(face(NEW, A_TWO)));

		FakeResponse deleted = post("/Archive/", "delete",
			"{\"target\":\"\",\"names\":[{\"name\":\"" + ELSEWHERE + "\"}]}", _adminToken);
		assertEquals(deleted.body(), 200, deleted.status());
		assertEquals("A deleted album describes nobody.", Collections.emptyList(), storedAlbums(anna.getId()));
		restart();
		assertEquals("", suggestion(face(NEW, A_TWO)));
	}

	// --- Added to in place. ---

	/**
	 * An index of schema 4 without the tables of the prototypes, as the build before wrote it, is
	 * added to, not rebuilt: the read marks of the bell and the search stay, and the background
	 * fills the prototypes, though the faces of every album are done.
	 */
	public void testAnIndexOfTheBuildBeforeIsAddedToInPlace() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO);
		_servlet.startIndexing();
		assertTrue(_servlet.index().awaitPass(60_000));
		assertTrue(_servlet.index().pipeline().awaitIdle(120_000));
		Person anna = created("Anna");
		confirm(ALBUM, A_ONE, anna);
		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));
		markRead();
		int[] search = _servlet.searchIndex().size();
		assertTrue("The search knows the photographs.", search[1] >= 2);
		long created = _servlet.spaceIndex().created();
		stop();
		asTheBuildBefore();

		start();
		SpaceIndex store = _servlet.spaceIndex();
		assertFalse("Not rebuilt.", store.isFresh());
		assertEquals(created, store.created());
		assertEquals(String.valueOf(SpaceIndex.MIGRATION), meta(store, SpaceIndex.MIGRATION_KEY));
		assertEquals("The read marks of the bell stay.", 1, count(store, "arrival_marks"));
		assertEquals("And so does the search.", search[1], _servlet.searchIndex().size()[1]);
		assertEquals("Nothing is known of the prototypes yet.", 0, count(store, "face_albums"));

		_servlet.startIndexing();
		assertTrue(_servlet.index().awaitPass(60_000));
		assertTrue(_servlet.index().pipeline().awaitIdle(120_000));
		assertEquals("The background filled them.", Collections.singletonList(anna.getId()), storedPersons());

		restart();
		assertEquals("And a restart knows them.", 1, _servlet.faces().recognition().size());
		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));
	}

	/** An index of a later build, which this one could not keep current, is built again. */
	public void testAnIndexOfALaterBuildIsRebuilt() throws Exception {
		createSpace(A_ONE);
		_servlet.spaceIndex().write(connection -> {
			try (PreparedStatement update = connection.prepareStatement("UPDATE meta SET val = ? WHERE name = ?")) {
				update.setString(1, String.valueOf(SpaceIndex.MIGRATION + 1));
				update.setString(2, SpaceIndex.MIGRATION_KEY);
				assertEquals(1, update.executeUpdate());
			}
		});
		restart();
		assertTrue(_servlet.spaceIndex().isFresh());
		assertEquals(String.valueOf(SpaceIndex.MIGRATION), meta(_servlet.spaceIndex(), SpaceIndex.MIGRATION_KEY));
	}

	/**
	 * A process killed while it fills the prototypes leaves an index that opens without a rebuild,
	 * and the next fill takes up where the killed one left off.
	 */
	public void testAKilledFillIsResumed() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, B);
		addAlbum(AGAIN, A_ONE);
		index();
		Person anna = created("Anna");
		Person bob = created("Bob");
		confirm(ALBUM, A_ONE, anna);
		confirm(ELSEWHERE, B, bob);
		confirm(AGAIN, A_ONE, anna);
		markRead();
		stop();
		asTheBuildBefore();

		// Another process adds the tables, fills the first album, and is killed while it fills
		// the others.
		List<String> command = new ArrayList<>();
		command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(KilledFaceFill.class.getName());
		command.add(_base.toString());
		command.add("3000");
		command.add(_base.resolve(ALBUM).toString());
		command.add(_base.resolve(ELSEWHERE).toString());
		command.add(_base.resolve(AGAIN).toString());
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue("The fill did not end: " + output, process.waitFor(60, TimeUnit.SECONDS));
		assertEquals(output, KilledFaceFill.KILLED, process.exitValue());

		start();
		SpaceIndex store = _servlet.spaceIndex();
		assertFalse("A killed fill is no reason for a rebuild: " + output, store.isFresh());
		assertEquals(1, count(store, "arrival_marks"));
		assertEquals(String.valueOf(SpaceIndex.MIGRATION), meta(store, SpaceIndex.MIGRATION_KEY));
		assertTrue("The first album was filled before the kill: " + output, storedAlbums(anna.getId()).contains(ALBUM));
		assertFalse("And is not read again.", _servlet.faces().recognition().refresh(_base.resolve(ALBUM).toFile()));

		for (File folder : FolderPipeline.tree(_base.toFile())) {
			assertEquals(FolderPipeline.Outcome.DONE, _servlet.faces().prototypeStep().run(folder));
		}
		assertEquals("Complete.", List.of(ALBUM, AGAIN), storedAlbums(anna.getId()));
		assertEquals(List.of(ELSEWHERE), storedAlbums(bob.getId()));
		assertEquals(3, _servlet.faces().recognition().size());
	}

	// --- Helpers. ---

	/** Stops the servlet, as a shutdown does. */
	private void stop() {
		_servlet.destroy();
		_servlet = null;
	}

	/** Starts a servlet over the space, as a start does. */
	private void start() throws Exception {
		_auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), _auth, "", SpaceStore.load(_base, ""));
		_servlet.init();
	}

	/** Makes the index of the stopped space what the build before this one wrote: no prototypes. */
	private void asTheBuildBefore() {
		try (SpaceIndex store = SpaceIndex.open(_base)) {
			assertTrue(store.write(connection -> {
				try (Statement statement = connection.createStatement()) {
					statement.execute("DROP TABLE face_prototypes");
					statement.execute("DROP TABLE face_albums");
					statement.execute("DELETE FROM meta WHERE name = '" + SpaceIndex.MIGRATION_KEY + "'");
				}
			}));
		}
	}

	/** Marks something as read in the bell, a row the index keeps and no sidecar knows. */
	private void markRead() {
		assertTrue(_servlet.spaceIndex().write(connection -> {
			try (PreparedStatement insert = connection.prepareStatement("INSERT INTO arrival_marks "
				+ "(member, folder, actor, first_at, last_at, marked) VALUES (?, ?, ?, ?, ?, ?)")) {
				insert.setString(1, "haui");
				insert.setString(2, ALBUM);
				insert.setString(3, "haui");
				insert.setLong(4, 1);
				insert.setLong(5, 2);
				insert.setLong(6, 3);
				insert.executeUpdate();
			}
		}));
	}

	private static int count(SpaceIndex store, String table) {
		return store.read(connection -> {
			try (Statement statement = connection.createStatement();
					ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
				rows.next();
				return Integer.valueOf(rows.getInt(1));
			}
		}, Integer.valueOf(-1)).intValue();
	}

	private static String meta(SpaceIndex store, String name) {
		return store.read(connection -> {
			try (PreparedStatement select = connection.prepareStatement("SELECT val FROM meta WHERE name = ?")) {
				select.setString(1, name);
				try (ResultSet rows = select.executeQuery()) {
					return rows.next() ? rows.getString(1) : null;
				}
			}
		}, null);
	}

	/** The persons of the prototypes the index holds, in order. */
	private List<String> storedPersons() {
		return _servlet.spaceIndex().read(connection -> {
			List<String> result = new ArrayList<>();
			try (Statement statement = connection.createStatement();
					ResultSet rows = statement.executeQuery("SELECT person FROM face_prototypes ORDER BY album, n")) {
				while (rows.next()) {
					result.add(rows.getString(1));
				}
			}
			return result;
		}, null);
	}

	/** The albums the index holds a prototype of the given person of, in order. */
	private List<String> storedAlbums(String person) {
		return _servlet.spaceIndex().read(connection -> {
			List<String> result = new ArrayList<>();
			try (PreparedStatement select =
				connection.prepareStatement("SELECT DISTINCT album FROM face_prototypes WHERE person = ? ORDER BY album")) {
				select.setString(1, person);
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						result.add(rows.getString(1));
					}
				}
			}
			return result;
		}, null);
	}

	private static String suggestion(FaceInfo face) {
		if (face.getState() != FaceState.UNDECIDED || face.isConfirmed()) {
			return "";
		}
		return face.getPerson();
	}

	private FaceInfo face(String folder, String name) throws Exception {
		ImagePart image = image(album("/" + folder + "/", _adminToken), name);
		assertFalse("No face in '" + folder + "/" + name + "'.", image.getFaces().isEmpty());
		return image.getFaces().get(0);
	}

	private Person created(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private void confirm(String folder, String name, Person person) throws Exception {
		tag(folder, assignment(name, 0, person.getId(), "CONFIRMED"));
	}

	private void tag(String folder, String... assignments) throws Exception {
		FakeResponse response = post("/" + folder + "/", "tag-faces",
			"{\"faces\":[" + String.join(",", assignments) + "]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
	}

	private static String assignment(String image, int face, String person, String state) {
		return "{\"image\":\"" + image + "\",\"face\":" + face + ",\"person\":\"" + person
			+ "\",\"state\":\"" + state + "\"}";
	}

	/** See {@link TestFaceRecognition}: "A" twice 0.95 alike, "B" and "C" nobody like them. */
	private static float[] embeddingOf(String name) {
		float[] result = new float[DIMENSIONS];
		if (A_ONE.equals(name)) {
			result[0] = 1;
		} else if (A_TWO.equals(name)) {
			result[0] = 0.95f;
			result[1] = (float) Math.sqrt(1 - 0.95 * 0.95);
		} else if (B.equals(name)) {
			result[2] = 1;
		} else {
			result[3] = 1;
		}
		return result;
	}
}
