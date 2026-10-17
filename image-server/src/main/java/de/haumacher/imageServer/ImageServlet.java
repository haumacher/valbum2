/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;


import de.haumacher.imageServer.MoveService.MoveRefused;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.AuthService.Location;
import de.haumacher.imageServer.auth.AuthService.PairRefused;
import de.haumacher.imageServer.auth.AuthService.PathRefused;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.MediaSignatures;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.SignIns;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.TotpSignIns;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.AnalysisRecord;
import de.haumacher.imageServer.cache.AnalysisStep;
import de.haumacher.imageServer.cache.BrokenParts;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.coded.CodedPictures;
import de.haumacher.imageServer.content.ContentIndex;
import de.haumacher.imageServer.content.ContentVectors;
import de.haumacher.imageServer.content.Embedder;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceImport;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.faces.FaceTags;
import de.haumacher.imageServer.faces.Faces;
import de.haumacher.imageServer.faces.PeopleStore;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.oidc.OidcLogins;
import de.haumacher.imageServer.oidc.OidcProvider;
import de.haumacher.imageServer.passkeys.Passkeys;
import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.places.PhotoPlaces;
import de.haumacher.imageServer.places.PlaceNames;
import de.haumacher.imageServer.places.Places;
import de.haumacher.imageServer.places.PlacesStep;
import de.haumacher.imageServer.raw.NoEmbeddedPreviewException;
import de.haumacher.imageServer.raw.RawFile;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.CacheRefreshed;
import de.haumacher.imageServer.shared.model.Contact;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.ContactDelete;
import de.haumacher.imageServer.shared.model.ContactIdentify;
import de.haumacher.imageServer.shared.model.ContactRename;
import de.haumacher.imageServer.shared.model.ContactSessionEnd;
import de.haumacher.imageServer.shared.model.ContactShutOut;
import de.haumacher.imageServer.shared.model.ContentHash;
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.CreatedTarget;
import de.haumacher.imageServer.shared.model.Crop;
import de.haumacher.imageServer.shared.model.DeviceCodeCreated;
import de.haumacher.imageServer.shared.model.DeviceCodeRequest;
import de.haumacher.imageServer.shared.model.DeviceEntry;
import de.haumacher.imageServer.shared.model.DeviceList;
import de.haumacher.imageServer.shared.model.EmailProof;
import de.haumacher.imageServer.shared.model.EmailVerify;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FaceAssignment;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageCount;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Invitation;
import de.haumacher.imageServer.shared.model.InvitationCreated;
import de.haumacher.imageServer.shared.model.InvitationList;
import de.haumacher.imageServer.shared.model.LabelChange;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MediaUrl;
import de.haumacher.imageServer.shared.model.MemberName;
import de.haumacher.imageServer.shared.model.MoveName;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveRequest;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.NewsKey;
import de.haumacher.imageServer.shared.model.NewsRead;
import de.haumacher.imageServer.shared.model.OidcExchange;
import de.haumacher.imageServer.shared.model.OidcStart;
import de.haumacher.imageServer.shared.model.OidcStarted;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.OtherSessionsEnded;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.PersonCreate;
import de.haumacher.imageServer.shared.model.PersonLink;
import de.haumacher.imageServer.shared.model.PersonMerge;
import de.haumacher.imageServer.shared.model.PersonRename;
import de.haumacher.imageServer.shared.model.PhotoRef;
import de.haumacher.imageServer.shared.model.PlaceCaption;
import de.haumacher.imageServer.shared.model.PresentFile;
import de.haumacher.imageServer.shared.model.ProofMethod;
import de.haumacher.imageServer.shared.model.RefusedFile;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchOptions;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.ShareLinkList;
import de.haumacher.imageServer.shared.model.ShareResend;
import de.haumacher.imageServer.shared.model.TagFaces;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.shared.model.UploadCheck;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.shared.model.UploadResult;
import de.haumacher.imageServer.shared.model.UploadedFile;
import de.haumacher.imageServer.shared.model.UserEntry;
import de.haumacher.imageServer.shared.model.UserList;
import de.haumacher.imageServer.shared.model.UserPermission;
import de.haumacher.imageServer.shared.ui.Settings;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.imageServer.upload.HashIndex;
import de.haumacher.imageServer.upload.UploadFactory;
import de.haumacher.imageServer.upload.UploadItem;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import de.haumacher.util.servlet.ByteRange;
import de.haumacher.util.servlet.Util;
import jakarta.activation.MimeType;
import jakarta.activation.MimeTypeParseException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.fileupload2.jakarta.JakartaServletFileUpload;

/**
 * {@link HttpServlet} serving image, video, preview and directory listing data.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@MultipartConfig
public class ImageServlet extends HttpServlet {

	private static final Logger LOG = Logger.getLogger(ImageServlet.class.getName());

	/** The {@link UploadedFile#getStatus() status} of contents that were written to the album. */
	public static final String STORED = "stored";

	/**
	 * The {@link UploadedFile#getStatus() status} of contents the target folder already held.
	 *
	 * <p>
	 * Nothing was written: this is what makes a retried upload idempotent, see issue #29.
	 * </p>
	 */
	public static final String PRESENT = "present";

	/** The message a PUT is refused with that would replace an existing file with other contents. */
	public static final String REPLACE_REFUSED =
		"A different file with this name already exists; it is not replaced.";

	/** The message an unreadable upload check is refused with. */
	public static final String CHECK_UNREADABLE = "The upload check cannot be read.";

	/** The message an unreadable request to remove a link is refused with. */
	public static final String UNLINK_UNREADABLE = "The request to remove a shared album cannot be read.";

	/** The message an unreadable share link is refused with. */
	public static final String SHARE_UNREADABLE = "The share link cannot be read.";

	/** The message an unreadable invitation is refused with, see issue #52. */
	public static final String INVITATION_UNREADABLE = "The invitation cannot be read.";

	/** The message an unreadable promotion request is refused with. */
	public static final String PROMOTION_UNREADABLE = "The request naming the user to promote cannot be read.";

	/**
	 * The message a request for an image below the caller's rating limit is refused with, see
	 * issue #51.
	 *
	 * <p>
	 * Only a share link has such a limit, and it is the author's choice of what the link shows —
	 * so the refusal says that, rather than pretending the file is not there.
	 * </p>
	 */
	public static final String RATING_REFUSED = "This image is not part of what this link shows.";

	/** The message a grant or revoke naming a share-link token is refused with, see issue #51. */
	public static final String SHARE_GRANT_REFUSED =
		"The grant of a share link belongs to the link: create one with ?action=share and delete it with "
			+ "?action=unshare.";

	/** The message an unreadable grant is refused with. */
	public static final String GRANT_UNREADABLE = "The grant cannot be read.";

	/** The message an unreadable group is refused with. */
	public static final String GROUP_UNREADABLE = "The group cannot be read.";

	/** The message an unreadable group rename is refused with, see issue #55. */
	public static final String RENAME_UNREADABLE = "The request naming the group to rename cannot be read.";

	/** The message an unreadable device code request is refused with, see issue #89. */
	public static final String DEVICE_CODE_UNREADABLE = "The request naming whom the code is for cannot be read.";

	/** The message an unreadable unpair request is refused with, see issue #55. */
	public static final String DEVICE_UNREADABLE = "The request naming the device to sign out cannot be read.";

	/**
	 * The message a request for an image above the caller's clearance is refused with, see issue
	 * #46.
	 *
	 * <p>
	 * The refusal names no privacy level: that the image exists is already visible from the path,
	 * but how far it is restricted is not the caller's business. It is a refusal, never a
	 * <code>404</code>: nothing here lies about what is there.
	 * </p>
	 */
	public static final String IMAGE_REFUSED =
		"This image is not available to you. Signing in may give access to it.";

	/** The message a request with an unknown <code>viewAs</code> value is refused with. */
	public static final String VIEW_AS_REFUSED =
		"Unknown 'viewAs' value; use 'public' or 'members'.";

	/**
	 * The message a thumbnail request is answered with when the preview cannot be built.
	 *
	 * <p>
	 * A failure of the preview generator is a failure of this server, not a missing file: the
	 * former <code>404</code> told the app the image was gone and hid every such failure in the
	 * server's log (issue #68). What is missing is answered with a <code>404</code> before the
	 * preview is ever asked for, see {@link #doGet(HttpServletRequest, HttpServletResponse)}.
	 * </p>
	 */
	public static final String PREVIEW_FAILED = "The preview of this image cannot be created.";

	/**
	 * The <code>type</code> of the display rendition of a HEIC/HEIF photograph (issue #186), an
	 * AVIF or a JPEG XL picture (issue #193), see {@link PreviewCache#createDisplay(File)}.
	 */
	public static final String DISPLAY_TYPE = "display";

	/** The message a display rendition of a photograph that needs none is refused with. */
	public static final String DISPLAY_NOT_NEEDED =
		"Only a HEIC/HEIF, AVIF, JPEG XL or raw photograph has a display rendition; ask for the original.";

	/** The message a display rendition that cannot be made is answered with. */
	public static final String DISPLAY_FAILED = "This photograph cannot be shown at full size.";

	/**
	 * The message a preview that cannot be made is answered with: {@link #PREVIEW_FAILED}, or for a
	 * HEIC or an AVIF on a server that cannot decode one, why not (issues #186, #193), and for a picture whose decoder
	 * needs more memory than the server has for one, how much and what to do (issue #207).
	 */
	static String previewFailed(File file, PreviewException failure) {
		if (failure.getCause() instanceof PictureTooLargeException
			|| failure.getCause() instanceof NoEmbeddedPreviewException) {
			// Which picture, how much it needs, how much there is and what to do (issue #207); which
			// raw carries no preview and what to do (issue #191).
			return failure.getCause().getMessage();
		}
		String unavailable = CodedPictures.unavailability(file.getName());
		if (unavailable != null) {
			return unavailable;
		}
		return PREVIEW_FAILED;
	}

	/**
	 * The message a request for a video rendition that is not made yet is answered with.
	 *
	 * <p>
	 * A transcode takes minutes, so no request ever waits for one: the answer is
	 * <code>202 Accepted</code> with a <code>Retry-After</code>, and the work is queued, see
	 * {@link VideoRenditions}.
	 * </p>
	 */
	public static final String RENDITION_PENDING = "This video is being prepared; please try again shortly.";

	/**
	 * The message a request for a preview, a display rendition or a face crop the background has not
	 * made yet is answered with, <code>202 Accepted</code> like {@link #RENDITION_PENDING}: a
	 * request never makes one, see issue #256.
	 */
	public static final String PREVIEW_PENDING = "This picture is being prepared; please try again shortly.";

	/**
	 * The message a request for a face crop that this caller is answered no faces of is refused
	 * with, see issue #124.
	 *
	 * <p>
	 * A <code>404</code> and not a <code>403</code>: a caller that is answered no face must not be
	 * told that there is one to be refused, exactly as an inbox they may not see is not there for
	 * them (issue #131).
	 * </p>
	 */
	public static final String FACE_NOT_FOUND = "There is no such face.";

	/**
	 * What a request for the people of the space is refused to a share link with, see issue #125.
	 *
	 * <p>
	 * The register is the space's own bookkeeping: who its photographs are of, by name. A link is
	 * handed an album and nothing around it, and it is never shown a face either (issue #124).
	 * </p>
	 */
	public static final String PEOPLE_REFUSED = "The people of this space are its members' business.";

	/**
	 * What <code>?type=duplicates</code> is refused with to a share link and to an anonymous caller,
	 * see issue #220: which photographs lie in which albums is an overview of the whole space, and a
	 * link is handed one album and nothing around it.
	 */
	public static final String DUPLICATES_REFUSED =
		"Which photos lie in several albums is shown to the members of this space only.";

	/** The type of the space-level overview of the photographs in several albums, see issue #220. */
	public static final String DUPLICATES_TYPE = "duplicates";

	/** The type of the list of every share link of the space, see issue #269. */
	public static final String SPACE_SHARES_TYPE = "space-shares";

	/** The type of the progress of the background work of the space, see issue #236. */
	public static final String CATCH_UP_TYPE = "catch-up";

	/** What <code>?type=catch-up</code> is refused with to everybody but an administrator. */
	public static final String CATCH_UP_REFUSED =
		"How far the server has got preparing the albums is shown to the administrator of this space only.";

	/** The type of the bell of issue #268: what arrived from others, see {@link Arrivals}. */
	public static final String NEWS_TYPE = "news";

	/** The type of the number of unread entries of the bell alone, what the app polls, see issue #268. */
	public static final String NEWS_COUNT_TYPE = "news-count";

	/**
	 * The type of the number of photographs a folder shows its caller, see {@link ImageCount} and
	 * issue #286: what a listing tile does not carry, asked when the app is about to show it.
	 */
	public static final String IMAGE_COUNT_TYPE = "image-count";

	/** The action marking entries of the bell as read, see issue #268 and {@link NewsRead}. */
	public static final String NEWS_READ_ACTION = "news-read";

	/**
	 * What the bell of issue #268 is refused with to everybody but a member: a share link, a contact
	 * and an anonymous visitor are shown one album, never what arrives in the others.
	 */
	public static final String NEWS_REFUSED = "Only the members of this space are told what others added to its albums.";

	/** What an unreadable {@link NewsRead} is refused with. */
	public static final String NEWS_READ_UNREADABLE = "The request marking news as read cannot be read.";

	/** What linking somebody else's account to a person is refused with, see issue #128. */
	public static final String LINK_REFUSED =
		"Only an administrator says who somebody else is; you may say who you are.";

	/**
	 * What a tagging request without the edit right is refused with, see issue #125.
	 *
	 * <p>
	 * Naming the people in a photograph needs {@link Rights#EDIT}, and there is deliberately no
	 * exception for one's own contribution: the contributor exception of issue #53 lets somebody
	 * take their photograph back out of an album, it does not let them write names into it. Issue
	 * #123 decided this.
	 * </p>
	 */
	public static final String TAGGING_REFUSED =
		"Naming the people in a photograph needs the right to edit this album.";

	/** What an unreadable tagging request is refused with. */
	public static final String TAG_UNREADABLE = "The tagging request cannot be read.";

	/** What an unreadable request about a person is refused with. */
	public static final String PERSON_UNREADABLE = "The request cannot be read.";

	/** What a confirmation or a rejection that names nobody is refused with. */
	public static final String TAG_PERSON_REQUIRED = "This decision is about a person; the request names none.";

	/** What a "this is not a face" that names somebody is refused with. */
	public static final String TAG_PERSON_REFUSED =
		"A false detection is about nobody; the request names a person.";

	/** What a tagging request naming something that is not an album is refused with. */
	public static final String TAG_NOT_AN_ALBUM = "There are no photographs to tag here.";

	/**
	 * What a face marked on a video is refused with, see issue #155.
	 *
	 * <p>
	 * The detector never looks at a video (issue #123), so there is no face on one to mark, and no
	 * region of one to look for a face in.
	 * </p>
	 */
	public static final String TAG_VIDEO_REFUSED = "Faces are marked on photographs only, not on a video.";

	/** Why an image of a tagging request is not in the album it names. */
	static String unknownImage(String name) {
		return "There is no image '" + name + "' in this album.";
	}

	/** Why a face of a tagging request is not one of the faces answered for its image. */
	static String unknownFace(String image, int index) {
		return "There is no face " + index + " in '" + image + "'.";
	}

	/**
	 * Why a hand-marked face of a tagging request is nowhere on its photograph, see issue #147.
	 *
	 * <p>
	 * The box is a fraction of the picture as it is shown, so it lies inside <code>0..1</code> and
	 * has a width and a height. Anything else is not a place on this photograph, and a face is
	 * somewhere or it is nowhere.
	 * </p>
	 */
	static String faceBoxInvalid(String image) {
		return "The marked face is not a place on '" + image + "'.";
	}

	/**
	 * The <code>action</code> that moves or resizes the box of an answered face, see issue #157 and
	 * {@link #tagFaces(Context, boolean)}.
	 */
	public static final String ADJUST_FACES_ACTION = "adjust-faces";

	/** The <code>action</code> that stores the crop of one photograph, see issue #212. */
	public static final String CROP_ACTION = "crop";

	/** Renames or removes one label on every photograph of an album, see issue #213 and {@link LabelChange}. */
	public static final String RELABEL_ACTION = "relabel";

	/**
	 * The parameter of a <code>?type=tn</code> naming the region of the rendition to cut, see issue
	 * #212 and {@link Crops#token(double[])}.
	 */
	public static final String CROP_PARAMETER = "crop";

	/**
	 * The parameter of a <code>?type=tn</code> naming the size of the preview, see issue #241:
	 * {@value #SIZE_NORMAL} (the default) or {@value #SIZE_LARGE}.
	 */
	public static final String SIZE_PARAMETER = "size";

	/** The <code>size</code> of the ordinary preview, the one a <code>?type=tn</code> without it gets. */
	public static final String SIZE_NORMAL = "normal";

	/**
	 * The <code>size</code> of the large preview a starred tile asks for on a dense screen, see
	 * {@link PreviewCache#createLargePreview(File, double[])} and issue #241.
	 */
	public static final String SIZE_LARGE = "large";

	/** A <code>size</code> parameter that names no size of preview. */
	public static final String SIZE_UNKNOWN =
		"A preview comes in the sizes '" + SIZE_NORMAL + "' and '" + SIZE_LARGE + "' only.";

	/** A <code>crop</code> parameter that names no region of the picture. */
	public static final String CROP_UNREADABLE = "The crop names no region of the picture.";

	/** A cut rendition asked of a video, which has none. */
	public static final String CROP_VIDEO_REFUSED = "Only a photograph is cropped, not a video.";

	/**
	 * A cut rendition asked by a caller who may not edit the album, of a region the photograph does
	 * not store: the album was read before its crop changed.
	 */
	public static final String CROP_NOT_STORED =
		"This photograph is cropped differently now; reload the album.";

	/** The message a crop request is refused with whose body cannot be read. */
	public static final String CROP_REQUEST_UNREADABLE = "The crop request cannot be read.";

	/** The message a crop is refused with outside an album. */
	public static final String CROP_NOT_AN_ALBUM = "There are no photographs to crop here.";

	/** The message a crop is refused with that does not lie on the picture. */
	static String cropInvalid(String image) {
		return "The crop is not a region of '" + image + "'.";
	}

	/** The message a crop is refused with for a caller who may not edit the album. */
	public static final String CROP_REFUSED = "Only an editor of this album may crop its photographs.";

	/**
	 * The message a change to a photograph made through a collection or a saved search is refused
	 * with where the caller may not edit the album the photograph lies in, see issue #292.
	 *
	 * @param name
	 *        The photograph as the collection or the saved search names it.
	 */
	public static String sourceEditRefused(String name) {
		return "'" + name + "' is not changed: what is said about a photograph is written to the album it lies in, "
			+ "and you may not edit that album.";
	}

	/** The <code>action</code> that stores the moment a video's poster shows, see issue #285. */
	public static final String POSTER_ACTION = "poster";

	/**
	 * The parameter of a <code>?type=tn</code> naming the moment of a video's poster in
	 * milliseconds, see issue #285 and {@link Posters}.
	 */
	public static final String POSTER_PARAMETER = "poster";

	/** The message a poster is refused with by a caller who may not edit the album. */
	public static final String POSTER_REFUSED = "Only an editor of this album may choose the thumbnail of its videos.";

	/** The message a poster request is refused with whose body cannot be read. */
	public static final String POSTER_REQUEST_UNREADABLE = "The thumbnail request cannot be read.";

	/** The message a poster is refused with outside an album. */
	public static final String POSTER_NOT_AN_ALBUM = "There are no videos to choose a thumbnail of here.";

	/** The message a poster is refused with on a photograph, or asked of one. */
	public static final String POSTER_NOT_A_VIDEO = "Only a video has a moment to show as its thumbnail.";

	/** The message a <code>?type=tn&amp;poster=</code> is refused with that names no moment. */
	public static final String POSTER_UNREADABLE = "The poster names no moment of the video.";

	/**
	 * A poster asked by a caller who may not edit the album, of a moment the video does not store,
	 * see {@link #askedPoster}.
	 */
	public static final String POSTER_NOT_STORED =
		"This video shows another moment as its thumbnail now; reload the album.";

	/** The message a poster is refused with that lies after the end of the video. */
	static String posterBeyondEnd(String video, long millis, long duration) {
		return "The moment " + seconds(millis) + " is after the end of '" + video + "' (" + seconds(duration) + ").";
	}

	/** The message a poster is refused with where the video cannot be read for its length. */
	static String posterUnreadableVideo(String video) {
		return "The length of '" + video + "' cannot be read, so no moment of it can be its thumbnail.";
	}

	/** A number of milliseconds as seconds with one decimal, <code>3.2 s</code>. */
	private static String seconds(long millis) {
		return String.format(java.util.Locale.ROOT, "%.1f s", millis / 1000.0);
	}

	/**
	 * How far after the end the container says a poster may still lie, in milliseconds: the player
	 * stopped on the last frame of the playback rendition, whose length is the original's rounded to
	 * its own frames. Such a moment is the last frame, see {@link PreviewCache#getPreviewFrame}.
	 */
	static final long POSTER_END_SLACK = 1000;

	/** The <code>action</code> that answers a selection of originals as one archive, see issue #164. */
	public static final String ZIP_ACTION = "zip";

	/** The <code>action</code> that adds photographs to a collection, see issue #221. */
	public static final String COLLECT_ACTION = "collect";

	/** The message a download request is refused with whose body cannot be read, see issue #164. */
	public static final String ZIP_UNREADABLE = "The download request could not be read.";

	/** The message a download request naming nothing is refused with, see issue #164. */
	public static final String ZIP_EMPTY = "Name at least one photograph to download.";

	/**
	 * The <code>action</code> that checks a download as {@link #ZIP_ACTION} would and answers an
	 * address the browser fetches by itself, see issue #209 and {@link ZipTickets}.
	 */
	public static final String ZIP_TICKET_ACTION = "zip-ticket";

	/**
	 * The parameter a signed address of an original carries where the browser is to save the file
	 * rather than show it, see issue #209: <code>download=1</code>.
	 */
	public static final String DOWNLOAD_PARAMETER = "download";

	/** The value of {@link #DOWNLOAD_PARAMETER} that asks for the attachment. */
	public static final String DOWNLOAD_PARAMETER_VALUE = "1";

	/** The parameter of a {@link #ZIP_ACTION} <code>GET</code> naming its ticket, see issue #209. */
	public static final String TICKET_PARAMETER = "ticket";

	/** The message a <code>GET ?action=zip</code> without a ticket is refused with, see issue #209. */
	public static final String ZIP_TICKET_REQUIRED =
		"A download is fetched with the address the album answered for it. Start the download again.";

	/** The message a used, run-out or foreign download ticket is refused with, see issue #209. */
	public static final String ZIP_TICKET_GONE =
		"This download address has been used already or has run out. Start the download again.";

	/** The <code>type</code> a face crop is asked for with, see issue #124. */
	public static final String FACE_TYPE = "face";

	/**
	 * The <code>type</code> asking for a signed address of one file of a video, see issue #185 and
	 * {@link MediaSignatures}.
	 *
	 * <p>
	 * A <code>GET</code>, not an action: it is a read — it answers what the caller may fetch
	 * already, checked exactly as that fetch is checked — it changes nothing (the secret made at
	 * first use is an implementation detail), and it needs no body.
	 * </p>
	 */
	public static final String MEDIA_URL_TYPE = "media-url";

	/** The parameter of {@link #MEDIA_URL_TYPE} naming the file: <code>video</code>, <code>teaser</code> or <code>original</code>. */
	public static final String MEDIA_FOR_PARAMETER = "for";

	/** The message a {@link #MEDIA_URL_TYPE} request is refused with that names no known file. */
	public static final String MEDIA_KIND_UNKNOWN =
		"A media address is made for 'video', 'teaser' or 'original'.";

	/**
	 * The message a {@link #MEDIA_URL_TYPE} request for a rendition or a teaser is refused with for
	 * a file that is no video; the original of every image has one, see issue #209.
	 */
	public static final String MEDIA_NOT_A_VIDEO = "Only a video has a playback or teaser address.";

	/** The parameter naming which face of an image is asked for, see issue #124. */
	public static final String FACE_PARAMETER = "face";

	/** The message a request for a video rendition that cannot be made is answered with. */
	public static final String RENDITION_FAILED = "This video cannot be prepared for playback.";

	/** How long a caller waits before asking for a pending rendition again, in seconds. */
	public static final String RETRY_AFTER_SECONDS = "10";

	/**
	 * How long a caller waits at most before asking for a preview, a display rendition or a face
	 * crop again that is not there yet, in seconds: the background makes one asked for next (issue
	 * #258), a matter of a second, where a transcode takes minutes.
	 */
	public static final String PREVIEW_RETRY_AFTER_SECONDS = "3";

	/**
	 * What a rendition request is answered with on a server that cannot transcode at all.
	 *
	 * <p>
	 * The reason follows: on a machine without the system libraries the bundled FFmpeg links, the
	 * program does not load, and saying so once is better than queueing work that can never be
	 * done (issue #81).
	 * </p>
	 */
	public static final String RENDITIONS_UNAVAILABLE = "Video renditions are not available on this server: ";

	/** What a request for the retired sharing grants of issue #49 is answered with. */
	public static final String RETIRED_GRANTS =
		"Sharing with a user is done by inviting them into the space: see the users of the space "
			+ "and invite whom you want in, with the role and clearance they should have.";

	/** What a request for the retired user groups of issue #49 is answered with. */
	public static final String RETIRED_GROUPS =
		"Groups are gone: a permission belongs to a user now. Invite the people you meant "
			+ "individually, each with the role and clearance they should have.";

	/** What a request for the retired shared-album links of issue #50 is answered with. */
	public static final String RETIRED_LINKS =
		"An album is no longer shared into somebody's own tree: invite them into this space, or "
			+ "hand them a share link.";

	/** What a request for the retired guest promotion of issue #52 is answered with. */
	public static final String RETIRED_PROMOTE =
		"There are no guests any more: a guest is a user with the 'view' role and a low clearance. "
			+ "Change what they may do with 'set-permission'.";

	/** The message a management request from somebody who is not an administrator is refused with. */
	public static final String ADMIN_ONLY = "Only an administrator of this space may do that.";

	/** The message an unreadable permission or user name is refused with. */
	public static final String PERMISSION_UNREADABLE = "The request cannot be read.";

	/** The message an invitation naming a clearance this build does not know is refused with. */
	public static final String CLEARANCE_REFUSED = "Unknown clearance; use one of " + Clearances.names() + ".";

	/**
	 * What the retired duplicate sweep of issue #118 is answered with, see issue #219: it moved
	 * photographs out of the album.
	 */
	public static final String RETIRED_FIND_DUPLICATES =
		"Finding duplicates was removed: it moved photos out of the album. Nothing was changed.";

	/** The message a share link asking to re-read the photo details is refused with, see issue #161. */
	public static final String REANALYZE_SHARE_REFUSED =
		"A share link cannot re-read the details of the photographs.";

	/** The message a caller who may not change the folder is refused the re-reading with. */
	public static final String REANALYZE_REFUSED =
		"You may not change this album, so its photo details cannot be re-read.";

	/** The message a folder nobody re-read yet answers <code>?type=reanalyze</code> with. */
	public static final String NO_REANALYSIS = "The details of this folder were not re-read since the server started.";

	/**
	 * How long a request to re-read a folder of folders waits for the background run before it is
	 * answered <code>202</code> with the counts so far, see {@link Reanalysis}.
	 */
	public static final long REANALYZE_WAIT_MILLIS = 10_000;

	/** The message a cache refresh from somebody who is not an administrator is refused with. */
	public static final String CACHE_REFRESH_REFUSED =
		"Only an administrator may throw an album's previews away.";

	static {
		LOG.info("Loading: " + ExifReaderPatch.class);
	}

	private Path _basePath;
	private ResourceCache _cache;

	/** The search field of the folder picker, see issue #261 and {@link #FOLDERS_TYPE}. */
	private final FolderFinder _folderFinder = new FolderFinder();

	/** What the contributors of the space added, counted for the owner screens of issue #203. */
	private Contributions _contributions;

	/** The space this servlet serves, see {@link #ImageServlet(File, AuthService, String)}. */
	private final String _space;

	/**
	 * The map template of the space this servlet serves, see issue #112.
	 *
	 * <p>
	 * A property of the space, read from its <code>space.json</code> at start-up and whenever its
	 * administrator saves the settings (issue #297), and answered by <code>?type=auth</code>, so
	 * that the app has it with the one request it makes anyway. Never empty:
	 * {@link SpaceStore#DEFAULT_MAP_URL} where the file names none.
	 * </p>
	 */
	private volatile String _mapUrl;

	/**
	 * The path of the space's inbox relative to its root, see issue #226: what its
	 * <code>space.json</code> names, else {@link SpaceStore#DEFAULT_INBOX}.
	 */
	private volatile String _inbox;

	/**
	 * The name of the space this servlet serves as the code mail of issue #199 says it: its
	 * <code>space.json</code> name, the folder name in a multi-space server, empty where neither
	 * says one (the mail then names the program).
	 */
	private volatile String _spaceName;

	/**
	 * What the space says about itself, see {@link SpaceStore}: as it was read at start-up, and as
	 * its administrator saved it since, see {@link #reconfigure(SpaceStore.Config)}.
	 */
	private volatile SpaceStore.Config _config;

	/** Who else hears of a saved configuration, see {@link #onReconfigured(Consumer)}. */
	private volatile Consumer<SpaceStore.Config> _onReconfigured = config -> {
		// Nobody.
	};

	/** What a photograph's faces are taken over from at analysis, see issue #129. */
	private final FaceImport _faceImport;

	/** The settings of the space made in the app, see issue #297. */
	private final SpaceConfiguration _settings;

	/**
	 * The proof of an e-mail address by a mailed code, see issue #199: one instance for the whole
	 * server, so that its rate limits count across every space; {@link EmailProofs#NONE} where no
	 * mail account is configured, and in every test that does not install one.
	 */
	private EmailProofs _proofs = EmailProofs.NONE;

	/**
	 * The proof of an e-mail address through a provider of OpenID Connect, see issue #200: one
	 * instance for the whole server, whose one callback serves every space; {@link OidcLogins#NONE}
	 * where no provider (or no public address) is configured, and in every test that installs none.
	 */
	private OidcLogins _oidc = OidcLogins.NONE;

	/** Passkeys of the contacts, see issue #204. */
	private Passkeys _passkeys = Passkeys.NONE;

	/** The transcoded sidecars of the videos this server shows, see issue #74. */
	private final VideoRenditions _videos = new VideoRenditions();

	/**
	 * Where every content of this space is, by its hash, see issue #118.
	 *
	 * <p>
	 * One index per space, exactly like the {@link AuthService} and the {@link ResourceCache}: a
	 * check never looks outside the space it was asked in. It is loaded here and built in the
	 * background only where somebody asked for that, see {@link #startIndexing()} — a test that
	 * drives this servlet headlessly gets the lookup and no threads.
	 * </p>
	 */
	private final HashIndex _index;

	/**
	 * The index of this space in <code>.valbum/index/</code> (issue #264): opened once with the
	 * servlet, shared by the hash index and the search, closed by {@link #destroy()}.
	 */
	private final SpaceIndex _spaceIndex;

	/** What the search of issue #227 asks of every photograph of the space, see {@link SearchIndex}. */
	private final SearchIndex _searchIndex;

	/**
	 * How often a query was run over the space, see {@link #searchRuns()}: what proves that a
	 * listing never runs a saved search, see issue #287.
	 */
	private final java.util.concurrent.atomic.AtomicLong _searchRuns = new java.util.concurrent.atomic.AtomicLong();

	/** Who the photographs of this space are of, see issue #125. */
	private final PeopleStore _people;

	/** The collections of this space, resolved through {@link #_index}, see issue #221. */
	private final PhotoCollections _collections;

	/** The hash index of this space, for the tests and for the sweep of issue #118. */
	public HashIndex index() {
		return _index;
	}

	/** Re-reading the photo details of a folder, see issue #161. */
	private final Reanalysis _reanalysis;

	/** The download tickets of this space, see issue #209. */
	private final ZipTickets _zipTickets = new ZipTickets();

	/** How many download tickets are held, for a test. */
	int zipTicketCount() {
		return _zipTickets.size();
	}

	/** How long a request waits for a tree to be re-read, see {@link #REANALYZE_WAIT_MILLIS}. */
	private long _reanalyzeWait = REANALYZE_WAIT_MILLIS;

	/** The re-reading of this space, for the tests. */
	Reanalysis reanalysis() {
		return _reanalysis;
	}

	/** Sets how long a request waits for a tree to be re-read, for the tests. */
	void setReanalyzeWait(long millis) {
		_reanalyzeWait = millis;
	}

	/** The people register of this space, see issue #125. */
	public PeopleStore people() {
		return _people;
	}

	/**
	 * Who is in the photographs of this space, see issue #124.
	 *
	 * <p>
	 * One per space like everything else here, and switched on by the space's own
	 * <code>space.json</code>. A space that did not ask for it holds an index that does nothing at
	 * all: no thread, no model, no file.
	 * </p>
	 */
	private final FaceIndex _faces;

	/** The face index of this space, for the tests. */
	public FaceIndex faces() {
		return _faces;
	}

	/**
	 * The content search of this space in the background, see issue #257: switched on by its
	 * <code>space.json</code>, fed by the embedder of the server ({@link #setEmbedder(Embedder)}).
	 */
	private final ContentIndex _content;

	/** The content search of this space, for the tests. */
	public ContentIndex content() {
		return _content;
	}

	/**
	 * The feed of the vector index of this space from the albums' caches, see issue #264 (slice 4)
	 * and {@link SearchIndex#nearest}.
	 */
	private final ContentVectors _vectors;

	/** The feed of the vector index of this space, for the tests. */
	public ContentVectors vectors() {
		return _vectors;
	}

	/**
	 * Installs the embedder of the content search, see issue #257: one for the whole server, made
	 * by {@link de.haumacher.imageServer.content.EmbedderSettings#create(Path)}; without it every
	 * photo waits, and the settings say why.
	 */
	public void setEmbedder(Embedder embedder) {
		_content.setEmbedder(embedder);
	}

	/**
	 * Starts building the hash index of this space in the background, see {@link HashIndex}.
	 *
	 * <p>
	 * Called once, by the wiring that starts a real server: a first build reads the whole library
	 * and writes a sidecar into every album that has none, which is the right thing for a server
	 * that is going to run, and the wrong thing for a unit test that builds a servlet over a
	 * fixture and asks it one question.
	 * </p>
	 */
	public void startIndexing() {
		// Behind hashing, everything an album needs before its first visit, newest album first:
		// previews, the cover, faces where they are on, places, and the videos last, see issue #236.
		// The face index has no walk of its own any more; an album opened before the background
		// reached it is still queued by its listing.
		synchronized (this) {
			if (!_catchUpInstalled) {
				CatchUp.install(_index.pipeline(), _basePath, _faces, _videos, _placesStep, _content, _vectors);
				_catchUpInstalled = true;
			}
		}
		// From now on the background records the folders, and a request is a pure read of what it
		// recorded, see issue #253.
		_cache.setBackground(true);
		_index.start();
	}

	/** Whether {@link #startIndexing()} added the steps of issue #236. */
	private boolean _catchUpInstalled;

	/** Where the places of the photographs come from, see issue #234; <code>null</code> for none. */
	private volatile Places _places;

	/** The warm-up of {@link #_places} in the background work of this space. */
	private PlacesStep _placesStep;

	/** The video renditions of this server, for the tests that wait for a transcode. */
	VideoRenditions videos() {
		return _videos;
	}

	private PrivacyFilter _privacy;

	/**
	 * The albums and listings this servlet answers from, shared with the share preview of issue
	 * #104.
	 *
	 * <p>
	 * One cache per space: the card of a share link is built from the very resource an answer of
	 * that link would be built from, and a directory watcher is an operating-system resource that
	 * is not spent twice.
	 * </p>
	 */
	ResourceCache cache() {
		return _cache;
	}

	/** What this servlet hides on the way out, shared with the share preview of issue #104. */
	PrivacyFilter privacy() {
		return _privacy;
	}

	/** The passkeys of this space, for the ways a contact signs in that the overview of issue #267 answers. */
	Passkeys passkeys() {
		return _passkeys;
	}


	private JakartaServletFileUpload<UploadItem, UploadFactory> _fileUpload;

	private final AuthService _auth;

	/** Who may do what here; the users, devices and codes of the space this servlet serves. */
	AuthService auth() {
		return _auth;
	}

	/**
	 * Creates a {@link ImageServlet} serving every request without authentication.
	 *
	 * @param basePath The root path of the photo album to serve.
	 */
	public ImageServlet(File basePath) throws IOException {
		this(basePath, AuthService.disabled());
	}

	/**
	 * Creates a {@link ImageServlet}.
	 *
	 * @param basePath The root path of the photo album to serve.
	 * @param auth Decides who may read and who may write, see {@link AuthService}.
	 */
	public ImageServlet(File basePath, AuthService auth) throws IOException {
		this(basePath, auth, "");
	}

	/**
	 * Creates an {@link ImageServlet} serving one space of a multi-space server (issue #82).
	 *
	 * @param space
	 *        The first path segment this space is addressed by, the empty string on a
	 *        single-space server. It is what <code>?type=auth</code> names, so that the app knows
	 *        which space it is talking to.
	 */
	public ImageServlet(File basePath, AuthService auth, String space) throws IOException {
		this(basePath, auth, space, SpaceStore.load(basePath.toPath(), space == null ? "" : space));
	}

	/**
	 * Creates an {@link ImageServlet} serving one space whose configuration has already been read.
	 *
	 * @param config
	 *        What the space says about itself, see {@link SpaceStore}. Only its map template
	 *        (issue #112) is this servlet's business; who may look is the {@link AuthService}'s.
	 */
	public ImageServlet(File basePath, AuthService auth, String space, SpaceStore.Config config)
			throws IOException {
		_space = space == null ? "" : space;
		_config = config == null ? new SpaceStore.Config(_space, SpaceStore.ANONYMOUS_NONE) : config;
		_mapUrl = config == null ? SpaceStore.DEFAULT_MAP_URL : config.getMapUrl();
		_spaceName = config == null ? "" : config.getName();
		_basePath = basePath.toPath();
		_inbox = config == null ? SpaceStore.DEFAULT_INBOX : config.getInbox();
		// One inbox per space, decided by where a folder lies, see issue #226.
		Inboxes.register(_basePath, _inbox);
		boolean facesEnabled = config != null && config.isFacesEnabled();
		_people = new PeopleStore(_basePath);
		// The register has to exist before the cache does: a photograph that is analysed for the
		// first time may name people, see FaceImport and issue #129. The zone is the space's: a
		// photograph that does not say its own is dated in it, see issue #183.
		_faceImport = new FaceImport(_people, facesEnabled);
		_cache = new ResourceCache(_faceImport::read, config == null ? null : config.getZone());
		_privacy = new PrivacyFilter(_cache);
		_auth = auth;
		_spaceIndex = SpaceIndex.open(_basePath);
		_index = new HashIndex(_basePath, _spaceIndex);
		// What a folder holds is found out in the background, first of all, see issue #253. A file
		// that cannot be read is a broken part of its album and is named in the status of the
		// background work (issue #252); where no background runs (a test's servlet), the request
		// that records the folder says so.
		_index.pipeline().addStep(new AnalysisStep(_cache, _index.pipeline()));
		_cache.setRefusals((folder, reason) -> _index.pipeline().reportFailure(folder, reason));
		_collections = new PhotoCollections(_basePath, _index);
		_faces = new FaceIndex(_basePath, facesEnabled, _spaceIndex);
		_reanalysis = new Reanalysis(_cache, _space.isEmpty() ? basePath.getName() : _space);
		_searchIndex = new SearchIndex(_basePath, _spaceIndex, this::lookupOrSkip, () -> {
			Places places = _cache.places();
			return places == null ? -1 : places.epoch();
		}, _cache::hasBackground);
		_index.pipeline().addStep(_searchIndex);
		// The vectors of the content search, where the space has it on, see issue #257.
		_content = new ContentIndex(_basePath, this::lookupOrSkip, config != null && config.isContentEnabled());
		// Their index, fed from each album's cache whenever the step writes it, see issue #264.
		_vectors = new ContentVectors(_basePath, _spaceIndex, _content);
		_content.setOnWritten(folder -> _vectors.refresh(folder));
		_settings = new SpaceConfiguration(this, _auth, _basePath, _space);
		String noFaces = _faces.unavailability();
		if (noFaces != null) {
			LOG.warning("The space '" + (_space.isEmpty() ? basePath.getName() : _space)
				+ "' asked for face detection, which this machine cannot do: " + noFaces);
		}
	}

	/** What the space says about itself now, see {@link #reconfigure(SpaceStore.Config)}. */
	SpaceStore.Config config() {
		return _config;
	}

	/**
	 * Lets the given listener hear of every configuration the administrator saves from now on, see
	 * {@link #reconfigure(SpaceStore.Config)}: the {@link de.haumacher.imageServer.auth.Spaces.Space}
	 * this servlet serves, so that whoever asks it reads the same.
	 */
	public void onReconfigured(Consumer<SpaceStore.Config> listener) {
		_onReconfigured = listener;
	}

	/**
	 * Takes the given configuration of the space into effect, at once and without a restart, see
	 * issue #297: what its administrator just saved.
	 *
	 * <p>
	 * The name and the map are answered from the next request on; the anonymous access decides the
	 * next request (where the space decides it at all, see {@link AuthService#anonymousFixed()});
	 * the zone dates what is read from now on; the inbox moves (it holds nothing, see
	 * {@link SpaceConfiguration#inboxFixed()}). Faces switched on join the background work, which
	 * then looks at every album of the space; switched off, nothing is looked at or offered any
	 * more, and nothing found or named before is touched. The content search of issue #257 is
	 * switched the same way.
	 * </p>
	 */
	void reconfigure(SpaceStore.Config config) {
		SpaceStore.Config before = _config;
		_config = config;
		_mapUrl = config.getMapUrl();
		_spaceName = config.getName();
		_cache.setZone(SpaceStore.zoneOf(config.getTimeZone()));
		if (!config.getInbox().equals(_inbox)) {
			PathInfo old = inboxPath();
			_inbox = config.getInbox();
			Inboxes.move(_basePath, _inbox);
			PathInfo moved = inboxPath();
			// What the two folders are, and the listings that show them or not, is the place's word.
			for (PathInfo path : new PathInfo[] { old, moved }) {
				_cache.invalidate(path);
				if (!path.isRoot()) {
					_cache.invalidate(path.parent());
				}
			}
		}
		_faceImport.setEnabled(config.isFacesEnabled());
		if (before.isFacesEnabled() != config.isFacesEnabled()) {
			synchronized (this) {
				CatchUp.switchFaces(_index.pipeline(), _basePath, _faces, config.isFacesEnabled(), _catchUpInstalled);
			}
			String noFaces = _faces.unavailability();
			if (noFaces != null) {
				LOG.warning("The space '" + (_space.isEmpty() ? _basePath.getFileName() : _space)
					+ "' asked for face detection, which this machine cannot do: " + noFaces);
			}
		}
		if (before.isContentEnabled() != config.isContentEnabled()) {
			synchronized (this) {
				CatchUp.switchContent(_index.pipeline(), _basePath, _content, _vectors, config.isContentEnabled(),
					_catchUpInstalled);
			}
		}
		_auth.setAnonymousAllowed(config.isAnonymousAllowed());
		_onReconfigured.accept(config);
	}

	/** The folder of the inbox of this space, whether it exists or not, see issue #226. */
	File inboxFolder() {
		return inboxPath().toFile();
	}

	/**
	 * Installs the proof of an e-mail address by a mailed code, see issue #199; before, every proof
	 * is answered {@link EmailProofs#NOT_CONFIGURED}.
	 */
	public void setEmailProofs(EmailProofs proofs) {
		_proofs = proofs == null ? EmailProofs.NONE : proofs;
	}

	/**
	 * Installs the proof of an e-mail address through OpenID Connect, see issue #200; before, no
	 * provider is offered and a sign-in is answered {@link OidcLogins#NOT_CONFIGURED}.
	 */
	public void setOidcLogins(OidcLogins oidc) {
		_oidc = oidc == null ? OidcLogins.NONE : oidc;
	}

	/**
	 * Lets the albums this space holds in memory take the given number of bytes of heap, see
	 * {@link ResourceCache#setBudget(long)} and issue #245; before, an eighth of the heap.
	 */
	public void setCacheMemory(long bytes) {
		_cache.setBudget(bytes);
	}

	/**
	 * Lets the index of this space take the given number of bytes of heap for its page cache, see
	 * {@link de.haumacher.imageServer.index.SpaceIndex#setBudget(long)} and issue #264; before, a
	 * sixteenth of the heap.
	 */
	public void setIndexMemory(long bytes) {
		_spaceIndex.setBudget(bytes);
	}

	/**
	 * Installs the gazetteer the places of the photographs come from, see issue #234; before, no
	 * photograph is answered any place. Adds the warm-up of the gazetteer to the background work of
	 * the space, behind hashing ({@link PlacesStep}).
	 */
	public void setPlaces(Places places) {
		if (_placesStep != null) {
			_placesStep.close();
			_index.pipeline().removeStep(_placesStep);
			_placesStep = null;
		}
		_places = places;
		_cache.setPlaces(places);
		if (places != null) {
			_placesStep = new PlacesStep(places, _index.pipeline());
			_index.pipeline().addStep(_placesStep);
			// The search reads the places, so it runs behind them, see issue #227.
			_index.pipeline().removeStep(_searchIndex);
			_index.pipeline().addStep(_searchIndex);
			// And the vectors, filed under the ids of the search, behind it.
			if (_index.pipeline().steps().contains(_vectors)) {
				_index.pipeline().removeStep(_vectors);
				_index.pipeline().addStep(_vectors);
			}
		}
	}

	/** What the search of issue #227 asks of every photograph of this space. */
	public SearchIndex searchIndex() {
		return _searchIndex;
	}

	/** The index of this space, see issue #264. */
	public SpaceIndex spaceIndex() {
		return _spaceIndex;
	}

	/** The gazetteer of this space, <code>null</code> for none. */
	public Places places() {
		return _places;
	}

	/** The authentication of this servlet's space. */
	AuthService getAuth() {
		return _auth;
	}

	/**
	 * Installs passkeys, see issue #204; before, none is offered and every passkey request is
	 * answered {@link Passkeys#NOT_CONFIGURED}.
	 */
	public void setPasskeys(Passkeys passkeys) {
		_passkeys = passkeys == null ? Passkeys.NONE : passkeys;
	}

	/** The requests of an authenticator app (issue #208) and of passkeys (issue #204). */
	private SignInActions signInActions() {
		return new SignInActions(this, _auth, issuer(), _passkeys, _space);
	}

	/**
	 * The name an authenticator app files this space's entry under (issue #208): the space's name,
	 * else its folder in a server of several spaces, else the application's.
	 */
	private String issuer() {
		if (!_spaceName.trim().isEmpty()) {
			return _spaceName.trim();
		}
		return _space.isEmpty() ? "VAlbum" : _space;
	}

	/** Who may prove which address, with the proofs this servlet was given. */
	AddressProof addressProof() {
		return new AddressProof(_auth, _proofs, _oidc, _spaceName, _passkeys,
			link -> SharePreview.linkTitle(_cache, _basePath, link));
	}

	@Override
	public void init() throws ServletException {
		super.init();

		File repository = new File(_basePath.toFile(), ".upload");
		repository.mkdirs();

		_fileUpload = new JakartaServletFileUpload<UploadItem, UploadFactory>(new UploadFactory(repository));
	}

	/**
	 * Gives the directory watcher of the {@link ResourceCache} back to the operating system.
	 *
	 * <p>
	 * A server holds one servlet and one cache, but a test builds many; without this, every one of
	 * them would keep an <code>inotify</code> instance until the process ends.
	 * </p>
	 */
	@Override
	public void destroy() {
		Inboxes.unregister(_basePath);
		_videos.shutdown();
		_index.shutdown();
		_faces.shutdown();
		_content.close();
		_reanalysis.shutdown();
		_searchIndex.close();
		if (_placesStep != null) {
			_placesStep.close();
		}
		// After every user of it: the background is down, and the search hears of no write more.
		_spaceIndex.close();
		try {
			_cache.close();
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot close the resource cache: " + ex.getMessage(), ex);
		}
		super.destroy();
	}

	/**
	 * The status a request touching a folder whose own <code>index.json</code> cannot be read is
	 * refused with, see issue #254 and {@link ResourceCache.UnreadableSidecar}: the request is
	 * understood, but the folder it addresses cannot be processed until somebody repairs or removes
	 * the file.
	 */
	public static final int SC_UNREADABLE_SIDECAR = 422;

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			serveGet(request, response);
		} catch (ResourceCache.UnreadableSidecar ex) {
			refuseUnreadable(request, response, ex);
		}
	}

	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			servePut(request, response);
		} catch (ResourceCache.UnreadableSidecar ex) {
			refuseUnreadable(request, response, ex);
		} finally {
			// What this request wrote is in the search before it returns, see SearchIndex.
			_searchIndex.writeThrough();
		}
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try {
			servePost(request, response);
		} catch (ResourceCache.UnreadableSidecar ex) {
			refuseUnreadable(request, response, ex);
		} finally {
			// What this request wrote is in the search before it returns, see SearchIndex.
			_searchIndex.writeThrough();
		}
	}

	/**
	 * Answers a request that touched a folder whose own <code>index.json</code> cannot be read, see
	 * issue #254: a refusal saying so, never a <code>500</code> and never an album made up from the
	 * files, which the next write would store over what the user wrote. The file is left as it is.
	 */
	private static void refuseUnreadable(HttpServletRequest request, HttpServletResponse response,
			ResourceCache.UnreadableSidecar ex) throws IOException {
		LOG.warning("Refusing '" + request.getMethod() + " " + request.getPathInfo() + "': the sidecar of '"
			+ ex.getFolder() + "' cannot be read: " + ex.getCause().getMessage());
		if (response.isCommitted()) {
			// Nothing can be said any more; the connection tells the client.
			throw ex;
		}
		response.reset();
		errorInfo(new Context(request, response), SC_UNREADABLE_SIDECAR, ex.getMessage());
	}

	private void serveGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		Context context = new Context(request, response);

		String type = context.getParameter("type");

		if (ZIP_ACTION.equals(context.getParameter("action"))) {
			// The browser's own fetch of a download ticket, see issue #209: who asks is the ticket's
			// business, never the bearer's.
			serveZipTicket(context);
			return;
		}

		Caller caller = _auth.caller(request);
		String media = context.getParameter(MediaSignatures.PARAMETER);
		if (media != null) {
			// A signed media address of issue #185: the signature decides who asks, a bearer beside
			// it is ignored, and it opens exactly the one file it was made for -- every other type
			// is no kind it can be made for and fails the check.
			try {
				caller = _auth.mediaCaller(media, pathInfo == null ? "" : pathInfo,
					MediaSignatures.Kind.ofType(type));
			} catch (AuthService.Refused ex) {
				LOG.warning("Refusing the media address of '" + pathInfo + "': " + ex.getMessage());
				errorInfo(context, ex.getStatus(), ex.getMessage());
				return;
			}
			// The address is a credential for ten minutes: no shared cache keeps what it opened.
			response.setHeader("Cache-Control", "private, no-store");
		}
		if (gone(context, caller)) {
			return;
		}
		if ("auth".equals(type)) {
			if (caller.getInvitationGone() != null) {
				// The one endpoint an invitation token reaches, and the one place it is told what
				// became of the invitation instead of simply being nobody, see issue #52.
				LOG.warning("Refusing the invitation at '" + pathInfo + "': " + caller.getInvitationGone());
				errorInfo(context, HttpServletResponse.SC_GONE, caller.getInvitationGone());
				return;
			}
			// Always answerable: this is how an unpaired app learns that it must pair. The map
			// template of the space rides along, see issue #112.
			AuthInfo info = _auth.authInfo(caller, _basePath, _space).setMapUrl(_mapUrl).setFaces(_faces.isEnabled());
			// Where the inbox is and how much waits there, for whoever may put something in it,
			// so that the app never guesses, see issue #226.
			answerInbox(info, caller);
			if (info.getShare() != null && info.getShare().getContact() != null) {
				// A recognised contact may add an address, where the server can mail a code (#199);
				// whether they have one already decides whether the app offers it (#211).
				info.getShare().setMethods(addressProof().contactMethods())
					.setContactHasEmail(AddressProof.hasEmail(caller.getContact()))
					// "Also signed in on n other browsers", see issue #203.
					.setOtherSessions(_auth.getContacts().otherSessions(caller.getContact().getId(),
						caller.getSession() == null ? "" : caller.getSession().getId()))
					// How else they are recognised on another browser, see issue #208.
					.setSignIns(SignInActions.signIns(caller.getContact(), _passkeys));
			}
			if (caller.isPaired()) {
				// What the share dialog may offer a link that is proven by an address (#211).
				info.setProofMethods(addressProof().contactMethods());
			}
			// How a member signs a new browser in besides a code (issue #233): the sign-in form
			// offers exactly these.
			info.setSignInMethods(memberSignInMethods());
			serveJsonObject(response, info);
			return;
		}
		if (SharedOverview.TYPE.equals(type)) {
			// Everything shared with a contact, see issue #267.
			new SharedOverview(this, _auth, _basePath).serve(context, caller);
			return;
		}
		if (caller.isOverview()) {
			// The overview opens no album itself; each of its albums is opened as its link (#267).
			LOG.warning("Refusing '" + pathInfo + "' to the overview of what is shared.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.OVERVIEW_ONLY);
			return;
		}
		if ("invitations".equals(type)) {
			serveInvitations(context, caller);
			return;
		}
		if ("users".equals(type)) {
			serveUsers(context, caller);
			return;
		}
		if ("groups".equals(type)) {
			retired(context, RETIRED_GROUPS);
			return;
		}
		if ("grants".equals(type)) {
			retired(context, RETIRED_GRANTS);
			return;
		}
		if ("links".equals(type)) {
			retired(context, RETIRED_LINKS);
			return;
		}
		if ("devices".equals(type)) {
			serveDevices(context, caller);
			return;
		}
		if (SpaceConfiguration.TYPE.equals(type)) {
			_settings.serve(context, caller);
			return;
		}
		if ("people".equals(type)) {
			servePeople(context, caller);
			return;
		}
		if ("contacts".equals(type)) {
			serveContacts(context, caller);
			return;
		}
		if (DUPLICATES_TYPE.equals(type)) {
			serveDuplicates(context, caller);
			return;
		}
		if (CATCH_UP_TYPE.equals(type)) {
			serveCatchUp(context, caller);
			return;
		}
		if (NEWS_TYPE.equals(type) || NEWS_COUNT_TYPE.equals(type)) {
			serveNews(context, caller, NEWS_COUNT_TYPE.equals(type));
			return;
		}

		int viewAs;
		try {
			viewAs = Privacy.viewAs(context.getParameter(Privacy.VIEW_AS_PARAMETER));
		} catch (IllegalArgumentException ex) {
			LOG.warning("Rejecting the unknown 'viewAs' value '" + ex.getMessage() + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, VIEW_AS_REFUSED);
			return;
		}

		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo resourcePath = location.getPath();

		if ("shares".equals(type)) {
			serveShares(context, caller, location);
			return;
		}
		if (SPACE_SHARES_TYPE.equals(type)) {
			serveSpaceShares(context, caller, location);
			return;
		}
		if ("reanalyze".equals(type)) {
			serveReanalysis(context, caller, resourcePath);
			return;
		}
		if (SEARCH_OPTIONS_TYPE.equals(type)) {
			serveSearchOptions(context, caller, resourcePath);
			return;
		}
		if (FOLDERS_TYPE.equals(type)) {
			serveFolderMatches(context, caller, resourcePath);
			return;
		}

		// A caller the mode shuts out and no grant lets in is told so before the disk is touched:
		// what lies at the path is not their business, not even whether anything does.
		if (!_auth.readAllowed(caller) && _auth.rights(caller, resourcePath).isEmpty()) {
			unauthorized(context, caller, false);
			return;
		}

		File file = resourcePath.toFile();
		// The inbox before its first upload is an empty inbox, not an address naming nothing, see
		// issue #226; reading it writes nothing.
		boolean unmadeInbox = !file.exists() && Inboxes.isInbox(file);
		// A photograph of a collection is addressed in the collection and served from the album it
		// lies in, see issue #221. What the caller may do is asked here, at the collection; what
		// they may see of the photograph below, at its own album.
		boolean collected = false;
		if (!file.exists() && !unmadeInbox && !resourcePath.isRoot()
			&& resourcePath.parent().toFile().isDirectory()) {
			Resource container = _cache.lookup(resourcePath.parent());
			ImagePart reference = PhotoCollections.isCollection(container)
				? PhotoCollections.referenceNamed((AlbumInfo) container, resourcePath.getName()) : null;
			if (reference != null) {
				// The label a link shows is the collection's own (issue #213), asked here.
				if (!labelShows(caller, resourcePath)) {
					LOG.warning("Hiding a photograph without the label of the link at '" + pathInfo + "'.");
					errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Labels.NOT_FOUND);
					return;
				}
				PathInfo source = _collections.locate(reference.getRef());
				if (source == null) {
					LOG.warning("The photograph of '" + pathInfo + "' is gone from the space.");
					errorInfo(context, HttpServletResponse.SC_NOT_FOUND, PhotoCollections.MISSING);
					return;
				}
				resourcePath = source;
				file = source.toFile();
				collected = true;
			}
		}
		if (!file.exists() && !unmadeInbox && !collected) {
			// A photograph of a saved search is addressed by its path below the saved search and
			// served from the album it lies in while it matches, see issue #227. Its labels are its
			// own, so a link's label is asked of it below, as of any photograph.
			PathInfo searched = searchedPhoto(context, resourcePath);
			if (searched == ANSWERED) {
				return;
			}
			if (searched != null) {
				resourcePath = searched;
				file = searched.toFile();
			}
		}
		if (!file.exists() && !unmadeInbox) {
			// Nothing is malformed about an address naming nothing: it is not found, and the
			// answer says which segment is missing, see issue #176.
			notFound(context, resourcePath);
			return;
		}

		if (file.isDirectory() || unmadeInbox) {
			if (media != null) {
				// Never issued for a folder; a folder that took a video's place is not opened by it.
				errorInfo(context, HttpServletResponse.SC_UNAUTHORIZED, AuthService.MEDIA_REFUSED);
				return;
			}
			if (type != null && !"json".equals(type) && !IMAGE_COUNT_TYPE.equals(type)) {
				// A folder is answered as JSON and nothing else (and counted, see
				// IMAGE_COUNT_TYPE); any other type is a request this server does not understand,
				// not a folder that is missing, see issue #176.
				LOG.warning("Rejecting the unknown type '" + type + "' at '" + pathInfo + "'.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, unknownType(type));
				return;
			}
			// The "view as" of the request survives the redirect, or the preview would jump back.
			String query = "/?type=" + type + viewAsQuery(context);
			if (pathInfo == null) {
				sendRedirect(response, request.getContextPath() + request.getServletPath() + query);
				return;
			}
			if (!pathInfo.endsWith("/")) {
				sendRedirect(response, request.getContextPath() + request.getServletPath() + pathInfo + query);
				return;
			}

			if (!_auth.mayView(caller, resourcePath)) {
				refuse(context, caller, resourcePath, Rights.VIEW, false);
				return;
			}
				// "View as" only ever lowers: it is safe for anybody to send, see Privacy#viewAs(String).
			int clearance = Math.min(_auth.clearance(caller, resourcePath), viewAs);
			if (IMAGE_COUNT_TYPE.equals(type)) {
				serveImageCount(context, resourcePath, clearance, viewAs, caller);
				return;
			}
			serveFolder(context, resourcePath, clearance, viewAs, caller);
		} else if (ResourceCache.isImage(file)) {
			// An image of an inbox is answered by the rule the inbox is answered by: one the
			// caller may not see is not there for them, whichever of its shapes they ask for --
			// its description, its thumbnail, its rendition or the original, see issue #131.
			PathInfo folderPath = resourcePath.parent();
			Resource folder = _cache.lookup(folderPath);
			if (Inboxes.isInbox(folder)) {
				Resource image = _cache.lookup(resourcePath);
				Inboxes.Visibility inbox = Inboxes.visibility(_auth, caller, folderPath, viewAs);
				if (!(image instanceof ImagePart)
					|| !Inboxes.shows(inbox, (ImagePart) image, caller.subject())) {
					LOG.warning("Hiding the image of an inbox at '" + pathInfo + "'.");
					errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Inboxes.NOT_FOUND);
					return;
				}
			}
			// A photograph without the label a link shows is not there for the link, whichever of
			// its shapes is asked for -- exactly as one of an inbox, see issue #213.
			if (!collected && !labelShows(caller, resourcePath)) {
				LOG.warning("Hiding a photograph without the label of the link at '" + pathInfo + "'.");
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Labels.NOT_FOUND);
				return;
			}

			if (MEDIA_URL_TYPE.equals(type)) {
				issueMediaUrl(context, caller, resourcePath, viewAs);
				return;
			}

			// The description and the thumbnail are looking; the original file is taking a copy.
			// A rendition is looking, exactly as the thumbnail is; only the original is taking a copy.
			// A face crop is looking at a piece of the thumbnail, so it asks for what the
			// thumbnail asks for, see issue #124.
			String right = jsonRequested(context) || "tn".equals(type) || FACE_TYPE.equals(type)
				|| VideoRenditions.Kind.PLAYBACK.parameter().equals(type)
				|| VideoRenditions.Kind.TEASER.parameter().equals(type) ? Rights.VIEW : Rights.DOWNLOAD;
			if (!_auth.rights(caller, resourcePath).contains(right)) {
				refuse(context, caller, resourcePath, right, false);
				return;
			}
			if (media != null && type == null && DOWNLOAD_PARAMETER_VALUE.equals(context.getParameter(DOWNLOAD_PARAMETER))) {
				// The browser's own download of one original, see issue #209: saved, not shown, and
				// under its own name wherever the page that asked lives.
				response.setHeader("Content-Disposition", ZipDownload.attachment(file.getName()));
			}
			int clearance = Math.min(_auth.clearance(caller, resourcePath), viewAs);
			serveImage(context, resourcePath, caller, clearance, _auth.minRating(caller, resourcePath, viewAs), viewAs);
		} else {
			// A file that is no photograph is no resource of this server: not found, as above.
			notFound(context, resourcePath);
		}
	}

	/**
	 * The message an address naming no folder or file is answered with, see issue #176.
	 *
	 * @param segment
	 *        The first segment of the address that names nothing.
	 */
	public static String notFound(String segment) {
		return "There is no album, folder or file '" + segment + "' here.";
	}

	/** The message a request for a folder in a form this server does not know is refused with. */
	public static String unknownType(String type) {
		return "A folder cannot be answered as '" + type + "'; ask for '?type=json'.";
	}

	/**
	 * Answers <code>404</code> with an {@link ErrorInfo} naming the first segment of the given path
	 * that does not exist (or, where every folder above exists, the path's own name).
	 */
	private static void notFound(Context context, PathInfo path) throws IOException {
		File missing = path.toFile();
		File root = path.getBasePath().toFile();
		while (true) {
			File parent = missing.getParentFile();
			if (parent == null || parent.equals(root) || parent.exists()) {
				break;
			}
			missing = parent;
		}
		LOG.warning("Not found: '" + context.request().getPathInfo() + "' (at '" + missing.getName() + "').");
		errorInfo(context, HttpServletResponse.SC_NOT_FOUND, notFound(missing.getName()));
	}

	/** The <code>viewAs</code> parameter of the current request, ready to be appended to a URL. */
	private static String viewAsQuery(Context context) {
		String value = context.getParameter(Privacy.VIEW_AS_PARAMETER);
		return value == null ? "" : "&" + Privacy.VIEW_AS_PARAMETER + "=" + value;
	}

	private void sendRedirect(HttpServletResponse response, String location) throws IOException {
		LOG.log(Level.INFO, "Redirecting to: " + location);

		response.setHeader("Access-Control-Allow-Origin", "*");
		response.sendRedirect(location);
	}

	private void servePut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		Context context = new Context(request, response);

		Caller caller = _auth.caller(request);
		if (gone(context, caller)) {
			return;
		}
		if (inboxHiddenFromLink(context, caller)) {
			return;
		}
		if (refusedFolderName(context, caller)) {
			return;
		}
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo resourcePath = location.getPath();

		// Whether this caller may change anything here at all; which right in particular is asked
		// for below, where it is known what the request wants to do.
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, resourcePath)) {
			unauthorized(context, caller, true);
			return;
		}

		String contentType = context.request().getContentType();
		if (contentType == null) {
			LOG.warning("Missing content type in PUT to '" + pathInfo + "'.");
			error(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
			return;
		}
		MimeType mimeType;
		try {
			mimeType = new MimeType(contentType);
		} catch (MimeTypeParseException ex) {
			LOG.warning("Invalid content type: " + contentType);
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		String baseType = mimeType.getBaseType();

		File file = resourcePath.toFile();
		if (!baseType.equals("application/json") && !makeInbox(context, caller, resourcePath)) {
			return;
		}
		if (!file.isDirectory()) {
			File parent = file.getParentFile();
			if (parent == null || !parent.isDirectory()
				|| (pathInfo != null && pathInfo.endsWith("/") && !baseType.equals("application/json"))) {
				// No folder to store into: an address naming nothing, answered as a read answers
				// it, see issues #176 and #215. A folder address is never a file name to upload to.
				notFound(context, resourcePath);
				return;
			}
			PathInfo folder = resourcePath.parent();

			if (baseType.equals("application/json")) {
				if (file.exists()) {
					// A file is not a folder; its sidecar belongs to the folder around it.
					error(context, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
					return;
				}
				// Creating an album changes the folder it lands in.
				if (!_auth.mayEdit(caller, folder)) {
					refuse(context, caller, folder, Rights.EDIT, true);
					return;
				}
				if (PhotoCollections.isCollection(folder.toFile())) {
					errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoCollections.FOLDER_REFUSED);
					return;
				}
				if (PhotoSearch.isSearch(folder.toFile())) {
					errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoSearch.FOLDER_REFUSED);
					return;
				}
				createAlbum(context, location, resourcePath);
				return;
			}

			if (!_auth.mayContribute(caller, folder)) {
				refuse(context, caller, folder, Rights.CONTRIBUTE, true);
				return;
			}
			if (PhotoCollections.isCollection(folder.toFile())) {
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoCollections.UPLOAD_REFUSED);
				return;
			}
			if (PhotoSearch.isSearch(folder.toFile())) {
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoSearch.UPLOAD_REFUSED);
				return;
			}
			// Nothing lands in a folder whose sidecar cannot be read, see issue #254.
			ResourceCache.checkSidecar(folder.toFile());
			storeSingleImage(context, caller, folder, file);
			return;
		}

		if (baseType.equals("multipart/form-data")) {
			if (!_auth.mayContribute(caller, resourcePath)) {
				refuse(context, caller, resourcePath, Rights.CONTRIBUTE, true);
				return;
			}
			if (PhotoCollections.isCollection(file)) {
				// A collection holds references, never files, see issue #221.
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoCollections.UPLOAD_REFUSED);
				return;
			}
			if (PhotoSearch.isSearch(file)) {
				// A saved search holds a query, never files, see issue #227.
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoSearch.UPLOAD_REFUSED);
				return;
			}
			// Nothing lands in a folder whose sidecar cannot be read, see issue #254.
			ResourceCache.checkSidecar(file);
			storeUploads(context, caller, resourcePath);
			return;
		}

		if (!baseType.equals("application/json")) {
			LOG.warning("Unsupported content type: " + contentType);
			error(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
			return;
		}

		if (!_auth.mayEdit(caller, resourcePath)) {
			refuse(context, caller, resourcePath, Rights.EDIT, true);
			return;
		}
		storeFolder(context, location, resourcePath);
	}

	/**
	 * Refuses a sidecar PUT whose last segment is a name no folder of the library may have, see
	 * issue #173.
	 *
	 * <p>
	 * Creating an album is a PUT of its sidecar to the path it is to have, and a folder named
	 * <code>@eaDir</code> or <code>#recycle</code> (or <code>.thing</code>) would vanish from the
	 * library the moment it was made, see {@link LibraryFiles}. {@link AuthService} refuses such an
	 * address as it refuses any address reaching into the server's own folders, with a
	 * <code>404</code> that tells the one who typed the title nothing. So the folder above is
	 * resolved instead, asked for the right a creation needs, and the name is refused as the rename
	 * of issue #130 refuses it: <code>400</code>, {@link FolderNames#illegalName(String)}, nothing
	 * written. Every other request naming such a segment is left to the ordinary refusal.
	 * </p>
	 *
	 * @return Whether the request has been answered.
	 */
	private boolean refusedFolderName(Context context, Caller caller) throws IOException {
		HttpServletRequest request = context.request();
		String contentType = request.getContentType();
		if (contentType == null || !contentType.trim().toLowerCase(Locale.ROOT).startsWith("application/json")) {
			return false;
		}
		String pathInfo = request.getPathInfo();
		if (pathInfo == null) {
			return false;
		}
		String trimmed = pathInfo.endsWith("/") ? pathInfo.substring(0, pathInfo.length() - 1) : pathInfo;
		int slash = trimmed.lastIndexOf('/');
		if (slash < 0) {
			return false;
		}
		String name = trimmed.substring(slash + 1);
		if (name.equals(".") || name.equals("..") || !LibraryFiles.isIgnored(name)) {
			return false;
		}
		Location parent;
		try {
			parent = _auth.resolve(caller, _basePath, slash == 0 ? "" : trimmed.substring(1, slash));
		} catch (PathRefused ex) {
			// The folder above is no place either: the ordinary refusal of the whole path.
			return false;
		}
		PathInfo folder = parent.getPath();
		if (!folder.toFile().isDirectory()) {
			return false;
		}
		if (!_auth.mayEdit(caller, folder)) {
			refuse(context, caller, folder, Rights.EDIT, true);
			return true;
		}
		LOG.warning("Refusing to create the folder '" + name + "' in '" + folder.toFile() + "'.");
		errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, FolderNames.illegalName(name));
		return true;
	}

	/**
	 * Resolves the path of the current request against a user's space, the one place it happens.
	 *
	 * <p>
	 * The data root is a folder like any other: an empty path (<code>PUT /data</code> and
	 * <code>PUT /data/</code>) addresses the space root itself and must be able to store its
	 * <code>index.json</code>.
	 * </p>
	 *
	 * <p>
	 * A path whose first segment starts with <code>~</code> addresses another user's space, every
	 * other path the caller's own, see {@link AuthService#resolve(Caller, Path, String)}. Reaching
	 * a path is never a permission to do anything with it: the rights are asked for separately, on
	 * every endpoint.
	 * </p>
	 *
	 * @return <code>null</code> if the path cannot be resolved; the response is completed with a
	 *         speaking refusal in that case.
	 */
	private Location resolve(Context context, Caller caller) throws IOException {
		String pathInfo = context.request().getPathInfo();
		String relativePath = pathInfo == null || pathInfo.isEmpty() ? "" : pathInfo.substring(1);
		try {
			return _auth.resolve(caller, _basePath, relativePath);
		} catch (PathRefused ex) {
			LOG.warning("Refusing the path '" + pathInfo + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return null;
		}
	}

	/**
	 * Refuses the request for want of a right, naming the right that is missing.
	 *
	 * <p>
	 * An anonymous caller is answered with <code>401</code> and the challenge that says how to get
	 * further; a signed-in caller with <code>403</code> and the right they do not hold, because
	 * signing in again would not help them. Nothing declines silently, see
	 * {@link AuthService#refusal(Caller, String, boolean)}.
	 * </p>
	 */
	private void refuse(Context context, Caller caller, PathInfo path, String right, boolean write)
			throws IOException {
		String message = _auth.refusal(caller, right, write);
		LOG.warning("Refusing '" + right + "' on '" + context.request().getPathInfo() + "': " + message);
		if (identified(caller)) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, message);
		} else {
			context.response().setHeader("WWW-Authenticate", "Bearer");
			errorInfo(context, HttpServletResponse.SC_UNAUTHORIZED, message);
		}
	}

	/**
	 * Creates a new album folder with the request body as its <code>index.json</code>.
	 *
	 * <p>
	 * The folder the client asks for is not necessarily the folder the album ends up in: when the
	 * folder above carries a placement rule, the album is filed into its year (or month) folder,
	 * see issue #48. The answer is a {@link CreateResult} naming the path the album really has, so
	 * that the client can go there instead of looking where it is not — spelled in the coordinates
	 * of the request that asked, the canonical form of another user's library included, see
	 * {@link Location#spell(String)}.
	 * </p>
	 */
	private void createAlbum(Context context, Location location, PathInfo resourcePath) throws IOException {
		byte[] contents = readBody(context.request());
		FolderResource resource = checkFolderResource(context, contents);
		if (resource == null) {
			return;
		}

		File asked = resourcePath.toFile();
		File parent = asked.getParentFile();
		String name = asked.getName();
		if (PhotoCollections.isCollection(resource)) {
			// A collection starts empty: what it references is added by ?action=collect, which
			// asks what the caller may see, never by the sidecar a client writes, see issue #221.
			AlbumInfo collection = (AlbumInfo) resource;
			collection.setParts(new ArrayList<>());
			collection.setIndexPicture(null);
			contents = sidecarOf(collection);
		} else if (PhotoSearch.isSearch(resource)) {
			// A saved search stores its query and nothing else of the photographs, see issue #227
			// -- but the picture its tile shows, chosen below where it is filed, see issue #287.
			AlbumInfo search = (AlbumInfo) resource;
			String refusal = PhotoSearch.refusal(search.getQuery());
			if (refusal != null) {
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, refusal);
				return;
			}
			search.setQuery(PhotoSearch.normalized(search.getQuery()));
			search.setParts(new ArrayList<>());
			search.setIndexPicture(null);
			contents = sidecarOf(search);
		} else if (resource instanceof AlbumInfo && ((AlbumInfo) resource).getQuery() != null) {
			// Only a saved search has a query.
			((AlbumInfo) resource).setQuery(null);
			contents = sidecarOf(resource);
		}

		// The album is filed by what is written on it: the date the request carries, else the date
		// in the folder name it asks for.
		PlacementRule rule = PlacementRule.of(parent);
		File placement;
		try {
			placement = PlacementRule.isPlacementFolder(name) ? null
				: rule.create(parent, AlbumDate.ofFolder(resource, name));
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot file the new album '" + name + "': " + ex.getMessage(), ex);
			errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.getMessage());
			return;
		}

		PathInfo createdPath = resourcePath;
		String message = "";
		if (placement != null) {
			PathInfo folderPath = resourcePath.parent();
			for (Path segment : parent.toPath().relativize(placement.toPath())) {
				folderPath = folderPath.child(segment.toString());
			}
			createdPath = folderPath.child(name);
			message = PlacementRule.filedIn(relative(parent, placement));
		}

		if (PhotoSearch.isSearch(resource)) {
			// The first match its creator is shown, in the order the search answers: what its tile
			// shows from now on, without a listing ever running it, see issue #287. Asked where the
			// search is really filed, for that is the folder it looks below; a search that finds
			// nothing stores none, and an editor's opening stores one once it finds something.
			AlbumInfo search = (AlbumInfo) resource;
			// Slim: no faces derived for a picture.
			List<ImagePart> first = searchMatches(searchScope(createdPath), search.getQuery(),
				_auth.caller(context.request()), Privacy.PRIVATE, null, true);
			if (!first.isEmpty()) {
				search.setIndexPicture(PrivacyFilter.thumbnail(first.get(0)));
				contents = sidecarOf(search);
			}
		}

		File folder = createdPath.toFile();
		if (folder.exists()) {
			LOG.warning("Refusing to create the album '" + folder.getAbsolutePath() + "': the name is taken.");
			errorInfo(context, HttpServletResponse.SC_CONFLICT, MoveService.nameTaken(name));
			return;
		}
		if (!folder.mkdirs()) {
			LOG.warning("Cannot create path: " + folder.getAbsolutePath());
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		try (FileOutputStream out = new FileOutputStream(new File(folder, "index.json"))) {
			out.write(stored(contents, resource));
		}

		// The listing above shows the new album, and it may show a new year folder, too.
		_cache.invalidateTree(resourcePath.parent());

		LOG.info("Created album: " + folder.getAbsolutePath());
		serveJsonObject(context.response(),
			CreateResult.create().setPath(location.spell(createdPath.relativePath())).setMessage(message));
	}

	/** The path of the given folder below the given one, with <code>/</code> as separator. */
	private static String relative(File folder, File descendant) {
		return folder.toPath().relativize(descendant.toPath()).toString().replace(File.separatorChar, '/');
	}

	/**
	 * Stores a single uploaded image at the requested path.
	 *
	 * <p>
	 * Originals are sacred, so this never replaces anything. The received contents are hashed: if
	 * the folder already holds them, nothing is written and the answer names the file that has
	 * them; if the requested name is taken by <em>different</em> contents, the request is refused
	 * with a <code>409</code>, see {@link #REPLACE_REFUSED}.
	 * </p>
	 */
	private void storeSingleImage(Context context, Caller caller, PathInfo folderPath, File target)
			throws IOException {
		String name = target.getName();
		String refusal = uploadRefusal(name);
		if (refusal != null) {
			LOG.warning("Refusing the upload of '" + name + "': " + refusal);
			errorInfo(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, refusal);
			return;
		}

		List<UploadItem> uploads = _fileUpload.parseRequest(context.request());
		if (uploads.size() != 1) {
			LOG.warning("Tried to upload multiple files to a single image location " + name + ": "
				+ uploads.stream().map(u -> u.getName()).collect(Collectors.joining(", ")));
			discard(uploads);
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		UploadItem upload = uploads.get(0);
		File folder = target.getParentFile();
		HashCache hashes = new HashCache(folder);
		UploadResult result;
		List<File> stored = Collections.emptyList();
		try {
			String hash = HashCache.sha256(upload.getUpload());
			String existing = hashes.nameOf(hash);
			if (existing != null) {
				upload.delete();
				LOG.info("Upload of '" + name + "' is already present as '" + existing + "'.");
				result = UploadResult.create().addFile(uploaded(name, existing, hash, PRESENT));
			} else if (target.exists()) {
				upload.delete();
				LOG.warning("Refusing to replace '" + target.getAbsolutePath() + "' with other contents.");
				errorInfo(context, HttpServletResponse.SC_CONFLICT, REPLACE_REFUSED);
				return;
			} else {
				store(upload, target);
				hashes.put(target, hash, attribution(caller, folderPath));
				LOG.info("Storing image: " + target);
				result = UploadResult.create().addFile(uploaded(name, name, hash, STORED));
				stored = Collections.singletonList(target);
			}
		} finally {
			hashes.flush();
		}

		// What was stored is analysed now, so that the album shows it with the next read, see #253.
		recordUploads(folder, stored);

		// The next read must see the new photo, and with it who contributed it.
		_cache.invalidate(folderPath);

		serveJsonObject(context.response(), result);
	}

	/**
	 * Records the given files, just uploaded into the given folder, see issue #253: the upload
	 * request analyses what it brought (it is a write, never a read of an album), so that the album
	 * shows it with the next read instead of after the background's quiet period. A file that cannot
	 * be read is recorded as broken. Then hands them to the fast lane of the background, see issue
	 * #258.
	 */
	private void recordUploads(File folder, List<File> stored) {
		if (stored.isEmpty()) {
			return;
		}
		try {
			AnalysisRecord.add(folder, stored, _cache.analyst());
		} catch (IOException | RuntimeException ex) {
			// The fast lane records them first.
			LOG.log(Level.WARNING, "Cannot record the upload into '" + folder + "': " + ex.getMessage(), ex);
		}
		// And the background makes their thumbnails at once, ahead of its catch-up and without the
		// quiet period of the watch, see issue #258.
		de.haumacher.imageServer.pipeline.FolderPipeline.uploaded(folder, stored);
	}

	/**
	 * Why a file of the given name is not taken into the library, <code>null</code> when it is.
	 *
	 * <p>
	 * A format the library does not hold (see {@link PreviewCache#SUPPORTED_EXTENSIONS}), and a
	 * hidden name or the name of another system's litter (a Mac's <code>._IMG_1.jpg</code>), which
	 * would be stored and never shown, see {@link LibraryFiles}. The sentence names the file, so
	 * that an app showing it needs nothing else to say which.
	 * </p>
	 */
	static String uploadRefusal(String name) {
		if (LibraryFiles.isIgnored(name)) {
			return unsupportedName(name);
		}
		if (!PreviewCache.SUPPORTED_EXTENSIONS.contains(extension(name))) {
			return unsupportedFormat(name);
		}
		return null;
	}

	/** The refusal of an upload in a format the library does not hold, see issue #186. */
	public static String unsupportedFormat(String name) {
		return "'" + name + "' was not uploaded: its format is not supported (JPEG, PNG, WebP, GIF, HEIC/HEIF, AVIF, JPEG XL, the raw formats DNG, CR2, CR3, NEF, ARW, ORF, RW2 and RAF, MP4, MOV, M4V, 3GP, MTS/M2TS, AVI, MKV and WebM are).";
	}

	/** The refusal of an upload whose name the library never shows, see issue #173. */
	public static String unsupportedName(String name) {
		return "'" + name + "' was not uploaded: a hidden file or the litter of another system is no photograph.";
	}

	/**
	 * Who to record as the contributor of what the given caller uploads, see issue #53.
	 *
	 * <p>
	 * The caller's {@link Caller#subject() subject} — the very string a grant is made out to — and
	 * the label to show for it. Both are copied into the hash sidecar at the upload and never
	 * looked up again: a share link that is renamed or deleted afterwards still says who
	 * contributed, and a user who is renamed keeps what they brought.
	 * </p>
	 *
	 * <p>
	 * An upload through a share link — an anonymous one or a personal one of issue #198 — also
	 * records the limits that link shows at the target folder, its {@link AuthService#minRating
	 * rating floor} and its {@link AuthService#clearance privacy level}, so that the photograph is
	 * described as the link shows it, see issue #214 and
	 * {@link Contributors#applyLinkLimits(List, File)}, and the label it shows, so that the
	 * photograph carries it (issue #213). A member's upload records none.
	 * </p>
	 */
	private HashCache.Attribution attribution(Caller caller, PathInfo folderPath) {
		if (caller == null) {
			return HashCache.Attribution.NONE;
		}
		// When it arrived, for the bell of the others, see issue #268.
		long now = System.currentTimeMillis();
		if (caller.isShareLink()) {
			return new HashCache.Attribution(caller.subject(), caller.contributorLabel(),
				_auth.minRating(caller, folderPath, Privacy.PRIVATE), _auth.clearance(caller, folderPath),
				_auth.photoLabel(caller),
				// The personal link a contact came through, counted for its owner (issue #203).
				caller.getContact() != null ? caller.getShare().getId() : "").uploadedAt(now);
		}
		return new HashCache.Attribution(caller.subject(), caller.contributorLabel()).uploadedAt(now);
	}


	/**
	 * Stores the files of a multipart upload in the given folder.
	 *
	 * <p>
	 * Every received file is hashed and compared with the contents the folder already holds: a
	 * duplicate is dropped and reported as {@link #PRESENT}, so that an upload retried after a
	 * lost connection never creates a second copy. Contents that are new are stored under a
	 * de-duplicated name; an existing file is never overwritten.
	 * </p>
	 *
	 * <p>
	 * This is where the attribution of issue #53 is recorded: every file that is actually stored
	 * gets the uploader's {@link Caller#subject() subject} and label in the hash sidecar beside
	 * it. A file reported as {@link #PRESENT} writes nothing, so it keeps the <em>first</em>
	 * contributor — whoever brought the photo here is who brought it here, however often it is
	 * sent again.
	 * </p>
	 *
	 * <p>
	 * A file the library does not take (see {@link #uploadRefusal(String)}) is answered in
	 * {@link UploadResult#getRefused()} and the rest of the request is stored, see issue #186; a
	 * request holding nothing but such files is refused with a <code>415</code> whose
	 * {@link ErrorInfo} names them.
	 * </p>
	 */
	private void storeUploads(Context context, Caller caller, PathInfo folderPath) throws IOException {
		File folder = folderPath.toFile();
		List<UploadItem> uploads = _fileUpload.parseRequest(context.request());

		// A file the library does not take is refused on its own; everything else is stored as if
		// it had come alone (issue #186). Only a request with nothing to store is refused whole.
		UploadResult result = UploadResult.create();
		List<UploadItem> accepted = new ArrayList<>();
		for (UploadItem upload : uploads) {
			String name = baseName(upload.getName());
			String refusal = uploadRefusal(name);
			if (refusal != null) {
				LOG.warning("Refusing the upload of '" + name + "': " + refusal);
				upload.delete();
				result.addRefused(RefusedFile.create().setName(name).setReason(refusal));
			} else {
				accepted.add(upload);
			}
		}
		if (accepted.isEmpty() && !result.getRefused().isEmpty()) {
			errorInfo(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
				result.getRefused().stream().map(RefusedFile::getReason).collect(Collectors.joining(" ")));
			return;
		}
		uploads = accepted;

		// A raw and the JPEG of its name sent together are one photograph, and are stored under one
		// free base name, so that a name taken by another photograph separates neither from the
		// other, see issue #191.
		List<String> batch = new ArrayList<>();
		for (UploadItem upload : uploads) {
			batch.add(baseName(upload.getName()));
		}
		Map<String, String> reserved = new HashMap<>();

		HashCache hashes = new HashCache(folder);
		List<File> stored = new ArrayList<>();
		try {
			for (UploadItem upload : uploads) {
				String name = baseName(upload.getName());
				String hash = HashCache.sha256(upload.getUpload());

				String existing = hashes.nameOf(hash);
				if (existing != null) {
					upload.delete();
					LOG.info("Upload of '" + name + "' is already present as '" + existing + "'.");
					result.addFile(uploaded(name, existing, hash, PRESENT));
					continue;
				}

				File targetFile = pairName(folder, name, batch, reserved);
				store(upload, targetFile);
				hashes.put(targetFile, hash, attribution(caller, folderPath));
				LOG.info("Storing image: " + targetFile);
				result.addFile(uploaded(name, targetFile.getName(), hash, STORED));
				stored.add(targetFile);
			}
		} finally {
			hashes.flush();
		}

		// What was stored is analysed now, so that the album shows it with the next read, see #253.
		recordUploads(folder, stored);

		// The next read must see the new photos, and with them who contributed them.
		_cache.invalidate(folderPath);

		serveJsonObject(context.response(), result);
	}

	/**
	 * Moves entries of the addressed folder into another folder, see issue #47.
	 *
	 * <p>
	 * A move is a write: it is refused exactly as a PUT is when the caller may not write, and the
	 * source and the target must both lie in the caller's space. Beyond that, the target folder
	 * must grant the contribute right, and the source folder the edit right — <em>or</em> the
	 * caller must be the contributor of every entry the request names, see issue #53 and
	 * {@link MoveService#contributedBy(PathInfo, List, String)}: whoever added a photo to somebody
	 * else's album may take it back out again, and nobody's else. Taking back is never a delete:
	 * the photo is renamed into a folder of the contributor's, exactly as any other move.
	 * </p>
	 *
	 * <p>
	 * A refusal that concerns the request as a whole is an {@link ErrorInfo}; a refusal that
	 * concerns one named entry is a {@link MoveOutcome#getMessage() message} in an otherwise
	 * successful answer, because the other entries did move.
	 * </p>
	 */
	private void moveEntries(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location sourceLocation = resolve(context, caller);
		if (sourceLocation == null) {
			return;
		}
		PathInfo source = sourceLocation.getPath();
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, source)) {
			unauthorized(context, caller, true);
			return;
		}

		MoveRequest moveRequest;
		try {
			byte[] contents = readBody(context.request());
			moveRequest = MoveRequest.readMoveRequest(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable move request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, MoveService.MOVE_UNREADABLE);
			return;
		}

		// The target is given in the same coordinates as any other path, the canonical form of
		// another user's space included: there is one way to name a folder.
		PathInfo target;
		try {
			target = _auth.resolve(caller, _basePath, moveRequest.getTarget() == null ? "" : moveRequest.getTarget())
				.getPath();
		} catch (PathRefused ex) {
			LOG.warning("Refusing the move target '" + moveRequest.getTarget() + "': " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND,
				AuthService.PATH_ESCAPED.equals(ex.getMessage()) ? MoveService.TARGET_ESCAPED : ex.getMessage());
			return;
		}

		List<String> names = moveRequest.getNames().stream().map(MoveName::getName).collect(Collectors.toList());
		// Who moves, recorded where photographs leave the inbox, see issue #268.
		MoveService moveService = new MoveService(_cache, _auth).movedBy(caller.subject(), caller.contributorLabel());

		if (PhotoCollections.isCollection(source.toFile()) || PhotoCollections.isCollection(target.toFile())) {
			// A collection holds references: nothing is moved into or out of it, see issue #221.
			LOG.warning("Refusing a move into or out of a collection at '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoCollections.MOVE_REFUSED);
			return;
		}
		if (PhotoSearch.isSearch(source.toFile()) || PhotoSearch.isSearch(target.toFile())) {
			// A saved search shows photographs that lie elsewhere, see issue #227.
			LOG.warning("Refusing a move into or out of a saved search at '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoSearch.MOVE_REFUSED);
			return;
		}

		// The source rule of issue #53: the edit right, or one's own contribution and nothing
		// else. A share link never gets that far — it has no album to take anything back into.
		if (!_auth.mayEdit(caller, source)) {
			if (caller.isShareLink()) {
				// Not the generic "this link does not allow changes here": the link may well
				// allow adding photos. What it has not got is anywhere to move one to.
				LOG.warning("Refusing the move through a share link at '" + context.request().getPathInfo()
					+ "': " + AuthService.SHARE_MOVE_REFUSED);
				errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARE_MOVE_REFUSED);
				return;
			}
			if (!moveService.contributedBy(source, names, caller.subject())) {
				refuseMove(context, caller, MoveService.CONTRIBUTION_REFUSED, true);
				return;
			}
		}
		if (!_auth.mayContribute(caller, target)) {
			refuseMove(context, caller, MoveService.CONTRIBUTE_REFUSED, true);
			return;
		}

		MoveResult result;
		try {
			// Only an album created for the move is given a cover, see issues #273 and #277.
			CreatedTarget created = moveRequest.getCreated();
			result = moveService.move(source, target, names, created != null,
				created == null ? null : created.getCover());
		} catch (MoveRefused ex) {
			LOG.warning("Refusing to move from '" + context.request().getPathInfo() + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}

		// A moved file announces itself through the sidecar it is written into; a moved *folder*
		// does not, so both trees are read again, see issue #118.
		treeChanged(source.toFile());
		treeChanged(target.toFile());

		serveJsonObject(context.response(), result);
	}

	/**
	 * Adds photographs of the addressed album (or collection) to a collection, see issue #221.
	 *
	 * <p>
	 * <code>POST &lt;album&gt;/?action=collect</code> with a {@link MoveRequest} whose target is the
	 * collection. It needs {@link Rights#EDIT} on the collection, and a name is added only where
	 * the caller is shown that photograph in its album &mdash; anything else is answered
	 * {@link #notInAlbum(String)} for that name, as a hidden photograph is not there for them. A
	 * photograph is identified by its content hash; one the collection already references is not
	 * added twice. A collection created for what arrives ({@link MoveRequest#getCreated()}) is
	 * covered by what arrived, as an album created for a move is, and adding never touches the
	 * cover of one that existed before (issue #277). Nothing on disk changes but the collection's sidecar (and the hash
	 * sidecar of an album whose photographs were not hashed yet).
	 * </p>
	 */
	private void collect(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo source = location.getPath();
		if (caller.isShareLink()) {
			LOG.warning("Refusing an addition to a collection through a share link at '"
				+ context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, PhotoCollections.COLLECT_REFUSED);
			return;
		}
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, source)) {
			unauthorized(context, caller, true);
			return;
		}
		MoveRequest request;
		try {
			request = MoveRequest.readMoveRequest(json(readBody(context.request())));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable collect request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoCollections.COLLECT_UNREADABLE);
			return;
		}
		PathInfo target;
		try {
			target = _auth.resolve(caller, _basePath, request.getTarget() == null ? "" : request.getTarget()).getPath();
		} catch (PathRefused ex) {
			LOG.warning("Refusing the collection '" + request.getTarget() + "': " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND,
				AuthService.PATH_ESCAPED.equals(ex.getMessage()) ? MoveService.TARGET_ESCAPED : ex.getMessage());
			return;
		}
		if (!_auth.mayEdit(caller, target)) {
			refuseMove(context, caller, _auth.refusal(caller, Rights.EDIT, true), true);
			return;
		}
		Resource collection = target.toFile().isDirectory() ? _cache.lookup(target) : null;
		if (!PhotoCollections.isCollection(collection)) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoCollections.NOT_A_COLLECTION);
			return;
		}
		Resource origin = source.toFile().isDirectory() ? _cache.lookup(source) : null;
		// A name of a saved search, and of the search view below a folder, is a path below the folder
		// searched, see issue #227.
		boolean fromSearch = PhotoSearch.isSearch(origin);
		boolean byPath = fromSearch;
		for (MoveName entry : request.getNames()) {
			byPath |= entry.getName() != null && entry.getName().indexOf('/') >= 0;
		}
		if (!(origin instanceof AlbumInfo || (byPath && origin instanceof ListingInfo))
			|| !_auth.mayView(caller, source)) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoCollections.NOT_AN_ALBUM);
			return;
		}
		if (fromSearch && PhotoSearch.refusal(((AlbumInfo) origin).getQuery()) != null) {
			errorInfo(context, HttpServletResponse.SC_NOT_IMPLEMENTED, PhotoSearch.refusal(((AlbumInfo) origin).getQuery()));
			return;
		}

		// What the caller is shown where the photographs come from.
		boolean fromCollection = PhotoCollections.isCollection(origin);
		AlbumInfo shown = byPath ? null
			: fromCollection ? resolveCollection((AlbumInfo) origin, source, caller, Privacy.PRIVATE)
				: sourceAlbum(source, caller, Privacy.PRIVATE);
		Map<String, String> hashes = null;
		Map<PathInfo, AlbumInfo> shownByFolder = new HashMap<>();
		Map<PathInfo, Map<String, String>> hashesByFolder = new HashMap<>();

		AlbumInfo stored = (AlbumInfo) collection;
		Map<String, String> nameByHash = new HashMap<>();
		Set<String> taken = new HashSet<>();
		for (ImagePart reference : PhotoCollections.references(stored)) {
			nameByHash.putIfAbsent(reference.getRef().getHash(), reference.getName());
			taken.add(reference.getName());
		}
		MoveResult result = MoveResult.create();
		List<ImagePart> added = new ArrayList<>();
		List<ImagePart> arrived = new ArrayList<>();
		// Where every name of the request is referenced now, see issue #277.
		Map<String, String> landedAs = new HashMap<>();
		for (MoveName entry : request.getNames()) {
			String name = entry.getName();
			MoveOutcome outcome = MoveOutcome.create().setName(name);
			result.addOutcome(outcome);
			ImagePart part;
			PathInfo photo = null;
			if (byPath) {
				photo = name == null ? null : photoNamed(source, origin, name);
				AlbumInfo album = null;
				if (photo != null) {
					PathInfo folder = photo.parent();
					if (shownByFolder.containsKey(folder)) {
						album = shownByFolder.get(folder);
					} else {
						album = folder.toFile().isDirectory() ? sourceAlbum(folder, caller, Privacy.PRIVATE) : null;
						shownByFolder.put(folder, album);
					}
				}
				part = album == null ? null : Crops.findImage(album, photo.getName());
			} else {
				part = shown == null || name == null ? null : Crops.findImage(shown, name);
			}
			if (part == null || part.isMissing()) {
				outcome.setMessage(notInAlbum(name));
				continue;
			}
			String hash;
			String path;
			if (photo != null) {
				PathInfo folder = photo.parent();
				Map<String, String> folderHashes = hashesByFolder.get(folder);
				if (folderHashes == null) {
					HashCache cache = new HashCache(folder.toFile());
					folderHashes = cache.hashByName();
					// The index hears of what was hashed only now, see issue #118.
					cache.flush();
					hashesByFolder.put(folder, folderHashes);
				}
				hash = folderHashes.get(photo.getName());
				if (hash == null) {
					outcome.setMessage(notInAlbum(name));
					continue;
				}
				path = _collections.relative(photo);
			} else if (fromCollection) {
				ImagePart reference = PhotoCollections.referenceNamed((AlbumInfo) origin, name);
				PathInfo located = reference == null ? null : _collections.locate(reference.getRef());
				if (located == null) {
					outcome.setMessage(PhotoCollections.MISSING);
					continue;
				}
				hash = reference.getRef().getHash();
				path = _collections.relative(located);
			} else {
				if (hashes == null) {
					HashCache cache = new HashCache(source.toFile());
					hashes = cache.hashByName();
					// The index hears of what was hashed only now, see issue #118.
					cache.flush();
				}
				hash = hashes.get(name);
				if (hash == null) {
					outcome.setMessage(notInAlbum(name));
					continue;
				}
				path = _collections.relative(source.child(name));
			}
			String present = nameByHash.get(hash);
			if (present != null) {
				outcome.setNewName(present).setMessage(PhotoCollections.alreadyCollected(present));
				landedAs.putIfAbsent(name, present);
				continue;
			}
			String fileName = path.substring(path.lastIndexOf('/') + 1);
			String local = PhotoCollections.freeName(taken, fileName);
			taken.add(local);
			nameByHash.put(hash, local);
			ImagePart reference =
				PhotoCollections.stored(local, PhotoRef.create().setHash(hash).setPath(path), new ArrayList<>());
			added.add(reference);
			ImagePart landed = PhotoCollections.copy(part);
			landed.setName(local);
			arrived.add(landed);
			landedAs.putIfAbsent(name, local);
			outcome.setNewName(local);
		}

		if (!added.isEmpty()) {
			for (ImagePart reference : added) {
				stored.addPart(reference);
			}
			ThumbnailInfo cover = stored.getIndexPicture();
			CreatedTarget created = request.getCreated();
			if (created != null
				&& (cover == null || PhotoCollections.referenceNamed(stored, cover.getImage()) == null)) {
				// Only a collection created for what arrives is given a cover, by the rule an album
				// created for a move is, see issue #277; adding never touches the cover of one
				// that existed before.
				String hintedName = created.getCover().isEmpty() ? null : landedAs.get(created.getCover());
				ImagePart hinted = null;
				for (ImagePart landed : arrived) {
					if (landed.getName().equals(hintedName)) {
						hinted = landed;
					}
				}
				ImagePart best = PrivacyFilter.createdCover(arrived, hinted);
				if (best != null) {
					stored.setIndexPicture(PrivacyFilter.thumbnail(best));
				}
			}
			storeSidecar(target.toFile(), sidecarOf(stored));
			_cache.invalidate(target);
			LOG.info("Added " + added.size() + " photograph(s) to the collection '" + target.toFile() + "'.");
		}
		serveJsonObject(context.response(), result);
	}

	/**
	 * Takes the named references out of the given collection, see issue #221: the photographs stay
	 * where they are, untouched.
	 */
	private MoveResult removeReferences(PathInfo folder, AlbumInfo collection, List<String> names) throws IOException {
		MoveResult result = MoveResult.create();
		Set<String> removed = new HashSet<>();
		for (String name : names) {
			MoveOutcome outcome = MoveOutcome.create().setName(name).setNewName("");
			if (PhotoCollections.referenceNamed(collection, name) != null && removed.add(name)) {
				outcome.setMessage(PhotoCollections.REMOVED);
			} else if (removed.contains(name)) {
				outcome.setMessage(PhotoCollections.REMOVED);
			} else {
				outcome.setMessage(notInAlbum(name));
			}
			result.addOutcome(outcome);
		}
		if (!removed.isEmpty()) {
			List<AlbumPart> parts = new ArrayList<>();
			for (AlbumPart part : collection.getParts()) {
				if (part instanceof ImagePart && removed.contains(((ImagePart) part).getName())) {
					continue;
				}
				parts.add(part);
			}
			collection.setParts(parts);
			ThumbnailInfo cover = collection.getIndexPicture();
			if (cover != null && removed.contains(cover.getImage())) {
				collection.setIndexPicture(null);
			}
			storeSidecar(folder.toFile(), sidecarOf(collection));
			_cache.invalidate(folder);
			LOG.info("Took " + removed.size() + " reference(s) out of the collection '" + folder.toFile() + "'.");
		}
		return result;
	}

	/**
	 * Refuses a move, with the message that names the folder the caller may not touch.
	 *
	 * <p>
	 * A caller that is nobody yet is told how to become somebody instead, exactly as every other
	 * refusal does, see {@link AuthService#refusal(Caller, boolean)}.
	 * </p>
	 */
	private void refuseMove(Context context, Caller caller, String message, boolean write) throws IOException {
		if (caller.isPaired()) {
			LOG.warning("Refusing the move at '" + context.request().getPathInfo() + "': " + message);
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, message);
		} else {
			unauthorized(context, caller, write);
		}
	}

	/**
	 * Deletes entries of the addressed folder, see issue #109.
	 *
	 * <p>
	 * A sibling of the move above, and the same request: a {@link MoveRequest} whose
	 * {@link MoveRequest#getTarget() target} is nothing — the target of a delete is the trash of
	 * the caller's space, which nobody picks. The answer is a {@link MoveResult} with one outcome
	 * per name, saying whether the entry was renamed into the trash or removed for good.
	 * </p>
	 *
	 * <p>
	 * It deletes an album, a folder <em>and a single photograph</em> (issue #131): a name that is
	 * an image file is moved into the album this one has in the trash, by the very move of issue
	 * #47 — the sidecar part and the hash entry ride along, and nothing is unlinked. That is
	 * general, for every album and not only for an inbox.
	 * </p>
	 *
	 * <p>
	 * It needs {@link Rights#EDIT} on the folder the entry lives in: removing an entry is an edit
	 * of the listing. The one exception is the contributor rule of issue #53, and it reaches
	 * exactly as far as it does for a move — a request naming <em>photographs only</em>, all of
	 * them the caller's own, is somebody taking their own contribution back out, and it never
	 * reaches a whole album. A share link is refused in its own words, since a link may well allow
	 * adding photos.
	 * </p>
	 */
	private void deleteEntries(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo folder = location.getPath();
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, folder)) {
			unauthorized(context, caller, true);
			return;
		}
		if (caller.isShareLink()) {
			LOG.warning("Refusing the delete through a share link at '" + context.request().getPathInfo() + "': "
				+ DeleteService.SHARE_DELETE_REFUSED);
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, DeleteService.SHARE_DELETE_REFUSED);
			return;
		}
		MoveRequest request;
		try {
			byte[] contents = readBody(context.request());
			request = MoveRequest.readMoveRequest(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable delete request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, DeleteService.DELETE_UNREADABLE);
			return;
		}
		List<String> names = request.getNames().stream().map(MoveName::getName).collect(Collectors.toList());

		if (!_auth.mayEdit(caller, folder)) {
			// Deleting an album is an edit of the listing it is an entry of, and nothing but the
			// edit right opens that. A request naming photographs alone is the other case: taking
			// one's own contribution back out is what the contributor exception of issue #53 is,
			// and throwing it away is taking it out, see issue #131.
			if (!DeleteService.onlyImages(folder.toFile(), names)) {
				refuseMove(context, caller, DeleteService.EDIT_REFUSED, true);
				return;
			}
			if (!new MoveService(_cache, _auth).contributedBy(folder, names, caller.subject())) {
				refuseMove(context, caller, MoveService.CONTRIBUTION_REFUSED, true);
				return;
			}
		}

		Resource collection = folder.toFile().isDirectory() ? _cache.lookup(folder) : null;
		if (PhotoCollections.isCollection(collection)) {
			// Taking a reference out of a collection never touches the photograph, see issue #221.
			serveJsonObject(context.response(), removeReferences(folder, (AlbumInfo) collection, names));
			return;
		}
		if (PhotoSearch.isSearch(collection)) {
			// A saved search holds nothing to delete, see issue #227; the saved search itself is
			// deleted from the folder it lies in.
			LOG.warning("Refusing a delete in the saved search '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoSearch.DELETE_REFUSED);
			return;
		}

		MoveResult result;
		try {
			// The trash lives below the caller's own space, exactly as the duplicates do: nothing
			// crosses a space boundary, see issue #82.
			result = new DeleteService(folder.getBasePath(), _cache).delete(folder, names);
		} catch (MoveRefused ex) {
			LOG.warning("Refusing to delete in '" + context.request().getPathInfo() + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}

		// What went into the trash is gone from the album; the index reads the folder again, see
		// issue #118.
		treeChanged(folder.toFile());

		serveJsonObject(context.response(), result);
	}

	/**
	 * Answers the originals named in the request body as one zip archive, see issue #164.
	 *
	 * <p>
	 * <code>POST &lt;album&gt;/?action=zip</code> with a {@link MoveRequest} whose
	 * <code>target</code> is ignored. A POST and not a <code>GET ?names=…</code>: a selection of a
	 * few hundred camera names does not fit into the request line a server accepts, and the app
	 * fetches the bytes through its client anyway (the bearer travels in the header, which no plain
	 * link could carry), so nothing is lost by leaving the URL out of it.
	 * </p>
	 *
	 * <p>
	 * It needs {@link Rights#DOWNLOAD} on the album, which is what the original of each image asks
	 * for as well, and every named photograph is asked exactly what its original would be asked:
	 * a name that is no photograph of this album is <code>404</code>, one of an inbox the caller
	 * may not see is not there for them (issue #131), one above the caller's clearance or below a
	 * link's rating limit is refused as its original would be ({@link #hidden(PathInfo, int, int)}).
	 * All of that is decided before the first byte of the archive is written: a refusal speaks,
	 * and an archive never silently lacks what was asked for. The archive is streamed, never
	 * written to disk, and the originals are only read, see {@link ZipDownload}.
	 * </p>
	 */
	private void zipEntries(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Integer viewAs = viewAsOf(context);
		if (viewAs == null) {
			return;
		}
		PathInfo folder = zipFolder(context, caller, viewAs);
		if (folder == null) {
			return;
		}
		Set<String> names = zipNames(context);
		if (names == null) {
			return;
		}
		Map<String, File> files = zipFiles(context, caller, folder, viewAs, names);
		if (files == null) {
			return;
		}
		sendZip(context, folder, files);
	}

	/**
	 * Answers <code>POST &lt;album&gt;/?action=zip-ticket</code>, the download of issue #209 a
	 * browser fetches by itself, see {@link ZipTickets}.
	 *
	 * <p>
	 * The request is the one of <code>?action=zip</code> and is checked exactly as that one is,
	 * every refusal answered before any ticket exists. The answer is a {@link MediaUrl} whose
	 * <code>url</code> is <code>&lt;album&gt;/?action=zip&amp;ticket=&lt;id&gt;&amp;media=&lt;signature&gt;</code>:
	 * the signature, made out to the asking device, share link or personal-link session, is over
	 * the album's path and the ticket together ({@link MediaSignatures.Kind#ARCHIVE}). A caller
	 * without a token gets a ticket without a signature, which opens only what such a caller may
	 * download anyway, and <code>media</code> is then empty.
	 * </p>
	 */
	private void issueZipTicket(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Integer viewAs = viewAsOf(context);
		if (viewAs == null) {
			return;
		}
		PathInfo folder = zipFolder(context, caller, viewAs);
		if (folder == null) {
			return;
		}
		Set<String> names = zipNames(context);
		if (names == null) {
			return;
		}
		if (zipFiles(context, caller, folder, viewAs, names) == null) {
			return;
		}
		HttpServletRequest request = context.request();
		String path = request.getPathInfo() == null ? "" : request.getPathInfo();
		java.time.Instant expires =
			java.time.Instant.now().plus(MediaSignatures.LIFETIME).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		String id = _zipTickets.newId();
		String signature;
		String subject;
		if (caller.isPaired() || caller.isShareLink()) {
			try {
				signature = _auth.signMedia(caller, ticketPath(path, id), MediaSignatures.Kind.ARCHIVE,
					expires.getEpochSecond());
			} catch (AuthService.Refused ex) {
				LOG.warning("Refusing a download ticket of '" + path + "': " + ex.getMessage());
				errorInfo(context, ex.getStatus(), ex.getMessage());
				return;
			}
			subject = MediaSignatures.subjectOf(signature);
		} else {
			signature = "";
			subject = ZipTickets.ANONYMOUS;
		}
		_zipTickets.put(id, new ZipTickets.Ticket(path, names, viewAs, subject, expires.getEpochSecond()));
		String url = request.getContextPath() + request.getServletPath() + encodePath(path) + "?action="
			+ ZIP_ACTION + "&" + TICKET_PARAMETER + "=" + id
			+ (signature.isEmpty() ? "" : "&" + MediaSignatures.PARAMETER + "=" + signature);
		LOG.info("Issued a download ticket for " + names.size() + " names of '" + path + "'.");
		serveJsonObject(context.response(),
			MediaUrl.create().setUrl(url).setMedia(signature).setExpires(expires.toString()));
	}

	/**
	 * Answers <code>GET &lt;album&gt;/?action=zip&amp;ticket=&lt;id&gt;[&amp;media=&lt;signature&gt;]</code>,
	 * the browser's own fetch of a download ticket, see {@link #issueZipTicket(Context)}.
	 *
	 * <p>
	 * A bearer beside it is ignored: the signature decides who asks, as for every signed media
	 * address (issue #185), and the ticket must have been made out to exactly that subject and that
	 * folder. It is used up by the first request, good or refused afterwards, and every name is
	 * asked again what it was asked when the ticket was made — as the device or link would be asked
	 * <em>now</em>.
	 * </p>
	 */
	private void serveZipTicket(Context context) throws IOException {
		HttpServletRequest request = context.request();
		String path = request.getPathInfo() == null ? "" : request.getPathInfo();
		String id = context.getParameter(TICKET_PARAMETER);
		String media = context.getParameter(MediaSignatures.PARAMETER);
		if (id == null || id.isEmpty()) {
			LOG.warning("Refusing a download of '" + path + "' without a ticket.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, ZIP_TICKET_REQUIRED);
			return;
		}
		Caller caller;
		String subject;
		if (media == null || media.isEmpty()) {
			caller = Caller.ANONYMOUS;
			subject = ZipTickets.ANONYMOUS;
		} else {
			try {
				caller = _auth.mediaCaller(media, ticketPath(path, id), MediaSignatures.Kind.ARCHIVE);
			} catch (AuthService.Refused ex) {
				LOG.warning("Refusing the download ticket of '" + path + "': " + ex.getMessage());
				errorInfo(context, ex.getStatus(), ex.getMessage());
				return;
			}
			subject = MediaSignatures.subjectOf(media);
		}
		ZipTickets.Ticket ticket = _zipTickets.take(id, path, subject);
		if (ticket == null) {
			LOG.warning("Refusing the download ticket of '" + path + "': used, run out or not made for this caller.");
			errorInfo(context, HttpServletResponse.SC_GONE, ZIP_TICKET_GONE);
			return;
		}
		if (gone(context, caller)) {
			return;
		}
		PathInfo folder = zipFolder(context, caller, ticket.getViewAs());
		if (folder == null) {
			return;
		}
		Map<String, File> files = zipFiles(context, caller, folder, ticket.getViewAs(), ticket.getNames());
		if (files == null) {
			return;
		}
		sendZip(context, folder, files);
		// The address was a credential for one download: no shared cache keeps what it opened.
	}

	/** The path a ticket's signature is made over: the folder's and the ticket's together. */
	private static String ticketPath(String path, String id) {
		return path + "?" + TICKET_PARAMETER + "=" + id;
	}

	/** The "view as" of the request, <code>null</code> where it was refused (and answered). */
	private Integer viewAsOf(Context context) throws IOException {
		try {
			return Privacy.viewAs(context.getParameter(Privacy.VIEW_AS_PARAMETER));
		} catch (IllegalArgumentException ex) {
			LOG.warning("Rejecting the unknown 'viewAs' value '" + ex.getMessage() + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, VIEW_AS_REFUSED);
			return null;
		}
	}

	/**
	 * The album a download addresses, where the caller may download from it at all; <code>null</code>
	 * where that was refused (and answered).
	 */
	private PathInfo zipFolder(Context context, Caller caller, int viewAs) throws IOException {
		Location location = resolve(context, caller);
		if (location == null) {
			return null;
		}
		PathInfo folder = location.getPath();
		if (!_auth.readAllowed(caller) && _auth.rights(caller, folder).isEmpty()) {
			unauthorized(context, caller, false);
			return null;
		}
		if (!folder.toFile().isDirectory()) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Inboxes.NOT_FOUND);
			return null;
		}
		if (Inboxes.isInbox(_cache.lookup(folder))
			&& Inboxes.visibility(_auth, caller, folder, viewAs) == Inboxes.Visibility.NONE) {
			LOG.warning("Hiding the inbox at '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Inboxes.NOT_FOUND);
			return null;
		}
		if (!_auth.rights(caller, folder).contains(Rights.DOWNLOAD)) {
			refuse(context, caller, folder, Rights.DOWNLOAD, false);
			return null;
		}
		return folder;
	}

	/** The names of a download request's body, each once; <code>null</code> where refused. */
	private Set<String> zipNames(Context context) throws IOException {
		MoveRequest request;
		try {
			byte[] contents = readBody(context.request());
			request = MoveRequest.readMoveRequest(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable download request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, ZIP_UNREADABLE);
			return null;
		}
		// Each name once, in the order asked: a photograph named twice is not two entries.
		Set<String> names = new LinkedHashSet<>();
		for (MoveName name : request.getNames()) {
			names.add(name.getName());
		}
		if (names.isEmpty()) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, ZIP_EMPTY);
			return null;
		}
		return names;
	}

	/**
	 * The files the given names of a download stand for, each asked what its original would be
	 * asked; <code>null</code> where one was refused (and answered).
	 *
	 * <p>
	 * In a plain folder a name is an entry of the folder, or &mdash; with a slash &mdash; the path
	 * of a photograph below it, so that what a search found in several albums is one archive (issue
	 * #243). Each is asked exactly what its original is asked in the album it lies in: the right to
	 * download there, the inbox rule, the label of a link, its privacy against the clearance there
	 * and its rating against the rating floor there. An entry of the archive is named as it was
	 * asked, a path keeping the albums' photographs of one name apart; a raw companion (issue #191)
	 * lies beside its photograph under its own name. A path is a path below the folder and nothing
	 * else: an empty segment, <code>..</code>, a backslash, a hidden or ignored segment is refused
	 * as an unknown name is.
	 * </p>
	 */
	private Map<String, File> zipFiles(Context context, Caller caller, PathInfo folder, int viewAs,
			Collection<String> names) throws IOException {
		Resource container = _cache.lookup(folder);
		if (PhotoCollections.isCollection(container)) {
			return collectionZipFiles(context, caller, folder, (AlbumInfo) container, viewAs, names);
		}
		if (PhotoSearch.isSearch(container)) {
			return searchZipFiles(context, caller, folder, (AlbumInfo) container, viewAs, names);
		}
		// A file once, though a photograph and its raw companion be named both.
		Map<String, File> result = new LinkedHashMap<>();
		for (String name : names) {
			PathInfo image = zipEntry(folder, name);
			File file = image == null ? null : image.toFile();
			if (file == null || !file.isFile() || !ResourceCache.isImage(file)) {
				LOG.warning("Refusing the download in '" + context.request().getPathInfo() + "': no photograph '"
					+ name + "'.");
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, notInAlbum(name));
				return null;
			}
			// The album the photograph lies in: the folder itself for a plain name.
			PathInfo source = image.parent();
			if (!source.equals(folder) && !_auth.rights(caller, image).contains(Rights.DOWNLOAD)) {
				refuse(context, caller, image, Rights.DOWNLOAD, false);
				return null;
			}
			if (Inboxes.isInbox(_cache.lookup(source))) {
				Inboxes.Visibility inbox = Inboxes.visibility(_auth, caller, source, viewAs);
				Resource part = _cache.lookup(image);
				if (!(part instanceof ImagePart) || !Inboxes.shows(inbox, (ImagePart) part, caller.subject())) {
					LOG.warning("Hiding the image of an inbox at '" + context.request().getPathInfo() + name + "'.");
					errorInfo(context, HttpServletResponse.SC_NOT_FOUND, notInAlbum(name));
					return null;
				}
			}
			if (!labelShows(caller, image)) {
				LOG.warning("Hiding a photograph without the label of the link in '" + context.request().getPathInfo()
					+ name + "'.");
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, notInAlbum(name));
				return null;
			}
			int clearance = Math.min(_auth.clearance(caller, source), viewAs);
			int minRating = _auth.minRating(caller, source, viewAs);
			String refusal = hidden(image, clearance, minRating);
			if (refusal != null) {
				imageRefused(context, caller, refusal);
				return null;
			}
			result.putIfAbsent(name, file);
			// A photograph's originals are its JPEG and the raw shot beside it, see issue #191.
			Resource part = _cache.lookup(image);
			if (part instanceof ImagePart && image.getName().equals(((ImagePart) part).getName())) {
				File raw = RawPairs.companion(source.toFile(), (ImagePart) part);
				if (raw != null) {
					int slash = name.lastIndexOf('/');
					result.putIfAbsent(name.substring(0, slash + 1) + raw.getName(), raw);
				}
			}
		}
		return result;
	}

	/**
	 * The photograph a name of a download in the given plain folder stands for, see
	 * {@link #zipFiles(Context, Caller, PathInfo, int, Collection)}; <code>null</code> where the
	 * name is no entry of the folder and no path below it.
	 */
	private static PathInfo zipEntry(PathInfo folder, String name) {
		if (name == null || name.indexOf('/') < 0) {
			// A name is an entry of this folder, never an address: nothing hidden.
			return name == null || name.isEmpty() || LibraryFiles.isIgnored(name) || name.indexOf('\\') >= 0
				? null : folder.child(name);
		}
		List<String> segments = PhotoSearch.segments(name);
		if (segments == null) {
			return null;
		}
		PathInfo result = folder;
		for (String segment : segments) {
			result = result.child(segment);
		}
		return result;
	}

	/**
	 * The originals the given names of a saved search stand for, see issue #227: each a match of its
	 * query, asked what it is asked in its own album, the label of a link asked of the photograph's
	 * own; an entry is named by the photograph's path below the folder searched.
	 */
	private Map<String, File> searchZipFiles(Context context, Caller caller, PathInfo folder, AlbumInfo search,
			int viewAs, Collection<String> names) throws IOException {
		String refusal = PhotoSearch.refusal(search.getQuery());
		if (refusal != null) {
			errorInfo(context, HttpServletResponse.SC_NOT_IMPLEMENTED, refusal);
			return null;
		}
		int clearance = Math.min(_auth.clearance(caller, folder), viewAs);
		int minRating = _auth.minRating(caller, folder, viewAs);
		Map<String, File> result = new LinkedHashMap<>();
		for (String name : names) {
			PathInfo image = photoNamed(folder, search, name);
			if (image == null || !image.toFile().isFile() || !labelShows(caller, image)) {
				LOG.warning("Refusing the download in '" + context.request().getPathInfo() + "': no photograph '"
					+ name + "'.");
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, notInAlbum(name));
				return null;
			}
			PathInfo source = image.parent();
			Resource part = _cache.lookup(image);
			if (Inboxes.isInbox(source.toFile())) {
				Inboxes.Visibility inbox = Inboxes.visibility(_auth, caller, source, viewAs);
				if (!(part instanceof ImagePart) || !Inboxes.shows(inbox, (ImagePart) part, caller.subject())) {
					errorInfo(context, HttpServletResponse.SC_NOT_FOUND, notInAlbum(name));
					return null;
				}
			}
			String hiddenBy = hidden(image, clearance, minRating);
			if (hiddenBy != null) {
				imageRefused(context, caller, hiddenBy);
				return null;
			}
			result.put(name, image.toFile());
			if (part instanceof ImagePart) {
				File raw = RawPairs.companion(source.toFile(), (ImagePart) part);
				if (raw != null) {
					int dot = name.lastIndexOf('.');
					String rawName = raw.getName();
					int rawDot = rawName.lastIndexOf('.');
					result.put((dot > 0 ? name.substring(0, dot) : name) + (rawDot > 0 ? rawName.substring(rawDot) : ""), raw);
				}
			}
		}
		return result;
	}

	/**
	 * The originals the given names of a collection stand for, see issue #221: each asked what it
	 * is asked in its own album, the label of a link asked of the collection's own; an entry is
	 * named as the photograph is named in the collection.
	 */
	private Map<String, File> collectionZipFiles(Context context, Caller caller, PathInfo folder,
			AlbumInfo collection, int viewAs, Collection<String> names) throws IOException {
		int clearance = Math.min(_auth.clearance(caller, folder), viewAs);
		int minRating = _auth.minRating(caller, folder, viewAs);
		Map<String, File> result = new LinkedHashMap<>();
		for (String name : names) {
			ImagePart reference = PhotoCollections.referenceNamed(collection, name);
			if (reference == null || !labelShows(caller, folder.child(name))) {
				LOG.warning("Refusing the download in '" + context.request().getPathInfo() + "': no photograph '"
					+ name + "'.");
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, notInAlbum(name));
				return null;
			}
			PathInfo image = _collections.locate(reference.getRef());
			if (image == null) {
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, PhotoCollections.MISSING);
				return null;
			}
			PathInfo source = image.parent();
			Resource part = _cache.lookup(image);
			if (Inboxes.isInbox(source.toFile())) {
				Inboxes.Visibility inbox = Inboxes.visibility(_auth, caller, source, viewAs);
				if (!(part instanceof ImagePart) || !Inboxes.shows(inbox, (ImagePart) part, caller.subject())) {
					errorInfo(context, HttpServletResponse.SC_NOT_FOUND, notInAlbum(name));
					return null;
				}
			}
			String refusal = hidden(image, clearance, minRating);
			if (refusal != null) {
				imageRefused(context, caller, refusal);
				return null;
			}
			result.put(name, image.toFile());
			if (part instanceof ImagePart) {
				File raw = RawPairs.companion(source.toFile(), (ImagePart) part);
				if (raw != null) {
					int dot = name.lastIndexOf('.');
					String rawName = raw.getName();
					int rawDot = rawName.lastIndexOf('.');
					result.put((dot > 0 ? name.substring(0, dot) : name) + (rawDot > 0 ? rawName.substring(rawDot) : ""), raw);
				}
			}
		}
		return result;
	}

	/** Streams the given files as one archive named by the given folder, see {@link ZipDownload}. */
	private void sendZip(Context context, PathInfo folder, Map<String, File> files) throws IOException {
		HttpServletResponse response = context.response();
		allowCrossOrigin(response);
		if (context.getParameter(TICKET_PARAMETER) != null) {
			// A ticket's address was a credential for one download: no shared cache keeps it.
			response.setHeader("Cache-Control", "private, no-store");
		}
		response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
		response.setContentType(ZipDownload.CONTENT_TYPE);
		response.setHeader("Content-Disposition", ZipDownload.contentDisposition(folder.toFile().getName()));
		LOG.info("Delivering " + files.size() + " originals of '" + context.request().getPathInfo() + "' as a zip.");
		ZipDownload.write(response.getOutputStream(), files);
	}

	/** The message a download naming something that is no photograph of the album is refused with. */
	static String notInAlbum(String name) {
		return "There is no photograph '" + name + "' in this album.";
	}

	/**
	 * Deletes the photographs of the addressed album that are rated as trash from disk, see issue
	 * #152.
	 *
	 * <p>
	 * The purge of an album: every photograph rated
	 * {@link de.haumacher.imageServer.auth.Ratings#TRASH}, group members one by one, is unlinked,
	 * leaves the sidecar and the hash cache, and takes its generated cache files with it, see
	 * {@link DeleteService#purge(PathInfo)}. The request carries no body; the answer is a
	 * {@link MoveResult} with one outcome per photograph, empty where there was nothing to purge.
	 * </p>
	 *
	 * <p>
	 * It is the administrator's act alone ({@link AuthService#mayAdminister(Caller)}): the one path
	 * of this server that removes a photograph from disk. Trashing and restoring are ratings and
	 * need {@link Rights#EDIT}; making it final does not. A share link is refused whatever it
	 * allows, an anonymous caller is told how to sign in.
	 * </p>
	 */
	private void purgeTrash(Context context) throws IOException {
		// Who the caller is, before where they are pointing, like every management request.
		Caller caller = _auth.caller(context.request());
		if (!_auth.mayAdminister(caller)) {
			if (!caller.isPaired() && !caller.isShareLink()) {
				unauthorized(context, caller, true);
				return;
			}
			LOG.warning("Refusing the purge at '" + context.request().getPathInfo() + "': "
				+ DeleteService.PURGE_REFUSED);
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, DeleteService.PURGE_REFUSED);
			return;
		}
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo folder = location.getPath();

		MoveResult result;
		try {
			result = new DeleteService(folder.getBasePath(), _cache).purge(folder);
		} catch (MoveRefused ex) {
			LOG.warning("Refusing to purge '" + context.request().getPathInfo() + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}

		if (!result.getOutcomes().isEmpty()) {
			// What was purged is gone from the album; the index reads the folder again, see #118.
			treeChanged(folder.toFile());
		}

		serveJsonObject(context.response(), result);
	}

	/**
	 * Files what is already in the addressed folder by that folder's placement rule, see issue #48.
	 *
	 * <p>
	 * The "apply once" of a rule, and never anything else: nothing here happens implicitly, on a
	 * read or at start-up. It is a write on the folder itself — every child is renamed — so it is
	 * refused exactly as a move out of that folder is, and it answers with the outcomes a move
	 * answers with, see {@link MoveService#place(PathInfo)}.
	 * </p>
	 */
	private void placeEntries(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo folder = location.getPath();
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, folder)) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayEdit(caller, folder)) {
			refuseMove(context, caller, MoveService.EDIT_REFUSED, true);
			return;
		}

		MoveResult result;
		try {
			result = new MoveService(_cache, _auth).place(folder);
		} catch (MoveRefused ex) {
			LOG.warning("Refusing to apply the placement rule of '" + context.request().getPathInfo() + "': "
				+ ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}

		serveJsonObject(context.response(), result);
	}

	/**
	 * Re-reads the photo details below the addressed folder and fills what the sidecars lack, see
	 * issue #161 and {@link Reanalysis}.
	 *
	 * <p>
	 * It changes the album's sidecar, so it needs {@link Rights#EDIT} on the folder, exactly as the
	 * duplicate sweep does; a share link is refused with a message of its own before anything else,
	 * because its rights may well allow contributing and re-reading is not contributing.
	 * </p>
	 *
	 * <p>
	 * An album is re-read while the request waits and answered <code>200</code>. A folder of
	 * folders is walked on the space's low-priority thread; the request waits for it at most
	 * {@link #REANALYZE_WAIT_MILLIS} and is otherwise answered <code>202</code> with the counts so
	 * far, which <code>?type=reanalyze</code> reads back later.
	 * </p>
	 */
	private void reanalyze(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		PathInfo folder = reanalysisFolder(context, caller);
		if (folder == null) {
			return;
		}

		Resource resource = _cache.lookup(folder);
		if (resource instanceof AlbumInfo) {
			serveJsonObject(context.response(), _reanalysis.now(folder));
			return;
		}

		Reanalysis.Progress progress = _reanalysis.start(folder);
		try {
			progress.done().get(_reanalyzeWait, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.TimeoutException ex) {
			// Answered below with what there is so far.
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		} catch (java.util.concurrent.ExecutionException ex) {
			// The run logs its own failure.
		}
		if (!progress.isDone()) {
			context.response().setStatus(HttpServletResponse.SC_ACCEPTED);
		}
		serveJsonObject(context.response(), progress.result());
	}

	/** Answers how far the latest re-reading of the addressed folder has got, see {@link Reanalysis}. */
	private void serveReanalysis(Context context, Caller caller, PathInfo folder) throws IOException {
		if (!checkReanalysis(context, caller, folder)) {
			return;
		}
		Reanalysis.Progress progress = _reanalysis.progress(folder.toFile());
		if (progress == null) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, NO_REANALYSIS);
			return;
		}
		serveJsonObject(context.response(), progress.result());
	}

	/** The folder a re-reading request addresses, <code>null</code> when it was answered. */
	private PathInfo reanalysisFolder(Context context, Caller caller) throws IOException {
		Location location = resolve(context, caller);
		if (location == null) {
			return null;
		}
		PathInfo folder = location.getPath();
		return checkReanalysis(context, caller, folder) ? folder : null;
	}

	/** Whether the given caller may re-read the given folder; answered when not. */
	private boolean checkReanalysis(Context context, Caller caller, PathInfo folder) throws IOException {
		if (caller.isShareLink()) {
			LOG.warning("Refusing the re-reading through a share link at '" + context.request().getPathInfo()
				+ "': " + REANALYZE_SHARE_REFUSED);
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, REANALYZE_SHARE_REFUSED);
			return false;
		}
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, folder)) {
			unauthorized(context, caller, true);
			return false;
		}
		if (!_auth.mayEdit(caller, folder)) {
			if (!identified(caller)) {
				unauthorized(context, caller, true);
				return false;
			}
			LOG.warning("Refusing the re-reading at '" + context.request().getPathInfo() + "': " + REANALYZE_REFUSED);
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, REANALYZE_REFUSED);
			return false;
		}
		if (!folder.toFile().isDirectory()) {
			error404(context);
			return false;
		}
		return true;
	}

	/**
	 * Throws away the generated cache files of the addressed folder, see issue #98.
	 *
	 * <p>
	 * The administrator's handle on the server's own cache, and nothing else: a preview written by
	 * a build that could still crash mid-write stays broken for ever, because a cached file is
	 * judged by its timestamp and nobody ever looks inside it. The files are deleted by the names
	 * the server writes them under (see {@link CacheRefresh}), so no request can ever reach a
	 * sidecar or an original through this.
	 * </p>
	 *
	 * <p>
	 * It is the administrator's alone — not an {@link Rights#EDIT} right on the folder. Deleting
	 * previews touches nothing of the album's content, but it makes the server redo work for a
	 * whole album on the next visit, which is a decision about the machine rather than about the
	 * photos, and the person who runs it is the one who makes it.
	 * </p>
	 */
	private void refreshCache(Context context) throws IOException {
		// Who the caller is, before where they are pointing: whether a folder exists is nothing an
		// anonymous caller learns from a management request.
		Caller caller = _auth.caller(context.request());
		if (!_auth.mayAdminister(caller)) {
			if (!caller.isPaired() && !caller.isShareLink()) {
				// Nobody yet: told how to become somebody, like every other refusal.
				unauthorized(context, caller, true);
				return;
			}
			LOG.warning("Refusing the cache refresh at '" + context.request().getPathInfo() + "': "
				+ CACHE_REFRESH_REFUSED);
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, CACHE_REFRESH_REFUSED);
			return;
		}

		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		File folder = location.getPath().toFile();
		if (!folder.isDirectory()) {
			error404(context);
			return;
		}

		int removed = CacheRefresh.refresh(folder);
		// Made again in the background, before anybody asks, see issue #236.
		_index.pipeline().forget(folder);
		_index.pipeline().notice(folder, false);
		// A rendition that failed once is never tried again while the server runs; the whole point
		// of throwing it away is that it is made afresh, see issue #74.
		_videos.forget(CacheRefresh.cacheDir(folder));
		// The same for a photograph the detector refused, see issue #124.
		_faces.forget(folder);

		serveJsonObject(context.response(), CacheRefreshed.create().setRemoved(removed));
	}

	/**
	 * Answers which of the asked contents this space already holds.
	 *
	 * <p>
	 * A client uses it to skip transferring what is already there, so it asks for what an upload
	 * asks for: the contribute right, see {@link AuthService#mayContribute(Caller, PathInfo)}. The
	 * upload itself is idempotent in any case, see {@link #storeUploads(Context, Caller, PathInfo)}.
	 * </p>
	 *
	 * <p>
	 * Since issue #118 the answer is not the addressed folder's alone. The folder is asked first —
	 * which also fills its sidecar, as it always did — and what it does not hold is looked up in
	 * the space's {@link HashIndex}, so a photo that was synced into the inbox and then moved into
	 * an album is still <em>present</em>, named by the path it is at now. How far that index has
	 * got travels with the answer, because a client that syncs a whole camera roll against an
	 * incomplete index would upload again what it cannot yet be told about.
	 * </p>
	 *
	 * <p>
	 * A share link is the exception and stays confined to the folder it was made for: a link sees
	 * no more of the space than its folder, and that includes what it may learn about it.
	 * </p>
	 *
	 * <p>
	 * An inbox is answered as a read answers it (issue #216): addressed by a caller who may not see
	 * it, the check is <code>404</code> with {@link Inboxes#NOT_FOUND}; a photograph in an inbox the
	 * caller may not see that photograph of is present without a path, see
	 * {@link #presentName(Caller, Path, String, Map)}.
	 * </p>
	 */
	private void checkUploads(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo resourcePath = location.getPath();

		// Contributing is what the answer is for. In the caller's own space, and in the
		// single-user library mode WRITES keeps open, it stays the plain read it has been since
		// issue #29 — that library has no grants and never had this endpoint closed.
		// Contributing is what the answer is for, but it says no more than a listing does — which
		// of these photos are already here — so looking is enough to ask it.
		if (!_auth.mayContribute(caller, resourcePath) && !_auth.mayView(caller, resourcePath)) {
			refuse(context, caller, resourcePath, Rights.CONTRIBUTE, false);
			return;
		}

		File folder = resourcePath.toFile();
		// The inbox before its first upload holds nothing yet; asking it is no address naming
		// nothing, see issue #226, and the check writes no sidecar into a folder that is not there.
		boolean unmadeInbox = !folder.exists() && Inboxes.isInbox(folder);
		if (!folder.isDirectory() && !unmadeInbox) {
			// Answered as a read answers it, see issues #176 and #215.
			notFound(context, resourcePath);
			return;
		}
		Inboxes.Visibility here = Inboxes.isInbox(folder)
			? Inboxes.visibility(_auth, caller, resourcePath, Privacy.PRIVATE)
			: Inboxes.Visibility.FULL;
		if (here == Inboxes.Visibility.NONE) {
			// An inbox the caller may not read is not there for them, see issues #135 and #216.
			LOG.warning("Hiding the inbox from a hash check at '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Inboxes.NOT_FOUND);
			return;
		}

		UploadCheck check;
		try {
			byte[] contents = readBody(context.request());
			check = UploadCheck.readUploadCheck(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable upload check: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, CHECK_UNREADABLE);
			return;
		}

		Map<String, String> nameByHash;
		if (unmadeInbox) {
			nameByHash = Collections.emptyMap();
		} else {
			HashCache hashes = new HashCache(folder);
			try {
				nameByHash = hashes.nameByHash();
			} finally {
				hashes.flush();
			}
		}

		// The folder was read (and its sidecar filled); the index hears about that through the
		// flush above and answers for the rest of the space.
		boolean confined = caller.isShareLink();

		UploadCheckResult result = UploadCheckResult.create();
		if (!confined) {
			result.setIndexed(_index.progress());
		}
		Map<String, Inboxes.Visibility> inboxes = new HashMap<>();
		for (ContentHash asked : check.getHashes()) {
			String name = nameByHash.get(asked.getHash());
			if (name != null) {
				if (here == Inboxes.Visibility.OWN && !Inboxes.contributedBy(folder, name, caller.subject())) {
					name = "";
				}
			} else if (!confined) {
				// Anywhere in the space, named by the path it is at, see PresentFile#name.
				name = _index.pathOf(asked.getHash());
				if (name != null) {
					name = presentName(caller, resourcePath.getBasePath(), name, inboxes);
				}
			}
			if (name != null) {
				result.addPresent(PresentFile.create().setHash(asked.getHash()).setName(name));
			}
		}

		serveJsonObject(context.response(), result);
	}

	/**
	 * How a photograph the space's {@link HashIndex} found is named to the given caller, see issue
	 * #216.
	 *
	 * <p>
	 * By its path, unless it lies in an inbox the caller may not see that photograph of (issue
	 * #135: a <code>view</code> member sees no inbox, a <code>contribute</code> member only their
	 * own contributions). Then it is answered present <em>without a path</em> (an empty name), not
	 * absent: absent would have the camera-roll sync upload the photograph again — a duplicate the
	 * owner later finds — while "already in the library somewhere" tells the caller nothing they
	 * do not hold already, the contents being their own.
	 * </p>
	 *
	 * @param path
	 *        The path relative to the space root the index answered.
	 * @param inboxes
	 *        What the caller sees of the folders asked so far in this request.
	 */
	private String presentName(Caller caller, Path spaceRoot, String path, Map<String, Inboxes.Visibility> inboxes) {
		int slash = path.lastIndexOf('/');
		if (slash < 0) {
			// Directly in the space root, which is never an inbox.
			return path;
		}
		String folderPath = path.substring(0, slash);
		Inboxes.Visibility visibility = inboxes.computeIfAbsent(folderPath, key -> {
			PathInfo folder = new PathInfo(spaceRoot, Paths.get(key));
			return Inboxes.isInbox(folder.toFile())
				? Inboxes.visibility(_auth, caller, folder, Privacy.PRIVATE)
				: Inboxes.Visibility.FULL;
		});
		switch (visibility) {
			case FULL:
				return path;
			case OWN:
				return Inboxes.contributedBy(spaceRoot.resolve(folderPath).toFile(), path.substring(slash + 1),
					caller.subject()) ? path : "";
			default:
				return "";
		}
	}

	/** Deletes the temporary files of an upload that is not stored. */
	private static void discard(List<UploadItem> uploads) {
		for (UploadItem upload : uploads) {
			upload.delete();
		}
	}

	/**
	 * A file with the given name in the given folder that does not exist yet.
	 *
	 * <p>
	 * A name clash is resolved by appending a number: nothing is ever overwritten. This is the one
	 * naming rule of this server: a move that collides at its target renames exactly like an
	 * upload does, see {@link MoveService}.
	 * </p>
	 */
	/**
	 * The file an upload of the given name is stored as: a free name, and for one of a raw pair sent
	 * in the same batch the name of a base free for both, see issue #191.
	 *
	 * @param batch
	 *        The names of the files of the request.
	 * @param reserved
	 *        The names reserved for the partners of pairs stored already, by the partner's name;
	 *        filled here.
	 */
	static File pairName(File folder, String name, List<String> batch, Map<String, String> reserved) {
		String wanted = reserved.remove(name);
		if (wanted != null) {
			return freeName(folder, wanted);
		}
		boolean raw = RawFile.isRawName(name);
		if (!raw && !RawPairs.isCompanionType(name)) {
			return freeName(folder, name);
		}
		String base = RawPairs.base(name);
		String partner = null;
		for (String other : batch) {
			if (!other.equals(name) && !reserved.containsKey(other) && RawPairs.base(other).equals(base)
				&& (raw ? RawPairs.isCompanionType(other) : RawFile.isRawName(other))) {
				partner = other;
				break;
			}
		}
		if (partner == null) {
			return freeName(folder, name);
		}
		Set<String> taken = new HashSet<>();
		String[] present = folder.list();
		if (present != null) {
			for (String file : present) {
				if (RawFile.isRawName(file) || RawPairs.isCompanionType(file)) {
					taken.add(RawPairs.base(file));
				}
			}
		}
		int dot = name.lastIndexOf('.');
		String stem = dot < 0 ? name : name.substring(0, dot);
		String candidate = stem;
		for (int n = 2; taken.contains(candidate.toLowerCase(Locale.ROOT)); n++) {
			candidate = stem + "-" + n;
		}
		String mine = RawPairs.withBaseOf(name, candidate + ".x");
		reserved.put(partner, RawPairs.withBaseOf(partner, candidate + ".x"));
		return freeName(folder, mine);
	}

	static File freeName(File folder, String fileName) {
		File targetFile = new File(folder, fileName);
		if (!targetFile.exists()) {
			return targetFile;
		}

		String extension = extension(fileName);
		String baseName = fileName.substring(0, fileName.length() - extension.length() - 1);
		int num = 2;
		do {
			targetFile = new File(folder, baseName + "-" + num + "." + extension);
			num++;
		} while (targetFile.exists());
		return targetFile;
	}

	/**
	 * Moves the received contents to their place in the album.
	 *
	 * <p>
	 * The target must not exist: the move fails rather than replacing an original.
	 * </p>
	 */
	private static void store(UploadItem upload, File target) throws IOException {
		Files.move(upload.getUpload().toPath(), target.toPath());
	}

	private static UploadedFile uploaded(String name, String storedAs, String hash, String status) {
		return UploadedFile.create().setName(name).setStoredAs(storedAs).setHash(hash).setStatus(status);
	}

	/**
	 * Answers the users of this server at <code>&lt;data&gt;/?type=users</code>, see issue #49.
	 *
	 * <p>
	 * Their names and roles, and since issue #55 their space, the day they arrived and how many
	 * devices they use: a member needs the names to share something, and the rest is what a
	 * management screen shows about people who all see each other anyway — there is nothing secret
	 * in a count. A guest is not shown the family's names, and an anonymous caller is not shown
	 * anything, see {@link AuthService#maySeeUsers(Caller)}.
	 * </p>
	 */
	private void serveUsers(Context context, Caller caller) throws IOException {
		if (!caller.isPaired()) {
			unauthorized(context, caller, false);
			return;
		}
		if (!_auth.maySeeUsers(caller)) {
			LOG.warning("Refusing the user list to '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.USERS_REFUSED);
			return;
		}

		UserList result = UserList.create();
		UserStore users = _auth.getUsers();
		if (users != null) {
			for (UserStore.User user : users.getUsers()) {
				if (user.getName().isEmpty() && !user.isPending()) {
					// The administrator's seat of a space nobody has signed into yet; there is
					// nobody there. A pending user is listed, and is marked as pending: somebody
					// was invited and has not arrived, which is exactly what the list should say.
					continue;
				}
				result.addUser(onTheWire(user));
			}
		}
		serveJsonObject(context.response(), result);
	}


	/**
	 * Answers the caller's own devices at <code>&lt;data&gt;/?type=devices</code>, see issue #55.
	 *
	 * <p>
	 * Every device the caller is signed in on, with the one that asked marked as the current one,
	 * and never a token or the hash of one: a device is named by its id, which is a name and not a
	 * secret. Only one's own — the administrator manages the users of this server, not other
	 * people's phones, and there is deliberately no endpoint that shows them.
	 * </p>
	 *
	 * <p>
	 * A share link and an invitation are refused: they are tokens, not sign-ins, and have no
	 * devices. The share link is told so (<code>403</code>); an invitation is anonymous on every
	 * endpoint but <code>?type=auth</code> and is answered <code>401</code> like anybody else who
	 * has not signed in, which is also the right answer — pairing is exactly what it should do.
	 * </p>
	 */
	private void serveDevices(Context context, Caller caller) throws IOException {
		if (caller.isShareLink()) {
			LOG.warning("Refusing the device list to the share link '" + caller.getShareLabel() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.DEVICES_REFUSED);
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, false);
			return;
		}
		serveJsonObject(context.response(), devices(caller));
	}

	/**
	 * Signs a device of the caller out at <code>&lt;data&gt;/?action=unpair</code>, see issue #55.
	 *
	 * <p>
	 * The body names the device by its {@link DeviceEntry#getId() id} and nothing else. Only the
	 * caller's own devices can be named: an id of somebody else's is refused exactly like an id
	 * nobody has, so that the endpoint says nothing about who holds what. Signing out the asking
	 * device is allowed and is the point of it — the answer still arrives, and the next request
	 * carrying that token is refused.
	 * </p>
	 *
	 * <p>
	 * The answer is what is left: the caller's remaining devices, as <code>?type=devices</code>
	 * answers them.
	 * </p>
	 */
	private void unpairDevice(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.isShareLink()) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.DEVICES_REFUSED);
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}

		DeviceEntry request;
		try {
			byte[] contents = readBody(context.request());
			request = DeviceEntry.readDeviceEntry(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unparsable unpair request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, DEVICE_UNREADABLE);
			return;
		}

		UserStore.Device device;
		try {
			device = _auth.unpair(caller, request.getId());
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing to sign out the device '" + request.getId() + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}
		LOG.info("Signed the device '" + device.getName() + "' of '" + caller.getUserName() + "' out.");
		serveJsonObject(context.response(), devices(caller));
	}

	/**
	 * Issues a code for a further device of the caller at
	 * <code>&lt;data&gt;/?action=device-code</code>, see issue #65.
	 *
	 * <p>
	 * Adding a device of one's own is not inviting somebody, and it is built so that it can never
	 * be mistaken for it: the answer is a code to type, not a link to send — no URL, no path
	 * segment, and nothing that is a bearer anywhere. It lives ten minutes, it works once, and
	 * whoever types it is signed in <em>as the caller</em>, which is why a device typing it appears
	 * in <code>?type=devices</code> at once and can be signed out there.
	 * </p>
	 *
	 * <p>
	 * The request needs no body: everything the code says — whom it signs in and which device
	 * asked — the server already knows from the token. A body may name somebody else
	 * ({@link DeviceCodeRequest}), which only an administrator may do: the <em>recovery code</em>
	 * of issue #89, for a person who lost every device they had. A share link is refused
	 * (<code>403</code>): it is nobody, and there is nobody for it to add a device to. An
	 * anonymous caller and an invitation bearer are answered <code>401</code>, exactly as
	 * <code>?type=devices</code> answers them.
	 * </p>
	 */
	private void createDeviceCode(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (_auth.getMode() == AuthMode.OFF) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.PAIRING_DISABLED);
			return;
		}
		if (caller.isShareLink()) {
			LOG.warning("Refusing a device code to the share link '" + caller.getShareLabel() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.DEVICE_CODE_REFUSED);
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}

		String userName;
		try {
			userName = deviceCodeTarget(context.request());
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable device code request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, DEVICE_CODE_UNREADABLE);
			return;
		}

		DeviceCodeStore.Issued issued;
		try {
			issued = _auth.deviceCode(caller, userName);
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing a device code to '" + caller.getUserName() + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}
		LOG.info("Issued " + issued.getRecord() + ".");
		serveJsonObject(context.response(), DeviceCodeCreated.create()
			.setCode(DeviceCodeStore.format(issued.getCode()))
			.setExpires(issued.getRecord().getExpires()));
	}

	/**
	 * Whom the given device-code request is for, the empty string for the caller themselves.
	 *
	 * <p>
	 * An empty body is the ordinary case and stays what it always was; a body that is a
	 * {@link DeviceCodeRequest} names the user the code signs in, see issue #89.
	 * </p>
	 */
	private static String deviceCodeTarget(HttpServletRequest request) throws IOException {
		byte[] contents = readBody(request);
		if (contents.length == 0) {
			return "";
		}
		DeviceCodeRequest parsed = DeviceCodeRequest.readDeviceCodeRequest(new JsonReader(
			new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		return parsed.getUserName() == null ? "" : parsed.getUserName();
	}

	/**
	 * Issues the caller's own backup code at <code>&lt;data&gt;/?action=backup-code</code>, see
	 * issue #92.
	 *
	 * <p>
	 * The way back from signing out of one's last device: a code the caller writes down today and
	 * types on some device on some later day. The answer is a {@link DeviceCodeCreated} like every
	 * other code's, with an empty {@link DeviceCodeCreated#getExpires() expiry} saying that this
	 * one does not run out — shown once, never again. Making one withdraws the one the caller
	 * had, so that there is never more than one piece of paper in the world.
	 * </p>
	 *
	 * <p>
	 * Always for the caller themselves, whatever the body says: making a code somebody else keeps
	 * is a different thing and is the administrator's ten-minute recovery code, see
	 * {@link #createDeviceCode(Context)}.
	 * </p>
	 */
	private void createBackupCode(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		DeviceCodeStore.Issued issued;
		try {
			issued = _auth.backupCode(caller);
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing a backup code: " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}
		LOG.info("Issued " + issued.getRecord() + ".");
		serveJsonObject(context.response(), DeviceCodeCreated.create()
			.setCode(DeviceCodeStore.format(issued.getCode()))
			.setExpires(issued.getRecord().getExpires()));
	}

	/**
	 * Withdraws the caller's own backup code at
	 * <code>&lt;data&gt;/?action=revoke-backup-code</code>, see issue #92.
	 *
	 * <p>
	 * The answer is what is left, as every device endpoint answers: the caller's devices, now
	 * saying that there is no backup code any more. A caller who has none is told so
	 * (<code>404</code>) rather than quietly congratulated.
	 * </p>
	 */
	private void revokeBackupCode(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		try {
			_auth.revokeBackupCode(caller);
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing to withdraw a backup code: " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}
		LOG.info("Withdrew the backup code of '" + caller.getUserName() + "'.");
		serveJsonObject(context.response(), devices(caller));
	}

	/** How a member signs a new browser in besides a code, see {@link AuthInfo#getSignInMethods()}. */
	private List<ProofMethod> memberSignInMethods() {
		List<ProofMethod> result = new ArrayList<>();
		if (_auth.getUsers() == null) {
			return result;
		}
		if (_auth.getTotp() != null) {
			result.add(ProofMethod.create().setName(TotpSignIns.METHOD));
		}
		if (_passkeys.isAvailable()) {
			result.add(ProofMethod.create().setName(Passkeys.METHOD));
		}
		result.addAll(addressProof().contactMethods());
		return result;
	}

	/** The caller's own devices as the protocol carries them, the asking one marked. */
	private DeviceList devices(Caller caller) {
		DeviceList result = DeviceList.create();
		if (caller.isPaired() && !caller.isShareLink() && caller.getUser() != null) {
			// Their ways to sign in on a new browser besides a code (issue #233).
			result.setSignIns(SignInActions.signIns(caller.getUser(), _passkeys));
		}
		// Not a code and never one: only that there is one and since when, see issue #92.
		result.setBackupCodeCreated(_auth.backupCodeCreated(caller));
		for (UserStore.Device device : _auth.devices(caller)) {
			result.addDevice(DeviceEntry.create()
				.setId(device.getId())
				.setName(device.getName())
				.setCreated(device.getCreated())
				.setCurrent(device.getId().equals(caller.getDeviceId())));
		}
		return result;
	}



	/**
	 * Answers the share links covering the addressed folder at
	 * <code>&lt;folder&gt;/?type=shares</code>, see issue #51.
	 *
	 * <p>
	 * The links on the folder and on every folder above it within the space, the nearest one first,
	 * expired ones included; a deleted one is gone (issue #217). Only the owner of the space and the administrator
	 * may ask, exactly as for the grants — and no answer ever carries a token: the token is shown
	 * once, when the link is made, and never again.
	 * </p>
	 */
	private void serveShares(Context context, Caller caller, Location location) throws IOException {
		if (!caller.isPaired()) {
			unauthorized(context, caller, false);
			return;
		}
		if (!_auth.mayShareLinks(caller)) {
			LOG.warning("Refusing the share links of '" + location.getOwner() + "' to '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARING_REFUSED);
			return;
		}

		ShareLinkList result = ShareLinkList.create();
		ShareStore shares = _auth.getShares();
		if (shares != null) {
			// Counted once, and only where a personal link is listed (issue #203).
			Contributions.Counts counts = null;
			for (ShareStore.Link link : shares.covering(location.getOwner(), location.getOwnerPath())) {
				// A link belongs to whoever handed it out; an administrator of the space sees them
				// all, because keeping the space in order is what an administrator is for (#84).
				if (mine(caller, link)) {
					if (counts == null && link.isPersonal()) {
						counts = contributions().count();
					}
					result.addLink(onTheWire(link, counts));
				}
			}
		}
		serveJsonObject(context.response(), result);
	}

	/**
	 * Creates a share link on the addressed folder at <code>&lt;folder&gt;/?action=share</code>,
	 * see issue #51.
	 *
	 * <p>
	 * Two records in one step: the {@link ShareStore.Link} that says what the link shows and how
	 * long it lives, and the {@link GrantStore.Grant} to its <code>token:&lt;id&gt;</code> subject
	 * that says what it may do. The grant is the one mechanism every other sharing uses, so a link
	 * needs no second answer to "may this caller do that here".
	 * </p>
	 *
	 * <p>
	 * The token travels back exactly once, in the {@link ShareLinkCreated}: this server keeps its
	 * hash and can never show it again. A lost link is deleted and made anew.
	 * </p>
	 */
	private void createShare(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayShareLinks(caller)) {
			LOG.warning("Refusing to share the library of '" + location.getOwner() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARING_REFUSED);
			return;
		}
		if (location.getOwner().isEmpty()) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.PATH_ESCAPED);
			return;
		}

		if (Inboxes.isInbox(location.getPath().toFile())) {
			// An inbox holds photographs nobody has looked at yet; a link to it would hand them
			// out unseen, see issue #131.
			LOG.warning("Refusing to share the inbox at '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, Inboxes.INBOX_NOT_SHARED);
			return;
		}

		ShareLink request = readShareLink(context);
		if (request == null) {
			return;
		}
		LinkOptions options = linkOptions(context, request);
		if (options == null) {
			return;
		}
		Set<String> rights = options._rights;
		int maxPrivacy = options._maxPrivacy;
		int minRating = options._minRating;
		String expires = options._expires;
		String label = options._label;
		String photoLabel = options._photoLabel;

		if (!mayGive(context, caller, location, null, options)) {
			return;
		}

		boolean personal = request.getType() == de.haumacher.imageServer.shared.model.ShareType.PERSONAL;
		if (!personal) {
			if (!request.getRecipients().isEmpty()) {
				LOG.warning("Refusing an anonymous share link with recipients.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.ANONYMOUS_RECIPIENTS);
				return;
			}
			// The link is the permission (issue #83): what it may do is recorded on the link
			// itself, and there is no grant beside it any more.
			ShareStore.Issued issued = _auth.getShares().create(location.getOwner(), location.getOwnerPath(),
				label, expires, maxPrivacy, minRating, rights, caller.getUserName());
			ShareStore.Link link = issued.getLink();
			_auth.getShares().setPhotoLabel(link, photoLabel);

			LOG.info("Created the share link " + link + " with " + rights + ".");
			serveJsonObject(context.response(), ShareLinkCreated.create()
				.setLink(onTheWire(link))
				.setToken(issued.getToken())
				.setUrl(shareUrl(context, issued.getToken())));
			return;
		}

		// A personal link (issue #198): every recipient is read before anything is written, so that
		// one unreadable address refuses the whole request and enters nobody.
		ContactStore contacts = _auth.getContacts();
		List<ContactStore.Request> recipients = new ArrayList<>();
		for (de.haumacher.imageServer.shared.model.ShareRecipient recipient : request.getRecipients()) {
			List<String[]> addresses = new ArrayList<>();
			for (de.haumacher.imageServer.shared.model.ContactAddress address : recipient.getAddresses()) {
				addresses.add(new String[] { PersonalLinks.kind(address.getKind()), address.getValue() });
			}
			try {
				ContactStore.Request read = contacts.read(recipient.getContact(), recipient.getName(), addresses);
				ContactStore.Contact known = read.getId().isEmpty() ? null : contacts.get(read.getId());
				if (known != null && known.isBlocked()) {
					throw new ContactStore.Refused(PersonalLinks.contactBlocked(known.getName()));
				}
				recipients.add(read);
			} catch (ContactStore.Refused ex) {
				LOG.warning("Refusing a recipient of a personal link: " + ex.getMessage());
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
				return;
			}
		}
		List<ContactStore.Contact> entered = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		for (ContactStore.Request recipient : recipients) {
			ContactStore.Contact contact = contacts.enter(recipient, caller.getUserName());
			if (contact.isBlocked()) {
				// Entered by an address the register holds for somebody shut out of the space.
				LOG.warning("Refusing the blocked contact " + contact + " as a recipient.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.contactBlocked(contact.getName()));
				return;
			}
			if (!ids.contains(contact.getId())) {
				ids.add(contact.getId());
				entered.add(contact);
			}
		}
		ShareStore.IssuedPersonal issued = _auth.getShares().createPersonal(location.getOwner(),
			location.getOwnerPath(), label, expires, maxPrivacy, minRating, rights, caller.getUserName(), ids);
		ShareStore.Link link = issued.getIssued().getLink();
		_auth.getShares().setPhotoLabel(link, photoLabel);

		LOG.info("Created the personal share link " + link + " with " + rights + " for " + entered.size()
			+ " recipient(s).");
		ShareLinkCreated result = ShareLinkCreated.create()
			.setLink(onTheWire(link))
			.setToken(issued.getIssued().getToken())
			.setUrl(shareUrl(context, issued.getIssued().getToken()));
		for (ContactStore.Contact contact : entered) {
			result.addRecipient(recipientLink(context, contact, issued.getTokens().get(contact.getId())));
		}
		serveJsonObject(context.response(), result);
	}

	/**
	 * The options of a share link as a request gives them, see {@link #linkOptions}: what
	 * <code>?action=share</code> creates a link with and <code>?action=edit-share</code> changes it
	 * to (issue #269).
	 */
	private static final class LinkOptions {
		Set<String> _rights;

		int _maxPrivacy;

		int _minRating;

		String _expires;

		String _label;

		String _photoLabel;
	}

	/**
	 * Reads the options of the given request, each checked on its own; <code>null</code> where one
	 * is refused (the response is complete). Whether the caller may give them is the question of
	 * the endpoint.
	 */
	private static LinkOptions linkOptions(Context context, ShareLink request) throws IOException {
		LinkOptions result = new LinkOptions();
		Set<String> rights = new LinkedHashSet<>();
		for (de.haumacher.imageServer.shared.model.RightName right : request.getRights()) {
			String name = right.getName();
			if (Rights.EDIT.equals(name)) {
				LOG.warning("Refusing a share link that would allow editing.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.SHARE_EDIT_REFUSED);
				return null;
			}
			if (!Rights.isKnown(name)) {
				LOG.warning("Refusing a share link with the unknown right '" + name + "'.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.unknownRight(name));
				return null;
			}
			rights.add(name);
		}
		if (rights.isEmpty()) {
			// A link that allows nothing would be a link to nothing; looking is the least it does.
			rights.add(Rights.VIEW);
		}
		result._rights = rights;

		result._maxPrivacy = request.getMaxPrivacy();
		if (result._maxPrivacy < Privacy.PUBLIC || result._maxPrivacy > Privacy.PRIVATE) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.SHARE_PRIVACY_REFUSED);
			return null;
		}
		result._minRating = request.getMinRating();
		if (!Ratings.isKnown(result._minRating)) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.SHARE_RATING_REFUSED);
			return null;
		}
		String expires = request.getExpires() == null ? "" : request.getExpires().trim();
		if (!expires.isEmpty()) {
			try {
				expires = java.time.Instant.parse(expires).toString();
			} catch (java.time.DateTimeException ex) {
				LOG.warning("Refusing the share link expiry '" + expires + "': " + ex.getMessage());
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.SHARE_EXPIRY_REFUSED);
				return null;
			}
		}
		result._expires = expires;
		result._label = request.getLabel() == null ? "" : request.getLabel().trim();
		result._photoLabel = request.getPhotoLabel() == null ? "" : request.getPhotoLabel();
		return result;
	}

	/**
	 * Whether the caller may give a link the given options, the one rule of creating a link
	 * (<code>?action=share</code>, anonymous and addressed alike) and of changing one
	 * (<code>?action=edit-share</code>), see issues #84, #269 and #282; answers the refusal where
	 * not.
	 *
	 * <p>
	 * Nobody hands out more than they hold, and it is said rather than quietly trimmed: a link
	 * answered with less than was asked for would be a link its maker believes does more than it
	 * does. Refused, each with its reason: a right the caller may not use themself, a privacy
	 * ceiling above their clearance, an expiry in the past, and a label the album does not have. On
	 * an edit each is asked only where it changes, so that what a link already had never blocks an
	 * edit of something else; a new link has nothing yet, so everything it asks for is asked.
	 * </p>
	 *
	 * @param location
	 *        Where the request was sent; the folder of a new link.
	 * @param link
	 *        The link being changed, <code>null</code> for one being created.
	 */
	private boolean mayGive(Context context, Caller caller, Location location, ShareStore.Link link,
			LinkOptions options) throws IOException {
		Set<String> had = link == null ? Rights.NONE : link.getRights();
		Set<String> held = _auth.rights(caller, location.getPath());
		for (String right : Rights.closure(options._rights)) {
			if (!had.contains(right) && !held.contains(right)) {
				LOG.warning("Refusing to give a share link '" + right + "', by '" + caller.getUserName() + "'.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.shareRightNotHeld(right));
				return false;
			}
		}
		int clearance = _auth.clearance(caller, location.getPath());
		if ((link == null || options._maxPrivacy != link.getMaxPrivacy()) && options._maxPrivacy > clearance) {
			LOG.warning("Refusing a share link showing more than '" + caller.getUserName() + "' may see.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.shareAboveClearance(clearance));
			return false;
		}
		if (!options._expires.isEmpty() && (link == null || !options._expires.equals(link.getExpires()))
			&& !java.time.Instant.parse(options._expires).isAfter(java.time.Instant.now())) {
			LOG.warning("Refusing a share link expiring in the past, by '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.SHARE_EXPIRY_PAST);
			return false;
		}
		if (!options._photoLabel.isEmpty() && (link == null || !options._photoLabel.equals(link.getPhotoLabel()))) {
			PathInfo folder = location.getPath();
			if (link != null) {
				try {
					folder = _auth.resolve(caller, _basePath, link.getPath()).getPath();
				} catch (AuthService.PathRefused ex) {
					errorInfo(context, ex.getStatus(), ex.getMessage());
					return false;
				}
			}
			if (!labelOfAlbum(context, folder, options._photoLabel)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether the given label is one of the album at the given folder, which a link showing its
	 * photographs needs; answers the refusal where not.
	 */
	private boolean labelOfAlbum(Context context, PathInfo folder, String photoLabel) throws IOException {
		// Labels belong to one album (issue #213): a link to a folder of folders showing "a
		// label" would show whatever photographs happen to carry a label of that spelling in
		// any album below, today's and tomorrow's, which nobody decided.
		Resource target = _cache.lookup(folder);
		if (!(target instanceof AlbumInfo)) {
			LOG.warning("Refusing a label filter on the folder '" + folder + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, Labels.LABEL_NEEDS_ALBUM);
			return false;
		}
		if (!Labels.of((AlbumInfo) target).contains(photoLabel)) {
			LOG.warning("Refusing the unknown label '" + photoLabel + "' at '" + folder + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, Labels.unknownLabel(photoLabel));
			return false;
		}
		return true;
	}

	/** The link of one recipient as it is answered once, see issue #198. */
	private de.haumacher.imageServer.shared.model.RecipientLink recipientLink(Context context,
			ContactStore.Contact contact, String token) {
		return de.haumacher.imageServer.shared.model.RecipientLink.create()
			.setContact(contact.getId())
			.setName(contact.getName())
			.setAddresses(PersonalLinks.addresses(contact))
			.setToken(token)
			.setUrl(shareUrl(context, token));
	}

	/**
	 * Answers the contacts of the space at <code>&lt;data&gt;/?type=contacts</code>, see issue #198.
	 *
	 * <p>
	 * To every signed-in member, whatever their role &mdash; "when you share photos, you also share
	 * contacts" &mdash; and never to a share link or an anonymous caller. A server without
	 * authentication has no share links and no contacts, and answers the empty register.
	 * </p>
	 */
	private void serveContacts(Context context, Caller caller) throws IOException {
		if (caller.isShareLink()) {
			LOG.warning("Refusing the contacts to the share link '" + caller.getShareLabel() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, PersonalLinks.CONTACTS_REFUSED);
			return;
		}
		if (!caller.isPaired() && _auth.getMode() != AuthMode.OFF) {
			unauthorized(context, caller, false);
			return;
		}
		ContactStore contacts = _auth.getContacts();
		serveJsonObject(context.response(), PersonalLinks.contacts(contacts, _auth.getShares(),
			contacts == null ? Contributions.Counts.NONE : contributions().count()));
	}

	/**
	 * The first open of a recipient's own link at <code>&lt;data&gt;/?action=identify</code>, see
	 * issue #198.
	 *
	 * <p>
	 * The recipient's token is the bearer and the body a {@link ContactIdentify}; the answer is the
	 * {@link ContactCredential} that recognises the contact from now on, given exactly once. Every
	 * other caller is answered what it would be answered anywhere: a second open the refusal asking
	 * who they are, everybody else <code>400</code>.
	 * </p>
	 */
	private void identify(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.isShareGone()) {
			gone(context, caller);
			return;
		}
		ContactIdentify request;
		try {
			byte[] contents = readBody(context.request());
			request = contents.length == 0 ? ContactIdentify.create()
				: ContactIdentify.readContactIdentify(new JsonReader(new ReaderAdapter(
					new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable identification: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.UNREADABLE);
			return;
		}
		ContactStore.Issued issued;
		try {
			issued = _auth.identify(caller, request.isRemember(), request.getDisplayName());
		} catch (AuthService.Refused ex) {
			if (caller.mustIdentify() && ex.getStatus() != HttpServletResponse.SC_GONE) {
				// Somebody else's first open won, or this was none: asked who they are, as anywhere.
				Caller now = _auth.caller(context.request());
				if (now.mustIdentify() || now.isShareGone()) {
					gone(context, now);
					return;
				}
			}
			LOG.warning("Refusing an identification: " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}
		LOG.info("Identified " + issued.getContact() + " through "
			+ (caller.getShare() == null ? "their overview link" : "the share link " + caller.getShare().getId())
			+ (request.isRemember() ? ", remembered." : "."));
		serveJsonObject(context.response(), ContactCredential.create()
			.setCredential(issued.getCredential())
			.setExpires(issued.getSession().getExpires())
			.setRemember(issued.getSession().isRemember())
			.setContact(AuthService.contactInfo(issued.getContact())));
	}

	/**
	 * Mails a code that proves an e-mail address, <code>&lt;data&gt;/?action=prove-email</code>,
	 * see issue #199.
	 *
	 * <p>
	 * The body is an {@link EmailProof}, the answer an {@link EmailProofSent} &mdash; the same
	 * whether or not the space knows the address. Who may name which address is
	 * {@link AddressProof}'s decision; the code, its rate limits and its mail are
	 * {@link EmailProofs}'.
	 * </p>
	 */
	private void proveEmail(Context context) throws IOException {
		Caller caller = proofCaller(context);
		if (caller == null) {
			return;
		}
		EmailProof request;
		try {
			byte[] contents = readBody(context.request());
			request = EmailProof.readEmailProof(new JsonReader(new ReaderAdapter(
				new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable request for a code: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AddressProof.PROOF_UNREADABLE);
			return;
		}
		AddressProof proof = addressProof();
		try {
			AddressProof.Target target = proof.target(caller, request.getAddress(), request.getChoice());
			serveJsonObject(context.response(), proof.prove(target, context.request().getRemoteAddr(),
				context.request().getHeader("Accept-Language"), spaceBase(context)));
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing a request for a code: " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
		} catch (EmailProofs.Refused ex) {
			proofRefused(context, ex);
		}
	}

	/**
	 * Proves an e-mail address with the mailed code, <code>&lt;data&gt;/?action=verify-email</code>,
	 * see issue #199.
	 *
	 * <p>
	 * The body is an {@link EmailVerify}; the answer the {@link ContactCredential} the proven address
	 * makes of the caller, see {@link AddressProof#verify}.
	 * </p>
	 */
	private void verifyEmail(Context context) throws IOException {
		Caller caller = proofCaller(context);
		if (caller == null) {
			return;
		}
		EmailVerify request;
		try {
			byte[] contents = readBody(context.request());
			request = EmailVerify.readEmailVerify(new JsonReader(new ReaderAdapter(
				new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable code: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AddressProof.PROOF_UNREADABLE);
			return;
		}
		AddressProof proof = addressProof();
		try {
			AddressProof.Target target = proof.target(caller, request.getAddress(), request.getChoice());
			serveJsonObject(context.response(), proof.verify(caller, target, request.getCode(),
				context.request().getRemoteAddr(), request.isRemember(), request.getDisplayName(),
				request.getDeviceName()));
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing a code: " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
		} catch (EmailProofs.Refused ex) {
			proofRefused(context, ex);
		}
	}

	/**
	 * The caller of a proof request, <code>null</code> where the response is complete.
	 *
	 * <p>
	 * A link that is gone is answered so first; then a server without a mail account
	 * {@link EmailProofs#NOT_CONFIGURED}, whoever asks; then a caller with nothing to prove here
	 * what it is answered anywhere &mdash; the refusal of its personal link, or
	 * {@link AddressProof#PROOF_NOT_HERE}.
	 * </p>
	 */
	private Caller proofCaller(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.isShareGone()) {
			gone(context, caller);
			return null;
		}
		if (!_proofs.isAvailable()) {
			LOG.warning("Refusing a proof of an address: no mail account is configured.");
			errorInfo(context, HttpServletResponse.SC_NOT_IMPLEMENTED, EmailProofs.NOT_CONFIGURED);
			return null;
		}
		if (!addressProof().mayProve(caller)) {
			if (caller.mustIdentify()) {
				gone(context, caller);
			} else {
				LOG.warning("Refusing a proof of an address outside a personal link.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AddressProof.PROOF_NOT_HERE);
			}
			return null;
		}
		return caller;
	}

	/**
	 * Starts a sign-in through a provider of OpenID Connect, <code>&lt;data&gt;/?action=oidc-start</code>,
	 * see issue #200.
	 *
	 * <p>
	 * The body is an {@link OidcStart} (the provider may also be named by the parameter
	 * <code>provider</code>), the answer an {@link OidcStarted}: the provider's address to navigate
	 * to and the binding the exchange must show. The sign-in returns to the very link it was started
	 * on, spelled from <code>VALBUM_PUBLIC_URL</code> in the coordinates of this request.
	 * </p>
	 */
	private void oidcStart(Context context) throws IOException {
		Caller caller = oidcCaller(context);
		if (caller == null) {
			return;
		}
		OidcStart request;
		try {
			byte[] contents = readBody(context.request());
			request = contents.length == 0 ? OidcStart.create()
				: OidcStart.readOidcStart(new JsonReader(new ReaderAdapter(
					new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable start of a sign-in: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AddressProof.PROOF_UNREADABLE);
			return;
		}
		String id = request.getProvider().isEmpty() ? context.getParameter("provider") : request.getProvider();
		OidcProvider provider = _oidc.provider(id == null ? "" : id);
		if (provider == null) {
			LOG.warning("Refusing a sign-in with the unknown provider '" + id + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, OidcLogins.providerUnknown(id == null ? "" : id));
			return;
		}
		AddressProof.Target target = addressProof().providerTarget(caller);
		// A member's sign-in (issue #233) has no link: it returns to the application itself. One at
		// the overview of issue #267 returns to the overview, or to the album of it it began in.
		String bearer = bearer(context.request());
		String returnUrl = _oidc.getPublicUrl() + appBase(context) + "/"
			+ (target.isMember() ? ""
				: bearer.isEmpty() ? sharedBase(context.request())
					: ShareStore.URL_SEGMENT + "/" + bearer + "/");
		try {
			OidcLogins.Started started = _oidc.start(provider, new OidcLogins.Start(_space, linkId(target),
				target._kind.name(), whom(target), returnUrl, request.isRemember(), request.getDisplayName()));
			serveJsonObject(context.response(), OidcStarted.create()
				.setUrl(started.getUrl())
				.setBinding(started.getBinding())
				.setExpires(started.getExpires().toString())
				.setReturnUrl(returnUrl));
		} catch (OidcLogins.Refused ex) {
			LOG.warning("Refusing a sign-in with " + provider + ": " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
		}
	}

	/**
	 * Finishes a sign-in through a provider of OpenID Connect,
	 * <code>&lt;data&gt;/?action=oidc-exchange</code>, see issue #200.
	 *
	 * <p>
	 * The body is an {@link OidcExchange}; the code works once, in this space, on the link it was
	 * started on and with its binding. Everything a mailed code is asked is then asked of the caller
	 * as they are now, and the answer is the {@link ContactCredential} a right code gives &mdash; or
	 * the refusal the provider's answer or the link met.
	 * </p>
	 */
	private void oidcExchange(Context context) throws IOException {
		Caller caller = oidcCaller(context);
		if (caller == null) {
			return;
		}
		OidcExchange request;
		try {
			byte[] contents = readBody(context.request());
			request = OidcExchange.readOidcExchange(new JsonReader(new ReaderAdapter(
				new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable exchange of a sign-in: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AddressProof.PROOF_UNREADABLE);
			return;
		}
		OidcLogins.Proven proven = _oidc.redeem(_space, request.getCode(), request.getBinding());
		AddressProof.Target target = addressProof().providerTarget(caller);
		if (proven == null || !proven.getStart().getLink().equals(linkId(target))
			|| !proven.getStart().getKind().equals(target._kind.name())
			|| !proven.getStart().getContact().equals(whom(target))) {
			LOG.warning("Refusing the exchange of a sign-in: " + (proven == null ? "unknown code" : "another link")
				+ ".");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, OidcLogins.EXCHANGE_UNKNOWN);
			return;
		}
		if (proven.isRefused()) {
			LOG.warning("Answering a refused sign-in with " + proven.getProvider() + ": " + proven.getRefusal());
			errorInfo(context, proven.getStatus(), proven.getRefusal());
			return;
		}
		String displayName = proven.getStart().getDisplayName().isEmpty() ? proven.getName()
			: proven.getStart().getDisplayName();
		try {
			serveJsonObject(context.response(), addressProof().provenByProvider(caller, target, proven.getEmail(),
				proven.getStart().isRemember(), displayName, proven.getProvider(), request.getDeviceName()));
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing a sign-in with " + proven.getProvider() + ": " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
		}
	}

	/**
	 * The caller of a sign-in request, <code>null</code> where the response is complete: like
	 * {@link #proofCaller(Context)}, with the server's providers in place of its mail account.
	 */
	private Caller oidcCaller(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.isShareGone()) {
			gone(context, caller);
			return null;
		}
		if (!_oidc.isAvailable()) {
			LOG.warning("Refusing a sign-in: no provider is offered.");
			errorInfo(context, HttpServletResponse.SC_NOT_IMPLEMENTED, OidcLogins.NOT_CONFIGURED);
			return null;
		}
		if (addressProof().providerTarget(caller) == null) {
			if (caller.mustIdentify()) {
				gone(context, caller);
			} else {
				LOG.warning("Refusing a sign-in outside a personal link.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AddressProof.PROOF_NOT_HERE);
			}
			return null;
		}
		return caller;
	}

	/** The id of the link a sign-in through a provider was started on, empty for a member's. */
	private static String linkId(AddressProof.Target target) {
		return target._link == null ? "" : target._link.getId();
	}

	/**
	 * Whom a sign-in through a provider is for: the contact the link asks for, the member adding an
	 * address (issue #233), empty for nobody.
	 */
	private static String whom(AddressProof.Target target) {
		if (target._user != null) {
			return "user:" + target._user.getName();
		}
		return target._contact == null ? "" : target._contact.getId();
	}

	/**
	 * Where a sign-in at the overview of issue #267 returns to, below the application's base:
	 * <code>shared/</code>, or <code>shared/&lt;link id&gt;/</code> for an album of it that names a
	 * link of this space &mdash; never a value the request made up.
	 */
	private String sharedBase(HttpServletRequest request) {
		String value = request.getHeader(AuthService.SHARED_HEADER);
		String id = value == null ? "" : value.trim();
		boolean link = !id.isEmpty() && !AuthService.SHARED_ALL.equals(id) && _auth.getShares() != null
			&& _auth.getShares().get(id) != null;
		return AuthService.SHARED_SEGMENT + "/" + (link ? id + "/" : "");
	}

	/** The bearer token of the given request, empty where it carries none. */
	private static String bearer(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return "";
		}
		return header.substring(7).trim();
	}

	/** Answers a refused code, with the time to wait where waiting helps. */
	private static void proofRefused(Context context, EmailProofs.Refused ex) throws IOException {
		LOG.warning("Refusing a code: " + ex.getMessage());
		if (ex.getRetryAfter() > 0) {
			context.response().setHeader("Retry-After", Long.toString(ex.getRetryAfter()));
		}
		errorInfo(context, ex.getStatus(), ex.getMessage());
	}

	/**
	 * The share link a management request about a personal link names, <code>null</code> if the
	 * response is complete, see issue #198.
	 *
	 * <p>
	 * The same rights as deleting it: the share flag, and the link the caller's own or the
	 * caller an administrator; a link of somebody else's is a link this request never saw.
	 * </p>
	 */
	private ShareStore.Link managedLink(Context context, Caller caller, Location location, String id)
			throws IOException {
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return null;
		}
		if (!_auth.mayShareLinks(caller)) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARING_REFUSED);
			return null;
		}
		ShareStore.Link link = reachedLink(context, caller, location, id);
		if (link == null) {
			return null;
		}
		if (!link.isPersonal()) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.NOT_PERSONAL);
			return null;
		}
		return link;
	}

	/**
	 * Sends one recipient of a personal link a fresh link of their own at
	 * <code>&lt;folder&gt;/?action=resend</code>, see issue #198.
	 *
	 * <p>
	 * The recipient's earlier link is void at once, and the fresh one identifies them on its first
	 * open like the first one did. Answered with a {@link ShareLinkCreated} carrying the one
	 * {@link de.haumacher.imageServer.shared.model.RecipientLink}, the link's own token not again.
	 * </p>
	 */
	private void resend(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		ShareResend request;
		try {
			byte[] contents = readBody(context.request());
			request = ShareResend.readShareResend(new JsonReader(new ReaderAdapter(
				new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.UNREADABLE);
			return;
		}
		ShareStore.Link link = managedLink(context, caller, location, request.getLink());
		if (link == null) {
			return;
		}
		ContactStore.Contact contact = _auth.getContacts().get(request.getContact());
		if (contact == null || link.recipient(contact.getId()) == null) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, PersonalLinks.NOT_A_RECIPIENT);
			return;
		}
		String token = _auth.getShares().resend(link.getId(), contact.getId());
		LOG.info("Sent the share link " + link + " again to " + contact + ".");
		serveJsonObject(context.response(), ShareLinkCreated.create()
			.setLink(onTheWire(link))
			.addRecipient(recipientLink(context, contact, token)));
	}

	/**
	 * Shuts a contact out of one personal link at <code>&lt;folder&gt;/?action=shut-out</code>, or
	 * lets them in again, see issue #198.
	 *
	 * <p>
	 * The contact's credentials opened through this link end at once, and no credential opens the
	 * link for them any more. Answered with the link as <code>?type=shares</code> lists it.
	 * </p>
	 */
	private void shutOut(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		ContactShutOut request = readShutOut(context);
		if (request == null) {
			return;
		}
		ShareStore.Link link = managedLink(context, caller, location, request.getLink());
		if (link == null) {
			return;
		}
		ContactStore.Contact contact = _auth.getContacts().get(request.getContact());
		if (contact == null) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, ContactStore.unknownContact(request.getContact()));
			return;
		}
		_auth.getShares().shutOut(link.getId(), contact.getId(), request.isShutOut());
		if (request.isShutOut()) {
			int ended = _auth.getContacts().endSessions(contact.getId(), link.getId());
			LOG.info("Shut " + contact + " out of the share link " + link + "; ended " + ended + " session(s).");
		} else {
			LOG.info("Let " + contact + " into the share link " + link + " again.");
		}
		serveJsonObject(context.response(),
			ShareLinkList.create().addLink(onTheWire(link, contributions().count())));
	}

	/**
	 * Shuts a contact out of every link of the space at <code>&lt;data&gt;/?action=block-contact</code>,
	 * or lets them in again, see issue #198.
	 *
	 * <p>
	 * Every credential of the contact ends at once. The contacts are the space's, shared by every
	 * member who shares, so whoever may create share links may do it. Answered with the contact.
	 * </p>
	 */
	private void blockContact(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayShareLinks(caller)) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARING_REFUSED);
			return;
		}
		ContactShutOut request = readShutOut(context);
		if (request == null) {
			return;
		}
		ContactStore.Contact contact = _auth.getContacts().block(request.getContact(), request.isShutOut());
		if (contact == null) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, ContactStore.unknownContact(request.getContact()));
			return;
		}
		LOG.info((request.isShutOut() ? "Shut " + contact + " out of" : "Let " + contact + " into")
			+ " every link of the space, by '" + caller.getUserName() + "'.");
		serveJsonObject(context.response(), contactOnTheWire(contact));
	}

	/**
	 * Whether the caller of the given request may manage the contacts of the space, see issue #203;
	 * answers the refusal where not.
	 *
	 * <p>
	 * The rights of <code>?action=block-contact</code> (issue #198): a signed-in member with the
	 * share flag, the register being the space's and shared by every member who shares. A share
	 * link &mdash; a contact's session included &mdash; is refused, an anonymous caller asked to
	 * sign in.
	 * </p>
	 *
	 * @return The caller, <code>null</code> where the request was answered.
	 */
	Caller contactManager(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.isShareLink()) {
			LOG.warning("Refusing to manage the contacts through the share link '" + caller.getShareLabel() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, PersonalLinks.CONTACTS_MANAGE_REFUSED);
			return null;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return null;
		}
		if (!_auth.mayShareLinks(caller) || _auth.getContacts() == null) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, PersonalLinks.CONTACTS_MANAGE_REFUSED);
			return null;
		}
		return caller;
	}

	/**
	 * Gives a contact another name in the space at <code>&lt;data&gt;/?action=rename-contact</code>,
	 * see issue #203.
	 *
	 * <p>
	 * What their uploads are labelled with was copied at the upload and stays. Answered with the
	 * contact as <code>?type=contacts</code> lists it.
	 * </p>
	 */
	private void renameContact(Context context) throws IOException {
		Caller caller = contactManager(context);
		if (caller == null) {
			return;
		}
		ContactRename request;
		try {
			request = ContactRename.readContactRename(new JsonReader(new ReaderAdapter(
				new InputStreamReader(new ByteArrayInputStream(readBody(context.request())), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.UNREADABLE);
			return;
		}
		if (request.getName().trim().isEmpty()) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.CONTACT_NAME_REQUIRED);
			return;
		}
		ContactStore.Contact contact;
		try {
			contact = _auth.getContacts().rename(request.getContact(), request.getName());
		} catch (ContactStore.Refused ex) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.CONTACT_NAME_REQUIRED);
			return;
		}
		if (contact == null) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, ContactStore.unknownContact(request.getContact()));
			return;
		}
		LOG.info("Renamed " + contact + ", by '" + caller.getUserName() + "'.");
		serveJsonObject(context.response(), contactOnTheWire(contact));
	}

	/**
	 * Ends one browser session of a contact, or all of them, at
	 * <code>&lt;data&gt;/?action=end-contact-session</code>, see issue #203.
	 *
	 * <p>
	 * The credential of that session opens nothing from then on. Answered with the contact.
	 * </p>
	 */
	private void endContactSession(Context context) throws IOException {
		Caller caller = contactManager(context);
		if (caller == null) {
			return;
		}
		ContactSessionEnd request;
		try {
			request = ContactSessionEnd.readContactSessionEnd(new JsonReader(new ReaderAdapter(
				new InputStreamReader(new ByteArrayInputStream(readBody(context.request())), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.UNREADABLE);
			return;
		}
		ContactStore contacts = _auth.getContacts();
		ContactStore.Contact contact = contacts.get(request.getContact());
		if (contact == null) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, ContactStore.unknownContact(request.getContact()));
			return;
		}
		if (request.getSession().isEmpty()) {
			int ended = contacts.endSessions(contact.getId(), null);
			LOG.info("Ended " + ended + " session(s) of " + contact + ", by '" + caller.getUserName() + "'.");
		} else if (contacts.endSession(contact.getId(), request.getSession())) {
			LOG.info("Ended a session of " + contact + ", by '" + caller.getUserName() + "'.");
		} else {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, PersonalLinks.SESSION_UNKNOWN);
			return;
		}
		serveJsonObject(context.response(), contactOnTheWire(contact));
	}

	/**
	 * Deletes a contact from the space at <code>&lt;data&gt;/?action=delete-contact</code>, see
	 * issue #203.
	 *
	 * <p>
	 * Deleting deletes ({@link AuthService#deleteContact(String)}): name, addresses and sessions go,
	 * and their own links with them; what they uploaded keeps the name copied at the upload.
	 * Answered with the contact as it was.
	 * </p>
	 */
	private void deleteContact(Context context) throws IOException {
		Caller caller = contactManager(context);
		if (caller == null) {
			return;
		}
		ContactDelete request;
		try {
			request = ContactDelete.readContactDelete(new JsonReader(new ReaderAdapter(
				new InputStreamReader(new ByteArrayInputStream(readBody(context.request())), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.UNREADABLE);
			return;
		}
		ContactStore.Contact contact = _auth.getContacts().get(request.getContact());
		if (contact == null) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, ContactStore.unknownContact(request.getContact()));
			return;
		}
		Contact answer = contactOnTheWire(contact);
		_auth.deleteContact(contact.getId());
		LOG.info("Deleted " + contact + ", by '" + caller.getUserName() + "'.");
		serveJsonObject(context.response(), answer);
	}

	/** One contact as <code>?type=contacts</code> lists it, see issue #203. */
	Contact contactOnTheWire(ContactStore.Contact contact) {
		return PersonalLinks.contact(contact, _auth.getShares(), contributions().count());
	}

	/**
	 * Signs a contact out of every browser but this one at
	 * <code>&lt;data&gt;/?action=end-other-sessions</code>, see issue #203.
	 *
	 * <p>
	 * Asked by the contact themself, in a session of a personal link: "Also signed in on 1 other
	 * browser &mdash; sign out others", which limits a forwarded link opened before the real
	 * recipient. Nobody else may: a member ends a contact's sessions one by one, and an anonymous
	 * link has no person to sign out. Answered with how many were ended.
	 * </p>
	 */
	private void endOtherSessions(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.getContact() == null || caller.getSession() == null) {
			if (!caller.isPaired() && !caller.isShareLink()) {
				unauthorized(context, caller, true);
				return;
			}
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, PersonalLinks.OTHER_SESSIONS_REFUSED);
			return;
		}
		int ended = _auth.getContacts().endOtherSessions(caller.getContact().getId(), caller.getSession().getId());
		LOG.info("Signed " + caller.getContact() + " out of " + ended + " other browser(s), at their request.");
		serveJsonObject(context.response(), OtherSessionsEnded.create().setEnded(ended));
	}

	/**
	 * Signs a contact out of this very browser at <code>&lt;data&gt;/?action=end-own-session</code>:
	 * "Not you? Switch person" ends the session on the server before the browser forgets its
	 * credential, so that no orphaned session keeps counting as "another browser".
	 *
	 * <p>
	 * The caller rules are those of {@link #endOtherSessions(Context)}: asked by the contact
	 * themself, in a session of a personal link, and refused to everybody else in the same words.
	 * Answered with how many were ended, one or none.
	 * </p>
	 */
	private void endOwnSession(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.getContact() == null || caller.getSession() == null) {
			if (!caller.isPaired() && !caller.isShareLink()) {
				unauthorized(context, caller, true);
				return;
			}
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, PersonalLinks.OTHER_SESSIONS_REFUSED);
			return;
		}
		boolean ended = _auth.getContacts().endSession(caller.getContact().getId(), caller.getSession().getId());
		LOG.info("Signed " + caller.getContact() + " out of this browser, at their request.");
		serveJsonObject(context.response(), OtherSessionsEnded.create().setEnded(ended ? 1 : 0));
	}

	private static ContactShutOut readShutOut(Context context) throws IOException {
		try {
			byte[] contents = readBody(context.request());
			return ContactShutOut.readContactShutOut(new JsonReader(new ReaderAdapter(
				new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.UNREADABLE);
			return null;
		}
	}

	/**
	 * Deletes a share link of the addressed folder at
	 * <code>&lt;folder&gt;/?action=unshare</code>, see issues #51 and #217.
	 *
	 * <p>
	 * Deleting deletes, as if the link had never been made: the record goes from the store and from
	 * every list, its token and its recipients' tokens are from then on tokens this server never
	 * issued, and the contact sessions opened through it end ({@link AuthService#deleteLink}). The
	 * wire name stays <code>unshare</code>, which every app built since issue #51 sends, and so does
	 * the answer, a {@link ShareLinkList} holding the link as it was before it was deleted.
	 * </p>
	 */
	private void removeShare(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayShareLinks(caller)) {
			LOG.warning("Refusing to delete a share link of '" + location.getOwner() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARING_REFUSED);
			return;
		}

		ShareLink request = readShareLink(context);
		if (request == null) {
			return;
		}
		String id = request.getId() == null ? "" : request.getId().trim();
		ShareStore.Link link = reachedLink(context, caller, location, id);
		if (link == null) {
			return;
		}

		ShareLink wire = onTheWire(link);
		_auth.deleteLink(link.getId());
		LOG.info("Deleted the share link " + link + ".");
		serveJsonObject(context.response(), ShareLinkList.create().addLink(wire));
	}

	/**
	 * The link of the given id that a request at the given location may name, <code>null</code> where
	 * the refusal was answered.
	 *
	 * <p>
	 * A link of somebody else's, or of no folder above this one, is a link this request never saw:
	 * it is told that there is none, not whose it is (issue #84). From the root of the space every
	 * link of the space is reached, which is where the shares of the space are listed and managed
	 * (issue #269).
	 * </p>
	 */
	private ShareStore.Link reachedLink(Context context, Caller caller, Location location, String id)
			throws IOException {
		String name = id == null ? "" : id.trim();
		ShareStore.Link link = name.isEmpty() || _auth.getShares() == null ? null : _auth.getShares().get(name);
		if (link == null || !reaches(location, link) || !mine(caller, link)) {
			LOG.warning("Refusing a request about the unknown share link '" + name + "'.");
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, AuthService.SHARE_UNKNOWN);
			return null;
		}
		return link;
	}

	/**
	 * Whether a request at the given location reaches the given link: a link on the folder or on a
	 * folder above it, and at the root of the space every link of the space (issue #269).
	 */
	private static boolean reaches(Location location, ShareStore.Link link) {
		return link.covers(location.getOwner(), location.getOwnerPath())
			|| (location.getOwnerPath().isEmpty() && link.getOwner().equals(location.getOwner()));
	}

	/**
	 * Answers every share link of the space at <code>&lt;data&gt;/?type=space-shares</code>, see issue
	 * #269: what is shared with whom.
	 *
	 * <p>
	 * An administrator is answered every link of the space, a member who may share the links they
	 * handed out ({@link #mine}), and a member without the share flag is refused with the reason.
	 * Expired links are answered too, marked by their expiry; a deleted one is gone (issue #217).
	 * Each link carries what the listing of a folder carries, and its folder's title, the labels of
	 * its album and the photographs added through it. No answer ever carries a token.
	 * </p>
	 */
	private void serveSpaceShares(Context context, Caller caller, Location location) throws IOException {
		if (!caller.isPaired()) {
			unauthorized(context, caller, false);
			return;
		}
		if (!_auth.mayShareLinks(caller)) {
			LOG.warning("Refusing the share links of the space to '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARING_REFUSED);
			return;
		}
		ShareLinkList result = ShareLinkList.create();
		ShareStore shares = _auth.getShares();
		if (shares != null) {
			Contributions.Counts counts = null;
			for (ShareStore.Link link : shares.getLinks()) {
				if (link.getOwner().equals(location.getOwner()) && mine(caller, link)) {
					if (counts == null) {
						counts = contributions().count();
					}
					result.addLink(spaceShare(link, counts));
				}
			}
		}
		serveJsonObject(context.response(), result);
	}

	/** One link as <code>?type=space-shares</code> lists it, see issue #269. */
	private ShareLink spaceShare(ShareStore.Link link, Contributions.Counts counts) {
		ShareLink result = onTheWire(link, counts)
			.setTitle(SharePreview.linkTitle(_cache, _basePath, link))
			.setUploads(counts.ofLink(link.getId()));
		for (String label : labelsOf(link)) {
			result.addAlbumLabel(de.haumacher.imageServer.shared.model.LabelName.create().setName(label));
		}
		return result;
	}

	/** The labels of the album the given link shares, empty for a folder of folders or one gone. */
	private java.util.Collection<String> labelsOf(ShareStore.Link link) {
		if (!LibraryFiles.isLibraryPath(link.getPath())) {
			return java.util.Collections.emptyList();
		}
		try {
			PathInfo folder = link.getPath().isEmpty() ? new PathInfo(_basePath)
				: new PathInfo(_basePath, java.nio.file.Paths.get(link.getPath()));
			Resource resource = folder.isDirectory() ? _cache.lookup(folder) : null;
			return resource instanceof AlbumInfo ? Labels.of((AlbumInfo) resource)
				: java.util.Collections.<String> emptyList();
		} catch (RuntimeException ex) {
			// A folder whose sidecar cannot be read offers no label, see issue #254.
			return java.util.Collections.emptyList();
		}
	}

	/**
	 * Changes the options of a share link in place at <code>&lt;folder&gt;/?action=edit-share</code>,
	 * see issue #269.
	 *
	 * <p>
	 * The body is a {@link ShareLink} naming the link by its id and giving every option as it
	 * should be: its label, expiry, rights, privacy ceiling, rating floor and the label whose
	 * photographs it shows. The token stays the same, and the next request through the link is
	 * answered with what it allows now. Who may: the maker of the link and an administrator, as for
	 * deleting it. What is refused, with the reason: a right the editor may not use themself, a
	 * privacy ceiling above their clearance, an expiry in the past, and a label the album does not
	 * have &mdash; each only where it changes, so that what a link already had never blocks an edit
	 * of something else. Answered with the link as <code>?type=space-shares</code> lists it.
	 * </p>
	 */
	private void editShare(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayShareLinks(caller)) {
			LOG.warning("Refusing to edit a share link of '" + location.getOwner() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARING_REFUSED);
			return;
		}
		ShareLink request = readShareLink(context);
		if (request == null) {
			return;
		}
		ShareStore.Link link = reachedLink(context, caller, location, request.getId());
		if (link == null) {
			return;
		}
		LinkOptions options = linkOptions(context, request);
		if (options == null) {
			return;
		}

		if (!mayGive(context, caller, location, link, options)) {
			return;
		}

		_auth.getShares().edit(link.getId(), options._label, options._expires, options._maxPrivacy,
			options._minRating, options._rights, options._photoLabel);
		LOG.info("Edited the share link " + link + " to " + link.getRights() + ", by '" + caller.getUserName() + "'.");
		serveJsonObject(context.response(),
			ShareLinkList.create().addLink(spaceShare(link, contributions().count())));
	}

	/**
	 * Takes one recipient off an addressed personal link at
	 * <code>&lt;folder&gt;/?action=remove-recipient</code>, see issue #269.
	 *
	 * <p>
	 * Their own link is from then on a token nobody issued and the link no longer admits them,
	 * while every other recipient's link keeps working ({@link AuthService#removeRecipient}).
	 * Answered with the link as <code>?type=space-shares</code> lists it.
	 * </p>
	 */
	private void removeRecipient(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		de.haumacher.imageServer.shared.model.RecipientRemove request;
		try {
			request = de.haumacher.imageServer.shared.model.RecipientRemove.readRecipientRemove(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(readBody(context.request())),
					StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PersonalLinks.UNREADABLE);
			return;
		}
		ShareStore.Link link = managedLink(context, caller, location, request.getLink());
		if (link == null) {
			return;
		}
		ContactStore.Contact contact = _auth.getContacts().get(request.getContact());
		if (contact == null || link.recipient(contact.getId()) == null
			|| !_auth.removeRecipient(link, contact)) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, PersonalLinks.NOT_A_RECIPIENT);
			return;
		}
		LOG.info("Took " + contact + " off the share link " + link + ", by '" + caller.getUserName() + "'.");
		serveJsonObject(context.response(),
			ShareLinkList.create().addLink(spaceShare(link, contributions().count())));
	}

	/**
	 * Whether the given link is the caller's business, see issue #84.
	 *
	 * <p>
	 * Their own, or anybody's if they administer the space. A link stored before the creator was
	 * recorded belongs to nobody and is an administrator's to manage.
	 * </p>
	 */
	private static boolean mine(Caller caller, ShareStore.Link link) {
		return Roles.isAdmin(caller.getRole()) || caller.getUserName().equals(link.getCreatedBy());
	}

	/** The body of a share request, <code>null</code> if it cannot be read (the response is complete). */
	private static ShareLink readShareLink(Context context) throws IOException {
		try {
			byte[] contents = readBody(context.request());
			return ShareLink.readShareLink(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable share link: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, SHARE_UNREADABLE);
			return null;
		}
	}

	/**
	 * The path the given token opens this server's web application at.
	 *
	 * <p>
	 * Relative to the server: the origin is the client's business (it knows which name it reached
	 * this server under), the context path is the servlet's. The static handler serves the
	 * application below this path without ever looking at the token, see
	 * {@link ShareStore#URL_SEGMENT}.
	 * </p>
	 */
	/** The server-relative address the given contact's overview link opens, see issue #267. */
	String overviewUrl(Context context, String token) {
		return shareUrl(context, token);
	}

	private String shareUrl(Context context, String token) {
		return appUrl(context, ShareStore.URL_SEGMENT, token);
	}

	/**
	 * The path a token opens this server's web application at, below the given segment.
	 *
	 * <p>
	 * <code>/s/</code> for a share link (issue #51) and <code>/i/</code> for an invitation (issue
	 * #52): two tokens of different kinds, one static handler, and the same rule for both.
	 * </p>
	 *
	 * <p>
	 * A session of a space lives <em>under</em> that space (issue #82):
	 * <code>&lt;context&gt;/&lt;space&gt;/s/&lt;token&gt;/</code>, because the space is the first
	 * path segment and the app opened at the link has to find a data root — at the context root of
	 * a multi-space server there is none. The base is taken from the request itself rather than
	 * from a field, so a single-space server keeps spelling <code>&lt;context&gt;/s/&lt;token&gt;/</code>
	 * exactly as before.
	 * </p>
	 *
	 * <p>
	 * A request that reached the space through the old single-space addresses of a library moved
	 * into it (<code>&lt;context&gt;/data/...</code>, see {@link de.haumacher.imageServer.auth.Spaces#alias()}) carries no space
	 * in its servlet path. What it creates is spelled with the space's own address all the same:
	 * the link or invitation is new, and the old addresses live only as long as the marker.
	 * </p>
	 */
	private String appUrl(Context context, String segment, String token) {
		String contextPath = context.getContextPath() == null ? "" : context.getContextPath();
		return contextPath + spaceBase(context) + "/" + segment + "/" + token + "/";
	}

	/**
	 * Where this space's application is mounted below the context root, without a trailing slash:
	 * <code>""</code> on a single-space server, <code>/&lt;space&gt;</code> on a multi-space one,
	 * also for a request that came through the alias of a library moved into a space (see
	 * {@link #appUrl(Context, String, String)}).
	 */
	String spaceBase(Context context) {
		String base = appBase(context);
		if (base.isEmpty() && !_space.isEmpty()) {
			base = "/" + _space;
		}
		return base;
	}

	/**
	 * Where the web application is mounted for the request at hand, without a trailing slash.
	 *
	 * <p>
	 * The servlet path of a request is <code>/data</code> on a single-space server and
	 * <code>/&lt;space&gt;/data</code> on a multi-space one, see
	 * {@link SpaceServlet.InSpace}: what is in front of the data prefix is the application's base.
	 * </p>
	 */
	private static String appBase(Context context) {
		String servletPath = context.request().getServletPath();
		if (servletPath == null || !servletPath.endsWith(Settings.DATA_PREFIX)) {
			return "";
		}
		return servletPath.substring(0, servletPath.length() - Settings.DATA_PREFIX.length());
	}

	/**
	 * The given share link as the protocol carries it, with the rights it was created with.
	 *
	 * <p>
	 * Never the token and never its hash: what a listing shows is what the author needs to tell one
	 * link from another, see issue #51.
	 * </p>
	 */
	private ShareLink onTheWire(ShareStore.Link link) {
		return onTheWire(link, null);
	}

	/**
	 * The given share link as the protocol carries it, with how many photographs each of its
	 * recipients and visitors added (issue #203); <code>null</code> counts none.
	 */
	private ShareLink onTheWire(ShareStore.Link link, Contributions.Counts counts) {
		Set<String> rights = link.getRights();
		ShareLink wire = ShareLink.create()
			.setId(link.getId())
			.setCreatedBy(link.getCreatedBy())
			.setLabel(link.getLabel())
			.setExpires(link.getExpires())
			.setMaxPrivacy(link.getMaxPrivacy())
			.setMinRating(link.getMinRating())
			.setRights(Rights.onTheWire(rights))
			.setPath(AuthService.canonical(link))
			.setCreated(link.getCreated())
			.setPhotoLabel(link.getPhotoLabel());
		return PersonalLinks.withRecipients(wire, link, _auth.getContacts(),
			counts == null ? Contributions.Counts.NONE : counts);
	}

	/** What the contributors of the space added, see {@link Contributions}. */
	private synchronized Contributions contributions() {
		if (_contributions == null) {
			_contributions = new Contributions(_basePath);
		}
		return _contributions;
	}

	/**
	 * Issues an invitation at <code>&lt;data&gt;/?action=invite</code>, see issue #52.
	 *
	 * <p>
	 * An invitation is a <em>pending user carrying a code</em> since issue #89: the user is created
	 * here, nameless and without devices, with what the request says they become, and one
	 * single-use code is made for them. It names no path and touches no album; the token travels
	 * back exactly once, in the {@link InvitationCreated}, together with the
	 * <code>/i/&lt;token&gt;/</code> URL the app is served under for it.
	 * </p>
	 *
	 * <p>
	 * Who may: a member and the admin, unless the server was started with
	 * <code>--invite admin</code>, and then only the admin. Never a guest, and never an anonymous
	 * caller, who is refused like every other write. Whom to: a member or a guest, never an admin —
	 * the library has one owner and nobody is invited into that seat.
	 * </p>
	 */
	private void createInvitation(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayInvite(caller)) {
			LOG.warning("Refusing an invitation to '" + caller.getUserName() + "': only an "
				+ "administrator invites into a space.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.INVITE_ADMIN_ONLY);
			return;
		}

		Invitation request = readInvitation(context);
		if (request == null) {
			return;
		}
		String requested = request.getRole() == null || request.getRole().trim().isEmpty() ? Roles.VIEW
			: request.getRole().trim();
		String role = Roles.of(requested);
		if (Roles.ADMIN.equals(role)) {
			LOG.warning("Refusing an invitation into the administrator's seat.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.INVITATION_ADMIN_REFUSED);
			return;
		}
		if (role == null) {
			LOG.warning("Refusing an invitation with the unknown role '" + requested + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.invitationRoleRefused(requested));
			return;
		}
		String expires = request.getExpires() == null ? "" : request.getExpires().trim();
		if (!expires.isEmpty()) {
			try {
				expires = java.time.Instant.parse(expires).toString();
			} catch (java.time.DateTimeException ex) {
				LOG.warning("Refusing the invitation expiry '" + expires + "': " + ex.getMessage());
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.INVITATION_EXPIRY_REFUSED);
				return;
			}
		}
		String note = request.getNote() == null ? "" : request.getNote().trim();

		// What the invitee will hold, see issue #82: what the invitation says, capped at what the
		// inviter holds themselves — nobody hands out more than they have.
		String clearance = request.getClearance() == null ? "" : request.getClearance().trim();
		if (!clearance.isEmpty() && !Clearances.isKnown(clearance)) {
			LOG.warning("Refusing an invitation with the unknown clearance '" + clearance + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, CLEARANCE_REFUSED);
			return;
		}
		if (clearance.isEmpty()) {
			clearance = Clearances.ofRole(role);
		}
		if (Clearances.level(clearance) > Clearances.level(caller.getClearance())) {
			clearance = caller.getClearance();
		}
		boolean mayShare = request.isMayShare() && caller.mayShare();
		String recipient = request.getRecipient() == null ? "" : request.getRecipient().trim();

		AuthService.Invited issued = _auth.createInvitation(caller.getUserName(), caller.getDeviceId(),
			role, clearance, mayShare, note, expires, recipient);
		serveJsonObject(context.response(), InvitationCreated.create()
			.setInvitation(onTheWire(issued))
			.setToken(issued.getToken())
			.setUrl(appUrl(context, InvitationStore.URL_SEGMENT, issued.getToken())));
	}

	/**
	 * Answers the invitations at <code>&lt;data&gt;/?type=invitations</code>, see issue #52.
	 *
	 * <p>
	 * The admin sees every invitation of the server, a member the ones they issued themselves:
	 * whom somebody else invited is nobody else's business, and the admin is the one who keeps the
	 * server in order. A guest and an anonymous caller see none at all, and no answer ever carries
	 * a token.
	 * </p>
	 *
	 * <p>
	 * Derived and not stored since issue #89: the list is the pending users of this space joined
	 * with their codes, see {@link AuthService#invitations()}. There is no invitation store left.
	 * </p>
	 */
	private void serveInvitations(Context context, Caller caller) throws IOException {
		if (!caller.isPaired()) {
			unauthorized(context, caller, false);
			return;
		}
		if (!Roles.isAdmin(caller.getRole())) {
			LOG.warning("Refusing the invitations to '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.INVITATIONS_REFUSED);
			return;
		}

		// An invitation that simply ran out leaves a user nobody can become; they go before the
		// list is answered, so that what is shown is what is there (issue #89).
		_auth.pruneInvitations();

		InvitationList result = InvitationList.create();
		boolean admin = Roles.ADMIN.equals(caller.getRole());
		for (AuthService.Invited invitation : _auth.invitations()) {
			if (admin || invitation.getUser().getInvitedBy().equals(caller.getUserName())) {
				result.addInvitation(onTheWire(invitation));
			}
		}
		serveJsonObject(context.response(), result);
	}

	/**
	 * Withdraws an invitation at <code>&lt;data&gt;/?action=uninvite</code>, see issue #52.
	 *
	 * <p>
	 * The code is marked withdrawn and kept — a management screen shows what became of an
	 * invitation somebody handed out, see issue #55 — and the link is refused from then on.
	 * <b>A pending user goes with it</b> (issue #89): an invitation nobody accepted is a user
	 * nobody is. Somebody who already accepted keeps their account: withdrawing an invitation is
	 * not removing somebody. Only the issuer and the admin may withdraw; anybody else is told that
	 * there is no such invitation, not whose it is.
	 * </p>
	 */
	private void removeInvitation(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}
		if (!Roles.isAdmin(caller.getRole())) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.INVITATIONS_REFUSED);
			return;
		}

		Invitation request = readInvitation(context);
		if (request == null) {
			return;
		}
		String id = request.getId() == null ? "" : request.getId().trim();
		AuthService.Invited invitation = _auth.invitation(id);
		boolean mine = invitation != null && (Roles.ADMIN.equals(caller.getRole())
			|| invitation.getUser().getInvitedBy().equals(caller.getUserName()));
		if (!mine) {
			LOG.warning("Refusing to withdraw the unknown invitation '" + id + "'.");
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, AuthService.INVITATION_UNKNOWN);
			return;
		}

		_auth.uninvite(invitation.getId());
		serveJsonObject(context.response(), InvitationList.create().addInvitation(onTheWire(invitation)));
	}


	/** The body of an invitation request, <code>null</code> if it cannot be read (the response is complete). */
	private static Invitation readInvitation(Context context) throws IOException {
		try {
			byte[] contents = readBody(context.request());
			return Invitation.readInvitation(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unparsable invitation: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, INVITATION_UNREADABLE);
			return null;
		}
	}

	/**
	 * The given invitation as the protocol carries it.
	 *
	 * <p>
	 * Never the token and never its hash: what a listing shows is what the inviter needs to tell
	 * one invitation from another and to see what became of it, see issue #52.
	 * </p>
	 */
	private static Invitation onTheWire(AuthService.Invited invitation) {
		UserStore.User user = invitation.getUser();
		DeviceCodeStore.Code code = invitation.getCode();
		return Invitation.create()
			.setId(code.getId())
			.setRole(user.getRole())
			.setClearance(user.getClearance())
			.setMayShare(user.isShare())
			.setNote(user.getNote())
			.setRecipient(user.getRecipient())
			.setExpires(code.getExpires())
			.setInvitedBy(user.getInvitedBy())
			.setCreated(code.getCreated())
			.setUsed(code.getUsed())
			// Whom it made: the name the person chose when they redeemed it, see issue #89.
			.setUsedBy(code.isUsed() ? user.getName() : "")
			.setRevoked(code.getRevoked());
	}





	/**
	 * Sets what a user of this space may do, at <code>&lt;data&gt;/?action=set-permission</code>,
	 * see issue #83.
	 *
	 * <p>
	 * The administrator's own decision. A user's permission is the same in every album, so this is
	 * the whole of sharing inside a space: who is in it, and what they may do.
	 * </p>
	 */
	private void setPermission(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!administrator(context, caller)) {
			return;
		}
		UserPermission request;
		try {
			byte[] contents = readBody(context.request());
			request = UserPermission.readUserPermission(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unparsable permission: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PERMISSION_UNREADABLE);
			return;
		}
		try {
			UserStore.User user = _auth.setPermission(request.getName(), request.getRole(),
				request.getClearance(), request.isMayShare());
			LOG.info("The permission of '" + user.getName() + "' is now " + user.getRole() + ".");
			serveJsonObject(context.response(), userList());
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing to set a permission: " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
		}
	}

	/**
	 * Removes a user of this space with their devices, at
	 * <code>&lt;data&gt;/?action=remove-user</code>, see issue #83.
	 *
	 * <p>
	 * Their tokens stop working at once. Nothing of theirs leaves the album tree: what they
	 * uploaded belongs to the space, and their name stays in its attribution.
	 * </p>
	 */
	private void removeUser(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!administrator(context, caller)) {
			return;
		}
		MemberName request;
		try {
			byte[] contents = readBody(context.request());
			request = MemberName.readMemberName(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unparsable user name: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PERMISSION_UNREADABLE);
			return;
		}
		try {
			String name = request.getName() == null ? "" : request.getName().trim();
			int revoked = _auth.removeUser(name);
			// The account goes; the person stays, tags and all -- the photographs of a former
			// member are still photographs of that person, see issue #128. Nothing in the answer
			// says so: the user is simply gone from the list, and the person is gone from
			// ?type=people's "user", which is where the link was.
			if (_people.unlinkUser(name)) {
				LOG.info("Unlinked the person of the removed user '" + name + "'.");
			}
			serveJsonObject(context.response(), userList().setRevokedLinks(revoked));
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing to remove a user: " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
		}
	}

	/** Whether the caller administers this space; the refusal is answered here if not. */
	boolean administrator(Context context, Caller caller) throws IOException {
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return false;
		}
		if (!Roles.isAdmin(caller.getRole())) {
			LOG.warning("Refusing the management request of '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, ADMIN_ONLY);
			return false;
		}
		return true;
	}

	/** The users of this space, as the protocol carries them. */
	UserList userList() {
		UserList result = UserList.create();
		for (UserStore.User user : _auth.getUsers().getUsers()) {
			if (user.getName().isEmpty() && !user.isPending()) {
				continue;
			}
			result.addUser(onTheWire(user));
		}
		return result;
	}

	/**
	 * The given user as the protocol carries them, see issue #55.
	 *
	 * <p>
	 * Never a device of theirs and never a token: how many devices somebody uses is a number, and
	 * what those devices are called is answered to their owner alone, see {@link DeviceList}.
	 * </p>
	 */
	private UserEntry onTheWire(UserStore.User user) {
		UserEntry result = UserEntry.create()
			.setName(user.getName())
			.setRole(user.getRole())
			.setSpace(user.getSpace())
			.setCreated(user.getCreated())
			.setClearance(user.getClearance())
			.setMayShare(user.isShare())
			.setDevices(user.getDevices().size())
			// An invitation is a pending user (issue #89), and the users list is where the
			// administrator sees whom they are still waiting for.
			.setPending(user.isPending())
			.setRecipient(user.getRecipient())
			.setInvitedBy(user.getInvitedBy())
			.setInvitation(user.getInvitation());
		// How they sign in on a new browser besides a code (issue #233), never a secret or a key.
		SignIns.Authenticator authenticator = user.getSignIns().getAuthenticator();
		result.setAuthenticator(authenticator == null ? "" : authenticator.getSince());
		for (SignIns.Passkey passkey : user.getSignIns().getPasskeys()) {
			result.addPasskey(PersonalLinks.passkey(passkey));
		}
		for (ContactStore.Address address : user.getAddresses()) {
			result.addAddresse(PersonalLinks.address(address));
		}
		// Who this user is in the photographs, looked up from the register, which is where the
		// link is stored; an id and the name to show, and nothing else of that person, see #128.
		PeopleStore.Entry person = _people.byUser(user.getName());
		if (person != null) {
			result.setPerson(person.getId()).setPersonName(person.getName());
		}
		return result;
	}


	/**
	 * Handles the pairing request at <code>&lt;data&gt;/?action=pair</code>.
	 *
	 * <p>
	 * Pairing is the only write-like request that is open to an unpaired caller: it is guarded by
	 * the pairing secret the server was started with, not by a token. Every other POST is refused
	 * like a PUT.
	 * </p>
	 */
	private void servePost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		Context context = new Context(request, response);

		String action = context.getParameter("action");
		if ("identify".equals(action)) {
			// The one request a personal link that does not know who is asking may make, see #198.
			identify(context);
			return;
		}
		if ("prove-email".equals(action)) {
			// A personal link that does not know who is asking may also prove an address, #199.
			proveEmail(context);
			return;
		}
		if ("verify-email".equals(action)) {
			verifyEmail(context);
			return;
		}
		if ("oidc-start".equals(action)) {
			// ... or through a provider of OpenID Connect, see issue #200.
			oidcStart(context);
			return;
		}
		if ("oidc-exchange".equals(action)) {
			oidcExchange(context);
			return;
		}
		if (signInActions().handle(action, context)) {
			// An authenticator app (#208): set up by a contact, used by a visitor not recognised yet.
			return;
		}

		Caller postCaller = _auth.caller(request);
		if (gone(context, postCaller)) {
			return;
		}
		if (postCaller.isOverview() && !"end-other-sessions".equals(action) && !"end-own-session".equals(action)) {
			// The overview changes nothing but the contact's own sessions, see issue #267.
			LOG.warning("Refusing the action '" + action + "' to the overview of what is shared.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.OVERVIEW_ONLY);
			return;
		}
		if (inboxHiddenFromLink(context, postCaller)) {
			return;
		}

		if ("check".equals(action)) {
			checkUploads(context);
			return;
		}
		if (NEWS_READ_ACTION.equals(action)) {
			markNewsRead(context, postCaller);
			return;
		}
		if ("move".equals(action)) {
			moveEntries(context);
			return;
		}
		if (SEARCH_ACTION.equals(action)) {
			search(context);
			return;
		}
		if (COLLECT_ACTION.equals(action)) {
			collect(context);
			return;
		}
		if ("delete".equals(action)) {
			deleteEntries(context);
			return;
		}
		if (ZIP_ACTION.equals(action)) {
			zipEntries(context);
			return;
		}
		if (ZIP_TICKET_ACTION.equals(action)) {
			issueZipTicket(context);
			return;
		}
		if ("purge".equals(action)) {
			purgeTrash(context);
			return;
		}
		if ("place".equals(action)) {
			placeEntries(context);
			return;
		}
		if ("refresh-cache".equals(action)) {
			refreshCache(context);
			return;
		}
		if ("reanalyze".equals(action)) {
			reanalyze(context);
			return;
		}
		if ("create-person".equals(action) || "rename-person".equals(action)
			|| "merge-persons".equals(action)) {
			editPeople(context, action);
			return;
		}
		if ("link-person".equals(action)) {
			linkPerson(context);
			return;
		}
		if ("tag-faces".equals(action)) {
			tagFaces(context, false);
			return;
		}
		if (ADJUST_FACES_ACTION.equals(action)) {
			tagFaces(context, true);
			return;
		}
		if (RELABEL_ACTION.equals(action)) {
			relabel(context);
			return;
		}
		if (CROP_ACTION.equals(action)) {
			cropImage(context);
			return;
		}
		if (POSTER_ACTION.equals(action)) {
			posterVideo(context);
			return;
		}
		if ("find-duplicates".equals(action)) {
			// Retired by issue #219, for every caller and before anything is read.
			retired(context, RETIRED_FIND_DUPLICATES);
			return;
		}
		if ("unlink".equals(action)) {
			retired(context, RETIRED_LINKS);
			return;
		}
		if ("grant".equals(action) || "revoke".equals(action)) {
			retired(context, RETIRED_GRANTS);
			return;
		}
		if ("group".equals(action) || "ungroup".equals(action)) {
			retired(context, RETIRED_GROUPS);
			return;
		}
		if ("regroup".equals(action)) {
			retired(context, RETIRED_GROUPS);
			return;
		}
		if ("unpair".equals(action)) {
			unpairDevice(context);
			return;
		}
		if ("device-code".equals(action)) {
			createDeviceCode(context);
			return;
		}
		if ("backup-code".equals(action)) {
			createBackupCode(context);
			return;
		}
		if ("revoke-backup-code".equals(action)) {
			revokeBackupCode(context);
			return;
		}
		if ("share".equals(action)) {
			createShare(context);
			return;
		}
		if ("resend".equals(action)) {
			resend(context);
			return;
		}
		if ("shut-out".equals(action)) {
			shutOut(context);
			return;
		}
		if ("edit-share".equals(action)) {
			editShare(context);
			return;
		}
		if ("remove-recipient".equals(action)) {
			removeRecipient(context);
			return;
		}
		if ("block-contact".equals(action)) {
			blockContact(context);
			return;
		}
		if ("rename-contact".equals(action)) {
			renameContact(context);
			return;
		}
		if ("end-contact-session".equals(action)) {
			endContactSession(context);
			return;
		}
		if ("delete-contact".equals(action)) {
			deleteContact(context);
			return;
		}
		if ("end-other-sessions".equals(action)) {
			endOtherSessions(context);
			return;
		}
		if ("end-own-session".equals(action)) {
			endOwnSession(context);
			return;
		}
		if (SharedOverview.LINK_ACTION.equals(action)) {
			// A contact's own link to the overview of what is shared with them, see issue #267.
			new SharedOverview(this, _auth, _basePath).issueLink(context);
			return;
		}
		if ("unshare".equals(action)) {
			removeShare(context);
			return;
		}
		if ("invite".equals(action)) {
			createInvitation(context);
			return;
		}
		if ("uninvite".equals(action)) {
			removeInvitation(context);
			return;
		}
		if ("promote".equals(action)) {
			retired(context, RETIRED_PROMOTE);
			return;
		}
		if ("set-permission".equals(action)) {
			setPermission(context);
			return;
		}
		if (SpaceConfiguration.TYPE.equals(action)) {
			_settings.save(context, postCaller);
			return;
		}
		if ("remove-user".equals(action)) {
			removeUser(context);
			return;
		}

		if (!"pair".equals(action)) {
			Caller caller = _auth.caller(request);
			if (!_auth.writeAllowed(caller)) {
				unauthorized(context, caller, true);
				return;
			}
			error(context, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}

		PairRequest pairRequest;
		try {
			byte[] contents = readBody(request);
			pairRequest = PairRequest.readPairRequest(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable pairing request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, "The pairing request cannot be read.");
			return;
		}

		PairResponse pairResponse;
		try {
			// The client address counts guesses at an authenticator app (issue #233); nothing else asks.
			String totp = pairRequest.getTotpCode();
			String client = totp == null || totp.isBlank() ? null : request.getRemoteAddr();
			pairResponse = _auth.pair(pairRequest, client, _passkeys, _space);
		} catch (PairRefused ex) {
			LOG.warning("Refusing to pair device '" + pairRequest.getDeviceName() + "': " + ex.getMessage());
			if (ex.getRetryAfter() > 0) {
				response.setHeader("Retry-After", Long.toString(ex.getRetryAfter()));
			}
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}

		LOG.info("Paired device: " + pairResponse.getDeviceName());
		serveJsonObject(response, pairResponse);
	}

	/**
	 * Answers a cross-origin preflight, so that a browser may send the
	 * <code>Authorization</code> header of an authenticated request.
	 */
	@Override
	protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		allowCrossOrigin(response);
		response.setHeader("Access-Control-Allow-Methods", "GET, PUT, POST, OPTIONS");
		response.setStatus(HttpServletResponse.SC_OK);
	}

	/**
	 * Refuses the request with <code>401</code>, naming the reason in the response body.
	 *
	 * <p>
	 * Nothing declines silently: the message is what the app shows the user, see
	 * {@link AuthService#refusal(Caller, boolean)}.
	 * </p>
	 */
	void unauthorized(Context context, Caller caller, boolean write) throws IOException {
		String message = _auth.refusal(caller, write);
		LOG.warning("Refusing " + (write ? "write" : "read") + " access to '" + context.request().getPathInfo()
			+ "': " + message);

		if (identified(caller)) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, message);
			return;
		}
		context.response().setHeader("WWW-Authenticate", "Bearer");
		errorInfo(context, HttpServletResponse.SC_UNAUTHORIZED, message);
	}

	/**
	 * Whether the given caller already said who it is, so that a challenge would not help it.
	 *
	 * <p>
	 * A paired device, and a share link of issue #51: both presented a token this server issued, so
	 * a refusal names what they may not do (<code>403</code>) instead of asking them to sign in.
	 * A link holder has nothing to sign in as, and telling them to pair a device would be nonsense.
	 * </p>
	 */
	private static boolean identified(Caller caller) {
		return caller.isPaired() || caller.isShareLink();
	}

	/**
	 * Answers a caller whose share link expired or is otherwise closed to them with <code>410 Gone</code>.
	 *
	 * <p>
	 * On every endpoint, <code>?type=auth</code> included: the app asks who it is, is told that the
	 * link is gone and why, and shows a plain page instead of an error dump, see issue #51.
	 * </p>
	 *
	 * @return Whether the request was answered here.
	 */
	boolean gone(Context context, Caller caller) throws IOException {
		if (caller.mustIdentify()) {
			// A personal link that does not know who is asking opens nothing, on every endpoint,
			// "?type=auth" included: the refusal says how to become somebody, see issue #198.
			AuthService.Identification identification = caller.getIdentification();
			LOG.warning("Refusing '" + context.request().getPathInfo() + "': " + identification.getMessage());
			HttpServletResponse response = context.response();
			allowCrossOrigin(response);
			response.setStatus(identification.getStatus());
			serveJson(response, PersonalLinks.identifyRequired(identification, addressProof().methods(identification),
				identification.isShared() ? "" : SharePreview.linkTitle(_cache, _basePath, identification.getLink())));
			return true;
		}
		if (!caller.isShareGone()) {
			return false;
		}
		LOG.warning("Refusing '" + context.request().getPathInfo() + "': " + caller.getGone());
		errorInfo(context, HttpServletResponse.SC_GONE, caller.getGone());
		return true;
	}

	/**
	 * Answers a write through a share link that addresses an inbox, or anything in or below one,
	 * exactly as a read is answered, see issue #215.
	 *
	 * <p>
	 * An inbox does not exist for a share link (issue #135): its listing, its images and their
	 * every shape are answered <code>404</code> with {@link Inboxes#NOT_FOUND}. A write is answered
	 * the same — the batch upload, the single-image upload, the hash check, a sidecar or a
	 * creation below it, and every action — before any right is asked, so that neither a stored
	 * upload nor a refusal naming a right tells the link that something is there. A member is not
	 * asked here; what a member may do in an inbox is #135's.
	 * </p>
	 *
	 * <p>
	 * The folder looked at is the deepest existing folder on the way to the address: the address
	 * itself, the album a file would be in, the album a new folder would be created in. An address
	 * the path rules refuse outright is looked at through its parent, so that
	 * <code>Box/@eaDir/</code> is no way to learn of <code>Box</code> either.
	 * </p>
	 *
	 * @return Whether the request has been answered.
	 */
	private boolean inboxHiddenFromLink(Context context, Caller caller) throws IOException {
		if (!caller.isShareLink()) {
			return false;
		}
		String pathInfo = context.request().getPathInfo();
		String relative = pathInfo == null || pathInfo.isEmpty() ? "" : pathInfo.substring(1);
		PathInfo path;
		try {
			path = _auth.resolve(caller, _basePath, relative).getPath();
		} catch (PathRefused ex) {
			String trimmed = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
			int slash = trimmed.lastIndexOf('/');
			if (slash < 0) {
				return false;
			}
			try {
				path = _auth.resolve(caller, _basePath, trimmed.substring(0, slash)).getPath();
			} catch (PathRefused parentRefused) {
				return false;
			}
		}
		// The inbox is a place, whether it exists yet or not, see issue #226: an address in or below
		// it is the inbox's, and a link never writes there -- not even the first upload that would
		// create it.
		if (!Inboxes.inOrBelowInbox(path.toFile())) {
			return false;
		}
		LOG.warning("Hiding the inbox from a share link's write at '" + pathInfo + "'.");
		errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Inboxes.NOT_FOUND);
		return true;
	}

	/**
	 * Where the inbox of this space lies, see issue #226, whether it exists yet or not.
	 */
	private PathInfo inboxPath() {
		return new PathInfo(_basePath, Paths.get(_inbox.replace('/', File.separatorChar)));
	}

	/**
	 * Says where the inbox of this space is and how much waits there, see
	 * {@link AuthInfo#getInbox()} and issue #226.
	 *
	 * <p>
	 * Only to a caller who may put something into it: a member who may contribute (they are told
	 * how many of <em>their own</em> photographs wait, which is what they are shown there, see
	 * issue #135), an editor (all of them), and everybody under <code>--auth off</code>. A share
	 * link never reaches the inbox (issues #135, #215) and is not told where it is; neither is an
	 * anonymous caller or a member who may only look.
	 * </p>
	 */
	private void answerInbox(AuthInfo info, Caller caller) {
		if (caller.isShareLink() || caller.getInvitationGone() != null) {
			return;
		}
		PathInfo inbox = inboxPath();
		Inboxes.Visibility visibility = Inboxes.visibility(_auth, caller, inbox, Privacy.PRIVATE);
		if (visibility == Inboxes.Visibility.NONE) {
			return;
		}
		info.setInbox(_inbox);
		try {
			Resource resource = _cache.lookup(inbox);
			if (resource instanceof AlbumInfo) {
				info.setInboxCount(Inboxes.waiting((AlbumInfo) resource, visibility, caller.subject()));
			}
		} catch (RuntimeException ex) {
			// The count is a badge; a folder that cannot be read leaves it at nothing.
			LOG.log(Level.WARNING, "Cannot count the inbox '" + inbox.toFile() + "': " + ex.getMessage(), ex);
		}
	}

	/**
	 * Creates the inbox of this space where an upload addresses it before it exists, see issue
	 * #226: the folder itself, or a photograph directly in it.
	 *
	 * <p>
	 * The first write into the inbox is what makes it, and nothing else does: a read of the inbox
	 * before is answered an empty inbox and writes nothing. The caller must be one who may put
	 * something there; everybody else is refused as an upload into any folder is.
	 * </p>
	 *
	 * @return Whether the request goes on; <code>false</code> where it has been answered.
	 */
	private boolean makeInbox(Context context, Caller caller, PathInfo resourcePath) throws IOException {
		File asked = resourcePath.toFile();
		PathInfo inbox;
		if (Inboxes.isInbox(asked)) {
			inbox = resourcePath;
		} else if (!resourcePath.isRoot() && Inboxes.isInbox(asked.getParentFile())) {
			inbox = resourcePath.parent();
		} else {
			return true;
		}
		File folder = inbox.toFile();
		if (folder.isDirectory()) {
			return true;
		}
		if (!_auth.mayContribute(caller, inbox)) {
			refuse(context, caller, inbox, Rights.CONTRIBUTE, true);
			return false;
		}
		try {
			Files.createDirectories(folder.toPath());
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot create the inbox '" + folder + "': " + ex.getMessage(), ex);
			errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
				"The inbox of this space cannot be created: " + ex.getMessage());
			return false;
		}
		LOG.info("Created the inbox of the space: " + folder.getAbsolutePath());
		_cache.invalidateTree(inbox);
		return true;
	}

	/**
	 * Answers a request for something this build has retired, see issue #83.
	 *
	 * <p>
	 * <code>410 Gone</code> and not <code>404</code>: the endpoint was here and is not coming
	 * back, and the message names what does the job now. Kept for one release, so that an app that
	 * was not updated says something useful instead of failing silently.
	 * </p>
	 */
	private static void retired(Context context, String message) throws IOException {
		LOG.warning("Refusing the retired endpoint '" + context.request().getPathInfo() + "': " + message);
		errorInfo(context, HttpServletResponse.SC_GONE, message);
	}

	/**
	 * What the cache holds for the given path, <code>null</code> where it lies in a folder whose
	 * own <code>index.json</code> cannot be read: for an answer drawn from many folders (a search,
	 * a collection, the duplicates), which leaves such a folder out rather than failing as a whole,
	 * see issue #254. A request addressing that folder itself is refused, see
	 * {@link #refuseUnreadable(HttpServletRequest, HttpServletResponse, ResourceCache.UnreadableSidecar)}.
	 */
	private Resource lookupOrSkip(PathInfo path) {
		try {
			return _cache.lookup(path);
		} catch (ResourceCache.UnreadableSidecar ex) {
			LOG.warning("Leaving out '" + path.toFile() + "': " + ex.getMessage());
			return null;
		}
	}

	/** Answers with the given status and an {@link ErrorInfo} body carrying the given message. */
	static void errorInfo(Context context, int status, String message) throws IOException {
		HttpServletResponse response = context.response();
		allowCrossOrigin(response);
		response.setStatus(status);
		serveJson(response, ErrorInfo.create().setMessage(message));
	}

	private static String baseName(String name) {
		int index = name.lastIndexOf('/');
		if (index < 0) {
			return name;
		}
		return name.substring(index + 1);
	}

	private static String extension(String name) {
		int index = name.lastIndexOf('.');
		if (index < 0) {
			return "";
		}
		return name.substring(index + 1).toLowerCase();
	}

	/**
	 * Stores the request body as <code>index.json</code> of the given folder, and names the folder
	 * after what it says about itself, see issue #130.
	 *
	 * <p>
	 * The client's bytes are stored verbatim: the body is only parsed to make sure that it is a
	 * {@link FolderResource}, and it is re-serialised only when it carries something the server
	 * derived and must not keep, see {@link #stored(byte[], FolderResource)}. A pre-existing
	 * <code>index.json</code> is kept as a timestamped backup.
	 * </p>
	 *
	 * <p>
	 * Before anything is written, the name the properties compose ({@link FolderNames#of}) is
	 * compared with the name the folder has, and where they differ the folder is renamed — the
	 * same-filesystem rename of {@link MoveService#renameFolder}, which carries the share links on
	 * the folder and below it along. A name a sibling already holds refuses the whole request with
	 * a <code>409</code> and writes nothing: one refusal, no half state. A rename never
	 * <em>places</em> the folder: a changed date does not file an album into another year folder,
	 * because a rule files on creation, on a move and when it is asked to, see
	 * {@link PlacementRule}.
	 * </p>
	 *
	 * <p>
	 * The answer is a {@link CreateResult} naming where the folder is now, exactly as the creation
	 * of an album answers where it landed, so the client can follow it instead of looking where it
	 * is not.
	 * </p>
	 */
	private void storeFolder(Context context, Location location, PathInfo resourcePath) throws IOException {
		byte[] contents = readBody(context.request());
		FolderResource resource = checkFolderResource(context, contents);
		if (resource == null) {
			return;
		}
		Resource existing = _cache.lookup(resourcePath);
		boolean rearranged;
		Caller caller = _auth.caller(context.request());
		List<SourceEdit> sourceEdits = null;
		if (PhotoCollections.isCollection(existing)) {
			if (!(resource instanceof AlbumInfo)) {
				LOG.warning("Refusing a folder sidecar for the collection '" + resourcePath.toFile() + "'.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, COLLECTION_SIDECAR_REFUSED);
				return;
			}
			// What is the collection's own is stored here, what is the photograph's own is written
			// to the album it lies in, see issue #221.
			sourceEdits = new ArrayList<>();
			resource = collectionWrite(resourcePath, (AlbumInfo) existing, (AlbumInfo) resource, caller, sourceEdits);
			rearranged = true;
		} else if (PhotoSearch.isSearch(existing)) {
			if (!(resource instanceof AlbumInfo)) {
				LOG.warning("Refusing a folder sidecar for the saved search '" + resourcePath.toFile() + "'.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, SEARCH_SIDECAR_REFUSED);
				return;
			}
			// A query this build cannot read is neither evaluated nor overwritten, see issue #227.
			String refusal = PhotoSearch.refusal(((AlbumInfo) existing).getQuery());
			if (refusal == null) {
				refusal = PhotoSearch.refusal(((AlbumInfo) resource).getQuery());
			}
			if (refusal != null) {
				LOG.warning("Refusing to write the saved search '" + resourcePath.toFile() + "': " + refusal);
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, refusal);
				return;
			}
			// The saved search stores its query and its own statements; what a part says about a
			// photograph is written to the album it lies in, its labels included, see issue #227.
			sourceEdits = new ArrayList<>();
			resource = searchWrite(resourcePath, (AlbumInfo) existing, (AlbumInfo) resource, caller, sourceEdits);
			rearranged = true;
		} else {
			// An album never becomes a collection or a saved search: the kind is stated once, when
			// it is created.
			rearranged = resource instanceof AlbumInfo
				&& (((AlbumInfo) resource).getKind() == de.haumacher.imageServer.shared.model.AlbumKind.COLLECTION
					|| ((AlbumInfo) resource).getKind() == de.haumacher.imageServer.shared.model.AlbumKind.SEARCH
					|| ((AlbumInfo) resource).getQuery() != null);
			if (rearranged) {
				((AlbumInfo) resource).setKind(de.haumacher.imageServer.shared.model.AlbumKind.ALBUM);
				((AlbumInfo) resource).setQuery(null);
			}
			// What this caller was not answered is not theirs to write: the body is merged onto
			// the stored folder through the very answer a read gives them, see issue #296.
			Resource answered = writerAnswer(context, resourcePath, caller, existing);
			if (answered == null) {
				LOG.warning("Hiding the inbox from a write at '" + context.request().getPathInfo() + "'.");
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Inboxes.NOT_FOUND);
				return;
			}
			if (existing instanceof AlbumInfo && !(resource instanceof AlbumInfo)
				&& !AnsweredWrite.showsAllImages(existing, answered)) {
				LOG.warning("Refusing to write a folder of folders over the album '" + resourcePath.toFile()
					+ "' that holds photographs the caller is not shown.");
				errorInfo(context, HttpServletResponse.SC_FORBIDDEN, HIDDEN_PHOTOS_REFUSED);
				return;
			}
			FolderResource merged = AnsweredWrite.merge(resource, existing, answered, withheld(caller, Privacy.PRIVATE));
			rearranged |= merged != resource;
			resource = merged;
			// An inbox is answered flat and by date; what the client writes back must not freeze
			// that derived order into the sidecar, see issue #131.
			rearranged |= Inboxes.restoreArrangement(resourcePath.toFile(), resource);
			// An app older than issue #213 does not know the labels and must not remove them.
			rearranged |= Labels.keepUnknown(contents, resource, existing);
			// Nor the place captions it does not know either.
			rearranged |= keepPlaceCaptions(contents, resource, existing);
			// The poster of a video is written by its action alone, see issue #285.
			rearranged |= keepPosters(resource, existing);
		}

		if (sourceEdits != null) {
			// Written to the albums the photographs lie in, and asked there, before anything is
			// written at all, see issue #292.
			String refused = refusedSourceEdit(sourceEdits, caller);
			if (refused != null) {
				LOG.warning("Refusing to write '" + resourcePath.toFile() + "': the change of '" + refused
					+ "' lands in an album the caller may not edit.");
				errorInfo(context, HttpServletResponse.SC_FORBIDDEN, sourceEditRefused(refused));
				return;
			}
		}

		PathInfo target = resourcePath;
		String message = "";
		String wanted = renameTo(resourcePath, resource);
		if (wanted != null) {
			if (!FolderNames.isLegal(wanted)) {
				LOG.warning("Refusing to name '" + resourcePath.toFile() + "' after '" + wanted + "'.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, FolderNames.illegalName(wanted));
				return;
			}
			try {
				new MoveService(_cache, _auth).renameFolder(resourcePath, wanted, location.getOwner(),
					location.getOwnerPath());
			} catch (MoveRefused ex) {
				LOG.warning("Refusing to rename '" + resourcePath.toFile() + "': " + ex.getMessage());
				errorInfo(context, ex.getStatus(), ex.getMessage());
				return;
			}
			target = resourcePath.parent().child(wanted);
			message = FolderNames.renamedTo(wanted);
		}

		storeSidecar(target.toFile(), stored(contents, resource, rearranged));

		// The next read must see what was just written, in the folder and in the listing above.
		_cache.invalidate(target);
		if (wanted != null) {
			// Nothing lies at the old path any more; a cached answer there would be a ghost.
			_cache.invalidate(resourcePath);
			// A renamed folder announces nothing to the hash index; the references of a
			// collection find its photographs there only once it is read again, see issue #221.
			treeChanged(target.toFile());
			// And the old path holds nothing any more: its rows go in the same step, see #264.
			treeChanged(resourcePath.toFile());
		}
		if (sourceEdits != null) {
			writeSources(sourceEdits, caller);
		}

		serveJsonObject(context.response(),
			CreateResult.create().setPath(location.spell(target.relativePath())).setMessage(message));
	}

	/**
	 * The message a folder of folders written over an album is refused with where the album holds
	 * photographs its writer is not shown, see issue #296.
	 */
	public static final String HIDDEN_PHOTOS_REFUSED =
		"This album holds photographs you are not shown; it cannot be turned into a folder of folders by you.";

	/**
	 * What the given caller is answered for the folder at the given path, as a read answers it:
	 * the inbox rule, privacy, the rating floor and the label of a link, the labels and the faces,
	 * the rights, the place names in the caller's language. <code>null</code> where the folder is an
	 * inbox the caller is not shown. Never for a collection or a saved search, whose answer is
	 * built from other folders and remembers a picture, see {@link #searchWrite} and
	 * {@link #collectionWrite}.
	 */
	private Resource writerAnswer(Context context, PathInfo path, Caller caller, Resource stored) {
		int viewAs = Privacy.PRIVATE;
		int clearance = Math.min(_auth.clearance(caller, path), viewAs);
		Resource shown = Inboxes.filter(stored, Inboxes.visibility(_auth, caller, path, viewAs), caller.subject());
		if (shown == null) {
			return null;
		}
		Resource answer = folderAnswer(shown, path, caller, clearance, viewAs);
		return PhotoSearch.partial(localized(withRights(answer, _auth.rights(caller, path)), context));
	}

	/** The message a folder sidecar written on a collection is refused with. */
	public static final String COLLECTION_SIDECAR_REFUSED = "A collection is written as an album, not as a folder.";

	/** The message a folder sidecar written on a saved search is refused with. */
	public static final String SEARCH_SIDECAR_REFUSED = "A saved search is written as an album, not as a folder.";

	/** A change a collection's sidecar makes to a photograph of another album, see issue #221. */
	private static final class SourceEdit {
		final PathInfo _image;

		final ImagePart _received;

		/** Whether the labels are the photograph's own too, as in a saved search (issue #227). */
		final boolean _labels;

		SourceEdit(PathInfo image, ImagePart received) {
			this(image, received, false);
		}

		SourceEdit(PathInfo image, ImagePart received, boolean labels) {
			_image = image;
			_received = received;
			_labels = labels;
		}
	}

	/**
	 * The stored form of a saved search written by the given caller, see issue #227.
	 *
	 * <p>
	 * The saved search's own statements are taken: its query, title, date, star and picture. It
	 * never stores a part: what a part says about the photograph &mdash; its turn, crop,
	 * description, rating, privacy, time and labels &mdash; is collected into the given list for
	 * {@link #writeSources(List, Caller)}, for every photograph this caller was answered. A picture
	 * is kept where it names a match this caller was answered, and the stored one is kept where it
	 * names one this caller was not shown and they write back nothing or the substitute they were
	 * answered for it. A changed query keeps the picture while it still finds it and takes the
	 * first of its new matches otherwise, see issue #287.
	 * </p>
	 */
	private AlbumInfo searchWrite(PathInfo path, AlbumInfo existing, AlbumInfo received, Caller caller,
			List<SourceEdit> edits) {
		AlbumInfo answered = searchAnswer(existing, path, caller, Privacy.PRIVATE);
		Set<String> shown = new HashSet<>();
		for (ImagePart image : PhotoSearch.images(answered)) {
			shown.add(image.getName());
		}
		// A caller answered no labels writes none back: what they were not shown is not theirs to
		// take away, see #withheld(Caller, int) and issue #296.
		boolean labels = !withheld(caller, Privacy.PRIVATE).contains(Labels.FIELD);
		for (AlbumPart part : received.getParts()) {
			List<ImagePart> images = part instanceof ImagePart ? Collections.singletonList((ImagePart) part)
				: part instanceof ImageGroup ? ((ImageGroup) part).getImages() : Collections.<ImagePart> emptyList();
			for (ImagePart image : images) {
				if (!shown.contains(image.getName())) {
					continue;
				}
				PathInfo source = photoNamed(path, existing, image.getName());
				if (source != null) {
					edits.add(new SourceEdit(source, image, labels));
				}
			}
		}

		ThumbnailInfo cover = received.getIndexPicture();
		ThumbnailInfo storedCover = existing.getIndexPicture();
		if (cover != null && !shown.contains(cover.getImage())) {
			// A picture is a match this caller was answered.
			cover = null;
		}
		boolean storedHidden = storedCover != null && !shown.contains(storedCover.getImage());
		ThumbnailInfo substitute = answered.getIndexPicture();
		if (storedHidden
			&& (cover == null || (substitute != null && cover.getImage().equals(substitute.getImage())))) {
			// The caller was answered a substitute for a picture they may not see: written back, it
			// is no choice of theirs, see issue #260.
			cover = storedCover;
		}

		SearchQuery query =
			PhotoSearch.normalized(received.getQuery() == null ? existing.getQuery() : received.getQuery());
		if (!sameQuery(query, existing.getQuery())) {
			// What it looks for changed: the picture is one of what it finds now, the one chosen
			// where it still is, the first of the new matches this caller is shown otherwise -- the
			// picture a search is created with, see issue #287. Slim: no faces derived for it.
			PathInfo scope = searchScope(path);
			List<ImagePart> found = searchMatches(scope, query, caller, Privacy.PRIVATE, null, true);
			boolean kept = false;
			if (cover != null) {
				for (ImagePart image : found) {
					kept |= image.getName().equals(cover.getImage());
				}
				// One this caller is not shown is kept while the new query still finds it.
				kept |= cover == storedCover && storedHidden
					&& PhotoSearch.matching(scope, cover.getImage(), query, this::lookupOrSkip, _people, _searchIndex) != null;
			}
			if (!kept) {
				cover = found.isEmpty() ? null : PrivacyFilter.thumbnail(found.get(0));
			}
		}

		AlbumInfo result = AlbumInfo.create()
			.setKind(de.haumacher.imageServer.shared.model.AlbumKind.SEARCH)
			.setQuery(query)
			.setTitle(received.getTitle())
			.setSubTitle(received.getSubTitle())
			.setStarred(received.isStarred())
			.setPlaceCaption(received.getPlaceCaption())
			.setDate(received.getDate());
		if (cover != null) {
			result.setIndexPicture(Crops.copy(cover));
		}
		return result;
	}

	/** Whether the given queries are written alike, see {@link PhotoSearch#normalized(SearchQuery)}. */
	private static boolean sameQuery(SearchQuery a, SearchQuery b) {
		if (a == null || b == null) {
			return a == b;
		}
		return queryJson(a).equals(queryJson(b));
	}

	private static String queryJson(SearchQuery query) {
		java.io.StringWriter buffer = new java.io.StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			query.writeTo(json);
		} catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
		return buffer.toString();
	}

	/**
	 * The stored form of a collection written by the given caller, see issue #221.
	 *
	 * <p>
	 * The collection's own statements are taken: the title, the date, the order, the headings, the
	 * album picture, the labels of each reference, and which references are left out (removed). A
	 * part naming no reference of the collection adds nothing &mdash; adding is
	 * <code>?action=collect</code>, which asks what the caller may see &mdash;, groups are read as
	 * their members, and a reference this caller was not answered (a photograph above their
	 * clearance, say) is kept where it stood. What a part says about the photograph itself is
	 * collected into the given list, for {@link #writeSources(List, Caller)}.
	 * </p>
	 */
	private AlbumInfo collectionWrite(PathInfo path, AlbumInfo existing, AlbumInfo received, Caller caller,
			List<SourceEdit> edits) {
		AlbumInfo answered = resolveCollection(existing, path, caller, Privacy.PRIVATE);
		Map<String, ImagePart> shown = new HashMap<>();
		for (AlbumPart part : answered.getParts()) {
			if (part instanceof ImagePart) {
				shown.put(((ImagePart) part).getName(), (ImagePart) part);
			}
		}
		Map<String, ImagePart> stored = PhotoCollections.byName(existing);
		// See #withheld(Caller, int) and issue #296.
		boolean labels = !withheld(caller, Privacy.PRIVATE).contains(Labels.FIELD);

		List<AlbumPart> parts = new ArrayList<>();
		Set<String> kept = new HashSet<>();
		List<AlbumPart> flat = new ArrayList<>();
		for (AlbumPart part : received.getParts()) {
			if (part instanceof ImageGroup) {
				flat.addAll(((ImageGroup) part).getImages());
			} else {
				flat.add(part);
			}
		}
		for (AlbumPart part : flat) {
			if (part instanceof Heading) {
				Heading heading = (Heading) part;
				parts.add(Heading.create().setText(heading.getText()).setLevel(heading.getLevel()));
				continue;
			}
			if (!(part instanceof ImagePart)) {
				continue;
			}
			ImagePart image = (ImagePart) part;
			ImagePart reference = stored.get(image.getName());
			if (reference == null || !kept.add(image.getName())) {
				continue;
			}
			ImagePart answer = shown.get(image.getName());
			if (answer == null) {
				parts.add(PhotoCollections.stored(reference.getName(), reference.getRef(), reference.getLabels()));
				continue;
			}
			PhotoRef ref = reference.getRef();
			if (!answer.isMissing() && answer.getRef() != null) {
				// The hint follows the photograph, see PhotoRef.
				ref = PhotoRef.create().setHash(ref.getHash()).setPath(answer.getRef().getPath());
			}
			// A caller answered no labels writes none back, as a saved search asks it.
			parts.add(PhotoCollections.stored(reference.getName(), ref,
				labels ? image.getLabels() : reference.getLabels()));
			if (!answer.isMissing()) {
				PathInfo source = _collections.locate(ref);
				if (source != null) {
					edits.add(new SourceEdit(source, image));
				}
			}
		}

		// What this caller was not shown is not theirs to remove: it stays after the reference it
		// followed before.
		List<ImagePart> order = PhotoCollections.references(existing);
		for (int n = 0; n < order.size(); n++) {
			ImagePart reference = order.get(n);
			if (shown.containsKey(reference.getName()) || kept.contains(reference.getName())) {
				continue;
			}
			int at = 0;
			for (int before = n - 1; before >= 0; before--) {
				int index = indexOfName(parts, order.get(before).getName());
				if (index >= 0) {
					at = index + 1;
					break;
				}
			}
			parts.add(at, PhotoCollections.stored(reference.getName(), reference.getRef(), reference.getLabels()));
			kept.add(reference.getName());
		}

		ThumbnailInfo cover = received.getIndexPicture();
		ThumbnailInfo storedCover = existing.getIndexPicture();
		if (storedCover != null && !shown.containsKey(storedCover.getImage()) && kept.contains(storedCover.getImage())) {
			// The caller was answered a substitute for a picture they may not see.
			cover = storedCover;
		} else if (cover != null && !kept.contains(cover.getImage())) {
			cover = null;
		}

		AlbumInfo result = AlbumInfo.create()
			.setKind(de.haumacher.imageServer.shared.model.AlbumKind.COLLECTION)
			.setTitle(received.getTitle())
			.setSubTitle(received.getSubTitle())
			// Whether the collection stands out in its folder is its own statement (#239).
			.setStarred(received.isStarred())
			.setPlaceCaption(received.getPlaceCaption())
			.setDate(received.getDate())
			.setParts(parts);
		if (cover != null) {
			result.setIndexPicture(cover);
		}
		return result;
	}

	private static int indexOfName(List<AlbumPart> parts, String name) {
		for (int n = 0; n < parts.size(); n++) {
			AlbumPart part = parts.get(n);
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return n;
			}
		}
		return -1;
	}

	/**
	 * Writes what a collection's sidecar said about its photographs into the albums they lie in,
	 * see issue #221: the turn, the crop, the description, the rating, the privacy and the time.
	 * An album is written only where something changed, through the one sidecar writer.
	 */
	private void writeSources(List<SourceEdit> edits, Caller caller) throws IOException {
		Map<PathInfo, List<SourceEdit>> byFolder = new LinkedHashMap<>();
		for (SourceEdit edit : edits) {
			byFolder.computeIfAbsent(edit._image.parent(), x -> new ArrayList<>()).add(edit);
		}
		for (Map.Entry<PathInfo, List<SourceEdit>> entry : byFolder.entrySet()) {
			PathInfo folder = entry.getKey();
			if (!sourceEditable(caller, folder)) {
				// Changes nothing there, see #refusedSourceEdit(List, Caller).
				continue;
			}
			// An album whose sidecar cannot be read is never written over, see issue #254.
			Resource resource = lookupOrSkip(folder);
			if (!(resource instanceof AlbumInfo) || PhotoCollections.isCollection(resource)) {
				continue;
			}
			AlbumInfo album = (AlbumInfo) resource;
			boolean changed = false;
			for (SourceEdit edit : entry.getValue()) {
				ImagePart image = Crops.findImage(album, edit._image.getName());
				if (image != null) {
					changed |= applyEdit(album, image, edit._received);
					if (edit._labels) {
						changed |= applyLabels(image, edit._received);
					}
				}
			}
			if (changed) {
				storeSidecar(folder.toFile(), sidecarOf(album));
				_cache.invalidate(folder);
				LOG.info("Wrote the edits made through a collection or a saved search into '" + folder.toFile() + "'.");
			}
		}
	}

	/**
	 * The name of the first photograph the given edits would change in an album the given caller
	 * may not edit, see issue #292; <code>null</code> where there is none.
	 *
	 * <p>
	 * An edit session writes back every photograph it was answered, changed or not, so the edits of
	 * a photograph whose album the caller may not edit are refused only where they would change
	 * something: the collection's or the saved search's own statements and its unchanged
	 * photographs are written as before. Asked of copies; nothing is changed here.
	 * </p>
	 */
	private String refusedSourceEdit(List<SourceEdit> edits, Caller caller) {
		Map<PathInfo, Boolean> editable = new HashMap<>();
		for (SourceEdit edit : edits) {
			PathInfo folder = edit._image.parent();
			if (editable.computeIfAbsent(folder, x -> Boolean.valueOf(sourceEditable(caller, x))).booleanValue()) {
				continue;
			}
			Resource resource = lookupOrSkip(folder);
			if (!(resource instanceof AlbumInfo) || PhotoCollections.isCollection(resource)) {
				continue;
			}
			ImagePart image = Crops.findImage((AlbumInfo) resource, edit._image.getName());
			if (image == null) {
				continue;
			}
			ImagePart copy = FaceIndex.copyOf(image);
			boolean changed = applyEdit(AlbumInfo.create(), copy, edit._received);
			if (edit._labels) {
				changed |= applyLabels(copy, edit._received);
			}
			if (changed) {
				return edit._received.getName();
			}
		}
		return null;
	}

	/** Takes over the labels of the given received part into the given stored one, see issue #227. */
	private static boolean applyLabels(ImagePart image, ImagePart received) {
		List<de.haumacher.imageServer.shared.model.LabelName> labels =
			PhotoCollections.stored("", PhotoRef.create(), received.getLabels()).getLabels();
		List<String> before = new ArrayList<>();
		for (de.haumacher.imageServer.shared.model.LabelName label : image.getLabels()) {
			before.add(label.getName());
		}
		List<String> after = new ArrayList<>();
		for (de.haumacher.imageServer.shared.model.LabelName label : labels) {
			after.add(label.getName());
		}
		if (before.equals(after)) {
			return false;
		}
		image.setLabels(labels);
		return true;
	}

	/**
	 * Keeps the stored {@link ImagePart#getPoster() posters} of the album's videos in an album
	 * written by a sidecar <code>PUT</code>, whatever it carries, see issue #285.
	 *
	 * <p>
	 * The poster is written by <code>?action=poster</code> alone: an app that does not know the field
	 * writes back what it read without it, and an edit session holds the album as it was when it
	 * began. Neither may take a chosen poster away or bring an old one back.
	 * </p>
	 *
	 * @return Whether the received album was changed.
	 */
	static boolean keepPosters(FolderResource received, Resource stored) {
		if (!(received instanceof AlbumInfo)) {
			return false;
		}
		Map<String, Long> byName = new HashMap<>();
		if (stored instanceof AlbumInfo) {
			for (AlbumPart part : ((AlbumInfo) stored).getParts()) {
				for (ImagePart image : imagesOf(part)) {
					long millis = Posters.millis(image);
					if (millis > 0) {
						byName.put(image.getName(), millis);
					}
				}
			}
		}
		boolean kept = false;
		for (AlbumPart part : ((AlbumInfo) received).getParts()) {
			for (ImagePart image : imagesOf(part)) {
				Long millis = byName.get(image.getName());
				long wanted = millis == null ? 0 : millis;
				if (Posters.millis(image) != wanted || (wanted == 0 && image.getPoster() != null)) {
					image.setPoster(Posters.of(wanted));
					kept = true;
				}
			}
		}
		return kept;
	}

	/** Recognises a body that names the place caption at all, see {@link #keepPlaceCaptions}. */
	private static final Pattern PLACE_CAPTION_KEY = Pattern.compile("\"placeCaption\"\\s*:");

	/**
	 * Keeps the stored {@link AlbumInfo#getPlaceCaption() place captions} of the album and its
	 * photographs in an album written by an app that does not know the field.
	 *
	 * <p>
	 * Such an app writes back what it read, without the field, and would silently reset what the
	 * author said &mdash; exactly as {@link Labels#keepUnknown} guards the labels. A body that names
	 * the field anywhere is taken as it stands.
	 * </p>
	 *
	 * @return Whether anything was taken over from the stored album.
	 */
	static boolean keepPlaceCaptions(byte[] contents, FolderResource received, Resource stored) {
		if (!(received instanceof AlbumInfo) || !(stored instanceof AlbumInfo)) {
			return false;
		}
		if (PLACE_CAPTION_KEY.matcher(new String(contents, StandardCharsets.UTF_8)).find()) {
			return false;
		}
		AlbumInfo album = (AlbumInfo) received;
		AlbumInfo before = (AlbumInfo) stored;
		boolean kept = false;
		if (before.getPlaceCaption() != PlaceCaption.UNSET) {
			album.setPlaceCaption(before.getPlaceCaption());
			kept = true;
		}
		Map<String, PlaceCaption> byName = new HashMap<>();
		for (AlbumPart part : before.getParts()) {
			for (ImagePart image : imagesOf(part)) {
				if (image.getPlaceCaption() != PlaceCaption.UNSET) {
					byName.put(image.getName(), image.getPlaceCaption());
				}
			}
		}
		for (AlbumPart part : album.getParts()) {
			for (ImagePart image : imagesOf(part)) {
				PlaceCaption caption = byName.get(image.getName());
				if (caption != null) {
					image.setPlaceCaption(caption);
					kept = true;
				}
			}
		}
		return kept;
	}

	private static List<ImagePart> imagesOf(AlbumPart part) {
		return part instanceof ImagePart ? Collections.singletonList((ImagePart) part)
			: part instanceof ImageGroup ? ((ImageGroup) part).getImages() : Collections.<ImagePart> emptyList();
	}

	/** Takes over into the given stored part what the given received one says about it. */
	private static boolean applyEdit(AlbumInfo album, ImagePart image, ImagePart received) {
		boolean changed = false;
		double[] before = Crops.renditionRegion(image);
		Orientation orientation = image.getOrientation();
		if (received.getOrientation() != null && received.getOrientation() != image.getOrientation()) {
			image.setOrientation(received.getOrientation());
			changed = true;
		}
		int rating = Math.max(Ratings.MIN, Math.min(Ratings.MAX, received.getRating()));
		if (rating != image.getRating()) {
			image.setRating(rating);
			changed = true;
		}
		int privacy = Math.max(Privacy.PUBLIC, Math.min(Privacy.PRIVATE, received.getPrivacy()));
		if (privacy != image.getPrivacy()) {
			image.setPrivacy(privacy);
			changed = true;
		}
		String comment = received.getComment() == null ? "" : received.getComment();
		if (!comment.equals(image.getComment() == null ? "" : image.getComment())) {
			image.setComment(comment);
			changed = true;
		}
		if (received.getDate() > 0 && received.getDate() != image.getDate()) {
			image.setDate(received.getDate());
			changed = true;
		}
		if (received.getPlaceCaption() != image.getPlaceCaption()) {
			// Whether the place stands in for a missing description is the photograph's own word.
			image.setPlaceCaption(received.getPlaceCaption());
			changed = true;
		}
		if (image.getKind() == ImageKind.IMAGE) {
			Crop crop = received.getCrop();
			if (crop == null || Crops.isValid(crop)) {
				Crop wanted = crop == null || Crops.isWhole(crop) ? null : Crops.clamped(crop);
				Crop current = image.getCrop();
				image.setCrop(wanted);
				if (!Crops.same(before, Crops.renditionRegion(image))) {
					changed = true;
				} else {
					image.setCrop(current);
				}
			}
		}
		if (changed && (orientation != image.getOrientation() || !Crops.same(before, Crops.renditionRegion(image)))) {
			// The album's own picture is framed anew where its photograph was turned or cut, as
			// ?action=crop does.
			ThumbnailInfo cover = album.getIndexPicture();
			if (cover != null && image.getName().equals(cover.getImage())) {
				album.setIndexPicture(PrivacyFilter.thumbnail(image));
			}
		}
		return changed;
	}

	/**
	 * The name the given folder must be renamed to before the given properties are written to it,
	 * <code>null</code> when it already has the name they compose, see issue #130.
	 *
	 * <p>
	 * Four folders are left alone whatever they say about themselves:
	 * </p>
	 * <ul>
	 * <li>the inbox of the space, whose place <code>space.json</code> names (issue #226);</li>
	 * <li>the root of a space, which is not the server's to name — it is the folder the library
	 * was started on;</li>
	 * <li>a year or month folder, which is part of a {@link PlacementRule placement} structure and
	 * means its name, not its title;</li>
	 * <li>one whose properties compose no name at all, which is every folder that carries no
	 * title: it keeps the name its owner gave it, and the date in that name goes on being read,
	 * see {@link AlbumDate#ofFolderName(String)}.</li>
	 * </ul>
	 *
	 * <p>
	 * An album that carries no explicit date keeps the date its name carries, see
	 * {@link FolderNames#of(FolderResource, String)}: the fallback stays a fallback, and writing
	 * the properties of such an album never undates it.
	 * </p>
	 * <ul>
	 * </ul>
	 */
	private static String renameTo(PathInfo resourcePath, FolderResource resource) {
		if (resourcePath.isRoot()) {
			return null;
		}
		if (Inboxes.isInbox(resourcePath.toFile())) {
			// The space names its inbox (space.json, issue #226); a title written on it never moves
			// it away from there.
			return null;
		}
		String current = resourcePath.getName();
		if (PlacementRule.isPlacementFolder(current)) {
			return null;
		}
		String wanted = FolderNames.of(resource, current);
		if (wanted.isEmpty() || wanted.equals(current)) {
			return null;
		}
		return wanted;
	}

	/**
	 * Tells the hash index (issue #118), the search (issue #227), the recognition (issue #127) and
	 * the vectors of the content search (issue #257) that a move, a delete, a purge or a rename
	 * rearranged the tree at the given folder: they read it again in the same step, see issue #264.
	 * The vectors after the search, whose ids they are filed under.
	 */
	private void treeChanged(File folder) {
		_index.treeChanged(folder);
		_searchIndex.treeChanged(folder);
		_faces.recognition().treeChanged(folder, _faces.isEnabled());
		_vectors.treeChanged(folder);
	}

	/**
	 * Writes the given bytes as the <code>index.json</code> of the given folder.
	 *
	 * <p>
	 * The one way a sidecar reaches the disk: a client's PUT and the album a move rewrites take
	 * the same path, so there is only ever one sidecar format. A pre-existing sidecar is kept as a
	 * timestamped backup and the new one appears by a rename, so a reader never sees half a file.
	 * </p>
	 *
	 * @throws ResourceCache.UnreadableSidecar
	 *         Where the folder's present <code>index.json</code> cannot be read: it is never
	 *         written over, see issue #254.
	 */
	static void storeSidecar(File directory, byte[] contents) throws IOException {
		// A sidecar that cannot be read may hold what the user wrote, and is never written over,
		// see issue #254.
		ResourceCache.checkSidecar(directory);

		File indexFile = new File(directory, "index.json");

		File tmpFile = File.createTempFile("index", ".json", directory);
		try (OutputStream stream = new FileOutputStream(tmpFile)) {
			stream.write(contents);
		}

		if (indexFile.exists()) {
			indexFile.renameTo(new File(directory, "index.json." + indexFile.lastModified()));
		}

		tmpFile.renameTo(indexFile);

		// The record of what the folder holds, which a move or a delete changed, see issue #253.
		AnalysisRecord.sidecarWritten(directory);

		// The one writer of a sidecar tells the search of issue #227, which reads the folder before
		// the request returns, see SearchIndex#writeThrough().
		SearchIndex.sidecarWritten(directory);

		// And the background, which makes what an edit asks for (a cut preview, a face crop), see
		// issue #256.
		de.haumacher.imageServer.pipeline.FolderPipeline.edited(directory);

		LOG.info("Stored folder resource: " + indexFile.getAbsolutePath());
	}

	/**
	 * Reads the complete request body into memory.
	 *
	 * <p>
	 * A folder sidecar is small; keeping it in memory is what allows validating it before anything
	 * is written to disk.
	 * </p>
	 */
	static byte[] readBody(HttpServletRequest request) throws IOException {
		try (InputStream in = request.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			Util.transfer(in, buffer);
			return buffer.toByteArray();
		}
	}

	/**
	 * Checks that the given bytes parse as a {@link FolderResource}.
	 *
	 * <p>
	 * If they do not, the response is completed with an error status and the reason is logged.
	 * </p>
	 *
	 * @return The parsed resource, <code>null</code> if the contents may not be stored.
	 */
	private static FolderResource checkFolderResource(Context context, byte[] contents) {
		Resource resource;
		try {
			resource = Resource.readResource(
				new JsonReader(new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable folder resource for '" + context.request().getPathInfo() + "': " + ex.getMessage());
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return null;
		}
		if (!(resource instanceof FolderResource)) {
			LOG.warning("Rejecting non-folder resource for '" + context.request().getPathInfo() + "': " + resource);
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return null;
		}
		return (FolderResource) resource;
	}

	/**
	 * The bytes to store for a received sidecar.
	 *
	 * <p>
	 * A client's body is stored exactly as it was sent — that is what keeps one sidecar format —
	 * unless it carries a date the server derived rather than the author stated. Such a field would
	 * freeze today's guess into the file and outlive whatever it was derived from, so it is cleared
	 * and only then is the body re-serialised, see {@link AlbumDate#clearDerived(FolderResource)}.
	 * </p>
	 */
	private static byte[] stored(byte[] contents, FolderResource resource) throws IOException {
		return stored(contents, resource, false);
	}

	/**
	 * The bytes to store for a received sidecar, see {@link #stored(byte[], FolderResource)}.
	 *
	 * @param rearranged
	 *        Whether the parts were already put back into the order the folder stores, see
	 *        {@link Inboxes#restoreArrangement(File, FolderResource)}.
	 */
	private static byte[] stored(byte[] contents, FolderResource resource, boolean rearranged) throws IOException {
		// Both, always: clearing the derived date must not be skipped because the parts were
		// rearranged, nor the other way round.
		boolean cleared = AlbumDate.clearDerived(resource);
		if (!cleared && !rearranged) {
			return contents;
		}
		LOG.info("Dropping the derived date of a stored sidecar: it is answered, never kept.");
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer,
			StandardCharsets.UTF_8)))) {
			resource.writeTo(json);
		}
		return buffer.toByteArray();
	}

	/**
	 * Delivers the description of a folder, filtered to what the request may see.
	 *
	 * <p>
	 * The cache keeps the folder as it is on disk; only the answer is filtered, see
	 * {@link PrivacyFilter}.
	 * </p>
	 */
	private void serveFolder(Context context, PathInfo pathInfo, int clearance, int viewAs, Caller caller)
			throws IOException {
		Resource resource = _cache.lookup(pathInfo);

		// What this caller may see of an inbox -- of this folder, if it is one, and of the inboxes
		// among the entries of this listing, see issue #131.
		Resource shown = Inboxes.filter(resource, Inboxes.visibility(_auth, caller, pathInfo, viewAs),
			caller.subject());
		if (shown == null) {
			// An inbox this caller may not see is not there for them, and a refusal naming it
			// would say that there is something to be refused.
			LOG.warning("Hiding the inbox at '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Inboxes.NOT_FOUND);
			return;
		}
		resource = shown;

		if (jsonRequested(context)) {
			if (PhotoSearch.isSearch(resource)) {
				String refusal = PhotoSearch.refusal(((AlbumInfo) resource).getQuery());
				if (refusal != null) {
					// Never a silently narrower (or wider) answer than its author meant, see #227.
					LOG.warning("Refusing the saved search '" + context.request().getPathInfo() + "': " + refusal);
					errorInfo(context, HttpServletResponse.SC_NOT_IMPLEMENTED, refusal);
					return;
				}
			}
			Resource answer = folderAnswer(resource, pathInfo, caller, clearance, viewAs);
			// A saved search answers its photographs slim, see ImagePart#isPartial().
			serveJson(context.response(),
				PhotoSearch.partial(localized(withRights(answer, _auth.rights(caller, pathInfo)), context)));
		} else {
			error404(context);
		}
	}



	/**
	 * The given answer with its place names in the language the caller asked for
	 * (<code>Accept-Language</code>), see issue #234 and {@link PhotoPlaces#localize}.
	 *
	 * @param answer
	 *        The caller's own copy of an album (what {@link #withRights(Resource, Set)} answers) or a
	 *        photograph; never the cached album, which is never changed here.
	 */
	private Resource localized(Resource answer, Context context) {
		Places places = _places;
		if (places == null) {
			return answer;
		}
		return PhotoPlaces.localize(answer, PlaceNames.language(context.request().getHeader("Accept-Language")),
			places);
	}

	/**
	 * Answers <code>&lt;folder&gt;/?type=image-count</code>, see {@link ImageCount} and issue #286:
	 * how many photographs the caller is shown on opening the folder, asked by the app when it is
	 * about to show the count of a tile whose listing did not carry one.
	 *
	 * <p>
	 * Reached exactly as {@link #serveFolder(Context, PathInfo, int, int, Caller)} is, after the
	 * same checks, and refused where opening would be: an inbox the caller may not see is not
	 * there, a saved search that cannot be run is refused with its reason. The count is taken of
	 * the very answer opening the folder would build, without what a count does not need: the
	 * faces, the labels a client offers, the places.
	 * </p>
	 */
	private void serveImageCount(Context context, PathInfo pathInfo, int clearance, int viewAs, Caller caller)
			throws IOException {
		Resource resource = _cache.lookup(pathInfo);
		Resource shown = Inboxes.filter(resource, Inboxes.visibility(_auth, caller, pathInfo, viewAs),
			caller.subject());
		if (shown == null) {
			LOG.warning("Hiding the inbox at '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Inboxes.NOT_FOUND);
			return;
		}
		int minRating = _auth.minRating(caller, pathInfo, viewAs);
		String label = _auth.photoLabel(caller);
		Resource answer;
		if (PhotoCollections.isCollection(shown)) {
			// The references resolved as opening the collection resolves them (see
			// #folderAnswer), without the faces, then the collection's own filters on top.
			AlbumInfo resolved = _collections.resolve((AlbumInfo) shown,
				source -> sourceAlbum(source, caller, viewAs, null, false), false, false);
			answer = _privacy.filter(resolved, pathInfo, clearance, minRating, label);
		} else if (PhotoSearch.isSearch(shown)) {
			String refusal = PhotoSearch.refusal(((AlbumInfo) shown).getQuery());
			if (refusal != null) {
				LOG.warning("Refusing the saved search '" + context.request().getPathInfo() + "': " + refusal);
				errorInfo(context, HttpServletResponse.SC_NOT_IMPLEMENTED, refusal);
				return;
			}
			answer = _privacy.filter(searchAnswer((AlbumInfo) shown, pathInfo, caller, viewAs), pathInfo, clearance,
				minRating, label);
		} else {
			answer = _privacy.filter(shown, pathInfo, clearance, minRating, label);
		}
		// A folder of folders shows no photograph of its own.
		int count = answer instanceof AlbumInfo ? ResourceCache.imageCount((AlbumInfo) answer) : 0;
		serveJsonObject(context.response(), ImageCount.create().setCount(count));
	}

	/**
	 * The given folder answer carrying the caller's rights on it, see {@link FolderResource#getRights()}.
	 *
	 * <p>
	 * Always on a copy: what the {@link ResourceCache} holds is one object shared by every request
	 * and by the sidecar a move rewrites, and one caller's rights are not another's. The copy
	 * shares the parts of the cached album, exactly as the copy of the {@link PrivacyFilter} does
	 * — it is only ever serialised into the response.
	 * </p>
	 */
	private static Resource withRights(Resource resource, Set<String> rights) {
		if (!(resource instanceof FolderResource)) {
			return resource;
		}
		FolderResource copy;
		if (resource instanceof AlbumInfo) {
			AlbumInfo album = (AlbumInfo) resource;
			AlbumInfo result = AlbumInfo.create()
				// Whether this is an album or an inbox is not a question of who is asking.
				.setKind(album.getKind())
				// What a saved search looks for is not a question of who is asking (#227).
				.setQuery(album.getQuery())
				.setTitle(album.getTitle())
				.setSubTitle(album.getSubTitle())
				.setStarred(album.isStarred())
				.setPlaceCaption(album.getPlaceCaption())
				.setDate(album.getDate())
				.setEffectiveDate(album.getEffectiveDate())
				// How far the face index has got is not a question of who is asking either, and
				// this copy is made after it was answered, see issue #124.
				.setFacesPending(album.isFacesPending())
				// Nor whether the background recorded the folder, see issue #253.
				.setPreparing(album.isPreparing())
				.setParts(album.getParts());
			if (album.getIndexPicture() != null) {
				result.setIndexPicture(album.getIndexPicture());
			}
			copy = result;
		} else if (resource instanceof ListingInfo) {
			ListingInfo listing = (ListingInfo) resource;
			copy = ListingInfo.create()
				.setTitle(listing.getTitle())
				.setPlacement(listing.getPlacement())
				// The choice of issue #110 rides along with every copy of a listing.
				.setIndex(listing.getIndex())
				.setStarred(listing.isStarred())
				.setFolders(listing.getFolders());
		} else {
			return resource;
		}
		copy.setRights(Rights.onTheWire(rights));
		return copy;
	}

	/**
	 * Delivers an image: its description, its thumbnail or the original.
	 *
	 * <p>
	 * A path is not a permission: an image above the request's clearance or below its rating limit
	 * is refused here, whichever of the three is asked for, see
	 * {@link #imageRefused(Context, Caller, String)}.
	 * </p>
	 */
	private void serveImage(Context context, PathInfo pathInfo, Caller caller, int clearance, int minRating,
			int viewAs) throws IOException {
		String refusal = hidden(pathInfo, clearance, minRating);
		if (refusal != null) {
			imageRefused(context, caller, refusal);
			return;
		}

		Resource described = _cache.lookup(pathInfo);
		if (!(described instanceof ImagePart)) {
			// A file the album does not describe (yet) is no photograph here, in any of its shapes:
			// the background has not recorded it, see issue #253, and nothing about it -- its
			// privacy above all -- is known.
			notFound(context, pathInfo);
			return;
		}

		String type = context.getParameter("type");
		if (BrokenParts.isBroken((ImagePart) described) && !jsonRequested(context) && type != null) {
			// Nothing is made of a file that cannot be read; its original can still be taken, see
			// issue #253.
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND,
				brokenFile(pathInfo.getName(), ((ImagePart) described).getBroken()));
			return;
		}

		if (jsonRequested(context)) {
			Resource resource = _cache.lookup(pathInfo);
			if (resource instanceof ImagePart && !Faces.maySee(_auth, caller, viewAs)) {
				// What an album answer hides is hidden here too: a single photograph carries the
				// tags of issue #125 in the cached object, and they are the members' business.
				resource = Faces.withoutTags((ImagePart) resource);
			}
			if (resource instanceof ImagePart && !Faces.maySee(_auth, caller, viewAs)) {
				// The labels are the members' bookkeeping too, see issue #213.
				resource = Labels.withoutLabels((ImagePart) resource);
			}
			serveJson(context.response(), localized(resource, context));
			return;
		}

		if (makes(type)) {
			// Somebody waits for this: the background work gives way until it is answered, see
			// issue #236.
			Background.requestStarted();
			try {
				serveMade(context, pathInfo, caller, viewAs, type);
			} finally {
				Background.requestEnded();
			}
			return;
		}
		serveMade(context, pathInfo, caller, viewAs, type);
	}

	/**
	 * The message a request for a picture of a file that cannot be read is refused with, see issue
	 * #253.
	 */
	public static String brokenFile(String name, String reason) {
		return "The file '" + name + "' cannot be read (" + reason + "): there is no picture of it.";
	}

	/**
	 * Whether a request of the given type may make something a person waits for: a thumbnail, a
	 * display rendition, a face crop, a video rendition.
	 */
	private boolean makes(String type) {
		// With a background, a preview, a display rendition and a face crop are served as it made
		// them, never made here, see issue #256. Neither is a request answered "not there yet":
		// counted, the app asking again would hold up the very work it waits for (issue #258).
		return (!_cache.hasBackground() && ("tn".equals(type) || DISPLAY_TYPE.equals(type) || FACE_TYPE.equals(type)))
			|| VideoRenditions.Kind.PLAYBACK.parameter().equals(type)
			|| VideoRenditions.Kind.TEASER.parameter().equals(type);
	}

	/** Delivers an image that is no JSON answer: see {@link #serveImage}. */
	private void serveMade(Context context, PathInfo pathInfo, Caller caller, int viewAs, String type)
			throws IOException {
		if ("tn".equals(type)) {
			String size = context.getParameter(SIZE_PARAMETER);
			boolean large = SIZE_LARGE.equals(size);
			if (size != null && !large && !SIZE_NORMAL.equals(size)) {
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, SIZE_UNKNOWN);
				return;
			}
			double[] region = null;
			String cropParameter = context.getParameter(CROP_PARAMETER);
			if (cropParameter != null) {
				region = askedRegion(context, pathInfo, caller, cropParameter);
				if (region == null) {
					return;
				}
			}
			long poster = 0;
			String posterParameter = context.getParameter(POSTER_PARAMETER);
			if (posterParameter != null) {
				poster = askedPoster(context, pathInfo, caller, posterParameter);
				if (poster < 0) {
					return;
				}
			}
			File data;
			try {
				if (_cache.hasBackground()) {
					// Served as the background made it, or not there yet: a request never makes a
					// preview and never looks at the original, see issue #256.
					File original = pathInfo.toFile();
					double[] cut = region;
					long moment = poster;
					data = PreviewCache.previewTarget(original, cut, large, moment);
					if (!PreviewCache.present(data)) {
						notMadeYet(context, original, data, failure -> previewFailed(original, failure),
							() -> {
								// The poster of a video at its chosen moment (#285), the large preview
								// of a starred tile (#241), a cut one, the plain one.
								if (moment > 0) {
									PreviewCache.createPoster(original, moment);
								} else if (large) {
									PreviewCache.createLargePreview(original, cut);
								} else {
									PreviewCache.createPreview(original, cut);
								}
							});
						return;
					}
				} else if (poster > 0) {
					// A video's poster has one size, see PreviewCache#createLargePreview.
					data = PreviewCache.createPoster(pathInfo.toFile(), poster);
				} else {
					// Nobody else makes it, see ResourceCache#setBackground(boolean). The large preview
					// of a starred tile is made like the preview, only larger (#241).
					data = large ? PreviewCache.createLargePreview(pathInfo.toFile(), region)
						: PreviewCache.createPreview(pathInfo.toFile(), region);
				}
			} catch (PreviewException ex) {
				LOG.log(Level.WARNING, ex.getMessage(), ex.getCause());
				errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, previewFailed(pathInfo.toFile(), ex));
				return;
			}
			serveData(context, data, "image/jpeg");
		} else if (DISPLAY_TYPE.equals(type)) {
			serveDisplay(context, pathInfo.toFile());
		} else if (FACE_TYPE.equals(type)) {
			serveFace(context, pathInfo, caller, viewAs);
		} else if (VideoRenditions.Kind.PLAYBACK.parameter().equals(type)) {
			serveRendition(context, pathInfo, VideoRenditions.Kind.PLAYBACK);
		} else if (VideoRenditions.Kind.TEASER.parameter().equals(type)) {
			serveRendition(context, pathInfo, VideoRenditions.Kind.TEASER);
		} else {
			Resource resource = _cache.lookup(pathInfo);
			if (resource != null) {
				// The raw companion of a photograph is served as what it is, see issue #191.
				String mimeType = RawFile.isRawName(pathInfo.getName()) ? RawFile.contentType(pathInfo.getName())
					: mimeType(context, resource);

				serveData(context, pathInfo.toFile(), mimeType);
			} else {
				// Never an empty answer: a file the album does not describe is no photograph here.
				notFound(context, pathInfo);
			}
		}
	}

	/**
	 * Answers <code>&lt;image&gt;?type=display</code>: the full-size JPEG the viewer shows in place
	 * of a HEIC/HEIF original that no browser but Safari and no app decoder can read, see issue
	 * #186 and {@link PreviewCache#createDisplay(File)}.
	 *
	 * <p>
	 * It asks the {@link Rights#DOWNLOAD} right, exactly as the original it stands in for: a
	 * <code>view</code>-only caller is shown the <code>?type=tn</code> rendition of a HEIC as of a
	 * JPEG (issue #95), and is not handed a picture of the original's size through the side door of
	 * its format.
	 * </p>
	 */
	private void serveDisplay(Context context, File file) throws IOException {
		if (!PreviewCache.needsDisplay(file)) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, DISPLAY_NOT_NEEDED);
			return;
		}
		File data;
		if (_cache.hasBackground()) {
			// As the background made it, or not there yet, see issue #256.
			data = PreviewCache.displayFile(file);
			if (!PreviewCache.present(data)) {
				notMadeYet(context, file, data, failure -> displayFailed(file, failure),
					() -> PreviewCache.createDisplay(file));
				return;
			}
		} else {
			try {
				data = PreviewCache.createDisplay(file);
			} catch (PreviewException ex) {
				LOG.log(Level.WARNING, ex.getMessage(), ex.getCause());
				errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, displayFailed(file, ex));
				return;
			}
		}
		serveData(context, data, "image/jpeg");
	}

	/**
	 * The message a display rendition that cannot be made is answered with: which raw carries no
	 * preview (issue #191); which picture, how much it needs and what to do (issues #207, #193); why
	 * a server cannot decode a coded picture; else {@link #DISPLAY_FAILED}.
	 */
	static String displayFailed(File file, PreviewException failure) {
		if (failure.getCause() instanceof NoEmbeddedPreviewException
			|| failure.getCause() instanceof PictureTooLargeException) {
			return failure.getCause().getMessage();
		}
		String unavailable = CodedPictures.unavailability(file.getName());
		return unavailable != null ? unavailable : DISPLAY_FAILED;
	}

	/**
	 * Answers a request for a generated file the background has not made (yet), see issue #256:
	 * <code>202 Accepted</code> with {@link #PREVIEW_PENDING}, the convention of a video rendition
	 * that is not there yet (issue #74) &mdash; or, where the background tried and failed, that
	 * failure, because coming back would not help.
	 *
	 * <p>
	 * Somebody waits for it: the background is told, and makes it ahead of its catch-up, see issue
	 * #258. Nothing is made here, and such a request is no request the background gives way to
	 * ({@link #makes(String)}): the app asking again must not hold up what it waits for.
	 * </p>
	 */
	private void notMadeYet(Context context, File original, File generated,
			java.util.function.Function<PreviewException, String> message,
			de.haumacher.imageServer.pipeline.FolderPipeline.Demand make) throws IOException {
		PreviewException failure = PreviewCache.failure(generated);
		if (failure != null) {
			errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message.apply(failure));
			return;
		}
		de.haumacher.imageServer.pipeline.FolderPipeline.demanded(original, generated, make);
		notThereYet(context);
	}

	/**
	 * Answers <code>202 Accepted</code> with {@link #PREVIEW_PENDING}, and when to ask again:
	 * {@link #PREVIEW_RETRY_AFTER_SECONDS}.
	 */
	private static void notThereYet(Context context) throws IOException {
		context.response().setHeader("Retry-After", PREVIEW_RETRY_AFTER_SECONDS);
		errorInfo(context, HttpServletResponse.SC_ACCEPTED, PREVIEW_PENDING);
	}

	/**
	 * Answers <code>&lt;video&gt;?type=media-url&amp;for=video|teaser|original</code> with a
	 * {@link MediaUrl}, see issue #185 and {@link MediaSignatures} — and
	 * <code>&lt;image&gt;?type=media-url&amp;for=original</code> for a photograph, the address the
	 * browser downloads it from by itself, see issue #209.
	 *
	 * <p>
	 * Everything the plain request for that file would be asked is asked here, before anything is
	 * signed: the inbox rule (by the caller of {@link #doGet}), the right —
	 * {@link Rights#VIEW} for the rendition and the teaser, {@link Rights#DOWNLOAD} for the
	 * original —, the clearance and the rating floor. The request carrying the signature is then
	 * served as the device or link would have been served, and is asked all of it again.
	 * </p>
	 */
	private void issueMediaUrl(Context context, Caller caller, PathInfo pathInfo, int viewAs) throws IOException {
		MediaSignatures.Kind kind = MediaSignatures.Kind.named(context.getParameter(MEDIA_FOR_PARAMETER));
		if (kind == null) {
			LOG.warning("Refusing a media address of '" + context.request().getPathInfo() + "' for '"
				+ context.getParameter(MEDIA_FOR_PARAMETER) + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, MEDIA_KIND_UNKNOWN);
			return;
		}
		if (kind != MediaSignatures.Kind.ORIGINAL && !VideoRenditions.isVideo(pathInfo.toFile())) {
			// The original of a photograph has an address too: the browser's own download of it,
			// see issue #209. A rendition and a teaser are a video's alone.
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, MEDIA_NOT_A_VIDEO);
			return;
		}
		String right = kind.right();
		if (!_auth.rights(caller, pathInfo).contains(right)) {
			refuse(context, caller, pathInfo, right, false);
			return;
		}
		int clearance = Math.min(_auth.clearance(caller, pathInfo), viewAs);
		String refusal = hidden(pathInfo, clearance, _auth.minRating(caller, pathInfo, viewAs));
		if (refusal != null) {
			imageRefused(context, caller, refusal);
			return;
		}
		HttpServletRequest request = context.request();
		String path = request.getPathInfo() == null ? "" : request.getPathInfo();
		java.time.Instant expires =
			java.time.Instant.now().plus(MediaSignatures.LIFETIME).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		String signature;
		try {
			signature = _auth.signMedia(caller, path, kind, expires.getEpochSecond());
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing a media address of '" + path + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}
		String query = (kind.getType() == null ? "" : "type=" + kind.getType() + "&")
			+ MediaSignatures.PARAMETER + "=" + signature;
		String url = request.getContextPath() + request.getServletPath() + encodePath(path) + "?" + query;
		serveJsonObject(context.response(),
			MediaUrl.create().setUrl(url).setMedia(signature).setExpires(expires.toString()));
	}

	/** The given path with every segment percent-encoded, the slashes kept. */
	private static String encodePath(String path) {
		StringBuilder result = new StringBuilder();
		String[] segments = path.split("/", -1);
		for (int n = 0; n < segments.length; n++) {
			if (n > 0) {
				result.append('/');
			}
			result.append(java.net.URLEncoder.encode(segments[n], StandardCharsets.UTF_8).replace("+", "%20"));
		}
		return result.toString();
	}

	/**
	 * Answers the people of this space at <code>&lt;data&gt;/?type=people</code>, see issue #125.
	 *
	 * <p>
	 * The members of the space, and nobody else: a share link is told so (<code>403</code>) and an
	 * anonymous caller is asked to sign in (<code>401</code>), exactly as with the faces themselves
	 * (issue #124). A server running without authentication has no members to be one of — everybody
	 * who reaches it is its owner and is answered like one.
	 * </p>
	 *
	 * <p>
	 * Every role sees the register: the names are what the boxes in an album mean, so somebody who
	 * may only look needs them to be able to read what they are shown. Changing it needs
	 * {@link Rights#EDIT}, see {@link #editPeople(Context, String)}.
	 * </p>
	 */
	private void servePeople(Context context, Caller caller) throws IOException {
		if (caller.isShareLink()) {
			LOG.warning("Refusing the people to the share link '" + caller.getShareLabel() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, PEOPLE_REFUSED);
			return;
		}
		if (!caller.isPaired() && _auth.getMode() != AuthMode.OFF) {
			unauthorized(context, caller, false);
			return;
		}
		serveJsonObject(context.response(), _people.toWire());
	}

	/**
	 * Answers how far the background work of this space has got at
	 * <code>&lt;data&gt;/?type=catch-up</code>, see issue #236 and {@link CatchUp}.
	 *
	 * <p>
	 * The administrator's question, like the user list: a caller who is not signed in is asked to
	 * sign in (a share link is refused, it has nothing to sign in as), and a member who is no
	 * administrator is refused with {@link #CATCH_UP_REFUSED}.
	 * </p>
	 */
	private void serveCatchUp(Context context, Caller caller) throws IOException {
		if (_auth.getMode() != AuthMode.OFF && !caller.isPaired()) {
			unauthorized(context, caller, false);
			return;
		}
		if (!_auth.maySeeUsers(caller)) {
			LOG.warning("Refusing the background progress to '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, CATCH_UP_REFUSED);
			return;
		}
		serveJsonObject(context.response(), CatchUp.status(_index.pipeline()));
	}

	/**
	 * Answers the bell of issue #268 at <code>&lt;data&gt;/?type=news</code> (and its number of unread
	 * entries alone at <code>?type=news-count</code>), see {@link Arrivals}: to a member only, the
	 * path of the request is not looked at.
	 */
	private void serveNews(Context context, Caller caller, boolean countOnly) throws IOException {
		if (!mayHaveNews(context, caller)) {
			return;
		}
		serveJsonObject(context.response(), arrivals().news(caller.subject(), newsView(caller), countOnly));
	}

	/**
	 * Marks entries of the bell of issue #268 as read, <code>POST &lt;data&gt;/?action=news-read</code>
	 * with a {@link NewsRead}, and answers the bell as it is then.
	 */
	private void markNewsRead(Context context, Caller caller) throws IOException {
		if (!mayHaveNews(context, caller)) {
			return;
		}
		NewsRead request;
		try {
			request = NewsRead.readNewsRead(json(readBody(context.request())));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable request marking news as read: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, NEWS_READ_UNREADABLE);
			return;
		}
		List<String> keys = new ArrayList<>();
		for (NewsKey key : request.getKeys()) {
			keys.add(key.getId());
		}
		Arrivals arrivals = arrivals();
		Arrivals.View view = newsView(caller);
		arrivals.markRead(caller.subject(), view, keys, request.isAll());
		serveJsonObject(context.response(), arrivals.news(caller.subject(), view, false));
	}

	/** Whether the given caller is a member, who has a bell; refuses everybody else, see issue #268. */
	private boolean mayHaveNews(Context context, Caller caller) throws IOException {
		if (caller.isPaired() && !caller.isShareLink()) {
			return true;
		}
		LOG.warning("Refusing the news to " + (caller.isShareLink()
			? "the share link '" + caller.getShareLabel() + "'" : "a caller who is no member") + ".");
		errorInfo(context, HttpServletResponse.SC_FORBIDDEN, NEWS_REFUSED);
		return false;
	}

	/** The bell of this space, see issue #268. */
	Arrivals arrivals() {
		return new Arrivals(_spaceIndex, _newsClock);
	}

	/** The time of the bell, for the tests. */
	private Arrivals.Clock _newsClock = System::currentTimeMillis;

	/** Sets the time of the bell, for the tests. */
	void setNewsClock(Arrivals.Clock clock) {
		_newsClock = clock;
	}

	/**
	 * What the given member is shown of an album in the bell of issue #268: what the album itself
	 * shows them &mdash; their right to view it, their clearance, never the trash &mdash;, and never
	 * the inbox, where nothing has arrived for anybody else yet.
	 */
	private Arrivals.View newsView(Caller caller) {
		Path root = _basePath;
		return folder -> {
			PathInfo path = folder.isEmpty() ? new PathInfo(root) : new PathInfo(root, Paths.get(folder));
			if (Inboxes.isInbox(path.toFile()) || !_auth.mayView(caller, path)) {
				return null;
			}
			Resource resource = lookupOrSkip(path);
			if (!(resource instanceof AlbumInfo)) {
				return null;
			}
			// Viewed as a member and never as the owner's preview: the trash is never news.
			Resource filtered = _privacy.filter(resource, path, _auth.clearance(caller, path),
				_auth.minRating(caller, path, Privacy.MEMBERS), _auth.photoLabel(caller));
			if (!(filtered instanceof AlbumInfo)) {
				return null;
			}
			AlbumInfo album = (AlbumInfo) filtered;
			List<String> names = new ArrayList<>();
			Arrivals.collectNames(album.getParts(), names);
			String title = album.getTitle() == null || album.getTitle().trim().isEmpty()
				? path.toFile().getName() : album.getTitle();
			return new Arrivals.Album(title, names);
		};
	}

	/**
	 * Answers the photographs of this space that lie in more than one album at
	 * <code>&lt;data&gt;/?type=duplicates</code>, see issue #220 and {@link Duplicates}.
	 *
	 * <p>
	 * Read-only, and asked of every member, not of editors alone: every copy is answered only where
	 * the album answers it to this caller — their clearance, the trash, the inbox as
	 * {@link Inboxes#visibility} shows it to them — so a <code>view</code> member learns nothing
	 * they could not find by opening the albums, and is spared the search. A share link and an
	 * anonymous caller are refused (<code>403</code>, {@link #DUPLICATES_REFUSED}): an overview of
	 * the whole space is no album. Under <code>--auth off</code> everybody is the owner.
	 * </p>
	 */
	private void serveDuplicates(Context context, Caller caller) throws IOException {
		if (caller.isShareLink() || (!caller.isPaired() && _auth.getMode() != AuthMode.OFF)) {
			LOG.warning("Refusing the duplicates to " + (caller.isShareLink()
				? "the share link '" + caller.getShareLabel() + "'" : "an anonymous caller") + ".");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, DUPLICATES_REFUSED);
			return;
		}
		int viewAs;
		try {
			viewAs = Privacy.viewAs(context.getParameter(Privacy.VIEW_AS_PARAMETER));
		} catch (IllegalArgumentException ex) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, VIEW_AS_REFUSED);
			return;
		}
		Path root = _basePath;
		Duplicates.View view = new Duplicates.View() {
			@Override
			public AlbumInfo album(String folder) {
				PathInfo path = folder.isEmpty() ? new PathInfo(root) : new PathInfo(root, Paths.get(folder));
				if (!_auth.mayView(caller, path)) {
					return null;
				}
				Resource resource = lookupOrSkip(path);
				return resource instanceof AlbumInfo ? (AlbumInfo) resource : null;
			}

			@Override
			public ImagePart photo(String file) {
				PathInfo path = new PathInfo(root, Paths.get(file));
				PathInfo folder = path.parent();
				Resource resource = lookupOrSkip(path);
				if (!(resource instanceof ImagePart)) {
					return null;
				}
				ImagePart image = (ImagePart) resource;
				if (Inboxes.isInbox(folder.toFile())) {
					Inboxes.Visibility inbox = Inboxes.visibility(_auth, caller, folder, viewAs);
					if (!Inboxes.shows(inbox, image, caller.subject())) {
						return null;
					}
				}
				int clearance = Math.min(_auth.clearance(caller, path), viewAs);
				return Duplicates.shows(image, clearance, _auth.minRating(caller, path, viewAs)) ? image : null;
			}
		};
		serveJsonObject(context.response(), Duplicates.compute(_index.shared(), _index.progress(), view));
	}

	/**
	 * Creates, renames or merges a person of this space, see issue #125.
	 *
	 * <p>
	 * The register is the space's, not a folder's, so all three ask for the same thing:
	 * {@link Rights#EDIT}, which since issue #83 is a property of the caller and not of the path.
	 * A share link is refused whatever it carries — a link is handed an album, never the space's
	 * bookkeeping.
	 * </p>
	 *
	 * <p>
	 * Nothing here ever touches an album. A merge writes an alias into the register and the read
	 * path resolves it, see {@link PeopleStore#resolve(String)}: renaming somebody or deciding that
	 * two of them are one is a single small write, whatever they are tagged in.
	 * </p>
	 */
	private void editPeople(Context context, String action) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo path = location.getPath();
		if (caller.isShareLink()) {
			LOG.warning("Refusing '" + action + "' to the share link '" + caller.getShareLabel() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, PEOPLE_REFUSED);
			return;
		}
		if (!_auth.mayEdit(caller, path)) {
			refuse(context, caller, path, Rights.EDIT, true);
			return;
		}

		byte[] contents = readBody(context.request());
		PersonCreate create = null;
		PersonRename rename = null;
		PersonMerge merge = null;
		try {
			switch (action) {
				case "create-person":
					create = PersonCreate.readPersonCreate(json(contents));
					break;
				case "rename-person":
					rename = PersonRename.readPersonRename(json(contents));
					break;
				default:
					merge = PersonMerge.readPersonMerge(json(contents));
					break;
			}
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unreadable '" + action + "' request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PERSON_UNREADABLE);
			return;
		}

		PeopleStore.Entry entry;
		try {
			if (create != null) {
				entry = _people.create(create.getName(), caller.subject());
			} else if (rename != null) {
				entry = _people.rename(rename.getId(), rename.getName());
			} else {
				entry = _people.merge(merge.getInto(), merge.getFrom());
			}
		} catch (PeopleStore.PersonRefused ex) {
			LOG.warning("Refusing '" + action + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}

		serveJsonObject(context.response(), entry.toWire());
	}

	/**
	 * Says that a person of the register <em>is</em> a member of this space, see issue #128.
	 *
	 * <p>
	 * <b>Who may say it is the whole of this endpoint.</b> An administrator links anybody to
	 * anybody: they manage the accounts of the space anyway. Everybody else may say
	 * &quot;this is me&quot; and nothing more &mdash; a member with {@link Rights#EDIT} links a
	 * person to their own account and unlinks the person that carries it, and naming somebody
	 * else's account is refused {@link #LINK_REFUSED}. Putting a name to a face of somebody else's
	 * family is one thing; putting their <em>account</em> to it is a statement about them.
	 * </p>
	 *
	 * <p>
	 * The link is written on the person and nowhere else (see
	 * {@link PeopleStore#link(String, String)}), so unlinking is the same request with an empty
	 * name and there is no second place to keep in step. A member is at most one person
	 * (<code>409</code>), and merging two people who are both a member is refused rather than
	 * guessed at (see {@link PeopleStore#MERGE_LINKED}).
	 * </p>
	 */
	private void linkPerson(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo path = location.getPath();
		if (caller.isShareLink()) {
			LOG.warning("Refusing 'link-person' to the share link '" + caller.getShareLabel() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, PEOPLE_REFUSED);
			return;
		}

		PersonLink request;
		try {
			request = PersonLink.readPersonLink(json(readBody(context.request())));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable 'link-person' request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PERSON_UNREADABLE);
			return;
		}
		String name = request.getUser() == null ? "" : request.getUser().trim();

		if (!_auth.mayAdminister(caller)) {
			if (!caller.isPaired()) {
				unauthorized(context, caller, true);
				return;
			}
			if (!_auth.mayEdit(caller, path)) {
				LOG.warning("Refusing 'link-person' to '" + caller.getUserName() + "'.");
				errorInfo(context, HttpServletResponse.SC_FORBIDDEN, LINK_REFUSED);
				return;
			}
			// "This is me", and nothing else: linking one's own account, or taking one's own
			// account off the person that carries it.
			String own = caller.getUserName();
			String subject = name.isEmpty() ? linkedUserOf(request.getId()) : name;
			if (own.isEmpty() || !own.equals(subject)) {
				LOG.warning("Refusing 'link-person' of '" + subject + "' to '" + own + "'.");
				errorInfo(context, HttpServletResponse.SC_FORBIDDEN, LINK_REFUSED);
				return;
			}
		}

		if (!name.isEmpty()) {
			UserStore users = _auth.getUsers();
			UserStore.User user = users == null ? null : users.getUser(name);
			if (user == null || user.getName().isEmpty() || user.isPending()) {
				// A pending user is nobody yet (issue #89): they have no name of their own, so
				// there is nothing to link a person to.
				LOG.warning("Refusing 'link-person' with the unknown member '" + name + "'.");
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, AuthService.unknownUser(name));
				return;
			}
		}

		PeopleStore.Entry entry;
		try {
			entry = _people.link(request.getId(), name);
		} catch (PeopleStore.PersonRefused ex) {
			LOG.warning("Refusing 'link-person': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}
		LOG.info("The person '" + entry.getName() + "' is "
			+ (name.isEmpty() ? "nobody in particular." : "the member '" + name + "'."));
		serveJsonObject(context.response(), entry.toWire());
	}

	/** Which member the person of the given id carries, the empty string for nobody. */
	private String linkedUserOf(String id) {
		PeopleStore.Entry entry = _people.resolve(id);
		return entry == null ? "" : entry.getUser();
	}

	/** A reader over the given request body. */
	private static JsonReader json(byte[] contents) {
		return new JsonReader(new ReaderAdapter(
			new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8)));
	}

	/**
	 * Stores what somebody decided about the faces of one album, see issue #125.
	 *
	 * <p>
	 * A detection is a guess of a model and lives in the album's cache; a name under a face is a
	 * human decision and belongs beside the photograph, in <code>index.json</code>, as a
	 * {@link FaceTag} of its {@link ImagePart}. This is the one place such a tag is written, and it
	 * writes the sidecar exactly as <code>?action=place</code> does: through
	 * {@link #storeSidecar(File, byte[])}, with the derived fields cleared, so that there is one
	 * sidecar format however a file came to be written.
	 * </p>
	 *
	 * <p>
	 * The request names a face by the {@link FaceInfo#getIndex() index} of the answer it read, and
	 * the server copies the box out of that answer: a client never sends coordinates, so it can
	 * neither invent a face that was not answered to it nor write a box in the wrong frame. The box
	 * is kept in the raw raster of the file (see {@link Faces}), which is what makes the tag outlive
	 * the cache it came from, the model that found it and any rotation the album applies.
	 * </p>
	 *
	 * <p>
	 * A second decision about a face <em>replaces</em> the first: a tag whose box the new one
	 * overlaps by more than {@link FaceTags#IOU_MATCH} is rewritten instead of being left beside it,
	 * so a face never carries two decisions at once.
	 * </p>
	 *
	 * <p>
	 * {@link FaceState#UNDECIDED} is the way back out (issue #138): on a detection it
	 * <em>removes</em> the tag on that box by the same overlap rule, so the face is answered as the
	 * plain detection it was and the recogniser of issue #127 may offer a suggestion for it again;
	 * on a face that is only a stored tag — one marked by hand — it keeps the region and takes the
	 * decision off it, a tag <code>{box, "", UNDECIDED}</code> (issue #155). It is idempotent
	 * &mdash; a face that carries no decision is left alone and nothing is refused &mdash; and a
	 * request that changes nothing writes nothing at all, so a sidecar is never touched for a no-op.
	 * </p>
	 *
	 * <p>
	 * With a box that meets none of the answered faces, {@link FaceState#UNDECIDED} marks a region
	 * (issue #155): {@link FaceIndex#mark(File, double, double, double, double)} looks for a face in
	 * the original around it and keeps what it finds as a detection of its own, and where it finds
	 * nothing the box is stored as an undecided tag. {@link FaceState#NOT_A_FACE} removes a region
	 * from every answer: a detection keeps its tag and is no longer answered (see {@link FaceTags}),
	 * a face that is only a tag loses it. A box marked again over a detection somebody called no
	 * face meets that detection, so any decision about it replaces the tag and brings it back.
	 * </p>
	 *
	 * <p>
	 * All or nothing: every name, index and person is checked before anything is written, because
	 * an album half tagged is a state nobody asked for.
	 * </p>
	 *
	 * <h3>Adjusting a box: <code>?action=adjust-faces</code> (issue #157)</h3>
	 *
	 * <p>
	 * The same {@link TagFaces} message, the same rights and the same checks, posted under a
	 * separate action. There a {@link FaceAssignment} carries <b>both</b> a {@link FaceAssignment#getFace()
	 * face} and a box, and means "this face, with this box": the face that was answered under that
	 * number is given the new box, in the frame the wire speaks (the picture upright, issue #142),
	 * turned into the raw raster by {@link Faces#toRaw}. The tag that face carried — found by the
	 * face's answered box, the overlap rule of every other write — is removed, and a tag on the new
	 * box is stored carrying the assignment's person and state: the client sends the decision the
	 * face stands under (or a new one — one request may move a box and name the face in it), and
	 * {@link FaceState#UNDECIDED} leaves the face undecided at the new box, a stored undecided tag
	 * being a region nobody decided about since issue #155. The new box is <em>not</em> snapped onto
	 * a detection it overlaps, as a mark is: a box somebody moved by hand is the human's statement
	 * about where the face is, and in the answer it wins over the detector's (see {@link FaceTags}).
	 * {@link FaceState#NOT_A_FACE} there means what it means here and ignores the box. An adjustment
	 * without a box, or with one that is not a place on the photograph, is refused with
	 * {@link #faceBoxInvalid(String)}; a number that names no answered face with
	 * {@link #unknownFace(String, int)}.
	 * </p>
	 *
	 * <p>
	 * <b>Why a separate action and not a meaning of <code>tag-faces</code>.</b> The field
	 * {@link FaceAssignment#getFace()} is a plain number whose default is <code>0</code>, and every
	 * client writes it: the viewer of issue #147, released since 2.7.0, marks a face by sending
	 * <code>face: 0</code> together with a box. Had "a face and a box" come to mean an adjustment
	 * under <code>tag-faces</code>, every mark of such an app would have silently moved face 0 of the
	 * photograph — its person and all — to the marked spot. An older client never calls
	 * <code>adjust-faces</code>, so under this action nothing moves behind anybody's back, and
	 * <code>tag-faces</code> keeps meaning exactly what it meant.
	 * </p>
	 *
	 * <p>
	 * A detection whose tag was adjusted keeps its region when its decision is forgotten: where the
	 * tag's box is not the detector's own, {@link FaceState#UNDECIDED} leaves an undecided tag at the
	 * adjusted box instead of removing the tag, so forgetting a name never throws away the box
	 * somebody drew — exactly what it does for a face marked by hand.
	 * </p>
	 *
	 * @param adjust
	 *        Whether the request is an <code>adjust-faces</code>.
	 */
	/**
	 * The region a <code>?type=tn&amp;crop=&hellip;</code> asks for, <code>null</code> where the
	 * request was answered with a refusal, see issue #212.
	 *
	 * <p>
	 * The region is spoken in the frame of the rendition ({@link Crops}). Whoever may see the
	 * photograph may ask for the region its part stores &mdash; that is what every tile of a cropped
	 * photograph asks &mdash;, and an editor of the album may ask for any region, because the app
	 * draws a crop it has just written before the album is read again. Anybody else asking for a
	 * region the album does not store is refused <code>409 CROP_NOT_STORED</code>: a cut preview is
	 * cached under its region, and a visitor must not be able to fill the cache with regions
	 * nobody chose.
	 * </p>
	 */
	private double[] askedRegion(Context context, PathInfo pathInfo, Caller caller, String parameter)
			throws IOException {
		double[] region = Crops.parse(parameter);
		if (region == null) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, CROP_UNREADABLE);
			return null;
		}
		if (PreviewCache.isVideoName(pathInfo.getName())) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, CROP_VIDEO_REFUSED);
			return null;
		}
		Resource resource = _cache.lookup(pathInfo);
		double[] stored = resource instanceof ImagePart ? Crops.renditionRegion((ImagePart) resource) : null;
		if (Crops.same(stored, region)) {
			return region;
		}
		if (!caller.isShareLink() && _auth.mayEdit(caller, pathInfo.parent())) {
			return region;
		}
		LOG.info("Refusing the cut preview " + parameter + " of '" + pathInfo.toPath() + "': " + CROP_NOT_STORED);
		errorInfo(context, HttpServletResponse.SC_CONFLICT, CROP_NOT_STORED);
		return null;
	}

	/**
	 * The moment a <code>?type=tn&amp;poster=&hellip;</code> asks for, <code>-1</code> where the
	 * request was answered with a refusal, see issue #285.
	 *
	 * <p>
	 * The rule of {@link #askedRegion}: whoever may see the video may ask for the moment its part
	 * stores &mdash; that is what every tile of it asks &mdash;, an editor of the album for any moment,
	 * because the app shows the poster it has just chosen before the album is read again, and anybody
	 * else is refused <code>409 POSTER_NOT_STORED</code>, so that a visitor cannot fill the cache
	 * with posters nobody chose. A moment of <code>0</code> is the first frame, which needs no
	 * permission.
	 * </p>
	 */
	private long askedPoster(Context context, PathInfo pathInfo, Caller caller, String parameter)
			throws IOException {
		long millis = Posters.parse(parameter);
		if (millis < 0) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, POSTER_UNREADABLE);
			return -1;
		}
		if (!PreviewCache.isVideoName(pathInfo.getName())) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, POSTER_NOT_A_VIDEO);
			return -1;
		}
		if (millis == 0) {
			return 0;
		}
		Resource resource = _cache.lookup(pathInfo);
		if (resource instanceof ImagePart && Posters.millis((ImagePart) resource) == millis) {
			return millis;
		}
		if (!caller.isShareLink() && _auth.mayEdit(caller, pathInfo.parent())) {
			return millis;
		}
		LOG.info("Refusing the poster " + parameter + " of '" + pathInfo.toPath() + "': " + POSTER_NOT_STORED);
		errorInfo(context, HttpServletResponse.SC_CONFLICT, POSTER_NOT_STORED);
		return -1;
	}

	/**
	 * Stores the crop of one photograph, <code>POST &lt;album&gt;/?action=crop</code>, see issue
	 * #212.
	 *
	 * <p>
	 * The body is an {@link ImagePart} carrying the {@link ImagePart#getName() name} of the
	 * photograph and the {@link ImagePart#getCrop() crop} to store; no crop (or one of the whole
	 * picture) takes the crop away. Nothing else of the part is read. An action and not a meaning of
	 * the sidecar <code>PUT</code>, because the crop is written at once from the viewer while the
	 * album may be open in an edit session holding unsaved changes: writing the whole buffer would
	 * store them too. The app puts the same crop into its copy of the part, so a later save of the
	 * buffer writes it again unchanged.
	 * </p>
	 *
	 * <p>
	 * Needs {@link Rights#EDIT} on the album (a share link and a contributor are refused
	 * <code>403</code>), refuses a video and a crop that is no region of the picture with
	 * <code>400</code> and an unknown photograph with <code>404</code>, writing nothing. Where the
	 * photograph is the album's picture (issue #154), its framing is measured anew on the cropped
	 * photograph ({@link PrivacyFilter#thumbnail(ImagePart)}): the old one was measured on another
	 * picture. The answer is the album as the caller is answered it.
	 * </p>
	 */
	/**
	 * Renames or removes one label on every photograph of the addressed album, see issue #213.
	 *
	 * <p>
	 * An action rather than a sidecar <code>PUT</code>, because the server must know that it was a
	 * rename: a share link on this album showing the label is carried along to the new name
	 * ({@link ShareStore#relabel}), the decision of #213, while a link whose label is removed goes
	 * on showing that label and therefore nothing. Needs {@link Rights#EDIT}; answers the album as
	 * the caller is answered it.
	 * </p>
	 */
	private void relabel(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo folderPath = location.getPath();
		if (caller.isShareLink()) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, Labels.RELABEL_REFUSED);
			return;
		}
		if (!_auth.mayEdit(caller, folderPath)) {
			if (!identified(caller)) {
				unauthorized(context, caller, true);
				return;
			}
			LOG.warning("Refusing the label change at '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, Labels.RELABEL_REFUSED);
			return;
		}
		File folder = folderPath.toFile();
		if (!folder.isDirectory()) {
			error404(context);
			return;
		}
		LabelChange request;
		try {
			request = LabelChange.readLabelChange(json(readBody(context.request())));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable label change: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, Labels.RELABEL_UNREADABLE);
			return;
		}
		String from = request.getFrom() == null ? "" : request.getFrom();
		String to = request.getTo() == null ? "" : request.getTo().trim();
		if (from.isEmpty()) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, Labels.RELABEL_UNREADABLE);
			return;
		}
		Resource resource = _cache.lookup(folderPath);
		if (!(resource instanceof AlbumInfo)) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, Labels.RELABEL_NOT_AN_ALBUM);
			return;
		}
		if (PhotoSearch.isSearch(resource)) {
			// The labels a saved search shows are the photographs' own, see issue #227.
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoSearch.RELABEL_REFUSED);
			return;
		}
		AlbumInfo album = (AlbumInfo) resource;
		if (!Labels.of(album).contains(from)) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, Labels.nothingLabeled(from));
			return;
		}
		if (!from.equals(to)) {
			int changed = Labels.relabel(album, from, to);
			storeSidecar(folder, sidecarOf(album));
			_cache.invalidate(folderPath);
			LOG.info((to.isEmpty() ? "Removed the label '" + from + "'" : "Renamed the label '" + from + "' to '" + to + "'")
				+ " on " + changed + " photograph(s) in '" + folder.getAbsolutePath() + "'.");
			if (!to.isEmpty() && _auth.getShares() != null) {
				_auth.getShares().relabel(location.getOwner(), location.getOwnerPath(), from, to);
			}
		}
		answerAlbum(context, folderPath, caller);
	}

	private void cropImage(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo folderPath = location.getPath();
		if (caller.isShareLink()) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, CROP_REFUSED);
			return;
		}
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, folderPath)) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayEdit(caller, folderPath)) {
			if (!identified(caller)) {
				unauthorized(context, caller, true);
				return;
			}
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, CROP_REFUSED);
			return;
		}
		File folder = folderPath.toFile();
		if (!folder.isDirectory()) {
			error404(context);
			return;
		}
		ImagePart request;
		try {
			// The part as every part is written, ["ImagePart", {...}].
			AlbumPart part = AlbumPart.readAlbumPart(json(readBody(context.request())));
			if (!(part instanceof ImagePart)) {
				throw new IllegalArgumentException("Not an image: " + part);
			}
			request = (ImagePart) part;
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable crop request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, CROP_REQUEST_UNREADABLE);
			return;
		}
		Resource resource = _cache.lookup(folderPath);
		PathInfo answerPath = folderPath;
		String name = request.getName();
		if (PhotoCollections.isCollection(resource) || PhotoSearch.isSearch(resource)) {
			// The crop is the photograph's own, written to the album it lies in, see issues #221 and #227.
			PathInfo photo = sourcePhoto(context, caller, folderPath, resource, name);
			if (photo == null) {
				return;
			}
			folderPath = photo.parent();
			folder = folderPath.toFile();
			name = photo.getName();
			resource = _cache.lookup(folderPath);
		}
		if (!(resource instanceof AlbumInfo)) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, CROP_NOT_AN_ALBUM);
			return;
		}
		AlbumInfo album = (AlbumInfo) resource;
		ImagePart image = Crops.findImage(album, name);
		if (image == null) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, unknownImage(request.getName()));
			return;
		}
		if (image.getKind() != ImageKind.IMAGE) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, CROP_VIDEO_REFUSED);
			return;
		}
		Crop crop = request.getCrop();
		if (crop != null && !Crops.isValid(crop)) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, cropInvalid(image.getName()));
			return;
		}
		Crop stored = crop == null || Crops.isWhole(crop) ? null : Crops.clamped(crop);
		double[] before = Crops.renditionRegion(image);
		image.setCrop(stored);
		if (!Crops.same(before, Crops.renditionRegion(image))) {
			ThumbnailInfo cover = album.getIndexPicture();
			if (cover != null && image.getName().equals(cover.getImage())) {
				album.setIndexPicture(PrivacyFilter.thumbnail(image));
			}
		}
		storeSidecar(folder, sidecarOf(album));
		_cache.invalidate(folderPath);
		LOG.info("Cropped '" + image.getName() + "' in '" + folder.getAbsolutePath() + "'.");
		answerAlbum(context, answerPath, caller);
	}

	/**
	 * The photograph a write addressed to the given collection or saved search by the given name is
	 * written to, see issues #221, #227 and #292; <code>null</code> where the request was answered.
	 *
	 * <p>
	 * It is the photograph the caller was shown under that name, which lies in an album of its own: what
	 * is said about it is the photograph's own and is written to that album. The caller's right to
	 * edit the collection or the saved search is no right to edit that album, so it is asked there,
	 * see {@link #sourceEditable(Caller, PathInfo)}, and refused <code>403</code> with
	 * {@link #sourceEditRefused(String)} where not held.
	 * </p>
	 */
	private PathInfo sourcePhoto(Context context, Caller caller, PathInfo folderPath, Resource resource, String name)
			throws IOException {
		PathInfo source;
		if (PhotoCollections.isCollection(resource)) {
			ImagePart reference = PhotoCollections.referenceNamed((AlbumInfo) resource, name);
			ImagePart shown = reference == null ? null
				: Crops.findImage(resolveCollection((AlbumInfo) resource, folderPath, caller, Privacy.PRIVATE), name);
			if (shown == null) {
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, unknownImage(name));
				return null;
			}
			source = shown.isMissing() ? null : _collections.locate(reference.getRef());
			if (source == null) {
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, PhotoCollections.MISSING);
				return null;
			}
		} else {
			ImagePart shown = Crops.findImage(searchAnswer((AlbumInfo) resource, folderPath, caller, Privacy.PRIVATE),
				name);
			source = shown == null ? null : photoNamed(folderPath, resource, name);
			if (source == null) {
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, unknownImage(name));
				return null;
			}
		}
		if (!sourceEditable(caller, source.parent())) {
			LOG.warning("Refusing the change of '" + name + "' at '" + context.request().getPathInfo()
				+ "': the album it lies in, '" + source.parent().toFile() + "', is not the caller's to edit.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, sourceEditRefused(name));
			return null;
		}
		return source;
	}

	/**
	 * Whether the given caller may write the sidecar of the given album, reached through a
	 * collection or a saved search, see issue #292.
	 *
	 * <p>
	 * The one rule of every such write: it is authorised against the album whose sidecar it writes,
	 * never against the collection or the saved search it was sent to. The caller must reach the
	 * album &mdash; a share link reaches its target and nothing outside it, see
	 * {@link AuthService#reachable(Caller, Path, PathInfo)} &mdash; and hold
	 * {@link Rights#EDIT} on it.
	 * </p>
	 */
	private boolean sourceEditable(Caller caller, PathInfo album) {
		return _auth.reachable(caller, _basePath, album) && _auth.mayEdit(caller, album);
	}

	/**
	 * Stores the moment the poster of one video shows, <code>POST &lt;album&gt;/?action=poster</code>,
	 * see issue #285.
	 *
	 * <p>
	 * The body is an {@link ImagePart} carrying the {@link ImagePart#getName() name} of the video and
	 * the {@link ImagePart#getPoster() poster} to store; no poster (or a moment of <code>0</code>)
	 * takes it away, which is the first frame again. Nothing else of the part is read. An action and
	 * not a meaning of the sidecar <code>PUT</code> for the reason {@link #cropImage} gives, and the
	 * <code>PUT</code> keeps the stored poster whatever it carries ({@link #keepPosters}).
	 * </p>
	 *
	 * <p>
	 * Needs {@link Rights#EDIT} on the album (a share link and a contributor are refused
	 * <code>403</code>); refuses a photograph and a moment after the end of the video with
	 * <code>400</code> and an unknown video with <code>404</code>, writing nothing. A moment up to
	 * {@link #POSTER_END_SLACK} after the end the container says is taken: the player stopped on
	 * the last frame of the rendition, and the poster is the last frame. The video itself is only
	 * read for its length; only the sidecar is written. Through a collection or a saved search the
	 * poster is the video's own, written to the album it lies in, and the edit right is asked there
	 * too, see {@link #sourcePhoto}. The answer is the album as the caller is answered it.
	 * </p>
	 */
	private void posterVideo(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo folderPath = location.getPath();
		if (caller.isShareLink()) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, POSTER_REFUSED);
			return;
		}
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, folderPath)) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayEdit(caller, folderPath)) {
			if (!identified(caller)) {
				unauthorized(context, caller, true);
				return;
			}
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, POSTER_REFUSED);
			return;
		}
		File folder = folderPath.toFile();
		if (!folder.isDirectory()) {
			error404(context);
			return;
		}
		ImagePart request;
		try {
			// The part as every part is written, ["ImagePart", {...}].
			AlbumPart part = AlbumPart.readAlbumPart(json(readBody(context.request())));
			if (!(part instanceof ImagePart)) {
				throw new IllegalArgumentException("Not an image: " + part);
			}
			request = (ImagePart) part;
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable poster request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, POSTER_REQUEST_UNREADABLE);
			return;
		}
		Resource resource = _cache.lookup(folderPath);
		PathInfo answerPath = folderPath;
		String name = request.getName();
		if (PhotoCollections.isCollection(resource) || PhotoSearch.isSearch(resource)) {
			// The poster is the video's own, written to the album it lies in, see issues #221 and #227.
			PathInfo photo = sourcePhoto(context, caller, folderPath, resource, name);
			if (photo == null) {
				return;
			}
			folderPath = photo.parent();
			folder = folderPath.toFile();
			name = photo.getName();
			resource = _cache.lookup(folderPath);
		}
		if (!(resource instanceof AlbumInfo)) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, POSTER_NOT_AN_ALBUM);
			return;
		}
		AlbumInfo album = (AlbumInfo) resource;
		ImagePart image = Crops.findImage(album, name);
		if (image == null) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, unknownImage(request.getName()));
			return;
		}
		if (!Posters.isVideo(image) || !image.getBroken().isEmpty()) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, POSTER_NOT_A_VIDEO);
			return;
		}
		long millis = request.getPoster() == null ? 0 : request.getPoster().getMillis();
		if (millis < 0) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, POSTER_UNREADABLE);
			return;
		}
		if (millis > 0) {
			long duration;
			try {
				duration = de.haumacher.imageServer.cache.VideoProbe.probe(new File(folder, image.getName()))
					.getDurationMillis();
			} catch (IOException | RuntimeException | UnsatisfiedLinkError ex) {
				LOG.log(Level.WARNING, "Cannot read the length of '" + image.getName() + "'.", ex);
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, posterUnreadableVideo(image.getName()));
				return;
			}
			if (duration >= 0 && millis > duration + POSTER_END_SLACK) {
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, posterBeyondEnd(image.getName(), millis, duration));
				return;
			}
		}
		if (Posters.millis(image) != millis) {
			image.setPoster(Posters.of(millis));
			storeSidecar(folder, sidecarOf(album));
			_cache.invalidate(folderPath);
			LOG.info("Poster of '" + image.getName() + "' in '" + folder.getAbsolutePath() + "' at " + millis + " ms.");
		}
		answerAlbum(context, answerPath, caller);
	}

	private void tagFaces(Context context, boolean adjust) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo folderPath = location.getPath();
		if (caller.isShareLink()) {
			// Before the general write gate, so that a link is told what it is really refused:
			// its rights may well allow contributing, and naming a face is not contributing.
			LOG.warning("Refusing the tagging through a share link at '" + context.request().getPathInfo()
				+ "': " + TAGGING_REFUSED);
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, TAGGING_REFUSED);
			return;
		}
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, folderPath)) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayEdit(caller, folderPath)) {
			// Deliberately the same answer for a viewer and for a contributor: naming the people
			// in a photograph is an edit of the album, and contributing is not editing.
			if (!identified(caller)) {
				unauthorized(context, caller, true);
				return;
			}
			LOG.warning("Refusing the tagging at '" + context.request().getPathInfo() + "': " + TAGGING_REFUSED);
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, TAGGING_REFUSED);
			return;
		}

		File folder = folderPath.toFile();
		if (!folder.isDirectory()) {
			error404(context);
			return;
		}

		TagFaces request;
		try {
			request = TagFaces.readTagFaces(json(readBody(context.request())));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable tagging request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, TAG_UNREADABLE);
			return;
		}

		Resource resource = _cache.lookup(folderPath);
		if (PhotoCollections.isCollection(resource)) {
			LOG.warning("Refusing the tagging in the collection '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoCollections.FACES_REFUSED);
			return;
		}
		if (PhotoSearch.isSearch(resource)) {
			LOG.warning("Refusing the tagging in the saved search '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoSearch.FACES_REFUSED);
			return;
		}
		if (!(resource instanceof AlbumInfo)) {
			LOG.warning("Refusing the tagging at '" + context.request().getPathInfo() + "': " + TAG_NOT_AN_ALBUM);
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, TAG_NOT_AN_ALBUM);
			return;
		}
		AlbumInfo album = (AlbumInfo) resource;
		Map<String, ImagePart> images = new LinkedHashMap<>();
		for (ImagePart image : FaceIndex.imagesOf(album)) {
			images.put(image.getName(), image);
		}
		// The very numbering this caller was answered: the detections of the moment first, then
		// every tag no detection matched, a face called no face keeping its number unanswered, see
		// FaceTags#answer.
		Map<String, FaceTags.Answer> answers = _faces.answers(album, folder, _people);

		// Everything is checked before anything is written.
		List<TagOperation> operations = new ArrayList<>();
		// How the photographs of this album are turned for display, read at most once and only
		// where a request really hands a box over, see issue #147.
		Map<String, Orientation> orientations = null;
		for (FaceAssignment assignment : request.getFaces()) {
			ImagePart image = images.get(assignment.getImage());
			if (image == null) {
				LOG.warning("Refusing the tagging of '" + assignment.getImage() + "': unknown image.");
				errorInfo(context, HttpServletResponse.SC_NOT_FOUND, unknownImage(assignment.getImage()));
				return;
			}
			FaceTags.Answer answer = answers.get(image.getName());
			boolean hasBox = marksABox(assignment);
			if (adjust && !hasBox) {
				LOG.warning("Refusing the adjustment of '" + assignment.getImage() + "': no box.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, faceBoxInvalid(assignment.getImage()));
				return;
			}
			// An adjustment names a face and a box; a mark only a box, see issue #157.
			boolean marked = hasBox && !adjust;
			int index = assignment.getFace();
			FaceInfo named = marked || answer == null ? null : answer.byIndex(index);
			if (!marked && named == null) {
				LOG.warning("Refusing the tagging of '" + assignment.getImage() + "': unknown face "
					+ index + ".");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST,
					unknownFace(assignment.getImage(), index));
				return;
			}
			if (marked && !FaceIndex.isPhotograph(image)) {
				LOG.warning("Refusing the tagging of '" + assignment.getImage() + "': " + TAG_VIDEO_REFUSED);
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, TAG_VIDEO_REFUSED);
				return;
			}
			if (hasBox && !isOnThePicture(assignment)) {
				LOG.warning("Refusing the tagging of '" + assignment.getImage() + "': the marked box is "
					+ assignment.getX() + "," + assignment.getY() + " " + assignment.getW() + "x"
					+ assignment.getH() + ".");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, faceBoxInvalid(assignment.getImage()));
				return;
			}
			FaceState state = assignment.getState();
			String person = assignment.getPerson() == null ? "" : assignment.getPerson().trim();
			if (state == FaceState.UNDECIDED) {
				// "Forget this decision", see issue #138. Whom it was about is none of the
				// request's business: what is taken back is the decision itself.
				person = "";
			} else if (state == FaceState.NOT_A_FACE) {
				if (!person.isEmpty()) {
					errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, TAG_PERSON_REFUSED);
					return;
				}
			} else {
				if (person.isEmpty()) {
					errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, TAG_PERSON_REQUIRED);
					return;
				}
				PeopleStore.Entry entry = _people.resolve(person);
				if (entry == null) {
					LOG.warning("Refusing the tagging with the unknown person '" + person + "'.");
					errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PeopleStore.unknownPerson(person));
					return;
				}
				// Stored as the person that survives today; an id merged away later goes on
				// resolving through the register's aliases.
				person = entry.getId();
			}
			double[] box;
			FaceInfo target;
			double[] adjusted = null;
			if (adjust) {
				// The new box of the named face, in the one frame a decision is stored in. Never
				// snapped onto a detection: this box is what somebody said, see issue #157.
				if (orientations == null) {
					orientations = FaceIndex.displayOrientations(folder, images.keySet());
				}
				Orientation exif = orientations.getOrDefault(image.getName(), Orientation.IDENTITY);
				adjusted = Faces.toRaw(exif, assignment.getX(), assignment.getY(),
					assignment.getW(), assignment.getH());
				target = named;
				box = state == FaceState.NOT_A_FACE
					? new double[] { named.getX(), named.getY(), named.getW(), named.getH() }
					: adjusted;
			} else if (marked) {
				// What the client drew, in the frame it drew in: the picture upright, see issue
				// #142. The one frame a decision is stored in is the raw raster of the file.
				if (orientations == null) {
					orientations = FaceIndex.displayOrientations(folder, images.keySet());
				}
				Orientation exif = orientations.getOrDefault(image.getName(), Orientation.IDENTITY);
				box = Faces.toRaw(exif, assignment.getX(), assignment.getY(),
					assignment.getW(), assignment.getH());
				target = meets(answer, box);
				if (target != null) {
					// The detector found this face after all (or somebody has already decided about
					// it, or called it no face): then this is that face's decision, carrying that
					// face's own box, and marking it by hand is the very same thing as naming it.
					box = new double[] { target.getX(), target.getY(), target.getW(), target.getH() };
				}
			} else {
				target = named;
				box = new double[] { named.getX(), named.getY(), named.getW(), named.getH() };
			}
			operations.add(new TagOperation(image, FaceTag.create()
				.setX(box[0])
				.setY(box[1])
				.setW(box[2])
				.setH(box[3])
				.setPerson(person)
				.setState(state), target, target != null && answer.isDetection(target),
				adjusted == null || state == FaceState.NOT_A_FACE ? null
					: new double[] { named.getX(), named.getY(), named.getW(), named.getH() },
				target != null && answer.isDetection(target) ? answer.detectedBox(target.getIndex()) : null));
		}

		boolean changed = false;
		boolean described = false;
		for (TagOperation operation : operations) {
			ImagePart image = operation._image;
			FaceTag tag = operation._tag;
			List<FaceTag> stored = new ArrayList<>(image.getTags());
			int existing = FaceTags.indexOf(stored, tag.getX(), tag.getY(), tag.getW(), tag.getH());
			boolean modified;
			if (operation._before != null) {
				modified = adjust(stored, operation);
				if (modified) {
					image.setTags(stored);
					changed = true;
				}
				continue;
			}
			switch (tag.getState()) {
				case UNDECIDED:
					if (operation._target == null) {
						// Nothing here yet: a region is marked, see issue #155.
						FaceCache.Face found = _faces.mark(new File(folder, image.getName()),
							tag.getX(), tag.getY(), tag.getW(), tag.getH());
						if (found != null) {
							described = true;
							// The region is now what the detector found there; a tag that only said
							// "a region" or "no face" about the same spot says nothing any more.
							modified = removeUndecided(stored, found.getX(), found.getY(), found.getW(),
								found.getH());
							modified |= removeUndecided(stored, tag.getX(), tag.getY(), tag.getW(), tag.getH());
						} else {
							modified = putRegion(stored, existing, tag);
						}
					} else if (operation._detection && existing >= 0 && operation._detected != null
						&& !FaceTags.sameBox(operation._detected, tag.getX(), tag.getY(), tag.getW(), tag.getH())) {
						// A detection whose box somebody adjusted (issue #157): forgetting takes the
						// decision off it and keeps the box that was drawn, as for a face marked by hand.
						modified = stored.get(existing).getState() != FaceState.UNDECIDED;
						if (modified) {
							stored.set(existing, tag);
						}
					} else if (operation._detection) {
						// Taking a decision back, see issue #138. A detection nobody decided anything
						// about is already undecided, so there is nothing to do and nothing to refuse.
						modified = existing >= 0;
						if (modified) {
							stored.remove(existing);
						}
					} else {
						// A face marked by hand is a region like a detection: forgetting takes the
						// decision off it and keeps the region, see issue #155.
						modified = existing >= 0 && stored.get(existing).getState() != FaceState.UNDECIDED;
						if (modified) {
							stored.set(existing, tag);
						}
					}
					break;
				case NOT_A_FACE:
					if (operation._detection) {
						// The detection keeps the statement, and is answered no more (FaceTags).
						modified = replaceOrAdd(stored, existing, tag);
					} else {
						// A face that is only a tag is gone with its tag: there is nothing left to
						// keep a detection away from.
						modified = existing >= 0;
						if (modified) {
							stored.remove(existing);
						}
					}
					break;
				default:
					modified = replaceOrAdd(stored, existing, tag);
					break;
			}
			if (modified) {
				image.setTags(stored);
				changed = true;
			}
		}

		if (changed) {
			storeSidecar(folder, sidecarOf(album));
			// The next read must see what was just written.
			_cache.invalidate(folderPath);
			LOG.info("Tagged " + operations.size() + " face(s) in '" + folder.getAbsolutePath() + "'.");
		}
		if (changed || described) {
			// And so must the next listing of every *other* album: a decision joins the prototypes
			// of issue #127 here, where it is made, and not when some walk next comes past -- and
			// one taken back leaves them here too, because an album is always described afresh.
			_faces.recognition().observe(folder, album);
		}
		answerAlbum(context, folderPath, caller);
	}

	/** One checked assignment of a tagging request, carried out once every one is checked. */
	private static final class TagOperation {

		final ImagePart _image;

		final FaceTag _tag;

		/** The answered (or hidden) face it is about, <code>null</code> for a new region. */
		final FaceInfo _target;

		/** Whether {@link #_target} is a detection rather than a stored tag alone. */
		final boolean _detection;

		/**
		 * For an adjustment (issue #157): the raw box the face was answered with before, by which
		 * the tag it carried is found; <code>null</code> for every other operation.
		 */
		final double[] _before;

		/** The detector's own raw box where {@link #_target} is a detection, else <code>null</code>. */
		final double[] _detected;

		TagOperation(ImagePart image, FaceTag tag, FaceInfo target, boolean detection, double[] before,
				double[] detected) {
			_image = image;
			_tag = tag;
			_target = target;
			_detection = detection;
			_before = before;
			_detected = detected;
		}
	}

	/**
	 * Gives a face its new box, see issue #157; whether the stored tags changed.
	 *
	 * <p>
	 * The tag the face carried goes, and the new one takes its place — or, where the new box lies on
	 * another stored region, that one's place, so that no spot carries two statements. A detection
	 * left undecided at exactly the detector's own box needs no tag at all.
	 * </p>
	 */
	private static boolean adjust(List<FaceTag> stored, TagOperation operation) {
		FaceTag tag = operation._tag;
		double[] before = operation._before;
		List<FaceTag> result = new ArrayList<>(stored);
		int old = FaceTags.indexOf(result, before[0], before[1], before[2], before[3]);
		int position = old;
		if (old >= 0) {
			result.remove(old);
		}
		int other = FaceTags.indexOf(result, tag.getX(), tag.getY(), tag.getW(), tag.getH());
		if (other >= 0) {
			result.remove(other);
			position = other;
		}
		boolean bare = tag.getState() == FaceState.UNDECIDED && operation._detected != null
			&& FaceTags.sameBox(operation._detected, tag.getX(), tag.getY(), tag.getW(), tag.getH());
		if (!bare) {
			if (position < 0 || position > result.size()) {
				result.add(tag);
			} else {
				result.add(position, tag);
			}
		}
		if (sameTags(stored, result)) {
			return false;
		}
		stored.clear();
		stored.addAll(result);
		return true;
	}

	/** Whether the two lists say the same, tag for tag. */
	private static boolean sameTags(List<FaceTag> one, List<FaceTag> two) {
		if (one.size() != two.size()) {
			return false;
		}
		for (int n = 0; n < one.size(); n++) {
			if (!sameTag(one.get(n), two.get(n))) {
				return false;
			}
		}
		return true;
	}

	/** Puts the given tag where the one at <code>existing</code> was, or adds it; whether it changed. */
	private static boolean replaceOrAdd(List<FaceTag> stored, int existing, FaceTag tag) {
		if (existing >= 0) {
			if (sameTag(stored.get(existing), tag)) {
				return false;
			}
			stored.set(existing, tag);
		} else {
			stored.add(tag);
		}
		return true;
	}

	/**
	 * Stores a marked region the detector found nothing in, see issue #155: an undecided tag, which
	 * takes the place of a "no face" on the same spot and leaves a decision there alone.
	 */
	private static boolean putRegion(List<FaceTag> stored, int existing, FaceTag tag) {
		if (existing < 0) {
			stored.add(tag);
			return true;
		}
		FaceTag before = stored.get(existing);
		if (before.getState() == FaceState.NOT_A_FACE) {
			stored.set(existing, tag);
			return true;
		}
		return false;
	}

	/**
	 * Removes a tag on the given box that decides nothing about a person — an undecided region or
	 * a "no face" — because the detector has just found a face there; whether one was removed.
	 */
	private static boolean removeUndecided(List<FaceTag> stored, double x, double y, double w, double h) {
		int existing = FaceTags.indexOf(stored, x, y, w, h);
		if (existing < 0) {
			return false;
		}
		FaceState state = stored.get(existing).getState();
		if (state != FaceState.UNDECIDED && state != FaceState.NOT_A_FACE) {
			return false;
		}
		stored.remove(existing);
		return true;
	}

	/** Whether the two tags say the same thing about the same box. */
	private static boolean sameTag(FaceTag one, FaceTag two) {
		return one.getState() == two.getState() && one.getPerson().equals(two.getPerson())
			&& one.getX() == two.getX() && one.getY() == two.getY() && one.getW() == two.getW()
			&& one.getH() == two.getH();
	}

	/**
	 * Whether the given assignment marks a face by hand instead of naming one, see issue #147.
	 *
	 * <p>
	 * A box is given exactly when any of its four numbers is not zero. An assignment that carries
	 * none is what every client before issue #147 sent, and it names a face by its index as it
	 * always did.
	 * </p>
	 */
	private static boolean marksABox(FaceAssignment assignment) {
		return assignment.getX() != 0 || assignment.getY() != 0
			|| assignment.getW() != 0 || assignment.getH() != 0;
	}

	/** Whether the box of the given assignment is a place on the photograph, see issue #147. */
	private static boolean isOnThePicture(FaceAssignment assignment) {
		double x = assignment.getX();
		double y = assignment.getY();
		double w = assignment.getW();
		double h = assignment.getH();
		return w > 0 && h > 0 && x >= 0 && y >= 0 && x + w <= 1 && y + h <= 1;
	}

	/**
	 * Which of the answered faces the given raw box is, <code>null</code> where it is a new one.
	 *
	 * <p>
	 * The overlap rule of {@link FaceTags#IOU_MATCH}, asked of what this caller was answered:
	 * a hand-marked box over a face the detector found, or over one somebody already decided
	 * about, is that face and not a second one.
	 * </p>
	 */
	private static FaceInfo meets(FaceTags.Answer answer, double[] box) {
		if (answer == null) {
			return null;
		}
		List<FaceInfo> faces = new ArrayList<>(answer.getFaces());
		// A detection somebody called no face is not answered, but a box marked over it again is
		// that detection coming back, not a new face beside it (issue #155).
		faces.addAll(answer.getHidden());
		FaceInfo best = null;
		double bestOverlap = FaceTags.IOU_MATCH;
		for (FaceInfo face : faces) {
			double overlap = FaceTags.iou(box[0], box[1], box[2], box[3],
				face.getX(), face.getY(), face.getW(), face.getH());
			if (overlap > bestOverlap) {
				bestOverlap = overlap;
				best = face;
			}
		}
		return best;
	}

	/**
	 * The bytes to store for a sidecar the server itself changed, see
	 * {@link #stored(byte[], FolderResource)}.
	 *
	 * <p>
	 * The same rule as for a received one, only without a body to keep verbatim: what the server
	 * derives on every read is cleared, and what somebody decided stays. The {@link FaceTag}s are
	 * deliberately not among the derived fields — they <em>are</em> the stored form, see
	 * {@link AlbumDate#clearDerived(FolderResource)}.
	 * </p>
	 */
	static byte[] sidecarOf(FolderResource resource) throws IOException {
		AlbumDate.clearDerived(resource);
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer,
			StandardCharsets.UTF_8)))) {
			resource.writeTo(json);
		}
		return buffer.toByteArray();
	}

	// --- The search of issue #227. ---

	/** The value of <code>?type=</code> answering what a search below a folder offers. */
	public static final String SEARCH_OPTIONS_TYPE = "search-options";

	/** The value of <code>?action=</code> searching below a folder. */
	public static final String SEARCH_ACTION = "search";

	/**
	 * Whether the given caller may search, see issue #227: the members of the space, never a share
	 * link and never an anonymous visitor. A share link sees a saved search, and only that.
	 */
	private boolean maySearch(Caller caller) {
		if (caller.isShareLink() || caller.isShareGone() || caller.hasInvalidToken()) {
			return false;
		}
		return caller.isPaired() || _auth.getMode() == AuthMode.OFF;
	}

	/** Refuses a search to somebody who is no member, see {@link #maySearch(Caller)}. */
	private void refuseSearch(Context context, Caller caller) throws IOException {
		LOG.warning("Refusing the search at '" + context.request().getPathInfo() + "': " + PhotoSearch.MEMBERS_ONLY);
		if (identified(caller)) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, PhotoSearch.MEMBERS_ONLY);
		} else {
			context.response().setHeader("WWW-Authenticate", "Bearer");
			errorInfo(context, HttpServletResponse.SC_UNAUTHORIZED, PhotoSearch.MEMBERS_ONLY);
		}
	}

	/**
	 * The photographs below the given folder that match the given query, each as the given caller
	 * is answered it in its own album, see issue #227.
	 *
	 * <p>
	 * The query picks from what the albums hold; the photograph's own album then decides what this
	 * caller is shown of it &mdash; the inbox rule, privacy, the rating floor (a share link's too),
	 * the trash, faces &mdash; exactly as for a part of a collection, see
	 * {@link #sourceAlbum(PathInfo, Caller, int)}. Every answer is a copy named by its path below
	 * the folder, carrying where it lies ({@link ImagePart#getRef()}) for a member.
	 * </p>
	 *
	 * @param query
	 *        What to look for; must have passed {@link PhotoSearch#refusal(SearchQuery)}.
	 * @param stored
	 *        Collects the photograph each answer stands for, as the cache holds it, by the answer's
	 *        name; <code>null</code> where nobody asks.
	 */
	private List<ImagePart> searchMatches(PathInfo scope, SearchQuery query, Caller caller, int viewAs,
			Map<String, PhotoSearch.Match> stored) {
		return searchMatches(scope, query, caller, viewAs, stored, false);
	}

	/**
	 * {@link #searchMatches(PathInfo, SearchQuery, Caller, int, Map)}, the answers without faces
	 * and tags where <code>slim</code>: for an answer that is written out
	 * {@link PhotoSearch#partial(Resource) partial} anyway, so that the faces of the albums are not
	 * derived for nothing.
	 */
	private List<ImagePart> searchMatches(PathInfo scope, SearchQuery query, Caller caller, int viewAs,
			Map<String, PhotoSearch.Match> stored, boolean slim) {
		// What the photograph's own fields hide from this caller is not looked for at all, see
		// SearchIndex.Visibility; the album decides the rest, as below.
		SearchIndex.Visibility visibility = new SearchIndex.Visibility(Math.min(_auth.clearance(caller, scope), viewAs),
			caller.isShareLink() ? caller.getShare().getMinRating() : Ratings.MIN, _auth.photoLabel(caller));
		return searchMatches(scope, query, (folder, names) -> sourceAlbum(folder, caller, viewAs, names, !slim),
			Faces.maySee(_auth, caller, viewAs), visibility, stored, slim);
	}

	/**
	 * {@link #searchMatches(PathInfo, SearchQuery, Caller, int, Map)} with the albums as somebody is
	 * answered them.
	 *
	 * <p>
	 * The {@link SearchIndex} picks the photographs (issue #264); each is answered as its own album
	 * answers it to the caller, so that a photograph the index holds stale is shown late, never one
	 * that must not be.
	 * </p>
	 *
	 * @param sources
	 *        The album of a folder as the caller is answered it there, <code>null</code> where they
	 *        are shown nothing of it. Asked once per folder.
	 * @param member
	 *        Whether the answers say where they lie.
	 * @param slim
	 *        Whether the answers leave out the faces and the tags, see
	 *        {@link #searchMatches(PathInfo, SearchQuery, Caller, int, Map, boolean)}.
	 */
	private List<ImagePart> searchMatches(PathInfo scope, SearchQuery query,
			java.util.function.BiFunction<PathInfo, Set<String>, AlbumInfo> sources, boolean member,
			SearchIndex.Visibility visibility, Map<String, PhotoSearch.Match> stored, boolean slim) {
		_searchRuns.incrementAndGet();
		List<PhotoSearch.Match> found = PhotoSearch.find(scope, query, _searchIndex, _people, visibility);
		Map<PathInfo, Map<String, ImagePart>> albums = shownAlbums(found, sources);
		List<ImagePart> result = new ArrayList<>(found.size());
		for (PhotoSearch.Match match : found) {
			ImagePart answer = answerOf(match, albums, member, slim);
			if (answer == null) {
				// Hidden from this caller in its own album: not there for them here either.
				continue;
			}
			result.add(answer);
			if (stored != null) {
				stored.put(match.getRelative(), match);
			}
		}
		return result;
	}

	/** A photograph found near a vector as the caller is answered it, and how near, see {@link #nearestAnswers}. */
	record ScoredPart(ImagePart part, float score) {
		// A pair.
	}

	/**
	 * The <code>k</code> photographs below the given folder nearest to the given vector of the
	 * content search (issue #257), the nearest first, each as its own album answers it to the
	 * caller: what the caller may not see is not looked for ({@link SearchIndex#nearest}, the
	 * visibility inside the vector query), and what the index holds stale is checked by the album,
	 * exactly as for the answers of {@link #searchMatches}.
	 *
	 * @param vector
	 *        A vector of the model the vector index holds.
	 */
	List<ScoredPart> nearestAnswers(PathInfo scope, float[] vector, float imageWeight, float textWeight, int k,
			Caller caller, int viewAs) {
		List<ScoredPart> result = new ArrayList<>();
		if (k <= 0 || !_auth.mayView(caller, scope)) {
			// Not one album of it would be answered.
			return result;
		}
		// Every rule of sourceAlbum() that drops all photographs of a folder is part of the filter
		// inside the vector query; what drops single photographs the index cannot tell (a
		// contributor's own uploads in the inbox, an album changed behind the index) is made up for
		// by asking for more, see NEAREST_MORE.
		SearchIndex.Visibility visibility = new SearchIndex.Visibility(Math.min(_auth.clearance(caller, scope), viewAs),
			caller.isShareLink() ? caller.getShare().getMinRating() : Ratings.MIN, _auth.photoLabel(caller));
		java.util.function.Predicate<String> folders = path -> {
			PathInfo folder = path.isEmpty() ? new PathInfo(_basePath) : new PathInfo(_basePath, Paths.get(path));
			return _auth.mayView(caller, folder) && (!Inboxes.isInbox(folder.toFile())
				|| Inboxes.visibility(_auth, caller, folder, viewAs) != Inboxes.Visibility.NONE);
		};
		boolean member = Faces.maySee(_auth, caller, viewAs);
		int limit = Math.max(k * NEAREST_MORE, NEAREST_LEAST);
		for (int asked = k;; asked = Math.min(limit, asked * 4)) {
			List<SearchIndex.Scored> scored =
				_searchIndex.nearest(scope, vector, imageWeight, textWeight, asked, visibility, folders);
			List<PhotoSearch.Match> found = new ArrayList<>(scored.size());
			for (SearchIndex.Scored each : scored) {
				found.add(each.match());
			}
			Map<PathInfo, Map<String, ImagePart>> albums =
				shownAlbums(found, (folder, names) -> sourceAlbum(folder, caller, viewAs, names, false));
			result.clear();
			for (SearchIndex.Scored each : scored) {
				ImagePart answer = answerOf(each.match(), albums, member, true);
				if (answer != null) {
					result.add(new ScoredPart(answer, each.score()));
					if (result.size() == k) {
						return result;
					}
				}
			}
			if (scored.size() < asked || asked >= limit) {
				// Every candidate there is was asked, or as many as a query may cost.
				return result;
			}
		}
	}

	/**
	 * How many times <code>k</code> candidates {@link #nearestAnswers} asks the index for at most,
	 * where their albums hide some of them from the caller (at least {@link #NEAREST_LEAST}): each
	 * round asks four times as many as the one before.
	 */
	static final int NEAREST_MORE = 16;

	/** The fewest candidates {@link #nearestAnswers} may ask for at most, see {@link #NEAREST_MORE}. */
	static final int NEAREST_LEAST = 1000;

	/**
	 * The albums of the given matches as the given sources answer them, each photograph by its name:
	 * each album is asked once, for its matches alone, see {@link #searchMatches}.
	 */
	private Map<PathInfo, Map<String, ImagePart>> shownAlbums(List<PhotoSearch.Match> found,
			java.util.function.BiFunction<PathInfo, Set<String>, AlbumInfo> sources) {
		// Each album is asked once, for its matches alone: what its filters and its faces cost is
		// paid for what is answered, not for the whole album.
		Map<PathInfo, Set<String>> matched = new LinkedHashMap<>();
		for (PhotoSearch.Match match : found) {
			matched.computeIfAbsent(match.getFolder(), x -> new HashSet<>()).add(match.getName());
		}
		// The albums the cache holds first: a search over more albums than the cache holds would
		// otherwise push out, one by one, each album it is about to ask next, and read every one.
		List<Map.Entry<PathInfo, Set<String>>> order = new ArrayList<>(matched.size());
		List<Map.Entry<PathInfo, Set<String>>> unheld = new ArrayList<>();
		for (Map.Entry<PathInfo, Set<String>> entry : matched.entrySet()) {
			(_cache.holds(entry.getKey()) ? order : unheld).add(entry);
		}
		order.addAll(unheld);
		Map<PathInfo, Map<String, ImagePart>> albums = new HashMap<>();
		for (Map.Entry<PathInfo, Set<String>> entry : order) {
			AlbumInfo album = sources.apply(entry.getKey(), entry.getValue());
			Map<String, ImagePart> byName = new HashMap<>();
			if (album != null) {
				for (ImagePart image : PhotoSearch.images(album)) {
					byName.put(image.getName(), image);
				}
			}
			albums.put(entry.getKey(), byName);
		}
		return albums;
	}

	/**
	 * The answer of the given match, see {@link #searchMatches}: a copy of the photograph as its
	 * album shows it, named by its path below the folder searched; <code>null</code> where the album
	 * hides it from the caller.
	 */
	private static ImagePart answerOf(PhotoSearch.Match match, Map<PathInfo, Map<String, ImagePart>> albums,
			boolean member, boolean slim) {
		PathInfo folder = match.getFolder();
		Map<String, ImagePart> album = albums.get(folder);
		ImagePart shown = album == null ? null : album.get(match.getName());
		if (shown == null) {
			return null;
		}
		ImagePart answer = PhotoSearch.answerCopy(shown);
		if (slim) {
			answer.setFaces(Collections.emptyList()).setTags(Collections.emptyList());
		}
		answer.setName(match.getRelative());
		answer.setRef(member
			? PhotoRef.create().setHash("").setPath(folder.child(match.getName()).relativePath())
			: null);
		return answer;
	}

	/**
	 * The given saved search as the given caller is answered it, before the filters of a link on
	 * it: its own title, date, star and query, and every photograph below the folder it lies in
	 * that matches the query, see {@link #searchMatches}.
	 *
	 * <p>
	 * Its picture is the one stored with it while this caller is shown that photograph, its first
	 * match otherwise, see {@link #searchCover(ThumbnailInfo, List)}. The cached album is never
	 * changed, and nothing is written.
	 * </p>
	 */
	AlbumInfo searchAnswer(AlbumInfo search, PathInfo path, Caller caller, int viewAs) {
		List<ImagePart> parts = searchMatches(searchScope(path), search.getQuery(), caller, viewAs, null);
		AlbumInfo result = AlbumInfo.create()
			.setKind(de.haumacher.imageServer.shared.model.AlbumKind.SEARCH)
			.setQuery(search.getQuery())
			.setTitle(search.getTitle())
			.setSubTitle(search.getSubTitle())
			.setStarred(search.isStarred())
			.setPlaceCaption(search.getPlaceCaption())
			.setDate(search.getDate())
			.setEffectiveDate(search.getEffectiveDate())
			.setParts(new ArrayList<>(parts));
		ThumbnailInfo cover = searchCover(search.getIndexPicture(), parts);
		if (cover != null) {
			result.setIndexPicture(cover);
		}
		return result;
	}

	/**
	 * The picture of a saved search among the given matches: the stored one where it is among
	 * them, framed as it was stored, the first match otherwise, <code>null</code> for no match.
	 *
	 * <p>
	 * The first match is the one the search shows first, in its answer's order
	 * ({@link PhotoSearch#ORDER}): what a saved search stores when it is created and what an
	 * editor's opening stores where it has none yet, see {@link #rememberSearchCover} and issue
	 * #287.
	 * </p>
	 */
	private static ThumbnailInfo searchCover(ThumbnailInfo chosen, List<ImagePart> parts) {
		if (chosen != null) {
			for (ImagePart part : parts) {
				if (part.getName().equals(chosen.getImage())) {
					return Crops.copy(chosen);
				}
			}
		}
		return parts.isEmpty() ? null : PrivacyFilter.thumbnail(parts.get(0));
	}

	/** How often a query was run over this space since the servlet started, for the tests of issue #287. */
	long searchRuns() {
		return _searchRuns.get();
	}

	/**
	 * Stores the picture of the given saved search where it has none or the stored one leads
	 * nowhere, on its opening by an editor, see issue #287.
	 *
	 * <p>
	 * A listing never runs a saved search: its tile shows the picture stored in the search's
	 * sidecar, see {@link #searchTile}. A search stores its first match when it is created, and
	 * an editor chooses another with the album picture action. A sidecar written before the
	 * search stored its picture (or one created while it found nothing) has none, and a stored
	 * one may lead nowhere any more &mdash; its photograph deleted, moved, or no longer found by
	 * the query, for whoever asks. Opening runs the query anyway; an editor's opening then stores
	 * the first match they are shown, so that the next listing shows it, and takes a picture that
	 * leads nowhere away where nothing is found any more.
	 * </p>
	 *
	 * <p>
	 * Only an editor's opening writes, neither a share link nor the author's preview of the
	 * public view (<code>viewAs</code>): the picture is the author's statement, and what a viewer
	 * is shown is narrower. A stored picture that this caller only does not see is never
	 * replaced: whether it leads anywhere is asked of the photograph itself
	 * ({@link #photoNamed}), not of what this caller is shown. Nothing is written where nothing
	 * changes, and a write that fails is logged and never fails the answer.
	 * </p>
	 *
	 * @param search
	 *        The saved search as the cache holds it; never changed.
	 * @param found
	 *        What opening answers this caller, before the filters of a link.
	 */
	private void rememberSearchCover(AlbumInfo search, PathInfo path, Caller caller, int viewAs, AlbumInfo found) {
		if (caller.isShareLink() || viewAs < Privacy.PRIVATE || !_auth.mayEdit(caller, path)) {
			return;
		}
		ThumbnailInfo stored = search.getIndexPicture();
		if (stored != null && photoNamed(path, search, stored.getImage()) != null) {
			// Chosen, and it still leads to a photograph the search finds.
			return;
		}
		List<ImagePart> images = PhotoSearch.images(found);
		ThumbnailInfo cover = images.isEmpty() ? null : PrivacyFilter.thumbnail(images.get(0));
		if (cover == null && stored == null) {
			return;
		}
		File folder = path.toFile();
		try {
			FolderResource sidecar = ResourceCache.readSidecar(folder);
			if (!PhotoSearch.isSearch(sidecar)) {
				return;
			}
			((AlbumInfo) sidecar).setIndexPicture(cover);
			storeSidecar(folder, sidecarOf(sidecar));
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot store the picture of the saved search '" + folder + "'.", ex);
			return;
		}
		_cache.invalidate(path);
		LOG.info((cover == null ? "Removed the picture leading nowhere" : "Stored '" + cover.getImage() + "' as the picture")
			+ " of the saved search '" + folder.getAbsolutePath() + "'.");
	}

	/**
	 * Answers <code>POST &lt;folder&gt;/?action=search</code> with a {@link SearchQuery}: the search
	 * view of issue #227, never stored.
	 *
	 * <p>
	 * The answer is an album of {@link de.haumacher.imageServer.shared.model.AlbumKind#SEARCH} that
	 * holds every photograph below the folder that matches, each as the caller is answered it in its
	 * own album and named by its path below the folder &mdash; so that
	 * <code>&lt;folder&gt;/&lt;name&gt;</code> is the photograph's own address. It carries the
	 * rights to look (and to download, where the caller may): nothing is edited through a search
	 * that is not saved. Members only, see {@link #maySearch(Caller)}.
	 * </p>
	 */
	private void search(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		if (!maySearch(caller)) {
			refuseSearch(context, caller);
			return;
		}
		PathInfo scope = location.getPath();
		if (!scope.toFile().isDirectory()) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, PhotoSearch.NOT_A_FOLDER);
			return;
		}
		if (!_auth.mayView(caller, scope)) {
			refuse(context, caller, scope, Rights.VIEW, false);
			return;
		}
		SearchQuery query;
		try {
			query = SearchQuery.readSearchQuery(json(readBody(context.request())));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable search: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PhotoSearch.UNREADABLE);
			return;
		}
		String refusal = PhotoSearch.refusal(query);
		if (refusal != null) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, refusal);
			return;
		}
		if (PhotoSearch.isSearch(_cache.lookup(scope))) {
			// The search of a saved search's own page looks where the saved search looks.
			scope = searchScope(scope);
		}
		int viewAs = Privacy.PRIVATE;
		// Written out partial: no faces derived, see ImagePart#isPartial().
		List<ImagePart> parts = searchMatches(scope, query, caller, viewAs, null, true);
		AlbumInfo answer = AlbumInfo.create()
			.setKind(de.haumacher.imageServer.shared.model.AlbumKind.SEARCH)
			.setQuery(PhotoSearch.normalized(query))
			.setParts(new ArrayList<>(parts));
		Set<String> rights = new HashSet<>();
		rights.add(Rights.VIEW);
		if (_auth.mayDownload(caller, scope)) {
			rights.add(Rights.DOWNLOAD);
		}
		Resource shown = withLabels(answer, caller, viewAs);
		// Ten thousand photographs found are ten thousand parts: each answered slim, the faces,
		// the tags and the full places asked of the photograph when it is opened, see
		// ImagePart#isPartial().
		serveJson(context.response(), PhotoSearch.partial(localized(withRights(shown, rights), context)));
	}

	/**
	 * Answers <code>&lt;folder&gt;/?type=search-options</code>: what the search view offers to
	 * choose from below the folder, see {@link SearchOptions} and issue #227.
	 *
	 * <p>
	 * Read off the photographs below the folder that the caller is shown, so that nothing is offered
	 * that would only find what they may not see. At a saved search, the folder it looks below.
	 * Members only, see {@link #maySearch(Caller)}.
	 * </p>
	 *
	 * <p>
	 * Answered from the {@link SearchIndex} alone, see {@link #optionRows(PathInfo, Caller)}: the
	 * options of the whole library must not read every album of it into the cache, which made the
	 * first answer at the top level after a start take as long as reading the library, and the
	 * choosers of the search view stood empty until then.
	 * </p>
	 */
	private void serveSearchOptions(Context context, Caller caller, PathInfo folder) throws IOException {
		if (!maySearch(caller)) {
			refuseSearch(context, caller);
			return;
		}
		if (!folder.toFile().isDirectory()) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, PhotoSearch.NOT_A_FOLDER);
			return;
		}
		if (!_auth.mayView(caller, folder)) {
			refuse(context, caller, folder, Rights.VIEW, false);
			return;
		}
		PathInfo scope = PhotoSearch.isSearch(_cache.lookup(folder)) ? searchScope(folder) : folder;

		// The persons, cameras, labels and places of exactly the photographs the caller is shown,
		// read off their rows in the index, see issue #264.
		SearchIndex.Facets facets = _searchIndex.facets(optionRows(scope, caller));
		SearchOptions options = SearchOptions.create();
		java.util.TreeMap<String, de.haumacher.imageServer.shared.model.Person> persons = new java.util.TreeMap<>();
		for (String stamped : facets._persons) {
			// The survivor of every merge, see FaceTags#person.
			PeopleStore.Entry person = _people.resolve(de.haumacher.imageServer.faces.FaceTags.person(stamped, _people));
			if (person != null) {
				persons.putIfAbsent(person.getName().toLowerCase(Locale.ROOT) + "\u0000" + person.getId(),
					de.haumacher.imageServer.shared.model.Person.create().setId(person.getId()).setName(person.getName()));
			}
		}
		options.setPersons(new ArrayList<>(persons.values()));
		for (String camera : facets._cameras) {
			options.addCamera(de.haumacher.imageServer.shared.model.CameraName.create().setName(camera));
		}
		options.setLabels(PhotoSearch.labelsOf(facets._labels));

		// The place names in the caller's language, as the photographs are answered them: the tags
		// of one photograph holding them all, localized as a photograph is.
		ImagePart holder = ImagePart.create().setPlaces(de.haumacher.imageServer.shared.model.PlaceInfo.create()
			.setTags(new ArrayList<>(facets._places.values())));
		Resource localized = localized(holder, context);
		List<de.haumacher.imageServer.shared.model.PlaceTag> sorted = new ArrayList<>(
			localized instanceof ImagePart && ((ImagePart) localized).getPlaces() != null
				? ((ImagePart) localized).getPlaces().getTags() : holder.getPlaces().getTags());
		sorted.sort(java.util.Comparator
			.comparing((de.haumacher.imageServer.shared.model.PlaceTag tag) -> tag.getKind().ordinal())
			.thenComparing(tag -> tag.getName().toLowerCase(Locale.ROOT))
			.thenComparingInt(de.haumacher.imageServer.shared.model.PlaceTag::getGeonameId));
		options.setPlaces(sorted);
		serveJsonObject(context.response(), options);
	}

	/**
	 * The rows of the {@link SearchIndex} of every photograph below the given folder that the given
	 * caller is shown in its album, without reading the albums where the index can tell, see
	 * {@link #serveSearchOptions(Context, Caller, PathInfo)}.
	 *
	 * <p>
	 * The same photographs {@link #searchMatches(PathInfo, SearchQuery, Caller, int, Map, boolean)}
	 * answers without a query, decided as follows:
	 * </p>
	 * <ul>
	 * <li>The privacy, the rating floor (the trash) and a link's label are the index's own
	 * {@link SearchIndex.Visibility}, which is never wider than what the album's
	 * {@link PrivacyFilter} shows: the clearance and the label are the caller's wherever they look,
	 * and the index never finds the trash.</li>
	 * <li>A folder whose sidecar or analysis record changed since the index read it is read into the
	 * index first ({@link SearchIndex#refresh(PathInfo, java.util.Collection)}), as the cache reads
	 * such an album again; a folder that is gone is forgotten.</li>
	 * <li>A folder the caller may not look into is left out, as its album would be.</li>
	 * <li>The inbox (issue #226) is left out for whoever is shown nothing of it, and taken whole by
	 * whoever is shown all of it. Only a caller shown their own contributions alone reads its album:
	 * who contributed a photograph is no field of the index. This is the one album read.</li>
	 * </ul>
	 */
	private List<Long> optionRows(PathInfo scope, Caller caller) {
		int viewAs = Privacy.PRIVATE;
		SearchIndex.Visibility visibility = new SearchIndex.Visibility(Math.min(_auth.clearance(caller, scope), viewAs),
			caller.isShareLink() ? caller.getShare().getMinRating() : Ratings.MIN, _auth.photoLabel(caller));
		_searchRuns.incrementAndGet();
		List<PhotoSearch.Match> found = PhotoSearch.find(scope, null, _searchIndex, _people, visibility);
		if (_searchIndex.refresh(scope, foldersOf(found).keySet())) {
			found = PhotoSearch.find(scope, null, _searchIndex, _people, visibility);
		}
		List<Long> result = new ArrayList<>(found.size());
		for (Map.Entry<PathInfo, List<PhotoSearch.Match>> entry : foldersOf(found).entrySet()) {
			PathInfo folder = entry.getKey();
			if (!folder.toFile().isDirectory() || !_auth.mayView(caller, folder)) {
				continue;
			}
			List<PhotoSearch.Match> matches = entry.getValue();
			if (Inboxes.isInbox(folder.toFile())) {
				Inboxes.Visibility shown = Inboxes.visibility(_auth, caller, folder, viewAs);
				if (shown == Inboxes.Visibility.NONE) {
					continue;
				}
				if (shown != Inboxes.Visibility.FULL) {
					matches = shownIn(folder, matches, caller, viewAs);
				}
			}
			for (PhotoSearch.Match match : matches) {
				result.add(Long.valueOf(match.getId()));
			}
		}
		return result;
	}

	/** The given matches by their folder, in the order they were found. */
	private static Map<PathInfo, List<PhotoSearch.Match>> foldersOf(List<PhotoSearch.Match> found) {
		Map<PathInfo, List<PhotoSearch.Match>> result = new LinkedHashMap<>();
		for (PhotoSearch.Match match : found) {
			result.computeIfAbsent(match.getFolder(), x -> new ArrayList<>()).add(match);
		}
		return result;
	}

	/**
	 * Those of the given matches in the given folder that its album shows the given caller, see
	 * {@link #sourceAlbum(PathInfo, Caller, int, Set, boolean)}.
	 */
	private List<PhotoSearch.Match> shownIn(PathInfo folder, List<PhotoSearch.Match> matches, Caller caller,
			int viewAs) {
		Set<String> names = new HashSet<>();
		for (PhotoSearch.Match match : matches) {
			names.add(match.getName());
		}
		AlbumInfo album = sourceAlbum(folder, caller, viewAs, names, false);
		Set<String> shown = new HashSet<>();
		if (album != null) {
			for (ImagePart image : PhotoSearch.images(album)) {
				shown.add(image.getName());
			}
		}
		List<PhotoSearch.Match> result = new ArrayList<>();
		for (PhotoSearch.Match match : matches) {
			if (shown.contains(match.getName())) {
				result.add(match);
			}
		}
		return result;
	}

	// --- The search field of the folder picker, issue #261. ---

	/** The value of <code>?type=</code> answering the folders below a folder that match a text. */
	public static final String FOLDERS_TYPE = "folders";

	/** The parameter carrying what was typed, see {@link #FOLDERS_TYPE}. */
	public static final String FOLDERS_TEXT_PARAMETER = "q";

	/** The parameter carrying how many matches are answered at most, see {@link FolderFinder#MAX_LIMIT}. */
	public static final String FOLDERS_LIMIT_PARAMETER = "limit";

	/** The message a request for the folders below something that is no folder is refused with. */
	public static final String FOLDERS_NOT_A_FOLDER = "Folders are looked for below a folder.";

	/** The message an unreadable limit is refused with. */
	public static final String FOLDERS_BAD_LIMIT = "The number of folders asked for cannot be read.";

	/**
	 * The parameter carrying a day, in milliseconds since the epoch: then the albums nearest to it
	 * are answered instead of the ones matching a text, see issue #279 and
	 * {@link FolderFinder#nearest(PathInfo, long, String, int, java.util.function.Predicate, java.util.function.Predicate)}.
	 */
	public static final String FOLDERS_NEAR_PARAMETER = "near";

	/**
	 * The parameter carrying the album, below the folder asked, a move takes its photographs out of,
	 * see {@link #FOLDERS_NEAR_PARAMETER}.
	 */
	public static final String FOLDERS_SOURCE_PARAMETER = "from";

	/** The message an unreadable day is refused with. */
	public static final String FOLDERS_BAD_NEAR = "The day to look near cannot be read.";

	/**
	 * Answers <code>&lt;folder&gt;/?type=folders&amp;q=&lt;text&gt;</code>: the albums and folders
	 * below the folder whose title, name or date matches the text, best first, see {@link FolderFinder}
	 * and issue #261; with <code>&amp;near=&lt;millis&gt;</code> the albums nearest to that day
	 * instead, see {@link #FOLDERS_NEAR_PARAMETER}.
	 *
	 * <p>
	 * Members only, as the search of issue #227 ({@link #maySearch(Caller)}): the picker that asks
	 * is the one of a move, of a collection, of an upload target and of a saved search, all of them
	 * members' business. Only folders the caller may see are answered, the inbox never (it is no
	 * tile of any listing). No picture is answered, so no photograph's privacy is touched.
	 * </p>
	 */
	private void serveFolderMatches(Context context, Caller caller, PathInfo folder) throws IOException {
		if (!maySearch(caller)) {
			refuseSearch(context, caller);
			return;
		}
		if (!folder.toFile().isDirectory() || Inboxes.isInbox(folder.toFile())) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, FOLDERS_NOT_A_FOLDER);
			return;
		}
		if (!_auth.mayView(caller, folder)) {
			refuse(context, caller, folder, Rights.VIEW, false);
			return;
		}
		int limit = FolderFinder.DEFAULT_LIMIT;
		String limitText = context.getParameter(FOLDERS_LIMIT_PARAMETER);
		if (limitText != null && !limitText.isEmpty()) {
			try {
				limit = Integer.parseInt(limitText);
			} catch (NumberFormatException ex) {
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, FOLDERS_BAD_LIMIT);
				return;
			}
		}
		String nearText = context.getParameter(FOLDERS_NEAR_PARAMETER);
		if (nearText != null && !nearText.isEmpty()) {
			// The album a move suggests, issue #279: one the caller may see and may add to.
			long near;
			try {
				near = Long.parseLong(nearText);
			} catch (NumberFormatException ex) {
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, FOLDERS_BAD_NEAR);
				return;
			}
			serveJsonObject(context.response(),
				_folderFinder.nearest(folder, near, context.getParameter(FOLDERS_SOURCE_PARAMETER), limit,
					path -> _auth.mayView(caller, path), path -> _auth.mayContribute(caller, path)));
			return;
		}
		serveJsonObject(context.response(), _folderFinder.find(folder, context.getParameter(FOLDERS_TEXT_PARAMETER),
			limit, path -> _auth.mayView(caller, path)));
	}

	/**
	 * The photograph a path below a saved search stands for, see issue #227: the photograph at that
	 * path below the folder the saved search looks in, while it matches the query.
	 *
	 * @return <code>null</code> where the path lies below no saved search; the path of the
	 *         photograph otherwise. Where the path lies below a saved search and stands for no
	 *         photograph of it, the request is answered and {@link #ANSWERED} returned.
	 */
	private PathInfo searchedPhoto(Context context, PathInfo requested) throws IOException {
		PathInfo path = inSpace(requested);
		if (path.isRoot()) {
			return null;
		}
		PathInfo search = path.parent();
		while (!search.isRoot() && !search.toFile().isDirectory()) {
			search = search.parent();
		}
		if (search.isRoot() || !search.toFile().isDirectory()) {
			return null;
		}
		Resource resource = _cache.lookup(search);
		if (!PhotoSearch.isSearch(resource)) {
			return null;
		}
		SearchQuery query = ((AlbumInfo) resource).getQuery();
		String refusal = PhotoSearch.refusal(query);
		if (refusal != null) {
			errorInfo(context, HttpServletResponse.SC_NOT_IMPLEMENTED, refusal);
			return ANSWERED;
		}
		String relative = path.toFile().toPath().toAbsolutePath().normalize().toString();
		String base = search.toFile().toPath().toAbsolutePath().normalize().toString();
		relative = relative.substring(base.length() + 1).replace(File.separatorChar, '/');
		PathInfo folder = PhotoSearch.matching(searchScope(search), relative, query, this::lookupOrSkip, _people,
			_searchIndex);
		if (folder == null) {
			LOG.warning("No photograph of the saved search at '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, PhotoSearch.NO_MATCH);
			return ANSWERED;
		}
		return folder.child(path.getName());
	}

	/**
	 * The folder a saved search at the given path looks below, see {@link PhotoSearch#scopeOf}: in
	 * the coordinates of the space, also where the path was reached through a share link, whose
	 * paths start at the link's target.
	 */
	private PathInfo searchScope(PathInfo search) {
		return PhotoSearch.scopeOf(inSpace(search));
	}

	/** The given path in the coordinates of the space this servlet serves, see {@link #searchScope}. */
	private PathInfo inSpace(PathInfo path) {
		java.nio.file.Path root = _basePath.toAbsolutePath().normalize();
		java.nio.file.Path file = path.toFile().toPath().toAbsolutePath().normalize();
		if (!file.startsWith(root)) {
			return path;
		}
		return file.equals(root) ? new PathInfo(_basePath) : new PathInfo(_basePath, root.relativize(file));
	}

	/** What {@link #searchedPhoto(Context, PathInfo)} answers for a request it answered itself. */
	private static final PathInfo ANSWERED = new PathInfo(java.nio.file.Paths.get("/"));

	/**
	 * The photographs a name of a request stands for in the given folder: an entry of it, or (#227)
	 * a path below it, which in a saved search is a path below the folder it looks in, where the
	 * photograph matches its query.
	 *
	 * @return <code>null</code> where the name stands for no photograph the folder can show.
	 */
	private PathInfo photoNamed(PathInfo folder, Resource container, String name) {
		List<String> segments = PhotoSearch.segments(name);
		if (segments == null) {
			return null;
		}
		if (PhotoSearch.isSearch(container)) {
			SearchQuery query = ((AlbumInfo) container).getQuery();
			if (PhotoSearch.refusal(query) != null) {
				return null;
			}
			PathInfo source = PhotoSearch.matching(searchScope(folder), name, query, this::lookupOrSkip, _people,
				_searchIndex);
			return source == null ? null : source.child(segments.get(segments.size() - 1));
		}
		PathInfo result = folder;
		for (String segment : segments) {
			result = result.child(segment);
		}
		return result;
	}

	/**
	 * Answers the album at the given path as the given caller is answered it by a plain listing.
	 *
	 * <p>
	 * What <code>?action=tag-faces</code> hands back, so that a client sees the effect of its write
	 * without asking again — filtered, faced and with the caller's rights on it, exactly like the
	 * <code>GET</code>. The inbox filter of issue #131 is not asked: only a caller with
	 * {@link Rights#EDIT} gets here, and such a caller sees all of an inbox anyway.
	 * </p>
	 */
	private void answerAlbum(Context context, PathInfo folderPath, Caller caller) throws IOException {
		Resource stored = _cache.lookup(folderPath);
		int viewAs = Privacy.PRIVATE;
		int clearance = Math.min(_auth.clearance(caller, folderPath), viewAs);
		Resource answer = folderAnswer(stored, folderPath, caller, clearance, viewAs);
		serveJson(context.response(),
			PhotoSearch.partial(localized(withRights(answer, _auth.rights(caller, folderPath)), context)));
	}

	/**
	 * The given folder as the given caller is answered it: filtered, faced and labelled, the
	 * collections of issue #221 resolved. The one place a folder answer is built.
	 *
	 * @param resource
	 *        What the cache holds, the inbox rule already applied; never modified.
	 */
	private Resource folderAnswer(Resource resource, PathInfo pathInfo, Caller caller, int clearance, int viewAs) {
		int minRating = _auth.minRating(caller, pathInfo, viewAs);
		String label = _auth.photoLabel(caller);
		if (PhotoCollections.isCollection(resource)) {
			// Every photograph as the caller would be answered it in its own album -- faces
			// included --, then the collection's own filters on top: a link's privacy, rating and
			// label (the label being the collection's own, see issue #213).
			AlbumInfo resolved = resolveCollection((AlbumInfo) resource, pathInfo, caller, viewAs);
			Resource answer = _privacy.filter(resolved, pathInfo, clearance, minRating, label);
			return withLabels(answer, caller, viewAs);
		}
		if (PhotoSearch.isSearch(resource)) {
			// Every match as the caller would be answered it in its own album, then a link's own
			// filters on top -- its label asked of the photograph's own labels, see issue #227.
			AlbumInfo found = searchAnswer((AlbumInfo) resource, pathInfo, caller, viewAs);
			// The picture its tile shows from now on, where it has none yet, see issue #287.
			rememberSearchCover((AlbumInfo) resource, pathInfo, caller, viewAs, found);
			Resource answer = _privacy.filter(found, pathInfo, clearance, minRating, label);
			return withLabels(answer, caller, viewAs);
		}
		Resource answer = _privacy.filter(resource, pathInfo, clearance, minRating, label);
		answer = withLabels(withFaces(answer, pathInfo, caller, viewAs), caller, viewAs);
		if (answer instanceof ListingInfo) {
			answer = withCollectionCovers((ListingInfo) answer, pathInfo, caller, viewAs, label);
		}
		return answer;
	}

	/**
	 * The given collection resolved for the given caller, see
	 * {@link PhotoCollections#resolve(AlbumInfo, java.util.function.Function, boolean, boolean)}.
	 */
	AlbumInfo resolveCollection(AlbumInfo collection, PathInfo path, Caller caller, int viewAs) {
		boolean member = Faces.maySee(_auth, caller, viewAs);
		boolean editor = !caller.isShareLink() && viewAs >= Privacy.PRIVATE && _auth.mayEdit(caller, path);
		return _collections.resolve(collection, folder -> sourceAlbum(folder, caller, viewAs), editor, member);
	}

	/**
	 * The album in the given folder as the given caller is answered it there, <code>null</code>
	 * where they are shown nothing of it: the source of a part of a collection, see issue #221.
	 */
	private AlbumInfo sourceAlbum(PathInfo folder, Caller caller, int viewAs) {
		return sourceAlbum(folder, caller, viewAs, null);
	}

	/**
	 * {@link #sourceAlbum(PathInfo, Caller, int)} for the given photographs alone, <code>null</code>
	 * for all of them: what the search of issue #227 asks, so that the filters and the faces are
	 * derived for its matches and not for every photograph of every album it touches.
	 */
	private AlbumInfo sourceAlbum(PathInfo folder, Caller caller, int viewAs, Set<String> only) {
		return sourceAlbum(folder, caller, viewAs, only, true);
	}

	/**
	 * {@link #sourceAlbum(PathInfo, Caller, int, Set)}, its faces derived only where
	 * <code>faces</code>: an answer that leaves out the faces and the tags of every photograph
	 * does not need them, see {@link #searchMatches(PathInfo, SearchQuery, Caller, int, Map, boolean)}.
	 */
	private AlbumInfo sourceAlbum(PathInfo folder, Caller caller, int viewAs, Set<String> only, boolean faces) {
		if (!folder.toFile().isDirectory() || !_auth.mayView(caller, folder)) {
			return null;
		}
		Resource stored = lookupOrSkip(folder);
		if (!(stored instanceof AlbumInfo) || PhotoCollections.isCollection(stored)) {
			return null;
		}
		if (only != null) {
			stored = PhotoSearch.only((AlbumInfo) stored, only);
		}
		Resource shown = Inboxes.filter(stored, Inboxes.visibility(_auth, caller, folder, viewAs), caller.subject());
		if (shown == null) {
			return null;
		}
		int clearance = Math.min(_auth.clearance(caller, folder), viewAs);
		Resource answer = _privacy.filter(shown, folder, clearance, _auth.minRating(caller, folder, viewAs), "");
		if (faces) {
			answer = withFaces(answer, folder, caller, viewAs, only == null);
		}
		return answer instanceof AlbumInfo ? (AlbumInfo) answer : null;
	}

	/**
	 * The collection of the given folder as a share link sees it, for the card of issue #104: what
	 * {@link SharePreview} draws, with the references kept so that it finds the file.
	 */
	AlbumInfo collectionForLink(PathInfo path, AlbumInfo collection, int clearance, int minRating, String label) {
		AlbumInfo resolved = _collections.resolve(collection, folder -> {
			if (!folder.toFile().isDirectory()) {
				return null;
			}
			Resource stored = lookupOrSkip(folder);
			if (!(stored instanceof AlbumInfo) || PhotoCollections.isCollection(stored)) {
				return null;
			}
			Resource shown = Inboxes.filter(stored, Inboxes.Visibility.NONE, "");
			if (shown == null) {
				return null;
			}
			Resource answer = _privacy.filter(shown, folder, clearance, minRating, "");
			return answer instanceof AlbumInfo ? (AlbumInfo) answer : null;
		}, false, true);
		Resource filtered = _privacy.filter(resolved, path, clearance, minRating, label);
		return filtered instanceof AlbumInfo ? (AlbumInfo) filtered : null;
	}

	/**
	 * The saved search of the given folder as a share link sees it, for the card of issue #104: what
	 * {@link SharePreview} draws, with the reference of its picture kept so that it finds the file,
	 * see issue #227.
	 *
	 * <p>
	 * The card shows the search by the picture stored for it, exactly as its tile does (issue #287),
	 * and never runs the query: a run would choose some match of the moment, which is not the
	 * picture its author chose. The answer holds that one photograph where the link may see it in
	 * its own album and through the link's label; otherwise none, and the card has no picture.
	 * </p>
	 */
	AlbumInfo searchForLink(PathInfo path, AlbumInfo search, int clearance, int minRating, String label) {
		AlbumInfo found = AlbumInfo.create()
			.setKind(de.haumacher.imageServer.shared.model.AlbumKind.SEARCH)
			.setTitle(search.getTitle())
			.setSubTitle(search.getSubTitle());
		ThumbnailInfo chosen = search.getIndexPicture();
		ImagePart picture = chosen == null || chosen.getImage().isEmpty() ? null
			: linkPicture(path, search, chosen.getImage(), clearance, minRating, label);
		if (picture != null) {
			found.setParts(new ArrayList<>(Collections.singletonList(picture)));
			found.setIndexPicture(Crops.copy(chosen));
		}
		return found;
	}

	/**
	 * The stored picture of the given saved search as a share link is shown it, named as the search
	 * names it and with its reference, <code>null</code> where it leads nowhere or the link may not
	 * see it. Never runs the query, see {@link #storedSearchCover}.
	 */
	private ImagePart linkPicture(PathInfo path, AlbumInfo search, String name, int clearance, int minRating,
			String label) {
		PathInfo image = photoNamed(path, search, name);
		if (image == null || Inboxes.isInbox(image.parent().toFile()) || !image.parent().toFile().isDirectory()) {
			return null;
		}
		PathInfo folder = image.parent();
		Resource stored = lookupOrSkip(folder);
		if (!(stored instanceof AlbumInfo) || PhotoSearch.holdsNoFiles(stored)) {
			return null;
		}
		stored = PhotoSearch.only((AlbumInfo) stored, Collections.singleton(image.getName()));
		Resource shown = Inboxes.filter(stored, Inboxes.Visibility.NONE, "");
		if (shown == null) {
			return null;
		}
		Resource answer = _privacy.filter(shown, folder, clearance, minRating, "");
		ImagePart part = answer instanceof AlbumInfo ? Crops.findImage((AlbumInfo) answer, image.getName()) : null;
		if (part == null || !PrivacyFilter.visible(part, clearance, minRating, label)) {
			return null;
		}
		return PhotoSearch.answerCopy(part).setName(name)
			.setRef(PhotoRef.create().setHash("").setPath(image.relativePath()));
	}

	/**
	 * The given listing with the pictures of its entries that lie in a collection checked against
	 * the photograph they stand for, see issue #221.
	 *
	 * <p>
	 * A collection's tile (and a folder's tile chosen through a collection, issue #110) shows its
	 * picture where the caller may see the photograph in its own album and the link's label is on
	 * the reference, with the photograph's crop; otherwise no picture at all, the choice of its
	 * author or nothing. A photograph of the inbox is never a tile's picture.
	 * </p>
	 */
	private ListingInfo withCollectionCovers(ListingInfo listing, PathInfo path, Caller caller, int viewAs,
			String label) {
		List<FolderInfo> folders = listing.getFolders();
		List<FolderInfo> result = null;
		for (int n = 0, size = folders.size(); n < size; n++) {
			FolderInfo folder = folders.get(n);
			FolderInfo shown = collectionCover(folder, path.child(folder.getName()), caller, viewAs, label);
			if (shown != folder && result == null) {
				result = new ArrayList<>(folders.subList(0, n));
			}
			if (result != null) {
				result.add(shown);
			}
		}
		if (result == null) {
			return listing;
		}
		return ListingInfo.create().setTitle(listing.getTitle()).setPlacement(listing.getPlacement())
			.setIndex(listing.getIndex()).setStarred(listing.isStarred()).setFolders(result)
			.setRights(listing.getRights());
	}

	/**
	 * The tile of the folder at the given path as a listing above it would show it to the given
	 * caller: its picture as the caller may see it, a collection's and a saved search's included.
	 * What the overview of issue #267 shows a link's folder with, asked as that link.
	 */
	FolderInfo tileFor(PathInfo path, Caller caller) {
		FolderInfo tile = ResourceCache.folderInfo(path.toFile());
		if (Inboxes.isInbox(path.toFile())) {
			// A link never shows an inbox's photographs, see issue #131.
			tile.setIndexPicture(null);
			return tile;
		}
		String label = _auth.photoLabel(caller);
		int clearance = _auth.clearance(caller, path);
		int minRating = _auth.minRating(caller, path, Privacy.PRIVATE);
		tile = _privacy.filterEntry(tile, path, clearance, minRating, label);
		tile = collectionCover(tile, path, caller, Privacy.PRIVATE, label);
		if (tile.getIndexPicture() == null) {
			// Where the tile has not learned its picture yet (the background did not record the
			// album), the link's own album is asked: the picture its card shows, see issue #260.
			Resource album = _cache.lookup(path);
			if (album instanceof AlbumInfo && !PhotoCollections.isCollection(album) && !PhotoSearch.isSearch(album)) {
				ThumbnailInfo cover = _privacy.tileCover((AlbumInfo) album, clearance, minRating, label);
				if (cover != null) {
					tile.setIndexPicture(cover);
				}
			}
		}
		return tile;
	}

	private FolderInfo collectionCover(FolderInfo folder, PathInfo childPath, Caller caller, int viewAs, String label) {
		if (folder.getKind() == de.haumacher.imageServer.shared.model.FolderKind.SEARCH) {
			return searchTile(folder, childPath, caller, viewAs, label);
		}
		ThumbnailInfo cover = folder.getIndexPicture();
		if (cover == null || cover.getImage().isEmpty() || !childPath.toFile().isDirectory()) {
			return folder;
		}
		PathInfo albumPath = FolderCover.albumOf(childPath, cover.getImage());
		if (!PhotoCollections.isCollection(albumPath.toFile())) {
			// Whether the picture lies in a collection is the sidecar's to say: the album of every
			// tile of a listing is never loaded to learn that it is none, see issue #286.
			return folder;
		}
		Resource album = lookupOrSkip(albumPath);
		if (!PhotoCollections.isCollection(album)) {
			return folder;
		}
		ImagePart reference =
			PhotoCollections.referenceNamed((AlbumInfo) album, FolderCover.imageName(cover.getImage()));
		ImagePart shown = null;
		if (reference != null && Labels.shows(reference, label)) {
			PathInfo image = _collections.locate(reference.getRef());
			if (image != null && !Inboxes.isInbox(image.parent().toFile())) {
				AlbumInfo source = sourceAlbum(image.parent(), caller, viewAs);
				shown = source == null ? null : Crops.findImage(source, image.getName());
			}
		}
		FolderInfo result = FolderInfo.create()
			.setName(folder.getName())
			.setKind(folder.getKind())
			.setImageCount(folder.getImageCount())
			.setTitle(folder.getTitle())
			.setSubTitle(folder.getSubTitle())
			.setStarred(folder.isStarred())
			.setLink(folder.getLink())
			.setEffectiveDate(folder.getEffectiveDate());
		if (shown != null) {
			ThumbnailInfo copy = Crops.copy(cover);
			double[] region = Crops.renditionRegion(shown);
			copy.setCrop(region == null ? null
				: Crop.create().setX(region[0]).setY(region[1])
					.setW(region[2]).setH(region[3]));
			// The moment of a video's poster, see issue #285.
			copy.setPoster(Posters.of(Posters.millis(shown)));
			result.setIndexPicture(copy);
		}
		return result;
	}

	/**
	 * The tile of a saved search in a listing, see issues #227 and #287: the picture stored in its
	 * sidecar where this caller is shown that photograph, the search icon (no picture) otherwise.
	 *
	 * <p>
	 * A listing never runs a saved search. The picture is stored when the search is created (its
	 * first match), when an editor chooses another, and on an editor's opening where it has none
	 * yet, see {@link #rememberSearchCover}. Whether this caller is shown it is asked of that one
	 * photograph exactly as opening the search would ask it: it is found by the query (one
	 * photograph looked up in the index, see {@link #photoNamed}), it lies in no inbox, and the
	 * caller is shown it in its own album and through the search, a link's limits included. A
	 * caller who is not is shown no other photograph instead: the search is shown by the picture
	 * stored for it and by no other, the rule of issue #110 &mdash; finding a substitute would
	 * mean running the search.
	 * </p>
	 */
	private FolderInfo searchTile(FolderInfo folder, PathInfo childPath, Caller caller, int viewAs, String label) {
		Resource stored = childPath.toFile().isDirectory() ? lookupOrSkip(childPath) : null;
		ThumbnailInfo cover =
			PhotoSearch.isSearch(stored) ? storedSearchCover((AlbumInfo) stored, childPath, caller, viewAs, label) : null;
		FolderInfo result = FolderInfo.create()
			.setName(folder.getName())
			.setKind(folder.getKind())
			.setImageCount(folder.getImageCount())
			.setTitle(folder.getTitle())
			.setSubTitle(folder.getSubTitle())
			.setStarred(folder.isStarred())
			.setLink(folder.getLink())
			.setEffectiveDate(folder.getEffectiveDate());
		if (cover != null) {
			result.setIndexPicture(cover);
		}
		return result;
	}

	/**
	 * The picture stored for the given saved search as the given caller is shown it on its tile,
	 * <code>null</code> where it has none or the caller is not shown that photograph, see
	 * {@link #searchTile}. Never runs the query.
	 */
	private ThumbnailInfo storedSearchCover(AlbumInfo search, PathInfo path, Caller caller, int viewAs,
			String label) {
		ThumbnailInfo chosen = search.getIndexPicture();
		if (chosen == null || chosen.getImage().isEmpty()) {
			return null;
		}
		// Null for a query this build cannot run, too.
		PathInfo image = photoNamed(path, search, chosen.getImage());
		if (image == null || Inboxes.isInbox(image.parent().toFile())) {
			// Deleted, moved, no longer found -- or a photograph of an inbox, never a tile's picture.
			return null;
		}
		AlbumInfo source = sourceAlbum(image.parent(), caller, viewAs, Collections.singleton(image.getName()), false);
		ImagePart shown = source == null ? null : Crops.findImage(source, image.getName());
		if (shown == null) {
			return null;
		}
		// The filters of the search itself on top, a link's label asked of the photograph's own.
		int clearance = Math.min(_auth.clearance(caller, path), viewAs);
		if (!PrivacyFilter.visible(shown, clearance, _auth.minRating(caller, path, viewAs), label)) {
			return null;
		}
		ThumbnailInfo result = Crops.copy(chosen);
		// The photograph as it is shown now: its crop (issue #212) and a video's poster (#285).
		double[] region = Crops.renditionRegion(shown);
		result.setCrop(region == null ? null
			: Crop.create().setX(region[0]).setY(region[1]).setW(region[2]).setH(region[3]));
		result.setPoster(Posters.of(Posters.millis(shown)));
		return result;
	}

	/**
	 * The given answer with the faces of its photographs, for a caller that is answered any.
	 *
	 * <p>
	 * The one place a face reaches the wire, and the one place it is decided who is answered one,
	 * see {@link Faces#maySee(AuthService, Caller, int)}: an anonymous visitor and a share link are
	 * answered the album exactly as before, faces and all left out, and so is everybody when the
	 * space did not switch the index on, and so is the author previewing their own album as the
	 * public sees it (<code>?viewAs=public</code>, issue #46). Nothing is dropped from an answer
	 * here — the faces are <em>added</em> to a copy, so a path that forgets to ask answers none.
	 * </p>
	 *
	 * <p>
	 * A listing is untouched: faces belong to photographs and a listing shows folders.
	 * </p>
	 */
	private Resource withLabels(Resource answer, Caller caller, int viewAs) {
		if (answer instanceof AlbumInfo && !Faces.maySee(_auth, caller, viewAs)) {
			return Labels.withoutLabels((AlbumInfo) answer);
		}
		return answer;
	}

	/**
	 * The fields of a photograph {@link #withLabels} and {@link #withFaces} never answer the given
	 * caller, whatever they hold: the labels and the face tags of a share link, an anonymous
	 * visitor and the public preview. A write keeps them as stored, empty or not, see
	 * {@link AnsweredWrite} and issue #296.
	 */
	private Set<String> withheld(Caller caller, int viewAs) {
		return Faces.maySee(_auth, caller, viewAs) ? Collections.<String> emptySet() : WITHHELD_FROM_GUESTS;
	}

	/** See {@link #withheld(Caller, int)}. */
	private static final Set<String> WITHHELD_FROM_GUESTS = Set.of(Labels.FIELD, Faces.TAGS_FIELD);

	private Resource withFaces(Resource answer, PathInfo pathInfo, Caller caller, int viewAs) {
		return withFaces(answer, pathInfo, caller, viewAs, true);
	}

	/**
	 * {@link #withFaces(Resource, PathInfo, Caller, int)} of an answer holding all of its album
	 * (<code>whole</code>), or some of its photographs, see {@link FaceIndex#derive(AlbumInfo, File, PeopleStore, boolean)}.
	 */
	private Resource withFaces(Resource answer, PathInfo pathInfo, Caller caller, int viewAs, boolean whole) {
		if (!(answer instanceof AlbumInfo)) {
			return answer;
		}
		AlbumInfo album = (AlbumInfo) answer;
		if (!Faces.maySee(_auth, caller, viewAs)) {
			// A detection is added and is therefore never there for such a caller; a tag is
			// stored in the album and has to be taken out again, see issue #125.
			return Faces.withoutTags(album);
		}
		if (!_faces.isEnabled() && !FaceIndex.tagged(album)) {
			return album;
		}
		File folder = pathInfo.toFile();
		AlbumInfo result = _faces.derive(album, folder, _people, whole);
		if (result.isFacesPending()) {
			// Whatever is not looked at yet is queued -- after the answer was built, so that what
			// the answer says about itself is what it was built from. It never waits for the work,
			// exactly as a request for a video rendition does not, see issue #74.
			_faces.queue(folder);
		}
		return result;
	}

	/**
	 * Delivers the crop of one face of a photograph, see issue #124.
	 *
	 * <p>
	 * Cut out of the preview and cached beside it, exactly like every other generated file. A
	 * request that is answered no faces at all is answered <code>404</code> here too — never the
	 * crop, and never a refusal that would say that there is a face to refuse. The same
	 * <code>viewAs</code> the album answer reads decides here (issue #46), so the author's preview
	 * of the public view has no crops either.
	 * </p>
	 */
	private void serveFace(Context context, PathInfo pathInfo, Caller caller, int viewAs) throws IOException {
		if (!_faces.isEnabled() || !Faces.maySee(_auth, caller, viewAs)) {
			LOG.warning("Refusing the face of '" + context.request().getPathInfo() + "'.");
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, FACE_NOT_FOUND);
			return;
		}
		int index;
		try {
			index = Integer.parseInt(context.getParameter(FACE_PARAMETER));
		} catch (NumberFormatException | NullPointerException ex) {
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, FACE_NOT_FOUND);
			return;
		}
		// The numbering of the answer, which counts the album's own tags too (issue #155).
		Resource described = _cache.lookup(pathInfo);
		List<FaceTag> tags = described instanceof ImagePart ? ((ImagePart) described).getTags()
			: Collections.<FaceTag> emptyList();
		// Cut by the background where there is one, never here, see issue #256.
		FaceIndex.Crop crop = _faces.crop(pathInfo.toFile(), index, tags, !_cache.hasBackground());
		if (crop.isPending()) {
			// Cut next by the background, see issue #258.
			File image = pathInfo.toFile();
			de.haumacher.imageServer.pipeline.FolderPipeline.demanded(image, crop.getTarget(),
				() -> _faces.crop(image, index, tags, true));
			notThereYet(context);
			return;
		}
		if (crop.getFile() == null) {
			LOG.warning("Refusing the face " + index + " of '" + context.request().getPathInfo()
				+ "': " + crop.getReason());
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, FACE_NOT_FOUND);
			return;
		}
		serveData(context, crop.getFile(), "image/jpeg");
	}

	/**
	 * Delivers a transcoded rendition of a video, see issue #74.
	 *
	 * <p>
	 * The rendition is never made while the request waits: what is not there is queued and
	 * answered with <code>202</code>, so that the app can come back for it. What could not be made
	 * is a failure of this server, answered like any other, and coming back would not help.
	 * </p>
	 */
	private void serveRendition(Context context, PathInfo pathInfo, VideoRenditions.Kind kind) throws IOException {
		File file = pathInfo.toFile();
		if (!VideoRenditions.isVideo(file)) {
			// Only a video has renditions; a photo has its thumbnail.
			error404(context);
			return;
		}
		VideoRenditions.Rendition rendition = _videos.lookup(file, kind);
		switch (rendition.getState()) {
			case READY:
				serveData(context, rendition.getFile(), "video/mp4");
				return;
			case PENDING:
				context.response().setHeader("Retry-After", RETRY_AFTER_SECONDS);
				errorInfo(context, HttpServletResponse.SC_ACCEPTED, RENDITION_PENDING);
				return;
			case UNAVAILABLE:
				LOG.warning("Refusing the " + kind.parameter() + " rendition of '"
					+ context.request().getPathInfo() + "': " + rendition.getReason());
				errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					RENDITIONS_UNAVAILABLE + rendition.getReason());
				return;
			default:
				LOG.warning("Refusing the " + kind.parameter() + " rendition of '"
					+ context.request().getPathInfo() + "': " + rendition.getReason());
				errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					renditionFailed(file, rendition.getReason()));
				return;
		}
	}

	/**
	 * The message a failed transcode is answered with: {@link #RENDITION_FAILED} and the reason the
	 * transcoder gave, see issue #184.
	 *
	 * <p>
	 * The app shows the reason ("the server could not convert this video: …") and writes it into its
	 * diagnostics log, so the absolute folder of the album — a fact about the server's disk, not about
	 * the video — is cut out of it; what is left names the file by its name.
	 * </p>
	 */
	static String renditionFailed(File file, String reason) {
		if (reason == null || reason.isEmpty()) {
			return RENDITION_FAILED;
		}
		File folder = file.getAbsoluteFile().getParentFile();
		String shown = folder == null ? reason : reason.replace(folder.getPath() + File.separator, "");
		return RENDITION_FAILED + " " + shown;
	}

	/**
	 * Why the image at the given path may not be shown to such a request.
	 *
	 * <p>
	 * An image file the album model does not describe (its analysis failed, say) carries no
	 * privacy level and is {@link Privacy#PUBLIC}, exactly as one that was never edited.
	 * </p>
	 *
	 * @return The message the request is refused with, <code>null</code> if the image may be shown.
	 */
	private String hidden(PathInfo pathInfo, int clearance, int minRating) {
		Resource resource = _cache.lookup(pathInfo);
		if (!(resource instanceof ImagePart)) {
			return null;
		}
		ImagePart image = (ImagePart) resource;
		if (!Privacy.visible(image.getPrivacy(), clearance)) {
			return IMAGE_REFUSED;
		}
		if (!Ratings.visible(image.getRating(), minRating)) {
			return RATING_REFUSED;
		}
		return null;
	}

	/**
	 * Whether the photograph at the given path is part of the label the caller is shown, see
	 * {@link AuthService#photoLabel} and issue #213.
	 *
	 * <p>
	 * Always for a caller shown every label. A file the album model does not describe carries no
	 * label, so a link showing one does not show it; a raw companion addressed by its own name is
	 * the photograph it belongs to (issue #191).
	 * </p>
	 */
	private boolean labelShows(Caller caller, PathInfo pathInfo) {
		String label = _auth.photoLabel(caller);
		if (label.isEmpty()) {
			return true;
		}
		Resource resource = _cache.lookup(pathInfo);
		return resource instanceof ImagePart && Labels.carries((ImagePart) resource, label);
	}

	/**
	 * Refuses a request for an image the caller may not see, see {@link #IMAGE_REFUSED}.
	 *
	 * <p>
	 * An anonymous caller is answered with <code>401</code> and the challenge that says how to get
	 * further; a signed-in caller with <code>403</code>, because signing in again would not help
	 * (that is also what the "view as" preview of the owner's own album produces).
	 * </p>
	 */
	private static void imageRefused(Context context, Caller caller, String message) throws IOException {
		LOG.warning("Refusing the image '" + context.request().getPathInfo() + "': " + message);
		if (identified(caller)) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, message);
		} else {
			context.response().setHeader("WWW-Authenticate", "Bearer");
			errorInfo(context, HttpServletResponse.SC_UNAUTHORIZED, message);
		}
	}

	private String mimeType(Context context, Resource resource) {
		if (resource instanceof ImagePart) {
			ImagePart image = (ImagePart) resource;
			ImageKind kind = image.getKind();
			switch (kind) {
			case VIDEO:
			case QUICKTIME:
				return videoType(image.getName(), kind);
			case IMAGE:
				String coded = CodedPictures.contentType(image.getName());
				if (coded != null) {
					// Not every container's MIME table knows them, see issues #186, #193.
					return coded;
				}
				String picture = pictureType(image.getName());
				if (picture != null) {
					return picture;
				}
				if (PreviewCache.isVideoName(image.getName())) {
					// A video not described yet (copied in after its album was cached) is still
					// a video: the container's MIME table need not know .3gp or .m4v.
					return videoType(image.getName(), kind);
				}
				return context.request().getServletContext().getMimeType(image.getName());
			}
		}
		return "application/binary";
	}

	/**
	 * The content type of a WebP or GIF original, see issue #190, by its extension in any case;
	 * <code>null</code> for every other name, whose type the container's MIME table says. Written
	 * down rather than left to that table, which need not know <code>.webp</code>.
	 */
	static String pictureType(String name) {
		switch (extension(name)) {
			case "webp":
				return "image/webp";
			case "gif":
				return "image/gif";
			default:
				return null;
		}
	}

	/**
	 * The content type of the original of a video, see issue #189.
	 *
	 * <p>
	 * By the container the analysis found and the name it came with: a QuickTime movie is
	 * <code>video/quicktime</code> whatever it is called, a 3GPP file <code>video/3gpp</code>, and
	 * every other file of the mp4 family — an <code>.m4v</code> of an Apple export among them, which
	 * is an mp4 with a brand of its own — <code>video/mp4</code>, the one type a browser's player
	 * is sure to try. The containers only FFmpeg reads (issue #192) by their extension: an MPEG
	 * transport stream <code>video/mp2t</code>, an AVI <code>video/x-msvideo</code>, a Matroska
	 * file <code>video/x-matroska</code> and a WebM file <code>video/webm</code>.
	 * </p>
	 */
	static String videoType(String name, ImageKind kind) {
		if (kind == ImageKind.QUICKTIME) {
			return "video/quicktime";
		}
		switch (extension(name)) {
			case "3gp":
				return "video/3gpp";
			case "mts":
			case "m2ts":
				return "video/mp2t";
			case "avi":
				return "video/x-msvideo";
			case "mkv":
				return "video/x-matroska";
			case "webm":
				return "video/webm";
			default:
				return "video/mp4";
		}
	}

	private static void serveJson(HttpServletResponse response, Resource album) throws IOException {
		LOG.log(Level.FINE, "Delivering JSON.");

		response.setContentType("application/json");
		response.setCharacterEncoding("utf-8");

		// Allow access from mobile app.
		allowCrossOrigin(response);
		try (JsonWriter json = new JsonWriter(new BufferedJson(new OutputStreamWriter(response.getOutputStream(), "utf-8")))) {
			album.writeTo(json);
		}
	}

	/**
	 * The writer of a JSON answer: the {@link JsonWriter} hands it every quote, comma and colon on
	 * its own, some millions for a search answer of a hundred thousand photographs, which a
	 * {@link java.io.Writer} takes one lock each for and an unbuffered one encodes each on its own;
	 * collected here first, see issue #276. One thread writes an answer.
	 */
	private static final class BufferedJson implements de.haumacher.msgbuf.io.Writer {

		private final java.io.Writer _out;

		private final char[] _buffer = new char[8192];

		private int _length;

		BufferedJson(java.io.Writer out) {
			_out = out;
		}

		@Override
		public void write(char ch) throws IOException {
			if (_length == _buffer.length) {
				drain();
			}
			_buffer[_length++] = ch;
		}

		@Override
		public void write(String text, int offset, int length) throws IOException {
			if (length > _buffer.length - _length) {
				drain();
				if (length > _buffer.length) {
					_out.write(text, offset, length);
					return;
				}
			}
			text.getChars(offset, offset + length, _buffer, _length);
			_length += length;
		}

		private void drain() throws IOException {
			_out.write(_buffer, 0, _length);
			_length = 0;
		}

		@Override
		public void flush() throws IOException {
			drain();
			_out.flush();
		}

		@Override
		public void close() throws IOException {
			drain();
			_out.close();
		}
	}

	/**
	 * Delivers a plain data object (one that is not a {@link Resource}) as JSON.
	 *
	 * <p>
	 * Unlike a {@link Resource}, such an object carries no type tag: the caller knows what it
	 * asked for.
	 * </p>
	 */
	static void serveJsonObject(HttpServletResponse response, de.haumacher.msgbuf.data.DataObject object)
			throws IOException {
		response.setContentType("application/json");
		response.setCharacterEncoding("utf-8");

		allowCrossOrigin(response);
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(response.getOutputStream(), "utf-8")))) {
			object.writeTo(json);
		}
	}

	/**
	 * Announces that the app may talk to this server from another origin, and that nothing of
	 * what it is told may be kept.
	 *
	 * <p>
	 * The <code>Authorization</code> header of an authenticated request makes a browser send a
	 * preflight, so the header must be allowed explicitly, see
	 * {@link #doOptions(HttpServletRequest, HttpServletResponse)}.
	 * </p>
	 *
	 * <p>
	 * Every JSON answer depends on the bearer that asked, and some — <code>410 Gone</code>
	 * among them — a browser caches by default: a withdrawn share link answered once at
	 * <code>?type=auth</code> would then be replayed for every later token at the same origin,
	 * and an invitation opened next would be told it was withdrawn. So no JSON answer may be
	 * stored, and a cache that stores anyway must key it on the bearer.
	 * </p>
	 */
	private static void allowCrossOrigin(HttpServletResponse response) {
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Allow-Headers",
			"Authorization, Content-Type, " + AuthService.CONTACT_HEADER + ", " + AuthService.SHARED_HEADER);
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Vary", "Authorization, " + AuthService.CONTACT_HEADER + ", " + AuthService.SHARED_HEADER);
	}

	/**
	 * Delivers the contents of the given file, honouring a <code>Range</code> request header.
	 *
	 * <p>
	 * Range support is generic: it applies to originals and thumbnails alike. A single byte range
	 * is answered with <code>206 Partial Content</code>, an unsatisfiable one with
	 * <code>416</code>. A multi-range request is answered with the complete file and status
	 * <code>200</code> (an allowed response that spares building a
	 * <code>multipart/byteranges</code> body); see {@link ByteRange}.
	 * </p>
	 */
	private void serveData(Context context, File file, String mimeType) throws IOException {
		LOG.log(Level.FINE, "Delivering image data: " + mimeType);
		HttpServletResponse response = context.response();

		response.setContentType(mimeType);
		// Allow access from mobile app (is required even for images, since they are rendered using WebGL from Flutter).
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Expose-Headers", "Accept-Ranges, Content-Range, Content-Length");

		long length = file.length();

		// Announce range support on every response, so that a client knows it may seek.
		response.setHeader("Accept-Ranges", "bytes");

		ByteRange range = ByteRange.parse(context.request().getHeader("Range"), length);
		if (range.isUnsatisfiable()) {
			LOG.log(Level.WARNING, "Unsatisfiable range '" + context.request().getHeader("Range") + "' for file of size " + length + ": " + file.getAbsolutePath());
			response.setHeader("Content-Range", "bytes */" + length);
			response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
			return;
		}

		if (range.isPartial()) {
			long start = range.getStart();
			long end = range.getEnd();
			response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
			response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
			response.setContentLengthLong(range.getLength());
			Util.sendSlice(response, file, start, range.getLength());
			return;
		}

		response.setContentLengthLong(length);
		try (FileInputStream in = new FileInputStream(file)) {
			Util.sendBytes(response, in);
		}
	}

	private static boolean jsonRequested(Context context) {
		return "json".equals(context.getParameter("type"));
	}

	private void error404(Context context) {
		error(context, HttpServletResponse.SC_NOT_FOUND);
	}

	private static void error(Context context, int errorCode) {
		LOG.log(Level.WARNING, "Faild to access '" + context.request().getPathInfo() + "': " + errorCode);

		HttpServletResponse response = context.response();
		allowCrossOrigin(response);
		response.setStatus(errorCode);
	}

	static class Context {

		private final HttpServletRequest _request;
		private final HttpServletResponse _response;

		/**
		 * Creates a {@link Context}.
		 */
		public Context(HttpServletRequest request, HttpServletResponse response) {
			_request = request;
			_response = response;
		}

		public String getContextPath() {
			return _request.getContextPath();
		}

		/**
		 * See {@link HttpServletRequest#getParameter(String)}.
		 */
		public String getParameter(String name) {
			return request().getParameter(name);
		}

		/**
		 * The current {@link HttpServletRequest}.
		 */
		public HttpServletRequest request() {
			return _request;
		}

		/**
		 * The current {@link HttpServletResponse}.
		 */
		public HttpServletResponse response() {
			return _response;
		}

	}
}
