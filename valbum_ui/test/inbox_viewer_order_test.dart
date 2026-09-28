import 'package:flutter_test/flutter_test.dart';
import 'package:valbum_ui/album_model.dart';
import 'package:valbum_ui/resource.dart';

/// The viewer pages through an inbox in the order the inbox draws it: the
/// days newest first, the photographs of a day chronologically.
void main() {
  int at(int day, int hour) =>
      DateTime(2026, 9, day, hour).millisecondsSinceEpoch;

  ImagePart image(String name, int date, {int rating = 0}) =>
      ImagePart(name: name, date: date, rating: rating);

  List<String> chain(AlbumInfo album) {
    var names = <String>[];
    AbstractImage? current = album.parts.whereType<AbstractImage>().isEmpty
        ? null
        : (album.parts.first as AbstractImage).home;
    while (current != null) {
      names.add((current as ImagePart).name);
      current = current.next;
    }
    return names;
  }

  test("an inbox links its images day by day, a day chronologically", () {
    // What the server answers: flat, newest first, undated last.
    var album = AlbumInfo(
      kind: AlbumKind.inbox,
      parts: [
        image("b2.jpg", at(12, 18)),
        image("b1.jpg", at(12, 9), rating: -2),
        image("a2.jpg", at(11, 20)),
        image("a1.jpg", at(11, 8)),
        image("x.jpg", 0),
      ],
    );
    AlbumInitializer().init(album);

    expect(chain(album), ["b1.jpg", "b2.jpg", "a1.jpg", "a2.jpg", "x.jpg"]);
    var b2 = album.parts.first as ImagePart;
    expect((b2.next as ImagePart).name, "a1.jpg");
    expect((b2.previous as ImagePart).name, "b1.jpg");
    expect((b2.home as ImagePart).name, "b1.jpg");
    expect((b2.end as ImagePart).name, "x.jpg");
  });

  test("an album keeps its stored order", () {
    var album = AlbumInfo(
      parts: [image("b.jpg", at(12, 18)), image("a.jpg", at(11, 8))],
    );
    AlbumInitializer().init(album);

    expect(chain(album), ["b.jpg", "a.jpg"]);
  });
}
