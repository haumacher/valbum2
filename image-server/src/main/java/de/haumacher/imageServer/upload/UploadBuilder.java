/*
 * Copyright (c) 2023 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.upload;

import java.io.File;
import java.io.IOException;

import org.apache.commons.fileupload2.core.FileItemFactory.AbstractFileItemBuilder;

/**
 * {@link AbstractFileItemBuilder} of {@link UploadItem}s.
 */
public class UploadBuilder extends AbstractFileItemBuilder<UploadItem, UploadBuilder> {

	private File _repository;

	/** 
	 * Creates a {@link UploadBuilder}.
	 *
	 * @param repository
	 */
	public UploadBuilder(File repository) {
		_repository = repository;
	}

	@Override
	public UploadItem get() throws IOException {
		return new UploadItem(_repository, getFieldName(), getContentType(), isFormField(), getFileName(), getFileItemHeaders());
	}

}
