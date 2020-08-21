/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Util {

	static String suffix(String name) {
		int sepIndex = name.lastIndexOf('.');
		if (sepIndex < 0) {
			return null;
		}
	
		return name.substring(sepIndex + 1).toLowerCase();
	}

}
