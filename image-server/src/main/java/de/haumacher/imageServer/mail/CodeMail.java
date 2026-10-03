/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one mail the server ever sends: the code that proves an address, see issue #199.
 *
 * <p>
 * A fixed text in German or English: the code, the name of the space, and nothing the requester
 * typed, so that nobody can make the server mail somebody a text of their choice. The language is
 * the one the requester's browser prefers (<code>Accept-Language</code>, the first of German and
 * English it names), because the requester is who reads it; English where it names neither.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class CodeMail {

	/** German. */
	public static final String GERMAN = "de";

	/** English, the default. */
	public static final String ENGLISH = "en";

	/** What a space without a name of its own is called in the mail. */
	public static final String DEFAULT_SPACE_NAME = "VAlbum";

	private static final int MAX_SPACE_NAME = 80;

	private final String _subject;

	private final String _text;

	private CodeMail(String subject, String text) {
		_subject = subject;
		_text = text;
	}

	/** The subject line. */
	public String getSubject() {
		return _subject;
	}

	/** The plain text. */
	public String getText() {
		return _text;
	}

	/**
	 * The mail carrying the given code.
	 *
	 * @param language
	 *        {@link #GERMAN} or {@link #ENGLISH}, see {@link #language(String)}.
	 * @param space
	 *        The name of the space, set by its administrator.
	 * @param minutes
	 *        How long the code is valid.
	 */
	public static CodeMail of(String language, String space, String code, long minutes) {
		String name = spaceName(space);
		if (GERMAN.equals(language)) {
			return new CodeMail("Ihr Code für " + name + ": " + code,
				"Ihr Code: " + code + "\n\n"
					+ "Geben Sie ihn auf der Seite ein, die danach gefragt hat, um die Fotos von „" + name
					+ "“ zu öffnen. Der Code gilt " + minutes + " Minuten und nur einmal.\n\n"
					+ "Wenn Sie diesen Code nicht angefordert haben, ignorieren Sie diese E-Mail. "
					+ "Ohne den Code kommt niemand hinein.\n");
		}
		return new CodeMail("Your code for " + name + ": " + code,
			"Your code: " + code + "\n\n"
				+ "Enter it on the page that asked for it to open the photos of \"" + name + "\". "
				+ "The code is valid for " + minutes + " minutes and works once.\n\n"
				+ "If you did not ask for this code, ignore this mail. Nobody gets in without the code.\n");
	}

	/** The name of the space as the mail says it: one line, not overly long, never empty. */
	static String spaceName(String space) {
		String name = space == null ? "" : space.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
		if (name.length() > MAX_SPACE_NAME) {
			name = name.substring(0, MAX_SPACE_NAME).trim() + "…";
		}
		return name.isEmpty() ? DEFAULT_SPACE_NAME : name;
	}

	/**
	 * The language of the mail for the given <code>Accept-Language</code> header.
	 *
	 * <p>
	 * The languages are taken in the order of their weight (<code>q</code>), the header's order
	 * breaking a tie, and the first that is German or English decides; English where there is none.
	 * </p>
	 */
	public static String language(String acceptLanguage) {
		if (acceptLanguage == null || acceptLanguage.isBlank()) {
			return ENGLISH;
		}
		List<String> tags = new ArrayList<>();
		List<Double> weights = new ArrayList<>();
		for (String entry : acceptLanguage.split(",")) {
			String[] parts = entry.split(";");
			String tag = parts[0].trim().toLowerCase(Locale.ROOT);
			double weight = 1.0;
			for (int n = 1; n < parts.length; n++) {
				String parameter = parts[n].trim();
				if (parameter.startsWith("q=")) {
					try {
						weight = Double.parseDouble(parameter.substring(2));
					} catch (NumberFormatException ex) {
						weight = 0;
					}
				}
			}
			int at = 0;
			while (at < weights.size() && weights.get(at).doubleValue() >= weight) {
				at++;
			}
			tags.add(at, tag);
			weights.add(at, Double.valueOf(weight));
		}
		for (int n = 0; n < tags.size(); n++) {
			if (weights.get(n).doubleValue() <= 0) {
				continue;
			}
			String primary = tags.get(n).split("-")[0];
			if (GERMAN.equals(primary) || ENGLISH.equals(primary)) {
				return primary;
			}
		}
		return ENGLISH;
	}
}
