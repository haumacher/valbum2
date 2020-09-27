package de.haumacher.imageServer.client.app;

import java.io.IOException;
import java.io.StringReader;

import com.google.gson.stream.JsonReader;
import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.dom.client.DivElement;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Node;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;

import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.ResourceRenderer;

/**
 * {@link EntryPoint} of the application.
 */
public class App implements EntryPoint {

	@Override
	public void onModuleLoad() {
		displayError("Started.");

		RequestBuilder builder = new RequestBuilder(RequestBuilder.GET, "/?type=json");
		try {
			Request request = builder.sendRequest(null, new RequestCallback() {
				@Override
				public void onResponseReceived(Request request,
						Response response) {
					if (response.getStatusCode() == Response.SC_OK) {
						JsonReader json = new JsonReader(new StringReader(response.getText()));
						
						try {
							Resource resource = Resource.readPolymorphic(json);
							resource.visit(ResourceRenderer.INSTANCE, new DomBuilder(Document.get().getBody()));
						} catch (IOException ex) {
							displayError("Couldn't parse response: " + response.getStatusText());
						}
					} else {
						displayError("Couldn't retrieve JSON (" + response.getStatusText() + ")");
					}
				}

				@Override
				public void onError(Request request, Throwable exception) {
					displayError("Couldn’t retrieve JSON");
				}
			});
		} catch (RequestException ex) {
			displayError("Couldn’t retrieve JSON");
		}
	}

	static void displayError(String message) {
		Document.get().getBody().appendChild(div(message));
	}

	static DivElement div(String message) {
		DivElement result = Document.get().createDivElement();
		result.appendChild(text(message));
		return result;
	}

	static Node text(String message) {
		return Document.get().createTextNode(message);
	}

}
