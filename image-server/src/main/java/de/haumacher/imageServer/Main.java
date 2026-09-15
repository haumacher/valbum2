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
		parser.addArgument("--invite").choices("members", "admin").setDefault("members").help(
			"Who may invite somebody onto this server (issue #52): 'members' lets every member hand "
				+ "out an invitation, 'admin' reserves that for the library owner");
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

		new Main(ns).start();
	}

	/**
	 * Runs the explicit library migration, see {@link LibraryMigration}.
	 *
	 * @return The process exit code: <code>0</code> if the library was moved, non-zero if the
	 *         migration was refused (nothing was moved then).
	 */
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

	/**
	 * Creates a {@link Main}.
	 */
	public Main(Namespace ns) {
		_port = ns.getInt("port");
		_basePath = ns.get("basepath");
		_contextPath = normlizeContextPath(ns.get("contextpath"));
		_webRoot = ns.get("webroot");
		_authMode = AuthMode.parse(ns.getString("auth"));
		_inviteMode = InviteMode.parse(ns.getString("invite"));

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
	}

	private void start() throws Exception {
		AuthService auth = new AuthService(_authMode, _pairingSecret, _basePath.toPath(), _inviteMode);
		final Server server = createServer(_port, _contextPath, _basePath, _webRoot, auth);
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
		if (_authMode != AuthMode.OFF) {
			System.out.println("Invitations: " + _inviteMode.protocolName());
			System.out.println("Pairing secret: " + _pairingSecret);
			UserStore.User owner = auth.getUsers().getOwner();
			if (owner == null) {
				System.out.println("Library owner: not signed in yet");
			} else {
				System.out.println("Library owner: '" + (owner.getName().isEmpty() ? "<unnamed>" : owner.getName())
					+ "', space: " + (owner.getSpace().isEmpty() ? "<the base folder>" : owner.getSpace()));
			}
		}
		server.join();
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
		webapp.addServlet(new ServletHolder(new ImageServlet(basePath, auth)), Settings.DATA_PREFIX + "/*");
		Path webRootPath = webRoot == null ? null : webRoot.toPath();
		// The same application is served below "/s/<token>/" (a share link, issue #51) and
		// "/i/<token>/" (an invitation, issue #52), so that either opens it with its own base href;
		// the static handler never looks at the token.
		webapp.addServlet(new ServletHolder(new ResourceServlet(webRootPath, Settings.DATA_PREFIX,
			ShareStore.URL_SEGMENT, InvitationStore.URL_SEGMENT)), STATIC_PREFIX + "/*");
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
