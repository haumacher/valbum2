/*
 * Copyright (c) 2023 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.upload;

import java.io.File;

import org.apache.commons.fileupload2.core.FileItemFactory;

/**
 * {@link FileItemFactory} for uploads that can be moved to their target location without additional copy.
 */
public class UploadFactory implements FileItemFactory<UploadItem> {

	private File _repository;

	/** 
	 * Creates a {@link UploadFactory}.
	 */
	public UploadFactory(File repository) {
		_repository = repository;
	}

	@Override
	public UploadBuilder fileItemBuilder() {
		return new UploadBuilder(_repository);
	}

}
