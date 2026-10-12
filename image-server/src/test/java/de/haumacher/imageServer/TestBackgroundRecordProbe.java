/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.cache.AnalysisRecord;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Probe for issue #253, composed with the move (#47) the user did when the report began: photos
 * the background recorded in one folder - never written to an index.json - are moved into an album
 * that holds a broken recording, on a server whose requests never analyse.
 */
@SuppressWarnings("javadoc")
public class TestBackgroundRecordProbe extends SpaceTestCase {

	private static final String TOKEN = SharingFixture.ALICE;

	@Override
	protected ImageServlet servlet() throws Exception {
		ImageServlet result = super.servlet();
		result.cache().setBackground(true);
		return result;
	}

	public void testMovedPartsAreThereAtOnceWithTheirSize() throws Exception {
		Path a = Files.createDirectories(_base.resolve("Library/A"));
		Path b = Files.createDirectories(_base.resolve("Library/B"));
		Files.write(a.resolve("x.jpg"), jpeg(6, 4));
		// Cut off as well, but another recording: the very same bytes in B would be a duplicate,
		// which a move sets aside (#82), see testAnIdenticalBrokenFileIsSetAsideAsADuplicate().
		Files.write(a.resolve("cut.mp4"), otherCut());
		Files.write(b.resolve("y.jpg"), jpeg(4, 6));
		Files.write(b.resolve("20250903_185939.mp4"), LargeMp4.truncated());
		servlet();
		record("Library/A");
		record("Library/B");
		assertFalse(Files.exists(a.resolve("index.json")));

		long before = ImageData.analyses();
		FakeResponse moved = move("/Library/A/", "Library/B", TOKEN, "x.jpg", "cut.mp4");
		assertEquals(body(moved), HttpServletResponse.SC_OK, moved.status());

		// No background pass in between: the move itself tells the albums.
		AlbumInfo target = album(get("/Library/B/", "json", TOKEN));
		de.haumacher.imageServer.shared.util.UpdateTransient.updateTransient(target);
		ImagePart x = target.getImageByName().get("x.jpg");
		assertNotNull("Moved in, there at once: " + target.getImageByName().keySet(), x);
		assertEquals(6, x.getWidth());
		assertEquals(4, x.getHeight());
		ImagePart cut = target.getImageByName().get("cut.mp4");
		assertNotNull("The broken one travels too.", cut);
		assertFalse(cut.getBroken().isEmpty());
		assertFalse(target.getImageByName().get("20250903_185939.mp4").getBroken().isEmpty());

		AlbumInfo source = album(get("/Library/A/", "json", TOKEN));
		de.haumacher.imageServer.shared.util.UpdateTransient.updateTransient(source);
		assertTrue("Gone from where it was: " + source.getImageByName().keySet(),
			source.getImageByName().isEmpty());

		// The background's next pass over both folders opens nothing that was already known.
		record("Library/A");
		record("Library/B");
		assertEquals("A move never makes the background analyse again.", before, ImageData.analyses());

		// And a restarted server answers the same.
		restartServer();
		AlbumInfo again = album(get("/Library/B/", "json", TOKEN));
		de.haumacher.imageServer.shared.util.UpdateTransient.updateTransient(again);
		assertEquals(6, again.getImageByName().get("x.jpg").getWidth());
		assertFalse(again.getImageByName().get("cut.mp4").getBroken().isEmpty());
	}

	/**
	 * A broken file whose very bytes the target already holds is a duplicate like any other: set
	 * aside, the target's own broken part standing for it, and nothing analysed again.
	 */
	public void testAnIdenticalBrokenFileIsSetAsideAsADuplicate() throws Exception {
		Path a = Files.createDirectories(_base.resolve("Library/A"));
		Path b = Files.createDirectories(_base.resolve("Library/B"));
		Files.write(a.resolve("cut.mp4"), LargeMp4.truncated());
		Files.write(b.resolve("20250903_185939.mp4"), LargeMp4.truncated());
		servlet();
		record("Library/A");
		record("Library/B");

		long before = ImageData.analyses();
		FakeResponse moved = move("/Library/A/", "Library/B", TOKEN, "cut.mp4");
		assertEquals(body(moved), HttpServletResponse.SC_OK, moved.status());
		assertTrue(body(moved), body(moved).contains("20250903_185939.mp4"));
		assertFalse(Files.exists(a.resolve("cut.mp4")));
		assertFalse(Files.exists(b.resolve("cut.mp4")));

		AlbumInfo target = album(get("/Library/B/", "json", TOKEN));
		de.haumacher.imageServer.shared.util.UpdateTransient.updateTransient(target);
		assertEquals(java.util.Collections.singleton("20250903_185939.mp4"), target.getImageByName().keySet());
		assertFalse(target.getImageByName().get("20250903_185939.mp4").getBroken().isEmpty());
		record("Library/A");
		record("Library/B");
		assertEquals(before, ImageData.analyses());
	}

	/**
	 * A folder moved as a whole takes its record along (it lies in the folder's cache directory):
	 * its broken file stays a broken part, and nothing is analysed again.
	 */
	public void testAMovedFolderKeepsItsRecord() throws Exception {
		Path a = Files.createDirectories(_base.resolve("Library/A"));
		Files.createDirectories(_base.resolve("Library/C"));
		Files.write(a.resolve("x.jpg"), jpeg(6, 4));
		Files.write(a.resolve("cut.mp4"), otherCut());
		servlet();
		record("Library/A");

		long before = ImageData.analyses();
		FakeResponse moved = move("/Library/", "Library/C", TOKEN, "A");
		assertEquals(body(moved), HttpServletResponse.SC_OK, moved.status());

		AlbumInfo album = album(get("/Library/C/A/", "json", TOKEN));
		de.haumacher.imageServer.shared.util.UpdateTransient.updateTransient(album);
		assertFalse("Recorded already: " + album, album.isPreparing());
		assertEquals(6, album.getImageByName().get("x.jpg").getWidth());
		assertFalse(album.getImageByName().get("cut.mp4").getBroken().isEmpty());
		record("Library/C/A");
		assertEquals(before, ImageData.analyses());
	}

	/** A recording cut off before its index, other bytes than {@link LargeMp4#truncated()}. */
	private static byte[] otherCut() {
		byte[] cut = LargeMp4.truncated();
		byte[] result = java.util.Arrays.copyOf(cut, cut.length + 8);
		System.arraycopy(new byte[] { 0, 0, 0, 8, 'f', 'r', 'e', 'e' }, 0, result, cut.length, 8);
		return result;
	}

	private void record(String path) throws Exception {
		servlet().index().pipeline().process(_base.resolve(path).toFile(), FolderPipeline.Stage.RECORD);
		assertNotNull(AnalysisRecord.read(_base.resolve(path).toFile()));
	}

	private static byte[] jpeg(int width, int height) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR), "jpg", out);
		return out.toByteArray();
	}
}
