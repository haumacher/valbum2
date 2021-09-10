/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * {@link HttpServlet} serving static resources from <code>/META-INF/resources</code>.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceServlet extends HttpServlet {

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		if (pathInfo == null) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		
		if (pathInfo.endsWith("/")) {
			pathInfo = pathInfo + "index.html";
		}
		
		Path path = Paths.get(pathInfo.substring(1)).normalize();
		if (path.startsWith("..")) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		
		Path fullPath = Paths.get("/META-INF/resources").resolve(path);
		
		InputStream in = getClass().getResourceAsStream(fullPath.toString().replace(path.getFileSystem().getSeparator(), "/"));
		if (in == null) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		try {
			Util.sendBytes(response, in);
		} finally {
			in.close();
		}
	}
	
}
