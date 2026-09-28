/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import junit.framework.TestCase;

/**
 * Test case that the container image of issue #174 installs every system library the Debian
 * package recommends, without needing Docker.
 *
 * <p>
 * The chain is two links long. {@link TestDebianPackageLibraries} derives from the ELF headers of
 * the bundled artifacts which system libraries FFmpeg and the face index link, and holds the
 * package's <code>Recommends</code> to them. This test holds the <code>apt-get install</code> of the
 * image's runtime stage to that same field: a library the presets start linking then fails the
 * Debian test, and the line added to the control file fails this one until the image installs it
 * too. The image itself is checked for real by its <code>check</code> target
 * (<code>src/docker/check-libraries</code>, run by the CI's Docker job), which resolves the
 * natives with <code>ldd</code> inside the built image.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDockerImageLibraries extends TestCase {

	private static final File DOCKERFILE = new File("src/docker/Dockerfile");

	private static final File DOCKERIGNORE = new File("src/docker/Dockerfile.dockerignore");

	private static final File CONTROL = new File("src/deb/control/control");

	/** The build context of the image, see the head of the Dockerfile. */
	private static final File CONTEXT = new File("src");

	/**
	 * For every relation of the package's <code>Recommends</code> the image installs one of its
	 * alternatives.
	 */
	public void testTheImageInstallsWhatThePackageRecommends() throws Exception {
		Set<String> installed = runtimePackages();
		assertFalse("The runtime stage of " + DOCKERFILE + " installs nothing.", installed.isEmpty());

		List<String> missing = new ArrayList<>();
		for (List<String> relation : relations(field(CONTROL, "Recommends"))) {
			boolean covered = false;
			for (String alternative : relation) {
				covered |= installed.contains(alternative);
			}
			if (!covered) {
				missing.add(String.join(" | ", relation));
			}
		}
		assertEquals("The image does not install what " + CONTROL + " recommends; add one alternative of each to the"
			+ " apt-get line of the runtime stage in " + DOCKERFILE + " (installed: " + installed + ").",
			List.of(), missing);
	}

	/** Every file the Dockerfile copies from the build context is admitted to it. */
	public void testTheContextAdmitsEveryCopiedFile() throws Exception {
		Set<String> admitted = new LinkedHashSet<>();
		for (String line : Files.readAllLines(DOCKERIGNORE.toPath(), StandardCharsets.UTF_8)) {
			if (line.startsWith("!")) {
				admitted.add(line.substring(1).trim());
			}
		}
		Pattern copy = Pattern.compile("^COPY\\s+(?:--chmod=\\S+\\s+)?(?!--from)(\\S+)\\s+\\S+\\s*$");
		int copied = 0;
		for (String line : Files.readAllLines(DOCKERFILE.toPath(), StandardCharsets.UTF_8)) {
			Matcher matcher = copy.matcher(line.trim());
			if (!matcher.matches()) {
				continue;
			}
			String source = matcher.group(1);
			copied++;
			assertTrue(DOCKERFILE + " copies '" + source + "', which " + DOCKERIGNORE + " does not admit.",
				admitted.contains(source));
			assertTrue(DOCKERFILE + " copies '" + source + "', which is not in " + CONTEXT.getPath() + ".",
				new File(CONTEXT, source).isFile());
		}
		assertTrue("No COPY from the build context found in " + DOCKERFILE, copied > 0);
	}

	/** The packages of the <code>apt-get install</code> in the stage named <code>runtime</code>. */
	private static Set<String> runtimePackages() throws IOException {
		String text = String.join("\n", Files.readAllLines(DOCKERFILE.toPath(), StandardCharsets.UTF_8));
		Matcher stage = Pattern.compile("(?m)^FROM\\s+\\S+\\s+AS\\s+runtime\\s*$").matcher(text);
		assertTrue("No stage 'runtime' in " + DOCKERFILE, stage.find());
		int next = text.indexOf("\nFROM ", stage.end());
		String body = text.substring(stage.end(), next < 0 ? text.length() : next).replace("\\\n", " ");

		Set<String> result = new LinkedHashSet<>();
		Matcher install = Pattern.compile("apt-get install ([^&\\n]*)").matcher(body);
		while (install.find()) {
			for (String word : install.group(1).trim().split("\\s+")) {
				if (!word.isEmpty() && !word.startsWith("-")) {
					result.add(word);
				}
			}
		}
		return result;
	}

	/** The value of one field of a control file, continuation lines joined. */
	private static String field(File control, String name) throws IOException {
		StringBuilder value = null;
		for (String line : Files.readAllLines(control.toPath(), StandardCharsets.UTF_8)) {
			if (value != null) {
				if (line.startsWith(" ")) {
					value.append(' ').append(line.trim());
					continue;
				}
				break;
			}
			if (line.startsWith(name + ":")) {
				value = new StringBuilder(line.substring(name.length() + 1).trim());
			}
		}
		assertNotNull("No field '" + name + "' in " + control, value);
		return value.toString();
	}

	/** The relations of a dependency field, each the list of its alternatives, versions dropped. */
	private static List<List<String>> relations(String field) {
		List<List<String>> result = new ArrayList<>();
		for (String relation : field.split(",")) {
			List<String> alternatives = new ArrayList<>();
			for (String alternative : relation.split("\\|")) {
				String name = alternative.replaceAll("\\(.*?\\)|\\[.*?\\]", "").trim();
				if (!name.isEmpty()) {
					alternatives.add(name);
				}
			}
			if (!alternatives.isEmpty()) {
				result.add(alternatives);
			}
		}
		return result;
	}
}
