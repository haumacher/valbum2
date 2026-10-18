package de.haumacher.imageServer.shared.model;

/**
 * Request to keep copies of a photograph as references to one of them, sent to the root of the
 * space as <code>?action=keep-as-references</code> (or <code>?action=keep-as-references-check</code>
 * for what it would do, with nothing done), see issue #307.
 *
 * <p>
 * For a photograph that lies as identical files in several albums ({@link DuplicateGroup}): the
 * {@link #getOriginal()} stays where it is, and every one of the {@link #getCopies()} is replaced in its album
 * by a reference to the original, in the copy's place, with the copy's labels and, where it
 * differs from the original's, the copy's description as the reference's own. The copy's file goes
 * into the trash of its album (it is rated as trash there), from where the purge deletes it. The
 * answer is a {@link MoveResult} with one {@link MoveOutcome} per copy, named as asked: the name of
 * the reference in the copy's album, or why that copy was refused.
 * </p>
 */
public class KeepAsReferencesRequest extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.KeepAsReferencesRequest} instance.
	 */
	public static de.haumacher.imageServer.shared.model.KeepAsReferencesRequest create() {
		return new de.haumacher.imageServer.shared.model.KeepAsReferencesRequest();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.KeepAsReferencesRequest} type in JSON format. */
	public static final String KEEP_AS_REFERENCES_REQUEST__TYPE = "KeepAsReferencesRequest";

	/** @see #getOriginal() */
	private static final String ORIGINAL__PROP = "original";

	/** @see #getCopies() */
	private static final String COPIES__PROP = "copies";

	private String _original = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.MoveName> _copies = new java.util.ArrayList<>();

	/**
	 * Creates a {@link KeepAsReferencesRequest} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.KeepAsReferencesRequest#create()
	 */
	protected KeepAsReferencesRequest() {
		super();
	}

	/**
	 * The photograph to keep as the original: the path of its file relative to the caller's space,
	 * <code>&lt;album&gt;/&lt;name&gt;</code> as a {@link DuplicateCopy} names it.
	 */
	public final String getOriginal() {
		return _original;
	}

	/**
	 * @see #getOriginal()
	 */
	public de.haumacher.imageServer.shared.model.KeepAsReferencesRequest setOriginal(String value) {
		internalSetOriginal(value);
		return this;
	}

	/** Internal setter for {@link #getOriginal()} without chain call utility. */
	protected final void internalSetOriginal(String value) {
		_original = value;
	}

	/**
	 * The copies to replace by references, each the path of its file like {@link #getOriginal()}.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.MoveName> getCopies() {
		return _copies;
	}

	/**
	 * @see #getCopies()
	 */
	public de.haumacher.imageServer.shared.model.KeepAsReferencesRequest setCopies(java.util.List<? extends de.haumacher.imageServer.shared.model.MoveName> value) {
		internalSetCopies(value);
		return this;
	}

	/** Internal setter for {@link #getCopies()} without chain call utility. */
	protected final void internalSetCopies(java.util.List<? extends de.haumacher.imageServer.shared.model.MoveName> value) {
		if (value == null) throw new IllegalArgumentException("Property 'copies' cannot be null.");
		_copies.clear();
		_copies.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getCopies()} list.
	 */
	public de.haumacher.imageServer.shared.model.KeepAsReferencesRequest addCopie(de.haumacher.imageServer.shared.model.MoveName value) {
		internalAddCopie(value);
		return this;
	}

	/** Implementation of {@link #addCopie(de.haumacher.imageServer.shared.model.MoveName)} without chain call utility. */
	protected final void internalAddCopie(de.haumacher.imageServer.shared.model.MoveName value) {
		_copies.add(value);
	}

	/**
	 * Removes a value from the {@link #getCopies()} list.
	 */
	public final void removeCopie(de.haumacher.imageServer.shared.model.MoveName value) {
		_copies.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.KeepAsReferencesRequest readKeepAsReferencesRequest(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.KeepAsReferencesRequest result = new de.haumacher.imageServer.shared.model.KeepAsReferencesRequest();
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
		out.name(ORIGINAL__PROP);
		out.value(getOriginal());
		out.name(COPIES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.MoveName x : getCopies()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ORIGINAL__PROP: setOriginal(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case COPIES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addCopie(de.haumacher.imageServer.shared.model.MoveName.readMoveName(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
