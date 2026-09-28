/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.SpaceStore;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.ToIntFunction;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * The one-time jobs of the server: the flags that do one thing to the library and exit instead of
 * starting the server (issue #180).
 *
 * <p>
 * This table is the only place a job is declared. {@link Main} registers the server's flags from
 * it, and <code>--list-jobs</code> prints it for <code>valbum-admin</code> (the front of the Debian
 * package and the container image), which offers every job listed as a command named after its
 * flag without the dashes and takes its help text from here - so the two cannot drift apart.
 * </p>
 *
 * <p>
 * An option of a job may carry a <em>short name</em>, the name <code>valbum-admin</code> shows and
 * accepts beside the server's own flag; the server itself knows only the flag. The mapping is:
 * </p>
 *
 * <table>
 * <tr><th>valbum-admin</th><th>server flag</th></tr>
 * <tr><td><code>create-space --name</code></td><td><code>--space-name</code></td></tr>
 * </table>
 *
 * <p>
 * The listing is one record per line, the fields separated by a tab, never containing a tab or a
 * line break:
 * </p>
 *
 * <pre>
 * valbum-jobs  1
 * job     &lt;command&gt;  &lt;flag&gt;  &lt;argument or empty&gt;  &lt;one sentence&gt;  &lt;description&gt;
 * option  &lt;command&gt;  &lt;name&gt;  &lt;flag&gt;  &lt;value or empty for a switch&gt;  &lt;help&gt;
 * </pre>
 */
public final class Jobs {

	/** The first line of {@link #list(PrintStream)}, naming the format and its version. */
	public static final String HEADER = "valbum-jobs\t1";

	/**
	 * An option of a job.
	 *
	 * @param flag
	 *        The server's flag, e.g. <code>--space-name</code>.
	 * @param shortName
	 *        What <code>valbum-admin</code> calls it, <code>null</code> for the flag itself.
	 * @param metavar
	 *        The value's placeholder, <code>null</code> for a switch without a value.
	 * @param choices
	 *        The values allowed, empty for any.
	 * @param help
	 *        What it does, without naming the job it belongs to.
	 */
	public record Option(String flag, String shortName, String metavar, List<String> choices, String help) {

		/** The name <code>valbum-admin</code> offers. */
		public String name() {
			return shortName == null ? flag : shortName;
		}

		/** The key of the option in a parsed {@link Namespace}. */
		String dest() {
			return flag.substring(2).replace('-', '_');
		}

		String value() {
			if (!choices.isEmpty()) {
				return "{" + String.join(",", choices) + "}";
			}
			return metavar == null ? "" : metavar;
		}
	}

	/**
	 * A one-time job.
	 *
	 * @param name
	 *        The command, the flag without its dashes.
	 * @param metavar
	 *        The placeholder of the job's argument, <code>null</code> for a job that takes none.
	 * @param summary
	 *        One sentence, for the list of commands.
	 * @param help
	 *        The full description.
	 * @param options
	 *        The options that apply to this job alone.
	 * @param run
	 *        Runs the job on the parsed arguments and answers the process exit code.
	 */
	public record Job(String name, String metavar, String summary, String help, List<Option> options,
			ToIntFunction<Namespace> run) {

		/** The server's flag. */
		public String flag() {
			return "--" + name;
		}

		/** The key of the job's flag in a parsed {@link Namespace}. */
		String dest() {
			return name.replace('-', '_');
		}

		/** Whether the parsed arguments ask for this job. */
		boolean requested(Namespace ns) {
			Object value = ns.get(dest());
			return metavar == null ? Boolean.TRUE.equals(value) : value != null;
		}
	}

	/**
	 * Every one-time job, in the order {@link Main} checks for them: where several are given, the
	 * first one runs, as it always did.
	 */
	public static final List<Job> ALL = List.of(
		new Job("migrate-to-user", "NAME",
			"Move the albums of the base folder into a folder named after the library owner.",
			"Move the albums at the base folder into a folder of that name and make it the library "
				+ "owner's space (issue #45). A one-time, explicit rename-only move; the server is "
				+ "not started afterwards",
			List.of(),
			ns -> Main.migrateLibrary(Main.basePath(ns), ns.getString("migrate_to_user"))),
		new Job("migrate-to-spaces", null,
			"Turn a library migrated per user into a server of several spaces.",
			"Turn a library migrated per user (--migrate-to-user) into a multi-space server: "
				+ "every user folder becomes a space with that user as its admin, and what the space "
				+ "model cannot represent is moved aside and reported. A one-time, explicit, "
				+ "rename-only step; the server is not started afterwards",
			List.of(),
			ns -> Main.migrateToSpaces(Main.basePath(ns))),
		new Job("replace-originals", "FOLDER",
			"Put downloaded originals in the place of the copies a phone uploaded without their position.",
			"Put the originals in the given folder in the place of the redacted copies a phone "
				+ "uploaded (issue #167): every file whose name the library holds exactly once and "
				+ "whose picture (JPEG scan data) or video (media data) is the same is moved into its "
				+ "album, the redacted copy set aside in <space>/.valbum/replaced/<timestamp>/, and "
				+ "the missing position and camera filled in. Run it with the server stopped; the "
				+ "server is not started afterwards",
			List.of(new Option("--dry-run", null, null, List.of(),
				"Print what would be replaced and skipped, and touch nothing")),
			ns -> Main.replaceOriginals(Main.basePath(ns), Path.of(ns.getString("replace_originals")),
				Main.spaceMode(ns), Boolean.TRUE.equals(ns.getBoolean("dry_run")))),
		new Job("create-space", "FOLDER",
			"Make a folder directly below the base folder a space of its own.",
			"Make the folder of that name directly below the base folder a space (issue #175): write "
				+ "its .valbum/space.json, creating the folder if it is missing; an existing folder "
				+ "becomes the space with its albums. Refused on a base folder that is a single-space "
				+ "library with albums of its own. The server is not started; its next start prints "
				+ "the sign-in code for the new space's administrator",
			List.of(
				new Option("--space-name", "--name", "NAME", List.of(),
					"The name to show for the space (the folder name otherwise)"),
				new Option("--anonymous", null, null,
					List.of(SpaceStore.ANONYMOUS_NONE, SpaceStore.ANONYMOUS_PUBLIC),
					"Whether visitors who are not signed in see the public photos of the space "
						+ "('public') or nothing ('none', the default)"),
				new Option("--faces", null, null, List.of(SpaceStore.FACES_ON, SpaceStore.FACES_OFF),
					"Whether the server looks for faces in the photos of the space (issue #124); "
						+ "'off' is the default")),
			ns -> Main.createSpace(Main.basePath(ns), ns.getString("create_space"), ns.getString("space_name"),
				orDefault(ns.getString("anonymous"), SpaceStore.ANONYMOUS_NONE),
				orDefault(ns.getString("faces"), SpaceStore.FACES_OFF), Main.spaceMode(ns))));

	private Jobs() {
		// Only the table.
	}

	private static String orDefault(String value, String fallback) {
		return value == null ? fallback : value;
	}

	/** Registers the flags of every job and their options with the server's parser. */
	static void addTo(ArgumentParser parser) {
		for (Job job : ALL) {
			Argument flag = parser.addArgument(job.flag()).help(job.help());
			if (job.metavar() == null) {
				flag.action(Arguments.storeTrue());
			} else {
				flag.metavar(job.metavar());
			}
			for (Option option : job.options()) {
				Argument argument = parser.addArgument(option.flag())
					.help("With " + job.flag() + ": " + lowerFirst(option.help()));
				if (!option.choices().isEmpty()) {
					argument.choices(option.choices().toArray(new String[0]));
				} else if (option.metavar() == null) {
					argument.action(Arguments.storeTrue());
				} else {
					argument.metavar(option.metavar());
				}
			}
		}
	}

	private static String lowerFirst(String help) {
		return Character.toLowerCase(help.charAt(0)) + help.substring(1);
	}

	/**
	 * Runs the first job the parsed arguments ask for.
	 *
	 * @return The process exit code, <code>null</code> if no job was asked for (the server starts
	 *         then).
	 */
	static Integer run(Namespace ns) {
		for (Job job : ALL) {
			if (job.requested(ns)) {
				return job.run().applyAsInt(ns);
			}
		}
		for (Job job : ALL) {
			for (Option option : job.options()) {
				Object value = ns.get(option.dest());
				if (value != null && !Boolean.FALSE.equals(value)) {
					System.err.println(option.flag() + " only applies to " + job.flag()
						+ (job.metavar() == null ? "" : " <" + job.metavar().toLowerCase() + ">") + ".");
					return 1;
				}
			}
		}
		return null;
	}

	/** Prints the table in the format described at {@link Jobs}. */
	public static void list(PrintStream out) {
		out.println(HEADER);
		for (Job job : ALL) {
			out.println(String.join("\t", "job", job.name(), job.flag(), field(job.metavar()),
				field(job.summary()), field(job.help())));
			for (Option option : job.options()) {
				out.println(String.join("\t", "option", job.name(), option.name(), option.flag(),
					option.value(), field(option.help())));
			}
		}
		out.flush();
	}

	private static String field(String value) {
		if (value == null) {
			return "";
		}
		if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
			throw new IllegalStateException("A job's text must not contain a tab or a line break: " + value);
		}
		return value;
	}
}
