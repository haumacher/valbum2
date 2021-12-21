/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.util;

import java.util.List;

import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;

/**
 * Indexing functionality to call after unmarshalling a {@link Resource}.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class UpdateTransient implements Resource.Visitor<Void, AlbumInfo, RuntimeException> {
	
	/**
	 * Singleton {@link UpdateTransient} instance.
	 */
	public static final UpdateTransient INSTANCE = new UpdateTransient();

	private UpdateTransient() {
		// Singleton constructor.
	}
	
	/** 
	 * Updates internal links of the given {@link Resource}.
	 */
	public static void updateTransient(Resource resource) {
		resource.visit(INSTANCE, null);
	}
	
	@Override
	public Void visit(AlbumInfo self, AlbumInfo arg) throws RuntimeException {
		updateContents(self.getParts(), self);
		return null;
	}
	
	@Override
	public Void visit(Heading self, AlbumInfo arg) throws RuntimeException {
		initOwner(self, arg);
		return null;
	}
	
	@Override
	public Void visit(ImagePart self, AlbumInfo arg) throws RuntimeException {
		initOwner(self, arg);
		ImagePart clash = arg.getImageByName().put(self.getName(), self);
		assert clash == null : "Duplicate name '" + self.getName() + "'.";
		return null;
	}
	
	@Override
	public Void visit(ImageGroup self, AlbumInfo arg) throws RuntimeException {
		initOwner(self, arg);
		updateContents(self.getImages(), arg);
		for (ImagePart part : self.getImages()) {
			part.setGroup(self);
		}
		return null;
	}

	@Override
	public Void visit(ListingInfo self, AlbumInfo arg) throws RuntimeException {
		return null;
	}
	
	@Override
	public Void visit(ErrorInfo self, AlbumInfo arg) throws RuntimeException {
		return null;
	}
	
	private void initOwner(AlbumPart<?> self, AlbumInfo arg) {
		self.setOwner(arg);
	}
	
	/** 
	 * Updates internal links of the given {@link AlbumInfo}.
	 */
	private void updateContents(List<? extends AlbumPart<?>> parts, AlbumInfo owner) {
		AbstractImage<?> firstImage = nextImage(parts, 0);
		AbstractImage<?> lastImage = prevImage(parts, parts.size() - 1);
		
		for (int n = 0, size = parts.size(); n < size; n++) {
			int index = n;
			
			AlbumPart<?> part = parts.get(index);
			if (part instanceof AbstractImage) {
				AbstractImage<?> image = (AbstractImage<?>) part;
				
				AbstractImage<?> prevImage = prevImage(parts, index - 1);
				AbstractImage<?> nextImage = nextImage(parts, index + 1);
				
				image.setHome(firstImage);
				image.setEnd(lastImage);
				image.setPrevious(prevImage);	
				image.setNext(nextImage);
				
				image.visit(this, owner);
			}
		}
	}

	/**
	 * Find {@link AbstractImage} preceding the given index.
	 * 
	 * @param parts
	 *        All parts of the album.
	 * @param i
	 *        The index to search the preceding image for.
	 * @return The {@link AbstractImage} preceding the given index, or <code>null</code> if there is no such image.
	 */
	private static AbstractImage<?> prevImage(List<? extends AlbumPart<?>> parts, int i) {
		while (i >= 0) {
			AlbumPart<?> part = parts.get(i--);
			if (part instanceof AbstractImage) {
				return (AbstractImage<?>) part;
			}
		}
		return null;
	}

	/**
	 * @see #prevImage(List, int)
	 */
	private static AbstractImage<?> nextImage(List<? extends AlbumPart<?>> parts, int i) {
		while (i < parts.size()) {
			AlbumPart<?> part = parts.get(i++);
			if (part instanceof AbstractImage) {
				return (AbstractImage<?>) part;
			}
		}
		return null;
	}

}
