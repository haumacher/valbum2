/// Entry point of the VAlbum app.
///
/// The app itself lives in the libraries re-exported below; see the "Layout"
/// section of `README.md`.
library;

import 'package:flutter/material.dart';
import 'package:flutter_web_plugins/url_strategy.dart';

import 'app.dart';

export 'album_edit.dart';
export 'album_model.dart';
export 'album_view.dart';
export 'app.dart';
export 'background.dart';
export 'camera_roll.dart';
export 'camera_roll_view.dart';
export 'client.dart';
export 'group_view.dart';
export 'image_view.dart';
export 'listing_view.dart';
export 'offline.dart';
export 'photo_library.dart';
export 'routes.dart';
export 'settings.dart';
export 'thumbnails.dart';

void main() {
  // Real paths instead of `/#/...`: the view of the app is in the URL, see
  // `routes.dart`. On the web the strategy strips the `<base href>` the
  // Flutter build writes into `index.html`, so the app works under the
  // server's context path; on every other platform the call does nothing.
  usePathUrlStrategy();

  runApp(const VAlbumApp());
}
