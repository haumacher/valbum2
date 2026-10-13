/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.ServerEnvironment;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Where and how the embedder of content search runs, from the environment, see issue #257 and
 * {@link ServerEnvironment}.
 *
 * <ul>
 * <li><code>VALBUM_EMBEDDER_DIR</code>: the folder of the model, the service's Python environment
 * and its code, <code>&lt;basepath&gt;/.embedder</code> by default (inside the library like the
 * place names, so that a container's volume keeps the 2 GB; hidden by its dot).</li>
 * <li><code>VALBUM_EMBEDDER_PORT</code>: the port of the service on <code>127.0.0.1</code>; any free
 * one by default.</li>
 * <li><code>VALBUM_EMBEDDER_PRECISION</code>: <code>fp32</code> (default, the reference to a cosine
 * above 0.999) or <code>int8</code> (half the memory and download).</li>
 * <li><code>VALBUM_EMBEDDER_URL</code>: an embedder service that runs on its own (started by hand,
 * or a container beside this one); then nothing is downloaded or started here.</li>
 * </ul>
 *
 * <p>
 * Nothing of this costs anything until a space asks for content search: the service is started,
 * and its environment and model are fetched, on the first request.
 * </p>
 */
public final class EmbedderSettings {

	/** The folder of the embedder; <code>&lt;basepath&gt;/.embedder</code> by default. */
	public static final String DIR = "VALBUM_EMBEDDER_DIR";

	/** The port of the service on 127.0.0.1; any free one by default. */
	public static final String PORT = "VALBUM_EMBEDDER_PORT";

	/** <code>fp32</code> or <code>int8</code>. */
	public static final String PRECISION = "VALBUM_EMBEDDER_PRECISION";

	/** An embedder service of its own; nothing is started then. */
	public static final String URL = "VALBUM_EMBEDDER_URL";

	/** The folder below the base path the embedder is kept in by default. */
	public static final String DEFAULT_DIR = ".embedder";

	/**
	 * How long the service may be without work before it ends and gives its memory back; it is
	 * started again on the next request (the model loads in a few seconds).
	 */
	public static final Duration IDLE_EXIT = Duration.ofMinutes(15);

	/** No content search at all: what a test builds a server with. */
	public static final EmbedderSettings NONE = new EmbedderSettings(false, null, 0, ContentModel.FP32, null);

	private final boolean _on;

	private final String _dir;

	private final int _port;

	private final String _precision;

	private final URI _url;

	private EmbedderSettings(boolean on, String dir, int port, String precision, URI url) {
		_on = on;
		_dir = dir;
		_port = port;
		_precision = precision;
		_url = url;
	}

	/**
	 * Reads the settings from the given environment.
	 *
	 * @throws ServerEnvironment.Invalid
	 *         For a setting that is there but unusable, naming it.
	 */
	public static EmbedderSettings read(Map<String, String> env) throws ServerEnvironment.Invalid {
		String dir = value(env, DIR);
		if (!dir.isEmpty()) {
			try {
				Path.of(dir);
			} catch (InvalidPathException ex) {
				throw new ServerEnvironment.Invalid(DIR + " is no folder: '" + dir + "'.");
			}
		}
		String portText = value(env, PORT);
		int port = 0;
		if (!portText.isEmpty()) {
			try {
				port = Integer.parseInt(portText);
			} catch (NumberFormatException ex) {
				port = -1;
			}
			if (port <= 0 || port > 65535) {
				throw new ServerEnvironment.Invalid(PORT + " must be a port number, not '" + portText + "'.");
			}
		}
		String precision = value(env, PRECISION).toLowerCase(Locale.ROOT);
		if (precision.isEmpty()) {
			precision = ContentModel.FP32;
		} else if (!ContentModel.bundled().hasPrecision(precision)) {
			throw new ServerEnvironment.Invalid(PRECISION + " must be one of " + ContentModel.bundled().precisions()
				+ ", not '" + value(env, PRECISION) + "'.");
		}
		String urlText = value(env, URL);
		URI url = null;
		if (!urlText.isEmpty()) {
			try {
				url = new URI(urlText);
			} catch (URISyntaxException ex) {
				url = null;
			}
			String scheme = url == null || url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
			if (url == null || !(scheme.equals("http") || scheme.equals("https")) || url.getHost() == null
				|| url.getQuery() != null) {
				throw new ServerEnvironment.Invalid(URL + " must be an http address of an embedder service, for "
					+ "example 'http://127.0.0.1:8091/', not '" + urlText + "'.");
			}
		}
		return new EmbedderSettings(true, dir.isEmpty() ? null : dir, port, precision, url);
	}

	private static String value(Map<String, String> env, String name) {
		String value = env.get(name);
		return value == null ? "" : value.trim();
	}

	/** Whether content search may be used at all ({@link #NONE} says no). */
	public boolean isOn() {
		return _on;
	}

	/** The folder of the embedder for the given base path. */
	public Path directory(Path basePath) {
		return _dir == null ? basePath.resolve(DEFAULT_DIR) : Path.of(_dir);
	}

	/** <code>fp32</code> or <code>int8</code>. */
	public String getPrecision() {
		return _precision;
	}

	/** The port of the service, 0 for any free one. */
	public int getPort() {
		return _port;
	}

	/** The address of a service of its own, <code>null</code> where the server starts its own. */
	public URI getUrl() {
		return _url;
	}

	/**
	 * The embedder of a server serving the given base path; nothing is started or downloaded
	 * before it is asked for a vector. <code>null</code> for {@link #NONE}.
	 */
	public Embedder create(Path basePath) {
		if (!_on) {
			return null;
		}
		if (_url != null) {
			return new HttpEmbedder(_url);
		}
		return new ManagedEmbedder(
			new PythonService(directory(basePath), _precision, _port, IDLE_EXIT, new ModelStore.Settings()));
	}

	/** What a start-up says about content search. */
	public String report(Path basePath) {
		if (!_on) {
			return "Content search: off";
		}
		if (_url != null) {
			return "Content search: the embedder service at " + _url + " (" + URL + "), asked when a space uses it";
		}
		ContentModel model = ContentModel.bundled();
		return "Content search: " + model.getModel() + " (Apache-2.0), " + _precision + " (" + PRECISION + "), in "
			+ directory(basePath).toAbsolutePath() + " (" + DIR + "); the model ("
			+ ModelStore.megabytes(model.size(_precision)) + ") and the Python service are fetched and started only "
			+ "when a space uses it";
	}
}
