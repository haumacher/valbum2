/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import static de.haumacher.util.html.HTML.*;

import java.io.IOException;
import java.util.function.Consumer;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumProperties;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ImageInfo.Kind;
import de.haumacher.imageServer.shared.ui.DataAttributes;
import de.haumacher.imageServer.shared.ui.ImageRow;
import de.haumacher.util.gwt.Native;
import de.haumacher.util.gwt.dom.DomBuilder;
import de.haumacher.util.xml.XmlFragment;
import elemental2.dom.Element;

/**
 * {@link XmlFragment} displaying a {@link AlbumInfo} model.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumDisplay extends ResourceDisplay {

	private AlbumInfo _album;
	
	/** 
	 * Creates a {@link AlbumDisplay}.
	 */
	public AlbumDisplay(AlbumInfo album) {
		_album = album;
	}

	@Override
	protected void render(UIContext context, DomBuilder out) throws IOException {
		int width = context.getPageWidth();
		String parentUrl = RenderUtil.parentUrl(_album.getDepth());

		out.begin(DIV);
		out.attr(ID_ATTR, "page");
		if (parentUrl != null) {
			out.attr(DataAttributes.DATA_ESCAPE, parentUrl);
		}
		
		out.begin(H1);
		AlbumProperties header = _album.getHeader();
		out.append(header.getTitle());
		out.end();
		out.begin(H2);
		out.append(header.getSubTitle());
		out.end();

		out.begin(DIV);
		out.attr(CLASS_ATTR, "image-rows");
		out.attr(STYLE_ATTR, "width: " + width + "px;");
		{
			ImageRow row = new ImageRow(width, 400);
			for (ImageInfo image : _album.getImages()) {
				if (row.isComplete()) {
					writeRow(out, row);
					row.clear();
				}
				row.add(image);
			}
			writeRow(out, row);
		}
		out.end();
		
		if (parentUrl != null) {
			writeAlbumToolbar(out, false, parentUrl);
		}
		out.end();
	}

	private void writeRow(DomBuilder out, ImageRow row) throws IOException {
		if (row.getSize() == 0) {
			return;
		}
		double rowHeight = row.getHeight();
		int spacing = row.getSpacing();

		out.begin(DIV);
		out.attr(CLASS_ATTR, "icons");
		out.attr("style", "display: table; margin-top: " + spacing + "px;");
		{
			out.begin(DIV);
			out.attr(STYLE_ATTR, "display: table-row;");
			for (int n = 0, cnt = row.getSize(); n < cnt; n++) {
				ImageInfo image = row.getImage(n);

				out.begin(DIV);
				out.attr(CLASS_ATTR, "icon");
				{
					out.begin(A);
					out.attr(CLASS_ATTR, "icon-link");
					if (n > 0) {
						out.openAttr(STYLE_ATTR);
						out.append("margin-left: ");
						out.append(spacing);
						out.append("px;");
						out.closeAttr();
					}
					out.openAttr(HREF_ATTR);
					{
						out.append(image.getName());
					}
					out.closeAttr();
					{
						out.begin(IMG);
						out.attr(CLASS_ATTR, "icon-display");
						{
							out.openAttr(SRC_ATTR);
							out.append(image.getName());
							out.append("?type=tn");
							out.closeAttr();
							
							out.attr(WIDTH_ATTR, row.getScaledWidth(n));
							out.attr(HEIGHT_ATTR, rowHeight);
						}
						out.end();
						
						if (image.getKind() == Kind.VIDEO) {
							out.begin(DIV);
							out.attr(CLASS_ATTR, "video-overlay");
							{
								out.begin(I);
								out.attr(CLASS_ATTR, "far fa-play-circle");
								out.end();
							}
							out.end();
						}
						
						writeToolbars(out, image);
					}
					out.end();
				}
				out.end();
			}
			out.end();
		}
		out.end();
	}

	private void writeToolbars(DomBuilder out, ImageInfo image) throws IOException {
		if (isEditMode()) {
			out.begin(SPAN);
			out.attr(CLASS_ATTR, "check-button" + (Math.random() > 0.5 ? " checked" : ""));
			{
				out.begin(I);
				out.attr(CLASS_ATTR, "display-unchecked far fa-square");
				out.end();
				
				out.begin(I);
				out.attr(CLASS_ATTR, "display-checked far fa-check-square");
				out.end();
			}
			out.end();
			
			out.begin(SPAN);
			out.attr(CLASS_ATTR, "toolbar-embedded toolbar-top");
			{
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-redo-alt");
					out.end();
				}
				out.end();
				
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-arrows-alt-v");
					out.end();
				}
				out.end();
				
				out.begin(SPAN);
				out.attr(CLASS_ATTR, "toolbar-button");
				{
					out.begin(I);
					out.attr(CLASS_ATTR, "fas fa-undo-alt");
					out.end();
				}
				out.end();
			}
			out.end();
			
			int rating = image.getRating();
			
			out.begin(SPAN);
			out.attr(CLASS_ATTR, "toolbar-embedded toolbar-bottom");
			makeChoice(
				data -> image.setRating((int) data),
				() -> image.setRating(0),
				createChoiceButton(out, "fas fa-star", rating >= 2, 2),
				createChoiceButton(out, "fas fa-plus", rating == 1, 1),
				createChoiceButton(out, "fas fa-minus", rating == -1, -1),
				createChoiceButton(out, "fas fa-trash-alt", rating <= -2, -2)
			);
			out.end();
		}
	}

	private void makeChoice(Consumer<Object> onSet, Runnable onReset, Element... buttons) {
		for (Element button : buttons) {
			button.addEventListener("click", event -> {
				if (button.classList.contains("active")) {
					button.classList.remove("active");
					onReset.run();
				} else {
					reset(buttons);
					button.classList.add("active");
					onSet.accept(Native.get(button, "vaUserData"));
				}
				
				event.stopPropagation();
				event.preventDefault();
			});
		}
	}

	private void reset(Element[] buttons) {
		for (Element button : buttons) {
			button.className = "toolbar-button";
		}
	}

	private Element createChoiceButton(DomBuilder out, String iconClass, boolean active, Object userValue) throws IOException {
		out.begin(SPAN);
		out.attr(CLASS_ATTR, active ? "toolbar-button active" : "toolbar-button");
		{
			out.begin(I);
			out.attr(CLASS_ATTR, iconClass);
			out.end();
		}
		out.end();
		
		Element result = out.getLast();
		Native.set(result, "vaUserData", userValue);
		return result;
	}

}
