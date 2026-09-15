/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.RightName;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.ShareLinkList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The library and the request helpers the share-link tests of issue #51 are driven with.
 *
 * <p>
 * The {@link SharingFixture} of issue #49 as {@link SpaceTestCase} sets it up, plus one rejected
 * photo in alice's zoo album: <code>rejected.jpg</code> with the rating <code>-1</code>, which is
 * what a link with a rating limit must hide. It is added here rather than in the fixture so that
 * every test written against the fixture before issue #51 keeps counting what it counted.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public abstract class ShareTestCase extends SpaceTestCase {

	/** The photo in the zoo album that a rating limit of <code>0</code> hides. */
	protected static final String REJECTED = "rejected.jpg";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		SharingFixture.album(_base, SharingFixture.ZOO, "Zoo",
			"[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3}],"
				+ "[\"ImagePart\",{\"name\":\"members.jpg\",\"width\":4,\"height\":3,\"privacy\":1}],"
				+ "[\"ImagePart\",{\"name\":\"private.jpg\",\"width\":4,\"height\":3,\"privacy\":2}],"
				+ "[\"ImagePart\",{\"name\":\"" + REJECTED + "\",\"width\":4,\"height\":3,\"rating\":-1}]",
			"public.jpg", "members.jpg", "private.jpg", REJECTED);
	}

	// --- Creating and managing links over HTTP. ---

	/** Sends <code>&lt;folder&gt;/?action=share</code> with the given link description. */
	protected FakeResponse share(String pathInfo, String token, String body) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "share");
		return post(pathInfo, body, token, parameters);
	}

	/** The body of a share request. */
	protected static String shareBody(String label, String expires, int maxPrivacy, int minRating, String... rights) {
		StringBuilder body = new StringBuilder("{\"label\":\"").append(label).append("\"");
		body.append(",\"expires\":\"").append(expires).append("\"");
		body.append(",\"maxPrivacy\":").append(maxPrivacy);
		body.append(",\"minRating\":").append(minRating);
		body.append(",\"rights\":[");
		for (int n = 0; n < rights.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(rights[n]).append("\"}");
		}
		return body.append("]}").toString();
	}

	/** Sends <code>&lt;folder&gt;/?action=unshare</code> naming the link of the given id. */
	protected FakeResponse unshare(String pathInfo, String token, String id) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "unshare");
		return post(pathInfo, "{\"id\":\"" + id + "\"}", token, parameters);
	}

	/** Sends <code>&lt;folder&gt;/?type=shares</code>. */
	protected FakeResponse shares(String pathInfo, String token) throws Exception {
		return get(pathInfo, "shares", token);
	}

	protected static ShareLinkCreated created(FakeResponse response) throws IOException {
		return ShareLinkCreated.readShareLinkCreated(reader(body(response)));
	}

	protected static ShareLinkList links(FakeResponse response) throws IOException {
		return ShareLinkList.readShareLinkList(reader(body(response)));
	}

	protected static AuthInfo auth(FakeResponse response) throws IOException {
		return AuthInfo.readAuthInfo(reader(body(response)));
	}

	/** The rights a link answer carries, in the order they are answered. */
	protected static List<String> rightsOf(ShareLink link) {
		List<String> result = new ArrayList<>();
		for (RightName right : link.getRights()) {
			result.add(right.getName());
		}
		return result;
	}

	/** The link of the given id in a listing, <code>null</code> if it lists none. */
	protected static ShareLink link(ShareLinkList list, String id) {
		for (ShareLink link : list.getLinks()) {
			if (link.getId().equals(id)) {
				return link;
			}
		}
		return null;
	}

	// --- Creating links behind the servlet's back, for lifetimes a request cannot ask for. ---

	/**
	 * Records a share link directly in the stores and answers its token.
	 *
	 * <p>
	 * The way to build a link that is already expired or already withdrawn: the endpoint refuses to
	 * create one, and rightly so. The caller restarts the servlet, so that it reads what was
	 * written here.
	 * </p>
	 */
	protected String issue(String owner, String path, String label, String expires, int maxPrivacy, int minRating,
			String... rights) throws Exception {
		ShareStore shares = new ShareStore(_base);
		// The link is the permission, see issue #83: what it may do is recorded on the link itself.
		ShareStore.Issued issued = shares.create(owner, path, label, expires, maxPrivacy, minRating,
			rights.length == 0 ? Arrays.asList(Rights.VIEW) : Arrays.asList(rights));
		restartServer();
		return issued.getToken();
	}

	/** A live link on the zoo album showing public photos rated <code>0</code> or better. */
	protected String zooToken(String... rights) throws Exception {
		return issue("alice", SharingFixture.ZOO, "Grandma", "", Privacy.PUBLIC, 0, rights);
	}

	/** The id of the link the given token opens. */
	protected String idOf(String token) {
		return new ShareStore(_base).lookup(token).getId();
	}


	/** The contents of the share store on disk. */
	protected String shareStoreContents() throws IOException {
		Path file = new ShareStore(_base).getFile();
		return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
	}

	/**
	 * A fingerprint of a user's albums: every file below the given space but the server's own
	 * caches.
	 *
	 * <p>
	 * The preview cache (<code>.vacache</code>) is what a thumbnail request creates and is no
	 * photo of anybody's, so it is left out; everything else must be byte for byte what it was.
	 * </p>
	 */
	protected String albumFingerprint(String space) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		// The space is the served tree now, see issue #83.
		Path root = _base.resolve(space.equals("alice") ? "" : space);
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile)
				.filter(file -> !root.relativize(file).toString().contains(PreviewCache.CACHE_DIRECTORY_NAME))
				// The server's own state is not part of anybody's library, see issue #83.
				.filter(file -> !root.relativize(file).toString()
					.startsWith(de.haumacher.imageServer.auth.UserStore.DIRECTORY_NAME + "/"))
				.forEach(files::add);
		}
		files.sort(Comparator.comparing(Path::toString));
		for (Path file : files) {
			digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		StringBuilder result = new StringBuilder();
		for (byte b : digest.digest()) {
			result.append(String.format("%02x", Byte.valueOf(b)));
		}
		return result.toString();
	}

	/** The rating limit that hides nothing, for a test saying so explicitly. */
	protected static final int NO_RATING_LIMIT = Ratings.MIN;
}
