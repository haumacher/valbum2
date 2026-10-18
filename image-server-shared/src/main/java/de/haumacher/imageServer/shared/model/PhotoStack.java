package de.haumacher.imageServer.shared.model;

/**
 * One burst of an album, see {@link AlbumInfo#getStacks()}: derived, never stored.
 */
public class PhotoStack extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PhotoStack} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PhotoStack create() {
		return new de.haumacher.imageServer.shared.model.PhotoStack();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PhotoStack} type in JSON format. */
	public static final String PHOTO_STACK__TYPE = "PhotoStack";

	/** @see #getMembers() */
	private static final String MEMBERS__PROP = "members";

	private final java.util.List<de.haumacher.imageServer.shared.model.StackMember> _members = new java.util.ArrayList<>();

	/**
	 * Creates a {@link PhotoStack} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PhotoStack#create()
	 */
	protected PhotoStack() {
		super();
	}

	/**
	 * The photographs of the stack by their {@link ImagePart#getName() names}, at least two, the best shot
	 * first: the highest {@link ImagePart#getRating()} where a member is rated, else the sharpest and best
	 * exposed by the measure the server keeps beside the vectors, else the first taken; the others in
	 * the order they were taken.
	 *
	 * <p>
	 * A list of messages, not a list of plain strings: the Dart backend of the model generator
	 * mis-types a <code>repeated string</code> field, see {@link UploadCheck#getHashes()}.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.StackMember> getMembers() {
		return _members;
	}

	/**
	 * @see #getMembers()
	 */
	public de.haumacher.imageServer.shared.model.PhotoStack setMembers(java.util.List<? extends de.haumacher.imageServer.shared.model.StackMember> value) {
		internalSetMembers(value);
		return this;
	}

	/** Internal setter for {@link #getMembers()} without chain call utility. */
	protected final void internalSetMembers(java.util.List<? extends de.haumacher.imageServer.shared.model.StackMember> value) {
		if (value == null) throw new IllegalArgumentException("Property 'members' cannot be null.");
		_members.clear();
		_members.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getMembers()} list.
	 */
	public de.haumacher.imageServer.shared.model.PhotoStack addMember(de.haumacher.imageServer.shared.model.StackMember value) {
		internalAddMember(value);
		return this;
	}

	/** Implementation of {@link #addMember(de.haumacher.imageServer.shared.model.StackMember)} without chain call utility. */
	protected final void internalAddMember(de.haumacher.imageServer.shared.model.StackMember value) {
		_members.add(value);
	}

	/**
	 * Removes a value from the {@link #getMembers()} list.
	 */
	public final void removeMember(de.haumacher.imageServer.shared.model.StackMember value) {
		_members.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PhotoStack readPhotoStack(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PhotoStack result = new de.haumacher.imageServer.shared.model.PhotoStack();
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
		out.name(MEMBERS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.StackMember x : getMembers()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case MEMBERS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addMember(de.haumacher.imageServer.shared.model.StackMember.readStackMember(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
