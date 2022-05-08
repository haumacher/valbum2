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
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.CssClasses;
import de.haumacher.imageServer.shared.ui.layout.AlbumLayout;
import de.haumacher.imageServer.shared.ui.layout.Content;
import de.haumacher.imageServer.shared.ui.layout.ContentVisitor;
import de.haumacher.imageServer.shared.ui.layout.DoubleRow;
import de.haumacher.imageServer.shared.ui.layout.Img;
import de.haumacher.imageServer.shared.ui.layout.Padding;
import de.haumacher.imageServer.shared.ui.layout.Row;
import de.haumacher.imageServer.shared.util.ToImage;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.DomGlobal;
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
		int maxRowHeight = Math.min(400, DomGlobal.window.innerHeight * 2 / 3);
		int pageWidth = context.getPageWidth();
		
		out.begin(DIV);
		out.attr(CLASS_ATTR, CssClasses.IMAGE_ROWS);
		out.attr(STYLE_ATTR, "width: " + pageWidth + "px;");
		{
			List<ImagePart> images = new ArrayList<>();
			for (AlbumPart part : getParts()) {
				if (part instanceof AbstractImage) {
					ImagePart image = (ImagePart) part;
					if (ToImage.toImage(image).getRating() < getMinRating()) {
						continue;
					}

					images.add(image);
				} else {
					flush(context, out, pageWidth, maxRowHeight, images);
					
					renderNonImage(context, out, part);
				}
			}
			flush(context, out, pageWidth, maxRowHeight, images);
		}
		out.end();
	}

	private void flush(UIContext context, DomBuilder out, double pageWidth, double maxRowHeight, List<ImagePart> images) throws IOException {
		if (images.isEmpty()) {
			return;
		}
		
		AlbumLayout layout = new AlbumLayout(pageWidth, maxRowHeight, images);
		writeRows(context, out, layout);
		images.clear();
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
	protected abstract List<? extends AlbumPart> getParts();

	private static final int PADDING = 2;

	private void writeRows(UIContext context, DomBuilder out, AlbumLayout layout) throws IOException {
		ContentVisitor<Void, Options, IOException> renderer = new ContentVisitor<Void, Options, IOException>() {
			@Override
			public Void visitRow(Row row, Options arg) throws IOException {
				double availableWidth = arg.outputWidth - PADDING * (row.size() - 1);
				double scale = availableWidth / row.getUnitWidth();
				double rowHeight = arg.rowHeight;
				
				out.begin(DIV);
				out.attr(CLASS_ATTR, CssClasses.ICONS);
				int vSpacing = arg.vSpacing;
				if (vSpacing > 0) {
					out.attr(STYLE_ATTR, "margin-top: " + vSpacing + "px");
				}
				{
					out.begin(DIV);
					out.attr(CLASS_ATTR, CssClasses.ICON_ROW);
					
					int n = 0;
					for (Content content : row) {
						int hSpacing = n == 0 ? 0 : PADDING;
						content.visit(this, options(content.getUnitWidth() * scale, rowHeight, hSpacing, n == 0 ? 0 : PADDING));
						n++;
					}
					out.end();
				}
				out.end();
				return null;
			}

			@Override
			public Void visitImg(Img content, Options arg) {
				double height = arg.rowHeight;
				double width = content.getUnitWidth() * height;
				
				renderImage(context, out, width, height, arg.hSpacing, content.getImage());
				return null;
			}

			@Override
			public Void visitDoubleRow(DoubleRow content, Options arg) throws IOException {
				double availableHeight = arg.rowHeight - PADDING;
				
				out.begin(DIV);
				out.attr(CLASS_ATTR, CssClasses.ICON_STACK);
				out.attr(STYLE_ATTR, "height: " + arg.rowHeight + "px;");
				int hSpacing = arg.hSpacing;
				if (hSpacing > 0) {
					out.attr(STYLE_ATTR, "padding-left: " + hSpacing + "px");
				}
				{
					out.begin(DIV);
					out.attr(CLASS_ATTR, CssClasses.STACK_ROW);
					content.getUpper().visit(this, options(arg.outputWidth, availableHeight * content.getH1(), 0, 0));
					out.end();
					
					out.begin(DIV);
					out.attr(CLASS_ATTR, CssClasses.STACK_ROW);
					out.attr(STYLE_ATTR, "margin-top: " + PADDING + "px");
					content.getLower().visit(this, options(arg.outputWidth, availableHeight * content.getH2(), 0, 0));
					out.end();
				}
				out.end();
				return null;
			}

			@Override
			public Void visitPadding(Padding content, Options arg) {
				// Ignore, automatically considered by the CSS engine.
				return null;
			}
		};
		
		double pageWidth = layout.getPageWidth();
		for (Row row : layout) {
			double scale = pageWidth / row.getUnitWidth();
			double rowHeight = scale;
			
			row.visit(renderer, options(pageWidth, rowHeight, 0, PADDING));
		}
	}

	protected final Options options(double outputWidth, double rowHeight, int hSpacing, int vSpacing) {
		return new Options(outputWidth, rowHeight, hSpacing, vSpacing);
	}

	protected abstract void renderImage(UIContext context, DomBuilder out, double scaledWidth,
			double rowHeight, int marginLeft, AbstractImage image);
	
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
	
	static final class Options {
		final double outputWidth;
		final double rowHeight;
		final int hSpacing;
		final int vSpacing;
		
		/** 
		 * Creates a {@link Options}.
		 */
		public Options(double outputWidth, double rowHeight, int hSpacing, int vSpacing) {
			this.outputWidth = outputWidth;
			this.rowHeight = rowHeight;
			this.hSpacing = hSpacing;
			this.vSpacing = vSpacing;
		}
	}
	
}
