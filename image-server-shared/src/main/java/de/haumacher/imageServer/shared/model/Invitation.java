package de.haumacher.imageServer.shared.model;

/**
 * An invitation: a single-use token that creates a user, see issue #52.
 *
 * <p>
 * Sent to <code>&lt;data&gt;/?action=invite</code> to create one, where only {@link #getRole()},
 * {@link #getExpires()} and {@link #getNote()} are read and everything else is answered by the server;
 * answered by <code>&lt;data&gt;/?type=invitations</code> and used to name the invitation to
 * withdraw at <code>&lt;data&gt;/?action=uninvite</code>, which reads nothing but the {@link #getId()}.
 * </p>
 *
 * <p>
 * The token is never part of this message: it is answered exactly once, in an
 * {@link InvitationCreated}, and the server stores nothing but its hash.
 * </p>
 */
public class Invitation extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.Invitation} instance.
	 */
	public static de.haumacher.imageServer.shared.model.Invitation create() {
		return new de.haumacher.imageServer.shared.model.Invitation();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.Invitation} type in JSON format. */
	public static final String INVITATION__TYPE = "Invitation";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getRole() */
	private static final String ROLE__PROP = "role";

	/** @see #getClearance() */
	private static final String CLEARANCE__PROP = "clearance";

	/** @see #isMayShare() */
	private static final String MAY_SHARE__PROP = "mayShare";

	/** @see #getNote() */
	private static final String NOTE__PROP = "note";

	/** @see #getRecipient() */
	private static final String RECIPIENT__PROP = "recipient";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	/** @see #getInvitedBy() */
	private static final String INVITED_BY__PROP = "invitedBy";

	/** @see #getCreated() */
	private static final String CREATED__PROP = "created";

	/** @see #getUsed() */
	private static final String USED__PROP = "used";

	/** @see #getUsedBy() */
	private static final String USED_BY__PROP = "usedBy";

	/** @see #getRevoked() */
	private static final String REVOKED__PROP = "revoked";

	private String _id = "";

	private String _role = "";

	private String _clearance = "";

	private boolean _mayShare = false;

	private String _note = "";

	private String _recipient = "";

	private String _expires = "";

	private String _invitedBy = "";

	private String _created = "";

	private String _used = "";

	private String _usedBy = "";

	private String _revoked = "";

	/**
	 * Creates a {@link Invitation} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.Invitation#create()
	 */
	protected Invitation() {
		super();
	}

	/**
	 * The short id of the invitation; answered by the server, and what names it in a request.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * The role the accepting user is created with: <code>member</code> or <code>guest</code>.
	 *
	 * <p>
	 * Never <code>admin</code>: the library has exactly one owner and nobody is invited into that
	 * seat. An empty role is read as <code>member</code>.
	 * </p>
	 */
	public final String getRole() {
		return _role;
	}

	/**
	 * @see #getRole()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setRole(String value) {
		internalSetRole(value);
		return this;
	}

	/** Internal setter for {@link #getRole()} without chain call utility. */
	protected final void internalSetRole(String value) {
		_role = value;
	}

	/**
	 * Which privacy levels the accepting user may see: <code>public</code>,
	 * <code>nonPrivate</code> or <code>all</code> (issue #82).
	 *
	 * <p>
	 * Empty means what the role implies. Never above the inviter's own clearance; stored with the
	 * created user and enforced by issue #83.
	 * </p>
	 */
	public final String getClearance() {
		return _clearance;
	}

	/**
	 * @see #getClearance()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setClearance(String value) {
		internalSetClearance(value);
		return this;
	}

	/** Internal setter for {@link #getClearance()} without chain call utility. */
	protected final void internalSetClearance(String value) {
		_clearance = value;
	}

	/**
	 * Whether the accepting user may create share links (issue #82).
	 */
	public final boolean isMayShare() {
		return _mayShare;
	}

	/**
	 * @see #isMayShare()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setMayShare(boolean value) {
		internalSetMayShare(value);
		return this;
	}

	/** Internal setter for {@link #isMayShare()} without chain call utility. */
	protected final void internalSetMayShare(boolean value) {
		_mayShare = value;
	}

	/**
	 * A note the inviter wrote for themselves, shown wherever the invitation is listed; may be empty.
	 */
	public final String getNote() {
		return _note;
	}

	/**
	 * @see #getNote()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setNote(String value) {
		internalSetNote(value);
		return this;
	}

	/** Internal setter for {@link #getNote()} without chain call utility. */
	protected final void internalSetNote(String value) {
		_note = value;
	}

	/**
	 * Whom this invitation was meant for, the inviter's own memento (issue #89).
	 *
	 * <p>
	 * Optional and free text: "Grandma", "Bob from the choir" &mdash; what the inviter needs in
	 * order to tell one open invitation from another weeks later, and what the users list keeps
	 * beside the name once the person has chosen one. It is stored on the pending user the
	 * invitation creates, not on a store of its own.
	 * </p>
	 */
	public final String getRecipient() {
		return _recipient;
	}

	/**
	 * @see #getRecipient()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setRecipient(String value) {
		internalSetRecipient(value);
		return this;
	}

	/** Internal setter for {@link #getRecipient()} without chain call utility. */
	protected final void internalSetRecipient(String value) {
		_recipient = value;
	}

	/**
	 * When the invitation expires, an ISO-8601 instant.
	 *
	 * <p>
	 * Empty in a request means "in seven days"; the answer always carries the instant the server
	 * settled on, so an invitation never lives forever.
	 * </p>
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/**
	 * The name of the user who issued the invitation; answered by the server.
	 */
	public final String getInvitedBy() {
		return _invitedBy;
	}

	/**
	 * @see #getInvitedBy()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setInvitedBy(String value) {
		internalSetInvitedBy(value);
		return this;
	}

	/** Internal setter for {@link #getInvitedBy()} without chain call utility. */
	protected final void internalSetInvitedBy(String value) {
		_invitedBy = value;
	}

	/**
	 * When the invitation was issued, an ISO-8601 instant; answered by the server.
	 */
	public final String getCreated() {
		return _created;
	}

	/**
	 * @see #getCreated()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setCreated(String value) {
		internalSetCreated(value);
		return this;
	}

	/** Internal setter for {@link #getCreated()} without chain call utility. */
	protected final void internalSetCreated(String value) {
		_created = value;
	}

	/**
	 * When the invitation was accepted, an ISO-8601 instant; empty while it is unused.
	 */
	public final String getUsed() {
		return _used;
	}

	/**
	 * @see #getUsed()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setUsed(String value) {
		internalSetUsed(value);
		return this;
	}

	/** Internal setter for {@link #getUsed()} without chain call utility. */
	protected final void internalSetUsed(String value) {
		_used = value;
	}

	/**
	 * The name of the user the invitation created, empty while it is unused.
	 */
	public final String getUsedBy() {
		return _usedBy;
	}

	/**
	 * @see #getUsedBy()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setUsedBy(String value) {
		internalSetUsedBy(value);
		return this;
	}

	/** Internal setter for {@link #getUsedBy()} without chain call utility. */
	protected final void internalSetUsedBy(String value) {
		_usedBy = value;
	}

	/**
	 * When the invitation was withdrawn, an ISO-8601 instant; empty while it stands.
	 */
	public final String getRevoked() {
		return _revoked;
	}

	/**
	 * @see #getRevoked()
	 */
	public de.haumacher.imageServer.shared.model.Invitation setRevoked(String value) {
		internalSetRevoked(value);
		return this;
	}

	/** Internal setter for {@link #getRevoked()} without chain call utility. */
	protected final void internalSetRevoked(String value) {
		_revoked = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.Invitation readInvitation(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.Invitation result = new de.haumacher.imageServer.shared.model.Invitation();
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
		out.name(ID__PROP);
		out.value(getId());
		out.name(ROLE__PROP);
		out.value(getRole());
		out.name(CLEARANCE__PROP);
		out.value(getClearance());
		out.name(MAY_SHARE__PROP);
		out.value(isMayShare());
		out.name(NOTE__PROP);
		out.value(getNote());
		out.name(RECIPIENT__PROP);
		out.value(getRecipient());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
		out.name(INVITED_BY__PROP);
		out.value(getInvitedBy());
		out.name(CREATED__PROP);
		out.value(getCreated());
		out.name(USED__PROP);
		out.value(getUsed());
		out.name(USED_BY__PROP);
		out.value(getUsedBy());
		out.name(REVOKED__PROP);
		out.value(getRevoked());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ROLE__PROP: setRole(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CLEARANCE__PROP: setClearance(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case MAY_SHARE__PROP: setMayShare(in.nextBoolean()); break;
			case NOTE__PROP: setNote(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case RECIPIENT__PROP: setRecipient(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case INVITED_BY__PROP: setInvitedBy(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CREATED__PROP: setCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case USED__PROP: setUsed(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case USED_BY__PROP: setUsedBy(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case REVOKED__PROP: setRevoked(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
