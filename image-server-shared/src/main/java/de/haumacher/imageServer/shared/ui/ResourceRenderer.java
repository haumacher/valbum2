/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui;

import static de.haumacher.util.xml.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumProperties;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.util.xml.XmlAppendable;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceRenderer implements Resource.Visitor<Void, XmlAppendable, IOException> {
	
	/**
	 * Singleton {@link ResourceRenderer} instance.
	 */
	public static final ResourceRenderer INSTANCE = new ResourceRenderer();

	private ResourceRenderer() {
		// Singleton constructor.
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
		return null;
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
					out.attr(HREF_ATTR, image.getName());
					{
						out.begin(IMG);
						
						out.openAttr(SRC_ATTR);
						out.append(image.getName());
						out.append("?type=tn");
						out.closeAttr();
						
						out.attr(WIDTH_ATTR, row.getScaledWidth(n));
						out.attr(HEIGHT_ATTR, rowHeight);
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
		{
			out.begin(LI);
			{
				out.begin(A);
				out.attr(HREF_ATTR, "..");
				{
					out.append("<- Go back");
				}
				out.end();
			}
			out.end();

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
		return null;
	}

	@Override
	public Void visit(ImageInfo image, XmlAppendable out) throws IOException {
		out.begin(IMG);
		out.attr(SRC_ATTR, image.getName());
		out.endEmpty();
		return null;
	}

	@Override
	public Void visit(ErrorInfo error, XmlAppendable out) throws IOException {
		out.begin(H1);
		out.append(error.getMessage());
		out.end();
		return null;
	}

}
