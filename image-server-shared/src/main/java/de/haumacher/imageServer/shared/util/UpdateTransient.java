/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.util;

import java.util.List;
import java.util.Map;

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
		String home= ToImage.toImage(parts.get(0)).getName();
		String end = ToImage.toImage(parts.get(parts.size() - 1)).getName();
		
		Map<String, ImagePart> imageByName = album.getImageByName();
	
		for (int n = 0, size = parts.size(); n < size; n++) {
			int index = n;
			String previous = (index > 0) ?  ToImage.toImage(parts.get(index - 1)).getName() : null;
			String next = (index + 1 < size) ? ToImage.toImage(parts.get(index + 1)).getName() : null;
			
			parts.get(index).visit(new AlbumPart.Visitor<Void, Void>() {
				@Override
				public Void visit(ImageGroup self, Void arg) {
					for (ImagePart image : self.getImages()) {
						visit(image, arg);
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
