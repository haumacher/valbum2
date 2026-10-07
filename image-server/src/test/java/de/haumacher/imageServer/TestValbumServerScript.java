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
			+ "env > \"$FAKE_ENV\"\n"
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

	// --- The settings read from the environment (issue #199). ---

	/** Every setting the server reads from its environment is documented, and none is set, in the shipped file. */
	public void testTheShippedConfigurationDocumentsTheEnvironmentSettings() throws Exception {
		String shipped = Files.readString(DEFAULTS, StandardCharsets.UTF_8);
		for (String name : ENV_SETTINGS) {
			assertTrue("Not documented: " + name, shipped.contains("#   " + name + "="));
			assertFalse("Set in the shipped file: " + name, shipped.contains("\n" + name + "="));
		}
		if (noShell()) {
			return;
		}
		run(Map.of());
		for (String name : ENV_SETTINGS) {
			assertNull("Nothing configured, nothing passed: " + name, environment().get(name));
		}
	}

	/** The mail account reaches the server through its environment and never through its arguments. */
	public void testTheMailSettingsReachTheServerThroughItsEnvironment() throws Exception {
		if (noShell()) {
			return;
		}
		configure("VALBUM_PUBLIC_URL=https://photos.example.org/valbum\n"
			+ "VALBUM_SMTP_HOST=smtp.example.org\n"
			+ "VALBUM_SMTP_PORT=465\n"
			+ "VALBUM_SMTP_USER=album@example.org\n"
			+ "VALBUM_SMTP_PASSWORD='se cr$t\"pw'\n"
			+ "VALBUM_SMTP_FROM=album@example.org\n"
			+ "VALBUM_SMTP_TLS=tls");
		List<String> args = run(Map.of());
		Map<String, String> env = environment();
		assertEquals("https://photos.example.org/valbum", env.get("VALBUM_PUBLIC_URL"));
		assertEquals("smtp.example.org", env.get("VALBUM_SMTP_HOST"));
		assertEquals("465", env.get("VALBUM_SMTP_PORT"));
		assertEquals("album@example.org", env.get("VALBUM_SMTP_USER"));
		assertEquals("The password arrives as written.", "se cr$t\"pw", env.get("VALBUM_SMTP_PASSWORD"));
		assertEquals("album@example.org", env.get("VALBUM_SMTP_FROM"));
		assertEquals("tls", env.get("VALBUM_SMTP_TLS"));
		for (String arg : args) {
			assertFalse("No secret on the command line: " + args, arg.contains("se cr") || arg.contains("smtp"));
		}
	}

	/** The gazetteer's folder and memory reach the server through its environment, see issue #234. */
	public void testTheGazetteerSettingsReachTheServerThroughItsEnvironment() throws Exception {
		if (noShell()) {
			return;
		}
		configure("VALBUM_GEONAMES_DIR=/var/cache/valbum/geonames\nVALBUM_GEONAMES_MEMORY=48");
		run(Map.of());
		Map<String, String> env = environment();
		assertEquals("/var/cache/valbum/geonames", env.get("VALBUM_GEONAMES_DIR"));
		assertEquals("48", env.get("VALBUM_GEONAMES_MEMORY"));
	}

	public void testTheEnvironmentWinsForTheMailSettingsToo() throws Exception {
		if (noShell()) {
			return;
		}
		configure("VALBUM_SMTP_HOST=smtp.example.org");
		run(Map.of("VALBUM_SMTP_HOST", "mail.example.net"));
		assertEquals("mail.example.net", environment().get("VALBUM_SMTP_HOST"));
	}

	// --- The providers of OpenID Connect (issue #200), named after the provider. ---

	public void testTheShippedConfigurationDocumentsGoogle() throws Exception {
		String shipped = Files.readString(DEFAULTS, StandardCharsets.UTF_8);
		assertTrue(shipped.contains("#   VALBUM_OIDC_GOOGLE_CLIENT_ID="));
		assertTrue(shipped.contains("#   VALBUM_OIDC_GOOGLE_CLIENT_SECRET="));
		assertTrue(shipped.contains("/oidc/callback"));
		assertFalse(shipped.contains("\nVALBUM_OIDC_"));
	}

	public void testEveryProviderReachesTheServerThroughItsEnvironment() throws Exception {
		if (noShell()) {
			return;
		}
		configure("VALBUM_OIDC_GOOGLE_CLIENT_ID=123.apps.googleusercontent.com\n"
			+ "VALBUM_OIDC_GOOGLE_CLIENT_SECRET='GOCSPX-se cr$t'\n"
			+ "VALBUM_OIDC_MY_IDP_CLIENT_ID=valbum\n"
			+ "VALBUM_OIDC_MY_IDP_LABEL=\"Our club\"");
		List<String> args = run(Map.of("VALBUM_OIDC_MY_IDP_CLIENT_ID", "from-the-caller",
			"VALBUM_OIDC_EXTRA_CLIENT_ID", "only-in-the-environment"));
		Map<String, String> env = environment();
		assertEquals("123.apps.googleusercontent.com", env.get("VALBUM_OIDC_GOOGLE_CLIENT_ID"));
		assertEquals("GOCSPX-se cr$t", env.get("VALBUM_OIDC_GOOGLE_CLIENT_SECRET"));
		assertEquals("The caller's environment wins.", "from-the-caller", env.get("VALBUM_OIDC_MY_IDP_CLIENT_ID"));
		assertEquals("Our club", env.get("VALBUM_OIDC_MY_IDP_LABEL"));
		assertEquals("only-in-the-environment", env.get("VALBUM_OIDC_EXTRA_CLIENT_ID"));
		for (String arg : args) {
			assertFalse("No secret on the command line: " + args, arg.contains("GOCSPX"));
		}
	}

	/** The names of the settings the server reads from its environment, see {@link ServerEnvironment}. */
	static final List<String> ENV_SETTINGS = List.of(ServerEnvironment.PUBLIC_URL, ServerEnvironment.SMTP_HOST,
		ServerEnvironment.SMTP_PORT, ServerEnvironment.SMTP_USER, ServerEnvironment.SMTP_PASSWORD,
		ServerEnvironment.SMTP_FROM, ServerEnvironment.SMTP_TLS, ServerEnvironment.GEONAMES_DIR,
		ServerEnvironment.GEONAMES_MEMORY);

	/** The environment the fake <code>java</code> was started with. */
	private Map<String, String> environment() throws Exception {
		Map<String, String> result = new java.util.HashMap<>();
		String last = null;
		for (String line : Files.readAllLines(_dir.resolve("env"), StandardCharsets.UTF_8)) {
			int eq = line.indexOf('=');
			if (eq > 0 && line.substring(0, eq).matches("[A-Za-z_][A-Za-z0-9_]*")) {
				last = line.substring(0, eq);
				result.put(last, line.substring(eq + 1));
			} else if (last != null) {
				result.put(last, result.get(last) + "\n" + line);
			}
		}
		return result;
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
		environment.put("FAKE_ENV", _dir.resolve("env").toString());
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
