/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import java.io.IOException;

import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Element;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public abstract class AbstractDisplay implements Display {
	
	private Element _element;
	private UIContext _context;
	
	/**
	 * TODO
	 */
	public Element element() {
		return _element;
	}

	@Override
	public final void show(UIContext context, DomBuilder out) {
		_context = context;
		try {
			render(context, out);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
		_element = out.getLast();
	}
	
	public void redraw() {
		Element parent = _element.parentElement;
		parent.removeChild(_element);
		_element = null;
		
		show(_context, _context.createDomBuilderImpl(parent));
	}

	/** 
	 * TODO
	 *
	 * @param context
	 * @param out
	 */
	protected abstract void render(UIContext context, DomBuilder out) throws IOException;

}
