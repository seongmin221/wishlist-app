import Foundation

/// The extension's direct send of a share to the server (C3-D1 C안, second half): a background
/// `URLSession` upload of the same idempotent ITEM-01 request the app would replay with the same key,
/// so a share starts analysis even if the app is not opened soon.
protocol ShareDirectSender {
    func send(record: InboxRecordFile)
}

/// C3: the slot exists but sends nothing; the app imports the inbox file and sends it (same key).
///
/// Enable only in the "인증 연결" stage, once all of these hold:
/// - Apple Developer membership (real app group and keychain access group on a team).
/// - Keychain sharing between the app and the extension, so the extension can read a valid ID token
///   without linking Shared or Firebase.
/// - A token expiry policy: an expired or missing token must leave the file for the app (no
///   refresh in the extension), and a 401 must never delete the inbox file.
struct DisabledShareDirectSender: ShareDirectSender {
    func send(record: InboxRecordFile) {}
}
