/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import java.util.HashMap;
import java.util.Map;

import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.EventListener;
import elemental2.dom.Touch;
import elemental2.dom.TouchEvent;

/**
 * Handler synthesizing swipe and pan/zoom gestures from touch events.
 */
public class TouchHandler {
	
	enum Direction {
		UP, DOWN, LEFT, RIGHT;
	}
	
	enum Status {
		STARTED, PENDING, CANCEL, COMPLETED;
	}

	private EventListener _touchStart = this::touchStart;
	private EventListener _touchMove = this::touchMove;
	private EventListener _touchEnd = this::touchEnd;
	
	private Map<Object, TouchPointer> _pointers = new HashMap<>();
	private TouchOperation _operation;
	private Element _target;
	
	public void attach(Element target) {
		if (_target != null) {
			detach();
		}
		_target = target;
		target.addEventListener("touchstart", _touchStart);
		target.addEventListener("touchmove", _touchMove);
		target.addEventListener("touchend", _touchEnd);
	}
	
	public void detach() {
		if (_target == null) {
			return;
		}
		_target.removeEventListener("touchstart", _touchStart);
		_target.removeEventListener("touchmove", _touchMove);
		_target.removeEventListener("touchend", _touchEnd);
		_target = null;
	}

	static class TouchPointer {

		private double _startX;
		private double _startY;
		private double _currentX;
		private double _currentY;

		/** 
		 * Creates a {@link TouchPointer}.
		 *
		 * @param startX Start X coordinate in screen coordinates.
		 * @param startY Start Y coordinate in screen coordinates.
		 */
		public TouchPointer(double startX, double startY) {
			_startX = startX;
			_startY = startY;
			
			reset();
		}
		
		/**
		 * TODO
		 */
		public double getStartX() {
			return _startX;
		}
		
		/**
		 * TODO
		 */
		public double getStartY() {
			return _startY;
		}
		
		/**
		 * TODO
		 */
		public double getCurrentX() {
			return _currentX;
		}
		
		/**
		 * TODO
		 */
		public double getCurrentY() {
			return _currentY;
		}

		/** 
		 * TODO
		 *
		 */
		public void reset() {
			_currentX = _startX;
			_currentY = _startY;
		}

		/** 
		 * TODO
		 *
		 * @param screenX
		 * @param screenY
		 */
		public void moveTo(double currentX, double currentY) {
			_currentX = currentX;
			_currentY = currentY;
		}
		
		public boolean hasMoveStarted() {
			return manhattanDistance() > 20;
		}

		public double manhattanDistance() {
			double dx = dx();
			double dy = dy();
			return Math.max(Math.abs(dx), Math.abs(dy));
		}

		/** 
		 * TODO
		 *
		 * @return
		 */
		public double dy() {
			return _currentY - _startY;
		}

		/** 
		 * TODO
		 *
		 * @return
		 */
		public double dx() {
			return _currentX - _startX;
		}
		
		public Direction moveDirection() {
			double dx = dx();
			double dy = dy();
			double absX = Math.abs(dx);
			double absY = Math.abs(dy);
			if (absX > absY) {
				if (dx > 0) {
					return Direction.RIGHT;
				} else {
					return Direction.LEFT;
				}
			} else {
				if (dy > 0) {
					return Direction.DOWN;	
				} else {
					return Direction.UP;
				}
			}
		}
	}
	
	interface TouchOperation {

		/** 
		 * TODO
		 *
		 */
		void update();

		/** 
		 * TODO
		 *
		 */
		void complete();
		
	}
	
	public interface Swipe {
		
		Direction getDirection();
		
		double getDistance();
		
	}
	
	class SwipeImpl implements TouchOperation, Swipe {

		private TouchPointer _pointer;

		/** 
		 * Creates a {@link SwipeImpl}.
		 */
		public SwipeImpl(TouchPointer pointer) {
			_pointer = pointer;
			onSwipe(Status.STARTED, this);
		}
		
		@Override
		public void update() {
			if (_pointer.manhattanDistance() > 100) {
				onSwipe(Status.COMPLETED, this);
				_operation = new None();
			} else {
				onSwipe(Status.PENDING, this);
			}
		}
		
		@Override
		public void complete() {
			onSwipe(Status.CANCEL, this);
		}
		
		@Override
		public Direction getDirection() {
			return _pointer.moveDirection();
		}
		
		@Override
		public double getDistance() {
			return _pointer.manhattanDistance();
		}
	}
	
	public interface PanZoom {

		/** 
		 * TODO
		 *
		 * @return
		 */
		double getStartMx();

		/** 
		 * TODO
		 *
		 * @return
		 */
		double getAlpha();

		/** 
		 * TODO
		 *
		 * @return
		 */
		double getZoom();

		/** 
		 * TODO
		 *
		 * @return
		 */
		double getStartMy();

		/** 
		 * TODO
		 *
		 * @return
		 */
		double getTy();

		/** 
		 * TODO
		 *
		 * @return
		 */
		double getTx();
		
	}
	
	class PanZoomImpl implements TouchOperation, PanZoom {
		
		private TouchPointer _p1;
		private TouchPointer _p2;

		private double _startDistance;
		private double _startMx;
		private double _startMy;
		private double _tx;
		private double _ty;
		private double _zoom;
		private double _startDx;
		private double _startDy;
		private double _startAlpha;
		private double _alpha;
		
		/** 
		 * Creates a {@link SwipeImpl}.
		 */
		public PanZoomImpl(TouchPointer p1, TouchPointer p2) {
			_p1 = p1;
			_p2 = p2;
			
			_startDx = _p2.getStartX() - _p1.getStartX();
			_startDy = _p2.getStartY() - _p1.getStartY();
			
			_startMx = _p1.getStartX() + _startDx / 2;
			_startMy = _p1.getStartY() + _startDy / 2;
			
			_startDistance = length(_startDx, _startDy);
			_startAlpha = Math.atan2(_startDy, _startDx);
			
			_tx = 0;
			_ty = 0;
			_zoom = 1.0;
			_alpha = 0;
			
			onPanZoom(Status.STARTED, this);
		}

		private double length(double dx, double dy) {
			return Math.sqrt(dx*dx + dy*dy);
		}
		
		@Override
		public void update() {
			double dx = _p2.getCurrentX() - _p1.getCurrentX();
			double dy = _p2.getCurrentY() - _p1.getCurrentY();

			double mx = _p1.getCurrentX() + dx / 2;
			double my = _p1.getCurrentY() + dy / 2;
			
			double distance = length(dx, dy);
			
			_tx = mx - _startMx;
			_ty = my - _startMy;
			
			_zoom = distance / _startDistance;
			
			_alpha = Math.atan2(dy, dx) - _startAlpha;
			
			onPanZoom(Status.PENDING, this);
		}

		@Override
		public void complete() {
			onPanZoom(Status.COMPLETED, this);
		}
		
		@Override
		public double getStartMx() {
			return _startMx;
		}
		
		@Override
		public double getTx() {
			return _tx;
		}
		
		@Override
		public double getTy() {
			return _ty;
		}
		
		@Override
		public double getStartMy() {
			return _startMy;
		}
		
		@Override
		public double getZoom() {
			return _zoom;
		}
		
		@Override
		public double getAlpha() {
			return _alpha;
		}
		
	}
	
	static class None implements TouchOperation {
		@Override
		public void update() {
			// Ignore.
		}

		@Override
		public void complete() {
			// Ignore.
		}
	}
	
	public void onSwipe(Status status, SwipeImpl data) {
		// Hook for sub-classes.
	}
	
	public void onPanZoom(Status status, PanZoom data) {
		// Hook for sub-classes.
	}
	
	private void touchStart(Event event) {
		TouchEvent touchEvent = (TouchEvent) event;
		
		for (TouchPointer pointer : _pointers.values()) {
			pointer.reset();
		}
		
		for (int n = 0, cnt = touchEvent.changedTouches.length; n < cnt; n++) {
			Touch touch = touchEvent.changedTouches.getAt(n);
			
			_pointers.put(touch.identifier, new TouchPointer(touch.screenX, touch.screenY));
		}
		
		event.preventDefault();
		event.stopPropagation();
	}
	
	private void touchMove(Event event) {
		TouchEvent touchEvent = (TouchEvent) event;
		
		for (int n = 0, cnt = touchEvent.changedTouches.length; n < cnt; n++) {
			Touch touch = touchEvent.changedTouches.getAt(n);
			
			TouchPointer pointer = _pointers.get(touch.identifier);
			if (pointer == null) {
				continue;
			}
			
			pointer.moveTo(touch.screenX, touch.screenY);
		}
		
		if (_operation == null) {
			if (hasStarted()) {
				switch (_pointers.size()) {
				case 1: {
					_operation = new SwipeImpl(_pointers.values().iterator().next());
					break;
				}
				case 2: {
					break;
				}
				}
			}
		} else {
			_operation.update();
		}
		
		event.preventDefault();
		event.stopPropagation();
	}
	
	private boolean hasStarted() {
		for (TouchPointer pointer : _pointers.values()) {
			if (pointer.hasMoveStarted()) {
				return true;
			}
		}
		return false;
	}

	private void touchEnd(Event event) {
		TouchEvent touchEvent = (TouchEvent) event;
		
		if (_operation != null) {
			_operation.complete();
			_operation = new None();
		}
		
		for (int n = 0, cnt = touchEvent.changedTouches.length; n < cnt; n++) {
			Touch touch = touchEvent.changedTouches.getAt(n);
			
			TouchPointer pointer = _pointers.remove(touch.identifier);
			if (pointer == null) {
				continue;
			}
		}
		
		if (_pointers.size() == 0) {
			// Reset and allow new operation.
			_operation = null;
		}

		event.preventDefault();
		event.stopPropagation();
	}
}
