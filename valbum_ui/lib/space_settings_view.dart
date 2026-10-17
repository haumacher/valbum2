/// The settings of a space, made by its administrator (issue #297): its name,
/// whether visitors see its public photos, faces, the content search (issue
/// #257), the map a position opens, the time zone of photos that state none,
/// and the inbox.
///
/// Before, these were keys of `space.json` edited on the server by hand. Now
/// the administrator finds them in the server settings, each switch with one
/// sentence of what it does and what it costs. The server checks every value
/// and answers a refusal in its own words, which the page shows as they
/// arrive; nothing is stored then. A saved change takes effect at once.
///
/// What only the operator of the machine configures — ports, paths, the
/// embedder of the content search — stays in the configuration files.
library;

import 'package:flutter/material.dart';

import 'client.dart';
import 'l10n/app_localizations.dart';
import 'manage_view.dart' show refusalMessage;
import 'offline.dart';
import 'page_insets.dart';
import 'resource.dart';
import 'selectable.dart';

/// The key of the section in the server settings.
const Key spaceSettingsSectionKey = Key("settings.space");

/// The key of the button opening [SpaceSettingsScreen].
const Key spaceSettingsOpenKey = Key("settings.space.open");

/// The key of the name field.
const Key spaceSettingsNameKey = Key("spaceSettings.name");

/// The key of the switch letting visitors see the public photos.
const Key spaceSettingsAnonymousKey = Key("spaceSettings.anonymous");

/// The key of the face switch.
const Key spaceSettingsFacesKey = Key("spaceSettings.faces");

/// The key of the switch of the content search (issue #257).
const Key spaceSettingsContentKey = Key("spaceSettings.content");

/// The key of the line saying whether the embedder of the content search can
/// describe photos now.
const Key spaceSettingsContentStatusKey = Key("spaceSettings.content.status");

/// The key of the map field.
const Key spaceSettingsMapKey = Key("spaceSettings.mapUrl");

/// The key of the time zone field.
const Key spaceSettingsTimeZoneKey = Key("spaceSettings.timeZone");

/// The key of the inbox field.
const Key spaceSettingsInboxKey = Key("spaceSettings.inbox");

/// The key of the save button.
const Key spaceSettingsSaveKey = Key("spaceSettings.save");

/// The key of the line saying why the last save was refused.
const Key spaceSettingsProblemKey = Key("spaceSettings.problem");

/// Opens [SpaceSettingsScreen] for the space [client] talks to.
Future<void> openSpaceSettings(BuildContext context,
        {required VAlbumClient client}) =>
    Navigator.of(context, rootNavigator: true).push(
      MaterialPageRoute<void>(
        builder: (_) => SelectableArea(
          child: SpaceSettingsScreen(client: client),
        ),
      ),
    );

/// The section of the server settings leading to [SpaceSettingsScreen].
///
/// Shown to the administrator of the space only: the settings screen builds it
/// for that role and no other, and the server refuses everybody else anyway.
class SpaceSettingsSection extends StatelessWidget {
  final VAlbumClient client;

  const SpaceSettingsSection({super.key, required this.client});

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return Column(
      key: spaceSettingsSectionKey,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 24),
        const Divider(),
        const SizedBox(height: 8),
        Text(l10n.spaceSettingsHeading,
            style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        Text(l10n.spaceSettingsLead),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          key: spaceSettingsOpenKey,
          icon: const Icon(Icons.tune),
          label: Text(l10n.spaceSettingsOpen),
          onPressed: () => openSpaceSettings(context, client: client),
        ),
      ],
    );
  }
}

/// The settings of the space, see the library comment.
class SpaceSettingsScreen extends StatefulWidget {
  /// Whom to ask and tell.
  final VAlbumClient client;

  const SpaceSettingsScreen({super.key, required this.client});

  @override
  State<SpaceSettingsScreen> createState() => SpaceSettingsScreenState();
}

class SpaceSettingsScreenState extends State<SpaceSettingsScreen> {
  /// What the server answered last, `null` before its first answer.
  SpaceSettings? _settings;

  /// Why the settings could not be read, `null` while they could.
  String? _loadProblem;

  /// Why the last save was refused, in the server's words; `null` for none.
  String? _saveProblem;

  bool _saving = false;

  final TextEditingController _name = TextEditingController();
  final TextEditingController _mapUrl = TextEditingController();
  final TextEditingController _timeZone = TextEditingController();
  final TextEditingController _inbox = TextEditingController();

  bool _anonymous = false;
  bool _faces = false;
  bool _content = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _name.dispose();
    _mapUrl.dispose();
    _timeZone.dispose();
    _inbox.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() => _loadProblem = null);
    try {
      var settings = await widget.client.spaceSettings();
      if (mounted) {
        setState(() => _show(settings));
      }
    } catch (error) {
      if (mounted) {
        setState(() => _loadProblem = refusalMessage(error));
      }
    }
  }

  /// Fills the form with what the server answered.
  void _show(SpaceSettings settings) {
    _settings = settings;
    _name.text = settings.name;
    // The default map is shown as an empty field with the default as hint:
    // a space that never named a map keeps following the default.
    _mapUrl.text =
        settings.mapUrl == settings.defaultMapUrl ? "" : settings.mapUrl;
    _timeZone.text = settings.timeZone;
    _inbox.text = settings.inbox;
    _anonymous = settings.anonymous == AnonymousAccess.public;
    _faces = settings.faces;
    _content = settings.content == ContentSwitch.on;
  }

  /// Whether the server knows the switch of the content search: one of an
  /// earlier release answers none, and is then sent none.
  bool get _hasContent => _settings?.content != ContentSwitch.keep;

  Future<void> _save() async {
    var current = _settings;
    if (current == null || _saving) {
      return;
    }
    if (refuseWhileOffline(context)) {
      return;
    }
    var l10n = AppLocalizations.of(context)!;
    var messenger = ScaffoldMessenger.of(context);
    var wanted = SpaceSettings(
      name: _name.text.trim(),
      anonymous: _anonymous ? AnonymousAccess.public : AnonymousAccess.none,
      faces: _faces,
      mapUrl: _mapUrl.text.trim(),
      timeZone: _timeZone.text.trim(),
      inbox: _inbox.text.trim(),
      content: !_hasContent
          ? ContentSwitch.keep
          : _content
              ? ContentSwitch.on
              : ContentSwitch.off,
    );
    setState(() {
      _saving = true;
      _saveProblem = null;
    });
    try {
      var saved = await widget.client.saveSpaceSettings(wanted);
      if (!mounted) {
        return;
      }
      setState(() => _show(saved));
      messenger.showSnackBar(SnackBar(content: Text(l10n.spaceSettingsSaved)));
    } catch (error) {
      if (mounted) {
        setState(() => _saveProblem = refusalMessage(error));
      }
    } finally {
      if (mounted) {
        setState(() => _saving = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    var l10n = AppLocalizations.of(context)!;
    return Scaffold(
      appBar: AppBar(title: Text(l10n.spaceSettingsTitle)),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 640),
          child: _body(l10n),
        ),
      ),
    );
  }

  Widget _body(AppLocalizations l10n) {
    var settings = _settings;
    var problem = _loadProblem;
    if (settings == null) {
      return ListView(
        padding: pagePadding(context, const EdgeInsets.all(16)),
        children: [
          if (problem == null)
            Row(children: [
              const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              const SizedBox(width: 8),
              Text(l10n.spaceSettingsAsking),
            ])
          else ...[
            Text(l10n.spaceSettingsUnavailable(problem)),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerLeft,
              child: OutlinedButton(onPressed: _load, child: Text(l10n.retry)),
            ),
          ],
        ],
      );
    }
    var theme = Theme.of(context);
    var anonymousFixed = settings.anonymousFixed;
    var facesUnavailable = settings.facesUnavailable;
    var inboxFixed = settings.inboxFixed;
    return ListView(
      padding: pagePadding(context, const EdgeInsets.all(16)),
      children: [
        TextField(
          key: spaceSettingsNameKey,
          controller: _name,
          decoration: InputDecoration(
            labelText: l10n.spaceSettingsName,
            helperText: l10n.spaceSettingsNameHelp,
            helperMaxLines: 3,
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 16),
        SwitchListTile(
          key: spaceSettingsAnonymousKey,
          contentPadding: EdgeInsets.zero,
          title: Text(l10n.spaceSettingsAnonymous),
          subtitle: Text(anonymousFixed.isEmpty
              ? l10n.spaceSettingsAnonymousHelp
              : "${l10n.spaceSettingsAnonymousHelp}\n$anonymousFixed"),
          value: _anonymous,
          onChanged: anonymousFixed.isEmpty
              ? (value) => setState(() => _anonymous = value)
              : null,
        ),
        SwitchListTile(
          key: spaceSettingsFacesKey,
          contentPadding: EdgeInsets.zero,
          title: Text(l10n.spaceSettingsFaces),
          subtitle: Text(facesUnavailable.isEmpty
              ? l10n.spaceSettingsFacesHelp
              : "${l10n.spaceSettingsFacesHelp}\n$facesUnavailable"),
          value: _faces,
          onChanged: (value) => setState(() => _faces = value),
        ),
        if (_hasContent) ...[
          SwitchListTile(
            key: spaceSettingsContentKey,
            contentPadding: EdgeInsets.zero,
            title: Text(l10n.spaceSettingsContent),
            subtitle: Text(l10n.spaceSettingsContentHelp),
            value: _content,
            onChanged: (value) => setState(() => _content = value),
          ),
          Row(
            key: spaceSettingsContentStatusKey,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                settings.contentUnavailable.isEmpty
                    ? Icons.check_circle_outline
                    : Icons.info_outline,
                size: 18,
                color: settings.contentUnavailable.isEmpty
                    ? theme.colorScheme.primary
                    : theme.colorScheme.onSurfaceVariant,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  settings.contentUnavailable.isEmpty
                      ? l10n.spaceSettingsContentReady
                      : l10n.spaceSettingsContentUnavailable(
                          settings.contentUnavailable),
                  style: theme.textTheme.bodySmall,
                ),
              ),
            ],
          ),
        ],
        const SizedBox(height: 16),
        TextField(
          key: spaceSettingsMapKey,
          controller: _mapUrl,
          keyboardType: TextInputType.url,
          autocorrect: false,
          decoration: InputDecoration(
            labelText: l10n.spaceSettingsMap,
            hintText: settings.defaultMapUrl,
            helperText: l10n.spaceSettingsMapHelp(
                settings.defaultMapUrl, "{lon}", "{lat}"),
            helperMaxLines: 4,
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 16),
        TextField(
          key: spaceSettingsTimeZoneKey,
          controller: _timeZone,
          autocorrect: false,
          decoration: InputDecoration(
            labelText: l10n.spaceSettingsTimeZone,
            hintText: settings.serverTimeZone,
            helperText: l10n.spaceSettingsTimeZoneHelp(settings.serverTimeZone),
            helperMaxLines: 4,
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 16),
        TextField(
          key: spaceSettingsInboxKey,
          controller: _inbox,
          enabled: inboxFixed.isEmpty,
          autocorrect: false,
          decoration: InputDecoration(
            labelText: l10n.spaceSettingsInbox,
            helperText:
                inboxFixed.isEmpty ? l10n.spaceSettingsInboxHelp : inboxFixed,
            helperMaxLines: 4,
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 24),
        Align(
          alignment: Alignment.centerLeft,
          child: FilledButton.icon(
            key: spaceSettingsSaveKey,
            onPressed: _saving ? null : _save,
            icon: const Icon(Icons.save),
            label: Text(l10n.save),
          ),
        ),
        if (_saveProblem != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(
              l10n.spaceSettingsRefused(_saveProblem!),
              key: spaceSettingsProblemKey,
              style: TextStyle(color: theme.colorScheme.error),
            ),
          ),
      ],
    );
  }
}
