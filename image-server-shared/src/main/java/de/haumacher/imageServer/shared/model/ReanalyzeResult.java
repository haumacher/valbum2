package de.haumacher.imageServer.shared.model;

/**
 * Answer to <code>&lt;folder&gt;/?action=reanalyze</code>, see issue #161.
 *
 * <p>
 * A part a sidecar already lists is never analysed again (issue #78), so a library described
 * before the camera (#78) or the position (#112) existed carries neither. On request the server
 * reads the headers of every photograph and video below the folder once more &mdash; never a pixel
 * &mdash; and fills in what the sidecar lacks: an empty {@link ImagePart#getCamera() camera} and an
 * absent (or <code>0/0</code>) {@link ImagePart#getLocation() location}. Nothing that is stored is
 * ever changed, least of all a date the author corrected.
 * </p>
 *
 * <p>
 * An album is re-read while the request waits. A folder of folders is re-read one album after the
 * other on a low-priority thread of the space; the request waits a few seconds for it and, where
 * that is not enough, is answered <code>202</code> with the counts so far and {@link #isRunning()} set.
 * The same counts are read back with <code>GET &lt;folder&gt;/?type=reanalyze</code>, and asking
 * for the same folder again while it runs joins that run instead of starting a second one.
 * </p>
 */
public class ReanalyzeResult extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ReanalyzeResult} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ReanalyzeResult create() {
		return new de.haumacher.imageServer.shared.model.ReanalyzeResult();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ReanalyzeResult} type in JSON format. */
	public static final String REANALYZE_RESULT__TYPE = "ReanalyzeResult";

	/** @see #getExamined() */
	private static final String EXAMINED__PROP = "examined";

	/** @see #getFilled() */
	private static final String FILLED__PROP = "filled";

	/** @see #getAlbums() */
	private static final String ALBUMS__PROP = "albums";

	/** @see #isRunning() */
	private static final String RUNNING__PROP = "running";

	private int _examined = 0;

	private int _filled = 0;

	private int _albums = 0;

	private boolean _running = false;

	/**
	 * Creates a {@link ReanalyzeResult} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ReanalyzeResult#create()
	 */
	protected ReanalyzeResult() {
		super();
	}

	/**
	 * How many photographs and videos were looked at.
	 */
	public final int getExamined() {
		return _examined;
	}

	/**
	 * @see #getExamined()
	 */
	public de.haumacher.imageServer.shared.model.ReanalyzeResult setExamined(int value) {
		internalSetExamined(value);
		return this;
	}

	/** Internal setter for {@link #getExamined()} without chain call utility. */
	protected final void internalSetExamined(int value) {
		_examined = value;
	}

	/**
	 * How many of them gained a camera, a position or both.
	 */
	public final int getFilled() {
		return _filled;
	}

	/**
	 * @see #getFilled()
	 */
	public de.haumacher.imageServer.shared.model.ReanalyzeResult setFilled(int value) {
		internalSetFilled(value);
		return this;
	}

	/** Internal setter for {@link #getFilled()} without chain call utility. */
	protected final void internalSetFilled(int value) {
		_filled = value;
	}

	/**
	 * How many album sidecars were written; an album that gained nothing is not written.
	 */
	public final int getAlbums() {
		return _albums;
	}

	/**
	 * @see #getAlbums()
	 */
	public de.haumacher.imageServer.shared.model.ReanalyzeResult setAlbums(int value) {
		internalSetAlbums(value);
		return this;
	}

	/** Internal setter for {@link #getAlbums()} without chain call utility. */
	protected final void internalSetAlbums(int value) {
		_albums = value;
	}

	/**
	 * Whether the run goes on in the background, so the counts are the counts so far.
	 */
	public final boolean isRunning() {
		return _running;
	}

	/**
	 * @see #isRunning()
	 */
	public de.haumacher.imageServer.shared.model.ReanalyzeResult setRunning(boolean value) {
		internalSetRunning(value);
		return this;
	}

	/** Internal setter for {@link #isRunning()} without chain call utility. */
	protected final void internalSetRunning(boolean value) {
		_running = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ReanalyzeResult readReanalyzeResult(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ReanalyzeResult result = new de.haumacher.imageServer.shared.model.ReanalyzeResult();
		result.readContent(in);
		return result;
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		writeContent(out);
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(EXAMINED__PROP);
		out.value(getExamined());
		out.name(FILLED__PROP);
		out.value(getFilled());
		out.name(ALBUMS__PROP);
		out.value(getAlbums());
		out.name(RUNNING__PROP);
		out.value(isRunning());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case EXAMINED__PROP: setExamined(in.nextInt()); break;
			case FILLED__PROP: setFilled(in.nextInt()); break;
			case ALBUMS__PROP: setAlbums(in.nextInt()); break;
			case RUNNING__PROP: setRunning(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
