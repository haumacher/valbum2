/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

/**
 * Visitor for {@link Content}.
 * 
 * @see Content#visit
 */
public interface ContentVisitor<R, A, E extends Throwable> {
	
	R visitRow(Row content, A arg) throws E;
	R visitImg(Img content, A arg) throws E;
	R visitDoubleRow(DoubleRow content, A arg) throws E;
	R visitPadding(Padding content, A arg) throws E;

}
