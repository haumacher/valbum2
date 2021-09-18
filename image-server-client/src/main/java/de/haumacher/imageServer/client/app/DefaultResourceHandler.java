/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

import java.io.IOException;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;

import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.io.StringW;
import de.haumacher.msgbuf.json.JsonWriter;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class DefaultResourceHandler implements ResourceHandler {
	private final String _url;

	/** 
	 * Creates a {@link DefaultResourceHandler}.
	 */
	DefaultResourceHandler(String url) {
		_url = url;
	}

	@Override
	public void store(Resource resource) {
		RequestBuilder builder = new RequestBuilder(RequestBuilder.PUT, _url);
		builder.setHeader("content-type", "application/json");
		try {
			StringW out = new StringW();
			JsonWriter json = new JsonWriter(out);
			json.setIndent("\t");
			resource.writeTo(json);
			String data = out.toString();
			
			builder.sendRequest(data, new RequestCallback() {
				@Override
				public void onResponseReceived(Request request, Response response) {
					if (response.getStatusCode() != Response.SC_OK) {
						App.displayError("Couldn't store resource: " + _url);
					}
				}

				@Override
				public void onError(Request request, Throwable exception) {
					App.displayError("Couldn't store resource: " + _url);
				}
			});
		} catch (RequestException | IOException ex) {
			App.displayError("Couldn't store resource: " + _url);
		}
	}
}