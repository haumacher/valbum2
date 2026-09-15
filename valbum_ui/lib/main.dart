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
export 'attribution.dart';
export 'background.dart';
export 'caller.dart';
export 'camera_roll.dart';
export 'camera_roll_view.dart';
export 'client.dart';
export 'connectivity.dart';
export 'diagnostics.dart';
export 'group_view.dart';
export 'image_view.dart';
export 'invitation.dart';
export 'listing_view.dart';
export 'move_view.dart';
export 'offline.dart';
export 'photo_library.dart';
export 'rights.dart';
export 'routes.dart';
export 'settings.dart';
export 'share_session.dart';
export 'share_view.dart';
export 'thumbnails.dart';
export 'upload_progress.dart';
export 'urls.dart';

void main() {
  // Real paths instead of `/#/...`: the view of the app is in the URL, see
  // `routes.dart`. On the web the strategy strips the `<base href>` the
  // Flutter build writes into `index.html`, so the app works under the
  // server's context path; on every other platform the call does nothing.
  usePathUrlStrategy();

  runApp(const VAlbumApp());
}
