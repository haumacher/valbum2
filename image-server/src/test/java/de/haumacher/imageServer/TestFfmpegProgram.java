/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import junit.framework.TestCase;

/**
 * Test case that the FFmpeg program the bundled artifact ships actually runs here, see issue #81.
 *
 * <p>
 * It is the loudest check there is: loading the program pulls in every native library of the
 * artifact, and <code>libavdevice</code> links system libraries (ALSA, X11/xcb) that a bare
 * machine does not carry. Without this test a missing library surfaces far away, as a rendition
 * request answered <code>202</code> and a test saying "no rendition was made"; with it, the build
 * fails where the loader says which library is missing.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFfmpegProgram extends TestCase {

	public void testTheBundledProgramRuns() throws Exception {
		String executable = VideoRenditions.executable();
		assertNotNull("No FFmpeg program.", executable);

		ProcessBuilder builder = new ProcessBuilder(executable, "-hide_banner", "-version");
		builder.redirectErrorStream(true);
		Process process = builder.start();
		String output;
		try (InputStream in = process.getInputStream()) {
			output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		int status = process.waitFor();
		System.out.println("Bundled FFmpeg at " + executable + ":");
		System.out.println(output);
		assertEquals("The bundled FFmpeg does not run:\n" + output, 0, status);
		assertTrue("Unexpected version output:\n" + output, output.contains("ffmpeg version"));

		assertNull("Video renditions must be available here: " + VideoRenditions.unavailability(),
			VideoRenditions.unavailability());
		assertNotNull("No H.264 encoder in the bundled FFmpeg.", VideoRenditions.encoder());
	}

	/**
	 * The child process is told where the bundled libraries are, see issue #87: on ARM the program's
	 * <code>DT_RUNPATH</code> does not reach the libraries its own libraries need.
	 */
	public void testTheChildProcessFindsTheBundledLibraries() throws Exception {
		String executable = VideoRenditions.executable();
		String directory = new File(executable).getAbsoluteFile().getParent();

		ProcessBuilder builder = VideoRenditions.program(List.of(executable, "-version"));
		// The machine running the test may itself have a library path set; ours goes in front of it.
		assertEquals("The libraries beside the program must be found first.",
			VideoRenditions.libraryPath(directory, System.getenv(VideoRenditions.LIBRARY_PATH)),
			builder.environment().get(VideoRenditions.LIBRARY_PATH));

		// Whatever the machine already said stays, behind ours.
		assertEquals("The inherited library path must be kept, behind ours.",
			directory + File.pathSeparator + "/opt/lib",
			VideoRenditions.libraryPath(directory, "/opt/lib"));
		assertEquals("Nothing inherited, nothing appended.", directory,
			VideoRenditions.libraryPath(directory, null));
		assertEquals("An empty value is nothing.", directory, VideoRenditions.libraryPath(directory, ""));
	}

}
