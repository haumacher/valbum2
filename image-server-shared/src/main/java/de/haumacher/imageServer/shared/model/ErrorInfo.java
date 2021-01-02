/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ErrorInfo implements Resource {
	
	private String _message;

	/** 
	 * Creates a {@link ErrorInfo}.
	 *
	 */
	public ErrorInfo(String message) {
		this();
		_message = message;
	}

	/** 
	 * Creates a {@link ErrorInfo}.
	 */
	protected ErrorInfo() {
		super();
	}

	@Override
	public Type type() {
		return Type.error;
	}

	/** 
	 * TODO
	 *
	 * @return
	 */
	public String getMessage() {
		return _message;
	}

	@Override
	public <R, A, E extends Throwable> R visit(Visitor<R, A, E> v, A arg) throws E {
		return v.visit(this, arg);
	}

	@Override
	public void writeContents(JsonWriter json) throws IOException {
		json.name("message");
		json.value(_message);
	}
	
	@Override
	public void readFrom(JsonReader json) throws IOException {
		json.beginObject();
		while (json.hasNext()) {
			switch (json.nextName()) {
				case "message": _message = json.nextString(); break;
			}
		}
		json.endObject();
	}

	/**
	 * Reads an {@link ErrorInfo} as JSON object from the given {@link JsonReader}.
	 */
	public static ErrorInfo read(JsonReader json) throws IOException {
		ErrorInfo result = new ErrorInfo();
		result.readFrom(json);
		return result;
	}

}
