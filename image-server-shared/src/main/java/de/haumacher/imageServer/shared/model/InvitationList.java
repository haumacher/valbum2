package de.haumacher.imageServer.shared.model;

/**
 * The invitations of this server, answered by <code>&lt;data&gt;/?type=invitations</code>.
 *
 * <p>
 * The admin is answered every invitation, a member the ones they issued themselves; a guest and an
 * anonymous caller are answered none at all. No answer ever carries a token.
 * </p>
 */
public class InvitationList extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.InvitationList} instance.
	 */
	public static de.haumacher.imageServer.shared.model.InvitationList create() {
		return new de.haumacher.imageServer.shared.model.InvitationList();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.InvitationList} type in JSON format. */
	public static final String INVITATION_LIST__TYPE = "InvitationList";

	/** @see #getInvitations() */
	private static final String INVITATIONS__PROP = "invitations";

	private final java.util.List<de.haumacher.imageServer.shared.model.Invitation> _invitations = new java.util.ArrayList<>();

	/**
	 * Creates a {@link InvitationList} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.InvitationList#create()
	 */
	protected InvitationList() {
		super();
	}

	/**
	 * The invitations, newest last, in the order they were issued.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.Invitation> getInvitations() {
		return _invitations;
	}

	/**
	 * @see #getInvitations()
	 */
	public de.haumacher.imageServer.shared.model.InvitationList setInvitations(java.util.List<? extends de.haumacher.imageServer.shared.model.Invitation> value) {
		internalSetInvitations(value);
		return this;
	}

	/** Internal setter for {@link #getInvitations()} without chain call utility. */
	protected final void internalSetInvitations(java.util.List<? extends de.haumacher.imageServer.shared.model.Invitation> value) {
		if (value == null) throw new IllegalArgumentException("Property 'invitations' cannot be null.");
		_invitations.clear();
		_invitations.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getInvitations()} list.
	 */
	public de.haumacher.imageServer.shared.model.InvitationList addInvitation(de.haumacher.imageServer.shared.model.Invitation value) {
		internalAddInvitation(value);
		return this;
	}

	/** Implementation of {@link #addInvitation(de.haumacher.imageServer.shared.model.Invitation)} without chain call utility. */
	protected final void internalAddInvitation(de.haumacher.imageServer.shared.model.Invitation value) {
		_invitations.add(value);
	}

	/**
	 * Removes a value from the {@link #getInvitations()} list.
	 */
	public final void removeInvitation(de.haumacher.imageServer.shared.model.Invitation value) {
		_invitations.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.InvitationList readInvitationList(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.InvitationList result = new de.haumacher.imageServer.shared.model.InvitationList();
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
		out.name(INVITATIONS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.Invitation x : getInvitations()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case INVITATIONS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addInvitation(de.haumacher.imageServer.shared.model.Invitation.readInvitation(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
