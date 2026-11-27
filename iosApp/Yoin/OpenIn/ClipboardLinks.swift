import Foundation
import Observation
import UIKit

/// Settings › Features and appearance › Music links › Detect music links in clipboard (on by default, as on
/// Android), and what the clipboard check remembers between returns: the pasteboard's change count when it
/// last looked. Never the clip itself. On this device only, not part of cloud sync.
@MainActor
@Observable
final class OpenInSettings {
    static let shared = OpenInSettings()

    nonisolated static let enabledKey = "openIn.clipboardEnabled"
    nonisolated static let lastSeenKey = "openIn.clipboardLastSeenChange"

    @ObservationIgnored private let defaults: UserDefaults
    private(set) var clipboardDetectionEnabled: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        clipboardDetectionEnabled = defaults.object(forKey: Self.enabledKey) as? Bool ?? true
    }

    func setClipboardDetectionEnabled(_ on: Bool) {
        defaults.set(on, forKey: Self.enabledKey)
        clipboardDetectionEnabled = on
    }

    /// The pasteboard's `changeCount` the check last looked at (nil: never).
    var lastSeenChangeCount: Int? {
        get { defaults.object(forKey: Self.lastSeenKey) as? Int }
        set { defaults.set(newValue, forKey: Self.lastSeenKey) }
    }

    /// The Music links row's summary.
    var summary: String {
        clipboardDetectionEnabled ? String(localized: "Clipboard detection on") : String(localized: "Clipboard detection off")
    }
}

/// The clipboard check's rules, pure so they're tested on their own (Android ClipboardLinkGate, iOS terms).
///
/// iOS shows "Yoin pasted from …" whenever an app reads the pasteboard's content. What can be asked without
/// it: `changeCount`, `hasURLs` / `hasStrings`, and `detectPatterns(for: [.probableWebURL])`. So the check
/// asks only those when Yoin comes to the front; when the clip probably holds a web link it hasn't looked at,
/// the card offers the system Paste button, and the content is read only when the user taps it (the system
/// control pastes without the banner). Then a Spotify or Apple Music link opens the card proper.
enum ClipboardLinkGate {
    /// Whether to look at the pasteboard at all: the setting on, an account to open or search in, no
    /// first-run landing over the shell, and a clip not looked at before (its change count).
    static func shouldDetect(enabled: Bool, hasAccounts: Bool, landingOpen: Bool,
                             changeCount: Int, lastSeen: Int?) -> Bool {
        enabled && hasAccounts && !landingOpen && changeCount != lastSeen
    }
}

/// What the check may ask the pasteboard without reading it (tests answer for UIPasteboard).
@MainActor
protocol PasteboardProbe {
    var changeCount: Int { get }
    /// `hasURLs || hasStrings`: something a link could be in.
    var mayHoldLink: Bool { get }
    /// `detectPatterns(for: [.probableWebURL])`: no paste banner.
    func probablyWebURL() async -> Bool
}

struct SystemPasteboardProbe: PasteboardProbe {
    var changeCount: Int { UIPasteboard.general.changeCount }
    var mayHoldLink: Bool { UIPasteboard.general.hasURLs || UIPasteboard.general.hasStrings }

    func probablyWebURL() async -> Bool { await Self.detectProbableWebURL() }

    nonisolated private static func detectProbableWebURL() async -> Bool {
        await answer { reply in
            UIPasteboard.general.detectPatterns(for: [\UIPasteboard.DetectedValues.probableWebURL]) { result in
                reply((try? result.get())?.contains(\UIPasteboard.DetectedValues.probableWebURL) ?? false)
            }
        }
    }

    /// Waits for a callback UIKit makes on a queue of its own (`detectPatterns` answers on
    /// `com.apple.UIKit.pasteboard.completion-queue`). Nonisolated, so the closures written here are too: a
    /// main-actor closure handed to UIKit there trapped in Swift 6's isolation check, killing the app on launch
    /// or on return to the front whenever a new clip was on the pasteboard (0.6.4 (38/39)).
    nonisolated static func answer(_ ask: @escaping @Sendable (@escaping @Sendable (Bool) -> Void) -> Void) async -> Bool {
        await withCheckedContinuation { (done: CheckedContinuation<Bool, Never>) in
            ask { found in done.resume(returning: found) }
        }
    }
}

/// Once each time Yoin comes to the front (Android ClipboardLinkWatcher): the pasteboard's change count, then
/// its pattern; a clip that probably holds a web link offers the Paste card. The change count looked at is
/// remembered so the same clip isn't offered again on the next return.
@MainActor
final class ClipboardLinkWatcher {
    private let settings: OpenInSettings
    private let probe: PasteboardProbe
    private var checking = false

    init(settings: OpenInSettings = .shared, probe: PasteboardProbe = SystemPasteboardProbe()) {
        self.settings = settings
        self.probe = probe
    }

    /// True when the Paste card should be offered.
    func check(hasAccounts: Bool, landingOpen: Bool) async -> Bool {
        guard !checking else { return false }
        let count = probe.changeCount
        guard ClipboardLinkGate.shouldDetect(enabled: settings.clipboardDetectionEnabled, hasAccounts: hasAccounts,
                                             landingOpen: landingOpen, changeCount: count,
                                             lastSeen: settings.lastSeenChangeCount) else { return false }
        checking = true
        defer { checking = false }
        settings.lastSeenChangeCount = count
        guard probe.mayHoldLink else { return false }
        return await probe.probablyWebURL()
    }
}
