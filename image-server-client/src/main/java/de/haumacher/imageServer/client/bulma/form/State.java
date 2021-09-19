/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.bulma.form;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public enum State {

	NONE(""),
	SUCCESS("is-success"),
	DANGER("is-danger"),
	;

	private String _name;

	/** 
	 * Creates a {@link State}.
	 *
	 * @param string
	 */
	State(String name) {
		_name = name;
	}
	
	@Override
	public String toString() {
		return _name;
	}
}
