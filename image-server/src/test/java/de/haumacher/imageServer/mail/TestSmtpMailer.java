/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import java.util.Properties;
import junit.framework.TestCase;

/**
 * Test case for {@link SmtpMailer} and {@link MailSettings}, see issue #199.
 */
@SuppressWarnings("javadoc")
public class TestSmtpMailer extends TestCase {

	public void testAMailReachesTheServerAsWritten() throws Exception {
		try (FakeSmtpServer smtp = new FakeSmtpServer()) {
			MailSettings settings = new MailSettings("localhost", smtp.getPort(), "", "", "album@example.org",
				MailSettings.Security.NONE);
			CodeMail mail = CodeMail.of(CodeMail.GERMAN, CodeMail.Purpose.OPEN,
				new CodeMail.About("Jörg", "Radtour nach Rom", "Familie Müller", null), "123456", 10);
			new SmtpMailer(settings).send("petra@gmx.de", mail.getSender(), mail.getSubject(), mail.getText());

			assertEquals(1, smtp.getReceived().size());
			FakeSmtpServer.Received received = smtp.getReceived().get(0);
			assertEquals(java.util.List.of("petra@gmx.de"), received.getRecipients());
			assertEquals("Code für „Radtour nach Rom“: 123456", received.getSubject());
			assertEquals(mail.getText(), received.getText().replace("\r\n", "\n"));
			jakarta.mail.internet.InternetAddress from =
				(jakarta.mail.internet.InternetAddress) received.getMessage().getFrom()[0];
			assertEquals("album@example.org", from.getAddress());
			assertEquals("Jörg über VAlbum", from.getPersonal());
			assertEquals("auto-generated", received.getMessage().getHeader("Auto-Submitted")[0]);
		}
	}

	public void testWithoutADisplayNameTheConfiguredSenderStands() throws Exception {
		try (FakeSmtpServer smtp = new FakeSmtpServer()) {
			MailSettings settings = new MailSettings("localhost", smtp.getPort(), "", "",
				"Album <album@example.org>", MailSettings.Security.NONE);
			new SmtpMailer(settings).send("petra@gmx.de", "", "s", "t");
			jakarta.mail.internet.InternetAddress from =
				(jakarta.mail.internet.InternetAddress) smtp.getReceived().get(0).getMessage().getFrom()[0];
			assertEquals("album@example.org", from.getAddress());
			assertEquals("Album", from.getPersonal());

			new SmtpMailer(settings).send("petra@gmx.de", "Haui via VAlbum", "s", "t");
			from = (jakarta.mail.internet.InternetAddress) smtp.getReceived().get(1).getMessage().getFrom()[0];
			assertEquals("The address stays, the name is the mail's.", "album@example.org", from.getAddress());
			assertEquals("Haui via VAlbum", from.getPersonal());
		}
	}

	public void testAFailureIsAnIOException() throws Exception {
		int port;
		try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
			port = free.getLocalPort();
		}
		MailSettings settings = new MailSettings("localhost", port, "", "", "album@example.org",
			MailSettings.Security.NONE);
		try {
			new SmtpMailer(settings).send("petra@gmx.de", "", "s", "t");
			fail("Nobody listens there.");
		} catch (java.io.IOException expected) {
			// Expected.
		}
	}

	public void testStartTlsIsRequiredNotMerelyTried() {
		Properties properties = new MailSettings("smtp.example.org", 587, "a@example.org", "pw", "a@example.org",
			MailSettings.Security.STARTTLS).sessionProperties();
		assertEquals("smtp", properties.getProperty("mail.transport.protocol"));
		assertEquals("true", properties.getProperty("mail.smtp.starttls.enable"));
		assertEquals("true", properties.getProperty("mail.smtp.starttls.required"));
		assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"));
		assertEquals("true", properties.getProperty("mail.smtp.auth"));
		assertEquals("587", properties.getProperty("mail.smtp.port"));
		assertNotNull(properties.getProperty("mail.smtp.timeout"));
	}

	public void testTlsSpeaksSmtps() {
		Properties properties = new MailSettings("smtp.example.org", 465, "", "", "a@example.org",
			MailSettings.Security.TLS).sessionProperties();
		assertEquals("smtps", properties.getProperty("mail.transport.protocol"));
		assertEquals("true", properties.getProperty("mail.smtps.ssl.enable"));
		assertEquals("true", properties.getProperty("mail.smtps.ssl.checkserveridentity"));
		assertEquals("false", properties.getProperty("mail.smtps.auth"));
	}

	public void testThePasswordIsNeverPrinted() {
		String printed = new MailSettings("smtp.example.org", 587, "a@example.org", "s3cret!", "a@example.org",
			MailSettings.Security.STARTTLS).toString();
		assertFalse(printed, printed.contains("s3cret!"));
		assertTrue(printed, printed.contains("smtp.example.org:587"));
	}

	public void testTheLanguageIsTheRequestersFirstOfGermanAndEnglish() {
		assertEquals("de", CodeMail.language("de-DE,de;q=0.9,en;q=0.8"));
		assertEquals("en", CodeMail.language("en-US,de;q=0.5"));
		assertEquals("de", CodeMail.language("fr-FR,de;q=0.7,en;q=0.5"));
		assertEquals("en", CodeMail.language("fr-FR,en;q=0.7,de;q=0.5"));
		assertEquals("de", CodeMail.language("en;q=0.2, de"));
		assertEquals("en", CodeMail.language("fr"));
		assertEquals("en", CodeMail.language(null));
		assertEquals("en", CodeMail.language("de;q=0, fr"));
	}
}
