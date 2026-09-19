/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import junit.framework.TestCase;

/**
 * Test case that the Debian package asks for every system library the bundled FFmpeg links, see
 * issue #87.
 *
 * <p>
 * The natives of <code>org.bytedeco:ffmpeg</code> link libraries that a headless Debian or
 * Raspberry Pi machine does not carry, above all <code>libavdevice</code>'s X11/xcb and ALSA. Where
 * they are missing the server starts and serves albums, photos, thumbnails and poster frames, but
 * every rendition request is refused. That is a silent hole in a package: nothing in the build
 * notices, and the first to notice is whoever installed it. So the libraries are derived here from
 * the artifacts themselves - every <code>DT_NEEDED</code> soname of every ELF, minus what the same
 * artifact ships beside it, minus the base system - and checked against
 * <code>src/deb/control/control</code>. An upgrade of the JavaCPP presets that links something new
 * then fails this test instead of the Pi.
 * </p>
 *
 * <p>
 * Why the libraries an artifact ships may be subtracted although the program looks some of them up
 * on the system path on ARM: {@link VideoRenditions#program(java.util.List)} hands the child
 * process an <code>LD_LIBRARY_PATH</code> pointing at the directory they were extracted to.
 * </p>
 *
 * <p>
 * One control file serves all three architectures although ALSA is shipped by the ARM artifacts and
 * only strictly external on amd64: one uniform line is simpler to read and to keep right than a
 * per-profile property, and it costs a Raspberry Pi one small package it very likely has anyway.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDebianPackageLibraries extends TestCase {

	/** The platform artifacts the Debian packages are built from, see the profiles in the POM. */
	private static final List<String> PACKAGED_PLATFORMS =
		List.of("linux-x86_64", "linux-arm64", "linux-armhf");

	/** What a Debian package may take for granted; no package declares these. */
	private static final List<Pattern> BASE_SYSTEM = List.of(
		Pattern.compile("libc\\.so\\..*"),
		Pattern.compile("libm\\.so\\..*"),
		Pattern.compile("libdl\\.so\\..*"),
		Pattern.compile("libpthread\\.so\\..*"),
		Pattern.compile("librt\\.so\\..*"),
		Pattern.compile("libgcc_s\\.so\\..*"),
		Pattern.compile("libstdc\\+\\+\\.so\\..*"),
		Pattern.compile("libz\\.so\\..*"),
		Pattern.compile("ld-linux.*"));

	/**
	 * Which Debian package provides which soname; alternatives separated by <code>|</code>, as the
	 * control file spells them.
	 */
	private static final Map<String, String> PROVIDERS = Map.of(
		"libxcb.so.1", "libxcb1",
		"libxcb-shm.so.0", "libxcb-shm0",
		"libxcb-shape.so.0", "libxcb-shape0",
		"libxcb-xfixes.so.0", "libxcb-xfixes0",
		"libasound.so.2", "libasound2t64|libasound2");

	private static final Pattern NATIVE_ENTRY =
		Pattern.compile("org/bytedeco/ffmpeg/(linux-[^/]+)/(.+)");

	private static final File CONTROL = new File("src/deb/control/control");

	/**
	 * Every library the bundled FFmpeg needs from the system is named in the package's
	 * <code>Recommends</code> (or <code>Depends</code>).
	 */
	public void testEveryExternalLibraryIsDeclared() throws Exception {
		Map<String, Set<String>> externals = externalLibraries();
		if (externals.isEmpty()) {
			System.out.println("No org.bytedeco FFmpeg platform artifact on the class path; nothing to check.");
			return;
		}
		assertTrue("A normal build has at least the linux-x86_64 natives on the class path, found: "
			+ externals.keySet(), externals.containsKey("linux-x86_64"));

		Map<String, String> control = control();
		Set<String> declared = new LinkedHashSet<>();
		declared.addAll(packages(control.get("Recommends")));
		declared.addAll(packages(control.get("Depends")));

		List<String> missing = new ArrayList<>();
		for (Map.Entry<String, Set<String>> platform : externals.entrySet()) {
			System.out.println("External libraries of " + platform.getKey() + ": "
				+ String.join(", ", platform.getValue()));
			for (String soname : platform.getValue()) {
				String provider = PROVIDERS.get(soname);
				if (provider == null) {
					missing.add(platform.getKey() + " needs '" + soname
						+ "', which no entry of the soname table of this test names."
						+ " Add it there and to 'Recommends' in " + CONTROL.getPath() + ".");
					continue;
				}
				boolean covered = false;
				for (String alternative : provider.split("\\|")) {
					covered |= declared.contains(alternative.trim());
				}
				if (!covered) {
					missing.add(platform.getKey() + " needs '" + soname + "', provided by '" + provider
						+ "', which " + CONTROL.getPath() + " does not name.");
				}
			}
		}
		assertEquals("The Debian package does not ask for every library the bundled FFmpeg links:\n"
			+ String.join("\n", missing), List.of(), missing);
	}

	/** The control file is well formed enough for jdeb to read it. */
	public void testControlFileIsWellFormed() throws Exception {
		assertTrue("Missing " + CONTROL.getAbsolutePath(), CONTROL.isFile());
		List<String> lines = Files.readAllLines(CONTROL.toPath(), StandardCharsets.UTF_8);
		Pattern field = Pattern.compile("[A-Za-z][A-Za-z0-9-]*: .*");
		int number = 0;
		for (String line : lines) {
			number++;
			assertFalse("Line " + number + " contains a tab: " + line, line.contains("\t"));
			if (line.isEmpty()) {
				continue;
			}
			if (line.startsWith(" ")) {
				assertFalse("Line " + number + " is a continuation of nothing.", number == 1);
				assertFalse("Line " + number + " starts with more than one blank: " + line,
					line.startsWith("  "));
				continue;
			}
			assertTrue("Line " + number + " is neither 'Field: value' nor a continuation: " + line,
				field.matcher(line).matches());
		}
		Map<String, String> control = control();
		assertNotNull("No Recommends field.", control.get("Recommends"));
		assertTrue("The description must say what the recommendation buys.",
			control.get("Description").contains("renditions"));
	}

	/** The fields of the control template, the <code>${...}</code> placeholders left alone. */
	private Map<String, String> control() throws IOException {
		Map<String, String> result = new LinkedHashMap<>();
		String name = null;
		StringBuilder value = new StringBuilder();
		for (String line : Files.readAllLines(CONTROL.toPath(), StandardCharsets.UTF_8)) {
			if (line.startsWith(" ") && name != null) {
				value.append('\n').append(line.substring(1));
				continue;
			}
			int colon = line.indexOf(':');
			if (colon < 0) {
				continue;
			}
			if (name != null) {
				result.put(name, value.toString());
			}
			name = line.substring(0, colon);
			value = new StringBuilder(line.substring(colon + 1).trim());
		}
		if (name != null) {
			result.put(name, value.toString());
		}
		return result;
	}

	/** The package names of a dependency field, every alternative of every relation on its own. */
	private static Set<String> packages(String field) {
		Set<String> result = new LinkedHashSet<>();
		if (field == null) {
			return result;
		}
		for (String relation : field.split(",")) {
			for (String alternative : relation.split("\\|")) {
				String name = alternative.trim();
				int version = name.indexOf('(');
				if (version >= 0) {
					name = name.substring(0, version).trim();
				}
				int architecture = name.indexOf('[');
				if (architecture >= 0) {
					name = name.substring(0, architecture).trim();
				}
				if (!name.isEmpty()) {
					result.add(name);
				}
			}
		}
		return result;
	}

	/**
	 * Per packaged platform, the sonames its ELF files ask for that neither the artifact itself nor
	 * the base system provides.
	 */
	private Map<String, Set<String>> externalLibraries() throws IOException {
		Map<String, Set<String>> result = new TreeMap<>();
		for (File jar : platformArtifacts()) {
			try (ZipFile zip = new ZipFile(jar)) {
				Map<String, Set<String>> shipped = new LinkedHashMap<>();
				Map<String, Set<String>> needed = new LinkedHashMap<>();
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					Matcher matcher = NATIVE_ENTRY.matcher(entry.getName());
					if (entry.isDirectory() || !matcher.matches()) {
						continue;
					}
					String platform = matcher.group(1);
					if (!PACKAGED_PLATFORMS.contains(platform)) {
						continue;
					}
					String file = new File(matcher.group(2)).getName();
					byte[] content;
					try (InputStream in = zip.getInputStream(entry)) {
						content = in.readAllBytes();
					}
					if (!Elf.isElf(content)) {
						continue;
					}
					shipped.computeIfAbsent(platform, key -> new LinkedHashSet<>()).add(file);
					needed.computeIfAbsent(platform, key -> new TreeSet<>()).addAll(Elf.needed(content));
				}
				for (Map.Entry<String, Set<String>> platform : needed.entrySet()) {
					Set<String> external =
						result.computeIfAbsent(platform.getKey(), key -> new TreeSet<>());
					for (String soname : platform.getValue()) {
						if (shipped.getOrDefault(platform.getKey(), Set.of()).contains(soname)) {
							continue;
						}
						if (BASE_SYSTEM.stream().anyMatch(pattern -> pattern.matcher(soname).matches())) {
							continue;
						}
						external.add(soname);
					}
				}
			}
		}
		return result;
	}

	/** The <code>org.bytedeco</code> FFmpeg platform artifacts on the test class path. */
	private static List<File> platformArtifacts() {
		List<File> result = new ArrayList<>();
		for (String element : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
			File file = new File(element);
			String name = file.getName();
			if (!name.startsWith("ffmpeg-") || !name.endsWith(".jar") || !name.contains("-linux-")) {
				continue;
			}
			if (file.isFile()) {
				result.add(file);
			}
		}
		result.sort((left, right) -> left.getName().compareTo(right.getName()));
		System.out.println("FFmpeg platform artifacts on the class path: "
			+ Arrays.toString(result.stream().map(File::getName).toArray()));
		return result;
	}

}
