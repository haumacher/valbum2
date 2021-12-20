/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

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
public class ToPath implements Resource.Visitor<String, Void, RuntimeException> {
	
	/**
	 * Singleton {@link ToPath} instance.
	 */
	public static final ToPath INSTANCE = new ToPath();

	private ToPath() {
		// Singleton constructor.
	}

	@Override
	public String visit(ImageGroup self, Void arg) throws RuntimeException {
		return self.getImages().get(self.getRepresentative()).visit(this, null) + App.ALTERNATIVES_SUFFIX;
	}

	@Override
	public String visit(ImagePart self, Void arg) throws RuntimeException {
		return self.getOwner().visit(this, arg) + self.getName();
	}

	@Override
	public String visit(AlbumInfo self, Void arg) throws RuntimeException {
		return self.getPath();
	}

	@Override
	public String visit(ListingInfo self, Void arg) throws RuntimeException {
		return self.getPath();
	}

	@Override
	public String visit(ErrorInfo self, Void arg) throws RuntimeException {
		throw new UnsupportedOperationException();
	}

	@Override
	public String visit(Heading self, Void arg) throws RuntimeException {
		throw new UnsupportedOperationException();
	}

}
