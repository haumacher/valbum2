/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;

/**
 * What the face index hands to the detector and what it does not, see issue #124.
 *
 * <p>
 * With a detector of the test's own, so that these questions are answered on every machine, and
 * answered about the index rather than about OpenCV: which files are offered at all, what happens
 * to one that cannot be read, and whether an album that is not done yet says so.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceIndexing extends FacesTestCase {

	/** A detector that writes down what it was handed and finds one face in the middle. */
	private static final class Counting implements FaceDetection.Detector {

		final List<String> _seen = new CopyOnWriteArrayList<>();

		/** The names this detector refuses, as a broken file would. */
		final List<String> _broken = new CopyOnWriteArrayList<>();

		/** Where the one face is, in the pixels of the preview this detector claims. */
		double[] _box = { 300, 200, 200, 200 };

		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			_seen.add(preview.getName());
			for (String broken : _broken) {
				if (preview.getName().contains(broken)) {
					throw new IOException("Cannot read this.");
				}
			}
			List<FaceDetection.Detected> faces = new ArrayList<>();
			// A quarter of the picture, in the middle; the preview is 800 by 600 as far as this
			// detector is concerned, which is all the index needs to normalise with.
			faces.add(new FaceDetection.Detected(_box[0], _box[1], _box[2], _box[3], 0.99, embedding()));
			return new FaceDetection.Result(800, 600, faces);
		}

		private static float[] embedding() {
			float[] result = new float[128];
			result[0] = 1;
			return result;
		}
	}

	private Counting _detector;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_detector = new Counting();
		FaceDetection.setDetector(_detector);
	}

	/** A video is never handed to the detector, not even as its poster frame. */
	public void testAVideoIsNeverLookedAt() throws Exception {
		createSpace(A_ONE, A_TWO, VIDEO);

		index();

		assertEquals("Only the two photographs.", 2, _detector._seen.size());
		for (String seen : _detector._seen) {
			assertFalse("A video was handed to the detector: " + seen, seen.contains(VIDEO));
		}
		assertEquals("And nothing is recorded for it.", 2, new FaceCache(album()).hashes().size());
	}

	/** Nothing is handed over twice: a second pass over an unchanged album detects nothing. */
	public void testNothingIsLookedAtTwice() throws Exception {
		createSpace(A_ONE, B);

		index();
		assertEquals(2, _detector._seen.size());

		index();
		assertEquals("A second pass has nothing to do.", 2, _detector._seen.size());
	}

	/** A photograph the detector refuses is remembered, and never offered again. */
	public void testABrokenPhotographIsRememberedOnce() throws Exception {
		createSpace(A_ONE, B);
		_detector._broken.add(B);

		index();
		assertEquals(2, _detector._seen.size());
		assertEquals("The one that worked is recorded.", 1, new FaceCache(album()).hashes().size());

		index();
		assertEquals("The broken one is not tried again.", 2, _detector._seen.size());

		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		assertFalse("A photograph that will never work is not 'pending'.", album.isFacesPending());
	}

	/** An album nobody has looked at yet says so, and queues itself. */
	public void testAnUnindexedAlbumIsPending() throws Exception {
		createSpace(A_ONE, B);

		AlbumInfo first = album("/" + ALBUM + "/", _adminToken);
		assertTrue("Nothing is known yet.", first.isFacesPending());
		for (ImagePart image : images(first)) {
			assertEquals(0, image.getFaces().size());
		}

		assertTrue("Reading the album queued the work.", _servlet.faces().awaitQueue(60000));

		AlbumInfo second = album("/" + ALBUM + "/", _adminToken);
		assertFalse("And now it is done.", second.isFacesPending());
		assertEquals(1, image(second, A_ONE).getFaces().size());
	}

	/**
	 * A box is normalised against the picture, not against the padding around it in the preview.
	 *
	 * <p>
	 * {@link PreviewCache} never scales a picture <em>up</em>: a picture smaller than the preview
	 * box is drawn centred into a canvas of the preview size, with a margin all round. The picture
	 * here is 320 by 476 and this detector reports a preview of 800 by 600, so the picture covers
	 * 320 by 476 of it, offset by 240 and 62 — and that, not the canvas, is what a box is a fraction
	 * of.
	 * </p>
	 */
	public void testTheBoxIsNormalisedAgainstThePicture() throws Exception {
		createSpace(A_ONE);

		index();

		BufferedImage picture = ImageIO.read(new File(album(), A_ONE));
		double width = picture.getWidth();
		double height = picture.getHeight();
		assertEquals("The fixture is smaller than the preview box, which is the point here.",
			320.0, width, 0.0);
		double left = (800 - width) / 2;
		double top = (600 - height) / 2;

		FaceCache cache = new FaceCache(album());
		String hash = Collections.min(cache.hashes());
		FaceCache.Face face = cache.facesOf(hash).get(0);
		// The detector said "300..500 across, 200..400 down" of a preview of 800 by 600.
		assertEquals((300 - left) / width, face.getX(), 1e-6);
		assertEquals((200 - top) / height, face.getY(), 1e-6);
		assertEquals(200 / width, face.getW(), 1e-6);
		assertEquals(200 / height, face.getH(), 1e-6);
	}

	/** A face that reaches over the edge of the picture is kept as far as the picture goes. */
	public void testABoxNeverLeavesThePicture() throws Exception {
		createSpace(A_ONE);
		// The picture covers 240..560 across of the 800 this detector claims; this face starts left
		// of it and ends inside.
		_detector._box = new double[] { 100, 50, 300, 400 };

		index();

		FaceCache cache = new FaceCache(album());
		FaceCache.Face face = cache.facesOf(Collections.min(cache.hashes())).get(0);
		assertEquals("Nothing of the margin is part of the picture.", 0.0, face.getX(), 1e-9);
		assertTrue("And the box stays inside it.", face.getX() + face.getW() <= 1.0000001);
		assertTrue(face.getY() >= 0 && face.getY() + face.getH() <= 1.0000001);
	}

	/** A face entirely in the margin around the picture is no face of the picture. */
	public void testABoxOutsideThePictureIsDropped() throws Exception {
		createSpace(A_ONE);
		// The picture starts at 240; this one ends at 200.
		_detector._box = new double[] { 0, 0, 200, 40 };

		index();

		FaceCache cache = new FaceCache(album());
		assertEquals("Nothing of the picture was found.", 0,
			cache.facesOf(Collections.min(cache.hashes())).size());
	}
}
