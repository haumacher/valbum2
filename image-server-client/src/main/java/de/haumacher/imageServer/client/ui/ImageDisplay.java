/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.ui.DataAttributes;
import de.haumacher.util.gwt.dom.DomBuilder;

/**
 * {@link ResourceDisplay} displaying an {@link ImageInfo} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageDisplay extends ResourceDisplay {

	private ImageInfo _image;

	/** 
	 * Creates a {@link ImageDisplay}.
	 */
	public ImageDisplay(ImageInfo image) {
		_image = image;
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		out.attr(CLASS_ATTR, "image-page");

		String previous = _image.getPrevious();
		String previousUrl = previous == null ? null : previous;
		if (previousUrl != null) {
			out.attr(DataAttributes.DATA_LEFT, previousUrl);
		}
		
		String next = _image.getNext();
		String nextUrl = next == null ? null : next;
		if (nextUrl != null) {
			out.attr(DataAttributes.DATA_RIGHT, nextUrl);
		}

		out.attr(DataAttributes.DATA_ESCAPE, "./");
		out.attr(DataAttributes.DATA_UP, "./");
		out.attr(DataAttributes.DATA_HOME, _image.getHome());
		out.attr(DataAttributes.DATA_END, _image.getEnd());
		
		{
			switch (_image.getKind()) {
				case IMAGE: {
					out.begin(DIV);
					out.attr(ID_ATTR, "image-container");
					{
						out.begin(IMG);
						out.attr(CLASS_ATTR, "image-display");
						out.attr(ID_ATTR, "image");
						out.attr(DRAGGABLE_ATTR, "false");
						out.attr(SRC_ATTR, _image.getName());
						out.attr(DataAttributes.DATA_WIDTH, _image.getWidth());
						out.attr(DataAttributes.DATA_HEIGHT, _image.getHeight());
						out.endEmpty();
					}
					out.end();
					break;
				}
				
				case VIDEO: {
					out.begin(VIDEO);
					out.attr(CLASS_ATTR, "image-display");
					out.attr("controls", "controls");
					{
						out.begin(SOURCE);
						out.attr(SRC_ATTR, _image.getName());
						{
							out.append("Your browser doesn't support embedded videos.");
						}
						out.end();
					}
					out.end();
					break;
				}
			}
			
			if (previousUrl != null) {
				out.begin(A);
				out.attr(HREF_ATTR, previousUrl);
				out.attr(CLASS_ATTR, "goto-previous hover-pane");
				{
					out.begin(DIV);
					out.attr(CLASS_ATTR, "vcenter");
					{
						out.begin(DIV);
						out.attr(CLASS_ATTR, "vcenter-content");
						{
							RenderUtil.icon(out, "fas fa-chevron-left");
						}
						out.end();
					}
					out.end();
				}
				out.end();
			}
			
			if (nextUrl != null) {
				out.begin(A);
				out.attr(HREF_ATTR, nextUrl);
				out.attr(CLASS_ATTR, "goto-next hover-pane");
				{
					out.begin(DIV);
					out.attr(CLASS_ATTR, "vcenter");
					{
						out.begin(DIV);
						out.attr(CLASS_ATTR, "vcenter-content");
						{
							RenderUtil.icon(out, "fas fa-chevron-right");
						}
						out.end();
					}
					out.end();
				}
				out.end();
			}
			
			String parentUrl = RenderUtil.parentUrl(_image.getDepth());
			if (parentUrl != null) {
				writeAlbumToolbar(out, true, parentUrl);
			}
		}
		out.end();
	}

}
