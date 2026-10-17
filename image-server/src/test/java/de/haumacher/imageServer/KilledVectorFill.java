/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.content.ContentVectors;
import de.haumacher.imageServer.index.SpaceIndex;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A process that feeds the vector index of a space and is killed while it does, for
 * {@link TestContentVectors#testAKilledFillIsResumed()}.
 *
 * <p>
 * Arguments: the root of the space, the album fed and committed first, and the albums it is killed
 * while feeding.
 * </p>
 */
public final class KilledVectorFill {

	/** The exit code of the kill. */
	public static final int KILLED = 3;

	private KilledVectorFill() {
		// Run as a process of its own.
	}

	@SuppressWarnings("javadoc")
	public static void main(String[] args) throws Exception {
		Path root = Paths.get(args[0]);
		SpaceIndex store = SpaceIndex.open(root);
		ContentVectors vectors = new ContentVectors(root, store, null);
		if (!vectors.refresh(new File(args[1]))) {
			System.out.println("Nothing fed of " + args[1]);
		}
		store.vectors().flush();
		System.out.println("Fed " + args[1]);
		Thread rest = new Thread(() -> {
			for (int n = 2; n < args.length; n++) {
				vectors.refresh(new File(args[n]));
			}
		});
		rest.start();
		rest.join();
		// No close, no commit, no shutdown hook: killed before what was fed last reached the disk.
		Runtime.getRuntime().halt(KILLED);
	}
}
