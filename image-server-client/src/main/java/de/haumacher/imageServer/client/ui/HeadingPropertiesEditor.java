/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import java.io.IOException;

import de.haumacher.imageServer.client.bulma.Button;
import de.haumacher.imageServer.client.bulma.form.Input;
import de.haumacher.imageServer.client.bulma.form.State;
import de.haumacher.imageServer.client.bulma.modal.ModalCard;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Event;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class HeadingPropertiesEditor extends ModalCard {

	/** 
	 * Creates a {@link HeadingPropertiesEditor}.
	 */
	public HeadingPropertiesEditor() {
		super();
	}

	private Input _textInput = new Input().setLabel("Text");
	
	public HeadingPropertiesEditor load(Heading heading) {
		_textInput.setValue(heading.getText());
		return this;
	}
	
	public Heading save(Heading heading) {
		heading.setText(_textInput.getValue());
		return heading;
	}

	@Override
	protected void renderTitle(UIContext context, DomBuilder out) throws IOException {
		out.append("Neue überschrift");
	}

	@Override
	protected void renderContent(UIContext context, DomBuilder out) throws IOException {
		_textInput.show(context, out);
	}

	@Override
	protected void renderButtons(UIContext context, DomBuilder out) throws IOException {
		new Button().setLabel("Ok").onClick(this::handleSave).show(context, out);
	}

	private void handleSave(Event event) {
		String value = _textInput.getValue();
		if (value.isEmpty()) {
			_textInput.setState(State.DANGER);
			_textInput.setMessage("Die Eingabe darf nicht leer sein.");
			return;
		}
		
		onSave(event);
		remove();
	}

	protected void onSave(Event event) {
		// Hook for subclasses.
	}
}