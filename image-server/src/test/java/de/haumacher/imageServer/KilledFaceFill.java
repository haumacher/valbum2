/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.Recognition;
import de.haumacher.imageServer.index.SpaceIndex;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A process that fills the prototypes of the recognition into the index of a space and is killed
 * while it does, for {@link TestFacePrototypes#testAKilledFillIsResumed()}.
 *
 * <p>
 * Arguments: the root of the space, how long to wait after the first album (so that H2 has written
 * it to disk), the first album, and the albums it is killed while filling.
 * </p>
 */
public final class KilledFaceFill {

	/** The exit code of the kill. */
	public static final int KILLED = 3;

	private KilledFaceFill() {
		// Run as a process of its own.
	}

	@SuppressWarnings("javadoc")
	public static void main(String[] args) throws Exception {
		Path root = Paths.get(args[0]);
		long wait = Long.parseLong(args[1]);
		SpaceIndex store = SpaceIndex.open(root);
		Recognition recognition = new Recognition(root, store);
		recognition.refresh(new File(args[2]));
		System.out.println("Filled " + args[2]);
		Thread.sleep(wait);
		Thread rest = new Thread(() -> {
			for (int n = 3; n < args.length; n++) {
				recognition.refresh(new File(args[n]));
			}
		});
		rest.start();
		// No close, no shutdown hook: killed in the middle of the rest.
		Runtime.getRuntime().halt(KILLED);
	}
}
