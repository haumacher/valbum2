/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.function.Predicate;

/**
 * The register keeping {@link SignInHolder}s and their {@link SignIns}, see issue #233: the
 * contacts ({@link ContactStore}) and the users ({@link UserStore}).
 *
 * <p>
 * Every change of a holder's ways goes through {@link #change(SignInHolder, Predicate)}, under the
 * register's lock and written before it returns; the operations below are that one move.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface SignInRegister {

	/**
	 * Changes the ways of the given holder, while this register still holds them, and writes the
	 * register where the change answers that it changed something.
	 *
	 * @return What the change answered; <code>false</code> for a holder this register does not hold
	 *         (any more).
	 */
	boolean change(SignInHolder holder, Predicate<SignIns> change) throws IOException;

	/** The holder of the passkey of the given credential id, <code>null</code> if nobody here holds it. */
	SignInHolder byPasskey(String id);

	/**
	 * Adds a passkey to the given holder.
	 *
	 * @return Whether it is the holder's now; <code>false</code> where somebody else of this
	 *         register holds a passkey of that id, or the holder is gone.
	 */
	default boolean addPasskey(SignInHolder holder, SignIns.Passkey passkey) throws IOException {
		boolean[] added = { false };
		change(holder, signIns -> {
			SignInHolder other = byPasskey(passkey.getId());
			if (other != null) {
				added[0] = other.getSignInKey().equals(holder.getSignInKey());
				return false;
			}
			added[0] = true;
			return signIns.addPasskey(passkey);
		});
		return added[0];
	}

	/** Writes down that the given passkey signed in; answers whether the holder still has it. */
	default boolean usedPasskey(SignInHolder holder, String id, long counter, boolean backupState, Instant now)
			throws IOException {
		return change(holder, signIns -> signIns.usedPasskey(id, counter, backupState, now));
	}

	/** Removes a passkey of the given holder; answers whether there was one of that id. */
	default boolean removePasskey(SignInHolder holder, String id) throws IOException {
		return change(holder, signIns -> signIns.removePasskey(id));
	}
}
