/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.html;

import java.io.IOException;

/**
 * {@link Appendable} for XML creation.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface XmlAppendable extends Appendable {
	
	enum State {
		/**
		 * Element content. Opening new tags is possible, see {@link TagOutput#begin(String)}.
		 */
		CONTENT,
		
		/**
		 * Within an opening tag. Writing attributes is possible, see {@link TagOutput#attr(String, String)}.
		 */
		ATTRIBUTES,
		
		/**
		 * Within an open attribute, writing text is possible, see {@link TagOutput#append(String)}.
		 */
		ATTRIBUTE,
	}
	
	void begin(String name) throws IOException;

	void end() throws IOException;

	void endEmpty() throws IOException;

	void attr(String name, CharSequence value) throws IOException;

	void openAttr(String name) throws IOException;

	void closeAttr() throws IOException;

	default void attr(String name, boolean value) throws IOException {
		attr(name, Boolean.toString(value));
	}

	default void attr(String name, int value) throws IOException {
		attr(name, Integer.toString(value));
	}

	default void attr(String name, float value) throws IOException {
		attr(name, Float.toString(value));
	}

	default void attr(String name, double value) throws IOException {
		attr(name, Double.toString(value));
	}

}
