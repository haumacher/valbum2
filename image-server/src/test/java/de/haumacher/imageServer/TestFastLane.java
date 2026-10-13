/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Crop;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Test case for the fast lane of the background work, see issue #258 (and #260): what was just
 * uploaded, what an edit of a sidecar asks for, and what a request was answered "not there yet" for
 * is made ahead of the catch-up, file by file, without the quiet period of the watch.
 *
 * <p>
 * Every test runs a started background whose quiet period (a minute) and sweep (ten minutes) are
 * far longer than any of its waits: only the fast lane can explain what is made in time. The
 * catch-up is held up by a step that takes its time on every folder named <code>Other</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFastLane extends SpaceTestCase {

	private static final String TOKEN = SharingFixture.ALICE;

	/** What the fast lane is given to do it in. */
	private static final long PROMPTLY = 10_000;

	/** How many folders the slow catch-up holds in its queue. */
	private static final int OTHERS = 10;

	/** How long the slow step takes for each of them. */
	private static final long SLOW_MILLIS = 600;

	/** The folders the slow step was run on, in order. */
	private final List<String> _slow = new CopyOnWriteArrayList<>();

	@Override
	protected void tearDown() throws Exception {
		Background.setQuietMillis(Background.QUIET_MILLIS);
		super.tearDown();
	}

	/**
	 * Two pictures uploaded one by one into an album copied in by hand a moment before (so that the
	 * upload alone cannot answer it complete): the album lists them without being prepared, and the
	 * tile of each answers its picture, long before the catch-up of the other folders is through.
	 */
	public void testAnUploadIsShownAheadOfTheCatchUp() throws Exception {
		others();
		start();
		Path album = Files.createDirectories(_base.resolve("Library/2024-05-01 Party"));
		jpeg(album.resolve("before.jpg"), Color.GRAY);

		assertEquals(HttpServletResponse.SC_OK, upload("/Library/2024-05-01 Party/", TOKEN, "up1.jpg",
			jpegBytes(Color.RED)).status());
		assertEquals(HttpServletResponse.SC_OK, upload("/Library/2024-05-01 Party/", TOKEN, "up2.jpg",
			jpegBytes(Color.BLUE)).status());

		await("The album lists the uploads, prepared, with their pictures.", () -> {
			AlbumInfo answer = albumOf("/Library/2024-05-01 Party/");
			return !answer.isPreparing() && names(answer).containsAll(List.of("before.jpg", "up1.jpg", "up2.jpg"))
				&& status("/Library/2024-05-01 Party/up1.jpg", "tn") == HttpServletResponse.SC_OK
				&& status("/Library/2024-05-01 Party/up2.jpg", "tn") == HttpServletResponse.SC_OK;
		});
		assertTrue("The catch-up of the other folders is not through: " + _slow, _slow.size() < OTHERS);
	}

	/**
	 * A request answered "not there yet" for the preview of a picture in the folder the catch-up
	 * comes to last has it made next, before the folders queued ahead of it.
	 */
	public void testADemandedPreviewIsMadeAheadOfTheQueue() throws Exception {
		others();
		Path deep = Files.createDirectories(_base.resolve("Library/1999-01-01 Deep"));
		File photo = jpeg(deep.resolve("old.jpg"), Color.GREEN);
		start();

		assertEquals("Not there yet.", HttpServletResponse.SC_ACCEPTED, status("/Library/1999-01-01 Deep/old.jpg", "tn"));
		await("Made on demand.", () -> PreviewCache.present(PreviewCache.previewFile(photo)));
		assertFalse("Before the catch-up came to its folder: " + _slow, _slow.contains("Library/1999-01-01 Deep"));
		assertTrue("Ahead of the folders queued before it: " + _slow, _slow.size() < OTHERS);
		assertEquals(HttpServletResponse.SC_OK, status("/Library/1999-01-01 Deep/old.jpg", "tn"));
	}

	/**
	 * A request answered "not there yet" makes nothing and is no request the background gives way
	 * to: an app asking again and again does not hold up the very work it waits for.
	 */
	public void testAskingAgainDoesNotHoldUpTheBackground() throws Exception {
		Path album = Files.createDirectories(_base.resolve("Library/2020-01-01 Waiting"));
		jpeg(album.resolve("w.jpg"), Color.ORANGE);
		// A long pause after every request the background gives way to.
		Background.setQuietMillis(5_000);
		pipeline().addStep(new Slow());
		start();
		// What other tests asked for in this process is over.
		await("Quiet.", () -> !Background.busy());

		long end = System.currentTimeMillis() + PROMPTLY;
		int asked = 0;
		int status = HttpServletResponse.SC_ACCEPTED;
		while (status == HttpServletResponse.SC_ACCEPTED && System.currentTimeMillis() < end) {
			status = status("/Library/2020-01-01 Waiting/w.jpg", "tn");
			asked++;
			assertFalse("A 202 is no request the background waits for.", Background.busy());
			Thread.sleep(20);
		}
		assertEquals("Made while being asked for " + asked + " times.", HttpServletResponse.SC_OK, status);
	}

	/**
	 * The fast lane writes nothing beside an original, only into <code>.vacache</code>; and the
	 * watch noticing the folder later finds everything made.
	 */
	public void testTheFastLaneWritesOnlyTheCacheAndTheWatchFindsItDone() throws Exception {
		Path album = Files.createDirectories(_base.resolve("Library/2024-06-01 Beach"));
		File before = jpeg(album.resolve("before.jpg"), Color.GRAY);
		start();
		await("Caught up.", () -> PreviewCache.present(PreviewCache.previewFile(before)));

		upload("/Library/2024-06-01 Beach/", TOKEN, "up1.jpg", jpegBytes(Color.RED));
		upload("/Library/2024-06-01 Beach/", TOKEN, "up2.jpg", jpegBytes(Color.BLUE));
		String uploaded = originals(album);

		await("Made.", () -> PreviewCache.present(PreviewCache.previewFile(album.resolve("up2.jpg").toFile())));
		awaitLane();
		assertEquals("Nothing beside an original but what the upload wrote.", uploaded, originals(album));

		// What the watch's notice ends in once its quiet period is over.
		String generated = generated(album);
		pipeline().process(album.toFile());
		assertEquals("The watch's notice makes nothing again.", generated, generated(album));
		assertEquals(uploaded, originals(album));
	}

	/**
	 * Starring an album asks for the large preview of its picture (issue #241), a crop for a cut
	 * preview: an edit of the sidecar gets both made at once, not after the quiet period, see issue
	 * #260.
	 */
	public void testAnEditGetsItsPreviewsAtOnce() throws Exception {
		Path child = Files.createDirectories(_base.resolve("Library/2024-07-01 Child"));
		File photo = jpeg(child.resolve("c.jpg"), Color.MAGENTA);
		start();
		await("Caught up.", () -> PreviewCache.present(PreviewCache.previewFile(photo)));

		// Starred, with its picture as the tile's.
		AlbumInfo album = albumOf("/Library/2024-07-01 Child/");
		album.setStarred(true);
		album.setIndexPicture(ThumbnailInfo.create().setImage("c.jpg").setScale(1.0));
		store(album);
		await("The large preview of the starred tile.",
			() -> PreviewCache.present(PreviewCache.largePreviewFile(photo, null)));

		// Its picture cut.
		album = albumOf("/Library/2024-07-01 Child/");
		album.getImageByName().get("c.jpg").setCrop(Crop.create().setX(0.25).setY(0.25).setW(0.5).setH(0.5));
		store(album);
		double[] region = Crops.renditionRegion(albumOf("/Library/2024-07-01 Child/").getImageByName().get("c.jpg"));
		assertNotNull(region);
		await("The cut preview.", () -> PreviewCache.present(PreviewCache.croppedPreviewFile(photo, region)));
		await("The large cut preview of the starred tile.",
			() -> PreviewCache.present(PreviewCache.largePreviewFile(photo, region)));
	}

	private void store(AlbumInfo album) throws Exception {
		FakeResponse stored = put("/Library/2024-07-01 Child/", json(album), TOKEN);
		assertEquals(body(stored), HttpServletResponse.SC_OK, stored.status());
	}

	/** The generated pictures of the given folder, with their stamps. */
	private static String generated(Path folder) {
		File[] files = folder.resolve(PreviewCache.CACHE_DIRECTORY_NAME).toFile()
			.listFiles((dir, name) -> name.endsWith(".jpg"));
		List<String> result = new ArrayList<>();
		for (File file : files == null ? new File[0] : files) {
			result.add(file.getName() + "@" + file.lastModified());
		}
		Collections.sort(result);
		return result.toString();
	}

	// --- Helpers. ---

	/** A step of the catch-up that takes its time on every folder named <code>Other</code>. */
	private final class Slow implements FolderPipeline.Step {
		@Override
		public String name() {
			return "slow";
		}

		@Override
		public boolean trustsRecord() {
			return false;
		}

		@Override
		public FolderPipeline.Outcome run(File folder) {
			String path = _base.relativize(folder.toPath()).toString().replace(File.separatorChar, '/');
			if (folder.getName().contains("Other")) {
				try {
					Thread.sleep(SLOW_MILLIS);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return FolderPipeline.Outcome.WAITING;
				}
			}
			_slow.add(path);
			return FolderPipeline.Outcome.DONE;
		}
	}

	/** Folders the catch-up takes its time with, newer than every album of the tests. */
	private void others() throws Exception {
		for (int n = 0; n < OTHERS; n++) {
			Path folder = Files.createDirectories(_base.resolve(String.format("Library/2025-%02d-01 Other", n + 1)));
			jpeg(folder.resolve("o.jpg"), new Color(n * 20, 100, 200 - n * 10));
		}
		pipeline().addStep(new Slow());
	}

	/** Starts the background as a server does, with a quiet period and a sweep that never come. */
	private void start() throws Exception {
		pipeline().setQuietMillis(60_000);
		pipeline().setSweepMillis(600_000);
		servlet().startIndexing();
		assertTrue(servlet().index().awaitPass(60_000));
	}

	private FolderPipeline pipeline() throws Exception {
		return servlet().index().pipeline();
	}

	/** Waits until the fast lane is empty and the piece it worked on last is done. */
	private void awaitLane() throws Exception {
		await("The fast lane is through.", () -> {
			try {
				return pipeline().urgentWork() == 0;
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		});
		CountDownLatch behind = new CountDownLatch(1);
		pipeline().execute(behind::countDown);
		assertTrue(behind.await(60, TimeUnit.SECONDS));
	}

	private static void await(String what, BooleanSupplier condition) throws Exception {
		long end = System.currentTimeMillis() + PROMPTLY;
		while (!condition.getAsBoolean()) {
			assertTrue(what, System.currentTimeMillis() < end);
			Thread.sleep(20);
		}
	}

	private int status(String path, String type) {
		try {
			return get(path, type, TOKEN).status();
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private AlbumInfo albumOf(String path) {
		try {
			AlbumInfo result = album(get(path, "json", TOKEN));
			de.haumacher.imageServer.shared.util.UpdateTransient.updateTransient(result);
			return result;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
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

	/** A fingerprint of everything in the given folder but its <code>.vacache</code>. */
	private static String originals(Path folder) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(folder)) {
			walk.filter(Files::isRegularFile)
				.filter(p -> !folder.relativize(p).startsWith(PreviewCache.CACHE_DIRECTORY_NAME))
				.forEach(files::add);
		}
		files.sort(Comparator.comparing(Path::toString));
		for (Path file : files) {
			digest.update(folder.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
			digest.update(Long.toString(file.toFile().lastModified()).getBytes(StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		return files.stream().map(p -> folder.relativize(p).toString()).toList() + ":"
			+ java.util.HexFormat.of().formatHex(digest.digest());
	}

	private static File jpeg(Path file, Color color) throws Exception {
		Files.write(file, jpegBytes(color));
		return file.toFile();
	}

	private static byte[] jpegBytes(Color color) throws Exception {
		BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, "jpg", out));
		return out.toByteArray();
	}

	private static String json(AlbumInfo album) throws Exception {
		java.io.StringWriter buffer = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json = new de.haumacher.msgbuf.json.JsonWriter(
			new de.haumacher.msgbuf.server.io.WriterAdapter(buffer))) {
			album.writeTo(json);
		}
		return buffer.toString();
	}
}
