/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

/**
 * Empty space inserted to a layout to make it's constraints acceptable. 
 */
public class Padding implements Content {

	private double _unitWidth;

	/** 
	 * Creates a {@link Padding}.
	 */
	public Padding(double unitWidth) {
		_unitWidth = unitWidth;
	}

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
		return visitor.visitPadding(this, arg);
	}

}
