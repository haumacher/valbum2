/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.FaceDetection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import junit.framework.TestCase;

/**
 * Test case that the face index loads no GUI library, see issue #182.
 *
 * <p>
 * {@link TestDebianPackageLibraries} reads the ELF headers of the artifacts and so says what a
 * library <em>would</em> need; it could not see that JavaCPP's preset loaded the GTK-linking
 * <code>highgui</code> beside the library that was checked. This test asks the process instead:
 * after {@link FaceDetection} has loaded and detected a face, which libraries are mapped
 * (<code>/proc/self/maps</code>). Every development machine and CI runner has GTK installed, so
 * only this question tells a headless machine's fate here.
 * </p>
 *
 * <p>
 * In a JVM of its own: surefire runs every test class of this module in one reused JVM, and a
 * library once mapped stays mapped, so the answer in the shared JVM would depend on which test ran
 * before. The child is started with this very JVM and class path and runs {@link #main(String[])}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFaceDetectionLoadsNoGui extends TestCase {

	/** What a headless machine does not have; a mapped path containing one of these fails. */
	static final List<String> GUI_LIBRARIES =
		List.of("libgtk", "libgdk", "libcairo", "libjniopencv_highgui", "libopencv_highgui");

	private static final String MAPPED = "mapped: ";

	private static final String FACES = "faces: ";

	private static final String UNAVAILABLE = "unavailable: ";

	private static final File PORTRAIT = new File("src/test/fixtures/faces/portrait-b.jpg");

	public void testTheFaceIndexMapsNoGuiLibrary() throws Exception {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")
			|| !Files.isReadable(Path.of("/proc/self/maps"))) {
			System.out.println("SKIPPING " + getName() + ": not Linux, there is no /proc/self/maps to ask ("
				+ System.getProperty("os.name") + ").");
			return;
		}

		String java = ProcessHandle.current().info().command().orElse(
			System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
		ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
			getClass().getName(), PORTRAIT.getAbsolutePath());
		builder.redirectErrorStream(true);
		Process child = builder.start();
		byte[] output = child.getInputStream().readAllBytes();
		assertTrue("The child JVM did not finish.", child.waitFor(5, TimeUnit.MINUTES));
		String text = new String(output, StandardCharsets.UTF_8);
		assertEquals("The child JVM failed:\n" + text, 0, child.exitValue());

		List<String> mapped = new ArrayList<>();
		String faces = null;
		for (String line : text.split("\n")) {
			if (line.startsWith(UNAVAILABLE)) {
				fail("Face detection does not load here: " + line.substring(UNAVAILABLE.length()));
			} else if (line.startsWith(MAPPED)) {
				mapped.add(line.substring(MAPPED.length()));
			} else if (line.startsWith(FACES)) {
				faces = line.substring(FACES.length());
			}
		}
		assertNotNull("The child printed no detection:\n" + text, faces);
		assertTrue("The detector ran in the child and found the face of " + PORTRAIT + ":\n" + text,
			Integer.parseInt(faces.trim()) > 0);
		assertTrue("The check reads the right thing: libopencv_java.so is mapped in the child:\n" + mapped,
			mapped.stream().anyMatch(path -> path.endsWith("/libopencv_java.so")));

		List<String> gui = new ArrayList<>();
		for (String path : mapped) {
			for (String library : GUI_LIBRARIES) {
				if (new File(path).getName().startsWith(library)) {
					gui.add(path);
				}
			}
		}
		assertEquals("Loading the face index mapped GUI libraries, which a headless machine does not have:\n"
			+ String.join("\n", gui), List.of(), gui);
	}

	/**
	 * The child: loads the face index, detects the given portrait and prints every mapped file.
	 */
	public static void main(String[] args) throws IOException {
		String unavailable = FaceDetection.unavailability();
		if (unavailable != null) {
			System.out.println(UNAVAILABLE + unavailable);
			return;
		}
		System.out.println(FACES + FaceDetection.detect(new File(args[0])).getFaces().size());
		TreeSet<String> paths = new TreeSet<>();
		for (String line : Files.readAllLines(Path.of("/proc/self/maps"), StandardCharsets.UTF_8)) {
			// address perms offset dev inode pathname
			String[] fields = line.trim().split("\\s+", 6);
			if (fields.length == 6 && fields[5].startsWith("/")) {
				paths.add(fields[5]);
			}
		}
		for (String path : paths) {
			System.out.println(MAPPED + path);
		}
	}
}
