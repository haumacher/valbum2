/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.app.App;
import de.haumacher.imageServer.client.app.ResourceHandler;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.shared.ui.CssClasses;
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
	 */
	public ListingDisplay(ListingInfo listing, ResourceHandler handler) {
		super(handler);
		_listing = listing;
	}
	
	@Override
	protected Resource getResource() {
		return _listing;
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		out.begin(H1);
		out.classAttr(CssClasses.HEADER);
		out.append(_listing.getTitle());
		out.end();

		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.LISTING);
		{
			for (FolderInfo folder : _listing.getFolders()) {
				out.begin(A);
				out.attr(CLASS_ATTR, CssClasses.ENTRY);
				out.openAttr(HREF_ATTR);
				{
					out.append(folder.getName());
					out.append('/');
				}
				out.closeAttr();
				{
					out.begin(DIV);
					out.attr(CLASS_ATTR, CssClasses.PREVIEW);
					ThumbnailInfo indexPicture = folder.getIndexPicture();
					if (indexPicture != null) {
						out.begin(IMG);
						out.attr(CLASS_ATTR, CssClasses.IMAGE_DISPLAY);
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
						out.attr(CLASS_ATTR, CssClasses.NO_IMAGE);
						{
							out.begin(I);
							out.attr(CLASS_ATTR, "far fa-folder-open");
							out.end();
						}
						out.end();
					}
					out.end();
					
					out.begin(DIV);
					out.attr(CLASS_ATTR, CssClasses.TITLE);
					{
						out.begin(DIV);
						out.append(folder.getTitle());
						out.end();
						
						if (folder.getSubTitle() != null) {
							out.begin(DIV);
							out.attr(CLASS_ATTR, CssClasses.SUBTITLE);
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
		
		String parentUrl = RenderUtil.parentUrl(_listing.getPath());
		writeAlbumToolbar(out, false, e -> {App.getInstance().gotoTarget(parentUrl); e.stopPropagation(); e.preventDefault();});
	}

}
