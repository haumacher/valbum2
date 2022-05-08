/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A horizontal layout of {@link Content}.
 */
public class Row implements Content, Iterable<Content> {
	
	private final List<Content> _contents = new ArrayList<>();
	
	private double _unitWidth;

	private int _height = 1;
	
	final void addContent(Content content) {
		_contents.add(content);
		_unitWidth += content.getUnitWidth();
		_height = Math.max(_height, content.getUnitHeight());
	}
	
	@Override
	public double getUnitWidth() {
		return _unitWidth;
	}
	
	@Override
	public int getUnitHeight() {
		return _height;
	}

	/**
	 * Whether this {@link Row} has no contents.
	 */
	final boolean isEmpty() {
		return _contents.isEmpty();
	}

	@Override
	public Iterator<Content> iterator() {
		return _contents.iterator();
	}
	
	@Override
	public <R, A, E extends Throwable> R visit(ContentVisitor<R, A, E> visitor, A arg) throws E {
		return visitor.visitRow(this, arg);
	}

	/** 
	 * The number of {@link Content}s in this {@link Row}.
	 */
	public int size() {
		return _contents.size();
	}

	void end(double minWidth) {
		double unitWidth = getUnitWidth();
		if (unitWidth < minWidth) {
			addContent(new Padding(minWidth - unitWidth));
		}
	}
}