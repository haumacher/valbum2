/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the start script of the Debian package, <code>src/deb/valbum-server</code>.
 *
 * <p>
 * The script is run as it is shipped, with the configuration file it is shipped with, against a
 * fake <code>java</code> on the <code>PATH</code> that says it is a Java 21 and prints the
 * arguments it is handed, one per line. So what is checked is exactly what the server would be
 * started with.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestValbumServerScript extends TestCase {

	private static final Path SCRIPT = Path.of("src/deb/valbum-server");

	private static final Path DEFAULTS = Path.of("src/deb/valbum.default");

	private Path _dir;

	private Path _base;

	private Path _config;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-script");
		_base = Files.createDirectory(_dir.resolve("library"));
		Path bin = Files.createDirectory(_dir.resolve("bin"));
		Path java = bin.resolve("java");
		Files.writeString(java, "#!/bin/sh\n"
			+ "if [ \"$1\" = -version ]; then echo 'openjdk version \"21.0.4\" 2024-07-16' >&2; exit 0; fi\n"
			+ "for arg in \"$@\"; do echo \"$arg\"; done\n", StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
		Files.writeString(_dir.resolve("valbum.jar"), "not a jar", StandardCharsets.UTF_8);
		_config = _dir.resolve("valbum");
		Files.copy(DEFAULTS, _config);
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testTheShippedConfigurationFollowsTheFolders() throws Exception {
		if (noShell()) {
			return;
		}
		List<String> args = run(Map.of());
		assertEquals(args.toString(), "auto", after(args, "--spaces"));
		assertEquals("The configured values are all passed on.", "writes", after(args, "--auth"));
	}

	public void testTheConfigurationChoosesTheMode() throws Exception {
		if (noShell()) {
			return;
		}
		configure("VALBUM_SPACES=multi");
		assertEquals("multi", after(run(Map.of()), "--spaces"));
	}

	public void testTheEnvironmentWinsOverTheConfiguration() throws Exception {
		if (noShell()) {
			return;
		}
		configure("VALBUM_SPACES=multi");
		assertEquals("single", after(run(Map.of("VALBUM_SPACES", "single")), "--spaces"));
	}

	/** A configuration written before the variable existed, kept as a conffile over an upgrade. */
	public void testAnOlderConfigurationFollowsTheFolders() throws Exception {
		if (noShell()) {
			return;
		}
		List<String> lines = new ArrayList<>(Files.readAllLines(_config, StandardCharsets.UTF_8));
		lines.removeIf(line -> line.startsWith("VALBUM_SPACES="));
		Files.write(_config, lines, StandardCharsets.UTF_8);
		assertEquals("auto", after(run(Map.of()), "--spaces"));
	}

	public void testTheCommandLineComesLast() throws Exception {
		if (noShell()) {
			return;
		}
		List<String> args = run(Map.of(), "--create-space", "family");
		assertEquals(Arrays.asList("--create-space", "family"), args.subList(args.size() - 2, args.size()));
		assertTrue("The configured mode comes before it: " + args,
			args.indexOf("--spaces") < args.indexOf("--create-space"));
	}

	/** Whether this machine cannot run the script; said once per test, never passed silently. */
	private boolean noShell() {
		if (new File("/bin/sh").canExecute()) {
			return false;
		}
		System.err.println("SKIPPED " + getName() + ": no /bin/sh to run the start script with.");
		return true;
	}

	private void configure(String line) throws Exception {
		Files.writeString(_config, Files.readString(_config, StandardCharsets.UTF_8) + "\n" + line + "\n",
			StandardCharsets.UTF_8);
	}

	private static String after(List<String> args, String option) {
		int index = args.indexOf(option);
		assertTrue("No " + option + " in " + args, index >= 0 && index + 1 < args.size());
		return args.get(index + 1);
	}

	/** Runs the script and answers the arguments the fake <code>java</code> was started with. */
	private List<String> run(Map<String, String> env, String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of("/bin/sh", SCRIPT.toString()));
		command.addAll(Arrays.asList(args));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
		Map<String, String> environment = builder.environment();
		environment.keySet().removeIf(name -> name.startsWith("VALBUM_") || name.startsWith("JAVA_"));
		environment.put("PATH", _dir.resolve("bin") + ":/usr/bin:/bin");
		environment.put("VALBUM_CONFIG", _config.toString());
		environment.put("VALBUM_JAR", _dir.resolve("valbum.jar").toString());
		environment.put("VALBUM_BASEPATH", _base.toString());
		environment.putAll(env);
		Process process = builder.start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals("The script failed: " + err, 0, process.waitFor());
		List<String> result = new ArrayList<>(out.lines().toList());
		int jar = result.indexOf("-jar");
		assertTrue("Not started with a JAR: " + result, jar >= 0);
		return result.subList(jar + 2, result.size());
	}
}
