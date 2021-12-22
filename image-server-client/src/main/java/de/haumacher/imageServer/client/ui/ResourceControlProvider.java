/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import de.haumacher.imageServer.client.app.DefaultResourceHandler;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
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
public class ResourceControlProvider implements Resource.Visitor<Display, DisplayMode, RuntimeException> {
	
	/**
	 * Singleton {@link ResourceControlProvider} instance.
	 */
	public static final ResourceControlProvider INSTANCE = new ResourceControlProvider();

	private ResourceControlProvider() {
		// Singleton constructor.
	}

	@Override
	public Display visit(AlbumInfo album, DisplayMode arg) {
		return new AlbumDisplay(album, handler());
	}

	@Override
	public Display visit(ListingInfo listing, DisplayMode arg) {
		return new ListingDisplay(listing, handler());
	}

	@Override
	public Display visit(ImagePart self, DisplayMode arg) {
		return new ImageDisplay(self, arg, handler());
	}
	
	@Override
	public Display visit(ImageGroup self, DisplayMode arg) throws RuntimeException {
		switch (arg) {
		case DEFAULT:
			return new ImageDisplay(self, DisplayMode.DEFAULT, handler());
		case DETAIL:
			return new GroupDisplay(self, handler());
		}
		throw new IllegalArgumentException("No such mode: " + arg);
	}

	private DefaultResourceHandler handler() {
		return new DefaultResourceHandler();
	}
	
	@Override
	public Display visit(ErrorInfo error, DisplayMode arg) {
		return new ErrorDisplay(error);
	}
	
	@Override
	public Display visit(Heading self, DisplayMode arg) throws RuntimeException {
		throw new UnsupportedOperationException();
	}

}
