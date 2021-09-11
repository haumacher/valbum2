package de.haumacher.imageServer.client.app;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.stream.JsonReader;
import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.Timer;

import de.haumacher.imageServer.client.ui.Display;
import de.haumacher.imageServer.client.ui.ResourceControlProvider;
import de.haumacher.imageServer.client.ui.UIContext;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.Settings;
import de.haumacher.util.gwt.dom.DomBuilder;
import de.haumacher.util.gwt.dom.DomBuilderImpl;
import de.haumacher.util.html.HTML;
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
public class App implements EntryPoint, UIContext {
	
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
	
	private String _contextPath;

	Timer _resizeTimer;

	private Display _display;
	
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
		
		HTMLBodyElement body = DomGlobal.document.body;
		
		body.addEventListener("click", this::handleEvent);
		body.addEventListener("keydown", this::handleEvent);
		body.addEventListener("wheel", this::handleEvent);
		body.addEventListener("mousedown", this::handleEvent);
		
		DomGlobal.window.addEventListener("popstate", this::onPopState);
		DomGlobal.window.addEventListener("resize", this::handleResize);
	    
		loadPage(ResourcePath.toPath(DomGlobal.window.location.hash), false);
	}

	private void handleResize(@SuppressWarnings("unused") Event event) {
		if (_resizeTimer != null) {
			_resizeTimer.cancel();
			_resizeTimer = null;
		}
		
		_resizeTimer = new Timer() {
			@Override
			public void run() {
				renderPage();
				_resizeTimer = null;
			}
		};
		
		_resizeTimer.schedule(200);
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
			ControlHandler handler = ControlHandler.getControlHandler(target);
			if (handler != null) {
				if (handler.handleEvent(target, event)) {
					return;
				}
			}
			target = target.parentElement;
		}
		
		if (_display instanceof ControlHandler) {
			((ControlHandler) _display).handleEvent(orig, event);
		}
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
	
	void updatePage(String path, Resource resource, boolean back) {
		setBaseUrl(currentDir(path));
		setDisplay(resource);
		renderPage();
		
		if (!back) {
			DomGlobal.window.history.pushState(null, "", DomGlobal.window.location.pathname + "#" + path);
		}
		
		Double lastOffset = _scrollOffset.get(path);
		if (lastOffset != null) {
			ScrollToOptions options = ScrollToOptions.create();
			options.setTop(lastOffset.doubleValue());
			DomGlobal.window.scroll(options);
		}
	}

	private void setDisplay(Resource resource) {
		_display = resource.visit(ResourceControlProvider.INSTANCE, null);
	}

	final void renderPage() {
		try {
			_display.show(this, createUpdater());
		} catch (IOException ex) {
			LOG.warning("Rendering failed: " + ex.getMessage());
		}
	}
	
	@Override
	public int getPageWidth() {
		return DomGlobal.window.innerWidth - 20;
	}

	@Override
	public String getContextPath() {
		return _contextPath;
	}

	private void rememberScrollOffset() {
		Window window = DomGlobal.window;
		_scrollOffset.put(ResourcePath.toPath(window.location.hash), Double.valueOf(window.scrollY));
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

	private DomBuilder createUpdater() {
		Element main = getMainElement();
		removeAllChildren(main);
		return createDomBuilderImpl(main);
	}
	
	@Override
	public DomBuilder createDomBuilderImpl(Element parent) {
		return new DomBuilderImpl(parent) {
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
