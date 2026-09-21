/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.PeopleStore;
import de.haumacher.imageServer.faces.Recognition;
import de.haumacher.imageServer.faces.Recognition.Match;
import de.haumacher.imageServer.faces.Recognition.Prototype;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The arithmetic of the recognition of issue #127, with numbers instead of photographs.
 *
 * <p>
 * Nothing here needs a detector, a model or a picture: what is tested is the rule — the threshold,
 * the margin to the second-best person, what a merge does to both — and how long a thousand
 * confirmed faces take to be compared against an album of two hundred.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestRecognition extends TestCase {

	/** How long an embedding of the bundled recogniser is. */
	private static final int DIMENSIONS = 128;

	private Path _base;

	@Override
	protected void tearDown() throws Exception {
		System.clearProperty(Recognition.THRESHOLD_PROPERTY);
		System.clearProperty(Recognition.MARGIN_PROPERTY);
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
			_base = null;
		}
		super.tearDown();
	}

	/** Nobody confirmed anywhere: nothing is ever suggested, and it costs nothing to ask. */
	public void testNobodyIsSuggestedWhereNobodyIsKnown() {
		Recognition recognition = new Recognition();
		assertTrue(recognition.isEmpty());
		assertEquals(0, recognition.size());
		Match match = recognition.matcher(null);
		assertTrue(match.isEmpty());
		assertEquals("", match.personOf(axis(0)));
	}

	/** The nearest prototype decides, and only from the threshold upwards. */
	public void testTheThreshold() {
		Recognition recognition = new Recognition();
		recognition.put(new File("/x"), Collections.singletonList(new Prototype("anna", axis(0))));
		Match match = recognition.matcher(null);

		assertEquals("The same face is the same person.", "anna", match.personOf(axis(0)));
		assertEquals("Just above the threshold is a suggestion.", "anna",
			match.personOf(similar(0.40)));
		assertEquals("Just below it is none.", "", match.personOf(similar(0.30)));
		assertEquals("And nothing at all is nobody.", "", match.personOf(axis(1)));
		assertEquals("", match.personOf(new float[0]));
		assertEquals("", match.personOf(null));
	}

	/** The threshold is a property of the server, not of the code, see issue #127. */
	public void testTheThresholdIsASystemProperty() {
		assertEquals(0.363, Recognition.DEFAULT_THRESHOLD, 1e-9);
		assertEquals(Recognition.DEFAULT_THRESHOLD, Recognition.threshold(), 1e-9);
		System.setProperty(Recognition.THRESHOLD_PROPERTY, "0.9");
		assertEquals(0.9, Recognition.threshold(), 1e-9);

		Recognition recognition = new Recognition();
		recognition.put(new File("/x"), Collections.singletonList(new Prototype("anna", axis(0))));
		assertEquals("A face the default would suggest is now too far away.", "",
			recognition.matcher(null).personOf(similar(0.5)));

		System.setProperty(Recognition.THRESHOLD_PROPERTY, "not a number");
		assertEquals("An unreadable value is no setting.", Recognition.DEFAULT_THRESHOLD,
			Recognition.threshold(), 1e-9);
	}

	/** Two people the numbers cannot tell apart are no suggestion at all. */
	public void testTheMarginToTheSecondBestPerson() {
		assertEquals(0.05, Recognition.DEFAULT_MARGIN, 1e-9);
		Recognition recognition = new Recognition();
		// Anna at 0.80 of the query, Annie at 0.78: both far above the threshold, and a hundredth
		// apart.
		recognition.put(new File("/x"), List.of(
			new Prototype("anna", similar(0.80)),
			new Prototype("annie", similar(0.78))));
		assertEquals("A hundredth is no lead.", "", recognition.matcher(null).personOf(axis(0)));

		Recognition clear = new Recognition();
		clear.put(new File("/x"), List.of(
			new Prototype("anna", similar(0.80)),
			new Prototype("annie", similar(0.60))));
		assertEquals("A fifth is.", "anna", clear.matcher(null).personOf(axis(0)));

		System.setProperty(Recognition.MARGIN_PROPERTY, "0.005");
		assertEquals("And the margin is a property of the server too.", "anna",
			recognition.matcher(null).personOf(axis(0)));
	}

	/** The best prototype of a person counts, not the average of their prototypes. */
	public void testTheNearestPrototypeOfEachPersonDecides() {
		Recognition recognition = new Recognition();
		// Anna has one face that is nothing like the query and one that is exactly it; Bob has one
		// that is moderately like it.
		recognition.put(new File("/x"), List.of(
			new Prototype("anna", axis(7)),
			new Prototype("anna", axis(0)),
			new Prototype("bob", similar(0.5))));
		assertEquals("anna", recognition.matcher(null).personOf(axis(0)));
	}

	/** One person with no rival is never held back by the margin. */
	public void testASinglePersonHasNoSecondBest() {
		Recognition recognition = new Recognition();
		recognition.put(new File("/x"), Collections.singletonList(new Prototype("anna", similar(0.37))));
		assertEquals("anna", recognition.matcher(null).personOf(axis(0)));
	}

	/** An album described afresh replaces what it said before; nothing accumulates. */
	public void testAnAlbumIsAlwaysDescribedWholesale() {
		Recognition recognition = new Recognition();
		File folder = new File("/x");
		recognition.put(folder, List.of(new Prototype("anna", axis(0)), new Prototype("bob", axis(1))));
		assertEquals(2, recognition.size());

		recognition.put(folder, Collections.singletonList(new Prototype("bob", axis(1))));
		assertEquals("What was taken back is gone.", 1, recognition.size());
		assertEquals("", recognition.matcher(null).personOf(axis(0)));
		assertEquals("bob", recognition.matcher(null).personOf(axis(1)));

		recognition.forget(folder);
		assertTrue(recognition.isEmpty());
	}

	/** A person merged away is resolved to the survivor, so both their faces count (issue #125). */
	public void testAMergeIsFeltWithoutTouchingAPrototype() throws Exception {
		_base = Files.createTempDirectory("valbum-recognition");
		Files.createDirectories(_base.resolve(de.haumacher.imageServer.auth.UserStore.DIRECTORY_NAME));
		PeopleStore people = new PeopleStore(_base);
		PeopleStore.Entry anna = people.create("Anna", "user:haui");
		PeopleStore.Entry annie = people.create("Annie", "user:haui");

		Recognition recognition = new Recognition();
		recognition.put(new File("/x"), List.of(
			new Prototype(anna.getId(), axis(0)),
			new Prototype(annie.getId(), axis(1))));

		assertEquals(annie.getId(), recognition.matcher(people).personOf(axis(1)));

		people.merge(anna.getId(), annie.getId());
		assertEquals("Annie's own face now names Anna.", anna.getId(),
			recognition.matcher(people).personOf(axis(1)));
		assertEquals("And so does Anna's.", anna.getId(), recognition.matcher(people).personOf(axis(0)));

		// The two used to be each other's second-best; now they are one person, so a face between
		// them is no longer a tie.
		Recognition close = new Recognition();
		close.put(new File("/x"), List.of(
			new Prototype(anna.getId(), similar(0.80)),
			new Prototype(annie.getId(), similar(0.78))));
		assertEquals("One person cannot be their own rival.", anna.getId(),
			close.matcher(people).personOf(axis(0)));
	}

	/** An id the register does not know at all stands for itself, see {@code FaceTags.person}. */
	public void testAnUnknownIdIsAnsweredAsItStands() throws Exception {
		_base = Files.createTempDirectory("valbum-recognition");
		Files.createDirectories(_base.resolve(de.haumacher.imageServer.auth.UserStore.DIRECTORY_NAME));
		PeopleStore people = new PeopleStore(_base);
		Recognition recognition = new Recognition();
		recognition.put(new File("/x"), Collections.singletonList(new Prototype("ghost", axis(0))));
		assertEquals("ghost", recognition.matcher(people).personOf(axis(0)));
	}

	/** Another model's numbers are never compared to this one's. */
	public void testAnEmbeddingOfAnotherLengthIsNotCompared() {
		Recognition recognition = new Recognition();
		recognition.put(new File("/x"), Collections.singletonList(new Prototype("anna", axis(0))));
		float[] shorter = new float[64];
		shorter[0] = 1;
		assertEquals("", recognition.matcher(null).personOf(shorter));
	}

	/**
	 * A thousand confirmed faces against an album of two hundred, well inside the budget.
	 *
	 * <p>
	 * The bound is deliberately generous — a build machine is not a benchmark — and the point is
	 * the order of magnitude: matching is a dot product per prototype per face and must never be
	 * felt in a listing.
	 * </p>
	 */
	public void testAThousandFacesAreMatchedInNoTime() {
		Random random = new Random(42);
		List<Prototype> prototypes = new ArrayList<>(1000);
		for (int n = 0; n < 1000; n++) {
			prototypes.add(new Prototype("person-" + (n % 50), random(random)));
		}
		Recognition recognition = new Recognition();
		recognition.put(new File("/x"), prototypes);
		assertEquals(1000, recognition.size());

		List<float[]> album = new ArrayList<>(200);
		for (int n = 0; n < 200; n++) {
			album.add(random(random));
		}

		// Once to let the just-in-time compiler see the loop, then measured.
		matchAll(recognition, album);
		long start = System.nanoTime();
		matchAll(recognition, album);
		long millis = (System.nanoTime() - start) / 1_000_000;
		System.out.println("Matched 200 faces against 1000 prototypes in " + millis + " ms.");
		assertTrue("Matching took " + millis + " ms.", millis < 500);
	}

	private static void matchAll(Recognition recognition, List<float[]> album) {
		Match match = recognition.matcher(null);
		for (float[] face : album) {
			match.personOf(face);
		}
	}

	// --- Synthetic embeddings. ---

	/** The unit vector along the given dimension: a person nobody else looks like at all. */
	private static float[] axis(int dimension) {
		float[] result = new float[DIMENSIONS];
		result[dimension] = 1;
		return result;
	}

	/** A vector whose cosine similarity to {@link #axis(int) axis(0)} is exactly the given one. */
	private static float[] similar(double cosine) {
		float[] result = new float[DIMENSIONS];
		result[0] = (float) cosine;
		result[DIMENSIONS - 1] = (float) Math.sqrt(1 - cosine * cosine);
		return result;
	}

	private static float[] random(Random random) {
		float[] result = new float[DIMENSIONS];
		for (int n = 0; n < DIMENSIONS; n++) {
			result[n] = (float) random.nextGaussian();
		}
		return result;
	}
}
