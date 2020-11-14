/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.xml;

import java.io.IOException;

/**
 * Algorithm producing XML output from a given value.
 * 
 * @param <T> The type of the value being rendered.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface Renderer<T> {
	
	/**
	 * Writes the given value to the given {@link XmlAppendable}.
	 *
	 * @param out The {@link XmlAppendable} to write to.
	 * @param value The value to write.
	 */
	void write(XmlAppendable out, T value) throws IOException;

}
