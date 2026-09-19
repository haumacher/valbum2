package de.haumacher.imageServer.shared.model;

/**
 * The invitation the caller presented, see {@link AuthInfo#getInvitation()} and issue #52.
 *
 * <p>
 * What the app needs to say "you were invited by alice as a member" before it asks for a name. An
 * invitation token is no login: it says what would be created if it were accepted, and nothing
 * more.
 * </p>
 */
public class InvitationInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.InvitationInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.InvitationInfo create() {
		return new de.haumacher.imageServer.shared.model.InvitationInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.InvitationInfo} type in JSON format. */
	public static final String INVITATION_INFO__TYPE = "InvitationInfo";

	/** @see #getRole() */
	private static final String ROLE__PROP = "role";

	/** @see #getInvitedBy() */
	private static final String INVITED_BY__PROP = "invitedBy";

	/** @see #getNote() */
	private static final String NOTE__PROP = "note";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	/** @see #getRecipient() */
	private static final String RECIPIENT__PROP = "recipient";

	private String _role = "";

	private String _invitedBy = "";

	private String _note = "";

	private String _expires = "";

	private String _recipient = "";

	/**
	 * Creates a {@link InvitationInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.InvitationInfo#create()
	 */
	protected InvitationInfo() {
		super();
	}

	/**
	 * The role the accepting user would be created with: <code>member</code> or <code>guest</code>.
	 */
	public final String getRole() {
		return _role;
	}

	/**
	 * @see #getRole()
	 */
	public de.haumacher.imageServer.shared.model.InvitationInfo setRole(String value) {
		internalSetRole(value);
		return this;
	}

	/** Internal setter for {@link #getRole()} without chain call utility. */
	protected final void internalSetRole(String value) {
		_role = value;
	}

	/**
	 * The name of the user who issued the invitation.
	 */
	public final String getInvitedBy() {
		return _invitedBy;
	}

	/**
	 * @see #getInvitedBy()
	 */
	public de.haumacher.imageServer.shared.model.InvitationInfo setInvitedBy(String value) {
		internalSetInvitedBy(value);
		return this;
	}

	/** Internal setter for {@link #getInvitedBy()} without chain call utility. */
	protected final void internalSetInvitedBy(String value) {
		_invitedBy = value;
	}

	/**
	 * The note the inviter wrote, empty if they wrote none.
	 */
	public final String getNote() {
		return _note;
	}

	/**
	 * @see #getNote()
	 */
	public de.haumacher.imageServer.shared.model.InvitationInfo setNote(String value) {
		internalSetNote(value);
		return this;
	}

	/** Internal setter for {@link #getNote()} without chain call utility. */
	protected final void internalSetNote(String value) {
		_note = value;
	}

	/**
	 * When the invitation expires, an ISO-8601 instant.
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.InvitationInfo setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/**
	 * Whom the inviter wrote this invitation for, empty if they wrote nobody (issue #89).
	 *
	 * <p>
	 * The inviter's memento, see {@link Invitation#getRecipient()}. The application does not show it
	 * to the person who opened the link &mdash; it is a note the inviter made to themselves, not
	 * a greeting &mdash; and it travels only so that a client that wants to greet by name could.
	 * </p>
	 */
	public final String getRecipient() {
		return _recipient;
	}

	/**
	 * @see #getRecipient()
	 */
	public de.haumacher.imageServer.shared.model.InvitationInfo setRecipient(String value) {
		internalSetRecipient(value);
		return this;
	}

	/** Internal setter for {@link #getRecipient()} without chain call utility. */
	protected final void internalSetRecipient(String value) {
		_recipient = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.InvitationInfo readInvitationInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.InvitationInfo result = new de.haumacher.imageServer.shared.model.InvitationInfo();
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
		out.name(ROLE__PROP);
		out.value(getRole());
		out.name(INVITED_BY__PROP);
		out.value(getInvitedBy());
		out.name(NOTE__PROP);
		out.value(getNote());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
		out.name(RECIPIENT__PROP);
		out.value(getRecipient());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ROLE__PROP: setRole(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case INVITED_BY__PROP: setInvitedBy(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NOTE__PROP: setNote(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case RECIPIENT__PROP: setRecipient(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
