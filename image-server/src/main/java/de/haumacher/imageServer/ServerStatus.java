/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.content.ContentIndex;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.pipeline.ReadOnlyFolders;
import de.haumacher.imageServer.places.GeoNamesStore;
import de.haumacher.imageServer.places.Places;
import de.haumacher.imageServer.shared.model.CatchUpStatus;
import de.haumacher.imageServer.shared.model.IndexProgress;
import de.haumacher.imageServer.shared.model.StatusInfo;
import de.haumacher.imageServer.shared.model.StatusItem;
import de.haumacher.imageServer.shared.model.StatusKey;
import de.haumacher.imageServer.shared.model.StatusProblem;
import de.haumacher.imageServer.shared.model.StatusProblemKind;
import de.haumacher.imageServer.shared.model.StatusStep;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The one answer of the status page of issue #305, <code>&lt;data&gt;/?type=status</code>: what the
 * server of a space is doing, waiting for and failing at, for its administrators.
 *
 * <p>
 * The author: "Wie schaffen wir es denn alles relevante an einer zentralen Stelle anzuzeigen."
 * Everything here is derived at the moment it is asked, from what the parts keep anyway: the
 * background's progress ({@link CatchUp#status}) and its outstanding failures
 * ({@link FolderPipeline#problems()}), the folders the server cannot write
 * ({@link ReadOnlyFolders#below(Path)}), the embedder ({@link ContentIndex#progress()}), what this
 * machine cannot do for faces and videos, the downloads of place names, the hash index. Nothing is
 * read from disk but the "seen" marks, one query.
 * </p>
 *
 * <h2>Problems, merged by cause</h2>
 *
 * <p>
 * Every outstanding failure, not only the last ("nicht nur den letzten Fehler"), merged by cause:
 * the step that failed and its reason without the file ("previews: 12 files in 3 albums &mdash;
 * unreadable format"), with every file. A cause has an {@link StatusProblem#getId() id} made of its
 * kind, its step and its reason; the same cause keeps its id across polls and restarts. A problem is
 * gone when it is fixed: the pipeline clears a failure when its step does its work again without
 * failing, and a folder that changes is tried again at once. "Retry now" ({@link #retry(String)})
 * tries a cause again that was fixed elsewhere.
 * </p>
 *
 * <h2>Seen, not deleted</h2>
 *
 * <p>
 * The red dot of the app means "something you have not seen yet" ("abhaken &mdash; alles gesehen?").
 * A problem marked as seen ({@link #markSeen(Collection, boolean)}) stays in the list and lights
 * nothing. Its marks are kept per file or folder of it, with the reason it had then, in the table
 * <code>status_seen</code> of the {@link SpaceIndex} &mdash; shared by every administrator of the
 * space, as the bell's read marks are shared by a member's devices, and surviving a restart. A
 * problem is seen while every one of its files is marked with its present reason: a file that fails
 * with another reason, or a new file of the cause, makes it new again. A mark outlives its failure
 * for {@link #SEEN_WINDOW} (a fixed failure that comes back within it is not news), and is
 * forgotten after that the next time anybody marks something.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class ServerStatus {

	/** How long a seen mark of a problem that is gone is kept, see {@link ServerStatus}. */
	public static final long SEEN_WINDOW = 90L * 24 * 60 * 60 * 1000;

	/** The {@link StatusProblem#getId() id} of the folders the server cannot write. */
	public static final String UNWRITABLE_ID = "unwritable";

	/** The {@link StatusProblem#getId() id} of the embedder that does not answer. */
	public static final String EMBEDDER_ID = "embedder";

	/** The {@link StatusProblem#getId() id} of the faces this machine cannot detect. */
	public static final String FACES_ID = "faces";

	/** The {@link StatusProblem#getId() id} of the videos this machine cannot transcode. */
	public static final String VIDEOS_ID = "videos";

	/** The reason the seen marks of {@link #UNWRITABLE_ID} carry: a folder is unwritable or not. */
	private static final String UNWRITABLE_REASON = "unwritable";

	private final Path _root;

	private final FolderPipeline _pipeline;

	private final ContentIndex _content;

	private final FaceIndex _faces;

	private final Supplier<Places> _places;

	private final Supplier<IndexProgress> _index;

	private final Supplier<String> _videos;

	private final SpaceIndex _store;

	private final LongSupplier _clock;

	/**
	 * Creates the status of one space.
	 *
	 * @param places
	 *        The gazetteer of the server, answering <code>null</code> for none.
	 * @param videos
	 *        Why this machine cannot transcode videos, answering <code>null</code> where it can.
	 */
	public ServerStatus(Path root, FolderPipeline pipeline, ContentIndex content, FaceIndex faces,
			Supplier<Places> places, Supplier<IndexProgress> index, Supplier<String> videos, SpaceIndex store,
			LongSupplier clock) {
		_root = root.toAbsolutePath().normalize();
		_pipeline = pipeline;
		_content = content;
		_faces = faces;
		_places = places;
		_index = index;
		_videos = videos;
		_store = store;
		_clock = clock;
	}

	/** What a member who is no administrator is answered: nothing but that. */
	public static StatusInfo forMember() {
		return StatusInfo.create().setAdmin(false);
	}

	/** Everything, for an administrator. */
	public StatusInfo forAdmin() {
		List<Problem> problems = problems();
		Map<String, String> seen = seenMarks();
		StatusInfo result = StatusInfo.create()
			.setAdmin(true)
			.setCatchUp(CatchUp.status(_pipeline, _content))
			.setIndex(_index.get())
			.setRoot(_root.toString());
		CatchUpStatus catchUp = result.getCatchUp();
		for (Map.Entry<String, Integer> entry : _pipeline.doneByStep().entrySet()) {
			result.addStep(StatusStep.create()
				.setStep(CatchUp.step(entry.getKey()))
				.setName(entry.getKey())
				.setDone(entry.getValue().intValue())
				.setTotal(catchUp.getAlbumsTotal()));
		}
		Places places = _places.get();
		if (places != null) {
			for (String name : places.loading()) {
				result.addPlacesLoading(StatusKey.create().setId(name));
			}
		}
		for (Problem problem : problems) {
			result.addProblem(problem.toWire(seen));
		}
		return result;
	}

	/**
	 * Marks the problems of the given ids (every problem, where <code>all</code>) as seen, as they are
	 * now; an id no problem has (any more) is passed over.
	 */
	public void markSeen(Collection<String> ids, boolean all) {
		List<Problem> problems = problems();
		Map<String, String> marks = new LinkedHashMap<>();
		Set<String> current = new LinkedHashSet<>();
		for (Problem problem : problems) {
			for (Item item : problem._items) {
				current.add(item._seenKey);
				if (all || ids.contains(problem._id)) {
					marks.put(item._seenKey, item._reason);
				}
			}
		}
		long now = _clock.getAsLong();
		_store.write(connection -> {
			// A mark of a problem that is gone is kept a while, then forgotten.
			List<String> old = new ArrayList<>();
			try (PreparedStatement select = connection.prepareStatement("SELECT item FROM status_seen WHERE marked < ?")) {
				select.setLong(1, now - SEEN_WINDOW);
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						if (!current.contains(rows.getString(1))) {
							old.add(rows.getString(1));
						}
					}
				}
			}
			try (PreparedStatement delete = connection.prepareStatement("DELETE FROM status_seen WHERE item = ?")) {
				for (String item : old) {
					delete.setString(1, item);
					delete.addBatch();
				}
				delete.executeBatch();
			}
			if (marks.isEmpty()) {
				return;
			}
			try (PreparedStatement merge = connection
				.prepareStatement("MERGE INTO status_seen (item, reason, marked) KEY (item) VALUES (?, ?, ?)")) {
				for (Map.Entry<String, String> mark : marks.entrySet()) {
					merge.setString(1, mark.getKey());
					merge.setString(2, mark.getValue());
					merge.setLong(3, now);
					merge.addBatch();
				}
				merge.executeBatch();
			}
		});
	}

	/** What {@link #retry(String)} did. */
	public enum Retried {
		/** Asked for again; the problem leaves the list when the retry succeeds. */
		STARTED,

		/** No problem has that id (any more): fixed meanwhile. */
		UNKNOWN,

		/** Nothing can be retried for it while the server runs, see {@link #NOT_RETRYABLE}. */
		NOT_RETRYABLE
	}

	/** What a retry of a problem that cannot be retried is refused with. */
	public static final String NOT_RETRYABLE =
		"This is found out once when the server starts; restart the server once it is fixed.";

	/** Tries again now what the problem of the given id failed at, see {@link ServerStatus}. */
	public Retried retry(String id) {
		for (Problem problem : problems()) {
			if (!problem._id.equals(id)) {
				continue;
			}
			if (!problem._retryable) {
				return Retried.NOT_RETRYABLE;
			}
			switch (problem._kind) {
				case FAILURE:
				case UNWRITABLE: {
					Set<String> folders = new LinkedHashSet<>();
					for (Item item : problem._items) {
						folders.add(item._folder);
					}
					_pipeline.retry(folders);
					return Retried.STARTED;
				}
				case EMBEDDER:
					return _content.retryNow() ? Retried.STARTED : Retried.NOT_RETRYABLE;
				case PLACES: {
					Places places = _places.get();
					boolean started = false;
					for (Item item : problem._items) {
						started |= places != null && places.retryNow(item._name);
					}
					return started ? Retried.STARTED : Retried.NOT_RETRYABLE;
				}
				default:
					return Retried.NOT_RETRYABLE;
			}
		}
		return Retried.UNKNOWN;
	}

	// --- The problems. ---

	/** One file or folder of a {@link Problem}. */
	private static final class Item {

		final String _folder;

		final String _name;

		final String _path;

		final int _photos;

		final String _what;

		/** What it is marked as seen by, see {@link ServerStatus}. */
		final String _seenKey;

		/** The reason it is marked with. */
		final String _reason;

		Item(String folder, String name, String path, int photos, String what, String seenKey, String reason) {
			_folder = folder;
			_name = name;
			_path = path;
			_photos = photos;
			_what = what;
			_seenKey = seenKey;
			_reason = reason;
		}
	}

	/** One cause, see {@link StatusProblem}. */
	private static final class Problem {

		final String _id;

		final StatusProblemKind _kind;

		final String _step;

		final String _reason;

		final boolean _retryable;

		final long _retryAt;

		final List<Item> _items = new ArrayList<>();

		Problem(String id, StatusProblemKind kind, String step, String reason, boolean retryable, long retryAt) {
			_id = id;
			_kind = kind;
			_step = step;
			_reason = reason;
			_retryable = retryable;
			_retryAt = retryAt;
		}

		StatusProblem toWire(Map<String, String> seen) {
			boolean allSeen = !_items.isEmpty();
			Set<String> albums = new LinkedHashSet<>();
			StatusProblem result = StatusProblem.create()
				.setId(_id)
				.setKind(_kind)
				.setReason(_reason)
				.setRetryable(_retryable)
				.setRetryAt(_retryAt);
			if (_step != null) {
				result.setStep(CatchUp.step(_step)).setStepName(_step);
			}
			for (Item item : _items) {
				albums.add(item._folder);
				allSeen &= item._reason.equals(seen.get(item._seenKey));
				result.addItem(StatusItem.create()
					.setFolder(item._folder)
					.setName(item._name == null ? "" : item._name)
					.setPath(item._path == null ? "" : item._path)
					.setPhotos(item._photos)
					.setWhat(item._what == null ? "" : item._what));
			}
			return result.setFiles(_items.size()).setAlbums(albums.size()).setSeen(allSeen);
		}
	}

	/** Every problem of the space now, the folders it cannot write first, the failures last. */
	private List<Problem> problems() {
		List<Problem> result = new ArrayList<>();
		List<ReadOnlyFolders.Found> unwritable = ReadOnlyFolders.below(_root);
		if (!unwritable.isEmpty()) {
			Problem problem = new Problem(UNWRITABLE_ID, StatusProblemKind.UNWRITABLE, null,
				"The server's user '" + System.getProperty("user.name")
					+ "' cannot write these folders: their photos show, but nothing made of them can be stored"
					+ " beside them (previews, hashes, faces, descriptions), and the work is done again after every"
					+ " restart. Fix: give them to the service user (e.g. 'chown -R valbum:valbum <folder>', or"
					+ " 'chmod u+w'), then retry.",
				_pipeline.isStarted(), 0);
			for (ReadOnlyFolders.Found found : unwritable) {
				String folder = _root.relativize(found.path()).toString().replace('\\', '/');
				problem._items.add(new Item(folder, null, found.path().toString(), found.photos(),
					String.join(", ", found.what()), "u\u0000" + folder, UNWRITABLE_REASON));
			}
			result.add(problem);
		}
		ContentIndex.Progress content = _content == null ? ContentIndex.Progress.OFF : _content.progress();
		if (content.enabled() && content.waiting() != null) {
			Problem problem = new Problem(EMBEDDER_ID, StatusProblemKind.EMBEDDER, ContentIndex.STEP,
				content.waiting(), content.retryAt() > 0, content.retryAt());
			problem._items.add(new Item("", null, null, 0, null, "e\u0000embedder", content.waiting()));
			result.add(problem);
		}
		String faces = _faces == null ? null : _faces.unavailability();
		if (faces != null) {
			Problem problem = new Problem(FACES_ID, StatusProblemKind.FACES, null, faces, false, 0);
			problem._items.add(new Item("", null, null, 0, null, "c\u0000faces", faces));
			result.add(problem);
		}
		String videos = _videos.get();
		if (videos != null) {
			Problem problem = new Problem(VIDEOS_ID, StatusProblemKind.VIDEOS, null, videos, false, 0);
			problem._items.add(new Item("", null, null, 0, null, "v\u0000videos", videos));
			result.add(problem);
		}
		Places places = _places.get();
		if (places != null) {
			Map<String, Problem> byCause = new LinkedHashMap<>();
			for (GeoNamesStore.Failed failed : places.failures()) {
				Problem problem = byCause.computeIfAbsent(failed.cause(), cause -> new Problem(
					"places:" + hash(cause), StatusProblemKind.PLACES, null, cause, true,
					failed.nextAttempt().toEpochMilli()));
				problem._items.add(new Item("", failed.name(), null, 0, null, "p\u0000" + failed.name(), failed.cause()));
			}
			result.addAll(byCause.values());
		}
		Map<String, Problem> byCause = new LinkedHashMap<>();
		boolean started = _pipeline.isStarted();
		for (FolderPipeline.Problem failure : _pipeline.problems()) {
			String kind = failure.kind() == null ? "" : failure.kind();
			String cause = kind + "\u0000" + failure.message();
			Problem problem = byCause.computeIfAbsent(cause,
				x -> new Problem("failure:" + hash(cause), StatusProblemKind.FAILURE, kind.isEmpty() ? null : kind,
					failure.message(), started, 0));
			problem._items.add(new Item(failure.folder(), failure.file(), null, 0, null,
				"f\u0000" + kind + "\u0000" + failure.folder() + "\u0000" + (failure.file() == null ? "" : failure.file()),
				failure.message()));
		}
		for (Problem problem : byCause.values()) {
			problem._items.sort((left, right) -> {
				int byFolder = left._folder.compareTo(right._folder);
				return byFolder != 0 ? byFolder
					: String.valueOf(left._name).compareTo(String.valueOf(right._name));
			});
		}
		result.addAll(byCause.values());
		return result;
	}

	/** The seen marks, by item, see {@link ServerStatus}. */
	private Map<String, String> seenMarks() {
		return _store.read(connection -> {
			Map<String, String> result = new HashMap<>();
			try (PreparedStatement select = connection.prepareStatement("SELECT item, reason FROM status_seen");
					ResultSet rows = select.executeQuery()) {
				while (rows.next()) {
					result.put(rows.getString(1), rows.getString(2));
				}
			}
			return result;
		}, new HashMap<>());
	}

	/** A short stable hash of the given cause, for an id. */
	static String hash(String cause) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(cause.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder();
			for (int n = 0; n < 8; n++) {
				result.append(String.format("%02x", Byte.valueOf(digest[n])));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
