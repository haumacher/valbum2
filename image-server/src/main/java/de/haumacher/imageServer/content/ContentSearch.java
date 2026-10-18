/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.shared.model.SearchProblem;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a {@link de.haumacher.imageServer.shared.model.SearchContent} criterion is answered, see issue
 * #257: the one place its weights, its cut and its page are set, and where the text of a query is
 * turned into a vector; and the cut of "More like this"
 * ({@link de.haumacher.imageServer.shared.model.SearchSimilar}), which pages alike.
 *
 * <p>
 * <b>Not calibrated.</b> The weights and the cut below are first guesses, set before the search was
 * ever tried on a real library (the calibration of package 1 of #257 was never run); they are
 * constants here so that a calibration changes them in one place. The score of a photograph is
 * <code>IMAGE_WEIGHT · cos(q, v_img) + TEXT_WEIGHT · cos(q, v_txt)</code>; the weights add up to
 * one, so the score is a cosine, and an unrelated photograph scores about <code>0</code>.
 * </p>
 */
public final class ContentSearch {

	/**
	 * The weight of the picture's vector: what a photograph shows is what the content search is
	 * for, the text of a photograph (album, places, description) is what the conventional search
	 * finds already.
	 */
	public static final float IMAGE_WEIGHT = 0.7f;

	/** The weight of the text's vector, see {@link #IMAGE_WEIGHT}. */
	public static final float TEXT_WEIGHT = 0.3f;

	/**
	 * The score below which a photograph is not answered at all: low on purpose. The answer is
	 * ordered by the score, so a cut too low shows the unrelated photographs last, while one too
	 * high would say "nothing found" of a library full of matches.
	 */
	public static final float THRESHOLD = 0.05f;

	/**
	 * The cosine below which a photograph is not answered by "More like this" (a
	 * {@link de.haumacher.imageServer.shared.model.SearchSimilar}): a cut of its own, for the cosines
	 * of two pictures lie on another scale than those of a text and a picture &mdash; two unrelated
	 * photographs are rarely orthogonal, while a text rarely comes near any picture. <b>Not
	 * calibrated</b> either: a first guess, set before "More like this" was tried on a real library,
	 * on the low side for the same reason as {@link #THRESHOLD}.
	 */
	public static final float SIMILAR_THRESHOLD = 0.3f;

	/**
	 * The cosine of their pictures' vectors at or above which two photographs taken one after the
	 * other belong to one burst of their album (a {@link de.haumacher.imageServer.shared.model.PhotoStack}),
	 * if they were also taken at most {@link #BURST_GAP} apart. High on purpose: a stack hides
	 * photographs behind one tile, so a cut too low stacks what the author wants to see side by
	 * side, while one too high merely shows a burst as single tiles, as before. <b>Not calibrated</b>:
	 * a first guess, set before stacks were tried on a real library.
	 */
	public static final float BURST_THRESHOLD = 0.9f;

	/**
	 * How far apart in time two photographs taken one after the other may be and still belong to
	 * one burst, in milliseconds, see {@link #BURST_THRESHOLD}. <b>Not calibrated</b>: a first guess
	 * of "a few minutes".
	 */
	public static final long BURST_GAP = 3 * 60_000L;

	/**
	 * The cosine of their pictures' vectors at or above which two photographs of the space are
	 * shots of the same scenery (a {@link de.haumacher.imageServer.shared.model.SimilarGroup}),
	 * whenever they were taken: lower than {@link #BURST_THRESHOLD}, for the same place seen on
	 * another day is less alike than two shots of one moment; far above {@link #SIMILAR_THRESHOLD},
	 * for the overview links what it finds into groups, and a cut too low would chain the whole
	 * library into one. <b>Not calibrated</b>: a first guess.
	 */
	public static final float SCENERY_THRESHOLD = 0.8f;

	/**
	 * How many of its nearest neighbours each photograph is compared with for the overview of the
	 * same scenery: what keeps the overview bounded (one query of the vector index per photograph,
	 * never every pair), at the price of a scenery shot more than this many times missing some of
	 * its links, which the links of the others usually make up for.
	 */
	public static final int SCENERY_NEIGHBOURS = 12;

	/** How many photographs the search view is answered, the nearest first, where it asks for no other number. */
	public static final int PAGE = 100;

	/**
	 * How many photographs a content search answers at most: what the search view may ask for with
	 * <code>&amp;count=</code> ("show more"), and what a saved search answers.
	 */
	public static final int MAX = 1000;

	/** The message a content search in a space that has it switched off is refused with. */
	public static final String SWITCHED_OFF = "Content search is switched off in this space.";

	/** What the message of a content search the embedder cannot answer starts with. */
	public static final String UNAVAILABLE = "Content search is not available now: ";

	/** Why a content search is not answered while the photographs are described for another model. */
	public static final String OTHER_MODEL =
		"the photos are being described again for another model; try again once that is done";

	/** Why a content search is not answered before any photograph was described. */
	public static final String NOT_DESCRIBED = "no photo of this space has been described yet";

	/** The message of a query whose content criterion is not where it may be. */
	public static final String COMBINED =
		"A search looks for what the photos show once, together with the other criteria (“all of these”): "
			+ "it cannot be excluded or be one of “any of these”.";

	/**
	 * The message of a query that looks for what the photos show by a text and by a photograph at
	 * once: a query ranks once, see {@link de.haumacher.imageServer.shared.model.SearchSimilar}.
	 */
	public static final String TEXT_AND_PHOTO =
		"A search finds what the photos show either by a description or by a photo, not by both at once.";

	/** The message of a content criterion without a text. */
	public static final String EMPTY = "Describe what the photos should show.";

	/** How many query vectors are kept, see {@link #vector(Embedder, String)}. */
	private static final int KEPT = 64;

	/** The vectors of the last queries, by the embedder's stamp and the text. */
	private static final Map<String, float[]> VECTORS = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
			return size() > KEPT;
		}
	};

	private ContentSearch() {
		// Static only.
	}

	/**
	 * A content search that cannot be answered, with the status and the sentence it is refused
	 * with: thrown from wherever a query is run, answered by the servlet as an error, see
	 * <code>ImageServlet#doGet</code>.
	 */
	public static final class Refused extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final int _status;

		private final SearchProblem _problem;

		/** Creates a {@link Refused}. */
		public Refused(int status, String message) {
			this(status, message, SearchProblem.NONE);
		}

		/**
		 * Creates a {@link Refused} for the given problem of a
		 * {@link de.haumacher.imageServer.shared.model.SearchSimilar}: answered as an empty result
		 * saying it, see {@link de.haumacher.imageServer.shared.model.AlbumInfo#getSearchProblem()},
		 * by whoever answers a search, and as an error only by what cannot say it otherwise.
		 */
		public Refused(int status, String message, SearchProblem problem) {
			super(message, null, false, false);
			_status = status;
			_problem = problem;
		}

		/** The HTTP status the refusal is answered with. */
		public int getStatus() {
			return _status;
		}

		/**
		 * What is wrong with the photograph of a "More like this", {@link SearchProblem#NONE} for
		 * every other refusal.
		 */
		public SearchProblem getProblem() {
			return _problem;
		}
	}

	/** Why "More like this" is not answered for a photograph that is gone or hidden from the caller. */
	public static final String PHOTO_NOT_FOUND = "The photo to compare with is not there.";

	/** Why "More like this" is not answered for a photograph without a vector yet. */
	public static final String PHOTO_NOT_DESCRIBED =
		"The photo to compare with has not been described yet; try again later.";

	/**
	 * The refusal of a "More like this" whose photograph is gone or hidden from the caller: the same
	 * for both, see {@link SearchProblem#PHOTO_NOT_FOUND}.
	 */
	public static Refused photoNotFound() {
		return new Refused(HttpServletResponse.SC_NOT_FOUND, PHOTO_NOT_FOUND, SearchProblem.PHOTO_NOT_FOUND);
	}

	/** The refusal of a "More like this" whose photograph has no vector yet. */
	public static Refused photoNotDescribed() {
		return new Refused(HttpServletResponse.SC_CONFLICT, PHOTO_NOT_DESCRIBED, SearchProblem.PHOTO_NOT_DESCRIBED);
	}

	/** The refusal of a content search the embedder cannot answer, for the given reason. */
	public static Refused unavailable(String reason) {
		String text = reason == null || reason.isBlank() ? "the embedder does not answer" : reason.strip();
		return new Refused(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
			UNAVAILABLE + text + (text.endsWith(".") ? "" : "."));
	}

	/**
	 * The vector of the given query text, see {@link Embedder.TextKind#QUERY}: asked of the embedder
	 * once per text and model, kept for the next pages and the saved searches opened again.
	 *
	 * @throws Refused
	 *         Where the embedder cannot answer, saying why.
	 */
	public static float[] vector(Embedder embedder, String text) {
		String stamp = embedder.stamp();
		String key = stamp == null ? null : stamp + '\u0000' + text;
		if (key != null) {
			synchronized (VECTORS) {
				float[] known = VECTORS.get(key);
				if (known != null) {
					return known;
				}
			}
		}
		float[] result;
		try {
			result = embedder.embedText(text, Embedder.TextKind.QUERY);
		} catch (IOException | RuntimeException ex) {
			throw unavailable(ex.getMessage());
		}
		if (result == null) {
			throw unavailable(null);
		}
		// The stamp is known once the embedder answered.
		String answered = embedder.stamp();
		if (answered != null) {
			synchronized (VECTORS) {
				VECTORS.put(answered + '\u0000' + text, result);
			}
		}
		return result;
	}

	/** Forgets every query vector kept, for the tests. */
	public static void forget() {
		synchronized (VECTORS) {
			VECTORS.clear();
		}
	}
}
