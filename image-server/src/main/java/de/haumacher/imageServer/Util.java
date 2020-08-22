/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.IOException;
import java.io.InputStream;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

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

	public static void sendBytes(HttpServletResponse response, InputStream in) throws IOException {
		ServletOutputStream out = response.getOutputStream();
		
		byte[] buffer = new byte[64*1024];
		while (true) {
			int direct = in.read(buffer);
			if (direct < 0) {
				break;
			}
			out.write(buffer, 0, direct);
		}
	}

}
