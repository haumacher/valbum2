/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * How sharp and how well exposed a picture is, for the best shot on top of a burst, see issue #257
 * and {@link de.haumacher.imageServer.shared.model.PhotoStack}.
 *
 * <p>
 * Measured on the <b>preview</b> (never the original), by the content step right where it embeds
 * the picture, and kept beside the picture's vector in the album's {@link ContentCache}: cheap
 * (a few milliseconds for a preview of 600 pixels), so a library described before the measure
 * existed has it added without asking the embedder.
 * </p>
 *
 * <p>
 * The measure is the variance of the Laplacian of the luminance (a blurred or shaken picture has
 * flat edges, so its Laplacian varies little), times the share of pixels that are neither black
 * nor white (a picture burnt out or drowned in shadow loses what it shows there). Plain Java on the
 * decoded preview: OpenCV would compute the same, and a picture of this size needs no native code.
 * The number has no unit and is compared only within one burst, whose previews are of one size.
 * </p>
 */
public final class Quality {

	private static final Logger LOG = Logger.getLogger(Quality.class.getName());

	/** The quality of a picture that cannot be measured. */
	public static final float UNMEASURABLE = -1f;

	/** The luminance at or below which a pixel counts as black. */
	private static final int DARK = 8;

	/** The luminance at or above which a pixel counts as white. */
	private static final int BRIGHT = 247;

	private Quality() {
		// Static only.
	}

	/**
	 * The quality of the picture in the given file (a preview), see {@link Quality};
	 * {@link #UNMEASURABLE} where it cannot be read.
	 */
	public static float measure(File preview) {
		BufferedImage image;
		try {
			image = ImageIO.read(preview);
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.FINE, "Cannot measure '" + preview + "': " + ex.getMessage(), ex);
			return UNMEASURABLE;
		}
		if (image == null) {
			return UNMEASURABLE;
		}
		return measure(image);
	}

	/** The quality of the given picture, see {@link Quality}. */
	public static float measure(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		if (width < 3 || height < 3) {
			return UNMEASURABLE;
		}
		int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);
		int[] luma = new int[rgb.length];
		long exposed = 0;
		for (int n = 0; n < rgb.length; n++) {
			int pixel = rgb[n];
			int value = (299 * ((pixel >> 16) & 0xFF) + 587 * ((pixel >> 8) & 0xFF) + 114 * (pixel & 0xFF)) / 1000;
			luma[n] = value;
			if (value > DARK && value < BRIGHT) {
				exposed++;
			}
		}
		double sum = 0;
		double squares = 0;
		long count = 0;
		for (int y = 1; y < height - 1; y++) {
			int row = y * width;
			for (int x = 1; x < width - 1; x++) {
				int at = row + x;
				int laplacian = luma[at - 1] + luma[at + 1] + luma[at - width] + luma[at + width] - 4 * luma[at];
				sum += laplacian;
				squares += (double) laplacian * laplacian;
				count++;
			}
		}
		double mean = sum / count;
		double variance = squares / count - mean * mean;
		return (float) (Math.max(0, variance) * exposed / rgb.length);
	}
}
