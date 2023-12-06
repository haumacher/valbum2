package de.haumacher.imageServer.shared.model;

/**
 * Part of an album that can be represented as an image.
 */
public abstract class AbstractImage extends AlbumPart {

	/** Visitor interface for the {@link de.haumacher.imageServer.shared.model.AbstractImage} hierarchy.*/
	public interface Visitor<R,A,E extends Throwable> {

		/** Visit case for {@link de.haumacher.imageServer.shared.model.ImageGroup}.*/
		R visit(de.haumacher.imageServer.shared.model.ImageGroup self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.ImagePart}.*/
		R visit(de.haumacher.imageServer.shared.model.ImagePart self, A arg) throws E;

	}

	private transient de.haumacher.imageServer.shared.model.AbstractImage _previous = null;

	private transient de.haumacher.imageServer.shared.model.AbstractImage _next = null;

	private transient de.haumacher.imageServer.shared.model.AbstractImage _home = null;

	private transient de.haumacher.imageServer.shared.model.AbstractImage _end = null;

	/**
	 * Creates a {@link AbstractImage} instance.
	 */
	protected AbstractImage() {
		super();
	}

	/**
	 * The previous image in the {@link #getOwner()}.
	 */
	public final de.haumacher.imageServer.shared.model.AbstractImage getPrevious() {
		return _previous;
	}

	/**
	 * @see #getPrevious()
	 */
	public de.haumacher.imageServer.shared.model.AbstractImage setPrevious(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetPrevious(value);
		return this;
	}

	/** Internal setter for {@link #getPrevious()} without chain call utility. */
	protected final void internalSetPrevious(de.haumacher.imageServer.shared.model.AbstractImage value) {
		_previous = value;
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
	public final de.haumacher.imageServer.shared.model.AbstractImage getNext() {
		return _next;
	}

	/**
	 * @see #getNext()
	 */
	public de.haumacher.imageServer.shared.model.AbstractImage setNext(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetNext(value);
		return this;
	}

	/** Internal setter for {@link #getNext()} without chain call utility. */
	protected final void internalSetNext(de.haumacher.imageServer.shared.model.AbstractImage value) {
		_next = value;
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
	public final de.haumacher.imageServer.shared.model.AbstractImage getHome() {
		return _home;
	}

	/**
	 * @see #getHome()
	 */
	public de.haumacher.imageServer.shared.model.AbstractImage setHome(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetHome(value);
		return this;
	}

	/** Internal setter for {@link #getHome()} without chain call utility. */
	protected final void internalSetHome(de.haumacher.imageServer.shared.model.AbstractImage value) {
		_home = value;
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
	public final de.haumacher.imageServer.shared.model.AbstractImage getEnd() {
		return _end;
	}

	/**
	 * @see #getEnd()
	 */
	public de.haumacher.imageServer.shared.model.AbstractImage setEnd(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetEnd(value);
		return this;
	}

	/** Internal setter for {@link #getEnd()} without chain call utility. */
	protected final void internalSetEnd(de.haumacher.imageServer.shared.model.AbstractImage value) {
		_end = value;
	}

	/**
	 * Checks, whether {@link #getEnd()} has a value.
	 */
	public final boolean hasEnd() {
		return _end != null;
	}

	@Override
	public de.haumacher.imageServer.shared.model.AbstractImage setOwner(de.haumacher.imageServer.shared.model.AlbumInfo value) {
		internalSetOwner(value);
		return this;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.AbstractImage readAbstractImage(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.AbstractImage result;
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
	public final <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.AlbumPart.Visitor<R,A,E> v, A arg) throws E {
		return visit((de.haumacher.imageServer.shared.model.AbstractImage.Visitor<R,A,E>) v, arg);
	}

}
