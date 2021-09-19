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

import de.haumacher.imageServer.client.app.ResourceHandler;
import de.haumacher.imageServer.client.bulma.form.Input;
import de.haumacher.imageServer.client.bulma.modal.ModalCard;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.imageServer.shared.ui.DataAttributes;
import de.haumacher.imageServer.shared.ui.ImageRow;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.util.gwt.dom.DomBuilder;
import de.haumacher.util.xml.XmlFragment;
import elemental2.dom.Event;
import elemental2.dom.MouseEvent;

/**
 * {@link XmlFragment} displaying a {@link AlbumInfo} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumDisplay extends ResourceDisplay {

	private AlbumInfo _album;
	private Set<AlbumPart> _selected = new HashSet<>();
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
	protected Resource getResource() {
		return _album;
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		int width = context.getPageWidth();
		String parentUrl = RenderUtil.parentUrl(_album.getDepth());

		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		out.attr(CLASS_ATTR, classes());
		if (parentUrl != null) {
			out.attr(DataAttributes.DATA_ESCAPE, parentUrl);
		}
		
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
			for (AlbumPart image : _album.getParts()) {
				if (row.isComplete()) {
					writeRow(context, out, row);
					row.clear();
				}
				row.add(image);
			}
			writeRow(context, out, row);
		}
		out.end();
		
		if (parentUrl != null) {
			writeAlbumToolbar(out, false, parentUrl);
		}
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
				AlbumPart image = row.getImage(n);
				
				ImagePreviewDisplay previewDisplay = new ImagePreviewDisplay(this, image, n, row.getScaledWidth(n), rowHeight, spacing);
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
								ImagePart current = ToImage.toImage(_album.getParts().get(index));
								ImagePreviewDisplay currentDisplay = _imageDisplays.get(current);
								setSelected(currentDisplay, select);
							}
						} else if (mouseEvent.ctrlKey) {
							toggleSelection(previewDisplay);
						} else {
							boolean toggle = (_selected.size() == 1 && _selected.contains(previewDisplay.getPart()));
							for (AlbumPart selectedInfo : _selected) {
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
		for (AlbumPart selected : _selected) {
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

	public void groupSelection(AlbumPart representative) {
		ImageGroup group = ImageGroup.create();
		for (AlbumPart selected : _selected) {
			selected.visit(new AlbumPart.Visitor<Void, List<ImagePart>>() {
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
		
		List<AlbumPart> newParts = _album.getParts().stream().map(p -> p == representative ? group : p).filter(p -> !_selected.contains(p)).collect(Collectors.toList());
		_album.setParts(newParts);
		
		_selected.clear();
		
		redraw();
	}
	
	@Override
	protected void openSettings(UIContext context, DomBuilder out) {
		new ModalCard() {
			
			private Input _titleInput;
			private Input _subtitleInput;

			protected void renderTitle(UIContext context, DomBuilder out) throws IOException {
				out.append("Eigenschaften des Albums bearbeiten");
			}
			
			@Override
			protected void renderContent(UIContext context, DomBuilder out) {
				_titleInput = new Input().setLabel("Titel").setValue(_album.getTitle());
				_titleInput.show(context, out);
				
				_subtitleInput = new Input().setLabel("Subtitel").setValue(_album.getSubTitle());
				_subtitleInput.show(context, out);
			}
			
			protected void renderButtons(UIContext context, DomBuilder out) throws IOException {
				out.begin(BUTTON);
				out.classAttr("button is-success");
				out.append("Übernehmen");
				out.end();
				
				out.getLast().addEventListener("click", this::handleOk);
			}
			
			private void handleOk(Event event) {
				_album.setTitle(_titleInput.getValue());
				_album.setSubTitle(_subtitleInput.getValue());
				
				remove();
				AlbumDisplay.this.redraw();
			}
		}.show(context(), out);
	}

}
