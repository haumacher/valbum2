/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.ui.layout;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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