/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Utilities for working with Maven dependencies at runtime.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class MavenUtil {

	/**
	 * Loads the version of an included library.
	 *
	 * @param groupId
	 *        The group ID of the library.
	 * @param artifactId
	 *        The artifact ID of the library.
	 * @return The version string of the library, or <code>null</code> if no
	 *         such library was included.
	 */
	public static String artifactVersion(String groupId, String artifactId) {
		try {
			InputStream in = Page.class.getResourceAsStream(
				"/META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties");
			if (in == null) {
				return null;
			}
	
			Properties pomProperties = new Properties();
			try {
				pomProperties.load(in);
			} finally {
				in.close();
			}
			return pomProperties.getProperty("version");
		} catch (IOException ex) {
			return null;
		}
	}

}
