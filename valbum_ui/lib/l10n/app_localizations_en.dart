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
  String get signedOutMessage => 'This device is signed out.';

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
  String invitationHeadlineWithRole(String invitedBy, String role) {
    return '$invitedBy invited you to this album server as $role.';
  }

  @override
  String invitationHeadlinePlain(String invitedBy) {
    return '$invitedBy invited you to this album server.';
  }

  @override
  String get peopleHeading => 'Members';

  @override
  String get inviteExplanation =>
      'A single-use link that creates one account in this space. Send it only to the person it is for.';

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
      'The problems this app ran into - copy them into a bug report.';

  @override
  String get diagnosticsEmpty => 'No problems recorded.';

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
  String get permissionMayHeading => 'Role';

  @override
  String get permissionSeesHeading => 'Sees';

  @override
  String get mayShareLinksSwitch => 'May share links';

  @override
  String get mayShareLinksExplanation =>
      'Links open an album for whoever holds them.';

  @override
  String get roleExplanationEdit =>
      'May create albums, change them and add photos.';

  @override
  String get roleExplanationContribute =>
      'May add photos to the albums, but change nothing.';

  @override
  String get roleExplanationView => 'May look at the albums, and nothing more.';

  @override
  String permissionDialogTitle(String user) {
    return 'What $user may do';
  }

  @override
  String get usersLead =>
      'Everybody with an account in this space, and the invitations nobody has accepted yet.';

  @override
  String get recoveryCodeTooltip => 'Recovery code';

  @override
  String get changePermissionTooltip => 'Change permission';

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
  String get withdrawInvitationMessage => 'The invitation link stops working.';

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
  String get noOpenInvitations => 'No invitation is waiting to be accepted.';

  @override
  String get expiresNever => 'expires: never';

  @override
  String expiredOnDay(String day) {
    return 'expired on $day';
  }

  @override
  String expiresOnDay(String day) {
    return 'expires on $day';
  }

  @override
  String get permissionSeeingAll => 'Sees: all photos';

  @override
  String get permissionSeeingNonPrivate => 'Sees: public and members\' photos';

  @override
  String get permissionSeeingPublic => 'Sees: public photos';

  @override
  String get permissionSharingMay => 'May share links';

  @override
  String get permissionSharingMayNot => 'May not share links';

  @override
  String get roleWordAdmin => 'Administrator';

  @override
  String get roleWordEdit => 'Editor';

  @override
  String get roleWordContribute => 'Contributor';

  @override
  String get roleWordView => 'Viewer';

  @override
  String get roleWordUnknown => 'Unknown role';

  @override
  String get clearanceWordAll => 'All photos';

  @override
  String get clearanceWordNonPrivate => 'Public and members\' photos';

  @override
  String get clearanceWordPublic => 'Public photos';

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
  String get shareLinkNotValid =>
      'This link is not valid. Ask whoever sent it to you for a new one.';

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
  String get expiryPickDate => 'On a date…';

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
  String get deleteLinkTooltip => 'Delete link…';

  @override
  String get linkNeverExpires => 'never expires';

  @override
  String get linkUpToMembers => 'up to members';

  @override
  String get linkPublicOnly => 'public only';

  @override
  String linkInheritedFrom(String folder) {
    return 'inherited from $folder, delete it there';
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
  String get privacyPublicOnly => 'Public photos';

  @override
  String get privacyUpToMembers => 'Also members-only photos';

  @override
  String get privacyMembersNote =>
      'A private photo is never shown through a link.';

  @override
  String get lowestRatingHeading => 'Lowest rating';

  @override
  String get linkRightsHelp => 'Viewing is always allowed, editing never.';

  @override
  String get linkRatingAllButTrash => 'Every photo but the trash';

  @override
  String linkRatingAtLeast(String rating) {
    return 'At least $rating';
  }

  @override
  String get createLink => 'Create link';

  @override
  String get theLinkHeading => 'The link';

  @override
  String get shareLinkOnce =>
      'Copy it now: the server keeps only its fingerprint and can never show it again. A lost link is deleted and made anew.';

  @override
  String get linkCopied => 'The link was copied.';

  @override
  String get deleteLinkTitle => 'Delete link';

  @override
  String deleteLinkNamed(String link) {
    return 'Delete the link $link? Whoever has it can no longer open it.';
  }

  @override
  String get deleteLinkUnnamed =>
      'Delete this link? Whoever has it can no longer open it.';

  @override
  String get deleteLinkPersonalNote =>
      'The links sent to its recipients stop working too. The contacts stay.';

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
  String get invitationSignedInNote => 'This device is signed in.';

  @override
  String get openYourAlbums => 'Open the albums';

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
  String get newAlbumTitle => 'New album';

  @override
  String get backToAlbum => 'Back to the album';

  @override
  String get groupPicture => 'Group picture';

  @override
  String get groupPictureIsThis => 'This image is the group picture';

  @override
  String get useAsGroupPicture => 'Use as group picture';

  @override
  String get videoPreparing =>
      'The playable version of this video is still being made.';

  @override
  String get videoPlayOriginal => 'Play the original';

  @override
  String get videoNeedsRendition =>
      'This browser cannot play the original of this video; it plays as soon as the server has converted it.';

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
      'Details for a bug report: the diagnostics log in the server settings.';

  @override
  String videoPreparingRetry(int seconds, int attempt, int attempts) {
    String _temp0 = intl.Intl.pluralLogic(
      seconds,
      locale: localeName,
      other: 'Asking again in $seconds seconds ($attempt of $attempts).',
      one: 'Asking again in 1 second ($attempt of $attempts).',
    );
    return '$_temp0';
  }

  @override
  String get videoPendingGaveUp =>
      'The playable version is still being made — try again in a minute.';

  @override
  String get videoTryAgain => 'Try again';

  @override
  String videoConversionFailed(String reason) {
    return 'The server could not convert this video: $reason';
  }

  @override
  String get videoPlayingOriginal => 'The original is played instead.';

  @override
  String get videoFetchedWithoutSignIn =>
      'The browser fetched the video without the sign-in and was refused.';

  @override
  String videoFormatRefused(String contentType) {
    return 'This browser or device cannot play this format ($contentType).';
  }

  @override
  String videoNotFetched(String contentType) {
    return 'The server delivers this video ($contentType), but the player could not fetch it.';
  }

  @override
  String videoServerRefused(String message, int status) {
    return 'The server refused the video with status $status: $message';
  }

  @override
  String videoServerRefusedBare(int status) {
    return 'The server refused the video with status $status.';
  }

  @override
  String videoServerUnreachable(String problem) {
    return 'The server could not be reached: $problem';
  }

  @override
  String videoDidNotStart(int seconds) {
    String _temp0 = intl.Intl.pluralLogic(
      seconds,
      locale: localeName,
      other: 'The video did not start within $seconds seconds.',
      one: 'The video did not start within 1 second.',
    );
    return '$_temp0';
  }

  @override
  String videoSilentDelivers(String contentType) {
    return 'The server delivers it ($contentType), but the player neither started nor reported an error.';
  }

  @override
  String get videoUnknownType => 'no type given';

  @override
  String get videoNoticeDismiss => 'Dismiss';

  @override
  String get pause => 'Pause';

  @override
  String get play => 'Play';

  @override
  String get photoPickerEntry => 'From the phone\'s photo library...';

  @override
  String get systemPickerEntry => 'Choose files... (max. 100)';

  @override
  String get pickerPhotosAndVideos => 'Photos and videos';

  @override
  String get pickerAllFiles => 'All files';

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
  String get reanalyze => 'Re-read photo details';

  @override
  String get reanalyzeExplanation =>
      'Reads the camera and the position from the files again and fills what is missing. It also corrects the recording times that an older version read in the wrong time zone. Apart from that, nothing already stored is changed.';

  @override
  String reanalyzeDone(int examined, int filled) {
    return 'Checked $examined photos; $filled of them gained a camera or a position.';
  }

  @override
  String reanalyzeRunning(int examined, int filled) {
    return 'Still reading in the background: checked $examined photos so far, $filled of them gained a camera or a position.';
  }

  @override
  String reanalyzeDatesCorrected(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Corrected the recording times of $count photos.',
      one: 'Corrected the recording time of 1 photo.',
    );
    return '$_temp0';
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
  String get addStarAction => 'Add star';

  @override
  String get removeStarAction => 'Remove star';

  @override
  String get starredBadge => 'Starred';

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
  String propertyRaw(String name) {
    return 'Raw file: $name';
  }

  @override
  String propertyLocation(String latitude, String longitude) {
    return 'Location: $latitude, $longitude';
  }

  @override
  String get showOnMap => 'Show on a map';

  @override
  String propertyPlace(String place) {
    return 'Place: $place';
  }

  @override
  String get showCoordinates => 'Show the coordinates';

  @override
  String get hideCoordinates => 'Hide the coordinates';

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
  String get viewerDownloadRaw => 'Download raw file';

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
  String get downloadCancelled => 'The download was cancelled.';

  @override
  String downloadProgress(int current, int count, String received) {
    return 'File $current of $count: $received downloaded';
  }

  @override
  String downloadProgressOf(
      int current, String total, int count, String received) {
    return 'File $current of $count: $received of $total downloaded';
  }

  @override
  String get selectPhotos => 'Select photos…';

  @override
  String get selectModeLeave => 'Leave the selection mode';

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
    return '$done of $total images sent';
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
  String get noticeMediaLocationNotGranted =>
      'Open the app once so it may read where the photos were taken; until then the background sync uploads nothing.';

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
  String get viewerEditPersons => 'Edit persons';

  @override
  String get viewerEditPersonsDone => 'Done naming faces';

  @override
  String get viewerMarkFace => 'Mark a face';

  @override
  String get viewerMarkFaceHint =>
      'Draw a rectangle around a person\'s face, or tap on the face.';

  @override
  String get viewerAdjustFaceHint =>
      'Drag the box to move it, or a corner to resize it.';

  @override
  String get viewerFaceDecision => 'This face';

  @override
  String get viewerMarkFaceTooSmall =>
      'Draw a larger rectangle around the person\'s face.';

  @override
  String get personsChooserInAlbum => 'In this album';

  @override
  String get personsChooserAll => 'All persons';

  @override
  String loadNotFoundAlbum(String server, String name) {
    return 'The album \'$name\' was not found on the server $server.';
  }

  @override
  String loadNotFoundEntry(String server, String name) {
    return 'The album or folder \'$name\' was not found on the server $server.';
  }

  @override
  String loadNotFoundStart(String server) {
    return 'The start page was not found on the server $server.';
  }

  @override
  String loadFailedAlbum(String server, String name) {
    return 'The album \'$name\' could not be opened on the server $server.';
  }

  @override
  String loadFailedEntry(String server, String name) {
    return 'The album or folder \'$name\' could not be opened on the server $server.';
  }

  @override
  String loadFailedStart(String server) {
    return 'The start page could not be opened on the server $server.';
  }

  @override
  String get goToStartPage => 'Go to the start page';

  @override
  String get loadFailureDetails => 'Details';

  @override
  String loadFailureTechnical(String url, int status) {
    return 'HTTP $status for $url';
  }

  @override
  String uploadNotTaken(String reasons, int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count files were not uploaded:',
      one: 'One file was not uploaded:',
    );
    return '$_temp0 $reasons';
  }

  @override
  String uploadFormatRefused(String names) {
    return 'The server does not take one of these files: $names. Nothing of this batch was stored.';
  }

  @override
  String cameraRollSkipped(String names, int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other:
          '$count items were skipped, their format is not supported by the server:',
      one: 'One item was skipped, its format is not supported by the server:',
    );
    return '$_temp0 $names.';
  }

  @override
  String get aboutMenuEntry => 'About VAlbum';

  @override
  String get aboutDescription =>
      'A self-hosted photo and video album. The photos stay on their owner\'s own server, and the server never changes the originals.';

  @override
  String aboutVersion(String version) {
    return 'Version $version';
  }

  @override
  String get aboutSourceCode => 'Source code, documentation and bug reports:';

  @override
  String get aboutGeoNames =>
      'Place names come from GeoNames, licensed under Creative Commons Attribution 4.0:';

  @override
  String get aboutLicense =>
      'Free software under the GNU Affero General Public License, version 3 or later.';

  @override
  String identifySharedBy(String sharer) {
    return 'Shared by $sharer';
  }

  @override
  String get identifyFirstOpenIntro =>
      'This link was sent to you. Please confirm your name.';

  @override
  String get identifyNameLabel => 'Your name';

  @override
  String identifyNotice(String sharer) {
    return 'With the photos you add, $sharer will see your name.';
  }

  @override
  String get identifyNoticeNobody =>
      'The person who shared this album will see your name with the photos you add.';

  @override
  String get identifyRemember => 'Remember me on this device';

  @override
  String get identifyContinue => 'Continue';

  @override
  String get identifyWhoTitle => 'Who are you?';

  @override
  String get identifyRecipientIntro =>
      'This link was already opened in another browser. Confirm that it is you to open the album here.';

  @override
  String get identifyOpenIntro =>
      'This album is shared with everybody who says who they are. Confirm your e-mail address to open it.';

  @override
  String identifySendCodeTo(String address) {
    return 'Send a code to $address';
  }

  @override
  String get identifyAddressLabel => 'Your e-mail address';

  @override
  String get identifySendCode => 'Send code';

  @override
  String identifyCodeSent(String address) {
    return 'A code was sent to $address.';
  }

  @override
  String get identifyCodeLabel => 'Code from the e-mail';

  @override
  String get identifyConfirmCode => 'Confirm';

  @override
  String identifyContinueWith(String provider) {
    return 'Continue with $provider';
  }

  @override
  String identifyAskAgain(String sharer) {
    return 'Ask $sharer to send you the link again.';
  }

  @override
  String get identifyAskAgainNobody =>
      'Ask the person who shared it to send you the link again.';

  @override
  String signedInAsContact(String name) {
    return 'Signed in as $name';
  }

  @override
  String get switchPerson => 'Not you? Switch person';

  @override
  String get linkTypeHeading => 'Who may open it';

  @override
  String get linkTypeAnonymous => 'Anyone with the link (anonymous)';

  @override
  String get linkTypeOpenPersonal => 'Anyone with the link (personalized)';

  @override
  String get linkTypeSelected => 'Selected contacts';

  @override
  String get linkTypeNeedsProof =>
      '\"Personalized\" needs a mail account or Google sign-in on the server.';

  @override
  String get recipientsHeading => 'Recipients';

  @override
  String get recipientsSearch => 'Search contacts';

  @override
  String get recipientsNoContacts => 'No contacts yet.';

  @override
  String get recipientsNoMatch => 'No contact matches.';

  @override
  String get recipientsNeeded => 'Choose at least one recipient.';

  @override
  String get newContact => 'New contact';

  @override
  String get newContactName => 'Name';

  @override
  String get newContactEmail => 'E-mail';

  @override
  String get newContactRemove => 'Remove';

  @override
  String get newContactInvalid => 'Not an e-mail address';

  @override
  String newContactAlready(String name) {
    return 'Already in your contacts as $name; ticked.';
  }

  @override
  String get recipientLinksHeading => 'Send each recipient their own link';

  @override
  String get recipientLinksOnce =>
      'Each link is shown only now and identifies its recipient. Send it through your own mail program or chat.';

  @override
  String sendEmailTo(String address) {
    return 'E-mail to $address';
  }

  @override
  String sendWhatsAppTo(String number) {
    return 'WhatsApp to $number';
  }

  @override
  String sendSmsTo(String number) {
    return 'SMS to $number';
  }

  @override
  String get sendOtherApp => 'Other app…';

  @override
  String get copyLinkAction => 'Copy link';

  @override
  String shareMessageSubject(String album) {
    return 'Photos: $album';
  }

  @override
  String shareMessageBody(String album, String name, String link) {
    return 'Hello $name,\n\nhere are the photos of $album:\n$link\n';
  }

  @override
  String get launchFailed => 'Nothing on this device opens this link.';

  @override
  String get linkPersonalOpen => 'Personalized';

  @override
  String linkRecipientCount(int count) {
    return 'Recipients: $count';
  }

  @override
  String get sendAgain => 'Send again';

  @override
  String sendAgainHeading(String name) {
    return 'A fresh link for $name';
  }

  @override
  String get sendAgainNote =>
      'The earlier link of this recipient no longer works.';

  @override
  String get linkDeliveryHeading => 'How it is sent';

  @override
  String get linkDeliveryEach => 'A link for each person';

  @override
  String get linkDeliveryGroup => 'One link for the group';

  @override
  String get groupLinkMailAll => 'E-mail to all recipients';

  @override
  String get groupLinkNote =>
      'Whoever opens it confirms their e-mail address once, by a code or by signing in.';

  @override
  String get groupLinkWithoutEmail =>
      'These recipients have no e-mail address, so the group link cannot recognise them. Send them their own link:';

  @override
  String shareMessageBodyGroup(String album, String link) {
    return 'Hello,\n\nhere are the photos of $album:\n$link\n';
  }

  @override
  String get identifyGroupIntro =>
      'This link was sent to a group. Confirm your e-mail address to open it.';

  @override
  String get cropMenu => 'Crop…';

  @override
  String get cropTitle => 'Crop';

  @override
  String get cropReset => 'Reset';

  @override
  String get cropAspectImage => 'Image ratio';

  @override
  String get cropAspectFree => 'Freeform';

  @override
  String get cropPortrait => 'Portrait';

  @override
  String get cropLandscape => 'Landscape';

  @override
  String get cropNote =>
      'A crop only changes how the photo is shown. Whoever may download it still gets the whole original.';

  @override
  String get cropAreaHint =>
      'Drag to draw, move or resize the frame; tap inside it to apply.';

  @override
  String get recipientsPickEmail => 'E-mail address from my contacts…';

  @override
  String get recipientsPickPhone => 'Phone number from my contacts…';

  @override
  String recipientsPickFailed(String reason) {
    return 'Could not open the contacts of this phone ($reason).';
  }

  @override
  String get addEmailOffer =>
      'Add your e-mail so we recognise you on other devices.';

  @override
  String get addEmailOpen => 'Add e-mail';

  @override
  String get addEmailNotNow => 'Not now';

  @override
  String get addEmailTitle => 'Add your e-mail';

  @override
  String get addEmailExplanation =>
      'We send a code to this address. Once you have confirmed it, you can open your links on other devices with it.';

  @override
  String get addEmailDone => 'Your e-mail address is saved.';

  @override
  String get labelSelectionAction => 'Label…';

  @override
  String labelDialogTitle(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count photos',
      one: 'one photo',
    );
    return 'Labels of $_temp0';
  }

  @override
  String get labelDialogHelp =>
      'A ticked label is given to every selected photo, an unticked one is taken off them. A label is a view of this album: the chips above the photos show it, and a share link can show the photos of one label alone.';

  @override
  String get labelNoneYet => 'This album has no labels yet.';

  @override
  String get labelNewField => 'New label';

  @override
  String get labelNewAdd => 'Add this label';

  @override
  String get labelApply => 'Apply';

  @override
  String labelChipTooltip(String label) {
    return 'Show only the photos labeled “$label”; tap again to show them all';
  }

  @override
  String labelFilterHidesAll(String label) {
    return 'No photo with the label “$label” passes the rating filter.';
  }

  @override
  String get labelRename => 'Rename label…';

  @override
  String get labelDelete => 'Remove label…';

  @override
  String labelRenameTitle(String label) {
    return 'Rename the label “$label”';
  }

  @override
  String get labelRenameField => 'New name';

  @override
  String get labelRenameHelp =>
      'The label is renamed on every photo of this album. A share link showing it follows the new name.';

  @override
  String labelDeleteTitle(String label) {
    return 'Remove the label “$label”?';
  }

  @override
  String get labelDeleteExplanation =>
      'The label is taken off every photo of this album; the photos stay. A share link showing this label will show nothing.';

  @override
  String get labelDeleteConfirm => 'Remove';

  @override
  String get labelFilterHeading => 'Photos';

  @override
  String get labelFilterWholeAlbum => 'The whole album';

  @override
  String labelFilterOnly(String label) {
    return 'Only photos with the label “$label”';
  }

  @override
  String linkShowsLabel(String label) {
    return 'Only “$label”';
  }

  @override
  String get peopleLeadInviter =>
      'The invitations you sent that nobody has accepted yet.';

  @override
  String trashSeveralQuestion(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Move $count photos to the trash?',
      one: 'Move 1 photo to the trash?',
    );
    return '$_temp0';
  }

  @override
  String get moveToTrash => 'Move to trash';

  @override
  String inboxTooltip(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count photos waiting',
      one: '1 photo waiting',
      zero: 'nothing waiting',
    );
    return 'Inbox: $_temp0';
  }

  @override
  String inboxMenuEntry(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count waiting',
      zero: 'nothing waiting',
    );
    return 'Inbox ($_temp0)';
  }

  @override
  String cameraRollInboxTarget(String name) {
    return 'New photos go into the inbox of the server: $name.';
  }

  @override
  String get noticeNoInbox =>
      'The server names no inbox for this device. Ask the administrator for the right to add photos.';

  @override
  String get duplicatesMenuEntry => 'Photos in several albums';

  @override
  String duplicatesPageTitle(int count) {
    return 'Photos in several albums ($count)';
  }

  @override
  String get duplicatesEmpty => 'No photo is in more than one album.';

  @override
  String duplicatesIndexing(int total, int done) {
    return 'The library is still being indexed ($done of $total folders); photos in folders not indexed yet are missing here.';
  }

  @override
  String duplicatesCopies(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'in $count albums',
      one: 'in 1 album',
    );
    return '$_temp0';
  }

  @override
  String get duplicatesSpaceRoot => 'Start page';

  @override
  String get duplicatesBack => 'Back to the photos in several albums';

  @override
  String get duplicatesDeleteTooltip => 'Move to the trash of this album';

  @override
  String get duplicatesDeleteTitle => 'Move to the trash?';

  @override
  String duplicatesDeleteQuestion(String album, String name) {
    return 'Move the photo \'$name\' in \'$album\' to the trash? It can be restored from the trash of that album.';
  }

  @override
  String get duplicatesDeleteConfirm => 'Move to trash';

  @override
  String duplicatesDeleted(String album, String name) {
    return 'Moved the photo \'$name\' to the trash of \'$album\'.';
  }

  @override
  String get linkAnonymous => 'Anonymous';

  @override
  String get recipientNotOpened => 'Not opened yet';

  @override
  String openedOn(String day) {
    return 'Opened $day';
  }

  @override
  String lastSeenOn(String day) {
    return 'Last seen $day';
  }

  @override
  String photosAdded(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count photos added',
      one: '1 photo added',
    );
    return '$_temp0';
  }

  @override
  String get shutOutMark => 'Shut out';

  @override
  String get shutOutOfLink => 'Shut out of this link';

  @override
  String get letInAgain => 'Let in again';

  @override
  String get contactsHeading => 'Contacts';

  @override
  String get contactsLead =>
      'The people your personal links were sent to or opened by. Every member sees them.';

  @override
  String get noContacts => 'No contacts yet.';

  @override
  String get contactRename => 'Rename…';

  @override
  String get contactRenameTitle => 'Rename contact';

  @override
  String get contactRenameNote => 'Photos already added keep the old name.';

  @override
  String get contactSessionsEntry => 'Signed-in browsers…';

  @override
  String contactSessionCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Signed in on $count browsers',
      one: 'Signed in on 1 browser',
      zero: 'Not signed in',
    );
    return '$_temp0';
  }

  @override
  String contactSessionsTitle(String name) {
    return 'Browsers of $name';
  }

  @override
  String contactSessionSince(String day) {
    return 'Since $day';
  }

  @override
  String contactSessionVia(String label) {
    return 'via \'$label\'';
  }

  @override
  String get noContactSessions => 'Not signed in anywhere.';

  @override
  String get endSession => 'End';

  @override
  String get endAllSessions => 'End all';

  @override
  String get shutOutEverywhere => 'Shut out of every link';

  @override
  String deleteContactTitle(String name) {
    return 'Delete $name?';
  }

  @override
  String get deleteContactMessage =>
      'Their addresses and sign-ins are deleted, and the links sent to them stop working. Photos they added stay and keep their name.';

  @override
  String get contactProvenAddress => 'Proven by the contact';

  @override
  String contactOwnName(String name) {
    return 'Own name: $name';
  }

  @override
  String otherSessionsSignOut(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Also signed in on $count other browsers — sign out others',
      one: 'Also signed in on 1 other browser — sign out others',
    );
    return '$_temp0';
  }

  @override
  String get otherSessionsEnded => 'Your other browsers are signed out.';

  @override
  String get shutOutEverywhereMark => 'Shut out everywhere';

  @override
  String get identifyTotp => 'Code from your authenticator app';

  @override
  String get totpCodeLabel => 'Code from the app';

  @override
  String get signInOffer => 'Be recognised on your other devices too?';

  @override
  String get signInOfferOpen => 'Set up';

  @override
  String get signInOptionsEntry => 'Sign-in options…';

  @override
  String get signInOptionsTitle => 'Sign-in options';

  @override
  String get signInOptionsLead =>
      'Optional: how you are recognised when you open the link on another device.';

  @override
  String get authenticatorHeading => 'Authenticator app';

  @override
  String authenticatorActiveSince(String date) {
    return 'Set up on $date.';
  }

  @override
  String get authenticatorExplanation =>
      'An app such as Google Authenticator shows a new code every 30 seconds.';

  @override
  String get authenticatorSetUp => 'Use an authenticator app';

  @override
  String get totpScan => 'Scan this code with your authenticator app.';

  @override
  String get totpOrEnterKey => 'Or enter this setup key in the app:';

  @override
  String get totpOnThisPhone =>
      'Add the entry to the authenticator app on this phone:';

  @override
  String get totpAddToApp => 'Add to authenticator app';

  @override
  String get totpLinkNote =>
      'If no app opens, choose \"Enter a setup key\" in your app.';

  @override
  String get totpShowKey => 'Show setup key';

  @override
  String get totpEnterCode => 'Then enter the code the app shows.';

  @override
  String get totpKeyCopied => 'Setup key copied.';

  @override
  String get contactSignInsEntry => 'Sign-in methods…';

  @override
  String contactSignInsTitle(String name) {
    return 'How $name signs in';
  }

  @override
  String get contactSignInsNone =>
      'No passkey and no authenticator app set up.';

  @override
  String get contactAuthenticatorMark => 'Authenticator app';

  @override
  String contactAuthenticatorRemoveTitle(String name) {
    return 'Remove the authenticator app of $name?';
  }

  @override
  String get contactSignInRemoveMessage =>
      'Its codes no longer sign in. The contact can set it up again.';

  @override
  String get identifyPasskey => 'Sign in with passkey';

  @override
  String get passkeyCancelled => 'No passkey was used.';

  @override
  String passkeyFailed(String reason) {
    return 'The browser could not use a passkey ($reason).';
  }

  @override
  String get passkeysHeading => 'Passkeys';

  @override
  String get passkeyExplanation =>
      'Your phone or browser keeps the passkey and syncs it to your other devices. No password, no code.';

  @override
  String get passkeyAdd => 'Recognise me on my other devices';

  @override
  String passkeyFrom(String date) {
    return 'Passkey from $date';
  }

  @override
  String passkeyLastUsed(String date) {
    return 'Last used on $date';
  }

  @override
  String contactPasskeyCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count passkeys',
      one: '1 passkey',
    );
    return '$_temp0';
  }

  @override
  String contactPasskeyRemoveTitle(String name) {
    return 'Remove this passkey of $name?';
  }

  @override
  String doingCollecting(String target) {
    return 'adding to the collection $target';
  }

  @override
  String get createCollection => 'New collection';

  @override
  String get newCollectionTitle => 'New collection';

  @override
  String get newCollectionHint =>
      'A collection shows photos of other albums without copying them.';

  @override
  String get collectionTakesNoMove =>
      'A collection holds no files; photos are added to it with “Add to collection”.';

  @override
  String get pickerNeedsCollection =>
      'Photos are added to a collection — open one or create one.';

  @override
  String get createNewCollection => 'Create new collection…';

  @override
  String get nothingToCollect => 'Select the photos to add first.';

  @override
  String get addToCollectionTitle => 'Add to collection';

  @override
  String addToCollectionConfirm(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Add $count photos here',
      one: 'Add 1 photo here',
    );
    return '$_temp0';
  }

  @override
  String addedToCollection(int count, String target) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Added $count photos to $target.',
      one: 'Added 1 photo to $target.',
      zero: 'Nothing was added to $target.',
    );
    return '$_temp0';
  }

  @override
  String get collectionPhotoMissing => 'This photo is no longer in the library';

  @override
  String get removeFromCollection => 'Remove from collection';

  @override
  String removeFromCollectionQuestion(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Remove $count photos from this collection?',
      one: 'Remove 1 photo from this collection?',
    );
    return '$_temp0';
  }

  @override
  String get removeFromCollectionExplanation =>
      'The photos are removed from this collection. They stay in their albums.';

  @override
  String removedFromCollection(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Removed $count photos from the collection.',
      one: 'Removed 1 photo from the collection.',
    );
    return '$_temp0';
  }

  @override
  String get addToCollection => 'Add to collection…';

  @override
  String propertySource(String album) {
    return 'In album: $album';
  }

  @override
  String get otherWaysToSignIn => 'Other ways to sign in…';

  @override
  String get noOtherWaysToSignIn =>
      'This server offers no other way to sign in.';

  @override
  String get signInWithCode => 'Use a sign-in code';

  @override
  String get memberSendCodeByEmail => 'Send me a code by e-mail';

  @override
  String get memberNameOrEmailLabel => 'Your user name or e-mail address';

  @override
  String get memberCodeSent =>
      'If this address is one of a member here, a code is on its way.';

  @override
  String get memberAddressSignsNobodyIn => 'This address signs nobody in here.';

  @override
  String get signInOptionsMemberLead =>
      'Optional: how you sign in on a new device without a code from another device of yours.';

  @override
  String get memberAddressesHeading => 'E-mail addresses';

  @override
  String get memberAddressesExplanation =>
      'With a confirmed address you sign in by a code sent to it, or with the account it belongs to, on the sign-in page and on every shared link.';

  @override
  String get memberAddAddress => 'Add e-mail address';

  @override
  String memberLinkProvider(String provider) {
    return 'Link $provider account';
  }

  @override
  String get memberAddressAdded =>
      'The address was added to your ways to sign in.';

  @override
  String get wayAuthenticator => 'your authenticator app';

  @override
  String get wayPasskey => 'a passkey';

  @override
  String get wayEmailAddress => 'a code sent to your e-mail address';

  @override
  String get noMemberSignIns =>
      'No other way to sign in set up: no authenticator app, no passkey, no e-mail address.';

  @override
  String memberSignInsState(String ways) {
    return 'Also signs you in: $ways';
  }

  @override
  String get memberSignInsEntry => 'Ways to sign in…';

  @override
  String get userSignInsTooltip => 'Ways to sign in';

  @override
  String get userSignInRemoveMessage =>
      'It no longer signs this member in. The member can set it up again.';

  @override
  String signInAddressRemoveTitle(String address, String name) {
    return 'Remove the address $address of $name?';
  }

  @override
  String get providerNeedsBrowser =>
      'Signing in with another account works in a browser only.';

  @override
  String providerReturnsElsewhere(String address) {
    return 'This sign-in would come back to $address, not to this page, and could not be finished here. Open $address in the browser and sign in there.';
  }

  @override
  String get providerReturnUnknown =>
      'This page came back from a sign-in it did not start. Start the sign-in again.';

  @override
  String get totpCodeIncomplete =>
      'Enter the six digits your authenticator app shows.';

  @override
  String get memberNameRequired => 'Enter your user name or e-mail address.';

  @override
  String get totpQrSemantics =>
      'Setup code for an authenticator app, as a QR code';

  @override
  String get catchUpHeading => 'Preparing the albums';

  @override
  String get catchUpAsking => 'Asking the server…';

  @override
  String catchUpUnavailable(String reason) {
    return 'The progress cannot be read: $reason';
  }

  @override
  String catchUpProgress(int done, int total) {
    return 'Albums prepared: $done of $total';
  }

  @override
  String catchUpComplete(int total) {
    String _temp0 = intl.Intl.pluralLogic(
      total,
      locale: localeName,
      other: 'All $total albums are prepared',
      one: 'The 1 album is prepared',
      zero: 'No albums to prepare',
    );
    return '$_temp0';
  }

  @override
  String get catchUpIdle => 'Nothing to do right now.';

  @override
  String catchUpNow(String step, String folder) {
    return 'Now: $step in $folder';
  }

  @override
  String get catchUpYielding =>
      'Paused while photos are being shown to somebody.';

  @override
  String catchUpVideos(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count videos waiting',
      one: '1 video waiting',
      zero: 'No video waiting',
    );
    return '$_temp0';
  }

  @override
  String get catchUpNoFailure => 'Nothing failed.';

  @override
  String catchUpFailure(String folder, String failure) {
    return 'Last failure in $folder: $failure';
  }

  @override
  String get catchUpRoot => 'the top folder';

  @override
  String get catchUpStepHash => 'Fingerprints';

  @override
  String get catchUpStepPreviews => 'Previews';

  @override
  String get catchUpStepCover => 'Album covers';

  @override
  String get catchUpStepFaces => 'Faces';

  @override
  String get catchUpStepPlaces => 'Places';

  @override
  String get catchUpStepVideos => 'Videos';

  @override
  String get catchUpStepOther => 'Other work';

  @override
  String get uploadTargetHeading => 'Upload new photos to';

  @override
  String get uploadTargetExplanation =>
      'New photos of this device go to the inbox of the space. For a while, say a holiday, they can go straight to an album instead. Only this device changes; other devices keep filling the inbox.';

  @override
  String get uploadTargetToInbox => 'New photos go to the inbox.';

  @override
  String uploadTargetLine(String album) {
    return 'New photos go to \'$album\'.';
  }

  @override
  String uploadTargetLineUntil(String album, String date) {
    return 'New photos go to \'$album\' (until $date).';
  }

  @override
  String get uploadTargetChoose => 'Choose album...';

  @override
  String get uploadTargetPickerTitle => 'Upload new photos to...';

  @override
  String get uploadTargetPickHere => 'Upload here';

  @override
  String get uploadTargetNoContribute =>
      'You may not add photos to this album.';

  @override
  String get uploadTargetBackToInbox => 'Back to inbox';

  @override
  String get uploadTargetChangeEnd => 'Change end date';

  @override
  String uploadTargetDialogTitle(String album) {
    return 'New photos go to \'$album\'';
  }

  @override
  String uploadTargetUntil(String date) {
    return 'Until $date';
  }

  @override
  String get uploadTargetNoEnd => 'No end date';

  @override
  String get uploadTargetSetEnd => 'Set an end date';

  @override
  String get uploadTargetEndExplanation =>
      'After the end date this device returns to the inbox by itself, so that a forgotten album does not swallow the next month\'s photos.';

  @override
  String get uploadTargetSave => 'Save';

  @override
  String uploadTargetWentTo(String album) {
    return 'The new photos went to \'$album\'.';
  }

  @override
  String noticeUploadTargetExpired(String album, String date) {
    return 'Uploading to \'$album\' ended on $date; new photos go to the inbox again.';
  }

  @override
  String noticeUploadTargetGone(String album) {
    return 'The album \'$album\' is no longer there; new photos go to the inbox.';
  }

  @override
  String noticeUploadTargetRefused(String album) {
    return 'You may no longer add photos to \'$album\'; new photos go to the inbox.';
  }

  @override
  String noticeUploadTargetNotAlbum(String album) {
    return 'The album \'$album\' takes no photos; new photos go to the inbox.';
  }

  @override
  String noticeUploadTargetFollowed(String before, String album) {
    return 'The album \'$before\' is now \'$album\'; new photos go there.';
  }

  @override
  String get groupByAction => 'Group by…';

  @override
  String get groupByTitle => 'Group by';

  @override
  String get groupByScopeAlbum => 'Applies to the whole album.';

  @override
  String groupByScopeChoiceSelection(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Selected photos ($count)',
      one: 'Selected photo (1)',
    );
    return '$_temp0';
  }

  @override
  String get groupByScopeChoiceAlbum => 'Whole album';

  @override
  String get groupBySectionKey => 'Sections by';

  @override
  String get groupBySubsectionKey => 'Subsections by';

  @override
  String get groupByKeyNone => 'None';

  @override
  String get groupByKeyDay => 'Day';

  @override
  String get groupByKeyTown => 'Town';

  @override
  String get groupByKeyDistrict => 'District';

  @override
  String get groupByKeyRegion => 'Region';

  @override
  String get groupByKeyCountry => 'Country';

  @override
  String get groupByKeyFeature => 'Landmark';

  @override
  String get groupByKeyDayAndTown => 'Day and town';

  @override
  String get groupByModeReplace => 'Replace the headings';

  @override
  String get groupByModeAddSubsections => 'Keep the sections, add subsections';

  @override
  String groupByWillReplace(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'The $count headings already there are replaced.',
      one: 'The heading already there is replaced.',
    );
    return '$_temp0';
  }

  @override
  String get groupByNothingKeyed =>
      'None of these photos has this information: no date, or no position with a place of this kind nearby. No heading is created.';

  @override
  String get groupByPreview => 'Headings to create';
}
