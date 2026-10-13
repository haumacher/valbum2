/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.ServerEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The settings of content search from the environment, see issue #257 and
 * <code>faq/server-config-from-environment.md</code>: defaults, refusals naming the variable, and
 * an embedder that costs nothing until it is asked; and the service code that travels in the JAR.
 */
@SuppressWarnings("javadoc")
public class TestEmbedderSettings extends TestCase {

	public void testDefaults() throws Exception {
		EmbedderSettings settings = EmbedderSettings.read(Map.of());
		assertTrue(settings.isOn());
		assertEquals("fp32", settings.getPrecision());
		assertEquals(0, settings.getPort());
		assertNull(settings.getUrl());
		assertEquals(Path.of("/photos/.embedder"), settings.directory(Path.of("/photos")));
		assertTrue(settings.report(Path.of("/photos")), settings.report(Path.of("/photos")).contains("only when a space uses it"));
	}

	public void testEverySetting() throws Exception {
		EmbedderSettings settings = EmbedderSettings.read(Map.of(EmbedderSettings.DIR, "/var/cache/valbum/embedder",
			EmbedderSettings.PORT, "8091", EmbedderSettings.PRECISION, "INT8"));
		assertEquals(Path.of("/var/cache/valbum/embedder"), settings.directory(Path.of("/photos")));
		assertEquals(8091, settings.getPort());
		assertEquals("int8", settings.getPrecision());
	}

	public void testAnUnusableSettingIsRefusedNamingIt() {
		assertRefused(Map.of(EmbedderSettings.PORT, "x"), EmbedderSettings.PORT);
		assertRefused(Map.of(EmbedderSettings.PORT, "70000"), EmbedderSettings.PORT);
		assertRefused(Map.of(EmbedderSettings.PRECISION, "fp16"), EmbedderSettings.PRECISION);
		assertRefused(Map.of(EmbedderSettings.URL, "ftp://x/"), EmbedderSettings.URL);
		assertRefused(Map.of(EmbedderSettings.URL, "127.0.0.1:8091"), EmbedderSettings.URL);
	}

	private static void assertRefused(Map<String, String> env, String variable) {
		try {
			EmbedderSettings.read(env);
			fail("Refused: " + env);
		} catch (ServerEnvironment.Invalid ex) {
			assertTrue(ex.getMessage(), ex.getMessage().startsWith(variable));
		}
	}

	public void testTheServerEnvironmentCarriesThem() throws Exception {
		ServerEnvironment environment = ServerEnvironment.read(Map.of(EmbedderSettings.PRECISION, "int8"));
		assertEquals("int8", environment.getEmbedder().getPrecision());
		assertFalse("A test server has no content search.", ServerEnvironment.NONE.getEmbedder().isOn());
		assertNull(ServerEnvironment.NONE.getEmbedder().create(Path.of(".")));
	}

	public void testAnEmbedderOfItsOwnIsTalkedTo() throws Exception {
		EmbedderSettings settings = EmbedderSettings.read(Map.of(EmbedderSettings.URL, "http://127.0.0.1:8091"));
		try (Embedder embedder = settings.create(Path.of("/photos"))) {
			assertTrue(embedder instanceof HttpEmbedder);
			assertEquals("http://127.0.0.1:8091/", ((HttpEmbedder) embedder).getBase().toString());
		}
	}

	public void testCreatingTheEmbedderTouchesNothing() throws Exception {
		Path base = Files.createTempDirectory("library");
		try {
			try (Embedder embedder = EmbedderSettings.read(Map.of()).create(base)) {
				assertTrue(embedder instanceof ManagedEmbedder);
				assertEquals("embeddinggemma-2@daa72c5/256/fp32/1", embedder.stamp());
			}
			try (Stream<Path> files = Files.list(base)) {
				assertEquals("Nothing written into the library.", 0, files.count());
			}
		} finally {
			Files.delete(base);
		}
	}

	public void testTheServiceCodeTravelsInTheJar() throws Exception {
		Path dir = Files.createTempDirectory("embedder");
		try {
			Path code = PythonService.unpack(dir);
			for (String name : PythonService.CODE) {
				assertTrue(name, Files.isRegularFile(code.resolve(name)));
			}
			assertTrue(Files.readString(code.resolve("embedder.py")).contains("/v1/embeddings"));
			assertTrue(Files.readString(code.resolve("requirements.txt")).contains("--hash=sha256:"));
			assertEquals("Unpacked once, named after its content.", code, PythonService.unpack(dir));
		} finally {
			try (Stream<Path> files = Files.walk(dir)) {
				files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
	}
}
