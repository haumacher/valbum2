package de.haumacher.imageServer.client.app;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import de.haumacher.imageServer.shared.ui.Settings;
import de.haumacher.util.gwt.dom.DomBuilder;
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
import elemental2.dom.PopStateEvent;
import elemental2.dom.ScrollToOptions;
import elemental2.dom.Window;

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
	
	private Map<String, Double> _scrollOffset = new HashMap<>();
	
	private Map<String, ControlHandler> _controlHandlers = new HashMap<>();

	private ControlHandler _handler = NONE;
	
	private String _contextPath;
	
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
		
		_contextPath = extractContextPath(DomGlobal.window.location.pathname);
		_controlHandlers.put(Controls.PAGE_CONTROL, new PageControlHandler());
		
		HTMLBodyElement body = DomGlobal.document.body;
		
		body.addEventListener("click", this::handleEvent);
		body.addEventListener("keydown", this::handleEvent);
		body.addEventListener("wheel", this::handleEvent);
		body.addEventListener("mousedown", this::handleEvent);
		
		DomGlobal.window.addEventListener("popstate", this::onPopState);
	    
		loadPage(ResourcePath.toPath(DomGlobal.window.location.hash), false);
	}
		
	private String extractContextPath(String pathname) {
		int index = pathname.lastIndexOf('/');
		if (index >= 0) {
			return pathname.substring(0, index); 
		}
		
		return "";
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

	private void loadPage(String path, boolean back) {
		String url = _contextPath + Settings.DATA_PREFIX + path + "?type=json";
		RequestBuilder builder = new RequestBuilder(RequestBuilder.GET, url);
		try {
			builder.sendRequest(null, new RequestCallback() {
				@Override
				public void onResponseReceived(Request request, Response response) {
					if (response.getStatusCode() == Response.SC_OK) {
						JsonReader json = new JsonReader(new StringReader(response.getText()));
						
						try {
							Resource resource = Resource.readPolymorphic(json);
							updatePage(path, resource, back);
						} catch (IOException ex) {
							displayError("Couldn't parse response from '" + url + "': " + response.getText());
						}
					} else {
						displayError("Couldn't retrieve JSON (" + response.getText() + "): " + url);
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
	
	void onPopState(Event event) {
		onPopState((PopStateEvent) event);
	}
	
	void onPopState(PopStateEvent event) {
		Window window = DomGlobal.window;
		String newPath = ResourcePath.toPath(window.location.hash);
		gotoTarget(newPath, true);
	}
	
	void updatePage(String path, Resource resource, boolean back) throws IOException {
		Window window = DomGlobal.window;
		
		setBaseUrl(currentDir(path));
		resource.visit(ResourceRenderer.INSTANCE, createUpdater());
		installHandler(resource.getHandler());
		
		if (!back) {
			window.history.pushState(null, "", window.location.pathname + "#" + path);
		}
		
		Double lastOffset = _scrollOffset.get(path);
		if (lastOffset != null) {
			ScrollToOptions options = ScrollToOptions.create();
			options.setTop(lastOffset.doubleValue());
			window.scroll(options);
		}
	}

	private void rememberScrollOffset() {
		Window window = DomGlobal.window;
		_scrollOffset.put(ResourcePath.toPath(window.location.hash), Double.valueOf(window.scrollY));
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
			protected void attrNonNull(String name, CharSequence value) {
				super.attrNonNull(name, value);
				
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
	final String currentDir(String url) {
		if (!url.endsWith("/")) {
			int sepIndex = url.lastIndexOf('/');
			if (sepIndex >= 0) {
				url = url.substring(0, sepIndex + 1);
			}
		}
		return _contextPath + Settings.DATA_PREFIX + url;
	}

	void handleNavigation(Event event) {
		event.stopPropagation();
		event.preventDefault();

		rememberScrollOffset();

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
		gotoTarget(href, false);
	}
	
	void gotoTarget(String href, boolean back) {
		loadPage(new ResourcePath(DomGlobal.window.location.hash).navigateTo(removeQuery(href)).getPath(), back);
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
