/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import de.haumacher.util.gwt.dom.DomBuilder;
import de.haumacher.util.xml.RenderContext;
import elemental2.dom.Element;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface UIContext extends RenderContext {

	/** 
	 * TODO
	 *
	 * @return
	 */
	int getPageWidth();

	default DomBuilder createDomBuilderImpl(Element parent) {
		return createDomBuilderImpl(parent, null);
	}
	
	/** 
	 * TODO
	 *
	 * @param parent
	 * @return
	 */
	DomBuilder createDomBuilderImpl(Element parent, Element before);

}
