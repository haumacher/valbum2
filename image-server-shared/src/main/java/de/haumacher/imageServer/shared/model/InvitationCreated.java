package de.haumacher.imageServer.shared.model;

/**
 * The answer to <code>&lt;data&gt;/?action=invite</code>: the new invitation, with its token.
 *
 * <p>
 * The one and only time the {@link #getToken()} is answered; the server keeps its hash and can never
 * show it again. A lost invitation is withdrawn and issued anew.
 * </p>
 */
public class InvitationCreated extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.InvitationCreated} instance.
	 */
	public static de.haumacher.imageServer.shared.model.InvitationCreated create() {
		return new de.haumacher.imageServer.shared.model.InvitationCreated();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.InvitationCreated} type in JSON format. */
	public static final String INVITATION_CREATED__TYPE = "InvitationCreated";

	/** @see #getInvitation() */
	private static final String INVITATION__PROP = "invitation";

	/** @see #getToken() */
	private static final String TOKEN__PROP = "token";

	/** @see #getUrl() */
	private static final String URL__PROP = "url";

	private de.haumacher.imageServer.shared.model.Invitation _invitation = null;

	private String _token = "";

	private String _url = "";

	/**
	 * Creates a {@link InvitationCreated} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.InvitationCreated#create()
	 */
	protected InvitationCreated() {
		super();
	}

	/**
	 * The invitation that was issued, as {@link InvitationList} lists it.
	 */
	public final de.haumacher.imageServer.shared.model.Invitation getInvitation() {
		return _invitation;
	}

	/**
	 * @see #getInvitation()
	 */
	public de.haumacher.imageServer.shared.model.InvitationCreated setInvitation(de.haumacher.imageServer.shared.model.Invitation value) {
		internalSetInvitation(value);
		return this;
	}

	/** Internal setter for {@link #getInvitation()} without chain call utility. */
	protected final void internalSetInvitation(de.haumacher.imageServer.shared.model.Invitation value) {
		_invitation = value;
	}

	/**
	 * Checks, whether {@link #getInvitation()} has a value.
	 */
	public final boolean hasInvitation() {
		return _invitation != null;
	}

	/**
	 * The token to accept the invitation with, answered exactly once and never stored.
	 */
	public final String getToken() {
		return _token;
	}

	/**
	 * @see #getToken()
	 */
	public de.haumacher.imageServer.shared.model.InvitationCreated setToken(String value) {
		internalSetToken(value);
		return this;
	}

	/** Internal setter for {@link #getToken()} without chain call utility. */
	protected final void internalSetToken(String value) {
		_token = value;
	}

	/**
	 * The invitation's path on this server: <code>&lt;context&gt;/i/&lt;token&gt;/</code>.
	 */
	public final String getUrl() {
		return _url;
	}

	/**
	 * @see #getUrl()
	 */
	public de.haumacher.imageServer.shared.model.InvitationCreated setUrl(String value) {
		internalSetUrl(value);
		return this;
	}

	/** Internal setter for {@link #getUrl()} without chain call utility. */
	protected final void internalSetUrl(String value) {
		_url = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.InvitationCreated readInvitationCreated(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.InvitationCreated result = new de.haumacher.imageServer.shared.model.InvitationCreated();
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
		if (hasInvitation()) {
			out.name(INVITATION__PROP);
			getInvitation().writeTo(out);
		}
		out.name(TOKEN__PROP);
		out.value(getToken());
		out.name(URL__PROP);
		out.value(getUrl());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case INVITATION__PROP: setInvitation(de.haumacher.imageServer.shared.model.Invitation.readInvitation(in)); break;
			case TOKEN__PROP: setToken(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case URL__PROP: setUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
