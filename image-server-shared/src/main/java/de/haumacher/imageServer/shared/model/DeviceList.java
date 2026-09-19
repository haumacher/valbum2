package de.haumacher.imageServer.shared.model;

/**
 * The caller's own devices, answered by <code>&lt;data&gt;/?type=devices</code> and by
 * <code>&lt;data&gt;/?action=unpair</code>, see issue #55.
 */
public class DeviceList extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.DeviceList} instance.
	 */
	public static de.haumacher.imageServer.shared.model.DeviceList create() {
		return new de.haumacher.imageServer.shared.model.DeviceList();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.DeviceList} type in JSON format. */
	public static final String DEVICE_LIST__TYPE = "DeviceList";

	/** @see #getDevices() */
	private static final String DEVICES__PROP = "devices";

	/** @see #getBackupCodeCreated() */
	private static final String BACKUP_CODE_CREATED__PROP = "backupCodeCreated";

	private final java.util.List<de.haumacher.imageServer.shared.model.DeviceEntry> _devices = new java.util.ArrayList<>();

	private String _backupCodeCreated = "";

	/**
	 * Creates a {@link DeviceList} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.DeviceList#create()
	 */
	protected DeviceList() {
		super();
	}

	/**
	 * The devices, in the order they were paired.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.DeviceEntry> getDevices() {
		return _devices;
	}

	/**
	 * @see #getDevices()
	 */
	public de.haumacher.imageServer.shared.model.DeviceList setDevices(java.util.List<? extends de.haumacher.imageServer.shared.model.DeviceEntry> value) {
		internalSetDevices(value);
		return this;
	}

	/** Internal setter for {@link #getDevices()} without chain call utility. */
	protected final void internalSetDevices(java.util.List<? extends de.haumacher.imageServer.shared.model.DeviceEntry> value) {
		if (value == null) throw new IllegalArgumentException("Property 'devices' cannot be null.");
		_devices.clear();
		_devices.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getDevices()} list.
	 */
	public de.haumacher.imageServer.shared.model.DeviceList addDevice(de.haumacher.imageServer.shared.model.DeviceEntry value) {
		internalAddDevice(value);
		return this;
	}

	/** Implementation of {@link #addDevice(de.haumacher.imageServer.shared.model.DeviceEntry)} without chain call utility. */
	protected final void internalAddDevice(de.haumacher.imageServer.shared.model.DeviceEntry value) {
		_devices.add(value);
	}

	/**
	 * Removes a value from the {@link #getDevices()} list.
	 */
	public final void removeDevice(de.haumacher.imageServer.shared.model.DeviceEntry value) {
		_devices.remove(value);
	}

	/**
	 * When the caller's backup code was made, empty while they have none (issue #92).
	 *
	 * <p>
	 * Never the code itself — that is answered exactly once, when it is made. What the list
	 * says is only whether there <em>is</em> one and since when, which is what the devices section
	 * shows and what the sign-out warning needs: signing out of one's last device is a door that
	 * locks behind one, unless a backup code is lying in a drawer.
	 * </p>
	 */
	public final String getBackupCodeCreated() {
		return _backupCodeCreated;
	}

	/**
	 * @see #getBackupCodeCreated()
	 */
	public de.haumacher.imageServer.shared.model.DeviceList setBackupCodeCreated(String value) {
		internalSetBackupCodeCreated(value);
		return this;
	}

	/** Internal setter for {@link #getBackupCodeCreated()} without chain call utility. */
	protected final void internalSetBackupCodeCreated(String value) {
		_backupCodeCreated = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.DeviceList readDeviceList(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.DeviceList result = new de.haumacher.imageServer.shared.model.DeviceList();
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
		out.name(DEVICES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.DeviceEntry x : getDevices()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(BACKUP_CODE_CREATED__PROP);
		out.value(getBackupCodeCreated());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case DEVICES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addDevice(de.haumacher.imageServer.shared.model.DeviceEntry.readDeviceEntry(in));
				}
				in.endArray();
			}
			break;
			case BACKUP_CODE_CREATED__PROP: setBackupCodeCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
