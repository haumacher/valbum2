/// A recipient out of the phone's own address book (issue #201).
///
/// The author's decision (2026-10-03): the sharer **picks one address or
/// number** in the system's picker, and the app holds no contacts permission
/// at all — the plugin `packages/contact_pick` opens the picker and answers the
/// one row picked. One picker cannot offer e-mail addresses and phone numbers
/// together, so the chooser offers the two as two entries.
///
/// Offered on Android and iOS only: [phoneContacts] is `null` on the web and
/// on the desktop, which have no address book to pick from.
library;

import 'package:contact_pick/contact_pick.dart';

import 'platform.dart';
import 'resource.dart';

/// One address picked: the contact's name in the address book (empty where
/// it has none) and the address or number as the address book holds it.
typedef PickedAddress = ({String name, ContactAddress address});

/// The phone's address book, see the library.
abstract class PhoneContacts {
  const PhoneContacts();

  /// Lets the user pick one address of [kind]; `null` where they cancelled.
  /// Throws where no picker could be opened.
  Future<PickedAddress?> pick(AddressKind kind);
}

/// The [PhoneContacts] of `packages/contact_pick`.
class PluginPhoneContacts extends PhoneContacts {
  const PluginPhoneContacts();

  @override
  Future<PickedAddress?> pick(AddressKind kind) async {
    var picked = await pickContact(
        kind == AddressKind.phone ? PickKind.phone : PickKind.email);
    if (picked == null) {
      return null;
    }
    return (
      name: picked.name,
      address: ContactAddress(kind: kind, value: picked.value),
    );
  }
}

/// The address book of this device, `null` where there is none to pick from.
///
/// A seam like `downloadSaver`: a widget test runs on the desktop build, so
/// a test that wants the entries sets this to a [PluginPhoneContacts] and
/// mocks the plugin's channel.
PhoneContacts? phoneContacts = defaultPhoneContacts();
