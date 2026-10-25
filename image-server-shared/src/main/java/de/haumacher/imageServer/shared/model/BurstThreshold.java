package de.haumacher.imageServer.shared.model;

/**
 * The cut of the bursts of a space, see {@link SpaceSettings#getBurst()} and issue #315: stored in its
 * <code>space.json</code> as <code>burstThreshold</code>.
 */
public class BurstThreshold extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.BurstThreshold} instance.
	 */
	public static de.haumacher.imageServer.shared.model.BurstThreshold create() {
		return new de.haumacher.imageServer.shared.model.BurstThreshold();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.BurstThreshold} type in JSON format. */
	public static final String BURST_THRESHOLD__TYPE = "BurstThreshold";

	/** @see #getCut() */
	private static final String CUT__PROP = "cut";

	/** @see #getDefaultCut() */
	private static final String DEFAULT_CUT__PROP = "defaultCut";

	private double _cut = 0.0d;

	private double _defaultCut = 0.0d;

	/**
	 * Creates a {@link BurstThreshold} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.BurstThreshold#create()
	 */
	protected BurstThreshold() {
		super();
	}

	/**
	 * The cosine of their pictures' vectors at or above which two photographs taken one after the
	 * other are one burst, between <code>0.5</code> and <code>1</code>; <code>0</code> for
	 * {@link #getDefaultCut()}.
	 */
	public final double getCut() {
		return _cut;
	}

	/**
	 * @see #getCut()
	 */
	public de.haumacher.imageServer.shared.model.BurstThreshold setCut(double value) {
		internalSetCut(value);
		return this;
	}

	/** Internal setter for {@link #getCut()} without chain call utility. */
	protected final void internalSetCut(double value) {
		_cut = value;
	}

	/**
	 * <em>Answered:</em> the server's built-in cut of the bursts, which a {@link #getCut()} of <code>0</code> means.
	 */
	public final double getDefaultCut() {
		return _defaultCut;
	}

	/**
	 * @see #getDefaultCut()
	 */
	public de.haumacher.imageServer.shared.model.BurstThreshold setDefaultCut(double value) {
		internalSetDefaultCut(value);
		return this;
	}

	/** Internal setter for {@link #getDefaultCut()} without chain call utility. */
	protected final void internalSetDefaultCut(double value) {
		_defaultCut = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.BurstThreshold readBurstThreshold(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.BurstThreshold result = new de.haumacher.imageServer.shared.model.BurstThreshold();
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
		out.name(CUT__PROP);
		out.value(getCut());
		out.name(DEFAULT_CUT__PROP);
		out.value(getDefaultCut());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CUT__PROP: setCut(in.nextDouble()); break;
			case DEFAULT_CUT__PROP: setDefaultCut(in.nextDouble()); break;
			default: super.readField(in, field);
		}
	}

}
