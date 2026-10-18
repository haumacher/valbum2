package de.haumacher.imageServer.shared.model;

/**
 * How far the server has got bringing the albums of a space up to date in the background, see
 * issue #236: <code>&lt;data&gt;/?type=catch-up</code>, answered to an administrator of the space
 * only.
 *
 * <p>
 * Read-only and derived from what the server is doing at the moment it is asked; nothing of it is
 * stored. Counted since the server started: a restart walks the library again, quickly where
 * everything is done already.
 * </p>
 */
public class CatchUpStatus extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.CatchUpStatus} instance.
	 */
	public static de.haumacher.imageServer.shared.model.CatchUpStatus create() {
		return new de.haumacher.imageServer.shared.model.CatchUpStatus();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.CatchUpStatus} type in JSON format. */
	public static final String CATCH_UP_STATUS__TYPE = "CatchUpStatus";

	/** @see #getAlbumsDone() */
	private static final String ALBUMS_DONE__PROP = "albumsDone";

	/** @see #getAlbumsTotal() */
	private static final String ALBUMS_TOTAL__PROP = "albumsTotal";

	/** @see #getStep() */
	private static final String STEP__PROP = "step";

	/** @see #getFolder() */
	private static final String FOLDER__PROP = "folder";

	/** @see #getVideosRemaining() */
	private static final String VIDEOS_REMAINING__PROP = "videosRemaining";

	/** @see #getContentRemaining() */
	private static final String CONTENT_REMAINING__PROP = "contentRemaining";

	/** @see #getFailure() */
	private static final String FAILURE__PROP = "failure";

	/** @see #getFailureFolder() */
	private static final String FAILURE_FOLDER__PROP = "failureFolder";

	/** @see #isYielding() */
	private static final String YIELDING__PROP = "yielding";

	/** @see #isContentEnabled() */
	private static final String CONTENT_ENABLED__PROP = "contentEnabled";

	/** @see #getContentDescribed() */
	private static final String CONTENT_DESCRIBED__PROP = "contentDescribed";

	/** @see #getContentTotal() */
	private static final String CONTENT_TOTAL__PROP = "contentTotal";

	/** @see #getContentWaiting() */
	private static final String CONTENT_WAITING__PROP = "contentWaiting";

	/** @see #getContentRetryAt() */
	private static final String CONTENT_RETRY_AT__PROP = "contentRetryAt";

	private int _albumsDone = 0;

	private int _albumsTotal = 0;

	private de.haumacher.imageServer.shared.model.CatchUpStep _step = de.haumacher.imageServer.shared.model.CatchUpStep.IDLE;

	private String _folder = "";

	private int _videosRemaining = 0;

	private int _contentRemaining = 0;

	private String _failure = "";

	private String _failureFolder = "";

	private boolean _yielding = false;

	private boolean _contentEnabled = false;

	private int _contentDescribed = 0;

	private int _contentTotal = 0;

	private String _contentWaiting = "";

	private long _contentRetryAt = 0L;

	/**
	 * Creates a {@link CatchUpStatus} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.CatchUpStatus#create()
	 */
	protected CatchUpStatus() {
		super();
	}

	/**
	 * How many albums had their photo steps run (previews, cover, faces, places).
	 */
	public final int getAlbumsDone() {
		return _albumsDone;
	}

	/**
	 * @see #getAlbumsDone()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setAlbumsDone(int value) {
		internalSetAlbumsDone(value);
		return this;
	}

	/** Internal setter for {@link #getAlbumsDone()} without chain call utility. */
	protected final void internalSetAlbumsDone(int value) {
		_albumsDone = value;
	}

	/**
	 * How many albums the background knows of: every folder holding photos or videos.
	 */
	public final int getAlbumsTotal() {
		return _albumsTotal;
	}

	/**
	 * @see #getAlbumsTotal()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setAlbumsTotal(int value) {
		internalSetAlbumsTotal(value);
		return this;
	}

	/** Internal setter for {@link #getAlbumsTotal()} without chain call utility. */
	protected final void internalSetAlbumsTotal(int value) {
		_albumsTotal = value;
	}

	/**
	 * The step running now, {@link CatchUpStep#IDLE} while nothing runs.
	 */
	public final de.haumacher.imageServer.shared.model.CatchUpStep getStep() {
		return _step;
	}

	/**
	 * @see #getStep()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setStep(de.haumacher.imageServer.shared.model.CatchUpStep value) {
		internalSetStep(value);
		return this;
	}

	/** Internal setter for {@link #getStep()} without chain call utility. */
	protected final void internalSetStep(de.haumacher.imageServer.shared.model.CatchUpStep value) {
		if (value == null) throw new IllegalArgumentException("Property 'step' cannot be null.");
		_step = value;
	}

	/**
	 * The folder the step runs on, relative to the root of the space; empty while idle.
	 */
	public final String getFolder() {
		return _folder;
	}

	/**
	 * @see #getFolder()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setFolder(String value) {
		internalSetFolder(value);
		return this;
	}

	/** Internal setter for {@link #getFolder()} without chain call utility. */
	protected final void internalSetFolder(String value) {
		_folder = value;
	}

	/**
	 * How many videos still wait for their teaser or playback rendition.
	 */
	public final int getVideosRemaining() {
		return _videosRemaining;
	}

	/**
	 * @see #getVideosRemaining()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setVideosRemaining(int value) {
		internalSetVideosRemaining(value);
		return this;
	}

	/** Internal setter for {@link #getVideosRemaining()} without chain call utility. */
	protected final void internalSetVideosRemaining(int value) {
		_videosRemaining = value;
	}

	/**
	 * How many photos and videos still wait to be described for the content search of issue #257,
	 * queued or waiting for the embedder; <code>0</code> where the space has it off.
	 */
	public final int getContentRemaining() {
		return _contentRemaining;
	}

	/**
	 * @see #getContentRemaining()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setContentRemaining(int value) {
		internalSetContentRemaining(value);
		return this;
	}

	/** Internal setter for {@link #getContentRemaining()} without chain call utility. */
	protected final void internalSetContentRemaining(int value) {
		_contentRemaining = value;
	}

	/**
	 * What failed last, as the server words it ("previews: IMG_1.jpg: &lt;reason&gt;"); empty while
	 * nothing failed. A failure is retried when its folder changes, never in a loop.
	 */
	public final String getFailure() {
		return _failure;
	}

	/**
	 * @see #getFailure()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setFailure(String value) {
		internalSetFailure(value);
		return this;
	}

	/** Internal setter for {@link #getFailure()} without chain call utility. */
	protected final void internalSetFailure(String value) {
		_failure = value;
	}

	/**
	 * Where {@link #getFailure()} happened, relative to the root of the space.
	 */
	public final String getFailureFolder() {
		return _failureFolder;
	}

	/**
	 * @see #getFailureFolder()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setFailureFolder(String value) {
		internalSetFailureFolder(value);
		return this;
	}

	/** Internal setter for {@link #getFailureFolder()} without chain call utility. */
	protected final void internalSetFailureFolder(String value) {
		_failureFolder = value;
	}

	/**
	 * Whether the background waits because requests are being served (they always go first).
	 */
	public final boolean isYielding() {
		return _yielding;
	}

	/**
	 * @see #isYielding()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setYielding(boolean value) {
		internalSetYielding(value);
		return this;
	}

	/** Internal setter for {@link #isYielding()} without chain call utility. */
	protected final void internalSetYielding(boolean value) {
		_yielding = value;
	}

	/**
	 * Whether the space has the content search of issue #257 on; every <code>content*</code> field
	 * below is empty where it is off.
	 */
	public final boolean isContentEnabled() {
		return _contentEnabled;
	}

	/**
	 * @see #isContentEnabled()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setContentEnabled(boolean value) {
		internalSetContentEnabled(value);
		return this;
	}

	/** Internal setter for {@link #isContentEnabled()} without chain call utility. */
	protected final void internalSetContentEnabled(boolean value) {
		_contentEnabled = value;
	}

	/**
	 * How many photos and videos of the space are described for the content search, of
	 * {@link #getContentTotal()}: what the step found in the cache of every album it looked at, kept up as
	 * it describes. Whether their work happens to be queued plays no part.
	 */
	public final int getContentDescribed() {
		return _contentDescribed;
	}

	/**
	 * @see #getContentDescribed()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setContentDescribed(int value) {
		internalSetContentDescribed(value);
		return this;
	}

	/** Internal setter for {@link #getContentDescribed()} without chain call utility. */
	protected final void internalSetContentDescribed(int value) {
		_contentDescribed = value;
	}

	/**
	 * How many photos and videos of the space are to be described for the content search: every one
	 * with a preview in an album the background looked at since the start.
	 */
	public final int getContentTotal() {
		return _contentTotal;
	}

	/**
	 * @see #getContentTotal()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setContentTotal(int value) {
		internalSetContentTotal(value);
		return this;
	}

	/** Internal setter for {@link #getContentTotal()} without chain call utility. */
	protected final void internalSetContentTotal(int value) {
		_contentTotal = value;
	}

	/**
	 * Why the content search cannot describe photos now, as the server words it (no embedder, the
	 * embedder unreachable, still preparing or downloading its model, refusing the key...); empty while
	 * it can. The albums whose photos wait for it are counted in {@link #getContentRemaining()}.
	 */
	public final String getContentWaiting() {
		return _contentWaiting;
	}

	/**
	 * @see #getContentWaiting()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setContentWaiting(String value) {
		internalSetContentWaiting(value);
		return this;
	}

	/** Internal setter for {@link #getContentWaiting()} without chain call utility. */
	protected final void internalSetContentWaiting(String value) {
		_contentWaiting = value;
	}

	/**
	 * When the embedder is asked again, in milliseconds since the epoch; <code>0</code> while
	 * {@link #getContentWaiting()} is empty.
	 */
	public final long getContentRetryAt() {
		return _contentRetryAt;
	}

	/**
	 * @see #getContentRetryAt()
	 */
	public de.haumacher.imageServer.shared.model.CatchUpStatus setContentRetryAt(long value) {
		internalSetContentRetryAt(value);
		return this;
	}

	/** Internal setter for {@link #getContentRetryAt()} without chain call utility. */
	protected final void internalSetContentRetryAt(long value) {
		_contentRetryAt = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.CatchUpStatus readCatchUpStatus(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.CatchUpStatus result = new de.haumacher.imageServer.shared.model.CatchUpStatus();
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
		out.name(ALBUMS_DONE__PROP);
		out.value(getAlbumsDone());
		out.name(ALBUMS_TOTAL__PROP);
		out.value(getAlbumsTotal());
		out.name(STEP__PROP);
		getStep().writeTo(out);
		out.name(FOLDER__PROP);
		out.value(getFolder());
		out.name(VIDEOS_REMAINING__PROP);
		out.value(getVideosRemaining());
		out.name(CONTENT_REMAINING__PROP);
		out.value(getContentRemaining());
		out.name(FAILURE__PROP);
		out.value(getFailure());
		out.name(FAILURE_FOLDER__PROP);
		out.value(getFailureFolder());
		out.name(YIELDING__PROP);
		out.value(isYielding());
		out.name(CONTENT_ENABLED__PROP);
		out.value(isContentEnabled());
		out.name(CONTENT_DESCRIBED__PROP);
		out.value(getContentDescribed());
		out.name(CONTENT_TOTAL__PROP);
		out.value(getContentTotal());
		out.name(CONTENT_WAITING__PROP);
		out.value(getContentWaiting());
		out.name(CONTENT_RETRY_AT__PROP);
		out.value(getContentRetryAt());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ALBUMS_DONE__PROP: setAlbumsDone(in.nextInt()); break;
			case ALBUMS_TOTAL__PROP: setAlbumsTotal(in.nextInt()); break;
			case STEP__PROP: setStep(de.haumacher.imageServer.shared.model.CatchUpStep.readCatchUpStep(in)); break;
			case FOLDER__PROP: setFolder(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case VIDEOS_REMAINING__PROP: setVideosRemaining(in.nextInt()); break;
			case CONTENT_REMAINING__PROP: setContentRemaining(in.nextInt()); break;
			case FAILURE__PROP: setFailure(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FAILURE_FOLDER__PROP: setFailureFolder(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case YIELDING__PROP: setYielding(in.nextBoolean()); break;
			case CONTENT_ENABLED__PROP: setContentEnabled(in.nextBoolean()); break;
			case CONTENT_DESCRIBED__PROP: setContentDescribed(in.nextInt()); break;
			case CONTENT_TOTAL__PROP: setContentTotal(in.nextInt()); break;
			case CONTENT_WAITING__PROP: setContentWaiting(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CONTENT_RETRY_AT__PROP: setContentRetryAt(in.nextLong()); break;
			default: super.readField(in, field);
		}
	}

}
