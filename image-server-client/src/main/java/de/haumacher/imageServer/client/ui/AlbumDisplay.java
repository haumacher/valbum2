/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumProperties;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ImageInfo.Kind;
import de.haumacher.imageServer.shared.ui.DataAttributes;
import de.haumacher.imageServer.shared.ui.ImageRow;
import de.haumacher.util.xml.RenderContext;
import de.haumacher.util.xml.XmlAppendable;
import de.haumacher.util.xml.XmlFragment;

/**
 * {@link XmlFragment} displaying a {@link AlbumInfo} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumDisplay extends ResourceDisplay {

	private AlbumInfo _album;

	/** 
	 * Creates a {@link AlbumDisplay}.
	 */
	public AlbumDisplay(AlbumInfo album) {
		_album = album;
	}

	@Override
	public void write(RenderContext context, XmlAppendable out) throws IOException {
		int width = context.getPageWidth();
		String parentUrl = RenderUtil.parentUrl(_album.getDepth());

		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		if (parentUrl != null) {
			out.attr(DataAttributes.DATA_ESCAPE, parentUrl);
		}
		
		out.begin(H1);
		AlbumProperties header = _album.getHeader();
		out.append(header.getTitle());
		out.end();
		out.begin(H2);
		out.append(header.getSubTitle());
		out.end();

		out.begin(DIV);
		out.attr(CLASS_ATTR, "image-rows");
		out.attr(STYLE_ATTR, "width: " + width + "px;");
		{
			ImageRow row = new ImageRow(width, 400);
			for (ImageInfo image : _album.getImages()) {
				if (row.isComplete()) {
					writeRow(out, row);
					row.clear();
				}
				row.add(image);
			}
			writeRow(out, row);
		}
		out.end();
		
		if (parentUrl != null) {
			writeAlbumToolbar(out, false, parentUrl);
		}
		out.end();
	}

	private void writeRow(XmlAppendable out, ImageRow row) throws IOException {
		if (row.getSize() == 0) {
			return;
		}
		double rowHeight = row.getHeight();
		int spacing = row.getSpacing();

		out.begin(DIV);
		out.attr(CLASS_ATTR, "icons");
		out.attr("style", "display: table; margin-top: " + spacing + "px;");
		{
			out.begin(DIV);
			out.attr(STYLE_ATTR, "display: table-row;");
			for (int n = 0, cnt = row.getSize(); n < cnt; n++) {
				ImageInfo image = row.getImage(n);

				out.begin(DIV);
				out.attr(CLASS_ATTR, "icon");
				{
					out.begin(A);
					out.attr(CLASS_ATTR, "icon-link");
					if (n > 0) {
						out.openAttr(STYLE_ATTR);
						out.append("margin-left: ");
						out.append(spacing);
						out.append("px;");
						out.closeAttr();
					}
					out.openAttr(HREF_ATTR);
					{
						out.append(image.getName());
						out.append("?type=page");
					}
					out.closeAttr();
					{
						out.begin(IMG);
						out.attr(CLASS_ATTR, "icon-display");
						{
							out.openAttr(SRC_ATTR);
							out.append(image.getName());
							out.append("?type=tn");
							out.closeAttr();
							
							out.attr(WIDTH_ATTR, row.getScaledWidth(n));
							out.attr(HEIGHT_ATTR, rowHeight);
						}
						out.end();
						
						if (image.getKind() == Kind.VIDEO) {
							out.begin(DIV);
							out.attr(CLASS_ATTR, "video-overlay");
							{
								out.begin(I);
								out.attr(CLASS_ATTR, "far fa-play-circle");
								out.end();
							}
							out.end();
						}
						
						writeToolbars(out);
					}
					out.end();
				}
				out.end();
			}
			out.end();
		}
		out.end();
	}

	private void writeToolbars(XmlAppendable out) throws IOException {
		boolean editMode = false;
		if (editMode) {
			out.begin(SPAN);
			out.attr(CLASS_ATTR, "check-button" + (Math.random() > 0.5 ? " checked" : ""));
			{
				out.begin(I);
				out.attr(CLASS_ATTR, "display-unchecked far fa-square");
				out.end();
				
				out.begin(I);
				out.attr(CLASS_ATTR, "display-checked far fa-check-square");
				out.end();
			}
			out.end();
			
			out.begin(SPAN);
			out.attr(CLASS_ATTR, "toolbar-embedded toolbar-top");
			{
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-redo-alt");
					out.end();
				}
				out.end();
				
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-arrows-alt-v");
					out.end();
				}
				out.end();
				
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-undo-alt");
					out.end();
				}
				out.end();
			}
			out.end();
			
			out.begin(SPAN);
			out.attr(CLASS_ATTR, "toolbar-embedded toolbar-bottom");
			{
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-star");
					out.end();
				}
				out.end();
				
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-plus");
					out.end();
				}
				out.end();
				
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "far fa-dot-circle");
					out.end();
				}
				out.end();
				
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-minus");
					out.end();
				}
				out.end();
				
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-trash-alt");
					out.end();
				}
				out.end();
			}
			out.end();
		}
	}

}
