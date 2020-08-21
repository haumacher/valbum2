/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.util.List;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.jpeg.JpegSegmentMetadataReader;
import com.drew.imaging.jpeg.JpegSegmentType;
import com.drew.lang.annotations.NotNull;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.exif.ExifThumbnailDirectory;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ExifReaderPatch {
	
	public static int TAG_THUMBNAIL_DATA = 0x10000;
	
	// Workaround for extracting thumbnail images from JPEG files, see https://github.com/drewnoakes/metadata-extractor/issues/276#issuecomment-677767368
	static {
		List<JpegSegmentMetadataReader> allReaders = (List<JpegSegmentMetadataReader>) JpegMetadataReader.ALL_READERS;
		for (int n = 0, cnt = allReaders.size(); n < cnt; n++) {
			if (allReaders.get(n).getClass() != ExifReader.class) {
				continue;
			}
			
			allReaders.set(n, new ExifReader() {
				@Override
				public void readJpegSegments(@NotNull final Iterable<byte[]> segments, @NotNull final Metadata metadata, @NotNull final JpegSegmentType segmentType) {
					super.readJpegSegments(segments, metadata, segmentType);

				    for (byte[] segmentBytes : segments) {
				        // Filter any segments containing unexpected preambles
				        if (!startsWithJpegExifPreamble(segmentBytes)) {
				        	continue;
				        }
				        
				        // Extract the thumbnail
				        try {
				            ExifThumbnailDirectory tnDirectory = metadata.getFirstDirectoryOfType(ExifThumbnailDirectory.class);
				            if (tnDirectory != null && tnDirectory.containsTag(ExifThumbnailDirectory.TAG_THUMBNAIL_OFFSET)) {
				            	int offset = tnDirectory.getInt(ExifThumbnailDirectory.TAG_THUMBNAIL_OFFSET);
				            	int length = tnDirectory.getInt(ExifThumbnailDirectory.TAG_THUMBNAIL_LENGTH);
				            	
				            	byte[] tnData = new byte[length];
				            	System.arraycopy(segmentBytes, JPEG_SEGMENT_PREAMBLE.length() + offset, tnData, 0, length);
				            	tnDirectory.setObject(TAG_THUMBNAIL_DATA, tnData);
				            }
				        } catch (MetadataException e) {
				            e.printStackTrace();
				        }
				    }
				}				
			});
			break;
		}
	}
}
