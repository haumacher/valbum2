/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui;

import static de.haumacher.util.xml.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
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
	public Void visit(AlbumInfo resource, XmlAppendable arg) throws IOException {
		//  TODO: Automatically created
		return null;
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

}
