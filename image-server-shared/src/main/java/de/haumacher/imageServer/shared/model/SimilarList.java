package de.haumacher.imageServer.shared.model;

/**
 * Photographs of the same scenery in several albums of a space, see issue #257 and
 * <code>&lt;data&gt;/?type=similar</code>: the overview beside the {@link DuplicateList} of #220.
 *
 * <p>
 * Three levels of "the same" are kept apart: the same content (a {@link DuplicateList}), the same
 * recording with other metadata (<code>SameRecording</code>, #167) and a similar picture, which
 * this is. Read-only and derived from the vectors of the content search: two photographs are
 * linked where the cosine of their pictures' vectors is at least the scenery threshold
 * (<code>ContentSearch#SCENERY_THRESHOLD</code>), among the nearest each has in the space, and a
 * group is what is linked. Two copies of one content are never linked to each other: they are a
 * duplicate, not a similar shot. Every photograph is one the caller may see, exactly as its album
 * would show it to them; a group of which the caller sees fewer than two contents, or photographs
 * in fewer than two albums, is no group for them.
 * </p>
 */
public class SimilarList extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SimilarList} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SimilarList create() {
		return new de.haumacher.imageServer.shared.model.SimilarList();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SimilarList} type in JSON format. */
	public static final String SIMILAR_LIST__TYPE = "SimilarList";

	/** @see #getGroups() */
	private static final String GROUPS__PROP = "groups";

	/** @see #getDescribed() */
	private static final String DESCRIBED__PROP = "described";

	/** @see #isContent() */
	private static final String CONTENT__PROP = "content";

	private final java.util.List<de.haumacher.imageServer.shared.model.SimilarGroup> _groups = new java.util.ArrayList<>();

	private de.haumacher.imageServer.shared.model.IndexProgress _described = null;

	private boolean _content = false;

	/**
	 * Creates a {@link SimilarList} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SimilarList#create()
	 */
	protected SimilarList() {
		super();
	}

	/**
	 * The groups, the most photographs first, then the newest, then by the path of the first.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.SimilarGroup> getGroups() {
		return _groups;
	}

	/**
	 * @see #getGroups()
	 */
	public de.haumacher.imageServer.shared.model.SimilarList setGroups(java.util.List<? extends de.haumacher.imageServer.shared.model.SimilarGroup> value) {
		internalSetGroups(value);
		return this;
	}

	/** Internal setter for {@link #getGroups()} without chain call utility. */
	protected final void internalSetGroups(java.util.List<? extends de.haumacher.imageServer.shared.model.SimilarGroup> value) {
		if (value == null) throw new IllegalArgumentException("Property 'groups' cannot be null.");
		_groups.clear();
		_groups.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getGroups()} list.
	 */
	public de.haumacher.imageServer.shared.model.SimilarList addGroup(de.haumacher.imageServer.shared.model.SimilarGroup value) {
		internalAddGroup(value);
		return this;
	}

	/** Implementation of {@link #addGroup(de.haumacher.imageServer.shared.model.SimilarGroup)} without chain call utility. */
	protected final void internalAddGroup(de.haumacher.imageServer.shared.model.SimilarGroup value) {
		_groups.add(value);
	}

	/**
	 * Removes a value from the {@link #getGroups()} list.
	 */
	public final void removeGroup(de.haumacher.imageServer.shared.model.SimilarGroup value) {
		_groups.remove(value);
	}

	/**
	 * How many of the photographs the caller may see have been described for the content search
	 * ({@link IndexProgress#getDone()}) of how many there are ({@link IndexProgress#getTotal()}): while some
	 * are not, a photograph not described yet is in no group, and the list is what is known so far.
	 */
	public final de.haumacher.imageServer.shared.model.IndexProgress getDescribed() {
		return _described;
	}

	/**
	 * @see #getDescribed()
	 */
	public de.haumacher.imageServer.shared.model.SimilarList setDescribed(de.haumacher.imageServer.shared.model.IndexProgress value) {
		internalSetDescribed(value);
		return this;
	}

	/** Internal setter for {@link #getDescribed()} without chain call utility. */
	protected final void internalSetDescribed(de.haumacher.imageServer.shared.model.IndexProgress value) {
		_described = value;
	}

	/**
	 * Checks, whether {@link #getDescribed()} has a value.
	 */
	public final boolean hasDescribed() {
		return _described != null;
	}

	/**
	 * Whether the space has the content search switched on; where it is off there are no vectors to
	 * compare, and the list is empty.
	 */
	public final boolean isContent() {
		return _content;
	}

	/**
	 * @see #isContent()
	 */
	public de.haumacher.imageServer.shared.model.SimilarList setContent(boolean value) {
		internalSetContent(value);
		return this;
	}

	/** Internal setter for {@link #isContent()} without chain call utility. */
	protected final void internalSetContent(boolean value) {
		_content = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SimilarList readSimilarList(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SimilarList result = new de.haumacher.imageServer.shared.model.SimilarList();
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
		for (de.haumacher.imageServer.shared.model.SimilarGroup x : getGroups()) {
			x.writeTo(out);
		}
		out.endArray();
		if (hasDescribed()) {
			out.name(DESCRIBED__PROP);
			getDescribed().writeTo(out);
		}
		out.name(CONTENT__PROP);
		out.value(isContent());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case GROUPS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addGroup(de.haumacher.imageServer.shared.model.SimilarGroup.readSimilarGroup(in));
				}
				in.endArray();
			}
			break;
			case DESCRIBED__PROP: setDescribed(de.haumacher.imageServer.shared.model.IndexProgress.readIndexProgress(in)); break;
			case CONTENT__PROP: setContent(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
