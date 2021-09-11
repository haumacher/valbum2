/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import java.io.IOException;

import de.haumacher.util.gwt.dom.DomBuilder;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface Display {
	
	void show(UIContext context, DomBuilder out) throws IOException;

}
