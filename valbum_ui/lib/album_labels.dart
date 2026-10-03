/// The labels of the photographs of an album, and the views they filter
/// (issue #213).
///
/// A label is the author's sub-view of one album — "the day we met Anna and
/// Ben" — stored on every photograph carrying it ([ImagePart.labels]) and
/// nowhere else: the labels of an album are the labels its photographs carry.
/// The members see them as chips above the grid that filter it; a share link
/// may show the photographs of one label only, which the server filters.
///
/// Pure functions over the generated model, so that they are checked without
/// a screen, plus the one piece of view state they need: which label an
/// album is filtered by ([labelFilterOf]), kept beside the album like its
/// rating filter so that the viewer pages through the same photographs the
/// grid shows.
library;

import 'album_edit.dart' show headingLevel, headingSubsection;
import 'resource.dart';

/// The label names of the given photograph, in the order it carries them.
List<String> labelsOf(ImagePart image) => [
      for (var label in image.labels) label.name,
    ];

/// Whether the given photograph carries [label].
bool carriesLabel(ImagePart image, String label) =>
    image.labels.any((name) => name.name == label);

/// The photographs a part stands for: an image itself, every member of a
/// group, nothing for a heading.
List<ImagePart> imagesOfPart(AlbumPart part) => switch (part) {
      ImagePart image => [image],
      ImageGroup group => group.images,
      _ => const [],
    };

/// Whether a view showing the photographs of [label] shows the given part:
/// an image carrying it, a group one of whose members carries it.
bool partCarriesLabel(AlbumPart part, String label) =>
    imagesOfPart(part).any((image) => carriesLabel(image, label));

/// One label of an album with the number of photographs carrying it.
typedef LabelCount = ({String label, int count});

/// The labels of the given parts, alphabetically ignoring case, each with the
/// number of photographs carrying it — the members of a group one by one.
///
/// [shown] says which parts count, so that the number on a chip is the number
/// of photographs a tap on it shows under the current rating filter.
List<LabelCount> albumLabels(
  Iterable<AlbumPart> parts, {
  bool Function(AlbumPart part)? shown,
}) {
  var counts = <String, int>{};
  for (var part in parts) {
    var counted = shown == null || shown(part);
    for (var image in imagesOfPart(part)) {
      for (var label in labelsOf(image).toSet()) {
        counts[label] = (counts[label] ?? 0) + (counted ? 1 : 0);
      }
    }
  }
  var labels = counts.keys.toList()..sort(compareLabels);
  return [for (var label in labels) (label: label, count: counts[label]!)];
}

/// The order labels are offered in: alphabetically ignoring case, the exact
/// spelling breaking a tie.
int compareLabels(String a, String b) {
  var result = a.toLowerCase().compareTo(b.toLowerCase());
  return result != 0 ? result : a.compareTo(b);
}

/// A label as it is stored: the text typed, without blanks around it.
String normalizedLabel(String typed) => typed.trim();

/// Gives every photograph of [parts] the given label.
///
/// A group is labeled member by member, so that the label survives a later
/// ungrouping and a link showing it shows the group. Answers whether anything
/// changed. The list is replaced, never added to: a part read without the
/// field carries a constant empty list.
bool addLabel(Iterable<AlbumPart> parts, String label) {
  var changed = false;
  for (var part in parts) {
    for (var image in imagesOfPart(part)) {
      if (!carriesLabel(image, label)) {
        image.labels = [...image.labels, LabelName(name: label)];
        changed = true;
      }
    }
  }
  return changed;
}

/// Takes the given label off every photograph of [parts]; answers whether
/// anything changed.
bool removeLabel(Iterable<AlbumPart> parts, String label) {
  var changed = false;
  for (var part in parts) {
    for (var image in imagesOfPart(part)) {
      if (carriesLabel(image, label)) {
        image.labels = [
          for (var name in image.labels)
            if (name.name != label) name,
        ];
        changed = true;
      }
    }
  }
  return changed;
}

/// Renames [from] to [to] on every photograph of [parts], or takes it off
/// where [to] is empty — what the server's `?action=relabel` does, for an
/// answer that could not be read. A photograph carrying both keeps one.
void renameLabelIn(Iterable<AlbumPart> parts, String from, String to) {
  for (var part in parts) {
    for (var image in imagesOfPart(part)) {
      if (!carriesLabel(image, from)) {
        continue;
      }
      var hasTarget = to.isNotEmpty && carriesLabel(image, to);
      var labels = <LabelName>[];
      for (var name in image.labels) {
        if (name.name != from) {
          labels.add(name);
        } else if (to.isNotEmpty && !hasTarget) {
          labels.add(LabelName(name: to));
          hasTarget = true;
        }
      }
      image.labels = labels;
    }
  }
}

/// Takes the labels of the photographs of [answer] over into the album shown,
/// photograph by photograph, matched by name: the server's answer to a label
/// change, applied without fetching the album anew, so that the view keeps
/// its scroll offset and its filters.
void applyLabelsFrom(AlbumInfo shown, AlbumInfo answer) {
  var byName = <String, List<LabelName>>{
    for (var part in answer.parts)
      for (var image in imagesOfPart(part)) image.name: image.labels,
  };
  for (var part in shown.parts) {
    for (var image in imagesOfPart(part)) {
      var labels = byName[image.name];
      if (labels != null) {
        image.labels = [for (var name in labels) LabelName(name: name.name)];
      }
    }
  }
}

/// How many of the photographs of [parts] carry [label], and how many there
/// are: the state the label's check box shows in the "Label…" dialog.
({int carrying, int total}) labelCoverage(
  Iterable<AlbumPart> parts,
  String label,
) {
  var carrying = 0;
  var total = 0;
  for (var part in parts) {
    for (var image in imagesOfPart(part)) {
      total++;
      if (carriesLabel(image, label)) {
        carrying++;
      }
    }
  }
  return (carrying: carrying, total: total);
}

/// The headings a filtered view shows (issue #213, the author's rule): a
/// heading is shown exactly when at least one photograph under it is shown.
///
/// [shown] is the view's parts, the hidden photographs already taken out. A
/// subsection counts the photographs up to the next heading of any level; a
/// section counts everything up to the next section, its subsections
/// included. Headings keep their place; only empty ones drop. The server's
/// `FilteredHeadings` applies the same rule to what a share link is answered,
/// both pinned by `image-server/src/test/fixtures/filtered-headings.json`.
List<AlbumPart> visibleHeadings(List<AlbumPart> shown) {
  var result = <AlbumPart>[];
  for (var index = 0; index < shown.length; index++) {
    var part = shown[index];
    if (part is! Heading || _showsAnything(shown, index)) {
      result.add(part);
    }
  }
  return result;
}

bool _showsAnything(List<AlbumPart> parts, int index) {
  var section = headingLevel(parts[index] as Heading) != headingSubsection;
  for (var next = index + 1; next < parts.length; next++) {
    var part = parts[next];
    if (part is Heading) {
      if (!section || headingLevel(part) != headingSubsection) {
        return false;
      }
      continue;
    }
    if (imagesOfPart(part).isNotEmpty) {
      return true;
    }
  }
  return false;
}

/// The parts a view filtered to [label] shows, the empty headings dropped;
/// with no label only the headings are pruned, see [visibleHeadings].
List<AlbumPart> labelView(List<AlbumPart> parts, String? label) =>
    visibleHeadings([
      for (var part in parts)
        if (label == null || part is Heading || partCarriesLabel(part, label))
          part,
    ]);

final Expando<String> _labelFilters = Expando("labelFilter");

/// The label the given album is filtered by on the screen, `null` for none.
///
/// Kept beside the album object like its rating filter
/// ([AlbumInfo.minRating]): the grid and the viewer read the same album, so
/// paging in the viewer stays inside what the grid shows.
String? labelFilterOf(AlbumInfo? album) =>
    album == null ? null : _labelFilters[album];

/// Filters the given album to [label], `null` showing every photograph.
void setLabelFilter(AlbumInfo album, String? label) =>
    _labelFilters[album] = label;

/// Whether the viewer shows the given image under its album's label filter.
bool shownByLabelFilter(AbstractImage image) {
  var label = labelFilterOf(image.owner);
  return label == null || partCarriesLabel(image, label);
}
