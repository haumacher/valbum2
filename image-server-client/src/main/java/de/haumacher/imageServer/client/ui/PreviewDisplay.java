/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.app.App;
import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.imageServer.shared.util.Orientations;
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
	private boolean _orientationChanged;
	private Orientation _origOrientation;

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
	 * Whether the image must be scaled down so that its fits into its original dimensions (before the orientation
	 * change).
	 * 
	 * <p>
	 * This option is set, while an image is being rotated while being displayed in a layout with multiple image to not
	 * disturb the layout of other images.
	 * </p>
	 */
	public boolean isOrientationChanged() {
		return _orientationChanged;
	}
	
	/**
	 * @see #isOrientationChanged()
	 */
	public void setDownScale(boolean downScale) {
		if (!_orientationChanged) {
			_origOrientation = _image.getOrientation();
			_orientationChanged = downScale;
		}
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
					boolean wrapImage; 
					
					Orientation orientation = _image.getOrientation();
					switch (orientation) {
					case ROT_L:
					case ROT_L_FLIP_H:
					case ROT_L_FLIP_V:
					case ROT_R:
						wrapImage = true;
						break;

					default:
						wrapImage = isOrientationChanged();
						break;
					}
					
					if (wrapImage) {
						out.begin(DIV);
						out.classAttr(CssClasses.ICON_WRAPPER);
						out.attr(STYLE_ATTR, "width: " + _width + "px; height: " + _rowHeight + "px;");
					}
					
					out.begin(IMG);
					out.attr(CLASS_ATTR, CssClasses.ICON_DISPLAY);
					out.openAttr(STYLE_ATTR);
					
					double imgWidth;
					double imgHeight;
					
					double scale;
					if (isOrientationChanged()) {
						// The image is being rotated within the album layout. To prevent disturbing other images while
						// rotating, the rotated variant must fit into its original dimensions.

						// The width and height of the display area was computed by applying the original orientation to the
						// image height. By applying it again, the image dimension is recovered.  
						imgWidth = Orientations.width(_origOrientation, _width, _rowHeight);
						imgHeight = Orientations.height(_origOrientation, _width, _rowHeight);

						// The width an height of the display area under the current orientation.
						double width = Orientations.width(orientation, imgWidth, imgHeight);
						double height = Orientations.height(orientation, imgWidth, imgHeight);
						
						scale = Math.min(_width / width, _rowHeight/ height);
					} else {
						imgWidth = Orientations.width(orientation, _width, _rowHeight);
						imgHeight = Orientations.height(orientation, _width, _rowHeight);
						
						scale = 1.0;
					}
					
					out.append("width: " + imgWidth + "px; height: " + imgHeight + "px;");
					out.append("transform-origin: top left; transform: ");
					out.append(Orientations.cssTransform(orientation, imgWidth, imgHeight, scale));
					out.append(";");
					out.closeAttr();
					out.attr(TITLE_ATTR, _image.getComment());
					{
						out.openAttr(SRC_ATTR);
						out.append(_image.getName());
						out.append("?type=tn");
						out.closeAttr();
						
						out.attr(WIDTH_ATTR, imgWidth);
						out.attr(HEIGHT_ATTR, imgHeight);
					}
					out.end();
					
					if (wrapImage) {
						out.end();
					}
					
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
