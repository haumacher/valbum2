/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.util.Orientations;

/**
 * A transformation info for an image.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class TXInfo {

	private final Orientation _orientation;
	private final double _width;
	private final double _height;
	
	private double _scale = 1.0;
	private double _tx = 0.0;
	private double _ty = 0.0;
	
	private double _customTx;
	private double _customTy;
	private double _customScale;
	
	private boolean _initial;

	public TXInfo(Orientation orientation, double width, double height, double scale, double tx, double ty) {
		_orientation = orientation;
		_width = width;
		_height = height;
		_customScale = _scale = scale;
		_customTx = _tx = tx;
		_customTy = _ty = ty;
		
		_initial = true;
	}
	
	/**
	 * Transformation to apply to the image to show it on the screen.
	 */
	public Orientation getOrientation() {
		return _orientation;
	}
	
	/**
	 * Width of the original image in image pixels without any transformation.
	 */
	public double getRawWidth() {
		return _width;
	}
	
	/**
	 * Height of the original image in image pixels without any transformation.
	 */
	public double getRawHeight() {
		return _height;
	}
	
	/**
	 * Width of the image in image pixels (without scaling) after the {@link #getOrientation()} transformation has been
	 * applied.
	 */
	public double getWidth() {
		return Orientations.width(_orientation, _width, _height);
	}
	
	/**
	 * Height the image in image pixels (without scaling) after the {@link #getOrientation()} transformation has been
	 * applied.
	 */
	public double getHeight() {
		return Orientations.height(_orientation, _width, _height);
	}
	
	/**
	 * The scaling applied to the image for showing it on the screen. A value of <code>1.0</code> means to show the
	 * image pixel by pixel in full resolution.
	 */
	public double getScale() {
		return _customScale;
	}
	
	/**
	 * The original scale given during construction.
	 */
	public double getOrigScale() {
		return _scale;
	}
	
	/**
	 * A translation in X direction to apply to the image after the {@link #getOrientation()} transformation has been
	 * applied.
	 */
	public double getTx() {
		return _customTx;
	}
	
	/**
	 * A translation in Y direction to apply to the image after the {@link #getOrientation()} transformation has been
	 * applied.
	 */
	public double getTy() {
		return _customTy;
	}

	/** 
	 * The CSS transform string to apply to the image.
	 */
	public String getTransform() {
		return Orientations.cssTransform(_orientation, _width, _height, _customScale, _customTx, _customTy);
	}

	/** 
	 * Sets a custom image transformation.
	 */
	public void setCustom(double tx, double ty, double scale) {
		_customTx = tx;
		_customTy = ty;
		_customScale = scale;
		
		_initial = false;
	}
	
	/**
	 * Resets scaling and translation to its original values.
	 */
	public void reset() {
		_customScale = _scale;
		_customTx = _tx;
		_customTy = _ty;
		
		_initial = true;
	}

	/** 
	 * Whether a {@link #setCustom(double, double, double) custom transformation} has been applied.
	 */
	public boolean isInitial() {
		return _initial;
	}

}
