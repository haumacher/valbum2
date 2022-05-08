/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

/**
 * Buffer of {@link Row} used in a {@link RowComputation}.
 */
public interface RowBuffer {

	/** 
	 * Adds the next completed row.
	 */
	void addRow(Row newRow);

}
