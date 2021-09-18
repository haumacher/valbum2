package de.haumacher.imageServer.shared.model;

public class ListingInfo extends Resource {

	/**
	 * Creates a {@link ListingInfo} instance.
	 */
	public static ListingInfo create() {
		return new ListingInfo();
	}

	/**
	 * Creates a {@link ListingInfo} instance.
	 *
	 * @see #create()
	 */
	protected ListingInfo() {
		super();
	}

	private int _depth = 0;

	private String _name = "";

	private String _title = "";

	private final java.util.List<FolderInfo> _folders = new java.util.ArrayList<>();

	public final int getDepth() {
		return _depth;
	}

	/**
	 * @see #getDepth()
	 */
	public final ListingInfo setDepth(int value) {
		_depth = value;
		return this;
	}

	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public final ListingInfo setName(String value) {
		_name = value;
		return this;
	}

	public final String getTitle() {
		return _title;
	}

	/**
	 * @see #getTitle()
	 */
	public final ListingInfo setTitle(String value) {
		_title = value;
		return this;
	}

	public final java.util.List<FolderInfo> getFolders() {
		return _folders;
	}

	/**
	 * @see #getFolders()
	 */
	public final ListingInfo setFolders(java.util.List<FolderInfo> value) {
		_folders.clear();
		_folders.addAll(value);
		return this;
	}

	/**
	 * Adds a value to the {@link #getFolders()} list.
	 */
	public final ListingInfo addFolder(FolderInfo value) {
		_folders.add(value);
		return this;
	}

	/** Reads a new instance from the given reader. */
	public static ListingInfo readListingInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		ListingInfo result = new ListingInfo();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	public String jsonType() {
		return "ListingInfo";
	}

	@Override
	public Object get(String field) {
		switch (field) {
			case "depth": return getDepth();
			case "name": return getName();
			case "title": return getTitle();
			case "folders": return getFolders();
			default: return super.get(field);
		}
	}

	@Override
	public void set(String field, Object value) {
		switch (field) {
			case "depth": setDepth((int) value); break;
			case "name": setName((String) value); break;
			case "title": setTitle((String) value); break;
			case "folders": setFolders((java.util.List<FolderInfo>) value); break;
			default: super.set(field, value); break;
		}
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name("depth");
		out.value(getDepth());
		out.name("name");
		out.value(getName());
		out.name("title");
		out.value(getTitle());
		out.name("folders");
		out.beginArray();
		for (FolderInfo x : getFolders()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case "depth": setDepth(in.nextInt()); break;
			case "name": setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "title": setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "folders": {
				in.beginArray();
				while (in.hasNext()) {
					addFolder(FolderInfo.readFolderInfo(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	@Override
	public int typeId() {
		return 3;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(1);
		out.value(getDepth());
		out.name(2);
		out.value(getName());
		out.name(3);
		out.value(getTitle());
		out.name(4);
		{
			java.util.List<FolderInfo> values = getFolders();
			out.beginArray(de.haumacher.msgbuf.binary.DataType.OBJECT, values.size());
			for (FolderInfo x : values) {
				x.writeTo(out);
			}
			out.endArray();
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.binary.DataReader in, int field) throws java.io.IOException {
		switch (field) {
			case 1: setDepth(in.nextInt()); break;
			case 2: setName(in.nextString()); break;
			case 3: setTitle(in.nextString()); break;
			case 4: {
				in.beginArray();
				while (in.hasNext()) {
					addFolder(FolderInfo.readFolderInfo(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	/** Reads a new instance from the given reader. */
	public static ListingInfo readListingInfo(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		ListingInfo result = new ListingInfo();
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
