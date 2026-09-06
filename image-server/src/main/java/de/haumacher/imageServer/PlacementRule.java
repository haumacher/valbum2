/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Placement;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Where a folder files what lands in it, see issue #48.
 *
 * <p>
 * A folder carries a {@link Placement} rule in its own <code>index.json</code>, and this class is
 * the whole of what the rule means: {@link #placementFor(Path, AlbumDate)} answers the folder an
 * entry of a given date belongs in, and {@link #create(File, AlbumDate)} makes that folder exist.
 * Year and month folders are ordinary folders — they are created on demand, they carry no rule of
 * their own, and nothing keeps the owner from moving an album out of one again: the rule places, it
 * does not police.
 * </p>
 *
 * <p>
 * The date a rule files by is the cheap one — the explicit date of the sidecar, else the date in
 * the folder name, see {@link AlbumDate#ofFolder(FolderResource, String)}. Placement never opens an
 * image: an album is filed by what is written on it, and what a listing shows about an album is
 * exactly what decides where it lands.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class PlacementRule {

	/**
	 * A folder that is itself a year or a month folder, and therefore never filed again.
	 *
	 * <p>
	 * Without this, a folder named <code>2020</code> that is moved into a folder filing by year
	 * would land in <code>2020/2020</code>, and applying the rule to a folder twice would nest its
	 * year folders once more each time. The cost is that an album a user really named
	 * <code>2020</code> is never filed; naming an album after nothing but a year is naming a year
	 * folder.
	 * </p>
	 */
	private static final Pattern PLACEMENT_FOLDER = Pattern.compile("\\d{4}([-_\\.]\\d{2})?");

	private final Placement _rule;

	private PlacementRule(Placement rule) {
		_rule = rule;
	}

	/** The rule the given folder carries in its own sidecar, {@link Placement#NONE} if it has none. */
	public static PlacementRule of(File folder) {
		return of(ResourceCache.sidecar(folder));
	}

	/** The rule the given sidecar states, {@link Placement#NONE} for an album or no sidecar at all. */
	public static PlacementRule of(FolderResource sidecar) {
		return new PlacementRule(sidecar instanceof ListingInfo ? ((ListingInfo) sidecar).getPlacement()
			: Placement.NONE);
	}

	/** A rule of the given kind, whatever any folder says. */
	public static PlacementRule of(Placement rule) {
		return new PlacementRule(rule == null ? Placement.NONE : rule);
	}

	/** What this rule files by. */
	public Placement rule() {
		return _rule;
	}

	/** Whether this rule files anything at all. */
	public boolean isActive() {
		return _rule != Placement.NONE;
	}

	/**
	 * The folder below the given folder that an entry of the given date belongs in.
	 *
	 * @param folderWithRule
	 *        The folder this rule belongs to.
	 * @param date
	 *        The date of the entry that lands there, see
	 *        {@link AlbumDate#ofFolder(FolderResource, String)}.
	 * @return The year (or month) folder, <code>null</code> when this rule files nothing or the
	 *         entry has no date to be filed by. Nothing is created here.
	 */
	public Path placementFor(Path folderWithRule, AlbumDate date) {
		if (!isActive() || date == null || !date.isSet()) {
			return null;
		}
		Path year = folderWithRule.resolve(yearName(date));
		if (_rule == Placement.BY_YEAR_MONTH && date.isMonthKnown()) {
			return year.resolve(monthName(date));
		}
		// A date known only to the year states no month, and this server invents none.
		return year;
	}

	/**
	 * {@link #placementFor(Path, AlbumDate)}, with the folder chain created.
	 *
	 * @return The folder the entry belongs in, <code>null</code> when this rule files it nowhere.
	 * @throws IOException
	 *         If the folder chain cannot be created.
	 */
	public File create(File folderWithRule, AlbumDate date) throws IOException {
		Path placement = placementFor(folderWithRule.toPath(), date);
		if (placement == null) {
			return null;
		}
		File result = placement.toFile();
		if (!result.isDirectory() && !result.mkdirs()) {
			throw new IOException("Cannot create the folder '" + result.getAbsolutePath() + "'.");
		}
		return result;
	}

	/**
	 * Whether a folder of this name is a year or month folder, and therefore already where it
	 * belongs.
	 */
	public static boolean isPlacementFolder(String name) {
		return name != null && PLACEMENT_FOLDER.matcher(name).matches();
	}

	/** How an album is told that a rule filed it away, see {@link ImageServlet}. */
	public static String filedIn(String folder) {
		return "The placement rule of the folder above filed this album in '" + folder + "'.";
	}

	/** The name of the year folder of the given date: <code>2020</code>. */
	public static String yearName(AlbumDate date) {
		return String.format("%04d", Integer.valueOf(date.year()));
	}

	/** The name of the month folder of the given date: <code>2020-05</code>, its year included. */
	public static String monthName(AlbumDate date) {
		return String.format("%04d-%02d", Integer.valueOf(date.year()), Integer.valueOf(date.month()));
	}
}
