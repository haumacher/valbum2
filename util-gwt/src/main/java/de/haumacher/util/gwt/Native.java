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

	public static native <T> T get(Object element, String property) /*-{
	    return element[property];
	}-*/;

	public static native void delete(Object element, String property) /*-{
	    delete element[property];
	}-*/;

	public static native void set(Object element, String property, Object value) /*-{
	    element[property] = value;
	}-*/;

}
