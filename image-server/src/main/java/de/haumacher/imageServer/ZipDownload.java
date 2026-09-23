/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A selection of originals as one archive, built on the fly, see issue #164.
 *
 * <p>
 * The archive is written straight into the response: nothing is ever written to disk, and an
 * original is only read. Every entry is framed as a deflate stream without compression
 * ({@link Deflater#NO_COMPRESSION}): a JPEG or a video does not get smaller by being deflated
 * again, and compressing a whole album would cost the server the processor for nothing. That
 * framing needs no size and no checksum in advance, so each original is read exactly once —
 * {@link ZipEntry#STORED} would need both before the first byte of the entry and therefore a
 * second pass over every file. The Java writer switches to Zip64 by itself for an archive or an
 * entry beyond four gigabytes.
 * </p>
 *
 * <p>
 * What goes in is decided before: {@link ImageServlet} refuses the whole request, speaking, before
 * the first byte of the archive is sent, so a caller never receives an archive that silently lacks
 * what was asked for.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class ZipDownload {

	/** The media type of the answer. */
	static final String CONTENT_TYPE = "application/zip";

	private ZipDownload() {
		// Static helpers only.
	}

	/**
	 * Writes the given files, in their order, as the entries of one archive into the given stream.
	 *
	 * <p>
	 * An entry is named by its file name alone (no folder), and carries the file's modification
	 * time. The stream is finished but not closed: that is the container's business.
	 * </p>
	 */
	static void write(OutputStream out, List<File> files) throws IOException {
		ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
		zip.setMethod(ZipOutputStream.DEFLATED);
		zip.setLevel(Deflater.NO_COMPRESSION);
		byte[] buffer = new byte[64 * 1024];
		for (File file : files) {
			ZipEntry entry = new ZipEntry(file.getName());
			entry.setTime(file.lastModified());
			zip.putNextEntry(entry);
			try (InputStream in = new FileInputStream(file)) {
				int read;
				while ((read = in.read(buffer)) >= 0) {
					zip.write(buffer, 0, read);
				}
			}
			zip.closeEntry();
		}
		zip.finish();
		zip.flush();
	}

	/**
	 * The <code>Content-Disposition</code> header naming the archive after the given folder.
	 *
	 * <p>
	 * Both spellings of RFC 6266: the plain <code>filename</code> for a client that knows no other,
	 * with everything but printable ASCII replaced, and <code>filename*</code> carrying the name as
	 * it is in UTF-8, which every current browser prefers.
	 * </p>
	 */
	static String contentDisposition(String folderName) {
		String name = (folderName == null || folderName.isEmpty() ? "album" : folderName) + ".zip";
		StringBuilder ascii = new StringBuilder();
		for (int n = 0; n < name.length(); n++) {
			char c = name.charAt(n);
			ascii.append(c < 0x20 || c >= 0x7F || c == '"' || c == '\\' ? '_' : c);
		}
		String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
		return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
	}
}
