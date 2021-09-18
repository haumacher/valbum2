/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;
import java.util.function.Consumer;

import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.util.gwt.Native;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.EventListener;
import elemental2.dom.Node;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImagePreviewDisplay extends AbstractDisplay {

	private final AlbumDisplay _owner;
	private AlbumPart _part;
	private final ImagePart _image;
	private boolean _selected;
	private final int _rowIndex;
	private final double _width;
	private final double _rowHeight;
	private final int _spacing;
	private Element _toolbarContainer;
	private UIContext _context;

	/** 
	 * Creates a {@link ImagePreviewDisplay}.
	 */
	public ImagePreviewDisplay(AlbumDisplay owner, AlbumPart part, int rowIndex, double width, double rowHeight, int spacing) {
		super();
		_owner = owner;
		_part = part;
		_image = ToImage.toImage(part);
		_rowIndex = rowIndex;
		_width = width;
		_rowHeight = rowHeight;
		_spacing = spacing;
	}
	
	/**
	 * TODO
	 */
	public AlbumDisplay getOwner() {
		return _owner;
	}
	
	/**
	 * TODO
	 */
	public AlbumPart getPart() {
		return _part;
	}
	
	public boolean isSelected() {
		return _selected;
	}

	/** 
	 * TODO
	 */
	public void setSelected(boolean value) {
		_selected = value;

		Element element = element();
		if (element != null) {
			if (value) {
				element.classList.add("selected");
			} else {
				element.classList.remove("selected");
			}
			
			updateToolbars();
		}
	}
	
	private void updateToolbars() {
		if (_toolbarContainer != null) {
			try {
				clear(_toolbarContainer);
				writeToolbars(_context.createDomBuilderImpl(_toolbarContainer));
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		}
	}

	private static void clear(Node container) {
		while (container.lastChild != null) {
			container.removeChild(container.lastChild);
		}
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		_context = context;
		
		out.begin(DIV);
		out.attr(CLASS_ATTR, "icon");
		{
			out.begin(A);
			out.attr(CLASS_ATTR, "icon-link");
			if (_rowIndex > 0) {
				out.openAttr(STYLE_ATTR);
				out.append("margin-left: ");
				out.append(_spacing);
				out.append("px;");
				out.closeAttr();
			}
			if (!isEditMode()) {
				out.openAttr(HREF_ATTR);
				{
					out.append(_image.getName());
				}
			}
			out.closeAttr();
			{
				out.begin(IMG);
				out.attr(CLASS_ATTR, "icon-display");
				{
					out.openAttr(SRC_ATTR);
					out.append(_image.getName());
					out.append("?type=tn");
					out.closeAttr();
					
					out.attr(WIDTH_ATTR, _width);
					out.attr(HEIGHT_ATTR, _rowHeight);
				}
				out.end();
				
				if (_image.getKind() == ImagePart.Kind.VIDEO) {
					out.begin(DIV);
					out.attr(CLASS_ATTR, "video-overlay");
					{
						out.begin(I);
						out.attr(CLASS_ATTR, "far fa-play-circle");
						out.end();
					}
					out.end();
				}
				
				if (isEditMode()) {
					out.begin(SPAN);
					{
						writeToolbars(out);
					}
					out.end();
					_toolbarContainer = out.getLast();
				} else {
					_toolbarContainer = null;
				}
			}
			out.end();
		}
		out.end();
	}

	private boolean isEditMode() {
		return getOwner().isEditMode();
	}

	private void writeToolbars(DomBuilder out) throws IOException {
		writeSelectedDisplay(out);
		
		out.begin(SPAN);
		out.attr(CLASS_ATTR, "toolbar-embedded toolbar-top");
		{
			out.begin(SPAN);
			out.attr(CLASS_ATTR, "toolbar-button");
			{
				out.begin(I);
				out.attr(CLASS_ATTR, "fas fa-redo-alt");
				out.end();
			}
			out.end();
			
			out.begin(SPAN);
			out.attr(CLASS_ATTR, "toolbar-button");
			{
				out.begin(I);
				out.attr(CLASS_ATTR, "fas fa-arrows-alt-v");
				out.end();
			}
			out.end();
			
			out.begin(SPAN);
			out.attr(CLASS_ATTR, "toolbar-button");
			{
				out.begin(I);
				out.attr(CLASS_ATTR, "fas fa-undo-alt");
				out.end();
			}
			out.end();
		}
		out.end();
		
		out.begin(DIV);
		out.attr(CLASS_ATTR, "toolbar-embedded toolbar-center");
		{
			if (isSelected() && !getOwner().hasMultiSelection()) {
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-heading");
					out.end();
				}
				out.end();
			}
			
			if (isSelected() && getOwner().hasMultiSelection()) {
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "far fa-object-group");
					out.end();
				}
				out.end();
				
				out.getLast().addEventListener("click", this::handleGroup);
			}
			
			if (false) {
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "far fa-object-ungroup");
					out.end();
				}
				out.end();
			}
		}
		out.end();
		
		int rating = _image.getRating();
		
		out.begin(DIV);
		out.attr(CLASS_ATTR, "toolbar-embedded toolbar-bottom");
		makeChoice(
				data -> _image.setRating((int) data),
				() -> _image.setRating(0),
				createChoiceButton(out, "fas fa-star", rating >= 2, 2),
				createChoiceButton(out, "fas fa-plus", rating == 1, 1),
				createChoiceButton(out, "fas fa-minus", rating == -1, -1),
				createChoiceButton(out, "fas fa-trash-alt", rating <= -2, -2)
				);
		out.end();
	}

	private void handleGroup(Event event) {
		getOwner().groupSelection(getPart());
		
		event.stopPropagation();
		event.preventDefault();
	}
	
	private Element writeSelectedDisplay(DomBuilder out) throws IOException {
		boolean value = isSelected();
		
		out.begin(SPAN);
		out.attr(CLASS_ATTR, (value ? "check-button" + " " + "checked" : "check-button"));
		{
			out.begin(I);
			out.attr(CLASS_ATTR, "far fa-check-square");
			out.end();
		}
		out.end();
		
		return out.getLast();
	}

	private void makeToggleButton(Element toggleButton, Consumer<Boolean> onChange) {
		toggleButton.addEventListener("click", event -> {
			boolean checked = toggleButton.classList.contains("checked");
			if (checked) {
				toggleButton.classList.remove("checked");
			} else {
				toggleButton.classList.add("checked");
			}
			onChange.accept(!checked);
			event.stopPropagation();
			event.preventDefault();
		});
	}

	private void makeChoice(Consumer<Object> onSet, Runnable onReset, Element... buttons) {
		for (Element button : buttons) {
			button.addEventListener("click", event -> {
				if (button.classList.contains("active")) {
					button.classList.remove("active");
					onReset.run();
				} else {
					reset(buttons);
					button.classList.add("active");
					onSet.accept(Native.get(button, "vaUserData"));
				}
				
				event.stopPropagation();
				event.preventDefault();
			});
		}
	}

	private void reset(Element[] buttons) {
		for (Element button : buttons) {
			button.className = "toolbar-button";
		}
	}

	private Element createChoiceButton(DomBuilder out, String iconClass, boolean active, Object userValue) throws IOException {
		out.begin(SPAN);
		out.attr(CLASS_ATTR, active ? "toolbar-button" + " " + "active" : "toolbar-button");
		{
			out.begin(I);
			out.attr(CLASS_ATTR, iconClass);
			out.end();
		}
		out.end();
		
		Element result = out.getLast();
		Native.set(result, "vaUserData", userValue);
		return result;
	}

	/** 
	 * TODO
	 *
	 * @param string
	 * @param object
	 */
	public void addEventListener(String type, EventListener listener) {
		element().addEventListener(type, listener);
	}

}
