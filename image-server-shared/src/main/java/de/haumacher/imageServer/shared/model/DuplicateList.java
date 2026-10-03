package de.haumacher.imageServer.shared.model;

/**
 * The photographs of a space that lie in more than one album, see issue #220 and
 * <code>&lt;data&gt;/?type=duplicates</code>.
 *
 * <p>
 * Read-only: nothing is moved or deleted by asking. Every copy is one the caller may see, exactly
 * as the album itself would show it to them; a content of which the caller sees fewer than two
 * copies is no group for them.
 * </p>
 */
public class DuplicateList extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.DuplicateList} instance.
	 */
	public static de.haumacher.imageServer.shared.model.DuplicateList create() {
		return new de.haumacher.imageServer.shared.model.DuplicateList();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.DuplicateList} type in JSON format. */
	public static final String DUPLICATE_LIST__TYPE = "DuplicateList";

	/** @see #getGroups() */
	private static final String GROUPS__PROP = "groups";

	/** @see #getIndexed() */
	private static final String INDEXED__PROP = "indexed";

	private final java.util.List<de.haumacher.imageServer.shared.model.DuplicateGroup> _groups = new java.util.ArrayList<>();

	private de.haumacher.imageServer.shared.model.IndexProgress _indexed = null;

	/**
	 * Creates a {@link DuplicateList} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.DuplicateList#create()
	 */
	protected DuplicateList() {
		super();
	}

	/**
	 * The groups, the most copies first, then the newest photograph, then by the path of the first
	 * copy.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.DuplicateGroup> getGroups() {
		return _groups;
	}

	/**
	 * @see #getGroups()
	 */
	public de.haumacher.imageServer.shared.model.DuplicateList setGroups(java.util.List<? extends de.haumacher.imageServer.shared.model.DuplicateGroup> value) {
		internalSetGroups(value);
		return this;
	}

	/** Internal setter for {@link #getGroups()} without chain call utility. */
	protected final void internalSetGroups(java.util.List<? extends de.haumacher.imageServer.shared.model.DuplicateGroup> value) {
		if (value == null) throw new IllegalArgumentException("Property 'groups' cannot be null.");
		_groups.clear();
		_groups.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getGroups()} list.
	 */
	public de.haumacher.imageServer.shared.model.DuplicateList addGroup(de.haumacher.imageServer.shared.model.DuplicateGroup value) {
		internalAddGroup(value);
		return this;
	}

	/** Implementation of {@link #addGroup(de.haumacher.imageServer.shared.model.DuplicateGroup)} without chain call utility. */
	protected final void internalAddGroup(de.haumacher.imageServer.shared.model.DuplicateGroup value) {
		_groups.add(value);
	}

	/**
	 * Removes a value from the {@link #getGroups()} list.
	 */
	public final void removeGroup(de.haumacher.imageServer.shared.model.DuplicateGroup value) {
		_groups.remove(value);
	}

	/**
	 * How far the space's hash index has got, see issue #118: while it is incomplete, a copy in a
	 * folder not indexed yet is simply not known, and the list is what is known so far.
	 */
	public final de.haumacher.imageServer.shared.model.IndexProgress getIndexed() {
		return _indexed;
	}

	/**
	 * @see #getIndexed()
	 */
	public de.haumacher.imageServer.shared.model.DuplicateList setIndexed(de.haumacher.imageServer.shared.model.IndexProgress value) {
		internalSetIndexed(value);
		return this;
	}

	/** Internal setter for {@link #getIndexed()} without chain call utility. */
	protected final void internalSetIndexed(de.haumacher.imageServer.shared.model.IndexProgress value) {
		_indexed = value;
	}

	/**
	 * Checks, whether {@link #getIndexed()} has a value.
	 */
	public final boolean hasIndexed() {
		return _indexed != null;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.DuplicateList readDuplicateList(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.DuplicateList result = new de.haumacher.imageServer.shared.model.DuplicateList();
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
		out.name(GROUPS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.DuplicateGroup x : getGroups()) {
			x.writeTo(out);
		}
		out.endArray();
		if (hasIndexed()) {
			out.name(INDEXED__PROP);
			getIndexed().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case GROUPS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addGroup(de.haumacher.imageServer.shared.model.DuplicateGroup.readDuplicateGroup(in));
				}
				in.endArray();
			}
			break;
			case INDEXED__PROP: setIndexed(de.haumacher.imageServer.shared.model.IndexProgress.readIndexProgress(in)); break;
			default: super.readField(in, field);
		}
	}

}
