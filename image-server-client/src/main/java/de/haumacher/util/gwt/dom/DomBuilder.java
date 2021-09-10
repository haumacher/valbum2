/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.gwt.dom;

import de.haumacher.util.xml.XmlAppendable;
import elemental2.dom.Document;
import elemental2.dom.Element;
import elemental2.dom.Node;

/**
 * {@link XmlAppendable} directly creating DOM structures.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class DomBuilder implements XmlAppendable {
	
	private Document _document;
	private Element _current;
	private String _attributeName;
	private StringBuilder _buffer;
	private Element _last;
	private Node _before;
	
	/** 
	 * Creates a {@link DomBuilder}.
	 */
	public DomBuilder(Element parent) {
		this(parent, null);
	}
	
	/**
	 * Creates a {@link DomBuilder}.
	 *
	 * @param parent
	 *        The parent {@link Element} to insert to.
	 * @param before
	 *        The reference element that is a child of the given parent. New
	 *        contents is inserted before this reference {@link Node}.
	 */
	public DomBuilder(Element parent, Node before) {
		_current = parent;
		_before = before;
		_document = _current.ownerDocument;
	}

	/**
	 * The current {@link Element} being created.
	 */
	public Element getCurrent() {
		return _current;
	}
	
	/**
	 * The last {@link Element} that was created.
	 * 
	 * <p>
	 * The last {@link Element} created is the one last closed with a call to {@link #end()}.
	 * </p>
	 */
	public Element getLast() {
		return _last;
	}

	@Override
	public XmlAppendable append(CharSequence csq) {
		if (_buffer != null) {
			_buffer.append(csq);
		} else {
			_current.appendChild(_document.createTextNode(csq.toString()));
		}
		return this;
	}

	@Override
	public XmlAppendable append(CharSequence csq, int start, int end)
			{
		if (_buffer != null) {
			_buffer.append(csq, start, end);
		} else {
			append(csq.subSequence(start, end));
		}
		return this;
	}

	@Override
	public XmlAppendable append(char c) {
		if (_buffer != null) {
			_buffer.append(c);
		} else {
			append(Character.toString(c));
		}
		return this;
	}

	@Override
	public void begin(String name) {
		Element child = _document.createElement(name);
		_current.insertBefore(child, _before);
		_current = child;
		_before = null;
	}

	@Override
	public void end() {
		_last = _current;
		_current = _current.parentElement;
		_before = _last.nextSibling;
	}

	@Override
	public void endEmpty() {
		end();
	}

	@Override
	public final void attr(String name, CharSequence value) {
		if (value != null) {
			attrNonNull(name, value);
		}
	}

	/** 
	 * Sets the non-<code>null</code> attribute value.
	 * 
	 * @see #attr(String, CharSequence)
	 */
	protected void attrNonNull(String name, CharSequence value) {
		_current.setAttribute(name, value.toString());
	}
	
	/**
	 * The current {@link Element} being built.
	 */
	public Element current() {
		return _current;
	}

	@Override
	public void openAttr(String name) {
		_attributeName = name;
		_buffer = new StringBuilder();
	}

	@Override
	public void closeAttr() {
		attr(_attributeName, _buffer);
		_buffer = null;
	}
	
	@Override
	public void special(String value) {
		// Ignore.
	}

}
