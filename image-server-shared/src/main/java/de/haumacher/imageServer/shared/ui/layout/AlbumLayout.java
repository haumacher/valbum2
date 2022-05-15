/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.haumacher.imageServer.shared.model.ImagePart;

/**
 * Algorithm layouting a sequence of image so that a given page width is filled allocating appropriate space for all
 * images.
 */
public class AlbumLayout implements Iterable<Row> {
	
	private final double _pageWidth;
	private final List<Row> _rows;
	
	/** 
	 * Creates a {@link AlbumLayout}.
	 *
	 * @param pageWidth The width of the page on which the result should be rendered.
	 * @param maxRowHeight The maximum height to scale a landscape image to.
	 * @param images the image to place on the page.
	 */
	public AlbumLayout(double pageWidth, double maxRowHeight, List<ImagePart> images) {
		_pageWidth = pageWidth;
		DefaultRowBuffer buffer = new DefaultRowBuffer();
		double minWidth = pageWidth / maxRowHeight;
		RowComputation rowComputation = new SimpleRowComputation(buffer, minWidth);
		for (ImagePart image : images) {
			Img content = new Img(image);
			
			if (content.getUnitWidth() >= minWidth) {
				// Take care of panorama images. Do not combine them with other images, because they would get scaled
				// down to a thumbnail.
				Row fullWidthRow = new Row();
				fullWidthRow.addContent(content);
				buffer.addRow(fullWidthRow);
			} else {
				rowComputation = rowComputation.addImage(content);
			}
		}
		rowComputation.end();
		
		_rows = buffer.getRows();
	}

	/** 
	 * Extracts all {@link ImagePart}s in this layout.
	 */
	public List<ImagePart> getAllImages() {
		class Collector implements ContentVisitor<Void, Void, RuntimeException> {
			
			List<ImagePart> _images = new ArrayList<>();

			@Override
			public Void visitRow(Row content, Void arg) throws RuntimeException {
				for (Content element : content) {
					element.visit(this, arg);
				}
				return null;
			}

			@Override
			public Void visitImg(Img content, Void arg) throws RuntimeException {
				_images.add(content.getImage());
				return null;
			}

			@Override
			public Void visitDoubleRow(DoubleRow content, Void arg) throws RuntimeException {
				content.getUpper().visit(this, arg);
				content.getLower().visit(this, arg);
				return null;
			}

			@Override
			public Void visitPadding(Padding content, Void arg) throws RuntimeException {
				return null;
			}
			
			/**
			 * All collected images.
			 */
			public List<ImagePart> getImages() {
				return _images;
			}
		}
		
		Collector collector = new Collector();
		for (Row row : _rows) {
			row.visit(collector, null);
		}
		return collector.getImages();
	}
	
	/**
	 * The width of the page, this layout is computed for.
	 */
	public double getPageWidth() {
		return _pageWidth;
	}
	
	@Override
	public Iterator<Row> iterator() {
		return _rows.iterator();
	}
	
	/**
	 * The {@link Row}s with content.
	 */
	public List<Row> getRows() {
		return _rows;
	}
	
	private static class SimpleRowComputation implements RowComputation {
		
		private final RowBuffer _out;
		
		private final double _minWidth;
		
		private Row currentRow = new Row();

		/** 
		 * Creates a {@link AlbumLayout.SimpleRowComputation}.
		 */
		public SimpleRowComputation(RowBuffer out, double minWidth) {
			_out = out;
			_minWidth = minWidth;
		}
		
		@Override
		public RowComputation addImage(Content img) {
			if (img.isPortrait()) {
				RowComputation inner = new DoubleRowComputation(_out, _minWidth);
				for (Content buffered : currentRow) {
					inner = inner.addImage(buffered);
				}
				return inner.addImage(img);
			} else {
				currentRow.addContent(img);
				if (isAcceptableWidth(currentRow.getUnitWidth())) {
					_out.addRow(currentRow);
					currentRow = new Row();
				}
				return this;
			}
		}
		
		@Override
		public void end() {
			if (!currentRow.isEmpty()) {
				currentRow.end(_minWidth);
				_out.addRow(currentRow);
			}
		}

		private boolean isAcceptableWidth(double currentWidth) {
			return  currentWidth >= _minWidth;
		}
	}
	
	/**
	 * {@link RowComputation} placing landscape images in two vertically aligned rows.
	 */
	private static class DoubleRowComputation implements RowComputation {
		
		private final RowBuffer _out;
		
		private final double _minWidth;
		
		private final double _halfMinWidth;
		
		private Row currentRow = new Row();
		
		private DoubleRowBuilder buffer = new DoubleRowBuilder();

		/** 
		 * Creates a {@link AlbumLayout.DoubleRowComputation}.
		 */
		public DoubleRowComputation(RowBuffer out, double minWidth) {
			_out = out;
			_minWidth = minWidth;
			_halfMinWidth = minWidth / 2;
		}

		@Override
		public RowComputation addImage(Content img) {
			if (img.isPortrait()) {
				DoubleRowBuilder prefix = buffer.split();
				if (prefix.acceptable()) {
					currentRow.addContent(prefix.build());
				} else {
					if (!prefix.isEmpty()) {
						// Revert to original state.
						prefix.addAll(buffer);
						buffer = prefix;
					}
				}
				currentRow.addContent(img);
				if (isAcceptableWidth(currentRow.getUnitWidth())) {
					_out.addRow(currentRow);
					
					// Re-do layout of buffered images.
					RowComputation result = new SimpleRowComputation(_out, _minWidth);
					for (Content content : buffer) {
						result = result.addImage(content);
					}

					return result;
				}
			} else {
				buffer.addContent(img);
				
				if (buffer.acceptable()) {
					if (isAcceptableWidth(currentRow.getUnitWidth() + buffer.getUnitWidth())) {
						currentRow.addContent(buffer.build());
						_out.addRow(currentRow);
						
						return new SimpleRowComputation(_out, _minWidth);
					}
				}
			}
			return this;
		}

		@Override
		public void end() {
			if (!buffer.isEmpty()) {
				currentRow.addContent(buffer.build());
			}
			currentRow.end(_halfMinWidth);
			_out.addRow(currentRow);
		}
		
		final boolean isAcceptableWidth(double currentWidth) {
			return  currentWidth >= _halfMinWidth;
		}

	}

}
