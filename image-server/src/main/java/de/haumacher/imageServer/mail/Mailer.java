/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import java.io.IOException;

/**
 * What sends a mail, see issue #199: {@link SmtpMailer} in the server, a fake in a test.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface Mailer {

	/**
	 * Sends one plain-text mail.
	 *
	 * @param to
	 *        The recipient, a normalised e-mail address.
	 * @throws IOException
	 *         If the mail could not be handed to the mail server.
	 */
	void send(String to, String subject, String text) throws IOException;
}
