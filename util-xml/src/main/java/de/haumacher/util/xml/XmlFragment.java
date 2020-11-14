/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.xml;

import java.io.IOException;

/**
 * Piece of XML that can be written to a {@link XmlAppendable}.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface XmlFragment {

	/**
	 * Produces content to the given {@link XmlAppendable}.
	 * 
	 * @param context
	 *        The {@link RenderContext} for writing.
	 */
	void write(RenderContext context, XmlAppendable out) throws IOException;

}
