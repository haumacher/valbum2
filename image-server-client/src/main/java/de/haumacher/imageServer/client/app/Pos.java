/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Pos {

	private final double _x;
	private final double _y;

	/** 
	 * Creates a {@link Pos}.
	 *
	 * @param x
	 * @param y
	 */
	public Pos(double x, double y) {
		_x = x;
		_y = y;
	}
	
	/**
	 * TODO
	 */
	public double getX() {
		return _x;
	}
	
	/**
	 * TODO
	 */
	public double getY() {
		return _y;
	}

}
