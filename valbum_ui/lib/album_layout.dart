import 'dart:collection';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:valbum_ui/resource.dart';

/// Algorithm to compute a layout of a sequence of image so that a given page width is filled
/// allocating appropriate space for all images.
class AlbumLayout with IterableMixin<Row> {
	
	final double _pageWidth;
	final List<Row> _rows;

  AlbumLayout._init(this._pageWidth, this._rows);

	/// Creates a [AlbumLayout].
	///
	/// @param pageWidth The width of the page on which the result should be rendered.
	/// @param maxRowHeight The maximum height to scale a landscape image to.
	/// @param images the image to place on the page.
	factory AlbumLayout(double pageWidth, double maxRowHeight, Iterable<AbstractImage> images) {
		DefaultRowBuffer buffer = DefaultRowBuffer();
		double minWidth = pageWidth / maxRowHeight;
		RowComputation rowComputation = SimpleRowComputation(buffer, minWidth);
		for (AbstractImage image in images) {
			Img content = Img(image);
			
			if (content.getUnitWidth() >= minWidth) {
				// Take care of panorama images. Do not combine them with other images, because they would get scaled
				// down to a thumbnail.
				Row fullWidthRow = Row();
				fullWidthRow.addContent(content);
				buffer.addRow(fullWidthRow);
			} else {
				rowComputation = rowComputation.addImage(content);
			}
		}
		rowComputation.end();
		
		return AlbumLayout._init(pageWidth, buffer.getRows());
	}

	/// Extracts all [ImagePart]s in this layout.
	List<AbstractImage> getAllImages() {
		Collector collector = Collector();
		for (Row row in _rows) {
			row.visit(collector, null);
		}
		return collector.getImages();
	}
	
	/// The width of the page, this layout is computed for.
	double getPageWidth() {
		return _pageWidth;
	}
	
	@override
	Iterator<Row> get iterator {
		return _rows.iterator;
	}
	
	/// The [Row]s with content.
	List<Row> getRows() {
		return _rows;
	}
}

class SimpleRowComputation implements RowComputation {
	
	final RowBuffer _out;
	
	final double _minWidth;
	
	Row currentRow = Row();

	/// Creates a [AlbumLayout.SimpleRowComputation].
	SimpleRowComputation(RowBuffer out, double minWidth) :
		_out = out,
		_minWidth = minWidth;

	
	@override
	RowComputation addImage(Content img) {
		if (img.isPortrait()) {
			RowComputation inner = DoubleRowComputation(_out, _minWidth);
			for (Content buffered in currentRow) {
				inner = inner.addImage(buffered);
			}
			return inner.addImage(img);
		} else {
			currentRow.addContent(img);
			if (isAcceptableWidth(currentRow.getUnitWidth())) {
				_out.addRow(currentRow);
				currentRow = Row();
			}
			return this;
		}
	}
	
	@override
	void end() {
		if (!currentRow.isEmpty) {
			currentRow.end(_minWidth);
			_out.addRow(currentRow);
		}
	}

	bool isAcceptableWidth(double currentWidth) {
		return  currentWidth >= _minWidth;
	}
}

/// [RowComputation] placing landscape images in two vertically aligned rows.
class DoubleRowComputation implements RowComputation {
	
	final RowBuffer _out;
	
	final double _minWidth;
	
	final double _halfMinWidth;
	
	Row currentRow = Row();
	
	DoubleRowBuilder buffer = DoubleRowBuilder.empty();

	/// Creates a [DoubleRowComputation].
	DoubleRowComputation(RowBuffer out, double minWidth) :
		_out = out,
		_minWidth = minWidth,
		_halfMinWidth = minWidth / 2;

	@override
	RowComputation addImage(Content img) {
		if (img.isPortrait()) {
			DoubleRowBuilder prefix = buffer.split();
			if (prefix.acceptable()) {
				currentRow.addContent(prefix.build());
			} else {
				if (!prefix.isEmpty) {
					// Revert to original state.
					prefix.addAll(buffer);
					buffer = prefix;
				}
			}
			currentRow.addContent(img);
			if (isAcceptableWidth(currentRow.getUnitWidth())) {
				_out.addRow(currentRow);
				
				// Re-do layout of buffered images.
				RowComputation result = SimpleRowComputation(_out, _minWidth);
				for (Content content in buffer) {
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
					
					return SimpleRowComputation(_out, _minWidth);
				}
			}
		}
		return this;
	}

	@override
	void end() {
		if (!buffer.isEmpty) {
			currentRow.addContent(buffer.build());
		}
		currentRow.end(_halfMinWidth);
		_out.addRow(currentRow);
	}

  @nonVirtual
  bool isAcceptableWidth(double currentWidth) {
		return  currentWidth >= _halfMinWidth;
	}

}

class Collector implements ContentVisitor<void, void> {
	
	final List<AbstractImage> _images = [];

	@override
	void visitRow(Row content, void arg) {
		for (Content element in content) {
			element.visit(this, arg);
		}
	}

	@override
	void visitImg(Img content, void arg) {
		_images.add(content.getImage());
	}

	@override
	void visitDoubleRow(DoubleRow content, void arg) {
		content.getUpper().visit(this, arg);
		content.getLower().visit(this, arg);
	}

	@override
	void visitPadding(Padding content, void arg) {
	}
	
	/// All collected images.
	List<AbstractImage> getImages() {
		return _images;
	}
}

/// A part of a [Row].
abstract class Content {
	
	/// The maximum width of an image (relative to its height) to interpret it as an portrait image (taking two lines in
	/// an [AlbumLayout]).
	static const double maxPortraitUnitWidth = 0.75;

	/// The width of the content, if it's height is scaled to <code>1.0</code>.
	double getUnitWidth();
	
	/// The number of grid rows, this [Content] spans.
	int getUnitHeight();

	/// Whether this is a portrait image, with a height considerably larger than its width.
	bool isPortrait() {
		return getUnitWidth() <= maxPortraitUnitWidth;
	}
	
	/// Visits this [Content] with the given [ContentVisitor]
	R visit<R,A>(ContentVisitor<R,A> visitor, A arg);

}

/// Visitor for [Content].
///
/// See [Content.visit]
abstract class ContentVisitor<R, A> {
	
	R visitRow(Row content, A arg);
	R visitImg(Img content, A arg);
	R visitDoubleRow(DoubleRow content, A arg);
	R visitPadding(Padding content, A arg);

}

/// [RowBuffer] building a [List] of [Row]s.
class DefaultRowBuffer implements RowBuffer {

	final List<Row> _rows = [];

	@override
	void addRow(Row newRow) {
		_rows.add(newRow);
	}
	
	/// The created rows.
	List<Row> getRows() {
		return _rows;
	}

}

/// Builder for a [DoubleRow].
class DoubleRowBuilder with IterableMixin<Content> {
	
	static const double lowerLimit = 3.0 / 4.0;
	static const double upperLimit = 4.0 / 3.0;
	
	Row _upper = Row();
	Row _lower = Row();
	
	List<RowState> _states = [];
	
	DoubleRowBuilder.empty() {
		addState(null);
	}

	/// Creates a [DoubleRowBuilder].
	DoubleRowBuilder(List<RowState> states) {
		addState(null);
		for (RowState state in states) {
			addContent(state.getLastAdded()!);
		}
	}
	
	/// The upper [Row].
	Row getUpper() {
		return _upper;
	}
	
	/// The lower [Row].
	Row getLower() {
		return _lower;
	}

	/// Tries to split of the largest acceptable prefix.
	///
	/// <p>
	/// The state after this method returns only contains contents in the suffix after the split out prefix.
	/// </p>
	DoubleRowBuilder split() {
		for (int index = _states.length - 1; index > 0 ; index--) {
			if (_states[index].isAcceptable()) {
				DoubleRowBuilder prefix = DoubleRowBuilder(_states.sublist(1, index + 1));
				DoubleRowBuilder suffix = DoubleRowBuilder(_states.sublist(index + 1, _states.length));
				resetTo(suffix);
				return prefix;
			}
		}
		
		return DoubleRowBuilder.empty();
	}

	void resetTo(DoubleRowBuilder other) {
		_upper = other._upper;
		_lower = other._lower;
		_states = other._states;
	}

  @override
  bool get isEmpty {
		return _states.length == 1;
	}

	void addState(Content? content) {
		_states.add(RowState(this, content));
	}

  @nonVirtual
  double getUnitWidth() {
		return topState().getUnitWidth();
	}

  @nonVirtual
  bool acceptable() {
		return topState().isAcceptable();
	}
	
	RowState topState() {
		return _states[_states.length - 1];
	}

  @nonVirtual
  void addContent(Content content) {
		smaller.addContent(content);
		addState(content);
	}
	
	Row get smaller {
		return _upper.getUnitWidth() <=_lower.getUnitWidth() ? _upper : _lower;
	}

  @nonVirtual
  double upperWidth() {
		return _upper.getUnitWidth();
	}

  @nonVirtual
  double lowerWidth() {
		return _lower.getUnitWidth();
	}

  @nonVirtual
  DoubleRow build() {
		if (!acceptable()) {
			double w1 = upperWidth();
			double w2 = lowerWidth();
			
			addContent(Padding((w1 - w2).abs()));
			
			if (w2 > w1) {
				flip();
			}
			
			assert(acceptable());
		}

		RowState top = topState();
		return DoubleRow(_upper, _lower, top.getUnitWidth(), top.getH1(), top.getH2());
	}
	
	void flip() {
		Row upper = _upper;
		_upper = _lower;
		_lower = upper;
	}

	@override
	Iterator<Content> get iterator {
		Iterator<RowState> inner = _states.sublist(1, _states.length).iterator;
		return RowIterator(inner);
	}

	/// Adds all [Content] to this builder.
	void addAll(Iterable<Content> contents) {
		for (Content content in contents) {
			addContent(content);
		}
	}
}

class RowIterator implements Iterator<Content> {
  Iterator<RowState> inner;

  RowIterator(this.inner);

  @override
  bool moveNext() {
    return inner.moveNext();
  }

  @override
  get current => inner.current.getLastAdded()!;
}

class RowState {
  final DoubleRowBuilder builder;
	final double _unitWidth;
  final bool _acceptable;
  final Content? _lastAdded;
	double _h1;
  double _h2;

  RowState._init(this.builder, this._unitWidth, this._lastAdded, this._h1, this._h2, this._acceptable);

	factory RowState(DoubleRowBuilder builder, Content? lastAdded) {
		double w1 = builder.upperWidth();
		double w2 = builder.lowerWidth();

    double unitWidth, h1 = 0, h2 = 0;
    bool acceptable;
		if (w1 == 0.0) {
			unitWidth = w2;
			acceptable = false;
		} else if (w2 == 0.0) {
			unitWidth = w1;
			acceptable = false;
		} else {
			double w1Inv = 1/w1;
			double w2Inv = 1/w2;
			double invSum = w1Inv + w2Inv;

			unitWidth = 1 / invSum;
			h1 = w1Inv / invSum;
			h2 = w2Inv / invSum;
			
			double hQuot = h1 / h2;
			acceptable = DoubleRowBuilder.lowerLimit <= hQuot && hQuot <= DoubleRowBuilder.upperLimit;
		}
    return RowState._init(builder, unitWidth, lastAdded, h1, h2, acceptable);
	}
	
	/// The relative height of the upper row.
	double getH1() {
		return _h1;
	}
	
	/// The relative height of the lower row.
	double getH2() {
		return _h2;
	}
	
	/// The content that was added just before the [RowState] computation was done.
	///
	/// @return The [Content] added before the computation was done, or <code>null</code>, if this is the
	///         initial value.
	Content? getLastAdded() {
		return _lastAdded;
	}

	/// The computed unit width just after [#getLastAdded()] was added.
	double getUnitWidth() {
		return _unitWidth;
	}

	/// The computed acceptable state just after [#getLastAdded()] was added.
	bool isAcceptable() {
		return _acceptable;
	}
}


/// A vertical placements of two [Row]s scaled to the same width.
class DoubleRow extends Content {

	final double _unitWidth;
	
	final Row _upper;
	final Row _lower;

	final double _h1;

	final double _h2;

	/// Creates a [DoubleRow].
	DoubleRow(Row upper, Row lower, double unitWidth, double h1, double h2) :
		_upper = upper,
		_lower = lower,
		_unitWidth = unitWidth,
    _h1 = h1,
		_h2 = h2;

	/// The upper [Row].
	Row getUpper() {
		return _upper;
	}
	
	/// The lower [Row].
	Row getLower() {
		return _lower;
	}
	
	/// Relative height of [#getUpper()].
	double getH1() {
		return _h1;
	}
	
	/// Relative height of [#getLower()].
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

/// An atomic image placed in a [Row].
class Img extends Content {
  final AbstractImage _image;
	final double _unitWidth;

  Img._init(this._image, this._unitWidth);

	/// Creates a [Img].
	factory Img(AbstractImage image) {
		ImagePart representative = ToImage.toImage(image);
		int width = representative.width;
		int height = representative.height;
		Orientation orientation = representative.orientation;
		
		int displayWidth = Orientations.widthInt(orientation, width, height);
		int displayHeight = Orientations.heightInt(orientation, width, height);
		
		return Img._init(image, (displayWidth as double) / displayHeight);
	}
	
	/// The [AbstractImage] represented by this [Content].
	AbstractImage getImage() {
		return _image;
	}
	
	/// The width of the image, if it was scaled to a height of <code>1.0</code>.
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

/// Empty space inserted to a layout to make it's constraints acceptable.
class Padding extends Content {

	final double _unitWidth;

	/// Creates a [Padding].
	Padding(double unitWidth) :
		_unitWidth = unitWidth;

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

/// Buffer of [Row] used in a [RowComputation].
abstract class RowBuffer {

	/// Adds the next completed row.
	void addRow(Row newRow);

}

/// Algorithm for placing images into rows.
///
/// See [RowBuffer]
abstract class RowComputation {

	/// Adds the given image.
	///
	/// @param img The image to place next.
	/// @return The [RowComputation] algorithm to continue with.
	RowComputation addImage(Content img);

	/// Flushes buffers and completes the computation.
	void end();
	
}

/// A horizontal layout of [Content].
class Row extends Content with IterableMixin<Content> {
	
	final List<Content> _contents = [];
	
	double _unitWidth = 0;

	int _height = 1;

  @nonVirtual
	void addContent(Content content) {
		_contents.add(content);
		_unitWidth += content.getUnitWidth();
		_height = max(_height, content.getUnitHeight());
	}
	
	@override
	double getUnitWidth() {
		return _unitWidth;
	}
	
	@override
	int getUnitHeight() {
		return _height;
	}

	/// Whether this [Row] has no contents.
  @override
  bool get isEmpty {
		return _contents.isEmpty;
	}

	@override
	Iterator<Content> get iterator {
		return _contents.iterator;
	}
	
	@override
	R visit<R, A>(ContentVisitor<R, A> visitor, A arg) {
		return visitor.visitRow(this, arg);
	}

	/// The number of [Content]s in this [Row].
	int size() {
		return _contents.length;
	}

	void end(double minWidth) {
		double unitWidth = getUnitWidth();
		if (unitWidth < minWidth) {
			addContent(Padding(minWidth - unitWidth));
		}
	}
}

class ToImage implements AbstractImageVisitor<ImagePart, void> {

  /// Singleton [ToImage] instance.
  static final ToImage instance = ToImage();

  ToImage() {
    // Singleton constructor.
  }

  @override
  ImagePart visitImageGroup(ImageGroup self, void arg) {
    return self.images[self.representative];
  }

  @override
  ImagePart visitImagePart(ImagePart self, void arg) {
    return self;
  }

  /// Invokes [ToImage] on the given [AbstractImage].
  static ImagePart toImage(AbstractImage image) {
    return image.visitAbstractImage(instance, null);
  }

}

class Orientations {

  /// Parses the given JPEG orientation code.
  static Orientation fromCode(int code) {
    if (code == 0) {
      // For safety reasons "unassigned".
      return Orientation.identity;
    }
    if (code < 1 || code > 8) {
      throw "Invalid JPEG orientation code: $code";
    }
    return Orientation.values[code - 1];
  }

  /// The JPEG orientation code.
  static int toCode(Orientation self) {
    return self.index + 1;
  }

  /// The display width of an image with the given [Orientation] and raw pixel width and height.
  static int widthInt(Orientation self, int rawWidth, int rawHeight) {
    if (toCode(self) >= 5) {
      return rawHeight;
    } else {
      return rawWidth;
    }
  }

  /// The display width of an image with the given [Orientation] and raw pixel width and height.
  static double width(Orientation self, double rawWidth, double rawHeight) {
    if (toCode(self) >= 5) {
      return rawHeight;
    } else {
      return rawWidth;
    }
  }

  /// The display height of an image with the given [Orientation] and raw pixel width and height.
  static int heightInt(Orientation self, int rawWidth, int rawHeight) {
    if (toCode(self) >= 5) {
      return rawWidth;
    } else {
      return rawHeight;
    }
  }

  /// The display height of an image with the given [Orientation] and raw pixel width and height.
  static double height(Orientation self, double rawWidth, double rawHeight) {
    if (toCode(self) >= 5) {
      return rawWidth;
    } else {
      return rawHeight;
    }
  }

  /// Rotates this orientation to the left.
  static Orientation rotL(Orientation self) {
    switch (self) {
      case Orientation.identity:		return Orientation.rotL;
      case Orientation.flipH:		return Orientation.rotLFlipV;
      case Orientation.rot180:		return Orientation.rotR;
      case Orientation.flipV:		return Orientation.rotLFlipH;
      case Orientation.rotLFlipV:	return Orientation.flipV;
      case Orientation.rotL: 		return Orientation.rot180;
      case Orientation.rotLFlipH:	return Orientation.flipH;
      case Orientation.rotR: 		return Orientation.identity;
    }
  }
}
