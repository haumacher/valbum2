/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.coded;

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
 * The one place that tells them apart by name. Each has a display rendition (no browser and no
 * app decoder can be expected to show the original), is turned by its container or codestream and
 * not by the EXIF it carries, and is described without decoding a pixel, so a server that cannot
 * decode one still lists it.
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

	/** Whether the given file is a {@link CodedPicture} by its name, the extension in any case. */
	public static boolean handles(File file) {
		return handles(file.getName());
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
	 * Reads the structure of the given picture, no pixel.
	 *
	 * @throws IOException
	 *         If the file is not what its name says or is coded in a way this server cannot read;
	 *         the message says which.
	 */
	public static CodedPicture read(File file) throws IOException {
		if (isJxl(file.getName())) {
			return JxlFile.read(file);
		}
		return HeifFile.read(file);
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

	private static String extension(String name) {
		String suffix = Util.suffix(name);
		return suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
	}
}
