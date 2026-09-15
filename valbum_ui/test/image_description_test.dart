/// Tests of the description edited in the fullscreen viewer (issue #80).
///
/// A description is written where the picture is looked at, not where the
/// tiles are: a long press (or `e`) opens the album's own description dialog
/// on the image that fills the screen. Outside the album's edit mode the
/// change is written to the server at once — the whole sidecar, exactly as the
/// album view writes it — and inside it, it goes into the editing buffer and
/// is saved with everything else.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:valbum_ui/album_view.dart' show TextInputDialog;
import 'package:valbum_ui/client.dart';
import 'package:valbum_ui/image_view.dart';
import 'package:valbum_ui/resource.dart';
import 'package:valbum_ui/rights.dart';
import 'package:valbum_ui/share_session.dart';
import 'package:valbum_ui/urls.dart';
import 'package:valbum_ui/video_view.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

import 'util/fake_image_http.dart';
import 'util/fake_video_player.dart';
import 'util/fixtures.dart';

/// The album the tests edit.
const List<String> albumPath = ["album"];
const String albumUrl = "http://server/valbum/data/album";

/// An image of that album.
ImagePart imagePart(
  String name, {
  String comment = "",
  ImageKind kind = ImageKind.image,
}) =>
    ImagePart(
      name: name,
      comment: comment,
      kind: kind,
      width: 2000,
      height: 1000,
    );

/// Links the given parts into an album, as loading one does.
AlbumInfo linkedAlbum(List<AlbumPart> parts, {List<RightName>? rights}) {
  var album = AlbumInfo(parts: parts, rights: rights ?? const []);
  for (var part in parts) {
    part.owner = album;
    if (part is ImageGroup) {
      for (var member in part.images) {
        member.owner = album;
        member.group = part;
      }
    }
  }
  return album;
}

/// A right as the server names it.
RightName right(String name) => RightName(name: name);

/// What was PUT to the server, and what the server answered.
class RecordingServer {
  /// Every request, in order.
  final List<http.Request> requests = [];

  /// The status the PUT is answered with.
  int putStatus;

  /// The body the PUT is answered with.
  String putBody;

  RecordingServer({this.putStatus = 200, this.putBody = "{}"});

  /// The PUTs of the album sidecar.
  List<http.Request> get puts =>
      [for (var request in requests) if (request.method == "PUT") request];

  VAlbumClient get client => VAlbumClient(
        dataUrl: "http://server/valbum/data",
        httpClient: MockClient(servingThumbnails((http.Request request) async {
          requests.add(request);
          if (request.method == "PUT") {
            return http.Response(
              putBody,
              putStatus,
              headers: const {"content-type": "application/json"},
            );
          }
          return http.Response("{}", 200);
        })),
      );
}

/// Pumps the viewer on [image], as the app builds it.
Future<void> pumpViewer(
  WidgetTester tester,
  AbstractImage image, {
  required RecordingServer server,
  bool editing = false,
  VoidCallback? onEdited,
  ShareSession? share,
  List<String>? editPath = albumPath,
}) async {
  Widget viewer = ImageView(
    client: server.client,
    baseUrl: albumUrl,
    image: image,
    onShowImage: (next) {},
    editPath: editPath,
    editing: editing,
    onEdited: onEdited,
  );
  if (share != null) {
    viewer = ShareSessionScope(session: share, child: viewer);
  }
  await withFakeImageHttp(() async {
    await tester.pumpWidget(MaterialApp(home: viewer));
    await tester.pumpAndSettle();
  });
}

/// Long-presses the picture.
Future<void> longPressImage(WidgetTester tester) async {
  await withFakeImageHttp(() async {
    await tester.longPress(find.byType(Image).first);
    await tester.pumpAndSettle();
  });
}

/// The text the dialog holds.
String dialogText(WidgetTester tester) =>
    tester.widget<TextField>(find.byType(TextField)).controller!.text;

/// Types [text] into the dialog and confirms it.
Future<void> confirm(WidgetTester tester, String text) async {
  await withFakeImageHttp(() async {
    await tester.enterText(find.byType(TextField), text);
    await tester.pumpAndSettle();
    await tester.tap(find.text("Übernehmen"));
    await tester.pumpAndSettle();
  });
}

void main() {
  setUp(() {
    VideoPlayerPlatform.instance = FakeVideoPlayerPlatform();
  });

  testWidgets('a long press opens the description of the image shown',
      (tester) async {
    var image = imagePart("a.jpg", comment: "Am Meer");
    linkedAlbum([image, imagePart("b.jpg")]);
    var server = RecordingServer();

    await pumpViewer(tester, image, server: server);
    await longPressImage(tester);

    expect(find.byType(TextInputDialog), findsOneWidget);
    expect(dialogText(tester), "Am Meer", reason: "prefilled with what is");
  });

  testWidgets('writes the whole album with the one changed description',
      (tester) async {
    var image = imagePart("a.jpg", comment: "Am Meer");
    var other = imagePart("b.jpg", comment: "Im Wald");
    linkedAlbum([image, other]);
    var server = RecordingServer();

    await pumpViewer(tester, image, server: server);
    await longPressImage(tester);
    await confirm(tester, "Am Meer, abends");

    // Exactly one write, to the album's own folder.
    expect(server.puts, hasLength(1));
    expect(server.puts.single.url.toString(), "$albumUrl/");
    var body = server.puts.single.body;
    expect(body, contains("Am Meer, abends"));
    // Everything else travels along unchanged: the sidecar is one document.
    expect(body, contains("Im Wald"));
    expect(body, contains("a.jpg"));
    expect(body, contains("b.jpg"));
    // And the model is what was sent.
    expect(image.comment, "Am Meer, abends");
    expect(other.comment, "Im Wald");
  });

  testWidgets('shows the new description at once', (tester) async {
    var image = imagePart("a.jpg");
    linkedAlbum([image]);
    var server = RecordingServer();

    await pumpViewer(tester, image, server: server);
    expect(find.byKey(const Key("image-comment")), findsNothing);

    await longPressImage(tester);
    await confirm(tester, "Der Hafen");

    expect(find.byKey(const Key("image-comment")), findsOneWidget);
    expect(find.text("Der Hafen"), findsOneWidget);
  });

  testWidgets('keeps the change in the buffer while the album is edited',
      (tester) async {
    var image = imagePart("a.jpg", comment: "Am Meer");
    linkedAlbum([image]);
    var server = RecordingServer();
    var dirtied = 0;

    await pumpViewer(
      tester,
      image,
      server: server,
      editing: true,
      onEdited: () => dirtied++,
    );
    await longPressImage(tester);
    await confirm(tester, "Am Meer, abends");

    expect(server.puts, isEmpty, reason: "the album view writes it, later");
    expect(dirtied, 1, reason: "and it knows there is something to write");
    expect(image.comment, "Am Meer, abends");
  });

  testWidgets('refuses where the caller may not edit the album',
      (tester) async {
    var image = imagePart("a.jpg", comment: "Am Meer");
    linkedAlbum([image], rights: [right(rightView), right(rightDownload)]);
    var server = RecordingServer();

    await pumpViewer(tester, image, server: server);
    await longPressImage(tester);

    expect(find.byType(TextInputDialog), findsNothing);
    expect(find.byKey(const Key("image-not-editable")), findsOneWidget);
    expect(find.text("You may not edit this album."), findsOneWidget);
    expect(server.puts, isEmpty);
    expect(image.comment, "Am Meer");
  });

  testWidgets('refuses inside a share link, whatever the rights say',
      (tester) async {
    var image = imagePart("a.jpg", comment: "Am Meer");
    // Every right — and still no editing: a link is not an account.
    linkedAlbum([image], rights: [right(rightEdit)]);
    var server = RecordingServer();

    await pumpViewer(
      tester,
      image,
      server: server,
      share: ShareSession(
        url: const SessionUrl(
          kind: SessionKind.share,
          token: "t0ken",
          dataUrl: "http://server/valbum/data",
          basePath: "/valbum/s/t0ken/",
        ),
        info: ShareInfo(label: "Urlaub", rights: [right(rightView)]),
        writeAllowed: false,
      ),
    );
    await longPressImage(tester);

    expect(find.byType(TextInputDialog), findsNothing);
    expect(find.byKey(const Key("image-not-editable")), findsOneWidget);
    expect(server.puts, isEmpty);
  });

  testWidgets('says why a write was refused and keeps what was typed',
      (tester) async {
    var image = imagePart("a.jpg", comment: "Am Meer");
    linkedAlbum([image]);
    var server = RecordingServer(
      putStatus: 403,
      putBody: '["ErrorInfo",{"message":"You may only look at this album."}]',
    );

    await pumpViewer(tester, image, server: server);
    await longPressImage(tester);
    await confirm(tester, "Am Meer, abends");

    // The server's own sentence, not a status code.
    expect(find.byKey(const Key("image-description-failed")), findsOneWidget);
    expect(find.text("You may only look at this album."), findsOneWidget);
    // The dialog is back, holding what was typed: a retry costs no typing.
    expect(find.byType(TextInputDialog), findsOneWidget);
    expect(dialogText(tester), "Am Meer, abends");
    // And nothing was changed behind it: the caption says what the server has.
    expect(image.comment, "Am Meer");
  });

  testWidgets('edits the description of a video too', (tester) async {
    var video = imagePart("clip.mp4", comment: "", kind: ImageKind.video);
    linkedAlbum([video]);
    var server = RecordingServer();

    await pumpViewer(tester, video, server: server);
    // The video fills the slot the image would: the long press is on it.
    await withFakeImageHttp(() async {
      await tester.longPress(find.byType(VideoView));
      await tester.pumpAndSettle();
    });

    expect(find.byType(TextInputDialog), findsOneWidget);
    await confirm(tester, "Die Wellen");

    expect(server.puts, hasLength(1));
    expect(server.puts.single.body, contains("Die Wellen"));
    expect(video.comment, "Die Wellen");
  });

  testWidgets('edits the member that is shown, not its group', (tester) async {
    var first = imagePart("a.jpg", comment: "Erster Versuch");
    var second = imagePart("b.jpg", comment: "Zweiter Versuch");
    var group = ImageGroup(images: [first, second]);
    linkedAlbum([group]);
    var server = RecordingServer();

    // The detail view of the group shows one member, see `group_view.dart`.
    await pumpViewer(tester, second, server: server);
    await longPressImage(tester);

    expect(dialogText(tester), "Zweiter Versuch");
    await confirm(tester, "Der bessere");

    expect(second.comment, "Der bessere");
    expect(first.comment, "Erster Versuch", reason: "the other one is not it");
    expect(server.puts, hasLength(1));
    expect(server.puts.single.body, contains("Der bessere"));
    expect(server.puts.single.body, contains("Erster Versuch"));
  });

  testWidgets('is on the keyboard as well as under a long press',
      (tester) async {
    var image = imagePart("a.jpg", comment: "Am Meer");
    linkedAlbum([image]);
    var server = RecordingServer();

    await pumpViewer(tester, image, server: server);
    await withFakeImageHttp(() async {
      await tester.sendKeyEvent(LogicalKeyboardKey.keyE);
      await tester.pumpAndSettle();
    });

    expect(find.byType(TextInputDialog), findsOneWidget);
    expect(dialogText(tester), "Am Meer");
  });

  testWidgets('writes nothing when the dialog is cancelled', (tester) async {
    var image = imagePart("a.jpg", comment: "Am Meer");
    linkedAlbum([image]);
    var server = RecordingServer();

    await pumpViewer(tester, image, server: server);
    await longPressImage(tester);
    await withFakeImageHttp(() async {
      await tester.tap(find.text("Abbrechen"));
      await tester.pumpAndSettle();
    });

    expect(server.puts, isEmpty);
    expect(image.comment, "Am Meer");
  });
}
