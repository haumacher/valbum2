/*
 * Copyright (c) 2026 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * A source of static web content addressed by web-root-relative paths.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface ContentSource {

	/**
	 * Whether the given web-root-relative path denotes an existing file of this source.
	 */
	boolean exists(String path);

	/**
	 * Opens the file with the given web-root-relative path.
	 *
	 * @return A stream of the file contents, or <code>null</code>, if this source has no such
	 *         file. The caller must close the result.
	 */
	InputStream open(String path) throws IOException;

	/**
	 * The size of the file with the given web-root-relative path, or <code>-1</code>, if unknown.
	 */
	default long size(String path) throws IOException {
		return -1;
	}

	/**
	 * {@link ContentSource} reading from a directory of the file system.
	 */
	class Directory implements ContentSource {

		private final Path _root;

		/**
		 * Creates a {@link Directory} source serving the given directory.
		 */
		public Directory(Path root) {
			_root = root.toAbsolutePath().normalize();
		}

		@Override
		public boolean exists(String path) {
			return resolve(path) != null;
		}

		@Override
		public InputStream open(String path) throws IOException {
			Path file = resolve(path);
			return file == null ? null : Files.newInputStream(file);
		}

		@Override
		public long size(String path) throws IOException {
			Path file = resolve(path);
			return file == null ? -1 : Files.size(file);
		}

		private Path resolve(String path) {
			Path file;
			try {
				file = _root.resolve(path).normalize();
			} catch (InvalidPathException ex) {
				return null;
			}
			if (!file.startsWith(_root)) {
				// Never serve anything outside of the web root.
				return null;
			}
			return Files.isRegularFile(file) ? file : null;
		}

		@Override
		public String toString() {
			return _root.toString();
		}

	}

	/**
	 * {@link ContentSource} reading from a prefix of the class path.
	 */
	class Classpath implements ContentSource {

		private final String _prefix;

		private final ClassLoader _loader;

		/**
		 * Creates a {@link Classpath} source serving resources below the given class path prefix.
		 *
		 * @param prefix
		 *        The resource prefix without a trailing <code>/</code>, e.g.
		 *        <code>/META-INF/resources</code>.
		 */
		public Classpath(String prefix, ClassLoader loader) {
			_prefix = prefix;
			_loader = loader;
		}

		@Override
		public boolean exists(String path) {
			return url(path) != null;
		}

		@Override
		public InputStream open(String path) throws IOException {
			URL url = url(path);
			return url == null ? null : url.openStream();
		}

		private URL url(String path) {
			if (path.isEmpty() || path.endsWith("/")) {
				return null;
			}
			// The class loader does not accept a leading separator and resolves nothing itself.
			URL url = _loader.getResource(_prefix.substring(1) + "/" + path);
			if (url == null) {
				return null;
			}
			if (url.getPath().endsWith("/")) {
				// A directory entry of a JAR file.
				return null;
			}
			if ("file".equals(url.getProtocol())) {
				try {
					if (Files.isDirectory(Path.of(url.toURI()))) {
						return null;
					}
				} catch (URISyntaxException ex) {
					return null;
				}
			}
			return url;
		}

		@Override
		public String toString() {
			return "classpath:" + _prefix;
		}

	}

}
