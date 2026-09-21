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
}
