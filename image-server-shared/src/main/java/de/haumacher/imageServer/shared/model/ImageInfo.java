package de.haumacher.imageServer.shared.model;

public class ImageInfo extends Resource {

	public enum Kind {

		IMAGE,

		VIDEO,

		;

		/** Writes this instance to the given output. */
		public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
			out.value(name());
		}

		/** Reads a new instance from the given reader. */
		public static Kind readKind(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
			return valueOf(in.nextString());
		}

		/** Writes this instance to the given binary output. */
		public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
			switch (this) {
				case IMAGE: out.value(1); break;
				case VIDEO: out.value(2); break;
				default: out.value(0);
			}
		}

		/** Reads a new instance from the given binary reader. */
		public static Kind readKind(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
			switch (in.nextInt()) {
				case 1: return IMAGE;
				case 2: return VIDEO;
				default: return IMAGE;
			}
		}
	}

	/**
	 * Creates a {@link ImageInfo} instance.
	 */
	public static ImageInfo create() {
		return new ImageInfo();
	}

	/**
	 * Creates a {@link ImageInfo} instance.
	 *
	 * @see #create()
	 */
	protected ImageInfo() {
		super();
	}

	private transient AlbumInfo _owner = null;

	private int _depth = 0;

	private Kind _kind = Kind.IMAGE;

	private String _name = "";

	private long _date = 0L;

	private int _width = 0;

	private int _height = 0;

	private String _comment = "";

	private String _previous = "";

	private String _next = "";

	private String _home = "";

	private String _end = "";

	private int _rating = 0;

	public final AlbumInfo getOwner() {
		return _owner;
	}

	/**
	 * @see #getOwner()
	 */
	public final ImageInfo setOwner(AlbumInfo value) {
		_owner = value;
		return this;
	}

	/**
	 * Checks, whether {@link #getOwner()} has a value.
	 */
	public final boolean hasOwner() {
		return _owner != null;
	}

	public final int getDepth() {
		return _depth;
	}

	/**
	 * @see #getDepth()
	 */
	public final ImageInfo setDepth(int value) {
		_depth = value;
		return this;
	}

	public final Kind getKind() {
		return _kind;
	}

	/**
	 * @see #getKind()
	 */
	public final ImageInfo setKind(Kind value) {
		_kind = value;
		return this;
	}

	/**
	 * Checks, whether {@link #getKind()} has a value.
	 */
	public final boolean hasKind() {
		return _kind != null;
	}

	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public final ImageInfo setName(String value) {
		_name = value;
		return this;
	}

	public final long getDate() {
		return _date;
	}

	/**
	 * @see #getDate()
	 */
	public final ImageInfo setDate(long value) {
		_date = value;
		return this;
	}

	public final int getWidth() {
		return _width;
	}

	/**
	 * @see #getWidth()
	 */
	public final ImageInfo setWidth(int value) {
		_width = value;
		return this;
	}

	public final int getHeight() {
		return _height;
	}

	/**
	 * @see #getHeight()
	 */
	public final ImageInfo setHeight(int value) {
		_height = value;
		return this;
	}

	public final String getComment() {
		return _comment;
	}

	/**
	 * @see #getComment()
	 */
	public final ImageInfo setComment(String value) {
		_comment = value;
		return this;
	}

	public final String getPrevious() {
		return _previous;
	}

	/**
	 * @see #getPrevious()
	 */
	public final ImageInfo setPrevious(String value) {
		_previous = value;
		return this;
	}

	public final String getNext() {
		return _next;
	}

	/**
	 * @see #getNext()
	 */
	public final ImageInfo setNext(String value) {
		_next = value;
		return this;
	}

	public final String getHome() {
		return _home;
	}

	/**
	 * @see #getHome()
	 */
	public final ImageInfo setHome(String value) {
		_home = value;
		return this;
	}

	public final String getEnd() {
		return _end;
	}

	/**
	 * @see #getEnd()
	 */
	public final ImageInfo setEnd(String value) {
		_end = value;
		return this;
	}

	public final int getRating() {
		return _rating;
	}

	/**
	 * @see #getRating()
	 */
	public final ImageInfo setRating(int value) {
		_rating = value;
		return this;
	}

	/** Reads a new instance from the given reader. */
	public static ImageInfo readImageInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		ImageInfo result = new ImageInfo();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	public String jsonType() {
		return "ImageInfo";
	}

	@Override
	public Object get(String field) {
		switch (field) {
			case "owner": return getOwner();
			case "depth": return getDepth();
			case "kind": return getKind();
			case "name": return getName();
			case "date": return getDate();
			case "width": return getWidth();
			case "height": return getHeight();
			case "comment": return getComment();
			case "previous": return getPrevious();
			case "next": return getNext();
			case "home": return getHome();
			case "end": return getEnd();
			case "rating": return getRating();
			default: return super.get(field);
		}
	}

	@Override
	public void set(String field, Object value) {
		switch (field) {
			case "owner": setOwner((AlbumInfo) value); break;
			case "depth": setDepth((int) value); break;
			case "kind": setKind((Kind) value); break;
			case "name": setName((String) value); break;
			case "date": setDate((long) value); break;
			case "width": setWidth((int) value); break;
			case "height": setHeight((int) value); break;
			case "comment": setComment((String) value); break;
			case "previous": setPrevious((String) value); break;
			case "next": setNext((String) value); break;
			case "home": setHome((String) value); break;
			case "end": setEnd((String) value); break;
			case "rating": setRating((int) value); break;
			default: super.set(field, value); break;
		}
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name("depth");
		out.value(getDepth());
		if (hasKind()) {
			out.name("kind");
			getKind().writeTo(out);
		}
		out.name("name");
		out.value(getName());
		out.name("date");
		out.value(getDate());
		out.name("width");
		out.value(getWidth());
		out.name("height");
		out.value(getHeight());
		out.name("comment");
		out.value(getComment());
		out.name("previous");
		out.value(getPrevious());
		out.name("next");
		out.value(getNext());
		out.name("home");
		out.value(getHome());
		out.name("end");
		out.value(getEnd());
		out.name("rating");
		out.value(getRating());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case "depth": setDepth(in.nextInt()); break;
			case "kind": setKind(Kind.readKind(in)); break;
			case "name": setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "date": setDate(in.nextLong()); break;
			case "width": setWidth(in.nextInt()); break;
			case "height": setHeight(in.nextInt()); break;
			case "comment": setComment(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "previous": setPrevious(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "next": setNext(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "home": setHome(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "end": setEnd(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "rating": setRating(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public int typeId() {
		return 1;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(2);
		out.value(getDepth());
		if (hasKind()) {
			out.name(3);
			getKind().writeTo(out);
		}
		out.name(4);
		out.value(getName());
		out.name(5);
		out.value(getDate());
		out.name(6);
		out.value(getWidth());
		out.name(7);
		out.value(getHeight());
		out.name(8);
		out.value(getComment());
		out.name(9);
		out.value(getPrevious());
		out.name(10);
		out.value(getNext());
		out.name(11);
		out.value(getHome());
		out.name(12);
		out.value(getEnd());
		out.name(13);
		out.value(getRating());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.binary.DataReader in, int field) throws java.io.IOException {
		switch (field) {
			case 2: setDepth(in.nextInt()); break;
			case 3: setKind(Kind.readKind(in)); break;
			case 4: setName(in.nextString()); break;
			case 5: setDate(in.nextLong()); break;
			case 6: setWidth(in.nextInt()); break;
			case 7: setHeight(in.nextInt()); break;
			case 8: setComment(in.nextString()); break;
			case 9: setPrevious(in.nextString()); break;
			case 10: setNext(in.nextString()); break;
			case 11: setHome(in.nextString()); break;
			case 12: setEnd(in.nextString()); break;
			case 13: setRating(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

	/** Reads a new instance from the given reader. */
	public static ImageInfo readImageInfo(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		ImageInfo result = new ImageInfo();
		while (in.hasNext()) {
			int field = in.nextName();
			result.readField(in, field);
		}
		in.endObject();
		return result;
	}

	@Override
	public <R,A> R visit(Resource.Visitor<R,A> v, A arg) {
		return v.visit(this, arg);
	}

}
