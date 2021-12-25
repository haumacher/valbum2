package de.haumacher.imageServer.shared.model;

/**
 * Part of an album that can be represented as an image.
 */
public abstract class AbstractImage<S extends AbstractImage<S>> extends AlbumPart<S> {

	/** Visitor interface for the {@link AbstractImage} hierarchy.*/
	public interface Visitor<R,A,E extends Throwable> {

		/** Visit case for {@link ImageGroup}.*/
		R visit(ImageGroup self, A arg) throws E;

		/** Visit case for {@link ImagePart}.*/
		R visit(ImagePart self, A arg) throws E;

	}

	/** @see #getPrevious() */
	private static final String PREVIOUS = "previous";

	/** @see #getNext() */
	private static final String NEXT = "next";

	/** @see #getHome() */
	private static final String HOME = "home";

	/** @see #getEnd() */
	private static final String END = "end";

	private transient AbstractImage<?> _previous = null;

	private transient AbstractImage<?> _next = null;

	private transient AbstractImage<?> _home = null;

	private transient AbstractImage<?> _end = null;

	/**
	 * Creates a {@link AbstractImage} instance.
	 */
	protected AbstractImage() {
		super();
	}

	/**
	 * The previous image in the {@link #getOwner()}.
	 */
	public final AbstractImage<?> getPrevious() {
		return _previous;
	}

	/**
	 * @see #getPrevious()
	 */
	public final S setPrevious(AbstractImage<?> value) {
		_previous = value;
		return self();
	}

	/**
	 * Checks, whether {@link #getPrevious()} has a value.
	 */
	public final boolean hasPrevious() {
		return _previous != null;
	}

	/**
	 * The next image in the {@link #getOwner()}.
	 */
	public final AbstractImage<?> getNext() {
		return _next;
	}

	/**
	 * @see #getNext()
	 */
	public final S setNext(AbstractImage<?> value) {
		_next = value;
		return self();
	}

	/**
	 * Checks, whether {@link #getNext()} has a value.
	 */
	public final boolean hasNext() {
		return _next != null;
	}

	/**
	 * The first image of the {@link #getOwner()}.
	 */
	public final AbstractImage<?> getHome() {
		return _home;
	}

	/**
	 * @see #getHome()
	 */
	public final S setHome(AbstractImage<?> value) {
		_home = value;
		return self();
	}

	/**
	 * Checks, whether {@link #getHome()} has a value.
	 */
	public final boolean hasHome() {
		return _home != null;
	}

	/**
	 * The last image of the {@link #getOwner()}.
	 */
	public final AbstractImage<?> getEnd() {
		return _end;
	}

	/**
	 * @see #getEnd()
	 */
	public final S setEnd(AbstractImage<?> value) {
		_end = value;
		return self();
	}

	/**
	 * Checks, whether {@link #getEnd()} has a value.
	 */
	public final boolean hasEnd() {
		return _end != null;
	}

	@Override
	public abstract String jsonType();

	/** Reads a new instance from the given reader. */
	public static AbstractImage<?> readAbstractImage(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		AbstractImage<?> result;
		in.beginArray();
		String type = in.nextString();
		switch (type) {
			case ImageGroup.IMAGE_GROUP__TYPE: result = de.haumacher.imageServer.shared.model.ImageGroup.readImageGroup(in); break;
			case ImagePart.IMAGE_PART__TYPE: result = de.haumacher.imageServer.shared.model.ImagePart.readImagePart(in); break;
			default: in.skipValue(); result = null; break;
		}
		in.endArray();
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			default: super.readField(in, field);
		}
	}

	/** Accepts the given visitor. */
	public abstract <R,A,E extends Throwable> R visit(Visitor<R,A,E> v, A arg) throws E;


	@Override
	public final <R,A,E extends Throwable> R visit(AlbumPart.Visitor<R,A,E> v, A arg) throws E {
		return visit((Visitor<R,A,E>) v, arg);
	}

}
