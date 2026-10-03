/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import junit.framework.TestCase;

/**
 * Review probe of #226: two spaces of one server, each with its own inbox name, never mistake the
 * other's folder for their inbox — the registry is static, the spaces are not.
 */
@SuppressWarnings("javadoc")
public class TestInboxRegistryProbe extends TestCase {

	public void testTwoSpacesKeepTheirOwnInbox() throws Exception {
		Path base = Files.createTempDirectory("valbum-inboxes");
		Path a = Files.createDirectories(base.resolve("a"));
		Path b = Files.createDirectories(base.resolve("b"));
		Inboxes.register(a, "Inbox");
		Inboxes.register(b, "Box");
		try {
			assertTrue(Inboxes.isInbox(a.resolve("Inbox").toFile()));
			assertFalse("B's folder named like A's inbox is an album", Inboxes.isInbox(b.resolve("Inbox").toFile()));
			assertTrue(Inboxes.isInbox(b.resolve("Box").toFile()));
			assertFalse(Inboxes.isInbox(a.resolve("Box").toFile()));
			assertEquals(new File(b.resolve("Box").toString()).getAbsolutePath(), Inboxes.inboxOf(b).getAbsolutePath());

			Inboxes.unregister(a);
			assertFalse("A gone, B unaffected", Inboxes.isInbox(a.resolve("Inbox").toFile()));
			assertTrue(Inboxes.isInbox(b.resolve("Box").toFile()));
		} finally {
			Inboxes.unregister(a);
			Inboxes.unregister(b);
		}
	}
}
