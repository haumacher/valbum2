/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.bulma.form;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.ui.AbstractDisplay;
import de.haumacher.imageServer.client.ui.UIContext;
import de.haumacher.util.gwt.dom.DomBuilder;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public abstract class AbstractFieldDisplay<S extends AbstractFieldDisplay<?>> extends AbstractDisplay {

	private String _label;
	private String _message;
	private State _state = State.NONE;

	/**
	 * TODO
	 */
	public String getLabel() {
		return _label;
	}
	
	public S setLabel(String label) {
		_label = label;
		redraw();
		return self();
	}
	
	/**
	 * TODO
	 */
	public String getMessage() {
		return _message;
	}
	
	public S setMessage(String message) {
		_message = message;
		redraw();
		return self();
	}

	/**
	 * TODO
	 */
	public State getState() {
		return _state;
	}
	
	public S setState(State state) {
		_state = state;
		redraw();
		return self();
	}
	
	protected abstract S self();

	@Override
	protected final void render(UIContext context, DomBuilder out) throws IOException {
		out.beginDiv(FIELD);
		{
			out.begin(LABEL);
			out.classAttr("label");
			out.append(_label);
			out.end();
			
			renderFieldValue(context, out);

			out.beginDiv("help" + " " + _state);
			{
				out.append(_message);
			}
			out.end();
		}
		out.end();
	}

	/** 
	 * TODO
	 *
	 * @param context
	 * @param out
	 * @throws IOException 
	 */
	protected abstract void renderFieldValue(UIContext context, DomBuilder out) throws IOException;

}
