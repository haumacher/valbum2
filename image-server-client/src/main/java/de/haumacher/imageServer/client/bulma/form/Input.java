/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.bulma.form;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;

import de.haumacher.imageServer.client.ui.AbstractDisplay;
import de.haumacher.imageServer.client.ui.UIContext;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Event;
import elemental2.dom.HTMLInputElement;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Input extends AbstractDisplay {

	private String _label;
	private String _placeholder;
	private String _type = TYPE_TEXT_VALUE;
	private String _typeIcon;
	private String _value;
	private State _state = State.NONE;
	private String _stateIcon;
	private String _message;
	
	private HTMLInputElement _inputElement;
	
	/**
	 * TODO
	 */
	public String getLabel() {
		return _label;
	}
	
	public Input setLabel(String label) {
		_label = label;
		redraw();
		return this;
	}
	
	/**
	 * TODO
	 */
	public String getPlaceholder() {
		return _placeholder;
	}
	
	public Input setPlaceholder(String placeholder) {
		_placeholder = placeholder;
		redraw();
		return this;
	}
	
	/**
	 * TODO
	 */
	public String getType() {
		return _type;
	}
	
	public Input setType(String type) {
		_type = type;
		redraw();
		return this;
	}
	
	/**
	 * TODO
	 */
	public String getTypeIcon() {
		return _typeIcon;
	}
	
	public Input setTypeIcon(String typeIcon) {
		_typeIcon = typeIcon;
		redraw();
		return this;
	}
	
	/**
	 * TODO
	 */
	public String getValue() {
		return _value;
	}

	public Input setValue(String value) {
		_value = value;
		redraw();
		return this;
	}
	
	/**
	 * TODO
	 */
	public State getState() {
		return _state;
	}
	
	public Input setState(State state) {
		_state = state;
		redraw();
		return this;
	}
	
	/**
	 * TODO
	 */
	public String getStateIcon() {
		return _stateIcon;
	}
	
	public Input setStateIcon(String stateIcon) {
		_stateIcon = stateIcon;
		redraw();
		return this;
	}
	
	/**
	 * TODO
	 */
	public String getMessage() {
		return _message;
	}
	
	public Input setMessage(String message) {
		_message = message;
		redraw();
		return this;
	}
	
	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
//		<div class="field">
//		  <label class="label">Email</label>
//		  <div class="control has-icons-left has-icons-right">
//		    <input class="input is-danger" type="email" placeholder="Email input" value="hello@">
//		    <span class="icon is-small is-left">
//		      <i class="fas fa-envelope"></i>
//		    </span>
//		    <span class="icon is-small is-right">
//		      <i class="fas fa-exclamation-triangle"></i>
//		    </span>
//		  </div>
//		  <p class="help is-danger">This email is invalid</p>
//		</div>

		out.beginDiv(FIELD);
		{
			out.begin(LABEL);
			out.classAttr("label");
			out.append(_label);
			out.end();

			out.beginDiv("control" + " " + (_typeIcon != null ? "has-icons-left" : "") + " " + (_stateIcon != null ? "has-icons-right" : null));
			{
				out.begin(INPUT);
				out.classAttr("input" + " " + _state);
				out.attr(TYPE_ATTR, _type);
				out.attr(PLACEHOLDER_ATTR, _placeholder);
				out.attr(VALUE_ATTR, _value);
				out.end();
				_inputElement = out.getLast();
				_inputElement.addEventListener("change", this::handleChange);
				
				if (_typeIcon != null) {
					out.begin(SPAN);
					out.classAttr("icon is-small is-left");
					{
						out.begin(I);
						out.classAttr(_typeIcon);
						out.end();
					}
					out.end();
				}

				if (_stateIcon != null) {
					out.begin(SPAN);
					out.classAttr("icon is-small is-right");
					{
						out.begin(I);
						out.classAttr(_stateIcon);
						out.end();
					}
					out.end();
				}
			}
			out.end();
			
			out.beginDiv("help" + " " + _state);
			{
				out.append(_message);
			}
			out.end();
		}
		out.end();
	}

	private void handleChange(Event event) {
		_value = _inputElement.value;
	}

}
