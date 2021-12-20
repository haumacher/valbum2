/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.gwt.dom;

import elemental2.dom.Node;

/**
 * Utilities for working with <code>elemental2.dom</code>.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class DomUtil {

	/**
	 * Removes all children from the given {@link Node}.
	 */
	public static void clear(Node node) {
		while (node.lastChild != null) {
			node.removeChild(node.lastChild);
		}
	}

	/**
	 * The combination of the given CSS classes.
	 *
	 * @param classes
	 *        CSS classes to join to a single CSS class string.
	 *        <code>null</code> values are skipped.
	 * @return The joined CSS class string, or <code>null</code>, if all given
	 *         classes were <code>null</code>.
	 */
	public static String cssClass(String... classes) {
		String result = null;
		for (String cssClass : classes) {
			if (result == null) {
				result = cssClass;
			} else if (cssClass != null) {
				result = result + " " + cssClass;
			}
		}
		return result;
	}

}
