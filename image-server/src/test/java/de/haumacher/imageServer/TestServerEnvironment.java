/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.mail.MailSettings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import junit.framework.TestCase;

/**
 * Test case for {@link ServerEnvironment}: what the environment configures, see issue #199.
 */
@SuppressWarnings("javadoc")
public class TestServerEnvironment extends TestCase {

	public void testNothingConfiguredIsNothing() throws Exception {
		ServerEnvironment environment = ServerEnvironment.read(Map.of());
		assertNull(environment.getPublicUrl());
		assertNull(environment.getMail());
		assertSame(EmailProofs.NONE, environment.emailProofs());
		assertFalse(environment.emailProofs().isAvailable());
	}

	public void testAMailAccountWithItsDefaults() throws Exception {
		ServerEnvironment environment = ServerEnvironment.read(Map.of(
			"VALBUM_SMTP_HOST", " smtp.example.org ",
			"VALBUM_SMTP_USER", "album@example.org",
			"VALBUM_SMTP_PASSWORD", " pass word "));
		MailSettings mail = environment.getMail();
		assertEquals("smtp.example.org", mail.getHost());
		assertEquals(MailSettings.Security.STARTTLS, mail.getSecurity());
		assertEquals(587, mail.getPort());
		assertEquals("The account is the sender.", "album@example.org", mail.getFrom());
		assertEquals("A password is taken as it stands.", " pass word ", mail.getPassword());
		assertTrue(environment.emailProofs().isAvailable());
	}

	public void testTlsHasItsOwnPort() throws Exception {
		MailSettings mail = ServerEnvironment.read(env("VALBUM_SMTP_TLS", "TLS")).getMail();
		assertEquals(MailSettings.Security.TLS, mail.getSecurity());
		assertEquals(465, mail.getPort());
		assertEquals(2525, ServerEnvironment.read(env("VALBUM_SMTP_PORT", "2525")).getMail().getPort());
	}

	public void testUnusableSettingsAreRefusedNamingTheVariable() {
		assertInvalid("VALBUM_SMTP_TLS", env("VALBUM_SMTP_TLS", "ssl"));
		assertInvalid("VALBUM_SMTP_PORT", env("VALBUM_SMTP_PORT", "smtp"));
		assertInvalid("VALBUM_SMTP_PORT", env("VALBUM_SMTP_PORT", "70000"));
		assertInvalid("VALBUM_SMTP_FROM", Map.of("VALBUM_SMTP_HOST", "smtp.example.org", "VALBUM_SMTP_USER", "album"));
		assertInvalid("VALBUM_SMTP_FROM", env("VALBUM_SMTP_FROM", "not an address"));
		assertInvalid("VALBUM_SMTP_TLS=none", env("VALBUM_SMTP_TLS", "none"));
		assertInvalid("VALBUM_PUBLIC_URL", Map.of("VALBUM_PUBLIC_URL", "photos.example.org"));
		assertInvalid("VALBUM_PUBLIC_URL", Map.of("VALBUM_PUBLIC_URL", "ftp://photos.example.org/"));
		assertInvalid("VALBUM_PUBLIC_URL", Map.of("VALBUM_PUBLIC_URL", "https://photos.example.org/?x=1"));
	}

	public void testAnUnencryptedRelayOnThisMachine() throws Exception {
		Map<String, String> env = new HashMap<>(env("VALBUM_SMTP_TLS", "none"));
		env.put("VALBUM_SMTP_HOST", "localhost");
		assertEquals(MailSettings.Security.NONE, ServerEnvironment.read(env).getMail().getSecurity());
	}

	public void testThePublicUrlLosesItsTrailingSlash() throws Exception {
		assertEquals("https://photos.example.org/valbum",
			ServerEnvironment.read(Map.of("VALBUM_PUBLIC_URL", "https://photos.example.org/valbum/")).getPublicUrl());
	}

	public void testTheStartUpNeverPrintsThePassword() throws Exception {
		Map<String, String> env = new HashMap<>(env("VALBUM_SMTP_PASSWORD", "s3cret!"));
		env.put("VALBUM_PUBLIC_URL", "https://photos.example.org");
		List<String> lines = Main.environmentReport(ServerEnvironment.read(env));
		assertEquals(2, lines.size());
		assertTrue(lines.toString(), lines.get(0).contains("https://photos.example.org"));
		assertTrue(lines.toString(), lines.get(1).contains("smtp.example.org"));
		assertFalse(lines.toString(), lines.toString().contains("s3cret!"));
		assertTrue(Main.environmentReport(ServerEnvironment.NONE).get(1).contains("not configured"));
	}

	private static Map<String, String> env(String name, String value) {
		Map<String, String> result = new HashMap<>();
		result.put("VALBUM_SMTP_HOST", "smtp.example.org");
		result.put("VALBUM_SMTP_FROM", "album@example.org");
		result.put(name, value);
		return result;
	}

	private static void assertInvalid(String named, Map<String, String> env) {
		try {
			ServerEnvironment.read(env);
			fail("Accepted: " + env);
		} catch (ServerEnvironment.Invalid ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains(named));
		}
	}
}
