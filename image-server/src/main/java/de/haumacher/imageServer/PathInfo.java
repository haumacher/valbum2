/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.Resource;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

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
	 * The folder this path is resolved against.
	 *
	 * <p>
	 * The space of the user the path was built for, see
	 * {@link de.haumacher.imageServer.auth.AuthService#spaceRoot(de.haumacher.imageServer.auth.AuthService.Caller, Path)}:
	 * the base folder of the server for a library that was never migrated, a folder below it for
	 * every user with a space of their own. Whose space it is, is what decides which grants apply
	 * to this path, see
	 * {@link de.haumacher.imageServer.auth.AuthService#rights(de.haumacher.imageServer.auth.AuthService.Caller, PathInfo)}.
	 * </p>
	 */
	public Path getBasePath() {
		return _basePath;
	}

	/**
	 * This path relative to its {@link #getBasePath() space}, with <code>/</code> as separator.
	 *
	 * <p>
	 * The empty string is the space itself. These are the coordinates a
	 * {@link de.haumacher.imageServer.auth.GrantStore.Grant#getPath() grant} is given in.
	 * </p>
	 */
	public String relativePath() {
		return _path == null ? "" : _path.toString().replace(File.separatorChar, '/');
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
	 * The path of the entry with the given name in this folder.
	 *
	 * <p>
	 * The child keeps this path's base folder, so that it is the same {@link PathInfo} the servlet
	 * would build for a request addressing it, cache key and parent relation included.
	 * </p>
	 */
	public PathInfo child(String name) {
		return new PathInfo(_basePath, _path == null ? Paths.get(name) : _path.resolve(name));
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
