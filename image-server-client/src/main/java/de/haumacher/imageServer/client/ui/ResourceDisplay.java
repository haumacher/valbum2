/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.app.App;
import de.haumacher.imageServer.client.app.ControlHandler;
import de.haumacher.imageServer.client.app.KeyCodes;
import de.haumacher.imageServer.client.app.ResourceHandler;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.CssClasses;
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

	protected void writeAlbumToolbar(DomBuilder out) throws IOException {
		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.TOOLBAR);
		{
			writeToolbarContents(out);
		}
		out.end();
	}

	private void writeToolbarContents(DomBuilder out) throws IOException {
		writeToolbarContentsLeft(out);
		
		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.TOOLBAR_RIGHT);
		{
			writeToolbarContentsRight(out);
		}
		out.end();
	}

	protected void writeToolbarContentsLeft(DomBuilder out) throws IOException {
		writeHomeButton(out);
		writeUpButton(out);
	}

	protected void writeHomeButton(DomBuilder out) throws IOException {
		out.begin(A);
		out.classAttr(CssClasses.TOOLBAR_BUTTON);
		out.attr(HREF_ATTR, "#");
		{
			RenderUtil.icon(out, "fas fa-home");
		}
		out.end();
		out.getLast().addEventListener("click", e -> {App.getInstance().showPage("/"); e.stopPropagation(); e.preventDefault(); });
	}

	protected void writeUpButton(DomBuilder out) throws IOException {
		out.begin(A);
		out.classAttr(CssClasses.TOOLBAR_BUTTON);
		out.attr(HREF_ATTR, "#");
		{
			RenderUtil.icon(out, "fas fa-chevron-up");
		}
		out.end();
		out.getLast().addEventListener("click", this::showParent);
	}

	protected void writeToolbarContentsRight(DomBuilder out) throws IOException {
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
		if (!event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey) {
			switch (key) {
			case KeyCodes.Escape:
				showParent(event);
				return true;
				
			}
		}
		return false;
	}

	protected abstract void showParent(Event event);
	
	protected final void show(Event event, Resource resource) {
		show(event, resource, DisplayMode.DEFAULT);
	}

	protected final void show(Event event, Resource resource, DisplayMode mode) {
		App.getInstance().showPage(resource, mode);
		event.stopPropagation();
		event.preventDefault();
	}

	

}
