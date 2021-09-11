/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.util.xml.XmlFragment;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceControlProvider implements Resource.Visitor<XmlFragment, Void, RuntimeException> {
	
	/**
	 * Singleton {@link ResourceControlProvider} instance.
	 */
	public static final ResourceControlProvider INSTANCE = new ResourceControlProvider();

	private ResourceControlProvider() {
		// Singleton constructor.
	}

	@Override
	public XmlFragment visit(AlbumInfo album, Void arg) throws RuntimeException {
		return new AlbumDisplay(album);
	}

	@Override
	public XmlFragment visit(ListingInfo listing, Void arg) throws RuntimeException {
		return new ListingDisplay(listing);
	}

	@Override
	public XmlFragment visit(ImageInfo image, Void arg) throws RuntimeException {
		return new ImageDisplay(image);
	}

	@Override
	public XmlFragment visit(ErrorInfo error, Void arg) throws RuntimeException {
		return new ErrorDisplay(error);
	}

}
