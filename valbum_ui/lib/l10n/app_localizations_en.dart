// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Virtual Photo Album';

  @override
  String get dismiss => 'Dismiss';

  @override
  String get cancel => 'Cancel';

  @override
  String get save => 'Save';

  @override
  String get remove => 'Remove';

  @override
  String get withdraw => 'Withdraw';

  @override
  String get done => 'Done';

  @override
  String get askingServer => 'Asking the server...';

  @override
  String get serverSettingsAction => 'Server settings...';

  @override
  String get signInRequiredTitle => 'Sign-in required';

  @override
  String get signInRequiredNoServer => 'This app talks to no server yet.';

  @override
  String get serverScreenTitle => 'Album server';

  @override
  String get serverUrlHelp =>
      'The address the album server is reached at, as you would open it in a browser, e.g. \'http://nas.local:8080/valbum/\'. Where the server holds several spaces, the address carries the space: \'https://host/valbum/<space>/\'.';

  @override
  String get serverLineNoServer => 'This browser talks to no server yet.';

  @override
  String serverLineTalksTo(String server) {
    return 'This browser talks to $server';
  }

  @override
  String get serverUrlLabel => 'Server URL';

  @override
  String get testConnection => 'Test connection';

  @override
  String get forgetThisServer => 'Forget this server';

  @override
  String get useLoadedServer => 'Use the server this app was loaded from';

  @override
  String get contactingServer => 'Contacting the server...';

  @override
  String get signInHeading => 'Sign in';

  @override
  String get signInCodeExplanation =>
      'Enter the code the server printed at start-up, or a code from one of your devices, or a recovery code your administrator gave you.';

  @override
  String get signInNoServer => 'Name a server above before signing in.';

  @override
  String get signOut => 'Sign out';

  @override
  String get signedOutMessage =>
      'This device no longer identifies itself to the server.';

  @override
  String get userNameHelp =>
      'Your name in this space; it is what the others see and what your photos are attributed to.';

  @override
  String get codeRequiredRefusal => 'Enter the code that signs this device in.';

  @override
  String get signInSucceeded => 'Sign-in succeeded.';

  @override
  String get signingIn => 'Signing in...';

  @override
  String get yourName => 'Your name';

  @override
  String get yourNameHelp => 'How the others on this server see you.';

  @override
  String get userNameLabel => 'User name';

  @override
  String get deviceNameLabel => 'Device name';

  @override
  String get signInCodeLabel => 'Sign-in code';

  @override
  String get signInCodeHelp =>
      'From the server\'s start-up, from My devices on a device you are already signed in on, from your administrator, or your backup code.';

  @override
  String get scanCode => 'Scan code';

  @override
  String otherServerRefusal(String server) {
    return 'This code is for $server, not for the server this page came from. Open that server and sign in there.';
  }

  @override
  String get notADeviceCode => 'This is not a device code.';

  @override
  String invitationMasked(String token) {
    return 'Invitation $token';
  }

  @override
  String get notThisOne => 'Not this one';

  @override
  String get askingAboutInvitation =>
      'Asking the server about this invitation...';

  @override
  String get invitationUnknown =>
      'This server does not know this invitation. Ask for a new one.';

  @override
  String get invitationUnknownHere =>
      'This server does not know this invitation. Ask for a new one, or type the plain server address.';

  @override
  String invitationHeadlineWithRole(String invitedBy, String may) {
    return '$invitedBy invited you to this album server: $may.';
  }

  @override
  String invitationHeadlinePlain(String invitedBy) {
    return '$invitedBy invited you to this album server.';
  }

  @override
  String get peopleHeading => 'People';

  @override
  String get inviteExplanation =>
      'An invitation is a single-use link that creates one account on this server. Send it to the person it is for, and to nobody else.';

  @override
  String get inviteAction => 'Invite…';

  @override
  String get signedInOnThisDevice => 'Signed in on this device';

  @override
  String signedInAsUser(String user) {
    return 'Signed in as $user';
  }

  @override
  String roleLine(String role) {
    return 'Role: $role';
  }

  @override
  String deviceLine(String device) {
    return 'Device: $device';
  }

  @override
  String spaceLine(String space) {
    return 'Space: $space';
  }

  @override
  String get notSignedIn => 'Not signed in';

  @override
  String get deviceUnknownToServer =>
      'This server does not know this device. Sign in again.';

  @override
  String identityUnknown(String problem) {
    return 'The server did not say who this device is: $problem';
  }

  @override
  String get libraryOwner => 'the library owner';

  @override
  String get wholeLibrary => 'the whole library';

  @override
  String get guestLibraryNotice =>
      'Guest: your library is what others share with you.';

  @override
  String get cacheHeading => 'Cache';

  @override
  String get cacheExplanation =>
      'Albums and thumbnails already seen are kept on this device, so that the library can be browsed while the server is away.';

  @override
  String currentlyCached(String size) {
    return 'Currently cached: $size';
  }

  @override
  String get currentlyCachedUnknown => 'Currently cached: ...';

  @override
  String get clearCache => 'Clear cache';

  @override
  String get clearCacheTitle => 'Clear the cache?';

  @override
  String get clearCacheQuestion =>
      'Everything kept for offline browsing is forgotten. It is fetched again the next time the server is reached.';

  @override
  String get clear => 'Clear';

  @override
  String cacheCleared(String size) {
    return 'Cache cleared, $size freed.';
  }

  @override
  String get diagnosticsHeading => 'Diagnostics';

  @override
  String get diagnosticsLead =>
      'What this app did on the network - copy it into a bug report.';

  @override
  String get diagnosticsEmpty =>
      'Nothing logged yet. Test the connection, or browse the album, and what the app asked the server appears here.';

  @override
  String get diagnosticsCopied => 'The diagnostics log is on the clipboard.';

  @override
  String get copy => 'Copy';

  @override
  String connectionSignedInAsOn(String user, String device) {
    return 'Signed in as $user on $device';
  }

  @override
  String get connectionNoSignInNeeded =>
      'Not signed in - this server needs no sign-in';

  @override
  String get connectionSignInForEverything =>
      'Not signed in - this server shows nothing without a sign-in';

  @override
  String get connectionSignInForChanges =>
      'Not signed in - changes need a sign-in';

  @override
  String get notAlbumData =>
      'The answer is not album data — not a VAlbum server?';

  @override
  String get albumServerReached => 'Album server reached';

  @override
  String get albumServerNeedsSignIn =>
      'Album server reached - it needs a sign-in before it shows anything';

  @override
  String get albumServerRefusesThisDevice =>
      'Album server reached - it refuses what this device is signed in as';

  @override
  String get deviceNameThisBrowser => 'This browser';

  @override
  String get deviceNameAndroid => 'Android phone';

  @override
  String get deviceNameIPhone => 'iPhone';

  @override
  String get deviceNameMac => 'Mac';

  @override
  String get deviceNameWindows => 'Windows PC';

  @override
  String get deviceNameLinux => 'Linux PC';

  @override
  String get deviceNameOther => 'My device';

  @override
  String get firstScreenTitle => 'Where is your album?';

  @override
  String get firstScreenLead =>
      'Type the address of your album server, or paste the invitation link you were sent. If somebody showed you a QR code, scan it.';

  @override
  String get firstScreenNoServer =>
      'That is not a server address. It looks like \'http://nas.local:8080/valbum/\'.';

  @override
  String get serverAddressOrLink => 'Server address or link';

  @override
  String get continueAction => 'Continue';

  @override
  String albumServerLine(String server) {
    return 'Album server: $server';
  }

  @override
  String get anotherServer => 'Another server';

  @override
  String get openWithoutSigningIn => 'Open without signing in';

  @override
  String get devicesHeading => 'My devices';

  @override
  String get devicesLead =>
      'Every device you signed in on holds a token of its own. Removing one here makes that token worthless; the device has to sign in again.';

  @override
  String get noDeviceSignedIn => 'No device is signed in.';

  @override
  String thisDeviceNamed(String name) {
    return '$name (this device)';
  }

  @override
  String get pairedAtUnknownTime => 'Paired at an unknown time';

  @override
  String pairedOn(String day) {
    return 'Paired on $day';
  }

  @override
  String get signOutHere => 'Sign out here';

  @override
  String get addDevice => 'Add a device…';

  @override
  String get noBackupCode =>
      'Backup code: none. Without one, signing out of your last device leaves you dependent on your administrator.';

  @override
  String backupCodeMade(String day) {
    return 'Backup code: made on $day. Keep it safe; making a new one withdraws it.';
  }

  @override
  String get createBackupCode => 'Create backup code…';

  @override
  String get createNewBackupCode => 'Create a new backup code…';

  @override
  String get withdrawBackupCodeTitle => 'Withdraw the backup code?';

  @override
  String get withdrawBackupCodeMessage =>
      'The code you wrote down stops working. Signing out of your last device then leaves you dependent on a recovery code from your administrator.';

  @override
  String get backupCodeTitle => 'Backup code';

  @override
  String get backupCodeAdvice =>
      'Write this down and keep it somewhere safe — a password manager, a drawer. It never expires, it works once, and it signs a device in as you, so give it to nobody. This is the only time it is shown.';

  @override
  String get backupCodeNoExpiry => 'This code does not expire. It works once.';

  @override
  String get addDeviceTitle => 'Add a device';

  @override
  String recoveryCodeTitle(String user) {
    return 'Recovery code for $user';
  }

  @override
  String get deviceCodeAdvice =>
      'Type this on the other device within 10 minutes. It signs that device in as you — never give it to anyone else.';

  @override
  String recoveryCodeAdvice(String user) {
    return 'Give this to $user within 10 minutes; it signs one of their devices in as them. It works once — give it to nobody else.';
  }

  @override
  String get deviceCodeQrAdvice =>
      'Or scan this on the other device, at Sign in.';

  @override
  String get deviceCodeLinkAdvice =>
      'Or send this link to the other device and open it in the app:';

  @override
  String get deviceCodeQrSemantics => 'Device code as a QR code';

  @override
  String get copyTheLink => 'Copy the link';

  @override
  String get linkOnClipboard => 'The link is on the clipboard.';

  @override
  String get codeExpired => 'This code has expired.';

  @override
  String codeExpiresIn(String time) {
    return 'Expires in $time';
  }

  @override
  String get newCode => 'New code';

  @override
  String deviceJoined(String name) {
    return '$name joined.';
  }

  @override
  String get signOutThisDeviceTitle => 'Sign out this device?';

  @override
  String get signOutThisDeviceMessage =>
      'This device forgets its sign-in and talks to the server anonymously again. You can sign in again at any time.';

  @override
  String removeDeviceTitle(String name) {
    return 'Remove the device \'$name\'?';
  }

  @override
  String removeDeviceMessage(String name) {
    return '\'$name\' stops being signed in. It has to sign in again before it can change anything.';
  }

  @override
  String lastDeviceWarning(String ways) {
    return 'This is your only signed-in device. To sign in again you need $ways.';
  }

  @override
  String waysOrLast(String rest, String last) {
    return '$rest, or $last';
  }

  @override
  String get wayBackupCode => 'your backup code';

  @override
  String get wayRecoveryFromAdmin => 'a recovery code from your administrator';

  @override
  String get wayRecoveryFromOtherAdmin =>
      'a recovery code from another administrator';

  @override
  String get wayServerRestart =>
      'a restart of the server, which prints a new sign-in code';

  @override
  String get maybeLastDeviceWarning =>
      'This may be your only signed-in device, and the server could not be asked. If it is, you need a recovery code from your administrator, your backup code, or a restart of the server to get back in.';

  @override
  String get permissionMayHeading => 'May';

  @override
  String get permissionSeesHeading => 'Sees';

  @override
  String get mayShareLinksSwitch => 'May share links';

  @override
  String get mayShareLinksExplanation =>
      'May hand out links that open an album for whoever holds them.';

  @override
  String get roleExplanationEdit =>
      'May create albums, change them and add photos.';

  @override
  String get roleExplanationContribute =>
      'May add photos to the albums, but change nothing.';

  @override
  String get roleExplanationView => 'May look at the albums, and nothing more.';

  @override
  String get clearanceExplanationAll =>
      'Sees every image, the private ones included.';

  @override
  String get clearanceExplanationNonPrivate =>
      'Sees every image that is not marked private.';

  @override
  String get clearanceExplanationPublic =>
      'Sees only the images marked public.';

  @override
  String permissionDialogTitle(String user) {
    return 'What $user may do';
  }

  @override
  String get usersHeading => 'Users';

  @override
  String get usersLead =>
      'Everybody who has an account on this server, and what they may do and see in it.';

  @override
  String get recoveryCodeTooltip => 'Recovery code';

  @override
  String get changePermissionTooltip => 'Change what they may do';

  @override
  String removeUserTitle(String user) {
    return 'Remove $user?';
  }

  @override
  String get removeUserMessage =>
      'Their devices are signed out; their photos and their name on them stay.';

  @override
  String get invitedPending => 'Invited (pending)';

  @override
  String invitedForPending(String recipient) {
    return 'Invited for $recipient (pending)';
  }

  @override
  String get withdrawInvitationTitle => 'Withdraw this invitation?';

  @override
  String get withdrawPendingUserMessage =>
      'The link stops working, and the seat it was holding goes.';

  @override
  String get withdrawInvitationMessage =>
      'The link stops working. Somebody who already accepted it keeps their account.';

  @override
  String invitedByUser(String user) {
    return 'invited by $user';
  }

  @override
  String sinceDay(String day) {
    return 'since $day';
  }

  @override
  String librarySpace(String space) {
    return 'library: $space';
  }

  @override
  String deviceCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count devices',
      one: '1 device',
    );
    return '$_temp0';
  }

  @override
  String invitedForRecipient(String recipient) {
    return 'invited for $recipient';
  }

  @override
  String get openInvitationsHeading => 'Open invitations';

  @override
  String get noOpenInvitations => 'No invitation is waiting to be accepted.';

  @override
  String invitationPermissionBy(String permission, String user) {
    return '$permission — invited by $user';
  }

  @override
  String forRecipient(String recipient) {
    return 'for $recipient';
  }

  @override
  String get expiresNever => 'expires: never';

  @override
  String expiredOnDay(String day) {
    return 'expired on $day';
  }

  @override
  String expiresOnDay(String day) {
    return 'expires $day';
  }

  @override
  String permissionSentence(String doing, String seeing, String sharing) {
    return '$doing; $seeing; $sharing.';
  }

  @override
  String get permissionDoingAdmin => 'You manage this server';

  @override
  String get permissionDoingEdit => 'You may edit every album of this space';

  @override
  String get permissionDoingContribute => 'You may add photos to this space';

  @override
  String get permissionDoingView => 'You may look at this space';

  @override
  String get permissionDoingNone => 'You are not signed in';

  @override
  String get permissionSeeingAll => 'you see all images';

  @override
  String get permissionSeeingNonPrivate => 'you see all but the private images';

  @override
  String get permissionSeeingPublic => 'you see the public images';

  @override
  String get permissionSharingMay => 'you may share links';

  @override
  String get permissionSharingMayNot => 'you may not share links';

  @override
  String permissionPhrase(String role, String clearance, String sharing) {
    return '$role — $clearance — $sharing';
  }

  @override
  String get permissionPhraseMayShare => 'may share links';

  @override
  String get permissionPhraseNoLinks => 'no links';

  @override
  String get roleWordAdmin => 'manages this server';

  @override
  String get roleWordEdit => 'may edit the albums';

  @override
  String get roleWordContribute => 'may add photos';

  @override
  String get roleWordView => 'may look';

  @override
  String get roleWordUnknown => 'unknown role';

  @override
  String get roleWordYouAdmin => 'you manage this server';

  @override
  String get roleWordYouEdit => 'you may edit the albums';

  @override
  String get roleWordYouContribute => 'you may add photos';

  @override
  String get roleWordYouView => 'you may look at the albums';

  @override
  String get clearanceWordAll => 'sees all images';

  @override
  String get clearanceWordNonPrivate => 'sees all but the private images';

  @override
  String get clearanceWordPublic => 'sees the public images';

  @override
  String get serverUrlEmpty =>
      'Enter the URL of the album server, e.g. \'http://nas.local:8080/valbum/\'.';

  @override
  String get serverUrlInvalid =>
      'That is not a server address. It looks like \'http://nas.local:8080/valbum/\'.';

  @override
  String get shareLinkRefusal =>
      'This is a link to a shared album, not a sign-in. Open it in a browser to see what was shared with you.';

  @override
  String get close => 'Close';

  @override
  String get create => 'Create';

  @override
  String get back => 'Back';

  @override
  String get stop => 'Stop';

  @override
  String get sharedAlbumFallback => 'Shared album';

  @override
  String get ratingVeryGood => 'Very good';

  @override
  String get ratingGood => 'Good';

  @override
  String get ratingUnrated => 'Unrated';

  @override
  String get ratingPoor => 'Poor';

  @override
  String get ratingTrash => 'Trash';

  @override
  String get ratingFloorEveryPhoto => 'every photo';

  @override
  String ratingFloorAtLeast(String rating) {
    return 'at least $rating';
  }

  @override
  String get showTrash => 'Show trash';

  @override
  String get trashPageTitle => 'Trash bin';

  @override
  String get trashRestore => 'Restore';

  @override
  String get trashPurgeAction => 'Purge…';

  @override
  String get trashPurgeTitle => 'Purge trash';

  @override
  String get trashPurgeMessage =>
      'The photographs are deleted from disk. This cannot be undone.';

  @override
  String get trashPurgeConfirm => 'Delete permanently';

  @override
  String trashPurged(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count photographs were deleted from disk.',
      one: '1 photograph was deleted from disk.',
    );
    return '$_temp0';
  }

  @override
  String get trashEmptyNotice => 'There is nothing in the trash of this album.';

  @override
  String get trashBackToAlbum => 'Back to the album';

  @override
  String doingPurgingTrash(String folder) {
    return 'purging the trash of $folder';
  }

  @override
  String get shareContinueToStart => 'Continue to the start page';

  @override
  String get shareBackToAlbum => 'Back to the shared album';

  @override
  String get expiryNever => 'Never';

  @override
  String get expiryOneDay => '1 day';

  @override
  String get expiryOneWeek => '1 week';

  @override
  String get expiryOneMonth => '1 month';

  @override
  String get expiryPickDate => 'A date…';

  @override
  String shareDialogTitle(String name) {
    return 'Share $name by link';
  }

  @override
  String get shareTargetTopLevel => 'the top level';

  @override
  String get linksHeading => 'Links';

  @override
  String get noLinksYet => 'No links yet.';

  @override
  String get linkNoLabel => '(no label)';

  @override
  String get withdrawTooltip => 'Withdraw…';

  @override
  String get linkNeverExpires => 'never expires';

  @override
  String get linkUpToMembers => 'up to members';

  @override
  String get linkPublicOnly => 'public only';

  @override
  String linkWithdrawnOn(String day) {
    return 'withdrawn $day';
  }

  @override
  String linkInheritedFrom(String folder) {
    return 'inherited from $folder, withdraw it there';
  }

  @override
  String get shareWholeSpace => 'the whole space';

  @override
  String get newLinkTile => 'New link…';

  @override
  String get newLinkHeading => 'New link';

  @override
  String get linkLabelLabel => 'Label';

  @override
  String get linkLabelHelp => 'What this link is, for your own list.';

  @override
  String get expiresHeading => 'Expires';

  @override
  String get showsHeading => 'Shows';

  @override
  String get privacyPublicOnly => 'Public photos only';

  @override
  String get privacyUpToMembers => 'Up to what members see';

  @override
  String get privacyMembersNote =>
      'A private photo is never shown through a link.';

  @override
  String get lowestRatingHeading => 'Lowest rating';

  @override
  String get linkNeverEdits => 'A link never allows editing.';

  @override
  String get createLink => 'Create link';

  @override
  String get theLinkHeading => 'The link';

  @override
  String get shareLinkOnce =>
      'Copy it now: the server keeps only its fingerprint and can never show it again. A lost link is withdrawn and made anew.';

  @override
  String get linkCopied => 'The link was copied.';

  @override
  String get withdrawLinkTitle => 'Withdraw the link?';

  @override
  String withdrawLinkMessage(String link) {
    return 'Anybody holding $link stops seeing the album at once. This cannot be undone; a new link can be made instead.';
  }

  @override
  String get withdrawLinkThisLink => 'this link';

  @override
  String get invitationGuestNote =>
      'A guest has no albums of their own: their library is what others share with them.';

  @override
  String get deviceNameHelp => 'Which of your devices this is.';

  @override
  String get joining => 'Joining...';

  @override
  String get joinAction => 'Join';

  @override
  String invitationJoinedAs(String user) {
    return 'You\'re in as $user.';
  }

  @override
  String get invitationSignedInNote =>
      'This device is signed in; your albums are yours from now on.';

  @override
  String get openYourAlbums => 'Open your albums';

  @override
  String get invitationChooseName => 'Choose the name you want to be known by.';

  @override
  String get inviteDialogTitle => 'Invite somebody';

  @override
  String get inviteRecipientLabel => 'For whom';

  @override
  String get inviteRecipientHelp =>
      'A note to yourself: whom this invitation is for. Optional.';

  @override
  String get inviteNoteLabel => 'Note';

  @override
  String get inviteNoteHelp =>
      'What the invited person reads when they open the link.';

  @override
  String get createInvitation => 'Create invitation';

  @override
  String get theInvitationHeading => 'The invitation';

  @override
  String invitationValidUntil(String day) {
    return 'Valid until $day, and for one person.';
  }

  @override
  String get invitationOnce =>
      'Send it now: the server keeps only its fingerprint and can never show it again. A lost invitation is withdrawn and made anew.';

  @override
  String get invitationCopied => 'The invitation was copied.';

  @override
  String inboxNotChosen(String name) {
    return 'No album chosen - new photos go into \'$name\'';
  }

  @override
  String get cameraRollHeading => 'Camera roll';

  @override
  String get cameraRollExplanation =>
      'New photos taken on this device are uploaded into an album of the library. Nothing is uploaded twice: the server is asked for the content of every photo before it is transferred.';

  @override
  String get cameraRollUploadNew => 'Upload new photos';

  @override
  String get noPhotoLibrary => 'No photo library on this platform';

  @override
  String get onlyOverWifi => 'Only over Wi-Fi';

  @override
  String get onlyOverWifiExplanation =>
      'New photos wait for a Wi-Fi or a wired connection, so that the upload does not eat into a mobile data plan.';

  @override
  String get chooseAction => 'Choose...';

  @override
  String get syncNow => 'Sync now';

  @override
  String get syncAnyway => 'Sync anyway';

  @override
  String get albumsToSync => 'Albums to sync';

  @override
  String photoCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count photos',
      one: '1 photo',
    );
    return '$_temp0';
  }

  @override
  String get saveServerFirst =>
      'Save the server URL first, then choose an album on it.';

  @override
  String get inboxAlbumTitle => 'Inbox album';

  @override
  String cannotList(String problem) {
    return 'Cannot list: $problem';
  }

  @override
  String get newAlbumAction => 'New album...';

  @override
  String get useThisAlbum => 'Use this album';

  @override
  String get libraryBreadcrumb => 'Library';

  @override
  String get noFoldersHere => 'No folders here yet - create one below.';

  @override
  String folderIsAlbum(String title) {
    return 'This is the album \'$title\'. New photos land here.';
  }

  @override
  String get nothingToShow => 'Nothing to show here.';

  @override
  String get newAlbumTitle => 'New album';

  @override
  String get folderNameLabel => 'Folder name';

  @override
  String cannotCreateFolder(String problem, String name) {
    return 'Cannot create \'$name\': $problem';
  }

  @override
  String get backToAlbum => 'Back to the album';

  @override
  String get groupPicture => 'Group picture';

  @override
  String get groupPictureIsThis => 'This image is the group picture';

  @override
  String get useAsGroupPicture => 'Use as group picture';

  @override
  String get videoPreparing => 'The video is being prepared…';

  @override
  String get videoPlayOriginal => 'Play the original';

  @override
  String get videoCannotPlay => 'Cannot play this video.';

  @override
  String get videoNetworkHint =>
      'The server could not be reached, or it refused the video.';

  @override
  String get videoFormatHint =>
      'This device cannot play the format of this video.';

  @override
  String get videoDiagnosticsHint =>
      'The technical details are in the diagnostics log of the server settings.';

  @override
  String get pause => 'Pause';

  @override
  String get play => 'Play';

  @override
  String get photoPickerEntry => 'From the phone\'s photo library...';

  @override
  String get systemPickerEntry => 'Choose files... (max. 100)';

  @override
  String get photoLibraryTitle => 'Photo library';

  @override
  String get allAlbums => 'All albums';

  @override
  String get selectAll => 'All';

  @override
  String get selectNone => 'None';

  @override
  String get photoLibraryNoAccess =>
      'No access to the photo library of this device.';

  @override
  String photoLibraryUnreadable(String problem) {
    return 'The photo library cannot be read: $problem';
  }

  @override
  String photoAlbumUnreadable(String problem) {
    return 'The album cannot be read: $problem';
  }

  @override
  String get photoLibraryNoAlbums =>
      'The photo library of this device holds no albums.';

  @override
  String get photoAlbumEmpty => 'This album holds no photos.';

  @override
  String photoPickerSelected(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count selected',
      one: '1 selected',
      zero: 'Nothing selected',
    );
    return '$_temp0';
  }

  @override
  String photoPickerUpload(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Upload $count photos',
      one: 'Upload 1 photo',
    );
    return '$_temp0';
  }

  @override
  String get uploadProgressTitle => 'Upload photos';

  @override
  String get scanCodeTitle => 'Scan a device code';

  @override
  String get scanCodeAdvice =>
      'Point the camera at the code shown under My devices on the device you are already signed in on.';

  @override
  String get cameraNotAllowed =>
      'This app is not allowed to use the camera. Allow it in the system settings, or type the code instead.';

  @override
  String get cameraUnsupported =>
      'This device cannot scan a code. Type it instead.';

  @override
  String get cameraNotOpened =>
      'The camera could not be opened. Type the code instead.';

  @override
  String get ok => 'OK';

  @override
  String get delete => 'Delete';

  @override
  String get deleteEllipsis => 'Delete…';

  @override
  String get reload => 'Reload';

  @override
  String get up => 'Up';

  @override
  String get home => 'Home';

  @override
  String get upload => 'Upload';

  @override
  String get apply => 'Apply';

  @override
  String get discard => 'Discard';

  @override
  String get stay => 'Stay';

  @override
  String get keepEditing => 'Keep editing';

  @override
  String get refresh => 'Refresh';

  @override
  String get open => 'Open';

  @override
  String get select => 'Select';

  @override
  String get group => 'Group';

  @override
  String get loading => 'Loading...';

  @override
  String get serverMenuEntry => 'Server...';

  @override
  String get titleLabel => 'Title';

  @override
  String get subtitleLabel => 'Subtitle';

  @override
  String get nameLabel => 'Name';

  @override
  String get dateLabel => 'Date';

  @override
  String get commentLabel => 'Comment';

  @override
  String get headingLabel => 'Heading';

  @override
  String get mustNotBeEmpty => 'Must not be empty';

  @override
  String get viewAsYourself => 'Yourself';

  @override
  String get viewAsMembers => 'Members';

  @override
  String get viewAsPublic => 'Public';

  @override
  String get viewAsStateYourself => 'yourself';

  @override
  String get viewAsStateMembers => 'members';

  @override
  String get viewAsStatePublic => 'public';

  @override
  String get viewAsLabel => 'View as';

  @override
  String get previewAsMembers =>
      'Viewing as members - this is what members see';

  @override
  String get previewAsPublic =>
      'Viewing as public - this is what the public sees';

  @override
  String get editHeadingTitle => 'Edit heading';

  @override
  String get insertHeading => 'Insert heading';

  @override
  String get deleteHeadingTooltip => 'Delete heading';

  @override
  String get headingLevelSection => 'Section';

  @override
  String get headingLevelSubsection => 'Subsection';

  @override
  String get addHeading => 'Add heading…';

  @override
  String get headingSelectTooltip => 'Selects the images under this heading';

  @override
  String get alreadyInOrder => 'Already in order';

  @override
  String get noOtherImageFromCamera => 'No other image from this camera';

  @override
  String get nothingToAdjust => 'Nothing to adjust';

  @override
  String saveFailed(String problem) {
    return 'Saving failed: $problem';
  }

  @override
  String get discardChangesTitle => 'Discard the changes to this album?';

  @override
  String get discardChangesMessage =>
      'The changes made here have not been saved. Discarding them shows the album again as the server has it.';

  @override
  String get saveChangesTitle => 'Save the changes to this album?';

  @override
  String get saveChangesMessage =>
      'Leaving the album ends the edit. Unsaved changes are lost unless they are saved now.';

  @override
  String get saveOrDiscardFirst => 'Save or discard your changes first';

  @override
  String get headingCannotMove => 'A heading cannot be moved.';

  @override
  String get shareLinkAction => 'Share link…';

  @override
  String get albumProperties => 'Album properties';

  @override
  String get folderProperties => 'Folder properties';

  @override
  String moveSubjectTo(String subject) {
    return 'Move $subject to…';
  }

  @override
  String get moveAlbumTo => 'Move album to…';

  @override
  String get deleteAlbumAction => 'Delete album…';

  @override
  String deleteSubjectAction(String subject) {
    return 'Delete $subject…';
  }

  @override
  String get sortByDate => 'Sort by date';

  @override
  String get minRatingLabel => 'Minimum rating';

  @override
  String get showMoreImages => 'Show more images';

  @override
  String get showFewerImages => 'Show fewer images';

  @override
  String get findDuplicatesAction => 'Find duplicates...';

  @override
  String get findDuplicatesTitle => 'Find duplicates';

  @override
  String get findDuplicatesMessage =>
      'Every photo of this album that the library already holds somewhere else is taken out of the album and kept aside in the library\'s own folder. Nothing is deleted, and the other copy stays where it is.';

  @override
  String get noDuplicatesFound =>
      'No photo of this album is anywhere else in the library.';

  @override
  String duplicatesSetAside(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other:
          '$count photos were set aside; the copies that stay are elsewhere in the library.',
      one:
          '1 photo was set aside; the copy that stays is elsewhere in the library.',
    );
    return '$_temp0';
  }

  @override
  String get reanalyze => 'Re-read photo details';

  @override
  String get reanalyzeExplanation =>
      'Reads the camera and the position from the files again and fills what is missing. Nothing already stored is changed.';

  @override
  String reanalyzeDone(int examined, int filled) {
    return 'Checked $examined photos; $filled of them gained a camera or a position.';
  }

  @override
  String reanalyzeRunning(int examined, int filled) {
    return 'Still reading in the background: checked $examined photos so far, $filled of them gained a camera or a position.';
  }

  @override
  String get refreshPreviews => 'Refresh previews';

  @override
  String get refreshPreviewsMessage =>
      'The thumbnails and video renditions of this album are thrown away and made anew when they are next shown. The photos themselves are not touched.';

  @override
  String previewsRefreshed(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count cached files thrown away; the previews are made anew.',
      one: '1 cached file thrown away; the previews are made anew.',
    );
    return '$_temp0';
  }

  @override
  String get notAnAlbumAnswer => 'The server did not answer with an album.';

  @override
  String ratingFilterHidesAll(int rating) {
    return 'No image is rated $rating or better - press + (or the + button) to show more.';
  }

  @override
  String get turnRight => 'Turn right';

  @override
  String get turnLeft => 'Turn left';

  @override
  String get flipVertically => 'Flip vertically';

  @override
  String get ungroupAction => 'Ungroup';

  @override
  String get chooseGroupPicture => 'Choose the group picture';

  @override
  String get selectAllFromCamera => 'Select all from this camera';

  @override
  String get adjustRecordingTimeAction => 'Adjust recording time…';

  @override
  String get imageProperties => 'Image properties';

  @override
  String get useAsAlbumPicture => 'Use as album picture';

  @override
  String get albumPictureTooltip => 'Album picture';

  @override
  String privacyControlTooltip(String next, String level) {
    return 'Privacy: $level (tap for $next)';
  }

  @override
  String get groupNeedsTwo => 'Select at least two images to group them';

  @override
  String get dragToReorder => 'Drag to reorder';

  @override
  String partCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count parts',
      one: '1 part',
    );
    return '$_temp0';
  }

  @override
  String get notATime => 'Not a time (yyyy-MM-dd HH:mm:ss)';

  @override
  String appliesToImages(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Applies to $count images',
      one: 'Applies to 1 image',
    );
    return '$_temp0';
  }

  @override
  String useNameDateOne(String time) {
    return 'Use the time in the file name: $time';
  }

  @override
  String useNameDateMany(int count) {
    return 'Use the time in the file name ($count images)';
  }

  @override
  String get adjustRecordingTimeTitle => 'Adjust recording time';

  @override
  String get correctTime => 'Correct time';

  @override
  String get pickDateAndTime => 'Pick date and time';

  @override
  String get adjustRecordingTimeHelp =>
      'The original recording time stays in the photo; the album keeps its own.';

  @override
  String get makeThisAnAlbum => 'Make this an album';

  @override
  String get makeThisAnInbox => 'Make this an inbox';

  @override
  String get inboxExplanation =>
      'Photographs waiting to be sorted, shown by the day they were taken.';

  @override
  String get dateNone => 'Date: none';

  @override
  String dateIs(String date) {
    return 'Date: $date';
  }

  @override
  String get pickDate => 'Pick a date';

  @override
  String get clearDate => 'Remove the date';

  @override
  String get dateFromFolderName => 'Taken from the folder name.';

  @override
  String get dateFromPhotos => 'Taken from the photos.';

  @override
  String get noAlbumPictureHint =>
      'No album picture chosen - choose one on a tile in the edit mode.';

  @override
  String get zoomIn => 'Zoom in';

  @override
  String get zoomOut => 'Zoom out';

  @override
  String get resetCrop => 'Reset the crop';

  @override
  String get privacyPublicName => 'Public';

  @override
  String get privacyMembersName => 'Members';

  @override
  String get privacyPrivateName => 'Private';

  @override
  String get unitDays => 'd';

  @override
  String get unitHours => 'h';

  @override
  String get unitMinutes => 'min';

  @override
  String get unitSeconds => 's';

  @override
  String get useAsFolderPicture => 'Use as folder picture';

  @override
  String get useNoFolderPicture => 'Use no folder picture';

  @override
  String get libraryEmptyNotice => 'There are no albums here yet.';

  @override
  String get libraryEmptyHint =>
      'Create the first one from the menu at the top right.';

  @override
  String get folderEmptyNotice => 'This folder has no albums yet.';

  @override
  String get createAlbum => 'Create album';

  @override
  String get createFolder => 'Create folder';

  @override
  String get applyRule => 'Apply rule';

  @override
  String get moveToAction => 'Move to…';

  @override
  String get nothingToFile => 'Nothing to file.';

  @override
  String filedAlbums(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Filed $count albums.',
      one: 'Filed 1 album.',
    );
    return '$_temp0';
  }

  @override
  String get placementNone => 'no rule';

  @override
  String get placementByYear => 'by year';

  @override
  String get placementByYearMonth => 'by year and month';

  @override
  String get placementHeading => 'Filing rule';

  @override
  String get placementExplanation =>
      'What arrives here is filed into its year folder. What is already here stays until the filing rule is applied from the menu.';

  @override
  String get createAlbumUndatedHint =>
      'Without a date the album stays in this folder.';

  @override
  String get createInboxLabel => 'Inbox';

  @override
  String get createInboxHint =>
      'Photographs waiting to be sorted: shown by the day they were taken, no date and no order of their own.';

  @override
  String get newFolderTitle => 'New folder';

  @override
  String imageCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count images',
      one: '1 image',
    );
    return '$_temp0';
  }

  @override
  String get targetTopLevel => 'the top level';

  @override
  String get pickerTopLevel => 'Top level';

  @override
  String get nothingToMove => 'Nothing to move.';

  @override
  String moveConfirm(String subject, String target) {
    return 'Move $subject to $target';
  }

  @override
  String nothingMovedTo(String target) {
    return 'Nothing moved to $target.';
  }

  @override
  String movedToTarget(String subject, String target) {
    return 'Moved $subject to $target.';
  }

  @override
  String get deleteExplanation =>
      'An album without any image is removed; one with images is moved to the trash folder of the space (nothing is deleted from disk).';

  @override
  String deleteQuestion(String what) {
    return 'Delete $what?';
  }

  @override
  String deletedWhat(String what) {
    return 'Deleted $what.';
  }

  @override
  String newAlbumCreatedEmpty(String target) {
    return 'The new album $target was created and is empty.';
  }

  @override
  String get imagesLiveInAlbums => 'Images live in albums - open one.';

  @override
  String get albumHoldsNoFolders => 'An album holds no folders.';

  @override
  String get folderCannotBeShown => 'This folder cannot be shown.';

  @override
  String get createNewAlbum => 'Create new album…';

  @override
  String get moveTitle => 'Move';

  @override
  String get inboxEmptyNotice => 'Nothing is waiting here.';

  @override
  String get inboxUndatedHeading => 'Undated';

  @override
  String get inboxDeleteExplanation =>
      'The photographs are moved to the trash folder of the space. Nothing is deleted from disk.';

  @override
  String get clearSelection => 'Clear the selection';

  @override
  String selectedCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count images selected',
      one: '1 image selected',
    );
    return '$_temp0';
  }

  @override
  String get selectEverythingBelow => 'Select everything below';

  @override
  String get nothingSelected => 'Nothing is selected.';

  @override
  String get notEditableMessage => 'You may not edit this album.';

  @override
  String get pictureFailedMessage => 'This picture could not be loaded.';

  @override
  String get takeBack => 'Take back…';

  @override
  String get previousImage => 'Previous image';

  @override
  String get nextImage => 'Next image';

  @override
  String get showAlternatives => 'Show the alternatives';

  @override
  String propertyFile(String name) {
    return 'File: $name';
  }

  @override
  String propertyTaken(String time) {
    return 'Taken: $time';
  }

  @override
  String propertyCamera(String camera) {
    return 'Camera: $camera';
  }

  @override
  String propertyLocation(String latitude, String longitude) {
    return 'Location: $latitude, $longitude';
  }

  @override
  String get showOnMap => 'Show on a map';

  @override
  String addedBy(String user) {
    return 'Added by $user';
  }

  @override
  String get rightLabelView => 'View';

  @override
  String get rightLabelDownload => 'Download';

  @override
  String get rightLabelContribute => 'Contribute';

  @override
  String get rightLabelEdit => 'Edit';

  @override
  String get rightExplanationView => 'See the album and its thumbnails';

  @override
  String get rightExplanationDownload => 'Take copies of the originals';

  @override
  String get rightExplanationContribute => 'Add photos';

  @override
  String get rightExplanationEdit => 'Change the album and everything in it';

  @override
  String get rightsPhraseEdit => 'you may change it';

  @override
  String get rightsPhraseContribute => 'you may add photos';

  @override
  String get rightsPhraseDownload => 'you may look and download';

  @override
  String get viewerDownload => 'Download original';

  @override
  String downloadSelection(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Download $count originals',
      one: 'Download 1 original',
    );
    return '$_temp0';
  }

  @override
  String downloadSaved(String name) {
    return 'Saved $name.';
  }

  @override
  String downloadSavedCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Saved $count originals.',
      one: 'Saved 1 original.',
    );
    return '$_temp0';
  }

  @override
  String downloadFailed(String reason) {
    return 'The download failed: $reason';
  }

  @override
  String get rightsPhraseView => 'you may look';

  @override
  String get rightsPhraseNone => 'you may do nothing here';

  @override
  String get sharedWithYou => 'Shared with you';

  @override
  String sharedByOwner(String owner) {
    return 'Shared by $owner';
  }

  @override
  String serverNotReached(String problem) {
    return 'The server could not be reached: $problem';
  }

  @override
  String httpFailure(String doing, int status) {
    return 'HTTP $status while $doing.';
  }

  @override
  String doingLoading(String url) {
    return 'loading $url';
  }

  @override
  String get doingLoadingImage => 'loading the image';

  @override
  String doingStoring(String url) {
    return 'storing $url';
  }

  @override
  String doingCreating(String url) {
    return 'creating $url';
  }

  @override
  String doingUploading(String url) {
    return 'uploading to $url';
  }

  @override
  String doingAsking(String url) {
    return 'asking $url';
  }

  @override
  String doingMoving(String target) {
    return 'moving to $target';
  }

  @override
  String doingDeleting(String folder) {
    return 'deleting in $folder';
  }

  @override
  String doingFiling(String folder) {
    return 'filing in $folder';
  }

  @override
  String doingFindingDuplicates(String folder) {
    return 'looking for duplicates in $folder';
  }

  @override
  String doingReanalyzing(String folder) {
    return 're-reading the photo details in $folder';
  }

  @override
  String doingSigningIn(String url) {
    return 'signing in at $url';
  }

  @override
  String doingRefreshingPreviews(String folder) {
    return 'refreshing the previews of $folder';
  }

  @override
  String serverUnreachableNoPreview(String problem) {
    return 'The server cannot be reached ($problem), so there is nothing to preview.';
  }

  @override
  String serverUnreachableNoCache(String problem) {
    return 'The server cannot be reached ($problem), and nothing is cached for this view.';
  }

  @override
  String notVAlbumServer(String server) {
    return 'The server at $server did not answer with album data - not a VAlbum server, or is the server URL in the settings wrong?';
  }

  @override
  String get noAnswerInTime => 'no answer in time';

  @override
  String get uploadConnectionLost => 'Connection lost';

  @override
  String uploadInterruptedCounts(int total, int onServer, int remaining) {
    return 'Of $total photos, $onServer are on the server; the remaining $remaining can be sent again.';
  }

  @override
  String get uploadCancelled => 'The upload was cancelled.';

  @override
  String get uploadAsking => 'The server is being asked...';

  @override
  String get uploadWaiting => 'Waiting for the server...';

  @override
  String uploadPreparing(int total, int done) {
    return 'Preparing: $done of $total...';
  }

  @override
  String uploadImageCount(int total, int done) {
    return '$done of $total images';
  }

  @override
  String uploadSummary(int stored, int present) {
    return '$stored uploaded, $present already present.';
  }

  @override
  String alreadyInLibrary(String where) {
    return 'Already in the library: $where.';
  }

  @override
  String get noticeGuestNoSpace => 'Ask the admin to give you an album space.';

  @override
  String get noticeNoServerConfigured => 'No album server is configured.';

  @override
  String get noticeOffline => 'Offline: the album server cannot be reached.';

  @override
  String noticeServerUnreachable(String problem) {
    return 'The server cannot be reached ($problem).';
  }

  @override
  String get noticePhotoLibraryUnreadable =>
      'The photo library cannot be read.';

  @override
  String noticePhotoLibraryFailed(String problem) {
    return 'The photo library could not be read: $problem';
  }

  @override
  String get noticePhotoAccessDenied =>
      'Access to the photo library was denied. Allow photo access for VAlbum in the system settings, then try again.';

  @override
  String noticePhotoLibraryOpenFailed(String problem) {
    return 'The photo library cannot be opened: $problem';
  }

  @override
  String noticeAlbumNotOnDevice(String name) {
    return 'The contents of $name are not on this device yet (still in the cloud?).';
  }

  @override
  String noticeBackgroundScheduleFailed(String problem) {
    return 'Background sync could not be scheduled: $problem';
  }

  @override
  String noticeBackgroundUnscheduleFailed(String problem) {
    return 'Background sync could not be switched off: $problem';
  }

  @override
  String get noticeNoNetwork =>
      'No network: the sync waits for a Wi-Fi connection.';

  @override
  String get noticeNoWifiMobile =>
      'No Wi-Fi: the sync is limited to Wi-Fi, and this device is on a mobile connection.';

  @override
  String get noticeNoWifiOther =>
      'No Wi-Fi: the sync is limited to Wi-Fi, and this device is on another network.';

  @override
  String get noticeNoBackgroundSyncHere =>
      'Background sync is not available on this platform; the camera roll syncs while the app is open.';

  @override
  String get noticeNoBackgroundSyncInBrowser =>
      'Background sync is not available in a browser; the camera roll syncs while the app is open.';

  @override
  String get noticeNoBackgroundSyncInApp =>
      'Background sync is not available in this app.';

  @override
  String get noticeNoBackgroundSyncInTest => 'No background sync in this test.';

  @override
  String get noticeNoPhotoLibraryPlatform =>
      'No photo library on this platform - camera-roll sync runs on Android and iOS.';

  @override
  String get noticeNoPhotoLibraryBrowser =>
      'No photo library in a browser - camera-roll sync runs on Android and iOS.';

  @override
  String get cameraRollOff => 'Camera-roll sync is off.';

  @override
  String cameraRollUploading(int total, int done) {
    return 'Uploading $done of $total...';
  }

  @override
  String cameraRollWaitingUntil(String time) {
    return 'Waiting until $time.';
  }

  @override
  String get cameraRollNextAttempt => 'the next attempt';

  @override
  String cameraRollFailedRetrying(String reason, String time) {
    return 'Failed: $reason - retrying at $time';
  }

  @override
  String cameraRollFailed(String reason) {
    return 'Failed: $reason';
  }

  @override
  String get cameraRollUnknownReason => 'unknown reason';

  @override
  String get cameraRollWaitingForPhotos => 'Waiting for new photos.';

  @override
  String cameraRollNothingNew(String time) {
    return 'Nothing new, checked at $time.';
  }

  @override
  String cameraRollSynced(int stored, int count, String time, int present) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other:
          'Synced $count photos at $time ($stored uploaded, $present already there).',
      one:
          'Synced 1 photo at $time ($stored uploaded, $present already there).',
    );
    return '$_temp0';
  }

  @override
  String cameraRollIndexing(int total, int done) {
    return 'The library is still being indexed ($done of $total folders); photos already in an unindexed album may be uploaded again.';
  }

  @override
  String cameraRollInboxGone(String name) {
    return 'The chosen inbox is gone; using \'$name\'.';
  }

  @override
  String get cameraRollNoSources => 'Choose the albums to sync.';

  @override
  String get cameraRollNewSource =>
      'Photos of a newly chosen album are fetched from the beginning.';

  @override
  String backgroundLastRunFailed(String problem, String time) {
    return 'Last background sync at $time failed: $problem';
  }

  @override
  String backgroundLastRun(int stored, String time, int present) {
    return 'Last background sync at $time: $stored uploaded, $present already present';
  }

  @override
  String get backgroundSyncOff => 'The camera-roll sync is switched off.';

  @override
  String get backgroundSyncDidNotRun => 'The sync did not run.';

  @override
  String backgroundTaskFailed(String problem) {
    return 'The background sync task failed: $problem';
  }

  @override
  String get offlineRefusal =>
      'Offline: changes need the server. Retry when it is reachable again.';

  @override
  String get offlineNoServer => 'Offline - the server cannot be reached.';

  @override
  String offlineShowingCopy(String time) {
    return 'Offline - showing the copy from $time';
  }

  @override
  String get invitationUsedNotSignedIn =>
      'This invitation was already used. If you accepted it on another device, sign in here with a device code from that device; otherwise ask for a new invitation.';

  @override
  String get invitationUsedSignedIn =>
      'This invitation was already used — you are already signed in here.';

  @override
  String invitationUsedSignedInAs(String user) {
    return 'This invitation was already used — you are signed in here as $user.';
  }

  @override
  String get invitationExpiredNotice =>
      'This invitation has expired. Ask for a new one.';

  @override
  String get invitationWithdrawnNotice => 'This invitation was withdrawn.';

  @override
  String get invitationNotOfThisServer =>
      'This is not an invitation of this server.';

  @override
  String loadingFailed(String problem) {
    return 'Loading failed: $problem';
  }

  @override
  String get noDataLoaded => 'No data loaded';

  @override
  String get tryAgain => 'Try again';

  @override
  String noSuchImage(String name) {
    return 'No such image: $name';
  }

  @override
  String noAlternatives(String name) {
    return 'No alternatives for image: $name';
  }

  @override
  String uploadFailed(String problem) {
    return 'Upload failed: $problem';
  }

  @override
  String get retry => 'Retry';

  @override
  String get personsMenuEntry => 'Persons in this album';

  @override
  String get personsTitle => 'Persons';

  @override
  String get personsReadOnlyNotice => 'Only editors may name faces';

  @override
  String get personsPendingNotice => 'Still looking for faces in this album…';

  @override
  String get personsEmptyNotice => 'No face was found in this album.';

  @override
  String get personsUnknownGroup => 'Who is this?';

  @override
  String personsUnknownGroupNumbered(int number) {
    return 'Who is this? (group $number)';
  }

  @override
  String get personsNotAFaceGroup => 'Not a face';

  @override
  String get personsNewGroup => 'New group';

  @override
  String personsFaceCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count faces',
      one: '1 face',
    );
    return '$_temp0';
  }

  @override
  String personsSelectedCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count faces selected',
      one: '1 face selected',
    );
    return '$_temp0';
  }

  @override
  String personsSuggestedHeading(String name) {
    return 'Is this $name?';
  }

  @override
  String get personsConfirmSuggestion => 'Confirm';

  @override
  String get personsChooseTitle => 'Name these faces';

  @override
  String get personsSearchLabel => 'Search';

  @override
  String get personsNewPersonEntry => 'New person…';

  @override
  String get personsNewPersonTitle => 'New person';

  @override
  String get personsNameLabel => 'Name';

  @override
  String get personsNobodyYet => 'Nobody is named in this space yet.';

  @override
  String get personsRenameEntry => 'Rename…';

  @override
  String get personsRenameTitle => 'Rename person';

  @override
  String get personsRenameNotice => 'Renames this person everywhere.';

  @override
  String get personsMergeEntry => 'Merge into…';

  @override
  String get personsMergeTitle => 'Merge into another person';

  @override
  String get personsMergeNotice =>
      'The faces of this person become the other person\'s. Nothing is deleted.';

  @override
  String get personsDiscardTitle =>
      'Discard the changes to this album\'s persons?';

  @override
  String get personsDiscardMessage =>
      'The decisions made here have not been saved. Discarding them shows the faces again as the server has them.';

  @override
  String get personsSaveTitle => 'Save the changes to this album\'s persons?';

  @override
  String get personsSaveMessage =>
      'Leaving this page ends the editing. Unsaved decisions are lost unless they are saved now.';

  @override
  String get personsDragToGroup => 'Drag onto a group';

  @override
  String get personsShowPhoto => 'Show the photo';

  @override
  String get personsNameEntry => 'Name person…';

  @override
  String get personsDeferEntry => 'Defer (new group)';

  @override
  String get personsNotAFaceEntry => 'Not a face';

  @override
  String get personsSomeoneElse => 'Someone else…';

  @override
  String get personsForgetEntry => 'Forget the decision';

  @override
  String get personsForgetTarget => 'Forget';

  @override
  String personsMemberBadge(String name) {
    return 'Member $name';
  }

  @override
  String get personsLinkMeEntry => 'This is me';

  @override
  String get personsLinkMemberEntry => 'Link to a member…';

  @override
  String get personsUnlinkEntry => 'Unlink';

  @override
  String get personsLinkChooseTitle => 'Which member is this?';

  @override
  String get personsLinkChooseNotice => 'A member is at most one person.';

  @override
  String get personsLinkNobodyFree =>
      'Every member of this space is somebody already.';

  @override
  String appearsInPhotosAs(String name) {
    return 'Appears in photos as $name';
  }

  @override
  String inboxPhotoCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count photographs',
      one: '1 photograph',
    );
    return '$_temp0';
  }

  @override
  String get viewerEditPersons => 'Edit persons';

  @override
  String get viewerEditPersonsDone => 'Done naming faces';

  @override
  String get viewerMarkFace => 'Mark a face';

  @override
  String get viewerMarkFaceHint =>
      'Draw a rectangle around a person\'s face, or tap on the face.';

  @override
  String get viewerFaceDecision => 'This face';

  @override
  String get viewerMarkFaceTooSmall =>
      'Draw a larger rectangle around the person\'s face.';

  @override
  String get personsChooserInAlbum => 'In this album';

  @override
  String get personsChooserAll => 'All persons';
}
