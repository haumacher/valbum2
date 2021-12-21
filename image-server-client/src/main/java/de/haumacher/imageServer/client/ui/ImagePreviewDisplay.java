/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;
import java.util.function.Consumer;

import de.haumacher.imageServer.client.app.App;
import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.ui.CssClasses;
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
	private AbstractImage _part;
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
	public ImagePreviewDisplay(AlbumDisplay owner, AbstractImage part, int rowIndex, double width, double rowHeight, int spacing) {
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
	public AbstractImage getPart() {
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
	protected void render(UIContext context, DomBuilder out) throws IOException {
		_context = context;
		
		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.ICON);
		{
			out.begin(A);
			{
				out.attr(CLASS_ATTR, CssClasses.ICON_LINK);
				if (_rowIndex > 0) {
					out.openAttr(STYLE_ATTR);
					out.append("margin-left: ");
					out.append(_spacing);
					out.append("px;");
					out.closeAttr();
				}
				if (!isEditMode()) {
					out.attr(HREF_ATTR, "#");
				}
				out.closeAttr();
				{
					out.begin(IMG);
					out.attr(CLASS_ATTR, CssClasses.ICON_DISPLAY);
					out.attr("title", _image.getComment());
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
						out.attr(CLASS_ATTR, CssClasses.VIDEO_OVERLAY);
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
			}
			out.end();
			if (!isEditMode()) {
				out.getLast().addEventListener("click", e -> {
					App.getInstance().showPage(_part, DisplayMode.DEFAULT);
					e.preventDefault();
					e.stopPropagation();
				});
			}
		}
		out.end();
	}

	private boolean isEditMode() {
		return getOwner().isEditMode();
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
			
			out.begin(SPAN);
			out.attr(CLASS_ATTR, CssClasses.TOOLBAR_BUTTON);
			{
				out.begin(I);
				out.attr(CLASS_ATTR, "fas fa-arrows-alt-v");
				out.end();
			}
			out.end();
			
			out.begin(SPAN);
			out.attr(CLASS_ATTR, CssClasses.TOOLBAR_BUTTON);
			{
				out.begin(I);
				out.attr(CLASS_ATTR, "fas fa-undo-alt");
				out.end();
			}
			out.end();
		}
		out.end();
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
		
		int rating = _image.getRating();
		Consumer<Object> setter = data -> _image.setRating((int) data);
		Runnable reset = () -> _image.setRating(0);
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
		new ImagePropertiesEditor(_image).showTopLevel(context());
		
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
