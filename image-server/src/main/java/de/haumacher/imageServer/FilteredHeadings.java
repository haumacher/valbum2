/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.util.ArrayList;
import java.util.List;

/**
 * Which headings a filtered view of an album shows, see issue #213.
 *
 * <p>
 * One rule for every view that filters photographs — a share link's label, rating floor and
 * privacy level here, a member's label chips and rating filter in the app: <em>a heading is shown
 * exactly when at least one photograph under it is shown.</em> A subsection (level 2, issue #158)
 * counts the photographs up to the next heading of any level; a section counts everything up to the
 * next section, its subsections included. Headings keep their place; only empty ones drop. The
 * unfiltered album and the edit mode keep every heading, empty ones included.
 * </p>
 *
 * <p>
 * The app applies the same rule (<code>visibleHeadings</code> in <code>album_filter.dart</code>),
 * and both are pinned by the shared table <code>src/test/fixtures/filtered-headings.json</code>.
 * </p>
 */
public final class FilteredHeadings {

	private FilteredHeadings() {
		// Static utility.
	}

	/**
	 * The given parts without the headings under which nothing is shown.
	 *
	 * @param shown
	 *        The parts the view shows, the hidden photographs already taken out; never modified.
	 * @return The given list itself where every heading stays, a shorter copy otherwise.
	 */
	public static List<AlbumPart> prune(List<AlbumPart> shown) {
		int size = shown.size();
		boolean[] keep = new boolean[size];
		boolean dropped = false;
		for (int n = 0; n < size; n++) {
			AlbumPart part = shown.get(n);
			if (!(part instanceof Heading)) {
				keep[n] = true;
				continue;
			}
			keep[n] = showsAnything(shown, n);
			dropped |= !keep[n];
		}
		if (!dropped) {
			return shown;
		}
		List<AlbumPart> result = new ArrayList<>(size);
		for (int n = 0; n < size; n++) {
			if (keep[n]) {
				result.add(shown.get(n));
			}
		}
		return result;
	}

	/** Whether a photograph stands under the heading at the given index. */
	private static boolean showsAnything(List<AlbumPart> parts, int index) {
		boolean section = !isSubsection((Heading) parts.get(index));
		for (int n = index + 1, size = parts.size(); n < size; n++) {
			AlbumPart part = parts.get(n);
			if (part instanceof Heading) {
				if (!section || !isSubsection((Heading) part)) {
					return false;
				}
				// A subsection of this section: its photographs are this section's too.
				continue;
			}
			if (part instanceof ImagePart || (part instanceof ImageGroup && !((ImageGroup) part).getImages().isEmpty())) {
				return true;
			}
		}
		return false;
	}

	/** Whether the given heading is a subsection; every level but 2 reads as a section, see issue #158. */
	public static boolean isSubsection(Heading heading) {
		return heading.getLevel() == 2;
	}
}
