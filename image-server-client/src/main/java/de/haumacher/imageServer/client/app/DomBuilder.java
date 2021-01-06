/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

import java.io.IOException;

import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;

import de.haumacher.util.xml.XmlAppendable;

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
	
	/** 
	 * Creates a {@link DomBuilder}.
	 */
	public DomBuilder(Element parent) {
		_current = parent;
		_document = _current.getOwnerDocument();
	}

	@Override
	public XmlAppendable append(CharSequence csq) throws IOException {
		if (_buffer != null) {
			_buffer.append(csq);
		} else {
			_current.appendChild(_document.createTextNode(csq.toString()));
		}
		return this;
	}

	@Override
	public XmlAppendable append(CharSequence csq, int start, int end)
			throws IOException {
		if (_buffer != null) {
			_buffer.append(csq, start, end);
		} else {
			append(csq.subSequence(start, end));
		}
		return this;
	}

	@Override
	public XmlAppendable append(char c) throws IOException {
		if (_buffer != null) {
			_buffer.append(c);
		} else {
			append(Character.toString(c));
		}
		return this;
	}

	@Override
	public void begin(String name) throws IOException {
		Element child = _document.createElement(name);
		_current.appendChild(child);
		_current = child;
	}

	@Override
	public void end() throws IOException {
		_current = _current.getParentElement();
	}

	@Override
	public void endEmpty() throws IOException {
		end();
	}

	@Override
	public void attr(String name, CharSequence value) throws IOException {
		_current.setAttribute(name, value.toString());
	}
	
	/**
	 * The current {@link Element} being built.
	 */
	public Element current() {
		return _current;
	}

	@Override
	public void openAttr(String name) throws IOException {
		_attributeName = name;
		_buffer = new StringBuilder();
	}

	@Override
	public void closeAttr() throws IOException {
		attr(_attributeName, _buffer);
		_buffer = null;
	}

}
