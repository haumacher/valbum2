/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import de.haumacher.imageServer.client.app.ResourceHandler;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;

/**
 * Creates a display for a {@link Resource}.
 * 
 * <p>
 * The visit argument is the path of the visited {@link Resource}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceControlProvider implements Resource.Visitor<Display, ResourceHandler> {
	
	/**
	 * Singleton {@link ResourceControlProvider} instance.
	 */
	public static final ResourceControlProvider INSTANCE = new ResourceControlProvider();

	private ResourceControlProvider() {
		// Singleton constructor.
	}

	@Override
	public Display visit(AlbumInfo album, ResourceHandler handler) {
		return new AlbumDisplay(album, handler);
	}

	@Override
	public Display visit(ListingInfo listing, ResourceHandler handler) {
		return new ListingDisplay(listing, handler);
	}

	@Override
	public Display visit(ImageInfo image, ResourceHandler handler) {
		return new ImageDisplay(image, handler);
	}

	@Override
	public Display visit(ErrorInfo error, ResourceHandler handler) {
		return new ErrorDisplay(error);
	}

}
