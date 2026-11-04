import AVFoundation

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

/// Things the engine did on its own that the UI may want to surface.
enum PlayerEvent: Sendable, Equatable {
    /// [track] failed to load or play and was skipped.
    case skippedAfterError(track: PlayableTrack, message: String)
    /// Every track in the queue failed in a row; playback stopped.
    case queueFailed
    /// Repeat off reached the end of the queue.
    case queueEnded
}

// MARK: - Stream quality (Subsonic)

/// How much of a Subsonic song the player asks for: the file as it is, or an
/// mp3 transcode at no more than [maxBitRateKbps] (Android `StreamQuality`).
enum StreamQuality: String, CaseIterable, Sendable {
    case original, kbps320 = "320", kbps192 = "192", kbps128 = "128"

    /// Android's low preset.
    static let low: StreamQuality = .kbps128

    var maxBitRateKbps: Int? {
        switch self {
        case .original: nil
        case .kbps320: 320
        case .kbps192: 192
        case .kbps128: 128
        }
    }

    var label: String { maxBitRateKbps.map { "\($0) kbps" } ?? "Original" }

    /// Outputs that carry the file as it is: a wire (owner rule 2026-11-04).
    static let wiredPorts: Set<AVAudioSession.Port> = [.headphones, .usbAudio, .lineOut, .HDMI]

    /// Automatic's rule: the original file when the route ends in a wire,
    /// the low preset for Bluetooth / AirPods, the speaker, AirPlay and the rest.
    static func forRoute(_ outputs: [AVAudioSession.Port]) -> StreamQuality {
        outputs.contains(where: wiredPorts.contains) ? .original : .low
    }
}

/// Settings › Server › Playback quality: Automatic (by route) or a fixed
/// quality. One choice on iOS, no Wi‑Fi / mobile split; device-local.
enum StreamQualityChoice: String, CaseIterable, Sendable {
    case automatic
    case original, kbps320 = "320", kbps192 = "192", kbps128 = "128"

    static let `default`: StreamQualityChoice = .automatic
    static let defaultsKey = "streamQuality"

    var label: String { fixed?.label ?? "Automatic" }

    var fixed: StreamQuality? { StreamQuality(rawValue: rawValue) }

    func resolve(routeOutputs: [AVAudioSession.Port]) -> StreamQuality {
        fixed ?? StreamQuality.forRoute(routeOutputs)
    }

    static var current: StreamQualityChoice {
        get {
            UserDefaults.standard.string(forKey: defaultsKey).flatMap(StreamQualityChoice.init(rawValue:)) ?? .default
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: defaultsKey) }
    }

    /// What a song opened now plays at.
    static func resolvedNow() -> StreamQuality {
        current.resolve(routeOutputs: AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portType))
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
    static func plan(_ quality: StreamQuality, track: PlayableTrack) -> StreamDelivery {
        guard let cap = quality.maxBitRateKbps, SubsonicStreamURLs.isStream(track.streamURL) else { return .original }
        if let source = track.sourceKbps, source <= cap { return .original }
        return StreamDelivery(maxBitRateKbps: cap, seeksByTime: track.sourceKbps != nil)
    }
}

/// Rewrites a signed Subsonic `stream` URL when the player opens it (Android
/// `SubsonicStreamUrls`). The token signs the password, not the query, so the
/// signature stays valid.
enum SubsonicStreamURLs {
    static let transcodeFormat = "mp3"
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
