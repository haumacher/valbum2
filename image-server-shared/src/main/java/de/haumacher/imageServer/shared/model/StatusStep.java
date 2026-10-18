package de.haumacher.imageServer.shared.model;

/**
 * How far one step of the background has got, see {@link StatusInfo#getSteps()}.
 */
public class StatusStep extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.StatusStep} instance.
	 */
	public static de.haumacher.imageServer.shared.model.StatusStep create() {
		return new de.haumacher.imageServer.shared.model.StatusStep();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.StatusStep} type in JSON format. */
	public static final String STATUS_STEP__TYPE = "StatusStep";

	/** @see #getStep() */
	private static final String STEP__PROP = "step";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getDone() */
	private static final String DONE__PROP = "done";

	/** @see #getTotal() */
	private static final String TOTAL__PROP = "total";

	private de.haumacher.imageServer.shared.model.CatchUpStep _step = de.haumacher.imageServer.shared.model.CatchUpStep.IDLE;

	private String _name = "";

	private int _done = 0;

	private int _total = 0;

	/**
	 * Creates a {@link StatusStep} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.StatusStep#create()
	 */
	protected StatusStep() {
		super();
	}

	/**
	 * The step, {@link CatchUpStep#OTHER} for one this description does not name.
	 */
	public final de.haumacher.imageServer.shared.model.CatchUpStep getStep() {
		return _step;
	}

	/**
	 * @see #getStep()
	 */
	public de.haumacher.imageServer.shared.model.StatusStep setStep(de.haumacher.imageServer.shared.model.CatchUpStep value) {
		internalSetStep(value);
		return this;
	}

	/** Internal setter for {@link #getStep()} without chain call utility. */
	protected final void internalSetStep(de.haumacher.imageServer.shared.model.CatchUpStep value) {
		if (value == null) throw new IllegalArgumentException("Property 'step' cannot be null.");
		_step = value;
	}

	/**
	 * The step's own name, as the server's log says it.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.StatusStep setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * How many albums the step is done with.
	 */
	public final int getDone() {
		return _done;
	}

	/**
	 * @see #getDone()
	 */
	public de.haumacher.imageServer.shared.model.StatusStep setDone(int value) {
		internalSetDone(value);
		return this;
	}

	/** Internal setter for {@link #getDone()} without chain call utility. */
	protected final void internalSetDone(int value) {
		_done = value;
	}

	/**
	 * How many albums there are.
	 */
	public final int getTotal() {
		return _total;
	}

	/**
	 * @see #getTotal()
	 */
	public de.haumacher.imageServer.shared.model.StatusStep setTotal(int value) {
		internalSetTotal(value);
		return this;
	}

	/** Internal setter for {@link #getTotal()} without chain call utility. */
	protected final void internalSetTotal(int value) {
		_total = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.StatusStep readStatusStep(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.StatusStep result = new de.haumacher.imageServer.shared.model.StatusStep();
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
		out.name(STEP__PROP);
		getStep().writeTo(out);
		out.name(NAME__PROP);
		out.value(getName());
		out.name(DONE__PROP);
		out.value(getDone());
		out.name(TOTAL__PROP);
		out.value(getTotal());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case STEP__PROP: setStep(de.haumacher.imageServer.shared.model.CatchUpStep.readCatchUpStep(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DONE__PROP: setDone(in.nextInt()); break;
			case TOTAL__PROP: setTotal(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

}
