/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourcePath {

	private String _path;

	/** 
	 * Creates a {@link ResourcePath}.
	 *
	 * @param hash
	 */
	public ResourcePath(String hash) {
		_path = toPath(hash);
	}

	/** 
	 * Navigates to the parent directory of the directory containing the current resource.
	 */
	private void gotoParentDir() {
		gotoCurrentDir();
		
		int length = _path.length();
		if (length > 1) {
			int index = _path.lastIndexOf('/', length - 2);
			if (index >= 0) {
				_path = _path.substring(0, index + 1);
			}
		}
	}

	/** 
	 * Navigates to the current directory of the resource currently being displayed.
	 */
	private void gotoCurrentDir() {
		if (!_path.endsWith("/")) {
			int index = _path.lastIndexOf('/');
			_path = _path.substring(0, index + 1);
		}
	}

	/** 
	 * Navigates to the root resource, if the given URL is absolute.
	 */
	private String processAbsoluteUrl(String url) {
		if (url.startsWith("/")) {
			_path = "/";
			url = url.substring(1);
		}
		return url;
	}

	public static String toPath(String hash) {
		if (hash == null) {
			return "/";
		}
		String decoded = com.google.gwt.http.client.URL.decode(hash);
		if (decoded.startsWith("#")) {
			decoded = decoded.substring(1);
		}
		return decoded.isEmpty() ? "/" : decoded;
	}

	/** 
	 * TODO
	 *
	 * @param href
	 * @return 
	 */
	public ResourcePath navigateTo(String href) {
		href = processAbsoluteUrl(href);
		gotoCurrentDir();
		
		String[] relative = href.split("(?<=/)");
		for (String name : relative) {
			switch (name) {
				case "":
					continue;
					
				case ".":
				case "./": {
					gotoCurrentDir();
					continue;
				}
					
				case "..":
				case "../": {
					gotoParentDir();
					break;
				}
					
				default:
					_path = _path + name;
					break;
			}
		}
		
		return this;
	}

	/** 
	 * TODO
	 *
	 * @return
	 */
	public String getPath() {
		return _path;
	}

}
