/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * The marker a library moved into a space leaves behind: <code>&lt;base&gt;/.valbum/{@value #FILE_NAME}</code>.
 *
 * <p>
 * A single-space library answers at <code>&lt;context&gt;/data/...</code>, its share links at
 * <code>&lt;context&gt;/s/&lt;token&gt;/</code> and its invitations at
 * <code>&lt;context&gt;/i/&lt;token&gt;/</code>. Moved into the space <code>&lt;folder&gt;</code>
 * (<code>--move-into-space</code>), it lives at <code>&lt;context&gt;/&lt;folder&gt;/...</code>;
 * while this file names that space, a multi-space server answers the old addresses as that space,
 * so that every paired device (which stored <code>&lt;context&gt;/data</code>), every share link
 * and every invitation already sent keeps working unchanged. See {@link Spaces#alias()}.
 * </p>
 *
 * <pre>
 * {"version":1,"space":"family","moved":"2026-09-28T10:15:30Z"}
 * </pre>
 *
 * <p>
 * The alias lives exactly as long as the file: deleting it by hand ends it at the next start.
 * </p>
 */
public final class SpaceAlias {

	/** The name of the marker in the base folder's {@value UserStore#DIRECTORY_NAME}. */
	public static final String FILE_NAME = "moved.json";

	private static final int VERSION = 1;

	private static final String VERSION__PROP = "version";

	private static final String SPACE__PROP = "space";

	private static final String MOVED__PROP = "moved";

	private SpaceAlias() {
		// Static utility.
	}

	/** The marker of the given base folder, whether it exists or not. */
	public static Path file(Path basePath) {
		return basePath.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
	}

	/**
	 * The space the old addresses of the given base folder answer as.
	 *
	 * @return <code>null</code> if there is no marker, or it names no space.
	 * @throws IOException
	 *         If the marker is there but cannot be read.
	 */
	public static String read(Path basePath) throws IOException {
		Path file = file(basePath);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		String space = "";
		try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
				JsonReader in = new JsonReader(new ReaderAdapter(reader))) {
			in.beginObject();
			while (in.hasNext()) {
				if (SPACE__PROP.equals(in.nextName())) {
					space = in.nextString();
				} else {
					in.skipValue();
				}
			}
			in.endObject();
		} catch (RuntimeException ex) {
			throw new IOException("Cannot read '" + file + "': " + ex.getMessage(), ex);
		}
		space = space.trim();
		return space.isEmpty() ? null : space;
	}

	/**
	 * Writes the marker, creating the base folder's {@value UserStore#DIRECTORY_NAME}.
	 *
	 * @throws java.nio.file.FileAlreadyExistsException
	 *         If there is a marker already; it is not touched then.
	 */
	public static void write(Path basePath, String space, Instant moved) throws IOException {
		Path file = file(basePath);
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
		try {
			try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8);
					JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(SPACE__PROP);
				out.value(space);
				out.name(MOVED__PROP);
				out.value(moved.toString());
				out.endObject();
			}
			Files.move(tmp, file);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}
}
