package de.haumacher.imageServer.shared.model;

/**
 * The grants on a folder and its ancestors, answered by
 * <code>&lt;folder&gt;/?type=grants</code>.
 *
 * <p>
 * Only the owner of the space and the admin may ask: a grant says who else is let in, which is
 * nobody else's business.
 * </p>
 */
public class GrantList extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.GrantList} instance.
	 */
	public static de.haumacher.imageServer.shared.model.GrantList create() {
		return new de.haumacher.imageServer.shared.model.GrantList();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.GrantList} type in JSON format. */
	public static final String GRANT_LIST__TYPE = "GrantList";

	/** @see #getGrants() */
	private static final String GRANTS__PROP = "grants";

	private final java.util.List<de.haumacher.imageServer.shared.model.Grant> _grants = new java.util.ArrayList<>();

	/**
	 * Creates a {@link GrantList} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.GrantList#create()
	 */
	protected GrantList() {
		super();
	}

	/**
	 * The grants covering the addressed folder, the nearest one first.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.Grant> getGrants() {
		return _grants;
	}

	/**
	 * @see #getGrants()
	 */
	public de.haumacher.imageServer.shared.model.GrantList setGrants(java.util.List<? extends de.haumacher.imageServer.shared.model.Grant> value) {
		internalSetGrants(value);
		return this;
	}

	/** Internal setter for {@link #getGrants()} without chain call utility. */
	protected final void internalSetGrants(java.util.List<? extends de.haumacher.imageServer.shared.model.Grant> value) {
		if (value == null) throw new IllegalArgumentException("Property 'grants' cannot be null.");
		_grants.clear();
		_grants.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getGrants()} list.
	 */
	public de.haumacher.imageServer.shared.model.GrantList addGrant(de.haumacher.imageServer.shared.model.Grant value) {
		internalAddGrant(value);
		return this;
	}

	/** Implementation of {@link #addGrant(de.haumacher.imageServer.shared.model.Grant)} without chain call utility. */
	protected final void internalAddGrant(de.haumacher.imageServer.shared.model.Grant value) {
		_grants.add(value);
	}

	/**
	 * Removes a value from the {@link #getGrants()} list.
	 */
	public final void removeGrant(de.haumacher.imageServer.shared.model.Grant value) {
		_grants.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.GrantList readGrantList(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.GrantList result = new de.haumacher.imageServer.shared.model.GrantList();
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
		out.name(GRANTS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.Grant x : getGrants()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case GRANTS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addGrant(de.haumacher.imageServer.shared.model.Grant.readGrant(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
