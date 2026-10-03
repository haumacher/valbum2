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
			CodeMail mail = CodeMail.of(CodeMail.GERMAN, "Familie Müller", "123456", 10);
			new SmtpMailer(settings).send("petra@gmx.de", mail.getSubject(), mail.getText());

			assertEquals(1, smtp.getReceived().size());
			FakeSmtpServer.Received received = smtp.getReceived().get(0);
			assertEquals(java.util.List.of("petra@gmx.de"), received.getRecipients());
			assertEquals("Ihr Code für Familie Müller: 123456", received.getSubject());
			assertTrue(received.getText(), received.getText().startsWith("Ihr Code: 123456"));
			assertEquals("album@example.org", received.getMessage().getFrom()[0].toString());
			assertEquals("auto-generated", received.getMessage().getHeader("Auto-Submitted")[0]);
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
			new SmtpMailer(settings).send("petra@gmx.de", "s", "t");
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

	public void testTheMailSaysTheCodeAndTheSpaceAndNothingElse() {
		CodeMail english = CodeMail.of(CodeMail.ENGLISH, "Holiday\r\nBcc: evil@example.org", "654321", 10);
		assertEquals("Your code for Holiday Bcc: evil@example.org: 654321", english.getSubject());
		assertTrue(english.getText(), english.getText().contains("valid for 10 minutes"));
		assertEquals("Your code for VAlbum: 1", CodeMail.of(CodeMail.ENGLISH, " ", "1", 10).getSubject());
	}
}
