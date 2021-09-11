/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.app.App;
import de.haumacher.imageServer.client.app.ControlHandler;
import de.haumacher.imageServer.client.app.KeyCodes;
import de.haumacher.imageServer.shared.ui.DataAttributes;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.KeyboardEvent;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public abstract class ResourceDisplay extends AbstractDisplay implements ControlHandler {

	private static final String TOOLBAR_CLASS = "toolbar";

	private static final String TOOLBAR_RIGHT_CLASS = "tb-right";
	
	private boolean _editMode;
	

	protected void writeAlbumToolbar(DomBuilder out, boolean isFile, CharSequence parentUrl) throws IOException {
		out.begin(DIV);
		out.attr(CLASS_ATTR, TOOLBAR_CLASS);
		{
			out.begin(A);
			out.attr(HREF_ATTR, parentUrl);
			{
				RenderUtil.icon(out, "fas fa-home");
			}
			out.end();

			out.begin(A);
			out.attr(HREF_ATTR, isFile ? "./" : "../");
			{
				RenderUtil.icon(out, "fas fa-chevron-up");
			}
			out.end();
			
			out.begin(DIV);
			out.attr(CLASS_ATTR, TOOLBAR_RIGHT_CLASS);
			if (isEditMode()) {
				RenderUtil.icon(out, "fas fa-save");
			} else {
				RenderUtil.icon(out, "fas fa-edit");
			}
			out.getLast().addEventListener("click", this::handleToggelEditMode);
			out.end();
		}
		out.end();
	}

	private void handleToggelEditMode(Event event) {
		_editMode = !_editMode;
		redraw();
	}

	/** 
	 * TODO
	 *
	 * @return
	 */
	protected final boolean isEditMode() {
		return _editMode;
	}
	
	@Override
	protected void onAttach(Element element) {
		super.onAttach(element);
		
		ControlHandler.setControlHandler(element, this);
	}
	
	@Override
	protected void onDetach(Element element) {
		ControlHandler.clearControlHandler(element);
		
		super.onDetach(element);
	}

	@Override
	public boolean handleEvent(Element target, Event event) {
		switch (event.type) {
			case "keydown": {
				String key = ((KeyboardEvent) event).key;
				switch (key) {
					case KeyCodes.Escape:
						return navigate(target, DataAttributes.DATA_ESCAPE);
					case KeyCodes.ArrowUp:
						return navigate(target, DataAttributes.DATA_UP);
					case KeyCodes.ArrowLeft:
						return navigate(target, DataAttributes.DATA_LEFT);
					case KeyCodes.ArrowRight:
						return navigate(target, DataAttributes.DATA_RIGHT);
					case KeyCodes.Home:
						return navigate(target, DataAttributes.DATA_HOME);
					case KeyCodes.End:
						return navigate(target, DataAttributes.DATA_END);
					default:
						return false;
				}
			}
			default:
				return false;
		}
	}

	private boolean navigate(Element target, String navigationAttr) {
		Element page = target.ownerDocument.getElementById("page");
		if (page.hasAttribute(navigationAttr)) {
			String url = page.getAttribute(navigationAttr);
			App.getInstance().gotoTarget(url);
		}
		return false;
	}

}
