/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.DomGlobal;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface Display {
	
	void show(UIContext context, DomBuilder out);
	
	default void showTopLevel(UIContext context) {
		DomBuilder out = context.createDomBuilderImpl(DomGlobal.document.body);
		show(context, out);
	}

}
