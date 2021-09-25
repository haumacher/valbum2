/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import java.io.IOException;

import de.haumacher.imageServer.client.bulma.Button;
import de.haumacher.imageServer.client.bulma.form.Input;
import de.haumacher.imageServer.client.bulma.modal.ModalCard;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.util.gwt.dom.DomBuilder;
import elemental2.dom.Event;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class AlbumPropertiesEditor extends ModalCard {
	
	private final AlbumDisplay _owner;
	
	private final AlbumInfo _album;

	private Input _titleInput;

	private Input _subtitleInput;

	/** 
	 * Creates a {@link AlbumPropertiesEditor}.
	 * @param albumDisplay 
	 */
	AlbumPropertiesEditor(AlbumDisplay owner, AlbumInfo album) {
		_owner = owner;
		_album = album;
	}

	@Override
	protected void renderTitle(UIContext context, DomBuilder out) throws IOException {
		out.append("Eigenschaften des Albums bearbeiten");
	}

	@Override
	protected void renderContent(UIContext context, DomBuilder out) {
		_titleInput = new Input().setLabel("Titel").setValue(_album.getTitle());
		_titleInput.show(context, out);
		
		_subtitleInput = new Input().setLabel("Subtitel").setValue(_album.getSubTitle());
		_subtitleInput.show(context, out);
	}

	@Override
	protected void renderButtons(UIContext context, DomBuilder out) throws IOException {
		new Button().setLabel("Übernehmen").onClick(this::handleOk).show(context, out);
	}

	private void handleOk(@SuppressWarnings("unused") Event event) {
		_album.setTitle(_titleInput.getValue());
		_album.setSubTitle(_subtitleInput.getValue());
		
		remove();
		_owner.redraw();
	}
}