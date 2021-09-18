/*
 * Copyright (c) 2021 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

import de.haumacher.imageServer.shared.model.Resource;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface ResourceHandler {

	void store(Resource resource);

}
