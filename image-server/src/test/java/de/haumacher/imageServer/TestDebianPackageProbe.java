/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import junit.framework.TestCase;

/**
 * Probe for issue #87: the Debian control file stays a valid control file after filtering, and the
 * packages it recommends are the very ones the documentation tells the user to install by hand.
 */
@SuppressWarnings("javadoc")
public class TestDebianPackageProbe extends TestCase {

	private static final Path CONTROL = Path.of("src/deb/control/control");

	private static final Path README = Path.of("../README.md");

	private static final Path RELEASE = Path.of("../RELEASE.md");

	/** A Debian package name, see Debian policy 5.6.1. */
	private static final Pattern PACKAGE_NAME = Pattern.compile("[a-z0-9][a-z0-9+.-]+");

	/**
	 * The filtered control file (placeholders replaced as the Maven build does) obeys the control
	 * file syntax: mandatory fields, unique fields, valid package names in every relationship
	 * field, and a description whose lines fit the policy's width.
	 */
	public void testFilteredControlIsWellFormed() throws IOException {
		String filtered = read(CONTROL).replace("${deb.version}", "2.3.0").replace("${deb.arch}", "arm64");
		assertFalse("A placeholder the build does not fill: " + filtered, filtered.contains("${"));

		Map<String, String> fields = fields(filtered);
		for (String mandatory : List.of("Package", "Version", "Architecture", "Maintainer", "Description")) {
			assertTrue("Mandatory field missing: " + mandatory, fields.containsKey(mandatory));
		}
		for (String relationship : List.of("Depends", "Recommends", "Suggests")) {
			for (String name : packages(fields.get(relationship))) {
				assertTrue(relationship + " names an invalid package: '" + name + "'", PACKAGE_NAME.matcher(name).matches());
			}
		}

		String[] description = fields.get("Description").split("\n");
		assertTrue("The synopsis is too long: " + description[0], description[0].length() <= 80);
		for (int n = 1; n < description.length; n++) {
			String line = description[n];
			assertTrue("A description line must be indented by one blank: '" + line + "'", line.startsWith(" "));
			assertTrue("A description line is too long: '" + line + "'", line.length() <= 80);
			assertFalse("Trailing blank in the description: '" + line + "'", line.endsWith(" ") && !line.equals(" ."));
			if (line.trim().equals(".")) {
				assertEquals("A paragraph separator is exactly ' .'", " .", line);
			}
		}
		assertTrue("The description must mention the renditions the recommendation buys.",
			fields.get("Description").contains("rendition"));
	}

	/**
	 * The recommended packages, the README's remedy command (English and German) and the RELEASE.md
	 * troubleshooting row must name the same packages, or the documentation sends the user after
	 * the wrong ones.
	 */
	public void testDocumentationNamesTheRecommendedPackages() throws IOException {
		Map<String, String> fields = fields(read(CONTROL));
		TreeSet<String> recommended = new TreeSet<>();
		for (String alternatives : fields.get("Recommends").split(",")) {
			// The first alternative is the current name, the others are older ones.
			recommended.add(packages(alternatives).get(0));
		}
		assertTrue("The xcb libraries the bundled libavdevice links must be recommended: " + recommended,
			recommended.containsAll(List.of("libxcb1", "libxcb-shm0", "libxcb-shape0", "libxcb-xfixes0")));

		Pattern remedy = Pattern.compile("sudo apt install ((?:lib[a-z0-9+.-]+ ?)+)");
		for (Path doc : List.of(README, RELEASE)) {
			String text = read(doc);
			Matcher m = remedy.matcher(text);
			int found = 0;
			while (m.find()) {
				found++;
				TreeSet<String> named = new TreeSet<>(List.of(m.group(1).trim().split(" +")));
				assertEquals(doc + " tells the user to install other packages than the control file recommends.",
					recommended, named);
			}
			assertTrue(doc + " must contain the remedy command at least once.", found >= 1);
		}
		// Both languages of the README carry it.
		Matcher m = remedy.matcher(read(README));
		int count = 0;
		while (m.find()) {
			count++;
		}
		assertTrue("The README names the remedy in English and in German.", count >= 2);
	}

	/**
	 * The library path handed to the child never contains an empty element (an empty element means
	 * the working directory to the loader) and puts the program's own directory first even when the
	 * environment already points somewhere else, with a directory containing blanks left as it is.
	 */
	public void testLibraryPathNeverNamesTheWorkingDirectory() {
		String directory = "/opt/java cpp/cache/ffmpeg-linux-arm64.jar/org/bytedeco/ffmpeg/linux-arm64";
		for (String inherited : new String[] { null, "", "/usr/local/lib", "/a:/b" }) {
			String path = VideoRenditions.libraryPath(directory, inherited);
			assertTrue("Ours first: " + path, path.startsWith(directory));
			for (String element : path.split(File.pathSeparator, -1)) {
				assertFalse("An empty element (the working directory) in '" + path + "'", element.isEmpty());
			}
			if (inherited != null && !inherited.isEmpty()) {
				assertTrue("The inherited path is kept: " + path, path.endsWith(File.pathSeparator + inherited));
			}
		}

		// A relative program name has no directory of its own: the working directory must not be
		// added silently, the builder is simply left without a library path of ours.
		ProcessBuilder builder = VideoRenditions.program(List.of("ffmpeg", "-version"));
		String path = builder.environment().get(VideoRenditions.LIBRARY_PATH);
		String cwd = new File("").getAbsolutePath();
		assertTrue("A bare program name must not put the working directory on the library path: " + path,
			path == null || !path.startsWith(cwd));
	}

	private static String read(Path path) throws IOException {
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	/** The fields of a control paragraph, continuation lines joined with a newline. */
	private static Map<String, String> fields(String control) {
		Map<String, String> result = new LinkedHashMap<>();
		String current = null;
		for (String line : control.split("\n")) {
			if (line.isEmpty()) {
				continue;
			}
			if (line.startsWith(" ") || line.startsWith("\t")) {
				assertNotNull("A continuation line before the first field: " + line, current);
				result.put(current, result.get(current) + "\n" + line);
			} else {
				int colon = line.indexOf(':');
				assertTrue("Not a field: " + line, colon > 0);
				current = line.substring(0, colon);
				assertFalse("Duplicate field: " + current, result.containsKey(current));
				result.put(current, line.substring(colon + 1).trim());
			}
		}
		return result;
	}

	/** The package names in a relationship field, version constraints and alternatives stripped. */
	private static List<String> packages(String relationship) {
		List<String> result = new ArrayList<>();
		if (relationship == null) {
			return result;
		}
		for (String part : relationship.split("[,|]")) {
			String name = part.trim().replaceAll("\\s*\\(.*\\)$", "");
			if (!name.isEmpty()) {
				result.add(name);
			}
		}
		return result;
	}

}
