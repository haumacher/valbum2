/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.coded;

import com.drew.metadata.Metadata;
import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * A photograph in a format that ImageIO does not read and that the server decodes itself: a
 * HEIC/HEIF (issue #186) or AVIF picture ({@link de.haumacher.imageServer.heif.HeifFile}) or a
 * JPEG XL picture ({@link de.haumacher.imageServer.jxl.JxlFile}, issue #193), see
 * {@link CodedPictures}.
 *
 * <p>
 * The structure of the file, read without decoding a pixel, and the two ways its pixels are asked
 * for: a region of the <em>raw raster</em> — the picture as coded, before the orientation its
 * container or codestream declares, which is the frame a face box is stored in (issue #142) — and
 * the whole picture upright as a JPEG, the display rendition of issue #186.
 * </p>
 */
public interface CodedPicture {

	/** The width of the raw raster, see the class comment. */
	int getRawWidth();

	/** The height of the raw raster, see the class comment. */
	int getRawHeight();

	/**
	 * How the raw raster is turned for display: what the container or codestream says, never an
	 * EXIF orientation the metadata may repeat.
	 */
	Orientation getOrientation();

	/** The width of the picture as shown, the orientation applied. */
	default int getDisplayWidth() {
		return getOrientation().ordinal() >= 4 ? getRawHeight() : getRawWidth();
	}

	/** The height of the picture as shown, the orientation applied. */
	default int getDisplayHeight() {
		return getOrientation().ordinal() >= 4 ? getRawWidth() : getRawHeight();
	}

	/**
	 * What the EXIF and XMP metadata of the picture say, as metadata-extractor reads them out of a
	 * JPEG: the same directories, so the date, the camera, the position and the named faces of
	 * issue #129 are read by the very code that reads them out of a JPEG. An orientation tag in
	 * them is not this picture's orientation, see {@link #getOrientation()}.
	 */
	Metadata metadata();

	/**
	 * Decodes a region of the raw raster, scaled to the given size.
	 *
	 * @return A raster of exactly the given size, in the raw raster's own orientation, opaque: what
	 *         the picture leaves transparent is
	 *         {@link de.haumacher.imageServer.PreviewCache#TRANSPARENT_BACKGROUND}.
	 */
	BufferedImage decodeRaw(File file, int left, int top, int width, int height, int outWidth, int outHeight)
			throws IOException;

	/** Decodes the whole raw raster at the given size, see {@link #decodeRaw(File, int, int, int, int, int, int)}. */
	default BufferedImage decodeRaw(File file, int outWidth, int outHeight) throws IOException {
		return decodeRaw(file, 0, 0, getRawWidth(), getRawHeight(), outWidth, outHeight);
	}

	/**
	 * Writes the picture upright, at the given size (the orientation applied), as a JPEG.
	 */
	void writeUprightJpeg(File file, int outWidth, int outHeight, File target) throws IOException;
}
