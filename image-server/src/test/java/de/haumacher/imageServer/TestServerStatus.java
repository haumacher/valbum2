/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.content.ContentIndex;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.pipeline.ReadOnlyFolders;
import de.haumacher.imageServer.shared.model.StatusInfo;
import de.haumacher.imageServer.shared.model.StatusItem;
import de.haumacher.imageServer.shared.model.StatusProblem;
import de.haumacher.imageServer.shared.model.StatusProblemKind;
import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case for <code>?type=status</code>, the one answer of the status page of issue #305, see
 * {@link ServerStatus}.
 */
@SuppressWarnings("javadoc")
public class TestServerStatus extends ShareTestCase {

	/** The token of a second administrator. */
	private static final String FRANK = "frank-token";

	private final List<File> _readOnly = new ArrayList<>();

	@Override
	protected void tearDown() throws Exception {
		for (File folder : _readOnly) {
			ReadOnlyFolders.writtenAgain(folder);
		}
		super.tearDown();
	}

	// --- Who is answered what. ---

	/**
	 * The administrator sees every outstanding failure merged by cause, every folder the server
	 * cannot write with its paths and photos, why the content search waits, and the progress.
	 */
	public void testTheAdministratorSeesEverything() throws Exception {
		servlet().index().indexNow();
		FolderPipeline pipeline = servlet().index().pipeline();
		pipeline.reportFailure(folder(SharingFixture.ZOO), CatchUp.PREVIEWS, "a.jpg", "unreadable format", true);
		pipeline.reportFailure(folder(SharingFixture.ZOO), CatchUp.PREVIEWS, "b.jpg", "unreadable format", true);
		pipeline.reportFailure(folder(SharingFixture.PUBLIC), CatchUp.PREVIEWS, "c.jpg", "unreadable format", true);
		pipeline.reportFailure(folder(SharingFixture.PUBLIC), CatchUp.PREVIEWS, "d.jpg", "out of memory", true);
		readOnly(SharingFixture.ZOO, "its photo hashes");
		readOnly(SharingFixture.PRIVATE, "its previews and faces");
		readOnly(SharingFixture.PRIVATE, "its photo hashes");
		servlet().content().setEnabled(true);

		StatusInfo status = status(SharingFixture.ALICE);
		assertTrue(status.isAdmin());
		assertEquals(_base.toAbsolutePath().normalize().toString(), status.getRoot());
		assertNotNull(status.getCatchUp());
		assertTrue("The progress is answered: " + status, status.getCatchUp().getAlbumsTotal() > 0);
		assertNotNull(status.getIndex());

		StatusProblem unreadable = failure(status, "unreadable format");
		assertEquals(StatusProblemKind.FAILURE, unreadable.getKind());
		assertEquals(CatchUp.PREVIEWS, unreadable.getStepName());
		assertEquals("Merged by cause.", 3, unreadable.getFiles());
		assertEquals(2, unreadable.getAlbums());
		assertEquals("Sorted by folder and file.", List.of(SharingFixture.ZOO + "/a.jpg", SharingFixture.ZOO + "/b.jpg",
			SharingFixture.PUBLIC + "/c.jpg"), files(unreadable));
		assertFalse(unreadable.isSeen());
		assertEquals("Another reason, another cause.", 1, failure(status, "out of memory").getFiles());

		StatusProblem unwritable = problem(status, ServerStatus.UNWRITABLE_ID);
		assertEquals(StatusProblemKind.UNWRITABLE, unwritable.getKind());
		assertEquals("Every folder, sorted.", 2, unwritable.getItems().size());
		StatusItem first = unwritable.getItems().get(1);
		StatusItem second = unwritable.getItems().get(0);
		assertEquals(SharingFixture.PRIVATE, first.getFolder());
		assertEquals(SharingFixture.ZOO, second.getFolder());
		assertEquals(folder(SharingFixture.PRIVATE).getAbsolutePath(), first.getPath());
		assertEquals(folder(SharingFixture.ZOO).getAbsolutePath(), second.getPath());
		assertEquals(1, first.getPhotos());
		assertEquals(4, second.getPhotos());
		assertEquals("its previews and faces, its photo hashes", first.getWhat());
		assertTrue(unwritable.getReason(), unwritable.getReason().contains("chown"));

		StatusProblem embedder = problem(status, ServerStatus.EMBEDDER_ID);
		assertEquals(StatusProblemKind.EMBEDDER, embedder.getKind());
		assertEquals(ContentIndex.NO_EMBEDDER, embedder.getReason());
		assertEquals(ContentIndex.NO_EMBEDDER, status.getCatchUp().getContentWaiting());
		assertTrue(status.getCatchUp().isContentEnabled());

		assertFalse("Nothing secret: " + body(status), body(status).contains("token"));
	}

	public void testALinkAndNobodyAreRefused() throws Exception {
		FakeResponse linked = get("/", ImageServlet.STATUS_TYPE, zooToken());
		assertEquals(403, linked.status());
		assertEquals(ImageServlet.STATUS_REFUSED, errorMessage(linked));

		FakeResponse anonymous = get("/", ImageServlet.STATUS_TYPE, null);
		assertEquals(403, anonymous.status());
		assertEquals(ImageServlet.STATUS_REFUSED, errorMessage(anonymous));

		FakeResponse seen = seen(null, true);
		assertEquals(403, seen.status());
		assertEquals(ImageServlet.STATUS_REFUSED, errorMessage(seen));
	}

	/** A member is answered that they are none, and nothing of the administrator's part. */
	public void testAMemberGetsNothingAdminOnly() throws Exception {
		servlet().index().pipeline().reportFailure(folder(SharingFixture.PRIVATE), CatchUp.PREVIEWS, "secret.jpg",
			"unreadable format", true);
		readOnly(SharingFixture.PRIVATE, "its photo hashes");
		for (String token : new String[] { SharingFixture.CAROL, SharingFixture.DAVE }) {
			StatusInfo status = status(token);
			assertFalse(status.isAdmin());
			assertNull(status.getCatchUp());
			assertNull(status.getIndex());
			assertTrue(status.getProblems().isEmpty());
			assertTrue(status.getSteps().isEmpty());
			assertEquals("", status.getRoot());
			assertFalse(body(status), body(status).contains("secret"));

			FakeResponse seen = seen(token, true);
			assertEquals(403, seen.status());
			assertEquals(ImageServlet.STATUS_ADMIN_ONLY, errorMessage(seen));
			FakeResponse retry = retry(token, ServerStatus.UNWRITABLE_ID);
			assertEquals(403, retry.status());
		}
	}

	/**
	 * Probe: a folder made unwritable on disk is found by the background itself, not reported by
	 * hand, and named with its paths and photos; a share link may neither mark nor retry.
	 */
	public void testAFolderTheServerCannotWriteIsFoundAndNamed() throws Exception {
		File zoo = folder(SharingFixture.ZOO);
		File locked = folder("Locked Album");
		assertTrue(locked.mkdirs());
		int photos = 0;
		for (File file : zoo.listFiles()) {
			if (file.getName().toLowerCase().endsWith(".jpg")) {
				java.nio.file.Files.copy(file.toPath(), new File(locked, file.getName()).toPath());
				photos++;
			}
		}
		assertTrue(photos > 0);
		assertTrue(locked.setWritable(false, false));
		_readOnly.add(locked);
		try {
			servlet().startIndexing();
			assertTrue(servlet().index().awaitPass(60_000));
			assertTrue(servlet().index().pipeline().awaitIdle(60_000));

			StatusProblem unwritable = problem(status(SharingFixture.ALICE), ServerStatus.UNWRITABLE_ID);
			StatusItem item = null;
			for (StatusItem each : unwritable.getItems()) {
				if (each.getFolder().equals("Locked Album")) {
					item = each;
				}
			}
			assertNotNull("Found by the background: " + unwritable.getItems(), item);
			assertEquals(locked.getAbsolutePath(), item.getPath());
			assertEquals(photos, item.getPhotos());

			assertEquals(403, seen(zooToken(), true).status());
			assertEquals(403, retry(zooToken(), ServerStatus.UNWRITABLE_ID).status());
		} finally {
			locked.setWritable(true, false);
		}
	}

	// --- Seen. ---

	/** Seen is stored in the index of the space: shared by the administrators, kept across a restart. */
	public void testSeenIsSharedByTheAdministratorsAndSurvivesARestart() throws Exception {
		secondAdmin();
		FolderPipeline pipeline = servlet().index().pipeline();
		pipeline.reportFailure(folder(SharingFixture.ZOO), CatchUp.PREVIEWS, "a.jpg", "unreadable format", true);
		pipeline.reportFailure(folder(SharingFixture.ZOO), CatchUp.COVER, "a.jpg", "unreadable format", true);
		String id = failure(status(SharingFixture.ALICE), "unreadable format", CatchUp.PREVIEWS).getId();

		FakeResponse marked = seen(SharingFixture.ALICE, false, id);
		assertEquals(marked.body(), 200, marked.status());
		StatusInfo answer = StatusInfo.readStatusInfo(reader(body(marked)));
		assertTrue("Answered as it is then.", failure(answer, "unreadable format", CatchUp.PREVIEWS).isSeen());
		assertFalse("Only what was marked.", failure(answer, "unreadable format", CatchUp.COVER).isSeen());
		assertTrue("The other administrator sees it seen.",
			failure(status(FRANK), "unreadable format", CatchUp.PREVIEWS).isSeen());

		restartServer();
		// A restart finds the failure again.
		servlet().index().pipeline().reportFailure(folder(SharingFixture.ZOO), CatchUp.PREVIEWS, "a.jpg",
			"unreadable format", true);
		StatusProblem again = failure(status(FRANK), "unreadable format", CatchUp.PREVIEWS);
		assertEquals("The same cause keeps its id.", id, again.getId());
		assertTrue("Still seen after the restart.", again.isSeen());

		assertEquals(200, seen(FRANK, true).status());
		for (StatusProblem problem : status(SharingFixture.ALICE).getProblems()) {
			assertTrue("Mark all as seen: " + problem, problem.isSeen());
		}
	}

	/** A seen problem is new again when one of its files fails for another reason, or a new one arrives. */
	public void testAFailureThatRecursWithAnotherReasonIsNewAgain() throws Exception {
		FolderPipeline pipeline = servlet().index().pipeline();
		File zoo = folder(SharingFixture.ZOO);
		pipeline.reportFailure(zoo, CatchUp.PREVIEWS, "a.jpg", "unreadable format", true);
		readOnly(SharingFixture.ZOO, "its photo hashes");
		assertEquals(200, seen(SharingFixture.ALICE, true).status());
		assertTrue(failure(status(SharingFixture.ALICE), "unreadable format").isSeen());
		assertTrue(problem(status(SharingFixture.ALICE), ServerStatus.UNWRITABLE_ID).isSeen());

		// The file fails again, for another reason: a new cause, not seen.
		pipeline.reportFailure(zoo, CatchUp.PREVIEWS, "a.jpg", "out of memory", true);
		assertFalse(failure(status(SharingFixture.ALICE), "out of memory").isSeen());

		// A new file joins the seen cause: new again.
		pipeline.reportFailure(zoo, CatchUp.PREVIEWS, "b.jpg", "unreadable format", true);
		StatusProblem joined = failure(status(SharingFixture.ALICE), "unreadable format");
		assertEquals(2, joined.getFiles());
		assertFalse("A new file arrived.", joined.isSeen());

		// A new folder the server cannot write: new again.
		readOnly(SharingFixture.PUBLIC, "its photo hashes");
		assertFalse(problem(status(SharingFixture.ALICE), ServerStatus.UNWRITABLE_ID).isSeen());
	}

	// --- Retry. ---

	/** "Retry now" works the folders of a cause again; one fixed meanwhile leaves the list. */
	public void testRetryNowRequeuesTheFoldersOfACause() throws Exception {
		servlet().startIndexing();
		FolderPipeline pipeline = servlet().index().pipeline();
		assertTrue(servlet().index().awaitPass(60_000));
		assertTrue(pipeline.awaitIdle(60_000));
		pipeline.setQuietMillis(50);
		Probe probe = new Probe();
		pipeline.addStep(probe);
		File zoo = folder(SharingFixture.ZOO);
		pipeline.process(zoo);
		StatusProblem broken = failure(status(SharingFixture.ALICE), Probe.REASON);
		assertEquals("probe", broken.getStepName());
		assertTrue(broken.isRetryable());
		assertEquals(List.of(SharingFixture.ZOO), folders(broken));

		// Fixed elsewhere; nothing in the folder changed, and the back-off is minutes long.
		int runs = probe._runs;
		probe._broken = false;
		FakeResponse retried = retry(SharingFixture.ALICE, broken.getId());
		assertEquals(retried.body(), 200, retried.status());

		long end = System.currentTimeMillis() + 30_000;
		while (hasFailure(status(SharingFixture.ALICE), Probe.REASON)) {
			assertTrue("The retry ends the problem in time.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		assertTrue("Run again.", probe._runs > runs);

		FakeResponse gone = retry(SharingFixture.ALICE, broken.getId());
		assertEquals(404, gone.status());
		assertEquals(ImageServlet.STATUS_GONE, errorMessage(gone));
	}

	/** What is found out once at start-up cannot be retried, and says so. */
	public void testWhatCannotBeRetriedSaysSo() throws Exception {
		servlet().content().setEnabled(true);
		FakeResponse retry = retry(SharingFixture.ALICE, ServerStatus.EMBEDDER_ID);
		assertEquals("No embedder at all: nothing to ask again.", 409, retry.status());
		assertEquals(ServerStatus.NOT_RETRYABLE, errorMessage(retry));
	}

	// --- The index. ---

	/** The table of the seen marks is added to an index of the build before in place: no rebuild. */
	public void testAnOldIndexMigratesInPlace() throws Exception {
		long created;
		try (SpaceIndex store = SpaceIndex.open(_base)) {
			created = store.created();
			assertTrue(store.write(connection -> {
				try (Statement statement = connection.createStatement()) {
					statement.execute("DROP TABLE status_seen");
					statement.execute("UPDATE meta SET val = '1' WHERE name = '" + SpaceIndex.MIGRATION_KEY + "'");
				}
			}));
		}
		try (SpaceIndex store = SpaceIndex.open(_base)) {
			assertFalse("Not rebuilt.", store.isFresh());
			assertEquals(created, store.created());
			assertEquals(Integer.valueOf(SpaceIndex.MIGRATION), store.read(connection -> {
				try (PreparedStatement select = connection.prepareStatement("SELECT val FROM meta WHERE name = ?")) {
					select.setString(1, SpaceIndex.MIGRATION_KEY);
					try (ResultSet rows = select.executeQuery()) {
						return rows.next() ? Integer.valueOf(rows.getString(1)) : null;
					}
				}
			}, null));
			assertEquals("The table is there.", Integer.valueOf(0), store.read(connection -> {
				try (Statement statement = connection.createStatement();
						ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM status_seen")) {
					rows.next();
					return Integer.valueOf(rows.getInt(1));
				}
			}, null));
		}
		assertEquals("The schema is what it was.", 4, SpaceIndex.SCHEMA);
	}

	// --- Helpers. ---

	/** A step that fails in the zoo until it is mended. */
	private static final class Probe implements FolderPipeline.Step {

		static final String REASON = "cannot read the probe";

		volatile boolean _broken = true;

		volatile int _runs;

		@Override
		public String name() {
			return "probe";
		}

		@Override
		public boolean trustsRecord() {
			return false;
		}

		@Override
		public FolderPipeline.Outcome run(File folder) throws IOException {
			if (!folder.getName().equals(new File(SharingFixture.ZOO).getName())) {
				return FolderPipeline.Outcome.DONE;
			}
			_runs++;
			if (_broken) {
				throw new IOException(REASON);
			}
			return FolderPipeline.Outcome.DONE;
		}
	}

	private File folder(String path) {
		return _base.resolve(path).toFile();
	}

	private void readOnly(String path, String what) {
		File folder = folder(path);
		ReadOnlyFolders.report(folder, what);
		_readOnly.add(folder);
	}

	private void secondAdmin() throws Exception {
		UserStore users = new UserStore(_base);
		User frank = new User("frank", Roles.ADMIN, "", Instant.now().toString(), Clearances.ALL, true);
		frank.addDevice(new Device("frank's device", UserStore.hash(FRANK), Instant.now().toString()));
		users.addUser(frank);
		users.store();
		restartServer();
	}

	private StatusInfo status(String token) throws Exception {
		FakeResponse response = get("/", ImageServlet.STATUS_TYPE, token);
		assertEquals(response.body(), 200, response.status());
		return StatusInfo.readStatusInfo(reader(body(response)));
	}

	private FakeResponse seen(String token, boolean all, String... ids) throws Exception {
		StringBuilder body = new StringBuilder("{\"all\":").append(all).append(",\"ids\":[");
		for (int n = 0; n < ids.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"id\":\"").append(ids[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.STATUS_SEEN_ACTION);
		return post("/", body.toString(), token, parameters);
	}

	private FakeResponse retry(String token, String id) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.STATUS_RETRY_ACTION);
		return post("/", "{\"id\":\"" + id + "\"}", token, parameters);
	}

	private static StatusProblem problem(StatusInfo status, String id) {
		for (StatusProblem problem : status.getProblems()) {
			if (problem.getId().equals(id)) {
				return problem;
			}
		}
		fail("No problem '" + id + "' in " + status);
		return null;
	}

	private static StatusProblem failure(StatusInfo status, String reason) {
		return failure(status, reason, null);
	}

	private static StatusProblem failure(StatusInfo status, String reason, String step) {
		for (StatusProblem problem : status.getProblems()) {
			if (problem.getKind() == StatusProblemKind.FAILURE && problem.getReason().equals(reason)
				&& (step == null || step.equals(problem.getStepName()))) {
				return problem;
			}
		}
		fail("No failure '" + reason + "' in " + status);
		return null;
	}

	private static boolean hasFailure(StatusInfo status, String reason) {
		for (StatusProblem problem : status.getProblems()) {
			if (problem.getKind() == StatusProblemKind.FAILURE && problem.getReason().equals(reason)) {
				return true;
			}
		}
		return false;
	}

	private static List<String> files(StatusProblem problem) {
		List<String> result = new ArrayList<>();
		for (StatusItem item : problem.getItems()) {
			result.add(item.getFolder() + "/" + item.getName());
		}
		return result;
	}

	private static List<String> folders(StatusProblem problem) {
		List<String> result = new ArrayList<>();
		for (StatusItem item : problem.getItems()) {
			result.add(item.getFolder());
		}
		return result;
	}

	private static String body(StatusInfo status) {
		return status.toString();
	}
}
