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

	/** @see #getFailure() */
	private static final String FAILURE__PROP = "failure";

	/** @see #getFailureFolder() */
	private static final String FAILURE_FOLDER__PROP = "failureFolder";

	/** @see #isYielding() */
	private static final String YIELDING__PROP = "yielding";

	private int _albumsDone = 0;

	private int _albumsTotal = 0;

	private de.haumacher.imageServer.shared.model.CatchUpStep _step = de.haumacher.imageServer.shared.model.CatchUpStep.IDLE;

	private String _folder = "";

	private int _videosRemaining = 0;

	private String _failure = "";

	private String _failureFolder = "";

	private boolean _yielding = false;

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
		out.name(FAILURE__PROP);
		out.value(getFailure());
		out.name(FAILURE_FOLDER__PROP);
		out.value(getFailureFolder());
		out.name(YIELDING__PROP);
		out.value(isYielding());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ALBUMS_DONE__PROP: setAlbumsDone(in.nextInt()); break;
			case ALBUMS_TOTAL__PROP: setAlbumsTotal(in.nextInt()); break;
			case STEP__PROP: setStep(de.haumacher.imageServer.shared.model.CatchUpStep.readCatchUpStep(in)); break;
			case FOLDER__PROP: setFolder(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case VIDEOS_REMAINING__PROP: setVideosRemaining(in.nextInt()); break;
			case FAILURE__PROP: setFailure(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FAILURE_FOLDER__PROP: setFailureFolder(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case YIELDING__PROP: setYielding(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
