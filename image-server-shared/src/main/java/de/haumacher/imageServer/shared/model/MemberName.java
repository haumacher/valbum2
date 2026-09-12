package de.haumacher.imageServer.shared.model;

/**
 * The name of a single group member, see {@link Group#getMembers()}.
 */
public class MemberName extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.MemberName} instance.
	 */
	public static de.haumacher.imageServer.shared.model.MemberName create() {
		return new de.haumacher.imageServer.shared.model.MemberName();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.MemberName} type in JSON format. */
	public static final String MEMBER_NAME__TYPE = "MemberName";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _name = "";

	/**
	 * Creates a {@link MemberName} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.MemberName#create()
	 */
	protected MemberName() {
		super();
	}

	/**
	 * The name of the user.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.MemberName setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.MemberName readMemberName(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.MemberName result = new de.haumacher.imageServer.shared.model.MemberName();
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
		out.name(NAME__PROP);
		out.value(getName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
