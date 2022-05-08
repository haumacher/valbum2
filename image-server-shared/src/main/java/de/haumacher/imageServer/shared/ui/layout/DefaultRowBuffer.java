/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link RowBuffer} building a {@link List} of {@link Row}s.
 */
public class DefaultRowBuffer implements RowBuffer {

	private List<Row> _rows = new ArrayList<>();

	@Override
	public void addRow(Row newRow) {
		_rows.add(newRow);
	}
	
	/**
	 * The created rows.
	 */
	public List<Row> getRows() {
		return _rows;
	}

}
