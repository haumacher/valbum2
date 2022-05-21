/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.util;

import static de.haumacher.imageServer.shared.model.Orientation.*;

import de.haumacher.imageServer.shared.model.Orientation;

/**
 * Operations on {@link Orientation}s.
 */
public class Orientations {
	
	/**
	 * Parses the given JPEG orientation code.
	 */
	public static Orientation fromCode(int code) {
		if (code == 0) {
			// For safety reasons "unassigned".
			return Orientation.IDENTITY;
		}
		if (code < 1 || code > 8) {
			throw new IllegalArgumentException("Invalid JPEG orientation code: " + code);
		}
		return Orientation.values()[code - 1];
	}
	
	/**
	 * The JPEG orientation code.
	 */
	public static int toCode(Orientation self) {
		return self.ordinal() + 1;
	}
	
	/**
	 * The display width of an image with the given {@link Orientation} and raw pixel width and height.
	 */
	public static int width(Orientation self, int rawWidth, int rawHeight) {
		if (toCode(self) >= 5) {
			return rawHeight;
		} else {
			return rawWidth;
		}
	}
	
	/**
	 * The display width of an image with the given {@link Orientation} and raw pixel width and height.
	 */
	public static double width(Orientation self, double rawWidth, double rawHeight) {
		if (toCode(self) >= 5) {
			return rawHeight;
		} else {
			return rawWidth;
		}
	}
	
	/**
	 * The display height of an image with the given {@link Orientation} and raw pixel width and height.
	 */
	public static int height(Orientation self, int rawWidth, int rawHeight) {
		if (toCode(self) >= 5) {
			return rawWidth;
		} else {
			return rawHeight;
		}
	}
	
	/**
	 * The display height of an image with the given {@link Orientation} and raw pixel width and height.
	 */
	public static double height(Orientation self, double rawWidth, double rawHeight) {
		if (toCode(self) >= 5) {
			return rawWidth;
		} else {
			return rawHeight;
		}
	}
	
	/**
	 * Rotates this orientation to the left.
	 */
	public static Orientation rotL(Orientation self) {
		switch (self) {
		case IDENTITY:		return ROT_L;
		case FLIP_H:		return ROT_L_FLIP_V;
		case ROT_180:		return ROT_R;
		case FLIP_V:		return ROT_L_FLIP_H;
		case ROT_L_FLIP_V:	return FLIP_V; 
		case ROT_L: 		return ROT_180;
		case ROT_L_FLIP_H:	return FLIP_H;
		case ROT_R: 		return IDENTITY;
		}
		throw new IllegalArgumentException("No such orientation: " + self);
	}
	
	/**
	 * Rotates this orientation to the right.
	 */
	public static Orientation rotR(Orientation self) {
		switch (self) {
		case ROT_L:        return  IDENTITY;		     
		case ROT_L_FLIP_V: return  FLIP_H;		
		case ROT_R:        return  ROT_180;			
		case ROT_L_FLIP_H: return  FLIP_V;		
		case FLIP_V:       return  ROT_L_FLIP_V;		
		case ROT_180:      return  ROT_L;	
		case FLIP_H:       return  ROT_L_FLIP_H;		
		case IDENTITY:     return  ROT_R;	
		}
		throw new IllegalArgumentException("No such orientation: " + self);
	}
	
	/**
	 * Horizontally flips this orientation.
	 */
	public static Orientation flipH(Orientation self) {
		switch (self) {
		case IDENTITY:		return FLIP_H;
		case FLIP_H:		return IDENTITY;
		case ROT_180:		return FLIP_V;
		case FLIP_V:		return ROT_180;
		case ROT_L_FLIP_V:	return ROT_R; 
		case ROT_L: 		return ROT_L_FLIP_H;
		case ROT_L_FLIP_H:	return ROT_L;
		case ROT_R: 		return ROT_L_FLIP_V;
		}
		throw new IllegalArgumentException("No such orientation: " + self);
	}
	
	/**
	 * Vertically flips this orientation.
	 */
	public static Orientation flipV(Orientation self) {
		switch (self) {
		case IDENTITY:		return FLIP_V;
		case FLIP_H:		return ROT_180;
		case ROT_180:		return FLIP_H;
		case FLIP_V:		return IDENTITY;
		case ROT_L_FLIP_V:	return ROT_L; 
		case ROT_L: 		return ROT_L_FLIP_V;
		case ROT_L_FLIP_H:	return ROT_R;
		case ROT_R: 		return ROT_L_FLIP_H;
		}
		throw new IllegalArgumentException("No such orientation: " + self);
	}
	
	/** 
	 * Combines this orientation with the given transformation.
	 */
	public static Orientation concat(Orientation self, Orientation tx) {
		switch (tx) {
		case IDENTITY:		return self;
		case FLIP_H:		return flipH(self);
		case ROT_180:		return rotL(rotL(self));
		case FLIP_V:		return flipV(self);
		case ROT_L_FLIP_V:	return flipV(rotL(self)); 
		case ROT_L: 		return rotL(self);
		case ROT_L_FLIP_H:	return flipH(rotL(self));
		case ROT_R: 		return rotR(self);
		}
		throw new IllegalArgumentException("No such orientation: " + tx);
	}
	
	/** 
	 * CSS style properties that transform the image into the correct orientation.
	 *
	 * @param imgWidth Width of the image.
	 * @param imgHeight Height of the image.
	 */
	public static String cssTransform(Orientation orientation, double imgWidth, double imgHeight, double scale) {
		return cssTransform(orientation, imgWidth, imgHeight, scale, 0, 0);
	}
	
	/** 
	 * CSS style properties that transform the image into the correct orientation.
	 *
	 * @param rawWidth Width of the image in its physical orientation.
	 * @param rawHeight Height of the image in its physical orientation.
	 * @param tx An extras translation in X direction.
	 * @param ty An extras translation in Y direction.
	 */
	public static String cssTransform(Orientation orientation, double rawWidth, double rawHeight, double scale, double tx, double ty) {
		String txStyle;
		switch (orientation) {
		case ROT_L:
			txStyle = " translate(" + tx + "px, " + ty + "px) scale(" + scale + ") translateY(" + rawWidth + "px) rotate(-90deg)";
			break;
		case ROT_R:
			txStyle = " translate(" + tx + "px, " + ty + "px) scale(" + scale + ") translateX(" + rawHeight + "px) rotate(90deg)";
			break;
		case ROT_180:
			txStyle = " translate(" + tx + "px, " + ty + "px) scale(" + scale + ") translate(" + rawWidth + "px, " + rawHeight + "px) rotate(180deg)";
			break;
		case IDENTITY:
			txStyle = " translate(" + tx + "px, " + ty + "px) scale(" + scale + ")";
			break;
		default: 
			txStyle = "";
		}
		return txStyle;
	}

	
}
