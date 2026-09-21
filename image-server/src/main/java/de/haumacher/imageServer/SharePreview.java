/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import de.haumacher.util.servlet.ResourceServlet;
import de.haumacher.util.servlet.Util;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a messenger sees when a share link is posted, see issue #104.
 *
 * <p>
 * A messenger or a social network fetches the link without running the application and reads the
 * Open Graph tags of the HTML it is answered. What <code>/s/&lt;token&gt;/</code> served was the
 * application's <code>index.html</code> with its <code>&lt;base href&gt;</code> rewritten, which
 * says nothing about the album; and the crawler sends no bearer, because the token becomes one only
 * after the application read it from the location. So two things are needed and they are both here:
 * </p>
 *
 * <ul>
 * <li>the tags themselves, written into the page of a <em>share</em> base only (an invitation is
 * private and gets none), and</li>
 * <li>one picture the card can name, <code>&lt;context&gt;[/&lt;space&gt;]/s/&lt;token&gt;/cover.jpg</code>,
 * answered without any <code>Authorization</code> header at all: the token in the path is the
 * authority for this one resource and for nothing else.</li>
 * </ul>
 *
 * <p>
 * The cover obeys every rule of the link it hangs below: a link that expired or was withdrawn
 * answers <code>410</code> here as it does on every other endpoint, the clearance is
 * <code>min(members, maxPrivacy)</code> and the rating limit is the link's, both applied by the
 * {@link PrivacyFilter} — an index picture the link may not show falls back to the first image it
 * may show, and a link that may show no image at all answers <code>404</code> and its page carries
 * no <code>og:image</code>.
 * </p>
 *
 * <p>
 * Nothing is stored: the tags are built for the request at hand, the cover is the
 * {@link PreviewCache preview} the album serves anyway, and no original is ever touched.
 * </p>
 *
 * <p>
 * This is the one place where the server spells an absolute URL to itself (issue #62 says it
 * otherwise never does), because a crawler cannot resolve a relative one: the surface is taken
 * from the request, which reports what a reverse proxy said the request came from.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class SharePreview implements ResourceServlet.PageDecorator, ResourceServlet.SessionResource {

	private static final Logger LOG = Logger.getLogger(SharePreview.class.getName());

	/** The name of the picture a share link's card is drawn with. */
	public static final String COVER_NAME = "cover.jpg";

	/** The path of the {@link #COVER_NAME cover} below a session base. */
	public static final String COVER_PATH = "/" + COVER_NAME;

	/** The message the cover of a token this space never issued is refused with. */
	public static final String COVER_UNKNOWN = "This link does not open anything on this server.";

	/** The message the cover of a link that may show no picture at all is refused with. */
	public static final String COVER_NONE = "This link shows no picture that could be a preview.";

	/** The message a cover whose preview could not be generated is refused with. */
	public static final String COVER_FAILED = "The preview of this album could not be generated.";

	private static final Pattern TITLE = Pattern.compile("(?is)<title>.*?</title>");

	private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

	private final Spaces _spaces;

	private final Function<String, ImageServlet> _data;

	/**
	 * Creates a {@link SharePreview}.
	 *
	 * @param spaces
	 *        The spaces of this server; a token of one space is simply unknown in another, exactly
	 *        as it is at the JSON API.
	 * @param data
	 *        The {@link ImageServlet} serving a space, by its segment. Its
	 *        {@link ImageServlet#cache() cache} and its {@link ImageServlet#privacy() privacy
	 *        filter} are the ones the album is served from, so a card never shows what an answer
	 *        would hide.
	 */
	public SharePreview(Spaces spaces, Function<String, ImageServlet> data) {
		_spaces = spaces;
		_data = data;
	}

	// --- The cover. ---

	@Override
	public boolean serve(HttpServletRequest request, HttpServletResponse response, String appBase, String prefix,
			String token, String relative) throws IOException {
		if (!ShareStore.URL_SEGMENT.equals(prefix) || !COVER_PATH.equals(relative)) {
			// Not the one resource this answers; the application's own files are none of it.
			return false;
		}
		Spaces.Space space = space(appBase);
		if (space == null) {
			// Not a space of this server; the address is answered as it was before.
			return false;
		}
		ShareStore.Link link = link(space, token);
		if (link == null) {
			refuse(response, HttpServletResponse.SC_GONE, COVER_UNKNOWN);
			return true;
		}
		if (link.isRevoked()) {
			refuse(response, HttpServletResponse.SC_GONE, AuthService.LINK_REVOKED);
			return true;
		}
		if (link.isExpired(Instant.now())) {
			refuse(response, HttpServletResponse.SC_GONE, AuthService.LINK_EXPIRED);
			return true;
		}
		File image = cover(space, link);
		if (image == null) {
			refuse(response, HttpServletResponse.SC_NOT_FOUND, COVER_NONE);
			return true;
		}
		File preview;
		try {
			preview = PreviewCache.createPreview(image);
		} catch (PreviewException ex) {
			LOG.log(Level.WARNING, "Cannot build the cover of " + link + ": " + ex.getMessage(), ex.getCause());
			refuse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, COVER_FAILED);
			return true;
		}
		send(response, preview);
		return true;
	}

	/** Delivers the given preview as the cover. */
	private static void send(HttpServletResponse response, File preview) throws IOException {
		response.setContentType(contentType(preview));
		response.setHeader("Access-Control-Allow-Origin", "*");
		// A session answer is never stored: the very same address means something else behind
		// another token, and a withdrawn link must not be replayed from a cache.
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Vary", "Authorization");
		response.setContentLengthLong(preview.length());
		try (FileInputStream in = new FileInputStream(preview)) {
			Util.sendBytes(response, in);
		}
	}

	/** What the given preview file is, by its name. */
	static String contentType(File preview) {
		return preview.getName().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
	}

	/** Refuses the cover with the given status and an {@link ErrorInfo} saying why. */
	private static void refuse(HttpServletResponse response, int status, String message) throws IOException {
		LOG.warning("Refusing a share cover: " + message);
		response.setStatus(status);
		response.setContentType("application/json;charset=utf-8");
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Vary", "Authorization");
		try (Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
				JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
			ErrorInfo.create().setMessage(message).writeTo(out);
		}
	}

	// --- The tags. ---

	@Override
	public String decorate(HttpServletRequest request, String html, String appBase, String prefix, String token) {
		if (!ShareStore.URL_SEGMENT.equals(prefix)) {
			// An invitation is private: it gets no card, see the class comment.
			return html;
		}
		Spaces.Space space = space(appBase);
		if (space == null) {
			return html;
		}
		ShareStore.Link link = link(space, token);
		if (link == null || !link.isLive()) {
			// A dead link is served the page it was served before; the application says what
			// became of it.
			return html;
		}
		PathInfo path = path(space, link);
		Resource shown = shown(space, path, link);
		if (shown == null) {
			return html;
		}
		String url = origin(request) + contextPath(request) + appBase + "/" + ShareStore.URL_SEGMENT + "/" + token
			+ "/";
		String title = title(shown, link);
		StringBuilder tags = new StringBuilder();
		tags.append("\n<title>").append(escape(title)).append("</title>\n");
		meta(tags, "og:title", title);
		String description = description(shown);
		if (!description.isEmpty()) {
			meta(tags, "og:description", description);
		}
		meta(tags, "og:type", "website");
		meta(tags, "og:url", url);
		if (cover(path, shown) != null) {
			meta(tags, "og:image", url + COVER_NAME);
		}
		tags.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
		return insert(html, tags.toString());
	}

	private static void meta(StringBuilder tags, String property, String content) {
		tags.append("<meta property=\"").append(property).append("\" content=\"").append(escape(content))
			.append("\">\n");
	}

	/**
	 * The given page with the given tags in its <code>&lt;head&gt;</code>.
	 *
	 * <p>
	 * The page's own <code>&lt;title&gt;</code> (the application's name) goes: what stands there
	 * is what a messenger shows when it finds no <code>og:title</code>, and a link to an album
	 * should never be announced as the name of a program.
	 * </p>
	 */
	static String insert(String html, String tags) {
		// The application's own title goes first, so that the one inserted below is never the one
		// that is dropped on a page that had none.
		String page = TITLE.matcher(html).replaceFirst(Matcher.quoteReplacement(""));
		int head = page.toLowerCase().indexOf("</head>");
		if (head < 0) {
			// Not a page this understands; it is served as it is.
			return html;
		}
		return page.substring(0, head) + tags + page.substring(head);
	}

	/** The given text as it may stand in an HTML attribute. */
	static String escape(String text) {
		StringBuilder result = new StringBuilder(text.length());
		for (int n = 0, length = text.length(); n < length; n++) {
			char ch = text.charAt(n);
			switch (ch) {
				case '&':
					result.append("&amp;");
					break;
				case '<':
					result.append("&lt;");
					break;
				case '>':
					result.append("&gt;");
					break;
				case '"':
					result.append("&quot;");
					break;
				case '\'':
					result.append("&#39;");
					break;
				default:
					result.append(ch);
			}
		}
		return result.toString();
	}

	/**
	 * The surface this server was reached under: <code>https://home.example.org</code>.
	 *
	 * <p>
	 * Behind a reverse proxy this is what the proxy said (see
	 * {@link org.eclipse.jetty.server.ForwardedRequestCustomizer}), which is the only thing there
	 * is to go on.
	 * </p>
	 */
	static String origin(HttpServletRequest request) {
		String scheme = request.getScheme();
		int port = request.getServerPort();
		StringBuilder result = new StringBuilder();
		result.append(scheme).append("://").append(request.getServerName());
		boolean standard = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
		if (port > 0 && !standard) {
			result.append(':').append(port);
		}
		return result.toString();
	}

	private static String contextPath(HttpServletRequest request) {
		String contextPath = request.getContextPath();
		return contextPath == null ? "" : contextPath;
	}

	/** The title a card announces the shared folder with; never the link's label, see issue #97. */
	static String title(Resource shown, ShareStore.Link link) {
		String title = "";
		if (shown instanceof AlbumInfo) {
			title = ((AlbumInfo) shown).getTitle();
		} else if (shown instanceof ListingInfo) {
			title = ((ListingInfo) shown).getTitle();
		}
		if (!title.isBlank()) {
			return title;
		}
		// A folder nobody titled is announced by its name.
		String path = link.getPath();
		int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}

	/** What a card says below the title: the subtitle, or the day the album happened. */
	static String description(Resource shown) {
		if (!(shown instanceof AlbumInfo)) {
			// A folder of folders has neither.
			return "";
		}
		AlbumInfo album = (AlbumInfo) shown;
		if (!album.getSubTitle().isBlank()) {
			return album.getSubTitle();
		}
		long date = album.getEffectiveDate();
		if (date <= 0) {
			return "";
		}
		return DAY.format(Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).toLocalDate());
	}

	// --- What the link opens. ---

	/** The space the given application base belongs to, <code>null</code> if there is none. */
	private Spaces.Space space(String appBase) {
		return _spaces.bySegment(appBase == null || appBase.isEmpty() ? "" : appBase.substring(1));
	}

	/** The link of the given token in the given space, <code>null</code> if it issued none. */
	private static ShareStore.Link link(Spaces.Space space, String token) {
		AuthService auth = space.getAuth();
		ShareStore shares = auth == null ? null : auth.getShares();
		// A server started with '--auth off' issues no link and honours none.
		return shares == null ? null : shares.lookup(token);
	}

	/** Where the given link points, in the coordinates of its space. */
	private static PathInfo path(Spaces.Space space, ShareStore.Link link) {
		String relative = link.getPath();
		if (relative.isEmpty()) {
			return new PathInfo(space.getRoot());
		}
		Path path = Paths.get(relative).normalize();
		if (path.isAbsolute() || path.startsWith("..") || path.toString().isEmpty()) {
			return null;
		}
		return new PathInfo(space.getRoot(), path);
	}

	/** What the given link shows of its target, filtered as every answer of it is. */
	private Resource shown(Spaces.Space space, PathInfo path, ShareStore.Link link) {
		if (path == null || !path.isDirectory()) {
			return null;
		}
		ImageServlet data = _data.apply(space.getSegment());
		if (data == null) {
			return null;
		}
		Resource resource = data.cache().lookup(path);
		if (resource == null) {
			return null;
		}
		// A link never shows an inbox, and never one among the entries of a shared folder: the
		// card of a link is what a link shows, see issue #131.
		Resource shown = Inboxes.filter(resource, path, Inboxes.Visibility.NONE, "");
		if (shown == null) {
			return null;
		}
		// A link never shows a private image, whoever made it, and never one below its rating.
		return data.privacy().filter(shown, path, Math.min(Privacy.MEMBERS, link.getMaxPrivacy()),
			link.getMinRating());
	}

	/**
	 * The picture the card of the given link is drawn with, <code>null</code> if it may show none.
	 *
	 * <p>
	 * The album's index picture, which the {@link PrivacyFilter} has already replaced by the first
	 * visible image where the stored one is hidden; for a shared <em>folder</em>, the picture of
	 * its first child that has one.
	 * </p>
	 */
	File cover(Spaces.Space space, ShareStore.Link link) {
		PathInfo path = path(space, link);
		return cover(path, shown(space, path, link));
	}

	/** The picture drawn for the given target, as the link sees it, see {@link #cover(Spaces.Space, ShareStore.Link)}. */
	private static File cover(PathInfo path, Resource shown) {
		if (shown == null) {
			return null;
		}
		if (shown instanceof AlbumInfo) {
			ImagePart image = coverOf((AlbumInfo) shown);
			if (image == null) {
				return null;
			}
			File file = path.child(image.getName()).toFile();
			return file.isFile() ? file : null;
		}
		if (shown instanceof ListingInfo) {
			for (FolderInfo folder : ((ListingInfo) shown).getFolders()) {
				ThumbnailInfo indexPicture = folder.getIndexPicture();
				if (indexPicture == null || indexPicture.getImage().isEmpty()) {
					// A folder whose cover the link may not show, or one that holds no picture.
					continue;
				}
				File file = path.child(folder.getName()).child(indexPicture.getImage()).toFile();
				if (file.isFile()) {
					return file;
				}
			}
		}
		return null;
	}

	/** The image the given album is shown by, <code>null</code> if it shows none. */
	private static ImagePart coverOf(AlbumInfo album) {
		ThumbnailInfo indexPicture = album.getIndexPicture();
		if (indexPicture != null) {
			ImagePart named = named(album, indexPicture.getImage());
			if (named != null) {
				return named;
			}
		}
		return PrivacyFilter.firstImage(album);
	}

	/** The image of the given name in the given album, <code>null</code> if it holds none. */
	private static ImagePart named(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				ImagePart image = (ImagePart) part;
				if (image.getName().equals(name)) {
					return image;
				}
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					if (image.getName().equals(name)) {
						return image;
					}
				}
			}
		}
		return null;
	}

}
