/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.xml;

import java.io.IOException;

/**
 * {@link XmlFragment} rendering a given value using a {@link Renderer}.
 * 
 * @param <T> The type of the value.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ValueFragment<T> implements XmlFragment {
	
	/**
	 * Creates a {@link ValueFragment} from the given value and {@link Renderer}.
	 */
	public static <T> ValueFragment<T> create(Renderer<? super T> renderer, T resource) {
		return new ValueFragment<T>(renderer, resource);
	}

	private final T _value;
	private final Renderer<? super T> _renderer;

	/** 
	 * Creates a {@link ValueFragment}.
	 */
	private ValueFragment(Renderer<? super T> renderer, T value) {
		_value = value;
		_renderer = renderer;
	}

	@Override
	public void write(RenderContext context, XmlAppendable out) throws IOException {
		_renderer.write(out, _value);
	}

}
