/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.app.App;
import de.haumacher.imageServer.client.app.KeyCodes;
import de.haumacher.imageServer.client.app.Pos;
import de.haumacher.imageServer.client.app.ResourceHandler;
import de.haumacher.imageServer.client.app.TXInfo;
import de.haumacher.imageServer.client.app.ToPath;
import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.imageServer.shared.ui.DataAttributes;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.util.gwt.Native;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.CSSStyleDeclaration;
import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.EventListener;
import elemental2.dom.HTMLElement;
import elemental2.dom.MouseEvent;
import elemental2.dom.WheelEvent;
import jsinterop.base.Js;

/**
 * {@link ResourceDisplay} displaying an {@link ImageInfo} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageDisplay extends ResourceDisplay {

	private AbstractImage<?> _image;

	/** 
	 * Creates a {@link ImageDisplay}.
	 */
	public ImageDisplay(AbstractImage<?> image, ResourceHandler handler) {
		super(handler);
		_image = image;
	}
	
	@Override
	protected Resource getResource() {
		return _image.getOwner();
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		out.attr(CLASS_ATTR, CssClasses.IMAGE_PAGE);

		AbstractImage<?> previous = _image.getPrevious();
		AbstractImage<?> next = _image.getNext();

		ImagePart imagePart = ToImage.toImage(_image);
		{
			switch (imagePart.getKind()) {
				case IMAGE: {
					out.begin(DIV);
					out.attr(ID_ATTR, "image-container");
					{
						out.begin(IMG);
						out.attr(CLASS_ATTR, CssClasses.IMAGE_DISPLAY);
						out.attr(ID_ATTR, "image");
						out.attr(DRAGGABLE_ATTR, "false");
						out.attr(SRC_ATTR, imagePart.getName());
						out.attr(DataAttributes.DATA_WIDTH, imagePart.getWidth());
						out.attr(DataAttributes.DATA_HEIGHT, imagePart.getHeight());
						out.endEmpty();
					}
					out.end();
					break;
				}
				
				case VIDEO: {
					out.begin(VIDEO);
					out.attr(CLASS_ATTR, CssClasses.IMAGE_DISPLAY);
					out.attr("controls", "controls");
					{
						out.begin(SOURCE);
						out.attr(SRC_ATTR, imagePart.getName());
						{
							out.append("Your browser doesn't support embedded videos.");
						}
						out.end();
					}
					out.end();
					break;
				}
			}
			
			if (previous != null) {
				out.begin(A);
				out.attr(HREF_ATTR, "#");
				out.attr(CLASS_ATTR, CssClasses.GOTO_PREVIOUS + " " + CssClasses.HOVER_PANE);
				{
					out.begin(DIV);
					out.attr(CLASS_ATTR, CssClasses.VCENTER);
					{
						out.begin(DIV);
						out.attr(CLASS_ATTR, CssClasses.VCENTER_CONTENT);
						{
							RenderUtil.icon(out, "fas fa-chevron-left");
						}
						out.end();
					}
					out.end();
				}
				out.end();
				
				out.getLast().addEventListener("click", this::showPrevious);
			}
			
			if (next != null) {
				out.begin(A);
				out.attr(HREF_ATTR, "#");
				out.attr(CLASS_ATTR, CssClasses.GOTO_NEXT + " " + CssClasses.HOVER_PANE);
				{
					out.begin(DIV);
					out.attr(CLASS_ATTR, CssClasses.VCENTER);
					{
						out.begin(DIV);
						out.attr(CLASS_ATTR, CssClasses.VCENTER_CONTENT);
						{
							RenderUtil.icon(out, "fas fa-chevron-right");
						}
						out.end();
					}
					out.end();
				}
				out.end();
				
				out.getLast().addEventListener("click", this::showNext);
			}
			
			writeAlbumToolbar(out, true, this::showParent);
		}
		
		String comment = imagePart.getComment();
		if (comment != null && !comment.isEmpty()) {
			out.beginDiv("va-comment");
			out.append(comment);
			out.end();
		}
		
		out.end();
	}

	@Override
	public boolean handleEvent(Element target, Event event) {
		switch (event.type) {
			case "wheel": {
				onImageZoom((WheelEvent) event);
				return true;
			}
			
			case "mousedown": {
				onImagePanStart((MouseEvent) event);
				return true;
			}
			
			default:
				return super.handleEvent(target, event);
		}
	}
	
	@Override
	protected boolean handleKeyDown(Element target, Event event, String key) {
		switch (key) {
			case KeyCodes.Escape:
			case KeyCodes.ArrowUp:
				showParent(event);
				return false;
				
			case KeyCodes.ArrowLeft:
				showPrevious(event);
				return false;
				
			case KeyCodes.ArrowRight:
				return showNext(event);
				
			case KeyCodes.Home:
				showFirst(event);
				return false;
				
			case KeyCodes.End:
				showLast(event);
				return false;
		}
		
		return super.handleKeyDown(target, event, key);
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

	private void onImageClick(MouseEvent event) {
		HTMLElement image = imageElement();

		String transform = image.style.transform;
		
		if (transform == "none" || transform == "") {
			HTMLElement container = containerElement();
			
			int width = Integer.parseInt(image.getAttribute(DataAttributes.DATA_WIDTH));
			int height = Integer.parseInt(image.getAttribute(DataAttributes.DATA_HEIGHT));
			
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

	private boolean showNext(Event event) {
		return show(event, _image.getNext());
	}

	private boolean showPrevious(Event event) {
		return show(event, _image.getPrevious());
	}

	private boolean showFirst(Event event) {
		return show(event, _image.getHome());
	}
	
	private boolean showLast(Event event) {
		return show(event, _image.getEnd());
	}
	
	private boolean show(Event event, Resource other) {
		if (other != null) {
			App.getInstance().gotoTarget(other);
		}
		event.stopPropagation();
		event.preventDefault();
		return false;
	}

	private void showParent(Event event) {
		String path = _image.visit(ToPath.INSTANCE, null);
		String parent = RenderUtil.parentUrl(path);
		App.getInstance().gotoTarget(parent);
		event.stopPropagation();
		event.preventDefault();
	}
	
}
