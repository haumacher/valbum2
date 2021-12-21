/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import de.haumacher.imageServer.client.app.App;
import de.haumacher.imageServer.client.app.ResourceHandler;
import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.imageServer.shared.ui.ImageRow;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.util.gwt.dom.DomBuilder;
import de.haumacher.util.xml.XmlFragment;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.KeyboardEvent;
import elemental2.dom.MouseEvent;

/**
 * {@link XmlFragment} displaying a {@link AlbumInfo} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumDisplay extends ResourceDisplay {

	private AlbumInfo _album;
	private Set<AbstractImage> _selected = new HashSet<>();
	private AlbumPart _lastClicked;
	private Map<AlbumPart, ImagePreviewDisplay> _imageDisplays;

	/** 
	 * Creates a {@link AlbumDisplay}.
	 * @param handler 
	 */
	public AlbumDisplay(AlbumInfo album, ResourceHandler handler) {
		super(handler);
		_album = album;
	}
	
	@Override
	protected AlbumInfo getResource() {
		return _album;
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		int width = context.getPageWidth();

		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		out.attr(CLASS_ATTR, classes());
		
		out.begin(H1);
		out.classAttr(CssClasses.HEADER);
		out.append(_album.getTitle());
		out.end();
		out.begin(H2);
		out.classAttr(CssClasses.HEADER);
		out.append(_album.getSubTitle());
		out.end();

		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.IMAGE_ROWS);
		out.attr(STYLE_ATTR, "width: " + width + "px;");
		{
			_imageDisplays = new HashMap<>();
			
			ImageRow row = new ImageRow(width, 400);
			for (AlbumPart part : _album.getParts()) {
				if (part instanceof AbstractImage) {
					AbstractImage image = (AbstractImage) part;
					if (ToImage.toImage(image).getRating() < getMinRating()) {
						continue;
					}
					
					if (row.isComplete()) {
						writeRow(context, out, row);
						row.clear();
					}
					row.add(image);
				} else {
					writeRow(context, out, row);
					row.clear();
					
					Heading heading = (Heading) part;
					new HeadingDisplay(this, heading).show(context, out);
				}
			}
			writeRow(context, out, row);
		}
		out.end();
		
		writeAlbumToolbar(out, false, this::showParent);
		out.end();
	}

	private void writeRow(UIContext context, DomBuilder out, ImageRow row) throws IOException {
		if (row.getSize() == 0) {
			return;
		}
		double rowHeight = row.getHeight();
		int spacing = row.getSpacing();

		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.ICONS);
		out.attr("style", "display: table; margin-top: " + spacing + "px;");
		{
			out.begin(DIV);
			out.attr(STYLE_ATTR, "display: table-row;");
			
			boolean editMode = isEditMode();
			for (int n = 0, cnt = row.getSize(); n < cnt; n++) {
				AbstractImage image = row.getImage(n);
				
				ImagePreviewDisplay previewDisplay = 
					new ImagePreviewDisplay(this, image, n, row.getScaledWidth(n), rowHeight, spacing);
				previewDisplay.setSelected(_selected.contains(image));
				previewDisplay.show(context, out);
				
				_imageDisplays.put(image, previewDisplay);

				if (editMode) {
					previewDisplay.addEventListener("click", event -> {
						MouseEvent mouseEvent = (MouseEvent) event;
						if (mouseEvent.shiftKey) {
							if (_lastClicked == null) {
								_lastClicked = _album.getParts().get(0);
							}
							
							boolean select = _selected.contains(_lastClicked);
							
							int start = _album.getParts().indexOf(_lastClicked);
							int stop = _album.getParts().indexOf(previewDisplay.getPart());
							int delta = start < stop ? 1 : -1;
							for (int index = start + delta; index != stop + delta; index+=delta) {
								AlbumPart rangePart = _album.getParts().get(index);
								if (rangePart instanceof AbstractImage) {
									ImagePart current = ToImage.toImage((AbstractImage) rangePart);
									ImagePreviewDisplay currentDisplay = _imageDisplays.get(current);
									setSelected(currentDisplay, select);
								}
							}
						} else if (mouseEvent.ctrlKey) {
							toggleSelection(previewDisplay);
						} else {
							boolean toggle = (_selected.size() == 1 && _selected.contains(previewDisplay.getPart()));
							for (AbstractImage selectedInfo : _selected) {
								_imageDisplays.get(selectedInfo).setSelected(false);
							}
							_selected.clear();

							if (!toggle) {
								setSelected(previewDisplay, true);
							}
						}
						
						_lastClicked = previewDisplay.getPart();
						
						event.stopPropagation();
						event.preventDefault();
					});
				}
			}
			out.end();
		}
		out.end();
	}

	private void toggleSelection(ImagePreviewDisplay previewDisplay) {
		setSelected(previewDisplay, !previewDisplay.isSelected());
	}

	private void setSelected(ImagePreviewDisplay previewDisplay, boolean select) {
		if (select) {
			_selected.add(previewDisplay.getPart());
		} else {
			_selected.remove(previewDisplay.getPart());
		}
		
		element().className = classes();
		
		if (!select) {
			previewDisplay.setSelected(false);
		}
		for (AbstractImage selected : _selected) {
			// Update due to multi-selection property has changed.
			_imageDisplays.get(selected).setSelected(true);
		}
	}
	
	private String classes() {
		List<String> classList = new ArrayList<>();
		
		int selectionSize = _selected.size();
		if (selectionSize > 0) {
			classList.add(CssClasses.SELECTION);
		} else {
			classList.add(CssClasses.NO_SELECTION);
		}
		
		if (selectionSize > 1) {
			classList.add(CssClasses.MULTI_SELECTION);
		} else {
			classList.add(CssClasses.NO_MULTI_SELECTION);
		}
		
		String result = classList.stream().collect(Collectors.joining(" "));
		return result;
	}

	/** 
	 * Whether more than one image is selected.
	 */
	public boolean hasMultiSelection() {
		return isEditMode() && _selected.size() > 1;
	}

	public void groupSelection(AbstractImage representative) {
		ImageGroup group = ImageGroup.create();
		for (AbstractImage<?> selected : _selected) {
			selected.visit(new AbstractImage.Visitor<Void, List<ImagePart>, RuntimeException>() {
				@Override
				public Void visit(ImageGroup self, List<ImagePart> arg) {
					arg.addAll(self.getImages());
					return null;
				}

				@Override
				public Void visit(ImagePart self, List<ImagePart> arg) {
					arg.add(self);
					return null;
				}
				
			}, group.getImages());
		}
		Collections.sort(group.getImages(), (a, b) -> Long.compare(a.getDate(), b.getDate()));
		group.setRepresentative(group.getImages().indexOf(ToImage.toImage(representative)));
		
		List<AlbumPart<?>> newParts = _album.getParts().stream().map(p -> p == representative ? group : p).filter(p -> !_selected.contains(p)).collect(Collectors.toList());
		_album.setParts(newParts);
		
		_selected.clear();
		
		redraw();
	}
	
	@Override
	protected boolean handleKeyDown(Element target, KeyboardEvent event, String key) {
		switch (key) {
		case "+": {
			if (getMinRating() > -1) {
				// Show more images.
				setMinRating(getMinRating() - 1);
			}
			redraw();
			return true;
		}
		case "-": {
			if (getMinRating() < 2) {
				// Show less images.
				setMinRating(getMinRating() + 1);
			}
			redraw();
			return true;
		}
		}
		return super.handleKeyDown(target, event, key);
	}
	
	@Override
	protected void openSettings() {
		new AlbumPropertiesEditor(this, _album).showTopLevel(context());
	}

	private void showParent(Event event) {
		String parentUrl = RenderUtil.parentUrl(_album.getPath());
		App.getInstance().gotoTarget(parentUrl);
		event.stopPropagation();
		event.preventDefault();
	}

	private int getMinRating() {
		return _album.getMinRating();
	}

	private void setMinRating(int minRating) {
		_album.setMinRating(minRating);
	}

}
