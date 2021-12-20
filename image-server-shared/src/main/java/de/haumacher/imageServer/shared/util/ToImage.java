/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.util;

import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.AbstractImage.Visitor;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;

/**
 * {@link Visitor} selecting the {@link ImagePart} to display for a given {@link AbstractImage}.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ToImage implements AbstractImage.Visitor<ImagePart, Void, RuntimeException> {
	
	/**
	 * Singleton {@link ToImage} instance.
	 */
	public static final ToImage INSTANCE = new ToImage();

	private ToImage() {
		// Singleton constructor.
	}

	@Override
	public ImagePart visit(ImageGroup self, Void arg) {
		return self.getImages().get(self.getRepresentative());
	}

	@Override
	public ImagePart visit(ImagePart self, Void arg) {
		return self;
	}

	/**
	 * Invokes {@link ToImage} on the given {@link AbstractImage}.
	 */
	public static ImagePart toImage(AbstractImage<?> image) {
		return image.visit(INSTANCE, null);
	}

}
