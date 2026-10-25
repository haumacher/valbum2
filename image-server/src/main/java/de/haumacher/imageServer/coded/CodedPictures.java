/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.coded;

import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.heif.HeifDecoder;
import de.haumacher.imageServer.heif.HeifFile;
import de.haumacher.imageServer.jxl.JxlFile;
import de.haumacher.util.servlet.Util;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * The photographs the server decodes itself, see {@link CodedPicture}: HEIC/HEIF (issue #186),
 * AVIF and JPEG XL (issue #193).
 *
 * <p>
 * The one place that tells them apart. Each has a display rendition (no browser and no app decoder
 * can be expected to show the original), is turned by its container or codestream and not by the
 * EXIF it carries, and is described without decoding a pixel, so a server that cannot decode one
 * still lists it.
 * </p>
 *
 * <p>
 * A file on disk is one by its first bytes, see {@link PictureFormat} and issue #312: a JPEG named
 * <code>.heic</code> is a JPEG, a HEIC named <code>.jpg</code> a HEIC. The name decides only where
 * the bytes say nothing recognisable &mdash; and where a request knows nothing but a name, as the
 * app does when it asks for a display rendition.
 * </p>
 */
public final class CodedPictures {

	/** The file extension of an AVIF picture, lower case. */
	public static final String AVIF = "avif";

	/** The file extension of a JPEG XL picture, lower case. */
	public static final String JXL = "jxl";

	/** The extensions of every {@link CodedPicture}, lower case. */
	public static final Set<String> EXTENSIONS = Set.of("heic", "heif", AVIF, JXL);

	private CodedPictures() {
		// Static utility.
	}

	/**
	 * Whether the given file is a {@link CodedPicture}: by its first bytes, by its name (the
	 * extension in any case) where they say nothing recognisable, see {@link PictureFormat}.
	 *
	 * <p>
	 * A video is decided by its name alone and is never one.
	 * </p>
	 */
	public static boolean handles(File file) {
		String name = file.getName();
		if (PreviewCache.isVideoName(name)) {
			return false;
		}
		PictureFormat format = PictureFormat.of(file);
		return format == null ? handles(name) : format.isCoded();
	}

	/** Whether the given file name is that of a {@link CodedPicture}, the extension in any case. */
	public static boolean handles(String name) {
		return EXTENSIONS.contains(extension(name));
	}

	/** Whether the given name is that of a JPEG XL picture. */
	public static boolean isJxl(String name) {
		return JXL.equals(extension(name));
	}

	/** Whether the given name is that of an AVIF picture. */
	public static boolean isAvif(String name) {
		return AVIF.equals(extension(name));
	}

	/**
	 * Reads the structure of the given picture, no pixel: by the reader its first bytes ask for, by
	 * the reader of its name where they say nothing recognisable.
	 *
	 * @throws IOException
	 *         If the file is no {@link CodedPicture}, or is coded in a way this server cannot read;
	 *         the message names the file and says which, and where the bytes are no picture format
	 *         at all, what they begin with.
	 */
	public static CodedPicture read(File file) throws IOException {
		PictureFormat format = PictureFormat.of(file);
		if (format == PictureFormat.JXL) {
			return JxlFile.read(file);
		}
		if (format == PictureFormat.HEIF || format == PictureFormat.AVIF) {
			return HeifFile.read(file);
		}
		String name = file.getName();
		if (format != null) {
			throw new IOException(
				"'" + name + "' is a " + format.label() + " file, no HEIC/HEIF, AVIF or JPEG XL picture.");
		}
		try {
			return isJxl(name) ? JxlFile.read(file) : HeifFile.read(file);
		} catch (IOException ex) {
			throw new IOException(unrecognised(file), ex);
		}
	}

	/**
	 * Why the given file, whose bytes are no format {@link PictureFormat} knows, cannot be read as
	 * the {@link CodedPicture} its name says: what it is not, and what it begins with.
	 */
	static String unrecognised(File file) {
		String name = file.getName();
		String expected = isJxl(name) ? "JPEG XL" : isAvif(name) ? "AVIF" : "HEIF";
		String head = PictureFormat.describeHead(file);
		return "'" + name + "' is neither " + expected + " nor any other picture format: "
			+ (head.isEmpty() ? "it is empty." : "it starts with " + head + ".");
	}

	/**
	 * Why the given picture cannot be decoded on this server, <code>null</code> where it can (or
	 * where it is no {@link CodedPicture} at all): by its first bytes, by its name where they say
	 * nothing recognisable.
	 */
	public static String unavailability(File file) {
		PictureFormat format = PictureFormat.of(file);
		if (format == null) {
			return unavailability(file.getName());
		}
		switch (format) {
			case AVIF:
				return HeifDecoder.av1Unavailability();
			case HEIF:
				return HeifDecoder.unavailability();
			default:
				return null;
		}
	}

	/**
	 * Why a picture of the given name cannot be decoded on this server, <code>null</code> where it
	 * can (or where the name is no {@link CodedPicture} at all).
	 */
	public static String unavailability(String name) {
		if (isJxl(name)) {
			return null;
		}
		if (isAvif(name)) {
			return HeifDecoder.av1Unavailability();
		}
		if (HeifFile.isHeif(name)) {
			return HeifDecoder.unavailability();
		}
		return null;
	}

	/**
	 * The content type of the original of the given name, <code>null</code> for a name that is no
	 * {@link CodedPicture}: not every container's MIME table knows them.
	 */
	public static String contentType(String name) {
		switch (extension(name)) {
			case "heic":
				return "image/heic";
			case "heif":
				return "image/heif";
			case AVIF:
				return "image/avif";
			case JXL:
				return "image/jxl";
			default:
				return null;
		}
	}

	/**
	 * The content type of the given original picture by its first bytes, by its name where they say
	 * nothing recognisable; <code>null</code> where neither says a picture format other than a
	 * raw's, whose type its name says.
	 *
	 * <p>
	 * A HEIF keeps the type its name says where that is <code>.heic</code> or <code>.heif</code>.
	 * </p>
	 */
	public static String contentType(File file) {
		String name = file.getName();
		PictureFormat format = PictureFormat.of(file);
		if (format == null) {
			return contentType(name);
		}
		if (format == PictureFormat.HEIF) {
			String named = contentType(name);
			return "image/heif".equals(named) ? named : format.contentType();
		}
		return format.contentType();
	}

	private static String extension(String name) {
		String suffix = Util.suffix(name);
		return suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
	}
}
