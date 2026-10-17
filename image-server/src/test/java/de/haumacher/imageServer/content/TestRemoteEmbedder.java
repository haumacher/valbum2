/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.ServerEnvironment;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import junit.framework.TestCase;

/**
 * The client of an embedder service on another machine, see issue #257: the key it sends and a
 * refused key, an https address with a pinned self-signed certificate, the refusal of vectors of
 * another model or preprocessing, the settings, and that the check sends a preview, never the
 * original.
 */
@SuppressWarnings("javadoc")
public class TestRemoteEmbedder extends TestCase {

	private static final Duration LIMIT = Duration.ofSeconds(5);

	private FakeEmbedderService _service;

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_service = new FakeEmbedderService();
		_dir = Files.createTempDirectory("remote-embedder");
	}

	@Override
	protected void tearDown() throws Exception {
		_service.close();
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	private HttpEmbedder client(String key) {
		return new HttpEmbedder(_service.url(), key, null, LIMIT, LIMIT);
	}

	// --- The key ---------------------------------------------------------------------------------

	public void testTheKeyIsSentWithEveryRequest() throws Exception {
		_service._key = "s3cret";
		try (HttpEmbedder embedder = client("s3cret")) {
			assertNull(embedder.unavailability());
			assertEquals(Embedder.DIMENSION, embedder.embedText("x", Embedder.TextKind.QUERY).length);
			assertEquals(Embedder.DIMENSION, embedder.embedImage(new byte[] { (byte) 0xFF, (byte) 0xD8 }).length);
		}
		assertEquals("Bearer s3cret", _service._healthAuthorizations.get(0));
		for (FakeEmbedderService.Request request : _service._requests) {
			assertEquals("Bearer s3cret", request.authorization());
		}
	}

	public void testNoKeyIsSentWhereNoneIsSet() throws Exception {
		try (HttpEmbedder embedder = client(null)) {
			embedder.embedText("x", Embedder.TextKind.QUERY);
		}
		assertNull(_service._requests.get(0).authorization());
	}

	public void testAMissingKeyMakesTheServiceUnavailableSayingWhy() throws Exception {
		_service._key = "s3cret";
		try (HttpEmbedder embedder = client(null)) {
			try {
				embedder.embedText("x", Embedder.TextKind.QUERY);
				fail("Refused.");
			} catch (EmbedderUnavailableException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("refuses this server: this embedder service needs a key"));
				assertTrue(ex.getMessage(), ex.getMessage().contains("VALBUM_EMBEDDER_KEY is not set here"));
			}
			String reason = embedder.unavailability();
			assertTrue(reason, reason.contains("needs a key"));
			assertNull("No stamp from a service that does not answer.", embedder.stamp());
		}
	}

	public void testAWrongKeyMakesTheServiceUnavailableSayingWhy() throws Exception {
		_service._key = "s3cret";
		try (HttpEmbedder embedder = client("guessed")) {
			try {
				embedder.embedImage(new byte[] { (byte) 0xFF, (byte) 0xD8 });
				fail("Refused.");
			} catch (EmbedderUnavailableException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("the key is wrong"));
				assertFalse(ex.getMessage(), ex.getMessage().contains("not set here"));
			}
			assertFalse(embedder.awaitAvailable(Duration.ofMillis(300)));
		}
	}

	// --- The model of the vectors ----------------------------------------------------------------

	public void testAServiceOfAnotherPreprocessingIsRefused() throws Exception {
		_service._preprocessing = ContentModel.PREPROCESSING + 1;
		try (HttpEmbedder embedder = client(null)) {
			String reason = embedder.unavailability();
			assertNotNull(reason);
			assertTrue(reason, reason.contains("preprocesses by version " + (ContentModel.PREPROCESSING + 1)));
			assertTrue(reason, reason.contains("same VAlbum release"));
			assertNull("No stamp that would lie.", embedder.stamp());
			long start = System.nanoTime();
			assertFalse(embedder.awaitAvailable(Duration.ofSeconds(10)));
			assertTrue("Not waited for.", System.nanoTime() - start < 5_000_000_000L);
			try {
				embedder.embedText("x", Embedder.TextKind.QUERY);
				fail("Refused.");
			} catch (EmbedderUnavailableException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("preprocesses by version"));
			}
		}
	}

	public void testAServiceThatNamesNoPreprocessingIsRefused() throws Exception {
		_service._preprocessing = -1;
		try (HttpEmbedder embedder = client(null)) {
			assertTrue(embedder.unavailability(), embedder.unavailability().contains("(none named)"));
			try {
				embedder.embedText("x", Embedder.TextKind.QUERY);
				fail("Refused.");
			} catch (EmbedderUnavailableException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("does not name the model of its vectors"));
			}
		}
	}

	public void testAVectorOfAnotherModelThanTheStampIsRefused() throws Exception {
		try (HttpEmbedder embedder = client(null)) {
			String stamp = embedder.stamp();
			assertEquals("embeddinggemma-2@daa72c5/256/int8/" + ContentModel.PREPROCESSING, stamp);
			// The service is restarted with the other precision between the stamp and the vector.
			_service._answerPrecision = "fp32";
			try {
				embedder.embedText("x", Embedder.TextKind.QUERY);
				fail("Another model.");
			} catch (EmbedderUnavailableException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("now embeds with embeddinggemma-2@daa72c5/256/fp32/"));
				assertTrue(ex.getMessage(), ex.getMessage().contains("not " + stamp));
			}
			// The stamp follows the service once it says so itself.
			_service._precision = "fp32";
			assertEquals("embeddinggemma-2@daa72c5/256/fp32/" + ContentModel.PREPROCESSING, embedder.stamp());
			assertEquals(Embedder.DIMENSION, embedder.embedText("x", Embedder.TextKind.QUERY).length);
		}
	}

	public void testTheServiceAndTheServerPreprocessAlike() throws Exception {
		try (InputStream in = ContentModel.class.getResourceAsStream(ContentModel.RESOURCE_FOLDER + "embedder.py")) {
			String code = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			Matcher version = Pattern.compile("(?m)^PREPROCESSING = (\\d+)$").matcher(code);
			assertTrue("embedder.py names its preprocessing.", version.find());
			assertEquals(ContentModel.PREPROCESSING, Integer.parseInt(version.group(1)));
		}
	}

	// --- TLS -----------------------------------------------------------------------------------------

	/** A self-signed certificate for 127.0.0.1 in a PKCS12 key store, made with the JDK's keytool. */
	private Path keyStore() throws Exception {
		Path store = _dir.resolve("embedder.p12");
		Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
		Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "embedder", "-keyalg", "EC",
			"-groupname", "secp256r1", "-dname", "CN=embedder", "-ext", "san=ip:127.0.0.1", "-validity", "2",
			"-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit").redirectErrorStream(true)
				.start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(out, 0, process.waitFor());
		return store;
	}

	private static KeyStore load(Path store) throws Exception {
		KeyStore keys = KeyStore.getInstance("PKCS12");
		try (FileInputStream in = new FileInputStream(store.toFile())) {
			keys.load(in, "changeit".toCharArray());
		}
		return keys;
	}

	private static SSLContext serverContext(KeyStore keys) throws Exception {
		KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		factory.init(keys, "changeit".toCharArray());
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(factory.getKeyManagers(), null, null);
		return context;
	}

	public void testAPinnedSelfSignedCertificateIsAcceptedAndNoOther() throws Exception {
		KeyStore keys = load(keyStore());
		byte[] pin = HttpEmbedder.fingerprint((X509Certificate) keys.getCertificate("embedder"));
		try (FakeEmbedderService https = new FakeEmbedderService(serverContext(keys))) {
			https._key = "s3cret";
			assertTrue(https.url().toString().startsWith("https://"));

			try (HttpEmbedder embedder = new HttpEmbedder(https.url(), "s3cret", pin, LIMIT, LIMIT)) {
				assertNull(embedder.unavailability());
				assertEquals(Embedder.DIMENSION, embedder.embedText("x", Embedder.TextKind.QUERY).length);
			}

			byte[] other = pin.clone();
			other[0] ^= 1;
			try (HttpEmbedder embedder = new HttpEmbedder(https.url(), "s3cret", other, LIMIT, LIMIT)) {
				embedder.embedText("x", Embedder.TextKind.QUERY);
				fail("Another certificate.");
			} catch (EmbedderUnavailableException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("TLS handshake"));
			}

			try (HttpEmbedder embedder = new HttpEmbedder(https.url(), "s3cret", null, LIMIT, LIMIT)) {
				embedder.embedText("x", Embedder.TextKind.QUERY);
				fail("A self-signed certificate is not trusted without its pin.");
			} catch (EmbedderUnavailableException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("TLS handshake"));
				assertTrue(ex.getMessage(), ex.getMessage().contains(EmbedderSettings.CERT_SHA256));
			}
		}
	}

	// --- The settings ------------------------------------------------------------------------------

	public void testTheKeyAndThePinAreRead() throws Exception {
		String colons = HexFormat.ofDelimiter(":").withUpperCase().formatHex(new byte[32]).replace("00", "AB");
		EmbedderSettings settings = EmbedderSettings.read(Map.of(EmbedderSettings.URL, "https://gpu.local:8091/",
			EmbedderSettings.KEY, "0123abcd", EmbedderSettings.CERT_SHA256, colons));
		assertTrue(settings.hasKey());
		assertTrue(settings.hasPinnedCertificate());
		String report = settings.report(Path.of("/photos"));
		assertTrue(report, report.contains("with its key") && report.contains("TLS with the pinned certificate"));
		assertFalse("The key is never printed: " + report, report.contains("0123abcd"));
		try (Embedder embedder = settings.create(Path.of("/photos"))) {
			assertTrue(((HttpEmbedder) embedder).hasKey());
		}
	}

	public void testAnUnencryptedServiceIsSaidToBeOne() throws Exception {
		String report = EmbedderSettings.read(Map.of(EmbedderSettings.URL, "http://gpu.local:8091/",
			EmbedderSettings.KEY, "k")).report(Path.of("/photos"));
		assertTrue(report, report.contains("unencrypted") && report.contains("the key travel in the clear"));
		report = EmbedderSettings.read(Map.of(EmbedderSettings.URL, "http://gpu.local:8091/")).report(Path.of("/p"));
		assertTrue(report, report.contains("without a key (VALBUM_EMBEDDER_KEY)"));
	}

	public void testAKeyWithoutAServiceOfItsOwnIsNamedAsUnused() throws Exception {
		String report = EmbedderSettings.read(Map.of(EmbedderSettings.KEY, "k")).report(Path.of("/photos"));
		assertTrue(report, report.contains("VALBUM_EMBEDDER_KEY is set but used only with VALBUM_EMBEDDER_URL"));
	}

	public void testUnusableKeysAndPinsAreRefusedNamingThem() {
		assertRefused(Map.of(EmbedderSettings.URL, "http://x/", EmbedderSettings.KEY, "two words"), EmbedderSettings.KEY);
		assertRefused(Map.of(EmbedderSettings.URL, "https://x/", EmbedderSettings.CERT_SHA256, "abc"),
			EmbedderSettings.CERT_SHA256);
		assertRefused(Map.of(EmbedderSettings.URL, "http://x/", EmbedderSettings.CERT_SHA256, "ab".repeat(32)),
			EmbedderSettings.CERT_SHA256);
		assertRefused(Map.of(EmbedderSettings.CERT_SHA256, "ab".repeat(32)), EmbedderSettings.CERT_SHA256);
	}

	private static void assertRefused(Map<String, String> env, String variable) {
		try {
			EmbedderSettings.read(env);
			fail("Refused: " + env);
		} catch (ServerEnvironment.Invalid ex) {
			assertTrue(ex.getMessage(), ex.getMessage().startsWith(variable));
		}
	}

	// --- What is sent of a photograph ---------------------------------------------------------------

	public void testTheCheckSendsThePreviewNeverTheOriginal() throws Exception {
		Path original = _dir.resolve("P3031379.JPG");
		Files.copy(Path.of("src/test/fixtures/test-album/2002-03-03 Schlosspark Karlsruhe/P3031379.JPG"), original,
			StandardCopyOption.REPLACE_EXISTING);
		try (HttpEmbedder embedder = client(null)) {
			assertEquals(0, EmbedderCheck.check(embedder, List.of(original)));
		}
		byte[] sent = null;
		for (FakeEmbedderService.Request request : _service._requests) {
			if ("image".equals(request.modality())) {
				sent = Base64.getDecoder().decode(request.input().substring(request.input().indexOf(',') + 1));
			}
		}
		assertNotNull("A picture was sent.", sent);
		byte[] preview = Files.readAllBytes(de.haumacher.imageServer.PreviewCache.previewFile(original.toFile()).toPath());
		assertTrue("The preview, as the server makes it.", java.util.Arrays.equals(preview, sent));
		assertTrue("Smaller than the original.", sent.length < Files.size(original));
	}

	public void testAPictureWithoutAPreviewIsNotSent() throws Exception {
		Path broken = _dir.resolve("broken.jpg");
		Files.writeString(broken, "no picture", StandardCharsets.UTF_8);
		try (HttpEmbedder embedder = client(null)) {
			assertEquals(0, EmbedderCheck.check(embedder, List.of(broken)));
		}
		for (FakeEmbedderService.Request request : _service._requests) {
			assertEquals("Only the texts.", "text", request.modality());
		}
	}
}
