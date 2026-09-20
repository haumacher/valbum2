/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ListingInfo;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * The name a folder on disk has, composed from the properties it carries, see issue #130.
 *
 * <p>
 * The name of an album's folder is <code>yyyy-MM-dd title</code> — the title alone when nothing
 * says when the album happened — and the name of a folder of folders is its title. That
 * composition happened once, when the album was created, and never again: a corrected typo or a
 * corrected date stayed in the sidecar while the folder kept the old name, and
 * {@link AlbumDate#ofFolderName(String) the date read from a folder name} could then disagree with
 * the date written in the sidecar.
 * </p>
 *
 * <p>
 * This class is the one composition, and the server applies it on every write of a folder's
 * properties, see {@link ImageServlet#storeFolder}. The app composes the very same name when it
 * creates an album (<code>valbum_ui/lib/album_date.dart</code>, <code>albumFolderName</code>), and
 * the two are pinned against each other by the shared table
 * <code>image-server/src/test/fixtures/folder-names.json</code>, exactly as issue #102 pins the
 * date a file name carries.
 * </p>
 *
 * <p>
 * Reading the date <em>from</em> the folder name stays what it was: the fallback for a sidecar
 * that carries no explicit date, and the only way an album created by hand with a file manager is
 * dated at all, see {@link AlbumDate#ofFolder(FolderResource, String)}. It can no longer
 * <em>disagree</em> with the properties, because writing the properties writes the name.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class FolderNames {

	/** The spelling of a date in a folder name, the naming convention. */
	private static final DateTimeFormatter FOLDER_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private FolderNames() {
		// Static only.
	}

	/**
	 * The name of the folder an album with the given title, taken on the given day, lives in.
	 *
	 * <p>
	 * <code>yyyy-MM-dd title</code> by the naming convention, and the title alone when the album
	 * has no explicit date — an album without a date is no less an album, it is only one the
	 * placement rules leave where it was made, see issue #119. The title is trimmed: a stray blank
	 * at either end is a slip of the keyboard, never a name.
	 * </p>
	 *
	 * @param date
	 *        The explicit date of the album ({@link AlbumInfo#getDate()}), <code>0</code> for none.
	 *        Never the {@link AlbumInfo#getEffectiveDate() effective} one: the folder name is
	 *        composed from what the album says about itself, not from what the server derived —
	 *        and the folder name is one of the things it is derived from.
	 * @param title
	 *        The title of the album.
	 */
	public static String albumFolderName(long date, String title) {
		String name = title == null ? "" : title.trim();
		if (date <= 0L) {
			return name;
		}
		String day = FOLDER_DATE.format(LocalDate.ofInstant(Instant.ofEpochMilli(date), ZoneId.systemDefault()));
		return name.isEmpty() ? day : day + " " + name;
	}

	/**
	 * The folder name the given properties compose to, the empty string when they compose none.
	 *
	 * <p>
	 * A resource without a title composes nothing: most folders of a library carry no title at all,
	 * and a folder that says nothing about itself keeps the name its owner gave it. That is not a
	 * refusal — there is simply nothing to name it after.
	 * </p>
	 */
	public static String of(FolderResource resource) {
		return of(resource, "");
	}

	/**
	 * The folder name the given properties compose for a folder that is called
	 * <code>currentName</code> today, the empty string when they compose none.
	 *
	 * <p>
	 * The date of an album an author never dated is the one in its folder name, and that is the
	 * only date it has, see {@link AlbumDate#ofAlbum(AlbumInfo, String)}. Renaming such an album
	 * after its title alone would throw that date away — writing a description would silently
	 * undate the album — so the date text it already carries is kept, spelled exactly as it is
	 * spelled: a folder called <code>2020 Trip</code> stays a folder of the year 2020 and does not
	 * become one of the first of January.
	 * </p>
	 *
	 * @param currentName
	 *        The name the folder has on disk, the empty string where there is none yet.
	 */
	public static String of(FolderResource resource, String currentName) {
		if (resource instanceof AlbumInfo) {
			AlbumInfo album = (AlbumInfo) resource;
			if (isBlank(album.getTitle())) {
				return "";
			}
			if (album.getDate() > 0L) {
				return albumFolderName(album.getDate(), album.getTitle());
			}
			String title = album.getTitle().trim();
			if (!AlbumDate.leadingDateText(title).isEmpty()) {
				// The title says the date itself; putting the one from the folder name in front of
				// it would say it twice.
				return title;
			}
			String carried = AlbumDate.leadingDateText(currentName);
			return carried.isEmpty() ? title : carried + " " + title;
		}
		if (resource instanceof ListingInfo) {
			String title = ((ListingInfo) resource).getTitle();
			return isBlank(title) ? "" : title.trim();
		}
		return "";
	}

	/**
	 * Whether the given composed name may be the name of a folder on disk.
	 *
	 * <p>
	 * A name that is a path (<code>a/b</code>), a name that is a navigation step (<code>.</code>,
	 * <code>..</code>) and a name that hides the folder (<code>.thing</code>, which the server
	 * itself uses for <code>.valbum</code> and <code>.vacache</code>) are none: a title that
	 * composes to one of them is refused, and nothing is written, see
	 * {@link #illegalName(String)}.
	 * </p>
	 */
	public static boolean isLegal(String name) {
		if (name == null || name.trim().isEmpty()) {
			return false;
		}
		if (name.equals(".") || name.equals("..") || name.startsWith(".")) {
			return false;
		}
		for (int n = 0, cnt = name.length(); n < cnt; n++) {
			char c = name.charAt(n);
			if (c == '/' || c == '\\' || c == 0) {
				return false;
			}
		}
		return true;
	}

	/** How a title that composes to a name no folder may have is refused. */
	public static String illegalName(String name) {
		return "'" + name + "' cannot be the name of a folder; the properties were not stored.";
	}

	/** How the answer of a properties write says that the folder was renamed. */
	public static String renamedTo(String name) {
		return "Renamed to '" + name + "'.";
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
