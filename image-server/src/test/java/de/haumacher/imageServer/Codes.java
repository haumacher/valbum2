/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import java.io.IOException;

/**
 * How a test gets a sign-in code, see issue #89.
 *
 * <p>
 * There is one way into a space since the pairing secret was retired: a single-use code that adds
 * a device to one user. Which of the three issuers made it is the only thing that differs, and
 * this helper picks the one that applies — the seat code the server issues while the space's
 * administrator has no device, and a code from a device the user already has afterwards.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
class Codes {

	private Codes() {
		// Helpers only.
	}

	/** A code signing the administrator of the space the given servlet serves in. */
	static String forServlet(ImageServlet servlet) throws IOException {
		return forOwner(servlet.auth());
	}

	/** A code signing the administrator of the given space in. */
	static String forOwner(AuthService auth) throws IOException {
		return forUser(auth, auth.getUsers().getOwner().getName());
	}

	/**
	 * A code signing the user of the given name in.
	 *
	 * @param userName
	 *        The name of the user, the empty string for an administrator who has none yet.
	 */
	static String forUser(AuthService auth, String userName) throws IOException {
		DeviceCodeStore.Issued seat = auth.issueSeatCode(null);
		if (seat != null && seat.getRecord().getUser().equals(userName)) {
			return seat.getCode();
		}
		UserStore.User user = auth.getUsers().getUser(userName);
		if (user == null || user.getDevices().isEmpty()) {
			throw new IllegalStateException("There is no code for '" + userName + "': no seat and no device.");
		}
		// What a signed-in device hands out for a further device of its own, see issue #65.
		return auth.getDeviceCodes().create(user.getName(), user.getDevices().get(0).getId()).getCode();
	}

	/**
	 * Signs a device of the given space's administrator in, answering what the server said.
	 *
	 * <p>
	 * The seat code while nobody signed in yet, a code from a device they already have afterwards
	 * — and the name is written on the administrator by the first of those, see issue #89.
	 * </p>
	 */
	static PairResponse signInAdmin(AuthService auth, String deviceName, String userName)
			throws AuthService.PairRefused, IOException {
		String code = forUser(auth, auth.getUsers().getOwner().getName());
		return auth.pair(PairRequest.create()
			.setDeviceCode(code)
			.setDeviceName(deviceName)
			.setUserName(userName));
	}

	/** The body of a pairing request redeeming the given code. */
	static String pairRequest(String code, String deviceName, String userName) {
		return "{\"deviceCode\":\"" + code + "\",\"deviceName\":\"" + deviceName + "\",\"userName\":\""
			+ userName + "\"}";
	}
}
