/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The time-based one-time password of RFC 6238, as every authenticator app computes it, see issue
 * #208.
 *
 * <p>
 * HMAC-SHA-1 over the number of {@link #PERIOD_SECONDS 30-second} steps since the epoch,
 * dynamically truncated to {@link #DIGITS six} digits (RFC 4226), with a secret of
 * {@link #SECRET_BYTES 160 bits} written in Base32 (RFC 4648) without padding &mdash; the defaults
 * of the <code>otpauth://</code> format, so that an app reading the link or the typed setup key
 * needs to be told nothing else. A code is accepted for the step it was made in and the one on
 * either side ({@link #TOLERANCE}), so that a clock a few seconds off and a code typed at the end of
 * its step still work. Which steps were used already is the caller's business, see
 * {@link TotpSignIns}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class Totp {

	/** How long one code lasts. */
	public static final int PERIOD_SECONDS = 30;

	/** The digits of a code. */
	public static final int DIGITS = 6;

	/** How many steps on either side of the current one are accepted. */
	public static final int TOLERANCE = 1;

	/** The length of a secret: 160 bits, what RFC 4226 recommends for HMAC-SHA-1. */
	public static final int SECRET_BYTES = 20;

	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

	private Totp() {
		// Static utility.
	}

	/** A fresh secret, in Base32. */
	public static String newSecret(SecureRandom random) {
		byte[] bytes = new byte[SECRET_BYTES];
		random.nextBytes(bytes);
		return base32(bytes);
	}

	/** The step the given moment falls into. */
	public static long step(Instant now) {
		return Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
	}

	/** The {@link #DIGITS six-digit} code of the given secret for the given step. */
	public static String code(byte[] key, long step) {
		return code(key, step, DIGITS, "HmacSHA1");
	}

	/**
	 * The code of the given secret for the given step, with the given number of digits and HMAC
	 * algorithm (RFC 6238 also names SHA-256 and SHA-512; an <code>otpauth://</code> link of this
	 * server never does).
	 */
	public static String code(byte[] key, long step, int digits, String algorithm) {
		byte[] hash;
		try {
			Mac mac = Mac.getInstance(algorithm);
			mac.init(new SecretKeySpec(key, algorithm));
			hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("No " + algorithm + " in this Java runtime.", ex);
		}
		int offset = hash[hash.length - 1] & 0x0F;
		int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
			| ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
		int modulus = 1;
		for (int n = 0; n < digits; n++) {
			modulus *= 10;
		}
		StringBuilder result = new StringBuilder(Integer.toString(binary % modulus));
		while (result.length() < digits) {
			result.insert(0, '0');
		}
		return result.toString();
	}

	/**
	 * The step within {@link #TOLERANCE} of the given one whose code the given one is,
	 * <code>-1</code> for none.
	 *
	 * <p>
	 * Blanks and dashes in the typed code are ignored (an app shows <code>123 456</code>). Every
	 * step is compared, in constant time, whatever an earlier one said.
	 * </p>
	 */
	public static long match(String secret, String typed, long now) {
		String given = typed == null ? "" : typed.replaceAll("[\\s-]", "");
		if (given.length() != DIGITS) {
			return -1;
		}
		byte[] key = base32Decode(secret);
		byte[] expected = given.getBytes(StandardCharsets.US_ASCII);
		long found = -1;
		for (long step = now - TOLERANCE; step <= now + TOLERANCE; step++) {
			if (MessageDigest.isEqual(code(key, step).getBytes(StandardCharsets.US_ASCII), expected) && found < 0) {
				found = step;
			}
		}
		return found;
	}

	/** The Base32 spelling (RFC 4648, upper case, no padding) of the given bytes. */
	public static String base32(byte[] bytes) {
		StringBuilder result = new StringBuilder((bytes.length * 8 + 4) / 5);
		int buffer = 0;
		int bits = 0;
		for (byte b : bytes) {
			buffer = (buffer << 8) | (b & 0xFF);
			bits += 8;
			while (bits >= 5) {
				result.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
				bits -= 5;
			}
		}
		if (bits > 0) {
			result.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
		}
		return result.toString();
	}

	/**
	 * The bytes of the given Base32 text; blanks, dashes and padding are ignored, case does not
	 * matter.
	 *
	 * @throws IllegalArgumentException
	 *         For a character that is no Base32.
	 */
	public static byte[] base32Decode(String text) {
		String clean = text.replaceAll("[\\s=-]", "").toUpperCase(Locale.ROOT);
		ByteBuffer result = ByteBuffer.allocate(clean.length() * 5 / 8);
		int buffer = 0;
		int bits = 0;
		for (int n = 0; n < clean.length(); n++) {
			int value = ALPHABET.indexOf(clean.charAt(n));
			if (value < 0) {
				throw new IllegalArgumentException("No Base32: '" + clean.charAt(n) + "'.");
			}
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				result.put((byte) (buffer >> (bits - 8)));
				bits -= 8;
			}
		}
		return result.array();
	}
}
