/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * The stamp of a file: what tells that it changed since it was read, see {@link ResourceCache}.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class Stamps {

	private Stamps() {
		// Static use only.
	}

	/**
	 * The modification time (at the file system's own resolution) and the size of the given file,
	 * <code>"-"</code> where it does not exist.
	 */
	static String of(File file) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
			return attributes.lastModifiedTime().toInstant().toString() + ":" + attributes.size();
		} catch (IOException ex) {
			return "-";
		}
	}
}
