/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.shared.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class AlbumUtil {

	/** 
	 * Inserts the given images into the given album with potentially existing images.
	 */
	public static void insertSorted(AlbumInfo album, List<? extends ImagePart> newImages) {
		if (!newImages.isEmpty()) {
			// Order new images by date.
			Collections.sort(newImages, (a, b) -> Long.compare(a.getDate(), b.getDate()));
			
			long lastDate = Long.MAX_VALUE;
			List<Long> dates = new ArrayList<>();
			Map<Long, Integer> indexByDate = new HashMap<>();
			List<AlbumPart> existing = album.getParts();
			for (int index = existing.size() - 1; index >= 0; index--) {
				AlbumPart part = existing.get(index);
				if (part instanceof AbstractImage) {
					ImagePart image = ToImage.toImage((AbstractImage) part);
					long date = image.getDate();
					if (date < lastDate) {
						Long dateAsLong = Long.valueOf(date);
						
						dates.add(dateAsLong);
						indexByDate.put(dateAsLong, Integer.valueOf(index));
						lastDate = date;
					}
				}
			}
			
			if (dates.isEmpty()) {
				// No images currently known, add all new images in date order.
				album.getParts().addAll(newImages);
			} else {
				// Strongly ascending dates of images already known.
				Collections.reverse(dates);
				
				// Insert new images just after the image with the largest date smaller than the date of the new
				// image.
				int insertBeforeDateIndex = dates.size();
				for (int newIndex = newImages.size() - 1; newIndex >= 0; newIndex--) {
					ImagePart newImage = newImages.get(newIndex);
					
					while (insertBeforeDateIndex > 0) {
						if (newImage.getDate() >= dates.get(insertBeforeDateIndex - 1)) {
							break;
						}
						insertBeforeDateIndex--;
					}
	
					int insertAfterIndex;
					if (insertBeforeDateIndex == 0) {
						Long insertBeforeDate = dates.get(insertBeforeDateIndex);
						Integer insertBeforeIndex = indexByDate.get(insertBeforeDate);
						insertAfterIndex = insertBeforeIndex.intValue() - 1;
					} else {
						Long insertAfterDate = dates.get(insertBeforeDateIndex - 1);
						insertAfterIndex = indexByDate.get(insertAfterDate).intValue();
					}
					album.getParts().add(insertAfterIndex + 1, newImage);
				}
			}
	
			// Update again to fix internal linking structure.
			UpdateTransient.updateTransient(album);
		}
	}

}
