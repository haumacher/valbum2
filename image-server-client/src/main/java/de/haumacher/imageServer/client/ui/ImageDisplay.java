/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;
import java.util.function.Function;

import de.haumacher.imageServer.client.app.App;
import de.haumacher.imageServer.client.app.KeyCodes;
import de.haumacher.imageServer.client.app.Pos;
import de.haumacher.imageServer.client.app.ResourceHandler;
import de.haumacher.imageServer.client.app.TXInfo;
import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.imageServer.shared.util.Orientations;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.util.gwt.Native;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.EventListener;
import elemental2.dom.HTMLElement;
import elemental2.dom.KeyboardEvent;
import elemental2.dom.MouseEvent;
import elemental2.dom.WheelEvent;

/**
 * {@link ResourceDisplay} displaying an {@link AbstractImage} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageDisplay extends ResourceDisplay {

	private AbstractImage _image;
	private DisplayMode _mode;
	
	private TouchHandler _gestures = new TouchHandler() {
		@Override
		public void onSwipe(Status status, SwipeImpl data) {
			if (status == Status.COMPLETED) {
				switch (data.getDirection()) {
				case LEFT:
					showNext(null);
					break;
				case RIGHT:
					showPrevious(null);
					break;
				case UP: 
					showDetail(null);
					break;
				case DOWN: 
					showParent(null);
					break;
				}
			}
		}
	};

	/** 
	 * Creates a {@link ImageDisplay}.
	 */
	public ImageDisplay(AbstractImage image, DisplayMode mode, ResourceHandler handler) {
		super(handler);
		_image = image;
		_mode = mode;
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

		ImagePart imagePart = ToImage.toImage(_image);
		{
			if (isVideo(imagePart.getKind())) {
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
			} else {
				TXInfo txInfo = createTx(context, imagePart);
				
				out.begin(DIV);
				out.attr(ID_ATTR, "image-container");
				{
					out.begin(IMG);
					out.attr(CLASS_ATTR, CssClasses.IMAGE_DISPLAY);
					out.attr(ID_ATTR, "image");
					out.attr(STYLE_ATTR, "transform-origin: top left; transform: " + txInfo.getTransform() + ";");
					out.attr(DRAGGABLE_ATTR, "false");
					out.attr(SRC_ATTR, imagePart.getName());
					out.endEmpty();
					
					setTxInfo(out.getLast(), txInfo);
				}
				out.end();
			}
			
			AbstractImage previous = previous();
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
				
				out.getLast().addEventListener("click", show(previous));
			}
			
			AbstractImage next = next();
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
				
				out.getLast().addEventListener("click", show(next));
			}
			
			writeAlbumToolbar(out);
		}
		
		String comment = imagePart.getComment();
		if (comment != null && !comment.isEmpty()) {
			out.beginDiv("va-comment");
			for (String p : comment.split("\\s*\\r?\\n\\s*")) {
				out.begin(P);
				out.append(p);
				out.end();
			}
			out.end();
		}
		
		out.end();
		
		_gestures.attach(out.getLast());
		
		if (Native.get(DomGlobal.screen, "orientation") != null) {
			// If on a mobile device.
			
			// DomGlobal.document.documentElement.requestFullscreen();
		}
	}

	private TXInfo createTx(UIContext context, ImagePart imagePart) {
		double imgWidth = imagePart.getWidth();
		double imgHeight = imagePart.getHeight();
		
		Orientation orientation = imagePart.getOrientation();
		
		double width = Orientations.width(orientation, imgWidth, imgHeight);
		double height = Orientations.height(orientation, imgWidth, imgHeight);
		
		int pageWidth = context.getPageWidth();
		int pageHeight = context.getPageHeight();
		
		double scale = Math.min(pageWidth / width, pageHeight / height);
		if (scale > 1.0) {
			scale = 1.0;
		}
		
		double displayWidth = scale * width;
		double displayHeight = scale * height;
		
		double tx = (pageWidth - displayWidth) / 2;
		double ty = (pageHeight - displayHeight) / 2;
		
		return new TXInfo(orientation, imgWidth, imgHeight, scale, tx, ty);
	}
	
	@Override
	protected void onDetach(Element element) {
		_gestures.detach();
		
		super.onDetach(element);
	}

	@Override
	protected void writeToolbarContentsLeft(DomBuilder out) throws IOException {
		super.writeToolbarContentsLeft(out);
		
		if (ToImage.toImage(_image).getGroup() != null) {
			out.begin(A);
			out.classAttr(CssClasses.TOOLBAR_BUTTON);
			out.attr(HREF_ATTR, "#");
			{
				RenderUtil.icon(out, "fas fa-chevron-down");
			}
			out.end();
			out.getLast().addEventListener("click", this::showDetail);
		}
	}

	public static boolean isVideo(ImageKind kind) {
		switch (kind) {
		case IMAGE: return false;
		case VIDEO:
		case QUICKTIME : return true;
		}
		throw new IllegalArgumentException("No such kind: " + kind);
	}

	private AbstractImage previous() {
		return navigate1(_image, AbstractImage::getPrevious);
	}
	
	private AbstractImage next() {
		return navigate1(_image, AbstractImage::getNext);
	}
	
	private AbstractImage home() {
		return navigate0(_image.getHome(), AbstractImage::getNext);
	}
	
	private AbstractImage end() {
		return navigate0(_image.getEnd(), AbstractImage::getPrevious);
	}
	
	public static AbstractImage navigate0(AbstractImage image, Function<AbstractImage, AbstractImage> step) {
		int minRating = image.getOwner().getMinRating();
		return matches(minRating, image) ? image : doNavigate(minRating, step, image);
	}
	
	private static AbstractImage navigate1(AbstractImage image, Function<AbstractImage, AbstractImage> step) {
		return doNavigate(image.getOwner().getMinRating(), step, image);
	}

	private static AbstractImage doNavigate(int minRating, Function<AbstractImage, AbstractImage> step,
			AbstractImage current) {
		while (true) {
			AbstractImage previous = step.apply(current);
			if (previous == null) {
				return null;
			}
			
			if (matches(minRating, previous)) {
				return previous;
			}
			
			current = previous;
		}
	}

	private static boolean matches(int minRating, AbstractImage image) {
		return ToImage.toImage(image).getRating() >= minRating;
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
	protected boolean handleKeyDown(Element target, KeyboardEvent event, String key) {
		if (!event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey) {
			switch (key) {
			case KeyCodes.ArrowUp:
				showParent(event);
				return true;
				
			case KeyCodes.ArrowLeft:
				showPrevious(event);
				return true;
				
			case KeyCodes.ArrowRight:
				return showNext(event);
				
			case KeyCodes.ArrowDown:
				return showDetail(event);
				
			case KeyCodes.Home:
				showFirst(event);
				return true;
				
			case KeyCodes.End:
				showLast(event);
				return true;
			}
		}
		
		return super.handleKeyDown(target, event, key);
	}

	private void onImageZoom(WheelEvent event) {
		HTMLElement container = containerElement();
		HTMLElement image = imageElement();
				
		TXInfo txInfo = getTxInfo(image);
		double origScale = txInfo.getScale();
		
		double direction = Math.signum(event.deltaY);
		
		double scaleBy = 1 + direction * 0.2;
		double newScale = origScale * scaleBy;
		if (newScale <= txInfo.getOrigScale()) {
			txInfo.reset();
		} else {
			Pos pos = position(event, container);	
			
			double currentTx = txInfo.getTx();
			double currentTy = txInfo.getTy();
				
			// Point in the original picture, where now should be the scale origin.
			double origX = (pos.getX() - currentTx) / origScale;
			double origY = (pos.getY() - currentTy) / origScale;
			
			double newTx = pos.getX() - origX * newScale;
			double newTy = pos.getY() - origY * newScale;
			
			if (direction < 0) {
				// Zoom out, ensure that image fits to its original position (centered on the screen), if the zoom level
				// reaches again the original zoom level.
				
				{
					int pageWidth = container.clientWidth;
					double newWidth = txInfo.getWidth() * newScale;
					double paddingLeft = newTx;
					double paddingRight = pageWidth - newWidth - newTx;
					if (paddingLeft > 0 || paddingRight > 0) {
						// Shift to the right to distribute empty space evenly.
						double shift = (paddingRight - paddingLeft) / 2;
						
						// A positive value means shift to the right.
						double shiftDirection = Math.signum(shift);
						double shiftValue = Math.abs(shift);
						
						// Limit shift so that the larger padding does not become smaller than zero. 
						double maxPadding = Math.max(paddingLeft, paddingRight);
						shiftValue = Math.min(shiftValue, maxPadding);
						
						newTx += shiftValue * shiftDirection;
					}
				}
				
				{
					int pageHeight = container.clientHeight;
					double newHeight = txInfo.getHeight() * newScale;
					double paddingTop = newTy;
					double paddingBottom = pageHeight - newHeight - newTy;
					if (paddingTop > 0 || paddingBottom > 0) {
						// Shift down to distribute empty space evenly.
						double shift = (paddingBottom - paddingTop) / 2;
						
						// A positive value means shift down.
						double shiftDirection = Math.signum(shift);
						double shiftValue = Math.abs(shift);
						
						// Limit shift so that the larger padding does not become smaller than zero. 
						double maxPadding = Math.max(paddingTop, paddingBottom);
						shiftValue = Math.min(shiftValue, maxPadding);
						
						newTy += shiftValue * shiftDirection;
					}
				}
			}
			
			txInfo.setCustom(newTx, newTy, newScale);
		}
		updateTransform(image, txInfo);
		event.preventDefault();
	}

	/**
	 * Event position relative to the container element..
	 */
	private Pos position(MouseEvent event, HTMLElement container) {
		double x = event.clientX;
		double y = event.clientY;
		
		// Make event relative to the image container.
		HTMLElement parent = container;
		while (parent != null) {
			x -= parent.offsetLeft;
			y -= parent.offsetTop;
			parent = (HTMLElement) parent.offsetParent;
		}
		
		return new Pos(x, y);
	}

	private TXInfo getTxInfo(HTMLElement image) {
		return Native.get(image, "txInfo");
	}

	private void setTxInfo(Element image, TXInfo tx) {
		Native.set(image, "txInfo", tx);
	}

	private void updateTransform(HTMLElement image, TXInfo tx) {
		image.style.transform = tx.getTransform();
	}
	
	private void onImageClick(MouseEvent event) {
		HTMLElement image = imageElement();

		TXInfo txInfo = getTxInfo(image);
		if (txInfo.isInitial()) {
			HTMLElement container = containerElement();
			
			// Scale to display the image 1:1 on screen.
			double newScale = 1.0;
			
			// Position of the click relative to the image container in screen coordinates.
			Pos clickPos = position(event, container);	
		
			// Click position relative to the image top-left corner in image pixels.
			double currentScale = txInfo.getScale();
			double imgX = (clickPos.getX() - txInfo.getTx()) / currentScale;
			double imgY = (clickPos.getY() - txInfo.getTy()) / currentScale;
			
			double newTx = clickPos.getX() - imgX * newScale;
			double newTy = clickPos.getY() - imgY * newScale;
			
			txInfo.setCustom(newTx, newTy, newScale);
		} else {
			txInfo.reset();
		}
		updateTransform(image, txInfo);
	}

	private void onImagePanStart(MouseEvent event) {
		HTMLElement container = containerElement();
		HTMLElement image = imageElement();
		if (image == null || container == null) {
			return;
		}

		double startX = event.clientX;
		double startY = event.clientY;
		
		TXInfo txInfo = getTxInfo(image);
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
		
		TXInfo txInfo = getTxInfo(image);
		double scale = txInfo.getScale();
		txInfo.setCustom(tx, ty, scale);
		updateTransform(image, txInfo);
		Native.set(container, "moved", Boolean.TRUE);
	}

	private Pos dragInfo(HTMLElement container) {
		return Native.get(container, "dragInfo");
	}

	final boolean showNext(Event event) {
		return show(event, next(), _mode);
	}

	final boolean showPrevious(Event event) {
		return show(event, previous(), _mode);
	}
	
	final boolean showDetail(Event event) {
		return show(event, ToImage.toImage(_image).getGroup(), DisplayMode.DETAIL);
	}

	private boolean showFirst(Event event) {
		return show(event, home(), _mode);
	}
	
	private boolean showLast(Event event) {
		return show(event, end(), _mode);
	}
	
	private EventListener show(AbstractImage resource) {
		return e -> show(e, resource, _mode);
	}
	
	private boolean show(Event event, AbstractImage resource, DisplayMode mode) {
		if (resource != null) {
			App.getInstance().showPage(resource, mode);
			if (event != null) {
				event.stopPropagation();
				event.preventDefault();
			}
			return true;
		}
		return false;
	}

	@Override
	protected void showParent(Event event) {
		switch (_mode) {
		case DEFAULT: {
			show(event, _image.getOwner());
			break;
		}
		
		case DETAIL: {
			show(event, ToImage.toImage(_image).getGroup(), DisplayMode.DETAIL);
			break;
		}
		}
	}
	
}
