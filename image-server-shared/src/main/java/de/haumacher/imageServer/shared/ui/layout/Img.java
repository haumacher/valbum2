/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.util.ToImage;

/**
 * An atomic image placed in a {@link Row}.
 */
public class Img implements Content {
	
	private final double _unitWidth;
	private AbstractImage _image;
	
	/** 
	 * Creates a {@link Img}.
	 */
	public Img(AbstractImage image) {
		_image = image;
		ImagePart representative = ToImage.toImage(image);
		_unitWidth = ((double) representative.getWidth()) / representative.getHeight();
	}
	
	/**
	 * The {@link AbstractImage} represented by this {@link Content}.
	 */
	public AbstractImage getImage() {
		return _image;
	}
	
	/**
	 * The width of the image, if it was scaled to a height of <code>1.0</code>.
	 */
	@Override
	public double getUnitWidth() {
		return _unitWidth;
	}
	
	@Override
	public int getUnitHeight() {
		return 1;
	}
	
	@Override
	public <R, A, E extends Throwable> R visit(ContentVisitor<R, A, E> visitor, A arg) throws E {
		return visitor.visitImg(this, arg);
	}
	
}