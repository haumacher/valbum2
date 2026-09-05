/// Entry point of the VAlbum app.
///
/// The app itself lives in the libraries re-exported below; see the "Layout"
/// section of `README.md`.
library;

import 'package:flutter/material.dart';

import 'app.dart';

export 'album_edit.dart';
export 'album_model.dart';
export 'album_view.dart';
export 'app.dart';
export 'client.dart';
export 'group_view.dart';
export 'image_view.dart';
export 'listing_view.dart';

void main() {
  runApp(const VAlbumApp());
}
