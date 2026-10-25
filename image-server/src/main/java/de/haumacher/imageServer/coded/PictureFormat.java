/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.coded;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * The format of a picture on disk as its first bytes say it, see issue #312.
 *
 * <p>
 * A picture's format is what its bytes are, not what its name says: a phone or an export tool
 * writes a JPEG under the name <code>.heic</code>, a HEIC under <code>.jpg</code>, a PNG under
 * <code>.jpg</code>. Every place that picks a reader, a decoder or a served content type for a
 * file on disk asks {@link #of(File)}, and falls back to the name only where the bytes say nothing
 * recognisable &mdash; then a file that cannot be read by the reader of its name is refused with
 * what its bytes are not.
 * </p>
 *
 * <p>
 * A raw photograph is {@link #RAW} by its bytes only as far as they go: most raws are TIFF files,
 * so the bytes tell a raw from a JPEG or a HEIC, but not one raw from another or from a plain TIFF.
 * Where the bytes say {@link #RAW} (or nothing), the raw's extension stays decisive, see
 * {@link de.haumacher.imageServer.raw.RawFile#isRaw(File)}.
 * </p>
 *
 * <p>
 * The answer is one read of at most {@link #HEAD} bytes, made anew each time: beside the full
 * decode or metadata read it chooses, it costs nothing worth remembering, and nothing is stored
 * about it.
 * </p>
 */
public enum PictureFormat {

	/** A JPEG: <code>FF D8 FF</code>. */
	JPEG("JPEG", "image/jpeg"),

	/** A PNG: its eight-byte signature. */
	PNG("PNG", "image/png"),

	/** A GIF: <code>GIF87a</code> or <code>GIF89a</code>. */
	GIF("GIF", "image/gif"),

	/** A WebP: <code>RIFF....WEBP</code>. */
	WEBP("WebP", "image/webp"),

	/** A HEIC/HEIF photograph: an ISO media file of a HEIF brand, issue #186. */
	HEIF("HEIF", "image/heic"),

	/** An AVIF picture: an ISO media file of an AVIF brand, issue #193. */
	AVIF("AVIF", "image/avif"),

	/** A JPEG XL picture, its bare codestream or its container, issue #193. */
	JXL("JPEG XL", "image/jxl"),

	/**
	 * A TIFF-based file (a DNG, CR2, NEF, ARW, ORF, RW2 or a plain TIFF), a Canon CR3 or a Fujifilm
	 * RAF: which raw it is, the name says, see issue #191.
	 */
	RAW("raw or TIFF", null);

	/** How many bytes of a file are looked at. */
	public static final int HEAD = 64;

	/** The major or compatible brands of a HEIC/HEIF photograph coded in HEVC. */
	private static final Set<String> HEVC_BRANDS =
		Set.of("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs");

	/** The brands of an AVIF picture. */
	private static final Set<String> AV1_BRANDS = Set.of("avif", "avis");

	/** The brands of the general image file format, which says nothing of the codec. */
	private static final Set<String> GENERAL_BRANDS = Set.of("mif1", "msf1", "mif2", "miaf");

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] JXL_CONTAINER =
		{ 0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', '\r', '\n', (byte) 0x87, '\n' };

	private static final String RAF_MAGIC = "FUJIFILMCCD-RAW";

	private final String _label;

	private final String _contentType;

	PictureFormat(String label, String contentType) {
		_label = label;
		_contentType = contentType;
	}

	/** The name of the format as a sentence says it, e.g. <code>JPEG</code>. */
	public String label() {
		return _label;
	}

	/**
	 * The content type of a file of this format, <code>null</code> for {@link #RAW}, whose type
	 * its name says.
	 */
	public String contentType() {
		return _contentType;
	}

	/** Whether this is a {@link CodedPicture}: decoded by the server itself, see {@link CodedPictures}. */
	public boolean isCoded() {
		return this == HEIF || this == AVIF || this == JXL;
	}

	/**
	 * Whether a browser and the app's codec show a picture of this format as it is: a JPEG, a PNG, a
	 * GIF or a WebP, the formats the viewer is handed as originals.
	 */
	public boolean isViewable() {
		return this == JPEG || this == PNG || this == GIF || this == WEBP;
	}

	/**
	 * The format of the given file by its first bytes, <code>null</code> where they say nothing
	 * recognisable or the file cannot be read.
	 */
	public static PictureFormat of(File file) {
		byte[] head = head(file);
		return head == null ? null : of(head, head.length);
	}

	/** The format of the given first bytes of a file, <code>null</code> where they say nothing recognisable. */
	public static PictureFormat of(byte[] head, int length) {
		if (length >= 3 && u8(head, 0) == 0xFF && u8(head, 1) == 0xD8 && u8(head, 2) == 0xFF) {
			return JPEG;
		}
		if (startsWith(head, length, PNG_SIGNATURE)) {
			return PNG;
		}
		if (length >= 6 && ("GIF87a".equals(ascii(head, 0, 6)) || "GIF89a".equals(ascii(head, 0, 6)))) {
			return GIF;
		}
		if (length >= 12 && "RIFF".equals(ascii(head, 0, 4)) && "WEBP".equals(ascii(head, 8, 4))) {
			return WEBP;
		}
		if (length >= 2 && u8(head, 0) == 0xFF && u8(head, 1) == 0x0A) {
			return JXL;
		}
		if (startsWith(head, length, JXL_CONTAINER)) {
			return JXL;
		}
		if (length >= 12 && "ftyp".equals(ascii(head, 4, 4))) {
			return isoMedia(head, length);
		}
		if (length >= 4) {
			String start = ascii(head, 0, 4);
			if ("II*\0".equals(start) || "MM\0*".equals(start) || "IIRO".equals(start) || "IIRS".equals(start)
				|| "MMOR".equals(start) || "IIU\0".equals(start)) {
				return RAW;
			}
		}
		if (length >= RAF_MAGIC.length() && RAF_MAGIC.equals(ascii(head, 0, RAF_MAGIC.length()))) {
			return RAW;
		}
		return null;
	}

	/**
	 * The format of an ISO media file by its <code>ftyp</code> box: a HEIC, an AVIF, a CR3, or
	 * <code>null</code> for every other brand (a video among them).
	 */
	private static PictureFormat isoMedia(byte[] head, int length) {
		String major = ascii(head, 8, 4);
		if ("crx ".equals(major)) {
			return RAW;
		}
		Set<String> brands = new HashSet<>();
		brands.add(major);
		long size = u32(head, 0);
		int end = (int) Math.min(length, size < 16 ? length : size);
		for (int at = 16; at + 4 <= end; at += 4) {
			brands.add(ascii(head, at, 4));
		}
		if (AV1_BRANDS.contains(major)) {
			return AVIF;
		}
		if (HEVC_BRANDS.contains(major)) {
			return HEIF;
		}
		if (GENERAL_BRANDS.contains(major) || brands.stream().anyMatch(GENERAL_BRANDS::contains)) {
			if (brands.stream().anyMatch(HEVC_BRANDS::contains)) {
				return HEIF;
			}
			if (brands.stream().anyMatch(AV1_BRANDS::contains)) {
				return AVIF;
			}
			// The general format without a codec brand: read as a HEIF, which tells the codec by
			// its items.
			return HEIF;
		}
		return null;
	}

	/**
	 * The first bytes of the given file as a refusal names them, e.g. <code>3F 2A 00 11</code>; the
	 * empty string for an empty or unreadable file.
	 */
	public static String describeHead(File file) {
		byte[] head = head(file);
		if (head == null || head.length == 0) {
			return "";
		}
		StringBuilder result = new StringBuilder();
		for (int n = 0, cnt = Math.min(8, head.length); n < cnt; n++) {
			if (n > 0) {
				result.append(' ');
			}
			result.append(String.format("%02X", u8(head, n)));
		}
		return result.toString();
	}

	/** At most {@link #HEAD} bytes of the beginning of the given file, <code>null</code> where it cannot be read. */
	private static byte[] head(File file) {
		if (!file.isFile()) {
			return null;
		}
		try (InputStream in = new FileInputStream(file)) {
			return in.readNBytes(HEAD);
		} catch (IOException ex) {
			return null;
		}
	}

	private static boolean startsWith(byte[] head, int length, byte[] prefix) {
		if (length < prefix.length) {
			return false;
		}
		for (int n = 0; n < prefix.length; n++) {
			if (head[n] != prefix[n]) {
				return false;
			}
		}
		return true;
	}

	private static int u8(byte[] head, int at) {
		return head[at] & 0xFF;
	}

	private static long u32(byte[] head, int at) {
		return ((long) u8(head, at) << 24) | (u8(head, at + 1) << 16) | (u8(head, at + 2) << 8) | u8(head, at + 3);
	}

	private static String ascii(byte[] head, int at, int length) {
		return new String(head, at, length, StandardCharsets.ISO_8859_1);
	}
}
