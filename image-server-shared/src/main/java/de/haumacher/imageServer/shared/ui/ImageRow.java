/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui;

import java.util.ArrayList;
import java.util.List;

import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.util.ToImage;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageRow {

	/** @see #getSpacing() */
	private int _spacing = 2;
	
	private final int _width;
	private final int _maxHeight;
	
	private final List<AbstractImage> _images = new ArrayList<>();
	
	private double _sumNormWidth = 0.0;

	/** 
	 * Creates a {@link ImageRow}.
	 */
	public ImageRow(int width, int maxHeight) {
		_width = width;
		_maxHeight = maxHeight;
	}
	
	public int getSize() {
		return _images.size();
	}

	public AbstractImage getImage(int n) {
		return _images.get(n);
	}
	
	/**
	 * Number of pixels between two images in a row.
	 */
	public int getSpacing() {
		return _spacing;
	}

	public void add(AbstractImage image) {
		double normWidth = normWidth(image);
		double newNormWidth = _sumNormWidth + normWidth;
		_sumNormWidth = newNormWidth;
		_images.add(image);
	}

	public double getHeight() {
		if (isComplete()) {
			return 1.0 / _sumNormWidth * getAvailableWidth();
		} else {
			return _maxHeight;
		}
	}

	public double getScaledWidth(int n) {
		AbstractImage image = _images.get(n);
		if (isComplete()) {
			return normWidth(image) * (getAvailableWidth() / _sumNormWidth);
		} else {
			return normWidth(image) * _maxHeight;
		}
	}

	public boolean isComplete() {
		return _sumNormWidth >= getMinNormWidth();
	}

	private double getMinNormWidth() {
		return ((double) getAvailableWidth()) / _maxHeight;
	}

	/**
	 * With in pixels available for image data (excluding spacing).
	 */
	private int getAvailableWidth() {
		int size = getSize();
		return _width - (size > 1 ? _spacing * (size - 1) : 0);
	}

	private double normWidth(AbstractImage part) {
		ImagePart image = ToImage.toImage(part);
		return ((double) image.getWidth()) / image.getHeight();
	}

	public void clear() {
		_images.clear();
		_sumNormWidth = 0.0;
	}

}
