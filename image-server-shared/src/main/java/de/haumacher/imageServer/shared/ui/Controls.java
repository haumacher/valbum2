/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui;

import de.haumacher.util.html.HTML;

/**
 * Constants for binding functionality to contents.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface Controls {

	/**
	 * {@link HTML#DATA_CONTROL} value marking an image page.
	 */
	String PAGE_CONTROL = "PageControl";

	/**
	 * Server-side functionality for an album page.
	 */
	String ALBUM_CONTROL = "AlbumControl";

}
