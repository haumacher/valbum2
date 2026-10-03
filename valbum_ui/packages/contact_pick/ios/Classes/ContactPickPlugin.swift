import Contacts
import ContactsUI
import Flutter
import UIKit

/// Picks one e-mail address or phone number in the system's contact picker
/// (issue #201).
///
/// `CNContactPickerViewController` runs out of process and needs no contacts
/// permission (and no `NSContactsUsageDescription`): with
/// `predicateForSelectionOfProperty` set, tapping a contact shows its card, and
/// tapping one address or number of the asked kind hands back exactly that
/// property. A contact without one is greyed out.
///
/// Unverified: written without a Mac to build or run it on.
public class ContactPickPlugin: NSObject, FlutterPlugin, CNContactPickerDelegate {

  private var pending: FlutterResult?

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(
      name: "de.haumacher.valbum/contact_pick",
      binaryMessenger: registrar.messenger())
    registrar.addMethodCallDelegate(ContactPickPlugin(), channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    guard call.method == "pick" else {
      result(FlutterMethodNotImplemented)
      return
    }
    if pending != nil {
      result(FlutterError(code: "BUSY", message: "A contact is being picked already.", details: nil))
      return
    }
    guard let presenter = ContactPickPlugin.topViewController() else {
      result(FlutterError(code: "NO_ACTIVITY", message: "The contacts picker needs the app in the foreground.", details: nil))
      return
    }
    let arguments = call.arguments as? [String: Any]
    let phone = (arguments?["kind"] as? String) == "phone"
    let key = phone ? CNContactPhoneNumbersKey : CNContactEmailAddressesKey

    let picker = CNContactPickerViewController()
    picker.delegate = self
    picker.displayedPropertyKeys = [key]
    picker.predicateForEnablingContact = NSPredicate(format: "%K.@count > 0", key)
    // Never the whole contact: a tap opens the card, and a tap on one address
    // of the asked kind picks it.
    picker.predicateForSelectionOfContact = NSPredicate(value: false)
    picker.predicateForSelectionOfProperty = NSPredicate(format: "key == %@", key)
    pending = result
    presenter.present(picker, animated: true, completion: nil)
  }

  public func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
    var value = ""
    if let address = contactProperty.value as? String {
      value = address
    } else if let number = contactProperty.value as? CNPhoneNumber {
      value = number.stringValue
    }
    finish(["name": ContactPickPlugin.name(of: contactProperty.contact), "value": value])
  }

  public func contactPickerDidCancel(_ picker: CNContactPickerViewController) {
    finish(nil)
  }

  private func finish(_ answer: [String: String]?) {
    let result = pending
    pending = nil
    result?(answer)
  }

  /// The contact's name from what the picker fetched, asking each key first:
  /// reading one that was not fetched raises an exception.
  private static func name(of contact: CNContact) -> String {
    var parts: [String] = []
    for key in [CNContactGivenNameKey, CNContactFamilyNameKey] where contact.isKeyAvailable(key) {
      let part = key == CNContactGivenNameKey ? contact.givenName : contact.familyName
      if !part.isEmpty {
        parts.append(part)
      }
    }
    if parts.isEmpty && contact.isKeyAvailable(CNContactOrganizationNameKey) {
      return contact.organizationName
    }
    return parts.joined(separator: " ")
  }

  private static func topViewController() -> UIViewController? {
    var root: UIViewController?
    if #available(iOS 13.0, *) {
      root = UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .flatMap { $0.windows }
        .first { $0.isKeyWindow }?.rootViewController
    }
    if root == nil {
      root = UIApplication.shared.delegate?.window??.rootViewController
    }
    while let presented = root?.presentedViewController {
      root = presented
    }
    return root
  }
}
