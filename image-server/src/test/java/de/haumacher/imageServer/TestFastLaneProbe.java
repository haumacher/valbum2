/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/**
 * Probe of issues #258 and #260 together: photographs uploaded into a new, empty album give the
 * tile of that album in its year's listing a picture whose preview is served, promptly, while the
 * quiet period and the sweep never come.
 */
@SuppressWarnings("javadoc")
public class TestFastLaneProbe extends SpaceTestCase {

	private static final String TOKEN = SharingFixture.ALICE;

	@Override
	protected void tearDown() throws Exception {
		Background.setQuietMillis(Background.QUIET_MILLIS);
		super.tearDown();
	}

	public void testAnUploadIntoANewAlbumGivesItsTileAPicture() throws Exception {
		// The year is known before; the album in it is new.
		Files.createDirectories(_base.resolve("Library/2026/2026-01-01 Old"));
		Files.write(_base.resolve("Library/2026/2026-01-01 Old/o.jpg"), jpeg(Color.GRAY));
		servlet().index().pipeline().setQuietMillis(60_000);
		servlet().index().pipeline().setSweepMillis(600_000);
		servlet().startIndexing();
		assertTrue(servlet().index().awaitPass(60_000));
		Files.createDirectories(_base.resolve("Library/2026/2026-10-13 New"));

		assertEquals(HttpServletResponse.SC_OK,
			upload("/Library/2026/2026-10-13 New/", TOKEN, "a.jpg", jpeg(Color.ORANGE)).status());
		assertEquals(HttpServletResponse.SC_OK,
			upload("/Library/2026/2026-10-13 New/", TOKEN, "b.jpg", jpeg(Color.CYAN)).status());

		long end = System.currentTimeMillis() + 10_000;
		while (true) {
			FolderInfo tile = tile();
			if (tile != null && tile.getIndexPicture() != null && get(
				"/Library/2026/2026-10-13 New/" + tile.getIndexPicture().getImage(), "tn", TOKEN)
					.status() == HttpServletResponse.SC_OK) {
				break;
			}
			assertTrue("The new album's tile has no served picture: " + tile, System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
	}

	private FolderInfo tile() throws Exception {
		Resource year = Resource.readResource(
			new JsonReader(new ReaderAdapter(new StringReader(get("/Library/2026/", "json", TOKEN).body()))));
		for (FolderInfo folder : ((ListingInfo) year).getFolders()) {
			if (folder.getName().equals("2026-10-13 New")) {
				return folder;
			}
		}
		return null;
	}

	private static byte[] jpeg(Color color) throws Exception {
		BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 64, 48);
		g.dispose();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", buffer);
		return buffer.toByteArray();
	}
}
