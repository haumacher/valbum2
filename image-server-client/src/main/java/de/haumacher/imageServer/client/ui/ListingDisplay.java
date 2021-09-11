/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.util.gwt.dom.DomBuilder;
import de.haumacher.util.xml.XmlFragment;

/**
 * {@link XmlFragment} displaying a {@link ListingInfo} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ListingDisplay extends ResourceDisplay {

	private ListingInfo _listing;

	/** 
	 * Creates a {@link ListingDisplay}.
	 *
	 * @param listing
	 */
	public ListingDisplay(ListingInfo listing) {
		_listing = listing;
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		out.begin(H1);
		out.append(_listing.getTitle());
		out.end();

		out.begin(DIV);
		out.attr(CLASS_ATTR, "listing");
		{
			for (FolderInfo folder : _listing.getFolders()) {
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
		
		String parentUrl = RenderUtil.parentUrl(_listing.getDepth());
		if (parentUrl != null) {
			writeAlbumToolbar(out, false, parentUrl);
		}
	}

}
