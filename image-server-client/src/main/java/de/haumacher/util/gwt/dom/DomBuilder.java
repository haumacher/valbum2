/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.gwt.dom;

import de.haumacher.util.xml.XmlAppendable;
import elemental2.dom.Element;

/**
 * {@link XmlAppendable} that allows to retrieve the elements being currently created.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface DomBuilder extends XmlAppendable {

	/**
	 * The {@link Element} whose contents is currently created.
	 */
	Element getCurrent();

	/**
	 * The {@link Element} that was finished last.
	 * 
	 * <p>
	 * The last {@link Element} created is the one last closed with a call to {@link #end()}.
	 * </p>
	 */
	Element getLast();

}
