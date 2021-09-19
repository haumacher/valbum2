/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.bulma.modal;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.ui.AbstractDisplay;
import de.haumacher.imageServer.client.ui.UIContext;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Event;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Modal extends AbstractDisplay {

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		out.beginDiv("modal is-active");
		{
			out.beginDiv("modal-background");
			out.end();
			out.getLast().addEventListener("click", this::handleClose);
			
			out.beginDiv("modal-content");
			renderContent(context, out);
			out.end();

			out.begin(BUTTON);
			out.classAttr("modal-close is-large");
			out.attr(ARIA_LABEL_ATTR, "close");
			out.end();
			out.getLast().addEventListener("click", this::handleClose);
		}
		out.end();
	}

	/** 
	 * TODO
	 *
	 * @param context
	 * @param out
	 */
	protected void renderContent(UIContext context, DomBuilder out) {
		
	}

	private void handleClose(Event event) {
		remove();
		
		event.stopPropagation();
		event.preventDefault();
	}

}
