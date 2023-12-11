import 'package:valbum_ui/resource.dart';

/**
 * Algorithm layouting a sequence of image so that a given page width is filled allocating appropriate space for all
 * images.
 */
class AlbumLayout implements Iterable<Row> {
	
	final double _pageWidth;
	late final List<Row> _rows;

	/** 
	 * Creates a {@link AlbumLayout}.
	 *
	 * @param pageWidth The width of the page on which the result should be rendered.
	 * @param maxRowHeight The maximum height to scale a landscape image to.
	 * @param images the image to place on the page.
	 */
	AlbumLayout(double pageWidth, double maxRowHeight, List<AbstractImage> images) :
		_pageWidth = pageWidth
  {
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
	List<AbstractImage> getAllImages() {
		Collector collector = new Collector();
		for (Row row : _rows) {
			row.visit(collector, null);
		}
		return collector.getImages();
	}
	
	/**
	 * The width of the page, this layout is computed for.
	 */
	double getPageWidth() {
		return _pageWidth;
	}
	
	@override
	get Iterator<Row> iterator {
		return _rows.iterator;
	}
	
	/**
	 * The {@link Row}s with content.
	 */
	List<Row> getRows() {
		return _rows;
	}
}

class SimpleRowComputation implements RowComputation {
	
	final RowBuffer _out;
	
	final double _minWidth;
	
	Row currentRow = new Row();

	/** 
	 * Creates a {@link AlbumLayout.SimpleRowComputation}.
	 */
	SimpleRowComputation(RowBuffer out, double minWidth) {
		_out = out;
		_minWidth = minWidth;
	}
	
	@override
	RowComputation addImage(Content img) {
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
	
	@override
	void end() {
		if (!currentRow.isEmpty()) {
			currentRow.end(_minWidth);
			_out.addRow(currentRow);
		}
	}

	bool isAcceptableWidth(double currentWidth) {
		return  currentWidth >= _minWidth;
	}
}

/**
 * {@link RowComputation} placing landscape images in two vertically aligned rows.
 */
class DoubleRowComputation implements RowComputation {
	
	final RowBuffer _out;
	
	final double _minWidth;
	
	final double _halfMinWidth;
	
	Row currentRow = new Row();
	
	DoubleRowBuilder buffer = new DoubleRowBuilder();

	/** 
	 * Creates a {@link AlbumLayout.DoubleRowComputation}.
	 */
	DoubleRowComputation(RowBuffer out, double minWidth) {
		_out = out;
		_minWidth = minWidth;
		_halfMinWidth = minWidth / 2;
	}

	@override
	RowComputation addImage(Content img) {
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

	@override
	void end() {
		if (!buffer.isEmpty()) {
			currentRow.addContent(buffer.build());
		}
		currentRow.end(_halfMinWidth);
		_out.addRow(currentRow);
	}
	
	final bool isAcceptableWidth(double currentWidth) {
		return  currentWidth >= _halfMinWidth;
	}

}

class Collector implements ContentVisitor<Void, Void> {
	
	List<AbstractImage> _images = new ArrayList<>();

	@override
	Void visitRow(Row content, Void arg) {
		for (Content element : content) {
			element.visit(this, arg);
		}
		return null;
	}

	@override
	Void visitImg(Img content, Void arg) {
		_images.add(content.getImage());
		return null;
	}

	@override
	Void visitDoubleRow(DoubleRow content, Void arg) {
		content.getUpper().visit(this, arg);
		content.getLower().visit(this, arg);
		return null;
	}

	@override
	Void visitPadding(Padding content, Void arg) {
		return null;
	}
	
	/**
	 * All collected images.
	 */
	List<AbstractImage> getImages() {
		return _images;
	}
}


/**
 * A part of a {@link Row}.
 */
interface Content {
	
	/**
	 * The maximum width of an image (relative to its height) to interpret it as an portrait image (taking two lines in
	 * an {@link AlbumLayout}).
	 */
	static final double MAX_PORTRAIT_UNIT_WIDTH = 0.75;

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
	default bool isPortrait() {
		return getUnitWidth() <= MAX_PORTRAIT_UNIT_WIDTH;
	}
	
	/**
	 * Visits this {@link Content} with the given {@link ContentVisitor}
	 */
	R visit<R,A>(ContentVisitor<R,A> visitor, A arg);


}

/**
 * Visitor for {@link Content}.
 * 
 * @see Content#visit
 */
interface ContentVisitor<R, A> {
	
	R visitRow(Row content, A arg);
	R visitImg(Img content, A arg);
	R visitDoubleRow(DoubleRow content, A arg);
	R visitPadding(Padding content, A arg);

}

/**
 * {@link RowBuffer} building a {@link List} of {@link Row}s.
 */
class DefaultRowBuffer implements RowBuffer {

	List<Row> _rows = new ArrayList<>();

	@override
	void addRow(Row newRow) {
		_rows.add(newRow);
	}
	
	/**
	 * The created rows.
	 */
	List<Row> getRows() {
		return _rows;
	}

}

/**
 * Builder for a {@link DoubleRow}.
 */
class DoubleRowBuilder implements Iterable<Content> {
	
	static final double LOWER_LIMIT = 3.0 / 4.0;
	static final double UPPER_LIMIT = 4.0 / 3.0;
	
	Row _upper = new Row();
	Row _lower = new Row();
	
	List<RowState> _states = new ArrayList<>();
	
	DoubleRowBuilder() {
		addState(null);
	}

	/** 
	 * Creates a {@link DoubleRowBuilder}.
	 */
	DoubleRowBuilder(List<RowState> states) {
		this();
		for (RowState state : states) {
			addContent(state.getLastAdded());
		}
	}
	
	/**
	 * The upper {@link Row}.
	 */
	Row getUpper() {
		return _upper;
	}
	
	/**
	 * The lower {@link Row}.
	 */
	Row getLower() {
		return _lower;
	}

	/** 
	 * Tries to split of the largest acceptable prefix. 
	 * 
	 * <p>
	 * The state after this method returns only contains contents in the suffix after the split out prefix.
	 * </p>
	 */
	DoubleRowBuilder split() {
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

	void resetTo(DoubleRowBuilder other) {
		_upper = other._upper;
		_lower = other._lower;
		_states = other._states;
	}

	final bool isEmpty() {
		return _states.size() == 1;
	}

	void addState(Content content) {
		_states.add(new RowState(content));
	}

	final double getUnitWidth() {
		return top().getUnitWidth();
	}
	
	final bool acceptable() {
		return top().isAcceptable();
	}
	
	RowState top() {
		return _states.get(_states.size() - 1);
	}
	
	final void addContent(Content content) {
		Row smaller = smaller();
		smaller.addContent(content);
		addState(content);
	}
	
	Row smaller() {
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
	
	void flip() {
		Row upper = _upper;
		_upper = _lower;
		_lower = upper;
	}

	@override
	Iterator<Content> iterator() {
		Iterator<RowState> inner = _states.subList(1, _states.size()).iterator();
		
		return new Iterator<Content>() {
			@override
			bool hasNext() {
				return inner.hasNext();
			}

			@override
			Content next() {
				return inner.next().getLastAdded();
			}
		};
	}

	/** 
	 * Adds all {@link Content} to this builder.
	 */
	void addAll(Iterable<Content> contents) {
		for (Content content : contents) {
			addContent(content);
		}
	}
}

class RowState {
	final double _unitWidth;
	final bool _acceptable;
	final Content _lastAdded;
	double _h1;
	double _h2;

	RowState(Content lastAdded) {
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
	double getH1() {
		return _h1;
	}
	
	/**
	 * The relative height of the lower row.
	 */
	double getH2() {
		return _h2;
	}
	
	/**
	 * The content that was added just before the {@link RowState} computation was done.
	 * 
	 * @return The {@link Content} added before the computation was done, or <code>null</code>, if this is the
	 *         initial value.
	 */
	Content getLastAdded() {
		return _lastAdded;
	}

	/**
	 * The computed unit width just after {@link #getLastAdded()} was added.
	 */
	double getUnitWidth() {
		return _unitWidth;
	}

	/**
	 * The computed acceptable state just after {@link #getLastAdded()} was added.
	 */
	bool isAcceptable() {
		return _acceptable;
	}
}


/**
 * A vertical placements of two {@link Row}s scaled to the same width.
 */
class DoubleRow implements Content {

	final double _unitWidth;
	
	final Row _upper;
	final Row _lower;

	double _h1;

	double _h2;

	/** 
	 * Creates a {@link DoubleRow}.
	 */
	DoubleRow(Row upper, Row lower, double unitWidth, double h1, double h2) {
		_upper = upper;
		_lower = lower;
		_unitWidth = unitWidth;
		_h1 = h1;
		_h2 = h2;
	}
	
	/**
	 * The upper {@link Row}.
	 */
	Row getUpper() {
		return _upper;
	}
	
	/**
	 * The lower {@link Row}.
	 */
	Row getLower() {
		return _lower;
	}
	
	/**
	 * Relative height of {@link #getUpper()}.
	 */
	double getH1() {
		return _h1;
	}
	
	/**
	 * Relative height of {@link #getLower()}.
	 */
	double getH2() {
		return _h2;
	}

	@override
	double getUnitWidth() {
		return _unitWidth;
	}

	@override
	int getUnitHeight() {
		return 2;
	}

	@override
	R visit<R, A>(ContentVisitor<R, A> visitor, A arg) {
		return visitor.visitDoubleRow(this, arg);
	}
	
}

/**
 * An atomic image placed in a {@link Row}.
 */
class Img implements Content {
	
	final double _unitWidth;
	AbstractImage _image;
	
	/** 
	 * Creates a {@link Img}.
	 */
	Img(AbstractImage image) {
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
	AbstractImage getImage() {
		return _image;
	}
	
	/**
	 * The width of the image, if it was scaled to a height of <code>1.0</code>.
	 */
	@override
	double getUnitWidth() {
		return _unitWidth;
	}
	
	@override
	int getUnitHeight() {
		return 1;
	}
	
	@override
	R visit<R, A>(ContentVisitor<R, A> visitor, A arg) {
		return visitor.visitImg(this, arg);
	}
	
}

/**
 * Empty space inserted to a layout to make it's constraints acceptable. 
 */
class Padding implements Content {

	double _unitWidth;

	/** 
	 * Creates a {@link Padding}.
	 */
	Padding(double unitWidth) {
		_unitWidth = unitWidth;
	}

	@override
	double getUnitWidth() {
		return _unitWidth;
	}
	
	@override
	int getUnitHeight() {
		return 1;
	}

	@override
	R visit<R, A>(ContentVisitor<R, A> visitor, A arg) {
		return visitor.visitPadding(this, arg);
	}

}

/**
 * Buffer of {@link Row} used in a {@link RowComputation}.
 */
interface RowBuffer {

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
class Row implements Content, Iterable<Content> {
	
	final List<Content> _contents = new ArrayList<>();
	
	double _unitWidth;

	int _height = 1;
	
	final void addContent(Content content) {
		_contents.add(content);
		_unitWidth += content.getUnitWidth();
		_height = Math.max(_height, content.getUnitHeight());
	}
	
	@override
	double getUnitWidth() {
		return _unitWidth;
	}
	
	@override
	int getUnitHeight() {
		return _height;
	}

	/**
	 * Whether this {@link Row} has no contents.
	 */
	final bool isEmpty() {
		return _contents.isEmpty();
	}

	@override
	Iterator<Content> iterator() {
		return _contents.iterator();
	}
	
	@override
	R visit<R, A>(ContentVisitor<R, A> visitor, A arg) {
		return visitor.visitRow(this, arg);
	}

	/** 
	 * The number of {@link Content}s in this {@link Row}.
	 */
	int size() {
		return _contents.size();
	}

	void end(double minWidth) {
		double unitWidth = getUnitWidth();
		if (unitWidth < minWidth) {
			addContent(new Padding(minWidth - unitWidth));
		}
	}
}