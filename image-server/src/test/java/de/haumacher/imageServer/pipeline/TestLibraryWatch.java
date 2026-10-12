/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for {@link LibraryWatch}, see issue #253: the background of a space watches the whole
 * library, not the folders a request happened to open, and degrades to the sweep where the system
 * grants no more watches.
 */
@SuppressWarnings("javadoc")
public class TestLibraryWatch extends TestCase {

	/** A step that remembers which folders it ran on. */
	private static final class Seen implements FolderPipeline.Step {

		final List<String> _folders = new CopyOnWriteArrayList<>();

		@Override
		public String name() {
			return "seen";
		}

		@Override
		public boolean trustsRecord() {
			return false;
		}

		@Override
		public FolderPipeline.Outcome run(File folder) throws IOException {
			_folders.add(folder.getName());
			return FolderPipeline.Outcome.DONE;
		}

		int count(String name) {
			int result = 0;
			for (String folder : _folders) {
				if (folder.equals(name)) {
					result++;
				}
			}
			return result;
		}
	}

	private Path _root;

	private File _album;

	private FolderPipeline _pipeline;

	private Seen _seen;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Background.setQuietMillis(0);
		_root = Files.createTempDirectory("valbum-library-watch");
		_album = new File(_root.toFile(), "2020/Trip");
		assertTrue(_album.mkdirs());
		photo(new File(_album, "a.jpg"));
		// Neither the server's own folders nor a cache directory are part of the library.
		assertTrue(new File(_root.toFile(), ".valbum").mkdirs());
		assertTrue(new File(_album, ".vacache").mkdirs());
		_pipeline = new FolderPipeline(_root, "watch-test");
		_pipeline.setQuietMillis(50);
		_seen = new Seen();
		_pipeline.addStep(_seen);
	}

	@Override
	protected void tearDown() throws Exception {
		_pipeline.shutdown();
		Background.setQuietMillis(Background.QUIET_MILLIS);
		try (Stream<Path> files = Files.walk(_root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	/** Every folder of the library is watched from the start, without any request. */
	public void testEveryFolderOfTheLibraryIsWatched() throws Exception {
		_pipeline.start();
		LibraryWatch watch = _pipeline.watch();
		assertTrue("The root, 2020 and 2020/Trip: " + watch.watchCount(), waitFor(() -> watch.watchCount() == 3));
		Thread.sleep(200);
		assertEquals("Nothing that is no part of the library.", 3, watch.watchCount());
		assertFalse(watch.isExhausted());
	}

	/** A folder copied in is noticed with everything in it, and watched from then on. */
	public void testANewFolderIsNoticedWithEverythingInIt() throws Exception {
		_pipeline.start();
		LibraryWatch watch = _pipeline.watch();
		assertTrue(waitFor(() -> watch.watchCount() == 3));

		File inner = new File(_root.toFile(), "Copied/Inner");
		assertTrue(inner.mkdirs());
		photo(new File(inner, "x.jpg"));

		assertTrue("Noticed without a request: " + _seen._folders,
			waitFor(() -> _seen.count("Copied") >= 1 && _seen.count("Inner") >= 1));
		assertTrue("Watched now: " + watch.watchCount(), waitFor(() -> watch.watchCount() == 5));
	}

	/** A photograph added, written over in place, or deleted makes its folder's work run again. */
	public void testAPhotographAddedReplacedOrDeletedIsNoticed() throws Exception {
		_pipeline.start();
		LibraryWatch watch = _pipeline.watch();
		assertTrue(waitFor(() -> watch.watchCount() == 3));

		File added = new File(_album, "b.jpg");
		photo(added);
		assertTrue("Added: " + _seen._folders, waitFor(() -> _seen.count("Trip") >= 1));
		assertTrue(_pipeline.awaitIdle(10_000));
		int afterAdd = _seen.count("Trip");

		Files.write(added.toPath(), Files.readAllBytes(new File(_album, "a.jpg").toPath()));
		assertTrue("Written over in place: " + _seen._folders, waitFor(() -> _seen.count("Trip") > afterAdd));
		assertTrue(_pipeline.awaitIdle(10_000));
		int afterReplace = _seen.count("Trip");

		assertTrue(added.delete());
		assertTrue("Deleted: " + _seen._folders, waitFor(() -> _seen.count("Trip") > afterReplace));
	}

	/** A permission fixed with <code>chmod</code> is a change (issue #251). */
	public void testAChangeOfPermissionsIsAChange() throws Exception {
		_pipeline.start();
		LibraryWatch watch = _pipeline.watch();
		assertTrue(waitFor(() -> watch.watchCount() == 3));
		File photo = new File(_album, "a.jpg");
		assertTrue(photo.setReadable(false, false) && photo.setReadable(true, false));
		assertTrue("A change of permissions is reported: " + _seen._folders,
			waitFor(() -> _seen.count("Trip") >= 1));
	}

	/**
	 * Where the system grants no more watches, nothing fails: the folders left unwatched are found
	 * by the sweep of modification times.
	 */
	public void testBeyondTheLimitTheSweepCoversTheRest() throws Exception {
		_pipeline.setMaxWatches(1);
		_pipeline.setSweepMillis(50);
		_pipeline.start();
		LibraryWatch watch = _pipeline.watch();
		assertTrue(waitFor(watch::isExhausted));
		assertEquals("The root alone.", 1, watch.watchCount());

		// Let the sweep take its baseline, then add a photograph to an unwatched folder.
		Thread.sleep(300);
		photo(new File(_album, "late.jpg"));
		assertTrue(_album.setLastModified(System.currentTimeMillis() + 5000));
		assertTrue("Found by the sweep: " + _seen._folders, waitFor(() -> _seen.count("Trip") >= 1));
	}

	/** The watches are given back with the shutdown. */
	public void testNothingIsWatchedAfterTheShutdown() throws Exception {
		_pipeline.start();
		LibraryWatch watch = _pipeline.watch();
		assertTrue(waitFor(() -> watch.watchCount() == 3));
		_pipeline.shutdown();
		assertNull(_pipeline.watch());
		int before = _seen._folders.size();
		photo(new File(_album, "after.jpg"));
		Thread.sleep(500);
		assertEquals(before, _seen._folders.size());
	}

	// --- Helpers. ---

	private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
		long end = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < end) {
			if (condition.getAsBoolean()) {
				return true;
			}
			Thread.sleep(20);
		}
		return condition.getAsBoolean();
	}

	private static void photo(File file) throws IOException {
		assertTrue(ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", file));
	}
}
