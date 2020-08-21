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

	/**
	 * Opens a tag with the given name.
	 * 
	 * <p>
	 * After this method returns, {@link #attr(String, CharSequence) attributes
	 * of the tag} can be written until the first call to
	 * {@link #append(CharSequence)} that puts text into the tag's content or
	 * {@link #begin(String)} that opens another tag in the content of the
	 * current tag.
	 * </p>
	 *
	 * @param name
	 *        The tag name.
	 */
	void begin(String name) throws IOException;

	/**
	 * Closes the last tag opended with {@link #begin(String)}.
	 */
	void end() throws IOException;

	/**
	 * Closes the last tag opened with {@link #begin(String)} as XML empty tag.
	 * 
	 * <p>
	 * Only {@link #attr(String, CharSequence) attributes} may have been written
	 * after {@link #begin(String)}.
	 * </p>
	 */
	void endEmpty() throws IOException;

	/**
	 * Writes an attribute to the currently open tag.
	 * 
	 * <p>
	 * The currently open tag is the last tag opened using
	 * {@link #begin(String)}. No other content must have been
	 * {@link #append(CharSequence) added} to this tag before calling this
	 * method.
	 * </p>
	 *
	 * @param name
	 *        The name of the attribute.
	 * @param value
	 *        The value of the attribute.
	 */
	void attr(String name, CharSequence value) throws IOException;

	/**
	 * Opens an attribute with the given name in the context of the currently
	 * open tag.
	 * 
	 * <p>
	 * Text {@link #append(CharSequence) appended} afterwards is used as
	 * attribute value.
	 * </p>
	 *
	 * @param name
	 *        The name of the attribute to open.
	 */
	void openAttr(String name) throws IOException;

	/**
	 * Closes the attribute opened using {@link #openAttr(String)}.
	 * 
	 * <p>
	 * Text {@link #append(CharSequence) appended} after closing the attribute
	 * is placed into the currently opened element's content.
	 * </p>
	 */
	void closeAttr() throws IOException;

	/**
	 * Convenience method for {@link #attr(String, CharSequence)} with a
	 * <code>boolean</code> value.
	 */
	default void attr(String name, boolean value) throws IOException {
		attr(name, Boolean.toString(value));
	}

	/**
	 * Convenience method for {@link #attr(String, CharSequence)} with an
	 * <code>int</code> value.
	 */
	default void attr(String name, int value) throws IOException {
		attr(name, Integer.toString(value));
	}

	/**
	 * Convenience method for {@link #attr(String, CharSequence)} with a
	 * <code>float</code> value.
	 */
	default void attr(String name, float value) throws IOException {
		attr(name, Float.toString(value));
	}

	/**
	 * Convenience method for {@link #attr(String, CharSequence)} with a
	 * <code>double</code> value.
	 */
	default void attr(String name, double value) throws IOException {
		attr(name, Double.toString(value));
	}

}
