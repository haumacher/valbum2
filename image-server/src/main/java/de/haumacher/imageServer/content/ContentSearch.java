/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a {@link de.haumacher.imageServer.shared.model.SearchContent} criterion is answered, see issue
 * #257: the one place its weights, its cut and its page are set, and where the text of a query is
 * turned into a vector.
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

		/** Creates a {@link Refused}. */
		public Refused(int status, String message) {
			super(message, null, false, false);
			_status = status;
		}

		/** The HTTP status the refusal is answered with. */
		public int getStatus() {
			return _status;
		}
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
