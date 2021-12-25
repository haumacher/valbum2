/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import java.util.List;

import de.haumacher.imageServer.client.app.ResourceHandler;
import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.ImageRow;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Event;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class GroupDisplay extends AbstractAlbumDisplay {

	private ImageGroup _group;

	/** 
	 * Creates a {@link GroupDisplay}.
	 */
	public GroupDisplay(ImageGroup group, ResourceHandler handler) {
		super(handler);
		_group = group;
	}

	@Override
	protected List<? extends AlbumPart> getParts() {
		return _group.getImages();
	}

	@Override
	protected void renderImage(UIContext context, DomBuilder out, ImageRow row,
			double rowHeight, int spacing, int n, AbstractImage image) {
		
		PreviewDisplay display = new PreviewDisplay(image, n, row.getScaledWidth(n), rowHeight, spacing);
		display.show(context, out);
	}

	@Override
	protected void showParent(Event event) {
		show(event, _group, DisplayMode.DEFAULT);
	}

	@Override
	protected Resource getResource() {
		return _group;
	}
	
	@Override
	protected Resource first() {
		return _group.getImages().get(0);
	}
	
	@Override
	protected Resource last() {
		return _group.getImages().get(_group.getImages().size() - 1);
	}

	@Override
	protected DisplayMode drillDownMode() {
		return DisplayMode.DETAIL;
	}
}
