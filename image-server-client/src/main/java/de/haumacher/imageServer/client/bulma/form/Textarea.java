/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.bulma.form;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.ui.UIContext;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Event;
import elemental2.dom.HTMLTextAreaElement;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Textarea extends AbstractFieldDisplay<Textarea> {

	private String _placeholder;
	
	private int _rows;
	
	private String _value;
	
	private HTMLTextAreaElement _inputElement;

	/**
	 * TODO
	 */
	public String getPlaceholder() {
		return _placeholder;
	}
	
	public Textarea setPlaceholder(String placeholder) {
		_placeholder = placeholder;
		redraw();
		return this;
	}
	
	/**
	 * TODO
	 */
	public int getRows() {
		return _rows;
	}
	
	public Textarea setRows(int rows) {
		_rows = rows;
		redraw();
		return this;
	}
	
	/**
	 * TODO
	 */
	public String getValue() {
		return _value;
	}

	public Textarea setValue(String value) {
		_value = value;
		redraw();
		return this;
	}
	
	@Override
	protected void renderFieldValue(UIContext context, DomBuilder out) throws IOException {
		out.begin("textarea");
		out.classAttr("textarea");
		out.attr(PLACEHOLDER_ATTR, _placeholder);
		out.attr("rows", _rows);
		out.append(_value);
		out.end();
		
		_inputElement = out.getLast();
		_inputElement.addEventListener("change", this::handleChange);
	}

	private void handleChange(Event event) {
		_value = _inputElement.value;
	}
	
	@Override
	protected Textarea self() {
		return this;
	}

}
