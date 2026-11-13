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

/// How a song's audio is encoded, as far as the server says (Android data/model/AudioFormat). Every field is
/// optional: a plain Subsonic server sends only the suffix and the bit rate, OpenSubsonic adds depth, rate and
/// channels. Spotify and Apple Music never say. `codec` is a lower-case name ("flac", "mp3", "opus", "aac",
/// "alac", "eac3-joc" for Dolby Atmos in E-AC-3).
struct SongFormat: Hashable, Sendable {
    var codec: String?
    var bitRateKbps: Int? = nil
    var bitDepth: Int? = nil
    var sampleRateHz: Int? = nil
    var channels: Int? = nil

    static let eac3Joc = "eac3-joc"
    private static let lossless: Set<String> = ["flac", "alac", "wav", "aiff", "ape", "wavpack", "dsd"]
    private static let mp4Suffixes: Set<String> = ["m4a", "mp4", "m4b"]

    var isLossless: Bool { codec.map(Self.lossless.contains) ?? false }

    /// E-AC-3 with joint object coding: Dolby Atmos. Its channel count is the base layer's, not the content's
    /// layout, so it gets no channel tag.
    var isDolbyAtmos: Bool { codec == Self.eac3Joc }

    /// The codec a file's suffix holds; a bit depth tells an ALAC `.m4a` from an AAC one (only lossless files
    /// have a depth). Nil when there is no suffix.
    static func codec(suffix: String?, bitDepth: Int? = nil) -> String? {
        guard let s = suffix?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !s.isEmpty else { return nil }
        switch s {
        case "flac", "fla": return "flac"
        case "ogg", "oga": return "vorbis"
        case "aac", "adts": return "aac"
        case "m4a", "mp4", "m4b": return (bitDepth ?? 0) > 0 ? "alac" : "aac"
        case "wav", "wave": return "wav"
        case "aif", "aiff", "aifc": return "aiff"
        case "wv": return "wavpack"
        case "dsf", "dff": return "dsd"
        default: return s
        }
    }

    /// The codec a MIME type names ("audio/flac" → "flac"), for a song the server lists without a suffix.
    static func codec(mime: String?) -> String? {
        switch mime?.lowercased() {
        case "audio/flac": "flac"
        case "audio/mpeg", "audio/mpeg-l2", "audio/mpeg-l1": "mp3"
        case "audio/opus": "opus"
        case "audio/vorbis": "vorbis"
        case "audio/mp4a-latm", "audio/aac": "aac"
        case "audio/alac": "alac"
        case "audio/raw", "audio/wav": "wav"
        case "audio/ac3": "ac3"
        case "audio/eac3": "eac3"
        case "audio/eac3-joc": eac3Joc
        // AC-4 may carry Atmos too, but the MIME type doesn't say: plain AC-4.
        case "audio/ac4": "ac4"
        default: nil
        }
    }

    /// What a Subsonic server says about the file (Android subsonicAudioFormat); nil when it says nothing
    /// usable. An MP4 the server can't read (Navidrome and E-AC-3) comes with the bit rate, depth, rate and
    /// channels all 0: that is no AAC, so it gets no tag.
    static func subsonic(suffix: String?, bitRateKbps: Int?, audio: TrackAudio?) -> SongFormat? {
        let known = [bitRateKbps, audio?.bitDepth, audio?.samplingRate, audio?.channelCount].map { $0 ?? 0 }
        let s = suffix?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let s, mp4Suffixes.contains(s), known.allSatisfy({ $0 <= 0 }) { return nil }
        func positive(_ v: Int?) -> Int? { v.flatMap { $0 > 0 ? $0 : nil } }
        let depth = positive(audio?.bitDepth)
        let f = SongFormat(codec: codec(suffix: suffix, bitDepth: depth), bitRateKbps: positive(bitRateKbps),
                           bitDepth: depth, sampleRateHz: positive(audio?.samplingRate),
                           channels: positive(audio?.channelCount))
        return f.codec != nil || f.bitRateKbps != nil ? f : nil
    }
}

/// A song's format as tags (Android ui/component/AudioFormatTags.kt, #121 #139): short labels, each its own
/// tag, never joined: "FLAC 24/96", "MP3 320", "5.1". Codec names and numbers read the same in every
/// language, so the tags are not translated.
enum FormatTags {
    /// How a codec is written on a tag: "flac" → "FLAC", "opus" → "Opus". E-AC-3 JOC is "Dolby Atmos", in
    /// words only: the logo needs a licence, and the trademark reads in English in every language.
    static func codecLabel(_ codec: String) -> String {
        switch codec.lowercased() {
        case "opus": "Opus"
        case "vorbis": "Vorbis"
        case "wavpack": "WavPack"
        case "ac3": "AC-3"
        case "eac3": "E-AC-3"
        case "ac4": "AC-4"
        case SongFormat.eac3Joc: "Dolby Atmos"
        default: codec.uppercased()
        }
    }

    /// The channel layout a count usually is: 6 → "5.1". Nil for mono and stereo.
    static func channelLayoutLabel(_ channels: Int?) -> String? {
        switch channels {
        case nil, 0, 1, 2: nil
        case 3: "2.1"
        case 4: "4.0"
        case 5: "5.0"
        case 6: "5.1"
        case 7: "6.1"
        case 8: "7.1"
        case let n?: "\(n)ch"
        }
    }

    /// 44100 → "44.1", 96000 → "96".
    static func khzLabel(_ hz: Int) -> String {
        let tenths = Int((Double(hz) / 100).rounded())
        return tenths % 10 == 0 ? String(tenths / 10) : "\(tenths / 10).\(tenths % 10)"
    }

    /// The codec tag: lossless as depth/kHz ("FLAC 24/96", "FLAC 44.1" without a depth), lossy as its bit
    /// rate ("MP3 320"), else the codec alone. Nil when the format names no codec.
    static func codecTag(_ f: SongFormat) -> String? {
        guard let codec = f.codec.map(codecLabel) else { return nil }
        if f.isDolbyAtmos { return codec }
        if f.isLossless {
            let khz = f.sampleRateHz.map(khzLabel)
            if let depth = f.bitDepth, let khz { return "\(codec) \(depth)/\(khz)" }
            if let khz { return "\(codec) \(khz)" }
            return codec
        }
        return f.bitRateKbps.map { "\(codec) \($0)" } ?? codec
    }

    /// Every tag of a format, codec first: ["FLAC 16/44.1", "5.1"]; Dolby Atmos alone.
    static func tags(_ f: SongFormat?) -> [String] {
        guard let f else { return [] }
        let layout = f.isDolbyAtmos ? nil : channelLayoutLabel(f.channels)
        return [codecTag(f), layout].compactMap { $0 }
    }
}

#if DEBUG
extension SongFormat {
    /// `-formatDemo flac,0,16,44100,6` (suffix, kbps, depth, Hz, channels; 0 = the server doesn't say): every
    /// song claims this format, for screenshots of the format tags.
    static let demo: SongFormat? = {
        guard let v = UserDefaults.standard.string(forKey: "formatDemo") else { return nil }
        let p = v.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
        func n(_ i: Int) -> Int? { p.indices.contains(i) ? Int(p[i]) : nil }
        return subsonic(suffix: p.first, bitRateKbps: n(1),
                        audio: TrackAudio(samplingRate: n(3), bitDepth: n(2), channelCount: n(4)))
    }()
}
#endif
