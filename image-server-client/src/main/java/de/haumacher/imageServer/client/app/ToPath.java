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

/**
 * Visitor computing the path of a {@link Resource}.
 */
public class ToPath implements Resource.Visitor<String, DisplayMode, RuntimeException> {
	
	/** 
	 * Retrieves the canonical path where the given {@link Resource} is located.
	 */
	public static String toPath(Resource resource, DisplayMode mode) {
		return resource.visit(INSTANCE, mode);
	}

	/**
	 * Singleton {@link ToPath} instance.
	 */
	private static final ToPath INSTANCE = new ToPath();

	private ToPath() {
		// Singleton constructor.
	}

	@Override
	public String visit(ImageGroup self, DisplayMode arg) throws RuntimeException {
		ImagePart representative = self.getImages().get(self.getRepresentative());
		String representativePath = defaultPath(representative);
		
		switch (arg) {
		case DEFAULT:
			return representativePath;
		case DETAIL:
			return representativePath + App.ALTERNATIVES_SUFFIX;
		}
		throw new IllegalArgumentException("No such mode: " + arg);
	}

	@Override
	public String visit(ImagePart self, DisplayMode arg) throws RuntimeException {
		ImageGroup group = self.getGroup();
		switch (arg) {
		case DEFAULT:
			if (group != null) {
				if (self != group.getImages().get(group.getRepresentative())) {
					// Not the default display of a group: Can only be shown in detail mode.
					return detailPath(group, self);
				}
			}
			return defaultPath(self);
		case DETAIL:
			return detailPath(group, self);
		}
		throw new IllegalArgumentException("No such mode: " + arg);
	}

	private String detailPath(ImageGroup group, ImagePart self) {
		return group.visit(this, DisplayMode.DETAIL) + self.getName();
	}

	private String defaultPath(ImagePart self) {
		return self.getOwner().visit(this, DisplayMode.DEFAULT) + self.getName();
	}

	@Override
	public String visit(AlbumInfo self, DisplayMode arg) throws RuntimeException {
		return self.getPath();
	}

	@Override
	public String visit(ListingInfo self, DisplayMode arg) throws RuntimeException {
		return self.getPath();
	}

	@Override
	public String visit(ErrorInfo self, DisplayMode arg) throws RuntimeException {
		throw new UnsupportedOperationException();
	}

	@Override
	public String visit(Heading self, DisplayMode arg) throws RuntimeException {
		throw new UnsupportedOperationException();
	}

}
