/*
 * Copyright (c) 2023 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.upload;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;

import org.apache.commons.fileupload2.core.FileItem;
import org.apache.commons.fileupload2.core.FileItemHeaders;

/**
 * An upload stored to a {@link File}.
 */
public class UploadItem implements FileItem<UploadItem> {

	private final File _repository;
	private final String _contentType;
	private final String _fileName;
	
	private String _fieldName;
	private boolean _isFormField;
	private FileItemHeaders _headers;

	private final File _upload;
	
	/** 
	 * Creates a {@link UploadItem}.
	 * @param fileItemHeaders 
	 */
	public UploadItem(File repository, String fieldName, String contentType, boolean isFormField, String fileName, FileItemHeaders fileItemHeaders) {
		_repository = repository;
		_fieldName = fieldName;
		_contentType = contentType;
		_isFormField = isFormField;
		_fileName = fileName;
		_headers = fileItemHeaders;

		_upload = createTmpFile(fileName);
	}
	
	/**
	 * The uploaded file.
	 */
	public File getUpload() {
		return _upload;
	}

	private File createTmpFile(String fileName) throws IOError {
		int start = fileName.lastIndexOf('/');
		if (start < 0) {
			start = 0;
		}

		int stop = fileName.lastIndexOf('.');
		if (stop < 0) {
			stop = fileName.length();
		}
		
		if (stop < start) {
			stop = fileName.length();
		}
		
		String prefix = start < stop ? fileName.substring(start, stop) : "upload";
		String suffix = fileName.substring(stop);
		
		try {
			return File.createTempFile(prefix, suffix, _repository);
		} catch (IOException ex) {
			throw new IOError(ex);
		}
	}

	@Override
	public FileItemHeaders getHeaders() {
		return _headers;
	}

	@Override
	public UploadItem setHeaders(FileItemHeaders headers) {
		_headers = headers;
		return this;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return new FileInputStream(_upload);
	}

	@Override
	public String getContentType() {
		return _contentType;
	}

	@Override
	public String getName() {
		return _fileName;
	}

	@Override
	public boolean isInMemory() {
		return false;
	}

	@Override
	public long getSize() {
		return _upload.length();
	}

	@Override
	public UploadItem delete() {
		_upload.delete();
		return this;
	}

	@Override
	public String getFieldName() {
		return _fieldName;
	}

	@Override
	public UploadItem setFieldName(String name) {
		_fieldName = name;
		return this;
	}

	@Override
	public boolean isFormField() {
		return _isFormField;
	}

	@Override
	public UploadItem setFormField(boolean state) {
		_isFormField = state;
		return this;
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return new FileOutputStream(_upload);
	}

	@Override
	public byte[] get() {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getString() {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getString(Charset toCharset) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public UploadItem write(Path file) throws IOException {
		throw new UnsupportedOperationException();
	}

}
