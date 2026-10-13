/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import de.haumacher.imageServer.AlbumDate;
import de.haumacher.imageServer.LibraryFiles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The background work of one space, folder by folder, see issue #235.
 *
 * <p>
 * A folder is brought up to date by an ordered list of {@link Step}s, each idempotent and cheap
 * when there is nothing to do. Hashing (see {@link de.haumacher.imageServer.upload.HashIndex}) is
 * the first; issue #236 adds previews, faces and places behind it. A step is only ever run here:
 * on one thread of the space at {@link Thread#MIN_PRIORITY}, one folder at a time, never on the
 * request path.
 * </p>
 *
 * <h2>When a folder is worked on</h2>
 *
 * <ul>
 * <li>By a walk over the whole space, see {@link #process(File)}, which the owner of the pipeline
 * runs once at start-up.</li>
 * <li>When the {@link LibraryWatch} of the space reports it: a folder whose watch reports a new,
 * changed or deleted photograph, a new folder appearing in a watched one (with everything below
 * it), see {@link #notice(File, boolean)}; and when the sweep finds its modification time moved
 * (see {@link #sweep(boolean)}). No restart, no admin action: a library copied in while the server
 * runs is taken care of by itself. A request never notices anything, see issue #253.</li>
 * </ul>
 *
 * <p>
 * <b>Debounced.</b> A folder that is still being copied into must not be worked on for every file
 * that lands in it. A noticed folder waits for a quiet period ({@link #QUIET_MILLIS}); then the
 * {@link #fingerprint(List) fingerprint} of what it holds (names, sizes, modification stamps) is
 * taken, and the folder waits another quiet period; it is worked on only when the fingerprint did
 * not change in between, and waits again otherwise. Watching the files themselves would not do:
 * inside a new folder nobody watches anything yet, and <code>cp -p</code> sets old modification
 * stamps.
 * </p>
 *
 * <h2>The fast lane of issue #258</h2>
 *
 * <p>
 * Ahead of everything a noticed folder and the catch-up wait for, the pipeline works for people
 * who wait. Three priorities, highest first:
 * </p>
 *
 * <ol>
 * <li><b>Just uploaded.</b> The upload hands over the folder and the files it wrote
 * ({@link #uploaded(File, Collection)}): no quiet period, no waiting for the watch. The analysis
 * of the folder first (until it is recorded, the album is answered as being prepared and without
 * the new files), then the thumbnail of every uploaded file, one file per task, then their display
 * renditions ({@link Step#runFile(File, Pass)}), then the folder as a whole: its faces, its tile and
 * everything else, its videos queued as units. A sidecar the server wrote ({@link #edited(File)}: a
 * star, a cover, a crop) joins here at the folder stage: its new cut and large previews are
 * made at once, everything else of the folder costs a stat.</li>
 * <li><b>Demanded.</b> A request answered "not there yet" for a preview, a display rendition or a
 * face crop says which file somebody waits for ({@link #demanded(File, File, Demand)}); it is
 * made next, the oldest demand first. Cheap on the request thread, deduplicated by the file, and
 * bounded ({@link #MAX_DEMANDS}).</li>
 * <li>The catch-up, as above; the units of the videos behind it.</li>
 * </ol>
 *
 * <p>
 * This orders the background's own work. It is no priority among requests, and changes nothing of
 * how the background gives way to them ({@link Background}): every decode of the lane waits for the
 * requests that make something and runs alone. A request answered "not there yet" makes nothing and
 * is not counted there, so neither the stream of those answers nor an app asking again holds up the
 * very work it waits for. A transcode of the video units that runs when urgent work arrives is put
 * aside ({@link UnitStep#putAside()}), as a request's transcode puts it aside. A folder of the lane
 * is not worked on by its watch's notice meanwhile; one noticed later finds the work done.
 * </p>
 *
 * <h2>What is remembered</h2>
 *
 * <p>
 * <code>&lt;space&gt;/.valbum/{@value #FILE_NAME}</code> records per folder which steps are done
 * for which fingerprint, so that a restart resumes:
 * </p>
 *
 * <pre>
 * {"version":2,"folders":{"2020/Trip":{"fingerprint":"&lt;hex&gt;","done":["hash","previews"],
 *   "units":{"videos":["teaser:a.mp4"]}}}}
 * </pre>
 *
 * <p>
 * <code>units</code> (version 2, issue #236) holds what a {@link UnitStep} finished of a folder
 * for that fingerprint, one entry per unit, so that a restart resumes in the middle of an album.
 * It is left out where it is empty, and a file of version 1, which has none, reads as it always
 * did.
 * </p>
 *
 * <h2>Stages, and the catch-up of issue #236</h2>
 *
 * <p>
 * Every step belongs to a {@link Stage}. The walk at start-up runs the {@link Stage#INDEX} steps
 * of every folder first (hashing: the camera-roll sync waits for a complete index, and must not
 * wait for previews and faces), then hands the folders to {@link #catchUp(List)}, newest album
 * first ({@link #newestFirst(List)}), whose {@link Stage#PHOTOS} steps (previews, the cover, faces,
 * places) run one folder per task. A noticed folder runs both stages at once. The units of the
 * {@link Stage#VIDEOS} steps (a teaser or a playback rendition each) wait in a queue of their own
 * and run, one unit per task, only while no photo work is queued or due: a long video queue never
 * holds up the photographs of another album, and a new album is held up by one transcode at most.
 * </p>
 *
 * <p>
 * A step that {@link Step#trustsRecord() trusts} that record is skipped for a folder whose
 * fingerprint is unchanged; hashing does not, its own check against the sidecar is as cheap and is
 * the truth. A step that could not store its result because the folder is not writable
 * ({@link ReadOnlyFolders}) is not run again for that folder in this process until its fingerprint
 * changes. A step that failed is not run again until its fingerprint changes or a back-off is over
 * (issue #251): a permission fixed with <code>chmod</code> changes no fingerprint, and must not wait
 * for a restart. The back-off starts at {@link #RETRY_MILLIS} and doubles with every failure in a row
 * up to {@link #MAX_RETRY_MILLIS}, so a folder that stays unreadable costs one attempt an hour; a
 * repeated failure is logged quietly. A watch reporting a change in the folder (also of permissions
 * only, see {@link #changed(File)}) ends the back-off at once.
 * </p>
 *
 * <p>
 * The {@link Status#getFailure() failure} the status shows describes the present: the most recent
 * one that is still outstanding. A failure is outstanding until the step that reported it does its
 * work again on the folder it ran on (or redoes the same unit) without reporting it again, or the
 * folder is gone; a success elsewhere clears nothing, and a folder that waits for its back-off still
 * counts as failed. At most {@link #MAX_OUTSTANDING} are remembered, the most recent.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class FolderPipeline {

	private static final Logger LOG = Logger.getLogger(FolderPipeline.class.getName());

	/** The name of the progress file below the space's own folder. */
	public static final String FILE_NAME = "pipeline.json";

	/** The version of {@value #FILE_NAME} this build writes; 2 added the units, see issue #236. */
	public static final int VERSION = 2;

	/** How long a noticed folder must stay unchanged before it is worked on. */
	public static final long QUIET_MILLIS = 3000;

	/** How long the pass may go on before the progress file is written again. */
	static final long WRITE_INTERVAL_MILLIS = 2000;

	private static final String VERSION__PROP = "version";

	private static final String FOLDERS__PROP = "folders";

	private static final String FINGERPRINT__PROP = "fingerprint";

	private static final String DONE__PROP = "done";

	private static final String UNITS__PROP = "units";

	/** When a step runs, see {@link FolderPipeline}. */
	public enum Stage {
		/**
		 * What a folder is: which media files it holds, and what each of them is, see issue #253 and
		 * {@link de.haumacher.imageServer.cache.AnalysisRecord}. A request answers nothing else, so
		 * this runs first of all.
		 */
		RECORD,

		/** What the index of the space needs: run for every folder before anything else but the record. */
		INDEX,

		/** What an album needs to be shown without waiting: previews, cover, faces, places. */
		PHOTOS,

		/** Videos, by {@link UnitStep units}, behind every photo of every album. */
		VIDEOS
	}

	/** What a {@link Step} did with a folder. */
	public enum Outcome {
		/** The folder is up to date for the step. */
		DONE,

		/**
		 * The step did its work but could not store it: the folder is not writable. Later steps
		 * still run; this one is not run again in this process until the folder changes.
		 */
		UNWRITABLE,

		/**
		 * The step could not do its work. Later steps, which build on it, do not run; this one is
		 * not run again in this process until the folder changes.
		 */
		FAILED,

		/**
		 * The step waits for something outside the folder that is on its way (the place names of a
		 * country being downloaded, see issue #234). It is not recorded as done and later steps
		 * still run; the step itself {@link FolderPipeline#notice(File, boolean) notices} the folder
		 * again when what it waits for arrives, so nothing polls.
		 */
		WAITING,

		/**
		 * A unit of a {@link UnitStep} was put aside for something more urgent (a request's
		 * transcode, issue #236). It is not done, stays first in its queue and runs again after
		 * {@link FolderPipeline#AGAIN_MILLIS}; the photo steps go on meanwhile. From a step that is no
		 * {@link UnitStep}, the same as {@link #WAITING}.
		 */
		AGAIN
	}

	/** How long a step that failed waits before it is run again on unchanged contents, at first. */
	public static final long RETRY_MILLIS = 2 * 60_000;

	/** How long a step that keeps failing waits at most, see {@link #RETRY_MILLIS}. */
	public static final long MAX_RETRY_MILLIS = 60 * 60_000;

	/** How long the units wait after one was put aside, see {@link Outcome#AGAIN}. */
	public static final long AGAIN_MILLIS = 1000;

	/**
	 * One kind of work done to every folder, see {@link FolderPipeline}.
	 *
	 * <p>
	 * The extension point of issue #236: previews, faces and places are steps behind hashing.
	 * </p>
	 */
	public interface Step {

		/** The name the step is recorded under in {@value FolderPipeline#FILE_NAME}; never changes. */
		String name();

		/**
		 * Brings the given folder up to date; does nothing (cheaply) where it is.
		 *
		 * <p>
		 * Called on the pipeline's thread only. An exception counts as {@link Outcome#FAILED}.
		 * </p>
		 */
		Outcome run(File folder) throws IOException;

		/**
		 * Whether the step may be skipped for a folder whose fingerprint is what it was when the
		 * step last finished there. <code>false</code> for a step whose own check is cheap.
		 */
		default boolean trustsRecord() {
			return true;
		}

		/** When the step runs, see {@link Stage}. */
		default Stage stage() {
			return Stage.PHOTOS;
		}

		/**
		 * Takes the cheap first look at the given folder that the walk at start-up takes of every
		 * folder before any step runs, see {@link FolderPipeline#discover(File)}; nothing by default.
		 */
		default void discover(File folder) throws IOException {
			// Nothing to look at.
		}

		/**
		 * Makes what the given pass of the fast lane asks of the given file, which was just uploaded
		 * (see {@link FolderPipeline#uploaded(File, Collection)} and issue #258); nothing by default.
		 *
		 * <p>
		 * Called on the pipeline's thread only, one file and one pass at a time, before the folder
		 * as a whole is worked on. Does nothing (cheaply) where it is there; a decode is a unit of
		 * {@link Background#decode(java.util.concurrent.Callable)} as everywhere in the background.
		 * </p>
		 */
		default void runFile(File file, Pass pass) throws InterruptedException {
			// Nothing of one file.
		}
	}

	/** What the fast lane makes of each uploaded file, pass by pass, see issue #258. */
	public enum Pass {
		/** What the tile of the file shows: the preview of a photograph, the poster of a video. */
		THUMBNAIL,

		/** What the viewer shows of a picture no browser shows as it is: its display rendition. */
		DISPLAY
	}

	/**
	 * Makes one generated file somebody asked for and was answered "not there yet", see
	 * {@link FolderPipeline#demanded(File, File, Demand)} and issue #258.
	 */
	public interface Demand {

		/** Makes it, decoding what it needs; called inside {@link Background#decode(java.util.concurrent.Callable)}. */
		void make() throws Exception;
	}

	/**
	 * A step of {@link Stage#VIDEOS}: its work in a folder comes in units that run one at a time,
	 * each recorded when it is finished, so that a restart resumes in the middle of an album.
	 */
	public interface UnitStep extends Step {

		@Override
		default Stage stage() {
			return Stage.VIDEOS;
		}

		/** The units of work the given folder holds, in the order they are to run; cheap. */
		List<String> units(File folder);

		/**
		 * Does one unit; does nothing (cheaply) where it is done.
		 *
		 * <p>
		 * {@link Outcome#WAITING} leaves the unit undone for this pass; it runs again when the
		 * folder is worked on again.
		 * </p>
		 */
		Outcome runUnit(File folder, String unit) throws IOException;

		/** What the given unit is the work for, which the status counts, see {@link Status#getVideos()}. */
		default String itemOf(String unit) {
			return unit;
		}

		/**
		 * Puts the unit that runs now aside for urgent work of the fast lane (an upload, a file
		 * somebody waits for, see issue #258), as a request does: {@link #runUnit(File, String)}
		 * then answers {@link Outcome#AGAIN} soon. Called on any thread; nothing by default, the
		 * unit then runs to its end.
		 */
		default void putAside() {
			// A unit that cannot be put aside runs to its end.
		}

		@Override
		default Outcome run(File folder) throws IOException {
			Outcome result = Outcome.DONE;
			for (String unit : units(folder)) {
				Outcome outcome = runUnit(folder, unit);
				if (outcome == Outcome.UNWRITABLE) {
					return outcome;
				}
				if (outcome != Outcome.DONE) {
					result = outcome;
				}
			}
			return result;
		}
	}

	/** What the background work of the space is doing, see {@link #status()}. */
	public static final class Status {

		final int _done;

		final int _total;

		final String _step;

		final String _folder;

		final int _videos;

		final String _failure;

		final String _failureFolder;

		Status(int done, int total, String step, String folder, int videos, String failure, String failureFolder) {
			_done = done;
			_total = total;
			_step = step;
			_folder = folder;
			_videos = videos;
			_failure = failure;
			_failureFolder = failureFolder;
		}

		/** The albums whose photo steps ran since the start, of {@link #getTotal()}. */
		public int getDone() {
			return _done;
		}

		/** The albums the pass knows of: every folder holding images it walked or noticed. */
		public int getTotal() {
			return _total;
		}

		/** The step running now, <code>null</code> while nothing runs. */
		public String getStep() {
			return _step;
		}

		/** The folder it runs on, relative to the space root; <code>null</code> while nothing runs. */
		public String getFolder() {
			return _folder;
		}

		/** How many videos still wait for a unit of a {@link UnitStep}. */
		public int getVideos() {
			return _videos;
		}

		/** Why the most recent failure that is still outstanding failed, <code>null</code> if none is. */
		public String getFailure() {
			return _failure;
		}

		/** Where that failure is, relative to the space root. */
		public String getFailureFolder() {
			return _failureFolder;
		}
	}

	/** What is recorded about one folder. */
	static final class Record {

		final String _fingerprint;

		final Set<String> _done;

		/** What each {@link UnitStep} finished here for the fingerprint, by step name. */
		final Map<String, Set<String>> _units;

		Record(String fingerprint, Set<String> done) {
			this(fingerprint, done, new LinkedHashMap<>());
		}

		Record(String fingerprint, Set<String> done, Map<String, Set<String>> units) {
			_fingerprint = fingerprint;
			_done = done;
			_units = units;
		}
	}

	/**
	 * Why a step is not run again on a folder while its fingerprint is the given one, see
	 * {@link FolderPipeline#_skipped}.
	 */
	private static final class Skip {

		final String _fingerprint;

		final Outcome _outcome;

		/** How many times in a row the step failed here, see {@link FolderPipeline#retryDelay(int)}. */
		final int _count;

		/** The failures, to tell a repeated one, which is logged quietly. */
		final Set<String> _reasons;

		/** From when a {@link Outcome#FAILED} step is run again. */
		volatile long _retryAt;

		/** Whether the folder was noticed again for the retry already. */
		volatile boolean _retrying;

		Skip(String fingerprint, Outcome outcome, int count, Set<String> reasons, long retryAt) {
			_fingerprint = fingerprint;
			_outcome = outcome;
			_count = count;
			_reasons = reasons;
			_retryAt = retryAt;
		}

		/** Whether the step is still not to run on the given contents at the given time. */
		boolean holds(String fingerprint, long now) {
			return _fingerprint.equals(fingerprint) && (_outcome != Outcome.FAILED || now < _retryAt);
		}
	}

	/** A failure that is still outstanding, see {@link FolderPipeline#_outstanding}. */
	private static final class Failure {

		/** Where it failed, relative to the space root, as the status says it. */
		final String _folder;

		final String _reason;

		/** The step that ran, <code>null</code> if none did. */
		final String _step;

		/** The folder the step ran on, <code>null</code> if none ran. */
		final String _origin;

		/** The unit of a {@link UnitStep} that failed, <code>null</code> for a step of a whole folder. */
		final String _unit;

		/** When it was reported, in the order of all failures. */
		final long _seq;

		Failure(String folder, String reason, String step, String origin, String unit, long seq) {
			_folder = folder;
			_reason = reason;
			_step = step;
			_origin = origin;
			_unit = unit;
			_seq = seq;
		}

		/** The same failure reported again replaces the one before. */
		String key() {
			return _step + "\u0000" + _origin + "\u0000" + _unit + "\u0000" + _folder + "\u0000" + _reason;
		}

		/** Whether it was reported by the given step on the given folder (and unit). */
		boolean of(String step, String origin, String unit) {
			return step.equals(_step) && origin.equals(_origin) && Objects.equals(unit, _unit);
		}
	}

	/** The units of one {@link UnitStep} waiting to run in one folder. */
	private static final class Units {

		final File _folder;

		final String _path;

		final String _fingerprint;

		final UnitStep _step;

		final Deque<String> _units;

		boolean _failed;

		boolean _unwritable;

		/** Why units failed in this pass, see {@link Skip#_reasons}. */
		final Set<String> _reasons = new LinkedHashSet<>();

		Units(File folder, String path, String fingerprint, UnitStep step, Deque<String> units) {
			_folder = folder;
			_path = path;
			_fingerprint = fingerprint;
			_step = step;
			_units = units;
		}
	}

	/** A folder waiting for its quiet period. */
	private static final class Pending {

		final File _folder;

		boolean _tree;

		long _due;

		/** The fingerprint taken when the quiet period last ended, <code>null</code> before. */
		String _fingerprint;

		Pending(File folder, boolean tree, long due) {
			_folder = folder;
			_tree = tree;
			_due = due;
		}
	}

	private final Path _root;

	private final Path _file;

	private final String _name;

	private final List<Step> _steps = new CopyOnWriteArrayList<>();

	/** Guarded by this. */
	private final Map<String, Record> _records = new LinkedHashMap<>();

	/**
	 * Folders and steps not to run again while the fingerprint is the given one (a failed one: until
	 * its back-off is over), by step + "\n" + path.
	 */
	private final Map<String, Skip> _skipped = new ConcurrentHashMap<>();

	private volatile long _retryMillis = RETRY_MILLIS;

	private volatile long _maxRetryMillis = MAX_RETRY_MILLIS;

	/** The clock of the back-off, see {@link #RETRY_MILLIS}. */
	private volatile LongSupplier _clock = System::currentTimeMillis;

	/** Guarded by this. */
	private final Map<String, Pending> _pending = new LinkedHashMap<>();

	private boolean _tickScheduled;

	private boolean _dirty;

	private long _written;

	private long _quietMillis = QUIET_MILLIS;

	private ScheduledExecutorService _executor;

	/** The folders waiting for their {@link Stage#PHOTOS} steps, in order; guarded by this. */
	private final Map<String, File> _photoQueue = new LinkedHashMap<>();

	/** The {@link UnitStep} work waiting, by step + "\n" + path, in order; guarded by this. */
	private final Map<String, Units> _unitQueue = new LinkedHashMap<>();

	/** Whether a task of {@link #work()} is queued on the thread; guarded by this. */
	private boolean _workScheduled;

	/** Before when no unit runs, see {@link Outcome#AGAIN}; guarded by this. */
	private long _unitsAfter;

	/** The task of {@link #work()} queued with a delay, <code>null</code> for none; guarded by this. */
	private java.util.concurrent.ScheduledFuture<?> _delayedWork;

	/** The unit step running a unit now, see {@link UnitStep#putAside()}. */
	private volatile UnitStep _runningUnit;

	/**
	 * How many files somebody waits for are remembered at most, see {@link #demanded(File, File, Demand)}:
	 * beyond, the oldest demand is forgotten; the catch-up makes it in its turn.
	 */
	public static final int MAX_DEMANDS = 1000;

	/** The folders of the fast lane whose analysis comes first, see {@link FolderPipeline}; guarded by this. */
	private final Map<String, File> _toRecord = new LinkedHashMap<>();

	/** The uploaded files whose thumbnail the fast lane makes, by absolute path; guarded by this. */
	private final Map<String, File> _thumbnails = new LinkedHashMap<>();

	/** The uploaded files whose display rendition the fast lane makes, by absolute path; guarded by this. */
	private final Map<String, File> _displays = new LinkedHashMap<>();

	/** The folders of the fast lane worked on as a whole, by path; guarded by this. */
	private final Map<String, File> _urgentFolders = new LinkedHashMap<>();

	/** A file somebody waits for, see {@link #demanded(File, File, Demand)}. */
	private static final class Wanted {

		final File _original;

		final File _target;

		final Demand _make;

		Wanted(File original, File target, Demand make) {
			_original = original;
			_target = target;
			_make = make;
		}
	}

	/** The files somebody waits for, the oldest first, by the absolute path of the target; guarded by this. */
	private final Map<String, Wanted> _demands = new LinkedHashMap<>();

	/** The folders holding images the pass knows of, see {@link Status#getTotal()}; guarded by this. */
	private final Set<String> _known = new LinkedHashSet<>();

	/** The ones of {@link #_known} whose photo steps ran; guarded by this. */
	private final Set<String> _settled = new HashSet<>();

	private volatile String _currentStep;

	private volatile String _currentFolder;

	/**
	 * How many outstanding failures are remembered at most, see {@link #_outstanding}: a library with
	 * thousands of broken files keeps the most recent ones, and the oldest are forgotten.
	 */
	public static final int MAX_OUTSTANDING = 1000;

	/**
	 * The failures that are still true as far as is known, the most recent last, see
	 * {@link Status#getFailure()}; by {@link Failure#key()}. Guarded by this.
	 */
	private final Map<String, Failure> _outstanding = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Failure> eldest) {
			return size() > MAX_OUTSTANDING;
		}
	};

	/** How many failures were reported, the sequence of {@link Failure#_seq}; guarded by this. */
	private long _failures;

	/** The watches of the library, see {@link LibraryWatch}; while started. */
	private LibraryWatch _watch;

	/** How many folders are watched at most, see {@link #setMaxWatches(int)}. */
	private int _maxWatches = Integer.MAX_VALUE;

	/**
	 * Creates the pipeline of the given space and reads its progress file; nothing runs until
	 * {@link #start()}.
	 *
	 * @param name
	 *        The name of the thread, for the thread dump.
	 */
	public FolderPipeline(Path root, String name) {
		_root = root.toAbsolutePath().normalize();
		_file = _root.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		_name = name;
		load();
	}

	/**
	 * Adds a step behind the ones there are.
	 *
	 * @throws IllegalArgumentException
	 *         For a step of {@link Stage#VIDEOS} that is no {@link UnitStep}.
	 */
	public void addStep(Step step) {
		if (step.stage() == Stage.VIDEOS && !(step instanceof UnitStep)) {
			throw new IllegalArgumentException("A step of the stage " + Stage.VIDEOS + " works in units: " + step.name());
		}
		_steps.add(step);
	}

	/** Takes a step out again. */
	public void removeStep(Step step) {
		_steps.remove(step);
	}

	/** The steps, in the order they run. */
	public List<Step> steps() {
		return new ArrayList<>(_steps);
	}

	/**
	 * Watches at most the given number of folders from the next {@link #start()} on; for the tests,
	 * which simulate the system's limit of watches, see {@link LibraryWatch}.
	 */
	public void setMaxWatches(int maxWatches) {
		_maxWatches = maxWatches;
	}

	/** The watches of the library while started, <code>null</code> otherwise; for the tests. */
	public synchronized LibraryWatch watch() {
		return _watch;
	}

	/** Sets the quiet period, for the tests. */
	public void setQuietMillis(long millis) {
		_quietMillis = millis;
	}

	/**
	 * Sets how long a step that failed waits first and at most before it runs again, see
	 * {@link #RETRY_MILLIS}; for the tests.
	 */
	public void setRetryMillis(long first, long max) {
		_retryMillis = first;
		_maxRetryMillis = max;
	}

	/** Replaces the clock of the back-off, see {@link #RETRY_MILLIS}; for the tests. */
	void setClock(LongSupplier clock) {
		_clock = clock;
	}

	/** How long a step waits after the given number of failures in a row. */
	long retryDelay(int count) {
		long result = Math.max(1, _retryMillis);
		for (int n = 1; n < count && result < _maxRetryMillis; n++) {
			result *= 2;
		}
		return Math.min(result, _maxRetryMillis);
	}

	/** The file the progress is recorded in. */
	public File getFile() {
		return _file.toFile();
	}

	// --- The thread. ---

	/**
	 * Starts the pipeline's thread and watches the whole library, see {@link LibraryWatch}.
	 *
	 * @return Whether it was started by this call.
	 */
	public synchronized boolean start() {
		if (_executor != null) {
			return false;
		}
		_executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, _name);
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		_watch = new LibraryWatch(_root, this, _name, _maxWatches);
		_watch.start();
		STARTED.add(this);
		// The first look at the folders, before anything else runs: what changes from now on is
		// what the next sweep finds.
		_executor.execute(() -> sweep(true));
		long every = Math.max(1, _sweepMillis);
		_executor.scheduleWithFixedDelay(() -> sweep(false), every, every, TimeUnit.MILLISECONDS);
		return true;
	}

	/** Whether {@link #start()} was called and {@link #shutdown()} was not. */
	public synchronized boolean isStarted() {
		return _executor != null;
	}

	/** Runs the given work on the pipeline's thread, behind what is queued. */
	public synchronized void execute(Runnable work) {
		if (_executor == null) {
			throw new IllegalStateException("Not started.");
		}
		_executor.execute(work);
	}

	/** The pipelines started in this process, see {@link #edited(File)}. */
	private static final java.util.List<FolderPipeline> STARTED = new java.util.concurrent.CopyOnWriteArrayList<>();

	/**
	 * Hands the given folder to the fast lane of the pipeline of the space it lies in: the server
	 * wrote its sidecar, see issues #256 and #258. A crop, a star, a picture chosen, a face tagged
	 * change which generated files a request asks for without changing a photograph (a cut preview,
	 * the large preview of a starred tile, a face crop, issue #260); the background makes them at
	 * once, and a request never does. Cheap and safe on a request thread.
	 */
	public static void edited(File folder) {
		FolderPipeline pipeline = owner(folder);
		if (pipeline != null) {
			pipeline.urgent(folder, Collections.<File> emptyList());
		}
	}

	/**
	 * Hands the given files, just uploaded into the given folder, to the fast lane of the pipeline
	 * of the space, see {@link FolderPipeline} and issue #258: no quiet period, nothing waits for the
	 * watch. Cheap and safe on a request thread; a multi-file upload hands over request by request,
	 * and what is handed over twice is queued once.
	 */
	public static void uploaded(File folder, Collection<File> files) {
		FolderPipeline pipeline = owner(folder);
		if (pipeline != null) {
			pipeline.urgent(folder, files);
		}
	}

	/**
	 * Tells the pipeline of the space that somebody waits for the given generated file of the given
	 * original: a request was answered "not there yet", see {@link FolderPipeline} and issue #258.
	 * The request makes nothing; the background makes it ahead of its catch-up.
	 *
	 * <p>
	 * Cheap and safe on a request thread: nothing is read, nothing decoded. A file asked for again
	 * keeps its place; beyond {@link #MAX_DEMANDS} the oldest demand is forgotten.
	 * </p>
	 *
	 * @return Whether a started pipeline took the demand.
	 */
	public static boolean demanded(File original, File target, Demand make) {
		FolderPipeline pipeline = owner(original.getParentFile());
		return pipeline != null && pipeline.demand(original, target, make);
	}

	/** The started pipeline of the space the given folder lies in, the innermost; <code>null</code> for none. */
	private static FolderPipeline owner(File folder) {
		if (folder == null) {
			return null;
		}
		FolderPipeline result = null;
		for (FolderPipeline pipeline : STARTED) {
			String path = pipeline.relative(folder);
			if (path != null && LibraryFiles.isLibraryPath(path)
				&& (result == null || pipeline._root.startsWith(result._root))) {
				result = pipeline;
			}
		}
		return result;
	}

	/** Stops the thread; what is done is written. */
	public void shutdown() {
		STARTED.remove(this);
		ScheduledExecutorService executor;
		LibraryWatch watch;
		synchronized (this) {
			executor = _executor;
			_executor = null;
			watch = _watch;
			_watch = null;
			_pending.clear();
			// Nothing is lost: the next walk finds the same work again, and what is done is recorded.
			_photoQueue.clear();
			_unitQueue.clear();
			_toRecord.clear();
			_thumbnails.clear();
			_displays.clear();
			_urgentFolders.clear();
			_demands.clear();
			_delayedWork = null;
			_workScheduled = false;
		}
		if (watch != null) {
			watch.close();
		}
		if (executor != null) {
			executor.shutdownNow();
			try {
				executor.awaitTermination(2, TimeUnit.SECONDS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		flush();
	}

	/**
	 * Waits until nothing is waiting for its quiet period, nothing is queued and nothing is being
	 * worked on; for the tests.
	 */
	public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
		long end = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < end) {
			ScheduledExecutorService executor;
			boolean pending;
			synchronized (this) {
				executor = _executor;
				pending = !idle();
			}
			if (executor == null) {
				return true;
			}
			if (!pending) {
				try {
					executor.submit(() -> {
						// Being run at all is the answer.
					}).get(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
				} catch (ExecutionException | RejectedExecutionException ex) {
					return true;
				} catch (TimeoutException ex) {
					return false;
				}
				synchronized (this) {
					if (idle()) {
						return true;
					}
				}
			}
			Thread.sleep(20);
		}
		return false;
	}

	/** Whether nothing waits; called while synchronized. */
	private boolean idle() {
		return _pending.isEmpty() && _photoQueue.isEmpty() && _unitQueue.isEmpty() && !hasUrgent() && !_workScheduled;
	}

	// --- The sweep. ---

	/**
	 * How often the folders of a space are swept by default, see {@link #sweep(boolean)}: a minute.
	 *
	 * <p>
	 * A sweep of a folder nothing happened in is one <code>stat</code> of it, so a sweep of 10 000
	 * folders costs a few milliseconds (measured, see issue #236), and once a minute is nothing. A
	 * minute is also about as long as one may wait before photos copied into an unbrowsed folder are
	 * hashed: the camera-roll sync of the app uploads what the index does not know.
	 * </p>
	 */
	public static final long DEFAULT_SWEEP_MILLIS = 60_000;

	private static volatile long _defaultSweepMillis = DEFAULT_SWEEP_MILLIS;

	/** Sets how often the pipelines started from now on sweep, see <code>--sweep-seconds</code>. */
	public static void setDefaultSweepMillis(long millis) {
		if (millis < 1) {
			throw new IllegalArgumentException("A sweep needs a positive interval: " + millis);
		}
		_defaultSweepMillis = millis;
	}

	private long _sweepMillis = _defaultSweepMillis;

	/** Sets how often this pipeline sweeps, before {@link #start()}; for the tests. */
	public void setSweepMillis(long millis) {
		_sweepMillis = millis;
	}

	/** How the sweep looks at the disk; a seam, so that a test can count what it asks. */
	public interface Disk {

		/** The modification time of the given directory, <code>0</code> if it is gone. */
		long modified(File directory);

		/** The directories directly in the given one that are part of the library. */
		File[] folders(File directory);
	}

	/** The disk itself. */
	public static final Disk DISK = new Disk() {
		@Override
		public long modified(File directory) {
			return directory.lastModified();
		}

		@Override
		public File[] folders(File directory) {
			File[] result = directory.listFiles(f -> f.isDirectory() && !LibraryFiles.isIgnored(f));
			return result == null ? new File[0] : result;
		}
	};

	private Disk _disk = DISK;

	/** Replaces how the sweep looks at the disk; for the tests. */
	public void setDisk(Disk disk) {
		_disk = disk;
	}

	/** What the last sweep found of a folder. */
	private static final class Seen {

		final long _modified;

		final File[] _folders;

		Seen(long modified, File[] folders) {
			_modified = modified;
			_folders = folders;
		}
	}

	/** What the last sweep found, by path; <code>null</code> before the first. Pipeline thread only. */
	private Map<String, Seen> _seen;

	/**
	 * Looks at the modification time of every folder of the space, see issue #236.
	 *
	 * <p>
	 * The directory watches of the {@link LibraryWatch} are the fast path, but they are bounded (the
	 * operating system grants a limited number), and a folder beyond the bound is watched by nobody. This is
	 * the guarantee for everything else: a library copied in while nobody browses is hashed and
	 * prepared all the same. A folder's modification time changes when an entry is added to it,
	 * removed or renamed in it; a folder whose time moved since the last sweep is noticed (with the
	 * usual quiet period), a new folder is noticed with everything below it, and a folder that is
	 * gone is forgotten ({@link #gone(String)}).
	 * </p>
	 *
	 * <p>
	 * Cheap: one <code>stat</code> per folder; a folder is listed only when its time moved, and no
	 * file is ever opened or even looked at here. Runs on the pipeline's thread; a round that falls
	 * while requests are being served is left out, as everything in the background gives way to
	 * them. The first look at start-up never waits: it is the baseline the walk is compared with. A
	 * file changed in place (written over
	 * without a new name) leaves its folder's time alone; that is what the watches are for.
	 * </p>
	 *
	 * @param baseline
	 *        Whether this is the first look, which notices nothing.
	 */
	void sweep(boolean baseline) {
		if (!baseline && Background.busy()) {
			// Requests are being served: this round is left out, never waited for, so that it holds
			// up nothing else on this thread; the next one comes an interval later.
			return;
		}
		Map<String, Seen> before = _seen;
		Map<String, Seen> now = new HashMap<>();
		Set<String> fresh = new HashSet<>();
		Deque<File> pending = new ArrayDeque<>();
		pending.add(_root.toFile());
		while (!pending.isEmpty()) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			File folder = pending.removeFirst();
			String path = relative(folder);
			if (path == null || (!path.isEmpty() && !LibraryFiles.isLibraryPath(path))) {
				continue;
			}
			long modified = _disk.modified(folder);
			if (modified == 0) {
				continue;
			}
			Seen last = before == null ? null : before.get(path);
			File[] folders;
			if (last != null && last._modified == modified) {
				folders = last._folders;
			} else {
				folders = _disk.folders(folder);
				if (!baseline && before != null) {
					if (last == null) {
						fresh.add(path);
						if (!fresh.contains(parentOf(path))) {
							// New, and everything below it with it.
							notice(folder, true);
						}
					} else {
						notice(folder, false);
					}
				}
			}
			now.put(path, new Seen(modified, folders));
			pending.addAll(Arrays.asList(folders));
		}
		if (!baseline && before != null) {
			for (String path : before.keySet()) {
				if (!now.containsKey(path)) {
					gone(path);
				}
			}
		}
		_seen = now;
		if (!baseline) {
			retryDue();
		}
		synchronized (this) {
			persist(false);
		}
	}

	/**
	 * Notices the folders with a step whose back-off after a failure is over, see
	 * {@link #RETRY_MILLIS}; each once per back-off. Called by the sweep.
	 */
	void retryDue() {
		long now = _clock.getAsLong();
		Set<String> paths = new LinkedHashSet<>();
		for (Map.Entry<String, Skip> entry : _skipped.entrySet()) {
			Skip skip = entry.getValue();
			if (skip._outcome == Outcome.FAILED && !skip._retrying && now >= skip._retryAt) {
				skip._retrying = true;
				paths.add(pathOf(entry.getKey()));
			}
		}
		for (String path : paths) {
			LOG.fine("Retrying the background work of '" + path + "' after a failure.");
			notice(path.isEmpty() ? _root.toFile() : _root.resolve(path).toFile(), false);
		}
	}

	/** The folder path of a key of {@link #_skipped} or {@link #_unitQueue}. */
	private static String pathOf(String key) {
		return key.substring(key.indexOf('\n') + 1);
	}

	/**
	 * Ends the back-off of the steps that failed on the given folder, and notices it: a watch saw a
	 * photograph in it change, maybe just its permissions, see
	 * {@link LibraryWatch}.
	 */
	public void changed(File folder) {
		String path = relative(folder);
		if (path == null) {
			return;
		}
		long now = _clock.getAsLong();
		for (Map.Entry<String, Skip> entry : _skipped.entrySet()) {
			Skip skip = entry.getValue();
			if (skip._outcome == Outcome.FAILED && pathOf(entry.getKey()).equals(path)) {
				skip._retryAt = Math.min(skip._retryAt, now);
				skip._retrying = true;
			}
		}
		notice(folder, false);
	}

	private static String parentOf(String path) {
		int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	/**
	 * Forgets a folder that is gone (deleted, or moved away; where it went is new and noticed): its
	 * record, its queued work, its place in the status. Nothing of it counts as failed.
	 */
	synchronized void gone(String path) {
		if (_records.remove(path) != null) {
			_dirty = true;
		}
		_pending.remove(path);
		_photoQueue.remove(path);
		_toRecord.remove(path);
		_urgentFolders.remove(path);
		_known.remove(path);
		_settled.remove(path);
		_unitQueue.values().removeIf(work -> work._path.equals(path));
		_skipped.keySet().removeIf(key -> pathOf(key).equals(path));
		_outstanding.values()
			.removeIf(failure -> path.equals(failure._folder) || path.equals(failure._origin));
	}

	// --- Noticing. ---

	/**
	 * Takes note of a folder that was loaded or changed; it is worked on after its quiet period.
	 *
	 * <p>
	 * Cheap, and safe on a request thread: nothing is read here. A folder outside the space, the
	 * server's own and what is no part of the library are ignored, and so is everything before
	 * {@link #start()}.
	 * </p>
	 *
	 * @param tree
	 *        Whether everything below the folder is to be worked on, too.
	 */
	public void notice(File folder, boolean tree) {
		String path = relative(folder);
		if (path == null || !LibraryFiles.isLibraryPath(path)) {
			return;
		}
		synchronized (this) {
			if (_executor == null) {
				return;
			}
			long due = System.currentTimeMillis() + _quietMillis;
			Pending pending = _pending.get(path);
			if (pending == null) {
				_pending.put(path, new Pending(folder, tree, due));
			} else {
				pending._due = due;
				pending._tree |= tree;
			}
			schedule(_quietMillis);
		}
	}

	/** Schedules the next look at the pending folders; called while synchronized. */
	private void schedule(long delay) {
		if (_tickScheduled || _executor == null) {
			return;
		}
		try {
			_executor.schedule(this::tick, Math.max(0, delay), TimeUnit.MILLISECONDS);
			_tickScheduled = true;
		} catch (RejectedExecutionException ex) {
			// Stopping.
		}
	}

	/** Works on the pending folders whose quiet period is over and whose contents stood still. */
	private void tick() {
		List<Pending> due = new ArrayList<>();
		synchronized (this) {
			_tickScheduled = false;
			long now = System.currentTimeMillis();
			for (Pending pending : _pending.values()) {
				if (pending._due <= now) {
					due.add(pending);
				}
			}
		}
		for (Pending pending : due) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			List<File> folders = pending._tree ? tree(pending._folder) : Arrays.asList(pending._folder);
			String fingerprint = fingerprint(folders);
			boolean ready;
			synchronized (this) {
				String path = relative(pending._folder);
				if (_pending.get(path) != pending) {
					continue;
				}
				ready = fingerprint.equals(pending._fingerprint) && pending._due <= System.currentTimeMillis();
				if (!pending._tree && inLane(path)) {
					// The fast lane works on it as a whole anyway, see issue #258.
					_pending.remove(path);
					continue;
				}
				if (ready) {
					_pending.remove(path);
				} else {
					// Still moving, or seen for the first time: one more quiet period.
					pending._fingerprint = fingerprint;
					pending._due = Math.max(pending._due, System.currentTimeMillis() + _quietMillis);
				}
			}
			if (ready) {
				if (pending._tree) {
					// A tree copied in: indexed at once, its albums brought up to date newest first,
					// before what the walk at start-up still has in its queue.
					for (File folder : folders) {
						if (Thread.currentThread().isInterrupted()) {
							return;
						}
						process(folder, Stage.INDEX);
					}
					queueFirst(newestFirst(folders));
				} else {
					for (File folder : folders) {
						if (Thread.currentThread().isInterrupted()) {
							return;
						}
						process(folder);
					}
				}
			}
		}
		synchronized (this) {
			persist(false);
			if (!_pending.isEmpty()) {
				long next = Long.MAX_VALUE;
				for (Pending pending : _pending.values()) {
					next = Math.min(next, pending._due);
				}
				schedule(next - System.currentTimeMillis());
			}
		}
	}

	// --- The work. ---

	/**
	 * Runs the {@link Stage#INDEX} and {@link Stage#PHOTOS} steps on the given folder, in the
	 * calling thread, and queues the units of its {@link Stage#VIDEOS} steps.
	 *
	 * <p>
	 * What a noticed folder ends in. A folder without images is skipped by no step here: whether
	 * there is anything to do is each step's own question.
	 * </p>
	 */
	public void process(File folder) {
		process(folder, Stage.PHOTOS);
	}

	/**
	 * Runs the steps up to the given stage on the given folder, in the calling thread; the
	 * {@link Stage#VIDEOS} steps are queued, never run here.
	 */
	public void process(File folder, Stage upTo) {
		String path = relative(folder);
		if (path == null) {
			return;
		}
		if (!folder.isDirectory()) {
			// Gone meanwhile: deleted, or moved by hand to where it is new and noticed.
			gone(path);
			return;
		}
		String fingerprint = fingerprint(Arrays.asList(folder));
		Set<String> done = new LinkedHashSet<>();
		Map<String, Set<String>> units = new LinkedHashMap<>();
		synchronized (this) {
			Record record = _records.get(path);
			if (record != null && record._fingerprint.equals(fingerprint)) {
				done.addAll(record._done);
				for (Map.Entry<String, Set<String>> entry : record._units.entrySet()) {
					units.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
				}
			}
		}
		boolean stopped = false;
		for (Step step : byStage()) {
			if (step.stage() == Stage.VIDEOS || step.stage().compareTo(upTo) > 0) {
				continue;
			}
			if (Thread.currentThread().isInterrupted()) {
				stopped = true;
				break;
			}
			String name = step.name();
			if (step.trustsRecord() && done.contains(name)) {
				continue;
			}
			String skipKey = name + "\n" + path;
			Skip skipped = _skipped.get(skipKey);
			if (skipped != null && skipped.holds(fingerprint, _clock.getAsLong())) {
				// Failed (a moment ago) or not storable for exactly these contents in this process
				// already.
				done.remove(name);
				if (skipped._outcome == Outcome.FAILED) {
					stopped = true;
					break;
				}
				continue;
			}
			Outcome outcome;
			Exception problem = null;
			long failures = failures();
			_currentStep = name;
			_currentFolder = path;
			try {
				outcome = step.run(folder);
			} catch (IOException | RuntimeException ex) {
				outcome = Outcome.FAILED;
				problem = ex;
			} finally {
				_currentStep = null;
				_currentFolder = null;
			}
			if (outcome == Outcome.DONE) {
				_skipped.remove(skipKey);
				done.add(name);
				succeeded(path, name, null, failures);
				continue;
			}
			if (outcome == Outcome.WAITING || outcome == Outcome.AGAIN) {
				_skipped.remove(skipKey);
				done.remove(name);
				continue;
			}
			done.remove(name);
			if (outcome == Outcome.UNWRITABLE) {
				_skipped.put(skipKey, new Skip(fingerprint, outcome, 0, Collections.emptySet(), Long.MAX_VALUE));
				// The step did its work: what it failed for before is no more; that it cannot store it
				// is said by ReadOnlyFolders.
				succeeded(path, name, null, failures);
				ReadOnlyFolders.report(folder, "the results of '" + name + "'");
				continue;
			}
			String reason = name + ": " + (problem == null ? "failed"
				: problem.getMessage() == null ? problem.getClass().getSimpleName() : problem.getMessage());
			Skip skip = failedSkip(skipKey, fingerprint, Collections.singleton(reason));
			_skipped.put(skipKey, skip);
			failed(path, reason, name, path, null);
			if (problem != null) {
				LOG.log(repeated(skipped, reason) ? Level.FINE : Level.WARNING, "Step '" + name + "' failed on '"
					+ folder.getAbsolutePath() + "' (tried again in " + minutes(skip) + "): " + problem.getMessage(),
					problem);
			}
			stopped = true;
			break;
		}
		boolean photos = upTo.compareTo(Stage.PHOTOS) >= 0;
		List<Units> work = photos && !stopped ? unitsOf(folder, path, fingerprint, done, units) : new ArrayList<>();
		synchronized (this) {
			Record before = _records.get(path);
			if (before == null || !before._fingerprint.equals(fingerprint) || !before._done.equals(done)
				|| !before._units.equals(units)) {
				_records.put(path, new Record(fingerprint, done, units));
				_dirty = true;
			}
			if (photos) {
				_photoQueue.remove(path);
				if (hasImages(folder)) {
					_known.add(path);
					_settled.add(path);
				}
				for (Units each : work) {
					_unitQueue.put(each._step.name() + "\n" + path, each);
				}
				scheduleWork();
			}
			persist(false);
		}
	}

	/** The steps in the order they run: by {@link Stage}, then in the order they were added. */
	private List<Step> byStage() {
		List<Step> result = new ArrayList<>(_steps);
		result.sort(Comparator.comparing(Step::stage));
		return result;
	}

	/**
	 * Takes the cheap first look of every step at the given folder, see {@link Step#discover(File)}:
	 * what the walk at start-up does for every folder, root first, before any step runs, so that a
	 * library is known as a whole long before every album of it is analysed.
	 */
	public void discover(File folder) {
		if (relative(folder) == null || !folder.isDirectory()) {
			return;
		}
		for (Step step : byStage()) {
			try {
				step.discover(folder);
			} catch (IOException | RuntimeException ex) {
				LOG.log(Level.WARNING, "Step '" + step.name() + "' cannot look at '" + folder.getAbsolutePath() + "': "
					+ ex.getMessage(), ex);
			}
		}
	}

	/**
	 * Tells the status about a failure a step did not fail for: one photograph of an album that
	 * cannot be read does not hold up the album, and is said all the same.
	 */
	public void reportFailure(File folder, String reason) {
		String path = relative(folder);
		failed(path == null ? folder.getAbsolutePath() : path, reason, _currentStep, _currentFolder, null);
	}

	/**
	 * Remembers a failure as outstanding, the most recent one, for the status.
	 *
	 * @param step
	 *        The step that ran, <code>null</code> if none did.
	 * @param origin
	 *        The folder it ran on, which may be another than the given path (a cover is made for the
	 *        folders above).
	 * @param unit
	 *        The unit of a {@link UnitStep} that failed, <code>null</code> for a whole folder.
	 */
	private synchronized void failed(String path, String reason, String step, String origin, String unit) {
		if (unit != null) {
			// A unit has one outcome: the one before is replaced.
			_outstanding.values().removeIf(failure -> failure.of(step, origin, unit));
		}
		Failure failure = new Failure(path, reason, step, origin, unit, ++_failures);
		_outstanding.remove(failure.key());
		_outstanding.put(failure.key(), failure);
	}

	/** How many failures were reported so far, see {@link #succeeded}. */
	private synchronized long failures() {
		return _failures;
	}

	/**
	 * The given step did its work on the given folder (or unit): what it reported there before the
	 * given count was taken is no more. What it reported in this run stands, it is the present.
	 */
	private synchronized void succeeded(String origin, String step, String unit, long failures) {
		_outstanding.values().removeIf(failure -> failure._seq <= failures && failure.of(step, origin, unit));
	}

	/**
	 * The record of a step that just failed on the given contents: it is run again after a back-off
	 * that grows with every failure in a row, see {@link #RETRY_MILLIS}.
	 */
	private Skip failedSkip(String key, String fingerprint, Set<String> reasons) {
		Skip before = _skipped.get(key);
		int count = before != null && before._outcome == Outcome.FAILED ? before._count + 1 : 1;
		return new Skip(fingerprint, Outcome.FAILED, count, reasons, _clock.getAsLong() + retryDelay(count));
	}

	/** Whether the given failure is the one the step failed for last time; logged quietly then. */
	private static boolean repeated(Skip before, String reason) {
		return before != null && before._outcome == Outcome.FAILED && before._reasons.contains(reason);
	}

	/** The back-off of the given record, for the log. */
	private String minutes(Skip skip) {
		long millis = Math.max(0, skip._retryAt - _clock.getAsLong());
		return millis >= 60_000 ? (millis / 60_000) + " min" : (millis / 1000) + " s";
	}

	/**
	 * The units of the {@link UnitStep}s still to do in the given folder; a step with nothing left
	 * is recorded as done in the given set.
	 */
	private List<Units> unitsOf(File folder, String path, String fingerprint, Set<String> done,
			Map<String, Set<String>> finished) {
		List<Units> result = new ArrayList<>();
		for (Step step : _steps) {
			if (step.stage() != Stage.VIDEOS) {
				continue;
			}
			String name = step.name();
			if (step.trustsRecord() && done.contains(name)) {
				continue;
			}
			Skip skipped = _skipped.get(name + "\n" + path);
			if (skipped != null && skipped.holds(fingerprint, _clock.getAsLong())) {
				continue;
			}
			UnitStep unitStep = (UnitStep) step;
			Deque<String> open = new ArrayDeque<>();
			Set<String> already = finished.getOrDefault(name, Collections.emptySet());
			for (String unit : unitStep.units(folder)) {
				if (!already.contains(unit)) {
					open.add(unit);
				}
			}
			if (open.isEmpty()) {
				done.add(name);
				finished.remove(name);
				continue;
			}
			done.remove(name);
			result.add(new Units(folder, path, fingerprint, unitStep, open));
		}
		return result;
	}

	/** Whether the given folder holds an image or a video. */
	private static boolean hasImages(File folder) {
		File[] files = folder.listFiles(f -> f.isFile() && ResourceCache.isImage(f));
		return files != null && files.length > 0;
	}

	// --- The fast lane of issue #258. ---

	/**
	 * Queues the given folder in the fast lane, with the given files just uploaded into it, see
	 * {@link #uploaded(File, Collection)} and {@link #edited(File)}.
	 */
	void urgent(File folder, Collection<File> files) {
		String path = relative(folder);
		if (path == null || !LibraryFiles.isLibraryPath(path)) {
			return;
		}
		synchronized (this) {
			if (_executor == null) {
				return;
			}
			if (!files.isEmpty()) {
				// The album answers only what is recorded: an upload into a folder recorded before
				// is recorded by the upload itself, anything else of it here, first.
				_toRecord.put(path, folder);
				for (File file : files) {
					String key = file.getAbsolutePath();
					_thumbnails.put(key, file);
					_displays.put(key, file);
				}
			}
			_urgentFolders.put(path, folder);
			hurry();
		}
	}

	/** Takes a demand, see {@link #demanded(File, File, Demand)}. */
	boolean demand(File original, File target, Demand make) {
		synchronized (this) {
			if (_executor == null) {
				return false;
			}
			String key = target.getAbsolutePath();
			if (!_demands.containsKey(key)) {
				_demands.put(key, new Wanted(original, target, make));
				while (_demands.size() > MAX_DEMANDS) {
					_demands.remove(_demands.keySet().iterator().next());
				}
				// Asked again while it waits: hurried already.
				hurry();
			}
			return true;
		}
	}

	/**
	 * Has the urgent work just queued run next: a task of {@link #work()} that waits for the units'
	 * pause runs at once, and a unit running now is put aside; called while synchronized.
	 */
	private void hurry() {
		if (_delayedWork != null && _delayedWork.cancel(false)) {
			_delayedWork = null;
			_workScheduled = false;
		}
		scheduleWork();
		UnitStep running = _runningUnit;
		if (running != null) {
			running.putAside();
		}
	}

	/** Whether work of the fast lane waits; called while synchronized. */
	private boolean hasUrgent() {
		return !_toRecord.isEmpty() || !_thumbnails.isEmpty() || !_displays.isEmpty() || !_urgentFolders.isEmpty()
			|| !_demands.isEmpty();
	}

	/** Whether the given folder waits in the fast lane, which works on it as a whole; while synchronized. */
	private boolean inLane(String path) {
		return _toRecord.containsKey(path) || _urgentFolders.containsKey(path);
	}

	/** How many pieces of work wait in the fast lane; for the tests. */
	public synchronized int urgentWork() {
		return _toRecord.size() + _thumbnails.size() + _displays.size() + _urgentFolders.size() + _demands.size();
	}

	/**
	 * Takes the next piece of the fast lane out of it, in the order of {@link FolderPipeline}:
	 * <code>null</code> when there is none. Called while synchronized.
	 */
	private Runnable nextUrgent() {
		if (!_toRecord.isEmpty()) {
			File folder = poll(_toRecord);
			return () -> process(folder, Stage.RECORD);
		}
		if (!_thumbnails.isEmpty()) {
			File file = poll(_thumbnails);
			return () -> runFile(file, Pass.THUMBNAIL);
		}
		if (!_displays.isEmpty()) {
			File file = poll(_displays);
			return () -> runFile(file, Pass.DISPLAY);
		}
		if (!_urgentFolders.isEmpty()) {
			File folder = poll(_urgentFolders);
			return () -> process(folder);
		}
		if (!_demands.isEmpty()) {
			Wanted wanted = poll(_demands);
			return () -> make(wanted);
		}
		return null;
	}

	/** Takes the first entry out of the given queue. */
	private static <T> T poll(Map<String, T> queue) {
		Map.Entry<String, T> first = queue.entrySet().iterator().next();
		queue.remove(first.getKey());
		return first.getValue();
	}

	/** Runs the given pass of every step on the given uploaded file, see {@link Step#runFile(File, Pass)}. */
	private void runFile(File file, Pass pass) {
		if (!file.isFile()) {
			return;
		}
		String path = relative(file.getParentFile());
		for (Step step : byStage()) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			_currentStep = step.name();
			_currentFolder = path;
			try {
				step.runFile(file, pass);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException ex) {
				// The folder as a whole comes next, and reports what it cannot do.
				LOG.log(Level.WARNING, "Step '" + step.name() + "' failed on the upload '" + file.getAbsolutePath()
					+ "': " + ex.getMessage(), ex);
			} finally {
				_currentStep = null;
				_currentFolder = null;
			}
		}
	}

	/** Makes the given file somebody waits for, unless it is there meanwhile. */
	private void make(Wanted wanted) {
		if (de.haumacher.imageServer.PreviewCache.present(wanted._target) || !wanted._original.isFile()) {
			return;
		}
		_currentFolder = relative(wanted._original.getParentFile());
		try {
			Background.decode(() -> {
				wanted._make.make();
				return null;
			});
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		} catch (Exception ex) {
			// Remembered by the maker as the failure a request is answered with, see issue #256.
			LOG.log(Level.FINE, "Cannot make '" + wanted._target.getName() + "': " + ex.getMessage(), ex);
		} finally {
			_currentFolder = null;
		}
	}

	// --- The catch-up of issue #236. ---

	/**
	 * Queues the {@link Stage#PHOTOS} steps of the given folders behind what is queued, in the given
	 * order; the walk at start-up hands over the library here, see {@link #newestFirst(List)}.
	 */
	public synchronized void catchUp(List<File> folders) {
		for (File folder : folders) {
			String path = relative(folder);
			if (path == null) {
				continue;
			}
			_photoQueue.put(path, folder);
			_known.add(path);
		}
		scheduleWork();
	}

	/**
	 * Counts the given folders among the albums of the status before their photo steps are queued:
	 * while the walk at start-up hashes, the admin sees how many albums there are to prepare.
	 */
	public synchronized void know(List<File> folders) {
		for (File folder : folders) {
			String path = relative(folder);
			if (path != null) {
				_known.add(path);
			}
		}
	}

	/** Queues the given folders in front of what is queued, in the given order. */
	private synchronized void queueFirst(List<File> folders) {
		Map<String, File> queue = new LinkedHashMap<>();
		for (File folder : folders) {
			String path = relative(folder);
			if (path != null) {
				queue.put(path, folder);
				if (hasImages(folder)) {
					_known.add(path);
					_settled.remove(path);
				}
			}
		}
		for (Map.Entry<String, File> entry : _photoQueue.entrySet()) {
			queue.putIfAbsent(entry.getKey(), entry.getValue());
		}
		_photoQueue.clear();
		_photoQueue.putAll(queue);
		scheduleWork();
	}

	/**
	 * The given folders, the newest album first: by the date the listing shows them by
	 * (<code>AlbumDate.ofFolder</code>, read from the sidecar and the name, no image opened),
	 * descending; undated ones last, each group by path.
	 */
	public static List<File> newestFirst(List<File> folders) {
		Map<File, Long> dates = new HashMap<>();
		for (File folder : folders) {
			long date;
			try {
				date = AlbumDate.ofFolder(ResourceCache.sidecar(folder), folder.getName()).millis();
			} catch (RuntimeException ex) {
				date = 0;
			}
			dates.put(folder, Long.valueOf(date));
		}
		List<File> result = new ArrayList<>(folders);
		result.sort(Comparator.comparing((File f) -> dates.get(f)).reversed()
			.thenComparing(File::getAbsolutePath));
		return result;
	}

	/** Queues a task of {@link #work()} unless one is queued or there is nothing; while synchronized. */
	private void scheduleWork() {
		if (_workScheduled || _executor == null
			|| (_photoQueue.isEmpty() && _unitQueue.isEmpty() && !hasUrgent())) {
			return;
		}
		try {
			long delay = _photoQueue.isEmpty() && !hasUrgent() ? _unitsAfter - System.currentTimeMillis() : 0;
			if (delay > 0) {
				_delayedWork = _executor.schedule(this::work, delay, TimeUnit.MILLISECONDS);
			} else {
				_delayedWork = null;
				_executor.execute(this::work);
			}
			_workScheduled = true;
		} catch (RejectedExecutionException ex) {
			// Stopping.
		}
	}

	/**
	 * One piece of queued work: the next piece of the fast lane, else the photo steps of the next
	 * folder, else one unit of the videos.
	 *
	 * <p>
	 * One piece per task, so that a folder whose quiet period ended is worked on before the next
	 * piece: ticks are due earlier than the task queued after this one; and so that urgent work
	 * handed over meanwhile is next, see issue #258.
	 * </p>
	 */
	private void work() {
		File folder = null;
		boolean unit = false;
		Runnable urgent;
		synchronized (this) {
			_workScheduled = false;
			_delayedWork = null;
			urgent = nextUrgent();
			if (urgent != null) {
				// Ahead of everything else, see issue #258.
			} else if (!_photoQueue.isEmpty()) {
				Map.Entry<String, File> first = _photoQueue.entrySet().iterator().next();
				_photoQueue.remove(first.getKey());
				folder = first.getValue();
			} else {
				unit = !_unitQueue.isEmpty() && System.currentTimeMillis() >= _unitsAfter;
			}
		}
		if (urgent != null) {
			urgent.run();
		} else if (folder != null) {
			process(folder);
		} else if (unit) {
			runUnit();
		}
		synchronized (this) {
			persist(false);
			if (!Thread.currentThread().isInterrupted()) {
				scheduleWork();
			}
		}
	}

	/**
	 * Runs everything queued in the calling thread: the photo steps, then every unit; for the tests
	 * and for a pipeline that was never started.
	 */
	public void drain() {
		while (!Thread.currentThread().isInterrupted()) {
			File folder = null;
			boolean unit;
			long wait;
			Runnable urgent;
			synchronized (this) {
				urgent = nextUrgent();
				if (urgent == null && !_photoQueue.isEmpty()) {
					Map.Entry<String, File> first = _photoQueue.entrySet().iterator().next();
					_photoQueue.remove(first.getKey());
					folder = first.getValue();
				}
				unit = !_unitQueue.isEmpty();
				wait = _unitsAfter - System.currentTimeMillis();
			}
			if (urgent != null) {
				urgent.run();
			} else if (folder != null) {
				process(folder);
			} else if (unit) {
				if (wait > 0) {
					try {
						Thread.sleep(wait);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						break;
					}
				}
				runUnit();
			} else {
				break;
			}
		}
		flush();
	}

	/** Runs the next unit of the first {@link UnitStep} work queued. */
	private void runUnit() {
		Units work;
		String unit;
		String key;
		synchronized (this) {
			if (_unitQueue.isEmpty()) {
				return;
			}
			Map.Entry<String, Units> first = _unitQueue.entrySet().iterator().next();
			key = first.getKey();
			work = first.getValue();
			unit = work._units.peekFirst();
			if (unit == null) {
				_unitQueue.remove(key);
				return;
			}
		}
		if (!fingerprint(Arrays.asList(work._folder)).equals(work._fingerprint)) {
			// Changed since it was queued: dropped, and worked on anew once it stands still.
			synchronized (this) {
				_unitQueue.remove(key, work);
			}
			notice(work._folder, false);
			return;
		}
		String name = work._step.name();
		Outcome outcome;
		Exception problem = null;
		_currentStep = name;
		_currentFolder = work._path;
		_runningUnit = work._step;
		try {
			synchronized (this) {
				if (hasUrgent()) {
					// Handed over while this was taken: it goes first, see issue #258.
					_unitsAfter = System.currentTimeMillis();
					return;
				}
			}
			outcome = work._step.runUnit(work._folder, unit);
		} catch (IOException | RuntimeException ex) {
			outcome = Outcome.FAILED;
			problem = ex;
		} finally {
			_runningUnit = null;
			_currentStep = null;
			_currentFolder = null;
		}
		if (Thread.currentThread().isInterrupted()) {
			// Stopping: the unit is not done, and the next start does it.
			return;
		}
		synchronized (this) {
			if (outcome == Outcome.AGAIN) {
				// Put aside for something more urgent: first in the queue still, a moment later.
				_unitsAfter = System.currentTimeMillis() + AGAIN_MILLIS;
				return;
			}
			work._units.remove(unit);
			Record record = _records.get(work._path);
			boolean current = record != null && record._fingerprint.equals(work._fingerprint);
			switch (outcome) {
				case DONE:
					if (current) {
						record._units.computeIfAbsent(name, x -> new LinkedHashSet<>()).add(unit);
						_dirty = true;
					}
					succeeded(work._path, name, unit, Long.MAX_VALUE);
					break;
				case FAILED: {
					work._failed = true;
					String reason = name + ": " + work._step.itemOf(unit)
						+ (problem == null || problem.getMessage() == null ? "" : ": " + problem.getMessage());
					work._reasons.add(reason);
					failed(work._path, reason, name, work._path, unit);
					if (problem != null) {
						LOG.log(repeated(_skipped.get(key), reason) ? Level.FINE : Level.WARNING, "Step '" + name
							+ "' failed on '" + unit + "' in '" + work._folder.getAbsolutePath() + "': "
							+ problem.getMessage(), problem);
					}
					break;
				}
				case UNWRITABLE:
					work._unwritable = true;
					work._units.clear();
					ReadOnlyFolders.report(work._folder, "the results of '" + name + "'");
					break;
				case WAITING:
				default:
					// Not done in this pass; done when the folder is worked on again.
					break;
			}
			if (work._units.isEmpty()) {
				_unitQueue.remove(key, work);
				if (work._failed || work._unwritable) {
					_skipped.put(key, work._unwritable
						? new Skip(work._fingerprint, Outcome.UNWRITABLE, 0, Collections.emptySet(), Long.MAX_VALUE)
						: failedSkip(key, work._fingerprint, work._reasons));
				} else if (current && complete(record, work)) {
					record._done.add(name);
					record._units.remove(name);
					_dirty = true;
				}
			}
		}
	}

	/** Whether every unit of the given work's step is recorded for the folder. */
	private static boolean complete(Record record, Units work) {
		Set<String> finished = record._units.getOrDefault(work._step.name(), Collections.emptySet());
		for (String unit : work._step.units(work._folder)) {
			if (!finished.contains(unit)) {
				return false;
			}
		}
		return true;
	}

	/** What the background work of the space is doing, see issue #236. */
	public synchronized Status status() {
		int done = 0;
		for (String path : _known) {
			if (_settled.contains(path)) {
				done++;
			}
		}
		Set<String> videos = new HashSet<>();
		for (Units work : _unitQueue.values()) {
			for (String unit : work._units) {
				videos.add(work._path + "/" + work._step.itemOf(unit));
			}
		}
		Failure last = null;
		for (Failure failure : _outstanding.values()) {
			last = failure;
		}
		return new Status(done, _known.size(), _currentStep, _currentFolder, videos.size(),
			last == null ? null : last._reason, last == null ? null : last._folder);
	}

	/** How many failures are outstanding, see {@link #MAX_OUTSTANDING}; for the tests. */
	synchronized int outstanding() {
		return _outstanding.size();
	}

	/** Whether the given step is recorded as done for the given folder as it is now. */
	public synchronized boolean isDone(File folder, String step) {
		Record record = _records.get(relative(folder));
		return record != null && record._done.contains(step)
			&& record._fingerprint.equals(fingerprint(Arrays.asList(folder)));
	}

	/** Forgets the records of folders that are gone or no part of the walked library. */
	public synchronized void retain(Set<String> paths) {
		if (_records.keySet().removeIf(path -> !paths.contains(path))) {
			_dirty = true;
		}
		_known.retainAll(paths);
		_settled.retainAll(paths);
	}

	/**
	 * Forgets what is recorded about the given folder, so that every step runs on it again when it
	 * is worked on next: its generated files were thrown away, see <code>?action=refresh-cache</code>.
	 */
	public synchronized void forget(File folder) {
		String path = relative(folder);
		if (path != null && _records.remove(path) != null) {
			_dirty = true;
		}
		_skipped.keySet().removeIf(key -> pathOf(key).equals(path));
	}

	/**
	 * The fingerprint of the image and video files of the given folders: their names, sizes and
	 * modification stamps.
	 */
	public static String fingerprint(List<File> folders) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
		for (File folder : folders) {
			File[] files = folder.listFiles(f -> f.isFile() && ResourceCache.isImage(f));
			digest.update(folder.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
			if (files == null) {
				continue;
			}
			Arrays.sort(files, Comparator.comparing(File::getName));
			for (File file : files) {
				digest.update((file.getName() + "\0" + file.length() + "\0" + file.lastModified() + "\n")
					.getBytes(StandardCharsets.UTF_8));
			}
		}
		StringBuilder result = new StringBuilder();
		for (byte b : digest.digest()) {
			result.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return result.toString();
	}

	/** The given folder and every folder below it that is part of the library. */
	public static List<File> tree(File root) {
		List<File> result = new ArrayList<>();
		if (!root.isDirectory()) {
			return result;
		}
		Deque<File> pending = new ArrayDeque<>();
		pending.add(root);
		while (!pending.isEmpty()) {
			File folder = pending.removeFirst();
			result.add(folder);
			File[] children = folder.listFiles(f -> f.isDirectory() && !LibraryFiles.isIgnored(f));
			if (children != null) {
				Arrays.sort(children, Comparator.comparing(File::getName));
				pending.addAll(Arrays.asList(children));
			}
		}
		return result;
	}

	/** The path of the given folder relative to the space root, <code>null</code> outside it. */
	private String relative(File folder) {
		Path path = folder.toPath().toAbsolutePath().normalize();
		if (!path.startsWith(_root)) {
			return null;
		}
		return _root.relativize(path).toString().replace(File.separatorChar, '/');
	}

	// --- The file. ---

	/** The records as they stand, for the tests. */
	synchronized Map<String, Record> records() {
		return new HashMap<>(_records);
	}

	private synchronized void load() {
		if (!Files.isRegularFile(_file)) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			read(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// Progress can always be made again; a broken file must never stop the server.
			LOG.log(Level.WARNING, "Starting over with the unreadable '" + _file + "': " + ex.getMessage());
			_records.clear();
		}
	}

	private void read(JsonReader in) throws IOException {
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case FOLDERS__PROP:
					in.beginObject();
					while (in.hasNext()) {
						String path = in.nextName();
						Record record = readRecord(in);
						if (record != null) {
							_records.put(path, record);
						}
					}
					in.endObject();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		if (version > VERSION) {
			LOG.warning("'" + _file + "' was written by a newer version (" + version + " > " + VERSION
				+ "); what is recorded is trusted only where the folder is unchanged anyway.");
		}
	}

	private static Record readRecord(JsonReader in) throws IOException {
		String fingerprint = null;
		Set<String> done = new LinkedHashSet<>();
		Map<String, Set<String>> units = new LinkedHashMap<>();
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case FINGERPRINT__PROP:
					fingerprint = in.nextString();
					break;
				case DONE__PROP:
					in.beginArray();
					while (in.hasNext()) {
						done.add(in.nextString());
					}
					in.endArray();
					break;
				case UNITS__PROP:
					in.beginObject();
					while (in.hasNext()) {
						String step = in.nextName();
						Set<String> finished = new LinkedHashSet<>();
						in.beginArray();
						while (in.hasNext()) {
							finished.add(in.nextString());
						}
						in.endArray();
						if (!finished.isEmpty()) {
							units.put(step, finished);
						}
					}
					in.endObject();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return fingerprint == null ? null : new Record(fingerprint, done, units);
	}

	/** Writes the progress file now, whatever the clock says. */
	public synchronized void flush() {
		persist(true);
	}

	/** Writes the progress file, unless it was written a moment ago; called while synchronized. */
	private void persist(boolean now) {
		if (!_dirty) {
			return;
		}
		long when = System.currentTimeMillis();
		if (!now && when - _written < WRITE_INTERVAL_MILLIS) {
			return;
		}
		try {
			store();
			_dirty = false;
			_written = when;
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot write '" + _file + "': " + ex.getMessage(), ex);
		}
	}

	private void store() throws IOException {
		Path folder = _file.getParent();
		Files.createDirectories(folder);
		Path tmpFile = Files.createTempFile(folder, "pipeline", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(FOLDERS__PROP);
				out.beginObject();
				for (Map.Entry<String, Record> entry : _records.entrySet()) {
					out.name(entry.getKey());
					out.beginObject();
					out.name(FINGERPRINT__PROP);
					out.value(entry.getValue()._fingerprint);
					out.name(DONE__PROP);
					out.beginArray();
					for (String step : entry.getValue()._done) {
						out.value(step);
					}
					out.endArray();
					Map<String, Set<String>> units = entry.getValue()._units;
					if (!units.isEmpty()) {
						out.name(UNITS__PROP);
						out.beginObject();
						for (Map.Entry<String, Set<String>> step : units.entrySet()) {
							out.name(step.getKey());
							out.beginArray();
							for (String unit : step.getValue()) {
								out.value(unit);
							}
							out.endArray();
						}
						out.endObject();
					}
					out.endObject();
				}
				out.endObject();
				out.endObject();
			}
		}
		try {
			Files.move(tmpFile, _file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
