/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.util.xml.XmlAppendable;

/**
 * Utility methods for rendering.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class RenderUtil {

	public static void icon(XmlAppendable out, String cssClass) throws IOException {
		out.begin(I);
		out.attr(CLASS_ATTR, cssClass);
		out.end();
	}

	public static String parentUrl(String path) {
		int slashIndex = path.endsWith("/") ? path.lastIndexOf('/', path.length() - 2) : path.lastIndexOf('/');
		if (slashIndex < 0) {
			return "/";
		}
		return path.substring(0, slashIndex + 1);
	}

}
