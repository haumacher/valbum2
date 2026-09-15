/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

/**
 * Whether this server hosts one space or several, see issue #82.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public enum SpaceMode {

	/**
	 * The base folder is the one space: today's addresses, today's <code>.valbum/users.json</code>.
	 */
	SINGLE("single"),

	/**
	 * Every folder directly below the base folder carrying a {@value SpaceStore#FILE_NAME} is a
	 * space, addressed by its folder name as the first path segment.
	 */
	MULTI("multi");

	private final String _protocolName;

	SpaceMode(String protocolName) {
		_protocolName = protocolName;
	}

	/** The name this mode is spelled with on the command line and in the start-up report. */
	public String protocolName() {
		return _protocolName;
	}

	/** The mode of the given name, or <code>null</code> for "decide from the folder tree". */
	public static SpaceMode parse(String name) {
		if (name == null || name.isEmpty() || "auto".equals(name)) {
			return null;
		}
		for (SpaceMode mode : values()) {
			if (mode.protocolName().equals(name)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("Unknown space mode '" + name + "'; use single, multi or auto.");
	}
}
