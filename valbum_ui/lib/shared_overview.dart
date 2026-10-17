/// The overview of everything shared with a contact, issue #267.
///
/// A friend invited to several albums has one link per album. The server
/// knows who they are — a contact of the space, recognised by the credential
/// this browser keeps for the space (`contact_session.dart`) — and which
/// links they may open: every personal link addressed to them, and every open
/// personal link they opened. This page lists them, one tile per link, at one
/// stable address per space, `<context>/shared/`:
///
///  * a tile is what the link itself shows of its folder — its title, its
///    picture as the link may show it, who shared it, until when, and what it
///    allows — fetched with that link's own identity, so a picture the link
///    does not show is never asked for;
///  * opening a tile opens `<context>/shared/<link id>/`, a link session like
///    `/s/<token>/` (`share_session.dart`): the link's own view and nothing
///    more, two links on nested folders staying two. It is a page of its own,
///    and the browser's back button leads back here;
///  * a browser the server does not recognise is asked who this is, as a link
///    asks (`identify_view.dart`): a mailed code, a provider, a passkey or an
///    authenticator app. A proof naming a member signs the member in, and the
///    page leaves for the member's own application (issue #233).
library;

import 'package:flutter/material.dart';

import 'about.dart';
import 'action_guard.dart';
import 'client.dart';
import 'contact_session.dart';
import 'crop.dart';
import 'identify_view.dart';
import 'l10n/app_localizations.dart';
import 'listing_view.dart' show indexPictureTile;
import 'manage_view.dart' show dayOf;
import 'page_insets.dart';
import 'resource.dart';
import 'share_session.dart';
import 'sign_in_options.dart';
import 'urls.dart';

/// The overview page, with everything it asks before it shows the albums.
class SharedOverviewPage extends StatefulWidget {
  /// Where the overview was loaded.
  final OverviewUrl overview;

  /// Builds the client of the overview, recognised by [credential] (`null`
  /// for nobody) and asking as [shared] — [VAlbumClient.sharedAll] for the
  /// overview, a link's id for the pictures of its tile.
  final VAlbumClient Function(String? credential, String shared) clientOf;

  /// Where this browser keeps who it is in the space.
  final ContactCredentialStore contactStore;

  /// Opens another page, keeping this one in the history: an album.
  final void Function(String url) openPage;

  /// Leaves for another page: a provider's sign-in.
  final void Function(String url) openUrl;

  /// A proof named a member: the app signs this browser in as them and
  /// leaves for their application (issue #233).
  final Future<void> Function(PairResponse member) onMember;

  /// The location the page was loaded at, for the return of a sign-in with
  /// a provider (`#oidc=<code>`); `null` where there is none.
  final Uri? location;

  /// Rewrites the location without loading anything: takes the code out.
  final void Function(Uri uri) rewriteLocation;

  /// The name this device is stored under, for a proof that names a member.
  final String? deviceName;

  const SharedOverviewPage({
    super.key,
    required this.overview,
    required this.clientOf,
    required this.contactStore,
    required this.openPage,
    required this.openUrl,
    required this.onMember,
    required this.rewriteLocation,
    this.location,
    this.deviceName,
  });

  @override
  State<SharedOverviewPage> createState() => SharedOverviewPageState();
}

class SharedOverviewPageState extends State<SharedOverviewPage> {
  /// The credential this browser holds for the space, `null` for none.
  late String? _credential = widget.contactStore.read(widget.overview.dataUrl);

  /// The overview's client, as [_credential] says.
  late VAlbumClient _client = _clientNow();

  /// What the server answered, `null` while it is asked.
  SharedWithYou? _shared;

  /// The sign-in offer, `null` while nothing is asked.
  IdentifyRequired? _identify;

  /// A refusal to show over the sign-in offer.
  String? _identifyMessage;

  /// Why there is no overview, `null` while all is well.
  String? _refusal;

  /// Whether the page is leaving for the member's application.
  bool _leaving = false;

  String get _dataUrl => widget.overview.dataUrl;

  VAlbumClient _clientNow() =>
      widget.clientOf(_credential, VAlbumClient.sharedAll);

  @override
  void initState() {
    super.initState();
    _start();
  }

  Future<void> _start() async {
    await _returnFromSignIn();
    if (!mounted || _leaving) {
      return;
    }
    await _load();
  }

  /// Finishes a sign-in with a provider that came back here as
  /// `<overview>#oidc=<code>`, see `contact_session.dart`.
  Future<void> _returnFromSignIn() async {
    var location = widget.location;
    var code = location == null ? null : oidcCodeOf(location);
    if (code == null) {
      return;
    }
    widget.rewriteLocation(withoutFragment(location!));
    var pending = widget.contactStore.pendingSignIn(_dataUrl);
    widget.contactStore.dropSignIn(_dataUrl);
    if (pending == null) {
      return;
    }
    try {
      var answer = await _client.oidcExchange(
        OidcExchange(
          code: code,
          binding: pending.binding,
          deviceName: pending.deviceName,
        ),
      );
      await _arrived(answer, remember: pending.remember);
    } on VAlbumException catch (refusal) {
      _identifyMessage = refusal.message;
    } catch (error) {
      _identifyMessage = "$error";
    }
  }

  /// Asks the server what is shared with this browser's contact.
  Future<void> _load() async {
    try {
      var answer = await _client.sharedWithYou();
      if (mounted) {
        setState(() {
          _shared = answer;
          _identify = null;
          _identifyMessage = null;
          _refusal = null;
        });
      }
    } on VAlbumException catch (refusal) {
      if (!mounted) {
        return;
      }
      setState(() {
        var needs = refusal.identify;
        if (needs != null) {
          _identify = needs;
          _identifyMessage ??= refusal.status == 401 ? null : refusal.message;
        } else {
          _refusal = refusal.message;
        }
      });
    } catch (error) {
      if (mounted) {
        setState(() => _refusal = "$error");
      }
    }
  }

  /// Keeps a credential the server answered, or signs a member in.
  Future<void> _arrived(ContactCredential answer, {bool? remember}) async {
    var member = answer.member;
    if (member != null) {
      _leaving = true;
      await widget.onMember(member);
      return;
    }
    var credential = answer.credential;
    if (credential.isNotEmpty) {
      widget.contactStore.write(
        _dataUrl,
        credential,
        remember: remember ?? answer.remember,
      );
      _credential = credential;
      _client = _clientNow();
    }
  }

  Future<void> _identified(ContactCredential answer) async {
    await _arrived(answer);
    if (!mounted || _leaving) {
      return;
    }
    setState(() {
      _identify = null;
      _identifyMessage = null;
      _shared = null;
    });
    await _load();
  }

  void _signInStarted(OidcStarted started, bool remember, String deviceName) {
    widget.contactStore.keepSignIn(
      _dataUrl,
      PendingSignIn(
        binding: started.binding,
        remember: remember,
        deviceName: deviceName,
      ),
    );
    widget.openUrl(started.url);
  }

  /// "Not you? Switch person": the server first, then this browser forgets.
  Future<void> _switchPerson() async {
    if (_credential != null) {
      try {
        await _client.endOwnSession();
      } catch (_) {
        // Forgotten all the same: the visitor is never stuck.
      }
    }
    widget.contactStore.clear(_dataUrl);
    if (!mounted) {
      return;
    }
    setState(() {
      _credential = null;
      _client = _clientNow();
      _shared = null;
      _refusal = null;
    });
    await _load();
  }

  Future<void> _signOutOthers(BuildContext context) async {
    var messenger = ScaffoldMessenger.maybeOf(context);
    var l10n = AppLocalizations.of(context)!;
    // Refused offline, a refusal said in the server's words (#293).
    var done = await writeAction(context, () => _client.endOtherSessions());
    if (done == null || !mounted) {
      return;
    }
    setState(() => _shared?.otherSessions = 0);
    messenger?.showSnackBar(SnackBar(content: Text(l10n.otherSessionsEnded)));
  }

  Future<void> _signInOptions(BuildContext context) async {
    var signIns = _shared?.signIns;
    if (signIns == null) {
      return;
    }
    var answer = await showSignInOptions(
      context: context,
      client: _client,
      signIns: signIns,
    );
    if (answer != null && mounted) {
      setState(() => _shared?.signIns = answer);
    }
  }

  @override
  Widget build(BuildContext context) {
    var needs = _identify;
    if (needs != null) {
      return IdentifyScreen(
        key: ValueKey(needs),
        client: _client,
        identify: needs,
        message: _identifyMessage,
        onCredential: _identified,
        onSignInStarted: _signInStarted,
        onSwitchPerson: _credential == null ? null : _switchPerson,
        deviceName: widget.deviceName,
      );
    }
    var refusal = _refusal;
    if (refusal != null) {
      return SharePlainPage(
        key: const Key("shared-overview-refused"),
        icon: Icons.link_off,
        message: refusal,
      );
    }
    var shared = _shared;
    if (shared == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return SharedOverviewView(
      shared: shared,
      dataUrl: _dataUrl,
      clientOf: (link) => widget.clientOf(_credential, link),
      onOpen: (album) =>
          widget.openPage(sharedAlbumAddress(_dataUrl, album.link)),
      onSwitchPerson: (_) => _switchPerson(),
      onSignOutOthers: _signOutOthers,
      onSignInOptions: _signInOptions,
    );
  }
}

/// The albums shared with a contact, one tile per link, see the library.
class SharedOverviewView extends StatelessWidget {
  /// What the server answered.
  final SharedWithYou shared;

  /// The data URL of the space.
  final String dataUrl;

  /// The client a tile's picture is fetched with: the link's own view.
  final VAlbumClient Function(String link) clientOf;

  /// Opens an album.
  final void Function(SharedAlbum album) onOpen;

  /// "Not you? Switch person".
  final void Function(BuildContext context) onSwitchPerson;

  /// "Sign out others".
  final void Function(BuildContext context) onSignOutOthers;

  /// "Sign-in options…".
  final void Function(BuildContext context) onSignInOptions;

  const SharedOverviewView({
    super.key,
    required this.shared,
    required this.dataUrl,
    required this.clientOf,
    required this.onOpen,
    required this.onSwitchPerson,
    required this.onSignOutOthers,
    required this.onSignInOptions,
  });

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return Scaffold(
      key: const Key("shared-overview"),
      appBar: AppBar(
        title: Text(l10n.sharedWithYouTitle),
        actions: [_menu(context, l10n)],
      ),
      body: shared.albums.isEmpty
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  l10n.sharedWithYouEmpty,
                  key: const Key("shared-overview-empty"),
                  textAlign: TextAlign.center,
                ),
              ),
            )
          : GridView.extent(
              maxCrossAxisExtent: 240,
              childAspectRatio: 0.76,
              padding: pagePadding(context, const EdgeInsets.all(12)),
              mainAxisSpacing: 12,
              crossAxisSpacing: 12,
              children: [
                for (var album in shared.albums) _tile(context, l10n, album),
              ],
            ),
    );
  }

  Widget _menu(BuildContext context, AppLocalizations l10n) {
    var contact = shared.contact;
    return PopupMenuButton<void Function(BuildContext)>(
      key: const Key("shared-overview-menu"),
      onSelected: (action) => action(context),
      itemBuilder: (context) => [
        if (contact != null)
          PopupMenuItem<void Function(BuildContext)>(
            enabled: false,
            child: Text(
              l10n.signedInAsContact(contact.displayName),
              key: const Key("contact-line"),
            ),
          ),
        if (shared.otherSessions > 0)
          PopupMenuItem<void Function(BuildContext)>(
            key: const Key("sign-out-others"),
            value: onSignOutOthers,
            child: Text(l10n.otherSessionsSignOut(shared.otherSessions)),
          ),
        if (shared.signIns != null)
          PopupMenuItem<void Function(BuildContext)>(
            key: const Key("sign-in-options"),
            value: onSignInOptions,
            child: Text(l10n.signInOptionsEntry),
          ),
        PopupMenuItem<void Function(BuildContext)>(
          key: const Key("switch-person"),
          value: onSwitchPerson,
          child: Text(l10n.switchPerson),
        ),
        const PopupMenuDivider(),
        aboutMenuItem(l10n),
      ],
    );
  }

  Widget _tile(BuildContext context, AppLocalizations l10n, SharedAlbum album) {
    var folder = album.folder;
    var title = folder == null
        ? ""
        : folder.title.trim().isNotEmpty
            ? folder.title.trim()
            : folder.name;
    var theme = Theme.of(context);
    var rights = {for (var right in album.rights) right.name};
    return Card(
      key: Key("shared-album-${album.link}"),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => onOpen(album),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // The picture takes what the caption leaves, square: a larger
            // font never pushes the caption out of the card.
            Expanded(
              child: LayoutBuilder(
                builder: (context, constraints) => Center(
                  child: _picture(
                    album,
                    constraints.maxWidth < constraints.maxHeight
                        ? constraints.maxWidth
                        : constraints.maxHeight,
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 6, 8, 6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title.isEmpty ? l10n.sharedAlbumFallback : title,
                    style: theme.textTheme.titleSmall,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  if (album.sharedBy.isNotEmpty)
                    Text(
                      l10n.sharedAlbumFrom(album.sharedBy),
                      key: Key("shared-album-from-${album.link}"),
                      style: theme.textTheme.bodySmall,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          album.expires.isEmpty
                              ? l10n.expiresNever
                              : l10n.expiresOnDay(dayOf(album.expires)),
                          style: theme.textTheme.bodySmall,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      if (rights.contains("download"))
                        Tooltip(
                          message: l10n.rightLabelDownload,
                          child: Icon(
                            Icons.download,
                            size: 16,
                            key: Key("shared-album-download-${album.link}"),
                          ),
                        ),
                      if (rights.contains("contribute"))
                        Tooltip(
                          message: l10n.rightLabelContribute,
                          child: Icon(
                            Icons.add_photo_alternate_outlined,
                            size: 16,
                            key: Key("shared-album-contribute-${album.link}"),
                          ),
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _picture(SharedAlbum album, double size) {
    var picture = album.folder?.indexPicture;
    if (picture == null || picture.image.isEmpty) {
      return Container(
        width: size,
        height: size,
        color: Colors.blue.withValues(alpha: 0.08),
        child: Icon(
          Icons.photo_album_outlined,
          size: size / 3,
          color: Colors.blue,
        ),
      );
    }
    // The link's own view: its root is the shared folder, so the picture is
    // addressed below the data root, asked as that link.
    var client = clientOf(album.link);
    return indexPictureTile(
      client,
      coverImageUrl("${client.dataUrl}/${picture.image}", picture),
      picture,
      size,
      key: Key("shared-album-picture-${album.link}"),
    );
  }
}
