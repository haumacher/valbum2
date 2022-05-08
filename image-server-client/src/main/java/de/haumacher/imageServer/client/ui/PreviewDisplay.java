/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.app.App;
import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Event;

/**
 * Base class for displaying a single image in the context of an {@link AbstractAlbumDisplay}.
 */
public class PreviewDisplay extends AbstractDisplay {

	private AbstractImage _part;
	private final ImagePart _image;
	private final double _width;
	private final double _rowHeight;
	private final int _marginLeft;

	/** 
	 * Creates a {@link PreviewDisplay}.
	 */
	public PreviewDisplay(AbstractImage part, double width, double rowHeight, int marginLeft) {
		_part = part;
		_image = ToImage.toImage(part);
		_width = width;
		_rowHeight = rowHeight;
		_marginLeft = marginLeft;
	}
	
	/**
	 * The displayed part.
	 */
	public AbstractImage getPart() {
		return _part;
	}

	/**
	 * The displayed {@link ImagePart} of {@link #getPart()}.
	 */
	public ImagePart getImage() {
		return _image;
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.ICON);
		{
			out.begin(A);
			{
				out.attr(CLASS_ATTR, CssClasses.ICON_LINK);
				if (_marginLeft > 0) {
					out.openAttr(STYLE_ATTR);
					out.append("margin-left: ");
					out.append(_marginLeft);
					out.append("px;");
					out.closeAttr();
				}
				if (!isEditMode()) {
					out.attr(HREF_ATTR, "#");
				}
				out.closeAttr();
				{
					out.begin(IMG);
					out.attr(CLASS_ATTR, CssClasses.ICON_DISPLAY);
					out.attr(STYLE_ATTR, "width: " + _width + "px; height: " + _rowHeight + "px;");
					out.attr("title", _image.getComment());
					{
						out.openAttr(SRC_ATTR);
						out.append(_image.getName());
						out.append("?type=tn");
						out.closeAttr();
						
						out.attr(WIDTH_ATTR, _width);
						out.attr(HEIGHT_ATTR, _rowHeight);
					}
					out.end();
					
					if (ImageDisplay.isVideo(_image.getKind())) {
						out.begin(DIV);
						out.attr(CLASS_ATTR, CssClasses.VIDEO_OVERLAY);
						{
							out.begin(I);
							out.attr(CLASS_ATTR, "far fa-play-circle");
							out.end();
						}
						out.end();
					}
					
					renderToolbar(context, out);
				}
			}
			out.end();
			if (!isEditMode()) {
				out.getLast().addEventListener("click", this::showDetail);
			}
		}
		out.end();
	}

	protected void renderToolbar(UIContext context, DomBuilder out) throws IOException {
		// Hook for sub-classes.
	}

	protected boolean isEditMode() {
		return false;
	}
	
	protected void showDetail(Event event) {
		App.getInstance().showPage(_part, DisplayMode.DEFAULT);
		event.preventDefault();
		event.stopPropagation();
	}


}
