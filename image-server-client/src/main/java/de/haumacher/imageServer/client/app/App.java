package de.haumacher.imageServer.client.app;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.gson.stream.JsonReader;
import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;

import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.Controls;
import de.haumacher.imageServer.shared.ui.ResourceRenderer;
import de.haumacher.util.html.HTML;
import de.haumacher.util.xml.XmlAppendable;
import elemental2.dom.Document;
import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.HTMLBaseElement;
import elemental2.dom.HTMLBodyElement;
import elemental2.dom.HTMLHeadElement;
import elemental2.dom.Node;
import elemental2.dom.NodeList;

/**
 * {@link EntryPoint} of the application.
 */
public class App implements EntryPoint {
	
	private static final Logger LOG = Logger.getLogger(App.class.getName());
	
	/**
	 * ID of the static element on the host page that should display the currently rendered resource.
	 */
	private static final String MAIN_ID = "main";

	private static final ControlHandler NONE = new ControlHandler() {
		@Override
		public boolean handleEvent(Element target, Event event) {
			return false;
		}
	};
	
	/**
	 * The path of the (JSON) resource currently being displayed in the main element of the page.
	 */
	private List<String> _path = new ArrayList<>();
	
	private Map<String, ControlHandler> _controlHandlers = new HashMap<>();

	private ControlHandler _handler = NONE;
	
	private static App INSTANCE;
	
	/**
	 * The {@link App} instance.
	 */
	public static App getInstance() {
		return INSTANCE;
	}

	@Override
	public void onModuleLoad() {
		INSTANCE = this;
		
		_controlHandlers.put(Controls.PAGE_CONTROL, new PageControlHandler());
		
		HTMLBodyElement body = DomGlobal.document.body;
		
		body.addEventListener("click", this::handleEvent);
		body.addEventListener("keydown", this::handleEvent);
		body.addEventListener("wheel", this::handleEvent);
		body.addEventListener("mousedown", this::handleEvent);
	    
		loadPage();
	}
		
	void handleEvent(Event event) {
		Element orig = (Element) event.target;
		Element target = orig;
		while (target != null) {
			if (target.hasAttribute(HTML.DATA_CONTROL_ATTR)) {
				String controlName = target.getAttribute(HTML.DATA_CONTROL_ATTR);
				ControlHandler handler = getHandler(controlName);
				if (handler != null) {
					if (handler.handleEvent(target, event)) {
						return;
					}
				}
				return;
			}
			target = target.parentElement;
		}
		
		_handler.handleEvent(orig, event);
	}

	private ControlHandler getHandler(String controlName) {
		ControlHandler handler = _controlHandlers.get(controlName);
		if (handler == null) {
			LOG.log(Level.WARNING, "No handler registered for control '" + controlName + "'.");
		}
		return handler;
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
							updatePage(currentDir(base), resource);
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
		setBaseUrl(base);
		resource.visit(ResourceRenderer.INSTANCE, createUpdater());
		installHandler(resource.getHandler());
	}

	private void installHandler(String handlerName) {
		if (handlerName == null) {
			_handler = NONE;
			return;
		}
		
		_handler = getHandler(handlerName);
	}

	private void setBaseUrl(String base) {
		Document document = DomGlobal.document;
		HTMLHeadElement head = document.head;
		NodeList<Element> baseElements = head.getElementsByTagName("base");
		for (int n = 0, cnt = baseElements.getLength(); n < cnt; n++) {
			baseElements.item(n).remove();
		}
		
		HTMLBaseElement baseElement = (HTMLBaseElement) document.createElement(HTML.BASE);
		baseElement.href = base;
		head.appendChild(baseElement);
	}

	private XmlAppendable createUpdater() {
		Element main = getMainElement();
		removeAllChildren(main);
		XmlAppendable out = new DomBuilder(main) {
			@Override
			public void attr(String name, CharSequence value) throws IOException {
				super.attr(name, value);

				if (HTML.HREF_ATTR.equals(name)) {
					Element current = current();
					if (HTML.A.equalsIgnoreCase(current.tagName)) {
					    current.addEventListener("click", App.this::handleNavigation);
					}
				}
			}
		};
		return out;
	}

	private void removeAllChildren(Element parent) {
		Node lastChild = parent.lastChild;
		while (lastChild != null) {
			parent.removeChild(lastChild);
			lastChild = parent.lastChild;
		}
	}

	private Element getMainElement() {
		Document document = DomGlobal.document;
		Element main = document.getElementById(MAIN_ID);
		return main;
	}

	/** 
	 * URL pointing to the current directory of the given URL.
	 */
	static final String currentDir(String url) {
		if (!url.endsWith("/")) {
			int sepIndex = url.lastIndexOf('/');
			if (sepIndex >= 0) {
				url = url.substring(0, sepIndex + 1);
			}
		}
		return url;
	}

	void handleNavigation(Event event) {
		event.stopPropagation();
		event.preventDefault();
		
		Element target = (Element) event.currentTarget;
		String href = target.getAttribute(HTML.HREF_ATTR);
		
		gotoTarget(href);
	}

	/** 
	 * Navigates to a new resource.
	 *
	 * @param href URL of the target resource to display.
	 */
	public void gotoTarget(String href) {
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
		DomGlobal.document.body.appendChild(div(message));
	}

	static Element div(String message) {
		Element result = DomGlobal.document.createElement(HTML.DIV);
		result.appendChild(text(message));
		return result;
	}

	static Node text(String message) {
		return DomGlobal.document.createTextNode(message);
	}

}
