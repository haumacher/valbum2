/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

/**
 * How much of the API requires a paired device.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public enum AuthMode {

	/** No authentication at all: every request is served, as before issue #28. */
	OFF("off"),

	/** Reads are open, writes (PUT, POST) require a paired device. */
	WRITES("writes"),

	/** Every request requires a paired device, reads included. */
	ALL("all");

	private final String _protocolName;

	AuthMode(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The name this mode is given on the command line and in {@link
	 * de.haumacher.imageServer.shared.model.AuthInfo#getMode()}.
	 */
	public String protocolName() {
		return _protocolName;
	}

	/**
	 * The {@link AuthMode} with the given {@link #protocolName()}.
	 *
	 * @throws IllegalArgumentException
	 *         If no mode has that name.
	 */
	public static AuthMode parse(String name) {
		for (AuthMode mode : values()) {
			if (mode.protocolName().equals(name)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("Unknown authentication mode '" + name + "', expected one of: off, writes, all");
	}
}
