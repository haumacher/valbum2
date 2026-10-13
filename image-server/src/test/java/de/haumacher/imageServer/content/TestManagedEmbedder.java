/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The embedder that starts its own service, see issue #257, with a shell process in the place of
 * the Python service: it says the port of a fake service in this process and ends when its stdin
 * closes, as <code>embedder.py --exit-with-stdin</code> does. Nothing is started before a vector is
 * asked for; until the service runs, every call is "unavailable" saying why; a service that ends
 * by itself is started again on the next request; a failing one is backed off.
 */
@SuppressWarnings("javadoc")
public class TestManagedEmbedder extends TestCase {

	private FakeEmbedderService _service;

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_service = new FakeEmbedderService();
		_dir = Files.createTempDirectory("managed-embedder");
	}

	@Override
	protected void tearDown() throws Exception {
		_service.close();
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
		super.tearDown();
	}

	/** A launcher whose service is a shell that names the fake's port and waits for stdin to close. */
	private final class ShellLauncher implements ManagedEmbedder.Launcher {

		final AtomicInteger _prepared = new AtomicInteger();

		volatile String _failure;

		volatile String _script;

		volatile long _prepareMillis;

		boolean _closed;

		@Override
		public List<String> prepare(Consumer<String> progress) throws IOException, InterruptedException {
			_prepared.incrementAndGet();
			progress.accept("downloading the model: 12 MB so far");
			Thread.sleep(_prepareMillis);
			if (_failure != null) {
				throw new IOException(_failure);
			}
			String script = _script != null ? _script
				: "echo '{\"listening\": " + _service.port() + "}'; echo 'valbum-embedder: listening' >&2; "
					+ "touch '" + _dir.resolve("started") + "'; cat > /dev/null; touch '" + _dir.resolve("stopped") + "'";
			return List.of("sh", "-c", script);
		}

		@Override
		public String stamp() {
			return "embeddinggemma-2@daa72c5/256/fp32/1";
		}

		@Override
		public void close() {
			_closed = true;
		}
	}

	private static boolean hasShell() {
		return new File("/bin/sh").canExecute();
	}

	public void testNothingStartsUntilAVectorIsAskedFor() throws Exception {
		ShellLauncher launcher = new ShellLauncher();
		try (ManagedEmbedder embedder = new ManagedEmbedder(launcher)) {
			assertEquals("Known without a start.", "embeddinggemma-2@daa72c5/256/fp32/1", embedder.stamp());
			Thread.sleep(100);
			assertEquals(0, launcher._prepared.get());
			assertNull(embedder.getProcess());
		}
		assertTrue(launcher._closed);
	}

	public void testTheFirstRequestStartsTheServiceAndIsToldToWait() throws Exception {
		if (!hasShell()) {
			System.err.println("SKIPPED " + getName() + ": no /bin/sh.");
			return;
		}
		ShellLauncher launcher = new ShellLauncher();
		launcher._prepareMillis = 300;
		try (ManagedEmbedder embedder = new ManagedEmbedder(launcher)) {
			try {
				embedder.embedText("flowers", Embedder.TextKind.QUERY);
				fail("Not running yet.");
			} catch (EmbedderUnavailableException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("being prepared"));
			}
			String reason = embedder.unavailability();
			assertTrue(reason, reason.contains("being prepared"));
			assertTrue(embedder.awaitAvailable(Duration.ofSeconds(10)));
			assertEquals(1, launcher._prepared.get());
			float[] vector = embedder.embedText("flowers", Embedder.TextKind.QUERY);
			assertEquals(Embedder.DIMENSION, vector.length);
			assertEquals("task: search result | query: flowers", _service._requests.get(0).input());
			assertTrue(Files.exists(_dir.resolve("started")));
			assertNotNull(embedder.getProcess());
		}
		// The service ends with the server: its stdin is closed.
		for (int n = 0; n < 50 && !Files.exists(_dir.resolve("stopped")); n++) {
			Thread.sleep(100);
		}
		assertTrue(Files.exists(_dir.resolve("stopped")));
	}

	public void testAServiceThatEndsByItselfIsStartedAgain() throws Exception {
		if (!hasShell()) {
			System.err.println("SKIPPED " + getName() + ": no /bin/sh.");
			return;
		}
		ShellLauncher launcher = new ShellLauncher();
		// Ends by itself after a moment, as after its idle time.
		launcher._script = "echo '{\"listening\": " + _service.port() + "}'; sleep 3; exit 0";
		try (ManagedEmbedder embedder = new ManagedEmbedder(launcher)) {
			assertTrue(embedder.awaitAvailable(Duration.ofSeconds(10)));
			Process process = embedder.getProcess();
			assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
			for (int n = 0; n < 50 && embedder.getProcess() != null; n++) {
				Thread.sleep(100);
			}
			assertNull(embedder.getProcess());
			assertTrue(embedder.awaitAvailable(Duration.ofSeconds(10)));
			assertEquals(2, launcher._prepared.get());
		}
	}

	public void testAFailedPreparationSaysWhyAndIsBackedOff() throws Exception {
		ShellLauncher launcher = new ShellLauncher();
		launcher._failure = "setup-venv: Python 3.11.2 is too old; content search needs Python 3.12 or newer.";
		try (ManagedEmbedder embedder = new ManagedEmbedder(launcher)) {
			assertFalse(embedder.awaitAvailable(Duration.ofSeconds(10)));
			String reason = embedder.unavailability();
			assertTrue(reason, reason.contains("Python 3.11.2 is too old"));
			assertTrue(reason, reason.contains("next attempt after"));
			try {
				embedder.embedImage(new byte[] { 1 });
				fail("Cannot run.");
			} catch (EmbedderUnavailableException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("too old"));
			}
			assertEquals("Backed off, not prepared again.", 1, launcher._prepared.get());
		}
	}

	public void testAServiceWhoseModelDoesNotLoadIsStoppedAndBackedOff() throws Exception {
		if (!hasShell()) {
			System.err.println("SKIPPED " + getName() + ": no /bin/sh.");
			return;
		}
		_service._status = "failed";
		_service._error = "FileNotFoundError: missing in /x: onnx/model.onnx";
		ShellLauncher launcher = new ShellLauncher();
		try (ManagedEmbedder embedder = new ManagedEmbedder(launcher)) {
			assertFalse(embedder.awaitAvailable(Duration.ofSeconds(10)));
			String reason = embedder.unavailability();
			assertTrue(reason, reason.contains("onnx/model.onnx"));
			assertTrue(reason, reason.contains("next attempt after"));
			assertNull("Stopped.", embedder.getProcess());
			for (int n = 0; n < 50 && !Files.exists(_dir.resolve("stopped")); n++) {
				Thread.sleep(100);
			}
			assertTrue(Files.exists(_dir.resolve("stopped")));
			assertEquals(1, launcher._prepared.get());
		}
	}

	public void testAServiceThatSaysNoPortFails() throws Exception {
		if (!hasShell()) {
			System.err.println("SKIPPED " + getName() + ": no /bin/sh.");
			return;
		}
		ShellLauncher launcher = new ShellLauncher();
		launcher._script = "echo 'Traceback: ModuleNotFoundError: onnxruntime' >&2; exit 1";
		try (ManagedEmbedder embedder = new ManagedEmbedder(launcher)) {
			assertFalse(embedder.awaitAvailable(Duration.ofSeconds(10)));
			String reason = embedder.unavailability();
			assertTrue(reason, reason.contains("ended before it listened"));
		}
	}
}
