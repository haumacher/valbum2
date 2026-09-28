/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.SpaceStore;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * <tr><td><code>move-into-space --name</code></td><td><code>--space-name</code></td></tr>
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
	 * Every one-time job, in the order {@link Main} checks for them and <code>valbum-admin help</code>
	 * lists them: where several are given, the first one runs. The jobs for libraries from before
	 * spaces existed come last.
	 */
	public static final List<Job> ALL = List.of(
		new Job("create-space", "FOLDER",
			"Make a folder directly below the base folder a space of its own.",
			"Make the folder of that name directly below the base folder a space: write its "
				+ ".valbum/space.json, creating the folder if it is missing; an existing folder becomes the "
				+ "space with its albums. A library that still has its albums in the base folder is moved "
				+ "into a space first, with move-into-space. At its next start the server prints the "
				+ "sign-in code for the new space's administrator",
			List.of(
				new Option("--space-name", "--name", "NAME", List.of(),
					"The name to show for the space (the folder name otherwise)"),
				new Option("--anonymous", null, null,
					List.of(SpaceStore.ANONYMOUS_NONE, SpaceStore.ANONYMOUS_PUBLIC),
					"Whether visitors who are not signed in see the public photos of the space "
						+ "('public') or nothing ('none', the default)"),
				new Option("--faces", null, null, List.of(SpaceStore.FACES_ON, SpaceStore.FACES_OFF),
					"Whether the server looks for faces in the photos of the space; 'off' is the default"),
				new Option("--time-zone", null, "ZONE", List.of(),
					"The time zone the photos of the space were taken in where a photo does not say its own, "
						+ "an IANA id such as Europe/Berlin (the server's zone otherwise)")),
			ns -> Main.createSpace(Main.basePath(ns), ns.getString("create_space"), ns.getString("space_name"),
				orDefault(ns.getString("anonymous"), SpaceStore.ANONYMOUS_NONE),
				orDefault(ns.getString("faces"), SpaceStore.FACES_OFF), ns.getString("time_zone"),
				Main.spaceMode(ns))),
		new Job("move-into-space", "FOLDER",
			"Move the library into a space of its own, so that further spaces fit beside it.",
			"Move the albums at the base folder, and everything the library knows - its users with "
				+ "their devices, share links, invitations and people - into a new or empty folder of that "
				+ "name, which becomes a space. It only renames; nothing is copied or deleted. The old "
				+ "addresses keep working: signed-in devices, share links and invitations already sent "
				+ "open the space as before",
			List.of(new Option("--space-name", "--name", "NAME", List.of(),
				"The name to show for the space (otherwise the name the library has, or the folder name)")),
			ns -> Main.moveIntoSpace(Main.basePath(ns), ns.getString("move_into_space"), ns.getString("space_name"),
				AuthMode.parse(ns.getString("auth")), Main.spaceMode(ns))),
		new Job("replace-originals", "FOLDER",
			"Put downloaded originals in the place of the copies a phone uploaded without their position.",
			"Put the originals in the given folder in the place of the redacted copies a phone "
				+ "uploaded: every file whose name the library holds exactly once and whose picture (JPEG "
				+ "scan data) or video (media data) is the same is moved into its album, the redacted copy "
				+ "set aside in <space>/.valbum/replaced/<timestamp>/, and the missing position and camera "
				+ "filled in",
			List.of(new Option("--dry-run", null, null, List.of(),
				"Print what would be replaced and skipped, and touch nothing")),
			ns -> Main.replaceOriginals(Main.basePath(ns), Path.of(ns.getString("replace_originals")),
				Main.spaceMode(ns), Boolean.TRUE.equals(ns.getBoolean("dry_run")))),
		new Job("migrate-to-user", "NAME",
			"For libraries from before spaces existed: move the albums into a folder named after the owner.",
			"For libraries from before spaces existed: move the albums at the base folder into a folder "
				+ "of that name, the library owner's - the first of two steps, migrate-to-spaces is the "
				+ "second. A current library moves into a space with move-into-space",
			List.of(),
			ns -> Main.migrateLibrary(Main.basePath(ns), ns.getString("migrate_to_user"))),
		new Job("migrate-to-spaces", null,
			"For libraries from before spaces existed: turn the folders of their users into spaces.",
			"For libraries from before spaces existed, split per user with migrate-to-user: every user "
				+ "folder becomes a space with that user as its administrator, and what spaces cannot "
				+ "represent is moved aside and reported. A current library moves into a space with "
				+ "move-into-space",
			List.of(),
			ns -> Main.migrateToSpaces(Main.basePath(ns))));

	private Jobs() {
		// Only the table.
	}

	private static String orDefault(String value, String fallback) {
		return value == null ? fallback : value;
	}

	/**
	 * Registers the flags of every job and their options with the server's parser.
	 *
	 * <p>
	 * Several jobs may share an option (<code>--space-name</code>); its flag is registered once.
	 * </p>
	 */
	static void addTo(ArgumentParser parser) {
		for (Job job : ALL) {
			Argument flag = parser.addArgument(job.flag()).help(job.help());
			if (job.metavar() == null) {
				flag.action(Arguments.storeTrue());
			} else {
				flag.metavar(job.metavar());
			}
		}
		Set<String> registered = new HashSet<>();
		for (Job job : ALL) {
			for (Option option : job.options()) {
				if (!registered.add(option.flag())) {
					continue;
				}
				Argument argument = parser.addArgument(option.flag())
					.help("With " + jobsOf(option.flag()) + ": " + lowerFirst(option.help()));
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

	/** The jobs that take the option of the given flag, as the server's usage names them. */
	private static String jobsOf(String optionFlag) {
		List<String> jobs = new ArrayList<>();
		for (Job job : ALL) {
			for (Option option : job.options()) {
				if (option.flag().equals(optionFlag)) {
					jobs.add(job.flag() + (job.metavar() == null ? "" : " <" + job.metavar().toLowerCase() + ">"));
				}
			}
		}
		return String.join(" or ", jobs);
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
					System.err.println(option.flag() + " only applies to " + jobsOf(option.flag()) + ".");
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
