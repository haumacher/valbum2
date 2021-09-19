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
public class ModalCard extends AbstractDisplay {

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
//			<div class="modal">
//			  <div class="modal-background"></div>
//			  <div class="modal-card">
//			    <header class="modal-card-head">
//			      <p class="modal-card-title">Modal title</p>
//			      <button class="delete" aria-label="close"></button>
//			    </header>
//			    <section class="modal-card-body">
//			      <!-- Content ... -->
//			    </section>
//			    <footer class="modal-card-foot">
//			      <button class="button is-success">Save changes</button>
//			      <button class="button">Cancel</button>
//			    </footer>
//			  </div>
//			</div>
		
		out.beginDiv("modal is-active");
		{
			out.beginDiv("modal-background");
			out.end();
			out.getLast().addEventListener("click", this::handleClose);
			
			out.beginDiv("modal-card");
			{
				out.begin(HEADER);
				out.classAttr("modal-card-head");
				{
					out.begin(P);
					out.classAttr("modal-card-title");
					renderTitle(context, out);
					out.end();
					
					out.begin(BUTTON);
					out.classAttr("delete");
					out.attr(ARIA_LABEL_ATTR, "close");
					out.end();
					out.getLast().addEventListener("click", this::handleClose);
				}
				out.end();

				out.begin(SECTION);
				out.classAttr("modal-card-body");
				{
					renderContent(context, out);
				}
				out.end();

				out.begin(FOOTER);
				out.classAttr("modal-card-foot");
				{
					renderButtons(context, out);
				}
				out.end();
			}
			out.end();
		}
		out.end();
	}

	protected void renderTitle(UIContext context, DomBuilder out) throws IOException {
		
	}

	protected void renderContent(UIContext context, DomBuilder out) throws IOException {
		
	}
	
	protected void renderButtons(UIContext context, DomBuilder out) throws IOException {
		
	}

	private void handleClose(Event event) {
		remove();
		
		event.stopPropagation();
		event.preventDefault();
	}

}
