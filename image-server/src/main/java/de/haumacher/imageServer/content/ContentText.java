/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.PlaceTag;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The text a photograph's <code>v_txt</code> is the vector of, see issue #257: the one place its
 * template is spelled.
 *
 * <pre>
 * title: &lt;album title&gt; | text: &lt;places, smallest first&gt;; &lt;album title&gt;, &lt;month year&gt;;
 *   &lt;description&gt;; &lt;labels&gt;
 * </pre>
 *
 * <p>
 * For example <code>title: 2025-10 Italien | text: Leaning Tower of Pisa, Pisa, Tuscany, Italy;
 * 2025-10 Italien, October 2025; Piazza at noon; holiday, architecture</code>. A part that says
 * nothing is left out with its separator. The album gives the trip's context for free (a Pisa
 * photo in "2025-10 Italien" carries "Italien" and the month); the places are the gazetteer's own
 * names of issue #234, the month is the photograph's own (else the album's), in English, in UTC.
 * Persons are not part of it: a name is answered by the search of the faces, exactly.
 * </p>
 *
 * <p>
 * Any change of what is written here is a change of {@link #VERSION}, which is part of the stamp of
 * every text vector ({@link #stamp(String)}): every <code>v_txt</code> is computed again, and no
 * <code>v_img</code> is.
 * </p>
 */
public final class ContentText {

	/** The version of the template; raise it with every change of {@link #of(AlbumInfo, String, ImagePart)}. */
	public static final int VERSION = 1;

	private ContentText() {
		// Static only.
	}

	/** The stamp of a text vector of an embedder of the given stamp: the template is part of it. */
	public static String stamp(String embedderStamp) {
		return embedderStamp + "/text" + VERSION;
	}

	/** The title and the text of a photograph, see {@link Embedder#embedDocument(String, String)}. */
	public record Text(String title, String body) {

		/** The text as the model is given it, its prefix included: what its vector is keyed by. */
		public String document() {
			return Embedder.document(title, body);
		}
	}

	/**
	 * The text of the given photograph, see {@link ContentText}.
	 *
	 * @param album
	 *        The album the photograph is read from, its places derived.
	 * @param folderName
	 *        The name of the album's folder, the title of an album that names none.
	 */
	public static Text of(AlbumInfo album, String folderName, ImagePart image) {
		String title = album == null || album.getTitle() == null || album.getTitle().isBlank() ? clean(folderName)
			: clean(album.getTitle());
		List<String> parts = new ArrayList<>();
		parts.add(places(image));
		long date = image.getDate() > 0 ? image.getDate() : album == null ? 0 : album.getEffectiveDate();
		parts.add(join(", ", title, month(date)));
		parts.add(clean(image.getComment()));
		List<String> labels = new ArrayList<>();
		for (LabelName label : image.getLabels()) {
			labels.add(clean(label.getName()));
		}
		parts.add(join(", ", labels.toArray(new String[0])));
		return new Text(title, join("; ", parts.toArray(new String[0])));
	}

	/** The places of the photograph, the smallest first, each name once. */
	static String places(ImagePart image) {
		if (image.getPlaces() == null) {
			return "";
		}
		List<PlaceTag> tags = new ArrayList<>(image.getPlaces().getTags());
		// The kinds run from the country to a feature: the feature first, as a person names it.
		tags.sort(Comparator.comparing((PlaceTag tag) -> tag.getKind().ordinal()).reversed());
		Set<String> names = new LinkedHashSet<>();
		for (PlaceTag tag : tags) {
			String name = clean(tag.getName());
			if (!name.isEmpty()) {
				names.add(name);
			}
		}
		return String.join(", ", names);
	}

	/** "October 2025", empty for no date. */
	static String month(long millis) {
		if (millis <= 0) {
			return "";
		}
		ZonedDateTime time = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC);
		return time.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + time.getYear();
	}

	/** The given text on one line, without the separators of the template. */
	static String clean(String text) {
		if (text == null) {
			return "";
		}
		return text.replace('|', '/').replace(';', ',').replaceAll("\\s+", " ").strip();
	}

	private static String join(String separator, String... parts) {
		StringBuilder result = new StringBuilder();
		for (String part : parts) {
			if (part == null || part.isEmpty()) {
				continue;
			}
			if (result.length() > 0) {
				result.append(separator);
			}
			result.append(part);
		}
		return result.toString();
	}
}
