/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import com.adobe.internal.xmp.XMPException;
import com.adobe.internal.xmp.XMPMeta;
import com.adobe.internal.xmp.XMPMetaFactory;
import com.adobe.internal.xmp.options.PropertyOptions;
import com.adobe.internal.xmp.options.SerializeOptions;
import de.haumacher.imageServer.faces.XmpFaces;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writing the face regions of the Metadata Working Group into a JPEG, for the tests.
 *
 * <p>
 * The counterpart of {@link Exif}: nothing in this server ever writes into an original, so the
 * only way to get a photograph that names people the way Picasa or digiKam name them is to write
 * one here. The regions are built with the very XMP library the server reads them with, and the
 * packet is put into an <code>APP1</code> segment behind the start of the image, which is where a
 * JPEG carries its XMP.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class Xmp {

	/** The marker that says an <code>APP1</code> segment carries XMP and not EXIF. */
	private static final String HEADER = "http://ns.adobe.com/xap/1.0/\u0000";

	/** The fixture of issue #129: a small picture naming two people. */
	static final String FIXTURE = "src/test/fixtures/xmp-faces/two-faces.jpg";

	/** The width of the fixture. */
	static final int FIXTURE_WIDTH = 400;

	/** The height of the fixture. */
	static final int FIXTURE_HEIGHT = 300;

	/** What the fixture says about Alice, as a tag stores it: left, top, width, height. */
	static final double[] ALICE = { 0.20, 0.275, 0.20, 0.25 };

	/** What the fixture says about Bob, as a tag stores it: left, top, width, height. */
	static final double[] BOB = { 0.61, 0.33, 0.18, 0.24 };

	private Xmp() {
		// Static utility.
	}

	/**
	 * Writes {@link #FIXTURE} afresh, from the working directory of the <code>image-server</code>
	 * module.
	 *
	 * <p>
	 * Run by hand when the fixture has to change; the result is checked in, so that a human can
	 * look into it with <code>exiftool</code> and see exactly what an old library hands this
	 * server. See <code>src/test/fixtures/xmp-faces/README.md</code>.
	 * </p>
	 */
	public static void main(String[] args) throws Exception {
		File plain = File.createTempFile("valbum-xmp", ".jpg");
		try {
			java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(FIXTURE_WIDTH,
				FIXTURE_HEIGHT, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
			java.awt.Graphics2D graphics = image.createGraphics();
			graphics.setColor(java.awt.Color.DARK_GRAY);
			graphics.fillRect(0, 0, FIXTURE_WIDTH, FIXTURE_HEIGHT);
			graphics.setColor(java.awt.Color.WHITE);
			draw(graphics, ALICE, "Alice");
			draw(graphics, BOB, "Bob");
			graphics.dispose();
			javax.imageio.ImageIO.write(image, "jpg", plain);

			File target = new File(FIXTURE);
			target.getParentFile().mkdirs();
			writeRegions(plain, target, FIXTURE_WIDTH, FIXTURE_HEIGHT,
				Region.corners("Alice", ALICE[0], ALICE[1], ALICE[2], ALICE[3]),
				Region.corners("Bob", BOB[0], BOB[1], BOB[2], BOB[3]));
			System.out.println("Wrote " + target.getAbsolutePath() + " (" + target.length() + " bytes).");
		} finally {
			plain.delete();
		}
	}

	/** Draws a box where the fixture says a face is, so that the picture shows what it claims. */
	private static void draw(java.awt.Graphics2D graphics, double[] box, String name) {
		int x = (int) Math.round(box[0] * FIXTURE_WIDTH);
		int y = (int) Math.round(box[1] * FIXTURE_HEIGHT);
		int w = (int) Math.round(box[2] * FIXTURE_WIDTH);
		int h = (int) Math.round(box[3] * FIXTURE_HEIGHT);
		graphics.drawOval(x, y, w, h);
		graphics.drawString(name, x, y - 4);
	}

	/** One entry of a <code>mwg-rs:RegionList</code>, in the centre form of the standard. */
	static final class Region {

		final String _name;

		final String _type;

		final double _cx;

		final double _cy;

		final double _w;

		final double _h;

		Region(String name, String type, double cx, double cy, double w, double h) {
			_name = name;
			_type = type;
			_cx = cx;
			_cy = cy;
			_w = w;
			_h = h;
		}

		/** A named face, the ordinary case. */
		static Region face(String name, double cx, double cy, double w, double h) {
			return new Region(name, XmpFaces.FACE, cx, cy, w, h);
		}

		/** A face whose corners are given instead of its centre, as a tag stores them. */
		static Region corners(String name, double x, double y, double w, double h) {
			return face(name, x + w / 2, y + h / 2, w, h);
		}
	}

	/**
	 * Writes the given JPEG with a <code>mwg-rs:Regions</code> structure naming the given regions.
	 *
	 * @param source
	 *        The JPEG to read; its pixels are copied untouched.
	 * @param target
	 *        Where to write it.
	 * @param appliedWidth
	 *        What <code>stDim:w</code> says the regions were measured on.
	 * @param appliedHeight
	 *        What <code>stDim:h</code> says the regions were measured on.
	 */
	static void writeRegions(File source, File target, int appliedWidth, int appliedHeight,
			Region... regions) throws IOException, XMPException {
		writePacket(source, target, packet(appliedWidth, appliedHeight, regions));
	}

	/** Writes the given JPEG with the given text as its XMP packet, however broken it is. */
	static void writePacket(File source, File target, String packet) throws IOException {
		byte[] jpeg = Files.readAllBytes(source.toPath());
		if (jpeg.length < 2 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
			throw new IOException("Not a JPEG: " + source);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		out.write(app1(packet));
		out.write(jpeg, 2, jpeg.length - 2);
		Files.write(target.toPath(), out.toByteArray());
	}

	/** The XMP packet naming the given regions. */
	static String packet(int appliedWidth, int appliedHeight, Region... regions) throws XMPException {
		XMPMeta meta = XMPMetaFactory.create();
		String dimensions = "Regions/" + prefix(XmpFaces.MWG_RS) + "AppliedToDimensions/"
			+ prefix(XmpFaces.ST_DIM);
		if (appliedWidth > 0) {
			meta.setProperty(XmpFaces.MWG_RS, dimensions + "w", Integer.toString(appliedWidth));
			meta.setProperty(XmpFaces.MWG_RS, dimensions + "h", Integer.toString(appliedHeight));
			meta.setProperty(XmpFaces.MWG_RS, dimensions + "unit", "pixel");
		}
		String list = "Regions/" + prefix(XmpFaces.MWG_RS) + "RegionList";
		for (int n = 0; n < regions.length; n++) {
			Region region = regions[n];
			meta.appendArrayItem(XmpFaces.MWG_RS, list, new PropertyOptions().setArray(true), null,
				new PropertyOptions().setStruct(true));
			String item = list + "[" + (n + 1) + "]/";
			if (region._type != null) {
				meta.setProperty(XmpFaces.MWG_RS, item + prefix(XmpFaces.MWG_RS) + "Type", region._type);
			}
			if (region._name != null) {
				meta.setProperty(XmpFaces.MWG_RS, item + prefix(XmpFaces.MWG_RS) + "Name", region._name);
			}
			String area = item + prefix(XmpFaces.MWG_RS) + "Area/" + prefix(XmpFaces.ST_AREA);
			meta.setProperty(XmpFaces.MWG_RS, area + "x", Double.toString(region._cx));
			meta.setProperty(XmpFaces.MWG_RS, area + "y", Double.toString(region._cy));
			meta.setProperty(XmpFaces.MWG_RS, area + "w", Double.toString(region._w));
			meta.setProperty(XmpFaces.MWG_RS, area + "h", Double.toString(region._h));
			meta.setProperty(XmpFaces.MWG_RS, area + "unit", "normalized");
		}
		return XMPMetaFactory.serializeToString(meta, new SerializeOptions().setUseCompactFormat(true));
	}

	/**
	 * The prefix the given namespace is registered under, registering it where it is not.
	 *
	 * <p>
	 * The reader registers the same three namespaces, but its constants are compile-time
	 * constants: naming one of them here does not load that class, so this side says it itself.
	 * </p>
	 */
	private static String prefix(String namespace) throws XMPException {
		String suggestion;
		if (XmpFaces.MWG_RS.equals(namespace)) {
			suggestion = "mwg-rs";
		} else if (XmpFaces.ST_AREA.equals(namespace)) {
			suggestion = "stArea";
		} else {
			suggestion = "stDim";
		}
		return XMPMetaFactory.getSchemaRegistry().registerNamespace(namespace, suggestion);
	}

	/** The <code>APP1</code> segment carrying the given XMP packet. */
	private static byte[] app1(String packet) throws IOException {
		byte[] payload = (HEADER + packet).getBytes(StandardCharsets.UTF_8);
		int length = 2 + payload.length;
		if (length > 0xFFFF) {
			throw new IOException("The XMP packet does not fit into one segment: " + length);
		}
		byte[] result = new byte[2 + length];
		result[0] = (byte) 0xFF;
		result[1] = (byte) 0xE1;
		result[2] = (byte) ((length >> 8) & 0xFF);
		result[3] = (byte) (length & 0xFF);
		System.arraycopy(payload, 0, result, 4, payload.length);
		return result;
	}
}
