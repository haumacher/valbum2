/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

/**
 * TODO
 * @author  <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageDimension {
	private final int width;

	private final int height;

	/** 
	 * Creates a {@link de.haumacher.imageServer.ImageDimension}.
	 */
	public ImageDimension(int width, int height) {
		this.width = width;
		this.height = height;
	}

	/**
	 * Height / width of the image (0.66 for a landsape 3:2 image).
	 */
	public double getRatio() {
		return ((double) getHeight()) / getWidth();
	}

	/**
	 * TODO
	 */
	public int getWidth() {
		return width;
	}

	/**
	 * TODO
	 */
	public int getHeight() {
		return height;
	}
	
}