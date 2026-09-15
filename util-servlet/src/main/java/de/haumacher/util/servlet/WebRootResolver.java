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
	 * The virtual base a request path carries, see issue #51.
	 *
	 * <p>
	 * The application is served not only at the context root but also below
	 * <code>/&lt;prefix&gt;/&lt;anything&gt;/</code>, where the second segment is an opaque token
	 * the static handler never looks at: a share link opens the very same application, with its
	 * base href rewritten so that every asset below it resolves, and everything deeper is a
	 * client-side route of that application.
	 * </p>
	 *
	 * @param pathInfo
	 *        The path info of the request, may be <code>null</code>.
	 * @param prefixes
	 *        The first segments a virtual base may have (<code>s</code> for the share links of
	 *        issue #51, <code>i</code> for the invitations of issue #52); none, or a
	 *        <code>null</code> or empty one, if this handler serves no virtual base at all.
	 * @return The virtual base including its leading slash and without a trailing one
	 *         (<code>/s/abc</code>), <code>null</code> if the path carries none.
	 */
	public static String virtualBase(String pathInfo, String... prefixes) {
		if (pathInfo == null || prefixes == null) {
			return null;
		}
		for (String prefix : prefixes) {
			String base = virtualBase(pathInfo, prefix);
			if (base != null) {
				return base;
			}
		}
		return null;
	}

	/**
	 * The virtual base of a session that lives inside a space: <code>/&lt;space&gt;/s/&lt;token&gt;</code>.
	 *
	 * <p>
	 * On a multi-space server a share link or an invitation of a space is opened under that space
	 * (issue #82), so that the application served there finds the space's data root. Three
	 * segments instead of two, and the same rule otherwise.
	 * </p>
	 *
	 * @param segments
	 *        The first segments that are spaces.
	 * @param prefixes
	 *        The session prefixes, as for {@link #virtualBase(String, String...)}.
	 * @return The base, including its leading slash and without a trailing one; <code>null</code>
	 *         if the path is no such session.
	 */
	public static String spaceSessionBase(String pathInfo, java.util.Collection<String> segments,
			String... prefixes) {
		String space = singleSegmentBase(pathInfo, segments);
		if (space == null) {
			return null;
		}
		String session = virtualBase(pathInfo.substring(space.length()), prefixes);
		return session == null ? null : space + session;
	}

	/**
	 * The virtual base of a path whose <em>first</em> segment is one of the given names.
	 *
	 * <p>
	 * The spaces of a multi-space server are addressed that way (issue #82):
	 * <code>/&lt;space&gt;/</code> is the application's base, exactly as
	 * <code>/s/&lt;token&gt;/</code> is for a share link — one segment instead of two, because the
	 * space is a name somebody types, not a token somebody was handed.
	 * </p>
	 *
	 * @param segments
	 *        The first segments that are a base of their own; <code>null</code> or empty for a
	 *        server that has none.
	 * @return The base, including its leading slash and without a trailing one;
	 *         <code>null</code> if the path does not lie below one of them.
	 */
	public static String singleSegmentBase(String pathInfo, java.util.Collection<String> segments) {
		if (pathInfo == null || segments == null || segments.isEmpty() || !pathInfo.startsWith("/")) {
			return null;
		}
		int end = pathInfo.indexOf('/', 1);
		String segment = end < 0 ? pathInfo.substring(1) : pathInfo.substring(1, end);
		if (segment.isEmpty() || !segments.contains(segment)) {
			return null;
		}
		return "/" + segment;
	}

	private static String virtualBase(String pathInfo, String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			return null;
		}
		String head = "/" + prefix + "/";
		if (!pathInfo.startsWith(head) || pathInfo.length() == head.length()) {
			return null;
		}
		int end = pathInfo.indexOf('/', head.length());
		String segment = end < 0 ? pathInfo.substring(head.length()) : pathInfo.substring(head.length(), end);
		if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
			return null;
		}
		return head + segment;
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
