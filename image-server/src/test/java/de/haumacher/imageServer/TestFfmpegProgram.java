/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

}
