/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.LibraryMigration;
import de.haumacher.imageServer.auth.LibraryMigration.MigrationRefused;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.auth.SpacesMigration;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.ui.Settings;
import de.haumacher.util.servlet.ResourceServlet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.helper.HelpScreenException;
import net.sourceforge.argparse4j.impl.type.FileArgumentType;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.ArgumentType;
import net.sourceforge.argparse4j.inf.Namespace;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Starts the image server.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Main {

	/**
	 * Prefix for resources served from <code>META-INF/resources</code>.
	 */
	public static final String STATIC_PREFIX = "";

	/**
	 * Image server main method.
	 */
	public static void main(String[] args) throws Exception {
		ArgumentParser parser = ArgumentParsers.newFor("imageserver").build().description("Start the image server");
		ArgumentType<Integer> type = new ArgumentType<Integer>() {
			@Override
			public Integer convert(ArgumentParser self, Argument arg, String value) throws ArgumentParserException {
				return Integer.parseInt(value);
			}
		};
		parser.addArgument("-p", "--port").type(type).setDefault(8080).help("The port to start the server.");
		parser.addArgument("-b", "--basepath").type(new FileArgumentType()).setDefault(new File(".")).help("The path containing albums to serve");
		parser.addArgument("-c", "--contextpath").setDefault("").help("The context path the albums are available over HTTP");
		parser.addArgument("-w", "--webroot").type(new FileArgumentType()).help(
			"A directory with the web application to serve (e.g. the output of 'flutter build web'), "
				+ "taking precedence over the web application bundled into this JAR");
		parser.addArgument("-a", "--auth").choices("off", "writes", "all").setDefault("writes").help(
			"What requires a device paired with this server: 'off' serves every request, "
				+ "'writes' refuses anonymous changes and uploads, 'all' refuses anonymous reads as well");
		parser.addArgument("--spaces").choices("auto", "single", "multi").setDefault("auto").help(
			"Whether this server hosts one space or several (issue #82): 'auto' decides from the "
				+ "folder tree — multi as soon as one folder directly below the base folder carries "
				+ "'.valbum/space.json', single otherwise — and 'single'/'multi' say so outright");
		parser.addArgument("--migrate-to-spaces").action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
			.help("Turn a library migrated per user (--migrate-to-user) into a multi-space server: "
				+ "every user folder becomes a space with that user as its admin, and what the space "
				+ "model cannot represent is moved aside and reported. A one-time, explicit, "
				+ "rename-only step; the server is not started afterwards");
		parser.addArgument("--replace-originals").type(new FileArgumentType()).help(
			"Put the originals in the given folder in the place of the redacted copies a phone "
				+ "uploaded (issue #167): every file whose name the library holds exactly once and "
				+ "whose picture (JPEG scan data) or video (media data) is the same is moved into its "
				+ "album, the redacted copy set aside in <space>/.valbum/replaced/<timestamp>/, and "
				+ "the missing position and camera filled in. Run it with the server stopped; the "
				+ "server is not started afterwards");
		parser.addArgument("--dry-run").action(net.sourceforge.argparse4j.impl.Arguments.storeTrue())
			.help("With --replace-originals: print what would be replaced and skipped, and touch nothing");
		parser.addArgument("--preview-threads").type(type).help(
			"How many thumbnails are generated at the same time (issue #69); the default is the "
				+ "number of processors, and the system property 'valbum.previewThreads' does the "
				+ "same. Serving an already cached thumbnail is never throttled");
		parser.addArgument("--pairing-secret").help(
			"Retired by issue #89; the server refuses to start when it is given. Use --admin-code, "
				+ "or let the server print a fresh sign-in code for the administrator at start-up");
		parser.addArgument("--admin-code").help(
			"The sign-in code the server prints for the administrator of a space that has no "
				+ "signed-in device yet, instead of a random one (issue #89): " + DeviceCodeStore.CODE_LENGTH
				+ " characters of '" + DeviceCodeStore.ALPHABET + "', a dash between groups allowed. "
				+ "It is issued anew at every start while the administrator has no device, and never "
				+ "once they have one");
		parser.addArgument("--migrate-to-user").help(
			"Move the albums at the base folder into a folder of that name and make it the library "
				+ "owner's space (issue #45). A one-time, explicit rename-only move; the server is "
				+ "not started afterwards");

		Namespace ns;
		try {
			ns = parser.parseArgs(args);
		} catch (HelpScreenException ex) {
			System.exit(-1);
			return;
		} catch (ArgumentParserException ex) {
			System.exit(-1);
			return;
		}

		String retired = retiredSecret(ns.getString("pairing_secret"));
		if (retired != null) {
			System.err.println(retired);
			System.exit(-1);
			return;
		}

		String codeProblem = adminCodeProblem(ns.getString("admin_code"));
		if (codeProblem != null) {
			System.err.println(codeProblem);
			System.exit(-1);
			return;
		}

		String migrateTo = ns.getString("migrate_to_user");
		if (migrateTo != null) {
			File basePath = ns.get("basepath");
			System.exit(migrateLibrary(basePath.toPath(), migrateTo));
			return;
		}

		if (Boolean.TRUE.equals(ns.getBoolean("migrate_to_spaces"))) {
			File basePath = ns.get("basepath");
			System.exit(migrateToSpaces(basePath.toPath()));
			return;
		}

		File replaceFrom = ns.get("replace_originals");
		boolean dryRun = Boolean.TRUE.equals(ns.getBoolean("dry_run"));
		if (replaceFrom != null || dryRun) {
			if (replaceFrom == null) {
				System.err.println("--dry-run only applies to --replace-originals <folder>.");
				System.exit(1);
				return;
			}
			File basePath = ns.get("basepath");
			System.exit(replaceOriginals(basePath.toPath(), replaceFrom.toPath(),
				SpaceMode.parse(ns.getString("spaces")), dryRun));
			return;
		}

		new Main(ns).start();
	}

	/**
	 * Replaces the redacted copies of the library by their originals, see {@link ReplaceOriginals}.
	 *
	 * @return The process exit code: <code>0</code> if the run went through (a skipped file is
	 *         reported, not a failure), <code>1</code> if it was refused (nothing was touched then).
	 */
	static int replaceOriginals(Path basePath, Path folder, SpaceMode spaces, boolean dryRun) {
		try {
			ReplaceOriginals.Report report = ReplaceOriginals.run(basePath, folder, spaces, dryRun);
			System.out.println((dryRun ? "Dry run: replacing" : "Replacing") + " the redacted copies in '"
				+ basePath + "' by the originals in '" + folder + "':");
			for (String line : report.getLines()) {
				System.out.println("  " + line);
			}
			for (String line : report.getSummary()) {
				System.out.println(line);
			}
			return 0;
		} catch (ReplaceOriginals.Refused ex) {
			System.err.println("Cannot replace the originals: " + ex.getMessage());
			return 1;
		} catch (IOException ex) {
			System.err.println("Cannot replace the originals: " + ex.getMessage());
			return 1;
		}
	}

	/**
	 * What a server started with the retired <code>--pairing-secret</code> is refused with (#89).
	 *
	 * <p>
	 * Refused, never ignored: somebody who wrote a secret into <code>/etc/default/valbum</code>
	 * believes it guards their library, and starting anyway would leave them believing it. The one
	 * line names what does the job now.
	 * </p>
	 *
	 * @return The message to print, <code>null</code> if no secret was given.
	 */
	static String retiredSecret(String secret) {
		if (secret == null || secret.isEmpty()) {
			return null;
		}
		return "--pairing-secret is gone (issue #89). This server prints a single-use sign-in code "
			+ "for the administrator of every space that has no signed-in device yet, valid "
			+ DeviceCodeStore.LIFETIME_MINUTES + " minutes; restart for a new one. Use --admin-code <code> "
			+ "to fix that code instead of taking the printed one.";
	}

	/**
	 * What a server started with an unusable <code>--admin-code</code> is refused with (#89).
	 *
	 * @return The message to print, <code>null</code> if the code is one this server could issue
	 *         itself (or none was given).
	 */
	static String adminCodeProblem(String code) {
		if (code == null || code.isEmpty()) {
			return null;
		}
		try {
			DeviceCodeStore.checkCode(code);
			return null;
		} catch (IllegalArgumentException ex) {
			return "Cannot use --admin-code: " + ex.getMessage();
		}
	}

	/**
	 * Runs the explicit library migration, see {@link LibraryMigration}.
	 *
	 * @return The process exit code: <code>0</code> if the library was moved, non-zero if the
	 *         migration was refused (nothing was moved then).
	 */
	/**
	 * Runs the explicit migration to the space model, see {@link SpacesMigration}.
	 *
	 * @return The process exit code: <code>0</code> if the library was migrated (or already is one
	 *         space), non-zero if the migration was refused (nothing was moved then).
	 */
	private static int migrateToSpaces(Path basePath) {
		try {
			SpacesMigration.Report report = SpacesMigration.migrate(basePath);
			System.out.println("Migrating '" + basePath + "' to the space model:");
			for (String line : report.getLines()) {
				System.out.println("  " + line);
			}
			if (report.getSpaces().isEmpty()) {
				System.out.println("This server now runs in single-space mode.");
			} else {
				System.out.println("This server now hosts " + report.getSpaces().size() + " space(s): "
					+ String.join(", ", report.getSpaces()));
			}
			return 0;
		} catch (SpacesMigration.MigrationRefused ex) {
			System.err.println("Cannot migrate to spaces: " + ex.getMessage());
			return 1;
		} catch (IOException ex) {
			System.err.println("Cannot migrate to spaces: " + ex.getMessage());
			return 1;
		}
	}

	private static int migrateLibrary(Path basePath, String userName) {
		try {
			List<String> moved = LibraryMigration.migrate(basePath, userName);
			System.out.println("Moved " + moved.size() + " entries of '" + basePath + "' into '"
				+ basePath.resolve(userName) + "':");
			for (String entry : moved) {
				System.out.println("  " + entry);
			}
			System.out.println("The library owner is now '" + userName + "' with the space '" + userName + "'.");
			return 0;
		} catch (MigrationRefused ex) {
			System.err.println("Cannot migrate the library: " + ex.getMessage());
			return 1;
		} catch (IOException ex) {
			System.err.println("Cannot migrate the library: " + ex.getMessage());
			return 1;
		}
	}

	private final int _port;
	private final String _contextPath;
	private final File _basePath;

	private final File _webRoot;

	private final AuthMode _authMode;

	private final InviteMode _inviteMode;

	private final String _adminCode;

	private final SpaceMode _spaceMode;

	/**
	 * Creates a {@link Main}.
	 */
	public Main(Namespace ns) {
		_port = ns.getInt("port");
		_basePath = ns.get("basepath");
		_contextPath = normlizeContextPath(ns.get("contextpath"));
		_webRoot = ns.get("webroot");
		_authMode = AuthMode.parse(ns.getString("auth"));
		// Only an administrator invites into a space since issue #83; the option is gone.
		_inviteMode = InviteMode.MEMBERS;

		Integer previewThreads = ns.get("preview_threads");
		if (previewThreads != null) {
			PreviewCache.setPermitCount(previewThreads.intValue());
		}

		String adminCode = ns.getString("admin_code");
		_adminCode = adminCode == null || adminCode.isEmpty() ? null : DeviceCodeStore.checkCode(adminCode);
		_spaceMode = SpaceMode.parse(ns.getString("spaces"));
	}

	private void start() throws Exception {
		Spaces spaces = Spaces.detect(_basePath.toPath(), _spaceMode, _authMode, _inviteMode);
		final Server server = createServer(_port, _contextPath, _basePath, _webRoot, spaces);
		server.start();

		System.out.println("Image server started: http://localhost:" + _port + _contextPath + "/ serving folder: " + _basePath);
		if (_webRoot != null) {
			System.out.println("Serving the web application from: " + _webRoot);
		}
		System.out.println("Preview generation: " + PreviewCache.permitCount() + " at a time");
		String renditions = VideoRenditions.unavailability();
		System.out.println(renditions == null
			? "Video renditions: available, transcoding with '" + VideoRenditions.encoderName() + "'"
			: "Video renditions: NOT available - " + renditions);
		System.out.println("Authentication: " + _authMode.protocolName());
		System.out.println("Spaces: " + spaces.getMode().protocolName());
		if (spaces.getMode() == SpaceMode.MULTI && spaces.getSpaces().isEmpty()) {
			System.out.println("  (no folder below the base folder carries .valbum/space.json; "
				+ "every address is answered with 'no such space')");
		}
		// A duplicates folder of an older version, which nothing writes to any more: named once,
		// never moved and never deleted, see issue #109.
		String legacy = spaces.getMode() == SpaceMode.MULTI
			? DeleteService.legacyDuplicates(_basePath.toPath())
			: null;
		if (legacy != null) {
			System.out.println(legacy);
		}
		if (_authMode != AuthMode.OFF) {
			for (Spaces.Space space : spaces.getSpaces()) {
				for (String line : reportSpace(spaces, space, _adminCode)) {
					System.out.println(line);
				}
			}
		}
		server.join();
	}

	/**
	 * Says who administers the given space, and prints its seat code while nobody signed in (#89).
	 *
	 * <p>
	 * The lines are answered rather than printed, so that a test can read what a start-up says
	 * without reading the console. The seat code is issued here and nowhere else: a space whose
	 * administrator already has a device gets none, which is what keeps the printed code from
	 * being the standing master key the pairing secret was.
	 * </p>
	 *
	 * @param fixedCode
	 *        What <code>--admin-code</code> fixed the seat code to, <code>null</code> for a random
	 *        one.
	 */
	static List<String> reportSpace(Spaces spaces, Spaces.Space space, String fixedCode) {
		List<String> lines = new ArrayList<>();
		String named;
		try {
			// A library written before Phase 6 could have a nameless owner *with* devices; in a
			// space the administrator is one user among several and needs a name, see issue #86.
			// A nameless administrator without devices is the fresh seat of issue #89 and is named
			// by whoever redeems the code below.
			named = space.getAuth().nameNamelessOwner(space.getSegment());
		} catch (IOException ex) {
			named = null;
			lines.add("Cannot name the administrator of '" + space.getRoot() + "': " + ex.getMessage());
		}
		if (named != null) {
			lines.add("Named the administrator of "
				+ (space.getSegment().isEmpty() ? "this library" : "space '" + space.getSegment() + "'")
				+ " '" + named + "' (it had no name); every device keeps working.");
		}
		String where = spaces.getMode() == SpaceMode.SINGLE
			? "Library owner: "
			: "Space '" + space.getSegment() + "' (" + space.getConfig().getName() + ", anonymous: "
				+ space.getConfig().getAnonymous() + "), admin: ";
		UserStore.User owner = space.getAuth().getUsers() == null ? null : space.getAuth().getUsers().getOwner();
		if (owner == null) {
			lines.add(where + "not signed in yet");
		} else {
			lines.add(where + "'" + (owner.getName().isEmpty() ? "<unnamed>" : owner.getName())
				+ "'" + (spaces.getMode() == SpaceMode.SINGLE
					? ", space: " + (owner.getSpace().isEmpty() ? "<the base folder>" : owner.getSpace())
					: ""));
		}
		try {
			DeviceCodeStore.Issued seat = space.getAuth().issueSeatCode(fixedCode);
			if (seat != null) {
				lines.add(seatCodeLine(space, seat.getCode()));
			}
		} catch (IOException ex) {
			lines.add("Cannot issue a sign-in code for the administrator of '" + space.getRoot() + "': "
				+ ex.getMessage());
		}
		return lines;
	}

	/** The one line that says how to sign the administrator of a space in, see issue #89. */
	static String seatCodeLine(Spaces.Space space, String code) {
		return (space.getSegment().isEmpty() ? "This library" : "Space '" + space.getSegment() + "'")
			+ ": sign the administrator in with the code " + DeviceCodeStore.format(code) + " (valid "
			+ DeviceCodeStore.LIFETIME_MINUTES + " minutes, once; restart the server for a new one).";
	}

	/**
	 * Builds the server, not yet started.
	 *
	 * <p>
	 * The server has no way of knowing its public surface: behind a reverse proxy it is reached as
	 * <code>https://home.example.org/valbum/</code> and sees <code>http://localhost:8082/</code>.
	 * So it never spells an absolute URL to itself (issue #62): a redirect is sent with the path it
	 * was given (<code>Location: /valbum/data/.../?type=json</code>), which the client resolves
	 * against the URL it actually used, and when a proxy does say where the request came from
	 * (<code>X-Forwarded-Proto</code>, <code>X-Forwarded-Host</code>, <code>Forwarded</code>), the
	 * request reports that surface as its scheme, host and port.
	 * </p>
	 *
	 * @param port
	 *        The port to listen on, <code>0</code> for any free one (a test).
	 */
	static Server createServer(int port, String contextPath, File basePath, File webRoot, AuthService auth)
			throws IOException {
		return createServer(port, contextPath, basePath, webRoot, Spaces.singleSpace(basePath.toPath(), auth));
	}

	/**
	 * Builds the server for the given spaces, see {@link #createServer(int, String, File, File, AuthService)}.
	 */
	static Server createServer(int port, String contextPath, File basePath, File webRoot, Spaces spaces)
			throws IOException {
		final Server server = new Server();

		HttpConfiguration config = new HttpConfiguration();
		config.setRelativeRedirectAllowed(true);
		config.addCustomizer(new ForwardedRequestCustomizer());
		ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(config));
		connector.setPort(port);
		server.addConnector(connector);

		HandlerCollection handlers = new HandlerCollection();

		WebAppContext webapp = new WebAppContext();
		webapp.setContextPath(contextPath);
		webapp.setResourceBase(basePath.toString());
		Path webRootPath = webRoot == null ? null : webRoot.toPath();
		// The same application is served below "/s/<token>/" (a share link, issue #51) and
		// "/i/<token>/" (an invitation, issue #52), so that either opens it with its own base href;
		// the static handler never looks at the token.
		ResourceServlet app = new ResourceServlet(webRootPath, Settings.DATA_PREFIX,
			ShareStore.URL_SEGMENT, InvitationStore.URL_SEGMENT);
		// A page load of a dead invitation address is the start page of its space, see issue #88.
		app.setSessionGuard(new InvitationRedirect(spaces));
		if (spaces.getMode() == SpaceMode.SINGLE) {
			ImageServlet data = new ImageServlet(basePath, spaces.single().getAuth(), "",
				spaces.single().getConfig());
			// Every photo of the space knows its hash from here on, see issue #118: one low
			// priority thread that reads the library once and then keeps out of the way.
			data.startIndexing();
			// What a messenger reads when a share link is posted, see issue #104.
			sharePreview(app, spaces, segment -> data);
			webapp.addServlet(new ServletHolder(data), Settings.DATA_PREFIX + "/*");
			webapp.addServlet(new ServletHolder(app), STATIC_PREFIX + "/*");
		} else {
			// The space is the first path segment: one door decides which space a request reaches,
			// and the application is rebased onto "/<space>/" like a share session, see SpaceServlet.
			app.setBaseSegments(spaces.segments());
			SpaceServlet front = new SpaceServlet(spaces, app);
			// Each space indexes its own photos, and nobody else's, see issue #118.
			front.startIndexing();
			sharePreview(app, spaces, front::dataOf);
			webapp.addServlet(new ServletHolder(front), STATIC_PREFIX + "/*");
		}
		webapp.setClassLoader(Main.class.getClassLoader());

		handlers.addHandler(webapp);

		if (!contextPath.equals("")) {
			WebAppContext redirect = new WebAppContext();
			redirect.setContextPath("");
			redirect.setResourceBase(basePath.toString());
			redirect.addServlet(new ServletHolder(new RedirectServlet(contextPath + "/")), "/");
			handlers.addHandler(redirect);
		}

		server.setHandler(handlers);
		return server;
	}

	/**
	 * Teaches the static handler what a share link is worth showing, see issue #104.
	 *
	 * <p>
	 * Two questions, one answer: the page of a share base carries the Open Graph tags of the
	 * shared album, and the one picture those tags name is answered below the link itself.
	 * </p>
	 */
	private static void sharePreview(ResourceServlet app, Spaces spaces,
			java.util.function.Function<String, ImageServlet> data) {
		SharePreview preview = new SharePreview(spaces, data);
		app.setPageDecorator(preview);
		app.setSessionResource(preview);
	}

	private String normlizeContextPath(String contextPath) {
		if (!contextPath.isEmpty()) {
			if (!contextPath.startsWith("/")) {
				contextPath = "/" + contextPath;
			}
			while (contextPath.endsWith("/")) {
				contextPath = contextPath.substring(0, contextPath.length() - 1);
			}
		}
		return contextPath;
	}

}
