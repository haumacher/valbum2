/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import junit.framework.TestCase;

/**
 * Test case for {@link PrivacyFilter#bestImage(Iterable)}, the one rule a cover is chosen by where
 * the server chooses one (issue #153).
 */
@SuppressWarnings("javadoc")
public class TestBestImage extends TestCase {

	public void testNothingToChooseFrom() {
		assertNull(PrivacyFilter.bestImage(Collections.emptyList()));
		assertNull(PrivacyFilter.bestImage(Collections.singletonList(Heading.create().setText("Morning"))));
		assertNull("A group without members shows nothing.",
			PrivacyFilter.bestImage(Collections.singletonList(ImageGroup.create())));
	}

	public void testTheHighestRatingWins() {
		List<AlbumPart> parts = Arrays.asList(image("a", 0), image("b", 1), image("c", -1));
		assertEquals("b", PrivacyFilter.bestImage(parts).getName());
	}

	public void testTheEarlierPartBreaksATie() {
		List<AlbumPart> parts = Arrays.asList(image("a", 1), Heading.create().setText("h"), image("b", 2),
			image("c", 2));
		assertEquals("b", PrivacyFilter.bestImage(parts).getName());
	}

	public void testWithoutRatingsItIsTheFirstImage() {
		List<AlbumPart> parts = Arrays.asList(Heading.create().setText("h"), image("a", 0), image("b", 0));
		assertEquals("a", PrivacyFilter.bestImage(parts).getName());
	}

	public void testATrashedImageNeverStands() {
		assertEquals("b", PrivacyFilter.bestImage(Arrays.asList(image("a", -2), image("b", -1))).getName());
		assertNull("Trash is shown nowhere (#152), so it stands for no album: none rather than a trashed one.",
			PrivacyFilter.bestImage(Arrays.asList(image("a", -2), image("b", -2))));
	}

	public void testAGroupCountsAsItsRepresentative() {
		ImageGroup group = ImageGroup.create();
		group.addImage(image("g1", 0));
		group.addImage(image("g2", 2));
		group.setRepresentative(0);
		assertEquals("The representative's rating, not the best member's.", "x",
			PrivacyFilter.bestImage(Arrays.asList(group, image("x", 1))).getName());

		group.setRepresentative(1);
		assertEquals("g2", PrivacyFilter.bestImage(Arrays.asList(group, image("x", 1))).getName());

		group.setRepresentative(7);
		assertEquals("An out-of-range representative reads as the first member.", "g1",
			PrivacyFilter.bestImage(Collections.singletonList(group)).getName());
	}

	private static ImagePart image(String name, int rating) {
		return ImagePart.create().setName(name).setRating(rating);
	}
}
