/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import junit.framework.TestCase;

/**
 * Test case for {@link PreviewCache}.
 */
@SuppressWarnings("javadoc")
public class TestPreviewCache extends TestCase {
	
	public void testUpdateDate() {
		assertTrue(PreviewCache.lastUpdate() > 0);
	}

}
