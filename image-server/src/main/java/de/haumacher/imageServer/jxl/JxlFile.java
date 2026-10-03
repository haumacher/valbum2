/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.jxl;

import com.drew.lang.ByteArrayReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.xmp.XmpReader;
import com.traneptora.jxlatte.JXLCodestreamDecoder;
import com.traneptora.jxlatte.JXLImage;
import com.traneptora.jxlatte.JXLOptions;
import com.traneptora.jxlatte.bundle.ImageHeader;
import com.traneptora.jxlatte.color.ColorFlags;
import com.traneptora.jxlatte.color.ColorManagement;
import com.traneptora.jxlatte.frame.Frame;
import com.traneptora.jxlatte.frame.FrameFlags;
import com.traneptora.jxlatte.frame.FrameHeader;
import com.traneptora.jxlatte.io.Bitreader;
import com.traneptora.jxlatte.io.Demuxer;
import com.traneptora.jxlatte.io.Loggers;
import com.traneptora.jxlatte.io.PushbackInputStream;
import com.traneptora.jxlatte.util.Dimension;
import com.traneptora.jxlatte.util.ImageBuffer;
import de.haumacher.imageServer.PictureReader;
import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.coded.CodedPicture;
import de.haumacher.imageServer.heif.HeifDecoder;
import de.haumacher.imageServer.heif.HeifFile;
import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.brotli.dec.BrotliInputStream;

/**
 * A JPEG XL picture (<code>.jxl</code>), see issue #193.
 *
 * <p>
 * <b>The decoder.</b> The pixels are decoded by JXLatte, a JPEG XL decoder in pure Java (MIT,
 * vendored as the module <code>jxlatte</code>), because no decoder the server bundles reads JPEG
 * XL — neither ImageIO, nor TwelveMonkeys, nor the FFmpeg 5.1 or the OpenCV 4.6 of the JavaCPP
 * presets — and a JNI binding of libjxl would add a native library to every package. JXLatte
 * decodes every still picture of the format (VarDCT and Modular, lossy and lossless, a JPEG
 * recompressed losslessly) to the same pixels as libjxl's <code>djxl</code> within one level; of an
 * animation it decodes the first frame, which is what a preview shows.
 * </p>
 *
 * <p>
 * <b>The memory.</b> JXLatte decodes the whole picture at once, as floating point or integer
 * planes, with no subsampling and no region: measured as the smallest <code>-Xmx</code> a decode
 * of a 12&nbsp;MP picture passes in (serial collector, 8&nbsp;MB young generation), a VarDCT
 * (lossy, or a recompressed JPEG) picture needs 25&ndash;39 bytes a pixel and a Modular (lossless,
 * or lossy Modular) one 106&ndash;108; an alpha channel, which is always Modular, adds 35. A decode
 * therefore reserves {@link #VARDCT_BYTES_PER_PIXEL} or {@link #MODULAR_BYTES_PER_PIXEL}, plus
 * {@link #ALPHA_BYTES_PER_PIXEL} with alpha, times its pixels from the decode budget of
 * {@link PictureReader} (issue #207) — so a 12&nbsp;MP lossless picture needs a server with about
 * 3&nbsp;GB of heap and is refused, with that sentence, on a smaller one — and takes 2&nbsp;s for
 * 12&nbsp;MP on a desktop processor, on one thread. What it answers is sampled down to the size
 * asked for while it is read out of the planes, so nothing larger than the result is made beside
 * them.
 * </p>
 *
 * <p>
 * <b>The raw raster</b> is the picture as coded, before the orientation of its codestream header
 * (the eight values of the EXIF orientation, which a JPEG XL decoder applies and an EXIF
 * orientation in its <code>Exif</code> box never overrides); {@link #getOrientation()} says it.
 * </p>
 *
 * <p>
 * <b>The metadata</b> are the <code>Exif</code> box (the offset field and the TIFF structure, as a
 * HEIF Exif item), the <code>xml </code> box (XMP), and either of them Brotli-compressed in a
 * <code>brob</code> box, which is how <code>cjxl</code> keeps the metadata of a JPEG it
 * recompresses. A bare codestream carries none.
 * </p>
 */
public final class JxlFile implements CodedPicture {

	private static final Logger LOG = Logger.getLogger(JxlFile.class.getName());

	/**
	 * What decoding a VarDCT picture holds per pixel, measured 25&ndash;39 bytes, see the class
	 * comment.
	 */
	static final int VARDCT_BYTES_PER_PIXEL = 48;

	/** What decoding a Modular picture holds per pixel, measured 106&ndash;108 bytes, see the class comment. */
	static final int MODULAR_BYTES_PER_PIXEL = 128;

	/** What an alpha channel adds per pixel, measured 35 bytes, see the class comment. */
	static final int ALPHA_BYTES_PER_PIXEL = 40;

	private static final byte[] CONTAINER_SIGNATURE =
		{ 0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A };

	private final int _rawWidth;

	private final int _rawHeight;

	private final Orientation _orientation;

	private final boolean _modular;

	private final boolean _alpha;

	private final byte[] _exif;

	private final byte[] _xmp;

	private JxlFile(int rawWidth, int rawHeight, Orientation orientation, boolean modular, boolean alpha,
			byte[] exif, byte[] xmp) {
		_rawWidth = rawWidth;
		_rawHeight = rawHeight;
		_orientation = orientation;
		_modular = modular;
		_alpha = alpha;
		_exif = exif;
		_xmp = xmp;
	}

	/**
	 * Reads the structure of the given JPEG XL file: its boxes and its codestream's header, no
	 * pixel.
	 *
	 * @throws IOException
	 *         If the file is no JPEG XL file.
	 */
	public static JxlFile read(File file) throws IOException {
		byte[][] metadata = boxes(file);
		ImageHeader header;
		boolean modular;
		try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
			Demuxer demuxer = new Demuxer(in);
			demuxer.reset();
			Bitreader reader = new Bitreader(new PushbackInputStream(demuxer));
			// Populates the level, as the decoder does.
			reader.showBits(16);
			JXLOptions options = options();
			Loggers loggers = new Loggers(options, silent());
			header = ImageHeader.read(loggers, reader, demuxer.getLevel());
			if (header.getPreviewSize() != null) {
				JXLOptions previewOptions = new JXLOptions(options);
				previewOptions.parseOnly = true;
				Frame preview = new Frame(reader, header, loggers, previewOptions);
				preview.readFrameHeader();
				preview.readTOC();
				preview.skipFrameData();
			}
			FrameHeader first = new Frame(reader, header, loggers, options).readFrameHeader();
			modular = first.encoding == FrameFlags.MODULAR;
		} catch (IOException | RuntimeException ex) {
			throw new IOException("'" + file.getName() + "' is no readable JPEG XL file: " + message(ex), ex);
		}
		Dimension size = header.getSize();
		if (size.width <= 0 || size.height <= 0) {
			throw new IOException("'" + file.getName() + "' has no picture.");
		}
		return new JxlFile(size.width, size.height, orientation(header.getOrientation()), modular,
			header.hasAlpha(), metadata[0], metadata[1]);
	}

	private static String message(Throwable ex) {
		return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
	}

	private static JXLOptions options() {
		JXLOptions options = new JXLOptions();
		// Nothing written to the server's standard error.
		options.verbosity = -1;
		return options;
	}

	private static PrintWriter silent() {
		return new PrintWriter(Writer.nullWriter());
	}

	/** The orientation of the given EXIF code, which is how JPEG XL counts it. */
	static Orientation orientation(int code) {
		Orientation[] values = Orientation.values();
		return code >= 1 && code <= values.length ? values[code - 1] : Orientation.IDENTITY;
	}

	/**
	 * The <code>Exif</code> (its TIFF structure) and <code>xml </code> boxes of the given file,
	 * Brotli-compressed or not; nothing for a bare codestream.
	 */
	private static byte[][] boxes(File file) throws IOException {
		byte[] exif = null;
		byte[] xmp = null;
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			long length = in.length();
			byte[] signature = new byte[CONTAINER_SIGNATURE.length];
			if (length < signature.length) {
				throw new IOException("'" + file.getName() + "' is no JPEG XL file.");
			}
			in.readFully(signature);
			if (signature[0] == (byte) 0xFF && signature[1] == 0x0A) {
				return new byte[2][];
			}
			if (!java.util.Arrays.equals(signature, CONTAINER_SIGNATURE)) {
				throw new IOException("'" + file.getName() + "' is no JPEG XL file.");
			}
			long position = signature.length;
			while (position + 8 <= length) {
				in.seek(position);
				long size = in.readInt() & 0xFFFFFFFFL;
				String type = fourCC(in.readInt());
				long header = 8;
				if (size == 1) {
					size = in.readLong();
					header = 16;
				} else if (size == 0) {
					size = length - position;
				}
				if (size < header || position + size > length) {
					throw new IOException("'" + file.getName() + "' is no readable JPEG XL file: the box '" + type
						+ "' is cut off.");
				}
				long body = size - header;
				String content = type;
				boolean compressed = false;
				if ("brob".equals(type) && body >= 4) {
					content = fourCC(in.readInt());
					body -= 4;
					compressed = true;
				}
				boolean wanted = ("Exif".equals(content) && exif == null) || ("xml ".equals(content) && xmp == null);
				if (wanted && body <= Integer.MAX_VALUE) {
					byte[] data = new byte[(int) body];
					in.readFully(data);
					try {
						if (compressed) {
							data = brotli(data);
						}
						if ("Exif".equals(content)) {
							exif = HeifFile.exifTiff(data);
						} else {
							xmp = data;
						}
					} catch (IOException ex) {
						LOG.log(Level.INFO, "Ignoring the unreadable '" + content + "' box of '" + file.getName() + "'.",
							ex);
					}
				}
				position += size;
			}
		}
		return new byte[][] { exif, xmp };
	}

	private static byte[] brotli(byte[] data) throws IOException {
		try (InputStream in = new BrotliInputStream(new ByteArrayInputStream(data))) {
			return in.readAllBytes();
		}
	}

	private static String fourCC(int value) {
		return new String(new byte[] { (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value },
			StandardCharsets.ISO_8859_1);
	}

	@Override
	public int getRawWidth() {
		return _rawWidth;
	}

	@Override
	public int getRawHeight() {
		return _rawHeight;
	}

	/** The orientation of the codestream header, see the class comment. */
	@Override
	public Orientation getOrientation() {
		return _orientation;
	}

	/** Whether the first frame is Modular coded (lossless as a rule), not VarDCT. */
	public boolean isModular() {
		return _modular;
	}

	/** Whether the picture has an alpha channel. */
	public boolean hasAlpha() {
		return _alpha;
	}

	/** The TIFF structure of the Exif box, <code>null</code> without one. */
	public byte[] getExif() {
		return _exif;
	}

	/** The XMP packet, <code>null</code> without one. */
	public byte[] getXmp() {
		return _xmp;
	}

	@Override
	public Metadata metadata() {
		Metadata metadata = new Metadata();
		if (_exif != null) {
			new ExifReader().extract(new ByteArrayReader(_exif), metadata, 0);
		}
		if (_xmp != null) {
			new XmpReader().extract(_xmp, metadata);
		}
		return metadata;
	}

	/** What a decode of this picture holds in the heap, in bytes, see the class comment. */
	public long decodeBytes() {
		return ((long) _rawWidth) * _rawHeight * ((_modular ? MODULAR_BYTES_PER_PIXEL : VARDCT_BYTES_PER_PIXEL)
			+ (_alpha ? ALPHA_BYTES_PER_PIXEL : 0));
	}

	@Override
	public BufferedImage decodeRaw(File file, int left, int top, int width, int height, int outWidth,
			int outHeight) throws IOException {
		if (left < 0 || top < 0 || width <= 0 || height <= 0 || left + width > _rawWidth
			|| top + height > _rawHeight) {
			throw new IllegalArgumentException("The region " + left + "," + top + " " + width + "x" + height
				+ " is not inside the picture of " + _rawWidth + "x" + _rawHeight + ".");
		}
		try (PictureReader.Reservation reservation = reserve(file)) {
			JXLImage image = decode(file);
			// The raw raster's pixel (x, y) is the decoded picture's pixel at its centre turned upright.
			AffineTransform rawToShown = PreviewCache.orientationTransform(_orientation, _rawWidth, _rawHeight);
			return sample(image, rawToShown, left, top, width, height, outWidth, outHeight);
		} catch (OutOfMemoryError ex) {
			throw PictureReader.outOfMemory(file.getName(), _rawWidth, _rawHeight, ex);
		}
	}

	@Override
	public void writeUprightJpeg(File file, int outWidth, int outHeight, File target) throws IOException {
		BufferedImage upright;
		try (PictureReader.Reservation reservation = reserve(file)) {
			JXLImage image = decode(file);
			upright = sample(image, new AffineTransform(), 0, 0, getDisplayWidth(), getDisplayHeight(), outWidth,
				outHeight);
		} catch (OutOfMemoryError ex) {
			throw PictureReader.outOfMemory(file.getName(), _rawWidth, _rawHeight, ex);
		}
		HeifDecoder.writeJpeg(upright, target);
	}

	private PictureReader.Reservation reserve(File file) throws IOException {
		return PictureReader.reserve(file.getName(), _modular ? "a lossless JPEG XL picture" : "a JPEG XL picture",
			_rawWidth, _rawHeight, decodeBytes());
	}

	/** The first frame of the picture, upright, in sRGB where its colours are not an ICC profile's. */
	private static JXLImage decode(File file) throws IOException {
		JXLImage image;
		try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
			Demuxer demuxer = new Demuxer(in);
			demuxer.reset();
			image = new JXLCodestreamDecoder(new PushbackInputStream(demuxer), options(), demuxer).decode(silent());
		} catch (IOException | RuntimeException ex) {
			throw new IOException("Cannot decode the JPEG XL picture '" + file.getName() + "': " + message(ex), ex);
		}
		if (image == null) {
			throw new IOException("Cannot decode the JPEG XL picture '" + file.getName() + "': no frame.");
		}
		if (!image.hasICCProfile()) {
			image = image.transform(ColorManagement.PRI_SRGB, ColorManagement.WP_D65, ColorFlags.TF_SRGB,
				JXLOptions.PEAK_DETECT_AUTO);
		}
		return image;
	}

	/**
	 * Samples the given rectangle of a frame of the picture down to the given size, every output
	 * pixel the mean of the pixels it covers, transparent pixels shown on
	 * {@link PreviewCache#TRANSPARENT_BACKGROUND}.
	 *
	 * @param frameToShown
	 *        Maps the frame the rectangle is given in onto the decoded (upright) picture.
	 */
	static BufferedImage sample(JXLImage image, AffineTransform frameToShown, int left, int top, int width,
			int height, int outWidth, int outHeight) {
		ImageBuffer[] buffers = image.getBuffer(false);
		int colors = image.getColorChannelCount();
		int alphaChannel = image.hasAlpha() ? colors + image.getAlphaIndex() : -1;
		boolean premultiplied = alphaChannel >= 0 && image.isAlphaPremultiplied();
		int channels = alphaChannel >= 0 ? 4 : 3;
		Channel[] read = new Channel[channels];
		for (int c = 0; c < 3; c++) {
			int source = colors == 1 ? 0 : c;
			read[c] = new Channel(buffers[source], image.getTaggedBitDepth(source));
		}
		if (alphaChannel >= 0) {
			read[3] = new Channel(buffers[alphaChannel], image.getTaggedBitDepth(alphaChannel));
		}
		int shownWidth = image.getWidth();
		int shownHeight = image.getHeight();
		double[] m = new double[6];
		frameToShown.getMatrix(m);

		int background = PreviewCache.TRANSPARENT_BACKGROUND.getRGB();
		double[] ground = { ((background >> 16) & 0xFF) / 255.0, ((background >> 8) & 0xFF) / 255.0,
			(background & 0xFF) / 255.0 };
		BufferedImage result = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_3BYTE_BGR);
		byte[] out = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
		double[] sum = new double[channels];
		for (int oy = 0; oy < outHeight; oy++) {
			int y0 = top + (int) ((long) oy * height / outHeight);
			int y1 = Math.max(y0 + 1, top + (int) ((long) (oy + 1) * height / outHeight));
			for (int ox = 0; ox < outWidth; ox++) {
				int x0 = left + (int) ((long) ox * width / outWidth);
				int x1 = Math.max(x0 + 1, left + (int) ((long) (ox + 1) * width / outWidth));
				java.util.Arrays.fill(sum, 0);
				int count = 0;
				for (int y = y0; y < y1; y++) {
					double cy = y + 0.5;
					for (int x = x0; x < x1; x++) {
						double cx = x + 0.5;
						int u = clamp((int) Math.floor(m[0] * cx + m[2] * cy + m[4]), shownWidth);
						int v = clamp((int) Math.floor(m[1] * cx + m[3] * cy + m[5]), shownHeight);
						double alpha = channels == 4 ? read[3].value(u, v) : 1;
						for (int c = 0; c < 3; c++) {
							double value = read[c].value(u, v);
							double shown = premultiplied ? value + ground[c] * (1 - alpha)
								: value * alpha + ground[c] * (1 - alpha);
							sum[c] += shown;
						}
						count++;
					}
				}
				int at = 3 * (oy * outWidth + ox);
				// BGR.
				out[at] = level(sum[2] / count);
				out[at + 1] = level(sum[1] / count);
				out[at + 2] = level(sum[0] / count);
			}
		}
		return result;
	}

	private static int clamp(int value, int size) {
		return value < 0 ? 0 : value >= size ? size - 1 : value;
	}

	private static byte level(double value) {
		int level = (int) Math.round(value * 255);
		return (byte) (level < 0 ? 0 : level > 255 ? 255 : level);
	}

	/** One plane of the decoded picture, read as 0..1. */
	private static final class Channel {

		private final float[][] _float;

		private final int[][] _int;

		private final double _max;

		Channel(ImageBuffer buffer, int bitDepth) {
			_float = buffer.isFloat() ? buffer.getFloatBuffer() : null;
			_int = buffer.isInt() ? buffer.getIntBuffer() : null;
			_max = (1L << Math.max(1, Math.min(31, bitDepth))) - 1;
		}

		double value(int x, int y) {
			double value = _float != null ? _float[y][x] : _int[y][x] / _max;
			return value < 0 ? 0 : value > 1 ? 1 : value;
		}
	}
}
