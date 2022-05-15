/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;
import java.util.function.Consumer;

import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.imageServer.shared.util.Orientations;
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
public class ImagePreviewDisplay extends PreviewDisplay {

	private final AlbumDisplay _owner;
	
	private boolean _selected;
	private Element _toolbarContainer;
	private UIContext _context;

	/** 
	 * Creates a {@link ImagePreviewDisplay}.
	 */
	public ImagePreviewDisplay(AlbumDisplay owner, AbstractImage part, double width, double rowHeight, int marginLeft) {
		super(part, width, rowHeight, marginLeft);
		_owner = owner;
	}
	
	/**
	 * TODO
	 */
	public AlbumDisplay getOwner() {
		return _owner;
	}
	
	public boolean isSelected() {
		return _selected;
	}

	/** 
	 * Marks/unmarks this image as selected.
	 * 
	 * <p>
	 * Only selected images can be commented or grouped.
	 * </p>
	 */
	public void setSelected(boolean value) {
		_selected = value;

		Element element = element();
		if (element != null) {
			if (value) {
				element.classList.add(CssClasses.SELECTED);
			} else {
				element.classList.remove(CssClasses.SELECTED);
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
	protected boolean isEditMode() {
		return getOwner().isEditMode();
	}
	
	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		_context = context;
		
		super.render(context, out);
	}
	
	@Override
	protected void renderToolbar(UIContext context, DomBuilder out) throws IOException {
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

	private void writeToolbars(DomBuilder out) throws IOException {
		int level = out.level();
		writeSelectedDisplay(out);
		out.checkLevel(level);

		writeToolbarTop(out);
		out.checkLevel(level);

		writeToolbarCenter(out);
		out.checkLevel(level);

		writeToolbarBottom(out);
		out.checkLevel(level);
	}

	private void writeToolbarTop(DomBuilder out) throws IOException {
		out.begin(SPAN);
		out.attr(CLASS_ATTR, CssClasses.TOOLBAR_EMBEDDED + " " + CssClasses.TOOLBAR_TOP);
		{
			out.begin(SPAN);
			out.attr(CLASS_ATTR, CssClasses.TOOLBAR_BUTTON);
			{
				out.begin(I);
				out.attr(CLASS_ATTR, "fas fa-redo-alt");
				out.end();
			}
			out.end();
			out.getLast().addEventListener("click", this::onRotateRight);
			
			out.begin(SPAN);
			out.attr(CLASS_ATTR, CssClasses.TOOLBAR_BUTTON);
			{
				out.begin(I);
				out.attr(CLASS_ATTR, "fas fa-arrows-alt-v");
				out.end();
			}
			out.end();
			out.getLast().addEventListener("click", this::onFlipVertically);
			
			out.begin(SPAN);
			out.attr(CLASS_ATTR, CssClasses.TOOLBAR_BUTTON);
			{
				out.begin(I);
				out.attr(CLASS_ATTR, "fas fa-undo-alt");
				out.end();
			}
			out.end();
			out.getLast().addEventListener("click", this::onRotateLeft);
		}
		out.end();
	}
	
	private void onRotateLeft(Event evt) {
		setDownScale(true);

		Orientation orientation = getImage().getOrientation();
		Orientation newOrientation = Orientations.rotL(orientation);
		getImage().setOrientation(newOrientation);

		redraw();
		evt.preventDefault();
		evt.stopPropagation();
	}

	private void onRotateRight(Event evt) {
		setDownScale(true);

		Orientation orientation = getImage().getOrientation();
		Orientation newOrientation = Orientations.rotR(orientation);
		getImage().setOrientation(newOrientation);

		redraw();
		evt.preventDefault();
		evt.stopPropagation();
	}
	
	private void onFlipVertically(Event evt) {
		
	}
	
	private void writeToolbarCenter(DomBuilder out) throws IOException {
		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.TOOLBAR_EMBEDDED + " " + CssClasses.TOOLBAR_CENTER);
		{
			if (isSelected()) {
				if (getOwner().hasMultiSelection()) {
					out.begin(SPAN);
					out.attr(CLASS_ATTR, CssClasses.TOOLBAR_BUTTON);
					{
						out.begin(I);
						out.attr(CLASS_ATTR, "far fa-object-group");
						out.end();
					}
					out.end();
					
					out.getLast().addEventListener("click", this::handleGroup);
				} else {
					out.begin(SPAN);
					out.attr(CLASS_ATTR, CssClasses.TOOLBAR_BUTTON);
					{
						out.begin(I);
						out.attr(CLASS_ATTR, "fas fa-heading");
						out.end();
					}
					out.end();
					
					out.getLast().addEventListener("click", this::handleCreateHeading);
				}
				
				out.begin(SPAN);
				out.attr(CLASS_ATTR, CssClasses.TOOLBAR_BUTTON);
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-bars");
					out.end();
				}
				out.end();
				
				out.getLast().addEventListener("click", this::handleEditSettings);
			}
			
			if (false) {
				out.begin(SPAN);
				out.attr(CLASS_ATTR, CssClasses.TOOLBAR_BUTTON);
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "far fa-object-ungroup");
					out.end();
				}
				out.end();
			}
		}
		out.end();
	}

	private void writeToolbarBottom(DomBuilder out) throws IOException {
		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.TOOLBAR_EMBEDDED + " " + CssClasses.TOOLBAR_BOTTOM);
		
		ImagePart image = getImage();
		int rating = image.getRating();
		Consumer<Object> setter = data -> image.setRating((int) data);
		Runnable reset = () -> image.setRating(0);
		makeChoice(
				setter,
				reset,
				createChoiceButton(out, "fas fa-star", rating >= 2, 2),
				createChoiceButton(out, "fas fa-plus", rating == 1, 1),
				createChoiceButton(out, "fas fa-minus", rating == -1, -1),
				createChoiceButton(out, "fas fa-trash-alt", rating <= -2, -2)
				);
		out.end();
	}

	private void handleCreateHeading(Event event) {
		new HeadingPropertiesEditor() {
			@Override
			protected void onSave(Event event) {
				Heading heading = save(Heading.create());
				
				AlbumDisplay owner = getOwner();
				AlbumInfo album = owner.getResource();
				int index = album.getParts().indexOf(getPart());
				album.getParts().add(index, heading);
				owner.redraw();
			}
		}.showTopLevel(context());
		
		event.stopPropagation();
		event.preventDefault();
	}
	
	private void handleGroup(Event event) {
		getOwner().groupSelection(getPart());
		
		event.stopPropagation();
		event.preventDefault();
	}
	
	private void handleEditSettings(Event event) {
		new ImagePropertiesEditor(getImage()).showTopLevel(context());
		
		event.stopPropagation();
		event.preventDefault();
	}
	
	private Element writeSelectedDisplay(DomBuilder out) throws IOException {
		boolean value = isSelected();
		
		out.begin(SPAN);
		out.attr(CLASS_ATTR, (value ? CssClasses.CHECK_BUTTON + " " + CssClasses.CHECKED : CssClasses.CHECK_BUTTON));
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
			boolean checked = toggleButton.classList.contains(CssClasses.CHECKED);
			if (checked) {
				toggleButton.classList.remove(CssClasses.CHECKED);
			} else {
				toggleButton.classList.add(CssClasses.CHECKED);
			}
			onChange.accept(!checked);
			event.stopPropagation();
			event.preventDefault();
		});
	}

	private void makeChoice(Consumer<Object> onSet, Runnable onReset, Element... buttons) {
		for (Element button : buttons) {
			button.addEventListener("click", event -> {
				if (button.classList.contains(CssClasses.ACTIVE)) {
					button.classList.remove(CssClasses.ACTIVE);
					onReset.run();
				} else {
					reset(buttons);
					button.classList.add(CssClasses.ACTIVE);
					Object data = Native.get(button, "vaUserData");
					if (data != null) {
						onSet.accept(data);
					}
				}
				
				event.stopPropagation();
				event.preventDefault();
			});
		}
	}

	private void reset(Element[] buttons) {
		for (Element button : buttons) {
			button.className = CssClasses.TOOLBAR_BUTTON;
		}
	}

	private Element createChoiceButton(DomBuilder out, String iconClass, boolean active, Object userValue) throws IOException {
		out.begin(SPAN);
		out.attr(CLASS_ATTR, active ? CssClasses.TOOLBAR_BUTTON + " " + CssClasses.ACTIVE : CssClasses.TOOLBAR_BUTTON);
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
