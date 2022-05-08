/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

/**
 * A vertical placements of two {@link Row}s scaled to the same width.
 */
public class DoubleRow implements Content {

	private final double _unitWidth;
	
	private final Row _upper;
	private final Row _lower;

	private double _h1;

	private double _h2;

	/** 
	 * Creates a {@link DoubleRow}.
	 */
	public DoubleRow(Row upper, Row lower, double unitWidth, double h1, double h2) {
		_upper = upper;
		_lower = lower;
		_unitWidth = unitWidth;
		_h1 = h1;
		_h2 = h2;
	}
	
	/**
	 * The upper {@link Row}.
	 */
	public Row getUpper() {
		return _upper;
	}
	
	/**
	 * The lower {@link Row}.
	 */
	public Row getLower() {
		return _lower;
	}
	
	/**
	 * Relative height of {@link #getUpper()}.
	 */
	public double getH1() {
		return _h1;
	}
	
	/**
	 * Relative height of {@link #getLower()}.
	 */
	public double getH2() {
		return _h2;
	}

	@Override
	public double getUnitWidth() {
		return _unitWidth;
	}

	@Override
	public int getUnitHeight() {
		return 2;
	}

	@Override
	public <R, A, E extends Throwable> R visit(ContentVisitor<R, A, E> visitor, A arg) throws E {
		return visitor.visitDoubleRow(this, arg);
	}
	
}
