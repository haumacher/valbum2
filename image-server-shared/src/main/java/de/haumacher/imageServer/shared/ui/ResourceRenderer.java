/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumProperties;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ImageInfo.Kind;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.util.xml.Renderer;
import de.haumacher.util.xml.XmlAppendable;

/**
 * {@link de.haumacher.imageServer.shared.model.Resource.Visitor} rendering a {@link Resource} to a {@link XmlAppendable}.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceRenderer implements Resource.Visitor<Void, XmlAppendable, IOException>, Renderer<Resource> {
	
	private static final String TOOLBAR_CLASS = "toolbar";

	private static final String TOOLBAR_RIGHT_CLASS = "tb-right";
	
	private int _width;

	/**
	 * Creates a {@link ResourceRenderer}.
	 *
	 * @param width The available page width.
	 */
	public ResourceRenderer(int width) {
		_width = width;
	}
	
	@Override
	public void write(XmlAppendable out, Resource value) throws IOException {
		value.visit(this, out);
	}

	@Override
	public Void visit(AlbumInfo album, XmlAppendable out) throws IOException {
		String parentUrl = parentUrl(album.getDepth());

		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		if (parentUrl != null) {
			out.attr(DataAttributes.DATA_ESCAPE, parentUrl);
		}
		
		out.begin(H1);
		AlbumProperties header = album.getHeader();
		out.append(header.getTitle());
		out.end();
		out.begin(H2);
		out.append(header.getSubTitle());
		out.end();

		out.begin(DIV);
		out.attr(CLASS_ATTR, "image-rows");
		out.attr(STYLE_ATTR, "width: " + _width + "px;");
		{
			ImageRow row = new ImageRow(_width, 400);
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
		
		if (parentUrl != null) {
			writeAlbumToolbar(out, false, parentUrl);
		}
		out.end();

		return null;
	}

	private void writeAlbumToolbar(XmlAppendable out, boolean isFile,
			CharSequence parentUrl) throws IOException {
		out.begin(DIV);
		out.attr(CLASS_ATTR, ResourceRenderer.TOOLBAR_CLASS);
		{
			out.begin(A);
			out.attr(HREF_ATTR, parentUrl);
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
			
			out.begin(DIV);
			out.attr(CLASS_ATTR, TOOLBAR_RIGHT_CLASS);
			boolean editMode = false;
			if (editMode) {
				icon(out, "fas fa-save");
			} else {
				icon(out, "fas fa-edit");
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

	@Override
	public Void visit(ListingInfo resource, XmlAppendable out) throws IOException {
		out.begin(H1);
		out.append(resource.getTitle());
		out.end();

		out.begin(DIV);
		out.attr(CLASS_ATTR, "listing");
		{
			for (FolderInfo folder : resource.getFolders()) {
				out.begin(A);
				out.attr(CLASS_ATTR, "entry");
				out.openAttr(HREF_ATTR);
				{
					out.append(folder.getName());
					out.append('/');
				}
				out.closeAttr();
				{
					out.begin(DIV);
					out.attr(CLASS_ATTR, "preview");
					ThumbnailInfo indexPicture = folder.getIndexPicture();
					if (indexPicture != null) {
						out.begin(IMG);
						out.attr(CLASS_ATTR, "image-display");
						out.attr(STYLE_ATTR, "transform: scale(" + indexPicture.getScale() + ") translate(" + indexPicture.getTx() + "px, " + indexPicture.getTy() + "px);");
						out.openAttr(SRC_ATTR);
						{
							out.append(folder.getName());
							out.append("/");
							out.append(indexPicture.getImage());
							out.append("?type=tn");
						}
						out.closeAttr();
						out.endEmpty();
					} else {
						out.begin(DIV);
						out.attr(CLASS_ATTR, "no-image");
						{
							out.begin(I);
							out.attr(CLASS_ATTR, "far fa-folder-open");
							out.end();
						}
						out.end();
					}
					out.end();
					
					out.begin(DIV);
					out.attr(CLASS_ATTR, "title");
					{
						out.begin(DIV);
						out.append(folder.getTitle());
						out.end();
						
						if (folder.getSubTitle() != null) {
							out.begin(DIV);
							out.attr(CLASS_ATTR, "subtitle");
							out.append(folder.getSubTitle());
							out.end();
						}
					}
					out.end();
				}
				out.end();
			}
		}
		out.end();
		
		String parentUrl = parentUrl(resource.getDepth());
		if (parentUrl != null) {
			writeAlbumToolbar(out, false, parentUrl);
		}

		return null;
	}

	@Override
	public Void visit(ImageInfo image, XmlAppendable out) throws IOException {
		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		out.attr(CLASS_ATTR, "image-page");

		String previous = image.getPrevious();
		String previousUrl = previous == null ? null : previous + "?type=page";
		if (previousUrl != null) {
			out.attr(DataAttributes.DATA_LEFT, previousUrl);
		}
		
		String next = image.getNext();
		String nextUrl = next == null ? null : next + "?type=page";
		if (nextUrl != null) {
			out.attr(DataAttributes.DATA_RIGHT, nextUrl);
		}

		out.attr(DataAttributes.DATA_ESCAPE, "./");
		out.attr(DataAttributes.DATA_UP, "./");
		out.attr(DataAttributes.DATA_HOME, image.getHome() + "?type=page");
		out.attr(DataAttributes.DATA_END, image.getEnd() +  "?type=page");
		
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
						out.attr(DataAttributes.DATA_WIDTH, image.getWidth());
						out.attr(DataAttributes.DATA_HEIGHT, image.getHeight());
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
						out.end();
					}
					out.end();
				}
				out.end();
			}
			
			String parentUrl = parentUrl(image.getDepth());
			if (parentUrl != null) {
				writeAlbumToolbar(out, true, parentUrl);
			}
		}
		out.end();
		return null;
	}

	private void icon(XmlAppendable out, String cssClass) throws IOException {
		out.begin(I);
		out.attr(CLASS_ATTR, cssClass);
		out.end();
	}

	private String parentUrl(int depth) {
		if (depth == 0) {
			return null;
		}
		StringBuilder result = new StringBuilder();
		for (int n = 0; n < depth; n++) {
			result.append("../");
		}
		return result.toString();
	}

	@Override
	public Void visit(ErrorInfo error, XmlAppendable out) throws IOException {
		out.begin(H1);
		out.append(error.getMessage());
		out.end();
		return null;
	}

}
