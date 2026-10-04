/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one mail the server ever sends: the code that proves an address, see issues #199 and #232.
 *
 * <p>
 * A fixed text in German or English that says who shared what: the sharer (the link's creator as
 * the members know them), the title of the shared album, the code, how long it is valid, and a
 * footer naming the space and its public address. Nothing in it is what the requester typed, so
 * that nobody can make the server mail somebody a text of their choice. The language is the one
 * the requester's browser prefers (<code>Accept-Language</code>, the first of German and English it
 * names), because the requester is who reads it; English where it names neither.
 * </p>
 *
 * <p>
 * The sender's display name is <code>&lt;sharer&gt; über VAlbum</code> (English
 * <code>&lt;sharer&gt; via VAlbum</code>), the address being the configured one; without a sharer
 * just <code>VAlbum</code>.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class CodeMail {

	/** German. */
	public static final String GERMAN = "de";

	/** English, the default. */
	public static final String ENGLISH = "en";

	/** What a space without a name of its own is called in the mail, and the sender without a sharer. */
	public static final String DEFAULT_SPACE_NAME = "VAlbum";

	private static final int MAX_NAME = 80;

	/** What the code is for. */
	public enum Purpose {
		/** Opening a personal share link: an opened recipient's token, an open or a group link. */
		OPEN,

		/** A contact recognised already adds an address to themself. */
		ADD_ADDRESS;
	}

	/**
	 * What the mail says about where the code comes from: nothing the requester typed.
	 */
	public static final class About {

		final String _sharer;

		final String _title;

		final String _space;

		final String _publicUrl;

		/**
		 * Creates {@link About}.
		 *
		 * @param sharer
		 *        The user name of the link's creator, empty or <code>null</code> for none.
		 * @param title
		 *        The title of the shared album (the rule of the card, never the link's label), empty
		 *        or <code>null</code> where it is gone.
		 * @param space
		 *        The name of the space, set by its administrator.
		 * @param publicUrl
		 *        The public address of the space's application (<code>VALBUM_PUBLIC_URL</code>, plus
		 *        <code>/&lt;space&gt;</code> on a server of several spaces), <code>null</code> where
		 *        none is configured.
		 */
		public About(String sharer, String title, String space, String publicUrl) {
			_sharer = line(sharer);
			_title = line(title);
			_space = spaceName(space);
			// Validated at start-up; never cut, a cut address would lead nowhere.
			_publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("[\\p{Cntrl}\\s]", "");
		}
	}

	private final String _sender;

	private final String _subject;

	private final String _text;

	private CodeMail(String sender, String subject, String text) {
		_sender = sender;
		_subject = subject;
		_text = text;
	}

	/** The display name of the sender; the address is the configured one. */
	public String getSender() {
		return _sender;
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
	 * @param purpose
	 *        What the code is for.
	 * @param about
	 *        Who shared what, in which space.
	 * @param minutes
	 *        How long the code is valid.
	 */
	public static CodeMail of(String language, Purpose purpose, About about, String code, long minutes) {
		return GERMAN.equals(language) ? german(purpose, about, code, minutes) : english(purpose, about, code, minutes);
	}

	private static CodeMail german(Purpose purpose, About about, String code, long minutes) {
		String sharer = about._sharer;
		String title = about._title;
		String sender = sharer.isEmpty() ? DEFAULT_SPACE_NAME : sharer + " über " + DEFAULT_SPACE_NAME;
		String subject = "Code für " + (title.isEmpty() ? about._space : "„" + title + "“") + ": " + code;
		StringBuilder text = new StringBuilder("Hallo,\n\n");
		if (purpose == Purpose.ADD_ADDRESS) {
			text.append("mit diesem Code bestätigen Sie Ihre E-Mail-Adresse für die Fotos, die ")
				.append(sharer.isEmpty() ? "mit Ihnen geteilt werden" : sharer + " mit Ihnen teilt").append(":\n");
		} else {
			String album = title.isEmpty() ? "ein Album" : "das Album „" + title + "“";
			if (sharer.isEmpty()) {
				text.append(capitalize(album)).append(" wurde mit Ihnen geteilt.\n");
			} else {
				text.append(sharer).append(" hat ").append(album).append(" mit Ihnen geteilt.\n");
			}
			text.append("Geben Sie diesen Code auf der Seite ein, die danach gefragt hat:\n");
		}
		text.append("\n    ").append(code).append("\n\n");
		text.append("Der Code gilt ").append(minutes).append(" Minuten und nur einmal.\n\n");
		text.append("Haben Sie keinen Code angefordert? Dann können Sie diese E-Mail\n");
		text.append(purpose == Purpose.ADD_ADDRESS ? "einfach ignorieren – ohne den Code wird die Adresse nicht gespeichert.\n"
			: "einfach ignorieren – ohne den Code kann niemand das Album öffnen.\n");
		footer(text, about);
		return new CodeMail(sender, subject, text.toString());
	}

	private static CodeMail english(Purpose purpose, About about, String code, long minutes) {
		String sharer = about._sharer;
		String title = about._title;
		String sender = sharer.isEmpty() ? DEFAULT_SPACE_NAME : sharer + " via " + DEFAULT_SPACE_NAME;
		String subject = "Code for " + (title.isEmpty() ? about._space : "“" + title + "”") + ": " + code;
		StringBuilder text = new StringBuilder("Hello,\n\n");
		if (purpose == Purpose.ADD_ADDRESS) {
			text.append("This code confirms your e-mail address for the photos ")
				.append(sharer.isEmpty() ? "shared with you" : sharer + " shares with you").append(":\n");
		} else {
			String album = title.isEmpty() ? "an album" : "the album “" + title + "”";
			if (sharer.isEmpty()) {
				text.append(capitalize(album)).append(" was shared with you.\n");
			} else {
				text.append(sharer).append(" shared ").append(album).append(" with you.\n");
			}
			text.append("Enter this code on the page that asked for it:\n");
		}
		text.append("\n    ").append(code).append("\n\n");
		text.append("The code is valid for ").append(minutes).append(" minutes and works once.\n\n");
		text.append("Didn't ask for a code? Then simply ignore this mail –\n");
		text.append(purpose == Purpose.ADD_ADDRESS ? "without the code the address is not saved.\n"
			: "without the code nobody can open the album.\n");
		footer(text, about);
		return new CodeMail(sender, subject, text.toString());
	}

	/** The signature: the space and, where configured, its public address. */
	private static void footer(StringBuilder text, About about) {
		text.append("\n–\n").append(about._space);
		if (!about._publicUrl.isEmpty()) {
			text.append(" · ").append(about._publicUrl).append(about._publicUrl.endsWith("/") ? "" : "/");
		}
		text.append("\n");
	}

	private static String capitalize(String phrase) {
		return Character.toUpperCase(phrase.charAt(0)) + phrase.substring(1);
	}

	/** The name of the space as the mail says it: one line, not overly long, never empty. */
	static String spaceName(String space) {
		String name = line(space);
		return name.isEmpty() ? DEFAULT_SPACE_NAME : name;
	}

	/** A name as the mail says it: one line, not overly long, empty for none. */
	static String line(String value) {
		String name = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
		if (name.length() > MAX_NAME) {
			name = name.substring(0, MAX_NAME).trim() + "…";
		}
		return name;
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
