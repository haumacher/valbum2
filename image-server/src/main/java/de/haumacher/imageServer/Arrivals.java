/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.NewsEntry;
import de.haumacher.imageServer.shared.model.NewsInfo;
import de.haumacher.imageServer.shared.model.NewsPhoto;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What arrived from others, the bell of issue #268, answered from the index of the space.
 *
 * <h2>Events</h2>
 *
 * <p>
 * A photograph arrives for the others when it is uploaded into an album (by a member, by a contact
 * through a link, by a device into a shared album), or when it is moved out of the inbox into an
 * album. Both are recorded in the attribution of <code>.hashes.json</code> (the time of the upload,
 * the mover and the time of the move, see {@link de.haumacher.imageServer.upload.HashCache}), and
 * read from there into the table <code>arrivals</code> of the {@link SpaceIndex} with the hashes:
 * written through by every write of a hash sidecar, and found again by a rebuild. An upload into
 * the inbox is nobody else's news and is never answered.
 * </p>
 *
 * <h2>Entries</h2>
 *
 * <p>
 * The events of one actor in one album are one entry while no more than {@link #BURST_GAP} lies
 * between two of them: an upload of 300 photographs that takes an hour, or a sorting session
 * moving photographs out of the inbox in several goes, is one entry, a second upload in the
 * evening another. Only the last {@link #WINDOW} is looked at, and the list answers at most
 * {@link #MAX_ENTRIES}, the unread first. One's own events are never an entry, and an entry holds
 * only what the member may see in its album now, see {@link View}.
 * </p>
 *
 * <h2>Read marks</h2>
 *
 * <p>
 * A member marks an entry as read by visiting it, or all of them at once; opening the list marks
 * nothing. A mark records the album, the actor and the span of time of the entry, in the table
 * <code>arrival_marks</code> of the index, so that every device of the member agrees. A photograph
 * that arrives later within the same burst is not covered by the mark, and makes the entry unread
 * again with just that photograph unread. Marks are temporary by nature: they age out with
 * {@link #WINDOW}, and a rebuild of the index drops them &mdash; what arrived before the index was
 * created ({@link SpaceIndex#created()}) counts as read, so a rebuild never rings for the whole
 * library.
 * </p>
 */
public final class Arrivals {

	/** The longest pause within one entry, see {@link Arrivals}: two hours. */
	public static final long BURST_GAP = 2L * 60 * 60 * 1000;

	/** How far back the bell looks, see {@link Arrivals}: 90 days. */
	public static final long WINDOW = 90L * 24 * 60 * 60 * 1000;

	/** How many entries the bell answers at most, see {@link Arrivals}. */
	public static final int MAX_ENTRIES = 200;

	/** What a member is shown of an album, see {@link Arrivals#news(String, View, boolean)}. */
	public interface View {

		/**
		 * The album at the given path (below the root of the space, <code>/</code>-separated) as the
		 * member may see it: its title and the names of the photographs shown to them, in album
		 * order; <code>null</code> for a folder they may not see, the inbox, or no album at all.
		 */
		Album album(String folder);
	}

	/** An album as a {@link View} shows it. */
	public static final class Album {

		final String _title;

		final List<String> _names;

		/**
		 * @param title
		 *        The title to show.
		 * @param names
		 *        The names of the photographs the member may see, in album order, the members of a
		 *        group included.
		 */
		public Album(String title, List<String> names) {
			_title = title;
			_names = names;
		}
	}

	/** One row of <code>arrivals</code>. */
	static final class Event {

		final String _folder;

		final String _name;

		final String _actor;

		final String _label;

		final long _time;

		Event(String folder, String name, String actor, String label, long time) {
			_folder = folder;
			_name = name;
			_actor = actor;
			_label = label;
			_time = time;
		}
	}

	/** One entry: the events of one actor in one album within one burst. */
	static final class Entry {

		final String _folder;

		final String _actor;

		final List<Event> _events = new ArrayList<>();

		Entry(String folder, String actor) {
			_folder = folder;
			_actor = actor;
		}

		long first() {
			return _events.get(0)._time;
		}

		long last() {
			return _events.get(_events.size() - 1)._time;
		}

		/** The label of the latest event: a contact renamed since is shown as they are called now. */
		String label() {
			return _events.get(_events.size() - 1)._label;
		}

		/** What names the entry, see {@link NewsEntry#getId()}. */
		String key() {
			return Arrivals.key(_folder, _actor, first());
		}
	}

	/** A read mark, see {@link Arrivals}. */
	static final class Mark {

		final String _folder;

		final String _actor;

		final long _first;

		final long _last;

		Mark(String folder, String actor, long first, long last) {
			_folder = folder;
			_actor = actor;
			_first = first;
			_last = last;
		}

		boolean covers(Event event) {
			return _folder.equals(event._folder) && _actor.equals(event._actor) && _first <= event._time
				&& event._time <= _last;
		}
	}

	private final SpaceIndex _index;

	private final Clock _clock;

	/** The time, for the tests. */
	public interface Clock {

		/** Now, milliseconds since the epoch. */
		long now();
	}

	/** Creates the bell of the given index. */
	public Arrivals(SpaceIndex index) {
		this(index, System::currentTimeMillis);
	}

	/** Creates the bell of the given index with the given clock, for the tests. */
	public Arrivals(SpaceIndex index, Clock clock) {
		_index = index;
		_clock = clock;
	}

	/**
	 * The bell of the given member.
	 *
	 * @param member
	 *        The subject of the member asking, see
	 *        {@link de.haumacher.imageServer.auth.AuthService.Caller#subject()}.
	 * @param view
	 *        What the member may see.
	 * @param countOnly
	 *        Whether only {@link NewsInfo#getCount()} is answered, see <code>?type=news-count</code>.
	 */
	public NewsInfo news(String member, View view, boolean countOnly) {
		List<Shown> shown = shown(member, view);
		NewsInfo result = NewsInfo.create();
		int count = 0;
		for (Shown entry : shown) {
			if (entry._unread) {
				count++;
			}
		}
		result.setCount(count);
		if (!countOnly) {
			for (Shown entry : shown) {
				result.addEntrie(entry.toEntry());
			}
		}
		return result;
	}

	/**
	 * Marks the entries of the given keys as read for the given member, or every entry where
	 * <code>all</code>; a key no entry has (any more) is passed over.
	 */
	public void markRead(String member, View view, Collection<String> keys, boolean all) {
		List<Shown> shown = shown(member, view);
		List<Entry> marked = new ArrayList<>();
		for (Shown entry : shown) {
			if (entry._unread && (all || keys.contains(entry._entry.key()))) {
				marked.add(entry._entry);
			}
		}
		long now = _clock.now();
		_index.write(connection -> {
			try (PreparedStatement delete =
				connection.prepareStatement("DELETE FROM arrival_marks WHERE last_at < ? OR marked < ?")) {
				// Marks age out with the window, see Arrivals.
				delete.setLong(1, now - WINDOW);
				delete.setLong(2, now - WINDOW);
				delete.executeUpdate();
			}
			if (marked.isEmpty()) {
				return;
			}
			try (PreparedStatement insert = connection.prepareStatement(
				"INSERT INTO arrival_marks (member, folder, actor, first_at, last_at, marked) VALUES (?, ?, ?, ?, ?, ?)")) {
				for (Entry entry : marked) {
					insert.setString(1, member);
					insert.setString(2, entry._folder);
					insert.setString(3, entry._actor);
					insert.setLong(4, entry.first());
					insert.setLong(5, entry.last());
					insert.setLong(6, now);
					insert.addBatch();
				}
				insert.executeBatch();
			}
		});
	}

	/** An entry as one member is shown it. */
	private static final class Shown {

		final Entry _entry;

		final Album _album;

		final List<NewsPhoto> _photos;

		final boolean _unread;

		Shown(Entry entry, Album album, List<NewsPhoto> photos, boolean unread) {
			_entry = entry;
			_album = album;
			_photos = photos;
			_unread = unread;
		}

		NewsEntry toEntry() {
			NewsEntry result = NewsEntry.create()
				.setId(_entry.key())
				.setAlbum(_entry._folder)
				.setTitle(_album._title)
				.setWho(_entry.label())
				.setFirst(_entry.first())
				.setLast(_entry.last())
				.setUnread(_unread);
			for (NewsPhoto photo : _photos) {
				result.addPhoto(photo);
			}
			return result;
		}
	}

	/** The entries the member is shown, the unread first, see {@link Arrivals}. */
	private List<Shown> shown(String member, View view) {
		long now = _clock.now();
		long created = _index.created();
		List<Event> events = events(member, now - WINDOW);
		List<Mark> marks = marks(member);
		Map<String, Album> albums = new HashMap<>();
		List<Shown> result = new ArrayList<>();
		for (Entry entry : group(events)) {
			Album album = albums.computeIfAbsent(entry._folder, folder -> {
				Album found = view.album(folder);
				return found == null ? NO_ALBUM : found;
			});
			if (album == NO_ALBUM) {
				continue;
			}
			Map<String, Event> byName = new HashMap<>();
			for (Event event : entry._events) {
				byName.put(event._name, event);
			}
			List<NewsPhoto> photos = new ArrayList<>();
			boolean unread = false;
			for (String name : album._names) {
				Event event = byName.get(name);
				if (event == null) {
					continue;
				}
				boolean photoUnread = event._time >= created && !covered(marks, event);
				unread |= photoUnread;
				photos.add(NewsPhoto.create().setName(name).setUnread(photoUnread));
			}
			if (photos.isEmpty()) {
				// Nothing of it the member may see: no entry for them.
				continue;
			}
			result.add(new Shown(entry, album, photos, unread));
		}
		result.sort(Comparator.comparing((Shown shown) -> !shown._unread)
			.thenComparing(Comparator.comparingLong((Shown shown) -> shown._entry.last()).reversed()));
		return result.size() > MAX_ENTRIES ? new ArrayList<>(result.subList(0, MAX_ENTRIES)) : result;
	}

	private static final Album NO_ALBUM = new Album("", Collections.emptyList());

	private static boolean covered(List<Mark> marks, Event event) {
		for (Mark mark : marks) {
			if (mark.covers(event)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The events since the given moment that are not the given member's own, by album, actor and
	 * time.
	 */
	private List<Event> events(String member, long since) {
		return _index.read(connection -> {
			List<Event> result = new ArrayList<>();
			try (PreparedStatement select = connection.prepareStatement(
				"SELECT f.path, a.name, a.actor, a.label, a.arrived FROM arrivals a JOIN folders f ON f.id = a.folder "
					+ "WHERE a.arrived >= ? AND a.actor <> ? ORDER BY f.path, a.actor, a.arrived, a.name")) {
				select.setLong(1, since);
				select.setString(2, member);
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						result.add(new Event(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
							rows.getLong(5)));
					}
				}
			}
			return result;
		}, Collections.emptyList());
	}

	/** The read marks of the given member. */
	private List<Mark> marks(String member) {
		return _index.read(connection -> readMarks(connection, member), Collections.emptyList());
	}

	private static List<Mark> readMarks(Connection connection, String member) throws SQLException {
		List<Mark> result = new ArrayList<>();
		try (PreparedStatement select =
			connection.prepareStatement("SELECT folder, actor, first_at, last_at FROM arrival_marks WHERE member = ?")) {
			select.setString(1, member);
			try (ResultSet rows = select.executeQuery()) {
				while (rows.next()) {
					result.add(new Mark(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4)));
				}
			}
		}
		return result;
	}

	/**
	 * The entries of the given events, which are sorted by album, actor and time: a new entry
	 * wherever the album or the actor changes, or more than {@link #BURST_GAP} passed.
	 */
	static List<Entry> group(List<Event> events) {
		List<Entry> result = new ArrayList<>();
		Entry current = null;
		for (Event event : events) {
			if (current == null || !current._folder.equals(event._folder) || !current._actor.equals(event._actor)
				|| event._time - current.last() > BURST_GAP) {
				current = new Entry(event._folder, event._actor);
				result.add(current);
			}
			current._events.add(event);
		}
		return result;
	}

	/** The key of the entry of the given actor in the given album that starts at the given moment. */
	static String key(String folder, String actor, long first) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest((folder + '\0' + actor + '\0' + first).getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(16);
			for (int n = 0; n < 8; n++) {
				result.append(Character.forDigit((hash[n] >> 4) & 0xF, 16));
				result.append(Character.forDigit(hash[n] & 0xF, 16));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the platform.", ex);
		}
	}

	/** The names of every photograph of the given parts in album order, a group's members included. */
	static void collectNames(Iterable<? extends AlbumPart> parts, List<String> names) {
		for (AlbumPart part : parts) {
			if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					names.add(image.getName());
				}
			} else if (part instanceof ImagePart) {
				names.add(((ImagePart) part).getName());
			}
		}
	}
}
