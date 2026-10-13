/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The download of the model of content search, see issue #257, against a local server in the
 * place of Hugging Face: nothing before it is asked for, every file checked against the pin before
 * it is used, an interrupted download resumed, a failure backed off, and a file placed by hand
 * used when it is the right one and never touched when it is not.
 */
@SuppressWarnings("javadoc")
public class TestModelStore extends TestCase {

	private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

	private static final byte[] TOKENIZER = "{\"tokenizer\": true}".getBytes(StandardCharsets.UTF_8);

	private static final byte[] MODEL = new byte[300_000];

	private HttpServer _server;

	private final Map<String, byte[]> _files = new ConcurrentHashMap<>();

	/** Status codes to answer the next requests of a path with. */
	private final Map<String, Integer> _failures = new ConcurrentHashMap<>();

	/** Bytes after which the next answer of a path breaks off. */
	private final Map<String, Integer> _cutAt = new ConcurrentHashMap<>();

	/** Bytes after which the next answer of a path stalls. */
	private final Map<String, Integer> _stallAt = new ConcurrentHashMap<>();

	/** Every request: "<path> <Range or ->". */
	private final List<String> _requests = Collections.synchronizedList(new ArrayList<>());

	private Path _dir;

	private ContentModel _model;

	private MutableClock _clock;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		for (int n = 0; n < MODEL.length; n++) {
			MODEL[n] = (byte) (n * 31 + n / 7);
		}
		_files.put("tokenizer.json", TOKENIZER);
		_files.put("onnx/model.onnx", MODEL);
		_server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		_server.createContext("/", this::handle);
		_server.start();
		_dir = Files.createTempDirectory("embedder");
		_model = model(TOKENIZER, MODEL);
		_clock = new MutableClock();
	}

	@Override
	protected void tearDown() throws Exception {
		_server.stop(0);
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
		super.tearDown();
	}

	private static ContentModel model(byte[] tokenizer, byte[] model) throws Exception {
		String json = "{\"model\":\"google/embeddinggemma-2\",\"repository\":\"test/model-ONNX\",\"revision\":\""
			+ REVISION + "\",\"dimension\":768,\"files\":{\"common\":[" + entry("tokenizer.json", tokenizer)
			+ "],\"fp32\":[" + entry("onnx/model.onnx", model) + "]}}";
		return ContentModel.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
	}

	private static String entry(String name, byte[] content) throws Exception {
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		return "{\"name\":\"" + name + "\",\"size\":" + content.length + ",\"sha256\":\"" + hash + "\"}";
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String range = exchange.getRequestHeaders().getFirst("Range");
		_requests.add(path + " " + (range == null ? "-" : range));
		String prefix = "/test/model-ONNX/resolve/" + REVISION + "/";
		byte[] content = path.startsWith(prefix) ? _files.get(path.substring(prefix.length())) : null;
		Integer failure = _failures.remove(path);
		if (failure != null || content == null) {
			exchange.sendResponseHeaders(failure != null ? failure : 404, -1);
			exchange.close();
			return;
		}
		int from = 0;
		int status = 200;
		if (range != null && range.startsWith("bytes=") && range.endsWith("-")) {
			from = Integer.parseInt(range.substring(6, range.length() - 1));
			status = 206;
		}
		Integer cut = _cutAt.remove(path);
		Integer stall = _stallAt.remove(path);
		int length = content.length - from;
		if (cut != null) {
			// The connection breaks: closed after what was sent.
			exchange.getResponseHeaders().set("Connection", "close");
		}
		exchange.sendResponseHeaders(status, length);
		try (OutputStream out = exchange.getResponseBody()) {
			if (stall != null) {
				out.write(content, from, stall);
				out.flush();
				Thread.sleep(3000);
				return;
			}
			out.write(content, from, cut == null ? length : Math.min(length, cut));
		} catch (IOException | InterruptedException ex) {
			// The client went away.
		}
		exchange.close();
	}

	private ModelStore store(boolean download) {
		ModelStore.Settings settings = download ? new ModelStore.Settings()
			.setBase(URI.create("http://127.0.0.1:" + _server.getAddress().getPort() + "/")).setClock(_clock)
			.setFirstBackoff(Duration.ofMinutes(10)).setStall(Duration.ofMillis(500)) : null;
		return new ModelStore(_dir, _model, "fp32", settings);
	}

	public void testNothingHappensUntilTheModelIsAskedFor() throws Exception {
		try (ModelStore store = store(true)) {
			assertFalse(store.isReady());
			assertTrue(store.problem(), store.problem().contains("not in"));
			Thread.sleep(100);
			assertTrue(_requests.isEmpty());
			assertFalse(Files.exists(store.getDirectory()));
		}
	}

	public void testTheFilesAreDownloadedCheckedAndRecorded() throws Exception {
		try (ModelStore store = store(true)) {
			store.request();
			assertTrue(store.awaitReady(Duration.ofSeconds(20)));
			assertNull(store.problem());
			assertEquals(_dir.resolve("model-ONNX@01234567"), store.getDirectory());
			assertTrue(java.util.Arrays.equals(MODEL, Files.readAllBytes(store.getDirectory().resolve("onnx/model.onnx"))));
			assertTrue(Files.isRegularFile(store.getDirectory().resolve("onnx/model.onnx.download")));
			assertFalse(Files.exists(store.getDirectory().resolve("onnx/model.onnx.part")));
		}
		_requests.clear();
		try (ModelStore again = store(true)) {
			assertTrue("Known from the records, without a request.", again.isReady());
			again.request();
			assertTrue(_requests.isEmpty());
		}
	}

	public void testAnInterruptedDownloadIsResumed() throws Exception {
		_cutAt.put("/test/model-ONNX/resolve/" + REVISION + "/onnx/model.onnx", 100_000);
		try (ModelStore store = store(true)) {
			store.request();
			assertFalse(store.awaitReady(Duration.ofSeconds(20)));
			assertNotNull(store.problem());
			long have = Files.size(store.getDirectory().resolve("onnx/model.onnx.part"));
			assertTrue("Kept what arrived: " + have, have > 0 && have <= 100_000);
			_clock.advance(Duration.ofMinutes(11));
			store.request();
			assertTrue(store.awaitReady(Duration.ofSeconds(20)));
			assertTrue(_requests.toString(), _requests.contains(
				"/test/model-ONNX/resolve/" + REVISION + "/onnx/model.onnx bytes=" + have + "-"));
			assertTrue(java.util.Arrays.equals(MODEL, Files.readAllBytes(store.getDirectory().resolve("onnx/model.onnx"))));
		}
	}

	public void testAStalledDownloadIsGivenUpAndResumed() throws Exception {
		_stallAt.put("/test/model-ONNX/resolve/" + REVISION + "/onnx/model.onnx", 70_000);
		try (ModelStore store = store(true)) {
			store.request();
			assertFalse(store.awaitReady(Duration.ofSeconds(20)));
			assertTrue(store.problem(), store.problem().contains("received nothing"));
			_clock.advance(Duration.ofMinutes(11));
			store.request();
			assertTrue(store.awaitReady(Duration.ofSeconds(20)));
			assertTrue(java.util.Arrays.equals(MODEL, Files.readAllBytes(store.getDirectory().resolve("onnx/model.onnx"))));
		}
	}

	public void testAFailureIsBackedOffAndSaid() throws Exception {
		_failures.put("/test/model-ONNX/resolve/" + REVISION + "/tokenizer.json", 500);
		try (ModelStore store = store(true)) {
			store.request();
			assertFalse(store.awaitReady(Duration.ofSeconds(20)));
			assertTrue(store.problem(), store.problem().contains("HTTP 500"));
			assertTrue(store.problem(), store.problem().contains("next attempt after"));
			int asked = _requests.size();
			store.request();
			assertFalse(store.awaitReady(Duration.ofMillis(200)));
			assertEquals("Backed off.", asked, _requests.size());
			_clock.advance(Duration.ofMinutes(11));
			store.request();
			assertTrue(store.awaitReady(Duration.ofSeconds(20)));
		}
	}

	public void testAWrongDownloadIsDropped() throws Exception {
		byte[] other = MODEL.clone();
		other[5] ^= 1;
		_files.put("onnx/model.onnx", other);
		try (ModelStore store = store(true)) {
			store.request();
			assertFalse(store.awaitReady(Duration.ofSeconds(20)));
			assertTrue(store.problem(), store.problem().contains("not the file of the pinned model"));
			assertFalse(Files.exists(store.getDirectory().resolve("onnx/model.onnx")));
			assertFalse(Files.exists(store.getDirectory().resolve("onnx/model.onnx.part")));
		}
	}

	public void testFilesPlacedByHandAreCheckedAndUsedWithoutTheNetwork() throws Exception {
		Path dir = _dir.resolve(ModelStore.folderName(_model));
		Files.createDirectories(dir.resolve("onnx"));
		Files.write(dir.resolve("tokenizer.json"), TOKENIZER);
		Files.write(dir.resolve("onnx/model.onnx"), MODEL);
		try (ModelStore store = store(false)) {
			assertFalse("Not checked yet.", store.isReady());
			store.request();
			assertTrue(store.awaitReady(Duration.ofSeconds(20)));
			assertTrue(_requests.isEmpty());
			assertTrue(Files.readString(dir.resolve("onnx/model.onnx.download")).contains("placed by hand"));
		}
	}

	public void testAWrongFilePlacedByHandIsNamedAndLeftAlone() throws Exception {
		Path dir = _dir.resolve(ModelStore.folderName(_model));
		Files.createDirectories(dir.resolve("onnx"));
		Files.write(dir.resolve("tokenizer.json"), TOKENIZER);
		Files.write(dir.resolve("onnx/model.onnx"), "something else".getBytes(StandardCharsets.UTF_8));
		try (ModelStore store = store(true)) {
			store.request();
			assertFalse(store.awaitReady(Duration.ofSeconds(20)));
			assertTrue(store.problem(), store.problem().contains("placed by hand but is not the file"));
			assertEquals("something else", Files.readString(dir.resolve("onnx/model.onnx")));
			assertTrue("Nothing downloaded over it.", _requests.isEmpty());
		}
	}

	public void testWithoutDownloadsAMissingFileIsNamed() throws Exception {
		try (ModelStore store = store(false)) {
			store.request();
			assertFalse(store.awaitReady(Duration.ofSeconds(20)));
			assertTrue(store.problem(), store.problem().contains("placed there by hand"));
			assertTrue(store.problem(), store.problem().contains(REVISION));
		}
	}

	public void testAFileChangedAfterItsCheckIsCheckedAgain() throws Exception {
		try (ModelStore store = store(true)) {
			store.request();
			assertTrue(store.awaitReady(Duration.ofSeconds(20)));
		}
		Path file = _dir.resolve(ModelStore.folderName(_model)).resolve("onnx/model.onnx");
		Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
		try (ModelStore store = store(true)) {
			assertFalse(store.isReady());
			store.request();
			assertTrue(store.awaitReady(Duration.ofSeconds(20)));
		}
	}

	public void testTheBundledPinIsComplete() throws Exception {
		ContentModel bundled = ContentModel.bundled();
		assertEquals("google/embeddinggemma-2", bundled.getModel());
		assertEquals("onnx-community/embeddinggemma-2-ONNX", bundled.getRepository());
		assertEquals(40, bundled.getRevision().length());
		assertEquals(List.of("fp32", "int8"), bundled.precisions());
		for (String precision : bundled.precisions()) {
			for (ContentModel.ModelFile file : bundled.files(precision)) {
				assertFalse("Never the audio encoder: " + file.name(), file.name().contains("audio"));
				assertFalse("Never fp16: " + file.name(), file.name().contains("fp16"));
			}
		}
		assertTrue(bundled.size("fp32") > 1_500_000_000L);
		assertEquals("embeddinggemma-2@daa72c5/256/fp32/1", bundled.stamp("fp32", 256));
	}

	public void testThePinRefusesAPathOutOfTheModel() throws Exception {
		String json = "{\"model\":\"m\",\"repository\":\"r/x\",\"revision\":\"" + REVISION
			+ "\",\"dimension\":768,\"files\":{\"common\":[{\"name\":\"../../etc/x\",\"size\":1,\"sha256\":\""
			+ "0".repeat(64) + "\"}]}}";
		try {
			ContentModel.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
			fail("Out of the model's folder.");
		} catch (IOException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("../../etc/x"));
		}
	}

	static final class MutableClock extends Clock {

		volatile Instant _now = Instant.parse("2026-10-13T12:00:00Z");

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return _now;
		}

		void advance(Duration duration) {
			_now = _now.plus(duration);
		}
	}
}
