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
	
	/**
	 * ID of the static element on the host page that should display the currently rendered resource.
	 */
	private static final String MAIN_ID = "main";
	
	/**
	 * The path of the (JSON) resource currently being displayed in the main element of the page.
	 */
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
		Element main = document.getElementById(MAIN_ID);
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
		
		href = removeQuery(href);
		href = processAbsoluteUrl(href);
		
		gotoCurrentDir();
		
		String[] relative = href.split("(?<=/)");
		for (String name : relative) {
			switch (name) {
				case "":
					continue;
					
				case ".":
				case "./": {
					gotoCurrentDir();
					continue;
				}
					
				case "..":
				case "../": {
					gotoParentDir();
					break;
				}
					
				default:
					_path.add(name);
					break;
			}
		}
		
		loadPage();
	}

	/** 
	 * Navigates to the parent directory of the directory containing the current resource.
	 */
	private void gotoParentDir() {
		gotoCurrentDir();
		
		int size = _path.size();
		if (size > 0) {
			_path.remove(size - 1);
		}
	}

	/** 
	 * Navigates to the current directory of the resource currently being displayed.
	 */
	private void gotoCurrentDir() {
		int size = _path.size();
		if (size > 0 && !_path.get(size - 1).endsWith("/")) {
			_path.remove(size - 1);
		}
	}

	/** 
	 * Navigates to the root resource, if the given URL is absolute.
	 */
	private String processAbsoluteUrl(String url) {
		if (url.startsWith("/")) {
			_path.clear();
			url = url.substring(1);
		}
		return url;
	}

	/** 
	 * Removes the query part from the given URL.
	 */
	private String removeQuery(String url) {
		int paramIndex = url.indexOf('?');
		if (paramIndex >= 0) {
			url = url.substring(0, paramIndex);
		}
		return url;
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
