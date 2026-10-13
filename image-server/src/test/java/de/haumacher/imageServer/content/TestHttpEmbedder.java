/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import junit.framework.TestCase;

/**
 * The client of the embedder service against a fake service, see issue #257: what it sends (the
 * task prefixes, the dimension, a picture as a data URI), what it accepts (a vector of the asked
 * width and of length 1) and how it refuses: a service that is not there, still loading or failed
 * is "unavailable" with the reason, a refused input is an error of its own.
 */
@SuppressWarnings("javadoc")
public class TestHttpEmbedder extends TestCase {

	private FakeEmbedderService _service;

	private HttpEmbedder _embedder;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_service = new FakeEmbedderService();
		_embedder = new HttpEmbedder(_service.url(), Duration.ofSeconds(2), Duration.ofSeconds(2));
	}

	@Override
	protected void tearDown() throws Exception {
		_embedder.close();
		_service.close();
		super.tearDown();
	}

	public void testAQueryCarriesTheTaskPrefixOfTheModelCard() throws Exception {
		float[] vector = _embedder.embedText("Blumen im Regen", Embedder.TextKind.QUERY);
		assertEquals(Embedder.DIMENSION, vector.length);
		assertEquals(0.6f, vector[0], 1e-6f);
		assertEquals(0.8f, vector[1], 1e-6f);
		FakeEmbedderService.Request request = _service._requests.get(0);
		assertEquals("task: search result | query: Blumen im Regen", request.input());
		assertEquals("text", request.modality());
		assertEquals(256, request.dimensions());
		assertEquals("base64", request.encoding());
	}

	public void testADocumentHasATitleOrNone() throws Exception {
		_embedder.embedText("Schlosspark Karlsruhe; March 2002", Embedder.TextKind.DOCUMENT);
		_embedder.embedDocument("2025-10 Italien", "Leaning Tower of Pisa, Pisa, Tuscany, Italy");
		_embedder.embedDocument("  ", "no title");
		_embedder.embedDocument("A | B", "a bar in the title");
		assertEquals("title: none | text: Schlosspark Karlsruhe; March 2002", _service._requests.get(0).input());
		assertEquals("title: 2025-10 Italien | text: Leaning Tower of Pisa, Pisa, Tuscany, Italy",
			_service._requests.get(1).input());
		assertEquals("title: none | text: no title", _service._requests.get(2).input());
		assertEquals("title: A / B | text: a bar in the title", _service._requests.get(3).input());
	}

	public void testAPictureIsSentAsADataUri() throws Exception {
		byte[] jpeg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3 };
		_embedder.embedImage(jpeg);
		byte[] png = { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 };
		_embedder.embedImage(png);
		FakeEmbedderService.Request request = _service._requests.get(0);
		assertEquals("image", request.modality());
		assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg), request.input());
		assertTrue(_service._requests.get(1).input().startsWith("data:image/png;base64,"));
	}

	public void testVectorsAsNumbersAreReadToo() throws Exception {
		_service._floats = true;
		float[] vector = _embedder.embedText("x", Embedder.TextKind.QUERY);
		assertEquals(0.8f, vector[1], 1e-6f);
	}

	public void testTheStampComesFromTheService() throws Exception {
		assertEquals("embeddinggemma-2@daa72c5/256/int8/" + ContentModel.PREPROCESSING, _embedder.stamp());
		assertNull(_embedder.unavailability());
		assertTrue(_embedder.awaitAvailable(Duration.ofSeconds(1)));
	}

	public void testAServiceThatIsNotThereIsUnavailable() throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		try (HttpEmbedder nowhere = new HttpEmbedder(URI.create("http://127.0.0.1:" + port + "/"))) {
			try {
				nowhere.embedText("x", Embedder.TextKind.QUERY);
				fail("Nothing answers.");
			} catch (EmbedderUnavailableException ex) {
				assertTrue(ex.getMessage(), ex.getMessage().contains("does not answer"));
			}
			assertNotNull(nowhere.unavailability());
			assertNull("No stamp without an answer.", nowhere.stamp());
			assertFalse(nowhere.awaitAvailable(Duration.ofMillis(300)));
		}
	}

	public void testALoadingServiceIsUnavailableUntilItIsReady() throws Exception {
		_service._status = "loading";
		try {
			_embedder.embedText("x", Embedder.TextKind.QUERY);
			fail("Still loading.");
		} catch (EmbedderUnavailableException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("loading"));
		}
		assertTrue(_embedder.unavailability(), _embedder.unavailability().contains("loading"));
		new Thread(() -> {
			try {
				Thread.sleep(300);
			} catch (InterruptedException ex) {
				return;
			}
			_service._status = "ready";
		}).start();
		assertTrue(_embedder.awaitAvailable(Duration.ofSeconds(5)));
		assertEquals(Embedder.DIMENSION, _embedder.embedText("x", Embedder.TextKind.QUERY).length);
	}

	public void testAFailedServiceSaysWhyAndIsNotWaitedFor() throws Exception {
		_service._status = "failed";
		_service._error = "FileNotFoundError: missing in /x: onnx/model.onnx";
		String reason = _embedder.unavailability();
		assertTrue(reason, reason.contains("onnx/model.onnx"));
		long start = System.nanoTime();
		assertFalse(_embedder.awaitAvailable(Duration.ofSeconds(10)));
		assertTrue("Not waited for.", System.nanoTime() - start < 5_000_000_000L);
	}

	public void testARefusedInputIsAnErrorNotAnUnavailability() throws Exception {
		_service._failWith = 400;
		_service._failMessage = "not an image this service can read";
		try {
			_embedder.embedImage("no picture".getBytes(StandardCharsets.UTF_8));
			fail("Refused.");
		} catch (EmbedderUnavailableException ex) {
			fail("A broken picture does not get better later: " + ex.getMessage());
		} catch (IOException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("not an image"));
			assertTrue(ex.getMessage(), ex.getMessage().contains("400"));
		}
	}

	public void testA503IsUnavailable() throws Exception {
		_service._failWith = 503;
		_service._failMessage = "the model is failed: out of memory";
		try {
			_embedder.embedText("x", Embedder.TextKind.QUERY);
			fail("Unavailable.");
		} catch (EmbedderUnavailableException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("out of memory"));
		}
	}

	public void testASlowServiceRunsIntoTheTimeLimit() throws Exception {
		_service._delayMillis = 4000;
		try {
			_embedder.embedText("x", Embedder.TextKind.QUERY);
			fail("Too slow.");
		} catch (EmbedderUnavailableException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("did not answer within"));
		}
	}

	public void testAVectorOfAnotherWidthIsRefused() throws Exception {
		_service._width = 768;
		try {
			_embedder.embedText("x", Embedder.TextKind.QUERY);
			fail("Not 256 wide.");
		} catch (IOException ex) {
			assertFalse(ex instanceof EmbedderUnavailableException);
			assertTrue(ex.getMessage(), ex.getMessage().contains("768"));
		}
	}

	public void testAVectorNotOfLengthOneIsRefused() throws Exception {
		_service._normalised = false;
		try {
			_embedder.embedText("x", Embedder.TextKind.QUERY);
			fail("Not normalised.");
		} catch (IOException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("length"));
		}
	}
}
