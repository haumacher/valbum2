/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import de.haumacher.imageServer.client.app.KeyCodes;
import de.haumacher.imageServer.client.app.ResourceHandler;
import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.imageServer.shared.ui.ImageRow;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Element;
import elemental2.dom.KeyboardEvent;

/**
 * Base class for displaying a list of {@link AlbumPart}s.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public abstract class AbstractAlbumDisplay extends ResourceDisplay {

	/** 
	 * Creates a {@link AbstractAlbumDisplay}.
	 */
	public AbstractAlbumDisplay(ResourceHandler handler) {
		super(handler);
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		out.attr(CLASS_ATTR, classes());
		
		renderContents(context, out);
		
		out.end();
	}

	private String classes() {
		List<String> classList = new ArrayList<>();
		
		buildClasses(classList);
		
		String result = classList.stream().collect(Collectors.joining(" "));
		return result;
	}

	protected void buildClasses(List<String> classList) {
		// Hook for sub-classes.
	}
	
	/** 
	 * Re-computes and sets the CSS classes.
	 */
	protected final void updateClasses() {
		element().className = classes();
	}

	protected void renderContents(UIContext context, DomBuilder out) throws IOException {
		renderImages(context, out);
		writeAlbumToolbar(out);
	}

	protected void renderImages(UIContext context, DomBuilder out) throws IOException {
		int width = context.getPageWidth();
		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.IMAGE_ROWS);
		out.attr(STYLE_ATTR, "width: " + width + "px;");
		{
			ImageRow row = new ImageRow(width, 400);
			for (AlbumPart part : getParts()) {
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
					
					renderNonImage(context, out, part);
				}
			}
			writeRow(context, out, row);
		}
		out.end();
	}

	protected int getMinRating() {
		return Integer.MIN_VALUE;
	}

	protected void renderNonImage(UIContext context, DomBuilder out, AlbumPart part) {
		// Hook for subclasses.
	}
	
	/** 
	 * The parts to display.
	 */
	protected abstract List<? extends AlbumPart<?>> getParts();

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
			
			for (int n = 0, cnt = row.getSize(); n < cnt; n++) {
				AbstractImage image = row.getImage(n);
				renderImage(context, out, row, rowHeight, spacing, n, image);
			}
			out.end();
		}
		out.end();
	}

	protected abstract void renderImage(UIContext context, DomBuilder out, ImageRow row,
			double rowHeight, int spacing, int n, AbstractImage image);
	
	@Override
	protected boolean handleKeyDown(Element target, KeyboardEvent event, String key) {
		if (!event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey) {
			switch (key) {
			case KeyCodes.ArrowRight:
				show(event, first(), drillDownMode());
				return true;
			case KeyCodes.ArrowLeft:
				show(event, last(), drillDownMode());
				return true;
			}
		}
		return super.handleKeyDown(target, event, key);
	}

	protected abstract DisplayMode drillDownMode();

	/** 
	 * The first resource to display in this collection.
	 */
	protected abstract Resource first();
	
	/**
	 * The last resource to display in this collection. 
	 */
	protected abstract Resource last();
}
