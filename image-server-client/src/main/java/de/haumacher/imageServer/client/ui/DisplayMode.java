/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.ui;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;

/**
 * Mode of display of a {@link Resource}.
 * 
 * <p>
 * Especially for {@link ImageGroup}s and {@link ImagePart}s, the {@link DisplayMode} is relevant. An {@link ImageGroup}
 * may either be shown as part of an {@link AlbumInfo} (showing its {@link ImageGroup#getRepresentative()
 * representative} image ({@link #DEFAULT})), or as its own overview page listing all images within the group
 * ({@link #DETAIL}). An {@link ImagePart} may be shown as preview either directly as part of its owning
 * {@link AlbumInfo} ({@link #DEFAULT}), as part of the details of the {@link ImageGroup} is contained in ({@link #DETAIL}).
 * </p>
 */
public enum DisplayMode {
	
	DEFAULT,
	
	DETAIL;

}
