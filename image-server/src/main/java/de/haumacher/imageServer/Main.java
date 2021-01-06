/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.helper.HelpScreenException;
import net.sourceforge.argparse4j.impl.type.FileArgumentType;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.ArgumentType;
import net.sourceforge.argparse4j.inf.Namespace;

/**
 * Starts the image server.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Main {
	
	/**
	 * Prefix for resources served from <code>META-INF/resources</code>.
	 */
	public static final String STATIC_PREFIX = "/static";

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

		try {
			Namespace ns = parser.parseArgs(args);
			
			new Main(ns).start();
		} catch (HelpScreenException ex) {
			System.exit(-1);
		} catch (ArgumentParserException ex) {
			System.exit(-1);
		}
	}
	
	private final int _port;
	private final String _contextPath;
	private final File _basePath;

	/** 
	 * Creates a {@link Main}.
	 */
	public Main(Namespace ns) {
		_port = ns.getInt("port");
		_basePath = ns.get("basepath");
		_contextPath = ns.get("contextpath");
	}
	
	private void start() throws Exception {
		final Server server = new Server();

		ServerConnector connector = new ServerConnector(server);
		connector.setPort(_port);
		connector.open();
		server.addConnector(connector);

		HandlerCollection handlers = new HandlerCollection();

		WebAppContext webapp = new WebAppContext();
		webapp.setContextPath(_contextPath);
		webapp.setResourceBase(_basePath.toString());
		webapp.addServlet(new ServletHolder(new ResourceServlet()), STATIC_PREFIX + "/*");
		webapp.addServlet(new ServletHolder(new ImageServlet(_basePath)), "/*");
		webapp.setClassLoader(Main.class.getClassLoader());

		handlers.addHandler(webapp);
		server.setHandler(handlers);
		server.start();

		System.out.println("Image server started: http://localhost:" + _port + _contextPath + "/");
		server.join();	
	}

}
