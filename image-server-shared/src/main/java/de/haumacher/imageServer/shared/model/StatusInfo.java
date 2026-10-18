package de.haumacher.imageServer.shared.model;

/**
 * Everything the server of a space is doing, waiting for and failing at, for the status page of
 * issue #305: <code>&lt;data&gt;/?type=status</code>.
 *
 * <p>
 * Derived from what the server is doing at the moment it is asked; only the "seen" marks of the
 * {@link StatusProblem problems} are stored (in the index of the space, shared by its
 * administrators). Answered to every member, and refused to a share link and an anonymous caller:
 * a member who is no administrator gets {@link #isAdmin()} <code>false</code> and nothing else, because
 * what fails names albums and files the member may not be allowed to see, and the paths on the
 * server are the administrator's business. Nothing secret is in it: no key, no token.
 * </p>
 */
public class StatusInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.StatusInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.StatusInfo create() {
		return new de.haumacher.imageServer.shared.model.StatusInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.StatusInfo} type in JSON format. */
	public static final String STATUS_INFO__TYPE = "StatusInfo";

	/** @see #isAdmin() */
	private static final String ADMIN__PROP = "admin";

	/** @see #getCatchUp() */
	private static final String CATCH_UP__PROP = "catchUp";

	/** @see #getSteps() */
	private static final String STEPS__PROP = "steps";

	/** @see #getIndex() */
	private static final String INDEX__PROP = "index";

	/** @see #getPlacesLoading() */
	private static final String PLACES_LOADING__PROP = "placesLoading";

	/** @see #getProblems() */
	private static final String PROBLEMS__PROP = "problems";

	/** @see #getRoot() */
	private static final String ROOT__PROP = "root";

	private boolean _admin = false;

	private de.haumacher.imageServer.shared.model.CatchUpStatus _catchUp = null;

	private final java.util.List<de.haumacher.imageServer.shared.model.StatusStep> _steps = new java.util.ArrayList<>();

	private de.haumacher.imageServer.shared.model.IndexProgress _index = null;

	private final java.util.List<de.haumacher.imageServer.shared.model.StatusKey> _placesLoading = new java.util.ArrayList<>();

	private final java.util.List<de.haumacher.imageServer.shared.model.StatusProblem> _problems = new java.util.ArrayList<>();

	private String _root = "";

	/**
	 * Creates a {@link StatusInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.StatusInfo#create()
	 */
	protected StatusInfo() {
		super();
	}

	/**
	 * Whether the caller administers the space; every other field is empty where not.
	 */
	public final boolean isAdmin() {
		return _admin;
	}

	/**
	 * @see #isAdmin()
	 */
	public de.haumacher.imageServer.shared.model.StatusInfo setAdmin(boolean value) {
		internalSetAdmin(value);
		return this;
	}

	/** Internal setter for {@link #isAdmin()} without chain call utility. */
	protected final void internalSetAdmin(boolean value) {
		_admin = value;
	}

	/**
	 * The background work of the space, as <code>?type=catch-up</code> answers it.
	 */
	public final de.haumacher.imageServer.shared.model.CatchUpStatus getCatchUp() {
		return _catchUp;
	}

	/**
	 * @see #getCatchUp()
	 */
	public de.haumacher.imageServer.shared.model.StatusInfo setCatchUp(de.haumacher.imageServer.shared.model.CatchUpStatus value) {
		internalSetCatchUp(value);
		return this;
	}

	/** Internal setter for {@link #getCatchUp()} without chain call utility. */
	protected final void internalSetCatchUp(de.haumacher.imageServer.shared.model.CatchUpStatus value) {
		_catchUp = value;
	}

	/**
	 * Checks, whether {@link #getCatchUp()} has a value.
	 */
	public final boolean hasCatchUp() {
		return _catchUp != null;
	}

	/**
	 * How many albums each photo step of the background is done with, in the order the steps run;
	 * counted since the server started, of {@link CatchUpStatus#getAlbumsTotal()}.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.StatusStep> getSteps() {
		return _steps;
	}

	/**
	 * @see #getSteps()
	 */
	public de.haumacher.imageServer.shared.model.StatusInfo setSteps(java.util.List<? extends de.haumacher.imageServer.shared.model.StatusStep> value) {
		internalSetSteps(value);
		return this;
	}

	/** Internal setter for {@link #getSteps()} without chain call utility. */
	protected final void internalSetSteps(java.util.List<? extends de.haumacher.imageServer.shared.model.StatusStep> value) {
		if (value == null) throw new IllegalArgumentException("Property 'steps' cannot be null.");
		_steps.clear();
		_steps.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getSteps()} list.
	 */
	public de.haumacher.imageServer.shared.model.StatusInfo addStep(de.haumacher.imageServer.shared.model.StatusStep value) {
		internalAddStep(value);
		return this;
	}

	/** Implementation of {@link #addStep(de.haumacher.imageServer.shared.model.StatusStep)} without chain call utility. */
	protected final void internalAddStep(de.haumacher.imageServer.shared.model.StatusStep value) {
		_steps.add(value);
	}

	/**
	 * Removes a value from the {@link #getSteps()} list.
	 */
	public final void removeStep(de.haumacher.imageServer.shared.model.StatusStep value) {
		_steps.remove(value);
	}

	/**
	 * How far the index of the photos' contents (their hashes, which the duplicates and the
	 * camera-roll sync wait for) has got.
	 */
	public final de.haumacher.imageServer.shared.model.IndexProgress getIndex() {
		return _index;
	}

	/**
	 * @see #getIndex()
	 */
	public de.haumacher.imageServer.shared.model.StatusInfo setIndex(de.haumacher.imageServer.shared.model.IndexProgress value) {
		internalSetIndex(value);
		return this;
	}

	/** Internal setter for {@link #getIndex()} without chain call utility. */
	protected final void internalSetIndex(de.haumacher.imageServer.shared.model.IndexProgress value) {
		_index = value;
	}

	/**
	 * Checks, whether {@link #getIndex()} has a value.
	 */
	public final boolean hasIndex() {
		return _index != null;
	}

	/**
	 * The files of place names being downloaded or waiting for their download, by name.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.StatusKey> getPlacesLoading() {
		return _placesLoading;
	}

	/**
	 * @see #getPlacesLoading()
	 */
	public de.haumacher.imageServer.shared.model.StatusInfo setPlacesLoading(java.util.List<? extends de.haumacher.imageServer.shared.model.StatusKey> value) {
		internalSetPlacesLoading(value);
		return this;
	}

	/** Internal setter for {@link #getPlacesLoading()} without chain call utility. */
	protected final void internalSetPlacesLoading(java.util.List<? extends de.haumacher.imageServer.shared.model.StatusKey> value) {
		if (value == null) throw new IllegalArgumentException("Property 'placesLoading' cannot be null.");
		_placesLoading.clear();
		_placesLoading.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getPlacesLoading()} list.
	 */
	public de.haumacher.imageServer.shared.model.StatusInfo addPlacesLoading(de.haumacher.imageServer.shared.model.StatusKey value) {
		internalAddPlacesLoading(value);
		return this;
	}

	/** Implementation of {@link #addPlacesLoading(de.haumacher.imageServer.shared.model.StatusKey)} without chain call utility. */
	protected final void internalAddPlacesLoading(de.haumacher.imageServer.shared.model.StatusKey value) {
		_placesLoading.add(value);
	}

	/**
	 * Removes a value from the {@link #getPlacesLoading()} list.
	 */
	public final void removePlacesLoading(de.haumacher.imageServer.shared.model.StatusKey value) {
		_placesLoading.remove(value);
	}

	/**
	 * Every problem of the server, merged by cause: the background's failures, the folders the server
	 * cannot write, the embedder that does not answer, faces or videos this machine cannot make, a
	 * download of place names that failed. A problem is gone when it is fixed.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.StatusProblem> getProblems() {
		return _problems;
	}

	/**
	 * @see #getProblems()
	 */
	public de.haumacher.imageServer.shared.model.StatusInfo setProblems(java.util.List<? extends de.haumacher.imageServer.shared.model.StatusProblem> value) {
		internalSetProblems(value);
		return this;
	}

	/** Internal setter for {@link #getProblems()} without chain call utility. */
	protected final void internalSetProblems(java.util.List<? extends de.haumacher.imageServer.shared.model.StatusProblem> value) {
		if (value == null) throw new IllegalArgumentException("Property 'problems' cannot be null.");
		_problems.clear();
		_problems.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getProblems()} list.
	 */
	public de.haumacher.imageServer.shared.model.StatusInfo addProblem(de.haumacher.imageServer.shared.model.StatusProblem value) {
		internalAddProblem(value);
		return this;
	}

	/** Implementation of {@link #addProblem(de.haumacher.imageServer.shared.model.StatusProblem)} without chain call utility. */
	protected final void internalAddProblem(de.haumacher.imageServer.shared.model.StatusProblem value) {
		_problems.add(value);
	}

	/**
	 * Removes a value from the {@link #getProblems()} list.
	 */
	public final void removeProblem(de.haumacher.imageServer.shared.model.StatusProblem value) {
		_problems.remove(value);
	}

	/**
	 * The folder of the space on the server, absolute: where the paths of the problems are below.
	 */
	public final String getRoot() {
		return _root;
	}

	/**
	 * @see #getRoot()
	 */
	public de.haumacher.imageServer.shared.model.StatusInfo setRoot(String value) {
		internalSetRoot(value);
		return this;
	}

	/** Internal setter for {@link #getRoot()} without chain call utility. */
	protected final void internalSetRoot(String value) {
		_root = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.StatusInfo readStatusInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.StatusInfo result = new de.haumacher.imageServer.shared.model.StatusInfo();
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
		out.name(ADMIN__PROP);
		out.value(isAdmin());
		if (hasCatchUp()) {
			out.name(CATCH_UP__PROP);
			getCatchUp().writeTo(out);
		}
		out.name(STEPS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.StatusStep x : getSteps()) {
			x.writeTo(out);
		}
		out.endArray();
		if (hasIndex()) {
			out.name(INDEX__PROP);
			getIndex().writeTo(out);
		}
		out.name(PLACES_LOADING__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.StatusKey x : getPlacesLoading()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(PROBLEMS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.StatusProblem x : getProblems()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(ROOT__PROP);
		out.value(getRoot());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ADMIN__PROP: setAdmin(in.nextBoolean()); break;
			case CATCH_UP__PROP: setCatchUp(de.haumacher.imageServer.shared.model.CatchUpStatus.readCatchUpStatus(in)); break;
			case STEPS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addStep(de.haumacher.imageServer.shared.model.StatusStep.readStatusStep(in));
				}
				in.endArray();
			}
			break;
			case INDEX__PROP: setIndex(de.haumacher.imageServer.shared.model.IndexProgress.readIndexProgress(in)); break;
			case PLACES_LOADING__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addPlacesLoading(de.haumacher.imageServer.shared.model.StatusKey.readStatusKey(in));
				}
				in.endArray();
			}
			break;
			case PROBLEMS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addProblem(de.haumacher.imageServer.shared.model.StatusProblem.readStatusProblem(in));
				}
				in.endArray();
			}
			break;
			case ROOT__PROP: setRoot(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
