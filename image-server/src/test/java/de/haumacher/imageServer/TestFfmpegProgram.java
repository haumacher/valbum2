/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

		// As the server runs it: the library path and the soname links of issues #87 and #210.
		ProcessBuilder builder = VideoRenditions.program(List.of(executable, "-hide_banner", "-version"));
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

	/**
	 * Every library beside the program that is stored under another name than its soname is
	 * reachable by its soname, see issue #210: the <code>linux-x86_64</code> artifact of the
	 * presets 1.5.9 ships <code>libva.so</code>, which <code>libavutil</code> links as
	 * <code>libva.so.2</code>, and a child process looks for a file of that name.
	 */
	public void testTheBundledLibrariesAreReachableByTheirSonames() throws Exception {
		String executable = VideoRenditions.executable();
		File directory = new File(executable).getAbsoluteFile().getParentFile();
		VideoRenditions.program(List.of(executable, "-version"));

		int renamed = 0;
		for (File file : directory.listFiles()) {
			String name = file.getName();
			if (!name.startsWith("lib") || !name.endsWith(".so")
				|| !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			byte[] content = Files.readAllBytes(file.toPath());
			String soname = Elf.isElf(content) ? Elf.soname(content) : null;
			if (soname == null || soname.equals(name)) {
				continue;
			}
			renamed++;
			assertTrue("'" + name + "' is linked as '" + soname + "', which is not beside the program.",
				new File(directory, soname).exists());
		}
		System.out.println("Libraries beside the program reachable by their soname only through a link: " + renamed);
	}

	/**
	 * OpenH264 is asked for a slice per thread, four at most, the only way it encodes in parallel since FFmpeg
	 * 6.0 (issue #210); x264 threads by frames and is left alone.
	 */
	public void testOpenH264GetsASlicePerThread() {
		assertEquals(List.of("-slices", "2"), VideoRenditions.slices("libopenh264", 2));
		assertEquals(List.of("-slices", "4"), VideoRenditions.slices("libopenh264", 4));
		assertEquals("Never more than four.", List.of("-slices", "4"), VideoRenditions.slices("libopenh264", 8));
		assertEquals(List.of(), VideoRenditions.slices("libopenh264", 1));
		assertEquals(List.of(), VideoRenditions.slices("libx264", 4));
	}

	/** {@link VideoRenditions#linkSonames(File)} links once and never replaces a file. */
	public void testASonameLinkIsMadeOnceAndReplacesNothing() throws Exception {
		File directory = new File(VideoRenditions.executable()).getAbsoluteFile().getParentFile();
		File libva = new File(directory, "libva.so");
		if (!Files.isRegularFile(libva.toPath(), LinkOption.NOFOLLOW_LINKS)) {
			System.out.println("No libva.so beside the program on this platform; nothing to link.");
			return;
		}
		Path scratch = Files.createTempDirectory("sonames");
		try {
			Files.copy(libva.toPath(), scratch.resolve("libva.so"));
			assertEquals(List.of("libva.so.2"), VideoRenditions.linkSonames(scratch.toFile()));
			Path link = scratch.resolve("libva.so.2");
			assertTrue(Files.isSymbolicLink(link));
			assertEquals("libva.so", Files.readSymbolicLink(link).toString());
			assertEquals("Once per directory.", List.of(), VideoRenditions.linkSonames(scratch.toFile()));

			Path other = Files.createTempDirectory("sonames");
			try {
				Files.copy(libva.toPath(), other.resolve("libva.so"));
				Files.writeString(other.resolve("libva.so.2"), "mine");
				assertEquals("A file of the soname's name stays.", List.of(),
					VideoRenditions.linkSonames(other.toFile()));
				assertEquals("mine", Files.readString(other.resolve("libva.so.2")));
			} finally {
				delete(other);
			}
		} finally {
			delete(scratch);
		}
	}

	private static void delete(Path directory) throws Exception {
		try (var files = Files.list(directory)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				Files.delete(file);
			}
		}
		Files.delete(directory);
	}

}
