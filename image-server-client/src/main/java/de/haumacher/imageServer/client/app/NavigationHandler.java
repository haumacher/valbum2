/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

import de.haumacher.imageServer.shared.ui.DataAttributes;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.KeyboardEvent;

/**
 * Base class for {@link ControlHandler}s interpreting navigation data attributes on the page element.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public abstract class NavigationHandler implements ControlHandler {

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
