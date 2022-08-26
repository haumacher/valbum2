/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.util;

import java.util.Arrays;
import java.util.stream.Collectors;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.util.AlbumUtil;
import junit.framework.TestCase;

/**
 * Test case for {@link AlbumUtil}.
 */
@SuppressWarnings("javadoc")
public class TestAlbumUtil extends TestCase {
	
	public void testInsertSorted() {
		AlbumInfo album = AlbumInfo.create()
			.addPart(Heading.create().setText("H1"))
			.addPart(ImagePart.create().setName("A").setDate(10))
			.addPart(Heading.create().setText("H2"))
			.addPart(ImagePart.create().setName("B").setDate(20))
			.addPart(Heading.create().setText("H3"))
			.addPart(ImagePart.create().setName("C").setDate(30));
		
		AlbumUtil.insertSorted(album, Arrays.asList(
			ImagePart.create().setName("A1").setDate(10),
			ImagePart.create().setName("A2").setDate(12),
			ImagePart.create().setName("B1").setDate(22),
			ImagePart.create().setName("C1").setDate(31)
		));
		
		assertEquals(Arrays.asList("H1", "A", "A1", "A2", "H2", "B", "B1", "H3", "C", "C1"), 
			album.getParts().stream().map(this::toName).collect(Collectors.toList()));
	}
	
	String toName(AlbumPart part) {
		return part.visit(new AlbumPart.Visitor<String, Void, RuntimeException>() {
			@Override
			public String visit(ImageGroup self, Void arg) throws RuntimeException {
				return self.getImages().get(self.getRepresentative()).getName();
			}

			@Override
			public String visit(ImagePart self, Void arg) throws RuntimeException {
				return self.getName();
			}

			@Override
			public String visit(Heading self, Void arg) throws RuntimeException {
				return self.getText();
			}
		}, null);
	}

}
