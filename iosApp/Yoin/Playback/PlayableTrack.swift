import AVFoundation
import YoinShared

/// One streamable song, already resolved by its provider (a Subsonic
/// `stream` URL, a cover URL). The engine never talks to a provider itself.
struct PlayableTrack: Identifiable, Hashable, Sendable {
    var id: String
    var provider: String
    var title: String
    var artist: String
    var album: String
    var albumId: String?
    var durationSec: Double
    var streamURL: URL
    var coverURL: URL?
    /// The provider's favorite flag when the track was resolved (nil = unknown).
    var starred: Bool? = nil
    /// The provider's own 1–5 rating (nil = unrated or unknown).
    var userRating: Int? = nil
    /// The file's own bit rate as the server lists it (Subsonic), for
    /// [StreamDelivery.plan]; nil = unknown or not a stream.
    var sourceKbps: Int? = nil
}

/// A queue slot. The same track can sit in the queue twice, so lists and
/// shuffle restore key on the slot, never on the track.
struct QueueItem: Identifiable, Hashable, Sendable {
    let id: UUID
    let track: PlayableTrack
    /// Added by Play next / Add to queue (Android `EXTRA_USER_QUEUED`).
    var userQueued: Bool

    init(_ track: PlayableTrack, userQueued: Bool = false, id: UUID = UUID()) {
        self.id = id
        self.track = track
        self.userQueued = userQueued
    }
}

/// Repeat axis (Android `PlayMode`): all is the default, one repeats the
/// current track on auto-advance only, off plays to the queue's end and stops.
enum RepeatMode: String, Sendable, CaseIterable {
    case all, one, off

    /// The split pill's repeat segment on Android: all → one → off → all.
    var next: RepeatMode {
        switch self {
        case .all: .one
        case .one: .off
        case .off: .all
        }
    }
}

/// Why the current song isn't playing (`PlayerEngine.failure`).
struct PlaybackFailure: Sendable, Equatable {
    var track: PlayableTrack
    var message: String
    /// The network or server failed (Retry may work); false = the queue's songs themselves failed.
    var network: Bool
}

/// Things the engine did on its own that the UI may want to surface.
enum PlayerEvent: Sendable, Equatable {
    /// [track] failed to load or play and was skipped.
    case skippedAfterError(track: PlayableTrack, message: String)
    /// [track] couldn't be reached (network / server): playback is held on it (`PlayerEngine.failure`).
    case failed(track: PlayableTrack, message: String)
    /// Every track in the queue failed in a row; playback stopped.
    case queueFailed
    /// Repeat off reached the end of the queue.
    case queueEnded
}

// MARK: - Stream quality (Subsonic)

#if os(iOS)
/// The rule is the shared one (YoinShared `StreamQualityPolicy`, Android's
/// player/StreamQuality.kt; owner decision O01): one choice for Wi‑Fi, one
/// for mobile data, Auto following the output. This file maps iOS's network
/// and route onto its inputs and keeps the two choices on this device.
enum StreamConditions {
    /// Android `outputKindOf` over AVAudioSession ports: a wire (headphones,
    /// USB, line out, HDMI) wins, then Bluetooth media (A2DP, LE Audio), else
    /// the speaker. Hands-free Bluetooth (HFP, Android's SCO), AirPlay,
    /// CarPlay, the earpiece and anything new count as the speaker, as every
    /// other Android output type does.
    static func outputKind(_ ports: [AVAudioSession.Port]) -> AudioOutputKind {
        AudioOutputKind.companion.strongest(kinds: ports.map(kind(of:)))
    }

    static func kind(of port: AVAudioSession.Port) -> AudioOutputKind {
        switch port {
        case .headphones, .usbAudio, .lineOut, .HDMI: .wired
        case .bluetoothA2DP, .bluetoothLE: .bluetooth
        default: .speaker
        }
    }

    /// Android's NET_CAPABILITY_NOT_METERED: a satisfied path that is
    /// neither expensive (cellular, a phone's hotspot) nor constrained (Low
    /// Data Mode). No network counts as metered.
    static func isUnmetered(satisfied: Bool, expensive: Bool, constrained: Bool) -> Bool {
        satisfied && !expensive && !constrained
    }
}
#endif

/// Settings › Server › Playback: the two choices, per device (Android
/// `StreamQualityPreference`, same keys as values: auto / original / 320 / 192 / 128).
enum StreamQualityStore {
    static let wifiKey = "streamQualityWifi"
    static let mobileKey = "streamQualityMobile"
    /// The single choice iOS had before O01 ("automatic", "original", "320", …).
    static let legacyKey = "streamQuality"

    /// The stored choices. The first read after the update moves the old
    /// single choice over: Automatic (or nothing) becomes the defaults
    /// (Wi‑Fi Original, mobile Auto), an explicit quality applies to both.
    static func load(_ defaults: UserDefaults = .standard) -> StreamQualitySettings {
        let wifi = defaults.string(forKey: wifiKey)
        let mobile = defaults.string(forKey: mobileKey)
        let legacy = defaults.string(forKey: legacyKey)
        let settings = StreamQualitySettings.companion.fromStored(wifiKey: wifi, mobileKey: mobile, legacyKey: legacy)
        if legacy != nil {
            if wifi == nil, mobile == nil { save(settings, defaults) }
            defaults.removeObject(forKey: legacyKey)
        }
        return settings
    }

    static func setWifi(_ quality: StreamQuality, _ defaults: UserDefaults = .standard) {
        save(StreamQualitySettings(wifi: quality, mobile: load(defaults).mobile), defaults)
    }

    static func setMobile(_ quality: StreamQuality, _ defaults: UserDefaults = .standard) {
        save(StreamQualitySettings(wifi: load(defaults).wifi, mobile: quality), defaults)
    }

    private static func save(_ settings: StreamQualitySettings, _ defaults: UserDefaults) {
        defaults.set(settings.wifi.key, forKey: wifiKey)
        defaults.set(settings.mobile.key, forKey: mobileKey)
    }
}

/// What the player asks the server for, for one song (Android `StreamDelivery`).
struct StreamDelivery: Hashable, Sendable {
    /// nil = the file as it is.
    var maxBitRateKbps: Int?
    /// A seek reopens the transcode at the target second (`timeOffset`): the
    /// server encodes a file listed above the cap at the cap, constant bit
    /// rate, and a transcode has no byte ranges to seek in.
    var seeksByTime: Bool

    var transcoded: Bool { maxBitRateKbps != nil }

    static let original = StreamDelivery(maxBitRateKbps: nil, seeksByTime: false)

    /// The file when [quality] is Original, when the song isn't a Subsonic
    /// stream, or when the file is already at or under the cap ([sourceKbps]):
    /// the server would send it as it is, and the file seeks by byte.
    ///
    /// A transcode seeks by reopening at `timeOffset` only when the server
    /// lists OpenSubsonic `transcodeOffset` ([timeOffset], Android
    /// `SubsonicServerTraits`, Codex A38): a server that ignores the parameter
    /// would answer from 0 while the clock counted from the target. Otherwise
    /// the transcode seeks in its own bytes, from 0.
    static func plan(_ quality: StreamQuality, track: PlayableTrack, timeOffset: Bool) -> StreamDelivery {
        guard SubsonicStreamURLs.isStream(track.streamURL) else { return .original }
        let source = track.sourceKbps.flatMap { $0 > 0 ? $0 : nil }
        let plan = StreamQualityPolicy.shared.planKbps(quality: quality, sourceKbps: Int32(clamping: source ?? 0))
        guard plan.transcoded else { return .original }
        return StreamDelivery(maxBitRateKbps: Int(plan.capKbps), seeksByTime: timeOffset && source != nil)
    }
}

/// Rewrites a signed Subsonic `stream` URL when the player opens it (Android
/// `SubsonicStreamUrls`). The token signs the password, not the query, so the
/// signature stays valid.
enum SubsonicStreamURLs {
    static let transcodeFormat = StreamQualityPolicy.shared.TRANSCODE_FORMAT
    private static let transcodeParameters: Set<String> = ["maxBitRate", "format", "timeOffset", "estimateContentLength"]

    static func isStream(_ url: URL) -> Bool {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        let last = c.path.split(separator: "/").last.map(String.init)
        return (last == "stream" || last == "stream.view")
            && c.queryItems?.contains(where: { $0.name == "id" }) == true
    }

    /// [url] asking for an mp3 at no more than the delivery's cap, starting
    /// [timeOffsetSec] in when above zero, with Navidrome's
    /// `estimateContentLength` (a transcode has no length otherwise). The
    /// original delivery strips every transcode parameter.
    static func url(_ url: URL, delivery: StreamDelivery, timeOffsetSec: Int = 0) -> URL {
        guard isStream(url), var c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        var items = (c.queryItems ?? []).filter { !transcodeParameters.contains($0.name) }
        if let kbps = delivery.maxBitRateKbps {
            items.append(URLQueryItem(name: "maxBitRate", value: String(kbps)))
            items.append(URLQueryItem(name: "format", value: transcodeFormat))
            if timeOffsetSec > 0 { items.append(URLQueryItem(name: "timeOffset", value: String(timeOffsetSec))) }
            items.append(URLQueryItem(name: "estimateContentLength", value: "true"))
        }
        c.queryItems = items
        return c.url ?? url
    }
}
