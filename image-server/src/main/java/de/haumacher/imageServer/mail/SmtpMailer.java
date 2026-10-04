/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Date;

/**
 * Sends a mail through the configured account, over SMTP submission with Jakarta Mail (Eclipse
 * Angus), see issue #199.
 *
 * <p>
 * One connection per mail: the server sends a code now and then, never a stream of mails.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class SmtpMailer implements Mailer {

	private final MailSettings _settings;

	/** Creates a {@link SmtpMailer} for the given account. */
	public SmtpMailer(MailSettings settings) {
		_settings = settings;
	}

	@Override
	public void send(String to, String sender, String subject, String text) throws IOException {
		Session session = Session.getInstance(_settings.sessionProperties());
		try {
			MimeMessage message = new MimeMessage(session);
			message.setFrom(from(sender));
			message.setRecipient(Message.RecipientType.TO, new InternetAddress(to, true));
			message.setSubject(subject, "UTF-8");
			message.setText(text, "UTF-8");
			message.setSentDate(new Date());
			// A machine wrote this; an auto-responder must not answer it (RFC 3834).
			message.setHeader("Auto-Submitted", "auto-generated");
			message.saveChanges();
			try (Transport transport = session.getTransport(_settings.protocol())) {
				String user = _settings.getUser();
				transport.connect(_settings.getHost(), _settings.getPort(), user.isEmpty() ? null : user,
					user.isEmpty() ? null : _settings.getPassword());
				transport.sendMessage(message, message.getAllRecipients());
			}
		} catch (MessagingException ex) {
			throw new IOException("Cannot send a mail through " + _settings + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * The configured sender address under the given display name (issue #232), encoded as UTF-8
	 * where it needs to be; the configured sender as it is where there is no display name.
	 */
	private InternetAddress from(String sender) throws MessagingException, IOException {
		InternetAddress configured = new InternetAddress(_settings.getFrom(), true);
		if (sender == null || sender.isBlank()) {
			return configured;
		}
		return new InternetAddress(configured.getAddress(), sender, "UTF-8");
	}
}
