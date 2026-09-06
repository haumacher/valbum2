/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When an album happened, and how precisely that is known, see issue #48.
 *
 * <p>
 * There are two dates on the wire and one in the sidecar: {@link AlbumInfo#getDate()} is the
 * explicit date the author set and the only one that is ever stored;
 * {@link AlbumInfo#getEffectiveDate()} is what the album is sorted and placed by and is derived
 * here on every read. This class is the one place that decides what "the date of an album" means.
 * </p>
 *
 * <p>
 * Two resolutions are needed, and they differ by what they are allowed to cost:
 * </p>
 * <ul>
 * <li>{@link #ofAlbum(AlbumInfo, String)} is the full rule — the explicit date, else the leading
 * date of the folder name, else the earliest image date — and is answered to a reader of a single
 * album, whose images the server has loaded anyway.</li>
 * <li>{@link #ofFolder(FolderResource, String)} is the cheap rule — the explicit date, else the
 * folder name — and is what a listing sorts by and what a {@link PlacementRule} files by. Neither
 * of those may open a thousand albums to answer one request.</li>
 * </ul>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class AlbumDate {

	/** The separators a date in a folder name may use, as in the album titles of this server. */
	private static final String SEP = "[-_\\.\\s]";

	/**
	 * The date at the start of a folder name: <code>YYYY</code>, <code>YYYY-MM</code> or
	 * <code>YYYY-MM-DD</code>.
	 *
	 * <p>
	 * Anchored at the start and stopped before a further digit, so that <code>20200524 Trip</code>
	 * is not read as the year 2020.
	 * </p>
	 */
	private static final Pattern LEADING_DATE = Pattern.compile(
		"^(\\d{4})(?:" + SEP + "(\\d{2})(?:" + SEP + "(\\d{2}))?)?(?![0-9])");

	/** The date of an album that nothing says anything about. */
	public static final AlbumDate NONE = new AlbumDate(0L, false);

	private final long _millis;

	private final boolean _monthKnown;

	private AlbumDate(long millis, boolean monthKnown) {
		_millis = millis;
		_monthKnown = monthKnown;
	}

	/** The date in milliseconds since the epoch, <code>0</code> if there is none. */
	public long millis() {
		return _millis;
	}

	/** Whether this date says anything at all. */
	public boolean isSet() {
		return _millis > 0L;
	}

	/**
	 * Whether the month of this date is known.
	 *
	 * <p>
	 * A folder named <code>2020 Trip</code> says a year and nothing more; filing it under
	 * <code>2020-01</code> would state a month nobody gave, see
	 * {@link PlacementRule#placementFor(java.nio.file.Path, AlbumDate)}.
	 * </p>
	 */
	public boolean isMonthKnown() {
		return _monthKnown;
	}

	/** The calendar year of this date in the server's time zone; <code>0</code> if it is not set. */
	public int year() {
		return isSet() ? local().getYear() : 0;
	}

	/** The calendar month of this date in the server's time zone; <code>0</code> if it is not set. */
	public int month() {
		return isSet() ? local().getMonthValue() : 0;
	}

	private LocalDate local() {
		return LocalDate.ofInstant(Instant.ofEpochMilli(_millis), ZoneId.systemDefault());
	}

	/**
	 * An explicit date: an instant a person picked, so its month is known.
	 *
	 * @param millis
	 *        Milliseconds since the epoch; <code>0</code> or less means {@link #NONE}.
	 */
	public static AlbumDate ofMillis(long millis) {
		return millis > 0L ? new AlbumDate(millis, true) : NONE;
	}

	/**
	 * The date at the start of the given folder name, {@link #NONE} if it does not start with one.
	 *
	 * <p>
	 * A bare year is January 1st, a year and month the first of that month, both at local midnight;
	 * a year alone is remembered as such, see {@link #isMonthKnown()}. A date that is no date
	 * (<code>2020-13</code>, <code>2020-02-31</code>) falls back to the part of it that is one.
	 * </p>
	 */
	public static AlbumDate ofFolderName(String folderName) {
		if (folderName == null) {
			return NONE;
		}
		Matcher matcher = LEADING_DATE.matcher(folderName);
		if (!matcher.lookingAt()) {
			return NONE;
		}
		int year = Integer.parseInt(matcher.group(1));
		int month = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
		int day = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));

		if (month < 1 || month > 12) {
			return atMidnight(year, 1, 1, false);
		}
		if (day < 1 || day > LocalDate.of(year, month, 1).lengthOfMonth()) {
			return atMidnight(year, month, 1, true);
		}
		return atMidnight(year, month, day, true);
	}

	/**
	 * The date a listing sorts a folder by and a {@link PlacementRule} files it by: the explicit
	 * date of its sidecar, else the date in its name.
	 *
	 * <p>
	 * No image is opened here, and none is even looked at: this is the date that can be answered
	 * for every folder of a listing without loading the albums behind them.
	 * </p>
	 *
	 * @param sidecar
	 *        The folder's own <code>index.json</code>, <code>null</code> if it has none.
	 * @param folderName
	 *        The name of the folder on disk.
	 */
	public static AlbumDate ofFolder(FolderResource sidecar, String folderName) {
		if (sidecar instanceof AlbumInfo) {
			AlbumDate explicit = ofMillis(((AlbumInfo) sidecar).getDate());
			if (explicit.isSet()) {
				return explicit;
			}
		}
		return ofFolderName(folderName);
	}

	/**
	 * The date the given album is sorted and placed by: the explicit date, else the date in the
	 * folder name, else the earliest date of an image it holds.
	 *
	 * @param album
	 *        The album as it was loaded, images included.
	 * @param folderName
	 *        The name of the album's folder on disk.
	 */
	public static AlbumDate ofAlbum(AlbumInfo album, String folderName) {
		AlbumDate byFolder = ofFolder(album, folderName);
		if (byFolder.isSet()) {
			return byFolder;
		}
		return ofMillis(earliestImageDate(album));
	}

	/**
	 * The earliest {@link ImagePart#getDate() date} of the images of the given album, <code>0</code>
	 * if it holds none with a date.
	 */
	public static long earliestImageDate(AlbumInfo album) {
		long result = 0L;
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result = earlier(result, ((ImagePart) part).getDate());
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					result = earlier(result, image.getDate());
				}
			}
		}
		return result;
	}

	/**
	 * Clears everything the server derives from a folder resource before it is written to disk.
	 *
	 * <p>
	 * The sidecar doctrine: what is stored means what it says, and
	 * {@link AlbumInfo#getEffectiveDate()} says "this is what the album is sorted by today", which
	 * is not a statement anybody made. Every path that writes an <code>index.json</code> goes
	 * through here, so a derived date can never be frozen into a sidecar by a round trip, see
	 * {@link ImageServlet#storeSidecar(java.io.File, byte[])}.
	 * </p>
	 *
	 * @return Whether anything had to be cleared.
	 */
	public static boolean clearDerived(FolderResource resource) {
		boolean changed = false;
		if (resource instanceof AlbumInfo) {
			AlbumInfo album = (AlbumInfo) resource;
			if (album.getEffectiveDate() != 0L) {
				album.setEffectiveDate(0L);
				changed = true;
			}
		} else if (resource instanceof ListingInfo) {
			// The folders of a stored listing are rebuilt from the disk on every read; a derived
			// date among them would be stale the moment it was written.
			for (FolderInfo folder : ((ListingInfo) resource).getFolders()) {
				if (folder.getEffectiveDate() != 0L) {
					folder.setEffectiveDate(0L);
					changed = true;
				}
			}
		}
		return changed;
	}

	private static long earlier(long result, long candidate) {
		if (candidate <= 0L) {
			return result;
		}
		return result <= 0L ? candidate : Math.min(result, candidate);
	}

	private static AlbumDate atMidnight(int year, int month, int day, boolean monthKnown) {
		long millis = LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		return new AlbumDate(millis, monthKnown);
	}

	@Override
	public String toString() {
		return isSet() ? local().toString() + (_monthKnown ? "" : " (year only)") : "<no date>";
	}
}
