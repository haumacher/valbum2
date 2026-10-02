/// What the app is, and where it comes from: the About dialog, issue #187.
///
/// Offered at the end of every main menu — the listing, the album, the inbox,
/// the viewer — and therefore inside a share link too, where the album's
/// floating menu is what a visitor has, and on the bare pages a dead link
/// ends on ([aboutButton]). A visitor who received a link is exactly who
/// asks "what is this?".
///
/// Flutter's own [AboutDialog] is the frame: it is localized by the Material
/// localizations, and its "View licenses" page lists the licences of every
/// package the app is built from, which an About dialog owes its reader.
/// VAlbum itself states no licence of its own, so there is no legalese line.
library;

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import 'l10n/app_localizations.dart';

/// The app's name, a name and therefore the same in every language.
const String appName = "VAlbum";

/// Where the source code, the documentation and the issues live.
const String githubUrl = "https://github.com/haumacher/valbum2";

/// The version the release build was made for, empty where none was given.
///
/// The release workflow passes `--dart-define=VALBUM_VERSION=<x.y.z>` to
/// `flutter build web` and `flutter build apk` — the version of the tag, the
/// same the server package and the APK's `versionName` carry. A build made
/// without it (a developer's, CI's, a desktop or iOS build) knows no honest
/// version — `pubspec.yaml`'s `1.0.0` is a placeholder no release ever
/// shipped — and the dialog then shows no version line at all.
const String buildVersion = String.fromEnvironment("VALBUM_VERSION");

/// The version the dialog shows; a test replaces it.
String shownVersion = buildVersion;

/// Opens [url] outside the app; a test replaces it.
Future<bool> Function(Uri url) openExternalUrl =
    (url) => launchUrl(url, mode: LaunchMode.externalApplication);

/// Shows the About dialog over [context].
void showAbout(BuildContext context) {
  var l10n = AppLocalizations.of(context)!;
  showAboutDialog(
    context: context,
    applicationName: appName,
    applicationVersion:
        shownVersion.isEmpty ? null : l10n.aboutVersion(shownVersion),
    applicationIcon: const Icon(Icons.photo_library_outlined, size: 48),
    children: [
      const SizedBox(height: 16),
      Text(l10n.aboutDescription, key: const Key("about-description")),
      const SizedBox(height: 16),
      Text(l10n.aboutSourceCode),
      Align(
        alignment: AlignmentDirectional.centerStart,
        child: TextButton.icon(
          key: const Key("about-github"),
          style: TextButton.styleFrom(padding: EdgeInsets.zero),
          onPressed: () => openExternalUrl(Uri.parse(githubUrl)),
          icon: const Icon(Icons.open_in_new, size: 18),
          // The address itself, so that it can be read and typed elsewhere.
          label: const Text(githubUrl),
        ),
      ),
    ],
  );
}

/// The menu entry opening [showAbout], the last of every main menu.
PopupMenuItem<void Function(BuildContext)> aboutMenuItem(
  AppLocalizations l10n,
) =>
    PopupMenuItem<void Function(BuildContext)>(
      key: const Key("about"),
      value: showAbout,
      child: Row(
        children: [
          const Padding(
            padding: EdgeInsets.only(right: 16),
            child: Icon(Icons.info_outline, color: Colors.blueAccent),
          ),
          Flexible(child: Text(l10n.aboutMenuEntry)),
        ],
      ),
    );

/// The way to [showAbout] on a page that has no menu.
Widget aboutButton(BuildContext context) => TextButton.icon(
      key: const Key("about"),
      onPressed: () => showAbout(context),
      icon: const Icon(Icons.info_outline),
      label: Text(AppLocalizations.of(context)!.aboutMenuEntry),
    );
