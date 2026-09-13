package de.haumacher.imageServer.shared.model;

/**
 * A device somebody paired with this server, see issue #55.
 *
 * <p>
 * Answered by <code>&lt;data&gt;/?type=devices</code> and sent to
 * <code>&lt;data&gt;/?action=unpair</code>, which names the device to sign out by its {@link #getId()}.
 * A caller only ever sees and unpairs devices of their own: the administrator manages the users of
 * this server, not other people's phones.
 * </p>
 *
 * <p>
 * The token is never part of this message, and neither is its hash: a device is named by its id,
 * which is a name and not a secret.
 * </p>
 */
public class DeviceEntry extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.DeviceEntry} instance.
	 */
	public static de.haumacher.imageServer.shared.model.DeviceEntry create() {
		return new de.haumacher.imageServer.shared.model.DeviceEntry();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.DeviceEntry} type in JSON format. */
	public static final String DEVICE_ENTRY__TYPE = "DeviceEntry";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getCreated() */
	private static final String CREATED__PROP = "created";

	/** @see #isCurrent() */
	private static final String CURRENT__PROP = "current";

	private String _id = "";

	private String _name = "";

	private String _created = "";

	private boolean _current = false;

	/**
	 * Creates a {@link DeviceEntry} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.DeviceEntry#create()
	 */
	protected DeviceEntry() {
		super();
	}

	/**
	 * The short id of the device, assigned when it was paired; what names it in a request.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.DeviceEntry setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * The name the device announced itself with when it was paired.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.DeviceEntry setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * When the device was paired, an ISO-8601 instant; answered by the server, ignored in a request.
	 */
	public final String getCreated() {
		return _created;
	}

	/**
	 * @see #getCreated()
	 */
	public de.haumacher.imageServer.shared.model.DeviceEntry setCreated(String value) {
		internalSetCreated(value);
		return this;
	}

	/** Internal setter for {@link #getCreated()} without chain call utility. */
	protected final void internalSetCreated(String value) {
		_created = value;
	}

	/**
	 * Whether this is the device the request came from; answered by the server, ignored in a request.
	 *
	 * <p>
	 * True on exactly one entry of a listing, so that the app can say "this device" and warn before
	 * signing it out — which is allowed, and is how a device signs itself out for good.
	 * </p>
	 */
	public final boolean isCurrent() {
		return _current;
	}

	/**
	 * @see #isCurrent()
	 */
	public de.haumacher.imageServer.shared.model.DeviceEntry setCurrent(boolean value) {
		internalSetCurrent(value);
		return this;
	}

	/** Internal setter for {@link #isCurrent()} without chain call utility. */
	protected final void internalSetCurrent(boolean value) {
		_current = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.DeviceEntry readDeviceEntry(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.DeviceEntry result = new de.haumacher.imageServer.shared.model.DeviceEntry();
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
		out.name(ID__PROP);
		out.value(getId());
		out.name(NAME__PROP);
		out.value(getName());
		out.name(CREATED__PROP);
		out.value(getCreated());
		out.name(CURRENT__PROP);
		out.value(isCurrent());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CREATED__PROP: setCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CURRENT__PROP: setCurrent(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
