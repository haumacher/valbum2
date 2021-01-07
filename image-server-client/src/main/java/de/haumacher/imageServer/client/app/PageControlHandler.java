/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.user.client.Event;

/**
 * {@link ControlHandler} for an image page.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class PageControlHandler implements ControlHandler {

	@Override
	public boolean handleEvent(Element target, Event event) {
		switch (event.getTypeInt()) {
			case Event.ONKEYDOWN: {
				int keyCode = event.getKeyCode();
				switch (keyCode) {
					case KeyCodes.KEY_UP:
						return navigate(target, "data-up");
					case KeyCodes.KEY_LEFT:
						return navigate(target, "data-left");
					case KeyCodes.KEY_RIGHT:
						return navigate(target, "data-right");
					case KeyCodes.KEY_HOME:
						return navigate(target, "data-home");
					case KeyCodes.KEY_END:
						return navigate(target, "data-end");
					default:
						return false;
				}
			}
			
			default: 
				return false;
		}
	}

	private boolean navigate(Element target, String navigationAttr) {
		Element page = target.getOwnerDocument().getElementById("page");
		if (page.hasAttribute(navigationAttr)) {
			String url = page.getAttribute(navigationAttr);
			App.getInstance().gotoTarget(url);
		}
		return false;
	}

}
