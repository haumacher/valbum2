/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.model;

import java.io.IOException;

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
		_message = message;
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
	public void writeTo(JsonWriter json) throws IOException {
		json.beginObject();
		json.name("message");
		json.value(_message);
		json.endObject();
	}

}
