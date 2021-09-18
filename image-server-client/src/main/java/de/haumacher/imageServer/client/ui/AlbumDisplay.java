/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.ui.DataAttributes;
import de.haumacher.imageServer.shared.ui.ImageRow;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.util.gwt.dom.DomBuilder;
import de.haumacher.util.xml.XmlFragment;
import elemental2.dom.MouseEvent;

/**
 * {@link XmlFragment} displaying a {@link AlbumInfo} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumDisplay extends ResourceDisplay {

	private AlbumInfo _album;
	private Set<ImageInfo> _selected = new HashSet<>();
	private AlbumPart _lastClicked;
	
	/** 
	 * Creates a {@link AlbumDisplay}.
	 */
	public AlbumDisplay(AlbumInfo album) {
		_album = album;
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		int width = context.getPageWidth();
		String parentUrl = RenderUtil.parentUrl(_album.getDepth());

		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		if (parentUrl != null) {
			out.attr(DataAttributes.DATA_ESCAPE, parentUrl);
		}
		
		out.begin(H1);
		out.append(_album.getTitle());
		out.end();
		out.begin(H2);
		out.append(_album.getSubTitle());
		out.end();

		out.begin(DIV);
		out.attr(CLASS_ATTR, "image-rows");
		out.attr(STYLE_ATTR, "width: " + width + "px;");
		{
			Map<ImageInfo, ImagePreviewDisplay> imageDisplays = new HashMap<>();
			
			ImageRow row = new ImageRow(width, 400);
			for (AlbumPart image : _album.getImages()) {
				if (row.isComplete()) {
					writeRow(context, out, imageDisplays, row);
					row.clear();
				}
				row.add(ToImage.toImage(image));
			}
			writeRow(context, out, imageDisplays, row);
		}
		out.end();
		
		if (parentUrl != null) {
			writeAlbumToolbar(out, false, parentUrl);
		}
		out.end();
	}

	private void writeRow(UIContext context, DomBuilder out, Map<ImageInfo, ImagePreviewDisplay> imageDisplays, ImageRow row) throws IOException {
		if (row.getSize() == 0) {
			return;
		}
		double rowHeight = row.getHeight();
		int spacing = row.getSpacing();

		out.begin(DIV);
		out.attr(CLASS_ATTR, "icons");
		out.attr("style", "display: table; margin-top: " + spacing + "px;");
		{
			out.begin(DIV);
			out.attr(STYLE_ATTR, "display: table-row;");
			
			boolean editMode = isEditMode();
			for (int n = 0, cnt = row.getSize(); n < cnt; n++) {
				ImageInfo image = row.getImage(n);
				
				ImagePreviewDisplay previewDisplay = new ImagePreviewDisplay(image, n, row.getScaledWidth(n), rowHeight, spacing);
				previewDisplay.setEditMode(editMode);
				previewDisplay.setSelected(_selected.contains(image));
				previewDisplay.show(context, out);
				
				imageDisplays.put(image, previewDisplay);

				if (editMode) {
					previewDisplay.addEventListener("click", event -> {
						MouseEvent mouseEvent = (MouseEvent) event;
						if (mouseEvent.shiftKey) {
							if (_lastClicked == null) {
								_lastClicked = _album.getImages().get(0);
							}
							
							boolean select = _selected.contains(_lastClicked);
							
							int start = _album.getImages().indexOf(_lastClicked);
							int stop = _album.getImages().indexOf(previewDisplay.getImage());
							int delta = start < stop ? 1 : -1;
							for (int index = start + delta; index != stop + delta; index+=delta) {
								ImageInfo current = ToImage.toImage(_album.getImages().get(index));
								ImagePreviewDisplay currentDisplay = imageDisplays.get(current);
								setSelected(currentDisplay, select);
							}
						} else if (mouseEvent.ctrlKey) {
							toggleSelection(previewDisplay);
						} else {
							boolean toggle = (_selected.size() == 1 && _selected.contains(previewDisplay.getImage()));
							for (ImageInfo selectedInfo : _selected) {
								imageDisplays.get(selectedInfo).setSelected(false);
							}
							_selected.clear();

							if (!toggle) {
								setSelected(previewDisplay, true);
							}
						}
						
						_lastClicked = previewDisplay.getImage();
						
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
			_selected.add(previewDisplay.getImage());
		} else {
			_selected.remove(previewDisplay.getImage());
		}
		previewDisplay.setSelected(select);
	}

}
