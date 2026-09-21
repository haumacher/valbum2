// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for German (`de`).
class AppLocalizationsDe extends AppLocalizations {
  AppLocalizationsDe([String locale = 'de']) : super(locale);

  @override
  String get appTitle => 'Virtuelles Fotoalbum';

  @override
  String get dismiss => 'Schließen';

  @override
  String get cancel => 'Abbrechen';

  @override
  String get save => 'Speichern';

  @override
  String get remove => 'Entfernen';

  @override
  String get withdraw => 'Zurückziehen';

  @override
  String get done => 'Fertig';

  @override
  String get askingServer => 'Anfrage an den Server...';

  @override
  String get serverSettingsAction => 'Servereinstellungen...';

  @override
  String get signInRequiredTitle => 'Anmeldung erforderlich';

  @override
  String get signInRequiredNoServer =>
      'Diese App ist noch mit keinem Server verbunden.';

  @override
  String get serverScreenTitle => 'Album-Server';

  @override
  String get serverUrlHelp =>
      'Die Adresse, unter der der Album-Server erreichbar ist, so wie man ihn in einem Browser öffnen würde, z. B. http://nas.local:8080/valbum/. Wenn der Server mehrere Unterordner enthält, enthält die Adresse den Unterordner: https://host/valbum/<Unterordner>/.';

  @override
  String get serverLineNoServer =>
      'Dieser Browser hat noch keine Verbindung zu einem Server hergestellt.';

  @override
  String serverLineTalksTo(String server) {
    return 'Dieser Browser kommuniziert mit dem $server';
  }

  @override
  String get serverUrlLabel => 'Server-URL';

  @override
  String get testConnection => 'Verbindung testen';

  @override
  String get forgetThisServer => 'Diesen Server vergessen';

  @override
  String get useLoadedServer =>
      'Den Server verwenden, von dem diese App geladen wurde';

  @override
  String get contactingServer => 'Verbindung zum Server wird hergestellt...';

  @override
  String get signInHeading => 'Anmelden';

  @override
  String get signInCodeExplanation =>
      'Geben Sie den Code ein, den der Server beim Start angezeigt hat, oder einen Code von einem Ihrer Geräte oder einen Wiederherstellungscode, den Sie von Ihrem Administrator erhalten haben.';

  @override
  String get signInNoServer =>
      'Geben Sie oben einen Server an, bevor Sie sich anmelden.';

  @override
  String get signOut => 'Abmelden';

  @override
  String get signedOutMessage =>
      'Dieses Gerät meldet sich nicht mehr beim Server an.';

  @override
  String get userNameHelp =>
      'Tragen Sie hier Ihren Namen ein; dieser wird anderen angezeigt und dient als Bildunterschrift für Ihre Fotos.';

  @override
  String get codeRequiredRefusal =>
      'Geben Sie den Code ein, mit dem dieses Gerät angemeldet wird.';

  @override
  String get signInSucceeded => 'Die Anmeldung war erfolgreich.';

  @override
  String get signingIn => 'Anmeldung läuft...';

  @override
  String get yourName => 'Ihr Name';

  @override
  String get yourNameHelp =>
      'So sehen dich die anderen Nutzer auf diesem Server.';

  @override
  String get userNameLabel => 'Benutzername';

  @override
  String get deviceNameLabel => 'Gerätename';

  @override
  String get signInCodeLabel => 'Anmeldecode';

  @override
  String get signInCodeHelp =>
      'Beim Start des Servers, über „Meine Geräte“ auf einem Gerät, auf dem Sie bereits angemeldet sind, von Ihrem Administrator oder über Ihren Backup-Code.';

  @override
  String get scanCode => 'Code scannen';

  @override
  String otherServerRefusal(String server) {
    return 'Dieser Code gilt für den $server, nicht für den Server, von dem diese Seite stammt. Öffnen Sie diesen Server und melden Sie sich dort an.';
  }

  @override
  String get notADeviceCode => 'Dies ist kein Gerätecode.';

  @override
  String invitationMasked(String token) {
    return 'Einladung $token';
  }

  @override
  String get notThisOne => 'Nicht diese hier';

  @override
  String get askingAboutInvitation =>
      'Der Server wird gerade zu dieser Einladung befragt...';

  @override
  String get invitationUnknown =>
      'Dieser Server kennt diese Einladung nicht. Fordern Sie eine neue an.';

  @override
  String get invitationUnknownHere =>
      'Dieser Server kennt diese Einladung nicht. Fordern Sie eine neue an oder geben Sie die einfache Serveradresse ein.';

  @override
  String invitationHeadlineWithRole(String invitedBy, String may) {
    return '$invitedBy hat dich zu diesem Album-Server eingeladen: $may.';
  }

  @override
  String invitationHeadlinePlain(String invitedBy) {
    return '$invitedBy hat dich zu diesem Album-Server eingeladen.';
  }

  @override
  String get peopleHeading => 'Menschen';

  @override
  String get inviteExplanation =>
      'Eine Einladung ist ein einmalig nutzbarer Link, über den ein Konto auf diesem Server erstellt wird. Senden Sie ihn an die Person, für die er bestimmt ist, und an niemanden sonst.';

  @override
  String get inviteAction => 'Einladen…';

  @override
  String get signedInOnThisDevice => 'Auf diesem Gerät angemeldet';

  @override
  String signedInAsUser(String user) {
    return 'Angemeldet als $user';
  }

  @override
  String roleLine(String role) {
    return 'Rolle: $role';
  }

  @override
  String deviceLine(String device) {
    return 'Gerät: $device';
  }

  @override
  String spaceLine(String space) {
    return 'Bereich: $space';
  }

  @override
  String get notSignedIn => 'Nicht angemeldet';

  @override
  String get deviceUnknownToServer =>
      'Dieser Server kennt dieses Gerät nicht. Melden Sie sich bitte erneut an.';

  @override
  String identityUnknown(String problem) {
    return 'Der Server hat nicht angegeben, um welches Gerät es sich handelt: $problem';
  }

  @override
  String get libraryOwner => 'der Bibliotheksbesitzer';

  @override
  String get wholeLibrary => 'die gesamte Bibliothek';

  @override
  String get guestLibraryNotice =>
      'Gast: Deine Bibliothek besteht aus dem, was andere mit dir teilen.';

  @override
  String get cacheHeading => 'Cache';

  @override
  String get cacheExplanation =>
      'Bereits angesehene Alben und Miniaturansichten werden auf diesem Gerät gespeichert, sodass die Bibliothek auch dann durchsucht werden kann, wenn der Server nicht erreichbar ist.';

  @override
  String currentlyCached(String size) {
    return 'Derzeit im Cache gespeichert: $size';
  }

  @override
  String get currentlyCachedUnknown => 'Derzeit im Cache gespeichert: ...';

  @override
  String get clearCache => 'Cache leeren';

  @override
  String get clearCacheTitle => 'Cache leeren?';

  @override
  String get clearCacheQuestion =>
      'Alle für das Offline-Surfen gespeicherten Daten werden gelöscht. Sie werden beim nächsten Zugriff auf den Server erneut abgerufen.';

  @override
  String get clear => 'Leeren';

  @override
  String cacheCleared(String size) {
    return 'Cache geleert, $size freigegeben.';
  }

  @override
  String get diagnosticsHeading => 'Diagnostik';

  @override
  String get diagnosticsLead =>
      'Was diese App im Netzwerk getan hat – fügen Sie dies in einen Fehlerbericht ein.';

  @override
  String get diagnosticsEmpty =>
      'Es wurde noch nichts protokolliert. Testen Sie die Verbindung oder durchsuchen Sie das Album – die Anfragen der App an den Server werden hier angezeigt.';

  @override
  String get diagnosticsCopied =>
      'Das Diagnoseprotokoll befindet sich in der Zwischenablage.';

  @override
  String get copy => 'Kopieren';

  @override
  String connectionSignedInAsOn(String user, String device) {
    return 'Angemeldet als $user auf $device';
  }

  @override
  String get connectionNoSignInNeeded =>
      'Nicht angemeldet – für diesen Server ist keine Anmeldung erforderlich';

  @override
  String get connectionSignInForEverything =>
      'Nicht angemeldet – dieser Server zeigt ohne Anmeldung keine Informationen an';

  @override
  String get connectionSignInForChanges =>
      'Nicht angemeldet – für Änderungen ist eine Anmeldung erforderlich';

  @override
  String get notAlbumData =>
      'Die Antwort lautet „keine Albumdaten“ – kein VAlbum-Server?';

  @override
  String get albumServerReached => 'Album-Server erreicht';

  @override
  String get albumServerNeedsSignIn =>
      'Der Album-Server wurde erreicht – es ist eine Anmeldung erforderlich, bevor Inhalte angezeigt werden';

  @override
  String get albumServerRefusesThisDevice =>
      'Der Album-Server wurde erreicht – er lehnt die Anmeldung mit diesem Gerät ab';

  @override
  String get deviceNameThisBrowser => 'Dieser Browser';

  @override
  String get deviceNameAndroid => 'Android-Smartphone';

  @override
  String get deviceNameIPhone => 'iPhone';

  @override
  String get deviceNameMac => 'Mac';

  @override
  String get deviceNameWindows => 'Windows-PC';

  @override
  String get deviceNameLinux => 'Linux-PC';

  @override
  String get deviceNameOther => 'Mein Gerät';

  @override
  String get firstScreenTitle => 'Wo ist dein Album?';

  @override
  String get firstScreenLead =>
      'Geben Sie die Adresse Ihres Album-Servers ein oder fügen Sie den Einladungslink ein, den Sie erhalten haben. Falls Ihnen jemand einen QR-Code gezeigt hat, scannen Sie diesen ein.';

  @override
  String get firstScreenNoServer =>
      'Das ist keine Serveradresse. Es sieht aus wie http://nas.local:8080/valbum/.';

  @override
  String get serverAddressOrLink => 'Serveradresse oder Link';

  @override
  String get continueAction => 'Weiter';

  @override
  String albumServerLine(String server) {
    return 'Album-Server: $server';
  }

  @override
  String get anotherServer => 'Ein anderer Server';

  @override
  String get openWithoutSigningIn => 'Ohne Anmeldung öffnen';

  @override
  String get devicesHeading => 'Meine Geräte';

  @override
  String get devicesLead =>
      'Jedes Gerät, auf dem Sie sich angemeldet haben, verfügt über ein eigenes Token. Wenn Sie ein Gerät hier entfernen, verliert dieses Token seine Gültigkeit; das Gerät muss sich erneut anmelden.';

  @override
  String get noDeviceSignedIn => 'Es ist kein Gerät angemeldet.';

  @override
  String thisDeviceNamed(String name) {
    return '$name (dieses Gerät)';
  }

  @override
  String get pairedAtUnknownTime => 'Zu einem unbekannten Zeitpunkt gekoppelt';

  @override
  String pairedOn(String day) {
    return 'Gekoppelt am $day';
  }

  @override
  String get signOutHere => 'Hier abmelden';

  @override
  String get addDevice => 'Gerät hinzufügen…';

  @override
  String get noBackupCode =>
      'Sicherheitscode: keiner. Ohne einen solchen sind Sie nach der Abmeldung von Ihrem letzten Gerät auf Ihren Administrator angewiesen.';

  @override
  String backupCodeMade(String day) {
    return 'Sicherheitscode: erstellt am $day. Bewahren Sie ihn sicher auf; wenn Sie einen neuen erstellen, wird dieser ungültig.';
  }

  @override
  String get createBackupCode => 'Sicherheitscode erstellen…';

  @override
  String get createNewBackupCode => 'Neuen Sicherheitscode erstellen…';

  @override
  String get withdrawBackupCodeTitle =>
      'Sollen Sie den Backup-Code widerrufen?';

  @override
  String get withdrawBackupCodeMessage =>
      'Der Code, den Sie notiert haben, funktioniert nicht mehr. Wenn Sie sich von Ihrem letzten Gerät abmelden, sind Sie fortan auf einen Wiederherstellungscode Ihres Administrators angewiesen.';

  @override
  String get backupCodeTitle => 'Sicherheitscode';

  @override
  String get backupCodeAdvice =>
      'Schreiben Sie ihn auf und bewahren Sie ihn an einem sicheren Ort auf – beispielsweise in einem Passwort-Manager oder in einer Schublade. Er läuft nie ab, funktioniert nur einmal und meldet ein Gerät in Ihrem Namen an; geben Sie ihn daher niemandem weiter. Dies ist das einzige Mal, dass er angezeigt wird.';

  @override
  String get backupCodeNoExpiry =>
      'Dieser Code verfällt nicht. Er kann einmal verwendet werden.';

  @override
  String get addDeviceTitle => 'Gerät hinzufügen';

  @override
  String recoveryCodeTitle(String user) {
    return 'Wiederherstellungscode für $user';
  }

  @override
  String get deviceCodeAdvice =>
      'Geben Sie diesen Code innerhalb von 10 Minuten auf dem anderen Gerät ein. Dadurch wird dieses Gerät unter Ihrem Namen angemeldet – geben Sie ihn niemals an andere weiter.';

  @override
  String recoveryCodeAdvice(String user) {
    return 'Geben Sie diesen Code innerhalb von 10 Minuten an $user weiter; damit kann sich dieser auf einem seiner Geräte als er selbst anmelden. Er funktioniert nur einmal – geben Sie ihn an niemanden sonst weiter.';
  }

  @override
  String get deviceCodeQrAdvice =>
      'Oder scannen Sie diesen Code auf dem anderen Gerät unter „Anmelden“.';

  @override
  String get deviceCodeLinkAdvice =>
      'Oder senden Sie diesen Link an das andere Gerät und öffnen Sie ihn in der App:';

  @override
  String get deviceCodeQrSemantics => 'Gerätecode als QR-Code';

  @override
  String get copyTheLink => 'Link kopieren';

  @override
  String get linkOnClipboard => 'Der Link befindet sich in der Zwischenablage.';

  @override
  String get codeExpired => 'Dieser Code ist abgelaufen.';

  @override
  String codeExpiresIn(String time) {
    return 'Läuft in $time ab';
  }

  @override
  String get newCode => 'Neuer Code';

  @override
  String deviceJoined(String name) {
    return '$name wurde verbunden.';
  }

  @override
  String get signOutThisDeviceTitle => 'Dieses Gerät auschecken?';

  @override
  String get signOutThisDeviceMessage =>
      'Dieses Gerät vergisst seine Anmeldung und kommuniziert wieder anonym mit dem Server. Sie können sich jederzeit erneut anmelden.';

  @override
  String removeDeviceTitle(String name) {
    return 'Das Gerät $name entfernen?';
  }

  @override
  String removeDeviceMessage(String name) {
    return '$name ist nicht mehr angemeldet. Es muss sich erneut anmelden, bevor es Änderungen vornehmen kann.';
  }

  @override
  String lastDeviceWarning(String ways) {
    return 'Dies ist Ihr einziges angemeldetes Gerät. Um sich erneut anzumelden, haben Sie noch $ways.';
  }

  @override
  String waysOrLast(String rest, String last) {
    return '$rest oder $last';
  }

  @override
  String get wayBackupCode => 'Ihr Backup-Code';

  @override
  String get wayRecoveryFromAdmin =>
      'ein Wiederherstellungscode von Ihrem Administrator';

  @override
  String get wayRecoveryFromOtherAdmin =>
      'ein Wiederherstellungscode von einem anderen Administrator';

  @override
  String get wayServerRestart =>
      'ein Neustart des Servers, wodurch ein neuer Anmeldecode ausgegeben wird';

  @override
  String get maybeLastDeviceWarning =>
      'Möglicherweise ist dies Ihr einziges angemeldetes Gerät, und der Server konnte nicht abgefragt werden. In diesem Fall benötigen Sie einen Wiederherstellungscode von Ihrem Administrator, Ihren Ersatzcode oder einen Neustart des Servers, um sich wieder anzumelden.';

  @override
  String get permissionMayHeading => 'Mai';

  @override
  String get permissionSeesHeading => 'Siehe';

  @override
  String get mayShareLinksSwitch => 'Links können geteilt werden';

  @override
  String get mayShareLinksExplanation =>
      'Es können Links verteilt werden, die ein Album für den jeweiligen Empfänger öffnen.';

  @override
  String get roleExplanationEdit =>
      'Darf Alben erstellen, diese bearbeiten und Fotos hinzufügen.';

  @override
  String get roleExplanationContribute =>
      'Darf Fotos zu den Alben hinzufügen, darf aber nichts ändern.';

  @override
  String get roleExplanationView => 'Darf sich die Alben ansehen, mehr nicht.';

  @override
  String get clearanceExplanationAll =>
      'Er sieht jedes Bild, auch die privaten.';

  @override
  String get clearanceExplanationNonPrivate =>
      'Er sieht jedes Bild, das nicht als privat markiert ist.';

  @override
  String get clearanceExplanationPublic =>
      'Es werden nur die als „öffentlich“ gekennzeichneten Bilder angezeigt.';

  @override
  String permissionDialogTitle(String user) {
    return 'Was $user tun darf';
  }

  @override
  String get usersHeading => 'Benutzer';

  @override
  String get usersLead =>
      'Alle Personen, die ein Konto auf diesem Server haben, sowie die Funktionen, die sie dort nutzen können, und die Inhalte, die sie dort sehen können.';

  @override
  String get recoveryCodeTooltip => 'Wiederherstellungscode';

  @override
  String get changePermissionTooltip => 'Ändern Sie, was sie tun dürfen';

  @override
  String removeUserTitle(String user) {
    return '$user entfernen?';
  }

  @override
  String get removeUserMessage =>
      'Die Geräte werden abgemeldet; die Fotos und der Name darauf bleiben erhalten.';

  @override
  String get invitedPending => 'Eingeladen (ausstehend)';

  @override
  String invitedForPending(String recipient) {
    return 'Eingeladen für $recipient (ausstehend)';
  }

  @override
  String get withdrawInvitationTitle => 'Diese Einladung zurückziehen?';

  @override
  String get withdrawPendingUserMessage =>
      'Der Link funktioniert nicht mehr, und der Platz, den dieser Nutzer belegt hatte, wird freigegeben.';

  @override
  String get withdrawInvitationMessage =>
      'Der Link funktioniert nicht mehr. Wer die Einladung bereits angenommen hat, behält sein Konto.';

  @override
  String invitedByUser(String user) {
    return 'eingeladen von $user';
  }

  @override
  String sinceDay(String day) {
    return 'seit $day';
  }

  @override
  String librarySpace(String space) {
    return 'Bibliothek: $space';
  }

  @override
  String deviceCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Geräte',
      one: '1 Gerät',
    );
    return '$_temp0';
  }

  @override
  String invitedForRecipient(String recipient) {
    return 'Eingeladen für $recipient';
  }

  @override
  String get openInvitationsHeading => 'Offene Einladungen';

  @override
  String get noOpenInvitations => 'Es wartet keine Einladung auf die Annahme.';

  @override
  String invitationPermissionBy(String permission, String user) {
    return '$permission – eingeladen von $user';
  }

  @override
  String forRecipient(String recipient) {
    return 'für $recipient';
  }

  @override
  String get expiresNever => 'Gültigkeitsdauer: unbegrenzt';

  @override
  String expiredOnDay(String day) {
    return 'am $day ablief';
  }

  @override
  String expiresOnDay(String day) {
    return 'abläuft nach $day';
  }

  @override
  String permissionSentence(String doing, String seeing, String sharing) {
    return '$doing; $seeing; $sharing.';
  }

  @override
  String get permissionDoingAdmin => 'Sie verwalten diesen Server';

  @override
  String get permissionDoingEdit =>
      'Sie dürfen jedes Album dieses Bereichs bearbeiten';

  @override
  String get permissionDoingContribute =>
      'Sie dürfen Fotos in diesen Bereich einfügen';

  @override
  String get permissionDoingView => 'Sie dürfen diesen Bereich betrachten';

  @override
  String get permissionDoingNone => 'Sie sind nicht angemeldet';

  @override
  String get permissionSeeingAll => 'Sie sehen alle Bilder';

  @override
  String get permissionSeeingNonPrivate =>
      'Du siehst alle Bilder außer den privaten.';

  @override
  String get permissionSeeingPublic => 'Sie sehen die öffentlichen Bilder';

  @override
  String get permissionSharingMay => 'Sie dürfen Links teilen';

  @override
  String get permissionSharingMayNot => 'Sie dürfen keine Links teilen';

  @override
  String permissionPhrase(String role, String clearance, String sharing) {
    return '$role — $clearance — $sharing';
  }

  @override
  String get permissionPhraseMayShare => 'Links teilen darf';

  @override
  String get permissionPhraseNoLinks => 'keine Links';

  @override
  String get roleWordAdmin => 'diesen Server verwaltet';

  @override
  String get roleWordEdit => 'die Alben bearbeiten darf';

  @override
  String get roleWordContribute => 'Fotos hinzufügen kann';

  @override
  String get roleWordView => 'kann so aussehen';

  @override
  String get roleWordUnknown => 'unbekannte Rolle';

  @override
  String get roleWordYouAdmin => ', die diesen Server verwaltet:';

  @override
  String get roleWordYouEdit => 'dürfen Sie die Alben bearbeiten';

  @override
  String get roleWordYouContribute => 'dürfen Sie Fotos hinzufügen';

  @override
  String get roleWordYouView => 'dürfen Sie sich die Alben ansehen';

  @override
  String get clearanceWordAll => 'alle Bilder sieht';

  @override
  String get clearanceWordNonPrivate =>
      'dass dieser jemand alle Bilder außer den privaten sieht';

  @override
  String get clearanceWordPublic => 'die öffentlichen Bilder sieht';

  @override
  String get serverUrlEmpty =>
      'Geben Sie die URL des Album-Servers ein, z. B. http://nas.local:8080/valbum/.';

  @override
  String get serverUrlInvalid =>
      'Das ist keine Serveradresse. Es sieht aus wie http://nas.local:8080/valbum/.';

  @override
  String get shareLinkRefusal =>
      'Dies ist ein Link zu einem freigegebenen Album, keine Anmeldung. Öffne ihn in einem Browser, um zu sehen, was für dich freigegeben wurde.';
}
