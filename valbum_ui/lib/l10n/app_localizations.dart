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
