/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

import com.google.gwt.dom.client.Element;
import com.google.gwt.user.client.Event;

/**
 * Controller functionality for a UI element.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface ControlHandler {

	/**
	 * Reacts on a browser event.
	 * 
	 * @param target
	 *        The control element.
	 * @param event
	 *        The {@link Event} to handle.
	 * @return Whether the event has been handled and should not be dispatched
	 *         to parent controls.
	 */
	boolean handleEvent(Element target, Event event);

}
