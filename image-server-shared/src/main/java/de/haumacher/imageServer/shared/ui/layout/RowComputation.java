/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

/**
 * Algorithm for placing images into rows.
 * 
 * @see {@link RowBuffer}
 */
interface RowComputation {

	/**
	 * Adds the given image.
	 *
	 * @param img The image to place next.
	 * @return The {@link RowComputation} algorithm to continue with.
	 */
	RowComputation addImage(Content img);

	/**
	 * Flushes buffers and completes the computation.
	 */
	void end();
	
}