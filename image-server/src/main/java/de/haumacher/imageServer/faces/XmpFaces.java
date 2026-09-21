/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import com.adobe.internal.xmp.XMPException;
import com.adobe.internal.xmp.XMPMeta;
import com.adobe.internal.xmp.XMPMetaFactory;
import com.drew.metadata.Metadata;
import com.drew.metadata.xmp.XmpDirectory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * The named faces an older tool wrote into a photograph, see issue #129.
 *
 * <h2>What is read</h2>
 *
 * <p>
 * Picasa, digiKam, Lightroom and others name the people in a picture in the XMP of the file
 * itself, in the form the Metadata Working Group defined: a <code>mwg-rs:Regions</code> structure
 * holding a <code>mwg-rs:RegionList</code>, each entry with a <code>mwg-rs:Type</code>, a
 * <code>mwg-rs:Name</code> and a <code>mwg-rs:Area</code> of <code>stArea:x</code>,
 * <code>stArea:y</code>, <code>stArea:w</code> and <code>stArea:h</code>. Only an entry of type
 * <code>Face</code> that carries a name is read here: an unnamed region is a detection, and
 * detections are the detector's business (issue #124), while a <code>Pet</code>, a
 * <code>Focus</code> or a <code>BarCode</code> region is about something else entirely.
 * </p>
 *
 * <h2>The frame</h2>
 *
 * <p>
 * An MWG area is <b>centre-based</b> and normalised: <code>x</code> and <code>y</code> are the
 * middle of the box, not its corner, so the corner is <code>x - w/2, y - h/2</code>. It is written
 * in the raster of the image <em>as it is stored in the file</em> &mdash; before the EXIF
 * orientation is applied &mdash; which is exactly the frame a {@link
 * de.haumacher.imageServer.shared.model.FaceTag} is stored in, see {@link Faces}. So the converted
 * box is stored as it stands and nothing is turned: a region of a file whose pixels lie on their
 * side lands on the same face the detector finds there.
 * </p>
 *
 * <p>
 * <code>mwg-rs:AppliedToDimensions</code> says which raster that was, and it is checked against
 * the file's own raw width and height: a copy that was resized or turned by a tool that copied the
 * XMP along carries regions of a picture that no longer exists, and a box in the wrong frame names
 * the wrong person. Such a list is <b>dropped whole</b>, with one line in the log &mdash; a region
 * is never scaled into the new raster, because the mismatch says that the two rasters have nothing
 * to do with each other.
 * </p>
 *
 * <h2>Failure</h2>
 *
 * <p>
 * Nothing here ever throws: a file without XMP, a malformed packet, a structure of another shape
 * and a coordinate that is no number are all "this file names nobody", said once in the log. The
 * import runs while an album is being read, and an album must not fail to open because a tool
 * wrote something odd into one of its pictures.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class XmpFaces {

	private static final Logger LOG = Logger.getLogger(XmpFaces.class.getName());

	/** The Metadata Working Group's region schema. */
	public static final String MWG_RS = "http://www.metadataworkinggroup.com/schemas/regions/";

	/** Adobe's area type, the shape of a <code>mwg-rs:Area</code>. */
	public static final String ST_AREA = "http://ns.adobe.com/xmp/sType/Area#";

	/** Adobe's dimensions type, the shape of <code>mwg-rs:AppliedToDimensions</code>. */
	public static final String ST_DIM = "http://ns.adobe.com/xap/1.0/sType/Dimensions#";

	/** The only region type this server has a use for. */
	public static final String FACE = "Face";

	/** The unit an area must be in, where it names one at all. */
	private static final String NORMALIZED = "normalized";

	/** The unit the applied-to dimensions must be in, where they name one at all. */
	private static final String PIXEL = "pixel";

	private static final String RS = prefix(MWG_RS, "mwg-rs");

	private static final String AREA = prefix(ST_AREA, "stArea");

	private static final String DIM = prefix(ST_DIM, "stDim");

	private XmpFaces() {
		// Static utility.
	}

	/** One named face of the file, its box already converted to the corner form of a tag. */
	public static final class Region {

		private final String _name;

		private final double _x;

		private final double _y;

		private final double _w;

		private final double _h;

		Region(String name, double x, double y, double w, double h) {
			_name = name;
			_x = x;
			_y = y;
			_w = w;
			_h = h;
		}

		/** Who this is, as the file spells it. */
		public String getName() {
			return _name;
		}

		/** The left edge, as a fraction of the raw width. */
		public double getX() {
			return _x;
		}

		/** The top edge, as a fraction of the raw height. */
		public double getY() {
			return _y;
		}

		/** The width, as a fraction of the raw width. */
		public double getW() {
			return _w;
		}

		/** The height, as a fraction of the raw height. */
		public double getH() {
			return _h;
		}

		@Override
		public String toString() {
			return _name + "[" + _x + ", " + _y + ", " + _w + ", " + _h + "]";
		}
	}

	/**
	 * The named faces the XMP of the given file states.
	 *
	 * @param metadata
	 *        What was read out of the file, see {@link
	 *        de.haumacher.imageServer.cache.ImageData#analyze(
	 *        de.haumacher.imageServer.shared.model.AlbumInfo, java.io.File)}.
	 * @param rawWidth
	 *        The width of the file's own raster, before the EXIF orientation.
	 * @param rawHeight
	 *        The height of the file's own raster, before the EXIF orientation.
	 * @param file
	 *        The name of the file, for the log alone.
	 * @return The regions, in the order the file lists them; never <code>null</code> and empty
	 *         where the file names nobody or says something this server cannot use.
	 */
	public static List<Region> read(Metadata metadata, int rawWidth, int rawHeight, String file) {
		if (metadata == null) {
			return Collections.emptyList();
		}
		for (XmpDirectory directory : metadata.getDirectoriesOfType(XmpDirectory.class)) {
			XMPMeta meta = directory.getXMPMeta();
			if (meta == null) {
				continue;
			}
			try {
				List<Region> result = read(meta, rawWidth, rawHeight, file);
				if (!result.isEmpty()) {
					return result;
				}
			} catch (XMPException | RuntimeException ex) {
				LOG.warning("Cannot read the face regions of '" + file + "', ignoring them: "
					+ ex.getMessage());
				return Collections.emptyList();
			}
		}
		return Collections.emptyList();
	}

	/** The named faces of one XMP packet, see {@link #read(Metadata, int, int, String)}. */
	private static List<Region> read(XMPMeta meta, int rawWidth, int rawHeight, String file)
			throws XMPException {
		String regions = "Regions";
		if (!meta.doesPropertyExist(MWG_RS, regions)) {
			return Collections.emptyList();
		}
		String list = regions + "/" + RS + "RegionList";
		int count = meta.countArrayItems(MWG_RS, list);
		if (count == 0) {
			return Collections.emptyList();
		}
		if (!appliesTo(meta, regions, rawWidth, rawHeight, file)) {
			return Collections.emptyList();
		}

		List<Region> result = new ArrayList<>();
		for (int n = 1; n <= count; n++) {
			String item = list + "[" + n + "]";
			String type = string(meta, MWG_RS, item + "/" + RS + "Type");
			if (type == null || !FACE.equalsIgnoreCase(type.trim())) {
				// A pet, the focus point or a bar code: not a person.
				continue;
			}
			String name = string(meta, MWG_RS, item + "/" + RS + "Name");
			if (name == null || name.trim().isEmpty()) {
				// An unnamed region is a detection, and this is not the detector.
				continue;
			}
			String area = item + "/" + RS + "Area/" + AREA;
			String unit = string(meta, MWG_RS, area + "unit");
			if (unit != null && !unit.trim().isEmpty() && !NORMALIZED.equalsIgnoreCase(unit.trim())) {
				LOG.warning("The face region '" + name.trim() + "' of '" + file
					+ "' is measured in '" + unit.trim() + "' and not in '" + NORMALIZED
					+ "', ignoring it.");
				continue;
			}
			Double cx = number(meta, area + "x", file);
			Double cy = number(meta, area + "y", file);
			Double w = number(meta, area + "w", file);
			Double h = number(meta, area + "h", file);
			if (cx == null || cy == null || w == null || h == null) {
				continue;
			}
			Region region = box(name.trim(), cx.doubleValue(), cy.doubleValue(),
				w.doubleValue(), h.doubleValue(), file);
			if (region != null) {
				result.add(region);
			}
		}
		return result;
	}

	/**
	 * Whether the regions were written for the raster this file has.
	 *
	 * <p>
	 * Dimensions the file does not carry are taken as the file's own: a tool that wrote regions
	 * without saying which picture they belong to wrote them for this one, and refusing them would
	 * throw away the only thing it said. A stated size that is not this one is refused, see the
	 * class comment.
	 * </p>
	 */
	private static boolean appliesTo(XMPMeta meta, String regions, int rawWidth, int rawHeight,
			String file) throws XMPException {
		String dimensions = regions + "/" + RS + "AppliedToDimensions/" + DIM;
		Double w = number(meta, dimensions + "w", file);
		Double h = number(meta, dimensions + "h", file);
		if (w == null || h == null) {
			return true;
		}
		String unit = string(meta, MWG_RS, dimensions + "unit");
		if (unit != null && !unit.trim().isEmpty() && !PIXEL.equalsIgnoreCase(unit.trim())) {
			LOG.warning("The face regions of '" + file + "' are applied to dimensions measured in '"
				+ unit.trim() + "' and not in '" + PIXEL + "', ignoring them.");
			return false;
		}
		if (Math.rint(w.doubleValue()) != rawWidth || Math.rint(h.doubleValue()) != rawHeight) {
			LOG.warning("The face regions of '" + file + "' were written for a picture of "
				+ format(w.doubleValue()) + "x" + format(h.doubleValue()) + " pixels and this one is "
				+ rawWidth + "x" + rawHeight + ", ignoring them.");
			return false;
		}
		return true;
	}

	/**
	 * The corner form of a centre-based area, <code>null</code> when it is no box.
	 *
	 * <p>
	 * A box that reaches out of the picture is cut off at its edge &mdash; a tool that measured a
	 * face running off the side wrote exactly that &mdash; and a box of no width or height, or one
	 * that lies outside the picture altogether, is no face and is dropped.
	 * </p>
	 */
	private static Region box(String name, double cx, double cy, double w, double h, String file) {
		if (!(w > 0) || !(h > 0)) {
			LOG.warning("The face region '" + name + "' of '" + file + "' has no extent, ignoring it.");
			return null;
		}
		double left = Math.max(0.0, cx - w / 2);
		double top = Math.max(0.0, cy - h / 2);
		double right = Math.min(1.0, cx + w / 2);
		double bottom = Math.min(1.0, cy + h / 2);
		if (right <= left || bottom <= top) {
			LOG.warning("The face region '" + name + "' of '" + file
				+ "' lies outside the picture, ignoring it.");
			return null;
		}
		return new Region(name, left, top, right - left, bottom - top);
	}

	/** The value of the given path, <code>null</code> when the file does not carry it. */
	private static String string(XMPMeta meta, String namespace, String path) throws XMPException {
		return meta.getPropertyString(namespace, path);
	}

	/** The number the given path states, <code>null</code> when it states none. */
	private static Double number(XMPMeta meta, String path, String file) throws XMPException {
		String value = meta.getPropertyString(MWG_RS, path);
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			double result = Double.parseDouble(value.trim());
			if (Double.isNaN(result) || Double.isInfinite(result)) {
				return null;
			}
			return Double.valueOf(result);
		} catch (NumberFormatException ex) {
			LOG.warning("The face regions of '" + file + "' state '" + value.trim()
				+ "', which is no number, ignoring it.");
			return null;
		}
	}

	/** A stated pixel count as it is put into a log line. */
	private static String format(double value) {
		return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
	}

	/** The prefix the given namespace is registered under, so that a path can be spelled. */
	private static String prefix(String namespace, String suggestion) {
		try {
			return XMPMetaFactory.getSchemaRegistry().registerNamespace(namespace, suggestion);
		} catch (XMPException ex) {
			LOG.warning("Cannot register the namespace '" + namespace + "': " + ex.getMessage());
			return suggestion + ":";
		}
	}
}
