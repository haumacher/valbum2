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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for <code>src/deb/valbum-admin</code>, the front of the server's one-time jobs (issue
 * #180), on the Debian package's backend.
 *
 * <p>
 * The script is run as it is shipped, through the shipped <code>valbum-server</code> and
 * configuration, with fakes on the <code>PATH</code>: <code>systemctl</code> keeps the state of the
 * service in a file, <code>id</code> says who is calling, <code>runuser</code> runs what it is given,
 * and <code>java</code> answers <code>--list-jobs</code> with the real {@link Main} of this build -
 * so the table of jobs is the one the jar declares - and otherwise records what a job would have
 * been started with. Everything the fakes are asked is written, in order, to one log.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestValbumAdmin extends TestCase {

	private static final Path SCRIPT = Path.of("src/deb/valbum-admin");

	private static final Path SERVER = Path.of("src/deb/valbum-server");

	private static final Path DEFAULTS = Path.of("src/deb/valbum.default");

	private Path _dir;

	private Path _log;

	private Path _state;

	private final Map<String, String> _env = new HashMap<>();

	private String _out;

	private String _err;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-admin");
		_log = _dir.resolve("log");
		_state = _dir.resolve("service-state");
		Files.writeString(_state, "active\n", StandardCharsets.UTF_8);
		Files.createDirectory(_dir.resolve("library"));
		Files.writeString(_dir.resolve("valbum.jar"), "not a jar", StandardCharsets.UTF_8);
		Files.copy(DEFAULTS, _dir.resolve("valbum"));
		Path bin = Files.createDirectory(_dir.resolve("bin"));
		String realJava = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		fake(bin, "java", "if [ \"$1\" = -version ]; then echo 'openjdk version \"21.0.4\" 2024-07-16' >&2; exit 0; fi\n"
			+ "for arg in \"$@\"; do\n"
			+ "  if [ \"$arg\" = --list-jobs ]; then\n"
			+ "    while [ \"$1\" != -jar ]; do shift; done; shift 2\n"
			+ "    exec " + quote(realJava) + " -cp " + quote(System.getProperty("java.class.path"))
			+ " de.haumacher.imageServer.Main \"$@\"\n"
			+ "  fi\n"
			+ "done\n"
			+ "{ printf 'java'; printf '|%s' \"$@\"; echo; } >> \"$FAKE_LOG\"\n"
			+ "exit \"${FAKE_JAVA_EXIT:-0}\"\n");
		fake(bin, "id", "if [ \"$1\" = -u ]; then echo \"${FAKE_UID:-0}\"; else exec /usr/bin/id \"$@\"; fi\n");
		fake(bin, "systemctl", "echo \"systemctl $*\" >> \"$FAKE_LOG\"\n"
			+ "case $1 in\n"
			+ "  is-active) state=$(cat \"$FAKE_STATE\"); echo \"$state\"; [ \"$state\" = active ];;\n"
			+ "  stop) [ -z \"${FAKE_STOP_FAILS:-}\" ] || exit 1; echo inactive > \"$FAKE_STATE\";;\n"
			+ "  start) echo active > \"$FAKE_STATE\";;\n"
			+ "esac\n");
		fake(bin, "runuser", "echo \"runuser $1 $2\" >> \"$FAKE_LOG\"\n"
			+ "while [ \"$1\" != -- ]; do shift; done; shift\n"
			+ "exec \"$@\"\n");

		_env.put("PATH", bin + ":/usr/bin:/bin");
		_env.put("FAKE_LOG", _log.toString());
		_env.put("FAKE_STATE", _state.toString());
		_env.put("VALBUM_SERVER", SERVER.toAbsolutePath().toString());
		_env.put("VALBUM_CONFIG", _dir.resolve("valbum").toString());
		_env.put("VALBUM_JAR", _dir.resolve("valbum.jar").toString());
		_env.put("VALBUM_BASEPATH", _dir.resolve("library").toString());
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testStopsRunsTheJobAsTheServiceUserAndStartsAgain() throws Exception {
		if (noShell()) {
			return;
		}
		assertEquals(_err, 0, run("create-space", "family", "--name", "The Family", "--anonymous", "public"));
		List<String> log = steps();
		assertEquals(Arrays.asList("systemctl stop valbum.service", "runuser -u valbum", "java", "systemctl start valbum.service"),
			log);
		String job = job();
		assertTrue("The configuration comes first: " + job, job.contains("|--basepath|" + _dir.resolve("library")));
		assertTrue("The job with its options, the short name turned into the server's flag: " + job,
			job.endsWith("|--create-space|family|--space-name|The Family|--anonymous|public"));
		assertEquals("One line per step: " + _out, 4, _out.lines().count());
	}

	public void testTheServersOwnFlagIsAcceptedAsWell() throws Exception {
		if (noShell()) {
			return;
		}
		assertEquals(_err, 0, run("create-space", "--space-name=The Family", "family"));
		assertTrue(job(), job().endsWith("|--create-space|family|--space-name|The Family"));
	}

	public void testAServiceThatWasNotRunningIsNotStarted() throws Exception {
		if (noShell()) {
			return;
		}
		Files.writeString(_state, "inactive\n", StandardCharsets.UTF_8);
		assertEquals(_err, 0, run("migrate-to-spaces"));
		assertEquals(Arrays.asList("runuser -u valbum", "java"), steps());
		assertTrue(job(), job().endsWith("|--migrate-to-spaces"));
		assertTrue(_out, _out.contains("stays stopped"));
	}

	public void testNoRestartLeavesTheServiceStopped() throws Exception {
		if (noShell()) {
			return;
		}
		assertEquals(_err, 0, run("replace-originals", "/srv/originals", "--dry-run", "--no-restart"));
		assertEquals(Arrays.asList("systemctl stop valbum.service", "runuser -u valbum", "java"), steps());
		assertTrue(job(), job().endsWith("|--replace-originals|/srv/originals|--dry-run"));
		assertTrue(_out, _out.contains("--no-restart"));
	}

	public void testTheJobsExitCodeIsPassedOn() throws Exception {
		if (noShell()) {
			return;
		}
		_env.put("FAKE_JAVA_EXIT", "3");
		assertEquals(3, run("migrate-to-user", "anna"));
		assertEquals("The service is started again all the same.",
			Arrays.asList("systemctl stop valbum.service", "runuser -u valbum", "java", "systemctl start valbum.service"),
			steps());
		assertTrue(_out, _out.contains("exit code 3"));
	}

	public void testSomebodyElseThanRootIsRefused() throws Exception {
		if (noShell()) {
			return;
		}
		_env.put("FAKE_UID", "1000");
		assertEquals(1, run("create-space", "family"));
		assertEquals(List.of(), steps());
		assertTrue(_err, _err.contains("sudo valbum-admin"));
	}

	public void testAFailedStopRunsNothing() throws Exception {
		if (noShell()) {
			return;
		}
		_env.put("FAKE_STOP_FAILS", "1");
		assertEquals(1, run("create-space", "family"));
		assertEquals(Arrays.asList("systemctl stop valbum.service"), steps());
		assertTrue(_err, _err.contains("could not stop valbum.service; nothing was run"));
	}

	public void testAMistakeIsRefusedBeforeTheServiceIsStopped() throws Exception {
		if (noShell()) {
			return;
		}
		assertEquals(2, run("create-spaces", "family"));
		assertEquals(2, run("create-space", "family", "--colour", "blue"));
		assertEquals(2, run("create-space"));
		assertEquals(2, run("migrate-to-spaces", "family"));
		assertEquals(2, run("create-space", "family", "--name"));
		assertEquals(List.of(), steps());
	}

	public void testHelpListsExactlyTheJobsOfTheJar() throws Exception {
		if (noShell()) {
			return;
		}
		assertEquals(_err, 0, run("help"));
		List<String> offered = new ArrayList<>();
		boolean commands = false;
		for (String line : _out.lines().toList()) {
			if (line.equals("Commands:")) {
				commands = true;
			} else if (commands && line.matches("  \\S.*")) {
				offered.add(line.trim().split(" +")[0]);
			} else if (commands && line.startsWith("   ")) {
				// The summary of the command above, wrapped.
			} else if (commands) {
				break;
			}
		}
		assertEquals(Jobs.ALL.stream().map(Jobs.Job::name).toList(), offered);
		assertFalse("help needs no root and stops nothing.", Files.exists(_log));

		for (Jobs.Job job : Jobs.ALL) {
			assertEquals(_err, 0, run("help", job.name()));
			assertTrue(_out, _out.startsWith("Usage: valbum-admin " + job.name()));
			for (Jobs.Option option : job.options()) {
				assertTrue(option.name() + " in: " + _out, _out.contains("\n  " + option.name()));
			}
			assertTrue(_out, _out.contains("--no-restart"));
		}
		assertEquals(2, run("help", "frobnicate"));
	}

	/** The one table that gives an option a short name: every short name belongs to one flag of its job. */
	public void testTheShortNamesAreUnambiguous() {
		for (Jobs.Job job : Jobs.ALL) {
			for (Jobs.Option option : job.options()) {
				for (Jobs.Option other : job.options()) {
					if (other != option) {
						assertFalse(job.name() + ": " + option.name(),
							List.of(other.name(), other.flag()).contains(option.name())
								|| List.of(other.name(), other.flag()).contains(option.flag()));
					}
				}
			}
		}
	}

	private static void fake(Path bin, String name, String body) throws Exception {
		Path file = bin.resolve(name);
		Files.writeString(file, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
	}

	private static String quote(String value) {
		return "'" + value.replace("'", "'\\''") + "'";
	}

	/** Whether this machine cannot run the script; said once per test, never passed silently. */
	private boolean noShell() {
		if (new File("/bin/bash").canExecute() && new File("/bin/sh").canExecute()) {
			return false;
		}
		System.err.println("SKIPPED " + getName() + ": no /bin/bash to run valbum-admin with.");
		return true;
	}

	/** The steps that change something, in order: <code>is-active</code> only asks. */
	private List<String> steps() throws Exception {
		if (!Files.exists(_log)) {
			return List.of();
		}
		List<String> steps = new ArrayList<>();
		for (String line : Files.readAllLines(_log, StandardCharsets.UTF_8)) {
			if (!line.startsWith("systemctl is-active")) {
				steps.add(line.startsWith("java|") ? "java" : line);
			}
		}
		return steps;
	}

	/** The arguments the job was started with, each behind a bar. */
	private String job() throws Exception {
		return Files.readAllLines(_log, StandardCharsets.UTF_8).stream().filter(line -> line.startsWith("java|"))
			.findFirst().orElseThrow();
	}

	private int run(String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of("/bin/bash", SCRIPT.toString()));
		command.addAll(Arrays.asList(args));
		ProcessBuilder builder = new ProcessBuilder(command);
		Map<String, String> environment = builder.environment();
		environment.keySet().removeIf(name -> name.startsWith("VALBUM_") || name.startsWith("JAVA_"));
		environment.putAll(_env);
		Process process = builder.start();
		_out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		_err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		return process.waitFor();
	}
}
