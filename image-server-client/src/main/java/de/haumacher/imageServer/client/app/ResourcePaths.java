/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

import de.haumacher.imageServer.client.ui.DisplayMode;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.Resource.Visitor;

/**
 * Utility for computing paths for {@link Resource}s.
 */
public class ResourcePaths {

	/** 
	 * The base path to set on a page displaying the given resource.
	 */
	public static String toBasePath(Resource resource, DisplayMode mode) {
		return resource.visit(new Visitor<String, DisplayMode, RuntimeException>() {
			@Override
			public String visit(AlbumInfo self, DisplayMode arg) throws RuntimeException {
				return self.getPath();
			}

			@Override
			public String visit(ListingInfo self, DisplayMode arg) throws RuntimeException {
				return self.getPath();
			}

			@Override
			public String visit(ImageGroup self, DisplayMode arg) throws RuntimeException {
				return self.getOwner().getPath();
			}

			@Override
			public String visit(ImagePart self, DisplayMode arg) throws RuntimeException {
				return self.getOwner().getPath();
			}

			@Override
			public String visit(Heading self, DisplayMode arg) throws RuntimeException {
				throw new UnsupportedOperationException();
			}
			
			@Override
			public String visit(ErrorInfo self, DisplayMode arg) throws RuntimeException {
				throw new UnsupportedOperationException();
			}
		}, mode);
	}

}
