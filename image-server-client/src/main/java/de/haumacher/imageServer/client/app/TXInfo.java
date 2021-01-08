/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class TXInfo {

	private double _scale = 1.0;
	private double _tx = 0.0;
	private double _ty = 0.0;

	/** 
	 * TODO
	 *
	 * @param scale
	 * @param tx
	 * @param ty
	 */
	public void set(double scale, double tx, double ty) {
		_scale = scale;
		_tx = tx;
		_ty = ty;
		
	}
	
	/**
	 * TODO
	 */
	public double getScale() {
		return _scale;
	}
	
	/**
	 * TODO
	 */
	public double getTx() {
		return _tx;
	}
	
	/**
	 * TODO
	 */
	public double getTy() {
		return _ty;
	}

}
