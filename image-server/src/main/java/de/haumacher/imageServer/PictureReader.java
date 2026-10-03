/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/**
 * The one way a picture's pixels are read through ImageIO: the preview of {@link PreviewCache} and
 * the region decodes of the face index ({@link de.haumacher.imageServer.faces.Originals}) alike, see
 * issue #207.
 *
 * <p>
 * <b>The canvas.</b> The raw raster of a picture is its whole canvas. For a GIF that is the logical
 * screen and for an animated WebP the canvas of its <code>VP8X</code> chunk, which is what the
 * metadata — and so the listing's width and height — say; the first frame, which is all the reader
 * answers, may be smaller and lie at an offset, and is then composed onto the canvas at that
 * offset, the rest of the canvas (the GIF's background colour and every transparent pixel alike)
 * being {@link PreviewCache#TRANSPARENT_BACKGROUND}, as issue #190 shows transparency. So the preview, the
 * face boxes measured in the raw raster and the listed size describe one picture.
 * </p>
 *
 * <p>
 * <b>The memory.</b> A decode is bounded by the size it is wanted at — subsampling and the source
 * region are honoured while decoding (issue #68) — for every reader the server uses except one: the
 * TwelveMonkeys WebP reader holds a whole raster of the picture in the heap before it subsamples,
 * for a lossless (VP8L) bitstream and for the losslessly compressed alpha of a lossy one, and keeps
 * per-macroblock state of the whole picture for a lossy one. Measured as the smallest
 * <code>-Xmx</code> a preview passes in, with the serial collector and an 8&nbsp;MB young
 * generation (so that the number is the live peak and not the collector's layout), 24&nbsp;MP
 * unless said: a JPEG, PNG or GIF 11&nbsp;MB (the JVM itself, the decode is bounded); a lossy WebP
 * 29&nbsp;MB (0.75 bytes a pixel above the JVM); a lossy one with compressed alpha 106&nbsp;MB
 * (3.96), with uncompressed alpha (6&nbsp;MP) 1 byte a pixel; a lossless one of 6, 12 and
 * 24&nbsp;MP 31, 53 and 91&nbsp;MB (3.0&ndash;3.5: the raster at three bytes a pixel plus the
 * entropy coder's state); a lossless one with alpha and noise at the encoder's slowest method
 * (6&nbsp;MP) 46&nbsp;MB (5.8). Such a decode therefore <em>reserves</em> what it may need —
 * {@link #LOSSY_BYTES_PER_PIXEL}, {@link #LOSSLESS_BYTES_PER_PIXEL} and
 * {@link #ALPHA_BYTES_PER_PIXEL} times the pixels of the frame, see
 * {@link #webpBytesPerPixel(File)} — from a budget beside the preview permits ({@link #budget()}:
 * half the heap, <code>Runtime.maxMemory() / 2</code>, the system property
 * {@value #BUDGET_PROPERTY} in MB overriding it; half, because the collector needs room beside a
 * raster of that size — with the default layout the 24&nbsp;MP lossless picture of 80&nbsp;MB live
 * needed a 119&nbsp;MB heap — and the server itself and the other previews go on meanwhile).
 * Several such pictures at once wait for each other instead of running the heap out, and a picture
 * that needs more than the whole budget is refused at once with a {@link PictureTooLargeException}
 * that says so — never an {@link OutOfMemoryError}, which is caught around every decode and
 * answered the same way should a reservation ever fall short. Every other decode reserves nothing;
 * its memory is bounded by the preview size and spent under the permits.
 * </p>
 *
 * <p>
 * <b>A raw photograph</b> (issue #191) is opened as the largest JPEG preview it carries, read in place
 * out of the raw file ({@link de.haumacher.imageServer.raw.RawFile}): its raw raster is that JPEG's
 * picture as stored, which the raw's own EXIF orientation turns upright.
 * </p>
 *
 * <p>
 * Nothing here ever writes: an original is read and never touched.
 * </p>
 */
public final class PictureReader implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(PictureReader.class.getName());

	/** The system property overriding the decode budget, in megabytes, see {@link #budget()}. */
	public static final String BUDGET_PROPERTY = "valbum.decodeBudget";

	/**
	 * What a lossy (VP8) WebP's decoder holds per pixel of the picture whatever the subsampling:
	 * measured 0.75 bytes, see the class comment.
	 */
	static final int LOSSY_BYTES_PER_PIXEL = 1;

	/**
	 * What a lossless (VP8L) bitstream's decoder holds per pixel: the whole raster at three or four
	 * bytes a pixel plus the entropy coder's state, measured 3.0&ndash;5.8 bytes, see the class
	 * comment.
	 */
	static final int LOSSLESS_BYTES_PER_PIXEL = 6;

	/**
	 * What the losslessly compressed alpha channel of a lossy WebP adds per pixel: the reader decodes
	 * it into a whole raster of four bytes a pixel and keeps one channel; measured 3.2 bytes above
	 * the lossy picture, see the class comment.
	 */
	static final int ALPHA_BYTES_PER_PIXEL = 4;

	private static final long MB = 1024 * 1024;

	private static volatile long budgetBytes = configuredBudget();

	/** The budget in KiB, so that every heap fits the permits of a {@link Semaphore}. */
	private static volatile Semaphore budget = new Semaphore(kib(budgetBytes), true);

	private static final AtomicLong RESERVED = new AtomicLong();

	private static final AtomicLong PEAK = new AtomicLong();

	private static final AtomicLong WAITS = new AtomicLong();

	/** Run after a reservation is taken, before the decode; attached by the tests only. */
	static volatile Runnable reservedHook;

	private final File _file;

	private final ImageInputStream _in;

	private final ImageReader _reader;

	/** The image of the reader that is the picture: 0, but for the TIFF preview of a raw. */
	private final int _index;

	private final int _width;

	private final int _height;

	/** Where the first frame lies on the canvas, <code>null</code> where it is the whole canvas. */
	private final Rectangle _frame;

	/** What a decode of the first frame holds beyond the raster it answers, in bytes. */
	private final long _wholeBytes;

	private PictureReader(File file, ImageInputStream in, ImageReader reader) throws IOException {
		this(file, in, reader, 0);
	}

	private PictureReader(File file, ImageInputStream in, ImageReader reader, int index) throws IOException {
		_file = file;
		_in = in;
		_reader = reader;
		_index = index;
		int frameWidth = reader.getWidth(index);
		int frameHeight = reader.getHeight(index);
		Rectangle frame = null;
		int width = frameWidth;
		int height = frameHeight;
		boolean webp = "webp".equalsIgnoreCase(reader.getFormatName());
		int[] animation = webp ? webpAnimation(file) : null;
		if (animation != null) {
			// The reader answers an animation's first frame, the metadata its canvas.
			width = animation[0];
			height = animation[1];
			Rectangle placed = new Rectangle(animation[2], animation[3], frameWidth, frameHeight);
			if (!placed.equals(new Rectangle(0, 0, width, height))) {
				frame = placed;
			}
		} else if ("gif".equalsIgnoreCase(reader.getFormatName())) {
			Rectangle placed = gifFrame(reader, frameWidth, frameHeight);
			int[] screen = gifScreen(reader);
			width = screen[0] > 0 ? screen[0] : placed.x + placed.width;
			height = screen[1] > 0 ? screen[1] : placed.y + placed.height;
			if (!placed.equals(new Rectangle(0, 0, width, height))) {
				frame = placed;
			}
		}
		_width = width;
		_height = height;
		_frame = frame;
		long pixels = ((long) frameWidth) * frameHeight;
		_wholeBytes = webp ? webpBytesPerPixel(file) * pixels : 0;
	}

	/**
	 * Opens the given picture for reading; a header read, no pixels.
	 *
	 * @throws IOException
	 *         Where the file cannot be read or no reader knows its format.
	 */
	public static PictureReader open(File file) throws IOException {
		if (de.haumacher.imageServer.raw.RawFile.isRaw(file)) {
			return openRaw(file);
		}
		ImageInputStream in = ImageIO.createImageInputStream(file);
		if (in == null) {
			throw new IOException("Cannot open image data of '" + file.getName() + "'.");
		}
		try {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				throw new IOException("No image reader for '" + file.getName() + "'.");
			}
			ImageReader reader = readers.next();
			try {
				// Only a GIF's metadata are asked, for where its first frame lies on the canvas.
				reader.setInput(in, true, !"gif".equalsIgnoreCase(reader.getFormatName()));
				return new PictureReader(file, in, reader);
			} catch (IOException | RuntimeException ex) {
				reader.dispose();
				throw ex;
			}
		} catch (IOException | RuntimeException ex) {
			in.close();
			throw ex;
		}
	}

	/**
	 * Opens a raw photograph as the preview it carries, see issue #191: its JPEG read in place, or
	 * the uncompressed TIFF image a DNG of Android's carries, through the TIFF reader of the JDK. One
	 * without a preview is refused with a sentence that says so.
	 */
	private static PictureReader openRaw(File file) throws IOException {
		de.haumacher.imageServer.raw.EmbeddedPreview preview = de.haumacher.imageServer.raw.RawFile.require(file);
		ImageInputStream in = de.haumacher.imageServer.raw.RawFile.open(file, preview);
		if (in == null) {
			throw new IOException("Cannot open image data of '" + file.getName() + "'.");
		}
		try {
			boolean tiff = preview.getTiffIndex() >= 0;
			Iterator<ImageReader> readers =
				ImageIO.getImageReadersByFormatName(tiff ? "tiff" : "jpeg");
			if (!readers.hasNext()) {
				throw new IOException("No image reader for the preview of '" + file.getName() + "'.");
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, !tiff, true);
				return new PictureReader(file, in, reader, tiff ? preview.getTiffIndex() : 0);
			} catch (IOException | RuntimeException ex) {
				reader.dispose();
				throw ex;
			}
		} catch (IOException | RuntimeException ex) {
			in.close();
			throw ex;
		}
	}

	/** The width of the picture's raw raster — its canvas — in pixels, before any orientation. */
	public int getWidth() {
		return _width;
	}

	/** The height of the picture's raw raster, likewise. */
	public int getHeight() {
		return _height;
	}

	/**
	 * What decoding the picture holds in the heap beyond the raster it answers, in bytes: 0 for a
	 * decoder that subsamples while it decodes.
	 */
	public long getWholeRasterBytes() {
		return _wholeBytes;
	}

	/**
	 * Decodes the given rectangle of the canvas, every <code>sampling</code>-th pixel, as
	 * {@link ImageReadParam#setSourceRegion(Rectangle)} and
	 * {@link ImageReadParam#setSourceSubsampling(int, int, int, int)} define it: the result is
	 * <code>ceil(width / sampling)</code> by <code>ceil(height / sampling)</code> pixels.
	 *
	 * @param region
	 *        The rectangle in the raw raster, <code>null</code> for the whole canvas.
	 * @throws PictureTooLargeException
	 *         Where the picture's decoder needs more memory than the server has for one picture.
	 */
	public BufferedImage read(Rectangle region, int sampling) throws IOException {
		int n = Math.max(1, sampling);
		Rectangle canvas = new Rectangle(0, 0, _width, _height);
		Rectangle wanted = region == null ? canvas : region.intersection(canvas);
		if (wanted.isEmpty()) {
			throw new IOException("Nothing to read of '" + _file.getName() + "' at " + region + ".");
		}
		Reservation reservation = _wholeBytes <= 0 ? null : reserve(_file.getName(), "a WebP", _width, _height, _wholeBytes);
		try {
			Runnable observer = reservedHook;
			if (reservation != null && observer != null) {
				observer.run();
			}
			BufferedImage image = _frame == null ? plain(region == null ? null : wanted, n) : composed(wanted, n);
			if (image == null) {
				throw new IOException("Nothing decoded from '" + _file.getName() + "'.");
			}
			return image;
		} catch (OutOfMemoryError ex) {
			// The raster that did not fit is gone with the stack; the server goes on.
			throw outOfMemory(_file.getName(), _width, _height, ex);
		} finally {
			if (reservation != null) {
				reservation.close();
			}
		}
	}

	private BufferedImage plain(Rectangle region, int n) throws IOException {
		ImageReadParam param = _reader.getDefaultReadParam();
		if (region != null) {
			param.setSourceRegion(region);
		}
		if (n > 1) {
			param.setSourceSubsampling(n, n, 0, 0);
		}
		return _reader.read(_index, param);
	}

	/**
	 * The first frame of an animation placed on its canvas: the sampled pixels of the wanted rectangle that
	 * fall into the frame are read out of the frame and drawn where they lie, everything else is
	 * {@link PreviewCache#TRANSPARENT_BACKGROUND}.
	 */
	private BufferedImage composed(Rectangle wanted, int n) throws IOException {
		int width = (wanted.width + n - 1) / n;
		int height = (wanted.height + n - 1) / n;
		BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = result.createGraphics();
		try {
			g.setColor(PreviewCache.TRANSPARENT_BACKGROUND);
			g.fillRect(0, 0, width, height);
			Rectangle inside = _frame.intersection(wanted);
			if (inside.isEmpty()) {
				return result;
			}
			// The first sampled column and row inside the frame: the grid starts at the wanted corner.
			int x0 = wanted.x + ceilDiv(inside.x - wanted.x, n) * n;
			int y0 = wanted.y + ceilDiv(inside.y - wanted.y, n) * n;
			int x1 = inside.x + inside.width;
			int y1 = inside.y + inside.height;
			if (x0 >= x1 || y0 >= y1) {
				return result;
			}
			ImageReadParam param = _reader.getDefaultReadParam();
			param.setSourceRegion(new Rectangle(x0 - _frame.x, y0 - _frame.y, x1 - x0, y1 - y0));
			if (n > 1) {
				param.setSourceSubsampling(n, n, 0, 0);
			}
			BufferedImage piece = _reader.read(0, param);
			g.drawImage(piece, (x0 - wanted.x) / n, (y0 - wanted.y) / n, null);
			return result;
		} finally {
			g.dispose();
		}
	}

	private static int ceilDiv(int value, int n) {
		return value <= 0 ? 0 : (value + n - 1) / n;
	}

	@Override
	public void close() throws IOException {
		try {
			_reader.dispose();
		} finally {
			_in.close();
		}
	}

	// --- The budget. ---

	/**
	 * How many bytes the decodes that hold a whole picture may hold at the same time, see the class
	 * comment.
	 */
	public static long budget() {
		return budgetBytes;
	}

	/**
	 * Sets the budget, see {@link #budget()}. At start-up and in the tests, never while a picture is
	 * being decoded.
	 */
	public static void setBudget(long bytes) {
		if (bytes < MB) {
			throw new IllegalArgumentException("The decode budget is at least 1 MB: " + bytes);
		}
		budgetBytes = bytes;
		budget = new Semaphore(kib(bytes), true);
	}

	/** Sets the budget back to the one the heap and the system property say. Tests only. */
	static void resetBudget() {
		setBudget(configuredBudget());
	}

	/** The most that was ever reserved at the same time since {@link #resetStatistics()}. Tests only. */
	static long peakReserved() {
		return PEAK.get();
	}

	/** How many decodes had to wait for the budget since {@link #resetStatistics()}. Tests only. */
	static long waits() {
		return WAITS.get();
	}

	/** Forgets the statistics. Tests only. */
	static void resetStatistics() {
		PEAK.set(RESERVED.get());
		WAITS.set(0);
	}

	private static long configuredBudget() {
		String value = System.getProperty(BUDGET_PROPERTY);
		if (value != null && !value.isBlank()) {
			try {
				long megabytes = Long.parseLong(value.trim());
				if (megabytes >= 1) {
					return megabytes * MB;
				}
				LOG.warning("Ignoring '" + BUDGET_PROPERTY + "=" + value + "': not a positive number.");
			} catch (NumberFormatException ex) {
				LOG.warning("Ignoring '" + BUDGET_PROPERTY + "=" + value + "': not a number.");
			}
		}
		return Math.max(MB, Runtime.getRuntime().maxMemory() / 2);
	}

	private static int kib(long bytes) {
		return (int) Math.min(Integer.MAX_VALUE, (bytes + 1023) / 1024);
	}

	private static long mb(long bytes) {
		return (bytes + MB - 1) / MB;
	}

	/**
	 * Memory of the decode budget held by one decode, given back by {@link #close()}, see
	 * {@link PictureReader#reserve(String, String, int, int, long)}.
	 */
	public static final class Reservation implements AutoCloseable {

		/** The semaphore the reservation was taken from, which a {@link #setBudget(long)} may have replaced. */
		private final Semaphore _semaphore;

		private int _kib;

		Reservation(Semaphore semaphore, int kib) {
			_semaphore = semaphore;
			_kib = kib;
		}

		@Override
		public void close() {
			if (_kib > 0) {
				RESERVED.addAndGet(-_kib * 1024L);
				_semaphore.release(_kib);
				_kib = 0;
			}
		}
	}

	/**
	 * Reserves what a decode holds beyond the raster it answers from the decode budget, waiting
	 * while others hold it; a decode that needs more than the whole budget is refused at once.
	 *
	 * <p>
	 * Every decoder that holds a whole picture asks here: the WebP reader of this class, the JPEG XL
	 * decoder and the composition of an AVIF with alpha (issue #193).
	 * </p>
	 *
	 * @param name
	 *        The name of the file, for the message.
	 * @param what
	 *        What the picture is, for the message: "a WebP".
	 * @throws PictureTooLargeException
	 *         Where the decode needs more than the whole budget; its message says what to do.
	 */
	public static Reservation reserve(String name, String what, int width, int height, long bytes)
			throws IOException {
		Semaphore semaphore = budget;
		if (bytes <= 0) {
			return new Reservation(semaphore, 0);
		}
		long total = budgetBytes;
		if (bytes > total) {
			throw new PictureTooLargeException("'" + name + "' is " + what + " of " + width + " × " + height
				+ " pixels whose decoder needs memory for the whole picture: about " + mb(bytes)
				+ " MB of memory, more than the " + mb(total) + " MB this server lets one picture take (half of its "
				+ mb(Runtime.getRuntime().maxMemory())
				+ " MB of heap). Start the server with more memory (-Xmx in JAVA_OPTS) or store the picture as a JPEG.");
		}
		int kib = kib(bytes);
		if (!semaphore.tryAcquire(kib)) {
			WAITS.incrementAndGet();
			try {
				semaphore.acquire(kib);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while waiting for the memory to decode '" + name + "'.");
			}
		}
		long now = RESERVED.addAndGet(kib * 1024L);
		PEAK.accumulateAndGet(now, Math::max);
		return new Reservation(semaphore, kib);
	}

	/**
	 * The answer to a decode that ran out of memory after all: the raster that did not fit is gone
	 * with the stack, the server goes on.
	 */
	public static PictureTooLargeException outOfMemory(String name, int width, int height, OutOfMemoryError ex) {
		return new PictureTooLargeException("'" + name + "' (" + width + " × " + height
			+ " pixels) could not be decoded in this server's memory (" + mb(Runtime.getRuntime().maxMemory())
			+ " MB of heap). Start the server with more memory (-Xmx in JAVA_OPTS) or store the picture as a JPEG.",
			ex);
	}

	// --- The formats. ---

	/**
	 * What the TwelveMonkeys reader holds per pixel to decode the first picture of the given WebP,
	 * from the chunks of the file: {@link #LOSSLESS_BYTES_PER_PIXEL} for a lossless bitstream,
	 * {@link #LOSSY_BYTES_PER_PIXEL} for a lossy one, plus {@link #ALPHA_BYTES_PER_PIXEL} for an
	 * alpha channel compressed losslessly (<code>ALPH</code> with compression 1, which the reader
	 * decodes into a whole four-byte raster); the first frame for an animation. A file it cannot make
	 * sense of is answered as lossless, the most a WebP can cost.
	 */
	static int webpBytesPerPixel(File file) throws IOException {
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			byte[] header = new byte[12];
			in.readFully(header);
			if (!"RIFF".equals(ascii(header, 0)) || !"WEBP".equals(ascii(header, 8))) {
				return LOSSLESS_BYTES_PER_PIXEL;
			}
			long end = Math.min(in.length(), 8 + le32(header, 4));
			int cost = chunksCost(in, 12, end, true);
			return cost > 0 ? cost : LOSSLESS_BYTES_PER_PIXEL;
		}
	}

	private static int chunksCost(RandomAccessFile in, long start, long end, boolean top) throws IOException {
		int alpha = 0;
		byte[] chunk = new byte[8];
		long pos = start;
		while (pos + 8 <= end) {
			in.seek(pos);
			in.readFully(chunk);
			String fourCC = ascii(chunk, 0);
			long size = le32(chunk, 4);
			long data = pos + 8;
			switch (fourCC) {
				case "VP8 ":
					return alpha + LOSSY_BYTES_PER_PIXEL;
				case "VP8L":
					return alpha + LOSSLESS_BYTES_PER_PIXEL;
				case "ALPH":
					if (size > 0 && (in.read() & 0x03) == 1) {
						alpha = ALPHA_BYTES_PER_PIXEL;
					}
					break;
				case "ANMF":
					if (top) {
						// X, Y, width, height, duration and flags, then the frame's own chunks.
						return chunksCost(in, data + 16, Math.min(end, data + size), false);
					}
					break;
				default:
					break;
			}
			pos = data + size + (size & 1);
		}
		return alpha;
	}

	/**
	 * The canvas of an animated WebP and where its first frame lies on it,
	 * <code>{canvasWidth, canvasHeight, x, y}</code>, from its <code>VP8X</code> and its first
	 * <code>ANMF</code> chunk; <code>null</code> for a WebP that is no animation.
	 */
	static int[] webpAnimation(File file) throws IOException {
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			byte[] header = new byte[12];
			in.readFully(header);
			if (!"RIFF".equals(ascii(header, 0)) || !"WEBP".equals(ascii(header, 8))) {
				return null;
			}
			long end = Math.min(in.length(), 8 + le32(header, 4));
			int[] canvas = null;
			byte[] chunk = new byte[8];
			byte[] payload = new byte[10];
			long pos = 12;
			while (pos + 8 <= end) {
				in.seek(pos);
				in.readFully(chunk);
				String fourCC = ascii(chunk, 0);
				long size = le32(chunk, 4);
				if (size >= 10 && ("VP8X".equals(fourCC) || "ANMF".equals(fourCC))) {
					in.readFully(payload);
					if ("VP8X".equals(fourCC)) {
						if ((payload[0] & 0x02) == 0) {
							return null;
						}
						canvas = new int[] { le24(payload, 4) + 1, le24(payload, 7) + 1 };
					} else if (canvas != null) {
						// The frame's offset is stored halved.
						return new int[] { canvas[0], canvas[1], 2 * le24(payload, 0), 2 * le24(payload, 3) };
					}
				}
				pos = pos + 8 + size + (size & 1);
			}
			return null;
		}
	}

	private static int le24(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8 | (bytes[offset + 2] & 0xFF) << 16;
	}

	private static String ascii(byte[] bytes, int offset) {
		return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
	}

	private static long le32(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFFL) | (bytes[offset + 1] & 0xFFL) << 8 | (bytes[offset + 2] & 0xFFL) << 16
			| (bytes[offset + 3] & 0xFFL) << 24;
	}

	/** The logical screen of a GIF, <code>{0, 0}</code> where the reader says none. */
	private static int[] gifScreen(ImageReader reader) throws IOException {
		IIOMetadata metadata = reader.getStreamMetadata();
		if (metadata == null) {
			return new int[] { 0, 0 };
		}
		Node descriptor = child(metadata.getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
		return new int[] { attribute(descriptor, "logicalScreenWidth"), attribute(descriptor, "logicalScreenHeight") };
	}

	/** Where the first frame of a GIF lies on its logical screen. */
	private static Rectangle gifFrame(ImageReader reader, int width, int height) throws IOException {
		IIOMetadata metadata = reader.getImageMetadata(0);
		if (metadata == null) {
			return new Rectangle(0, 0, width, height);
		}
		Node descriptor = child(metadata.getAsTree("javax_imageio_gif_image_1.0"), "ImageDescriptor");
		return new Rectangle(attribute(descriptor, "imageLeftPosition"), attribute(descriptor, "imageTopPosition"),
			width, height);
	}

	private static Node child(Node node, String name) {
		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (name.equals(child.getNodeName())) {
				return child;
			}
		}
		return null;
	}

	private static int attribute(Node node, String name) {
		if (node == null || node.getAttributes() == null) {
			return 0;
		}
		Node attribute = node.getAttributes().getNamedItem(name);
		if (attribute == null) {
			return 0;
		}
		try {
			return Integer.parseInt(attribute.getNodeValue());
		} catch (NumberFormatException ex) {
			return 0;
		}
	}
}
