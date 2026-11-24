import Foundation

/// The share extension's hand-off: the extension keeps the shared text in the App Group for a moment and
/// opens `yoin://open-in`; the app takes it once and forgets it (Android holds it in memory until the shell
/// takes it). Shared by the app and the extension (YoinShare).
enum OpenInInbox {
    static let appGroup = "group.com.gpo.yoin"
    static let textKey = "openIn.sharedText"
    static let atKey = "openIn.sharedAt"
    /// The URL the extension opens Yoin with.
    static let url = URL(string: "yoin://open-in")!
    /// Older than this, a share left behind (Yoin never opened) is dropped, not acted on.
    static let maxAge: TimeInterval = 10 * 60

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

    static func isOpenIn(_ url: URL) -> Bool { url.scheme == "yoin" && url.host() == "open-in" }
}
