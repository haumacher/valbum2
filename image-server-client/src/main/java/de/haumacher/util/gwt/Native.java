/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.gwt;

import elemental2.dom.Element;

/**
 * Utilities for accessing custom properties of {@link Element}s.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Native {

	public static native <T> T get(Element element, String property) /*-{
	    return element[property];
	}-*/;

	public static native void delete(Element element, String property) /*-{
	    delete element[property];
	}-*/;

	public static native void set(Element element, String property, Object value) /*-{
	    element[property] = value;
	}-*/;

}
