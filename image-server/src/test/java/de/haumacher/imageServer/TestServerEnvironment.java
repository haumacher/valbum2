/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.mail.MailSettings;
import de.haumacher.imageServer.oidc.OidcLogins;
import de.haumacher.imageServer.oidc.OidcProvider;
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

	/** The gazetteer of issue #234: inside the library by default, elsewhere where configured. */
	public void testTheGazetteerDirectory() throws Exception {
		java.nio.file.Path base = java.nio.file.Path.of("/srv/photos");
		ServerEnvironment defaults = ServerEnvironment.read(Map.of());
		assertEquals(base.resolve(".geonames"), defaults.geonamesDirectory(base));
		assertEquals(0, defaults.getGeonamesMemory());

		ServerEnvironment configured = ServerEnvironment.read(Map.of(ServerEnvironment.GEONAMES_DIR, "/var/cache/geonames",
			ServerEnvironment.GEONAMES_MEMORY, "64"));
		assertEquals(java.nio.file.Path.of("/var/cache/geonames"), configured.geonamesDirectory(base));
		assertEquals(64L * 1024 * 1024, configured.getGeonamesMemory());
		de.haumacher.imageServer.places.Places places = configured.places(base);
		try {
			assertEquals(java.nio.file.Path.of("/var/cache/geonames"), places.getStore().getDirectory());
			assertTrue(places.getStore().isDownloading());
			assertEquals(64L * 1024 * 1024, places.getBudget());
		} finally {
			places.getStore().close();
		}

		assertNull("A test server has none.", ServerEnvironment.NONE.geonamesDirectory(base));
		assertNull(ServerEnvironment.NONE.places(base));
		assertTrue(Main.placesReport(defaults, base), Main.placesReport(defaults, base).contains("/srv/photos/.geonames"));
	}

	public void testAnUnusableGazetteerMemoryIsRefused() {
		for (String value : List.of("lots", "0", "-5")) {
			try {
				ServerEnvironment.read(Map.of(ServerEnvironment.GEONAMES_MEMORY, value));
				fail("Accepted: " + value);
			} catch (ServerEnvironment.Invalid ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains(ServerEnvironment.GEONAMES_MEMORY));
			}
		}
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

	public void testTheCodeMailNamesThePublicAddress() throws Exception {
		assertNull(ServerEnvironment.read(env("VALBUM_SMTP_PORT", "2525")).emailProofs().getPublicUrl());
		assertEquals("https://photos.example.org/valbum",
			ServerEnvironment.read(env("VALBUM_PUBLIC_URL", "https://photos.example.org/valbum/")).emailProofs()
				.getPublicUrl());
	}

	public void testThePublicUrlLosesItsTrailingSlash() throws Exception {
		assertEquals("https://photos.example.org/valbum",
			ServerEnvironment.read(Map.of("VALBUM_PUBLIC_URL", "https://photos.example.org/valbum/")).getPublicUrl());
	}

	public void testTheStartUpNeverPrintsThePassword() throws Exception {
		Map<String, String> env = new HashMap<>(env("VALBUM_SMTP_PASSWORD", "s3cret!"));
		env.put("VALBUM_PUBLIC_URL", "https://photos.example.org");
		List<String> lines = Main.environmentReport(ServerEnvironment.read(env));
		assertEquals(4, lines.size());
		assertTrue(lines.toString(), lines.get(0).contains("https://photos.example.org"));
		assertTrue(lines.toString(), lines.get(1).contains("smtp.example.org"));
		assertFalse(lines.toString(), lines.toString().contains("s3cret!"));
		assertTrue(Main.environmentReport(ServerEnvironment.NONE).get(1).contains("not configured"));
	}

	// --- Passkeys, issue #204. ---

	public void testPasskeysAreOfferedForTheHostOfThePublicAddress() throws Exception {
		ServerEnvironment environment =
			ServerEnvironment.read(Map.of("VALBUM_PUBLIC_URL", "https://photos.example.org/valbum"));
		assertEquals("photos.example.org", environment.passkeys().getRpId());
		assertEquals("Passkeys: offered for photos.example.org (origin https://photos.example.org)",
			Main.environmentReport(environment).get(3));
		assertFalse(ServerEnvironment.NONE.passkeys().isAvailable());
		assertEquals("Passkeys: not offered (VALBUM_PUBLIC_URL is not set)",
			Main.environmentReport(ServerEnvironment.NONE).get(3));
	}

	// --- OpenID Connect, issue #200. ---

	public void testGoogleNeedsOnlyItsClient() throws Exception {
		ServerEnvironment environment = ServerEnvironment.read(Map.of(
			"VALBUM_PUBLIC_URL", "https://photos.example.org/valbum",
			"VALBUM_OIDC_GOOGLE_CLIENT_ID", " 123.apps.googleusercontent.com ",
			"VALBUM_OIDC_GOOGLE_CLIENT_SECRET", "GOCSPX-secret"));
		List<OidcProvider> providers = environment.getOidcProviders();
		assertEquals(1, providers.size());
		OidcProvider google = providers.get(0);
		assertEquals("google", google.getId());
		assertEquals("Google", google.getLabel());
		assertEquals("oidc:google", google.method());
		assertEquals("123.apps.googleusercontent.com", google.getClientId());
		assertEquals(OidcProvider.GOOGLE_DISCOVERY, google.getDiscoveryUrl());
		OidcLogins logins = environment.oidcLogins();
		assertTrue(logins.isAvailable());
		assertEquals("https://photos.example.org/valbum/oidc/callback", logins.redirectUri());
	}

	public void testAFurtherProviderIsFurtherVariables() throws Exception {
		Map<String, String> env = new HashMap<>();
		env.put("VALBUM_PUBLIC_URL", "https://photos.example.org");
		env.put("VALBUM_OIDC_GOOGLE_CLIENT_ID", "g");
		env.put("VALBUM_OIDC_GOOGLE_CLIENT_SECRET", "gs");
		env.put("VALBUM_OIDC_MY_IDP_CLIENT_ID", "m");
		env.put("VALBUM_OIDC_MY_IDP_CLIENT_SECRET", "ms");
		env.put("VALBUM_OIDC_MY_IDP_DISCOVERY_URL", "https://login.example.org/.well-known/openid-configuration");
		env.put("VALBUM_OIDC_MY_IDP_LABEL", "Our Club");
		// Half of a commented-out line filled in: no setting.
		env.put("VALBUM_OIDC_OTHER_CLIENT_ID", "");
		List<OidcProvider> providers = ServerEnvironment.read(env).getOidcProviders();
		assertEquals(2, providers.size());
		assertEquals("google", providers.get(0).getId());
		assertEquals("my_idp", providers.get(1).getId());
		assertEquals("Our Club", providers.get(1).getLabel());
		assertEquals("oidc:my_idp", providers.get(1).method());
	}

	public void testWithoutAPublicAddressNoProviderIsOffered() throws Exception {
		ServerEnvironment environment = ServerEnvironment.read(Map.of(
			"VALBUM_OIDC_GOOGLE_CLIENT_ID", "g", "VALBUM_OIDC_GOOGLE_CLIENT_SECRET", "gs"));
		assertEquals(1, environment.getOidcProviders().size());
		assertSame(OidcLogins.NONE, environment.oidcLogins());
		String line = Main.environmentReport(environment).get(2);
		assertTrue(line, line.contains("not offered"));
		assertTrue(line, line.contains("VALBUM_PUBLIC_URL"));
	}

	public void testUnusableProvidersAreRefusedNamingTheVariable() {
		assertInvalid("VALBUM_OIDC_GOOGLE_CLIENT_SECRET", Map.of("VALBUM_OIDC_GOOGLE_CLIENT_ID", "g"));
		assertInvalid("VALBUM_OIDC_GOOGLE_CLIENT_ID", Map.of("VALBUM_OIDC_GOOGLE_CLIENT_SECRET", "g"));
		assertInvalid("VALBUM_OIDC_CLUB_DISCOVERY_URL",
			Map.of("VALBUM_OIDC_CLUB_CLIENT_ID", "c", "VALBUM_OIDC_CLUB_CLIENT_SECRET", "s"));
		assertInvalid("VALBUM_OIDC_CLUB_DISCOVERY_URL", Map.of("VALBUM_OIDC_CLUB_CLIENT_ID", "c",
			"VALBUM_OIDC_CLUB_CLIENT_SECRET", "s", "VALBUM_OIDC_CLUB_DISCOVERY_URL", "http://login.example.org/x"));
		assertInvalid("VALBUM_OIDC_GOOGLE_CLIENTID", Map.of("VALBUM_OIDC_GOOGLE_CLIENTID", "g"));
		assertInvalid("VALBUM_OIDC_GOOGLE_LABEL", Map.of("VALBUM_OIDC_GOOGLE_CLIENT_ID", "g",
			"VALBUM_OIDC_GOOGLE_CLIENT_SECRET", "s", "VALBUM_OIDC_GOOGLE_LABEL", "Goo\u0007gle"));
	}

	public void testTheStartUpNeverPrintsTheClientSecret() throws Exception {
		ServerEnvironment environment = ServerEnvironment.read(Map.of(
			"VALBUM_PUBLIC_URL", "https://photos.example.org/valbum",
			"VALBUM_OIDC_GOOGLE_CLIENT_ID", "123.apps.googleusercontent.com",
			"VALBUM_OIDC_GOOGLE_CLIENT_SECRET", "GOCSPX-s3cret"));
		List<String> lines = Main.environmentReport(environment);
		String line = lines.get(2);
		assertTrue(line, line.contains("Google (google)"));
		assertTrue(line, line.contains("https://photos.example.org/valbum/oidc/callback"));
		assertFalse(lines.toString(), lines.toString().contains("GOCSPX-s3cret"));
		assertFalse(environment.getOidcProviders().toString().contains("GOCSPX-s3cret"));
		assertTrue(Main.environmentReport(ServerEnvironment.NONE).get(2).contains("not configured"));
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
