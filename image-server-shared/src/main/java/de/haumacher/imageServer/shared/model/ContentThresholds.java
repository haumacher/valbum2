package de.haumacher.imageServer.shared.model;

/**
 * The cuts of the content search of a space, see {@link SpaceSettings#getThresholds()} and issue #302:
 * stored in its <code>space.json</code> as <code>contentThreshold</code> and
 * <code>similarThreshold</code>.
 */
public class ContentThresholds extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContentThresholds} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContentThresholds create() {
		return new de.haumacher.imageServer.shared.model.ContentThresholds();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContentThresholds} type in JSON format. */
	public static final String CONTENT_THRESHOLDS__TYPE = "ContentThresholds";

	/** @see #getText() */
	private static final String TEXT__PROP = "text";

	/** @see #getSimilar() */
	private static final String SIMILAR__PROP = "similar";

	/** @see #getDefaultText() */
	private static final String DEFAULT_TEXT__PROP = "defaultText";

	/** @see #getDefaultSimilar() */
	private static final String DEFAULT_SIMILAR__PROP = "defaultSimilar";

	private double _text = 0.0d;

	private double _similar = 0.0d;

	private double _defaultText = 0.0d;

	private double _defaultSimilar = 0.0d;

	/**
	 * Creates a {@link ContentThresholds} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContentThresholds#create()
	 */
	protected ContentThresholds() {
		super();
	}

	/**
	 * The score below which a search by a text ({@link SearchContent}) answers no photograph,
	 * between <code>0</code> and <code>1</code>; <code>0</code> for {@link #getDefaultText()}.
	 */
	public final double getText() {
		return _text;
	}

	/**
	 * @see #getText()
	 */
	public de.haumacher.imageServer.shared.model.ContentThresholds setText(double value) {
		internalSetText(value);
		return this;
	}

	/** Internal setter for {@link #getText()} without chain call utility. */
	protected final void internalSetText(double value) {
		_text = value;
	}

	/**
	 * The cosine below which "More like this" ({@link SearchSimilar}) answers no photograph,
	 * between <code>0</code> and <code>1</code>; <code>0</code> for {@link #getDefaultSimilar()}.
	 */
	public final double getSimilar() {
		return _similar;
	}

	/**
	 * @see #getSimilar()
	 */
	public de.haumacher.imageServer.shared.model.ContentThresholds setSimilar(double value) {
		internalSetSimilar(value);
		return this;
	}

	/** Internal setter for {@link #getSimilar()} without chain call utility. */
	protected final void internalSetSimilar(double value) {
		_similar = value;
	}

	/**
	 * <em>Answered:</em> the server's built-in cut of a search by a text, which a {@link #getText()} of <code>0</code> means.
	 */
	public final double getDefaultText() {
		return _defaultText;
	}

	/**
	 * @see #getDefaultText()
	 */
	public de.haumacher.imageServer.shared.model.ContentThresholds setDefaultText(double value) {
		internalSetDefaultText(value);
		return this;
	}

	/** Internal setter for {@link #getDefaultText()} without chain call utility. */
	protected final void internalSetDefaultText(double value) {
		_defaultText = value;
	}

	/**
	 * <em>Answered:</em> the server's built-in cut of "More like this", which a {@link #getSimilar()} of <code>0</code> means.
	 */
	public final double getDefaultSimilar() {
		return _defaultSimilar;
	}

	/**
	 * @see #getDefaultSimilar()
	 */
	public de.haumacher.imageServer.shared.model.ContentThresholds setDefaultSimilar(double value) {
		internalSetDefaultSimilar(value);
		return this;
	}

	/** Internal setter for {@link #getDefaultSimilar()} without chain call utility. */
	protected final void internalSetDefaultSimilar(double value) {
		_defaultSimilar = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContentThresholds readContentThresholds(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContentThresholds result = new de.haumacher.imageServer.shared.model.ContentThresholds();
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
		out.name(TEXT__PROP);
		out.value(getText());
		out.name(SIMILAR__PROP);
		out.value(getSimilar());
		out.name(DEFAULT_TEXT__PROP);
		out.value(getDefaultText());
		out.name(DEFAULT_SIMILAR__PROP);
		out.value(getDefaultSimilar());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TEXT__PROP: setText(in.nextDouble()); break;
			case SIMILAR__PROP: setSimilar(in.nextDouble()); break;
			case DEFAULT_TEXT__PROP: setDefaultText(in.nextDouble()); break;
			case DEFAULT_SIMILAR__PROP: setDefaultSimilar(in.nextDouble()); break;
			default: super.readField(in, field);
		}
	}

}
