/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import de.haumacher.imageServer.mail.CodeMail.About;
import de.haumacher.imageServer.mail.CodeMail.Purpose;
import junit.framework.TestCase;

/**
 * Test case for the text of {@link CodeMail}, see issue #232: who shared what, in both languages,
 * for both purposes.
 */
@SuppressWarnings("javadoc")
public class TestCodeMail extends TestCase {

	private static final About RADTOUR =
		new About("Haui", "Radtour nach Rom", "Familie Haumacher", "https://fotos.haumacher.de/valbum");

	public void testGermanOpen() {
		CodeMail mail = CodeMail.of(CodeMail.GERMAN, Purpose.OPEN, RADTOUR, "023826", 10);
		assertEquals("Haui über VAlbum", mail.getSender());
		assertEquals("Code für „Radtour nach Rom“: 023826", mail.getSubject());
		assertEquals("Hallo,\n"
			+ "\n"
			+ "Haui hat das Album „Radtour nach Rom“ mit Ihnen geteilt.\n"
			+ "Geben Sie diesen Code auf der Seite ein, die danach gefragt hat:\n"
			+ "\n"
			+ "    023826\n"
			+ "\n"
			+ "Der Code gilt 10 Minuten und nur einmal.\n"
			+ "\n"
			+ "Haben Sie keinen Code angefordert? Dann können Sie diese E-Mail\n"
			+ "einfach ignorieren – ohne den Code kann niemand das Album öffnen.\n"
			+ "\n"
			+ "–\n"
			+ "Familie Haumacher · https://fotos.haumacher.de/valbum/\n", mail.getText());
	}

	public void testEnglishOpen() {
		CodeMail mail = CodeMail.of(CodeMail.ENGLISH, Purpose.OPEN, RADTOUR, "023826", 10);
		assertEquals("Haui via VAlbum", mail.getSender());
		assertEquals("Code for “Radtour nach Rom”: 023826", mail.getSubject());
		assertEquals("Hello,\n"
			+ "\n"
			+ "Haui shared the album “Radtour nach Rom” with you.\n"
			+ "Enter this code on the page that asked for it:\n"
			+ "\n"
			+ "    023826\n"
			+ "\n"
			+ "The code is valid for 10 minutes and works once.\n"
			+ "\n"
			+ "Didn't ask for a code? Then simply ignore this mail –\n"
			+ "without the code nobody can open the album.\n"
			+ "\n"
			+ "–\n"
			+ "Familie Haumacher · https://fotos.haumacher.de/valbum/\n", mail.getText());
	}

	public void testGermanAddAddress() {
		CodeMail mail = CodeMail.of(CodeMail.GERMAN, Purpose.ADD_ADDRESS, RADTOUR, "023826", 10);
		assertEquals("Haui über VAlbum", mail.getSender());
		assertEquals("Code für „Radtour nach Rom“: 023826", mail.getSubject());
		assertEquals("Hallo,\n"
			+ "\n"
			+ "mit diesem Code bestätigen Sie Ihre E-Mail-Adresse für die Fotos, die Haui mit Ihnen teilt:\n"
			+ "\n"
			+ "    023826\n"
			+ "\n"
			+ "Der Code gilt 10 Minuten und nur einmal.\n"
			+ "\n"
			+ "Haben Sie keinen Code angefordert? Dann können Sie diese E-Mail\n"
			+ "einfach ignorieren – ohne den Code wird die Adresse nicht gespeichert.\n"
			+ "\n"
			+ "–\n"
			+ "Familie Haumacher · https://fotos.haumacher.de/valbum/\n", mail.getText());
	}

	public void testEnglishAddAddress() {
		CodeMail mail = CodeMail.of(CodeMail.ENGLISH, Purpose.ADD_ADDRESS, RADTOUR, "023826", 10);
		assertEquals("Haui via VAlbum", mail.getSender());
		assertEquals("Code for “Radtour nach Rom”: 023826", mail.getSubject());
		assertEquals("Hello,\n"
			+ "\n"
			+ "This code confirms your e-mail address for the photos Haui shares with you:\n"
			+ "\n"
			+ "    023826\n"
			+ "\n"
			+ "The code is valid for 10 minutes and works once.\n"
			+ "\n"
			+ "Didn't ask for a code? Then simply ignore this mail –\n"
			+ "without the code the address is not saved.\n"
			+ "\n"
			+ "–\n"
			+ "Familie Haumacher · https://fotos.haumacher.de/valbum/\n", mail.getText());
	}

	public void testWithoutAPublicAddressTheFooterIsTheSpaceAlone() {
		About about = new About("Haui", "Radtour nach Rom", "Familie Haumacher", null);
		assertTrue(CodeMail.of(CodeMail.GERMAN, Purpose.OPEN, about, "1", 10).getText()
			.endsWith("\n\n–\nFamilie Haumacher\n"));
		assertTrue(CodeMail.of(CodeMail.ENGLISH, Purpose.ADD_ADDRESS, about, "1", 10).getText()
			.endsWith("\n\n–\nFamilie Haumacher\n"));
		About nameless = new About("Haui", "Radtour nach Rom", " ", "http://localhost:9090/valbum");
		assertTrue(CodeMail.of(CodeMail.ENGLISH, Purpose.OPEN, nameless, "1", 10).getText()
			.endsWith("\n\n–\nVAlbum · http://localhost:9090/valbum/\n"));
	}

	public void testWithoutATitle() {
		About about = new About("Haui", "", "Familie Haumacher", null);
		CodeMail german = CodeMail.of(CodeMail.GERMAN, Purpose.OPEN, about, "023826", 10);
		assertEquals("Code für Familie Haumacher: 023826", german.getSubject());
		assertTrue(german.getText(), german.getText().contains("\nHaui hat ein Album mit Ihnen geteilt.\n"));
		CodeMail english = CodeMail.of(CodeMail.ENGLISH, Purpose.OPEN, about, "023826", 10);
		assertEquals("Code for Familie Haumacher: 023826", english.getSubject());
		assertTrue(english.getText(), english.getText().contains("\nHaui shared an album with you.\n"));
	}

	public void testWithoutASharer() {
		About about = new About("", "Radtour nach Rom", "Familie Haumacher", null);
		CodeMail german = CodeMail.of(CodeMail.GERMAN, Purpose.OPEN, about, "023826", 10);
		assertEquals("VAlbum", german.getSender());
		assertTrue(german.getText(),
			german.getText().contains("\nDas Album „Radtour nach Rom“ wurde mit Ihnen geteilt.\n"));
		CodeMail english = CodeMail.of(CodeMail.ENGLISH, Purpose.OPEN, about, "023826", 10);
		assertEquals("VAlbum", english.getSender());
		assertTrue(english.getText(),
			english.getText().contains("\nThe album “Radtour nach Rom” was shared with you.\n"));

		assertTrue(CodeMail.of(CodeMail.GERMAN, Purpose.ADD_ADDRESS, about, "1", 10).getText()
			.contains("für die Fotos, die mit Ihnen geteilt werden:\n"));
		assertTrue(CodeMail.of(CodeMail.ENGLISH, Purpose.ADD_ADDRESS, about, "1", 10).getText()
			.contains("for the photos shared with you:\n"));

		About nothing = new About(null, null, null, null);
		CodeMail bare = CodeMail.of(CodeMail.GERMAN, Purpose.OPEN, nothing, "023826", 10);
		assertEquals("Code für VAlbum: 023826", bare.getSubject());
		assertTrue(bare.getText(), bare.getText().contains("\nEin Album wurde mit Ihnen geteilt.\n"));
		assertTrue(CodeMail.of(CodeMail.ENGLISH, Purpose.OPEN, nothing, "1", 10).getText()
			.contains("\nAn album was shared with you.\n"));
	}

	public void testEveryNameIsOneLineAndNotOverlyLong() {
		About about = new About("Haui\r\nBcc: evil@example.org", "Rom\nBcc: x@example.org", "Holiday\r\nX: y",
			null);
		CodeMail mail = CodeMail.of(CodeMail.ENGLISH, Purpose.OPEN, about, "654321", 10);
		assertEquals("Haui Bcc: evil@example.org via VAlbum", mail.getSender());
		assertEquals("Code for “Rom Bcc: x@example.org”: 654321", mail.getSubject());
		assertTrue(mail.getText(), mail.getText().endsWith("\n–\nHoliday X: y\n"));

		String long_ = "x".repeat(200);
		CodeMail cut = CodeMail.of(CodeMail.ENGLISH, Purpose.OPEN, new About(long_, long_, long_, null), "1", 10);
		assertEquals("x".repeat(80) + "… via VAlbum", cut.getSender());
		assertEquals("Code for “" + "x".repeat(80) + "…”: 1", cut.getSubject());
	}
}
