/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

/**
 * Constants from https:/**www.w3.org/TR/uievents-key/#key-attribute-value
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface KeyCodes {

	/** The down arrow key, to navigate or traverse downward. (KEYCODE_DPAD_DOWN) */
	String ARROW_DOWN = "ArrowDown";
	
	/** The left arrow key, to navigate or traverse leftward. (KEYCODE_DPAD_LEFT)*/
	String ARROW_LEFT = "ArrowLeft";
	
	/** The right arrow key, to navigate or traverse rightward. (KEYCODE_DPAD_RIGHT)*/
	String ARROW_RIGHT = "ArrowRight";
	
	/** The up arrow key, to navigate or traverse upward. (KEYCODE_DPAD_UP)*/
	String ARROW_UP = "ArrowUp";
	
	/** The End key, used with keyboard entry to go to the end of content (KEYCODE_MOVE_END).*/
	String END = "End";
	
	/** The Home key, used with keyboard entry, to go to start of content (KEYCODE_MOVE_HOME).
	 For the mobile phone Home key (which goes to the phone’s main screen), use "GoHome".*/
	String HOME = "Home";
	
	/** The Page Down key, to scroll down or display next page of content.*/
	String PAGE_DOWN = "PageDown";
	String PAGE_UP = "PageUp";
	
}
