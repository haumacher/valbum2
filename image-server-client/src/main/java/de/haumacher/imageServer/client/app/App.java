package de.haumacher.imageServer.client.app;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.google.gson.stream.JsonReader;
import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.dom.client.BaseElement;
import com.google.gwt.dom.client.DivElement;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.HeadElement;
import com.google.gwt.dom.client.Node;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.Event;

import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.ResourceRenderer;
import de.haumacher.util.html.HTML;

/**
 * {@link EntryPoint} of the application.
 */
public class App implements EntryPoint {
	
	private List<String> _path = new ArrayList<>();

	@Override
	public void onModuleLoad() {
		loadPage();
	}

	private void loadPage() {
		String base = "/" + _path.stream().collect(Collectors.joining(""));
		String url = base + "?type=json";
		RequestBuilder builder = new RequestBuilder(RequestBuilder.GET, url);
		try {
			builder.sendRequest(null, new RequestCallback() {
				@Override
				public void onResponseReceived(Request request, Response response) {
					if (response.getStatusCode() == Response.SC_OK) {
						JsonReader json = new JsonReader(new StringReader(response.getText()));
						
						try {
							Resource resource = Resource.readPolymorphic(json);
							updatePage(base, resource);
						} catch (IOException ex) {
							displayError("Couldn't parse response: " + response.getStatusText());
						}
					} else {
						displayError("Couldn't retrieve JSON (" + response.getStatusText() + "): " + url);
					}
				}

				@Override
				public void onError(Request request, Throwable exception) {
					displayError("Couldn’t retrieve JSON: " + url);
				}
			});
		} catch (RequestException ex) {
			displayError("Couldn’t retrieve JSON: " + url);
		}
	}
	
	void updatePage(String base, Resource resource) throws IOException {
		Document document = Document.get();
		Element main = document.getElementById("main");
		main.removeAllChildren();
		
		HeadElement head = document.getHead();
		NodeList<Element> baseElements = head.getElementsByTagName("base");
		for (int n = 0, cnt = baseElements.getLength(); n < cnt; n++) {
			baseElements.getItem(n).removeFromParent();
		}
		
		BaseElement baseElement = document.createBaseElement();
		if (!base.endsWith("/")) {
			int sepIndex = base.lastIndexOf('/');
			if (sepIndex >= 0) {
				base = base.substring(0, sepIndex + 1);
			}
		}
		baseElement.setHref(base);
		head.appendChild(baseElement);
		
		resource.visit(ResourceRenderer.INSTANCE, new DomBuilder(main) {
			@Override
			public void attr(String name, CharSequence value) throws IOException {
				super.attr(name, value);

				if (HTML.HREF_ATTR.equals(name)) {
					Element current = current();
					if (HTML.A.equals(current.getTagName().toLowerCase())) {
					    Event.sinkEvents(current, Event.ONCLICK);
					    Event.setEventListener(current, App.this::handleNavigation); 											
					}
				}
			}
		});
	}

	void handleNavigation(Event event) {
		event.stopPropagation();
		event.preventDefault();
		
		Element target = event.getCurrentEventTarget().cast();
		String href = target.getAttribute(HTML.HREF_ATTR);
		
		if (href.startsWith("/")) {
			_path.clear();
			href = href.substring(1);
		}
		
		int paramIndex = href.indexOf('?');
		if (paramIndex >= 0) {
			href = href.substring(0, paramIndex);
		}
		
		String[] relative = href.split("(?<=/)");
		for (String name : relative) {
			switch (name) {
				case "":
					continue;
					
				case ".":
				case "./": {
					int size = _path.size();
					if (size > 0 && !_path.get(size - 1).endsWith("/")) {
						_path.remove(size - 1);
					}
					continue;
				}
					
				case "..":
				case "../": {
					int size = _path.size();
					if (size > 0 && !_path.get(size - 1).endsWith("/")) {
						_path.remove(size - 1);
						size--;
					}
					if (size > 0) {
						_path.remove(size - 1);
					}
					break;
				}
					
				default:
					_path.add(name);
					break;
			}
		}
		
		loadPage();
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
