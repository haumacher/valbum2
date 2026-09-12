/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.shared.model.RightName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a caller may do with a folder, see issue #49.
 *
 * <p>
 * The four rights of a {@link GrantStore.Grant} and the implications between them. They are
 * persisted strings and protocol names, not an enum ordinal, so that a store written today is read
 * by every later build, exactly as {@link Roles} is.
 * </p>
 *
 * <p>
 * The implications are applied once, when a set of rights is computed, see
 * {@link #closure(Collection)}: {@link #EDIT} implies {@link #CONTRIBUTE}, {@link #DOWNLOAD} and
 * {@link #VIEW}; {@link #CONTRIBUTE} and {@link #DOWNLOAD} imply {@link #VIEW}. Nothing else in
 * this server compares rights, it only asks whether one is in the set.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Rights {

	/** Look at the listing, the thumbnails and the previews of a folder. */
	public static final String VIEW = "view";

	/** Fetch the original files. */
	public static final String DOWNLOAD = "download";

	/** Add entries to a folder: upload into it, move into it. */
	public static final String CONTRIBUTE = "contribute";

	/** Change a folder: store its sidecar, move out of it, apply its placement rule. */
	public static final String EDIT = "edit";

	/** The rights in the order they are answered in, weakest first. */
	private static final List<String> ORDER =
		Collections.unmodifiableList(Arrays.asList(VIEW, DOWNLOAD, CONTRIBUTE, EDIT));

	/** Every right: what the owner of a space holds in it. */
	public static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(ORDER));

	/** Nothing at all: what a caller without a grant holds. */
	public static final Set<String> NONE = Collections.emptySet();

	/** Looking without changing: what an anonymous caller holds in an unmigrated library. */
	public static final Set<String> READ_ONLY =
		Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(VIEW, DOWNLOAD)));

	/** Whether the given string is one of the rights this build knows. */
	public static boolean isKnown(String right) {
		return ORDER.contains(right);
	}

	/** The known rights, for a message naming them. */
	public static String names() {
		return String.join(", ", ORDER);
	}

	/**
	 * The given rights with the implications applied.
	 *
	 * <p>
	 * The one place the implications live. Unknown names are dropped: a store written by a later
	 * build must not hand this one a right it cannot enforce.
	 * </p>
	 *
	 * @return A set in the order of {@link #ORDER}, never <code>null</code>.
	 */
	public static Set<String> closure(Collection<String> rights) {
		Set<String> result = new LinkedHashSet<>();
		boolean edit = rights.contains(EDIT);
		boolean contribute = edit || rights.contains(CONTRIBUTE);
		boolean download = edit || rights.contains(DOWNLOAD);
		boolean view = contribute || download || rights.contains(VIEW);
		if (view) {
			result.add(VIEW);
		}
		if (download) {
			result.add(DOWNLOAD);
		}
		if (contribute) {
			result.add(CONTRIBUTE);
		}
		if (edit) {
			result.add(EDIT);
		}
		return result;
	}

	/** The given rights as the protocol carries them, in the order of {@link #ORDER}. */
	public static List<RightName> onTheWire(Collection<String> rights) {
		List<RightName> result = new ArrayList<>(rights.size());
		for (String right : ORDER) {
			if (rights.contains(right)) {
				result.add(RightName.create().setName(right));
			}
		}
		return result;
	}

	/** The rights of the given protocol list, unknown names dropped. */
	public static Set<String> fromTheWire(Collection<RightName> rights) {
		Set<String> result = new LinkedHashSet<>();
		for (RightName right : rights) {
			if (isKnown(right.getName())) {
				result.add(right.getName());
			}
		}
		return result;
	}
}
