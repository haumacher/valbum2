/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import java.io.IOException;

import de.haumacher.imageServer.client.bulma.Button;
import de.haumacher.imageServer.client.bulma.form.Textarea;
import de.haumacher.imageServer.client.bulma.modal.ModalCard;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Event;

/**
 * Allow editing properties of an {@link ImagePart}.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImagePropertiesEditor extends ModalCard {

	private ImagePart _image;
	private Textarea _commentDisplay;

	/** 
	 * Creates a {@link ImagePropertiesEditor}.
	 */
	public ImagePropertiesEditor(ImagePart image) {
		_image = image;
	}

	@Override
	protected void renderTitle(UIContext context, DomBuilder out) throws IOException {
		out.append(_image.getName());
	}
	
	@Override
	protected void renderContent(UIContext context, DomBuilder out) throws IOException {
		_commentDisplay = new Textarea().setLabel("Beschreibung").setValue(_image.getComment());
		_commentDisplay.show(context, out);
	}
	
	@Override
	protected void renderButtons(UIContext context, DomBuilder out)
			throws IOException {
		new Button().setLabel("Ok").onClick(this::handleSave).show(context, out);
	}

	private void handleSave(Event event) {
		_image.setComment(_commentDisplay.getValue());
		remove();
	}
	
}
