/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.nio.file.Path;

import de.haumacher.imageServer.shared.model.Resource;

/**
 * A path of a {@link Resource} being accessed.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class PathInfo {

	private Path _basePath;
	private Path _path;
	private File _file;
	
	/** 
	 * Creates a {@link PathInfo}.
	 *
	 * @param basePath
	 */
	public PathInfo(Path basePath) {
		this(basePath, null);
	}

	/** 
	 * Creates a {@link PathInfo}.
	 *
	 * @param basePath
	 * @param path
	 */
	public PathInfo(Path basePath, Path path) {
		_basePath = basePath;
		_path = path;
		_file = resolved().toFile();
	}

	private Path resolved() {
		return _path != null ? _basePath.resolve(_path) : _basePath;
	}
	
	/**
	 * The client-side view of this path.
	 */
	public String toPath() {
		return _path != null ? "/" + _path.toString() : "/";
	}
	
	/**
	 * Whether this is the top-level path.
	 */
	public boolean isRoot() {
		return _path == null;
	}

	/**
	 * The parent path of this one, if this is not the {@link #isRoot() root} path.
	 */
	public PathInfo parent() {
		return new PathInfo(_basePath, _path.getParent());
	}

	/** 
	 * The {@link File} represented by this {@link PathInfo}.
	 */
	public File toFile() {
		return _file;
	}
	
	@Override
	public int hashCode() {
		return _file.hashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		return obj instanceof PathInfo ? _file.equals(((PathInfo) obj)._file) : false;
	}

	/** 
	 * The name of the top-level resource.
	 */
	public String getName() {
		return _file.getName();
	}

	/** 
	 * Whether the accessed path is a directory.
	 */
	public boolean isDirectory() {
		return _file.isDirectory();
	}

	/** 
	 * The number of {@link #parent() ancestor} paths this path has.
	 */
	public int getDepth() {
		return _path == null ? 0 : _path.getNameCount();
	}

}
