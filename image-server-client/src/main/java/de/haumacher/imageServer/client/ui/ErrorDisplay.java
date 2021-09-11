/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import java.io.IOException;

import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.util.gwt.dom.DomBuilder;
import de.haumacher.util.html.HTML;
import de.haumacher.util.xml.XmlFragment;

/**
 * {@link XmlFragment} displaying an {@link ErrorInfo} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ErrorDisplay implements Display {

	private ErrorInfo _error;

	/** 
	 * Creates a {@link ErrorDisplay}.
	 */
	public ErrorDisplay(ErrorInfo error) {
		_error = error;
	}

	@Override
	public void show(UIContext context, DomBuilder out) throws IOException {
		out.begin(HTML.H1);
		out.append(_error.getMessage());
		out.end();
	}

}
