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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The Debian package keeps <code>/etc/default/valbum</code> to root and the group
 * <code>valbum</code>, because it holds the mail password since issue #199.
 *
 * <p>
 * The package ships the file with mode 640 (root-owned: the group does not exist yet when it is
 * unpacked), and <code>postinst</code> hands it to the group <code>valbum</code> and takes the
 * world's read right away on every install and upgrade &mdash; an edited conffile, which dpkg keeps
 * with the mode it had, included &mdash; without touching what it says. The maintainer script is run
 * as shipped against fakes of the commands that change the system.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDebianConfiguration extends TestCase {

	private static final Path POSTINST = Path.of("src/deb/control/postinst");

	private static final Path POM = Path.of("pom.xml");

	private static final Path CONFFILES = Path.of("src/deb/control/conffiles");

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-postinst");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testThePackageShipsTheConfigurationUnreadableToTheWorld() throws Exception {
		String pom = Files.readString(POM, StandardCharsets.UTF_8);
		Matcher entry = Pattern.compile("<dst>/etc/default/valbum</dst>.*?<filemode>(\\d+)</filemode>", Pattern.DOTALL)
			.matcher(pom);
		assertTrue("No mapping for /etc/default/valbum.", entry.find());
		assertEquals("640", entry.group(1));
		assertTrue("A conffile, so that an edited one survives an upgrade.",
			Files.readAllLines(CONFFILES, StandardCharsets.UTF_8).contains("/etc/default/valbum"));
	}

	/** An upgrade over a world-readable file the administrator edited: the group and mode change, the text does not. */
	public void testPostinstHandsTheConfigurationToTheGroupKeepingItsText() throws Exception {
		if (!new File("/bin/sh").canExecute()) {
			System.err.println("SKIPPED " + getName() + ": no /bin/sh to run postinst with.");
			return;
		}
		Path config = _dir.resolve("valbum");
		String edited = "VALBUM_PORT=9999\nVALBUM_SMTP_PASSWORD='mine'\n";
		Files.writeString(config, edited, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));

		Path log = _dir.resolve("log");
		Path bin = Files.createDirectory(_dir.resolve("bin"));
		String logLine = "echo \"$(basename \"$0\") $*\" >> \"$FAKE_LOG\"\n";
		for (String command : new String[] { "addgroup", "adduser", "chown", "chgrp", "systemctl",
			"deb-systemd-helper", "deb-systemd-invoke" }) {
			fake(bin, command, logLine);
		}
		fake(bin, "getent", "exit 0\n");
		fake(bin, "stat", "echo valbum\n");
		// Only the file of this test is really changed; anything else (/var/lib/valbum) is logged.
		fake(bin, "chmod", logLine + "case $2 in " + _dir + "/*) exec /bin/chmod \"$@\";; esac\n");

		ProcessBuilder builder = new ProcessBuilder("/bin/sh", POSTINST.toString(), "configure", "2.9.0");
		Map<String, String> env = builder.environment();
		env.put("PATH", bin + ":/usr/bin:/bin");
		env.put("FAKE_LOG", log.toString());
		env.put("VALBUM_CONFIG", config.toString());
		Process process = builder.redirectErrorStream(true).start();
		String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(out, 0, process.waitFor());

		List<String> steps = new ArrayList<>(Files.readAllLines(log, StandardCharsets.UTF_8));
		assertTrue(steps.toString(), steps.contains("chgrp valbum " + config));
		assertTrue(steps.toString(), steps.contains("chmod 0640 " + config));
		assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)));
		assertEquals("The administrator's text is kept.", edited, Files.readString(config, StandardCharsets.UTF_8));
	}

	private static void fake(Path bin, String name, String body) throws Exception {
		Path file = bin.resolve(name);
		Files.writeString(file, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
	}
}
