/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.util.ToImage;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceControlProvider implements Resource.Visitor<Display, Void> {
	
	/**
	 * Singleton {@link ResourceControlProvider} instance.
	 */
	public static final ResourceControlProvider INSTANCE = new ResourceControlProvider();

	private ResourceControlProvider() {
		// Singleton constructor.
	}

	@Override
	public Display visit(AlbumInfo album, Void arg) {
		return new AlbumDisplay(album);
	}

	@Override
	public Display visit(ListingInfo listing, Void arg) {
		return new ListingDisplay(listing);
	}

	@Override
	public Display visit(ImageInfo image, Void arg) {
		return new ImageDisplay(image);
	}

	@Override
	public Display visit(ErrorInfo error, Void arg) {
		return new ErrorDisplay(error);
	}

	@Override
	public Display visit(ImageGroup self, Void arg) {
		return new ImageDisplay(ToImage.toImage(self));
	}

}
