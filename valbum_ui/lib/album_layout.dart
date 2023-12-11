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
	public AlbumLayout(double pageWidth, double maxRowHeight, List<? extends AbstractImage> images) {
		_pageWidth = pageWidth;
		DefaultRowBuffer buffer = new DefaultRowBuffer();
		double minWidth = pageWidth / maxRowHeight;
		RowComputation rowComputation = new SimpleRowComputation(buffer, minWidth);
		for (AbstractImage image : images) {
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
	public List<AbstractImage> getAllImages() {
		class Collector implements ContentVisitor<Void, Void, RuntimeException> {
			
			List<AbstractImage> _images = new ArrayList<>();

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
			public List<AbstractImage> getImages() {
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

/**
 * A part of a {@link Row}.
 */
public interface Content {
	
	/**
	 * The maximum width of an image (relative to its height) to interpret it as an portrait image (taking two lines in
	 * an {@link AlbumLayout}).
	 */
	public static final double MAX_PORTRAIT_UNIT_WIDTH = 0.75;

	/**
	 * The width of the content, if it's height is scaled to <code>1.0</code>.
	 */
	double getUnitWidth();
	
	/** 
	 * The number of grid rows, this {@link Content} spans.
	 */
	int getUnitHeight();

	/**
	 * Whether this is a portrait image, with a height considerably larger than its width.
	 */
	default boolean isPortrait() {
		return getUnitWidth() <= MAX_PORTRAIT_UNIT_WIDTH;
	}
	
	/**
	 * Visits this {@link Content} with the given {@link ContentVisitor}
	 */
	<R,A,E extends Throwable> R visit(ContentVisitor<R,A,E> visitor, A arg) throws E;


}

/**
 * Visitor for {@link Content}.
 * 
 * @see Content#visit
 */
public interface ContentVisitor<R, A, E extends Throwable> {
	
	R visitRow(Row content, A arg) throws E;
	R visitImg(Img content, A arg) throws E;
	R visitDoubleRow(DoubleRow content, A arg) throws E;
	R visitPadding(Padding content, A arg) throws E;

}

/**
 * {@link RowBuffer} building a {@link List} of {@link Row}s.
 */
public class DefaultRowBuffer implements RowBuffer {

	private List<Row> _rows = new ArrayList<>();

	@Override
	public void addRow(Row newRow) {
		_rows.add(newRow);
	}
	
	/**
	 * The created rows.
	 */
	public List<Row> getRows() {
		return _rows;
	}

}

/**
 * Builder for a {@link DoubleRow}.
 */
public class DoubleRowBuilder implements Iterable<Content> {
	
	private static final double LOWER_LIMIT = 3.0 / 4.0;
	private static final double UPPER_LIMIT = 4.0 / 3.0;
	
	private Row _upper = new Row();
	private Row _lower = new Row();
	
	private List<RowState> _states = new ArrayList<>();
	
	private class RowState {
		private final double _unitWidth;
		private final boolean _acceptable;
		private final Content _lastAdded;
		private double _h1;
		private double _h2;

		public RowState(Content lastAdded) {
			_lastAdded = lastAdded;
			
			double w1 = upperWidth();
			double w2 = lowerWidth();
			if (w1 == 0.0) {
				_unitWidth = w2;
				_acceptable = false;
			} else if (w2 == 0.0) {
				_unitWidth = w1;
				_acceptable = false;
			} else {
				double w1Inv = 1/w1;
				double w2Inv = 1/w2;
				double invSum = w1Inv + w2Inv;
				
				_unitWidth = 1 / invSum;
				
				_h1 = w1Inv / invSum;
				_h2 = w2Inv / invSum;
				
				double hQuot = _h1 / _h2;
				
				_acceptable = LOWER_LIMIT <= hQuot && hQuot <= UPPER_LIMIT;
			}
		}
		
		/**
		 * The relative height of the upper row.
		 */
		public double getH1() {
			return _h1;
		}
		
		/**
		 * The relative height of the lower row.
		 */
		public double getH2() {
			return _h2;
		}
		
		/**
		 * The content that was added just before the {@link RowState} computation was done.
		 * 
		 * @return The {@link Content} added before the computation was done, or <code>null</code>, if this is the
		 *         initial value.
		 */
		public Content getLastAdded() {
			return _lastAdded;
		}

		/**
		 * The computed unit width just after {@link #getLastAdded()} was added.
		 */
		public double getUnitWidth() {
			return _unitWidth;
		}

		/**
		 * The computed acceptable state just after {@link #getLastAdded()} was added.
		 */
		public boolean isAcceptable() {
			return _acceptable;
		}
	}
	
	DoubleRowBuilder() {
		addState(null);
	}

	/** 
	 * Creates a {@link DoubleRowBuilder}.
	 */
	private DoubleRowBuilder(List<RowState> states) {
		this();
		for (RowState state : states) {
			addContent(state.getLastAdded());
		}
	}
	
	/**
	 * The upper {@link Row}.
	 */
	public Row getUpper() {
		return _upper;
	}
	
	/**
	 * The lower {@link Row}.
	 */
	public Row getLower() {
		return _lower;
	}

	/** 
	 * Tries to split of the largest acceptable prefix. 
	 * 
	 * <p>
	 * The state after this method returns only contains contents in the suffix after the split out prefix.
	 * </p>
	 */
	public DoubleRowBuilder split() {
		for (int index = _states.size() - 1; index > 0 ; index--) {
			if (_states.get(index).isAcceptable()) {
				DoubleRowBuilder prefix = new DoubleRowBuilder(_states.subList(1, index + 1));
				DoubleRowBuilder suffix = new DoubleRowBuilder(_states.subList(index + 1, _states.size()));
				resetTo(suffix);
				return prefix;
			}
		}
		
		return new DoubleRowBuilder();
	}

	private void resetTo(DoubleRowBuilder other) {
		_upper = other._upper;
		_lower = other._lower;
		_states = other._states;
	}

	final boolean isEmpty() {
		return _states.size() == 1;
	}

	private void addState(Content content) {
		_states.add(new RowState(content));
	}

	final double getUnitWidth() {
		return top().getUnitWidth();
	}
	
	final boolean acceptable() {
		return top().isAcceptable();
	}
	
	private RowState top() {
		return _states.get(_states.size() - 1);
	}
	
	final void addContent(Content content) {
		Row smaller = smaller();
		smaller.addContent(content);
		addState(content);
	}
	
	private Row smaller() {
		return _upper.getUnitWidth() <=_lower.getUnitWidth() ? _upper : _lower;
	}

	final double upperWidth() {
		return _upper.getUnitWidth();
	}

	final double lowerWidth() {
		return _lower.getUnitWidth();
	}
	
	final DoubleRow build() {
		if (!acceptable()) {
			double w1 = upperWidth();
			double w2 = lowerWidth();
			
			addContent(new Padding(Math.abs(w1 - w2)));
			
			if (w2 > w1) {
				flip();
			}
			
			assert acceptable();
		}

		RowState top = top();
		return new DoubleRow(_upper, _lower, top.getUnitWidth(), top.getH1(), top.getH2());
	}
	
	private void flip() {
		Row upper = _upper;
		_upper = _lower;
		_lower = upper;
	}

	@Override
	public Iterator<Content> iterator() {
		Iterator<RowState> inner = _states.subList(1, _states.size()).iterator();
		
		return new Iterator<Content>() {
			@Override
			public boolean hasNext() {
				return inner.hasNext();
			}

			@Override
			public Content next() {
				return inner.next().getLastAdded();
			}
		};
	}

	/** 
	 * Adds all {@link Content} to this builder.
	 */
	public void addAll(Iterable<Content> contents) {
		for (Content content : contents) {
			addContent(content);
		}
	}
	
}

/**
 * A vertical placements of two {@link Row}s scaled to the same width.
 */
public class DoubleRow implements Content {

	private final double _unitWidth;
	
	private final Row _upper;
	private final Row _lower;

	private double _h1;

	private double _h2;

	/** 
	 * Creates a {@link DoubleRow}.
	 */
	public DoubleRow(Row upper, Row lower, double unitWidth, double h1, double h2) {
		_upper = upper;
		_lower = lower;
		_unitWidth = unitWidth;
		_h1 = h1;
		_h2 = h2;
	}
	
	/**
	 * The upper {@link Row}.
	 */
	public Row getUpper() {
		return _upper;
	}
	
	/**
	 * The lower {@link Row}.
	 */
	public Row getLower() {
		return _lower;
	}
	
	/**
	 * Relative height of {@link #getUpper()}.
	 */
	public double getH1() {
		return _h1;
	}
	
	/**
	 * Relative height of {@link #getLower()}.
	 */
	public double getH2() {
		return _h2;
	}

	@Override
	public double getUnitWidth() {
		return _unitWidth;
	}

	@Override
	public int getUnitHeight() {
		return 2;
	}

	@Override
	public <R, A, E extends Throwable> R visit(ContentVisitor<R, A, E> visitor, A arg) throws E {
		return visitor.visitDoubleRow(this, arg);
	}
	
}

/**
 * An atomic image placed in a {@link Row}.
 */
public class Img implements Content {
	
	private final double _unitWidth;
	private AbstractImage _image;
	
	/** 
	 * Creates a {@link Img}.
	 */
	public Img(AbstractImage image) {
		_image = image;
		ImagePart representative = ToImage.toImage(image);
		int width = representative.getWidth();
		int height = representative.getHeight();
		Orientation orientation = representative.getOrientation();
		
		int displayWidth = Orientations.width(orientation, width, height);
		int displayHeight = Orientations.height(orientation, width, height);
		
		_unitWidth = ((double) displayWidth) / displayHeight;
	}
	
	/**
	 * The {@link AbstractImage} represented by this {@link Content}.
	 */
	public AbstractImage getImage() {
		return _image;
	}
	
	/**
	 * The width of the image, if it was scaled to a height of <code>1.0</code>.
	 */
	@Override
	public double getUnitWidth() {
		return _unitWidth;
	}
	
	@Override
	public int getUnitHeight() {
		return 1;
	}
	
	@Override
	public <R, A, E extends Throwable> R visit(ContentVisitor<R, A, E> visitor, A arg) throws E {
		return visitor.visitImg(this, arg);
	}
	
}

/**
 * Empty space inserted to a layout to make it's constraints acceptable. 
 */
public class Padding implements Content {

	private double _unitWidth;

	/** 
	 * Creates a {@link Padding}.
	 */
	public Padding(double unitWidth) {
		_unitWidth = unitWidth;
	}

	@Override
	public double getUnitWidth() {
		return _unitWidth;
	}
	
	@Override
	public int getUnitHeight() {
		return 1;
	}

	@Override
	public <R, A, E extends Throwable> R visit(ContentVisitor<R, A, E> visitor, A arg) throws E {
		return visitor.visitPadding(this, arg);
	}

}

/**
 * Buffer of {@link Row} used in a {@link RowComputation}.
 */
public interface RowBuffer {

	/** 
	 * Adds the next completed row.
	 */
	void addRow(Row newRow);

}

/**
 * Algorithm for placing images into rows.
 * 
 * @see {@link RowBuffer}
 */
interface RowComputation {

	/**
	 * Adds the given image.
	 *
	 * @param img The image to place next.
	 * @return The {@link RowComputation} algorithm to continue with.
	 */
	RowComputation addImage(Content img);

	/**
	 * Flushes buffers and completes the computation.
	 */
	void end();
	
}

/**
 * A horizontal layout of {@link Content}.
 */
public class Row implements Content, Iterable<Content> {
	
	private final List<Content> _contents = new ArrayList<>();
	
	private double _unitWidth;

	private int _height = 1;
	
	final void addContent(Content content) {
		_contents.add(content);
		_unitWidth += content.getUnitWidth();
		_height = Math.max(_height, content.getUnitHeight());
	}
	
	@Override
	public double getUnitWidth() {
		return _unitWidth;
	}
	
	@Override
	public int getUnitHeight() {
		return _height;
	}

	/**
	 * Whether this {@link Row} has no contents.
	 */
	final boolean isEmpty() {
		return _contents.isEmpty();
	}

	@Override
	public Iterator<Content> iterator() {
		return _contents.iterator();
	}
	
	@Override
	public <R, A, E extends Throwable> R visit(ContentVisitor<R, A, E> visitor, A arg) throws E {
		return visitor.visitRow(this, arg);
	}

	/** 
	 * The number of {@link Content}s in this {@link Row}.
	 */
	public int size() {
		return _contents.size();
	}

	void end(double minWidth) {
		double unitWidth = getUnitWidth();
		if (unitWidth < minWidth) {
			addContent(new Padding(minWidth - unitWidth));
		}
	}
}