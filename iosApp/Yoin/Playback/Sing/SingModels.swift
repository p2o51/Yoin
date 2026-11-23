import CoreGraphics
import Foundation

/// Source of a 4-channel quad audio stream (`[CH0=I_L, CH1=I_R, CH2=V_L, CH3=V_R]`) where `I + V == original`.
enum SingQuadSource: Equatable, Sendable {
    /// Locally cached 4ch Quad AAC `.m4a` produced either on-device (`Lyra-Sing-Lite`) or cached from Lyra.
    case localFile(URL)
    /// Authenticated HTTP 206 Range `.m4a` URL served by Lyra (`/api/v1/sing/{song_id}/quad.m4a`).
    case remoteM4a(URL)

    var url: URL {
        switch self {
        case .localFile(let url), .remoteM4a(let url): return url
        }
    }
}

/// Processing phase reported while preparing a Sing stream (server SSE or on-device Race-to-Idle).
enum SingStage: String, Equatable, Sendable {
    case queued
    case separating
    case encoding
    case warming
}

/// Failure category for Sing preparation so the UI can handle errors quietly without disrupting playback.
enum SingFailureReason: Equatable, Sendable {
    case unsupportedSource
    case deviceTooSlow(chunkSeconds: Double)
    case thermalThrottled
    case modelDownloadFailed(String)
    case modelChecksumMismatch
    case serverError(String)
    case separationFailed(String)
}

/// Observable lifecycle state of Sing mode for the current song.
enum SingState: Equatable, Sendable {
    /// Commercial DRM source (Spotify / Apple Music) or no active track — hide the Sing button.
    case unavailable
    /// Available, currently off.
    case idle
    /// On-device model has not been downloaded yet (0 MB app-bundle overhead).
    case needsModelDownload
    /// Downloading and verifying the on-demand `LyraSingLite.mlpackage.zip`.
    case downloadingModel(progress: Double)
    /// Preparing stems on Lyra server or on-device.
    case preparing(stage: SingStage, etaSeconds: Int?)
    /// 4-channel quad source ready for real-time mixing.
    case ready(source: SingQuadSource)
    /// Failed; playback of the original track continues uninterrupted.
    case failed(SingFailureReason)
}

/// Per-sample gain interpolation shape used by `SingQuadDownmixer`.
enum SingRampCurve: Equatable, Sendable {
    /// Click-free `20 ms` slider moves.
    case linear
    /// Natural `~0.95 s` Sing cut-in: vocals recede immediately and settle softly into the backing track.
    case easeOutCubic
    /// Smooth `~0.75 s` Sing exit: vocals rise back to `1.0` before restoring the original stereo stream.
    case easeInOut

    @inline(__always)
    func evaluate(_ t: Float) -> Float {
        let u = max(0, min(1, t))
        switch self {
        case .linear:
            return u
        case .easeOutCubic:
            let inv = 1 - u
            return 1 - inv * inv * inv
        case .easeInOut:
            if u < 0.5 {
                return 2 * u * u
            }
            let k = -2 * u + 2
            return 1 - (k * k) * 0.5
        }
    }
}

/// Pinned metadata for the on-demand iOS CoreML `Lyra-Sing-Lite` v1 artifact (FP32 compute, `base_ch=48`).
enum SingModelAsset {
    static let version = "v1"
    static let packageName = "LyraSingLite.mlpackage"
    static let zipFileName = "LyraSingLite.mlpackage.zip"
    /// Deterministic zip SHA-256 verified against `lyra_sing_lite_best.pt` (`SNR=134.05 dB`, `320.7x RT` on Apple Silicon).
    static let zipSha256 = "32c0c0d143f219e3bfdf2de474186219716672f774adb4cbfa3a30be590c4aae"
    static let expectedZipBytes: Int64 = 7_990_000
}

/// Maps the UI vocal slider (`0.0 ... 1.0`, default `0.12`) to linear vocal gain `g ∈ [0.0, 1.0]`
/// with perceptual shaping and the Apple Music Sing `10%–12%` haptic sweet-spot detent.
enum SingGainCurve {
    /// Default slider position on enabling Sing (`12%` ≈ `-18 dB` natural artifact-masking sweet spot).
    static let defaultSlider: Float = 0.12
    /// Haptic detent zone (`10%–13.5%`, snapping cleanly to `12%`) signalling the cleanest vocal floor.
    static let detentRange: ClosedRange<Float> = 0.10...0.135
    /// Seamless cut-in ease-out ramp duration from `1.0` (original) down to target (`0.95 s`).
    static let cutInRampSeconds: Double = 0.95
    /// Seamless cut-out ease-in-out ramp duration from current gain back to `1.0` (`0.75 s`).
    static let cutOutRampSeconds: Double = 0.75
    /// Per-sample linear gain smoothing window (`20 ms`) preventing zipper/click noise during slider drags.
    static let smoothingWindowSeconds: Double = 0.020
    /// Minimum position epsilon considered "at 0%" for the two-step anti-accidental-dismiss gesture.
    static let minimumEpsilon: Float = 0.005

    /// Perceptual slider-to-linear-gain mapping (`g(0) = 0`, `g(0.12) ≈ 0.12`, `g(1) = 1`, smooth monotonic).
    static func linearGain(forSlider position: Float) -> Float {
        let p = max(0, min(1, position))
        if p <= 0.0001 { return 0 }
        if p >= 0.9999 { return 1 }
        if p <= defaultSlider {
            return p
        }
        let t = (p - defaultSlider) / (1.0 - defaultSlider)
        return defaultSlider + (1.0 - defaultSlider) * powf(t, 1.35)
    }

    /// True when the slider position sits inside the `10%–13.5%` haptic sweet-spot detent.
    static func isInDetent(_ position: Float) -> Bool {
        detentRange.contains(position)
    }

    /// Snaps `position` into `defaultSlider` (`0.12`) when it falls inside `detentRange`.
    static func snap(_ position: Float) -> Float {
        let clamped = max(0, min(1, position))
        return detentRange.contains(clamped) ? defaultSlider : clamped
    }

    /// True when the slider is parked at the minimum (`0%`) end.
    static func isAtMinimum(_ position: Float) -> Bool {
        position <= minimumEpsilon
    }
}

/// Pure, testable gesture state machine implementing the two-step anti-accidental-dismiss left-drag rule:
/// 1. Dragging all the way to the left (`0%`) in a single continuous gesture stops at minimum and **never** exits Sing,
///    regardless of how far past the left edge the finger travels.
/// 2. Only when vocal is **already at `0%`**, the user lifts their finger, and starts a **new** press + drag left
///    past `dismissThresholdPt` does Sing turn off.
struct SingCapsuleDismissGesture: Equatable, Sendable {
    enum StepResult: Equatable, Sendable {
        /// Update vocal slider level (`0...1`); `overpullProgress` (`0...1`) drives elastic pull feedback
        /// during the deliberate second-drag exit.
        case update(level: Float, overpullProgress: CGFloat)
        /// Second-drag threshold crossed from `0%` — exit Sing now.
        case dismiss
    }

    private(set) var isActive = false
    private(set) var startedAtMinimum = false
    private(set) var startLevel: Float = SingGainCurve.defaultSlider
    private(set) var dismissed = false

    let dismissThresholdPt: CGFloat

    init(dismissThresholdPt: CGFloat = 28) {
        self.dismissThresholdPt = max(12, dismissThresholdPt)
    }

    mutating func begin(currentLevel: Float) {
        isActive = true
        startedAtMinimum = SingGainCurve.isAtMinimum(currentLevel)
        startLevel = max(0, min(1, currentLevel))
        dismissed = false
    }

    /// Evaluates one drag update given horizontal translation `translationX` (negative = dragging left)
    /// and usable slider travel width `trackWidth`.
    mutating func drag(translationX: CGFloat, trackWidth: CGFloat) -> StepResult {
        if !isActive {
            begin(currentLevel: startLevel)
        }
        if dismissed {
            return .dismiss
        }
        // Only a brand-new gesture that STARTED while already at 0% can exit Sing on a leftward pull.
        if startedAtMinimum && translationX < 0 {
            let pull = -translationX
            if pull >= dismissThresholdPt {
                dismissed = true
                return .dismiss
            }
            let progress = min(1, max(0, pull / dismissThresholdPt))
            return .update(level: 0, overpullProgress: progress)
        }

        // Standard level drag (or pulling right from 0%): clamps cleanly to [0, 1] and NEVER dismisses.
        let width = max(1, trackWidth)
        let raw = startLevel + Float(translationX / width)
        let clamped = max(0, min(1, raw))
        return .update(level: SingGainCurve.snap(clamped), overpullProgress: 0)
    }

    mutating func end() {
        isActive = false
        startedAtMinimum = false
        dismissed = false
    }
}

/// Uniform provider contract mirroring Android's `SingStreamProvider`:
/// both `LyraSingRemoteClient` and `SingCoreMLSeparator` conform to this protocol.
protocol SingStreamProvider: Sendable {
    func prepare(songId: String, localAudioURL: URL?, tier: String?) -> AsyncStream<SingState>
}
