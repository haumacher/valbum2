/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;

/**
 * Turns texts and pictures into vectors of one shared space, see issue #257: a text query and a
 * photograph are compared by the dot product of their vectors.
 *
 * <p>
 * Every vector is L2-normalised and {@link #DIMENSION} wide (the leading part of the model's 768,
 * normalised again: the model is trained for that truncation). Vectors are comparable only when
 * their {@link #stamp() stamps} are equal.
 * </p>
 *
 * <p>
 * An embedder may not be able to answer yet: its model is still being downloaded, its service is
 * starting, or it cannot run on this machine at all. A call then fails with
 * {@link EmbedderUnavailableException}, whose message says why; {@link #unavailability()} says the
 * same without trying, and {@link #awaitAvailable(Duration)} waits for it, which is what a
 * background step does instead of failing every photograph.
 * </p>
 */
public interface Embedder extends Closeable {

	/**
	 * The width of the vectors VAlbum stores: 1 KiB of float32 per vector, nearly lossless after
	 * the model card's Matryoshka table.
	 */
	int DIMENSION = 256;

	/**
	 * What a text is to the model, see the task prefixes of the model card. The prefix is added
	 * here, by the client, not hidden in the service: what is embedded is decided where it is
	 * visible.
	 */
	enum TextKind {
		/** A search query, compared with documents and pictures. */
		QUERY("task: search result | query: "),

		/** A text describing a photograph, without a title (see {@link Embedder#document(String, String)}). */
		DOCUMENT("title: none | text: ");

		private final String _prefix;

		TextKind(String prefix) {
			_prefix = prefix;
		}

		/** What is put before the text. */
		public String prefix() {
			return _prefix;
		}

		/** The text as the model is given it. */
		public String apply(String text) {
			return _prefix + text;
		}
	}

	/**
	 * A document as the model card spells it: <code>title: &lt;title&gt; | text: &lt;text&gt;</code>,
	 * <code>none</code> for an empty title.
	 */
	static String document(String title, String text) {
		String head = title == null || title.isBlank() ? "none" : title.replace('|', '/').strip();
		return "title: " + head + " | text: " + text;
	}

	/** The vector of a text, its prefix given by the kind. */
	float[] embedText(String text, TextKind kind) throws IOException;

	/** The vector of a document with a title, see {@link #document(String, String)}. */
	float[] embedDocument(String title, String text) throws IOException;

	/**
	 * The vector of a picture.
	 *
	 * @param image
	 *        The encoded picture (JPEG, PNG, WebP): a preview, never an original; upright, or with an
	 *        EXIF orientation that says how to show it.
	 */
	float[] embedImage(byte[] image) throws IOException;

	/**
	 * What every vector of this embedder is stamped with, see {@link ContentModel#stamp(String, int)};
	 * <code>null</code> while it is not known (a service of its own that was not reached yet).
	 */
	String stamp();

	/**
	 * Why this embedder cannot answer now, <code>null</code> when it can. Asking may set in motion
	 * what is needed (a download, the start of the service); it never waits for it.
	 */
	String unavailability();

	/**
	 * Why this embedder cannot answer now, as {@link #unavailability()} says it, but without setting
	 * anything in motion: what the settings of a space show while its content search is off, see
	 * issue #257. By default the same as {@link #unavailability()}, which asks a service of its own
	 * and starts nothing.
	 */
	default String peekUnavailability() {
		return unavailability();
	}

	/**
	 * How far this embedder has got preparing itself, as {@link #unavailability()} would say it, but
	 * known without asking anybody and without setting anything in motion: what the progress of the
	 * background shows between two questions of the content search (the download of a model goes on
	 * while the step backs off). <code>null</code> where it cannot tell without asking; by default
	 * always.
	 */
	default String progress() {
		return null;
	}

	/**
	 * How many units of the content search may run at once on a lane of their own, beside the photo
	 * work of the server, see issue #311: as many as a service on a machine of its own embeds at once.
	 * 0 (the default) for an embedder that shares the server's machine: its units wait behind the
	 * photo work and run one at a time, so that it never takes the cores a preview needs. Known
	 * without asking anybody; may change while the server runs.
	 */
	default int lane() {
		return 0;
	}

	/**
	 * Waits until this embedder can answer, setting in motion what is needed.
	 *
	 * @return Whether it can, <code>false</code> when the time ran out or it never will (then
	 *         {@link #unavailability()} says why).
	 */
	boolean awaitAvailable(Duration timeout) throws InterruptedException;

	/** Stops what this embedder started. */
	@Override
	void close();
}
