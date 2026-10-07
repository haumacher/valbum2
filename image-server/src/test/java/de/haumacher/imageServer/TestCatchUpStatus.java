/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.CatchUpStatus;
import de.haumacher.imageServer.shared.model.CatchUpStep;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.StringWriter;

/**
 * Test case for <code>?type=catch-up</code>, the progress of the background work of issue #236:
 * the administrator's question, and nobody else's.
 */
@SuppressWarnings("javadoc")
public class TestCatchUpStatus extends ShareTestCase {

	public void testTheAdministratorIsAnswered() throws Exception {
		servlet().index().indexNow();
		FakeResponse response = get("/", ImageServlet.CATCH_UP_TYPE, SharingFixture.ALICE);
		assertEquals(response.body(), 200, response.status());
		CatchUpStatus status = CatchUpStatus.readCatchUpStatus(reader(response.body()));
		assertEquals("Nothing runs in a test that started no background.", CatchUpStep.IDLE, status.getStep());
		assertEquals("", status.getFolder());
		assertEquals(0, status.getVideosRemaining());
		assertTrue("Every album the synchronous walk saw is done: " + body(response),
			status.getAlbumsTotal() > 0 && status.getAlbumsDone() == status.getAlbumsTotal());
	}

	public void testAMemberIsRefused() throws Exception {
		for (String token : new String[] { SharingFixture.CAROL, SharingFixture.DAVE }) {
			FakeResponse response = get("/", ImageServlet.CATCH_UP_TYPE, token);
			assertEquals(403, response.status());
			assertEquals(ImageServlet.CATCH_UP_REFUSED, errorMessage(response));
		}
	}

	public void testALinkAndNobodyAreRefused() throws Exception {
		String link = zooToken();
		FakeResponse linked = get("/", ImageServlet.CATCH_UP_TYPE, link);
		assertEquals("A link has nothing to sign in as.", 403, linked.status());

		FakeResponse anonymous = get("/", ImageServlet.CATCH_UP_TYPE, null);
		assertEquals("Nobody is asked to sign in.", 401, anonymous.status());
		assertFalse(errorMessage(anonymous).isEmpty());
	}

	public void testTheStatusReadsBackWhatWasWritten() throws Exception {
		CatchUpStatus status = CatchUpStatus.create()
			.setAlbumsDone(12)
			.setAlbumsTotal(4711)
			.setStep(CatchUpStep.FACES)
			.setFolder("2024/2024-05-01 Zoo")
			.setVideosRemaining(3)
			.setFailure("previews: broken.jpg: Cannot create image preview for 'broken.jpg'.")
			.setFailureFolder("2023/Broken")
			.setYielding(true);
		String json = write(status);
		CatchUpStatus read = CatchUpStatus.readCatchUpStatus(reader(json));
		assertEquals(json, write(read));
		assertEquals(4711, read.getAlbumsTotal());
		assertEquals(CatchUpStep.FACES, read.getStep());
		assertTrue(read.isYielding());

		CatchUpStatus empty = CatchUpStatus.readCatchUpStatus(reader("{}"));
		assertEquals("A client reads what it does not know as idle.", CatchUpStep.IDLE, empty.getStep());
		assertEquals(write(empty), write(CatchUpStatus.readCatchUpStatus(reader(write(empty)))));
	}

	private static String write(CatchUpStatus status) throws Exception {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			status.writeTo(json);
		}
		return buffer.toString();
	}
}
