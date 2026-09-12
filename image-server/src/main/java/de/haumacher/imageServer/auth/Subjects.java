/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

/**
 * Who a {@link GrantStore.Grant} is made out to, see issue #49.
 *
 * <p>
 * A subject is a single string, so that a grant is one flat record and the store needs no variant
 * types: <code>user:haui</code>, <code>group:family</code>, <code>anonymous</code>, and
 * <code>token:&lt;id&gt;</code> for the share links of issue #51.
 * </p>
 *
 * <p>
 * A <code>token:</code> subject names a share link of issue #51, see {@link ShareStore}. It is the
 * one subject a client never writes: it is recorded and removed together with the link's record, so
 * that the two stores can never disagree about what a link opens. {@link #isKnown(String)} therefore
 * does not accept it — a grant made out by hand to a token nobody issued would promise something to
 * nobody.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Subjects {

	/** The prefix of a subject naming a single user. */
	public static final String USER_PREFIX = "user:";

	/** The prefix of a subject naming a {@link GroupStore.Group}. */
	public static final String GROUP_PREFIX = "group:";

	/** The subject naming everybody, signed in or not. */
	public static final String ANONYMOUS = "anonymous";

	/** The prefix of a subject naming a share-link token, see issue #51. */
	public static final String TOKEN_PREFIX = "token:";

	/** Why a subject is not one this server understands. */
	public static final String SUBJECT_REFUSED =
		"A grant is made out to 'user:<name>', 'group:<name>' or 'anonymous'.";

	/** The subject naming the user of the given name. */
	public static String user(String name) {
		return USER_PREFIX + name;
	}

	/** The subject naming the group of the given name. */
	public static String group(String name) {
		return GROUP_PREFIX + name;
	}

	/**
	 * The subject naming the share link of the given id, see {@link ShareStore}.
	 *
	 * <p>
	 * Such a subject is <em>not</em> {@link #isKnown(String) known}: nobody makes a grant out to a
	 * token by hand. It is created and removed together with the link record, which is what keeps
	 * the two stores from ever disagreeing.
	 * </p>
	 */
	public static String token(String id) {
		return TOKEN_PREFIX + id;
	}

	/** Whether the given subject is one this build can grant anything to. */
	public static boolean isKnown(String subject) {
		if (subject == null) {
			return false;
		}
		return ANONYMOUS.equals(subject) || nameOf(subject, USER_PREFIX) != null
			|| nameOf(subject, GROUP_PREFIX) != null;
	}

	/**
	 * The name in the given subject if it has the given prefix.
	 *
	 * @return <code>null</code> if the subject has another prefix or names nothing at all.
	 */
	public static String nameOf(String subject, String prefix) {
		if (subject == null || !subject.startsWith(prefix) || subject.length() == prefix.length()) {
			return null;
		}
		return subject.substring(prefix.length());
	}
}
