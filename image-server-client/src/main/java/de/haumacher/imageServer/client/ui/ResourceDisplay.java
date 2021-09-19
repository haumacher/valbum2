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
import de.haumacher.imageServer.shared.ui.DataAttributes;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.DomGlobal;
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

	protected void writeAlbumToolbar(DomBuilder out, boolean isFile, CharSequence parentUrl) throws IOException {
		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.TOOLBAR);
		{
			out.begin(A);
			out.classAttr(CssClasses.TOOLBAR_BUTTON);
			out.attr(HREF_ATTR, parentUrl);
			{
				RenderUtil.icon(out, "fas fa-home");
			}
			out.end();

			out.begin(A);
			out.classAttr(CssClasses.TOOLBAR_BUTTON);
			out.attr(HREF_ATTR, isFile ? "./" : "../");
			{
				RenderUtil.icon(out, "fas fa-chevron-up");
			}
			out.end();
			
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
				
				out.begin(SPAN);
				out.classAttr(CssClasses.TOOLBAR_BUTTON);
				{
					RenderUtil.icon(out, "fas fa-bars");
				}
				out.end();
				out.getLast().addEventListener("click", this::handleOpenSettings);
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
		DomBuilder out = context().createDomBuilderImpl(DomGlobal.document.body);

		new Modal().show(context(), out);
		
		event.stopPropagation();
		event.preventDefault();
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
		if (page != null && page.hasAttribute(navigationAttr)) {
			String url = page.getAttribute(navigationAttr);
			App.getInstance().gotoTarget(url);
		}
		return false;
	}

}
