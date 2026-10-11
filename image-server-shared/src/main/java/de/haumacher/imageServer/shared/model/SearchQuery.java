package de.haumacher.imageServer.shared.model;

/**
 * A search over the photographs of a space, see issue #227: what a saved search
 * ({@link AlbumKind#SEARCH}) stores in its sidecar and what the search view sends.
 *
 * <p>
 * <b>A persisted format.</b> The meaning of every criterion is frozen once a build writing it has
 * shipped. A change of meaning, and a new field of an existing criterion, raises {@link #getVersion()};
 * a new kind of criterion is a new message. A build reading a query of a {@link #getVersion()} it does
 * not know, or a criterion it does not know, refuses to evaluate it and says so (&quot;This search
 * was saved by a newer version&hellip;&quot;) &mdash; it never answers a silently narrower or wider
 * result.
 * </p>
 */
public class SearchQuery extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchQuery} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchQuery create() {
		return new de.haumacher.imageServer.shared.model.SearchQuery();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchQuery} type in JSON format. */
	public static final String SEARCH_QUERY__TYPE = "SearchQuery";

	/** @see #getVersion() */
	private static final String VERSION__PROP = "version";

	/** @see #getRoot() */
	private static final String ROOT__PROP = "root";

	/** @see #getGroupBy() */
	private static final String GROUP_BY__PROP = "groupBy";

	private int _version = 0;

	private de.haumacher.imageServer.shared.model.SearchCriterion _root = null;

	private final java.util.List<de.haumacher.imageServer.shared.model.SearchGroup> _groupBy = new java.util.ArrayList<>();

	/**
	 * Creates a {@link SearchQuery} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchQuery#create()
	 */
	protected SearchQuery() {
		super();
	}

	/**
	 * The version of the format the query was written in; <code>1</code> in this build. A query
	 * without a version (<code>0</code>) is read as version <code>1</code>.
	 */
	public final int getVersion() {
		return _version;
	}

	/**
	 * @see #getVersion()
	 */
	public de.haumacher.imageServer.shared.model.SearchQuery setVersion(int value) {
		internalSetVersion(value);
		return this;
	}

	/** Internal setter for {@link #getVersion()} without chain call utility. */
	protected final void internalSetVersion(int value) {
		_version = value;
	}

	/**
	 * The condition a photograph must meet; an empty {@link SearchAnd} for every photograph.
	 *
	 * <p>
	 * Never absent: a criterion of a newer build is read as nothing, so an absent root (like an
		 * absent member of a {@link SearchAnd}, {@link SearchOr} or {@link SearchNot}) is refused as
	 * unknown rather than read as "everything".
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.SearchCriterion getRoot() {
		return _root;
	}

	/**
	 * @see #getRoot()
	 */
	public de.haumacher.imageServer.shared.model.SearchQuery setRoot(de.haumacher.imageServer.shared.model.SearchCriterion value) {
		internalSetRoot(value);
		return this;
	}

	/** Internal setter for {@link #getRoot()} without chain call utility. */
	protected final void internalSetRoot(de.haumacher.imageServer.shared.model.SearchCriterion value) {
		_root = value;
	}

	/**
	 * Checks, whether {@link #getRoot()} has a value.
	 */
	public final boolean hasRoot() {
		return _root != null;
	}

	/**
	 * The keys the photographs found are grouped by, in order, see issue #244: the first makes the
	 * headings, the second the subheadings; empty for the plain result by date.
	 *
	 * <p>
	 * Up to two are honoured, and the app offers at most two. Presentation alone: the server stores
	 * it with the query of a saved search and evaluates nothing of it &mdash; the headings are
	 * derived by the app on every open and never stored. Not a criterion, so a build that does not
	 * know it answers the same photographs, by date, and {@link #getVersion()} stays.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.SearchGroup> getGroupBy() {
		return _groupBy;
	}

	/**
	 * @see #getGroupBy()
	 */
	public de.haumacher.imageServer.shared.model.SearchQuery setGroupBy(java.util.List<? extends de.haumacher.imageServer.shared.model.SearchGroup> value) {
		internalSetGroupBy(value);
		return this;
	}

	/** Internal setter for {@link #getGroupBy()} without chain call utility. */
	protected final void internalSetGroupBy(java.util.List<? extends de.haumacher.imageServer.shared.model.SearchGroup> value) {
		if (value == null) throw new IllegalArgumentException("Property 'groupBy' cannot be null.");
		_groupBy.clear();
		_groupBy.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getGroupBy()} list.
	 */
	public de.haumacher.imageServer.shared.model.SearchQuery addGroupBy(de.haumacher.imageServer.shared.model.SearchGroup value) {
		internalAddGroupBy(value);
		return this;
	}

	/** Implementation of {@link #addGroupBy(de.haumacher.imageServer.shared.model.SearchGroup)} without chain call utility. */
	protected final void internalAddGroupBy(de.haumacher.imageServer.shared.model.SearchGroup value) {
		_groupBy.add(value);
	}

	/**
	 * Removes a value from the {@link #getGroupBy()} list.
	 */
	public final void removeGroupBy(de.haumacher.imageServer.shared.model.SearchGroup value) {
		_groupBy.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchQuery readSearchQuery(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchQuery result = new de.haumacher.imageServer.shared.model.SearchQuery();
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
		out.name(VERSION__PROP);
		out.value(getVersion());
		if (hasRoot()) {
			out.name(ROOT__PROP);
			getRoot().writeTo(out);
		}
		out.name(GROUP_BY__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.SearchGroup x : getGroupBy()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case VERSION__PROP: setVersion(in.nextInt()); break;
			case ROOT__PROP: setRoot(de.haumacher.imageServer.shared.model.SearchCriterion.readSearchCriterion(in)); break;
			case GROUP_BY__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addGroupBy(de.haumacher.imageServer.shared.model.SearchGroup.readSearchGroup(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
