/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

/**
 * Somebody who signs in on another browser with an authenticator app or a passkey, see
 * {@link SignIns}: a contact of a personal link or a member (issue #233).
 *
 * <p>
 * Kept by a {@link SignInRegister}, which alone changes its {@link #getSignIns() ways}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface SignInHolder {

	/**
	 * What this holder is named by among every holder of the space, contacts and members alike:
	 * <code>contact:&lt;id&gt;</code>, <code>user:&lt;name&gt;</code>.
	 *
	 * <p>
	 * The key the guesses at its authenticator app are counted under, and what binds a passkey
	 * registration to it &mdash; so a contact and a member never share a lock or a ticket.
	 * </p>
	 */
	String getSignInKey();

	/** The name an authenticator app files the entry under, and a passkey shows. */
	String getSignInName();

	/**
	 * The WebAuthn user handle of this holder's passkeys in the given space: opaque, stable, and
	 * distinct from every other holder's of the relying party, since an authenticator keeps one
	 * passkey per relying party and handle.
	 */
	String passkeyHandle(String space);

	/** Its ways to sign in; read only, see {@link SignInRegister}. */
	SignIns getSignIns();
}
