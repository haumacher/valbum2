/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import org.eclipse.jetty.servlet.DefaultServlet;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageServlet extends DefaultServlet {

	/** 
	 * Creates a {@link ImageServlet}.
	 */
	public ImageServlet() {
		super(new ImageResourceService());
	}
	
}
