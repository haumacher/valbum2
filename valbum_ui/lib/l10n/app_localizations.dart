import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_de.dart';
import 'app_localizations_en.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
      : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations? of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations);
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
    delegate,
    GlobalMaterialLocalizations.delegate,
    GlobalCupertinoLocalizations.delegate,
    GlobalWidgetsLocalizations.delegate,
  ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('de'),
    Locale('en')
  ];

  /// The name of the application, shown as the window or browser tab title
  ///
  /// In en, this message translates to:
  /// **'Virtual Photo Album'**
  String get appTitle;

  /// Button dismissing a banner
  ///
  /// In en, this message translates to:
  /// **'Dismiss'**
  String get dismiss;

  /// Button leaving a dialog without doing anything
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancel;

  /// Button storing what was entered
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get save;

  /// Button or tooltip taking an entry away
  ///
  /// In en, this message translates to:
  /// **'Remove'**
  String get remove;

  /// Button or tooltip taking an invitation or a code back
  ///
  /// In en, this message translates to:
  /// **'Withdraw'**
  String get withdraw;

  /// Button closing a dialog that only showed something
  ///
  /// In en, this message translates to:
  /// **'Done'**
  String get done;

  /// Shown while a section waits for the server to answer
  ///
  /// In en, this message translates to:
  /// **'Asking the server...'**
  String get askingServer;

  /// Button opening the server settings screen
  ///
  /// In en, this message translates to:
  /// **'Server settings...'**
  String get serverSettingsAction;

  /// Title of the page a server shows when it refuses an anonymous caller
  ///
  /// In en, this message translates to:
  /// **'Sign-in required'**
  String get signInRequiredTitle;

  /// Shown on the sign-in page when no server is configured
  ///
  /// In en, this message translates to:
  /// **'This app talks to no server yet.'**
  String get signInRequiredNoServer;

  /// Title of the server settings screen
  ///
  /// In en, this message translates to:
  /// **'Album server'**
  String get serverScreenTitle;

  /// Explains what belongs in the server address field
  ///
  /// In en, this message translates to:
  /// **'The address the album server is reached at, as you would open it in a browser, e.g. \'http://nas.local:8080/valbum/\'. Where the server holds several spaces, the address carries the space: \'https://host/valbum/<space>/\'.'**
  String get serverUrlHelp;

  /// Shown in the web build when no server is known
  ///
  /// In en, this message translates to:
  /// **'This browser talks to no server yet.'**
  String get serverLineNoServer;

  /// Names the server the web build talks to
  ///
  /// In en, this message translates to:
  /// **'This browser talks to {server}'**
  String serverLineTalksTo(String server);

  /// Label of the server address field
  ///
  /// In en, this message translates to:
  /// **'Server URL'**
  String get serverUrlLabel;

  /// Button asking the entered server whether it answers
  ///
  /// In en, this message translates to:
  /// **'Test connection'**
  String get testConnection;

  /// Button dropping the stored server address
  ///
  /// In en, this message translates to:
  /// **'Forget this server'**
  String get forgetThisServer;

  /// Button going back to the server the app came from
  ///
  /// In en, this message translates to:
  /// **'Use the server this app was loaded from'**
  String get useLoadedServer;

  /// Shown while the connection test runs
  ///
  /// In en, this message translates to:
  /// **'Contacting the server...'**
  String get contactingServer;

  /// Heading of the sign-in section
  ///
  /// In en, this message translates to:
  /// **'Sign in'**
  String get signInHeading;

  /// Explains where a sign-in code comes from
  ///
  /// In en, this message translates to:
  /// **'Enter the code the server printed at start-up, or a code from one of your devices, or a recovery code your administrator gave you.'**
  String get signInCodeExplanation;

  /// Shown instead of the sign-in form while no server is named
  ///
  /// In en, this message translates to:
  /// **'Name a server above before signing in.'**
  String get signInNoServer;

  /// Button making this device forget its sign-in
  ///
  /// In en, this message translates to:
  /// **'Sign out'**
  String get signOut;

  /// Shown after a sign-out
  ///
  /// In en, this message translates to:
  /// **'This device no longer identifies itself to the server.'**
  String get signedOutMessage;

  /// Explains what the user name field is for
  ///
  /// In en, this message translates to:
  /// **'Your name in this space; it is what the others see and what your photos are attributed to.'**
  String get userNameHelp;

  /// Shown when the sign-in button is pressed with an empty code field
  ///
  /// In en, this message translates to:
  /// **'Enter the code that signs this device in.'**
  String get codeRequiredRefusal;

  /// Shown after the server accepted a sign-in
  ///
  /// In en, this message translates to:
  /// **'Sign-in succeeded.'**
  String get signInSucceeded;

  /// Shown while the sign-in request runs
  ///
  /// In en, this message translates to:
  /// **'Signing in...'**
  String get signingIn;

  /// Label of the name field while an invitation is being accepted
  ///
  /// In en, this message translates to:
  /// **'Your name'**
  String get yourName;

  /// Explains the name field of an invitation
  ///
  /// In en, this message translates to:
  /// **'How the others on this server see you.'**
  String get yourNameHelp;

  /// Label of the name field when a code signs in a user who has no name yet
  ///
  /// In en, this message translates to:
  /// **'User name'**
  String get userNameLabel;

  /// Label of the field naming this device at the server
  ///
  /// In en, this message translates to:
  /// **'Device name'**
  String get deviceNameLabel;

  /// Label of the code field
  ///
  /// In en, this message translates to:
  /// **'Sign-in code'**
  String get signInCodeLabel;

  /// Explains where the code in the code field comes from
  ///
  /// In en, this message translates to:
  /// **'From the server\'s start-up, from My devices on a device you are already signed in on, from your administrator, or your backup code.'**
  String get signInCodeHelp;

  /// Tooltip of the button opening the camera to read a code
  ///
  /// In en, this message translates to:
  /// **'Scan code'**
  String get scanCode;

  /// Shown when a scanned code names a different server than this page talks to
  ///
  /// In en, this message translates to:
  /// **'This code is for {server}, not for the server this page came from. Open that server and sign in there.'**
  String otherServerRefusal(String server);

  /// Shown when something scanned is no device code
  ///
  /// In en, this message translates to:
  /// **'This is not a device code.'**
  String get notADeviceCode;

  /// Names the invitation the entered address carries; the token is masked
  ///
  /// In en, this message translates to:
  /// **'Invitation {token}'**
  String invitationMasked(String token);

  /// Button dropping the invitation of the entered address
  ///
  /// In en, this message translates to:
  /// **'Not this one'**
  String get notThisOne;

  /// Shown while the server is asked what an invitation offers
  ///
  /// In en, this message translates to:
  /// **'Asking the server about this invitation...'**
  String get askingAboutInvitation;

  /// Shown when the server does not know the pasted invitation
  ///
  /// In en, this message translates to:
  /// **'This server does not know this invitation. Ask for a new one.'**
  String get invitationUnknown;

  /// The same, on the first screen, where a plain address is the alternative
  ///
  /// In en, this message translates to:
  /// **'This server does not know this invitation. Ask for a new one, or type the plain server address.'**
  String get invitationUnknownHere;

  /// Who invited, and what the invitation offers
  ///
  /// In en, this message translates to:
  /// **'{invitedBy} invited you to this album server: {may}.'**
  String invitationHeadlineWithRole(String invitedBy, String may);

  /// Who invited, where the offered role says nothing the app knows
  ///
  /// In en, this message translates to:
  /// **'{invitedBy} invited you to this album server.'**
  String invitationHeadlinePlain(String invitedBy);

  /// Heading of the section inviting people
  ///
  /// In en, this message translates to:
  /// **'People'**
  String get peopleHeading;

  /// Explains what an invitation is
  ///
  /// In en, this message translates to:
  /// **'An invitation is a single-use link that creates one account on this server. Send it to the person it is for, and to nobody else.'**
  String get inviteExplanation;

  /// Button opening the invitation dialog
  ///
  /// In en, this message translates to:
  /// **'Invite…'**
  String get inviteAction;

  /// Shown where the signed-in user has no name yet
  ///
  /// In en, this message translates to:
  /// **'Signed in on this device'**
  String get signedInOnThisDevice;

  /// Names the user this device is signed in as
  ///
  /// In en, this message translates to:
  /// **'Signed in as {user}'**
  String signedInAsUser(String user);

  /// Names the role the server reports
  ///
  /// In en, this message translates to:
  /// **'Role: {role}'**
  String roleLine(String role);

  /// Names this device at the server
  ///
  /// In en, this message translates to:
  /// **'Device: {device}'**
  String deviceLine(String device);

  /// Names the space this device's requests are resolved against
  ///
  /// In en, this message translates to:
  /// **'Space: {space}'**
  String spaceLine(String space);

  /// Shown while this device holds no token
  ///
  /// In en, this message translates to:
  /// **'Not signed in'**
  String get notSignedIn;

  /// Shown when the server answers this device as an anonymous caller
  ///
  /// In en, this message translates to:
  /// **'This server does not know this device. Sign in again.'**
  String get deviceUnknownToServer;

  /// Shown when the server could not be asked who this device is
  ///
  /// In en, this message translates to:
  /// **'The server did not say who this device is: {problem}'**
  String identityUnknown(String problem);

  /// How a user without a name is named on screen
  ///
  /// In en, this message translates to:
  /// **'the library owner'**
  String get libraryOwner;

  /// How the space of a user without a space of their own is named
  ///
  /// In en, this message translates to:
  /// **'the whole library'**
  String get wholeLibrary;

  /// Tells a guest what their own root holds
  ///
  /// In en, this message translates to:
  /// **'Guest: your library is what others share with you.'**
  String get guestLibraryNotice;

  /// Heading of the offline cache section
  ///
  /// In en, this message translates to:
  /// **'Cache'**
  String get cacheHeading;

  /// Explains what the offline cache is
  ///
  /// In en, this message translates to:
  /// **'Albums and thumbnails already seen are kept on this device, so that the library can be browsed while the server is away.'**
  String get cacheExplanation;

  /// How much the offline cache holds
  ///
  /// In en, this message translates to:
  /// **'Currently cached: {size}'**
  String currentlyCached(String size);

  /// Shown while the cache size is being read
  ///
  /// In en, this message translates to:
  /// **'Currently cached: ...'**
  String get currentlyCachedUnknown;

  /// Button emptying the offline cache
  ///
  /// In en, this message translates to:
  /// **'Clear cache'**
  String get clearCache;

  /// Title of the question before the cache is emptied
  ///
  /// In en, this message translates to:
  /// **'Clear the cache?'**
  String get clearCacheTitle;

  /// What emptying the cache does
  ///
  /// In en, this message translates to:
  /// **'Everything kept for offline browsing is forgotten. It is fetched again the next time the server is reached.'**
  String get clearCacheQuestion;

  /// Button confirming that the cache is emptied
  ///
  /// In en, this message translates to:
  /// **'Clear'**
  String get clear;

  /// Says how much emptying the cache freed
  ///
  /// In en, this message translates to:
  /// **'Cache cleared, {size} freed.'**
  String cacheCleared(String size);

  /// Heading of the diagnostics section
  ///
  /// In en, this message translates to:
  /// **'Diagnostics'**
  String get diagnosticsHeading;

  /// Explains what the diagnostics log is for
  ///
  /// In en, this message translates to:
  /// **'What this app did on the network - copy it into a bug report.'**
  String get diagnosticsLead;

  /// Shown while the diagnostics log holds nothing
  ///
  /// In en, this message translates to:
  /// **'Nothing logged yet. Test the connection, or browse the album, and what the app asked the server appears here.'**
  String get diagnosticsEmpty;

  /// Shown after the log was copied
  ///
  /// In en, this message translates to:
  /// **'The diagnostics log is on the clipboard.'**
  String get diagnosticsCopied;

  /// Button putting the diagnostics log on the clipboard
  ///
  /// In en, this message translates to:
  /// **'Copy'**
  String get copy;

  /// What the connection test says about a signed-in device
  ///
  /// In en, this message translates to:
  /// **'Signed in as {user} on {device}'**
  String connectionSignedInAsOn(String user, String device);

  /// What the connection test says about a server running without authentication
  ///
  /// In en, this message translates to:
  /// **'Not signed in - this server needs no sign-in'**
  String get connectionNoSignInNeeded;

  /// What the connection test says about a server that refuses anonymous callers
  ///
  /// In en, this message translates to:
  /// **'Not signed in - this server shows nothing without a sign-in'**
  String get connectionSignInForEverything;

  /// What the connection test says about a server that lets anonymous callers read
  ///
  /// In en, this message translates to:
  /// **'Not signed in - changes need a sign-in'**
  String get connectionSignInForChanges;

  /// What the connection test says when the answer is no album resource
  ///
  /// In en, this message translates to:
  /// **'The answer is not album data — not a VAlbum server?'**
  String get notAlbumData;

  /// What the connection test says about a server whose root has no title
  ///
  /// In en, this message translates to:
  /// **'Album server reached'**
  String get albumServerReached;

  /// What the connection test says about a server answering 401
  ///
  /// In en, this message translates to:
  /// **'Album server reached - it needs a sign-in before it shows anything'**
  String get albumServerNeedsSignIn;

  /// What the connection test says about a server answering 403
  ///
  /// In en, this message translates to:
  /// **'Album server reached - it refuses what this device is signed in as'**
  String get albumServerRefusesThisDevice;

  /// Suggested device name in a browser
  ///
  /// In en, this message translates to:
  /// **'This browser'**
  String get deviceNameThisBrowser;

  /// Suggested device name on Android
  ///
  /// In en, this message translates to:
  /// **'Android phone'**
  String get deviceNameAndroid;

  /// Suggested device name on iOS
  ///
  /// In en, this message translates to:
  /// **'iPhone'**
  String get deviceNameIPhone;

  /// Suggested device name on macOS
  ///
  /// In en, this message translates to:
  /// **'Mac'**
  String get deviceNameMac;

  /// Suggested device name on Windows
  ///
  /// In en, this message translates to:
  /// **'Windows PC'**
  String get deviceNameWindows;

  /// Suggested device name on Linux
  ///
  /// In en, this message translates to:
  /// **'Linux PC'**
  String get deviceNameLinux;

  /// Suggested device name on any other platform
  ///
  /// In en, this message translates to:
  /// **'My device'**
  String get deviceNameOther;

  /// Heading of the first screen off the web
  ///
  /// In en, this message translates to:
  /// **'Where is your album?'**
  String get firstScreenTitle;

  /// Explains the one field of the first screen
  ///
  /// In en, this message translates to:
  /// **'Type the address of your album server, or paste the invitation link you were sent. If somebody showed you a QR code, scan it.'**
  String get firstScreenLead;

  /// Shown when what was entered is no server address
  ///
  /// In en, this message translates to:
  /// **'That is not a server address. It looks like \'http://nas.local:8080/valbum/\'.'**
  String get firstScreenNoServer;

  /// Label of the one field of the first screen
  ///
  /// In en, this message translates to:
  /// **'Server address or link'**
  String get serverAddressOrLink;

  /// Button accepting what was entered on the first screen
  ///
  /// In en, this message translates to:
  /// **'Continue'**
  String get continueAction;

  /// Names the server the first screen settled on
  ///
  /// In en, this message translates to:
  /// **'Album server: {server}'**
  String albumServerLine(String server);

  /// Button going back to the address field
  ///
  /// In en, this message translates to:
  /// **'Another server'**
  String get anotherServer;

  /// Button opening a server that shows its albums to anonymous callers
  ///
  /// In en, this message translates to:
  /// **'Open without signing in'**
  String get openWithoutSigningIn;

  /// Heading of the devices section
  ///
  /// In en, this message translates to:
  /// **'My devices'**
  String get devicesHeading;

  /// Explains what the devices section lists
  ///
  /// In en, this message translates to:
  /// **'Every device you signed in on holds a token of its own. Removing one here makes that token worthless; the device has to sign in again.'**
  String get devicesLead;

  /// Shown when the device list is empty
  ///
  /// In en, this message translates to:
  /// **'No device is signed in.'**
  String get noDeviceSignedIn;

  /// Marks the device the app runs on
  ///
  /// In en, this message translates to:
  /// **'{name} (this device)'**
  String thisDeviceNamed(String name);

  /// Shown for a device whose pairing day the server does not name
  ///
  /// In en, this message translates to:
  /// **'Paired at an unknown time'**
  String get pairedAtUnknownTime;

  /// The day a device was paired
  ///
  /// In en, this message translates to:
  /// **'Paired on {day}'**
  String pairedOn(String day);

  /// Tooltip of the button signing this very device out
  ///
  /// In en, this message translates to:
  /// **'Sign out here'**
  String get signOutHere;

  /// Button showing a code for a further device of one's own
  ///
  /// In en, this message translates to:
  /// **'Add a device…'**
  String get addDevice;

  /// Shown while the caller has no backup code
  ///
  /// In en, this message translates to:
  /// **'Backup code: none. Without one, signing out of your last device leaves you dependent on your administrator.'**
  String get noBackupCode;

  /// Shown once a backup code exists
  ///
  /// In en, this message translates to:
  /// **'Backup code: made on {day}. Keep it safe; making a new one withdraws it.'**
  String backupCodeMade(String day);

  /// Button making the first backup code
  ///
  /// In en, this message translates to:
  /// **'Create backup code…'**
  String get createBackupCode;

  /// Button replacing an existing backup code
  ///
  /// In en, this message translates to:
  /// **'Create a new backup code…'**
  String get createNewBackupCode;

  /// Title of the question before a backup code is withdrawn
  ///
  /// In en, this message translates to:
  /// **'Withdraw the backup code?'**
  String get withdrawBackupCodeTitle;

  /// What withdrawing the backup code means
  ///
  /// In en, this message translates to:
  /// **'The code you wrote down stops working. Signing out of your last device then leaves you dependent on a recovery code from your administrator.'**
  String get withdrawBackupCodeMessage;

  /// Title of the dialog showing a freshly made backup code
  ///
  /// In en, this message translates to:
  /// **'Backup code'**
  String get backupCodeTitle;

  /// What a backup code is, said where it is shown
  ///
  /// In en, this message translates to:
  /// **'Write this down and keep it somewhere safe — a password manager, a drawer. It never expires, it works once, and it signs a device in as you, so give it to nobody. This is the only time it is shown.'**
  String get backupCodeAdvice;

  /// Shown in place of the countdown for a backup code
  ///
  /// In en, this message translates to:
  /// **'This code does not expire. It works once.'**
  String get backupCodeNoExpiry;

  /// Title of the dialog showing a device code
  ///
  /// In en, this message translates to:
  /// **'Add a device'**
  String get addDeviceTitle;

  /// Title of the dialog showing a recovery code
  ///
  /// In en, this message translates to:
  /// **'Recovery code for {user}'**
  String recoveryCodeTitle(String user);

  /// What a device code is, said where it is shown
  ///
  /// In en, this message translates to:
  /// **'Type this on the other device within 10 minutes. It signs that device in as you — never give it to anyone else.'**
  String get deviceCodeAdvice;

  /// What a recovery code is, said where it is shown
  ///
  /// In en, this message translates to:
  /// **'Give this to {user} within 10 minutes; it signs one of their devices in as them. It works once — give it to nobody else.'**
  String recoveryCodeAdvice(String user);

  /// What the QR code beside a device code is for
  ///
  /// In en, this message translates to:
  /// **'Or scan this on the other device, at Sign in.'**
  String get deviceCodeQrAdvice;

  /// What the copyable link beside a device code is for
  ///
  /// In en, this message translates to:
  /// **'Or send this link to the other device and open it in the app:'**
  String get deviceCodeLinkAdvice;

  /// Screen-reader label of the QR code
  ///
  /// In en, this message translates to:
  /// **'Device code as a QR code'**
  String get deviceCodeQrSemantics;

  /// Tooltip of the button copying the device-code link
  ///
  /// In en, this message translates to:
  /// **'Copy the link'**
  String get copyTheLink;

  /// Shown after the device-code link was copied
  ///
  /// In en, this message translates to:
  /// **'The link is on the clipboard.'**
  String get linkOnClipboard;

  /// Shown once a device code has run out
  ///
  /// In en, this message translates to:
  /// **'This code has expired.'**
  String get codeExpired;

  /// How long a device code still works
  ///
  /// In en, this message translates to:
  /// **'Expires in {time}'**
  String codeExpiresIn(String time);

  /// Button asking for a fresh code
  ///
  /// In en, this message translates to:
  /// **'New code'**
  String get newCode;

  /// Shown when a device paired while the code was on screen
  ///
  /// In en, this message translates to:
  /// **'{name} joined.'**
  String deviceJoined(String name);

  /// Title of the question before this device is signed out
  ///
  /// In en, this message translates to:
  /// **'Sign out this device?'**
  String get signOutThisDeviceTitle;

  /// What signing this device out does while another device stays
  ///
  /// In en, this message translates to:
  /// **'This device forgets its sign-in and talks to the server anonymously again. You can sign in again at any time.'**
  String get signOutThisDeviceMessage;

  /// Title of the question before another device is removed
  ///
  /// In en, this message translates to:
  /// **'Remove the device \'{name}\'?'**
  String removeDeviceTitle(String name);

  /// What removing another device does
  ///
  /// In en, this message translates to:
  /// **'\'{name}\' stops being signed in. It has to sign in again before it can change anything.'**
  String removeDeviceMessage(String name);

  /// Warns before the last signed-in device is signed out
  ///
  /// In en, this message translates to:
  /// **'This is your only signed-in device. To sign in again you need {ways}.'**
  String lastDeviceWarning(String ways);

  /// Joins the last of several ways back with the ones before it
  ///
  /// In en, this message translates to:
  /// **'{rest}, or {last}'**
  String waysOrLast(String rest, String last);

  /// One way back into an account
  ///
  /// In en, this message translates to:
  /// **'your backup code'**
  String get wayBackupCode;

  /// One way back into an account
  ///
  /// In en, this message translates to:
  /// **'a recovery code from your administrator'**
  String get wayRecoveryFromAdmin;

  /// One way back into an administrator's account
  ///
  /// In en, this message translates to:
  /// **'a recovery code from another administrator'**
  String get wayRecoveryFromOtherAdmin;

  /// One way back into an administrator's account
  ///
  /// In en, this message translates to:
  /// **'a restart of the server, which prints a new sign-in code'**
  String get wayServerRestart;

  /// Warns before a sign-out when the device list could not be read
  ///
  /// In en, this message translates to:
  /// **'This may be your only signed-in device, and the server could not be asked. If it is, you need a recovery code from your administrator, your backup code, or a restart of the server to get back in.'**
  String get maybeLastDeviceWarning;

  /// Heading of the role choices
  ///
  /// In en, this message translates to:
  /// **'May'**
  String get permissionMayHeading;

  /// Heading of the clearance choices
  ///
  /// In en, this message translates to:
  /// **'Sees'**
  String get permissionSeesHeading;

  /// Label of the switch allowing share links
  ///
  /// In en, this message translates to:
  /// **'May share links'**
  String get mayShareLinksSwitch;

  /// What allowing share links means
  ///
  /// In en, this message translates to:
  /// **'May hand out links that open an album for whoever holds them.'**
  String get mayShareLinksExplanation;

  /// What the edit role allows
  ///
  /// In en, this message translates to:
  /// **'May create albums, change them and add photos.'**
  String get roleExplanationEdit;

  /// What the contribute role allows
  ///
  /// In en, this message translates to:
  /// **'May add photos to the albums, but change nothing.'**
  String get roleExplanationContribute;

  /// What the view role allows
  ///
  /// In en, this message translates to:
  /// **'May look at the albums, and nothing more.'**
  String get roleExplanationView;

  /// What the widest clearance shows
  ///
  /// In en, this message translates to:
  /// **'Sees every image, the private ones included.'**
  String get clearanceExplanationAll;

  /// What the middle clearance shows
  ///
  /// In en, this message translates to:
  /// **'Sees every image that is not marked private.'**
  String get clearanceExplanationNonPrivate;

  /// What the narrowest clearance shows
  ///
  /// In en, this message translates to:
  /// **'Sees only the images marked public.'**
  String get clearanceExplanationPublic;

  /// Title of the dialog changing a user's permission
  ///
  /// In en, this message translates to:
  /// **'What {user} may do'**
  String permissionDialogTitle(String user);

  /// Heading of the users section
  ///
  /// In en, this message translates to:
  /// **'Users'**
  String get usersHeading;

  /// Explains what the users section lists
  ///
  /// In en, this message translates to:
  /// **'Everybody who has an account on this server, and what they may do and see in it.'**
  String get usersLead;

  /// Tooltip of the button making a recovery code
  ///
  /// In en, this message translates to:
  /// **'Recovery code'**
  String get recoveryCodeTooltip;

  /// Tooltip of the button opening the permission dialog
  ///
  /// In en, this message translates to:
  /// **'Change what they may do'**
  String get changePermissionTooltip;

  /// Title of the question before a user is removed
  ///
  /// In en, this message translates to:
  /// **'Remove {user}?'**
  String removeUserTitle(String user);

  /// What removing a user does
  ///
  /// In en, this message translates to:
  /// **'Their devices are signed out; their photos and their name on them stay.'**
  String get removeUserMessage;

  /// Names a pending user the inviter wrote no memento for
  ///
  /// In en, this message translates to:
  /// **'Invited (pending)'**
  String get invitedPending;

  /// Names a pending user by the inviter's memento
  ///
  /// In en, this message translates to:
  /// **'Invited for {recipient} (pending)'**
  String invitedForPending(String recipient);

  /// Title of the question before an invitation is withdrawn
  ///
  /// In en, this message translates to:
  /// **'Withdraw this invitation?'**
  String get withdrawInvitationTitle;

  /// What withdrawing a pending user's invitation does
  ///
  /// In en, this message translates to:
  /// **'The link stops working, and the seat it was holding goes.'**
  String get withdrawPendingUserMessage;

  /// What withdrawing an invitation does
  ///
  /// In en, this message translates to:
  /// **'The link stops working. Somebody who already accepted it keeps their account.'**
  String get withdrawInvitationMessage;

  /// Who invited a pending user
  ///
  /// In en, this message translates to:
  /// **'invited by {user}'**
  String invitedByUser(String user);

  /// Since when a user is on this server
  ///
  /// In en, this message translates to:
  /// **'since {day}'**
  String sinceDay(String day);

  /// Where a user's library lies
  ///
  /// In en, this message translates to:
  /// **'library: {space}'**
  String librarySpace(String space);

  /// How many devices a user is signed in on
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 device} other{{count} devices}}'**
  String deviceCount(int count);

  /// The inviter's memento beside a user's name
  ///
  /// In en, this message translates to:
  /// **'invited for {recipient}'**
  String invitedForRecipient(String recipient);

  /// Heading of the open invitations list
  ///
  /// In en, this message translates to:
  /// **'Open invitations'**
  String get openInvitationsHeading;

  /// Shown when no invitation is open
  ///
  /// In en, this message translates to:
  /// **'No invitation is waiting to be accepted.'**
  String get noOpenInvitations;

  /// What an invitation offers, and who handed it out
  ///
  /// In en, this message translates to:
  /// **'{permission} — invited by {user}'**
  String invitationPermissionBy(String permission, String user);

  /// Who an invitation was written for
  ///
  /// In en, this message translates to:
  /// **'for {recipient}'**
  String forRecipient(String recipient);

  /// Shown for an invitation without an expiry
  ///
  /// In en, this message translates to:
  /// **'expires: never'**
  String get expiresNever;

  /// When an invitation ran out
  ///
  /// In en, this message translates to:
  /// **'expired on {day}'**
  String expiredOnDay(String day);

  /// When an invitation runs out
  ///
  /// In en, this message translates to:
  /// **'expires {day}'**
  String expiresOnDay(String day);

  /// What a permission allows, in one sentence of three clauses
  ///
  /// In en, this message translates to:
  /// **'{doing}; {seeing}; {sharing}.'**
  String permissionSentence(String doing, String seeing, String sharing);

  /// First clause of the permission sentence
  ///
  /// In en, this message translates to:
  /// **'You manage this server'**
  String get permissionDoingAdmin;

  /// First clause of the permission sentence
  ///
  /// In en, this message translates to:
  /// **'You may edit every album of this space'**
  String get permissionDoingEdit;

  /// First clause of the permission sentence
  ///
  /// In en, this message translates to:
  /// **'You may add photos to this space'**
  String get permissionDoingContribute;

  /// First clause of the permission sentence
  ///
  /// In en, this message translates to:
  /// **'You may look at this space'**
  String get permissionDoingView;

  /// First clause of the permission sentence
  ///
  /// In en, this message translates to:
  /// **'You are not signed in'**
  String get permissionDoingNone;

  /// Second clause of the permission sentence
  ///
  /// In en, this message translates to:
  /// **'you see all images'**
  String get permissionSeeingAll;

  /// Second clause of the permission sentence
  ///
  /// In en, this message translates to:
  /// **'you see all but the private images'**
  String get permissionSeeingNonPrivate;

  /// Second clause of the permission sentence
  ///
  /// In en, this message translates to:
  /// **'you see the public images'**
  String get permissionSeeingPublic;

  /// Third clause of the permission sentence
  ///
  /// In en, this message translates to:
  /// **'you may share links'**
  String get permissionSharingMay;

  /// Third clause of the permission sentence
  ///
  /// In en, this message translates to:
  /// **'you may not share links'**
  String get permissionSharingMayNot;

  /// What somebody else's permission allows, in three clauses
  ///
  /// In en, this message translates to:
  /// **'{role} — {clearance} — {sharing}'**
  String permissionPhrase(String role, String clearance, String sharing);

  /// Third clause about somebody else
  ///
  /// In en, this message translates to:
  /// **'may share links'**
  String get permissionPhraseMayShare;

  /// Third clause about somebody else
  ///
  /// In en, this message translates to:
  /// **'no links'**
  String get permissionPhraseNoLinks;

  /// What the admin role allows, about somebody else
  ///
  /// In en, this message translates to:
  /// **'manages this server'**
  String get roleWordAdmin;

  /// What the edit role allows, about somebody else
  ///
  /// In en, this message translates to:
  /// **'may edit the albums'**
  String get roleWordEdit;

  /// What the contribute role allows, about somebody else
  ///
  /// In en, this message translates to:
  /// **'may add photos'**
  String get roleWordContribute;

  /// What the view role allows, about somebody else
  ///
  /// In en, this message translates to:
  /// **'may look'**
  String get roleWordView;

  /// Shown for a role the app does not know
  ///
  /// In en, this message translates to:
  /// **'unknown role'**
  String get roleWordUnknown;

  /// What the admin role allows, said to the person
  ///
  /// In en, this message translates to:
  /// **'you manage this server'**
  String get roleWordYouAdmin;

  /// What the edit role allows, said to the person
  ///
  /// In en, this message translates to:
  /// **'you may edit the albums'**
  String get roleWordYouEdit;

  /// What the contribute role allows, said to the person
  ///
  /// In en, this message translates to:
  /// **'you may add photos'**
  String get roleWordYouContribute;

  /// What the view role allows, said to the person
  ///
  /// In en, this message translates to:
  /// **'you may look at the albums'**
  String get roleWordYouView;

  /// What the widest clearance shows, about somebody else
  ///
  /// In en, this message translates to:
  /// **'sees all images'**
  String get clearanceWordAll;

  /// What the middle clearance shows, about somebody else
  ///
  /// In en, this message translates to:
  /// **'sees all but the private images'**
  String get clearanceWordNonPrivate;

  /// What the narrowest clearance shows, about somebody else
  ///
  /// In en, this message translates to:
  /// **'sees the public images'**
  String get clearanceWordPublic;

  /// Shown when the server address field is empty
  ///
  /// In en, this message translates to:
  /// **'Enter the URL of the album server, e.g. \'http://nas.local:8080/valbum/\'.'**
  String get serverUrlEmpty;

  /// Shown when what was entered cannot be read as a server address
  ///
  /// In en, this message translates to:
  /// **'That is not a server address. It looks like \'http://nas.local:8080/valbum/\'.'**
  String get serverUrlInvalid;

  /// Shown when a share link is pasted where a server is asked for
  ///
  /// In en, this message translates to:
  /// **'This is a link to a shared album, not a sign-in. Open it in a browser to see what was shared with you.'**
  String get shareLinkRefusal;

  /// Button closing a dialog
  ///
  /// In en, this message translates to:
  /// **'Close'**
  String get close;

  /// Button creating what the dialog asked for
  ///
  /// In en, this message translates to:
  /// **'Create'**
  String get create;

  /// Tooltip of the way back out of a screen
  ///
  /// In en, this message translates to:
  /// **'Back'**
  String get back;

  /// Button asking a running transfer to stop
  ///
  /// In en, this message translates to:
  /// **'Stop'**
  String get stop;

  /// How a share link with no label of its own and no album name is called
  ///
  /// In en, this message translates to:
  /// **'Shared album'**
  String get sharedAlbumFallback;

  /// The best of the five rating levels
  ///
  /// In en, this message translates to:
  /// **'Very good'**
  String get ratingVeryGood;

  /// The second of the five rating levels
  ///
  /// In en, this message translates to:
  /// **'Good'**
  String get ratingGood;

  /// The middle rating level: no rating given
  ///
  /// In en, this message translates to:
  /// **'Unrated'**
  String get ratingUnrated;

  /// The fourth of the five rating levels
  ///
  /// In en, this message translates to:
  /// **'Poor'**
  String get ratingPoor;

  /// The lowest rating level, the waste basket
  ///
  /// In en, this message translates to:
  /// **'Trash'**
  String get ratingTrash;

  /// Says that a share link shows the photos of every rating
  ///
  /// In en, this message translates to:
  /// **'every photo'**
  String get ratingFloorEveryPhoto;

  /// Says which rating a share link shows from upwards
  ///
  /// In en, this message translates to:
  /// **'at least {rating}'**
  String ratingFloorAtLeast(String rating);

  /// Button leaving a dead invitation page for the ordinary start
  ///
  /// In en, this message translates to:
  /// **'Continue to the start page'**
  String get shareContinueToStart;

  /// Button leading back to the root of what a share link opens
  ///
  /// In en, this message translates to:
  /// **'Back to the shared album'**
  String get shareBackToAlbum;

  /// Choice: the link never expires
  ///
  /// In en, this message translates to:
  /// **'Never'**
  String get expiryNever;

  /// Choice: expires after one day
  ///
  /// In en, this message translates to:
  /// **'1 day'**
  String get expiryOneDay;

  /// Choice: expires after one week
  ///
  /// In en, this message translates to:
  /// **'1 week'**
  String get expiryOneWeek;

  /// Choice: expires after one month
  ///
  /// In en, this message translates to:
  /// **'1 month'**
  String get expiryOneMonth;

  /// Choice opening a date picker for the day a link expires
  ///
  /// In en, this message translates to:
  /// **'A date…'**
  String get expiryPickDate;

  /// Title of the dialog listing and making the links of one folder
  ///
  /// In en, this message translates to:
  /// **'Share {name} by link'**
  String shareDialogTitle(String name);

  /// How the root folder is named in the share dialog's title
  ///
  /// In en, this message translates to:
  /// **'the top level'**
  String get shareTargetTopLevel;

  /// Heading above the share links covering a folder
  ///
  /// In en, this message translates to:
  /// **'Links'**
  String get linksHeading;

  /// Shown where no share link covers this folder
  ///
  /// In en, this message translates to:
  /// **'No links yet.'**
  String get noLinksYet;

  /// Stands in the list for a share link its author gave no label
  ///
  /// In en, this message translates to:
  /// **'(no label)'**
  String get linkNoLabel;

  /// Tooltip of the button taking a share link back
  ///
  /// In en, this message translates to:
  /// **'Withdraw…'**
  String get withdrawTooltip;

  /// Part of a link's description: it has no expiry
  ///
  /// In en, this message translates to:
  /// **'never expires'**
  String get linkNeverExpires;

  /// Part of a link's description: it shows what members see
  ///
  /// In en, this message translates to:
  /// **'up to members'**
  String get linkUpToMembers;

  /// Part of a link's description: it shows only public photos
  ///
  /// In en, this message translates to:
  /// **'public only'**
  String get linkPublicOnly;

  /// Says on which day a share link was taken back
  ///
  /// In en, this message translates to:
  /// **'withdrawn {day}'**
  String linkWithdrawnOn(String day);

  /// Says that a link was made on a folder further up
  ///
  /// In en, this message translates to:
  /// **'inherited from {folder}, withdraw it there'**
  String linkInheritedFrom(String folder);

  /// How a link covering the root of a space names its folder
  ///
  /// In en, this message translates to:
  /// **'the whole space'**
  String get shareWholeSpace;

  /// Entry opening the form of a new share link
  ///
  /// In en, this message translates to:
  /// **'New link…'**
  String get newLinkTile;

  /// Heading above the form of a new share link
  ///
  /// In en, this message translates to:
  /// **'New link'**
  String get newLinkHeading;

  /// Field naming a share link in its author's own list
  ///
  /// In en, this message translates to:
  /// **'Label'**
  String get linkLabelLabel;

  /// Explains the label field of a new share link
  ///
  /// In en, this message translates to:
  /// **'What this link is, for your own list.'**
  String get linkLabelHelp;

  /// Heading above the choices of how long a link or invitation lives
  ///
  /// In en, this message translates to:
  /// **'Expires'**
  String get expiresHeading;

  /// Heading above the choices of what a share link shows
  ///
  /// In en, this message translates to:
  /// **'Shows'**
  String get showsHeading;

  /// Choice: the link shows only the photos marked public
  ///
  /// In en, this message translates to:
  /// **'Public photos only'**
  String get privacyPublicOnly;

  /// Choice: the link shows what a member of the space sees
  ///
  /// In en, this message translates to:
  /// **'Up to what members see'**
  String get privacyUpToMembers;

  /// Explains the ceiling of what any share link can show
  ///
  /// In en, this message translates to:
  /// **'A private photo is never shown through a link.'**
  String get privacyMembersNote;

  /// Heading above the rating floor of a new share link
  ///
  /// In en, this message translates to:
  /// **'Lowest rating'**
  String get lowestRatingHeading;

  /// Says that a share link can never carry the right to edit
  ///
  /// In en, this message translates to:
  /// **'A link never allows editing.'**
  String get linkNeverEdits;

  /// Button making the share link the form describes
  ///
  /// In en, this message translates to:
  /// **'Create link'**
  String get createLink;

  /// Heading above the URL of a share link that was just made
  ///
  /// In en, this message translates to:
  /// **'The link'**
  String get theLinkHeading;

  /// Warns that the share link URL is shown exactly once
  ///
  /// In en, this message translates to:
  /// **'Copy it now: the server keeps only its fingerprint and can never show it again. A lost link is withdrawn and made anew.'**
  String get shareLinkOnce;

  /// Said after the share link URL went to the clipboard
  ///
  /// In en, this message translates to:
  /// **'The link was copied.'**
  String get linkCopied;

  /// Title of the dialog confirming that a share link is taken back
  ///
  /// In en, this message translates to:
  /// **'Withdraw the link?'**
  String get withdrawLinkTitle;

  /// Explains what taking a share link back does
  ///
  /// In en, this message translates to:
  /// **'Anybody holding {link} stops seeing the album at once. This cannot be undone; a new link can be made instead.'**
  String withdrawLinkMessage(String link);

  /// How a share link with no label is named in the withdraw question
  ///
  /// In en, this message translates to:
  /// **'this link'**
  String get withdrawLinkThisLink;

  /// Explains the retired guest role on the welcome screen of an invitation
  ///
  /// In en, this message translates to:
  /// **'A guest has no albums of their own: their library is what others share with them.'**
  String get invitationGuestNote;

  /// Explains the device name field
  ///
  /// In en, this message translates to:
  /// **'Which of your devices this is.'**
  String get deviceNameHelp;

  /// Shown while the invitation is being accepted
  ///
  /// In en, this message translates to:
  /// **'Joining...'**
  String get joining;

  /// Button accepting an invitation
  ///
  /// In en, this message translates to:
  /// **'Join'**
  String get joinAction;

  /// Said after an invitation was accepted
  ///
  /// In en, this message translates to:
  /// **'You\'re in as {user}.'**
  String invitationJoinedAs(String user);

  /// Said below the welcome after an invitation was accepted
  ///
  /// In en, this message translates to:
  /// **'This device is signed in; your albums are yours from now on.'**
  String get invitationSignedInNote;

  /// Button leaving the invitation page for the album
  ///
  /// In en, this message translates to:
  /// **'Open your albums'**
  String get openYourAlbums;

  /// Refusal shown where the invited person named nobody
  ///
  /// In en, this message translates to:
  /// **'Choose the name you want to be known by.'**
  String get invitationChooseName;

  /// Title of the dialog issuing an invitation
  ///
  /// In en, this message translates to:
  /// **'Invite somebody'**
  String get inviteDialogTitle;

  /// Field holding the inviter's own note about whom an invitation is for
  ///
  /// In en, this message translates to:
  /// **'For whom'**
  String get inviteRecipientLabel;

  /// Explains the recipient field of an invitation
  ///
  /// In en, this message translates to:
  /// **'A note to yourself: whom this invitation is for. Optional.'**
  String get inviteRecipientHelp;

  /// Field holding what the invited person reads
  ///
  /// In en, this message translates to:
  /// **'Note'**
  String get inviteNoteLabel;

  /// Explains the note field of an invitation
  ///
  /// In en, this message translates to:
  /// **'What the invited person reads when they open the link.'**
  String get inviteNoteHelp;

  /// Button issuing the invitation the form describes
  ///
  /// In en, this message translates to:
  /// **'Create invitation'**
  String get createInvitation;

  /// Heading above the URL of an invitation that was just issued
  ///
  /// In en, this message translates to:
  /// **'The invitation'**
  String get theInvitationHeading;

  /// Says how long an invitation lives
  ///
  /// In en, this message translates to:
  /// **'Valid until {day}, and for one person.'**
  String invitationValidUntil(String day);

  /// Warns that the invitation URL is shown exactly once
  ///
  /// In en, this message translates to:
  /// **'Send it now: the server keeps only its fingerprint and can never show it again. A lost invitation is withdrawn and made anew.'**
  String get invitationOnce;

  /// Said after the invitation URL went to the clipboard
  ///
  /// In en, this message translates to:
  /// **'The invitation was copied.'**
  String get invitationCopied;

  /// Says where the camera roll uploads while no album was chosen
  ///
  /// In en, this message translates to:
  /// **'No album chosen - new photos go into \'{name}\''**
  String inboxNotChosen(String name);

  /// Heading of the camera-roll section of the server settings
  ///
  /// In en, this message translates to:
  /// **'Camera roll'**
  String get cameraRollHeading;

  /// Explains what the camera-roll sync does
  ///
  /// In en, this message translates to:
  /// **'New photos taken on this device are uploaded into an album of the library. Nothing is uploaded twice: the server is asked for the content of every photo before it is transferred.'**
  String get cameraRollExplanation;

  /// Switch enabling the camera-roll sync
  ///
  /// In en, this message translates to:
  /// **'Upload new photos'**
  String get cameraRollUploadNew;

  /// Said where the device has no photo library at all
  ///
  /// In en, this message translates to:
  /// **'No photo library on this platform'**
  String get noPhotoLibrary;

  /// Switch limiting the camera-roll sync to Wi-Fi
  ///
  /// In en, this message translates to:
  /// **'Only over Wi-Fi'**
  String get onlyOverWifi;

  /// Explains the Wi-Fi-only switch
  ///
  /// In en, this message translates to:
  /// **'New photos wait for a Wi-Fi or a wired connection, so that the upload does not eat into a mobile data plan.'**
  String get onlyOverWifiExplanation;

  /// Button opening the picker of the album new photos go into
  ///
  /// In en, this message translates to:
  /// **'Choose...'**
  String get chooseAction;

  /// Button starting a camera-roll sync at once
  ///
  /// In en, this message translates to:
  /// **'Sync now'**
  String get syncNow;

  /// Button starting a sync although the server is still indexing
  ///
  /// In en, this message translates to:
  /// **'Sync anyway'**
  String get syncAnyway;

  /// Heading above the device albums the sync watches
  ///
  /// In en, this message translates to:
  /// **'Albums to sync'**
  String get albumsToSync;

  /// How many photos an album of the device holds
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 photo} other{{count} photos}}'**
  String photoCount(int count);

  /// Refusal shown where an album is chosen before a server is known
  ///
  /// In en, this message translates to:
  /// **'Save the server URL first, then choose an album on it.'**
  String get saveServerFirst;

  /// Title of the dialog choosing the album new photos go into
  ///
  /// In en, this message translates to:
  /// **'Inbox album'**
  String get inboxAlbumTitle;

  /// Said where the folders of a server cannot be read
  ///
  /// In en, this message translates to:
  /// **'Cannot list: {problem}'**
  String cannotList(String problem);

  /// Button creating an album in the folder shown
  ///
  /// In en, this message translates to:
  /// **'New album...'**
  String get newAlbumAction;

  /// Button choosing the folder shown as the inbox
  ///
  /// In en, this message translates to:
  /// **'Use this album'**
  String get useThisAlbum;

  /// The root of the library in the breadcrumb of the inbox picker
  ///
  /// In en, this message translates to:
  /// **'Library'**
  String get libraryBreadcrumb;

  /// Said where a folder of the library holds no folders
  ///
  /// In en, this message translates to:
  /// **'No folders here yet - create one below.'**
  String get noFoldersHere;

  /// Said where the inbox picker stands in an album
  ///
  /// In en, this message translates to:
  /// **'This is the album \'{title}\'. New photos land here.'**
  String folderIsAlbum(String title);

  /// Said where the server answered something the picker cannot show
  ///
  /// In en, this message translates to:
  /// **'Nothing to show here.'**
  String get nothingToShow;

  /// Title of the dialog asking for the name of a new album
  ///
  /// In en, this message translates to:
  /// **'New album'**
  String get newAlbumTitle;

  /// Field holding the name of the folder an album is written to
  ///
  /// In en, this message translates to:
  /// **'Folder name'**
  String get folderNameLabel;

  /// Said where an album could not be created
  ///
  /// In en, this message translates to:
  /// **'Cannot create \'{name}\': {problem}'**
  String cannotCreateFolder(String problem, String name);

  /// Tooltip of the way out of the alternatives view
  ///
  /// In en, this message translates to:
  /// **'Back to the album'**
  String get backToAlbum;

  /// Tooltip of the image representing a group in the album
  ///
  /// In en, this message translates to:
  /// **'Group picture'**
  String get groupPicture;

  /// Tooltip of the button while the image shown represents its group
  ///
  /// In en, this message translates to:
  /// **'This image is the group picture'**
  String get groupPictureIsThis;

  /// Button making the image shown represent its group
  ///
  /// In en, this message translates to:
  /// **'Use as group picture'**
  String get useAsGroupPicture;

  /// Said while the server is still making the video rendition
  ///
  /// In en, this message translates to:
  /// **'The video is being prepared…'**
  String get videoPreparing;

  /// Button playing the original file instead of waiting
  ///
  /// In en, this message translates to:
  /// **'Play the original'**
  String get videoPlayOriginal;

  /// Headline shown where a video cannot be played
  ///
  /// In en, this message translates to:
  /// **'Cannot play this video.'**
  String get videoCannotPlay;

  /// Hint below the video refusal where the video never arrived
  ///
  /// In en, this message translates to:
  /// **'The server could not be reached, or it refused the video.'**
  String get videoNetworkHint;

  /// Hint below the video refusal where the format cannot be decoded
  ///
  /// In en, this message translates to:
  /// **'This device cannot play the format of this video.'**
  String get videoFormatHint;

  /// Says where the raw failure of a video can be read
  ///
  /// In en, this message translates to:
  /// **'The technical details are in the diagnostics log of the server settings.'**
  String get videoDiagnosticsHint;

  /// Tooltip of the button pausing a video
  ///
  /// In en, this message translates to:
  /// **'Pause'**
  String get pause;

  /// Tooltip of the button playing a video
  ///
  /// In en, this message translates to:
  /// **'Play'**
  String get play;

  /// Menu entry opening the app's own picker over the photo library
  ///
  /// In en, this message translates to:
  /// **'From the phone\'s photo library...'**
  String get photoPickerEntry;

  /// Menu entry opening the system's file picker
  ///
  /// In en, this message translates to:
  /// **'Choose files... (max. 100)'**
  String get systemPickerEntry;

  /// Title of the screen picking photos off the device
  ///
  /// In en, this message translates to:
  /// **'Photo library'**
  String get photoLibraryTitle;

  /// Tooltip leading back to the list of the device's albums
  ///
  /// In en, this message translates to:
  /// **'All albums'**
  String get allAlbums;

  /// Button selecting every photo shown
  ///
  /// In en, this message translates to:
  /// **'All'**
  String get selectAll;

  /// Button clearing the selection
  ///
  /// In en, this message translates to:
  /// **'None'**
  String get selectNone;

  /// Said where the device refused access to its photos
  ///
  /// In en, this message translates to:
  /// **'No access to the photo library of this device.'**
  String get photoLibraryNoAccess;

  /// Said where the photo library answered with a failure
  ///
  /// In en, this message translates to:
  /// **'The photo library cannot be read: {problem}'**
  String photoLibraryUnreadable(String problem);

  /// Said where one album of the device answered with a failure
  ///
  /// In en, this message translates to:
  /// **'The album cannot be read: {problem}'**
  String photoAlbumUnreadable(String problem);

  /// Said where the device names no album at all
  ///
  /// In en, this message translates to:
  /// **'The photo library of this device holds no albums.'**
  String get photoLibraryNoAlbums;

  /// Said where an album of the device holds nothing
  ///
  /// In en, this message translates to:
  /// **'This album holds no photos.'**
  String get photoAlbumEmpty;

  /// Says how many photos of the device are selected
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =0{Nothing selected} =1{1 selected} other{{count} selected}}'**
  String photoPickerSelected(int count);

  /// Button uploading the photos selected on the device
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{Upload 1 photo} other{Upload {count} photos}}'**
  String photoPickerUpload(int count);

  /// Title of the dialog an upload runs behind
  ///
  /// In en, this message translates to:
  /// **'Upload photos'**
  String get uploadProgressTitle;

  /// Title of the camera page reading a device code
  ///
  /// In en, this message translates to:
  /// **'Scan a device code'**
  String get scanCodeTitle;

  /// Says what to point the camera at while scanning a device code
  ///
  /// In en, this message translates to:
  /// **'Point the camera at the code shown under My devices on the device you are already signed in on.'**
  String get scanCodeAdvice;

  /// Said where the camera permission was refused
  ///
  /// In en, this message translates to:
  /// **'This app is not allowed to use the camera. Allow it in the system settings, or type the code instead.'**
  String get cameraNotAllowed;

  /// Said where the device has no camera to scan with
  ///
  /// In en, this message translates to:
  /// **'This device cannot scan a code. Type it instead.'**
  String get cameraUnsupported;

  /// Said where the camera failed for a reason the plugin does not name
  ///
  /// In en, this message translates to:
  /// **'The camera could not be opened. Type the code instead.'**
  String get cameraNotOpened;

  /// Button closing a dialog that only showed something
  ///
  /// In en, this message translates to:
  /// **'OK'**
  String get ok;

  /// Button confirming that something is deleted
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get delete;

  /// Menu entry opening the delete question for an entry of a listing
  ///
  /// In en, this message translates to:
  /// **'Delete…'**
  String get deleteEllipsis;

  /// Menu entry fetching the current view from the server again
  ///
  /// In en, this message translates to:
  /// **'Reload'**
  String get reload;

  /// Tooltip of the control leading to the folder above
  ///
  /// In en, this message translates to:
  /// **'Up'**
  String get up;

  /// Tooltip of the control leading to the top of the library
  ///
  /// In en, this message translates to:
  /// **'Home'**
  String get home;

  /// Tooltip of the button adding photos to the album shown
  ///
  /// In en, this message translates to:
  /// **'Upload'**
  String get upload;

  /// Button of a dialog taking over what was entered
  ///
  /// In en, this message translates to:
  /// **'Apply'**
  String get apply;

  /// Button throwing unsaved changes away
  ///
  /// In en, this message translates to:
  /// **'Discard'**
  String get discard;

  /// Button of the leave question: go on editing
  ///
  /// In en, this message translates to:
  /// **'Stay'**
  String get stay;

  /// Button of the discard question: go on editing
  ///
  /// In en, this message translates to:
  /// **'Keep editing'**
  String get keepEditing;

  /// Button confirming that the generated previews are made anew
  ///
  /// In en, this message translates to:
  /// **'Refresh'**
  String get refresh;

  /// Tooltip of the tool opening a photograph full screen
  ///
  /// In en, this message translates to:
  /// **'Open'**
  String get open;

  /// Tooltip of the selection box of a tile
  ///
  /// In en, this message translates to:
  /// **'Select'**
  String get select;

  /// Tooltip of the tool making a group of the selected images
  ///
  /// In en, this message translates to:
  /// **'Group'**
  String get group;

  /// Shown while a view waits for the server
  ///
  /// In en, this message translates to:
  /// **'Loading...'**
  String get loading;

  /// Menu entry opening the server settings
  ///
  /// In en, this message translates to:
  /// **'Server...'**
  String get serverMenuEntry;

  /// Label of the field holding the title of an album or a folder
  ///
  /// In en, this message translates to:
  /// **'Title'**
  String get titleLabel;

  /// Label of the field holding the subtitle of an album
  ///
  /// In en, this message translates to:
  /// **'Subtitle'**
  String get subtitleLabel;

  /// Label of the field holding the name of a new folder
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get nameLabel;

  /// Label of the field holding the date of an album
  ///
  /// In en, this message translates to:
  /// **'Date'**
  String get dateLabel;

  /// Label of the field holding the description of an image
  ///
  /// In en, this message translates to:
  /// **'Comment'**
  String get commentLabel;

  /// Label of the field holding the text of a heading inside an album
  ///
  /// In en, this message translates to:
  /// **'Heading'**
  String get headingLabel;

  /// Said under a field that has to be filled in
  ///
  /// In en, this message translates to:
  /// **'Must not be empty'**
  String get mustNotBeEmpty;

  /// Menu entry showing the album as its owner sees it
  ///
  /// In en, this message translates to:
  /// **'Yourself'**
  String get viewAsYourself;

  /// Menu entry showing the album as a member of the space sees it
  ///
  /// In en, this message translates to:
  /// **'Members'**
  String get viewAsMembers;

  /// Menu entry showing the album as the public sees it
  ///
  /// In en, this message translates to:
  /// **'Public'**
  String get viewAsPublic;

  /// Names the view on the screen in the line 'View as - yourself'
  ///
  /// In en, this message translates to:
  /// **'yourself'**
  String get viewAsStateYourself;

  /// Names the view on the screen in the line 'View as - members'
  ///
  /// In en, this message translates to:
  /// **'members'**
  String get viewAsStateMembers;

  /// Names the view on the screen in the line 'View as - public'
  ///
  /// In en, this message translates to:
  /// **'public'**
  String get viewAsStatePublic;

  /// Menu label above the three choices of whose view of the album is shown
  ///
  /// In en, this message translates to:
  /// **'View as'**
  String get viewAsLabel;

  /// Banner over an album shown as a member of the space sees it
  ///
  /// In en, this message translates to:
  /// **'Viewing as members - this is what members see'**
  String get previewAsMembers;

  /// Banner over an album shown as the public sees it
  ///
  /// In en, this message translates to:
  /// **'Viewing as public - this is what the public sees'**
  String get previewAsPublic;

  /// Title of the dialog editing the text of a heading inside an album
  ///
  /// In en, this message translates to:
  /// **'Edit heading'**
  String get editHeadingTitle;

  /// Tooltip of the tool putting a heading before a tile
  ///
  /// In en, this message translates to:
  /// **'Insert heading'**
  String get insertHeading;

  /// Tooltip of the tool removing a heading from an album
  ///
  /// In en, this message translates to:
  /// **'Delete heading'**
  String get deleteHeadingTooltip;

  /// Said when sorting an album by date would change nothing
  ///
  /// In en, this message translates to:
  /// **'Already in order'**
  String get alreadyInOrder;

  /// Said when no further image of the album was taken with the same camera
  ///
  /// In en, this message translates to:
  /// **'No other image from this camera'**
  String get noOtherImageFromCamera;

  /// Said when a recording time correction would change nothing
  ///
  /// In en, this message translates to:
  /// **'Nothing to adjust'**
  String get nothingToAdjust;

  /// Said when the album could not be written to the server
  ///
  /// In en, this message translates to:
  /// **'Saving failed: {problem}'**
  String saveFailed(String problem);

  /// Title of the question asked when an edit is cancelled
  ///
  /// In en, this message translates to:
  /// **'Discard the changes to this album?'**
  String get discardChangesTitle;

  /// Explains what discarding the changes to an album does
  ///
  /// In en, this message translates to:
  /// **'The changes made here have not been saved. Discarding them shows the album again as the server has it.'**
  String get discardChangesMessage;

  /// Title of the question asked when an album with unsaved changes is left
  ///
  /// In en, this message translates to:
  /// **'Save the changes to this album?'**
  String get saveChangesTitle;

  /// Explains what leaving an album with unsaved changes does
  ///
  /// In en, this message translates to:
  /// **'Leaving the album ends the edit. Unsaved changes are lost unless they are saved now.'**
  String get saveChangesMessage;

  /// Refuses an action that would throw unsaved changes away
  ///
  /// In en, this message translates to:
  /// **'Save or discard your changes first'**
  String get saveOrDiscardFirst;

  /// Refuses a move of a selection holding nothing but headings
  ///
  /// In en, this message translates to:
  /// **'A heading cannot be moved.'**
  String get headingCannotMove;

  /// Menu entry opening the dialog that hands out a link to a folder
  ///
  /// In en, this message translates to:
  /// **'Share link…'**
  String get shareLinkAction;

  /// Menu entry and dialog title of the properties of an album
  ///
  /// In en, this message translates to:
  /// **'Album properties'**
  String get albumProperties;

  /// Menu entry and dialog title of the properties of a folder
  ///
  /// In en, this message translates to:
  /// **'Folder properties'**
  String get folderProperties;

  /// Menu entry moving what is named into another folder
  ///
  /// In en, this message translates to:
  /// **'Move {subject} to…'**
  String moveSubjectTo(String subject);

  /// Menu entry moving the album being shown into another folder
  ///
  /// In en, this message translates to:
  /// **'Move album to…'**
  String get moveAlbumTo;

  /// Menu entry deleting the album being shown
  ///
  /// In en, this message translates to:
  /// **'Delete album…'**
  String get deleteAlbumAction;

  /// Menu entry deleting what is named
  ///
  /// In en, this message translates to:
  /// **'Delete {subject}…'**
  String deleteSubjectAction(String subject);

  /// Menu entry ordering the images of an album by their recording time
  ///
  /// In en, this message translates to:
  /// **'Sort by date'**
  String get sortByDate;

  /// Menu label above the rating filter of an album
  ///
  /// In en, this message translates to:
  /// **'Minimum rating'**
  String get minRatingLabel;

  /// Menu entry lowering the rating an image needs to be shown
  ///
  /// In en, this message translates to:
  /// **'Show more images'**
  String get showMoreImages;

  /// Menu entry raising the rating an image needs to be shown
  ///
  /// In en, this message translates to:
  /// **'Show fewer images'**
  String get showFewerImages;

  /// Menu entry looking for photos the library already holds elsewhere
  ///
  /// In en, this message translates to:
  /// **'Find duplicates...'**
  String get findDuplicatesAction;

  /// Title of the question asked before duplicates are set aside
  ///
  /// In en, this message translates to:
  /// **'Find duplicates'**
  String get findDuplicatesTitle;

  /// Explains what looking for duplicates does
  ///
  /// In en, this message translates to:
  /// **'Every photo of this album that the library already holds somewhere else is taken out of the album and kept aside in the library\'s own folder. Nothing is deleted, and the other copy stays where it is.'**
  String get findDuplicatesMessage;

  /// Said when the duplicate sweep found nothing
  ///
  /// In en, this message translates to:
  /// **'No photo of this album is anywhere else in the library.'**
  String get noDuplicatesFound;

  /// Says how many photos the duplicate sweep took out of the album
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 photo was set aside; the copy that stays is elsewhere in the library.} other{{count} photos were set aside; the copies that stay are elsewhere in the library.}}'**
  String duplicatesSetAside(int count);

  /// Menu entry and dialog title of throwing the generated previews away
  ///
  /// In en, this message translates to:
  /// **'Refresh previews'**
  String get refreshPreviews;

  /// Explains what refreshing the previews of an album does
  ///
  /// In en, this message translates to:
  /// **'The thumbnails and video renditions of this album are thrown away and made anew when they are next shown. The photos themselves are not touched.'**
  String get refreshPreviewsMessage;

  /// Says how many generated files the server deleted
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 cached file thrown away; the previews are made anew.} other{{count} cached files thrown away; the previews are made anew.}}'**
  String previewsRefreshed(int count);

  /// Said when the preview of another clearance is not an album
  ///
  /// In en, this message translates to:
  /// **'The server did not answer with an album.'**
  String get notAnAlbumAnswer;

  /// Said where the rating filter hides every image of the album
  ///
  /// In en, this message translates to:
  /// **'No image is rated {rating} or better - press + (or the + button) to show more.'**
  String ratingFilterHidesAll(int rating);

  /// Tooltip of the tool turning an image a quarter to the right
  ///
  /// In en, this message translates to:
  /// **'Turn right'**
  String get turnRight;

  /// Tooltip of the tool turning an image a quarter to the left
  ///
  /// In en, this message translates to:
  /// **'Turn left'**
  String get turnLeft;

  /// Tooltip of the tool mirroring an image top to bottom
  ///
  /// In en, this message translates to:
  /// **'Flip vertically'**
  String get flipVertically;

  /// Tooltip of the tool dissolving a group of images
  ///
  /// In en, this message translates to:
  /// **'Ungroup'**
  String get ungroupAction;

  /// Tooltip of the tool opening the alternatives of a group to pick the one shown
  ///
  /// In en, this message translates to:
  /// **'Choose the group picture'**
  String get chooseGroupPicture;

  /// Tooltip of the tool adding every image of the same camera to the selection
  ///
  /// In en, this message translates to:
  /// **'Select all from this camera'**
  String get selectAllFromCamera;

  /// Tooltip of the tool correcting the recording time of the selection
  ///
  /// In en, this message translates to:
  /// **'Adjust recording time…'**
  String get adjustRecordingTimeAction;

  /// Tooltip and dialog title of what an image is
  ///
  /// In en, this message translates to:
  /// **'Image properties'**
  String get imageProperties;

  /// Tooltip of the tool making this image the picture of the album
  ///
  /// In en, this message translates to:
  /// **'Use as album picture'**
  String get useAsAlbumPicture;

  /// Tooltip of the mark on the tile that is the album's picture
  ///
  /// In en, this message translates to:
  /// **'Album picture'**
  String get albumPictureTooltip;

  /// Tooltip of the control cycling through the privacy levels of an image
  ///
  /// In en, this message translates to:
  /// **'Privacy: {level} (tap for {next})'**
  String privacyControlTooltip(String next, String level);

  /// Refuses a grouping of fewer than two images
  ///
  /// In en, this message translates to:
  /// **'Select at least two images to group them'**
  String get groupNeedsTwo;

  /// Tooltip of the handle a tile is picked up by
  ///
  /// In en, this message translates to:
  /// **'Drag to reorder'**
  String get dragToReorder;

  /// Says how many album parts a drag carries
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 part} other{{count} parts}}'**
  String partCount(int count);

  /// Said under the field of the recording time dialog while the text is not a time
  ///
  /// In en, this message translates to:
  /// **'Not a time (yyyy-MM-dd HH:mm:ss)'**
  String get notATime;

  /// Says how many images a recording time correction changes
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{Applies to 1 image} other{Applies to {count} images}}'**
  String appliesToImages(int count);

  /// Button dating one image by the time its file name says
  ///
  /// In en, this message translates to:
  /// **'Use the time in the file name: {time}'**
  String useNameDateOne(String time);

  /// Button dating several images by the time their file names say
  ///
  /// In en, this message translates to:
  /// **'Use the time in the file name ({count} images)'**
  String useNameDateMany(int count);

  /// Title of the dialog correcting the recording time of a selection
  ///
  /// In en, this message translates to:
  /// **'Adjust recording time'**
  String get adjustRecordingTimeTitle;

  /// Label of the field holding the corrected recording time
  ///
  /// In en, this message translates to:
  /// **'Correct time'**
  String get correctTime;

  /// Tooltip of the button opening the date and time pickers
  ///
  /// In en, this message translates to:
  /// **'Pick date and time'**
  String get pickDateAndTime;

  /// Says what a recording time correction does not touch
  ///
  /// In en, this message translates to:
  /// **'The original recording time stays in the photo; the album keeps its own.'**
  String get adjustRecordingTimeHelp;

  /// Label of the switch turning an inbox back into an ordinary album
  ///
  /// In en, this message translates to:
  /// **'Make this an album'**
  String get makeThisAnAlbum;

  /// Label of the switch turning an album into an inbox
  ///
  /// In en, this message translates to:
  /// **'Make this an inbox'**
  String get makeThisAnInbox;

  /// Says what an inbox is, beside the switch that makes one
  ///
  /// In en, this message translates to:
  /// **'Photographs waiting to be sorted, shown by the day they were taken.'**
  String get inboxExplanation;

  /// Says that an album carries no date at all
  ///
  /// In en, this message translates to:
  /// **'Date: none'**
  String get dateNone;

  /// Says the date an album is shown and filed by
  ///
  /// In en, this message translates to:
  /// **'Date: {date}'**
  String dateIs(String date);

  /// Tooltip of the button opening the date picker of an album
  ///
  /// In en, this message translates to:
  /// **'Pick a date'**
  String get pickDate;

  /// Tooltip of the button taking the explicit date of an album away
  ///
  /// In en, this message translates to:
  /// **'Remove the date'**
  String get clearDate;

  /// Says where the date shown for an album comes from
  ///
  /// In en, this message translates to:
  /// **'Taken from the folder name.'**
  String get dateFromFolderName;

  /// Says where the date shown for an album comes from
  ///
  /// In en, this message translates to:
  /// **'Taken from the photos.'**
  String get dateFromPhotos;

  /// Said in the album properties while no picture stands for the album
  ///
  /// In en, this message translates to:
  /// **'No album picture chosen - choose one on a tile in the edit mode.'**
  String get noAlbumPictureHint;

  /// Tooltip of the button enlarging the crop of the album picture
  ///
  /// In en, this message translates to:
  /// **'Zoom in'**
  String get zoomIn;

  /// Tooltip of the button shrinking the crop of the album picture
  ///
  /// In en, this message translates to:
  /// **'Zoom out'**
  String get zoomOut;

  /// Tooltip of the button putting the crop of the album picture back
  ///
  /// In en, this message translates to:
  /// **'Reset the crop'**
  String get resetCrop;

  /// The privacy level of an image everybody may see
  ///
  /// In en, this message translates to:
  /// **'Public'**
  String get privacyPublicName;

  /// The privacy level of an image only members of the space may see
  ///
  /// In en, this message translates to:
  /// **'Members'**
  String get privacyMembersName;

  /// The privacy level of an image only its owner may see
  ///
  /// In en, this message translates to:
  /// **'Private'**
  String get privacyPrivateName;

  /// The abbreviation of the unit day, in a time offset such as '+1 d 4 h'
  ///
  /// In en, this message translates to:
  /// **'d'**
  String get unitDays;

  /// The abbreviation of the unit hour, in a time offset such as '+1 d 4 h'
  ///
  /// In en, this message translates to:
  /// **'h'**
  String get unitHours;

  /// The abbreviation of the unit minute, in a time offset such as '+13 min 5 s'
  ///
  /// In en, this message translates to:
  /// **'min'**
  String get unitMinutes;

  /// The abbreviation of the unit second, in a time offset such as '+13 min 5 s'
  ///
  /// In en, this message translates to:
  /// **'s'**
  String get unitSeconds;

  /// Menu entry making this entry the picture of the folder above
  ///
  /// In en, this message translates to:
  /// **'Use as folder picture'**
  String get useAsFolderPicture;

  /// Menu entry taking the picture of the folder away
  ///
  /// In en, this message translates to:
  /// **'Use no folder picture'**
  String get useNoFolderPicture;

  /// Said where a library holds nothing
  ///
  /// In en, this message translates to:
  /// **'There are no albums here yet.'**
  String get libraryEmptyNotice;

  /// Says how an empty library is filled
  ///
  /// In en, this message translates to:
  /// **'Create the first one from the menu at the top right.'**
  String get libraryEmptyHint;

  /// Said where a folder below the top holds nothing
  ///
  /// In en, this message translates to:
  /// **'This folder has no albums yet.'**
  String get folderEmptyNotice;

  /// Menu entry making a new album in this folder
  ///
  /// In en, this message translates to:
  /// **'Create album'**
  String get createAlbum;

  /// Menu entry making a new folder in this folder
  ///
  /// In en, this message translates to:
  /// **'Create folder'**
  String get createFolder;

  /// Menu entry filing what is already in this folder by its rule
  ///
  /// In en, this message translates to:
  /// **'Apply rule'**
  String get applyRule;

  /// Menu entry and dialog title of moving something into another folder
  ///
  /// In en, this message translates to:
  /// **'Move to…'**
  String get moveToAction;

  /// Said when the filing rule moved nothing
  ///
  /// In en, this message translates to:
  /// **'Nothing to file.'**
  String get nothingToFile;

  /// Says how many albums the filing rule moved
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{Filed 1 album.} other{Filed {count} albums.}}'**
  String filedAlbums(int count);

  /// The filing rule that files nothing
  ///
  /// In en, this message translates to:
  /// **'no rule'**
  String get placementNone;

  /// The filing rule that files what arrives into its year folder
  ///
  /// In en, this message translates to:
  /// **'by year'**
  String get placementByYear;

  /// The filing rule that files what arrives into its year and month folder
  ///
  /// In en, this message translates to:
  /// **'by year and month'**
  String get placementByYearMonth;

  /// Heading above the choice of the filing rule of a folder
  ///
  /// In en, this message translates to:
  /// **'Filing rule'**
  String get placementHeading;

  /// Says what the filing rule of a folder does and does not do
  ///
  /// In en, this message translates to:
  /// **'What arrives here is filed into its year folder. What is already here stays until the filing rule is applied from the menu.'**
  String get placementExplanation;

  /// Says what leaving the date of a new album empty means
  ///
  /// In en, this message translates to:
  /// **'Without a date the album stays in this folder.'**
  String get createAlbumUndatedHint;

  /// Label of the choice making the new folder an inbox
  ///
  /// In en, this message translates to:
  /// **'Inbox'**
  String get createInboxLabel;

  /// Says what an inbox is, in the dialog making one
  ///
  /// In en, this message translates to:
  /// **'Photographs waiting to be sorted: shown by the day they were taken, no date and no order of their own.'**
  String get createInboxHint;

  /// Title of the dialog making a folder
  ///
  /// In en, this message translates to:
  /// **'New folder'**
  String get newFolderTitle;

  /// Names the images a move or a delete acts on
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 image} other{{count} images}}'**
  String imageCount(int count);

  /// Names the root of the library as the target of a move
  ///
  /// In en, this message translates to:
  /// **'the top level'**
  String get targetTopLevel;

  /// Names the root of the library in the folder picker
  ///
  /// In en, this message translates to:
  /// **'Top level'**
  String get pickerTopLevel;

  /// Refuses a move that names nothing
  ///
  /// In en, this message translates to:
  /// **'Nothing to move.'**
  String get nothingToMove;

  /// Button of the folder picker, naming what moves and where
  ///
  /// In en, this message translates to:
  /// **'Move {subject} to {target}'**
  String moveConfirm(String subject, String target);

  /// Says that a move changed nothing
  ///
  /// In en, this message translates to:
  /// **'Nothing moved to {target}.'**
  String nothingMovedTo(String target);

  /// Says what moved and where
  ///
  /// In en, this message translates to:
  /// **'Moved {subject} to {target}.'**
  String movedToTarget(String subject, String target);

  /// Says what deleting an album or a folder does
  ///
  /// In en, this message translates to:
  /// **'An album without any image is removed; one with images is moved to the trash folder of the space (nothing is deleted from disk).'**
  String get deleteExplanation;

  /// Title of the question asked before something is deleted
  ///
  /// In en, this message translates to:
  /// **'Delete {what}?'**
  String deleteQuestion(String what);

  /// Says what was deleted
  ///
  /// In en, this message translates to:
  /// **'Deleted {what}.'**
  String deletedWhat(String what);

  /// Said when an album was made but the move into it was refused
  ///
  /// In en, this message translates to:
  /// **'The new album {target} was created and is empty.'**
  String newAlbumCreatedEmpty(String target);

  /// Says why a folder of folders cannot take images
  ///
  /// In en, this message translates to:
  /// **'Images live in albums - open one.'**
  String get imagesLiveInAlbums;

  /// Says why an album cannot take an album or a folder
  ///
  /// In en, this message translates to:
  /// **'An album holds no folders.'**
  String get albumHoldsNoFolders;

  /// Said in the folder picker where the server answered something else
  ///
  /// In en, this message translates to:
  /// **'This folder cannot be shown.'**
  String get folderCannotBeShown;

  /// Entry of the folder picker making the album to move into
  ///
  /// In en, this message translates to:
  /// **'Create new album…'**
  String get createNewAlbum;

  /// Title of the dialog reporting what a move did
  ///
  /// In en, this message translates to:
  /// **'Move'**
  String get moveTitle;

  /// Said where an inbox holds nothing
  ///
  /// In en, this message translates to:
  /// **'Nothing is waiting here.'**
  String get inboxEmptyNotice;

  /// Heading of the photographs of an inbox whose date nobody knows
  ///
  /// In en, this message translates to:
  /// **'Undated'**
  String get inboxUndatedHeading;

  /// Says what deleting photographs of an inbox does
  ///
  /// In en, this message translates to:
  /// **'The photographs are moved to the trash folder of the space. Nothing is deleted from disk.'**
  String get inboxDeleteExplanation;

  /// Menu entry unselecting everything
  ///
  /// In en, this message translates to:
  /// **'Clear the selection'**
  String get clearSelection;

  /// Says how many photographs of an inbox are selected
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 image selected} other{{count} images selected}}'**
  String selectedCount(int count);

  /// Tooltip of a heading that selects the photographs under it
  ///
  /// In en, this message translates to:
  /// **'Select everything below'**
  String get selectEverythingBelow;

  /// Refuses an action that needs a selection
  ///
  /// In en, this message translates to:
  /// **'Nothing is selected.'**
  String get nothingSelected;

  /// Refuses the edit mode to a caller who may only look
  ///
  /// In en, this message translates to:
  /// **'You may not edit this album.'**
  String get notEditableMessage;

  /// Said over the thumbnail when the picture itself did not arrive
  ///
  /// In en, this message translates to:
  /// **'This picture could not be loaded.'**
  String get pictureFailedMessage;

  /// Tooltip of the control moving one's own photo out of somebody's album
  ///
  /// In en, this message translates to:
  /// **'Take back…'**
  String get takeBack;

  /// Tooltip of the control showing the image before this one
  ///
  /// In en, this message translates to:
  /// **'Previous image'**
  String get previousImage;

  /// Tooltip of the control showing the image after this one
  ///
  /// In en, this message translates to:
  /// **'Next image'**
  String get nextImage;

  /// Tooltip of the control opening the other images of a group
  ///
  /// In en, this message translates to:
  /// **'Show the alternatives'**
  String get showAlternatives;

  /// The line naming the file an image is stored in
  ///
  /// In en, this message translates to:
  /// **'File: {name}'**
  String propertyFile(String name);

  /// The line naming when an image was recorded
  ///
  /// In en, this message translates to:
  /// **'Taken: {time}'**
  String propertyTaken(String time);

  /// The line naming the camera an image was taken with
  ///
  /// In en, this message translates to:
  /// **'Camera: {camera}'**
  String propertyCamera(String camera);

  /// The line naming where an image was taken, in decimal degrees
  ///
  /// In en, this message translates to:
  /// **'Location: {latitude}, {longitude}'**
  String propertyLocation(String latitude, String longitude);

  /// Tooltip of the button opening the place a photo was taken on a map
  ///
  /// In en, this message translates to:
  /// **'Show on a map'**
  String get showOnMap;

  /// The line naming who brought a photo into an album
  ///
  /// In en, this message translates to:
  /// **'Added by {user}'**
  String addedBy(String user);

  /// The name of the right to see a folder and its thumbnails
  ///
  /// In en, this message translates to:
  /// **'View'**
  String get rightLabelView;

  /// The name of the right to fetch the originals
  ///
  /// In en, this message translates to:
  /// **'Download'**
  String get rightLabelDownload;

  /// The name of the right to add photos
  ///
  /// In en, this message translates to:
  /// **'Contribute'**
  String get rightLabelContribute;

  /// The name of the right to change a folder
  ///
  /// In en, this message translates to:
  /// **'Edit'**
  String get rightLabelEdit;

  /// What the right to view means
  ///
  /// In en, this message translates to:
  /// **'See the album and its thumbnails'**
  String get rightExplanationView;

  /// What the right to download means
  ///
  /// In en, this message translates to:
  /// **'Take copies of the originals'**
  String get rightExplanationDownload;

  /// What the right to contribute means
  ///
  /// In en, this message translates to:
  /// **'Add photos'**
  String get rightExplanationContribute;

  /// What the right to edit means
  ///
  /// In en, this message translates to:
  /// **'Change the album and everything in it'**
  String get rightExplanationEdit;

  /// Half a sentence saying what the caller may do with a folder
  ///
  /// In en, this message translates to:
  /// **'you may change it'**
  String get rightsPhraseEdit;

  /// Half a sentence saying what the caller may do with a folder
  ///
  /// In en, this message translates to:
  /// **'you may add photos'**
  String get rightsPhraseContribute;

  /// Half a sentence saying what the caller may do with a folder
  ///
  /// In en, this message translates to:
  /// **'you may look and download'**
  String get rightsPhraseDownload;

  /// Half a sentence saying what the caller may do with a folder
  ///
  /// In en, this message translates to:
  /// **'you may look'**
  String get rightsPhraseView;

  /// Half a sentence saying what the caller may do with a folder
  ///
  /// In en, this message translates to:
  /// **'you may do nothing here'**
  String get rightsPhraseNone;

  /// Says that the folder shown belongs to somebody else
  ///
  /// In en, this message translates to:
  /// **'Shared with you'**
  String get sharedWithYou;

  /// Names the owner of the folder shown
  ///
  /// In en, this message translates to:
  /// **'Shared by {owner}'**
  String sharedByOwner(String owner);

  /// Said when a request never got an answer
  ///
  /// In en, this message translates to:
  /// **'The server could not be reached: {problem}'**
  String serverNotReached(String problem);

  /// Said when the server refused a request without a reason of its own
  ///
  /// In en, this message translates to:
  /// **'HTTP {status} while {doing}.'**
  String httpFailure(String doing, int status);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'loading {url}'**
  String doingLoading(String url);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'loading the image'**
  String get doingLoadingImage;

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'storing {url}'**
  String doingStoring(String url);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'creating {url}'**
  String doingCreating(String url);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'uploading to {url}'**
  String doingUploading(String url);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'asking {url}'**
  String doingAsking(String url);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'moving to {target}'**
  String doingMoving(String target);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'deleting in {folder}'**
  String doingDeleting(String folder);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'filing in {folder}'**
  String doingFiling(String folder);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'looking for duplicates in {folder}'**
  String doingFindingDuplicates(String folder);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'signing in at {url}'**
  String doingSigningIn(String url);

  /// Names what the app was doing when a request failed
  ///
  /// In en, this message translates to:
  /// **'refreshing the previews of {folder}'**
  String doingRefreshingPreviews(String folder);

  /// Said when a thumbnail could not be fetched and none is cached
  ///
  /// In en, this message translates to:
  /// **'The server cannot be reached ({problem}), so there is nothing to preview.'**
  String serverUnreachableNoPreview(String problem);

  /// Said when a view could not be fetched and none is cached
  ///
  /// In en, this message translates to:
  /// **'The server cannot be reached ({problem}), and nothing is cached for this view.'**
  String serverUnreachableNoCache(String problem);

  /// Said when the answer is not album data
  ///
  /// In en, this message translates to:
  /// **'The server at {server} did not answer with album data - not a VAlbum server, or is the server URL in the settings wrong?'**
  String notVAlbumServer(String server);

  /// Names a request that timed out
  ///
  /// In en, this message translates to:
  /// **'no answer in time'**
  String get noAnswerInTime;

  /// Names an upload that was cut off by the network
  ///
  /// In en, this message translates to:
  /// **'Connection lost'**
  String get uploadConnectionLost;

  /// Says how much of an interrupted upload arrived
  ///
  /// In en, this message translates to:
  /// **'Of {total} photos, {onServer} are on the server; the remaining {remaining} can be sent again.'**
  String uploadInterruptedCounts(int total, int onServer, int remaining);

  /// Said when the user stopped an upload
  ///
  /// In en, this message translates to:
  /// **'The upload was cancelled.'**
  String get uploadCancelled;

  /// Said while the server is asked which photos it already holds
  ///
  /// In en, this message translates to:
  /// **'The server is being asked...'**
  String get uploadAsking;

  /// Said while the last photos are on their way and the answer is outstanding
  ///
  /// In en, this message translates to:
  /// **'Waiting for the server...'**
  String get uploadWaiting;

  /// Said while the photos are being hashed
  ///
  /// In en, this message translates to:
  /// **'Preparing: {done} of {total}...'**
  String uploadPreparing(int total, int done);

  /// Says how many images of an upload have arrived
  ///
  /// In en, this message translates to:
  /// **'{done} of {total} images'**
  String uploadImageCount(int total, int done);

  /// Says what an upload transferred and what the server already held
  ///
  /// In en, this message translates to:
  /// **'{stored} uploaded, {present} already present.'**
  String uploadSummary(int stored, int present);

  /// Names where the photos the server already held are kept
  ///
  /// In en, this message translates to:
  /// **'Already in the library: {where}.'**
  String alreadyInLibrary(String where);

  /// Said to a guest, who has no library of their own to sync into
  ///
  /// In en, this message translates to:
  /// **'Ask the admin to give you an album space.'**
  String get noticeGuestNoSpace;

  /// Says why the camera-roll sync cannot run
  ///
  /// In en, this message translates to:
  /// **'No album server is configured.'**
  String get noticeNoServerConfigured;

  /// Says why the camera-roll sync cannot run
  ///
  /// In en, this message translates to:
  /// **'Offline: the album server cannot be reached.'**
  String get noticeOffline;

  /// Says why the camera-roll sync could not run
  ///
  /// In en, this message translates to:
  /// **'The server cannot be reached ({problem}).'**
  String noticeServerUnreachable(String problem);

  /// Says why the camera-roll sync cannot run
  ///
  /// In en, this message translates to:
  /// **'The photo library cannot be read.'**
  String get noticePhotoLibraryUnreadable;

  /// Says why the camera-roll sync could not run
  ///
  /// In en, this message translates to:
  /// **'The photo library could not be read: {problem}'**
  String noticePhotoLibraryFailed(String problem);

  /// Says that the device refused access to its photos
  ///
  /// In en, this message translates to:
  /// **'Access to the photo library was denied. Allow photo access for VAlbum in the system settings, then try again.'**
  String get noticePhotoAccessDenied;

  /// Says that the device's photo library could not be opened
  ///
  /// In en, this message translates to:
  /// **'The photo library cannot be opened: {problem}'**
  String noticePhotoLibraryOpenFailed(String problem);

  /// Says that a photo is not stored on the device itself
  ///
  /// In en, this message translates to:
  /// **'The contents of {name} are not on this device yet (still in the cloud?).'**
  String noticeAlbumNotOnDevice(String name);

  /// Says that the platform refused the background task
  ///
  /// In en, this message translates to:
  /// **'Background sync could not be scheduled: {problem}'**
  String noticeBackgroundScheduleFailed(String problem);

  /// Says that the platform refused to drop the background task
  ///
  /// In en, this message translates to:
  /// **'Background sync could not be switched off: {problem}'**
  String noticeBackgroundUnscheduleFailed(String problem);

  /// Says why a Wi-Fi-only sync is not running
  ///
  /// In en, this message translates to:
  /// **'No network: the sync waits for a Wi-Fi connection.'**
  String get noticeNoNetwork;

  /// Says why a Wi-Fi-only sync is not running
  ///
  /// In en, this message translates to:
  /// **'No Wi-Fi: the sync is limited to Wi-Fi, and this device is on a mobile connection.'**
  String get noticeNoWifiMobile;

  /// Says why a Wi-Fi-only sync is not running
  ///
  /// In en, this message translates to:
  /// **'No Wi-Fi: the sync is limited to Wi-Fi, and this device is on another network.'**
  String get noticeNoWifiOther;

  /// Says that this platform runs no background sync
  ///
  /// In en, this message translates to:
  /// **'Background sync is not available on this platform; the camera roll syncs while the app is open.'**
  String get noticeNoBackgroundSyncHere;

  /// Says that a browser runs no background sync
  ///
  /// In en, this message translates to:
  /// **'Background sync is not available in a browser; the camera roll syncs while the app is open.'**
  String get noticeNoBackgroundSyncInBrowser;

  /// Says that this build runs no background sync
  ///
  /// In en, this message translates to:
  /// **'Background sync is not available in this app.'**
  String get noticeNoBackgroundSyncInApp;

  /// Says that a test drives the sync itself
  ///
  /// In en, this message translates to:
  /// **'No background sync in this test.'**
  String get noticeNoBackgroundSyncInTest;

  /// Says that this platform has no camera roll
  ///
  /// In en, this message translates to:
  /// **'No photo library on this platform - camera-roll sync runs on Android and iOS.'**
  String get noticeNoPhotoLibraryPlatform;

  /// Says that a browser has no camera roll
  ///
  /// In en, this message translates to:
  /// **'No photo library in a browser - camera-roll sync runs on Android and iOS.'**
  String get noticeNoPhotoLibraryBrowser;

  /// The status line of a sync that is switched off
  ///
  /// In en, this message translates to:
  /// **'Camera-roll sync is off.'**
  String get cameraRollOff;

  /// The status line of a running sync
  ///
  /// In en, this message translates to:
  /// **'Uploading {done} of {total}...'**
  String cameraRollUploading(int total, int done);

  /// Says when a postponed sync is tried again
  ///
  /// In en, this message translates to:
  /// **'Waiting until {time}.'**
  String cameraRollWaitingUntil(String time);

  /// Names an unknown time the sync is tried again at
  ///
  /// In en, this message translates to:
  /// **'the next attempt'**
  String get cameraRollNextAttempt;

  /// The status line of a failed sync that will be tried again
  ///
  /// In en, this message translates to:
  /// **'Failed: {reason} - retrying at {time}'**
  String cameraRollFailedRetrying(String reason, String time);

  /// The status line of a failed sync that will not be tried again
  ///
  /// In en, this message translates to:
  /// **'Failed: {reason}'**
  String cameraRollFailed(String reason);

  /// Stands where a failed sync said nothing about why
  ///
  /// In en, this message translates to:
  /// **'unknown reason'**
  String get cameraRollUnknownReason;

  /// The status line of a sync that has not run yet
  ///
  /// In en, this message translates to:
  /// **'Waiting for new photos.'**
  String get cameraRollWaitingForPhotos;

  /// The status line of a sync that found nothing to do
  ///
  /// In en, this message translates to:
  /// **'Nothing new, checked at {time}.'**
  String cameraRollNothingNew(String time);

  /// The status line of a sync that transferred something
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{Synced 1 photo at {time} ({stored} uploaded, {present} already there).} other{Synced {count} photos at {time} ({stored} uploaded, {present} already there).}}'**
  String cameraRollSynced(int stored, int count, String time, int present);

  /// Says why a sync transferred nothing while the server builds its index
  ///
  /// In en, this message translates to:
  /// **'The library is still being indexed ({done} of {total} folders); photos already in an unindexed album may be uploaded again.'**
  String cameraRollIndexing(int total, int done);

  /// Says that the sync fell back to the default inbox
  ///
  /// In en, this message translates to:
  /// **'The chosen inbox is gone; using \'{name}\'.'**
  String cameraRollInboxGone(String name);

  /// Said while the sync watches no album of the device
  ///
  /// In en, this message translates to:
  /// **'Choose the albums to sync.'**
  String get cameraRollNoSources;

  /// Says what ticking a further album of the device does
  ///
  /// In en, this message translates to:
  /// **'Photos of a newly chosen album are fetched from the beginning.'**
  String get cameraRollNewSource;

  /// Says how the last background sync ended
  ///
  /// In en, this message translates to:
  /// **'Last background sync at {time} failed: {problem}'**
  String backgroundLastRunFailed(String problem, String time);

  /// Says what the last background sync did
  ///
  /// In en, this message translates to:
  /// **'Last background sync at {time}: {stored} uploaded, {present} already present'**
  String backgroundLastRun(int stored, String time, int present);

  /// Says why a background run did nothing
  ///
  /// In en, this message translates to:
  /// **'The camera-roll sync is switched off.'**
  String get backgroundSyncOff;

  /// Says that a background run did nothing and said no reason
  ///
  /// In en, this message translates to:
  /// **'The sync did not run.'**
  String get backgroundSyncDidNotRun;

  /// Says that the background task itself failed
  ///
  /// In en, this message translates to:
  /// **'The background sync task failed: {problem}'**
  String backgroundTaskFailed(String problem);

  /// Refuses a change while the server cannot be reached
  ///
  /// In en, this message translates to:
  /// **'Offline: changes need the server. Retry when it is reachable again.'**
  String get offlineRefusal;

  /// The banner over a view that could not be fetched
  ///
  /// In en, this message translates to:
  /// **'Offline - the server cannot be reached.'**
  String get offlineNoServer;

  /// The banner over a view answered from the offline cache
  ///
  /// In en, this message translates to:
  /// **'Offline - showing the copy from {time}'**
  String offlineShowingCopy(String time);

  /// Says that an invitation link has been redeemed
  ///
  /// In en, this message translates to:
  /// **'This invitation was already used. If you accepted it on another device, sign in here with a device code from that device; otherwise ask for a new invitation.'**
  String get invitationUsedNotSignedIn;

  /// Says that an invitation link has been redeemed by this device
  ///
  /// In en, this message translates to:
  /// **'This invitation was already used — you are already signed in here.'**
  String get invitationUsedSignedIn;

  /// Says that an invitation link has been redeemed by this device
  ///
  /// In en, this message translates to:
  /// **'This invitation was already used — you are signed in here as {user}.'**
  String invitationUsedSignedInAs(String user);

  /// Says that an invitation link is too old
  ///
  /// In en, this message translates to:
  /// **'This invitation has expired. Ask for a new one.'**
  String get invitationExpiredNotice;

  /// Says that an invitation link was taken back
  ///
  /// In en, this message translates to:
  /// **'This invitation was withdrawn.'**
  String get invitationWithdrawnNotice;

  /// Says that this server does not know the invitation link
  ///
  /// In en, this message translates to:
  /// **'This is not an invitation of this server.'**
  String get invitationNotOfThisServer;

  /// Said when a view could not be fetched
  ///
  /// In en, this message translates to:
  /// **'Loading failed: {problem}'**
  String loadingFailed(String problem);

  /// Said when the server answered nothing at all
  ///
  /// In en, this message translates to:
  /// **'No data loaded'**
  String get noDataLoaded;

  /// Button asking the server once more
  ///
  /// In en, this message translates to:
  /// **'Try again'**
  String get tryAgain;

  /// Said when the address names an image the album does not hold
  ///
  /// In en, this message translates to:
  /// **'No such image: {name}'**
  String noSuchImage(String name);

  /// Said when the address names the alternatives of an image that is in no group
  ///
  /// In en, this message translates to:
  /// **'No alternatives for image: {name}'**
  String noAlternatives(String name);

  /// Said when an upload did not go through
  ///
  /// In en, this message translates to:
  /// **'Upload failed: {problem}'**
  String uploadFailed(String problem);

  /// Button of the offline banner, fetching the view again
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get retry;

  /// The album menu entry opening the face editor
  ///
  /// In en, this message translates to:
  /// **'Persons in this album'**
  String get personsMenuEntry;

  /// The title of the face editor page
  ///
  /// In en, this message translates to:
  /// **'Persons'**
  String get personsTitle;

  /// Banner telling a caller without the edit right that the editor only shows
  ///
  /// In en, this message translates to:
  /// **'Only editors may name faces'**
  String get personsReadOnlyNotice;

  /// Banner shown while the server is still detecting faces
  ///
  /// In en, this message translates to:
  /// **'Still looking for faces in this album…'**
  String get personsPendingNotice;

  /// Shown instead of an empty face editor
  ///
  /// In en, this message translates to:
  /// **'No face was found in this album.'**
  String get personsEmptyNotice;

  /// The heading of a group of faces nobody has named
  ///
  /// In en, this message translates to:
  /// **'Who is this?'**
  String get personsUnknownGroup;

  /// The heading of one of several groups of faces nobody has named
  ///
  /// In en, this message translates to:
  /// **'Who is this? (group {number})'**
  String personsUnknownGroupNumbered(int number);

  /// The heading of the group of boxes that show no face at all
  ///
  /// In en, this message translates to:
  /// **'Not a face'**
  String get personsNotAFaceGroup;

  /// The target faces are dropped onto to make a group of their own
  ///
  /// In en, this message translates to:
  /// **'New group'**
  String get personsNewGroup;

  /// How many faces stand in a group
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 face} other{{count} faces}}'**
  String personsFaceCount(int count);

  /// How many faces are selected, shown in the app bar
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 face selected} other{{count} faces selected}}'**
  String personsSelectedCount(int count);

  /// The heading of a group of faces the server believes to be somebody, unconfirmed
  ///
  /// In en, this message translates to:
  /// **'Is this {name}?'**
  String personsSuggestedHeading(String name);

  /// The button accepting what the server suggested about a group of faces
  ///
  /// In en, this message translates to:
  /// **'Confirm'**
  String get personsConfirmSuggestion;

  /// The title of the dialog picking the person a group of faces is
  ///
  /// In en, this message translates to:
  /// **'Name these faces'**
  String get personsChooseTitle;

  /// The label of the field narrowing a list of people down
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get personsSearchLabel;

  /// The entry of the person chooser adding somebody nobody has named yet
  ///
  /// In en, this message translates to:
  /// **'New person…'**
  String get personsNewPersonEntry;

  /// The title of the dialog asking the name of a new person
  ///
  /// In en, this message translates to:
  /// **'New person'**
  String get personsNewPersonTitle;

  /// The label of the field a person's name is typed into
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get personsNameLabel;

  /// Shown in the person chooser while the register of the space is empty
  ///
  /// In en, this message translates to:
  /// **'Nobody is named in this space yet.'**
  String get personsNobodyYet;

  /// The menu entry renaming a person
  ///
  /// In en, this message translates to:
  /// **'Rename…'**
  String get personsRenameEntry;

  /// The title of the dialog renaming a person
  ///
  /// In en, this message translates to:
  /// **'Rename person'**
  String get personsRenameTitle;

  /// Warning that a rename reaches every album of the space
  ///
  /// In en, this message translates to:
  /// **'Renames this person everywhere.'**
  String get personsRenameNotice;

  /// The menu entry merging a person into another one
  ///
  /// In en, this message translates to:
  /// **'Merge into…'**
  String get personsMergeEntry;

  /// The title of the dialog merging a person into another one
  ///
  /// In en, this message translates to:
  /// **'Merge into another person'**
  String get personsMergeTitle;

  /// What a merge does, said before it is done
  ///
  /// In en, this message translates to:
  /// **'The faces of this person become the other person\'s. Nothing is deleted.'**
  String get personsMergeNotice;

  /// The title of the question asked when the face editor is cancelled with unsaved changes
  ///
  /// In en, this message translates to:
  /// **'Discard the changes to this album\'s persons?'**
  String get personsDiscardTitle;

  /// What discarding the unsaved decisions of the face editor does
  ///
  /// In en, this message translates to:
  /// **'The decisions made here have not been saved. Discarding them shows the faces again as the server has them.'**
  String get personsDiscardMessage;

  /// The title of the question asked when the face editor is left with unsaved changes
  ///
  /// In en, this message translates to:
  /// **'Save the changes to this album\'s persons?'**
  String get personsSaveTitle;

  /// What leaving the face editor with unsaved decisions does
  ///
  /// In en, this message translates to:
  /// **'Leaving this page ends the editing. Unsaved decisions are lost unless they are saved now.'**
  String get personsSaveMessage;

  /// The tooltip of the handle a face is picked up by
  ///
  /// In en, this message translates to:
  /// **'Drag onto a group'**
  String get personsDragToGroup;

  /// The tooltip of the gesture showing the whole photograph a face was found in
  ///
  /// In en, this message translates to:
  /// **'Show the photo'**
  String get personsShowPhoto;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['de', 'en'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'de':
      return AppLocalizationsDe();
    case 'en':
      return AppLocalizationsEn();
  }

  throw FlutterError(
      'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
      'an issue with the localizations generation tool. Please file an issue '
      'on GitHub with a reproducible sample app and the gen-l10n configuration '
      'that was used.');
}
