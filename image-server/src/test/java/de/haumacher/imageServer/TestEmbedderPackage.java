/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The package <code>valbum-embedder</code> of issue #257, the embedder service of content search on
 * a machine of its own (<code>src/deb-embedder</code>): what the build puts into it, the key its
 * <code>postinst</code> makes into the configuration, and what its start script starts the service
 * with. The scripts are run as shipped, against fakes of the commands that change the system and of
 * the service's Python.
 */
@SuppressWarnings("javadoc")
public class TestEmbedderPackage extends TestCase {

	private static final Path FOLDER = Path.of("src/deb-embedder");

	private static final Path POSTINST = FOLDER.resolve("control/postinst");

	private static final Path LAUNCHER = FOLDER.resolve("valbum-embedder");

	private static final Path TEMPLATE = FOLDER.resolve("valbum-embedder.default");

	private static final Pattern KEY_LINE = Pattern.compile("(?m)^VALBUM_EMBEDDER_KEY=(.*)$");

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-embedder");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	// --- What the package carries --------------------------------------------------------------------

	public void testTheBuildPutsTheServiceUnitAndTemplateIntoThePackage() throws Exception {
		String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
		int start = pom.indexOf("<id>deb-embedder</id>");
		assertTrue("No execution building valbum-embedder.", start > 0);
		String execution = pom.substring(start, pom.indexOf("</execution>", start));
		assertTrue(execution, execution.contains("valbum-embedder_${deb.version}_${deb.arch}.deb"));
		for (String[] file : new String[][] { { "deb-embedder/valbum-embedder", "/usr/bin/valbum-embedder", "755" },
			{ "embedder/embedder.py", "/usr/share/valbum-embedder/embedder.py", "644" },
			{ "embedder/model.json", "/usr/share/valbum-embedder/model.json", "644" },
			{ "embedder/requirements.txt", "/usr/share/valbum-embedder/requirements.txt", "644" },
			{ "embedder/setup-venv", "/usr/share/valbum-embedder/setup-venv", "755" },
			{ "deb-embedder/valbum-embedder.default", "/usr/share/valbum-embedder/valbum-embedder.default", "644" },
			{ "deb-embedder/valbum-embedder.service", "/lib/systemd/system/valbum-embedder.service", "644" } }) {
			Matcher entry = Pattern.compile("<src>\\$\\{project.basedir\\}/src/" + Pattern.quote(file[0]) + "</src>\\s*"
				+ "<type>file</type>\\s*<dst>" + Pattern.quote(file[1]) + "</dst>.*?<filemode>(\\d+)</filemode>",
				Pattern.DOTALL).matcher(execution);
			assertTrue("Not in the package: " + file[1], entry.find());
			assertEquals(file[1], file[2], entry.group(1));
			assertTrue("The source exists: " + file[0], Files.isRegularFile(Path.of("src").resolve(file[0])));
		}
		assertFalse("No JAR, no Java on the embedder's machine.", execution.contains(".jar"));
		assertFalse("The configuration is made by postinst with its key, never shipped as a conffile.",
			execution.contains("<dst>/etc/default/valbum-embedder</dst>")
				|| Files.exists(FOLDER.resolve("control/conffiles")));
		assertTrue("No package for armhf: onnxruntime has nothing for it.",
			Pattern.compile("<deb.arch>armhf</deb.arch>\\s*<embedder.deb.skip>true</embedder.deb.skip>").matcher(pom)
				.find());
	}

	public void testTheControlFileIsWellFormedAndDependsOnPython() throws Exception {
		String control = Files.readString(FOLDER.resolve("control/control"), StandardCharsets.UTF_8)
			.replace("${deb.version}", "2.16.0").replace("${deb.arch}", "arm64");
		assertFalse(control, control.contains("${"));
		Map<String, String> fields = new HashMap<>();
		String last = null;
		for (String line : control.split("\n")) {
			if (line.startsWith(" ")) {
				assertTrue("A description line is too long: '" + line + "'", line.length() <= 80);
				fields.put(last, fields.get(last) + "\n" + line);
			} else {
				int colon = line.indexOf(':');
				last = line.substring(0, colon);
				assertNull("Twice: " + last, fields.put(last, line.substring(colon + 1).trim()));
			}
		}
		assertEquals("valbum-embedder", fields.get("Package"));
		assertTrue(fields.get("Depends"), fields.get("Depends").contains("python3-venv"));
		assertFalse("No Java.", fields.get("Depends").contains("java"));
		assertTrue(fields.get("Description").split("\n")[0].length() <= 80);
	}

	public void testTheUnitRunsTheServiceAsItsOwnUserWithItsConfiguration() throws Exception {
		String unit = Files.readString(FOLDER.resolve("valbum-embedder.service"), StandardCharsets.UTF_8);
		assertTrue(unit.contains("User=valbum-embedder"));
		assertTrue(unit.contains("EnvironmentFile=-/etc/default/valbum-embedder"));
		assertTrue(unit.contains("ExecStart=/usr/bin/valbum-embedder"));
		assertTrue(unit.contains("ReadWritePaths=/var/lib/valbum-embedder"));
	}

	public void testTheTemplateDocumentsEverySettingAndSetsOnlyTheKey() throws Exception {
		String template = Files.readString(TEMPLATE, StandardCharsets.UTF_8);
		for (String name : List.of("VALBUM_EMBEDDER_HOST", "VALBUM_EMBEDDER_PORT", "VALBUM_EMBEDDER_TLS_CERT",
			"VALBUM_EMBEDDER_TLS_KEY", "VALBUM_EMBEDDER_PRECISION", "VALBUM_EMBEDDER_THREADS", "VALBUM_EMBEDDER_PARALLEL", "VALBUM_EMBEDDER_DIR",
			"VALBUM_EMBEDDER_MODEL_URL", "PYTHON")) {
			assertTrue("Not documented: " + name, template.contains("#   " + name + "="));
			assertFalse("Set: " + name, template.contains("\n" + name + "="));
		}
		Matcher key = KEY_LINE.matcher(template);
		assertTrue(key.find());
		assertEquals("Empty in the template; postinst fills it in.", "", key.group(1));
		assertTrue("Says what travels.", template.contains("preview") && template.contains("Unencrypted"));
	}

	// --- postinst -----------------------------------------------------------------------------------

	private Path config() {
		return _dir.resolve("etc").resolve("valbum-embedder");
	}

	private String postinst() throws Exception {
		Files.createDirectories(config().getParent());
		Path bin = _dir.resolve("bin");
		if (!Files.isDirectory(bin)) {
			Files.createDirectory(bin);
			String logLine = "echo \"$(basename \"$0\") $*\" >> \"$FAKE_LOG\"\n";
			for (String command : new String[] { "addgroup", "adduser", "chown", "systemctl", "deb-systemd-helper",
				"deb-systemd-invoke" }) {
				fake(bin, command, logLine);
			}
			fake(bin, "getent", "exit 0\n");
			fake(bin, "stat", "echo valbum-embedder\n");
			// Only the files of this test are really changed; the group is logged.
			fake(bin, "chgrp", logLine);
			fake(bin, "chmod", logLine + "case $2 in " + _dir + "/*) exec /bin/chmod \"$@\";; esac\n");
		}
		ProcessBuilder builder = new ProcessBuilder("/bin/sh", POSTINST.toString(), "configure", "");
		Map<String, String> env = builder.environment();
		env.put("PATH", bin + ":/usr/bin:/bin");
		env.put("FAKE_LOG", _dir.resolve("log").toString());
		env.put("VALBUM_EMBEDDER_CONFIG", config().toString());
		env.put("VALBUM_EMBEDDER_TEMPLATE", TEMPLATE.toAbsolutePath().toString());
		env.put("VALBUM_EMBEDDER_HOME", _dir.resolve("home").toString());
		Process process = builder.redirectErrorStream(true).start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(out, 0, process.waitFor());
		return out;
	}

	private String key() throws Exception {
		Matcher key = KEY_LINE.matcher(Files.readString(config(), StandardCharsets.UTF_8));
		assertTrue(key.find());
		return key.group(1);
	}

	public void testTheFirstInstallationMakesAKeyReadableByTheServiceOnly() throws Exception {
		if (noShell()) {
			return;
		}
		String out = postinst();
		String key = key();
		assertTrue("32 random bytes in hex: " + key, key.matches("[0-9a-f]{64}"));
		assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(config())));
		List<String> steps = Files.readAllLines(_dir.resolve("log"), StandardCharsets.UTF_8);
		assertTrue(steps.toString(), steps.stream().anyMatch(s -> s.startsWith("chgrp valbum-embedder ")));
		assertTrue("Says where the key is: " + out, out.contains("VALBUM_EMBEDDER_KEY in " + config()));
		assertFalse("Never prints the key itself: " + out, out.contains(key));
		String rest = Files.readString(config(), StandardCharsets.UTF_8).replace(key, "");
		assertEquals("Otherwise the template as it is.", Files.readString(TEMPLATE, StandardCharsets.UTF_8), rest);
		assertFalse("No temporary file left.", Files.exists(Path.of(config() + ".dpkg-new")));

		Files.delete(config());
		postinst();
		assertFalse("Every installation its own key.", key.equals(key()));
	}

	public void testAnUpgradeKeepsTheKey() throws Exception {
		if (noShell()) {
			return;
		}
		postinst();
		String key = key();
		String out = postinst();
		assertEquals(key, key());
		assertFalse("Nothing to say about a key that was kept: " + out, out.contains("made a key"));
	}

	public void testAKeyTheAdministratorRemovedStaysRemoved() throws Exception {
		if (noShell()) {
			return;
		}
		postinst();
		String text = Files.readString(config(), StandardCharsets.UTF_8);
		Files.writeString(config(), KEY_LINE.matcher(text).replaceFirst("VALBUM_EMBEDDER_KEY="), StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(config(), PosixFilePermissions.fromString("rw-r--r--"));
		postinst();
		assertEquals("The administrator's choice: an open service in the home network.", "", key());
		assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(config())));
	}

	// --- The start script -------------------------------------------------------------------------------

	/** A copy of the service's code whose setup-venv makes a fake Python that records how it was started. */
	private Path code() throws Exception {
		Path code = Files.createDirectories(_dir.resolve("code"));
		Files.copy(Path.of("src/embedder/requirements.txt"), code.resolve("requirements.txt"));
		Files.writeString(code.resolve("embedder.py"), "# the service\n", StandardCharsets.UTF_8);
		Files.writeString(code.resolve("setup-venv"), "mkdir -p \"$1/bin\"\n"
			+ "echo \"$PYTHON\" > \"$FAKE_DIR/python-used\"\n"
			+ "printf '#!/bin/sh\\nenv > \"$FAKE_DIR/env\"\\nfor a in \"$@\"; do echo \"$a\"; done > \"$FAKE_DIR/args\"\\n'"
			+ " > \"$1/bin/python3\"\n"
			+ "chmod 755 \"$1/bin/python3\"\n", StandardCharsets.UTF_8);
		return code;
	}

	private List<String> launch(String configuration, Map<String, String> env) throws Exception {
		Path code = code();
		Path dir = Files.createDirectories(_dir.resolve("data"));
		Path config = _dir.resolve("valbum-embedder.conf");
		Files.writeString(config, Files.readString(TEMPLATE, StandardCharsets.UTF_8) + "\n" + configuration + "\n",
			StandardCharsets.UTF_8);
		ProcessBuilder builder = new ProcessBuilder("/bin/sh", LAUNCHER.toString(), "--extra");
		Map<String, String> environment = builder.environment();
		environment.keySet().removeIf(name -> name.startsWith("VALBUM_") || name.equals("PYTHON"));
		environment.put("VALBUM_EMBEDDER_CONFIG", config.toString());
		environment.put("VALBUM_EMBEDDER_CODE", code.toString());
		environment.put("VALBUM_EMBEDDER_DIR", dir.toString());
		environment.put("FAKE_DIR", _dir.toString());
		environment.putAll(env);
		Process process = builder.redirectErrorStream(true).start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(out, 0, process.waitFor());
		return Files.readAllLines(_dir.resolve("args"), StandardCharsets.UTF_8);
	}

	private Map<String, String> environment() throws Exception {
		Map<String, String> result = new HashMap<>();
		for (String line : Files.readAllLines(_dir.resolve("env"), StandardCharsets.UTF_8)) {
			int eq = line.indexOf('=');
			if (eq > 0) {
				result.put(line.substring(0, eq), line.substring(eq + 1));
			}
		}
		return result;
	}

	public void testTheServiceListensOnTheNetworkAndFetchesItsModel() throws Exception {
		if (noShell()) {
			return;
		}
		List<String> args = launch("VALBUM_EMBEDDER_KEY=0123abcd", Map.of());
		assertEquals("Isolated from the caller's Python settings.", "-I", args.get(0));
		assertTrue(args.toString(), args.get(1).endsWith("embedder.py"));
		assertEquals("0.0.0.0", after(args, "--host"));
		assertEquals("8091", after(args, "--port"));
		assertEquals("fp32", after(args, "--precision"));
		assertEquals(_dir.resolve("data").toString(), after(args, "--model-root"));
		assertEquals("https://huggingface.co/", after(args, "--model-url"));
		assertTrue(args.contains("--fetch"));
		assertFalse("No TLS unless configured.", args.contains("--tls-cert"));
		assertEquals("The caller's arguments last.", "--extra", args.get(args.size() - 1));
		assertEquals("The key by the environment.", "0123abcd", environment().get("VALBUM_EMBEDDER_KEY"));
		for (String arg : args) {
			assertFalse("Never on the command line: " + args, arg.contains("0123abcd"));
		}
		try (Stream<Path> made = Files.list(_dir.resolve("data").resolve("python"))) {
			List<Path> environments = made.toList();
			assertEquals(1, environments.size());
			assertTrue("Named after the requirements and the machine: " + environments,
				environments.get(0).getFileName().toString().matches("[0-9a-f]{12}-.+"));
		}
	}

	public void testTheConfigurationChoosesAddressTlsAndPrecision() throws Exception {
		if (noShell()) {
			return;
		}
		List<String> args = launch("VALBUM_EMBEDDER_HOST=127.0.0.1\nVALBUM_EMBEDDER_PORT=9443\n"
			+ "VALBUM_EMBEDDER_PRECISION=int8\nVALBUM_EMBEDDER_TLS_CERT=/etc/e/cert.pem\n"
			+ "VALBUM_EMBEDDER_TLS_KEY=/etc/e/key.pem\nPYTHON=/opt/python3.12", Map.of("VALBUM_EMBEDDER_PORT", "9444"));
		assertEquals("127.0.0.1", after(args, "--host"));
		assertEquals("The caller's environment wins.", "9444", after(args, "--port"));
		assertEquals("int8", after(args, "--precision"));
		assertEquals("/etc/e/cert.pem", after(args, "--tls-cert"));
		assertEquals("/etc/e/key.pem", after(args, "--tls-key"));
		assertEquals("/opt/python3.12", Files.readString(_dir.resolve("python-used"), StandardCharsets.UTF_8).trim());
	}

	public void testWithoutAKeyNoneReachesTheService() throws Exception {
		if (noShell()) {
			return;
		}
		launch("", Map.of());
		assertNull(environment().get("VALBUM_EMBEDDER_KEY"));
	}

	public void testAnUnwritableFolderIsNamed() throws Exception {
		if (noShell()) {
			return;
		}
		ProcessBuilder builder = new ProcessBuilder("/bin/sh", LAUNCHER.toString());
		builder.environment().put("VALBUM_EMBEDDER_CONFIG", _dir.resolve("none").toString());
		builder.environment().put("VALBUM_EMBEDDER_DIR", _dir.resolve("missing").toString());
		Process process = builder.redirectErrorStream(true).start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(1, process.waitFor());
		assertTrue(out, out.contains("cannot write") && out.contains("VALBUM_EMBEDDER_DIR"));
	}

	private static String after(List<String> args, String option) {
		int index = args.indexOf(option);
		assertTrue("No " + option + " in " + args, index >= 0 && index + 1 < args.size());
		return args.get(index + 1);
	}

	private boolean noShell() {
		if (new File("/bin/sh").canExecute()) {
			return false;
		}
		System.err.println("SKIPPED " + getName() + ": no /bin/sh to run the package's scripts with.");
		return true;
	}

	private static void fake(Path bin, String name, String body) throws Exception {
		Path file = bin.resolve(name);
		Files.writeString(file, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
	}
}
