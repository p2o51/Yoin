import AVFoundation
import Observation
import YoinShared

/// Whether the output in use spatializes multichannel songs now (Android SpatialAudioMonitor, #139), for the
/// "Spatial" tag after Now Playing's format tags. Yoin plays a 5.1 or 7.1 file as the original and lets the
/// system spatialize it (`allowedAudioSpatializationFormats`, Settings › Spatial audio); whether the listener
/// then hears it spatialized is the output's call: AirPods or Beats with Spatial Audio on (fixed or head
/// tracked) do, the speaker and plain headphones fold it down to stereo. iOS says it per output port of the
/// current route, not per channel layout (Android's canBeSpatialized), and not whether the head is tracked;
/// the tag needs neither.
@MainActor
@Observable
final class SpatialAudioMonitor {
    static let shared = SpatialAudioMonitor()

    /// An output of the current route has spatial audio on.
    private(set) var outputSpatializes = false

    private init() {
        refresh()
        // A route change (headphones in or out) and a capability change (Spatial Audio switched for them).
        for name in [AVAudioSession.routeChangeNotification, AVAudioSession.spatialPlaybackCapabilitiesChangedNotification] {
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.refresh() }
            }
        }
    }

    func refresh() {
        #if DEBUG
        // `-spatialDemo YES`: the output spatializes, for screenshots of the tag (the simulator's never does).
        if UserDefaults.standard.bool(forKey: "spatialDemo") { outputSpatializes = true; return }
        #endif
        let now = AVAudioSession.sharedInstance().currentRoute.outputs.contains { $0.isSpatialAudioEnabled }
        if now != outputSpatializes { outputSpatializes = now }
    }

    /// Android spatialPlaybackOf(...) == Spatialized: a song of more than two channels, Yoin's setting on, and
    /// an output that spatializes. Mono, stereo and an unknown layout never are.
    nonisolated static func spatialized(channels: Int?, settingOn: Bool, outputSpatializes: Bool) -> Bool {
        guard let channels, channels > 2 else { return false }
        return settingOn && outputSpatializes
    }

    /// The song's channel count as its server lists it (Subsonic, OpenSubsonic `channelCount`); nil for other
    /// services, which never say.
    static func channels(of track: PlayableTrack) -> Int? {
        #if DEBUG
        if let demo = SongFormat.demo { return demo.channels }
        #endif
        guard track.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return nil }
        return ReplayGainSettings.shared.audio(songId: track.id)?.channelCount
    }

    /// The accent tag Now Playing shows after [track]'s format tags: "Spatial" while it is spatialized
    /// (Android NowPlayingFormatTags, np_format_spatial), else nil.
    func tag(for track: PlayableTrack?) -> String? {
        guard let track, !track.formatTags.isEmpty else { return nil }
        let on = Self.spatialized(channels: Self.channels(of: track),
                                  settingOn: ReplayGainSettings.shared.spatializeMultichannel,
                                  outputSpatializes: outputSpatializes)
        return on ? String(localized: "Spatial") : nil
    }
}
