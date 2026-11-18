import Foundation
import UIKit

/// The device Yoin runs on, as Spotify Connect lists it (Android SpotifyMusicSource.connectState's
/// localNames; parity I23). Spotify gives a device a name and a type; an app on iOS can't read the
/// device's own name, so an iPhone is the Smartphone named "… iPhone …" and an iPad the Tablet named
/// "… iPad …" (a Pixel is a Smartphone too). A Mac's Spotify app is the Computer listed under the Mac's
/// own name.
///
/// On an iPhone or iPad, "here" is driven through App Remote. A Mac has no App Remote (the desktop app
/// doesn't speak it): its Spotify is one more Connect device, driven by its id through the Web API
/// (`SpotifyAudioOutput.macTarget`).
struct SpotifyThisDevice: Equatable, Sendable {
    enum Kind: Equatable, Sendable { case phone, tablet, mac }

    var kind: Kind
    /// A Mac's own names (its host name, and whatever UIKit calls it); empty on iPhone and iPad.
    var names: [String] = []
    /// Persisted local Spotify Connect device ID for this device & account, used before name matching.
    var learnedId: String? = nil

    static let phone = SpotifyThisDevice(kind: .phone)
    static let tablet = SpotifyThisDevice(kind: .tablet)
    static func mac(_ names: [String]) -> SpotifyThisDevice { SpotifyThisDevice(kind: .mac, names: names) }

    func withLearnedId(_ id: String?) -> SpotifyThisDevice {
        var copy = self
        copy.learnedId = id.flatMap { $0.isEmpty ? nil : $0 }
        return copy
    }

    private static func storageKey(profileId: String, kind: Kind) -> String {
        let suffix: String = switch kind {
        case .phone: "phone"
        case .tablet: "tablet"
        case .mac: "mac"
        }
        return "spotifyLearnedDeviceId.\(profileId).\(suffix)"
    }

    static func loadLearnedId(profileId: String?, kind: Kind, defaults: UserDefaults = .standard) -> String? {
        guard let profileId, !profileId.isEmpty else { return nil }
        return defaults.string(forKey: storageKey(profileId: profileId, kind: kind))
            .flatMap { $0.isEmpty ? nil : $0 }
    }

    static func saveLearnedId(_ deviceId: String?, profileId: String?, kind: Kind, defaults: UserDefaults = .standard) {
        guard let profileId, !profileId.isEmpty else { return }
        let key = storageKey(profileId: profileId, kind: kind)
        if let deviceId, !deviceId.isEmpty {
            defaults.set(deviceId, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    var isMac: Bool { kind == .mac }

    /// What copy calls it ("on this iPhone"): a product name, the same in every language.
    var word: String {
        switch kind {
        case .phone: "iPhone"
        case .tablet: "iPad"
        case .mac: "Mac"
        }
    }

    /// Every [word] a line of copy may have been written with.
    static let words = ["iPhone", "iPad", "Mac"]

    /// Spotify's type for it.
    var type: String {
        switch kind {
        case .phone: "Smartphone"
        case .tablet: "Tablet"
        case .mac: "Computer"
        }
    }

    /// Whether the Connect device called [name] of [type] is this one.
    func matches(name: String, type: String) -> Bool {
        guard type.caseInsensitiveCompare(self.type) == .orderedSame else { return false }
        switch kind {
        case .phone, .tablet:
            return name.localizedCaseInsensitiveContains(word)
        case .mac:
            let name = name.trimmingCharacters(in: .whitespaces)
            let folded = Self.fold(name)
            return names.contains { own in
                let own = own.trimmingCharacters(in: .whitespaces)
                guard !own.isEmpty else { return false }
                if own.caseInsensitiveCompare(name) == .orderedSame { return true }
                // A host name is the computer's name made safe for the network ("51的Mac mini" is
                // 51deMac-mini.local): the two agree once both are folded.
                let f = Self.fold(own)
                return !f.isEmpty && f == folded
            }
        }
    }

    /// Matches by persisted local device ID first (for renamed devices), then by type + name.
    func matches(id: String?, name: String, type: String) -> Bool {
        if let learnedId, !learnedId.isEmpty, let id, !id.isEmpty, learnedId == id {
            return true
        }
        return matches(name: name, type: type)
    }

    func matches(_ now: SpotifySource.PlayerNow) -> Bool {
        matches(id: now.deviceId, name: now.deviceName, type: now.deviceType)
    }
    func matches(_ d: SpotifySource.Device) -> Bool {
        matches(id: d.id, name: d.name, type: d.type)
    }

    /// This device in the account's Connect list; persisted device ID first, then active/matching name.
    func pick(_ devices: [SpotifySource.Device]) -> SpotifySource.Device? {
        if let learnedId, !learnedId.isEmpty,
           let learned = devices.first(where: { $0.hasId && $0.id == learnedId }) {
            return learned
        }
        let mine = devices.filter { $0.hasId && matches(name: $0.name, type: $0.type) }
        return mine.first(where: \.isActive) ?? mine.first
    }

    /// A name as macOS turns it into a host name: Latin letters, no accents, only letters and digits,
    /// lower case.
    static func fold(_ s: String) -> String {
        let latin = s.applyingTransform(.toLatin, reverse: false) ?? s
        let plain = latin.applyingTransform(.stripDiacritics, reverse: false) ?? latin
        return String(String.UnicodeScalarView(plain.lowercased().unicodeScalars.filter {
            $0.isASCII && CharacterSet.alphanumerics.contains($0)
        }))
    }

    /// The Mac's names an iPad app can read: the kernel's host name without its domain, and UIKit's name
    /// for the device (generic on iPadOS; on a Mac it may be the computer's).
    static func macNames(hostName: String, uiName: String, override: String? = nil) -> [String] {
        if let override, !override.isEmpty { return [override] }
        let short = hostName.split(separator: ".").first.map(String.init) ?? hostName
        return [uiName, short].filter { !$0.isEmpty && $0 != "localhost" }
    }

    /// What Yoin runs on now. `-spotifyThisMac <name>` (debug builds) names the stand-in Mac of a
    /// `-platformMac YES` run, whose host is a simulator.
    @MainActor static var current: SpotifyThisDevice {
        if Platform.current.isMac {
            var debugName: String?
            #if DEBUG
            debugName = UserDefaults.standard.string(forKey: "spotifyThisMac")
            #endif
            return .mac(macNames(hostName: kernelHostName(), uiName: UIDevice.current.name, override: debugName))
        }
        return UIDevice.current.userInterfaceIdiom == .pad ? .tablet : .phone
    }

    private static func kernelHostName() -> String {
        var buffer = [CChar](repeating: 0, count: 256)
        guard gethostname(&buffer, buffer.count - 1) == 0 else { return "" }
        return String(cString: buffer)
    }
}
