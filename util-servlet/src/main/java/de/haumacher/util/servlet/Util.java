/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.servlet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Collection of static utilities.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Util {

	/**
	 * The lower-case file name suffix of the given file name, or <code>null</code> if the given file name has no suffix.
	 */
	public static String suffix(String fileName) {
		int sepIndex = fileName.lastIndexOf('.');
		if (sepIndex < 0) {
			return null;
		}
	
		return fileName.substring(sepIndex + 1).toLowerCase();
	}

	public static void sendBytes(HttpServletResponse response, InputStream in) throws IOException {
		OutputStream out = response.getOutputStream();
		transfer(in, out);
	}

	/** 
	 * TODO
	 *
	 * @param in
	 * @param out
	 * @throws IOException
	 */
	public static void transfer(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[64*1024];
		while (true) {
			int direct = in.read(buffer);
			if (direct < 0) {
				break;
			}
			out.write(buffer, 0, direct);
		}
	}

	/** 
	 * TODO
	 *
	 * @param src
	 * @param dst
	 * @throws IOException 
	 * @throws FileNotFoundException 
	 */
	public static void copy(File src, File dst) throws IOException {
		try (FileInputStream in = new FileInputStream(src)) {
			try (FileOutputStream out = new FileOutputStream(dst)) {
				transfer(in, out);
			}
		}
	}

}
