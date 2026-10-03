/// One e-mail address or phone number out of the phone's address book,
/// picked in the system's own picker (issue #201).
///
/// No contacts permission is involved, and none is declared: on Android the
/// picker (`Intent.ACTION_PICK` on the e-mail or phone data rows) hands back
/// one data row with a temporary read grant for exactly that row; on iOS
/// `CNContactPickerViewController` runs out of process and hands back the one
/// property the user tapped. Neither picker can offer both kinds at once, so
/// the caller says which kind it wants.
///
/// Implemented on Android and iOS only; no other platform answers the
/// channel.
library;

import 'package:flutter/services.dart';

/// The channel both platform sides answer on.
const MethodChannel contactPickChannel =
    MethodChannel("de.haumacher.valbum/contact_pick");

/// The one method of [contactPickChannel]: its argument is a map
/// `{"kind": "email" | "phone"}`, its answer a map `{"name", "value"}`, or
/// `null` where the user cancelled.
const String contactPickMethod = "pick";

/// The two kinds of address the picker offers.
enum PickKind { email, phone }

/// What the user picked: the contact's display name (empty where the address
/// book has none) and the one address or number, as the address book holds
/// it.
typedef PickedContact = ({String name, String value});

/// Opens the system's picker for one address of [kind]; `null` where the user
/// cancelled or picked a row without a value. Throws a [PlatformException]
/// where no picker could be opened.
Future<PickedContact?> pickContact(PickKind kind) async {
  var answer = await contactPickChannel.invokeMapMethod<String, Object?>(
    contactPickMethod,
    {"kind": kind.name},
  );
  if (answer == null) {
    return null;
  }
  var value = (answer["value"] as String? ?? "").trim();
  if (value.isEmpty) {
    return null;
  }
  return (name: (answer["name"] as String? ?? "").trim(), value: value);
}
