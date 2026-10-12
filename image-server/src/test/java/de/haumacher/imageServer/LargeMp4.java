/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import test.de.haumacher.valbum.GenerateTestAlbum;

/**
 * Builds the videos of issue #252: an mp4 over 4 GiB out of a tiny recorded one, as a phone
 * records it, an mp4 holding no video track, and a recording that was cut off.
 *
 * <p>
 * Nothing of the size is ever written: the large file is the tiny video's <code>ftyp</code>, its
 * <code>mdat</code> under a 64-bit <code>largesize</code> header and grown past 2<sup>32</sup>
 * bytes by a hole (a sparse region, which costs no disk), and its <code>moov</code> behind that, at
 * an offset beyond 2<sup>32</sup>. The samples stay at the start of the <code>mdat</code>, so
 * their <code>stco</code> offsets are only moved by the longer header.
 * </p>
 */
@SuppressWarnings("javadoc")
public final class LargeMp4 {

	/** The size the <code>mdat</code> is grown to: 4 GiB and a bit, like the reported file. */
	public static final long MDAT_SIZE = (1L << 32) + 16368;

	/** The boxes whose content are boxes, on the way down to <code>stco</code>. */
	private static final Set<String> CONTAINERS =
		new HashSet<>(Arrays.asList("moov", "trak", "mdia", "minf", "stbl"));

	private LargeMp4() {
		// Static only.
	}

	/**
	 * Writes the large video into the given file.
	 *
	 * @return Whether it was written as a sparse file; <code>false</code> where the file system
	 *         cannot leave a hole (the file is then deleted again), or where that cannot be told.
	 */
	public static boolean write(File target) throws Exception {
		File tiny = File.createTempFile("valbum-tiny", ".mp4");
		try {
			GenerateTestAlbum.recordTinyVideo(tiny, "2025-09-03T18:59:39Z");
			write(Files.readAllBytes(tiny.toPath()), target);
		} finally {
			tiny.delete();
		}
		return keptSparse(target);
	}

	/**
	 * Writes the given small video grown past 4 GiB into the given file, as {@link #write(File)}
	 * grows the tiny one: an mp4 or a QuickTime movie of 32-bit boxes and chunk offsets, its
	 * <code>mdat</code> before its <code>moov</code> (a portrait one for issue #255).
	 *
	 * @return See {@link #write(File)}.
	 */
	public static boolean write(File source, File target) throws Exception {
		write(Files.readAllBytes(source.toPath()), target);
		return keptSparse(target);
	}

	/** Whether the given file was written as a sparse file; deleted where it was not. */
	private static boolean keptSparse(File target) {
		long used = diskUsageKiB(target);
		if (used < 0 || used > 64 * 1024) {
			target.delete();
			return false;
		}
		return true;
	}

	/** A valid mp4 container saying nothing but its <code>ftyp</code> and its movie header. */
	public static byte[] withoutVideoTrack() throws Exception {
		byte[] ftyp = box("ftyp", concat("isom".getBytes(StandardCharsets.ISO_8859_1), new byte[] { 0, 0, 2, 0 },
			"isomiso2avc1mp41".getBytes(StandardCharsets.ISO_8859_1)));
		ByteBuffer mvhd = ByteBuffer.allocate(100);
		mvhd.putInt(0); // version and flags
		mvhd.putInt(0); // creation time
		mvhd.putInt(0); // modification time
		mvhd.putInt(1000); // time scale
		mvhd.putInt(0); // duration
		mvhd.putInt(0x00010000); // rate 1.0
		mvhd.putShort((short) 0x0100); // volume 1.0
		mvhd.put(new byte[10]); // reserved
		int[] matrix = { 0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000 };
		for (int value : matrix) {
			mvhd.putInt(value);
		}
		mvhd.put(new byte[24]); // pre-defined
		mvhd.putInt(2); // next track id
		byte[] moov = box("moov", box("mvhd", mvhd.array()));
		return concat(ftyp, moov);
	}

	/**
	 * A recording that was cut off: its <code>ftyp</code> and the start of its <code>mdat</code>,
	 * and no <code>moov</code> &mdash; neither metadata-extractor nor FFmpeg (&quot;moov atom not
	 * found&quot;) can read it. The <code>mdat</code> says the size it would have had.
	 */
	public static byte[] truncated() {
		byte[] ftyp = box("ftyp", concat("isom".getBytes(StandardCharsets.ISO_8859_1), new byte[] { 0, 0, 2, 0 },
			"isomiso2avc1mp41".getBytes(StandardCharsets.ISO_8859_1)));
		ByteBuffer mdat = ByteBuffer.allocate(64 * 1024);
		mdat.putInt(10 * 1024 * 1024);
		mdat.put("mdat".getBytes(StandardCharsets.ISO_8859_1));
		for (int n = 0; mdat.hasRemaining(); n++) {
			mdat.put((byte) (n * 31));
		}
		return concat(ftyp, mdat.array());
	}

	private static void write(byte[] mp4, File target) throws IOException {
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		byte[] payload = null;
		long oldPayloadStart = -1;
		byte[] moov = null;
		int pos = 0;
		while (pos < mp4.length) {
			int size = ByteBuffer.wrap(mp4, pos, 4).getInt();
			String type = new String(mp4, pos + 4, 4, StandardCharsets.ISO_8859_1);
			if (size == 1 || size == 0) {
				throw new IOException("The tiny video is expected to have 32-bit box sizes only.");
			}
			switch (type) {
				case "mdat":
					oldPayloadStart = pos + 8;
					payload = Arrays.copyOfRange(mp4, pos + 8, pos + size);
					break;
				case "moov":
					moov = Arrays.copyOfRange(mp4, pos, pos + size);
					break;
				case "free":
					break;
				default:
					if (payload != null) {
						throw new IOException("Unexpected box behind the mdat: " + type);
					}
					head.write(mp4, pos, size);
			}
			pos += size;
		}
		if (payload == null || moov == null) {
			throw new IOException("The tiny video has no mdat or no moov.");
		}
		long mdatStart = head.size();
		long newPayloadStart = mdatStart + 16;
		shiftChunkOffsets(moov, 8, moov.length, newPayloadStart - oldPayloadStart);

		ByteBuffer header = ByteBuffer.allocate(16);
		header.putInt(1);
		header.put("mdat".getBytes(StandardCharsets.ISO_8859_1));
		header.putLong(MDAT_SIZE);

		target.delete();
		try (RandomAccessFile out = new RandomAccessFile(target, "rw")) {
			out.write(head.toByteArray());
			out.write(header.array());
			out.write(payload);
			out.seek(mdatStart + MDAT_SIZE);
			out.write(moov);
		}
	}

	/** Moves every <code>stco</code> entry in the given range of boxes by the given delta. */
	private static void shiftChunkOffsets(byte[] data, int from, int to, long delta) throws IOException {
		int pos = from;
		while (pos < to) {
			int size = ByteBuffer.wrap(data, pos, 4).getInt();
			String type = new String(data, pos + 4, 4, StandardCharsets.ISO_8859_1);
			if (CONTAINERS.contains(type)) {
				shiftChunkOffsets(data, pos + 8, pos + size, delta);
			} else if ("stco".equals(type)) {
				ByteBuffer table = ByteBuffer.wrap(data);
				int count = table.getInt(pos + 12);
				for (int n = 0; n < count; n++) {
					int at = pos + 16 + 4 * n;
					table.putInt(at, (int) (table.getInt(at) + delta));
				}
			} else if ("co64".equals(type)) {
				throw new IOException("The tiny video is expected to have 32-bit chunk offsets.");
			}
			pos += size;
		}
	}

	/** The disk the given file occupies in KiB as <code>du</code> says, <code>-1</code> if unknown. */
	private static long diskUsageKiB(File file) {
		try {
			Process du = new ProcessBuilder("du", "-k", file.getAbsolutePath()).redirectErrorStream(true).start();
			String out = new String(du.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (!du.waitFor(10, TimeUnit.SECONDS) || du.exitValue() != 0) {
				return -1;
			}
			return Long.parseLong(out.split("\\s+")[0]);
		} catch (IOException | RuntimeException ex) {
			return -1;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return -1;
		}
	}

	private static byte[] box(String type, byte[] content) {
		ByteBuffer result = ByteBuffer.allocate(8 + content.length);
		result.putInt(8 + content.length);
		result.put(type.getBytes(StandardCharsets.ISO_8859_1));
		result.put(content);
		return result.array();
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.write(part, 0, part.length);
		}
		return out.toByteArray();
	}
}
