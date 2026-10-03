/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.ContactList;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.IdentifyRequired;
import de.haumacher.imageServer.shared.model.RecipientLink;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The requests the tests of personal share links (issue #198) are driven with.
 *
 * <p>
 * A session of a personal link sends two things: the link's token as its bearer and the contact
 * credential in {@link AuthService#CONTACT_HEADER}. Every helper here takes both.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public abstract class PersonalLinkTestCase extends ShareTestCase {

	/** The zoo album in the space's coordinates. */
	protected static final String ZOO = "/" + SharingFixture.ZOO + "/";

	/** Petra's address. */
	protected static final String PETRA = "Petra@GMX.de";

	/** Klaus's number. */
	protected static final String KLAUS = "+49 171 123-4567";

	/** A personal link on the given folder sent to the given recipients, each a JSON object. */
	protected FakeResponse sharePersonal(String pathInfo, String token, String... recipients) throws Exception {
		return share(pathInfo, token, personalBody("Party", recipients));
	}

	/** The body of a personal link that allows looking and contributing. */
	protected static String personalBody(String label, String... recipients) {
		StringBuilder body = new StringBuilder("{\"label\":\"").append(label)
			.append("\",\"type\":\"PERSONAL\",\"maxPrivacy\":0,\"minRating\":-2,")
			.append("\"rights\":[{\"name\":\"view\"},{\"name\":\"download\"},{\"name\":\"contribute\"}],\"recipients\":[");
		for (int n = 0; n < recipients.length; n++) {
			body.append(n == 0 ? "" : ",").append(recipients[n]);
		}
		return body.append("]}").toString();
	}

	/** A new recipient with the given name and e-mail address. */
	protected static String email(String name, String address) {
		return "{\"name\":\"" + name + "\",\"addresses\":[{\"kind\":\"EMAIL\",\"value\":\"" + address + "\"}]}";
	}

	/** A new recipient with the given name and phone number. */
	protected static String phone(String name, String number) {
		return "{\"name\":\"" + name + "\",\"addresses\":[{\"kind\":\"PHONE\",\"value\":\"" + number + "\"}]}";
	}

	/** An existing contact as a recipient. */
	protected static String known(String id) {
		return "{\"contact\":\"" + id + "\"}";
	}

	/** Creates a personal link and answers what was created. */
	protected ShareLinkCreated created(String pathInfo, String... recipients) throws Exception {
		FakeResponse response = sharePersonal(pathInfo, SharingFixture.ALICE, recipients);
		assertEquals(response.body(), 200, response.status());
		return created(response);
	}

	/** The token of the recipient of the given name. */
	protected static String tokenOf(ShareLinkCreated created, String name) {
		for (RecipientLink recipient : created.getRecipients()) {
			if (recipient.getName().equals(name)) {
				return recipient.getToken();
			}
		}
		fail("No recipient '" + name + "' in " + created);
		return null;
	}

	/** The contact id of the recipient of the given name. */
	protected static String contactOf(ShareLinkCreated created, String name) {
		for (RecipientLink recipient : created.getRecipients()) {
			if (recipient.getName().equals(name)) {
				return recipient.getContact();
			}
		}
		fail("No recipient '" + name + "' in " + created);
		return null;
	}

	// --- Requests with a credential. ---

	protected Map<String, String> headers(String bearer, String credential) {
		Map<String, String> headers = new HashMap<>();
		if (bearer != null) {
			headers.put("Authorization", "Bearer " + bearer);
		}
		if (credential != null) {
			headers.put(AuthService.CONTACT_HEADER, credential);
		}
		return headers;
	}

	protected FakeResponse getAs(String pathInfo, String type, String bearer, String credential) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		FakeResponse response = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers(bearer, credential),
			parameters), response.response());
		return response;
	}

	protected FakeResponse postAs(String pathInfo, String action, String body, String bearer, String credential)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		FakeResponse response = new FakeResponse();
		servlet().doPost(TestImageServletPut.request(pathInfo, "application/json",
			body.getBytes(StandardCharsets.UTF_8), headers(bearer, credential), parameters), response.response());
		return response;
	}

	/** Sends <code>?action=identify</code> with the given recipient's token. */
	protected FakeResponse identify(String token, boolean remember) throws Exception {
		return identify(token, null, remember);
	}

	protected FakeResponse identify(String token, String credential, boolean remember) throws Exception {
		return postAs("/", "identify", "{\"remember\":" + remember + "}", token, credential);
	}

	/** The credential the first open of the given recipient's token issues. */
	protected String credential(String token) throws Exception {
		FakeResponse response = identify(token, true);
		assertEquals(response.body(), 200, response.status());
		return ContactCredential.readContactCredential(reader(body(response))).getCredential();
	}

	protected FakeResponse uploadAs(String pathInfo, String bearer, String credential, String fileName,
			byte[] contents) throws Exception {
		Map<String, String> headers = headers(bearer, credential);
		headers.put("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		files.put(fileName, contents);
		FakeResponse response = new FakeResponse();
		servlet().doPut(TestImageServletPut.request(pathInfo, "multipart/form-data; boundary=" + BOUNDARY,
			multipart(files), headers, new HashMap<>()), response.response());
		return response;
	}

	/** A real photograph of its own contents, so that what the album says about it can be read. */
	protected static byte[] photo(String seed) throws Exception {
		java.util.Random random = new java.util.Random(("personal/" + seed).hashCode());
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16,
			java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	protected static IdentifyRequired identifyRequired(FakeResponse response) throws Exception {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo, got: " + response.body(), resource instanceof ErrorInfo);
		IdentifyRequired result = ((ErrorInfo) resource).getIdentify();
		assertNotNull("Expected an IdentifyRequired, got: " + response.body(), result);
		return result;
	}

	/** The image of the given name in the album the response answers. */
	protected static de.haumacher.imageServer.shared.model.ImagePart image(FakeResponse response, String name)
			throws Exception {
		for (de.haumacher.imageServer.shared.model.AlbumPart part : album(response).getParts()) {
			if (part instanceof de.haumacher.imageServer.shared.model.ImagePart
				&& ((de.haumacher.imageServer.shared.model.ImagePart) part).getName().equals(name)) {
				return (de.haumacher.imageServer.shared.model.ImagePart) part;
			}
		}
		fail("No image '" + name + "' in " + response.body());
		return null;
	}

	protected static ContactList contactList(FakeResponse response) throws Exception {
		return ContactList.readContactList(reader(body(response)));
	}
}
