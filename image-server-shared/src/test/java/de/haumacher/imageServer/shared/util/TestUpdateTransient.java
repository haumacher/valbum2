/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.util;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import junit.framework.TestCase;

/**
 * Test case for {@link UpdateTransient}.
 */
@SuppressWarnings("javadoc")
public class TestUpdateTransient extends TestCase {

	public void testUpdate() {
		AlbumInfo album = AlbumInfo.create()
			.addPart(ImageGroup.create().setRepresentative(1)
				.addImage(ImagePart.create().setName("A1"))
				.addImage(ImagePart.create().setName("A2"))
				.addImage(ImagePart.create().setName("A3"))
			)
			.addPart(ImagePart.create().setName("B"));
		
		UpdateTransient.updateTransient(album);
		
		assertNotNull(album.getImageByName().get("A1"));
		assertNotNull(album.getImageByName().get("A2"));
		assertNotNull(album.getImageByName().get("A3"));
		assertNotNull(album.getImageByName().get("B"));
	}
	
}
