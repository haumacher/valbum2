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
      'Die Adresse des Album-Servers, wie Sie sie im Browser öffnen würden, z. B. \'http://nas.local:8080/valbum/\'. Hat der Server mehrere Bereiche, enthält die Adresse den Bereich: \'https://host/valbum/<Bereich>/\'.';

  @override
  String get serverLineNoServer =>
      'Dieser Browser hat noch keine Verbindung zu einem Server hergestellt.';

  @override
  String serverLineTalksTo(String server) {
    return 'Dieser Browser ist mit $server verbunden';
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
  String get signedOutMessage => 'Dieses Gerät ist abgemeldet.';

  @override
  String get userNameHelp =>
      'Ihr Name in diesem Bereich: So sehen andere Sie, und Ihre Fotos werden Ihnen unter diesem Namen zugeordnet.';

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
      'Unter diesem Namen sehen andere Sie auf diesem Server.';

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
    return 'Dieser Code gilt für $server, nicht für den Server, von dem diese Seite stammt. Öffnen Sie jenen Server und melden Sie sich dort an.';
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
      'Die Einladung wird beim Server geprüft...';

  @override
  String get invitationUnknown =>
      'Dieser Server kennt diese Einladung nicht. Fordern Sie eine neue an.';

  @override
  String get invitationUnknownHere =>
      'Dieser Server kennt diese Einladung nicht. Fordern Sie eine neue an, oder geben Sie nur die Serveradresse ein.';

  @override
  String invitationHeadlineWithRole(String invitedBy, String role) {
    return '$invitedBy hat Sie als $role zu diesem Album-Server eingeladen.';
  }

  @override
  String invitationHeadlinePlain(String invitedBy) {
    return '$invitedBy hat Sie zu diesem Album-Server eingeladen.';
  }

  @override
  String get peopleHeading => 'Mitglieder';

  @override
  String get inviteExplanation =>
      'Ein einmalig nutzbarer Link, über den ein Konto in diesem Bereich erstellt wird. Senden Sie ihn nur an die Person, für die er bestimmt ist.';

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
      'Gast: Ihre Bibliothek besteht aus dem, was andere mit Ihnen teilen.';

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
      'Alles, was für die Offline-Ansicht gespeichert wurde, wird gelöscht. Es wird beim nächsten Kontakt mit dem Server neu geladen.';

  @override
  String get clear => 'Leeren';

  @override
  String cacheCleared(String size) {
    return 'Cache geleert, $size freigegeben.';
  }

  @override
  String get diagnosticsHeading => 'Diagnose';

  @override
  String get diagnosticsLead =>
      'Die Probleme, auf die diese App gestoßen ist – kopieren Sie sie in einen Fehlerbericht.';

  @override
  String get diagnosticsEmpty => 'Keine Probleme aufgezeichnet.';

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
      'Nicht angemeldet – dieser Server zeigt ohne Anmeldung nichts an';

  @override
  String get connectionSignInForChanges =>
      'Nicht angemeldet – für Änderungen ist eine Anmeldung erforderlich';

  @override
  String get notAlbumData =>
      'Die Antwort enthält keine Albumdaten – ist das kein VAlbum-Server?';

  @override
  String get albumServerReached => 'Album-Server erreicht';

  @override
  String get albumServerNeedsSignIn =>
      'Album-Server erreicht – er zeigt erst nach einer Anmeldung etwas an';

  @override
  String get albumServerRefusesThisDevice =>
      'Album-Server erreicht – er lehnt die Anmeldung dieses Geräts ab';

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
  String get firstScreenTitle => 'Wo ist Ihr Album?';

  @override
  String get firstScreenLead =>
      'Geben Sie die Adresse Ihres Album-Servers ein oder fügen Sie den Einladungslink ein, den Sie erhalten haben. Falls Ihnen jemand einen QR-Code gezeigt hat, scannen Sie diesen ein.';

  @override
  String get firstScreenNoServer =>
      'Das ist keine Serveradresse. Eine Serveradresse sieht so aus: \'http://nas.local:8080/valbum/\'.';

  @override
  String get serverAddressOrLink => 'Serveradresse oder Link';

  @override
  String get continueAction => 'Weiter';

  @override
  String albumServerLine(String server) {
    return 'Album-Server: $server';
  }

  @override
  String get anotherServer => 'Anderer Server';

  @override
  String get openWithoutSigningIn => 'Ohne Anmeldung öffnen';

  @override
  String get devicesHeading => 'Meine Geräte';

  @override
  String get devicesLead =>
      'Jedes Gerät, auf dem Sie sich angemeldet haben, hat ein eigenes Token. Entfernen Sie ein Gerät hier, wird sein Token ungültig; das Gerät muss sich neu anmelden.';

  @override
  String get noDeviceSignedIn => 'Es ist kein Gerät angemeldet.';

  @override
  String thisDeviceNamed(String name) {
    return '$name (dieses Gerät)';
  }

  @override
  String get pairedAtUnknownTime => 'Anmeldezeitpunkt unbekannt';

  @override
  String pairedOn(String day) {
    return 'Angemeldet am $day';
  }

  @override
  String get signOutHere => 'Hier abmelden';

  @override
  String get addDevice => 'Gerät hinzufügen…';

  @override
  String get noBackupCode =>
      'Backup-Code: keiner. Ohne ihn sind Sie nach der Abmeldung Ihres letzten Geräts auf Ihren Administrator angewiesen.';

  @override
  String backupCodeMade(String day) {
    return 'Backup-Code: erstellt am $day. Bewahren Sie ihn sicher auf; ein neuer Code macht ihn ungültig.';
  }

  @override
  String get createBackupCode => 'Backup-Code erstellen…';

  @override
  String get createNewBackupCode => 'Neuen Backup-Code erstellen…';

  @override
  String get withdrawBackupCodeTitle => 'Backup-Code zurückziehen?';

  @override
  String get withdrawBackupCodeMessage =>
      'Der notierte Code funktioniert dann nicht mehr. Melden Sie danach Ihr letztes Gerät ab, brauchen Sie einen Wiederherstellungscode Ihres Administrators.';

  @override
  String get backupCodeTitle => 'Backup-Code';

  @override
  String get backupCodeAdvice =>
      'Schreiben Sie ihn auf und bewahren Sie ihn sicher auf – im Passwort-Manager oder in einer Schublade. Er läuft nie ab, gilt einmal und meldet ein Gerät unter Ihrem Namen an; geben Sie ihn niemandem. Er wird nur dieses eine Mal angezeigt.';

  @override
  String get backupCodeNoExpiry =>
      'Dieser Code läuft nicht ab. Er gilt einmal.';

  @override
  String get addDeviceTitle => 'Gerät hinzufügen';

  @override
  String recoveryCodeTitle(String user) {
    return 'Wiederherstellungscode für $user';
  }

  @override
  String get deviceCodeAdvice =>
      'Geben Sie diesen Code innerhalb von 10 Minuten auf dem anderen Gerät ein. Er meldet jenes Gerät unter Ihrem Namen an – geben Sie ihn niemandem.';

  @override
  String recoveryCodeAdvice(String user) {
    return 'Geben Sie diesen Code innerhalb von 10 Minuten an $user weiter; er meldet ein Gerät unter diesem Namen an. Er gilt einmal – geben Sie ihn niemand anderem.';
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
    return '$name ist angemeldet.';
  }

  @override
  String get signOutThisDeviceTitle => 'Dieses Gerät abmelden?';

  @override
  String get signOutThisDeviceMessage =>
      'Dieses Gerät vergisst seine Anmeldung und greift wieder anonym auf den Server zu. Sie können sich jederzeit neu anmelden.';

  @override
  String removeDeviceTitle(String name) {
    return 'Gerät „$name“ entfernen?';
  }

  @override
  String removeDeviceMessage(String name) {
    return '„$name“ wird abgemeldet und muss sich neu anmelden, bevor es etwas ändern kann.';
  }

  @override
  String lastDeviceWarning(String ways) {
    return 'Dies ist Ihr einziges angemeldetes Gerät. Um sich wieder anzumelden, brauchen Sie $ways.';
  }

  @override
  String waysOrLast(String rest, String last) {
    return '$rest oder $last';
  }

  @override
  String get wayBackupCode => 'Ihren Backup-Code';

  @override
  String get wayRecoveryFromAdmin =>
      'einen Wiederherstellungscode Ihres Administrators';

  @override
  String get wayRecoveryFromOtherAdmin =>
      'einen Wiederherstellungscode eines anderen Administrators';

  @override
  String get wayServerRestart =>
      'einen Neustart des Servers, der einen neuen Anmeldecode ausgibt';

  @override
  String get maybeLastDeviceWarning =>
      'Dies ist vielleicht Ihr einziges angemeldetes Gerät, und der Server war nicht erreichbar. Wenn ja, brauchen Sie zum Wiederanmelden einen Wiederherstellungscode Ihres Administrators, Ihren Backup-Code oder einen Neustart des Servers.';

  @override
  String get permissionMayHeading => 'Rolle';

  @override
  String get permissionSeesHeading => 'Sieht';

  @override
  String get mayShareLinksSwitch => 'Darf Links teilen';

  @override
  String get mayShareLinksExplanation =>
      'Ein Link öffnet ein Album für jeden, der ihn hat.';

  @override
  String get roleExplanationEdit =>
      'Darf Alben anlegen und ändern und Fotos hinzufügen.';

  @override
  String get roleExplanationContribute =>
      'Darf Fotos hinzufügen, aber nichts ändern.';

  @override
  String get roleExplanationView => 'Darf die Alben ansehen, sonst nichts.';

  @override
  String permissionDialogTitle(String user) {
    return 'Was $user tun darf';
  }

  @override
  String get usersLead =>
      'Alle mit einem Konto in diesem Bereich, und die Einladungen, die noch niemand angenommen hat.';

  @override
  String get recoveryCodeTooltip => 'Wiederherstellungscode';

  @override
  String get changePermissionTooltip => 'Berechtigung ändern';

  @override
  String removeUserTitle(String user) {
    return '$user entfernen?';
  }

  @override
  String get removeUserMessage =>
      'Die Geräte dieser Person werden abgemeldet; ihre Fotos bleiben und tragen weiter ihren Namen.';

  @override
  String get invitedPending => 'Eingeladen (ausstehend)';

  @override
  String invitedForPending(String recipient) {
    return 'Eingeladen für $recipient (ausstehend)';
  }

  @override
  String get withdrawInvitationTitle => 'Diese Einladung zurückziehen?';

  @override
  String get withdrawInvitationMessage =>
      'Der Einladungslink funktioniert dann nicht mehr.';

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
    return 'eingeladen für $recipient';
  }

  @override
  String get noOpenInvitations => 'Keine offene Einladung.';

  @override
  String get expiresNever => 'läuft nie ab';

  @override
  String expiredOnDay(String day) {
    return 'abgelaufen am $day';
  }

  @override
  String expiresOnDay(String day) {
    return 'läuft am $day ab';
  }

  @override
  String get permissionSeeingAll => 'Sieht: alle Fotos';

  @override
  String get permissionSeeingNonPrivate => 'Sieht: alle außer privaten Fotos';

  @override
  String get permissionSeeingPublic => 'Sieht: öffentliche Fotos';

  @override
  String get permissionSharingMay => 'Darf Links teilen';

  @override
  String get permissionSharingMayNot => 'Darf keine Links teilen';

  @override
  String get roleWordAdmin => 'Administrator';

  @override
  String get roleWordEdit => 'Bearbeiter';

  @override
  String get roleWordContribute => 'Mitwirkender';

  @override
  String get roleWordView => 'Betrachter';

  @override
  String get roleWordUnknown => 'Unbekannte Rolle';

  @override
  String get clearanceWordAll => 'Alle Fotos';

  @override
  String get clearanceWordNonPrivate => 'Alle außer privaten Fotos';

  @override
  String get clearanceWordPublic => 'Öffentliche Fotos';

  @override
  String get serverUrlEmpty =>
      'Geben Sie die Adresse des Album-Servers ein, z. B. \'http://nas.local:8080/valbum/\'.';

  @override
  String get serverUrlInvalid =>
      'Das ist keine Serveradresse. Eine Serveradresse sieht so aus: \'http://nas.local:8080/valbum/\'.';

  @override
  String get shareLinkRefusal =>
      'Dies ist ein Link zu einem geteilten Album, keine Anmeldung. Öffnen Sie ihn in einem Browser, um zu sehen, was mit Ihnen geteilt wurde.';

  @override
  String get shareLinkNotValid =>
      'Dieser Link ist ungültig. Bitten Sie die Person, die ihn Ihnen geschickt hat, um einen neuen.';

  @override
  String get close => 'Schließen';

  @override
  String get create => 'Erstellen';

  @override
  String get back => 'Zurück';

  @override
  String get stop => 'Stopp';

  @override
  String get sharedAlbumFallback => 'Geteiltes Album';

  @override
  String get ratingVeryGood => 'Sehr gut';

  @override
  String get ratingGood => 'Gut';

  @override
  String get ratingUnrated => 'Nicht bewertet';

  @override
  String get ratingPoor => 'Mangelhaft';

  @override
  String get ratingTrash => 'Papierkorb';

  @override
  String get ratingFloorEveryPhoto => 'jedes Foto';

  @override
  String ratingFloorAtLeast(String rating) {
    return 'mindestens $rating';
  }

  @override
  String get showTrash => 'Papierkorb anzeigen';

  @override
  String get trashPageTitle => 'Papierkorb';

  @override
  String get trashRestore => 'Wiederherstellen';

  @override
  String get trashPurgeAction => 'Endgültig löschen…';

  @override
  String get trashPurgeTitle => 'Papierkorb leeren';

  @override
  String get trashPurgeMessage =>
      'Die Fotos werden von der Festplatte gelöscht. Dieser Vorgang kann nicht rückgängig gemacht werden.';

  @override
  String get trashPurgeConfirm => 'Endgültig löschen';

  @override
  String trashPurged(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos wurden von der Festplatte gelöscht.',
      one: '1 Foto wurde von der Festplatte gelöscht.',
    );
    return '$_temp0';
  }

  @override
  String get trashEmptyNotice => 'Der Papierkorb dieses Albums ist leer.';

  @override
  String get trashBackToAlbum => 'Zurück zum Album';

  @override
  String doingPurgingTrash(String folder) {
    return 'Leeren des Papierkorbs von $folder';
  }

  @override
  String get shareContinueToStart => 'Weiter zur Startseite';

  @override
  String get shareBackToAlbum => 'Zurück zum geteilten Album';

  @override
  String get expiryNever => 'Nie';

  @override
  String get expiryOneDay => '1 Tag';

  @override
  String get expiryOneWeek => '1 Woche';

  @override
  String get expiryOneMonth => '1 Monat';

  @override
  String get expiryPickDate => 'An einem bestimmten Datum…';

  @override
  String shareDialogTitle(String name) {
    return '$name per Link teilen';
  }

  @override
  String get shareTargetTopLevel => 'die oberste Ebene';

  @override
  String get linksHeading => 'Links';

  @override
  String get noLinksYet => 'Noch keine Links.';

  @override
  String get linkNoLabel => '(keine Bezeichnung)';

  @override
  String get deleteLinkTooltip => 'Link löschen…';

  @override
  String get linkNeverExpires => 'läuft nie ab';

  @override
  String get linkUpToMembers => 'auch Mitglieder-Fotos';

  @override
  String get linkPublicOnly => 'nur öffentliche';

  @override
  String linkInheritedFrom(String folder) {
    return 'geerbt von $folder, dort löschen';
  }

  @override
  String get shareWholeSpace => 'der gesamte Bereich';

  @override
  String get newLinkTile => 'Neuer Link…';

  @override
  String get newLinkHeading => 'Neuer Link';

  @override
  String get linkLabelLabel => 'Bezeichnung';

  @override
  String get linkLabelHelp =>
      'Wofür dieser Link ist – nur für Ihre eigene Übersicht.';

  @override
  String get expiresHeading => 'Läuft ab';

  @override
  String get showsHeading => 'Zeigt';

  @override
  String get privacyPublicOnly => 'Öffentliche Fotos';

  @override
  String get privacyUpToMembers =>
      'Auch Fotos, die nur für Mitglieder sichtbar sind';

  @override
  String get privacyMembersNote =>
      'Ein privates Foto wird niemals über einen Link angezeigt.';

  @override
  String get lowestRatingHeading => 'Niedrigste Bewertung';

  @override
  String get linkRightsHelp =>
      'Das Anzeigen ist immer erlaubt, das Bearbeiten niemals.';

  @override
  String get linkRatingAllButTrash => 'Jedes Foto außer denen im Papierkorb';

  @override
  String linkRatingAtLeast(String rating) {
    return 'Mindestens $rating';
  }

  @override
  String get createLink => 'Link erstellen';

  @override
  String get theLinkHeading => 'Der Link';

  @override
  String get shareLinkOnce =>
      'Kopieren Sie ihn jetzt: Der Server speichert nur seinen Fingerabdruck und kann ihn nie wieder anzeigen. Ein verlorener Link wird gelöscht und neu erstellt.';

  @override
  String get linkCopied => 'Der Link wurde kopiert.';

  @override
  String get deleteLinkTitle => 'Link löschen';

  @override
  String deleteLinkNamed(String link) {
    return 'Den Link $link löschen? Wer ihn hat, kann ihn nicht mehr öffnen.';
  }

  @override
  String get deleteLinkUnnamed =>
      'Diesen Link löschen? Wer ihn hat, kann ihn nicht mehr öffnen.';

  @override
  String get deleteLinkPersonalNote =>
      'Auch die an die Empfänger gesendeten Links funktionieren nicht mehr. Die Kontakte bleiben bestehen.';

  @override
  String get invitationGuestNote =>
      'Ein Gast hat keine eigenen Alben: Seine Bibliothek besteht aus den Inhalten, die andere mit ihm teilen.';

  @override
  String get deviceNameHelp => 'Um welches Ihrer Geräte es sich handelt.';

  @override
  String get joining => 'Beitreten...';

  @override
  String get joinAction => 'Beitreten';

  @override
  String invitationJoinedAs(String user) {
    return 'Sie sind als $user dabei.';
  }

  @override
  String get invitationSignedInNote => 'Dieses Gerät ist angemeldet.';

  @override
  String get openYourAlbums => 'Zu den Alben';

  @override
  String get invitationChooseName =>
      'Wählen Sie den Namen, unter dem Sie bekannt sein möchten.';

  @override
  String get inviteDialogTitle => 'Jemanden einladen';

  @override
  String get inviteRecipientLabel => 'Für wen';

  @override
  String get inviteRecipientHelp =>
      'Eine Notiz für Sie selbst: für wen diese Einladung ist. Optional.';

  @override
  String get inviteNoteLabel => 'Anmerkung';

  @override
  String get inviteNoteHelp =>
      'Was die eingeladene Person liest, wenn sie den Link öffnet.';

  @override
  String get createInvitation => 'Einladung erstellen';

  @override
  String get theInvitationHeading => 'Die Einladung';

  @override
  String invitationValidUntil(String day) {
    return 'Gültig bis $day und für eine Person.';
  }

  @override
  String get invitationOnce =>
      'Senden Sie sie jetzt: Der Server speichert nur ihren Fingerabdruck und kann sie nie wieder anzeigen. Eine verlorene Einladung wird zurückgezogen und neu erstellt.';

  @override
  String get invitationCopied => 'Die Einladung wurde kopiert.';

  @override
  String get cameraRollHeading => 'Kamerarolle';

  @override
  String get cameraRollExplanation =>
      'Neue Fotos dieses Geräts werden in ein Album der Bibliothek hochgeladen. Nichts wird doppelt hochgeladen: Vor jeder Übertragung wird geprüft, ob der Server das Foto schon hat.';

  @override
  String get cameraRollUploadNew => 'Neue Fotos hochladen';

  @override
  String get noPhotoLibrary =>
      'Auf dieser Plattform gibt es keine Fotobibliothek';

  @override
  String get onlyOverWifi => 'Nur über WLAN';

  @override
  String get onlyOverWifiExplanation =>
      'Neue Fotos warten auf WLAN oder eine Kabelverbindung, damit der Upload nicht das mobile Datenvolumen belastet.';

  @override
  String get syncNow => 'Jetzt synchronisieren';

  @override
  String get syncAnyway => 'Trotzdem synchronisieren';

  @override
  String get albumsToSync => 'Zu synchronisierende Alben';

  @override
  String photoCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos',
      one: '1 Foto',
    );
    return '$_temp0';
  }

  @override
  String get newAlbumTitle => 'Neues Album';

  @override
  String get backToAlbum => 'Zurück zum Album';

  @override
  String get groupPicture => 'Gruppenbild';

  @override
  String get groupPictureIsThis => 'Dieses Bild ist das Gruppenbild';

  @override
  String get useAsGroupPicture => 'Als Gruppenbild verwenden';

  @override
  String get videoPreparing =>
      'Die abspielbare Version dieses Videos wird noch erstellt.';

  @override
  String get videoPlayOriginal => 'Die Originaldatei abspielen';

  @override
  String get videoNeedsRendition =>
      'Dieser Browser kann das Original dieses Videos nicht abspielen; es wird abgespielt, sobald der Server es konvertiert hat.';

  @override
  String get videoCannotPlay => 'Dieses Video kann nicht abgespielt werden.';

  @override
  String get videoNetworkHint =>
      'Der Server war nicht erreichbar oder hat das Video abgelehnt.';

  @override
  String get videoFormatHint =>
      'Dieses Gerät kann das Format dieses Videos nicht wiedergeben.';

  @override
  String get videoDiagnosticsHint =>
      'Details für einen Fehlerbericht: das Diagnoseprotokoll in den Servereinstellungen.';

  @override
  String videoPreparingRetry(int seconds, int attempt, int attempts) {
    String _temp0 = intl.Intl.pluralLogic(
      seconds,
      locale: localeName,
      other: 'Erneute Anfrage in $seconds Sekunden ($attempt von $attempts).',
      one: 'Erneute Anfrage in 1 Sekunde ($attempt von $attempts).',
    );
    return '$_temp0';
  }

  @override
  String get videoPendingGaveUp =>
      'Die abspielbare Version wird noch erstellt – versuchen Sie es in einer Minute erneut.';

  @override
  String get videoTryAgain => 'Erneut versuchen';

  @override
  String videoConversionFailed(String reason) {
    return 'Der Server konnte dieses Video nicht konvertieren: $reason';
  }

  @override
  String get videoPlayingOriginal =>
      'Stattdessen wird die Originaldatei abgespielt.';

  @override
  String get videoFetchedWithoutSignIn =>
      'Der Browser hat das Video ohne Anmeldung abgerufen und wurde abgewiesen.';

  @override
  String videoFormatRefused(String contentType) {
    return 'Dieser Browser oder dieses Gerät kann dieses Format nicht wiedergeben ($contentType).';
  }

  @override
  String videoNotFetched(String contentType) {
    return 'Der Server stellt dieses Video bereit ($contentType), doch der Player konnte es nicht abrufen.';
  }

  @override
  String videoServerRefused(String message, int status) {
    return 'Der Server hat das Video mit dem Status $status abgelehnt: $message';
  }

  @override
  String videoServerRefusedBare(int status) {
    return 'Der Server hat das Video mit dem Status $status abgelehnt.';
  }

  @override
  String videoServerUnreachable(String problem) {
    return 'Der Server konnte nicht erreicht werden: $problem';
  }

  @override
  String videoDidNotStart(int seconds) {
    String _temp0 = intl.Intl.pluralLogic(
      seconds,
      locale: localeName,
      other: 'Das Video wurde nicht innerhalb von $seconds Sekunden gestartet.',
      one: 'Das Video wurde nicht innerhalb von 1 Sekunde gestartet.',
    );
    return '$_temp0';
  }

  @override
  String videoSilentDelivers(String contentType) {
    return 'Der Server liefert es aus ($contentType), aber der Player hat weder gestartet noch einen Fehler gemeldet.';
  }

  @override
  String get videoUnknownType => 'kein Typ angegeben';

  @override
  String get videoNoticeDismiss => 'Schließen';

  @override
  String get pause => 'Pause';

  @override
  String get play => 'Abspielen';

  @override
  String get photoPickerEntry => 'Aus der Fotobibliothek des Smartphones...';

  @override
  String get systemPickerEntry => 'Dateien auswählen... (max. 100)';

  @override
  String get pickerPhotosAndVideos => 'Fotos und Videos';

  @override
  String get pickerAllFiles => 'Alle Dateien';

  @override
  String get photoLibraryTitle => 'Fotobibliothek';

  @override
  String get allAlbums => 'Alle Alben';

  @override
  String get selectAll => 'Alle';

  @override
  String get selectNone => 'Keine';

  @override
  String get photoLibraryNoAccess =>
      'Kein Zugriff auf die Fotobibliothek dieses Geräts.';

  @override
  String photoLibraryUnreadable(String problem) {
    return 'Die Fotobibliothek kann nicht gelesen werden: $problem';
  }

  @override
  String photoAlbumUnreadable(String problem) {
    return 'Das Album kann nicht gelesen werden: $problem';
  }

  @override
  String get photoLibraryNoAlbums =>
      'Die Fotobibliothek dieses Geräts enthält keine Alben.';

  @override
  String get photoAlbumEmpty => 'Dieses Album enthält keine Fotos.';

  @override
  String photoPickerSelected(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count ausgewählt',
      one: '1 ausgewählt',
      zero: 'Nichts ausgewählt',
    );
    return '$_temp0';
  }

  @override
  String photoPickerUpload(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos hochladen',
      one: '1 Foto hochladen',
    );
    return '$_temp0';
  }

  @override
  String get uploadProgressTitle => 'Fotos hochladen';

  @override
  String get scanCodeTitle => 'Gerätecode scannen';

  @override
  String get scanCodeAdvice =>
      'Richten Sie die Kamera auf den Code, der unter „Meine Geräte“ auf dem Gerät angezeigt wird, auf dem Sie bereits angemeldet sind.';

  @override
  String get cameraNotAllowed =>
      'Diese App darf die Kamera nicht verwenden. Erlauben Sie es in den Systemeinstellungen, oder geben Sie den Code ein.';

  @override
  String get cameraUnsupported =>
      'Dieses Gerät kann keinen Code scannen. Geben Sie ihn stattdessen ein.';

  @override
  String get cameraNotOpened =>
      'Die Kamera konnte nicht geöffnet werden. Geben Sie stattdessen den Code ein.';

  @override
  String get ok => 'OK';

  @override
  String get delete => 'Löschen';

  @override
  String get deleteEllipsis => 'Löschen…';

  @override
  String get reload => 'Neu laden';

  @override
  String get up => 'Nach oben';

  @override
  String get home => 'Startseite';

  @override
  String get upload => 'Hochladen';

  @override
  String get apply => 'Übernehmen';

  @override
  String get discard => 'Verwerfen';

  @override
  String get stay => 'Bleiben';

  @override
  String get keepEditing => 'Weiter bearbeiten';

  @override
  String get refresh => 'Aktualisieren';

  @override
  String get open => 'Öffnen';

  @override
  String get select => 'Auswählen';

  @override
  String get group => 'Gruppieren';

  @override
  String get loading => 'Wird geladen...';

  @override
  String get serverMenuEntry => 'Server...';

  @override
  String get titleLabel => 'Titel';

  @override
  String get subtitleLabel => 'Untertitel';

  @override
  String get nameLabel => 'Name';

  @override
  String get dateLabel => 'Datum';

  @override
  String get commentLabel => 'Kommentar';

  @override
  String get headingLabel => 'Überschrift';

  @override
  String get mustNotBeEmpty => 'Darf nicht leer sein';

  @override
  String get viewAsYourself => 'Sie selbst';

  @override
  String get viewAsMembers => 'Mitglieder';

  @override
  String get viewAsPublic => 'Öffentlichkeit';

  @override
  String get viewAsStateYourself => 'Sie selbst';

  @override
  String get viewAsStateMembers => 'Mitglieder';

  @override
  String get viewAsStatePublic => 'Öffentlichkeit';

  @override
  String get viewAsLabel => 'Anzeigen als';

  @override
  String get previewAsMembers =>
      'Ansicht als Mitglied – so sehen es die Mitglieder';

  @override
  String get previewAsPublic =>
      'Ansicht als Öffentlichkeit – so sieht es die Öffentlichkeit';

  @override
  String get editHeadingTitle => 'Überschrift bearbeiten';

  @override
  String get insertHeading => 'Überschrift einfügen';

  @override
  String get deleteHeadingTooltip => 'Überschrift löschen';

  @override
  String get headingLevelSection => 'Abschnitt';

  @override
  String get headingLevelSubsection => 'Unterabschnitt';

  @override
  String get addHeading => 'Überschrift hinzufügen…';

  @override
  String get headingSelectTooltip =>
      'Wählt die Bilder unter dieser Überschrift aus';

  @override
  String get alreadyInOrder => 'Ist bereits sortiert';

  @override
  String get noOtherImageFromCamera =>
      'Es gibt kein weiteres Bild von dieser Kamera';

  @override
  String get nothingToAdjust => 'Es gibt nichts anzupassen';

  @override
  String saveFailed(String problem) {
    return 'Speichern fehlgeschlagen: $problem';
  }

  @override
  String get discardChangesTitle => 'Die Änderungen an diesem Album verwerfen?';

  @override
  String get discardChangesMessage =>
      'Die Änderungen hier sind nicht gespeichert. Verwerfen zeigt das Album wieder so, wie es auf dem Server ist.';

  @override
  String get saveChangesTitle => 'Änderungen an diesem Album speichern?';

  @override
  String get saveChangesMessage =>
      'Wenn Sie das Album verlassen, endet die Bearbeitung. Nicht gespeicherte Änderungen gehen verloren.';

  @override
  String get saveOrDiscardFirst =>
      'Speichern oder verwerfen Sie Ihre Änderungen zuerst';

  @override
  String get headingCannotMove =>
      'Eine Überschrift kann nicht verschoben werden.';

  @override
  String get shareLinkAction => 'Per Link teilen…';

  @override
  String get albumProperties => 'Albumeigenschaften';

  @override
  String get folderProperties => 'Ordnereigenschaften';

  @override
  String moveSubjectTo(String subject) {
    return '$subject verschieben nach…';
  }

  @override
  String get moveAlbumTo => 'Album verschieben nach…';

  @override
  String get deleteAlbumAction => 'Album löschen…';

  @override
  String deleteSubjectAction(String subject) {
    return '$subject löschen…';
  }

  @override
  String get sortByDate => 'Nach Datum sortieren';

  @override
  String get minRatingLabel => 'Mindestbewertung';

  @override
  String get showMoreImages => 'Weitere Bilder anzeigen';

  @override
  String get showFewerImages => 'Weniger Bilder anzeigen';

  @override
  String get reanalyze => 'Fotodetails erneut einlesen';

  @override
  String get reanalyzeExplanation =>
      'Die Kamera- und Positionsdaten werden erneut aus den Dateien ausgelesen und fehlende Angaben ergänzt. Außerdem werden die Aufnahmedaten korrigiert, die von einer älteren Version in der falschen Zeitzone eingelesen wurden. Abgesehen davon wird nichts an den bereits gespeicherten Daten geändert.';

  @override
  String reanalyzeDone(int examined, int filled) {
    return '$examined Fotos überprüft; bei $filled davon wurde eine Kamera oder eine Position hinzugefügt.';
  }

  @override
  String reanalyzeRunning(int examined, int filled) {
    return 'Wird im Hintergrund noch gelesen: Bisher wurden $examined Fotos überprüft, $filled davon haben eine Kamera oder eine Position erhalten.';
  }

  @override
  String reanalyzeDatesCorrected(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Die Aufnahmezeiten von $count Fotos wurden korrigiert.',
      one: 'Die Aufnahmezeit von 1 Foto wurde korrigiert.',
    );
    return '$_temp0';
  }

  @override
  String get refreshPreviews => 'Vorschauen aktualisieren';

  @override
  String get refreshPreviewsMessage =>
      'Die Vorschaubilder und Videofassungen dieses Albums werden verworfen und beim nächsten Anzeigen neu erstellt. Die Fotos selbst bleiben unberührt.';

  @override
  String previewsRefreshed(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other:
          '$count zwischengespeicherte Dateien wurden verworfen; die Vorschauen werden neu erstellt.',
      one:
          '1 zwischengespeicherte Datei wurde verworfen; die Vorschauen werden neu erstellt.',
    );
    return '$_temp0';
  }

  @override
  String get notAnAlbumAnswer => 'Der Server hat kein Album zurückgegeben.';

  @override
  String ratingFilterHidesAll(int rating) {
    return 'Kein Bild ist mit $rating oder besser bewertet – drücken Sie + (oder die Schaltfläche +), um mehr anzuzeigen.';
  }

  @override
  String get turnRight => 'Nach rechts drehen';

  @override
  String get turnLeft => 'Nach links drehen';

  @override
  String get flipVertically => 'Vertikal spiegeln';

  @override
  String get ungroupAction => 'Gruppierung aufheben';

  @override
  String get chooseGroupPicture => 'Wählen Sie das Gruppenbild aus';

  @override
  String get selectAllFromCamera => 'Alle Bilder dieser Kamera auswählen';

  @override
  String get adjustRecordingTimeAction => 'Aufnahmezeit anpassen…';

  @override
  String get imageProperties => 'Bildeigenschaften';

  @override
  String get useAsAlbumPicture => 'Als Albumbild verwenden';

  @override
  String get albumPictureTooltip => 'Albumbild';

  @override
  String privacyControlTooltip(String next, String level) {
    return 'Sichtbarkeit: $level (tippen für $next)';
  }

  @override
  String get groupNeedsTwo =>
      'Wählen Sie mindestens zwei Bilder aus, um sie zu gruppieren';

  @override
  String get dragToReorder => 'Zum Neuanordnen ziehen';

  @override
  String partCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Teile',
      one: '1 Teil',
    );
    return '$_temp0';
  }

  @override
  String get notATime => 'Keine Zeitangabe (JJJJ-MM-TT HH:mm:ss)';

  @override
  String appliesToImages(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Gilt für $count Bilder',
      one: 'Gilt für 1 Bild',
    );
    return '$_temp0';
  }

  @override
  String useNameDateOne(String time) {
    return 'Zeit aus dem Dateinamen verwenden: $time';
  }

  @override
  String useNameDateMany(int count) {
    return 'Zeit aus dem Dateinamen verwenden ($count Bilder)';
  }

  @override
  String get adjustRecordingTimeTitle => 'Aufnahmezeit anpassen';

  @override
  String get correctTime => 'Richtige Zeit';

  @override
  String get pickDateAndTime => 'Datum und Uhrzeit auswählen';

  @override
  String get adjustRecordingTimeHelp =>
      'Die ursprüngliche Aufnahmezeit bleibt im Foto erhalten; das Album behält seine eigene bei.';

  @override
  String get dateNone => 'Datum: keine Angabe';

  @override
  String dateIs(String date) {
    return 'Datum: $date';
  }

  @override
  String get pickDate => 'Datum wählen';

  @override
  String get clearDate => 'Datum entfernen';

  @override
  String get dateFromFolderName => 'Aus dem Ordnernamen übernommen.';

  @override
  String get dateFromPhotos => 'Aus den Fotos übernommen.';

  @override
  String get noAlbumPictureHint =>
      'Kein Albumbild gewählt – wählen Sie im Bearbeitungsmodus eines auf einer Kachel.';

  @override
  String get zoomIn => 'Vergrößern';

  @override
  String get zoomOut => 'Verkleinern';

  @override
  String get resetCrop => 'Zuschneidebereich zurücksetzen';

  @override
  String get privacyPublicName => 'Öffentlich';

  @override
  String get privacyMembersName => 'Mitglieder';

  @override
  String get privacyPrivateName => 'Privat';

  @override
  String get unitDays => 'd';

  @override
  String get unitHours => 'h';

  @override
  String get unitMinutes => 'min';

  @override
  String get unitSeconds => 's';

  @override
  String get useAsFolderPicture => 'Als Ordnerbild verwenden';

  @override
  String get useNoFolderPicture => 'Kein Ordnerbild verwenden';

  @override
  String get addStarAction => 'Stern hinzufügen';

  @override
  String get removeStarAction => 'Stern entfernen';

  @override
  String get starredBadge => 'Mit Stern markiert';

  @override
  String get libraryEmptyNotice => 'Hier gibt es noch keine Alben.';

  @override
  String get libraryEmptyHint =>
      'Erstellen Sie das erste über das Menü oben rechts.';

  @override
  String get folderEmptyNotice => 'Dieser Ordner enthält noch keine Alben.';

  @override
  String get createAlbum => 'Album erstellen';

  @override
  String get createFolder => 'Ordner erstellen';

  @override
  String get applyRule => 'Regel anwenden';

  @override
  String get moveToAction => 'Verschieben nach…';

  @override
  String get nothingToFile => 'Nichts abzulegen.';

  @override
  String filedAlbums(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Alben abgelegt.',
      one: '1 Album abgelegt.',
    );
    return '$_temp0';
  }

  @override
  String get placementNone => 'keine Regel';

  @override
  String get placementByYear => 'nach Jahr';

  @override
  String get placementByYearMonth => 'nach Jahr und Monat';

  @override
  String get placementHeading => 'Ablageregel';

  @override
  String get placementExplanation =>
      'Was hier eintrifft, wird im entsprechenden Jahresordner abgelegt. Was bereits hier ist, bleibt so lange dort, bis die Ablageregel über das Menü angewendet wird.';

  @override
  String get createAlbumUndatedHint =>
      'Ohne Datum bleibt das Album in diesem Ordner.';

  @override
  String get newFolderTitle => 'Neuer Ordner';

  @override
  String imageCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Bilder',
      one: '1 Bild',
    );
    return '$_temp0';
  }

  @override
  String get targetTopLevel => 'die oberste Ebene';

  @override
  String get pickerTopLevel => 'Oberste Ebene';

  @override
  String get nothingToMove => 'Es gibt nichts zu verschieben.';

  @override
  String moveConfirm(String subject, String target) {
    return '$subject nach $target verschieben';
  }

  @override
  String nothingMovedTo(String target) {
    return 'Nichts nach $target verschoben.';
  }

  @override
  String movedToTarget(String subject, String target) {
    return '$subject wurde nach $target verschoben.';
  }

  @override
  String get deleteExplanation =>
      'Ein Album ohne Bilder wird entfernt; ein Album mit Bildern kommt in den Papierkorb-Ordner des Bereichs (von der Festplatte wird nichts gelöscht).';

  @override
  String deleteQuestion(String what) {
    return '$what löschen?';
  }

  @override
  String deletedWhat(String what) {
    return 'Gelöscht: $what.';
  }

  @override
  String newAlbumCreatedEmpty(String target) {
    return 'Das neue Album $target wurde erstellt und ist leer.';
  }

  @override
  String get imagesLiveInAlbums => 'Bilder liegen in Alben – öffnen Sie eines.';

  @override
  String get albumHoldsNoFolders => 'Ein Album enthält keine Ordner.';

  @override
  String get folderCannotBeShown =>
      'Dieser Ordner kann nicht angezeigt werden.';

  @override
  String get createNewAlbum => 'Neues Album erstellen…';

  @override
  String get moveTitle => 'Verschieben';

  @override
  String get inboxEmptyNotice => 'Hier wartet nichts.';

  @override
  String get inboxUndatedHeading => 'Ohne Datum';

  @override
  String get inboxDeleteExplanation =>
      'Die Fotos kommen in den Papierkorb-Ordner des Bereichs. Von der Festplatte wird nichts gelöscht.';

  @override
  String get clearSelection => 'Auswahl aufheben';

  @override
  String selectedCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Bilder ausgewählt',
      one: '1 Bild ausgewählt',
    );
    return '$_temp0';
  }

  @override
  String get selectEverythingBelow => 'Alles darunter auswählen';

  @override
  String get nothingSelected => 'Es ist nichts ausgewählt.';

  @override
  String get notEditableMessage => 'Sie dürfen dieses Album nicht bearbeiten.';

  @override
  String get pictureFailedMessage => 'Dieses Bild konnte nicht geladen werden.';

  @override
  String get takeBack => 'Zurücknehmen…';

  @override
  String get previousImage => 'Vorheriges Bild';

  @override
  String get nextImage => 'Nächstes Bild';

  @override
  String get showAlternatives => 'Alternativen anzeigen';

  @override
  String propertyFile(String name) {
    return 'Datei: $name';
  }

  @override
  String propertyTaken(String time) {
    return 'Aufgenommen: $time';
  }

  @override
  String propertyCamera(String camera) {
    return 'Kamera: $camera';
  }

  @override
  String propertyRaw(String name) {
    return 'Rohdatei: $name';
  }

  @override
  String propertyLocation(String latitude, String longitude) {
    return 'Standort: $latitude, $longitude';
  }

  @override
  String get showOnMap => 'Auf der Karte anzeigen';

  @override
  String propertyPlace(String place) {
    return 'Ort: $place';
  }

  @override
  String get showCoordinates => 'Koordinaten anzeigen';

  @override
  String get hideCoordinates => 'Koordinaten ausblenden';

  @override
  String addedBy(String user) {
    return 'Hinzugefügt von $user';
  }

  @override
  String get rightLabelView => 'Ansehen';

  @override
  String get rightLabelDownload => 'Herunterladen';

  @override
  String get rightLabelContribute => 'Beitragen';

  @override
  String get rightLabelEdit => 'Bearbeiten';

  @override
  String get rightExplanationView => 'Das Album und seine Vorschaubilder sehen';

  @override
  String get rightExplanationDownload => 'Die Originale herunterladen';

  @override
  String get rightExplanationContribute => 'Fotos hinzufügen';

  @override
  String get rightExplanationEdit =>
      'Das Album und seinen gesamten Inhalt ändern';

  @override
  String get rightsPhraseEdit => 'Sie dürfen es ändern';

  @override
  String get rightsPhraseContribute => 'Sie dürfen Fotos hinzufügen';

  @override
  String get rightsPhraseDownload => 'Sie dürfen ansehen und herunterladen';

  @override
  String get viewerDownload => 'Original herunterladen';

  @override
  String get viewerDownloadRaw => 'Rohdatei herunterladen';

  @override
  String downloadSelection(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Originale herunterladen',
      one: '1 Original herunterladen',
    );
    return '$_temp0';
  }

  @override
  String downloadSaved(String name) {
    return '$name wurde gespeichert.';
  }

  @override
  String downloadSavedCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Originale wurden gespeichert.',
      one: '1 Original gespeichert.',
    );
    return '$_temp0';
  }

  @override
  String downloadFailed(String reason) {
    return 'Der Download ist fehlgeschlagen: $reason';
  }

  @override
  String get downloadCancelled => 'Der Download wurde abgebrochen.';

  @override
  String downloadProgress(int current, int count, String received) {
    return 'Datei $current von $count: $received heruntergeladen';
  }

  @override
  String downloadProgressOf(
      int current, String total, int count, String received) {
    return 'Datei $current von $count: $received von $total heruntergeladen';
  }

  @override
  String get selectPhotos => 'Fotos auswählen…';

  @override
  String get selectModeLeave => 'Den Auswahlmodus verlassen';

  @override
  String get rightsPhraseView => 'Sie dürfen ansehen';

  @override
  String get rightsPhraseNone => 'Sie dürfen hier nichts tun';

  @override
  String get sharedWithYou => 'Mit Ihnen geteilt';

  @override
  String sharedByOwner(String owner) {
    return 'Geteilt von $owner';
  }

  @override
  String serverNotReached(String problem) {
    return 'Der Server konnte nicht erreicht werden: $problem';
  }

  @override
  String httpFailure(String doing, int status) {
    return 'HTTP $status beim $doing.';
  }

  @override
  String doingLoading(String url) {
    return 'Laden von $url';
  }

  @override
  String get doingLoadingImage => 'Laden des Bildes';

  @override
  String doingStoring(String url) {
    return 'Speichern von $url';
  }

  @override
  String doingCreating(String url) {
    return 'Erstellen von $url';
  }

  @override
  String doingUploading(String url) {
    return 'Hochladen nach $url';
  }

  @override
  String doingAsking(String url) {
    return 'Abfragen von $url';
  }

  @override
  String doingMoving(String target) {
    return 'Verschieben nach $target';
  }

  @override
  String doingDeleting(String folder) {
    return 'Löschen in $folder';
  }

  @override
  String doingFiling(String folder) {
    return 'Ablegen in $folder';
  }

  @override
  String doingReanalyzing(String folder) {
    return 'Neueinlesen der Fotodetails in $folder';
  }

  @override
  String doingSigningIn(String url) {
    return 'Anmelden bei $url';
  }

  @override
  String doingRefreshingPreviews(String folder) {
    return 'Aktualisieren der Vorschaubilder von $folder';
  }

  @override
  String serverUnreachableNoPreview(String problem) {
    return 'Der Server ist nicht erreichbar ($problem), daher kann keine Vorschau angezeigt werden.';
  }

  @override
  String serverUnreachableNoCache(String problem) {
    return 'Der Server ist nicht erreichbar ($problem), und für diese Ansicht ist nichts im Cache gespeichert.';
  }

  @override
  String notVAlbumServer(String server) {
    return 'Der Server unter $server hat keine Albumdaten geliefert – kein VAlbum-Server, oder ist die Server-Adresse in den Einstellungen falsch?';
  }

  @override
  String get noAnswerInTime => 'keine Antwort in der vorgesehenen Zeit';

  @override
  String get uploadConnectionLost => 'Verbindung unterbrochen';

  @override
  String uploadInterruptedCounts(int total, int onServer, int remaining) {
    return 'Von $total Fotos befinden sich $onServer auf dem Server; die verbleibenden $remaining können erneut gesendet werden.';
  }

  @override
  String get uploadCancelled => 'Der Upload wurde abgebrochen.';

  @override
  String get uploadAsking => 'Der Server wird gefragt...';

  @override
  String get uploadWaiting => 'Warten auf den Server...';

  @override
  String uploadPreparing(int total, int done) {
    return 'Vorbereitung: $done von $total...';
  }

  @override
  String uploadImageCount(int total, int done) {
    return '$done von $total Bildern gesendet';
  }

  @override
  String uploadSummary(int stored, int present) {
    return '$stored hochgeladen, $present bereits vorhanden.';
  }

  @override
  String alreadyInLibrary(String where) {
    return 'Bereits in der Bibliothek vorhanden: $where.';
  }

  @override
  String get noticeGuestNoSpace =>
      'Bitten Sie den Administrator um einen eigenen Bereich für Ihre Alben.';

  @override
  String get noticeNoServerConfigured =>
      'Es ist kein Album-Server konfiguriert.';

  @override
  String get noticeOffline => 'Offline: Der Album-Server ist nicht erreichbar.';

  @override
  String noticeServerUnreachable(String problem) {
    return 'Der Server ist nicht erreichbar ($problem).';
  }

  @override
  String get noticePhotoLibraryUnreadable =>
      'Die Fotobibliothek kann nicht gelesen werden.';

  @override
  String noticePhotoLibraryFailed(String problem) {
    return 'Die Fotobibliothek konnte nicht gelesen werden: $problem';
  }

  @override
  String get noticePhotoAccessDenied =>
      'Der Zugriff auf die Fotobibliothek wurde verweigert. Erlauben Sie VAlbum in den Systemeinstellungen den Zugriff auf Fotos und versuchen Sie es dann erneut.';

  @override
  String get noticeMediaLocationNotGranted =>
      'Öffnen Sie die App einmal, damit sie den Aufnahmeort der Fotos auslesen kann; bis dahin werden bei der Synchronisierung im Hintergrund keine Daten hochgeladen.';

  @override
  String noticePhotoLibraryOpenFailed(String problem) {
    return 'Die Fotobibliothek kann nicht geöffnet werden: $problem';
  }

  @override
  String noticeAlbumNotOnDevice(String name) {
    return 'Der Inhalt von $name befindet sich noch nicht auf diesem Gerät (noch in der Cloud?).';
  }

  @override
  String noticeBackgroundScheduleFailed(String problem) {
    return 'Die Hintergrundsynchronisierung konnte nicht geplant werden: $problem';
  }

  @override
  String noticeBackgroundUnscheduleFailed(String problem) {
    return 'Die Hintergrundsynchronisierung konnte nicht deaktiviert werden: $problem';
  }

  @override
  String get noticeNoNetwork =>
      'Kein Netzwerk: Die Synchronisierung wartet auf eine WLAN-Verbindung.';

  @override
  String get noticeNoWifiMobile =>
      'Kein WLAN: Die Synchronisierung ist auf WLAN beschränkt, und dieses Gerät nutzt eine Mobilfunkverbindung.';

  @override
  String get noticeNoWifiOther =>
      'Kein WLAN: Die Synchronisierung ist auf WLAN beschränkt, und dieses Gerät befindet sich in einem anderen Netzwerk.';

  @override
  String get noticeNoBackgroundSyncHere =>
      'Die Hintergrundsynchronisierung ist auf dieser Plattform nicht verfügbar; die Fotos werden synchronisiert, solange die App geöffnet ist.';

  @override
  String get noticeNoBackgroundSyncInBrowser =>
      'Die Hintergrundsynchronisierung ist in einem Browser nicht verfügbar; die Fotos werden synchronisiert, solange die App geöffnet ist.';

  @override
  String get noticeNoBackgroundSyncInApp =>
      'Die Hintergrundsynchronisierung ist in dieser App nicht verfügbar.';

  @override
  String get noticeNoBackgroundSyncInTest =>
      'In diesem Test findet keine Synchronisierung im Hintergrund statt.';

  @override
  String get noticeNoPhotoLibraryPlatform =>
      'Auf dieser Plattform gibt es keine Fotobibliothek – die Synchronisierung der Kamerarolle funktioniert unter Android und iOS.';

  @override
  String get noticeNoPhotoLibraryBrowser =>
      'In einem Browser gibt es keine Fotobibliothek – die Synchronisierung der Kamerarolle funktioniert unter Android und iOS.';

  @override
  String get cameraRollOff =>
      'Die Synchronisierung der Kamerarolle ist deaktiviert.';

  @override
  String cameraRollUploading(int total, int done) {
    return 'Hochladen: $done von $total...';
  }

  @override
  String cameraRollWaitingUntil(String time) {
    return 'Warten bis $time.';
  }

  @override
  String get cameraRollNextAttempt => 'zum nächsten Versuch';

  @override
  String cameraRollFailedRetrying(String reason, String time) {
    return 'Fehlgeschlagen: $reason – erneuter Versuch um $time';
  }

  @override
  String cameraRollFailed(String reason) {
    return 'Fehlgeschlagen: $reason';
  }

  @override
  String get cameraRollUnknownReason => 'unbekannter Grund';

  @override
  String get cameraRollWaitingForPhotos => 'Warten auf neue Fotos.';

  @override
  String cameraRollNothingNew(String time) {
    return 'Nichts Neues, überprüft um $time.';
  }

  @override
  String cameraRollSynced(int stored, int count, String time, int present) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other:
          '$count Fotos wurden um $time synchronisiert ($stored hochgeladen, $present bereits vorhanden).',
      one:
          '1 Foto wurde um $time synchronisiert ($stored hochgeladen, $present bereits vorhanden).',
    );
    return '$_temp0';
  }

  @override
  String cameraRollIndexing(int total, int done) {
    return 'Die Bibliothek wird noch indiziert ($done von $total Ordnern); Fotos, die sich bereits in einem nicht indizierten Album befinden, werden möglicherweise erneut hochgeladen.';
  }

  @override
  String get cameraRollNoSources =>
      'Wählen Sie die Alben aus, die synchronisiert werden sollen.';

  @override
  String get cameraRollNewSource =>
      'Fotos eines neu ausgewählten Albums werden von Anfang an abgerufen.';

  @override
  String backgroundLastRunFailed(String problem, String time) {
    return 'Die letzte Hintergrundsynchronisierung um $time ist fehlgeschlagen: $problem';
  }

  @override
  String backgroundLastRun(int stored, String time, int present) {
    return 'Letzte Hintergrundsynchronisierung um $time: $stored hochgeladen, $present bereits vorhanden';
  }

  @override
  String get backgroundSyncOff =>
      'Die Synchronisierung der Kamerarolle ist deaktiviert.';

  @override
  String get backgroundSyncDidNotRun =>
      'Die Synchronisierung wurde nicht ausgeführt.';

  @override
  String backgroundTaskFailed(String problem) {
    return 'Die Hintergrund-Synchronisierungsaufgabe ist fehlgeschlagen: $problem';
  }

  @override
  String get offlineRefusal =>
      'Offline: Für Änderungen ist der Server erforderlich. Versuchen Sie es erneut, sobald er wieder erreichbar ist.';

  @override
  String get offlineNoServer => 'Offline – der Server ist nicht erreichbar.';

  @override
  String offlineShowingCopy(String time) {
    return 'Offline – Anzeige der Kopie vom $time';
  }

  @override
  String get invitationUsedNotSignedIn =>
      'Diese Einladung wurde bereits verwendet. Wenn Sie sie auf einem anderen Gerät angenommen haben, melden Sie sich hier mit einem Gerätecode von diesem Gerät an; andernfalls fordern Sie eine neue Einladung an.';

  @override
  String get invitationUsedSignedIn =>
      'Diese Einladung wurde bereits verwendet – Sie sind hier bereits angemeldet.';

  @override
  String invitationUsedSignedInAs(String user) {
    return 'Diese Einladung wurde bereits verwendet – Sie sind hier als $user angemeldet.';
  }

  @override
  String get invitationExpiredNotice =>
      'Diese Einladung ist abgelaufen. Fordern Sie eine neue an.';

  @override
  String get invitationWithdrawnNotice =>
      'Diese Einladung wurde zurückgezogen.';

  @override
  String get invitationNotOfThisServer =>
      'Dies ist keine Einladung dieses Servers.';

  @override
  String loadingFailed(String problem) {
    return 'Laden fehlgeschlagen: $problem';
  }

  @override
  String get noDataLoaded => 'Es wurden keine Daten geladen';

  @override
  String get tryAgain => 'Erneut versuchen';

  @override
  String noSuchImage(String name) {
    return 'Kein solches Bild: $name';
  }

  @override
  String noAlternatives(String name) {
    return 'Keine Alternativen für das Bild: $name';
  }

  @override
  String uploadFailed(String problem) {
    return 'Upload fehlgeschlagen: $problem';
  }

  @override
  String get retry => 'Erneut versuchen';

  @override
  String get personsMenuEntry => 'Personen in diesem Album';

  @override
  String get personsTitle => 'Personen';

  @override
  String get personsReadOnlyNotice =>
      'Nur Bearbeiter dürfen Gesichter benennen';

  @override
  String get personsPendingNotice =>
      'Es wird noch nach Gesichtern in diesem Album gesucht…';

  @override
  String get personsEmptyNotice =>
      'In diesem Album wurde kein Gesicht gefunden.';

  @override
  String get personsUnknownGroup => 'Wer ist das?';

  @override
  String personsUnknownGroupNumbered(int number) {
    return 'Wer ist das? (Gruppe $number)';
  }

  @override
  String get personsNotAFaceGroup => 'Kein Gesicht';

  @override
  String get personsNewGroup => 'Neue Gruppe';

  @override
  String personsFaceCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Gesichter',
      one: '1 Gesicht',
    );
    return '$_temp0';
  }

  @override
  String personsSelectedCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Gesichter ausgewählt',
      one: '1 Gesicht ausgewählt',
    );
    return '$_temp0';
  }

  @override
  String personsSuggestedHeading(String name) {
    return 'Ist das $name?';
  }

  @override
  String get personsConfirmSuggestion => 'Bestätigen';

  @override
  String get personsChooseTitle => 'Diese Gesichter benennen';

  @override
  String get personsSearchLabel => 'Suche';

  @override
  String get personsNewPersonEntry => 'Neue Person…';

  @override
  String get personsNewPersonTitle => 'Neue Person';

  @override
  String get personsNameLabel => 'Name';

  @override
  String get personsNobodyYet => 'In diesem Bereich ist noch niemand benannt.';

  @override
  String get personsRenameEntry => 'Umbenennen…';

  @override
  String get personsRenameTitle => 'Person umbenennen';

  @override
  String get personsRenameNotice => 'Benennt diese Person überall um.';

  @override
  String get personsMergeEntry => 'Zusammenführen mit…';

  @override
  String get personsMergeTitle => 'Mit einer anderen Person zusammenführen';

  @override
  String get personsMergeNotice =>
      'Die Gesichter dieser Person werden der anderen Person zugeordnet. Es wird nichts gelöscht.';

  @override
  String get personsDiscardTitle =>
      'Änderungen an den Personen dieses Albums verwerfen?';

  @override
  String get personsDiscardMessage =>
      'Die Entscheidungen hier sind nicht gespeichert. Verwerfen zeigt die Gesichter wieder so, wie sie auf dem Server sind.';

  @override
  String get personsSaveTitle =>
      'Änderungen an den Personen dieses Albums speichern?';

  @override
  String get personsSaveMessage =>
      'Wenn Sie diese Seite verlassen, endet die Bearbeitung. Nicht gespeicherte Entscheidungen gehen verloren.';

  @override
  String get personsDragToGroup => 'Auf eine Gruppe ziehen';

  @override
  String get personsShowPhoto => 'Foto anzeigen';

  @override
  String get personsNameEntry => 'Person benennen…';

  @override
  String get personsDeferEntry => 'Zurückstellen (neue Gruppe)';

  @override
  String get personsNotAFaceEntry => 'Kein Gesicht';

  @override
  String get personsSomeoneElse => 'Jemand anderes…';

  @override
  String get personsForgetEntry => 'Entscheidung zurücknehmen';

  @override
  String get personsForgetTarget => 'Zurücknehmen';

  @override
  String personsMemberBadge(String name) {
    return 'Mitglied $name';
  }

  @override
  String get personsLinkMeEntry => 'Das bin ich';

  @override
  String get personsLinkMemberEntry => 'Mit einem Mitglied verknüpfen…';

  @override
  String get personsUnlinkEntry => 'Verknüpfung aufheben';

  @override
  String get personsLinkChooseTitle => 'Welches Mitglied ist das?';

  @override
  String get personsLinkChooseNotice =>
      'Ein Mitglied ist höchstens eine Person.';

  @override
  String get personsLinkNobodyFree =>
      'Jedes Mitglied dieses Bereichs ist bereits einer Person zugeordnet.';

  @override
  String appearsInPhotosAs(String name) {
    return 'Erscheint auf Fotos als $name';
  }

  @override
  String get viewerEditPersons => 'Personen bearbeiten';

  @override
  String get viewerEditPersonsDone => 'Benennen der Gesichter beenden';

  @override
  String get viewerMarkFace => 'Ein Gesicht markieren';

  @override
  String get viewerMarkFaceHint =>
      'Ziehen Sie ein Rechteck um das Gesicht einer Person, oder tippen Sie auf das Gesicht.';

  @override
  String get viewerAdjustFaceHint =>
      'Ziehen Sie das Feld, um es zu verschieben, oder eine Ecke, um die Größe anzupassen.';

  @override
  String get viewerFaceDecision => 'Dieses Gesicht';

  @override
  String get viewerMarkFaceTooSmall =>
      'Zeichnen Sie ein größeres Rechteck um das Gesicht der Person.';

  @override
  String get personsChooserInAlbum => 'In diesem Album';

  @override
  String get personsChooserAll => 'Alle Personen';

  @override
  String loadNotFoundAlbum(String server, String name) {
    return 'Das Album „$name“ wurde auf dem Server $server nicht gefunden.';
  }

  @override
  String loadNotFoundEntry(String server, String name) {
    return 'Das Album oder der Ordner „$name“ wurde auf dem Server $server nicht gefunden.';
  }

  @override
  String loadNotFoundStart(String server) {
    return 'Die Startseite wurde auf dem Server $server nicht gefunden.';
  }

  @override
  String loadFailedAlbum(String server, String name) {
    return 'Das Album „$name“ konnte auf dem Server $server nicht geöffnet werden.';
  }

  @override
  String loadFailedEntry(String server, String name) {
    return 'Das Album oder der Ordner „$name“ konnte auf dem Server $server nicht geöffnet werden.';
  }

  @override
  String loadFailedStart(String server) {
    return 'Die Startseite konnte auf dem Server $server nicht geöffnet werden.';
  }

  @override
  String get goToStartPage => 'Zur Startseite';

  @override
  String get loadFailureDetails => 'Details';

  @override
  String loadFailureTechnical(String url, int status) {
    return 'HTTP-$status für $url';
  }

  @override
  String uploadNotTaken(String reasons, int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Dateien wurden nicht hochgeladen:',
      one: 'Eine Datei wurde nicht hochgeladen:',
    );
    return '$_temp0 $reasons';
  }

  @override
  String uploadFormatRefused(String names) {
    return 'Der Server akzeptiert eine dieser Dateien nicht: $names. Aus diesem Stapel wurde nichts gespeichert.';
  }

  @override
  String cameraRollSkipped(String names, int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other:
          '$count Dateien wurden übersprungen, ihr Format unterstützt der Server nicht:',
      one:
          'Eine Datei wurde übersprungen, ihr Format unterstützt der Server nicht:',
    );
    return '$_temp0 $names.';
  }

  @override
  String get aboutMenuEntry => 'Über VAlbum';

  @override
  String get aboutDescription =>
      'Ein selbst gehostetes Foto- und Videoalbum. Die Fotos verbleiben auf dem eigenen Server des Besitzers, und der Server verändert die Originale niemals.';

  @override
  String aboutVersion(String version) {
    return 'Version $version';
  }

  @override
  String get aboutSourceCode => 'Quellcode, Dokumentation und Fehlerberichte:';

  @override
  String get aboutGeoNames =>
      'Die Ortsnamen stammen von GeoNames und stehen unter der Creative Commons Attribution 4.0-Lizenz:';

  @override
  String get aboutLicense =>
      'Freie Software unter der GNU Affero General Public License, Version 3 oder höher.';

  @override
  String identifySharedBy(String sharer) {
    return 'Geteilt von $sharer';
  }

  @override
  String get identifyFirstOpenIntro =>
      'Dieser Link wurde Ihnen zugesandt. Bitte bestätigen Sie Ihren Namen.';

  @override
  String get identifyNameLabel => 'Ihr Name';

  @override
  String identifyNotice(String sharer) {
    return 'Bei den Fotos, die Sie hinzufügen, sieht $sharer Ihren Namen.';
  }

  @override
  String get identifyNoticeNobody =>
      'Die Person, die dieses Album geteilt hat, sieht Ihren Namen bei den Fotos, die Sie hinzufügen.';

  @override
  String get identifyRemember => 'Auf diesem Gerät angemeldet bleiben';

  @override
  String get identifyContinue => 'Weiter';

  @override
  String get identifyWhoTitle => 'Wer sind Sie?';

  @override
  String get identifyRecipientIntro =>
      'Dieser Link wurde bereits in einem anderen Browser geöffnet. Bestätigen Sie, dass Sie es sind, um das Album hier zu öffnen.';

  @override
  String get identifyOpenIntro =>
      'Dieses Album ist mit allen geteilt, die sagen, wer sie sind. Bestätigen Sie Ihre E-Mail-Adresse, um es zu öffnen.';

  @override
  String identifySendCodeTo(String address) {
    return 'Code an $address senden';
  }

  @override
  String get identifyAddressLabel => 'Ihre E-Mail-Adresse';

  @override
  String get identifySendCode => 'Code senden';

  @override
  String identifyCodeSent(String address) {
    return 'Ein Code wurde an $address gesendet.';
  }

  @override
  String get identifyCodeLabel => 'Code aus der E-Mail';

  @override
  String get identifyConfirmCode => 'Bestätigen';

  @override
  String identifyContinueWith(String provider) {
    return 'Mit $provider fortfahren';
  }

  @override
  String identifyAskAgain(String sharer) {
    return 'Bitten Sie $sharer, Ihnen den Link erneut zu senden.';
  }

  @override
  String get identifyAskAgainNobody =>
      'Bitten Sie die Person, die ihn geteilt hat, Ihnen den Link erneut zu senden.';

  @override
  String signedInAsContact(String name) {
    return 'Angemeldet als $name';
  }

  @override
  String get switchPerson => 'Nicht Sie? Person wechseln';

  @override
  String get linkTypeHeading => 'Wer ihn öffnen darf';

  @override
  String get linkTypeAnonymous => 'Jeder mit dem Link (anonym)';

  @override
  String get linkTypeOpenPersonal => 'Jeder mit dem Link (personalisiert)';

  @override
  String get linkTypeSelected => 'Ausgewählte Kontakte';

  @override
  String get linkTypeNeedsProof =>
      'Für „Personalisiert“ ist ein E-Mail-Konto oder eine Google-Anmeldung auf dem Server erforderlich.';

  @override
  String get recipientsHeading => 'Empfänger';

  @override
  String get recipientsSearch => 'Kontakte suchen';

  @override
  String get recipientsNoContacts => 'Noch keine Kontakte.';

  @override
  String get recipientsNoMatch => 'Es wurde kein Kontakt gefunden.';

  @override
  String get recipientsNeeded => 'Wählen Sie mindestens einen Empfänger aus.';

  @override
  String get newContact => 'Neuer Kontakt';

  @override
  String get newContactName => 'Name';

  @override
  String get newContactEmail => 'E-Mail';

  @override
  String get newContactRemove => 'Entfernen';

  @override
  String get newContactInvalid => 'Keine E-Mail-Adresse';

  @override
  String newContactAlready(String name) {
    return 'Bereits in Ihren Kontakten als $name gespeichert; markiert.';
  }

  @override
  String get recipientLinksHeading =>
      'Jedem Empfänger seinen eigenen Link senden';

  @override
  String get recipientLinksOnce =>
      'Jeder Link wird nur jetzt angezeigt und erkennt seinen Empfänger. Versenden Sie ihn mit Ihrem eigenen E-Mail-Programm oder Chat.';

  @override
  String sendEmailTo(String address) {
    return 'E-Mail an $address';
  }

  @override
  String sendWhatsAppTo(String number) {
    return 'WhatsApp an $number';
  }

  @override
  String sendSmsTo(String number) {
    return 'SMS an $number';
  }

  @override
  String get sendOtherApp => 'Andere App…';

  @override
  String get copyLinkAction => 'Link kopieren';

  @override
  String shareMessageSubject(String album) {
    return 'Fotos: $album';
  }

  @override
  String shareMessageBody(String album, String name, String link) {
    return 'Hallo $name,\n\nhier sind die Fotos von $album:\n$link\n';
  }

  @override
  String get launchFailed =>
      'Keine App auf diesem Gerät kann diesen Link öffnen.';

  @override
  String get linkPersonalOpen => 'Personalisiert';

  @override
  String linkRecipientCount(int count) {
    return 'Empfänger: $count';
  }

  @override
  String get sendAgain => 'Erneut senden';

  @override
  String sendAgainHeading(String name) {
    return 'Ein neuer Link für $name';
  }

  @override
  String get sendAgainNote =>
      'Der frühere Link dieses Empfängers funktioniert nicht mehr.';

  @override
  String get linkDeliveryHeading => 'So wird der Link versendet';

  @override
  String get linkDeliveryEach => 'Ein Link für jede Person';

  @override
  String get linkDeliveryGroup => 'Ein Link für die Gruppe';

  @override
  String get groupLinkMailAll => 'E-Mail an alle Empfänger';

  @override
  String get groupLinkNote =>
      'Wer den Link öffnet, bestätigt einmal seine E-Mail-Adresse – per Code oder per Anmeldung.';

  @override
  String get groupLinkWithoutEmail =>
      'Diese Empfänger haben keine E-Mail-Adresse, sodass der Gruppenlink sie nicht erkennen kann. Senden Sie ihnen einen eigenen Link:';

  @override
  String shareMessageBodyGroup(String album, String link) {
    return 'Hallo,\n\nhier sind die Fotos von $album:\n$link\n';
  }

  @override
  String get identifyGroupIntro =>
      'Dieser Link wurde an eine Gruppe gesendet. Bestätigen Sie Ihre E-Mail-Adresse, um ihn zu öffnen.';

  @override
  String get cropMenu => 'Zuschneiden…';

  @override
  String get cropTitle => 'Zuschneiden';

  @override
  String get cropReset => 'Zurücksetzen';

  @override
  String get cropAspectImage => 'Seitenverhältnis des Bildes';

  @override
  String get cropAspectFree => 'Freiform';

  @override
  String get cropPortrait => 'Hochformat';

  @override
  String get cropLandscape => 'Querformat';

  @override
  String get cropNote =>
      'Zuschneiden ändert nur die Darstellung des Fotos. Wer es herunterladen darf, erhält weiterhin das ganze Original.';

  @override
  String get cropAreaHint =>
      'Ziehen Sie, um den Rahmen zu zeichnen, zu verschieben oder seine Größe zu ändern; tippen Sie in den Rahmen, um ihn zu übernehmen.';

  @override
  String get recipientsPickEmail => 'E-Mail-Adresse aus meinen Kontakten…';

  @override
  String get recipientsPickPhone => 'Telefonnummer aus meinen Kontakten…';

  @override
  String recipientsPickFailed(String reason) {
    return 'Die Kontakte dieses Telefons konnten nicht geöffnet werden ($reason).';
  }

  @override
  String get addEmailOffer =>
      'Fügen Sie Ihre E-Mail-Adresse hinzu, damit wir Sie auf anderen Geräten wiedererkennen können.';

  @override
  String get addEmailOpen => 'E-Mail-Adresse hinzufügen';

  @override
  String get addEmailNotNow => 'Jetzt nicht';

  @override
  String get addEmailTitle => 'Ihre E-Mail-Adresse hinzufügen';

  @override
  String get addEmailExplanation =>
      'Wir senden einen Code an diese Adresse. Sobald Sie diesen bestätigt haben, können Sie damit Ihre Links auf anderen Geräten öffnen.';

  @override
  String get addEmailDone => 'Ihre E-Mail-Adresse wurde gespeichert.';

  @override
  String get labelSelectionAction => 'Label…';

  @override
  String labelDialogTitle(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos',
      one: 'ein Foto',
    );
    return 'Labels von $_temp0';
  }

  @override
  String get labelDialogHelp =>
      'Ein markiertes Label wird jedem ausgewählten Foto zugewiesen, ein unmarkiertes wird entfernt. Ein Label ist eine Ansicht dieses Albums: Die Chips über den Fotos zeigen dies an, und über einen Freigabelink können die Fotos eines einzelnen Labels angezeigt werden.';

  @override
  String get labelNoneYet => 'Dieses Album hat noch keine Labels.';

  @override
  String get labelNewField => 'Neues Label';

  @override
  String get labelNewAdd => 'Dieses Label hinzufügen';

  @override
  String get labelApply => 'Anwenden';

  @override
  String labelChipTooltip(String label) {
    return 'Nur die Fotos mit dem Label „$label“ anzeigen; erneut antippen, um alle anzuzeigen';
  }

  @override
  String labelFilterHidesAll(String label) {
    return 'Kein Foto mit dem Label „$label“ passiert den Bewertungsfilter.';
  }

  @override
  String get labelRename => 'Label umbenennen…';

  @override
  String get labelDelete => 'Label entfernen…';

  @override
  String labelRenameTitle(String label) {
    return 'Benennen Sie das Label „$label“ um.';
  }

  @override
  String get labelRenameField => 'Neuer Name';

  @override
  String get labelRenameHelp =>
      'Das Label wird auf jedem Foto dieses Albums umbenannt. Ein Freigabelink, der das Foto anzeigt, folgt dem neuen Namen.';

  @override
  String labelDeleteTitle(String label) {
    return 'Soll das Label „$label“ entfernt werden?';
  }

  @override
  String get labelDeleteExplanation =>
      'Das Label wird von jedem Foto dieses Albums entfernt; die Fotos bleiben erhalten. Ein Freigabelink, der dieses Label anzeigt, zeigt nichts an.';

  @override
  String get labelDeleteConfirm => 'Entfernen';

  @override
  String get labelFilterHeading => 'Fotos';

  @override
  String get labelFilterWholeAlbum => 'Das gesamte Album';

  @override
  String labelFilterOnly(String label) {
    return 'Nur Fotos mit dem Label „$label“';
  }

  @override
  String linkShowsLabel(String label) {
    return 'Nur „$label“';
  }

  @override
  String get peopleLeadInviter =>
      'Ihre Einladungen, die noch niemand angenommen hat.';

  @override
  String trashSeveralQuestion(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos in den Papierkorb verschieben?',
      one: 'Ein Foto in den Papierkorb verschieben?',
    );
    return '$_temp0';
  }

  @override
  String get moveToTrash => 'In den Papierkorb verschieben';

  @override
  String inboxTooltip(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos warten',
      one: '1 Foto wartet',
      zero: 'nichts wartet',
    );
    return 'Posteingang: $_temp0';
  }

  @override
  String inboxMenuEntry(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count warten',
      zero: 'nichts wartet',
    );
    return 'Posteingang ($_temp0)';
  }

  @override
  String cameraRollInboxTarget(String name) {
    return 'Neue Fotos landen im Posteingang des Servers: $name.';
  }

  @override
  String get noticeNoInbox =>
      'Der Server gibt keinen Posteingang für dieses Gerät an. Bitten Sie den Administrator um die Berechtigung, Fotos hinzuzufügen.';

  @override
  String get duplicatesMenuEntry => 'Fotos in mehreren Alben';

  @override
  String duplicatesPageTitle(int count) {
    return 'Fotos in mehreren Alben ($count)';
  }

  @override
  String get duplicatesEmpty =>
      'Kein Foto befindet sich in mehr als einem Album.';

  @override
  String duplicatesIndexing(int total, int done) {
    return 'Die Bibliothek wird noch indexiert ($done von $total Ordnern); Fotos in noch nicht indexierten Ordnern fehlen hier.';
  }

  @override
  String duplicatesCopies(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'in $count Alben',
      one: 'in 1 Album',
    );
    return '$_temp0';
  }

  @override
  String get duplicatesSpaceRoot => 'Startseite';

  @override
  String get duplicatesBack => 'Zurück zu den Fotos in mehreren Alben';

  @override
  String get duplicatesDeleteTooltip =>
      'In den Papierkorb dieses Albums verschieben';

  @override
  String get duplicatesDeleteTitle => 'In den Papierkorb verschieben?';

  @override
  String duplicatesDeleteQuestion(String album, String name) {
    return 'Das Foto „$name“ in „$album“ in den Papierkorb verschieben? Es kann aus dem Papierkorb dieses Albums wiederhergestellt werden.';
  }

  @override
  String get duplicatesDeleteConfirm => 'In den Papierkorb verschieben';

  @override
  String duplicatesDeleted(String album, String name) {
    return 'Das Foto „$name“ wurde in den Papierkorb von „$album“ verschoben.';
  }

  @override
  String get linkAnonymous => 'Anonym';

  @override
  String get recipientNotOpened => 'Noch nicht geöffnet';

  @override
  String openedOn(String day) {
    return 'Geöffnet am $day';
  }

  @override
  String lastSeenOn(String day) {
    return 'Zuletzt da am $day';
  }

  @override
  String photosAdded(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos hinzugefügt',
      one: '1 Foto hinzugefügt',
    );
    return '$_temp0';
  }

  @override
  String get shutOutMark => 'Ausgesperrt';

  @override
  String get shutOutOfLink => 'Von diesem Link aussperren';

  @override
  String get letInAgain => 'Wieder zulassen';

  @override
  String get contactsHeading => 'Kontakte';

  @override
  String get contactsLead =>
      'Die Personen, an die Ihre persönlichen Links gingen oder die sie geöffnet haben. Alle Mitglieder sehen sie.';

  @override
  String get noContacts => 'Noch keine Kontakte.';

  @override
  String get contactRename => 'Umbenennen…';

  @override
  String get contactRenameTitle => 'Kontakt umbenennen';

  @override
  String get contactRenameNote =>
      'Bereits hinzugefügte Fotos behalten den alten Namen.';

  @override
  String get contactSessionsEntry => 'Angemeldete Browser…';

  @override
  String contactSessionCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'In $count Browsern angemeldet',
      one: 'In 1 Browser angemeldet',
      zero: 'Nicht angemeldet',
    );
    return '$_temp0';
  }

  @override
  String contactSessionsTitle(String name) {
    return 'Browser von $name';
  }

  @override
  String contactSessionSince(String day) {
    return 'Seit $day';
  }

  @override
  String contactSessionVia(String label) {
    return 'über „$label“';
  }

  @override
  String get noContactSessions => 'Nirgends angemeldet.';

  @override
  String get endSession => 'Beenden';

  @override
  String get endAllSessions => 'Alle beenden';

  @override
  String get shutOutEverywhere => 'Von allen Links aussperren';

  @override
  String deleteContactTitle(String name) {
    return '$name löschen?';
  }

  @override
  String get deleteContactMessage =>
      'Adressen und Anmeldungen des Kontakts werden gelöscht, die an ihn gesendeten Links funktionieren nicht mehr. Seine Fotos bleiben und behalten seinen Namen.';

  @override
  String get contactProvenAddress => 'Vom Kontakt bestätigt';

  @override
  String contactOwnName(String name) {
    return 'Eigener Name: $name';
  }

  @override
  String otherSessionsSignOut(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Auch in $count anderen Browsern angemeldet – andere abmelden',
      one: 'Auch in 1 anderen Browser angemeldet – andere abmelden',
    );
    return '$_temp0';
  }

  @override
  String get otherSessionsEnded => 'Ihre anderen Browser sind abgemeldet.';

  @override
  String get shutOutEverywhereMark => 'Überall ausgesperrt';

  @override
  String get identifyTotp => 'Code aus Ihrer Authenticator-App';

  @override
  String get totpCodeLabel => 'Code aus der App';

  @override
  String get signInOffer =>
      'Möchten Sie auch auf Ihren anderen Geräten erkannt werden?';

  @override
  String get signInOfferOpen => 'Einrichten';

  @override
  String get signInOptionsEntry => 'Anmeldeoptionen…';

  @override
  String get signInOptionsTitle => 'Anmeldeoptionen';

  @override
  String get signInOptionsLead =>
      'Optional: woran Sie erkannt werden, wenn Sie den Link auf einem anderen Gerät öffnen.';

  @override
  String get authenticatorHeading => 'Authenticator-App';

  @override
  String authenticatorActiveSince(String date) {
    return 'Eingerichtet am $date.';
  }

  @override
  String get authenticatorExplanation =>
      'Eine App wie Google Authenticator zeigt alle 30 Sekunden einen neuen Code an.';

  @override
  String get authenticatorSetUp => 'Authenticator-App verwenden';

  @override
  String get totpScan => 'Scannen Sie diesen Code mit Ihrer Authenticator-App.';

  @override
  String get totpOrEnterKey =>
      'Oder geben Sie diesen Einrichtungsschlüssel in der App ein:';

  @override
  String get totpOnThisPhone =>
      'Fügen Sie den Eintrag der Authenticator-App auf diesem Telefon hinzu:';

  @override
  String get totpAddToApp => 'Zur Authenticator-App hinzufügen';

  @override
  String get totpLinkNote =>
      'Falls sich keine App öffnet, wählen Sie in Ihrer App „Einrichtungsschlüssel eingeben“.';

  @override
  String get totpShowKey => 'Einrichtungsschlüssel anzeigen';

  @override
  String get totpEnterCode =>
      'Geben Sie anschließend den Code ein, den die App anzeigt.';

  @override
  String get totpKeyCopied => 'Einrichtungsschlüssel kopiert.';

  @override
  String get contactSignInsEntry => 'Anmeldemethoden…';

  @override
  String contactSignInsTitle(String name) {
    return 'Wie sich $name anmeldet';
  }

  @override
  String get contactSignInsNone =>
      'Kein Passkey und keine Authenticator-App eingerichtet.';

  @override
  String get contactAuthenticatorMark => 'Authenticator-App';

  @override
  String contactAuthenticatorRemoveTitle(String name) {
    return 'Authenticator-App von $name entfernen?';
  }

  @override
  String get contactSignInRemoveMessage =>
      'Ihre Codes gelten dann nicht mehr. Der Kontakt kann sie neu einrichten.';

  @override
  String get identifyPasskey => 'Mit Passkey anmelden';

  @override
  String get passkeyCancelled => 'Es wurde kein Passkey verwendet.';

  @override
  String passkeyFailed(String reason) {
    return 'Der Browser konnte keinen Passkey verwenden ($reason).';
  }

  @override
  String get passkeysHeading => 'Passkeys';

  @override
  String get passkeyExplanation =>
      'Ihr Telefon oder Browser bewahrt den Passkey auf und überträgt ihn auf Ihre anderen Geräte. Kein Passwort, kein Code.';

  @override
  String get passkeyAdd => 'Mich auf meinen anderen Geräten erkennen';

  @override
  String passkeyFrom(String date) {
    return 'Passkey vom $date';
  }

  @override
  String passkeyLastUsed(String date) {
    return 'Zuletzt verwendet am $date';
  }

  @override
  String contactPasskeyCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Passkeys',
      one: '1 Passkey',
    );
    return '$_temp0';
  }

  @override
  String contactPasskeyRemoveTitle(String name) {
    return 'Diesen Passkey von $name entfernen?';
  }

  @override
  String doingCollecting(String target) {
    return 'Hinzufügen zur Sammlung $target';
  }

  @override
  String get createCollection => 'Neue Sammlung';

  @override
  String get newCollectionTitle => 'Neue Sammlung';

  @override
  String get newCollectionHint =>
      'Eine Sammlung zeigt Fotos anderer Alben, ohne sie zu kopieren.';

  @override
  String get collectionTakesNoMove =>
      'Eine Sammlung enthält keine Dateien; Fotos kommen mit „Zur Sammlung hinzufügen“ hinein.';

  @override
  String get pickerNeedsCollection =>
      'Fotos kommen in eine Sammlung – öffnen oder erstellen Sie eine.';

  @override
  String get createNewCollection => 'Neue Sammlung erstellen…';

  @override
  String get nothingToCollect => 'Wählen Sie zuerst die Fotos aus.';

  @override
  String get addToCollectionTitle => 'Zur Sammlung hinzufügen';

  @override
  String addToCollectionConfirm(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Hier $count Fotos hinzufügen',
      one: 'Hier 1 Foto hinzufügen',
    );
    return '$_temp0';
  }

  @override
  String addedToCollection(int count, String target) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos wurden zu $target hinzugefügt.',
      one: '1 Foto wurde zu $target hinzugefügt.',
      zero: 'Es wurde nichts zu $target hinzugefügt.',
    );
    return '$_temp0';
  }

  @override
  String get collectionPhotoMissing =>
      'Dieses Foto ist nicht mehr in der Bibliothek';

  @override
  String get removeFromCollection => 'Aus der Sammlung entfernen';

  @override
  String removeFromCollectionQuestion(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos aus dieser Sammlung entfernen?',
      one: '1 Foto aus dieser Sammlung entfernen?',
    );
    return '$_temp0';
  }

  @override
  String get removeFromCollectionExplanation =>
      'Die Fotos werden aus dieser Sammlung entfernt. Sie verbleiben in ihren Alben.';

  @override
  String removedFromCollection(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos wurden aus der Sammlung entfernt.',
      one: '1 Foto wurde aus der Sammlung entfernt.',
    );
    return '$_temp0';
  }

  @override
  String get addToCollection => 'Zur Sammlung hinzufügen…';

  @override
  String propertySource(String album) {
    return 'Im Album: $album';
  }

  @override
  String get otherWaysToSignIn => 'Andere Anmeldewege…';

  @override
  String get noOtherWaysToSignIn =>
      'Dieser Server bietet keinen anderen Anmeldeweg an.';

  @override
  String get signInWithCode => 'Anmeldecode verwenden';

  @override
  String get memberSendCodeByEmail => 'Code per E-Mail senden';

  @override
  String get memberNameOrEmailLabel =>
      'Ihr Benutzername oder Ihre E-Mail-Adresse';

  @override
  String get memberCodeSent =>
      'Gehört diese Adresse einem Mitglied hier, ist ein Code unterwegs.';

  @override
  String get memberAddressSignsNobodyIn =>
      'Mit dieser Adresse meldet sich hier niemand an.';

  @override
  String get signInOptionsMemberLead =>
      'Optional: So melden Sie sich auf einem neuen Gerät an, ohne einen Code von einem anderen Ihrer Geräte zu benötigen.';

  @override
  String get memberAddressesHeading => 'E-Mail-Adressen';

  @override
  String get memberAddressesExplanation =>
      'Mit einer bestätigten Adresse melden Sie sich über einen an diese Adresse gesendeten Code oder mit dem dazugehörigen Konto auf der Anmeldeseite und über jeden geteilten Link an.';

  @override
  String get memberAddAddress => 'E-Mail-Adresse hinzufügen';

  @override
  String memberLinkProvider(String provider) {
    return '$provider-Konto verknüpfen';
  }

  @override
  String get memberAddressAdded =>
      'Die Adresse wurde zu Ihren Anmeldewegen hinzugefügt.';

  @override
  String get wayAuthenticator => 'Ihre Authentifizierungs-App';

  @override
  String get wayPasskey => 'einen Passkey';

  @override
  String get wayEmailAddress => 'einen Code an Ihre E-Mail-Adresse';

  @override
  String get noMemberSignIns =>
      'Kein weiterer Anmeldeweg eingerichtet: keine Authenticator-App, kein Passkey, keine E-Mail-Adresse.';

  @override
  String memberSignInsState(String ways) {
    return 'Meldet Sie auch an: $ways';
  }

  @override
  String get memberSignInsEntry => 'Anmeldewege…';

  @override
  String get userSignInsTooltip => 'Anmeldewege';

  @override
  String get userSignInRemoveMessage =>
      'Damit meldet sich dieses Mitglied nicht mehr an. Es kann den Weg erneut einrichten.';

  @override
  String signInAddressRemoveTitle(String address, String name) {
    return 'Soll die Adresse $address von $name entfernt werden?';
  }

  @override
  String get providerNeedsBrowser =>
      'Die Anmeldung mit einem anderen Konto funktioniert nur im Browser.';

  @override
  String providerReturnsElsewhere(String address) {
    return 'Diese Anmeldung würde zu $address zurückführen, nicht zu dieser Seite, und könnte hier nicht abgeschlossen werden. Öffnen Sie $address im Browser und melden Sie sich dort an.';
  }

  @override
  String get providerReturnUnknown =>
      'Diese Seite kommt von einer Anmeldung zurück, die hier nicht begonnen wurde. Starten Sie die Anmeldung erneut.';

  @override
  String get totpCodeIncomplete =>
      'Geben Sie die sechs Ziffern ein, die Ihre Authenticator-App anzeigt.';

  @override
  String get memberNameRequired =>
      'Geben Sie Ihren Benutzernamen oder Ihre E-Mail-Adresse ein.';

  @override
  String get totpQrSemantics =>
      'Einrichtungscode für eine Authenticator-App als QR-Code';

  @override
  String get catchUpHeading => 'Vorbereitung der Alben';

  @override
  String get catchUpAsking => 'Anfrage an den Server…';

  @override
  String catchUpUnavailable(String reason) {
    return 'Der Fortschritt kann nicht gelesen werden: $reason';
  }

  @override
  String catchUpProgress(int done, int total) {
    return 'Vorbereitete Alben: $done von $total';
  }

  @override
  String catchUpComplete(int total) {
    String _temp0 = intl.Intl.pluralLogic(
      total,
      locale: localeName,
      other: 'Alle $total Alben sind vorbereitet',
      one: 'Das eine Album ist vorbereitet',
      zero: 'Keine Alben vorzubereiten',
    );
    return '$_temp0';
  }

  @override
  String get catchUpIdle => 'Derzeit gibt es nichts zu tun.';

  @override
  String catchUpNow(String step, String folder) {
    return 'Aktuell: $step in $folder';
  }

  @override
  String get catchUpYielding =>
      'Pausiert, solange jemandem Fotos gezeigt werden.';

  @override
  String catchUpVideos(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Videos warten',
      one: '1 Video wartet',
      zero: 'Kein Video wartet',
    );
    return '$_temp0';
  }

  @override
  String get catchUpNoFailure => 'Es ist nichts fehlgeschlagen.';

  @override
  String catchUpFailure(String folder, String failure) {
    return 'Letzter Fehler in $folder: $failure';
  }

  @override
  String get catchUpRoot => 'dem obersten Ordner';

  @override
  String get catchUpStepHash => 'Fingerabdrücke';

  @override
  String get catchUpStepPreviews => 'Vorschauen';

  @override
  String get catchUpStepCover => 'Albumcover';

  @override
  String get catchUpStepFaces => 'Gesichter';

  @override
  String get catchUpStepPlaces => 'Orte';

  @override
  String get catchUpStepVideos => 'Videos';

  @override
  String get catchUpStepOther => 'Sonstige Arbeiten';

  @override
  String get uploadTargetHeading => 'Neue Fotos hochladen nach';

  @override
  String get uploadTargetExplanation =>
      'Neue Fotos dieses Geräts landen im Posteingang des Bereichs. Für eine Weile, etwa im Urlaub, können sie stattdessen direkt in ein Album gehen. Das gilt nur für dieses Gerät; andere Geräte füllen weiter den Posteingang.';

  @override
  String get uploadTargetToInbox => 'Neue Fotos landen im Posteingang.';

  @override
  String uploadTargetLine(String album) {
    return 'Neue Fotos landen in „$album“.';
  }

  @override
  String uploadTargetLineUntil(String album, String date) {
    return 'Neue Fotos landen in „$album“ (bis $date).';
  }

  @override
  String get uploadTargetChoose => 'Album auswählen...';

  @override
  String get uploadTargetPickerTitle => 'Neue Fotos hochladen nach...';

  @override
  String get uploadTargetPickHere => 'Hier hochladen';

  @override
  String get uploadTargetNoContribute =>
      'Sie dürfen diesem Album keine Fotos hinzufügen.';

  @override
  String get uploadTargetBackToInbox => 'Zurück zum Posteingang';

  @override
  String get uploadTargetChangeEnd => 'Enddatum ändern';

  @override
  String uploadTargetDialogTitle(String album) {
    return 'Neue Fotos landen in „$album“';
  }

  @override
  String uploadTargetUntil(String date) {
    return 'Bis $date';
  }

  @override
  String get uploadTargetNoEnd => 'Kein Enddatum';

  @override
  String get uploadTargetSetEnd => 'Enddatum festlegen';

  @override
  String get uploadTargetEndExplanation =>
      'Nach dem Enddatum kehrt dieses Gerät von selbst in den Posteingang zurück, damit ein vergessenes Album nicht die Fotos der nächsten Monate verschlingt.';

  @override
  String get uploadTargetSave => 'Speichern';

  @override
  String uploadTargetWentTo(String album) {
    return 'Die neuen Fotos sind in „$album“ gelandet.';
  }

  @override
  String noticeUploadTargetExpired(String album, String date) {
    return 'Das Hochladen nach „$album“ endete am $date; neue Fotos landen wieder im Posteingang.';
  }

  @override
  String noticeUploadTargetGone(String album) {
    return 'Das Album „$album“ gibt es nicht mehr; neue Fotos landen im Posteingang.';
  }

  @override
  String noticeUploadTargetRefused(String album) {
    return 'Sie dürfen „$album“ keine Fotos mehr hinzufügen; neue Fotos landen im Posteingang.';
  }

  @override
  String noticeUploadTargetNotAlbum(String album) {
    return '„$album“ nimmt keine Fotos auf; neue Fotos landen im Posteingang.';
  }

  @override
  String noticeUploadTargetFollowed(String before, String album) {
    return 'Das Album „$before“ heißt jetzt „$album“; neue Fotos landen dort.';
  }

  @override
  String get groupByAction => 'Gruppieren nach…';

  @override
  String get groupByTitle => 'Gruppieren nach';

  @override
  String get groupByScopeAlbum => 'Gilt für das gesamte Album.';

  @override
  String groupByScopeChoiceSelection(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Ausgewählte Fotos ($count)',
      one: 'Ausgewähltes Foto (1)',
    );
    return '$_temp0';
  }

  @override
  String get groupByScopeChoiceAlbum => 'Gesamtes Album';

  @override
  String get groupBySectionKey => 'Abschnitte nach';

  @override
  String get groupBySubsectionKey => 'Unterabschnitte nach';

  @override
  String get groupByKeyNone => 'Keine';

  @override
  String get groupByKeyDay => 'Tag';

  @override
  String get groupByKeyTown => 'Ort';

  @override
  String get groupByKeyDistrict => 'Stadtteil';

  @override
  String get groupByKeyRegion => 'Region';

  @override
  String get groupByKeyCountry => 'Land';

  @override
  String get groupByKeyFeature => 'Sehenswürdigkeit';

  @override
  String get groupByKeyDayAndTown => 'Tag und Ort';

  @override
  String get groupByModeReplace => 'Überschriften ersetzen';

  @override
  String get groupByModeAddSubsections =>
      'Abschnitte beibehalten, Unterabschnitte hinzufügen';

  @override
  String groupByWillReplace(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Die bereits vorhandenen $count Überschriften werden ersetzt.',
      one: 'Die bereits vorhandene Überschrift wird ersetzt.',
    );
    return '$_temp0';
  }

  @override
  String get groupByNothingKeyed =>
      'Keines dieser Fotos enthält diese Informationen: kein Datum oder keine Position mit einem Ort dieser Art in der Nähe. Es wird keine Überschrift erstellt.';

  @override
  String get groupByPreview => 'Zu erstellende Überschriften';
}
