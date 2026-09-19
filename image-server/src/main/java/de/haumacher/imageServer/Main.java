/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
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
		parser.addArgument("--preview-threads").type(type).help(
			"How many thumbnails are generated at the same time (issue #69); the default is the "
				+ "number of processors, and the system property 'valbum.previewThreads' does the "
				+ "same. Serving an already cached thumbnail is never throttled");
		parser.addArgument("--pairing-secret").help(
			"The secret a device must present to be paired with this server; "
				+ "a random one is generated and printed at start-up if none is given");
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

		new Main(ns).start();
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

	private final String _pairingSecret;

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

		String secret = ns.getString("pairing_secret");
		if (_authMode != AuthMode.OFF && (secret == null || secret.isEmpty())) {
			// Without a secret nobody could ever pair; a generated one is printed at start-up.
			secret = AuthService.generateSecret();
		}
		_pairingSecret = secret;
		_spaceMode = SpaceMode.parse(ns.getString("spaces"));
	}

	private void start() throws Exception {
		Spaces spaces = Spaces.detect(_basePath.toPath(), _spaceMode, _authMode, _pairingSecret, _inviteMode);
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
		if (_authMode != AuthMode.OFF) {
			System.out.println("Pairing secret: " + _pairingSecret);
			for (Spaces.Space space : spaces.getSpaces()) {
				reportSpace(spaces, space);
			}
		}
		server.join();
	}

	/** Says who administers the given space and whether anybody may look in without signing in. */
	private void reportSpace(Spaces spaces, Spaces.Space space) {
		try {
			// A library written before Phase 6 could have a nameless owner; in a space the
			// administrator is one user among several and needs a name, see issue #86.
			String named = space.getAuth().nameNamelessOwner(space.getSegment());
			if (named != null) {
				System.out.println("Named the administrator of "
					+ (space.getSegment().isEmpty() ? "this library" : "space '" + space.getSegment() + "'")
					+ " '" + named + "' (it had no name); every device keeps working.");
			}
		} catch (IOException ex) {
			System.err.println("Cannot name the administrator of '" + space.getRoot() + "': " + ex.getMessage());
		}
		String where = spaces.getMode() == SpaceMode.SINGLE
			? "Library owner: "
			: "Space '" + space.getSegment() + "' (" + space.getConfig().getName() + ", anonymous: "
				+ space.getConfig().getAnonymous() + "), admin: ";
		UserStore.User owner = space.getAuth().getUsers() == null ? null : space.getAuth().getUsers().getOwner();
		if (owner == null) {
			System.out.println(where + "not signed in yet");
		} else {
			System.out.println(where + "'" + (owner.getName().isEmpty() ? "<unnamed>" : owner.getName())
				+ "'" + (spaces.getMode() == SpaceMode.SINGLE
					? ", space: " + (owner.getSpace().isEmpty() ? "<the base folder>" : owner.getSpace())
					: ""));
		}
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
			webapp.addServlet(new ServletHolder(new ImageServlet(basePath, spaces.single().getAuth())),
				Settings.DATA_PREFIX + "/*");
			webapp.addServlet(new ServletHolder(app), STATIC_PREFIX + "/*");
		} else {
			// The space is the first path segment: one door decides which space a request reaches,
			// and the application is rebased onto "/<space>/" like a share session, see SpaceServlet.
			app.setBaseSegments(spaces.segments());
			webapp.addServlet(new ServletHolder(new SpaceServlet(spaces, app)), STATIC_PREFIX + "/*");
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
