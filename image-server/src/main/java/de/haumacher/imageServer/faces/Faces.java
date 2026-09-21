/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.util.Orientations;

/**
 * Which frame a face box is written in, and who is answered one at all, see issue #124.
 *
 * <h2>The frame</h2>
 *
 * <p>
 * A face is found on the <em>preview</em> of {@link PreviewCache}, which is the photograph
 * <em>upright</em>: the preview generator has already applied the EXIF orientation of the file. A
 * box is stored and answered in the <em>raw raster of the file</em> instead — the pixels as they
 * lie in it, before the EXIF orientation and before {@link
 * de.haumacher.imageServer.shared.model.ImagePart#getOrientation()}, both normalised to
 * <code>0..1</code> per axis.
 * </p>
 *
 * <p>
 * Why that frame and not the upright one: it is the only frame nothing can move. Turning a
 * photograph in the application changes {@code ImagePart.orientation} and would otherwise
 * invalidate every box of that image; writing the EXIF of a file changes its upright frame in the
 * same way. The raw raster changes only when the pixels change, and then the content hash changes
 * with it and the entry is gone anyway. A client draws a box by applying to it exactly the
 * transform it applies to the picture — first the file's EXIF, then the album's orientation.
 * </p>
 *
 * <p>
 * The arithmetic is done in normalised coordinates, so a quarter turn is a permutation of the two
 * axes and needs no pixel counts at all. {@link #toRaw(Orientation, double, double, double, double)}
 * maps an upright box to the raw raster and {@link #toUpright(Orientation, double, double, double,
 * double)} maps it back, which is what the face crop of <code>?type=face</code> needs to cut the
 * right piece out of the preview again. For every one of the eight orientations they are exact
 * inverses, and for a quarter turn the two sides of the box swap — a box that is wide in the
 * upright frame is tall in the raw one.
 * </p>
 *
 * <p>
 * The eight EXIF orientations, as the mapping from a raw point <code>(s,t)</code> to the upright
 * point <code>(u,v)</code> it is shown at:
 * </p>
 *
 * <pre>
 * 1 IDENTITY       u = s      v = t
 * 2 FLIP_H         u = 1-s    v = t
 * 3 ROT_180        u = 1-s    v = 1-t
 * 4 FLIP_V         u = s      v = 1-t
 * 5 ROT_L_FLIP_V   u = t      v = s
 * 6 ROT_L          u = 1-t    v = s
 * 7 ROT_L_FLIP_H   u = 1-t    v = 1-s
 * 8 ROT_R          u = t      v = 1-s
 * </pre>
 *
 * <h2>Who is answered a face</h2>
 *
 * <p>
 * Signed-in members, and nobody else — and not even they while they are looking at their own album
 * as somebody else, see {@link #maySee(AuthService, Caller, int)}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class Faces {

	private Faces() {
		// Static utility.
	}

	/** A point in the unit square. */
	private static final class Point {

		final double _x;

		final double _y;

		Point(double x, double y) {
			_x = x;
			_y = y;
		}
	}

	/**
	 * The given upright box in the raw raster of a file with the given EXIF orientation.
	 *
	 * @param exif
	 *        How the file says its pixels are to be turned for display.
	 * @param x
	 *        The left edge in the upright frame, <code>0..1</code>.
	 * @param y
	 *        The top edge in the upright frame, <code>0..1</code>.
	 * @param w
	 *        The width in the upright frame, <code>0..1</code>.
	 * @param h
	 *        The height in the upright frame, <code>0..1</code>.
	 * @return The same box as <code>{x, y, w, h}</code> of the raw raster.
	 */
	public static double[] toRaw(Orientation exif, double x, double y, double w, double h) {
		Point one = rawOf(exif, x, y);
		Point two = rawOf(exif, x + w, y + h);
		return box(one, two);
	}

	/**
	 * The inverse of {@link #toRaw(Orientation, double, double, double, double)}: a box of the raw
	 * raster in the upright frame the preview is drawn in.
	 */
	public static double[] toUpright(Orientation exif, double x, double y, double w, double h) {
		Point one = uprightOf(exif, x, y);
		Point two = uprightOf(exif, x + w, y + h);
		return box(one, two);
	}

	private static double[] box(Point one, Point two) {
		double left = Math.min(one._x, two._x);
		double top = Math.min(one._y, two._y);
		return new double[] { left, top, Math.abs(two._x - one._x), Math.abs(two._y - one._y) };
	}

	/** Where the given upright point lies in the raw raster. */
	private static Point rawOf(Orientation exif, double u, double v) {
		switch (exif) {
			case IDENTITY:       return new Point(u, v);
			case FLIP_H:         return new Point(1 - u, v);
			case ROT_180:        return new Point(1 - u, 1 - v);
			case FLIP_V:         return new Point(u, 1 - v);
			case ROT_L_FLIP_V:   return new Point(v, u);
			case ROT_L:          return new Point(v, 1 - u);
			case ROT_L_FLIP_H:   return new Point(1 - v, 1 - u);
			case ROT_R:          return new Point(1 - v, u);
			default:             return new Point(u, v);
		}
	}

	/** Where the given raw point is shown in the upright frame. */
	private static Point uprightOf(Orientation exif, double s, double t) {
		switch (exif) {
			case IDENTITY:       return new Point(s, t);
			case FLIP_H:         return new Point(1 - s, t);
			case ROT_180:        return new Point(1 - s, 1 - t);
			case FLIP_V:         return new Point(s, 1 - t);
			case ROT_L_FLIP_V:   return new Point(t, s);
			case ROT_L:          return new Point(1 - t, s);
			case ROT_L_FLIP_H:   return new Point(1 - t, 1 - s);
			case ROT_R:          return new Point(t, 1 - s);
			default:             return new Point(s, t);
		}
	}

	/** Whether the given orientation swaps the two sides of a box, see {@link Orientations}. */
	public static boolean swaps(Orientation exif) {
		return Orientations.toCode(exif) >= 5;
	}

	/**
	 * Whether a request is answered the faces of an image at all, see issue #124.
	 *
	 * <p>
	 * Signed-in members only. An anonymous visitor of a space that is open to the public and a
	 * share link are answered none — in the spirit of issue #96, where who contributed a photograph
	 * is bookkeeping among the members of a space, only more so: who is <em>in</em> a photograph is
	 * the most personal thing this server knows. A link never shows a face, whoever made it and
	 * whatever rights it carries.
	 * </p>
	 *
	 * <p>
	 * A server running with <code>--auth off</code> has no members to be one of: there is no
	 * authentication at all, everybody who reaches it is its owner, and it is answered like one.
	 * </p>
	 *
	 * <p>
	 * A member who may only contribute to an inbox sees the faces of their own photographs and of
	 * no others — not by a rule of its own, but because {@link de.haumacher.imageServer.Inboxes}
	 * has already taken everybody else's photographs out of the answer (issue #131).
	 * </p>
	 *
	 * <p>
	 * <b>The preview of issue #46 counts.</b> <code>?viewAs=public</code> is the author asking to be
	 * shown exactly what a public caller is shown, so it must hide the faces too — a preview that
	 * showed something the public never sees would be worth nothing, and the point of the switch is
	 * that one can trust it. It is a cap and never a grant, so it is expressed here as the
	 * clearance the request is capped at: faces need {@link Privacy#MEMBERS}, which is the very
	 * level they are answered at, so <code>?viewAs=members</code> still shows them and a request
	 * without the parameter (capped at {@link Privacy#PRIVATE}) is unaffected.
	 * </p>
	 *
	 * @param viewAs
	 *        What the request asked to be shown as, see {@link Privacy#viewAs(String)} —
	 *        {@link Privacy#PRIVATE} when it asked for nothing.
	 */
	public static boolean maySee(AuthService auth, Caller caller, int viewAs) {
		if (viewAs < Privacy.MEMBERS) {
			// The author looking at their own album as the public sees it: as the public sees it.
			return false;
		}
		if (caller == null) {
			return false;
		}
		if (caller.isShareLink() || caller.isShareGone() || caller.hasInvalidToken()) {
			return false;
		}
		if (caller.isPaired()) {
			return true;
		}
		return auth != null && auth.getMode() == AuthMode.OFF;
	}
}
