/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.StringReader;
import junit.framework.TestCase;

/**
 * Probe for the camera label of #78: edges of the rule, the derived-field clearing of #48/#53 and a
 * grouped image on the wire.
 */
@SuppressWarnings("javadoc")
public class TestCameraProbe extends TestCase {

	public void testLabelEdges() {
		assertEquals("Apple iPhone 15 Pro", ImageData.cameraLabel("  Apple ", "iPhone 15 Pro"));
		assertEquals("CANON EOS 5D", ImageData.cameraLabel("canon", "CANON EOS 5D"));
		assertEquals("Only the model where both name the same thing.", "canon", ImageData.cameraLabel("Canon", "canon"));
		assertEquals("Canon PowerShot Canon", ImageData.cameraLabel("Canon", "PowerShot Canon"));
		assertEquals("", ImageData.cameraLabel("   ", "\t"));
		assertEquals("Leica Q2", ImageData.cameraLabel("Leica", "Leica Q2".replace(' ', ' ')));
		// Two different phones of one make stay two cameras.
		assertFalse(ImageData.cameraLabel("SAMSUNG", "SM-G991B").equals(ImageData.cameraLabel("SAMSUNG", "SM-S908B")));
	}

	public void testClearDerivedKeepsTheCamera() {
		ImagePart part = ImagePart.create().setName("a.jpg").setWidth(4).setHeight(3).setDate(5L)
			.setCamera("Canon EOS 5D").setContributor("user:haui").setContributorLabel("Haui");
		AlbumInfo album = AlbumInfo.create().setTitle("T");
		album.addPart(part);
		album.setEffectiveDate(123L);
		AlbumDate.clearDerived(album);
		assertEquals("The camera is stored, not derived.", "Canon EOS 5D", part.getCamera());
		assertEquals("The contributor is derived and cleared.", "", part.getContributor());
		assertEquals(0L, album.getEffectiveDate());
	}

	public void testGroupedImageCarriesTheCameraOnTheWire() throws Exception {
		ImagePart a = ImagePart.create().setName("a.jpg").setWidth(4).setHeight(3).setCamera("Canon EOS 5D");
		ImagePart b = ImagePart.create().setName("b.jpg").setWidth(4).setHeight(3).setCamera("Apple iPhone 15 Pro");
		ImageGroup group = ImageGroup.create().setRepresentative(0);
		group.addImage(a);
		group.addImage(b);
		AlbumInfo album = AlbumInfo.create().setTitle("T");
		album.addPart(group);

		String json = album.toString();
		assertTrue(json, json.contains("\"camera\":\"Canon EOS 5D\""));
		Resource copy = Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(json))));
		ImageGroup readBack = (ImageGroup) ((AlbumInfo) copy).getParts().get(0);
		assertEquals("Apple iPhone 15 Pro", readBack.getImages().get(1).getCamera());
	}

}
