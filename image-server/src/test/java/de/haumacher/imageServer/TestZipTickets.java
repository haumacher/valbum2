/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.MediaSignatures;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.shared.model.MediaUrl;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Test case for the download tickets of issue #209.
 *
 * <p>
 * On the web the browser downloads an archive by itself, without the bearer:
 * <code>POST &lt;album&gt;/?action=zip-ticket</code> checks the names exactly as
 * <code>?action=zip</code> does and answers a signed address, which opens that one archive once,
 * to the subject it was made for, for as long as a media signature lives.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestZipTickets extends ShareTestCase {

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	// --- Checked before a ticket exists. ---

	public void testATicketIsRefusedAsTheZipIs() throws Exception {
		FakeResponse privateImage = ticket(ZOO, SharingFixture.CAROL, "public.jpg", "private.jpg");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, privateImage.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(privateImage));

		FakeResponse unknown = ticket(ZOO, SharingFixture.ALICE, "nowhere.jpg");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, unknown.status());
		assertEquals(ImageServlet.notInAlbum("nowhere.jpg"), errorMessage(unknown));

		FakeResponse address = ticket(ZOO, SharingFixture.ALICE, "../../Private/secret.jpg");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, address.status());

		FakeResponse empty = ticket(ZOO, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, empty.status());
		assertEquals(ImageServlet.ZIP_EMPTY, errorMessage(empty));

		FakeResponse rating = ticket("/", zooToken(Rights.VIEW, Rights.DOWNLOAD), "public.jpg", REJECTED);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, rating.status());
		assertEquals(ImageServlet.RATING_REFUSED, errorMessage(rating));

		FakeResponse noDownload = ticket("/", zooToken(Rights.VIEW), "public.jpg");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, noDownload.status());
		assertEquals(AuthService.DOWNLOAD_REFUSED, errorMessage(noDownload));

		assertEquals("No refused request left a ticket behind.", 0, servlet().zipTicketCount());
	}

	// --- The browser's download. ---

	public void testTheSignedAddressDownloadsOnceWithoutABearer() throws Exception {
		MediaUrl url = issued(ZOO, SharingFixture.ALICE, "public.jpg", "members.jpg");
		assertTrue(url.getUrl(), url.getUrl().startsWith("/valbum/data/2024/2024-05-01%20Zoo/?action=zip&ticket="));
		assertTrue(url.getUrl(), url.getUrl().endsWith("&media=" + url.getMedia()));
		assertTrue(url.getMedia(), url.getMedia().startsWith(MediaSignatures.DEVICE + "."));
		assertFalse("The device token never appears in the address.", url.getUrl().contains(SharingFixture.ALICE));
		long expires = Instant.parse(url.getExpires()).getEpochSecond() - Instant.now().getEpochSecond();
		assertTrue("Ten minutes: " + expires, expires > 590 && expires <= 600);
		assertEquals(1, servlet().zipTicketCount());

		FakeResponse download = fetch(ZOO, url, null);

		assertEquals(download.body(), HttpServletResponse.SC_OK, download.status());
		assertEquals("application/zip", download.contentType());
		assertEquals("attachment; filename=\"2024-05-01 Zoo.zip\"; filename*=UTF-8''2024-05-01%20Zoo.zip",
			download.header("Content-Disposition"));
		assertEquals("private, no-store", download.header("Cache-Control"));
		Map<String, byte[]> entries = entries(download);
		assertEquals(Arrays.asList("public.jpg", "members.jpg"), Arrays.asList(entries.keySet().toArray()));
		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			assertTrue(entry.getKey(), Arrays.equals(original(entry.getKey()), entry.getValue()));
		}

		FakeResponse again = fetch(ZOO, url, null);
		assertEquals("A ticket is used once.", HttpServletResponse.SC_GONE, again.status());
		assertEquals(ImageServlet.ZIP_TICKET_GONE, errorMessage(again));
		assertEquals(0, servlet().zipTicketCount());
	}

	public void testATicketOfOneDeviceIsRefusedForAnother() throws Exception {
		MediaUrl alices = issued(ZOO, SharingFixture.ALICE, "public.jpg");
		MediaUrl bobs = issued(ZOO, SharingFixture.BOB, "public.jpg");

		// Bob's signature with alice's ticket: the signature is over the ticket, so it opens none.
		FakeResponse swapped = ticketGet(ZOO, parameterOf(alices, ImageServlet.TICKET_PARAMETER), bobs.getMedia(), null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, swapped.status());
		assertEquals(AuthService.MEDIA_REFUSED, errorMessage(swapped));

		// A bearer beside the ticket is no signature: alice's own token does not open it either.
		FakeResponse bearer = ticketGet(ZOO, parameterOf(alices, ImageServlet.TICKET_PARAMETER), null, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_GONE, bearer.status());
		assertEquals(ImageServlet.ZIP_TICKET_GONE, errorMessage(bearer));

		// Neither attempt used the ticket up: alice still downloads.
		assertEquals(HttpServletResponse.SC_OK, fetch(ZOO, alices, null).status());
		assertEquals(HttpServletResponse.SC_OK, fetch(ZOO, bobs, SharingFixture.CAROL).status());
	}

	public void testALinksTicketIsRefusedForAnotherLink() throws Exception {
		String first = zooToken(Rights.VIEW, Rights.DOWNLOAD);
		String second = zooToken(Rights.VIEW, Rights.DOWNLOAD);
		MediaUrl firsts = issued("/", first, "public.jpg");
		MediaUrl seconds = issued("/", second, "public.jpg");
		assertTrue(firsts.getMedia(), firsts.getMedia().startsWith(MediaSignatures.SHARE + "." + idOf(first) + "."));
		assertFalse(firsts.getUrl(), firsts.getUrl().contains(first));

		FakeResponse swapped = ticketGet("/", parameterOf(firsts, ImageServlet.TICKET_PARAMETER), seconds.getMedia(), null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, swapped.status());

		FakeResponse download = fetch("/", firsts, null);
		assertEquals(download.body(), HttpServletResponse.SC_OK, download.status());
		assertTrue(Arrays.equals(original("public.jpg"), entries(download).get("public.jpg")));
	}

	public void testAWithdrawnLinksTicketIsGone() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);
		MediaUrl url = issued("/", token, "public.jpg");

		FakeResponse withdrawn = unshare(ZOO, SharingFixture.ALICE, idOf(token));
		assertEquals(withdrawn.body(), HttpServletResponse.SC_OK, withdrawn.status());

		assertEquals(HttpServletResponse.SC_GONE, fetch("/", url, null).status());
	}

	public void testAnExpiredTicketIsGone() throws Exception {
		MediaUrl url = issued(ZOO, SharingFixture.ALICE, "public.jpg");
		String ticket = parameterOf(url, ImageServlet.TICKET_PARAMETER);
		String device = url.getMedia().split("\\.")[1];
		String expired = new MediaSignatures(_base).sign(ZOO + "?ticket=" + ticket, MediaSignatures.Kind.ARCHIVE,
			MediaSignatures.DEVICE, device, Instant.now().getEpochSecond() - 1);

		FakeResponse response = ticketGet(ZOO, ticket, expired, null);

		assertEquals(HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.MEDIA_EXPIRED, errorMessage(response));
	}

	public void testATicketOpensOnlyItsFolder() throws Exception {
		MediaUrl url = issued(ZOO, SharingFixture.ALICE, "public.jpg");

		FakeResponse elsewhere = ticketGet("/" + SharingFixture.PUBLIC + "/", parameterOf(url, ImageServlet.TICKET_PARAMETER),
			url.getMedia(), null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, elsewhere.status());
		assertEquals(AuthService.MEDIA_REFUSED, errorMessage(elsewhere));
	}

	public void testAnAnonymousCallerGetsATicketWithoutASignature() throws Exception {
		// An open space: an anonymous caller may download the public photographs anyway.
		MediaUrl url = issued(ZOO, null, "public.jpg");
		assertEquals("", url.getMedia());
		assertFalse(url.getUrl(), url.getUrl().contains("media="));

		FakeResponse download = fetch(ZOO, url, null);
		assertEquals(download.body(), HttpServletResponse.SC_OK, download.status());

		FakeResponse members = ticket(ZOO, null, "members.jpg");
		assertEquals("Told to sign in, as for the original.", HttpServletResponse.SC_UNAUTHORIZED, members.status());

		// A device's ticket is no anonymous one, and the other way round.
		MediaUrl alices = issued(ZOO, SharingFixture.ALICE, "members.jpg");
		assertEquals(HttpServletResponse.SC_GONE,
			ticketGet(ZOO, parameterOf(alices, ImageServlet.TICKET_PARAMETER), null, null).status());
	}

	public void testADownloadWithoutATicketIsRefused() throws Exception {
		FakeResponse response = ticketGet(ZOO, null, null, SharingFixture.ALICE);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ImageServlet.ZIP_TICKET_REQUIRED, errorMessage(response));
	}

	// --- The store. ---

	public void testTheStoreHandsATicketOutOnceToItsSubjectBeforeItRunsOut() {
		AtomicLong now = new AtomicLong(1000);
		ZipTickets store = new ZipTickets(now::get);
		store.put("a", new ZipTickets.Ticket("/x/", Arrays.asList("a.jpg"), 2, "d.one", 1600));
		store.put("b", new ZipTickets.Ticket("/x/", Arrays.asList("b.jpg"), 2, "d.one", 1600));

		assertNull("Another subject.", store.take("a", "/x/", "d.two"));
		assertNull("Another folder.", store.take("a", "/y/", "d.one"));
		assertEquals(Arrays.asList("a.jpg"), store.take("a", "/x/", "d.one").getNames());
		assertNull("Once.", store.take("a", "/x/", "d.one"));

		now.set(1600);
		assertNull("Run out.", store.take("b", "/x/", "d.one"));
		store.put("c", new ZipTickets.Ticket("/x/", Arrays.asList("c.jpg"), 2, "d.one", 2200));
		assertEquals("A run-out ticket is pruned.", 1, store.size());
	}

	// --- Helpers. ---

	private FakeResponse ticket(String pathInfo, String token, String... names) throws Exception {
		StringBuilder body = new StringBuilder("{\"target\":\"\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_TICKET_ACTION);
		return post(pathInfo, body.toString(), token, parameters);
	}

	private MediaUrl issued(String pathInfo, String token, String... names) throws Exception {
		FakeResponse response = ticket(pathInfo, token, names);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertEquals("no-store", response.header("Cache-Control"));
		return MediaUrl.readMediaUrl(reader(body(response)));
	}

	/** The browser's fetch of the address the ticket answered, with an optional bearer beside it. */
	private FakeResponse fetch(String pathInfo, MediaUrl url, String token) throws Exception {
		return ticketGet(pathInfo, parameterOf(url, ImageServlet.TICKET_PARAMETER), url.getMedia().isEmpty() ? null : url.getMedia(),
			token);
	}

	private FakeResponse ticketGet(String pathInfo, String ticket, String media, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		if (ticket != null) {
			parameters.put(ImageServlet.TICKET_PARAMETER, ticket);
		}
		if (media != null) {
			parameters.put(MediaSignatures.PARAMETER, media);
		}
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}

	private static String parameterOf(MediaUrl url, String name) {
		String query = url.getUrl().substring(url.getUrl().indexOf('?') + 1);
		for (String pair : query.split("&")) {
			int equals = pair.indexOf('=');
			if (pair.substring(0, equals).equals(name)) {
				return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private static Map<String, byte[]> entries(FakeResponse response) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		Map<String, byte[]> result = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.bodyBytes()))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				result.put(entry.getName(), zip.readAllBytes());
			}
		}
		return result;
	}

	private byte[] original(String name) throws IOException {
		return Files.readAllBytes(_base.resolve(SharingFixture.ZOO).resolve(name));
	}
}
