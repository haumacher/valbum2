/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A minimal SMTP server on the loopback interface for the tests of issue #199.
 *
 * <p>
 * Plain SMTP (no STARTTLS, no AUTH), one connection after the other, every message kept with its
 * envelope recipients. Just enough for Jakarta Mail to hand a mail over, so that a test reads what
 * the server really sent.
 * </p>
 */
@SuppressWarnings("javadoc")
public final class FakeSmtpServer implements AutoCloseable {

	/** A mail as it arrived. */
	public static final class Received {

		private final List<String> _recipients;

		private final MimeMessage _message;

		Received(List<String> recipients, MimeMessage message) {
			_recipients = recipients;
			_message = message;
		}

		public List<String> getRecipients() {
			return _recipients;
		}

		public MimeMessage getMessage() {
			return _message;
		}

		public String getSubject() throws Exception {
			return _message.getSubject();
		}

		public String getText() throws Exception {
			return (String) _message.getContent();
		}
	}

	private final ServerSocket _socket;

	private final Thread _thread;

	private final List<Received> _received = new ArrayList<>();

	public FakeSmtpServer() throws IOException {
		_socket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
		_thread = new Thread(this::serve, "fake-smtp");
		_thread.setDaemon(true);
		_thread.start();
	}

	public int getPort() {
		return _socket.getLocalPort();
	}

	public synchronized List<Received> getReceived() {
		return new ArrayList<>(_received);
	}

	private void serve() {
		while (!_socket.isClosed()) {
			try (Socket client = _socket.accept()) {
				session(client);
			} catch (IOException ex) {
				// Closed, or a client that went away.
			}
		}
	}

	private void session(Socket client) throws IOException {
		BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
		OutputStream raw = client.getOutputStream();
		PrintWriter out = new PrintWriter(raw, true, StandardCharsets.ISO_8859_1);
		reply(out, "220 fake ESMTP");
		List<String> recipients = new ArrayList<>();
		String line;
		while ((line = in.readLine()) != null) {
			String command = line.length() >= 4 ? line.substring(0, 4).toUpperCase() : line.toUpperCase();
			switch (command) {
				case "EHLO":
					reply(out, "250-fake\r\n250 8BITMIME");
					break;
				case "HELO":
				case "MAIL":
				case "RSET":
				case "NOOP":
					if (command.equals("RSET")) {
						recipients.clear();
					}
					reply(out, "250 OK");
					break;
				case "RCPT":
					int open = line.indexOf('<');
					int close = line.indexOf('>');
					recipients.add(line.substring(open + 1, close));
					reply(out, "250 OK");
					break;
				case "DATA":
					reply(out, "354 go ahead");
					StringBuilder data = new StringBuilder();
					String body;
					while ((body = in.readLine()) != null && !body.equals(".")) {
						data.append(body.startsWith("..") ? body.substring(1) : body).append("\r\n");
					}
					try {
						MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
							new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.ISO_8859_1)));
						synchronized (this) {
							_received.add(new Received(new ArrayList<>(recipients), message));
						}
					} catch (Exception ex) {
						throw new IOException(ex);
					}
					recipients.clear();
					reply(out, "250 queued");
					break;
				case "QUIT":
					reply(out, "221 bye");
					return;
				default:
					reply(out, "502 not implemented");
			}
		}
	}

	private static void reply(PrintWriter out, String text) {
		out.print(text + "\r\n");
		out.flush();
	}

	@Override
	public void close() throws IOException {
		_socket.close();
	}
}
