/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

/**
 * A part of a {@link Row}.
 */
public interface Content {
	
	/**
	 * The width of the content, if it's height is scaled to <code>1.0</code>.
	 */
	double getUnitWidth();
	
	/** 
	 * The number of grid rows, this {@link Content} spans.
	 */
	int getUnitHeight();

	/**
	 * Whether this is a portrait image, with a height considerably larger than its width.
	 */
	default boolean isPortrait() {
		return getUnitWidth() <= 0.75;
	}
	
	/**
	 * Visits this {@link Content} with the given {@link ContentVisitor}
	 */
	<R,A,E extends Throwable> R visit(ContentVisitor<R,A,E> visitor, A arg) throws E;


}