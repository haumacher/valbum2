/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Event;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class HeadingDisplay extends AbstractDisplay {

	private AlbumDisplay _album;
	private Heading _heading;

	/** 
	 * Creates a {@link HeadingDisplay}.
	 */
	public HeadingDisplay(AlbumDisplay album, Heading heading) {
		_album = album;
		_heading = heading;
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		out.begin(H3, CssClasses.HEADER);
		out.append(_heading.getText());
		
		if (_album.isEditMode()) {
			out.begin(SPAN);
			out.classAttr(CssClasses.TOOLBAR_INLINE);
			
			out.begin(SPAN);
			out.classAttr(CssClasses.TOOLBAR_BUTTON);
			RenderUtil.icon(out, "fas fa-bars");
			out.end();
			out.getLast().addEventListener("click", this::handleEdit);
			
			out.begin(SPAN);
			out.classAttr(CssClasses.TOOLBAR_BUTTON);
			RenderUtil.icon(out, "far fa-trash-alt");
			out.end();
			out.getLast().addEventListener("click", this::handleDelete);
			
			out.end();
		}
		
		out.end();
	}

	private void handleDelete(Event event) {
		_album.getResource().getParts().remove(_heading);
		remove();
		_album.redraw();
	}

	private void handleEdit(Event event) {
		Heading heading = _heading;
		new HeadingPropertiesEditor() {
			@Override
			protected void onSave(Event event) {
				save(heading);
				HeadingDisplay.this.redraw();
			}
		}.load(heading).showTopLevel(context());
	}

}
