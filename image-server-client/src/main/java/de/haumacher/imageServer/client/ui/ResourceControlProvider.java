/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceControlProvider implements Resource.Visitor<Display, Void, RuntimeException> {
	
	/**
	 * Singleton {@link ResourceControlProvider} instance.
	 */
	public static final ResourceControlProvider INSTANCE = new ResourceControlProvider();

	private ResourceControlProvider() {
		// Singleton constructor.
	}

	@Override
	public Display visit(AlbumInfo album, Void arg) throws RuntimeException {
		return new AlbumDisplay(album);
	}

	@Override
	public Display visit(ListingInfo listing, Void arg) throws RuntimeException {
		return new ListingDisplay(listing);
	}

	@Override
	public Display visit(ImageInfo image, Void arg) throws RuntimeException {
		return new ImageDisplay(image);
	}

	@Override
	public Display visit(ErrorInfo error, Void arg) throws RuntimeException {
		return new ErrorDisplay(error);
	}

}
