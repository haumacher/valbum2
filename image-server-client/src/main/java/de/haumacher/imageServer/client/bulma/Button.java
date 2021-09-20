/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.bulma;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.ui.AbstractDisplay;
import de.haumacher.imageServer.client.ui.UIContext;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Event;
import elemental2.dom.EventListener;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Button extends AbstractDisplay {

	private EventListener _onClick;
	private String _label;
	private String _type = "is-success";

	public String getLabel() {
		return _label;
	}
	
	public Button setLabel(String label) {
		_label = label;
		return this;
	}
	
	public String getType() {
		return _type;
	}
	
	public Button setType(String type) {
		_type = type;
		return this;
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		out.begin(BUTTON);
		out.classAttr("button" + " " + _type);
		out.append(_label);
		out.end();
		
		if (_onClick != null) {
			out.getLast().addEventListener("click", this::handleClick);
		}
	}

	public Button onClick(EventListener listener) {
		_onClick = listener;
		return this;
	}

	private void handleClick(Event event) {
		_onClick.handleEvent(event);
		
		event.stopPropagation();
		event.preventDefault();
	}

}
