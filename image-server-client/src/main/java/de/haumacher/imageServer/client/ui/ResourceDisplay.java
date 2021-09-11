/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.util.xml.XmlAppendable;
import de.haumacher.util.xml.XmlFragment;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public abstract class ResourceDisplay implements XmlFragment {

	private static final String TOOLBAR_CLASS = "toolbar";

	private static final String TOOLBAR_RIGHT_CLASS = "tb-right";
	
	protected void writeAlbumToolbar(XmlAppendable out, boolean isFile,
			CharSequence parentUrl) throws IOException {
		out.begin(DIV);
		out.attr(CLASS_ATTR, TOOLBAR_CLASS);
		{
			out.begin(A);
			out.attr(HREF_ATTR, parentUrl);
			{
				RenderUtil.icon(out, "fas fa-home");
			}
			out.end();

			out.begin(A);
			out.attr(HREF_ATTR, isFile ? "./" : "../");
			{
				RenderUtil.icon(out, "fas fa-chevron-up");
			}
			out.end();
			
			out.begin(DIV);
			out.attr(CLASS_ATTR, TOOLBAR_RIGHT_CLASS);
			boolean editMode = false;
			if (editMode) {
				RenderUtil.icon(out, "fas fa-save");
			} else {
				RenderUtil.icon(out, "fas fa-edit");
			}
			out.end();
		}
		out.end();
	}


}
