/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Starts the image server.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Main {
	
	/** 
	 * Image server main method.
	 */
	public static void main(String[] args) throws Exception {
		new Main().start();
	}

	private int _port = 8080;
	private String _contextPath = "";
	private String _basePath = ".";

	private void start() throws Exception {
		final Server server = new Server();

		ServerConnector connector = new ServerConnector(server);
		connector.setPort(_port);
		connector.open();
		server.addConnector(connector);

		HandlerCollection handlers = new HandlerCollection();

		WebAppContext webapp = new WebAppContext();
		webapp.setContextPath(_contextPath);
		webapp.setBaseResource(Resource.newResource(_basePath));
		webapp.addServlet(new ServletHolder(new ImageServlet()), "/");

		handlers.addHandler(webapp);
		server.setHandler(handlers);
		server.start();

		System.out.println("Image server started: http://localhost:" + _port + _contextPath + "/");
		server.join();	
	}

}
