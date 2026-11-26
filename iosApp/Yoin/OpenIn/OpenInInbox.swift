import Foundation

/// The share extension's hand-off (App Review-safe, public API only): the extension keeps the shared text in
/// the App Group and says so; Yoin takes it once the next time it comes to the front (or at once, when it is
/// already in front on an iPad or a Mac, through a Darwin notification) and forgets it. Older than
/// [maxAge], a share is dropped. Shared by the app and the extension (YoinShare). Android holds the text in
/// memory until its shell takes it.
enum OpenInInbox {
    static let appGroup = "group.com.gpo.yoin"
    static let textKey = "openIn.sharedText"
    static let atKey = "openIn.sharedAt"
    /// Older than this, a share left behind (Yoin not opened) is dropped, not acted on.
    static let maxAge: TimeInterval = 10 * 60
    /// Posted by the extension after it saved a share: a Yoin already in front takes it now.
    static let darwinNotification = "com.gpo.yoin.openIn.shared"

    static var defaults: UserDefaults? { UserDefaults(suiteName: appGroup) }

    static func put(_ text: String, now: Date = Date(), into defaults: UserDefaults? = OpenInInbox.defaults) {
        defaults?.set(String(text.prefix(4000)), forKey: textKey)
        defaults?.set(now.timeIntervalSince1970, forKey: atKey)
    }

    /// The waiting share, removed as it is read; nil when none or too old.
    static func take(now: Date = Date(), from defaults: UserDefaults? = OpenInInbox.defaults) -> String? {
        guard let defaults, let text = defaults.string(forKey: textKey) else { return nil }
        let at = defaults.double(forKey: atKey)
        defaults.removeObject(forKey: textKey)
        defaults.removeObject(forKey: atKey)
        return now.timeIntervalSince1970 - at <= maxAge ? text : nil
    }

    /// The extension: tell a running Yoin a share is waiting.
    static func postShared() {
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(),
                                             CFNotificationName(darwinNotification as CFString), nil, nil, true)
    }
}
