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
  String get ratingTrash => 'Müll';

  @override
  String get ratingFloorEveryPhoto => 'jedes Foto';

  @override
  String ratingFloorAtLeast(String rating) {
    return 'mindestens $rating';
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
  String get expiryPickDate => 'Ein Datum…';

  @override
  String shareDialogTitle(String name) {
    return '$name per Link freigeben';
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
  String get withdrawTooltip => 'Abheben…';

  @override
  String get linkNeverExpires => 'läuft nie ab';

  @override
  String get linkUpToMembers => 'für Mitglieder';

  @override
  String get linkPublicOnly => 'nur öffentlich';

  @override
  String linkWithdrawnOn(String day) {
    return 'zurückgezogen vor $day';
  }

  @override
  String linkInheritedFrom(String folder) {
    return 'der von $folder geerbt wurde; heben Sie ihn dort auf';
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
  String get linkLabelHelp => 'Was dieser Link ist, für Ihre eigene Liste.';

  @override
  String get expiresHeading => 'Ablaufdatum';

  @override
  String get showsHeading => 'Anzeigen';

  @override
  String get privacyPublicOnly => 'Nur öffentliche Fotos';

  @override
  String get privacyUpToMembers => 'Was die Mitglieder sehen';

  @override
  String get privacyMembersNote =>
      'Ein privates Foto wird niemals über einen Link angezeigt.';

  @override
  String get lowestRatingHeading => 'Niedrigste Bewertung';

  @override
  String get linkNeverEdits => 'Ein Link erlaubt niemals eine Bearbeitung.';

  @override
  String get createLink => 'Link erstellen';

  @override
  String get theLinkHeading => 'Der Link';

  @override
  String get shareLinkOnce =>
      'Kopieren Sie sie jetzt: Der Server speichert nur ihren Fingerabdruck und kann sie nie wieder anzeigen. Ein verlorener Link wird zurückgezogen und neu erstellt.';

  @override
  String get linkCopied => 'Der Link wurde kopiert.';

  @override
  String get withdrawLinkTitle => 'Link zurückziehen?';

  @override
  String withdrawLinkMessage(String link) {
    return 'Jeder, der den $link hat, kann das Album sofort nicht mehr sehen. Dies lässt sich nicht rückgängig machen; stattdessen kann ein neuer Link erstellt werden.';
  }

  @override
  String get withdrawLinkThisLink => 'dieser Link';

  @override
  String get invitationGuestNote =>
      'Ein Gast hat keine eigenen Alben: Seine Bibliothek besteht aus den Inhalten, die andere mit ihm teilen.';

  @override
  String get deviceNameHelp => 'Um welches Ihrer Geräte es sich handelt.';

  @override
  String get joining => 'Beitritt...';

  @override
  String get joinAction => 'Beitreten';

  @override
  String invitationJoinedAs(String user) {
    return 'Du bist als $user dabei.';
  }

  @override
  String get invitationSignedInNote =>
      'Dieses Gerät ist angemeldet; deine Alben gehören ab sofort dir.';

  @override
  String get openYourAlbums => 'Öffne deine Alben';

  @override
  String get invitationChooseName =>
      'Wählen Sie den Namen, unter dem Sie bekannt sein möchten.';

  @override
  String get inviteDialogTitle => 'Jemanden einladen';

  @override
  String get inviteRecipientLabel => 'Für wen';

  @override
  String get inviteRecipientHelp =>
      'Ein Hinweis für Sie selbst: An wen diese Einladung gerichtet ist. Optional.';

  @override
  String get inviteNoteLabel => 'Anmerkung';

  @override
  String get inviteNoteHelp =>
      'Was die eingeladene Person sieht, wenn sie den Link öffnet.';

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
      'Jetzt senden: Der Server speichert nur ihren Fingerabdruck und kann sie niemals wieder anzeigen. Eine verlorene Einladung wird zurückgezogen und neu erstellt.';

  @override
  String get invitationCopied => 'Die Einladung wurde kopiert.';

  @override
  String inboxNotChosen(String name) {
    return 'Kein Album ausgewählt – neue Fotos werden in $name gespeichert';
  }

  @override
  String get cameraRollHeading => 'Kamera-Rolle';

  @override
  String get cameraRollExplanation =>
      'Neue Fotos, die auf diesem Gerät aufgenommen werden, werden in ein Album der Bibliothek hochgeladen. Es wird nichts doppelt hochgeladen: Vor der Übertragung wird der Server nach dem Inhalt jedes Fotos abgefragt.';

  @override
  String get cameraRollUploadNew => 'Neue Fotos hochladen';

  @override
  String get noPhotoLibrary =>
      'Auf dieser Plattform gibt es keine Fotobibliothek';

  @override
  String get onlyOverWifi => 'Nur über WLAN';

  @override
  String get onlyOverWifiExplanation =>
      'Neue Fotos warten auf eine WLAN- oder Kabelverbindung, damit der Upload nicht das mobiles Datenvolumen belastet.';

  @override
  String get chooseAction => 'Auswählen...';

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
  String get saveServerFirst =>
      'Speichern Sie zuerst die Server-URL und wählen Sie dann ein Album auf diesem Server aus.';

  @override
  String get inboxAlbumTitle => 'Album „Posteingang“';

  @override
  String cannotList(String problem) {
    return 'Kann nicht aufgelistet werden: $problem';
  }

  @override
  String get newAlbumAction => 'Neues Album...';

  @override
  String get useThisAlbum => 'Dieses Album verwenden';

  @override
  String get libraryBreadcrumb => 'Bibliothek';

  @override
  String get noFoldersHere =>
      'Hier gibt es noch keine Ordner – erstelle unten einen.';

  @override
  String folderIsAlbum(String title) {
    return 'Dies ist das Album $title. Neue Fotos landen hier.';
  }

  @override
  String get nothingToShow => 'Hier gibt es nichts anzuzeigen.';

  @override
  String get newAlbumTitle => 'Neues Album';

  @override
  String get folderNameLabel => 'Ordnername';

  @override
  String cannotCreateFolder(String problem, String name) {
    return '$name kann nicht erstellt werden: $problem';
  }

  @override
  String get backToAlbum => 'Zurück zum Album';

  @override
  String get groupPicture => 'Gruppenfoto';

  @override
  String get groupPictureIsThis => 'Dieses Bild ist das Gruppenbild';

  @override
  String get useAsGroupPicture => 'Als Gruppenbild verwenden';

  @override
  String get videoPreparing => 'Das Video wird gerade vorbereitet…';

  @override
  String get videoPlayOriginal => 'Die Originaldatei abspielen';

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
      'Die technischen Details finden sich im Diagnoseprotokoll der Servereinstellungen.';

  @override
  String get pause => 'Pause';

  @override
  String get play => 'Abspielen';

  @override
  String get photoPickerEntry => 'Aus der Fotobibliothek des Smartphones...';

  @override
  String get systemPickerEntry => 'Dateien auswählen... (max. 100)';

  @override
  String get photoLibraryTitle => 'Fotogalerie';

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
      'Diese App darf die Kamera nicht verwenden. Erlaube dies in den Systemeinstellungen oder gib stattdessen den Code ein.';

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
  String get viewAsYourself => 'Du selbst';

  @override
  String get viewAsMembers => 'Mitglieder';

  @override
  String get viewAsPublic => 'Öffentlich';

  @override
  String get viewAsStateYourself => 'Sie selbst';

  @override
  String get viewAsStateMembers => 'Mitglieder';

  @override
  String get viewAsStatePublic => 'public';

  @override
  String get viewAsLabel => 'Anzeigen als';

  @override
  String get previewAsMembers =>
      'Ansicht als Mitglied – das sehen die Mitglieder';

  @override
  String get previewAsPublic =>
      'Ansicht als „öffentlich“ – das sieht die Öffentlichkeit';

  @override
  String get editHeadingTitle => 'Überschrift bearbeiten';

  @override
  String get insertHeading => 'Überschrift einfügen';

  @override
  String get deleteHeadingTooltip => 'Überschrift löschen';

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
      'Die hier vorgenommenen Änderungen wurden nicht gespeichert. Wenn Sie sie verwerfen, wird das Album wieder so angezeigt, wie es auf dem Server vorliegt.';

  @override
  String get saveChangesTitle =>
      'Sollen die Änderungen an diesem Album gespeichert werden?';

  @override
  String get saveChangesMessage =>
      'Wenn Sie das Album verlassen, wird der Bearbeitungsvorgang beendet. Nicht gespeicherte Änderungen gehen verloren, sofern sie nicht jetzt gespeichert werden.';

  @override
  String get saveOrDiscardFirst =>
      'Speichern oder verwerfen Sie Ihre Änderungen zuerst';

  @override
  String get headingCannotMove =>
      'Eine Überschrift kann nicht verschoben werden.';

  @override
  String get shareLinkAction => 'Link freigeben…';

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
  String get findDuplicatesAction => 'Duplikate suchen...';

  @override
  String get findDuplicatesTitle => 'Duplikate finden';

  @override
  String get findDuplicatesMessage =>
      'Jedes Foto dieses Albums, das die Bibliothek bereits an anderer Stelle besitzt, wird aus dem Album entfernt und in einem eigenen Ordner der Bibliothek abgelegt. Es wird nichts gelöscht, und die andere Kopie bleibt an ihrem Platz.';

  @override
  String get noDuplicatesFound =>
      'Es gibt nirgendwo sonst in der Bibliothek ein Foto dieses Albums.';

  @override
  String duplicatesSetAside(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other:
          '$count Fotos wurden aussortiert; die verbleibenden Kopien befinden sich an anderer Stelle in der Bibliothek.',
      one:
          '1 Foto wurde beiseitegelegt; die verbleibende Kopie befindet sich an anderer Stelle in der Bibliothek.',
    );
    return '$_temp0';
  }

  @override
  String get refreshPreviews => 'Vorschauen aktualisieren';

  @override
  String get refreshPreviewsMessage =>
      'Die Miniaturansichten und Videodarstellungen dieses Albums werden gelöscht und bei der nächsten Anzeige neu erstellt. Die Fotos selbst bleiben davon unberührt.';

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
    return 'Kein Bild wurde mit $rating oder besser bewertet – drücke + (oder die +-Taste), um weitere anzuzeigen.';
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
  String get adjustRecordingTimeAction => 'Aufnahmedauer anpassen…';

  @override
  String get imageProperties => 'Bildeigenschaften';

  @override
  String get useAsAlbumPicture => 'Als Albumbild verwenden';

  @override
  String get albumPictureTooltip => 'Albumbild';

  @override
  String privacyControlTooltip(String next, String level) {
    return 'Datenschutzstufe: $level (tippen Sie für $next)';
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
    return 'Verwende die Zeitangabe im Dateinamen: $time';
  }

  @override
  String useNameDateMany(int count) {
    return 'Verwende die Zeitangabe im Dateinamen ($count Bilder)';
  }

  @override
  String get adjustRecordingTimeTitle => 'Aufnahmezeit anpassen';

  @override
  String get correctTime => 'Korrekte Zeit';

  @override
  String get pickDateAndTime => 'Datum und Uhrzeit auswählen';

  @override
  String get adjustRecordingTimeHelp =>
      'Die ursprüngliche Aufnahmezeit bleibt im Foto erhalten; das Album behält seine eigene bei.';

  @override
  String get makeThisAnAlbum => 'Als Album speichern';

  @override
  String get makeThisAnInbox => 'Als Posteingang festlegen';

  @override
  String get inboxExplanation =>
      'Fotos, die darauf warten, sortiert zu werden, sortiert nach dem Aufnahmedatum.';

  @override
  String get dateNone => 'Datum: keine Angabe';

  @override
  String dateIs(String date) {
    return 'Datum: $date';
  }

  @override
  String get pickDate => 'Wähle ein Datum aus';

  @override
  String get clearDate => 'Datum entfernen';

  @override
  String get dateFromFolderName => 'Es wird aus dem Ordnernamen übernommen.';

  @override
  String get dateFromPhotos => 'Aus den Fotos übernommen.';

  @override
  String get noAlbumPictureHint =>
      'Es wurde kein Albumbild ausgewählt – wählen Sie im Bearbeitungsmodus eines auf einer Kachel aus.';

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
  String get libraryEmptyNotice => 'Hier gibt es noch keine Alben.';

  @override
  String get libraryEmptyHint =>
      'Erstellen Sie die erste über das Menü oben rechts.';

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
  String get nothingToFile => 'Es gibt nichts einzureichen.';

  @override
  String filedAlbums(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Alben wurden verschoben.',
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
  String get createInboxLabel => 'Posteingang';

  @override
  String get createInboxHint =>
      'Fotos, die darauf warten, sortiert zu werden: sortiert nach dem Tag, an dem sie aufgenommen wurden, ohne Datum und ohne eigene Reihenfolge.';

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
    return 'Es wurde nichts an $target verschoben.';
  }

  @override
  String movedToTarget(String subject, String target) {
    return '$subject wurde nach $target verschoben.';
  }

  @override
  String get deleteExplanation =>
      'Ein Album ohne Bilder wird entfernt; ein Album mit Bildern wird in den Papierkorb des Speicherbereichs verschoben (es wird nichts von der Festplatte gelöscht).';

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
  String get imagesLiveInAlbums =>
      'Bilder befinden sich in Alben – öffne eines.';

  @override
  String get albumHoldsNoFolders => 'Ein Album enthält keine Ordner.';

  @override
  String get folderCannotBeShown =>
      'Dieser Ordner kann nicht angezeigt werden.';

  @override
  String get createNewAlbum => 'Neues Album erstellen…';

  @override
  String get moveTitle => 'Zug';

  @override
  String get inboxEmptyNotice => 'Hier wartet nichts.';

  @override
  String get inboxUndatedHeading => 'Ohne Datum';

  @override
  String get inboxDeleteExplanation =>
      'Die Fotos werden in den Papierkorb des Speicherbereichs verschoben. Es wird nichts von der Festplatte gelöscht.';

  @override
  String get clearSelection => 'Auswahl aufheben';

  @override
  String selectedCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count ausgewählte Bilder',
      one: '1 Bild ausgewählt',
    );
    return '$_temp0';
  }

  @override
  String get selectEverythingBelow => 'Alles darunter auswählen';

  @override
  String get nothingSelected => 'Es ist nichts ausgewählt.';

  @override
  String get notEditableMessage => 'Du darfst dieses Album nicht bearbeiten.';

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
  String propertyLocation(String latitude, String longitude) {
    return 'Standort: $latitude, $longitude';
  }

  @override
  String get showOnMap => 'Auf der Karte anzeigen';

  @override
  String addedBy(String user) {
    return 'Hinzugefügt von $user';
  }

  @override
  String get rightLabelView => 'Anzeigen';

  @override
  String get rightLabelDownload => 'Herunterladen';

  @override
  String get rightLabelContribute => 'Beitrag leisten';

  @override
  String get rightLabelEdit => 'Bearbeiten';

  @override
  String get rightExplanationView =>
      'Das Album und seine Vorschaubilder anzeigen';

  @override
  String get rightExplanationDownload => 'Kopien der Originale anfertigen';

  @override
  String get rightExplanationContribute => 'Fotos hinzufügen';

  @override
  String get rightExplanationEdit =>
      'Das Album und seinen gesamten Inhalt ändern';

  @override
  String get rightsPhraseEdit => 'Du kannst das ändern';

  @override
  String get rightsPhraseContribute => 'Du kannst Fotos hinzufügen';

  @override
  String get rightsPhraseDownload => 'Sie können ihn ansehen und herunterladen';

  @override
  String get rightsPhraseView => 'Sie können einen Blick hineinwerfen';

  @override
  String get rightsPhraseNone => 'Hier können Sie nichts tun';

  @override
  String get sharedWithYou => 'Wurde für dich freigegeben';

  @override
  String sharedByOwner(String owner) {
    return 'Freigegeben von $owner';
  }

  @override
  String serverNotReached(String problem) {
    return 'Der Server konnte nicht erreicht werden: $problem';
  }

  @override
  String httpFailure(String doing, int status) {
    return 'HTTP-$status während $doing.';
  }

  @override
  String doingLoading(String url) {
    return 'Laden von $url';
  }

  @override
  String get doingLoadingImage => 'das Bild wurde geladen';

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
    return 'Hochladen an $url';
  }

  @override
  String doingAsking(String url) {
    return 'Abfrage von $url';
  }

  @override
  String doingMoving(String target) {
    return 'Wechsel zu $target';
  }

  @override
  String doingDeleting(String folder) {
    return 'Löschen im $folder';
  }

  @override
  String doingFiling(String folder) {
    return 'Ablage im $folder';
  }

  @override
  String doingFindingDuplicates(String folder) {
    return 'Suche nach Duplikaten im $folder';
  }

  @override
  String doingSigningIn(String url) {
    return 'Anmeldung unter $url';
  }

  @override
  String doingRefreshingPreviews(String folder) {
    return 'Aktualisierung der Vorschauen von $folder';
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
    return 'Der Server unter $server hat keine Albumdaten zurückgegeben – es handelt sich also nicht um einen VAlbum-Server, oder ist die Server-URL in den Einstellungen falsch?';
  }

  @override
  String get noAnswerInTime => 'keine Antwort innerhalb der vorgegebenen Zeit';

  @override
  String get uploadConnectionLost => 'Verbindung unterbrochen';

  @override
  String uploadInterruptedCounts(int total, int onServer, int remaining) {
    return 'Von $total Fotos befinden sich $onServer auf dem Server; die verbleibenden $remaining können erneut gesendet werden.';
  }

  @override
  String get uploadCancelled => 'Der Upload wurde abgebrochen.';

  @override
  String get uploadAsking => 'Der Server wird gerade abgefragt...';

  @override
  String get uploadWaiting => 'Warten auf den Server...';

  @override
  String uploadPreparing(int total, int done) {
    return 'Vorbereitung: $done von $total...';
  }

  @override
  String uploadImageCount(int total, int done) {
    return '$done von $total Bildern';
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
      'Bitte den Administrator, dir Speicherplatz für ein Album zuzuweisen.';

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
    return 'Hochladen von $done von $total...';
  }

  @override
  String cameraRollWaitingUntil(String time) {
    return 'Warten bis $time.';
  }

  @override
  String get cameraRollNextAttempt => 'beim nächsten Versuch';

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
  String cameraRollInboxGone(String name) {
    return 'Der ausgewählte Posteingang ist nicht mehr vorhanden; es wird $name verwendet.';
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
    return 'Ladefehler: $problem';
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
      'Nur Redakteure dürfen Gesichter benennen';

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
      other: '$count Personen',
      one: '1 Person',
    );
    return '$_temp0';
  }

  @override
  String personsSelectedCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count ausgewählte Gesichter',
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
  String get personsChooseTitle => 'Benenne diese Gesichter';

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
  String get personsMergeEntry => 'Mit … zusammenführen…';

  @override
  String get personsMergeTitle => 'Mit einer anderen Person zusammenführen';

  @override
  String get personsMergeNotice =>
      'Die Gesichter dieser Person werden zu denen der anderen Personen. Es wird nichts gelöscht.';

  @override
  String get personsDiscardTitle =>
      'Sollen die Änderungen an den Personen in diesem Album verworfen werden?';

  @override
  String get personsDiscardMessage =>
      'Die hier getroffenen Einstellungen wurden nicht gespeichert. Wenn Sie sie verwerfen, werden die Gesichter wieder so angezeigt, wie sie auf dem Server gespeichert sind.';

  @override
  String get personsSaveTitle =>
      'Sollen die Änderungen an den Personen in diesem Album gespeichert werden?';

  @override
  String get personsSaveMessage =>
      'Wenn Sie diese Seite verlassen, wird die Bearbeitung beendet. Nicht gespeicherte Änderungen gehen verloren, sofern sie nicht jetzt gespeichert werden.';

  @override
  String get personsDragToGroup => 'Auf eine Gruppe ziehen';

  @override
  String get personsShowPhoto => '„Foto anzeigen“';

  @override
  String get personsNameEntry => 'Person benennen…';

  @override
  String get personsDeferEntry => 'Zurückstellen (neue Gruppe)';

  @override
  String get personsNotAFaceEntry => 'Kein Gesicht';

  @override
  String get personsSomeoneElse => 'Jemand anderes…';

  @override
  String get personsForgetEntry => 'Entscheidung rückgängig machen';

  @override
  String get personsForgetTarget => 'Vergessen';

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
      'Jedes Element dieses Raums ist bereits jemand.';

  @override
  String appearsInPhotosAs(String name) {
    return 'Erscheint auf Fotos als $name';
  }

  @override
  String inboxPhotoCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count Fotos',
      one: '1 Foto',
    );
    return '$_temp0';
  }

  @override
  String get viewerEditPersons => 'Personen bearbeiten';

  @override
  String get viewerEditPersonsDone => 'Benennen der Gesichter beenden';

  @override
  String get viewerMarkFace => 'Ein Gesicht markieren';

  @override
  String get viewerMarkFaceHint =>
      'Zeichnen Sie ein Rechteck um das Gesicht einer Person oder tippen Sie auf das Gesicht.';

  @override
  String get viewerFaceDecision => 'Dieses Gesicht';

  @override
  String get viewerMarkFaceTooSmall =>
      'Zeichnen Sie ein größeres Rechteck um das Gesicht der Person.';

  @override
  String get personsChooserInAlbum => 'In diesem Album';

  @override
  String get personsChooserAll => 'Alle Personen';
}
