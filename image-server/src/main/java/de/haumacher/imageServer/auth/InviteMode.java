/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

/**
 * Who may invite somebody onto this server, see issue #52.
 *
 * <p>
 * A family server wants every member to be able to hand a link to a cousin; a server somebody runs
 * for a club wants exactly one person to decide who joins. The flag is the whole difference, and it
 * is a start-up decision, not a per-request one.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public enum InviteMode {

	/** Every member (and the admin) may invite members and guests; the default. */
	MEMBERS("members"),

	/** Only the library owner invites; a member is refused with a message that says so. */
	ADMIN("admin");

	private final String _protocolName;

	InviteMode(String protocolName) {
		_protocolName = protocolName;
	}

	/** The name this mode is given on the command line. */
	public String protocolName() {
		return _protocolName;
	}

	/**
	 * The {@link InviteMode} with the given {@link #protocolName()}.
	 *
	 * @throws IllegalArgumentException
	 *         If no mode has that name.
	 */
	public static InviteMode parse(String name) {
		for (InviteMode mode : values()) {
			if (mode.protocolName().equals(name)) {
				return mode;
			}
		}
		throw new IllegalArgumentException(
			"Unknown invitation mode '" + name + "', expected one of: members, admin");
	}
}
