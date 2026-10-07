package de.haumacher.imageServer.shared.model;

/**
 * One condition of a {@link SearchQuery}, see issue #227.
 *
 * <p>
 * A tree: {@link SearchAnd}, {@link SearchOr} and {@link SearchNot} combine the conditions below
 * them, every other kind is a leaf asking one thing of a photograph.
 * </p>
 */
public abstract class SearchCriterion extends de.haumacher.msgbuf.data.AbstractDataObject {

	/** Type codes for the {@link de.haumacher.imageServer.shared.model.SearchCriterion} hierarchy. */
	public enum TypeKind {

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchAnd}. */
		SEARCH_AND,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchOr}. */
		SEARCH_OR,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchNot}. */
		SEARCH_NOT,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchPerson}. */
		SEARCH_PERSON,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchDate}. */
		SEARCH_DATE,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchPlace}. */
		SEARCH_PLACE,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchLabel}. */
		SEARCH_LABEL,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchRating}. */
		SEARCH_RATING,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchMedia}. */
		SEARCH_MEDIA,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchText}. */
		SEARCH_TEXT,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchCamera}. */
		SEARCH_CAMERA,

		/** Type literal for {@link de.haumacher.imageServer.shared.model.SearchFolder}. */
		SEARCH_FOLDER,
		;

	}

	/** Visitor interface for the {@link de.haumacher.imageServer.shared.model.SearchCriterion} hierarchy.*/
	public interface Visitor<R,A,E extends Throwable> {

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchAnd}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchAnd self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchOr}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchOr self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchNot}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchNot self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchPerson}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchPerson self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchDate}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchDate self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchPlace}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchPlace self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchLabel}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchLabel self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchRating}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchRating self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchMedia}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchMedia self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchText}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchText self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchCamera}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchCamera self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.SearchFolder}.*/
		R visit(de.haumacher.imageServer.shared.model.SearchFolder self, A arg) throws E;

	}

	/**
	 * Creates a {@link SearchCriterion} instance.
	 */
	protected SearchCriterion() {
		super();
	}

	/** The type code of this instance. */
	public abstract TypeKind kind();

	/** The type identifier for this concrete subtype. */
	public abstract String jsonType();

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchCriterion readSearchCriterion(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchCriterion result;
		in.beginArray();
		String type = in.nextString();
		switch (type) {
			case SearchAnd.SEARCH_AND__TYPE: result = de.haumacher.imageServer.shared.model.SearchAnd.readSearchAnd(in); break;
			case SearchOr.SEARCH_OR__TYPE: result = de.haumacher.imageServer.shared.model.SearchOr.readSearchOr(in); break;
			case SearchNot.SEARCH_NOT__TYPE: result = de.haumacher.imageServer.shared.model.SearchNot.readSearchNot(in); break;
			case SearchPerson.SEARCH_PERSON__TYPE: result = de.haumacher.imageServer.shared.model.SearchPerson.readSearchPerson(in); break;
			case SearchDate.SEARCH_DATE__TYPE: result = de.haumacher.imageServer.shared.model.SearchDate.readSearchDate(in); break;
			case SearchPlace.SEARCH_PLACE__TYPE: result = de.haumacher.imageServer.shared.model.SearchPlace.readSearchPlace(in); break;
			case SearchLabel.SEARCH_LABEL__TYPE: result = de.haumacher.imageServer.shared.model.SearchLabel.readSearchLabel(in); break;
			case SearchRating.SEARCH_RATING__TYPE: result = de.haumacher.imageServer.shared.model.SearchRating.readSearchRating(in); break;
			case SearchMedia.SEARCH_MEDIA__TYPE: result = de.haumacher.imageServer.shared.model.SearchMedia.readSearchMedia(in); break;
			case SearchText.SEARCH_TEXT__TYPE: result = de.haumacher.imageServer.shared.model.SearchText.readSearchText(in); break;
			case SearchCamera.SEARCH_CAMERA__TYPE: result = de.haumacher.imageServer.shared.model.SearchCamera.readSearchCamera(in); break;
			case SearchFolder.SEARCH_FOLDER__TYPE: result = de.haumacher.imageServer.shared.model.SearchFolder.readSearchFolder(in); break;
			default: in.skipValue(); result = null; break;
		}
		in.endArray();
		return result;
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.beginArray();
		out.value(jsonType());
		writeContent(out);
		out.endArray();
	}

	/** Accepts the given visitor. */
	public abstract <R,A,E extends Throwable> R visit(Visitor<R,A,E> v, A arg) throws E;

}
