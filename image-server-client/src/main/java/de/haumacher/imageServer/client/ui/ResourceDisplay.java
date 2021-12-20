/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.app.App;
import de.haumacher.imageServer.client.app.ControlHandler;
import de.haumacher.imageServer.client.app.ResourceHandler;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.EventListener;
import elemental2.dom.KeyboardEvent;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public abstract class ResourceDisplay extends AbstractDisplay implements ControlHandler {

	/**
	 * The path of the displayed {@link Resource}.
	 */
	private ResourceHandler _handler;

	private boolean _editMode;

	/** 
	 * Creates a {@link ResourceDisplay}.
	 */
	public ResourceDisplay(ResourceHandler handler) {
		_handler = handler;
	}

	protected void writeAlbumToolbar(DomBuilder out, boolean isFile, EventListener gotoParent) throws IOException {
		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.TOOLBAR);
		{
			if (gotoParent != null) {
				out.begin(A);
				out.classAttr(CssClasses.TOOLBAR_BUTTON);
				out.attr(HREF_ATTR, "#");
				{
					RenderUtil.icon(out, "fas fa-home");
				}
				out.end();
				out.getLast().addEventListener("click", e -> {App.getInstance().gotoTarget("/"); e.stopPropagation(); e.preventDefault(); });
				
				out.begin(A);
				out.classAttr(CssClasses.TOOLBAR_BUTTON);
				out.attr(HREF_ATTR, "#");
				{
					RenderUtil.icon(out, "fas fa-chevron-up");
				}
				out.end();
				out.getLast().addEventListener("click", gotoParent);
			}
			
			out.begin(DIV);
			out.attr(CLASS_ATTR, CssClasses.TOOLBAR_RIGHT);
			{
				out.begin(SPAN);
				out.classAttr(CssClasses.TOOLBAR_BUTTON);
				{
					if (isEditMode()) {
						RenderUtil.icon(out, "fas fa-save");
					} else {
						RenderUtil.icon(out, "fas fa-edit");
					}
				}
				out.end();
				out.getLast().addEventListener("click", this::handleToggelEditMode);
				
				if (isEditMode()) {
					out.begin(SPAN);
					out.classAttr(CssClasses.TOOLBAR_BUTTON);
					{
						RenderUtil.icon(out, "fas fa-bars");
					}
					out.end();
					out.getLast().addEventListener("click", this::handleOpenSettings);
				}
			}
			out.end();
		}
		out.end();
	}

	private void handleToggelEditMode(Event event) {
		boolean newEditMode = !_editMode;
		
		_editMode = newEditMode;
		
		if (!newEditMode) {
			_handler.store(getResource());
		}
		redraw();
		
		event.stopPropagation();
		event.preventDefault();
	}

	private void handleOpenSettings(Event event) {
		openSettings();
		
		event.stopPropagation();
		event.preventDefault();
	}

	protected void openSettings() {
		// Hook for subclasses.
	}

	protected abstract Resource getResource();

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
				KeyboardEvent keyEvent = (KeyboardEvent) event;
				String key = keyEvent.key;
				return handleKeyDown(target, keyEvent, key);
			}
			default:
				return false;
		}
	}

	protected boolean handleKeyDown(Element target, KeyboardEvent event, String key) {
		return false;
	}

}
