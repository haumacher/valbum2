/*
 * Copyright (c) 2026 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.servlet;

import java.util.function.Predicate;

/**
 * Pure resolution of a request path to a static resource path of a web root.
 *
 * <p>
 * The rules implement single-page-application hosting: a request that does not resolve to an
 * existing file but looks like a client-side route (its last path segment has no file name
 * extension) falls back to the application entry point {@link #INDEX}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class WebRootResolver {

	/**
	 * The application entry point that deep links fall back to.
	 */
	public static final String INDEX = "index.html";

	/**
	 * Resolves the path info of a request to the web-root-relative path of the resource to deliver.
	 *
	 * @param pathInfo
	 *        The path info of the request, e.g. <code>/sub/file.txt</code>. May be
	 *        <code>null</code> for a request to the servlet root.
	 * @param exists
	 *        Whether a web-root-relative path (never starting with a <code>/</code>) denotes an
	 *        existing file.
	 * @return The web-root-relative path of the file to deliver, or <code>null</code>, if the
	 *         request cannot be served (a 404 must be sent).
	 */
	public static String resolve(String pathInfo, Predicate<String> exists) {
		String relative = normalize(pathInfo);
		if (relative == null) {
			// The path escapes the web root.
			return null;
		}

		String candidate = relative.isEmpty() || relative.endsWith("/") ? relative + INDEX : relative;
		if (exists.test(candidate)) {
			return candidate;
		}

		if (exists.test(INDEX)) {
			// Anything the web root does not hold is a client-side route: the application decides
			// what to display. Image routes such as <code>/album/IMG_0417.JPG</code> carry a file
			// extension, so the fallback must not depend on the shape of the last segment.
			return INDEX;
		}

		return null;
	}

	/**
	 * Whether the given path looks like a client-side route (as opposed to a request for a file).
	 *
	 * <p>
	 * A path is a route, if its last segment carries no file name extension.
	 * </p>
	 *
	 * @param pathInfo
	 *        The path info of the request, may be <code>null</code>.
	 */
	public static boolean isRoute(String pathInfo) {
		if (pathInfo == null) {
			return true;
		}
		String lastSegment = pathInfo.substring(pathInfo.lastIndexOf('/') + 1);
		return lastSegment.indexOf('.') < 0;
	}

	/**
	 * Normalizes the given path info to a web-root-relative path without a leading <code>/</code>.
	 *
	 * <p>
	 * A trailing <code>/</code> is preserved, <code>.</code> and <code>..</code> segments are
	 * resolved.
	 * </p>
	 *
	 * @param pathInfo
	 *        The path info of the request, may be <code>null</code>.
	 * @return The normalized relative path, or <code>null</code>, if the path escapes the web root.
	 */
	public static String normalize(String pathInfo) {
		if (pathInfo == null || pathInfo.isEmpty() || pathInfo.equals("/")) {
			return "";
		}

		StringBuilder result = new StringBuilder();
		int depth = 0;
		for (String segment : pathInfo.split("/", -1)) {
			switch (segment) {
				case "":
				case ".":
					continue;
				case "..":
					if (depth == 0) {
						return null;
					}
					depth--;
					result.setLength(result.lastIndexOf("/", result.length() - 2) + 1);
					continue;
				default:
					if (segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0) {
						return null;
					}
					result.append(segment).append('/');
					depth++;
			}
		}

		if (!pathInfo.endsWith("/") && result.length() > 0) {
			// Drop the separator appended after the last segment.
			result.setLength(result.length() - 1);
		}
		return result.toString();
	}

}
