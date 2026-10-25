/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Makes the Python embedder service of issue #257 ready to run on this machine: its code, its
 * Python environment and the model.
 *
 * <h2>The code</h2>
 *
 * <p>
 * The service (<code>image-server/src/embedder</code>: <code>embedder.py</code>,
 * <code>model.json</code>, <code>requirements.txt</code>, <code>setup-venv</code>) travels inside
 * the server's JAR and is unpacked into <code>&lt;dir&gt;/service/&lt;hash&gt;/</code>, named after
 * its content: the Debian package, the container image and a plain <code>java -jar</code> run the
 * very same code, of the very same version as the server.
 * </p>
 *
 * <h2>The environment</h2>
 *
 * <p>
 * A virtual environment in <code>&lt;dir&gt;/python/&lt;hash&gt;-&lt;arch&gt;/</code>, named after
 * the requirements and the machine, made by <code>setup-venv</code> with the system's
 * <code>python3</code> (3.12 or newer; the package recommends <code>python3-venv</code>): the pinned
 * wheels with their hashes, from PyPI or, offline, from <code>&lt;dir&gt;/wheels/</code>. Made the
 * first time content search is asked for, never at installation: about 50 MB of downloads, 190 MB
 * on disk.
 * </p>
 *
 * <h2>The model</h2>
 *
 * <p>
 * A {@link ModelStore} below <code>&lt;dir&gt;</code>.
 * </p>
 */
public final class PythonService implements ManagedEmbedder.Launcher {

	private static final Logger LOG = Logger.getLogger(PythonService.class.getName());

	/** The files of the bundled service. */
	static final List<String> CODE = List.of("embedder.py", ContentModel.MANIFEST, "requirements.txt", "setup-venv");

	/** The folder below the embedder's folder the wheels of an offline install are put into. */
	public static final String WHEELS = "wheels";

	/** How long making the environment may take: a download of 50 MB on a slow line. */
	static final Duration SETUP_TIMEOUT = Duration.ofMinutes(30);

	/** The priority the service runs at, below the server's, so that viewing photos comes first. */
	static final int NICE = 10;

	/** How many inferences the child runs at once, see issue #311. */
	static final int PARALLEL = 1;

	private final Path _dir;

	private final String _precision;

	private final int _port;

	private final Duration _idleExit;

	private final ModelStore _model;

	/**
	 * Creates a {@link PythonService}.
	 *
	 * @param dir
	 *        The embedder's folder, <code>VALBUM_EMBEDDER_DIR</code>.
	 * @param port
	 *        The port on <code>127.0.0.1</code>, 0 for any free one.
	 * @param idleExit
	 *        How long the service may be idle before it ends and gives its memory back.
	 * @param download
	 *        How the model is downloaded, <code>null</code> to use only what was placed by hand.
	 */
	public PythonService(Path dir, String precision, int port, Duration idleExit, ModelStore.Settings download) {
		_dir = dir;
		_precision = precision;
		_port = port;
		_idleExit = idleExit;
		_model = new ModelStore(dir, ContentModel.bundled(), precision, download);
	}

	/** The model's files. */
	public ModelStore getModel() {
		return _model;
	}

	@Override
	public List<String> prepare(Consumer<String> progress) throws IOException, InterruptedException {
		progress.accept("unpacking the service into " + _dir);
		Path code = unpack(_dir);
		Path python = environment(code, progress);
		progress.accept("checking the model in " + _model.getDirectory());
		_model.request();
		while (!_model.awaitReady(Duration.ofSeconds(5))) {
			String problem = _model.problem();
			if (!_model.isRunning()) {
				throw new IOException(problem);
			}
			progress.accept(problem);
		}
		return command(python, code);
	}

	/** The command that runs the service of the given code with the given Python. */
	List<String> command(Path python, Path code) {
		List<String> command = new ArrayList<>();
		command.add(python.toString());
		command.add("-I");
		command.add(code.resolve("embedder.py").toString());
		command.add("--model-dir");
		command.add(_model.getDirectory().toString());
		command.add("--precision");
		command.add(_precision);
		command.add("--port");
		command.add(String.valueOf(_port));
		// One inference at a time: the child shares the server's machine, and its units wait behind
		// the photo work one by one (issue #311); said here, not left to the service's default, which
		// fills a machine of its own.
		command.add("--parallel");
		command.add(String.valueOf(PARALLEL));
		command.add("--nice");
		command.add(String.valueOf(NICE));
		command.add("--idle-exit");
		command.add(String.valueOf(_idleExit.toSeconds()));
		command.add("--exit-with-stdin");
		return command;
	}

	/**
	 * Unpacks the bundled service into a folder named after its content, once.
	 *
	 * @return The folder.
	 */
	static Path unpack(Path dir) throws IOException {
		List<byte[]> contents = new ArrayList<>();
		MessageDigest digest = sha256();
		for (String name : CODE) {
			try (InputStream in = ContentModel.class.getResourceAsStream(ContentModel.RESOURCE_FOLDER + name)) {
				if (in == null) {
					throw new IOException("The server was built without the embedder service (" + name + ").");
				}
				byte[] bytes = in.readAllBytes();
				contents.add(bytes);
				digest.update(name.getBytes(StandardCharsets.UTF_8));
				digest.update(bytes);
			}
		}
		Path target = dir.resolve("service").resolve(HexFormat.of().formatHex(digest.digest()).substring(0, 12));
		if (Files.isRegularFile(target.resolve(CODE.get(CODE.size() - 1)))) {
			return target;
		}
		Path tmp = Files.createDirectories(dir.resolve("service")).resolve(target.getFileName() + ".tmp");
		deleteTree(tmp);
		Files.createDirectories(tmp);
		for (int n = 0; n < CODE.size(); n++) {
			Files.write(tmp.resolve(CODE.get(n)), contents.get(n));
		}
		deleteTree(target);
		Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
		return target;
	}

	/**
	 * The Python of the service's environment, made where it is missing.
	 */
	private Path environment(Path code, Consumer<String> progress) throws IOException, InterruptedException {
		byte[] requirements = Files.readAllBytes(code.resolve("requirements.txt"));
		String name = HexFormat.of().formatHex(sha256().digest(requirements)).substring(0, 12) + "-"
			+ System.getProperty("os.arch").toLowerCase(Locale.ROOT);
		Path venv = _dir.resolve("python").resolve(name);
		Path python = venv.resolve("bin").resolve("python3");
		if (Files.isExecutable(python)) {
			return python;
		}
		progress.accept("installing the Python packages of the service into " + venv
			+ " (about 50 MB from PyPI, or from " + _dir.resolve(WHEELS) + ")");
		ProcessBuilder builder = new ProcessBuilder("sh", code.resolve("setup-venv").toString(), venv.toString(),
			code.resolve("requirements.txt").toString(), _dir.resolve(WHEELS).toString());
		builder.redirectErrorStream(true);
		builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
		Process process;
		try {
			process = builder.start();
		} catch (IOException ex) {
			throw new IOException("cannot run 'sh' to make the Python environment: " + ex.getMessage(), ex);
		}
		String last = "";
		try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = out.readLine()) != null) {
				LOG.info("Content search: " + line);
				if (!line.isBlank()) {
					last = line;
				}
			}
		}
		if (!process.waitFor(SETUP_TIMEOUT.toMinutes(), TimeUnit.MINUTES)) {
			process.destroyForcibly();
			throw new IOException("making the Python environment took longer than " + SETUP_TIMEOUT.toMinutes() + " minutes");
		}
		if (process.exitValue() != 0 || !Files.isExecutable(python)) {
			throw new IOException("cannot make the Python environment of the service: "
				+ last.replaceFirst("^setup-venv: ", ""));
		}
		return python;
	}

	private static MessageDigest sha256() throws IOException {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new IOException(ex);
		}
	}

	private static void deleteTree(Path path) throws IOException {
		if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var files = Files.walk(path)) {
			for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		}
	}

	@Override
	public String stamp() {
		return ContentModel.bundled().stamp(_precision, Embedder.DIMENSION);
	}

	@Override
	public void close() {
		_model.close();
	}

	@Override
	public String toString() {
		return "the embedder service in " + _dir + " (" + _precision + ")";
	}
}
