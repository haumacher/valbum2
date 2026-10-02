/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.heif;

import de.haumacher.imageServer.VideoRenditions;
import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decodes the pixels of a HEIC/HEIF photograph with the FFmpeg program the server already bundles,
 * see issue #186.
 *
 * <p>
 * Neither ImageIO nor the bundled FFmpeg libraries read HEIF (FFmpeg demuxes it from 7.1 on, the
 * presets ship 5.1), but every HEIC a phone writes is plain HEVC inside: one picture, or a grid of
 * tiles (a 4032&nbsp;&times;&nbsp;3024 photograph is 48 tiles of 512&nbsp;&times;&nbsp;512). So
 * the container is read in Java ({@link HeifFile}), the tiles are handed to the program's native
 * <code>hevc</code> decoder as one Annex&nbsp;B stream — each tile its own picture, its parameter
 * sets in front of it — and the program's filters do the rest: <code>tile</code> stitches the grid
 * row by row, <code>crop</code> cuts it to the picture (and to the region asked for),
 * <code>scale</code> brings it to the size asked for, in the colour range and matrix the
 * <code>nclx</code> box declares. The full raster never enters the Java heap: what is read back is
 * the size that was asked for, which is how a Raspberry Pi makes a preview of a 50 MP photograph.
 * </p>
 *
 * <p>
 * The program is the one {@link VideoRenditions} runs, started the same way
 * ({@link VideoRenditions#program(List)}, which puts the bundled libraries on the child's library
 * path); its <code>hevc</code> decoder is part of the LGPL build on every packaged platform, so no
 * library is added to any package. Where the program cannot be run, {@link #unavailability()} says
 * why and every decode throws with that reason; the server goes on listing the photograph.
 * </p>
 */
public final class HeifDecoder {

	private static final Logger LOG = Logger.getLogger(HeifDecoder.class.getName());

	/** How long one decode may take before it is given up. */
	private static final long TIMEOUT_SECONDS = 120;

	/** How many lines of the program's complaint are kept for the message. */
	private static final int ERROR_TAIL_LINES = 10;

	/** How the program is found, a seam for the tests. */
	public interface ProgramLocator {

		/** The path of the FFmpeg program, or a throw saying why there is none. */
		String locate() throws Exception;
	}

	private static final ProgramLocator BUNDLED = VideoRenditions::executable;

	private static volatile ProgramLocator _locator = BUNDLED;

	private static volatile String _unavailable;

	private static volatile boolean _checked;

	private static final Object LOCK = new Object();

	private HeifDecoder() {
		// Static utility.
	}

	/**
	 * Replaces how the program is found and forgets what was found before. Tests only.
	 *
	 * @param locator
	 *        <code>null</code> to go back to the bundled program.
	 */
	public static void setProgramLocator(ProgramLocator locator) {
		synchronized (LOCK) {
			_locator = locator == null ? BUNDLED : locator;
			_unavailable = null;
			_checked = false;
		}
	}

	/**
	 * Why HEIC/HEIF photographs cannot be decoded here, <code>null</code> when they can.
	 *
	 * <p>
	 * Asked once and remembered, like {@link VideoRenditions#unavailability()}: the program is there
	 * with an HEVC decoder or it is not.
	 * </p>
	 */
	public static String unavailability() {
		if (_checked) {
			return _unavailable;
		}
		synchronized (LOCK) {
			if (!_checked) {
				try {
					String program = program();
					if (!hasHevcDecoder(program)) {
						throw new IOException("The bundled FFmpeg has no HEVC decoder.");
					}
					LOG.info("HEIC/HEIF photographs are decoded with '" + program + "'.");
					_unavailable = null;
				} catch (Throwable ex) {
					// Every Throwable: a missing native library arrives as an Error.
					String message = ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
					LOG.log(Level.WARNING, "HEIC/HEIF photographs cannot be decoded: " + message, ex);
					_unavailable = "HEIC/HEIF photographs cannot be decoded on this server: " + message;
				}
				_checked = true;
			}
			return _unavailable;
		}
	}

	private static String program() throws IOException {
		try {
			String program = _locator.locate();
			if (program == null) {
				throw new IOException("No FFmpeg program available.");
			}
			return program;
		} catch (IOException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IOException("No FFmpeg program available.", ex);
		}
	}

	private static boolean hasHevcDecoder(String program) throws IOException {
		ProcessBuilder builder = VideoRenditions.program(List.of(program, "-hide_banner", "-decoders"));
		builder.redirectErrorStream(true);
		Process process = builder.start();
		boolean found = false;
		try (BufferedReader reader =
			new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				// " VFS..D hevc                 HEVC (High Efficiency Video Coding)"
				String[] columns = line.trim().split("\\s+");
				if (columns.length >= 2 && columns[0].startsWith("V") && "hevc".equals(columns[1])) {
					found = true;
				}
			}
		}
		waitFor(process);
		return found;
	}

	/**
	 * Decodes the whole raw raster of the given photograph at the given size.
	 *
	 * @see #decodeRaw(File, HeifFile, int, int, int, int, int, int)
	 */
	public static BufferedImage decodeRaw(File file, HeifFile heif, int outWidth, int outHeight)
			throws IOException {
		return decodeRaw(file, heif, 0, 0, heif.getRawWidth(), heif.getRawHeight(), outWidth, outHeight);
	}

	/**
	 * Decodes a region of the raw raster of the given photograph (see {@link HeifFile}), scaled to
	 * the given size.
	 *
	 * @return A {@link BufferedImage#TYPE_3BYTE_BGR} raster of exactly the given size, in the raw
	 *         raster's own orientation.
	 */
	public static BufferedImage decodeRaw(File file, HeifFile heif, int left, int top, int width, int height,
			int outWidth, int outHeight) throws IOException {
		check(left, top, width, height, heif);
		List<String> command = command(heif, left, top, width, height, outWidth, outHeight, null);
		command.add("-f");
		command.add("rawvideo");
		command.add("-pix_fmt");
		command.add("bgr24");
		command.add("pipe:1");
		BufferedImage result = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_3BYTE_BGR);
		byte[] pixels = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
		run(file, heif, command, pixels);
		return result;
	}

	/**
	 * Writes the given photograph upright, at the given size, as a JPEG.
	 *
	 * <p>
	 * The display rendition of issue #186: the program turns, scales and encodes, so not even the
	 * scaled raster enters the Java heap.
	 * </p>
	 *
	 * @param outWidth
	 *        The width of the picture as shown, the orientation applied.
	 * @param outHeight
	 *        Its height.
	 */
	public static void writeUprightJpeg(File file, HeifFile heif, int outWidth, int outHeight, File target)
			throws IOException {
		writeUprightJpeg(file, heif, heif.getOrientation(), outWidth, outHeight, target);
	}

	/** Writes the given photograph turned by the given orientation, see the method above. */
	static void writeUprightJpeg(File file, HeifFile heif, Orientation orientation, int outWidth, int outHeight,
			File target) throws IOException {
		List<String> command = command(heif, 0, 0, heif.getRawWidth(), heif.getRawHeight(), outWidth, outHeight,
			orientation);
		command.add("-c:v");
		command.add("mjpeg");
		command.add("-q:v");
		command.add("2");
		command.add("-f");
		command.add("mjpeg");
		command.add("-y");
		command.add(target.getAbsolutePath());
		run(file, heif, command, null);
		if (!target.isFile() || target.length() == 0) {
			throw new IOException("FFmpeg wrote no picture of '" + file.getName() + "'.");
		}
	}

	private static void check(int left, int top, int width, int height, HeifFile heif) {
		if (left < 0 || top < 0 || width <= 0 || height <= 0 || left + width > heif.getRawWidth()
			|| top + height > heif.getRawHeight()) {
			throw new IllegalArgumentException("The region " + left + "," + top + " " + width + "x" + height
				+ " is not inside the picture of " + heif.getRawWidth() + "x" + heif.getRawHeight() + ".");
		}
	}

	/**
	 * The command line up to its output: the stream on standard input, the filters.
	 *
	 * @param turn
	 *        The orientation to apply after the crop, <code>null</code> for the raw raster.
	 */
	static List<String> command(HeifFile heif, int left, int top, int width, int height, int outWidth,
			int outHeight, Orientation turn) throws IOException {
		String unavailable = unavailability();
		if (unavailable != null) {
			throw new IOException(unavailable);
		}
		List<String> command = new ArrayList<>();
		command.add(program());
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("error");
		command.add("-threads");
		command.add(Integer.toString(Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));
		command.add("-f");
		command.add("hevc");
		command.add("-i");
		command.add("pipe:0");
		command.add("-frames:v");
		command.add("1");
		command.add("-an");
		command.add("-vf");
		command.add(filters(heif, left, top, width, height, outWidth, outHeight, turn));
		return command;
	}

	/** The filter graph, see the class comment. */
	static String filters(HeifFile heif, int left, int top, int width, int height, int outWidth, int outHeight,
			Orientation turn) {
		StringBuilder graph = new StringBuilder();
		if (heif.getColumns() > 1 || heif.getRows() > 1) {
			graph.append("tile=").append(heif.getColumns()).append('x').append(heif.getRows()).append(',');
		}
		graph.append("crop=w=").append(width).append(":h=").append(height)
			.append(":x=").append(heif.getCropLeft() + left)
			.append(":y=").append(heif.getCropTop() + top)
			.append(":exact=1");
		if (turn != null) {
			String turning = turn(turn);
			if (!turning.isEmpty()) {
				graph.append(',').append(turning);
			}
		}
		graph.append(",scale=w=").append(outWidth).append(":h=").append(outHeight).append(":flags=area");
		Boolean fullRange = heif.getFullRange();
		if (fullRange != null) {
			graph.append(":in_range=").append(fullRange.booleanValue() ? "full" : "limited");
		}
		String matrix = matrix(heif.getMatrix());
		if (matrix != null) {
			graph.append(":in_color_matrix=").append(matrix);
		}
		graph.append(turn == null ? ",format=bgr24" : ",format=yuvj420p");
		return graph.toString();
	}

	/**
	 * The filters that bring a raw raster of the given orientation upright: the pixel formulas of
	 * {@link de.haumacher.imageServer.faces.Originals#upright}, spelled in FFmpeg's terms.
	 */
	static String turn(Orientation orientation) {
		switch (orientation) {
			case FLIP_H:
				return "hflip";
			case ROT_180:
				return "hflip,vflip";
			case FLIP_V:
				return "vflip";
			case ROT_L_FLIP_V:
				// shown (u, v) = raw (v, u)
				return "transpose=cclock_flip";
			case ROT_L:
				// shown (u, v) = raw (v, H - 1 - u): a quarter turn clockwise
				return "transpose=clock";
			case ROT_L_FLIP_H:
				// shown (u, v) = raw (W - 1 - v, H - 1 - u)
				return "transpose=clock_flip";
			case ROT_R:
				// shown (u, v) = raw (W - 1 - v, u): a quarter turn anti-clockwise
				return "transpose=cclock";
			case IDENTITY:
			default:
				return "";
		}
	}

	/** The name <code>scale</code> knows the given H.273 matrix by, <code>null</code> to leave it. */
	private static String matrix(int coefficients) {
		switch (coefficients) {
			case 1:
				return "bt709";
			case 5:
			case 6:
				return "bt601";
			case 7:
				return "smpte240m";
			case 9:
			case 10:
				return "bt2020";
			default:
				return null;
		}
	}

	/**
	 * Runs the program, feeding it the coded tiles and reading what it answers into the given
	 * buffer (<code>null</code> where it writes a file).
	 */
	private static void run(File file, HeifFile heif, List<String> command, byte[] pixels) throws IOException {
		ProcessBuilder builder = VideoRenditions.program(command);
		Process process = builder.start();
		Deque<String> tail = new ArrayDeque<>();
		Thread errors = new Thread(() -> collect(process.getErrorStream(), tail), "heif-decoder-errors");
		errors.setDaemon(true);
		errors.start();
		IOException[] feedFailure = new IOException[1];
		Thread feeder = new Thread(() -> {
			try (OutputStream in = process.getOutputStream()) {
				feed(file, heif, in);
			} catch (IOException ex) {
				feedFailure[0] = ex;
			}
		}, "heif-decoder-input");
		feeder.setDaemon(true);
		feeder.start();
		int read = 0;
		try (InputStream out = process.getInputStream()) {
			if (pixels != null) {
				read = out.readNBytes(pixels, 0, pixels.length);
			}
			// Whatever else comes is not wanted, but must be taken so the program can end.
			out.transferTo(OutputStream.nullOutputStream());
		} finally {
			int status = waitFor(process);
			join(feeder);
			if (feedFailure[0] != null) {
				throw feedFailure[0];
			}
			join(errors);
			if (status != 0) {
				String last;
				synchronized (tail) {
					last = tail.isEmpty() ? "" : tail.peekLast().trim();
					LOG.warning("FFmpeg failed to decode '" + file.getName() + "' with exit code " + status + ":\n"
						+ String.join("\n", tail));
				}
				throw new IOException("Cannot decode the HEIC/HEIF photograph '" + file.getName() + "'"
					+ (last.isEmpty() ? "." : ": " + last));
			}
		}
		if (pixels != null && read != pixels.length) {
			throw new IOException("Cannot decode the HEIC/HEIF photograph '" + file.getName() + "': "
				+ read + " of " + pixels.length + " bytes decoded.");
		}
	}

	/** The coded pictures as one Annex B stream: per tile its parameter sets, then its units. */
	static void feed(File file, HeifFile heif, OutputStream out) throws IOException {
		byte[] startCode = { 0, 0, 0, 1 };
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			for (HeifFile.Tile tile : heif.getTiles()) {
				out.write(tile._parameterSets);
				byte[] data = tile._inline;
				if (data == null) {
					long total = 0;
					for (long length : tile._lengths) {
						total += length;
					}
					if (total > Integer.MAX_VALUE) {
						throw new IOException("A tile of '" + file.getName() + "' is too large.");
					}
					data = new byte[(int) total];
					int at = 0;
					for (int n = 0; n < tile._offsets.length; n++) {
						in.seek(tile._offsets[n]);
						in.readFully(data, at, (int) tile._lengths[n]);
						at += (int) tile._lengths[n];
					}
				}
				int at = 0;
				int lengthSize = tile._lengthSize;
				while (at + lengthSize <= data.length) {
					long size = 0;
					for (int n = 0; n < lengthSize; n++) {
						size = (size << 8) | (data[at + n] & 0xFF);
					}
					at += lengthSize;
					if (size <= 0 || at + size > data.length) {
						throw new IOException("A tile of '" + file.getName() + "' is cut off.");
					}
					out.write(startCode);
					out.write(data, at, (int) size);
					at += (int) size;
				}
			}
		}
	}

	private static void collect(InputStream errors, Deque<String> tail) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(errors, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				synchronized (tail) {
					tail.addLast(line);
					if (tail.size() > ERROR_TAIL_LINES) {
						tail.removeFirst();
					}
				}
			}
		} catch (IOException ex) {
			// The program is gone; what it said so far is all there is.
		}
	}

	private static int waitFor(Process process) throws IOException {
		try {
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("FFmpeg did not finish decoding within " + TIMEOUT_SECONDS + " s.");
			}
			return process.exitValue();
		} catch (InterruptedException ex) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while decoding.", ex);
		}
	}

	private static void join(Thread thread) {
		try {
			thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
