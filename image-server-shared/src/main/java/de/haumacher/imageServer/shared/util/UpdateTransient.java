/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.util;

import java.util.List;
import java.util.Map;

import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;

/**
 * Indexing functionality to call after unmarshalling a {@link Resource}.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class UpdateTransient implements Resource.Visitor<Void, Void> {
	
	/**
	 * Singleton {@link UpdateTransient} instance.
	 */
	public static final UpdateTransient INSTANCE = new UpdateTransient();

	private UpdateTransient() {
		// Singleton constructor.
	}
	
	@Override
	public Void visit(ImageInfo self, Void arg) {
		return null;
	}

	@Override
	public Void visit(AlbumInfo self, Void arg) {
		updateTransient(self);
		return null;
	}

	@Override
	public Void visit(ListingInfo self, Void arg) {
		return null;
	}

	@Override
	public Void visit(ErrorInfo self, Void arg) {
		return null;
	}

	/** 
	 * Updates internal links of the given {@link Resource}.
	 */
	public static void updateTransient(Resource resource) {
		resource.visit(INSTANCE, null);
	}
	
	/** 
	 * Updates internal links of the given {@link AlbumInfo}.
	 */
	public static void updateTransient(AlbumInfo album) {
		List<AlbumPart> parts = album.getParts();
		if (parts.isEmpty()) {
			return;
		}
		
		AbstractImage firstImage = nextImage(parts, 0);
		AbstractImage lastImage = prevImage(parts, parts.size() - 1);
		
		String home= firstImage != null ? ToImage.toImage(firstImage).getName() : null;
		String end = lastImage != null ? ToImage.toImage(lastImage).getName() : null;
		
		Map<String, ImagePart> imageByName = album.getImageByName();
	
		for (int n = 0, size = parts.size(); n < size; n++) {
			int index = n;
			
			AlbumPart part = parts.get(index);
			if (part instanceof AbstractImage) {
				AbstractImage image = (AbstractImage) part;
				
				AbstractImage prevImage = prevImage(parts, index - 1);
				AbstractImage nextImage = nextImage(parts, index + 1);
				
				String previous = (prevImage != null) ?  ToImage.toImage(prevImage).getName() : null;
				String next = (nextImage != null) ? ToImage.toImage(nextImage).getName() : null;
				
				image.visit(new AbstractImage.Visitor<Void, Void>() {
					@Override
					public Void visit(ImageGroup self, Void arg) {
						for (ImagePart groupImage : self.getImages()) {
							visit(groupImage, arg);
						}
						return null;
					}
					
					@Override
					public Void visit(ImagePart self, Void arg) {
						ImagePart clash = imageByName.put(self.getName(), self);
						assert clash == null : "Duplicate name '" + self.getName() + "'.";
						
						self.setHome(home);
						self.setEnd(end);
						self.setPrevious(previous);	
						self.setNext(next);	
						return null;
					}
				}, null);
			}
		}
	}

	private static AbstractImage prevImage(List<AlbumPart> parts, int i) {
		while (i > 0) {
			AlbumPart part = parts.get(i--);
			if (part instanceof AbstractImage) {
				return (AbstractImage) part;
			}
		}
		return null;
	}

	private static AbstractImage nextImage(List<AlbumPart> parts, int i) {
		while (i < parts.size()) {
			AlbumPart part = parts.get(i++);
			if (part instanceof AbstractImage) {
				return (AbstractImage) part;
			}
		}
		return null;
	}

}
