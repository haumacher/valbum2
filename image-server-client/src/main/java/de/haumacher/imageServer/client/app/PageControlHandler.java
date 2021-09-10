/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

import de.haumacher.util.gwt.Native;
import elemental2.dom.CSSStyleDeclaration;
import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.EventListener;
import elemental2.dom.HTMLElement;
import elemental2.dom.KeyboardEvent;
import elemental2.dom.MouseEvent;
import elemental2.dom.WheelEvent;
import jsinterop.base.Js;

/**
 * {@link ControlHandler} for an image page.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class PageControlHandler implements ControlHandler {

	@Override
	public boolean handleEvent(Element target, Event event) {
		switch (event.type) {
			case "keydown": {
				String key = ((KeyboardEvent) event).key;
				switch (key) {
					case KeyCodes.ARROW_UP:
						return navigate(target, "data-up");
					case KeyCodes.ARROW_LEFT:
						return navigate(target, "data-left");
					case KeyCodes.ARROW_RIGHT:
						return navigate(target, "data-right");
					case KeyCodes.HOME:
						return navigate(target, "data-home");
					case KeyCodes.END:
						return navigate(target, "data-end");
					default:
						return false;
				}
			}
			
			case "wheel": {
				onImageZoom((WheelEvent) event);
				return true;
			}
			
			case "mousedown": {
				onImagePanStart((MouseEvent) event);
				return true;
			}
			
			default: 
				return false;
		}
	}

	private boolean navigate(Element target, String navigationAttr) {
		Element page = target.ownerDocument.getElementById("page");
		if (page.hasAttribute(navigationAttr)) {
			String url = page.getAttribute(navigationAttr);
			App.getInstance().gotoTarget(url);
		}
		return false;
	}
	
	private void onImageZoom(WheelEvent event) {
		HTMLElement container = containerElement();
		HTMLElement image = imageElement();
				
		TXInfo txInfo = txInfo(image);
		double origScale = txInfo.getScale();
		
		double scaleBy = 1 - event.deltaY * 0.05;
		double newScale = origScale * scaleBy;
		if (newScale <= 0.1) {
			// Limit scale.
			return;
		}

		Pos pos = position(event, container, image);	
		
		double tx1 = txInfo.getTx();
		double ty1 = txInfo.getTy();
			
		// Point in the original picture, where now should be the scale origin.
		double origX = (pos.getX() - tx1) / origScale;
		double origY = (pos.getY() - ty1) / origScale;
		
		double tx = pos.getX() - newScale * origX;
		double ty = pos.getY() - newScale * origY;
		
		setTransform(image, tx, ty, newScale);
			
		event.preventDefault();
	}

	/**
	 * Event position relative to untransformed image.
	 */
	private Pos position(MouseEvent event, HTMLElement container, HTMLElement image) {
		MouseEvent mouseEvent = event;
		double x = mouseEvent.clientX;
		double y = mouseEvent.clientY;
		
		// Make event relative to the image container.
		HTMLElement parent = container;
		while (parent != null) {
			x -= parent.offsetLeft;
			y -= parent.offsetTop;
			parent = (HTMLElement) parent.offsetParent;
		}
		
		x -= image.offsetLeft;
		y -= image.offsetTop;
		
		return new Pos(x, y);
	}

	private void setTransform(HTMLElement image, double tx, double ty, double scale) {
		CSSStyleDeclaration style = image.style;
		style.transformOrigin = Js.cast("0px 0px");
		style.transform = "translate(" + tx + "px, " + ty + "px) scale(" + scale + ")";

		txInfo(image).set(scale, tx, ty);
	}

	private TXInfo txInfo(Element image) {
		TXInfo result = Native.get(image, "txInfo");
		if (result == null) {
			result = new TXInfo();
			Native.set(image, "txInfo", result);
		}
		return result;
	}

	private <T> T withDefault(T value, T defaultValue) {
		return value == null ? defaultValue : value;
	}

	private void onImageClick(MouseEvent event) {
		HTMLElement image = imageElement();

		String transform = image.style.transform;
		
		if (transform == "none" || transform == "") {
			HTMLElement container = containerElement();
			
			int width = Integer.parseInt(image.getAttribute("data-width"));
			int height = Integer.parseInt(image.getAttribute("data-height"));
			
			int containerWidth = container.offsetWidth;
			int containerHeight = container.offsetHeight;
			
			int middleX = containerWidth / 2;
			int middleY = containerHeight / 2;
		
			// Scale to display the image 1:1 on screen (it is fit to it's container using CSS scaling).
			double scale = 1.0 / Math.min(((double) containerWidth) / width, ((double) containerHeight) / height);
			
			Pos pos = position(event, container, image);	
		
			double tx = -pos.getX() * scale + middleX - image.offsetLeft;
			double ty = -pos.getY() * scale + middleY - image.offsetTop;

			int maxTx = -image.offsetLeft;		
			int minTx = -image.offsetLeft - width + containerWidth;		
			if (minTx < maxTx) {
				if (tx > maxTx) {
					tx = maxTx;
				} else if (tx < minTx) {
					tx = minTx;
				}
			}

			int maxTy = -image.offsetTop;
			int minTy = -image.offsetTop - height + containerHeight;
			if (minTy < maxTy) {
				if (ty > maxTy) {
					ty = maxTy;
				} else if (ty < minTy) {
					ty = minTy;
				}
			}
			
			setTransform(image, tx, ty, scale);
		} else {
			image.style.transform = "none";
			Native.delete(image, "txInfo");
		}
	}

	private void onImagePanStart(MouseEvent event) {
		HTMLElement container = containerElement();
		HTMLElement image = imageElement();

		double startX = event.clientX;
		double startY = event.clientY;
		
		TXInfo txInfo = txInfo(image);
		double tx = txInfo.getTx();
		double ty = txInfo.getTy();
		
		Native.set(container, "dragInfo", new Pos(startX - tx, startY - ty));
		Native.delete(container, "moved");
		
		container.addEventListener("mousemove", _onImagePan);
		container.addEventListener("mouseup", _onImagePanStop);
		
		event.preventDefault();
		event.stopPropagation();
	}
	
	private HTMLElement imageElement() {
		return (HTMLElement) DomGlobal.document.getElementById("image");
	}

	private HTMLElement containerElement() {
		return (HTMLElement) DomGlobal.document.getElementById("image-container");
	}

	private EventListener _onImagePan = this::onImagePan;
	private EventListener _onImagePanStop = this::onImagePanStop;
	
	private void onImagePanStop(Event event) {
		onImagePanStop((MouseEvent) event);
	}
	
	private void onImagePanStop(MouseEvent event) {
		HTMLElement container = containerElement();
		
		container.removeEventListener("mouseup", _onImagePanStop);
		container.removeEventListener("mousemove", _onImagePan);
		
		if (Native.get(container, "moved") != null) {
			Native.delete(container, "moved");
			event.stopPropagation();
			event.preventDefault();
		} else {
			onImageClick(event);
		}
	}

	private void onImagePan(Event event) {
		onImagePan((MouseEvent) event);
	}
	
	private void onImagePan(MouseEvent event) {
		HTMLElement container = containerElement();
		HTMLElement image = imageElement();
		
		double x = event.pageX;
		double y = event.pageY;
		
		Pos dragStart = dragInfo(container);
		
		double tx = x - dragStart.getX();
		double ty = y - dragStart.getY();
		
		double scale = txInfo(image).getScale();
		
		setTransform(image, tx, ty, scale);
		Native.set(container, "moved", Boolean.TRUE);
	}

	private Pos dragInfo(HTMLElement container) {
		return Native.get(container, "dragInfo");
	}
	

}
