/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import com.drew.metadata.Metadata;
import com.drew.metadata.webp.WebpRiffHandler;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Reads the metadata of a WebP without reading its picture, see issue #207.
 *
 * <p>
 * metadata-extractor's own WebP reader hands its handler every <code>VP8 </code> and
 * <code>VP8L</code> chunk whole, as one byte array, to read the four bytes of the picture's size out
 * of it: a 50&nbsp;MB lossless WebP put 50&nbsp;MB into the heap for every analysis, every preview and
 * every orientation look-up, on top of what decoding it costs. This reader walks the chunks itself
 * and gives the very same handler ({@link WebpRiffHandler}) the whole of the small chunks —
 * <code>VP8X</code>, <code>EXIF</code>, <code>XMP </code>, <code>ICCP</code> — and only the
 * {@value #HEADER_BYTES} first bytes of a picture chunk, which is all the handler reads of it. So
 * the directories that come out are those metadata-extractor answers, without the file-system and
 * file-type directories that nothing here asks.
 * </p>
 */
public final class WebpMetadata {

	/** How much of a picture chunk the handler is given: VP8 reads up to byte 9, VP8L up to byte 4. */
	static final int HEADER_BYTES = 16;

	/** Larger metadata chunks than this are not read: no EXIF, XMP or ICC profile is that large. */
	private static final long MAX_METADATA_CHUNK = 64L * 1024 * 1024;

	private WebpMetadata() {
		// Static utility.
	}

	/** Whether the given file is a WebP by its content: a RIFF container of the form WEBP. */
	public static boolean isWebp(File file) {
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			if (in.length() < 12) {
				return false;
			}
			byte[] header = new byte[12];
			in.readFully(header);
			return "RIFF".equals(ascii(header, 0)) && "WEBP".equals(ascii(header, 8));
		} catch (IOException ex) {
			return false;
		}
	}

	/** Reads the metadata of the given WebP. */
	public static Metadata read(File file) throws IOException {
		Metadata metadata = new Metadata();
		WebpRiffHandler handler = new WebpRiffHandler(metadata);
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			byte[] header = new byte[12];
			in.readFully(header);
			if (!"RIFF".equals(ascii(header, 0)) || !"WEBP".equals(ascii(header, 8))) {
				throw new IOException("Not a WebP: '" + file.getName() + "'.");
			}
			long end = Math.min(in.length(), 8 + le32(header, 4));
			long pos = 12;
			byte[] chunk = new byte[8];
			while (pos + 8 <= end) {
				in.seek(pos);
				in.readFully(chunk);
				String fourCC = ascii(chunk, 0);
				long size = le32(chunk, 4);
				long data = pos + 8;
				long available = Math.max(0, Math.min(size, end - data));
				if (handler.shouldAcceptChunk(fourCC)) {
					boolean picture = "VP8 ".equals(fourCC) || "VP8L".equals(fourCC);
					long length = picture ? Math.min(available, HEADER_BYTES) : available;
					if (length <= MAX_METADATA_CHUNK) {
						byte[] payload = new byte[(int) length];
						in.readFully(payload);
						handler.processChunk(fourCC, payload);
					}
				}
				pos = data + size + (size & 1);
			}
		}
		return metadata;
	}

	private static String ascii(byte[] bytes, int offset) {
		return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
	}

	private static long le32(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFFL) | (bytes[offset + 1] & 0xFFL) << 8 | (bytes[offset + 2] & 0xFFL) << 16
			| (bytes[offset + 3] & 0xFFL) << 24;
	}
}
