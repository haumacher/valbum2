package de.haumacher.imageServer.shared.model;

/**
 * The settings of a space its administrator makes in the app, see issue #297:
 * <code>GET &lt;data&gt;/?type=space-settings</code> answers them, and
 * <code>POST &lt;data&gt;/?action=space-settings</code> with this message stores them and answers
 * them as they are then.
 *
 * <p>
 * The administrator's question and decision alone: a member who is no administrator, a share link
 * and a visitor are refused with the server's reason. What is stored is the space's
 * <code>.valbum/space.json</code>, in the format it always had; a key this build does not know is
 * kept as it is written. A change takes effect without a restart.
 * </p>
 *
 * <p>
 * The fields marked <em>answered</em> say what the server knows around the settings and are
 * ignored on a write.
 * </p>
 */
public class SpaceSettings extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SpaceSettings} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SpaceSettings create() {
		return new de.haumacher.imageServer.shared.model.SpaceSettings();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SpaceSettings} type in JSON format. */
	public static final String SPACE_SETTINGS__TYPE = "SpaceSettings";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getAnonymous() */
	private static final String ANONYMOUS__PROP = "anonymous";

	/** @see #getAnonymousFixed() */
	private static final String ANONYMOUS_FIXED__PROP = "anonymousFixed";

	/** @see #isFaces() */
	private static final String FACES__PROP = "faces";

	/** @see #getFacesUnavailable() */
	private static final String FACES_UNAVAILABLE__PROP = "facesUnavailable";

	/** @see #getMapUrl() */
	private static final String MAP_URL__PROP = "mapUrl";

	/** @see #getDefaultMapUrl() */
	private static final String DEFAULT_MAP_URL__PROP = "defaultMapUrl";

	/** @see #getTimeZone() */
	private static final String TIME_ZONE__PROP = "timeZone";

	/** @see #getServerTimeZone() */
	private static final String SERVER_TIME_ZONE__PROP = "serverTimeZone";

	/** @see #getInbox() */
	private static final String INBOX__PROP = "inbox";

	/** @see #getInboxFixed() */
	private static final String INBOX_FIXED__PROP = "inboxFixed";

	/** @see #getContent() */
	private static final String CONTENT__PROP = "content";

	/** @see #getContentUnavailable() */
	private static final String CONTENT_UNAVAILABLE__PROP = "contentUnavailable";

	/** @see #getThresholds() */
	private static final String THRESHOLDS__PROP = "thresholds";

	private String _name = "";

	private de.haumacher.imageServer.shared.model.AnonymousAccess _anonymous = de.haumacher.imageServer.shared.model.AnonymousAccess.NONE;

	private String _anonymousFixed = "";

	private boolean _faces = false;

	private String _facesUnavailable = "";

	private String _mapUrl = "";

	private String _defaultMapUrl = "";

	private String _timeZone = "";

	private String _serverTimeZone = "";

	private String _inbox = "";

	private String _inboxFixed = "";

	private de.haumacher.imageServer.shared.model.ContentSwitch _content = de.haumacher.imageServer.shared.model.ContentSwitch.KEEP;

	private String _contentUnavailable = "";

	private de.haumacher.imageServer.shared.model.ContentThresholds _thresholds = null;

	/**
	 * Creates a {@link SpaceSettings} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SpaceSettings#create()
	 */
	protected SpaceSettings() {
		super();
	}

	/**
	 * The name shown for the space; the folder's name where the file gives none. Never empty.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * Whether a visitor who is not signed in sees the public photos of the space.
	 */
	public final de.haumacher.imageServer.shared.model.AnonymousAccess getAnonymous() {
		return _anonymous;
	}

	/**
	 * @see #getAnonymous()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setAnonymous(de.haumacher.imageServer.shared.model.AnonymousAccess value) {
		internalSetAnonymous(value);
		return this;
	}

	/** Internal setter for {@link #getAnonymous()} without chain call utility. */
	protected final void internalSetAnonymous(de.haumacher.imageServer.shared.model.AnonymousAccess value) {
		if (value == null) throw new IllegalArgumentException("Property 'anonymous' cannot be null.");
		_anonymous = value;
	}

	/**
	 * <em>Answered:</em> why {@link #getAnonymous()} decides nothing on this server, empty where it
	 * does: a server that serves one library, or one started with <code>--auth all</code> or
	 * <code>--auth off</code>, decides at its start who may look. A change is refused then.
	 */
	public final String getAnonymousFixed() {
		return _anonymousFixed;
	}

	/**
	 * @see #getAnonymousFixed()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setAnonymousFixed(String value) {
		internalSetAnonymousFixed(value);
		return this;
	}

	/** Internal setter for {@link #getAnonymousFixed()} without chain call utility. */
	protected final void internalSetAnonymousFixed(String value) {
		_anonymousFixed = value;
	}

	/**
	 * Whether the server looks for faces in the photographs of the space, see issue #124.
	 */
	public final boolean isFaces() {
		return _faces;
	}

	/**
	 * @see #isFaces()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setFaces(boolean value) {
		internalSetFaces(value);
		return this;
	}

	/** Internal setter for {@link #isFaces()} without chain call utility. */
	protected final void internalSetFaces(boolean value) {
		_faces = value;
	}

	/**
	 * <em>Answered:</em> why the server cannot look for faces on this machine although the space
	 * may ask for it, empty where it can.
	 */
	public final String getFacesUnavailable() {
		return _facesUnavailable;
	}

	/**
	 * @see #getFacesUnavailable()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setFacesUnavailable(String value) {
		internalSetFacesUnavailable(value);
		return this;
	}

	/** Internal setter for {@link #getFacesUnavailable()} without chain call utility. */
	protected final void internalSetFacesUnavailable(String value) {
		_facesUnavailable = value;
	}

	/**
	 * The map a position is shown on: a URL template with <code>{lat}</code> and
	 * <code>{lon}</code>, see issue #112. Empty on a write for {@link #getDefaultMapUrl()}.
	 */
	public final String getMapUrl() {
		return _mapUrl;
	}

	/**
	 * @see #getMapUrl()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setMapUrl(String value) {
		internalSetMapUrl(value);
		return this;
	}

	/** Internal setter for {@link #getMapUrl()} without chain call utility. */
	protected final void internalSetMapUrl(String value) {
		_mapUrl = value;
	}

	/**
	 * <em>Answered:</em> the map of a space that names none of its own.
	 */
	public final String getDefaultMapUrl() {
		return _defaultMapUrl;
	}

	/**
	 * @see #getDefaultMapUrl()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setDefaultMapUrl(String value) {
		internalSetDefaultMapUrl(value);
		return this;
	}

	/** Internal setter for {@link #getDefaultMapUrl()} without chain call utility. */
	protected final void internalSetDefaultMapUrl(String value) {
		_defaultMapUrl = value;
	}

	/**
	 * The time zone a photograph that says neither its offset nor a GPS time was taken in, an IANA
	 * id such as <code>Europe/Berlin</code>, see issue #183; empty for the server's own zone.
	 */
	public final String getTimeZone() {
		return _timeZone;
	}

	/**
	 * @see #getTimeZone()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setTimeZone(String value) {
		internalSetTimeZone(value);
		return this;
	}

	/** Internal setter for {@link #getTimeZone()} without chain call utility. */
	protected final void internalSetTimeZone(String value) {
		_timeZone = value;
	}

	/**
	 * <em>Answered:</em> the server's own time zone, which an empty {@link #getTimeZone()} means.
	 */
	public final String getServerTimeZone() {
		return _serverTimeZone;
	}

	/**
	 * @see #getServerTimeZone()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setServerTimeZone(String value) {
		internalSetServerTimeZone(value);
		return this;
	}

	/** Internal setter for {@link #getServerTimeZone()} without chain call utility. */
	protected final void internalSetServerTimeZone(String value) {
		_serverTimeZone = value;
	}

	/**
	 * The folder of the space's inbox, relative to the space root with <code>/</code> between the
	 * folders, see issue #226.
	 */
	public final String getInbox() {
		return _inbox;
	}

	/**
	 * @see #getInbox()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setInbox(String value) {
		internalSetInbox(value);
		return this;
	}

	/** Internal setter for {@link #getInbox()} without chain call utility. */
	protected final void internalSetInbox(String value) {
		_inbox = value;
	}

	/**
	 * <em>Answered:</em> why the {@link #getInbox()} cannot be changed now, empty where it can: another
	 * inbox would turn the photographs waiting in this one into an ordinary album.
	 */
	public final String getInboxFixed() {
		return _inboxFixed;
	}

	/**
	 * @see #getInboxFixed()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setInboxFixed(String value) {
		internalSetInboxFixed(value);
		return this;
	}

	/** Internal setter for {@link #getInboxFixed()} without chain call utility. */
	protected final void internalSetInboxFixed(String value) {
		_inboxFixed = value;
	}

	/**
	 * Whether the server describes every photo of the space for the content search of issue #257:
	 * {@link ContentSwitch#ON} or {@link ContentSwitch#OFF} in an answer. On a write,
	 * {@link ContentSwitch#KEEP} (what an app that does not know the switch sends) leaves the space
	 * as it is.
	 */
	public final de.haumacher.imageServer.shared.model.ContentSwitch getContent() {
		return _content;
	}

	/**
	 * @see #getContent()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setContent(de.haumacher.imageServer.shared.model.ContentSwitch value) {
		internalSetContent(value);
		return this;
	}

	/** Internal setter for {@link #getContent()} without chain call utility. */
	protected final void internalSetContent(de.haumacher.imageServer.shared.model.ContentSwitch value) {
		if (value == null) throw new IllegalArgumentException("Property 'content' cannot be null.");
		_content = value;
	}

	/**
	 * <em>Answered:</em> why the embedder of the content search cannot describe photos now (it is
	 * not reachable, refuses this server's key, is still downloading its model), empty where it
	 * can. Where the switch is off and the embedder runs on this server, it says that nothing is
	 * started before the switch is turned on.
	 */
	public final String getContentUnavailable() {
		return _contentUnavailable;
	}

	/**
	 * @see #getContentUnavailable()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setContentUnavailable(String value) {
		internalSetContentUnavailable(value);
		return this;
	}

	/** Internal setter for {@link #getContentUnavailable()} without chain call utility. */
	protected final void internalSetContentUnavailable(String value) {
		_contentUnavailable = value;
	}

	/**
	 * The space's cuts of the content search (issue #302), which a query stating none of its own is
	 * answered with. Always answered. On a write, absent (what an app that does not know them
	 * sends) leaves the space's cuts as they are.
	 */
	public final de.haumacher.imageServer.shared.model.ContentThresholds getThresholds() {
		return _thresholds;
	}

	/**
	 * @see #getThresholds()
	 */
	public de.haumacher.imageServer.shared.model.SpaceSettings setThresholds(de.haumacher.imageServer.shared.model.ContentThresholds value) {
		internalSetThresholds(value);
		return this;
	}

	/** Internal setter for {@link #getThresholds()} without chain call utility. */
	protected final void internalSetThresholds(de.haumacher.imageServer.shared.model.ContentThresholds value) {
		_thresholds = value;
	}

	/**
	 * Checks, whether {@link #getThresholds()} has a value.
	 */
	public final boolean hasThresholds() {
		return _thresholds != null;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SpaceSettings readSpaceSettings(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SpaceSettings result = new de.haumacher.imageServer.shared.model.SpaceSettings();
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
		out.name(NAME__PROP);
		out.value(getName());
		out.name(ANONYMOUS__PROP);
		getAnonymous().writeTo(out);
		out.name(ANONYMOUS_FIXED__PROP);
		out.value(getAnonymousFixed());
		out.name(FACES__PROP);
		out.value(isFaces());
		out.name(FACES_UNAVAILABLE__PROP);
		out.value(getFacesUnavailable());
		out.name(MAP_URL__PROP);
		out.value(getMapUrl());
		out.name(DEFAULT_MAP_URL__PROP);
		out.value(getDefaultMapUrl());
		out.name(TIME_ZONE__PROP);
		out.value(getTimeZone());
		out.name(SERVER_TIME_ZONE__PROP);
		out.value(getServerTimeZone());
		out.name(INBOX__PROP);
		out.value(getInbox());
		out.name(INBOX_FIXED__PROP);
		out.value(getInboxFixed());
		out.name(CONTENT__PROP);
		getContent().writeTo(out);
		out.name(CONTENT_UNAVAILABLE__PROP);
		out.value(getContentUnavailable());
		if (hasThresholds()) {
			out.name(THRESHOLDS__PROP);
			getThresholds().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ANONYMOUS__PROP: setAnonymous(de.haumacher.imageServer.shared.model.AnonymousAccess.readAnonymousAccess(in)); break;
			case ANONYMOUS_FIXED__PROP: setAnonymousFixed(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FACES__PROP: setFaces(in.nextBoolean()); break;
			case FACES_UNAVAILABLE__PROP: setFacesUnavailable(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case MAP_URL__PROP: setMapUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEFAULT_MAP_URL__PROP: setDefaultMapUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case TIME_ZONE__PROP: setTimeZone(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SERVER_TIME_ZONE__PROP: setServerTimeZone(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case INBOX__PROP: setInbox(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case INBOX_FIXED__PROP: setInboxFixed(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CONTENT__PROP: setContent(de.haumacher.imageServer.shared.model.ContentSwitch.readContentSwitch(in)); break;
			case CONTENT_UNAVAILABLE__PROP: setContentUnavailable(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case THRESHOLDS__PROP: setThresholds(de.haumacher.imageServer.shared.model.ContentThresholds.readContentThresholds(in)); break;
			default: super.readField(in, field);
		}
	}

}
