/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.ServerEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Prepares the embedder of content search on this machine and measures it, see issue #257: the
 * very code path of the server (unpacking, Python environment, model download, the service as a
 * child process, the HTTP client), without a server.
 *
 * <pre>
 * java -cp /usr/share/valbum/valbum.jar de.haumacher.imageServer.content.EmbedderCheck \
 *     [--basepath /var/lib/valbum] [--precision int8] [picture.jpg ...]
 * </pre>
 *
 * <p>
 * Reads <code>VALBUM_EMBEDDER_*</code> like the server (run it as the server's user, with its
 * configuration), waits until the service is ready, then prints the time of a text query, of each
 * picture, and the memory of the service. It also prepares an offline machine: the files placed
 * by hand are checked here.
 * </p>
 */
public final class EmbedderCheck {

	private EmbedderCheck() {
		// Main class.
	}

	/** See {@link EmbedderCheck}. */
	public static void main(String[] args) throws Exception {
		Path basePath = Path.of(System.getenv().getOrDefault("VALBUM_BASEPATH", "."));
		String precision = null;
		List<Path> images = new ArrayList<>();
		for (int n = 0; n < args.length; n++) {
			switch (args[n]) {
				case "--basepath" -> basePath = Path.of(args[++n]);
				case "--precision" -> precision = args[++n];
				case "--help", "-h" -> {
					System.out.println("usage: EmbedderCheck [--basepath <library>] [--precision fp32|int8] [picture ...]");
					return;
				}
				default -> images.add(Path.of(args[n]));
			}
		}
		java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());
		if (precision != null) {
			env.put(EmbedderSettings.PRECISION, precision);
		}
		EmbedderSettings settings;
		try {
			settings = EmbedderSettings.read(env);
		} catch (ServerEnvironment.Invalid ex) {
			System.err.println("Cannot check: " + ex.getMessage());
			System.exit(1);
			return;
		}
		System.out.println(settings.report(basePath));
		int status;
		try (Embedder embedder = settings.create(basePath)) {
			status = check(embedder, images);
		}
		// After close(): the service is stopped with the check, not left behind.
		System.exit(status);
	}

	static int check(Embedder embedder, List<Path> images) throws InterruptedException, IOException {
		long start = System.nanoTime();
		String last = null;
		while (!embedder.awaitAvailable(Duration.ofSeconds(10))) {
			String problem = embedder.unavailability();
			if (problem == null) {
				break;
			}
			if (!Objects.equals(problem, last)) {
				System.out.println("  " + problem);
				last = problem;
			}
			if (problem.contains("cannot run") || problem.contains("cannot load")) {
				System.out.println("Not available.");
				return 1;
			}
		}
		System.out.printf("Ready after %.1f s: %s%n", seconds(start), embedder.stamp());

		String[] queries = { "flowers", "mountains in the evening", "Blumen im Regen", "a rainy street at night",
			"children playing" };
		embedder.embedText(queries[0], Embedder.TextKind.QUERY);
		start = System.nanoTime();
		int rounds = 4;
		for (int r = 0; r < rounds; r++) {
			for (String query : queries) {
				embedder.embedText(query, Embedder.TextKind.QUERY);
			}
		}
		System.out.printf("Text query: %.0f ms each (%d queries)%n", seconds(start) * 1000 / (rounds * queries.length),
			rounds * queries.length);

		if (!images.isEmpty()) {
			embedder.embedImage(Files.readAllBytes(images.get(0)));
			start = System.nanoTime();
			for (Path image : images) {
				long one = System.nanoTime();
				embedder.embedImage(Files.readAllBytes(image));
				System.out.printf("  %s: %.0f ms%n", image.getFileName(), seconds(one) * 1000);
			}
			System.out.printf("Picture: %.0f ms each (%d pictures)%n", seconds(start) * 1000 / images.size(),
				images.size());
		}
		if (embedder instanceof ManagedEmbedder managed && managed.getProcess() != null) {
			Path status = Path.of("/proc", String.valueOf(managed.getProcess().pid()), "status");
			if (Files.isReadable(status)) {
				for (String line : Files.readAllLines(status)) {
					if (line.startsWith("VmRSS") || line.startsWith("VmHWM")) {
						System.out.println("Service memory: " + line.replaceAll("\\s+", " "));
					}
				}
			}
		}
		return 0;
	}

	private static double seconds(long start) {
		return (System.nanoTime() - start) / 1e9;
	}
}
