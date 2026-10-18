package de.haumacher.imageServer.shared.model;

/**
 * The photograph looks like the given one, see issue #257: "More like this", the content search
 * with a photograph as its query.
 *
 * <p>
 * It ranks like a {@link SearchContent}: the photographs are answered by how near the vector of
 * their picture is to the one of the given photograph (a video by its poster), the nearest first
 * ({@link ImagePart#getScore()}), those too far from it cut, the photograph itself never. It stands
 * where a {@link SearchContent} may stand, and <b>a query ranks once</b>: a {@link SearchSimilar}
 * next to a {@link SearchContent} is refused with a sentence (a text and a photograph are two
 * questions, and no weighting of the two is meant), as is a second one. The text of a search
 * holding it is a {@link SearchText}, which narrows.
 * </p>
 *
 * <p>
 * The reference is a {@link PhotoRef}, as a part of a collection holds it: the path relative to the
 * space root is what the app sends, the content hash is filled in by the server when a saved search
 * stores it, so that the search still finds its photograph after a move or a rename. A saved search
 * stores the reference, never a vector. Where the photograph is gone, or the caller may not see it,
 * the search answers nothing and {@link SearchProblem#PHOTO_NOT_FOUND} &mdash; the same for both,
 * and its reference left out of the query answered; where it has no vector yet,
 * {@link SearchProblem#PHOTO_NOT_DESCRIBED}. The vector is the one the index holds: no embedder is
 * asked.
 * </p>
 */
public class SearchSimilar extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchSimilar} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchSimilar create() {
		return new de.haumacher.imageServer.shared.model.SearchSimilar();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchSimilar} type in JSON format. */
	public static final String SEARCH_SIMILAR__TYPE = "SearchSimilar";

	/** @see #getRef() */
	private static final String REF__PROP = "ref";

	private de.haumacher.imageServer.shared.model.PhotoRef _ref = null;

	/**
	 * Creates a {@link SearchSimilar} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchSimilar#create()
	 */
	protected SearchSimilar() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_SIMILAR;
	}

	/**
	 * The photograph to compare with; never absent, see above.
	 */
	public final de.haumacher.imageServer.shared.model.PhotoRef getRef() {
		return _ref;
	}

	/**
	 * @see #getRef()
	 */
	public de.haumacher.imageServer.shared.model.SearchSimilar setRef(de.haumacher.imageServer.shared.model.PhotoRef value) {
		internalSetRef(value);
		return this;
	}

	/** Internal setter for {@link #getRef()} without chain call utility. */
	protected final void internalSetRef(de.haumacher.imageServer.shared.model.PhotoRef value) {
		_ref = value;
	}

	/**
	 * Checks, whether {@link #getRef()} has a value.
	 */
	public final boolean hasRef() {
		return _ref != null;
	}

	@Override
	public String jsonType() {
		return SEARCH_SIMILAR__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchSimilar readSearchSimilar(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchSimilar result = new de.haumacher.imageServer.shared.model.SearchSimilar();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		if (hasRef()) {
			out.name(REF__PROP);
			getRef().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case REF__PROP: setRef(de.haumacher.imageServer.shared.model.PhotoRef.readPhotoRef(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
