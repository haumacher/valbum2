/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.util.xml.RenderContext;
import de.haumacher.util.xml.XmlAppendable;
import de.haumacher.util.xml.XmlFragment;

/**
 * {@link XmlFragment} displaying an {@link ErrorInfo} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ErrorDisplay implements XmlFragment {

	private ErrorInfo _error;

	/** 
	 * Creates a {@link ErrorDisplay}.
	 */
	public ErrorDisplay(ErrorInfo error) {
		_error = error;
	}

	@Override
	public void write(RenderContext context, XmlAppendable out) throws IOException {
		out.begin(H1);
		out.append(_error.getMessage());
		out.end();
	}

}
