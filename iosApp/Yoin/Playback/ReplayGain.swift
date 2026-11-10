import Foundation
import Observation

/// A song's audio facts as the server lists them (OpenSubsonic, shared `SongAudio`): ReplayGain and format.
struct TrackAudio: Hashable, Sendable {
    var trackGainDb: Double?
    var albumGainDb: Double?
    var trackPeak: Double?
    var albumPeak: Double?
    var samplingRate: Int?
    var bitDepth: Int?
    var channelCount: Int?
}

/// Settings › Volume leveling (Android ReplayGainMode, #139): which of a song's ReplayGain values the player
/// applies. Subsonic only; a song without tags always plays as it is.
enum ReplayGainMode: String, CaseIterable, Sendable {
    case off, track
    /// The album gain, or the track gain where the song has none (an album mixed from different sources is
    /// tagged per track on purpose).
    case album

    var label: String {
        switch self {
        case .off: String(localized: "Off")
        case .track: String(localized: "By track")
        case .album: String(localized: "By album")
        }
    }
}

/// The gain the player applies to a song.
struct AppliedReplayGain: Hashable, Sendable {
    var db: Double
    /// What every sample is multiplied by. iOS's player can only turn a song down (AVAudioMix volume ≤ 1),
    /// so a gain above 0 dB plays at full scale.
    var linear: Double
    var peakLimited: Bool

    static let unity = AppliedReplayGain(db: 0, linear: 1, peakLimited: false)

    /// Android appliedReplayGain: the chosen value plus the pre-amp, lowered where the peak would pass full
    /// scale. Unity when leveling is off or the song has no tag for it (an untagged song is never guessed at).
    static func of(_ audio: TrackAudio?, mode: ReplayGainMode, preampDb: Double = ReplayGainSettings.preampDb) -> Self {
        guard let audio else { return .unity }
        let picked: (Double, Double?)?
        switch mode {
        case .off: picked = nil
        case .track: picked = audio.trackGainDb.map { ($0, audio.trackPeak) }
        case .album: picked = audio.albumGainDb.map { ($0, audio.albumPeak ?? audio.trackPeak) }
            ?? audio.trackGainDb.map { ($0, audio.trackPeak) }
        }
        guard let (gain, peak) = picked else { return .unity }
        var db = gain + preampDb
        var limited = false
        if let peak, peak > 0 {
            let ceiling = -20 * log10(peak)
            if db > ceiling { db = ceiling; limited = true }
        }
        return AppliedReplayGain(db: db, linear: min(1, pow(10, db / 20)), peakLimited: limited)
    }
}

/// The leveling choice (UserDefaults) and each queued Subsonic song's audio facts by song id, which the AV
/// output reads when it makes an item. A change applies at once, to the song playing too.
@MainActor
@Observable
final class ReplayGainSettings {
    static let shared = ReplayGainSettings()
    /// About −14 LUFS, where streaming services sit (Android DefaultPreampDb).
    nonisolated static let preampDb = 4.0
    static let changed = Notification.Name("YoinReplayGainChanged")

    var mode: ReplayGainMode {
        didSet {
            guard mode != oldValue else { return }
            UserDefaults.standard.set(mode.rawValue, forKey: "replayGainMode")
            NotificationCenter.default.post(name: Self.changed, object: nil)
        }
    }

    /// Settings › Spatial audio (Android #139): 5.1 / 7.1 files spatialized (on) or folded to stereo.
    var spatializeMultichannel: Bool {
        didSet {
            guard spatializeMultichannel != oldValue else { return }
            UserDefaults.standard.set(spatializeMultichannel, forKey: "spatializeMultichannel")
            NotificationCenter.default.post(name: Self.changed, object: nil)
        }
    }

    @ObservationIgnored private var audio: [String: TrackAudio] = [:]

    init() {
        spatializeMultichannel = UserDefaults.standard.object(forKey: "spatializeMultichannel") as? Bool ?? true
        // By album (owner 2026-11-04): keeps each album's own dynamics.
        mode = UserDefaults.standard.string(forKey: "replayGainMode").flatMap(ReplayGainMode.init(rawValue:)) ?? .album
    }

    func remember(_ a: TrackAudio?, songId: String) {
        if let a { audio[songId] = a }
    }

    func audio(songId: String) -> TrackAudio? { audio[songId] }

    func applied(songId: String?) -> AppliedReplayGain {
        AppliedReplayGain.of(songId.flatMap { audio[$0] }, mode: mode)
    }
}

/// Words for a song's format: "FLAC 24/96", "MP3 320", "Dolby Atmos" (Android quality tags, #121 #139).
enum QualityTag {
    static func label(suffix: String?, bitRateKbps: Int?, audio: TrackAudio?) -> String? {
        let fmt = suffix?.trimmingCharacters(in: .whitespaces).uppercased()
        let channels = audio?.channelCount ?? 0
        if channels > 2 { return channels >= 8 ? "7.1" : channels >= 6 ? "5.1" : "\(channels)ch" }
        guard let fmt, !fmt.isEmpty else { return nil }
        let lossless: Set<String> = ["FLAC", "ALAC", "WAV", "AIFF", "APE", "WV", "DSF", "DFF"]
        if lossless.contains(fmt), let depth = audio?.bitDepth, let rate = audio?.samplingRate {
            let khz = Double(rate) / 1000
            let k = khz == khz.rounded() ? String(Int(khz)) : String(format: "%.1f", khz)
            return "\(fmt) \(depth)/\(k)"
        }
        if !lossless.contains(fmt), let kbps = bitRateKbps, kbps > 0 { return "\(fmt) \(kbps)" }
        return fmt
    }
}
