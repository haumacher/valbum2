/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Reading one rectangle out of an original, see issue #140.
 *
 * <p>
 * A face is <em>found</em> on the 600&nbsp;px preview and <em>described</em> from the file itself:
 * a face 40&nbsp;px across on the preview of a 24&nbsp;megapixel photograph is 400&nbsp;px in the
 * file, and both the crop the editor shows and the numbers the recogniser computes are worth what
 * those 400 pixels are worth, not what ten times downscaled pixels are.
 * </p>
 *
 * <p>
 * <b>Never the whole raster.</b> A region decode through
 * {@link ImageReadParam#setSourceRegion(Rectangle)} plus
 * {@link ImageReadParam#setSourceSubsampling(int, int, int, int)} allocates a raster of the piece
 * asked for and of the sampling chosen, so the memory of one face is bounded by the size it is
 * wanted at (a few hundred pixels) whatever the photograph's size — the very rule
 * {@link de.haumacher.imageServer.PreviewCache} already follows for a preview (issue #68). The
 * entropy stream of a JPEG is still parsed to the end of the region, which costs time and no
 * memory.
 * </p>
 *
 * <p>
 * The rectangle is given in the <em>raw</em> raster of the file, before the EXIF orientation:
 * that is the frame every face box is stored in, see {@link Faces}. Turning what was read upright
 * is {@link #upright(BufferedImage, Orientation)}, and it happens afterwards, on the small raster.
 * </p>
 *
 * <p>
 * Nothing here ever writes: an original is read and never touched.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class Originals {

	private static final AtomicLong DECODES = new AtomicLong();

	private Originals() {
		// Static utility.
	}

	/** How many region decodes this process has made; for the tests. */
	public static long decodes() {
		return DECODES.get();
	}

	/** A piece of an original, and where in its raw raster it was cut from. */
	public static final class Region {

		private final BufferedImage _image;

		private final int _left;

		private final int _top;

		private final int _sampling;

		private final int _rawWidth;

		private final int _rawHeight;

		Region(BufferedImage image, int left, int top, int sampling, int rawWidth, int rawHeight) {
			_image = image;
			_left = left;
			_top = top;
			_sampling = sampling;
			_rawWidth = rawWidth;
			_rawHeight = rawHeight;
		}

		/** The pixels that were read. */
		public BufferedImage getImage() {
			return _image;
		}

		/** The left edge of the region in the raw raster of the file, in pixels. */
		public int getLeft() {
			return _left;
		}

		/** The top edge of the region in the raw raster of the file, in pixels. */
		public int getTop() {
			return _top;
		}

		/** Every how many pixels of the file one pixel of {@link #getImage()} was taken. */
		public int getSampling() {
			return _sampling;
		}

		/** The width of the file's own raster, in pixels. */
		public int getRawWidth() {
			return _rawWidth;
		}

		/** The height of the file's own raster, in pixels. */
		public int getRawHeight() {
			return _rawHeight;
		}

		/** Where the given horizontal pixel of {@link #getImage()} lies in the raw raster. */
		public double rawX(double x) {
			return _left + x * _sampling;
		}

		/** Where the given vertical pixel of {@link #getImage()} lies in the raw raster. */
		public double rawY(double y) {
			return _top + y * _sampling;
		}
	}

	/**
	 * Reads the given rectangle of the given file's raw raster, subsampled so that the long side of
	 * the result is at most the given number of pixels.
	 *
	 * @throws IOException
	 *         Where the file cannot be read at all or no reader can decode it — the caller falls
	 *         back to the preview, see {@link FaceIndex}.
	 */
	public static Region decodeLongSide(File file, double left, double top, double right, double bottom,
			int maxLongSide) throws IOException {
		return decode(file, left, top, right, bottom, maxLongSide, BY_LONG_SIDE);
	}

	/**
	 * Reads the given rectangle of the given file's raw raster, subsampled so that the long side of
	 * the result is at least the given number of pixels (and less than twice as much), see issue
	 * #163.
	 *
	 * <p>
	 * The partner of {@link #fitLongSide(BufferedImage, int)}: subsampling only knows whole numbers,
	 * so a region that is to reach the detector at exactly the size the preview reaches it at is
	 * read a little larger and scaled down afterwards — the memory stays bounded by four times the
	 * pixels asked for, never the whole raster.
	 * </p>
	 */
	public static Region decodeLongSideAtLeast(File file, double left, double top, double right, double bottom,
			int minLongSide) throws IOException {
		return decode(file, left, top, right, bottom, minLongSide, AT_LEAST_LONG_SIDE);
	}

	/**
	 * Reads the given rectangle of the given file's raw raster, subsampled so that the short side of
	 * the result is at least the given number of pixels (and less than twice as much).
	 */
	public static Region decodeShortSide(File file, double left, double top, double right, double bottom,
			int minShortSide) throws IOException {
		return decode(file, left, top, right, bottom, minShortSide, BY_SHORT_SIDE);
	}

	private static final int BY_LONG_SIDE = 0;

	private static final int BY_SHORT_SIDE = 1;

	private static final int AT_LEAST_LONG_SIDE = 2;

	private static Region decode(File file, double left, double top, double right, double bottom, int bound,
			int mode) throws IOException {
		try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
			if (in == null) {
				throw new IOException("Cannot open '" + file.getName() + "'.");
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				throw new IOException("No image reader for '" + file.getName() + "'.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				int rawWidth = reader.getWidth(0);
				int rawHeight = reader.getHeight(0);
				int x0 = clamp((int) Math.floor(left), 0, rawWidth - 1);
				int y0 = clamp((int) Math.floor(top), 0, rawHeight - 1);
				int x1 = clamp((int) Math.ceil(right), x0 + 1, rawWidth);
				int y1 = clamp((int) Math.ceil(bottom), y0 + 1, rawHeight);
				int width = x1 - x0;
				int height = y1 - y0;
				int sampling;
				switch (mode) {
					case BY_LONG_SIDE:
						sampling = samplingForLongSide(Math.max(width, height), bound);
						break;
					case AT_LEAST_LONG_SIDE:
						sampling = samplingForShortSide(Math.max(width, height), bound);
						break;
					default:
						sampling = samplingForShortSide(Math.min(width, height), bound);
						break;
				}

				ImageReadParam param = reader.getDefaultReadParam();
				param.setSourceRegion(new Rectangle(x0, y0, width, height));
				if (sampling > 1) {
					param.setSourceSubsampling(sampling, sampling, 0, 0);
				}
				BufferedImage image = reader.read(0, param);
				if (image == null) {
					throw new IOException("Nothing decoded from '" + file.getName() + "'.");
				}
				DECODES.incrementAndGet();
				return new Region(image, x0, y0, sampling, rawWidth, rawHeight);
			} finally {
				reader.dispose();
			}
		}
	}

	/** The smallest sampling that brings the given side to at most the given number of pixels. */
	public static int samplingForLongSide(int side, int max) {
		if (max <= 0 || side <= max) {
			return 1;
		}
		return (side + max - 1) / max;
	}

	/** The largest sampling that keeps the given side at the given number of pixels or above. */
	public static int samplingForShortSide(int side, int min) {
		if (min <= 0) {
			return 1;
		}
		return Math.max(1, side / min);
	}

	/**
	 * The given raster scaled down so that its long side is at most the given number of pixels, the
	 * raster itself where it already is, see issue #163.
	 *
	 * <p>
	 * Bilinear: {@link #decodeLongSideAtLeast} leaves less than a factor of two to take away, which
	 * is what bilinear interpolation does without dropping pixels. The aspect is kept, so a fraction
	 * of the result is the same fraction of the raster that was read.
	 * </p>
	 */
	public static BufferedImage fitLongSide(BufferedImage image, int maxLongSide) {
		int width = image.getWidth();
		int height = image.getHeight();
		int longSide = Math.max(width, height);
		if (maxLongSide <= 0 || longSide <= maxLongSide) {
			return image;
		}
		double scale = ((double) maxLongSide) / longSide;
		int scaledWidth = Math.max(1, (int) Math.round(width * scale));
		int scaledHeight = Math.max(1, (int) Math.round(height * scale));
		BufferedImage result = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D graphics = result.createGraphics();
		try {
			graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(image, 0, 0, scaledWidth, scaledHeight, null);
		} finally {
			graphics.dispose();
		}
		return result;
	}

	/**
	 * The given raster of a file with the given EXIF orientation, turned the way it is shown.
	 *
	 * <p>
	 * Pixel for pixel rather than through an {@link java.awt.geom.AffineTransform}: this is only
	 * ever asked of a face crop of a few hundred pixels or of the centre of issue #163 at the size of
	 * a preview, and a permutation of two axes is exactly
	 * what it is — nothing is interpolated and nothing is off by half a pixel.
	 * </p>
	 */
	public static BufferedImage upright(BufferedImage raw, Orientation exif) {
		if (exif == Orientation.IDENTITY) {
			return raw;
		}
		int rawWidth = raw.getWidth();
		int rawHeight = raw.getHeight();
		boolean swaps = Faces.swaps(exif);
		int width = swaps ? rawHeight : rawWidth;
		int height = swaps ? rawWidth : rawHeight;
		BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int v = 0; v < height; v++) {
			for (int u = 0; u < width; u++) {
				int s;
				int t;
				switch (exif) {
					case FLIP_H:
						s = rawWidth - 1 - u;
						t = v;
						break;
					case ROT_180:
						s = rawWidth - 1 - u;
						t = rawHeight - 1 - v;
						break;
					case FLIP_V:
						s = u;
						t = rawHeight - 1 - v;
						break;
					case ROT_L_FLIP_V:
						s = v;
						t = u;
						break;
					case ROT_L:
						s = v;
						t = rawHeight - 1 - u;
						break;
					case ROT_L_FLIP_H:
						s = rawWidth - 1 - v;
						t = rawHeight - 1 - u;
						break;
					case ROT_R:
						s = rawWidth - 1 - v;
						t = u;
						break;
					default:
						s = u;
						t = v;
						break;
				}
				result.setRGB(u, v, raw.getRGB(s, t));
			}
		}
		return result;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
