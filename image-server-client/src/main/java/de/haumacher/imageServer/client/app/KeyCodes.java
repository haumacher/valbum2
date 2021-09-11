/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.client.app;

/**
 * Constants from https://www.w3.org/TR/uievents-key/#key-attribute-value
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public interface KeyCodes {

	// 3.2. Modifier Keys
	// key attribute value Typical Usage (Non-normative)
	/** The Alt (Alternative) key. */
	String Alt = "Alt";

	// This key enables the alternate modifier function for interpreting
	// concurrent or subsequent keyboard input.
	// This key value is also used for the Apple Option key.
	/**
	 * The Alternate Graphics (AltGr or AltGraph) key. This key is used enable
	 * the ISO Level 3 shift modifier (the standard Shift key is the level 2
	 * modifier). See [ISO9995-1].
	 */
	String AltGraph = "AltGraph";

	/**
	 * The Caps Lock (Capital) key. Toggle capital character lock function for
	 * interpreting subsequent keyboard input event.
	 */
	String CapsLock = "CapsLock";

	/**
	 * The Control or Ctrl key, to enable control modifier function for
	 * interpreting concurrent or subsequent keyboard input.
	 */
	String Control = "Control";

	/** The Function switch Fn key. */
	String Fn = "Fn";

	// Activating this key simultaneously with another key changes that key’s
	// value to an alternate character or function. This key is often handled
	// directly in the keyboard hardware and does not usually generate key
	// events.
	/**
	 * The Function-Lock (FnLock or F-Lock) key. Activating this key switches
	 * the mode of the keyboard to changes some keys' values to an alternate
	 * character or function. This key is often handled directly in the keyboard
	 * hardware and does not usually generate key events.
	 */
	String FnLock = "FnLock";

	/**
	 * The Meta key, to enable meta modifier function for interpreting
	 * concurrent or subsequent keyboard input. This key value is used for the
	 * "Windows Logo" key and the Apple Command or ⌘ key.
	 */
	String Meta = "Meta";

	/**
	 * The NumLock or Number Lock key, to toggle numpad mode function for
	 * interpreting subsequent keyboard input.
	 */
	String NumLock = "NumLock";

	/**
	 * The Scroll Lock key, to toggle between scrolling and cursor movement
	 * modes.
	 */
	String ScrollLock = "ScrollLock";

	/**
	 * The Shift key, to enable shift modifier function for interpreting
	 * concurrent or subsequent keyboard input.
	 */
	String Shift = "Shift";

	/** The Symbol modifier key (used on some virtual keyboards). */
	String Symbol = "Symbol";

	/** The Symbol Lock key. */
	String SymbolLock = "SymbolLock";

	// Legacy modifier keys:
	// key attribute value Typical Usage (Non-normative)
	/** The Hyper key. */
	String Hyper = "Hyper";

	/** The Super key. */
	String Super = "Super";

	// 3.3. Whitespace Keys
	// key attribute value Typical Usage (Non-normative)
	/**
	 * The Enter or ↵ key, to activate current selection or accept current
	 * input.
	 */
	String Enter = "Enter";

	// This key value is also used for the Return (Macintosh numpad) key.
	// This key value is also used for the Android KEYCODE_DPAD_CENTER.
	/** The Horizontal Tabulation Tab key. */
	String Tab = "Tab";

	// The space or spacebar key is encoded as " ".
	// 3.4. Navigation Keys
	// key attribute value Typical Usage (Non-normative)
	/**
	 * The down arrow key, to navigate or traverse downward. (KEYCODE_DPAD_DOWN)
	 */
	String ArrowDown = "ArrowDown";

	/**
	 * The left arrow key, to navigate or traverse leftward. (KEYCODE_DPAD_LEFT)
	 */
	String ArrowLeft = "ArrowLeft";

	/**
	 * The right arrow key, to navigate or traverse rightward.
	 * (KEYCODE_DPAD_RIGHT)
	 */
	String ArrowRight = "ArrowRight";

	/** The up arrow key, to navigate or traverse upward. (KEYCODE_DPAD_UP) */
	String ArrowUp = "ArrowUp";

	/**
	 * The End key, used with keyboard entry to go to the end of content
	 * (KEYCODE_MOVE_END).
	 */
	String End = "End";

	/**
	 * The Home key, used with keyboard entry, to go to start of content
	 * (KEYCODE_MOVE_HOME).
	 */
	String Home = "Home";

	// For the mobile phone Home key (which goes to the phone’s main screen),
	// use "GoHome".
	/** The Page Down key, to scroll down or display next page of content. */
	String PageDown = "PageDown";

	/** The Page Up key, to scroll up or display previous page of content. */
	String PageUp = "PageUp";

	// 3.5. Editing Keys
	// key attribute value Typical Usage (Non-normative)
	/**
	 * The Backspace key. This key value is also used for the key labeled Delete
	 * on MacOS keyboards.
	 */
	String Backspace = "Backspace";

	/** Remove the currently selected input. */
	String Clear = "Clear";

	/** Copy the current selection. (APPCOMMAND_COPY) */
	String Copy = "Copy";

	/** The Cursor Select (Crsel) key. */
	String CrSel = "CrSel";

	/** Cut the current selection. (APPCOMMAND_CUT) */
	String Cut = "Cut";

	/**
	 * The Delete (Del) Key. This key value is also used for the key labeled
	 * Delete on MacOS keyboards when modified by the Fn key.
	 */
	String Delete = "Delete";

	/**
	 * The Erase to End of Field key. This key deletes all characters from the
	 * current cursor position to the end of the current field.
	 */
	String EraseEof = "EraseEof";

	/** The Extend Selection (Exsel) key. */
	String ExSel = "ExSel";

	/**
	 * The Insert (Ins) key, to toggle between text modes for insertion or
	 * overtyping. (KEYCODE_INSERT)
	 */
	String Insert = "Insert";

	/** The Paste key. (APPCOMMAND_PASTE) */
	String Paste = "Paste";

	/** Redo the last action. (APPCOMMAND_REDO) */
	String Redo = "Redo";

	/** Undo the last action. (APPCOMMAND_UNDO) */
	String Undo = "Undo";

	// 3.6. UI Keys
	// key attribute value Typical Usage (Non-normative)
	/**
	 * The Accept (Commit, OK) key. Accept current option or input method
	 * sequence conversion.
	 */
	String Accept = "Accept";

	/** The Again key, to redo or repeat an action. */
	String Again = "Again";

	/** The Attention (Attn) key. */
	String Attn = "Attn";

	/** The Cancel key. */
	String Cancel = "Cancel";

	/**
	 * Show the application’s context menu. This key is commonly found between
	 * the right Meta key and the right Control key.
	 */
	String ContextMenu = "ContextMenu";

	/**
	 * The Esc key. This key was originally used to initiate an escape sequence,
	 * but is now more generally used to exit or "escape" the current context,
	 * such as closing a dialog or exiting full screen mode.
	 */
	String Escape = "Escape";

	/** The Execute key. */
	String Execute = "Execute";

	/** Open the Find dialog. (APPCOMMAND_FIND) */
	String Find = "Find";

	/**
	 * Open a help dialog or toggle display of help information.
	 * (APPCOMMAND_HELP, KEYCODE_HELP)
	 */
	String Help = "Help";

	/** Pause the current state or application (as appropriate). */
	String Pause = "Pause";

	// Do not use this value for the Pause button on media controllers. Use
	// "MediaPause" instead.
	/** Play or resume the current state or application (as appropriate). */
	String Play = "Play";

	// Do not use this value for the Play button on media controllers. Use
	// "MediaPlay" instead.
	/** The properties (Props) key. */
	String Props = "Props";

	/** The Select key. */
	String Select = "Select";

	/** The ZoomIn key. (KEYCODE_ZOOM_IN) */
	String ZoomIn = "ZoomIn";

	/** The ZoomOut key. (KEYCODE_ZOOM_OUT) */
	String ZoomOut = "ZoomOut";

	// 3.7. Device Keys
	// key attribute value Typical Usage (Non-normative)
	/**
	 * The Brightness Down key. Typically controls the display brightness.
	 * (KEYCODE_BRIGHTNESS_DOWN)
	 */
	String BrightnessDown = "BrightnessDown";

	/**
	 * The Brightness Up key. Typically controls the display brightness.
	 * (KEYCODE_BRIGHTNESS_UP)
	 */
	String BrightnessUp = "BrightnessUp";

	/**
	 * Toggle removable media to eject (open) and insert (close) state.
	 * (KEYCODE_MEDIA_EJECT)
	 */
	String Eject = "Eject";

	/** The LogOff key. */
	String LogOff = "LogOff";

	/** Toggle power state. (KEYCODE_POWER) */
	String Power = "Power";

	// Note: Some devices might not expose this key to the operating
	// environment.
	/** The PowerOff key. Sometime called PowerDown. */
	String PowerOff = "PowerOff";

	/** The Print Screen or SnapShot key, to initiate print-screen function. */
	String PrintScreen = "PrintScreen";

	/**
	 * The Hibernate key. This key saves the current state of the computer to
	 * disk so that it can be restored. The computer will then shutdown.
	 */
	String Hibernate = "Hibernate";

	/**
	 * The Standby key. This key turns off the display and places the computer
	 * into a low-power mode without completely shutting down. It is sometimes
	 * labelled Suspend or Sleep key. (KEYCODE_SLEEP)
	 */
	String Standby = "Standby";

	/** The WakeUp key. (KEYCODE_WAKEUP) */
	String WakeUp = "WakeUp";

	// 3.8. IME and Composition Keys
	// key attribute value Typical Usage (Non-normative)
	/** The All Candidates key, to initate the multi-candidate mode. */
	String AllCandidates = "AllCandidates";

	/** The Alphanumeric key. */
	String Alphanumeric = "Alphanumeric";

	/**
	 * The Code Input key, to initiate the Code Input mode to allow characters
	 * to be entered by their code points.
	 */
	String CodeInput = "CodeInput";

	/**
	 * The Compose key, also known as Multi_key on the X Window System. This key
	 * acts in a manner similar to a dead key, triggering a mode where
	 * subsequent key presses are combined to produce a different character.
	 */
	String Compose = "Compose";

	/** The Convert key, to convert the current input method sequence. */
	String Convert = "Convert";

	/**
	 * A dead key combining key. It may be any combining key from any keyboard
	 * layout. For example, on a PC/AT French keyboard, using a French mapping
	 * and without any modifier activiated, this is the key value U+0302
	 * COMBINING CIRCUMFLEX ACCENT. In another layout this might be a different
	 * unicode combining key.
	 */
	String Dead = "Dead";

	// For applications that need to differentiate between specific combining
	// characters, the associated compositionupdate event’s data attribute
	// provides the specific key value.
	/**
	 * The Final Mode Final key used on some Asian keyboards, to enable the
	 * final mode for IMEs.
	 */
	String FinalMode = "FinalMode";

	/** Switch to the first character group. (ISO/IEC 9995) */
	String GroupFirst = "GroupFirst";

	/** Switch to the last character group. (ISO/IEC 9995) */
	String GroupLast = "GroupLast";

	/** Switch to the next character group. (ISO/IEC 9995) */
	String GroupNext = "GroupNext";

	/** Switch to the previous character group. (ISO/IEC 9995) */
	String GroupPrevious = "GroupPrevious";

	/**
	 * The Mode Change key, to toggle between or cycle through input modes of
	 * IMEs.
	 */
	String ModeChange = "ModeChange";

	/** The Next Candidate function key. */
	String NextCandidate = "NextCandidate";

	/**
	 * The NonConvert ("Don’t Convert") key, to accept current input method
	 * sequence without conversion in IMEs.
	 */
	String NonConvert = "NonConvert";

	/** The Previous Candidate function key. */
	String PreviousCandidate = "PreviousCandidate";

	/** The Process key. */
	String Process = "Process";

	/** The Single Candidate function key. */
	String SingleCandidate = "SingleCandidate";

	// Keys specific to Korean keyboards:
	// key attribute value Typical Usage (Non-normative)
	/**
	 * The Hangul (Korean characters) Mode key, to toggle between Hangul and
	 * English modes.
	 */
	String HangulMode = "HangulMode";

	/** The Hanja (Korean characters) Mode key. */
	String HanjaMode = "HanjaMode";

	/** The Junja (Korean characters) Mode key. */
	String JunjaMode = "JunjaMode";

	// Keys specific to Japanese keyboards:
	// key attribute value Typical Usage (Non-normative)
	/**
	 * The Eisu key. This key may close the IME, but its purpose is defined by
	 * the current IME. (KEYCODE_EISU)
	 */
	String Eisu = "Eisu";

	/** The (Half-Width) Characters key. */
	String Hankaku = "Hankaku";

	/** The Hiragana (Japanese Kana characters) key. */
	String Hiragana = "Hiragana";

	/** The Hiragana/Katakana toggle key. (KEYCODE_KATAKANA_HIRAGANA) */
	String HiraganaKatakana = "HiraganaKatakana";

	/**
	 * The Kana Mode (Kana Lock) key. This key is used to enter hiragana mode
	 * (typically from romaji mode).
	 */
	String KanaMode = "KanaMode";

	/**
	 * The Kanji (Japanese name for ideographic characters of Chinese origin)
	 * Mode key. This key is typically used to switch to a hiragana keyboard for
	 * the purpose of converting input into kanji. (KEYCODE_KANA)
	 */
	String KanjiMode = "KanjiMode";

	/** The Katakana (Japanese Kana characters) key. */
	String Katakana = "Katakana";

	/** The Roman characters function key. */
	String Romaji = "Romaji";

	/** The Zenkaku (Full-Width) Characters key. */
	String Zenkaku = "Zenkaku";

	/**
	 * The Zenkaku/Hankaku (full-width/half-width) toggle key.
	 * (KEYCODE_ZENKAKU_HANKAKU)
	 */
	String ZenkakuHankaku = "ZenkakuHankaku";
	// 3.9. General-Purpose Function Keys

	// The exact number of these general purpose function keys varies on
	// different platforms, and only the first few are defined explicitly here.
	// Additional function key names are implicitly defined by incrementing the
	// base-10 index at the end of the function key name. Thus, "F24" and
	// "Soft8" are all valid key values.
	// key attribute value Typical Usage (Non-normative)
	/** The F1 key, a general purpose function key, as index 1. */
	String F1 = "F1";

	/** The F2 key, a general purpose function key, as index 2. */
	String F2 = "F2";

	/** The F3 key, a general purpose function key, as index 3. */
	String F3 = "F3";

	/** The F4 key, a general purpose function key, as index 4. */
	String F4 = "F4";

	/** The F5 key, a general purpose function key, as index 5. */
	String F5 = "F5";

	/** The F6 key, a general purpose function key, as index 6. */
	String F6 = "F6";

	/** The F7 key, a general purpose function key, as index 7. */
	String F7 = "F7";

	/** The F8 key, a general purpose function key, as index 8. */
	String F8 = "F8";

	/** The F9 key, a general purpose function key, as index 9. */
	String F9 = "F9";

	/** The F10 key, a general purpose function key, as index 10. */
	String F10 = "F10";

	/** The F11 key, a general purpose function key, as index 11. */
	String F11 = "F11";

	/** The F12 key, a general purpose function key, as index 12. */
	String F12 = "F12";

	/** General purpose virtual function key, as index 1. */
	String Soft1 = "Soft1";

	/** General purpose virtual function key, as index 2. */
	String Soft2 = "Soft2";

	/** General purpose virtual function key, as index 3. */
	String Soft3 = "Soft3";

	/** General purpose virtual function key, as index 4. */
	String Soft4 = "Soft4";
	// 3.10. Multimedia Keys

	// These are extra keys found on "multimedia" keyboards.
	// key attribute value Typical Usage (Non-normative)
	/**
	 * Select next (numerically or logically) lower channel.
	 * (APPCOMMAND_MEDIA_CHANNEL_DOWN, KEYCODE_CHANNEL_DOWN)
	 */
	String ChannelDown = "ChannelDown";

	/**
	 * Select next (numerically or logically) higher channel.
	 * (APPCOMMAND_MEDIA_CHANNEL_UP, KEYCODE_CHANNEL_UP)
	 */
	String ChannelUp = "ChannelUp";

	/**
	 * Close the current document or message (Note: This doesn’t close the
	 * application). (APPCOMMAND_CLOSE)
	 */
	String Close = "Close";

	/**
	 * Open an editor to forward the current message. (APPCOMMAND_FORWARD_MAIL)
	 */
	String MailForward = "MailForward";

	/**
	 * Open an editor to reply to the current message.
	 * (APPCOMMAND_REPLY_TO_MAIL)
	 */
	String MailReply = "MailReply";

	/** Send the current message. (APPCOMMAND_SEND_MAIL) */
	String MailSend = "MailSend";

	/**
	 * Close the current media, for example to close a CD or DVD tray.
	 * (KEYCODE_MEDIA_CLOSE)
	 */
	String MediaClose = "MediaClose";

	/**
	 * Initiate or continue forward playback at faster than normal speed, or
	 * increase speed if already fast forwarding.
	 * (APPCOMMAND_MEDIA_FAST_FORWARD, KEYCODE_MEDIA_FAST_FORWARD)
	 */
	String MediaFastForward = "MediaFastForward";

	/**
	 * Pause the currently playing media. (APPCOMMAND_MEDIA_PAUSE,
	 * KEYCODE_MEDIA_PAUSE)
	 */
	String MediaPause = "MediaPause";

	// Media controller devices should use this value rather than "Pause" for
	// their pause keys.
	/**
	 * Initiate or continue media playback at normal speed, if not currently
	 * playing at normal speed. (APPCOMMAND_MEDIA_PLAY, KEYCODE_MEDIA_PLAY)
	 */
	String MediaPlay = "MediaPlay";

	/**
	 * Toggle media between play and pause states. (APPCOMMAND_MEDIA_PLAY_PAUSE,
	 * KEYCODE_MEDIA_PLAY_PAUSE)
	 */
	String MediaPlayPause = "MediaPlayPause";

	/**
	 * Initiate or resume recording of currently selected media.
	 * (APPCOMMAND_MEDIA_RECORD, KEYCODE_MEDIA_RECORD)
	 */
	String MediaRecord = "MediaRecord";

	/**
	 * Initiate or continue reverse playback at faster than normal speed, or
	 * increase speed if already rewinding. (APPCOMMAND_MEDIA_REWIND,
	 * KEYCODE_MEDIA_REWIND)
	 */
	String MediaRewind = "MediaRewind";

	/**
	 * Stop media playing, pausing, forwarding, rewinding, or recording, if not
	 * already stopped. (APPCOMMAND_MEDIA_STOP, KEYCODE_MEDIA_STOP)
	 */
	String MediaStop = "MediaStop";

	/**
	 * Seek to next media or program track. (APPCOMMAND_MEDIA_NEXTTRACK,
	 * KEYCODE_MEDIA_NEXT)
	 */
	String MediaTrackNext = "MediaTrackNext";

	/**
	 * Seek to previous media or program track. (APPCOMMAND_MEDIA_PREVIOUSTRACK,
	 * KEYCODE_MEDIA_PREVIOUS)
	 */
	String MediaTrackPrevious = "MediaTrackPrevious";

	/** Open a new document or message. (APPCOMMAND_NEW) */
	String New = "New";

	/** Open an existing document or message. (APPCOMMAND_OPEN) */
	String Open = "Open";

	/** Print the current document or message. (APPCOMMAND_PRINT) */
	String Print = "Print";

	/** Save the current document or message. (APPCOMMAND_SAVE) */
	String Save = "Save";

	/**
	 * Spellcheck the current document or selection. (APPCOMMAND_SPELL_CHECK)
	 */
	String SpellCheck = "SpellCheck";
	// 3.11. Multimedia Numpad Keys

	// The normal 0 ... 9 numpad keys are encoded as "0" ... "9", but some
	// multimedia keypads have buttons numbered from 1 ... 12. In these
	// instances, the 10 key is often labeled 10 /0.

	// Note: The 10 or 10 /0 key MUST be assigned a key value of "0".
	// key attribute value Typical Usage (Non-normative)
	/** The 11 key found on media numpads that have buttons from 1 ... 12. */
	String Key11 = "Key11";

	/** The 12 key found on media numpads that have buttons from 1 ... 12. */
	String Key12 = "Key12";
	// 3.12. Audio Keys

	// Multimedia keys related to audio.
	// key attribute value Typical Usage (Non-normative)
	/** Adjust audio balance leftward. (VK_AUDIO_BALANCE_LEFT) */
	String AudioBalanceLeft = "AudioBalanceLeft";

	/** Adjust audio balance rightward. (VK_AUDIO_BALANCE_RIGHT) */
	String AudioBalanceRight = "AudioBalanceRight";

	/**
	 * Decrease audio bass boost or cycle down through bass boost states.
	 * (APPCOMMAND_BASS_DOWN, VK_BASS_BOOST_DOWN)
	 */
	String AudioBassBoostDown = "AudioBassBoostDown";

	/** Toggle bass boost on/off. (APPCOMMAND_BASS_BOOST) */
	String AudioBassBoostToggle = "AudioBassBoostToggle";

	/**
	 * Increase audio bass boost or cycle up through bass boost states.
	 * (APPCOMMAND_BASS_UP, VK_BASS_BOOST_UP)
	 */
	String AudioBassBoostUp = "AudioBassBoostUp";

	/** Adjust audio fader towards front. (VK_FADER_FRONT) */
	String AudioFaderFront = "AudioFaderFront";

	/** Adjust audio fader towards rear. (VK_FADER_REAR) */
	String AudioFaderRear = "AudioFaderRear";

	/**
	 * Advance surround audio mode to next available mode.
	 * (VK_SURROUND_MODE_NEXT)
	 */
	String AudioSurroundModeNext = "AudioSurroundModeNext";

	/** Decrease treble. (APPCOMMAND_TREBLE_DOWN) */
	String AudioTrebleDown = "AudioTrebleDown";

	/** Increase treble. (APPCOMMAND_TREBLE_UP) */
	String AudioTrebleUp = "AudioTrebleUp";

	/** Decrease audio volume. (APPCOMMAND_VOLUME_DOWN, KEYCODE_VOLUME_DOWN) */
	String AudioVolumeDown = "AudioVolumeDown";

	/** Increase audio volume. (APPCOMMAND_VOLUME_UP, KEYCODE_VOLUME_UP) */
	String AudioVolumeUp = "AudioVolumeUp";

	/**
	 * Toggle between muted state and prior volume level.
	 * (APPCOMMAND_VOLUME_MUTE, KEYCODE_VOLUME_MUTE)
	 */
	String AudioVolumeMute = "AudioVolumeMute";

	/** Toggle the microphone on/off. (APPCOMMAND_MIC_ON_OFF_TOGGLE) */
	String MicrophoneToggle = "MicrophoneToggle";

	/** Decrease microphone volume. (APPCOMMAND_MICROPHONE_VOLUME_DOWN) */
	String MicrophoneVolumeDown = "MicrophoneVolumeDown";

	/** Increase microphone volume. (APPCOMMAND_MICROPHONE_VOLUME_UP) */
	String MicrophoneVolumeUp = "MicrophoneVolumeUp";

	/**
	 * Mute the microphone. (APPCOMMAND_MICROPHONE_VOLUME_MUTE, KEYCODE_MUTE)
	 */
	String MicrophoneVolumeMute = "MicrophoneVolumeMute";
	// 3.13. Speech Keys

	// Multimedia keys related to speech recognition.
	// key attribute value Typical Usage (Non-normative)
	/**
	 * Show correction list when a word is incorrectly identified.
	 * (APPCOMMAND_CORRECTION_LIST)
	 */
	String SpeechCorrectionList = "SpeechCorrectionList";

	/**
	 * Toggle between dictation mode and command/control mode.
	 * (APPCOMMAND_DICTATE_OR_COMMAND_CONTROL_TOGGLE)
	 */
	String SpeechInputToggle = "SpeechInputToggle";
	// 3.14. Application Keys

	// The Application Keys are special keys that are assigned to launch a
	// particular application. Additional application key names can be defined
	// by concatenating "Launch" with the name of the application.
	// key attribute value Typical Usage (Non-normative)
	/**
	 * The first generic "LaunchApplication" key. This is commonly associated
	 * with launching "My Computer", and may have a computer symbol on the key.
	 * (APPCOMMAND_LAUNCH_APP1)
	 */
	String LaunchApplication1 = "LaunchApplication1";

	/**
	 * The second generic "LaunchApplication" key. This is commonly associated
	 * with launching "Calculator", and may have a calculator symbol on the key.
	 * (APPCOMMAND_LAUNCH_APP2, KEYCODE_CALCULATOR)
	 */
	String LaunchApplication2 = "LaunchApplication2";

	/** The "Calendar" key. (KEYCODE_CALENDAR) */
	String LaunchCalendar = "LaunchCalendar";

	/** The "Contacts" key. (KEYCODE_CONTACTS) */
	String LaunchContacts = "LaunchContacts";

	/** The "Mail" key. (APPCOMMAND_LAUNCH_MAIL) */
	String LaunchMail = "LaunchMail";

	/** The "Media Player" key. (APPCOMMAND_LAUNCH_MEDIA_SELECT) */
	String LaunchMediaPlayer = "LaunchMediaPlayer";

	/** The "Music Player" key. */
	String LaunchMusicPlayer = "LaunchMusicPlayer";

	/** The "Phone" key. */
	String LaunchPhone = "LaunchPhone";

	/** The "Screen Saver" key. */
	String LaunchScreenSaver = "LaunchScreenSaver";

	/** The "Spreadsheet" key. */
	String LaunchSpreadsheet = "LaunchSpreadsheet";

	/** The "Web Browser" key. */
	String LaunchWebBrowser = "LaunchWebBrowser";

	/** The "WebCam" key. */
	String LaunchWebCam = "LaunchWebCam";

	/** The "Word Processor" key. */
	String LaunchWordProcessor = "LaunchWordProcessor";

	// 3.15. Browser Keys
	// key attribute value Typical Usage (Non-normative)
	/**
	 * Navigate to previous content or page in current history.
	 * (APPCOMMAND_BROWSER_BACKWARD)
	 */
	String BrowserBack = "BrowserBack";

	/** Open the list of browser favorites. (APPCOMMAND_BROWSER_FAVORITES) */
	String BrowserFavorites = "BrowserFavorites";

	/**
	 * Navigate to next content or page in current history.
	 * (APPCOMMAND_BROWSER_FORWARD)
	 */
	String BrowserForward = "BrowserForward";

	/** Go to the user’s preferred home page. (APPCOMMAND_BROWSER_HOME) */
	String BrowserHome = "BrowserHome";

	/** Refresh the current page or content. (APPCOMMAND_BROWSER_REFRESH) */
	String BrowserRefresh = "BrowserRefresh";

	/** Call up the user’s preferred search page. (APPCOMMAND_BROWSER_SEARCH) */
	String BrowserSearch = "BrowserSearch";

	/** Stop loading the current page or content. (APPCOMMAND_BROWSER_STOP) */
	String BrowserStop = "BrowserStop";

	// 3.16. Mobile Phone Keys
	// key attribute value Typical Usage (Non-normative)
	/**
	 * The Application switch key, which provides a list of recent apps to
	 * switch between. (KEYCODE_APP_SWITCH)
	 */
	String AppSwitch = "AppSwitch";

	/** The Call key. (KEYCODE_CALL) */
	String Call = "Call";

	/** The Camera key. (KEYCODE_CAMERA) */
	String Camera = "Camera";

	/** The Camera focus key. (KEYCODE_FOCUS) */
	String CameraFocus = "CameraFocus";

	/** The End Call key. (KEYCODE_ENDCALL) */
	String EndCall = "EndCall";

	/** The Back key. (KEYCODE_BACK) */
	String GoBack = "GoBack";

	/** The Home key, which goes to the phone’s main screen. (KEYCODE_HOME) */
	String GoHome = "GoHome";

	/** The Headset Hook key. (KEYCODE_HEADSETHOOK) */
	String HeadsetHook = "HeadsetHook";

	/** The Last Number Redial key. */
	String LastNumberRedial = "LastNumberRedial";

	/** The Notification key. (KEYCODE_NOTIFICATION) */
	String Notification = "Notification";

	/**
	 * Toggle between manner mode state: silent, vibrate, ring, ...
	 * (KEYCODE_MANNER_MODE)
	 */
	String MannerMode = "MannerMode";

	/** The Voice Dial key. */
	String VoiceDial = "VoiceDial";

	// 3.17. TV Keys
	// key attribute value Typical Usage (Non-normative)
	/** Switch to viewing TV. (KEYCODE_TV) */
	String TV = "TV";

	/** TV 3D Mode. (KEYCODE_3D_MODE) */
	String TV3DMode = "TV3DMode";

	/** Toggle between antenna and cable input. (KEYCODE_TV_ANTENNA_CABLE) */
	String TVAntennaCable = "TVAntennaCable";

	/** Audio description. (KEYCODE_TV_AUDIO_DESCRIPTION) */
	String TVAudioDescription = "TVAudioDescription";

	/**
	 * Audio description mixing volume down.
	 * (KEYCODE_TV_AUDIO_DESCRIPTION_MIX_DOWN)
	 */
	String TVAudioDescriptionMixDown = "TVAudioDescriptionMixDown";

	/**
	 * Audio description mixing volume up. (KEYCODE_TV_AUDIO_DESCRIPTION_MIX_UP)
	 */
	String TVAudioDescriptionMixUp = "TVAudioDescriptionMixUp";

	/** Contents menu. (KEYCODE_TV_CONTENTS_MENU) */
	String TVContentsMenu = "TVContentsMenu";

	/** Contents menu. (KEYCODE_TV_DATA_SERVICE) */
	String TVDataService = "TVDataService";

	/** Switch the input mode on an external TV. (KEYCODE_TV_INPUT) */
	String TVInput = "TVInput";

	/** Switch to component input #1. (KEYCODE_TV_INPUT_COMPONENT_1) */
	String TVInputComponent1 = "TVInputComponent1";

	/** Switch to component input #2. (KEYCODE_TV_INPUT_COMPONENT_2) */
	String TVInputComponent2 = "TVInputComponent2";

	/** Switch to composite input #1. (KEYCODE_TV_INPUT_COMPOSITE_1) */
	String TVInputComposite1 = "TVInputComposite1";

	/** Switch to composite input #2. (KEYCODE_TV_INPUT_COMPOSITE_2) */
	String TVInputComposite2 = "TVInputComposite2";

	/** Switch to HDMI input #1. (KEYCODE_TV_INPUT_HDMI_1) */
	String TVInputHDMI1 = "TVInputHDMI1";

	/** Switch to HDMI input #2. (KEYCODE_TV_INPUT_HDMI_2) */
	String TVInputHDMI2 = "TVInputHDMI2";

	/** Switch to HDMI input #3. (KEYCODE_TV_INPUT_HDMI_3) */
	String TVInputHDMI3 = "TVInputHDMI3";

	/** Switch to HDMI input #4. (KEYCODE_TV_INPUT_HDMI_4) */
	String TVInputHDMI4 = "TVInputHDMI4";

	/** Switch to VGA input #1. (KEYCODE_TV_INPUT_VGA_1) */
	String TVInputVGA1 = "TVInputVGA1";

	/** Media context menu. (KEYCODE_TV_MEDIA_CONTEXT_MENU) */
	String TVMediaContext = "TVMediaContext";

	/** Toggle network. (KEYCODE_TV_NETWORK) */
	String TVNetwork = "TVNetwork";

	/** Number entry. (KEYCODE_TV_NUMBER_ENTRY) */
	String TVNumberEntry = "TVNumberEntry";

	/** Toggle the power on an external TV. (KEYCODE_TV_POWER) */
	String TVPower = "TVPower";

	/** Radio. (KEYCODE_TV_RADIO_SERVICE) */
	String TVRadioService = "TVRadioService";

	/** Satellite. (KEYCODE_TV_SATELLITE) */
	String TVSatellite = "TVSatellite";

	/** Broadcast Satellite. (KEYCODE_TV_SATELLITE_BS) */
	String TVSatelliteBS = "TVSatelliteBS";

	/** Communication Satellite. (KEYCODE_TV_SATELLITE_CS) */
	String TVSatelliteCS = "TVSatelliteCS";

	/** Toggle between available satellites. (KEYCODE_TV_SATELLITE_SERVICE) */
	String TVSatelliteToggle = "TVSatelliteToggle";

	/** Analog Terrestrial. (KEYCODE_TV_TERRESTRIAL_ANALOG) */
	String TVTerrestrialAnalog = "TVTerrestrialAnalog";

	/** Digital Terrestrial. (KEYCODE_TV_TERRESTRIAL_DIGITAL) */
	String TVTerrestrialDigital = "TVTerrestrialDigital";

	/** Timer programming. (KEYCODE_TV_TIMER_PROGRAMMING) */
	String TVTimer = "TVTimer";
	// 3.18. Media Controller Keys

	// The key attribute values for media controllers (e.g. remote controls for
	// television, audio systems, and set-top boxes) are derived in part from
	// the consumer electronics technical specifications:

	// DTV Application Software Environment [DASE]

	// Open Cable Application Platform 1.1.3 [OCAP]

	// ANSI/CEA-2014-B, Web-based Protocol and Framework for Remote User
	// Interface on UPnPTM Networks and the Internet [WEB4CE]

	// Android KeyEvent KEYCODEs [AndroidKeycode]

	// key attribute value Typical Usage (Non-normative)
	/**
	 * Switch the input mode on an external AVR (audio/video receiver).
	 * (KEYCODE_AVR_INPUT)
	 */
	String AVRInput = "AVRInput";

	/**
	 * Toggle the power on an external AVR (audio/video receiver).
	 * (KEYCODE_AVR_POWER)
	 */
	String AVRPower = "AVRPower";

	/**
	 * General purpose color-coded media function key, as index 0 (red).
	 * (VK_COLORED_KEY_0, KEYCODE_PROG_RED)
	 */
	String ColorF0Red = "ColorF0Red";

	/**
	 * General purpose color-coded media function key, as index 1 (green).
	 * (VK_COLORED_KEY_1, KEYCODE_PROG_GREEN)
	 */
	String ColorF1Green = "ColorF1Green";

	/**
	 * General purpose color-coded media function key, as index 2 (yellow).
	 * (VK_COLORED_KEY_2, KEYCODE_PROG_YELLOW)
	 */
	String ColorF2Yellow = "ColorF2Yellow";

	/**
	 * General purpose color-coded media function key, as index 3 (blue).
	 * (VK_COLORED_KEY_3, KEYCODE_PROG_BLUE)
	 */
	String ColorF3Blue = "ColorF3Blue";

	/**
	 * General purpose color-coded media function key, as index 4 (grey).
	 * (VK_COLORED_KEY_4)
	 */
	String ColorF4Grey = "ColorF4Grey";

	/**
	 * General purpose color-coded media function key, as index 5 (brown).
	 * (VK_COLORED_KEY_5)
	 */
	String ColorF5Brown = "ColorF5Brown";

	/** Toggle the display of Closed Captions. (VK_CC, KEYCODE_CAPTIONS) */
	String ClosedCaptionToggle = "ClosedCaptionToggle";

	/**
	 * Adjust brightness of device, by toggling between or cycling through
	 * states. (VK_DIMMER)
	 */
	String Dimmer = "Dimmer";

	/** Swap video sources. (VK_DISPLAY_SWAP) */
	String DisplaySwap = "DisplaySwap";

	/** Select Digital Video Rrecorder. (KEYCODE_DVR) */
	String DVR = "DVR";

	/** Exit the current application. (VK_EXIT) */
	String Exit = "Exit";

	/** Clear program or content stored as favorite 0. (VK_CLEAR_FAVORITE_0) */
	String FavoriteClear0 = "FavoriteClear0";

	/** Clear program or content stored as favorite 1. (VK_CLEAR_FAVORITE_1) */
	String FavoriteClear1 = "FavoriteClear1";

	/** Clear program or content stored as favorite 2. (VK_CLEAR_FAVORITE_2) */
	String FavoriteClear2 = "FavoriteClear2";

	/** Clear program or content stored as favorite 3. (VK_CLEAR_FAVORITE_3) */
	String FavoriteClear3 = "FavoriteClear3";

	/**
	 * Select (recall) program or content stored as favorite 0.
	 * (VK_RECALL_FAVORITE_0)
	 */
	String FavoriteRecall0 = "FavoriteRecall0";

	/**
	 * Select (recall) program or content stored as favorite 1.
	 * (VK_RECALL_FAVORITE_1)
	 */
	String FavoriteRecall1 = "FavoriteRecall1";

	/**
	 * Select (recall) program or content stored as favorite 2.
	 * (VK_RECALL_FAVORITE_2)
	 */
	String FavoriteRecall2 = "FavoriteRecall2";

	/**
	 * Select (recall) program or content stored as favorite 3.
	 * (VK_RECALL_FAVORITE_3)
	 */
	String FavoriteRecall3 = "FavoriteRecall3";

	/** Store current program or content as favorite 0. (VK_STORE_FAVORITE_0) */
	String FavoriteStore0 = "FavoriteStore0";

	/** Store current program or content as favorite 1. (VK_STORE_FAVORITE_1) */
	String FavoriteStore1 = "FavoriteStore1";

	/** Store current program or content as favorite 2. (VK_STORE_FAVORITE_2) */
	String FavoriteStore2 = "FavoriteStore2";

	/** Store current program or content as favorite 3. (VK_STORE_FAVORITE_3) */
	String FavoriteStore3 = "FavoriteStore3";

	/** Toggle display of program or content guide. (VK_GUIDE, KEYCODE_GUIDE) */
	String Guide = "Guide";

	/**
	 * If guide is active and displayed, then display next day’s content.
	 * (VK_NEXT_DAY)
	 */
	String GuideNextDay = "GuideNextDay";

	/**
	 * If guide is active and displayed, then display previous day’s content.
	 * (VK_PREV_DAY)
	 */
	String GuidePreviousDay = "GuidePreviousDay";

	/**
	 * Toggle display of information about currently selected context or media.
	 * (VK_INFO, KEYCODE_INFO)
	 */
	String Info = "Info";

	/** Toggle instant replay. (VK_INSTANT_REPLAY) */
	String InstantReplay = "InstantReplay";

	/** Launch linked content, if available and appropriate. (VK_LINK) */
	String Link = "Link";

	/** List the current program. (VK_LIST) */
	String ListProgram = "ListProgram";

	/**
	 * Toggle display listing of currently available live content or programs.
	 * (VK_LIVE)
	 */
	String LiveContent = "LiveContent";

	/** Lock or unlock current content or program. (VK_LOCK) */
	String Lock = "Lock";

	/**
	 * Show a list of media applications: audio/video players and image viewers.
	 * (VK_APPS)
	 */
	String MediaApps = "MediaApps";

	// Do not confuse this key value with the Windows' VK_APPS / VK_CONTEXT_MENU
	// key, which is encoded as "ContextMenu".
	/** Audio track key. (KEYCODE_MEDIA_AUDIO_TRACK) */
	String MediaAudioTrack = "MediaAudioTrack";

	/**
	 * Select previously selected channel or media. (VK_LAST,
	 * KEYCODE_LAST_CHANNEL)
	 */
	String MediaLast = "MediaLast";

	/**
	 * Skip backward to next content or program. (KEYCODE_MEDIA_SKIP_BACKWARD)
	 */
	String MediaSkipBackward = "MediaSkipBackward";

	/**
	 * Skip forward to next content or program. (VK_SKIP,
	 * KEYCODE_MEDIA_SKIP_FORWARD)
	 */
	String MediaSkipForward = "MediaSkipForward";

	/**
	 * Step backward to next content or program. (KEYCODE_MEDIA_STEP_BACKWARD)
	 */
	String MediaStepBackward = "MediaStepBackward";

	/** Step forward to next content or program. (KEYCODE_MEDIA_STEP_FORWARD) */
	String MediaStepForward = "MediaStepForward";

	/** Media top menu. (KEYCODE_MEDIA_TOP_MENU) */
	String MediaTopMenu = "MediaTopMenu";

	/** Navigate in. (KEYCODE_NAVIGATE_IN) */
	String NavigateIn = "NavigateIn";

	/** Navigate to next key. (KEYCODE_NAVIGATE_NEXT) */
	String NavigateNext = "NavigateNext";

	/** Navigate out. (KEYCODE_NAVIGATE_OUT) */
	String NavigateOut = "NavigateOut";

	/** Navigate to previous key. (KEYCODE_NAVIGATE_PREVIOUS) */
	String NavigatePrevious = "NavigatePrevious";

	/**
	 * Cycle to next favorite channel (in favorites list).
	 * (VK_NEXT_FAVORITE_CHANNEL)
	 */
	String NextFavoriteChannel = "NextFavoriteChannel";

	/**
	 * Cycle to next user profile (if there are multiple user profiles).
	 * (VK_USER)
	 */
	String NextUserProfile = "NextUserProfile";

	/** Access on-demand content or programs. (VK_ON_DEMAND) */
	String OnDemand = "OnDemand";

	/** Pairing key to pair devices. (KEYCODE_PAIRING) */
	String Pairing = "Pairing";

	/** Move picture-in-picture window down. (VK_PINP_DOWN) */
	String PinPDown = "PinPDown";

	/** Move picture-in-picture window. (VK_PINP_MOVE) */
	String PinPMove = "PinPMove";

	/** Toggle display of picture-in-picture window. (VK_PINP_TOGGLE) */
	String PinPToggle = "PinPToggle";

	/** Move picture-in-picture window up. (VK_PINP_UP) */
	String PinPUp = "PinPUp";

	/** Decrease media playback speed. (VK_PLAY_SPEED_DOWN) */
	String PlaySpeedDown = "PlaySpeedDown";

	/** Reset playback to normal speed. (VK_PLAY_SPEED_RESET) */
	String PlaySpeedReset = "PlaySpeedReset";

	/** Increase media playback speed. (VK_PLAY_SPEED_UP) */
	String PlaySpeedUp = "PlaySpeedUp";

	/** Toggle random media or content shuffle mode. (VK_RANDOM_TOGGLE) */
	String RandomToggle = "RandomToggle";

	/**
	 * Not a physical key, but this key code is sent when the remote control
	 * battery is low. (VK_RC_LOW_BATTERY)
	 */
	String RcLowBattery = "RcLowBattery";

	/**
	 * Toggle or cycle between media recording speeds. (VK_RECORD_SPEED_NEXT)
	 */
	String RecordSpeedNext = "RecordSpeedNext";

	/**
	 * Toggle RF (radio frequency) input bypass mode (pass RF input directly to
	 * the RF output). (VK_RF_BYPASS)
	 */
	String RfBypass = "RfBypass";

	/** Toggle scan channels mode. (VK_SCAN_CHANNELS_TOGGLE) */
	String ScanChannelsToggle = "ScanChannelsToggle";

	/**
	 * Advance display screen mode to next available mode. (VK_SCREEN_MODE_NEXT)
	 */
	String ScreenModeNext = "ScreenModeNext";

	/**
	 * Toggle display of device settings screen. (VK_SETTINGS, KEYCODE_SETTINGS)
	 */
	String Settings = "Settings";

	/** Toggle split screen mode. (VK_SPLIT_SCREEN_TOGGLE) */
	String SplitScreenToggle = "SplitScreenToggle";

	/**
	 * Switch the input mode on an external STB (set top box).
	 * (KEYCODE_STB_INPUT)
	 */
	String STBInput = "STBInput";

	/**
	 * Toggle the power on an external STB (set top box). (KEYCODE_STB_POWER)
	 */
	String STBPower = "STBPower";

	/** Toggle display of subtitles, if available. (VK_SUBTITLE) */
	String Subtitle = "Subtitle";

	/**
	 * Toggle display of teletext, if available (VK_TELETEXT,
	 * KEYCODE_TV_TELETEXT).
	 */
	String Teletext = "Teletext";

	/** Advance video mode to next available mode. (VK_VIDEO_MODE_NEXT) */
	String VideoModeNext = "VideoModeNext";

	/**
	 * Cause device to identify itself in some manner, e.g., audibly or visibly.
	 * (VK_WINK)
	 */
	String Wink = "Wink";

	/**
	 * Toggle between full-screen and scaled content, or alter magnification
	 * level. (VK_ZOOM, KEYCODE_TV_ZOOM_MODE)
	 */
	String ZoomToggle = "ZoomToggle";

	// Some of the keys defined in the media controller standards already have
	// appropriate keys defined in other sections of this specification. These
	// following table summarizes the key values that MUST be used:
	// key attribute value Typical Usage (Non-normative)

	/**
	 * This key value is used when an implementation is unable to identify
	 * another key value, due to either hardware, platform, or software
	 * constraints.
	 */
	String Unidentified = "Unidentified";
}
