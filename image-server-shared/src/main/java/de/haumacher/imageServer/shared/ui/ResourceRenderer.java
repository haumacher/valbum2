/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;
import java.util.List;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumProperties;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.util.xml.Renderer;
import de.haumacher.util.xml.XmlAppendable;

/**
 * {@link de.haumacher.imageServer.shared.model.Resource.Visitor} rendering a {@link Resource} to a {@link XmlAppendable}.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceRenderer implements Resource.Visitor<Void, XmlAppendable, IOException>, Renderer<Resource> {
	
	/**
	 * Singleton {@link ResourceRenderer} instance.
	 */
	public static final ResourceRenderer INSTANCE = new ResourceRenderer();

	private ResourceRenderer() {
		// Singleton constructor.
	}
	
	@Override
	public void write(XmlAppendable out, Resource value) throws IOException {
		value.visit(this, out);
	}

	@Override
	public Void visit(AlbumInfo album, XmlAppendable out) throws IOException {
		out.begin(H1);
		AlbumProperties header = album.getHeader();
		out.append(header.getTitle());
		out.end();
		out.begin(H2);
		out.append(header.getSubTitle());
		out.end();

		out.begin(DIV);
		out.attr(CLASS_ATTR, "image-rows");
		{
			ImageRow row = new ImageRow(1280, 400);
			for (ImageInfo image : album.getImages()) {
				if (row.isComplete()) {
					writeRow(out, row);
					row.clear();
				}
				row.add(image);
			}
			writeRow(out, row);
		}
		out.end();
		
		writeAlbumToolbar(out, album.getDepth(), false);

		return null;
	}

	private void writeAlbumToolbar(XmlAppendable out, int depth, boolean isFile) throws IOException {
		if (depth == 0) {
			return;
		}
		out.begin(DIV);
		out.attr(CLASS_ATTR, "toolbar");
		{
			out.begin(A);
			out.attr(HREF_ATTR, parentUrl(depth));
			{
				icon(out, "fas fa-home");
			}
			out.end();

			out.begin(A);
			out.attr(HREF_ATTR, isFile ? "./" : "../");
			{
				icon(out, "fas fa-chevron-up");
			}
			out.end();
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
		out.attr("style", "display: table; margin-top: " + spacing + "px;");
		{
			out.begin(DIV);
			out.attr(STYLE_ATTR, "display: table-row;");
			for (int n = 0, cnt = row.getSize(); n < cnt; n++) {
				ImageInfo image = row.getImage(n);

				out.begin(DIV);
				out.openAttr(STYLE_ATTR);
				out.append("display: table-cell;");
				out.closeAttr();
				{
					out.begin(A);
					out.openAttr(STYLE_ATTR);
					out.append("display: block;");
					if (n > 0) {
						out.append("margin-left: ");
						out.append(spacing);
						out.append("px;");
					}
					out.closeAttr();
					out.openAttr(HREF_ATTR);
					{
						out.append(image.getName());
						out.append("?type=page");
					}
					out.closeAttr();
					{
						out.begin(IMG);
						{
							out.openAttr(SRC_ATTR);
							out.append(image.getName());
							out.append("?type=tn");
							out.closeAttr();
							
							out.attr(WIDTH_ATTR, row.getScaledWidth(n));
							out.attr(HEIGHT_ATTR, rowHeight);
						}
						out.end();
					}
					out.end();
				}
				out.end();
			}
			out.end();
		}
		out.end();
	}

	@Override
	public Void visit(ListingInfo resource, XmlAppendable out) throws IOException {
		out.begin(UL);
		out.attr(CLASS_ATTR, "listing");
		{
			for (FolderInfo folder : resource.getFolders()) {
				out.begin(LI);
				{
					out.begin(A);
					out.openAttr(HREF_ATTR);
					out.append(folder.getName());
					out.append('/');
					out.closeAttr();
					{
						out.append(folder.getName());
					}
					out.end();
				}
				out.end();
			}
		}
		out.end();
		
		writeAlbumToolbar(out, resource.getDepth(), false);

		return null;
	}

	@Override
	public Void visit(ImageInfo image, XmlAppendable out) throws IOException {
		ImageInfo previous = image.getPrevious();
		String previousUrl = previous == null ? null : previous.getName() + "?type=page";
		
		ImageInfo next = image.getNext();
		String nextUrl = next == null ? null : next.getName() + "?type=page";
		
		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		out.attr(CLASS_ATTR, "image-page");
		if (previousUrl != null) {
			out.attr("data-left", previousUrl);
		}
		if (nextUrl != null) {
			out.attr("data-right", nextUrl);
		}
		out.attr("data-up", "./");
		
		AlbumInfo album = image.getAlbum();
		List<ImageInfo> images = album.getImages();
		String homeUrl = images.get(0).getName() + "?type=page";
		out.attr("data-home", homeUrl);
		
		String endUrl = images.get(images.size() - 1).getName() + "?type=page";
		out.attr("data-end", endUrl);
		
		{
			switch (image.getKind()) {
				case IMAGE: {
					out.begin(DIV);
					out.attr(ID_ATTR, "image-container");
					{
						out.begin(IMG);
						out.attr(CLASS_ATTR, "image-display");
						out.attr(ID_ATTR, "image");
						out.attr(DRAGGABLE_ATTR, "false");
						out.attr(SRC_ATTR, image.getName());
						out.attr("data-width", image.getWidth());
						out.attr("data-height", image.getHeight());
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
						out.attr(SRC_ATTR, image.getName());
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
							icon(out, "fas fa-chevron-left");
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
							icon(out, "fas fa-chevron-right");
						}
					}
					out.end();
				}
				out.end();
			}
			
			writeAlbumToolbar(out, album.getDepth(), true);
		}
		out.end();
		return null;
	}

	private void icon(XmlAppendable out, String cssClass) throws IOException {
		out.begin(I);
		out.attr(CLASS_ATTR, cssClass);
		out.end();
	}

	private CharSequence parentUrl(int depth) {
		StringBuilder result = new StringBuilder();
		for (int n = 0; n < depth; n++) {
			result.append("../");
		}
		return result;
	}

	@Override
	public Void visit(ErrorInfo error, XmlAppendable out) throws IOException {
		out.begin(H1);
		out.append(error.getMessage());
		out.end();
		return null;
	}

}
