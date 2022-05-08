/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;
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
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.imageServer.shared.util.UpdateTransient;
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
public class AlbumDisplay extends AbstractAlbumDisplay {

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
	protected void renderContents(UIContext context, DomBuilder out) throws IOException {
		out.begin(H1);
		out.classAttr(CssClasses.HEADER);
		out.append(_album.getTitle());
		out.end();
		out.begin(H2);
		out.classAttr(CssClasses.HEADER);
		out.append(_album.getSubTitle());
		out.end();

		super.renderContents(context, out);
	}

	@Override
	protected List<? extends AlbumPart> getParts() {
		return _album.getParts();
	}
	
	@Override
	protected void renderImages(UIContext context, DomBuilder out) throws IOException {
		_imageDisplays = new HashMap<>();
		
		super.renderImages(context, out);
	}
	
	@Override
	protected void renderImage(UIContext context, DomBuilder out, double scaledWidth,
			double rowHeight, int marginLeft, AbstractImage image) {
		ImagePreviewDisplay previewDisplay = 
			new ImagePreviewDisplay(this, image, scaledWidth, rowHeight, marginLeft);
		previewDisplay.setSelected(_selected.contains(image));
		previewDisplay.show(context, out);
		
		_imageDisplays.put(image, previewDisplay);

		if (isEditMode()) {
			previewDisplay.addEventListener("click", event -> {
				MouseEvent mouseEvent = (MouseEvent) event;
				if (mouseEvent.shiftKey) {
					List<? extends AlbumPart> parts = getParts();
					if (_lastClicked == null) {
						_lastClicked = parts.get(0);
					}
					
					boolean select = _selected.contains(_lastClicked);
					
					int start = parts.indexOf(_lastClicked);
					int stop = parts.indexOf(previewDisplay.getPart());
					int delta = start < stop ? 1 : -1;
					for (int index = start + delta; index != stop + delta; index+=delta) {
						AlbumPart rangePart = parts.get(index);
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
	
	@Override
	protected void renderNonImage(UIContext context, DomBuilder out, AlbumPart part) {
		if (part instanceof Heading) {
			Heading heading = (Heading) part;
			new HeadingDisplay(this, heading).show(context, out);
			return;
		}
		
		super.renderNonImage(context, out, part);
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
		
		updateClasses();
		
		if (!select) {
			previewDisplay.setSelected(false);
		}
		for (AbstractImage selected : _selected) {
			// Update due to multi-selection property has changed.
			_imageDisplays.get(selected).setSelected(true);
		}
	}

	@Override
	protected void buildClasses(List<String> classList) {
		super.buildClasses(classList);

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
	}
	
	/** 
	 * Whether more than one image is selected.
	 */
	public boolean hasMultiSelection() {
		return isEditMode() && _selected.size() > 1;
	}

	public void groupSelection(AbstractImage representative) {
		ImageGroup group = ImageGroup.create();
		for (AbstractImage selected : _selected) {
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
		
		List<AlbumPart> newParts = getParts().stream().map(p -> p == representative ? group : p).filter(p -> !_selected.contains(p)).collect(Collectors.toList());
		_album.setParts(newParts);
		
		_selected.clear();
		
		// Recompute linking.
		UpdateTransient.updateTransient(_album);
		
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

	@Override
	protected void showParent(Event event) {
		String parentUrl = RenderUtil.parentUrl(_album.getPath());
		App.getInstance().showPage(parentUrl);
		event.stopPropagation();
		event.preventDefault();
	}

	@Override
	protected int getMinRating() {
		return _album.getMinRating();
	}

	private void setMinRating(int minRating) {
		_album.setMinRating(minRating);
	}
	
	@Override
	protected Resource first() {
		return ImageDisplay.navigate0(firstImage(), AbstractImage::getNext);
	}

	private AbstractImage firstImage() {
		for (AlbumPart part : _album.getParts()) {
			if (part instanceof AbstractImage) {
				return (AbstractImage) part;
			}
		}
		return null;
	}
	
	@Override
	protected Resource last() {
		return ImageDisplay.navigate0(lastImage(), AbstractImage::getPrevious);
	}

	private AbstractImage lastImage() {
		List<AlbumPart> parts = _album.getParts();
		for (int n = parts.size() - 1; n >= 0; n--) {
			AlbumPart part = parts.get(n);
			if (part instanceof AbstractImage) {
				return (AbstractImage) part;
			}
		}
		return null;
	}
	
	@Override
	protected DisplayMode drillDownMode() {
		return DisplayMode.DEFAULT;
	}

}
