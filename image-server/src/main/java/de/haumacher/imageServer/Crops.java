/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.Crop;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.shared.util.Orientations;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The crop of a photograph, see issue #212 and {@link ImagePart#getCrop()}.
 *
 * <h2>Two frames</h2>
 *
 * <p>
 * A crop is <em>stored</em> in the frame the picture is shown in: the file upright and the
 * {@link ImagePart#getOrientation() orientation} stored beside it applied, which is what the editor
 * draws. A rendition of the server &mdash; a <code>?type=tn</code>, a preview, a share card &mdash;
 * is upright by the file alone; the app turns it by the stored orientation on the way to the
 * screen. The region a rendition is cut to is therefore the stored crop taken back through that
 * orientation, {@link #toRendition(Orientation, Crop)}, and a tile that turns the cut rendition
 * shows exactly the stored region.
 * </p>
 *
 * <p>
 * The stored orientation is the <em>app's</em> transform (<code>image_transform.dart</code>,
 * <code>ImageTransform.orientationTransform</code>), which turns {@link Orientation#ROT_L} a quarter
 * counter-clockwise &mdash; the opposite of what the same constant means as an EXIF code, see
 * {@link de.haumacher.imageServer.faces.Faces}. The server never turned a picture by the stored
 * orientation before; the table below is the app's, pinned for both toolchains by the shared
 * fixture <code>image-server/src/test/fixtures/crop-orientations.json</code>.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class Crops {

	/** The smallest side a crop may have, as a fraction of the picture's side. */
	public static final double MIN_SIDE = 0.01;

	/** How far a crop may reach over the picture and still lie on it: a rounding error. */
	public static final double TOLERANCE = 1e-4;

	/** The decimals a region is spelled with on the wire and in a cache name. */
	private static final String FORMAT = "%.4f,%.4f,%.4f,%.4f";

	private Crops() {
		// Static utility.
	}

	/**
	 * Whether the given crop is one a photograph may store: on the picture, with a positive width
	 * and height of at least {@link #MIN_SIDE}.
	 */
	public static boolean isValid(Crop crop) {
		return crop != null && isValid(crop.getX(), crop.getY(), crop.getW(), crop.getH());
	}

	/** Whether the given box is a valid crop, see {@link #isValid(Crop)}. */
	public static boolean isValid(double x, double y, double w, double h) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(w) || !Double.isFinite(h)) {
			return false;
		}
		return x >= -TOLERANCE && y >= -TOLERANCE && w >= MIN_SIDE && h >= MIN_SIDE
			&& x + w <= 1 + TOLERANCE && y + h <= 1 + TOLERANCE;
	}

	/** Whether the given crop shows the whole picture, which is stored as no crop at all. */
	public static boolean isWhole(Crop crop) {
		return crop == null || (Math.abs(crop.getX()) <= TOLERANCE && Math.abs(crop.getY()) <= TOLERANCE
			&& Math.abs(crop.getW() - 1) <= TOLERANCE && Math.abs(crop.getH() - 1) <= TOLERANCE);
	}

	/** The given crop clamped onto the picture, as a fresh object. */
	public static Crop clamped(Crop crop) {
		double x = clamp(crop.getX(), 0, 1 - MIN_SIDE);
		double y = clamp(crop.getY(), 0, 1 - MIN_SIDE);
		double w = clamp(crop.getW(), MIN_SIDE, 1 - x);
		double h = clamp(crop.getH(), MIN_SIDE, 1 - y);
		return Crop.create().setX(x).setY(y).setW(w).setH(h);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	/**
	 * The region of the rendition the given part's stored crop names, <code>null</code> for the
	 * whole picture, see {@link #toRendition(Orientation, Crop)}.
	 */
	public static double[] renditionRegion(ImagePart image) {
		Crop crop = image.getCrop();
		if (crop == null || isWhole(crop)) {
			return null;
		}
		return toRendition(image.getOrientation(), crop);
	}

	/**
	 * The given crop of the shown picture as a box <code>{x, y, w, h}</code> of the server's
	 * rendition, which the given stored orientation turns into the shown picture.
	 */
	public static double[] toRendition(Orientation orientation, Crop crop) {
		double[] one = renditionOf(orientation, crop.getX(), crop.getY());
		double[] two = renditionOf(orientation, crop.getX() + crop.getW(), crop.getY() + crop.getH());
		return box(one, two);
	}

	/** The inverse of {@link #toRendition(Orientation, Crop)}. */
	public static Crop toShown(Orientation orientation, double[] region) {
		double[] one = shownOf(orientation, region[0], region[1]);
		double[] two = shownOf(orientation, region[0] + region[2], region[1] + region[3]);
		double[] box = box(one, two);
		return Crop.create().setX(box[0]).setY(box[1]).setW(box[2]).setH(box[3]);
	}

	private static double[] box(double[] one, double[] two) {
		return new double[] { Math.min(one[0], two[0]), Math.min(one[1], two[1]), Math.abs(two[0] - one[0]),
			Math.abs(two[1] - one[1]) };
	}

	/** Where the given point of the rendition is shown, the app's transform normalised. */
	private static double[] shownOf(Orientation orientation, double x, double y) {
		switch (orientation) {
			case FLIP_H:       return new double[] { 1 - x, y };
			case ROT_180:      return new double[] { 1 - x, 1 - y };
			case FLIP_V:       return new double[] { x, 1 - y };
			case ROT_L_FLIP_V: return new double[] { y, x };
			case ROT_L:        return new double[] { y, 1 - x };
			case ROT_L_FLIP_H: return new double[] { 1 - y, 1 - x };
			case ROT_R:        return new double[] { 1 - y, x };
			case IDENTITY:
			default:           return new double[] { x, y };
		}
	}

	/** Where the given shown point lies in the rendition, the inverse of {@link #shownOf}. */
	private static double[] renditionOf(Orientation orientation, double u, double v) {
		switch (orientation) {
			case FLIP_H:       return new double[] { 1 - u, v };
			case ROT_180:      return new double[] { 1 - u, 1 - v };
			case FLIP_V:       return new double[] { u, 1 - v };
			case ROT_L_FLIP_V: return new double[] { v, u };
			case ROT_L:        return new double[] { 1 - v, u };
			case ROT_L_FLIP_H: return new double[] { 1 - v, 1 - u };
			case ROT_R:        return new double[] { v, 1 - u };
			case IDENTITY:
			default:           return new double[] { u, v };
		}
	}

	/**
	 * The region spelled as the <code>crop</code> parameter of a <code>?type=tn</code> spells it:
	 * <code>x,y,w,h</code> with four decimals each.
	 */
	public static String token(double[] region) {
		return String.format(Locale.ROOT, FORMAT, region[0], region[1], region[2], region[3]);
	}

	/**
	 * The region a <code>crop</code> parameter names, rounded to the four decimals it is cached
	 * under, <code>null</code> where it names no valid region.
	 */
	public static double[] parse(String token) {
		if (token == null) {
			return null;
		}
		String[] parts = token.split(",");
		if (parts.length != 4) {
			return null;
		}
		double[] result = new double[4];
		try {
			for (int n = 0; n < 4; n++) {
				result[n] = round(Double.parseDouble(parts[n].trim()));
			}
		} catch (NumberFormatException ex) {
			return null;
		}
		if (!isValid(result[0], result[1], result[2], result[3])) {
			return null;
		}
		return result;
	}

	private static double round(double value) {
		return Math.round(value * 10000) / 10000.0;
	}

	/** Whether the two regions are the same at the four decimals a region is spelled with. */
	public static boolean same(double[] one, double[] two) {
		if (one == null || two == null) {
			return one == two;
		}
		for (int n = 0; n < 4; n++) {
			if (Math.abs(round(one[n]) - round(two[n])) > TOLERANCE / 2) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Twelve hex digits of the SHA-256 of the region's {@link #token(double[]) spelling}: what the
	 * name of a cut preview carries, so that another crop is another file and an old one is simply
	 * never asked for again (the way issue #141 names a face crop).
	 */
	public static String key(double[] region) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(token(region).getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder();
			for (int n = 0; n < 6; n++) {
				result.append(String.format("%02x", digest[n] & 0xff));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * The given cover with the region of its photograph's crop, see {@link ThumbnailInfo#getCrop()}:
	 * a copy where the photograph is cropped, the cover itself where it is not.
	 *
	 * @param album
	 *        The sidecar of the album the photograph lies in.
	 * @param cover
	 *        A cover whose {@link ThumbnailInfo#getImage() image} may be a path; its last segment
	 *        names the photograph in the given album.
	 */
	public static ThumbnailInfo withRegion(de.haumacher.imageServer.shared.model.AlbumInfo album,
			ThumbnailInfo cover) {
		if (album == null || cover == null) {
			return cover;
		}
		String image = cover.getImage();
		String name = image.substring(image.lastIndexOf('/') + 1);
		ImagePart part = findImage(album, name);
		double[] region = part == null ? null : renditionRegion(part);
		if (region == null) {
			if (cover.getCrop() == null) {
				return cover;
			}
			return copy(cover).setCrop(null);
		}
		return copy(cover).setCrop(Crop.create().setX(region[0]).setY(region[1]).setW(region[2]).setH(region[3]));
	}

	/** A copy of the given cover. */
	public static ThumbnailInfo copy(ThumbnailInfo cover) {
		return ThumbnailInfo.create()
			.setImage(cover.getImage())
			.setScale(cover.getScale())
			.setTx(cover.getTx())
			.setTy(cover.getTy())
			.setOrientation(cover.getOrientation())
			.setCrop(cover.getCrop());
	}

	/** The part of the given name in the given album, a group member included. */
	public static ImagePart findImage(de.haumacher.imageServer.shared.model.AlbumInfo album, String name) {
		for (de.haumacher.imageServer.shared.model.AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				if (((ImagePart) part).getName().equals(name)) {
					return (ImagePart) part;
				}
			} else if (part instanceof de.haumacher.imageServer.shared.model.ImageGroup) {
				for (ImagePart member : ((de.haumacher.imageServer.shared.model.ImageGroup) part).getImages()) {
					if (member.getName().equals(name)) {
						return member;
					}
				}
			}
		}
		return null;
	}

	/**
	 * The width and height the given part is shown at, its crop applied: what the album lays its
	 * tile out at and what the album picture is measured on.
	 */
	public static double[] shownSize(ImagePart image) {
		Orientation orientation = image.getOrientation();
		double width = Orientations.width(orientation, image.getWidth(), image.getHeight());
		double height = Orientations.height(orientation, image.getWidth(), image.getHeight());
		Crop crop = image.getCrop();
		if (crop != null && !isWhole(crop)) {
			width *= crop.getW();
			height *= crop.getH();
		}
		return new double[] { width, height };
	}
}
