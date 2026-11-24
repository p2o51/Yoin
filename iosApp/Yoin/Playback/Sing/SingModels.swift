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
/// Do NOT re-export as CoreML FP16: empirical testing showed FP16 collapses `voc_SNR` (131.5 dB → 18.7 dB)
/// and breaks operator fusion (4× slower). Keep the 8.3 MB FP32 `.mlpackage` delivered via Apple ODR.
enum SingModelAsset {
    static let version = "v1"
    /// Apple On-Demand Resources tag (Download Only On Demand — 0 MB in main IPA).
    static let odrTag = "sing_model_v1"
    /// High preservation priority so iOS keeps the 8.3 MB compiled model cached across songs.
    static let odrPreservationPriority: Double = 0.8
    static let compiledResourceName = "LyraSingLite"
    static let compiledResourceExtension = "mlmodelc"
    static let packageName = "LyraSingLite.mlpackage"
    static let zipFileName = "LyraSingLite.mlpackage.zip"
    /// Deterministic zip SHA-256 verified against `lyra_sing_lite_best.pt` (`SNR=137.97 dB`, `8.58 ms/chunk` on Apple Silicon).
    static let zipSha256 = "32c0c0d143f219e3bfdf2de474186219716672f774adb4cbfa3a30be590c4aae"
    static let expectedZipBytes: Int64 = 7_990_000
}

/// Maps the UI vocal slider (`0.0 ... 1.0`) to linear vocal gain `g ∈ [0.0, 1.0]`
/// with perceptual dB shaping (`RANGE_DB = 28.0`, matching Android's `SingGainCurve`) so equal slider
/// travel yields equal loudness steps, the `12%` (`-18.4 dB`) default lands around `1/3` (`~34%`) of
/// the slider, and the bottom `5%` knee fades smoothly to true silence (`g = 0`).
enum SingGainCurve {
    /// Perceptual dynamic range above the silent knee (`28 dB` → default `0.12` gain sits at `~34.2%`).
    static let rangeDb: Double = 28.0
    /// Below this slider position, gain falls linearly from `dbCurve(knee)` to `0.0`.
    static let knee: Double = 0.05
    /// Default vocal gain on enabling Sing (`12%` ≈ `-18.4 dB` natural artifact-masking sweet spot).
    static let defaultGain: Float = 0.12
    /// Gains inside `0.10...0.135` snap cleanly to `defaultGain` with a light haptic detent.
    static let snapLowGain: Float = 0.10
    static let snapHighGain: Float = 0.135
    /// Default slider position corresponding to `defaultGain` (`~0.342`).
    static let defaultSlider: Float = position(forGain: defaultGain)
    /// Seamless cut-in ease-out ramp duration from `1.0` (original) down to target (`0.95 s`).
    static let cutInRampSeconds: Double = 0.95
    /// Seamless cut-out ease-in-out ramp duration from current gain back to `1.0` (`0.75 s`).
    static let cutOutRampSeconds: Double = 0.75
    /// Per-sample linear gain smoothing window (`20 ms`) preventing zipper/click noise during slider drags.
    static let smoothingWindowSeconds: Double = 0.020
    /// Minimum position epsilon considered "at 0%".
    static let minimumEpsilon: Float = 0.005
    /// Maximum position epsilon considered "at 100%" (original vocals) for exit reset.
    static let maximumEpsilon: Float = 0.005

    private static var kneeGain: Double { dbCurve(knee) }

    private static func dbCurve(_ p: Double) -> Double {
        pow(10.0, -rangeDb * (1.0 - p) / 20.0)
    }

    /// Perceptual slider-to-linear-gain mapping (`g(0) = 0`, `g(defaultSlider) ≈ 0.12`, `g(1) = 1`).
    static func linearGain(forSlider position: Float) -> Float {
        let p = Double(max(0, min(1, position)))
        if p <= 0.0001 { return 0 }
        if p >= 0.9999 { return 1 }
        let g = p >= knee ? dbCurve(p) : kneeGain * (p / knee)
        return Float(g)
    }

    /// Inverse mapping from linear vocal gain `g ∈ [0, 1]` back to slider position `p ∈ [0, 1]`.
    static func position(forGain gain: Float) -> Float {
        let g = Double(max(0, min(1, gain)))
        if g <= 0.0 { return 0 }
        if g >= 1.0 { return 1 }
        let p = g >= kneeGain ? 1.0 + 20.0 * log10(g) / rangeDb : (g / kneeGain) * knee
        return Float(max(0, min(1, p)))
    }

    /// True when the slider's mapped gain sits inside the `10%–13.5%` haptic sweet-spot detent.
    static func isInDetent(_ position: Float) -> Bool {
        let g = linearGain(forSlider: position)
        return g >= snapLowGain && g <= snapHighGain
    }

    /// Snaps `position` into `defaultSlider` when its gain falls inside the sweet-spot detent.
    static func snap(_ position: Float) -> Float {
        let clamped = max(0, min(1, position))
        return isInDetent(clamped) ? defaultSlider : clamped
    }

    /// True when the slider is parked at the minimum (`0%`) end.
    static func isAtMinimum(_ position: Float) -> Bool {
        position <= minimumEpsilon
    }

    /// True when the slider is parked at the maximum (`100%`, original vocals) end.
    static func isAtMaximum(_ position: Float) -> Bool {
        position >= 1.0 - maximumEpsilon
    }
}

/// Pure, testable gesture state machine implementing the single-gesture high-end overpull exit rule:
/// 1. Dragging down/left to `0%` (pure instrumental) clamps cleanly at `0` and **never** exits Sing.
/// 2. Dragging up/right to `100%` (original vocals) and continuing past the `100%` end by
///    `dismissThresholdPt` in a single continuous gesture provides elastic overpull feedback and
///    exits Sing directly without needing to lift and re-drag.
struct SingCapsuleDismissGesture: Equatable, Sendable {
    enum StepResult: Equatable, Sendable {
        /// Update vocal slider level (`0...1`); `overpullProgress` (`0...1`) drives elastic pull feedback
        /// past the `100%` end before exiting.
        case update(level: Float, overpullProgress: CGFloat)
        /// Overpull threshold crossed past `100%` — exit Sing now.
        case dismiss
    }

    private(set) var isActive = false
    private(set) var startLevel: Float = SingGainCurve.defaultSlider
    private(set) var dismissed = false

    let dismissThresholdPt: CGFloat

    init(dismissThresholdPt: CGFloat = 28) {
        self.dismissThresholdPt = max(12, dismissThresholdPt)
    }

    mutating func begin(currentLevel: Float) {
        isActive = true
        startLevel = max(0, min(1, currentLevel))
        dismissed = false
    }

    /// Evaluates one relative drag update given positive-toward-max `deltaPt`
    /// (`+translationX` on horizontal capsule, `-translationY` on vertical slider) and usable `trackLength`.
    mutating func drag(deltaPt: CGFloat, trackLength: CGFloat) -> StepResult {
        if !isActive {
            begin(currentLevel: startLevel)
        }
        if dismissed {
            return .dismiss
        }
        let width = max(1, trackLength)
        let travelToMax = CGFloat(1.0 - startLevel) * width
        if deltaPt > travelToMax {
            let overshoot = deltaPt - travelToMax
            if overshoot >= dismissThresholdPt {
                dismissed = true
                return .dismiss
            }
            let progress = min(1, max(0, overshoot / dismissThresholdPt))
            return .update(level: 1.0, overpullProgress: progress)
        }

        // Standard scrub (including dragging arbitrarily far past 0%): clamps cleanly to [0, 1] and never dismisses.
        let raw = startLevel + Float(deltaPt / width)
        let clamped = max(0, min(1, raw))
        return .update(level: SingGainCurve.snap(clamped), overpullProgress: 0)
    }

    /// Evaluates an absolute vertical touch position (`y == 0` is 100%, `y == trackHeight` is 0%,
    /// `y < 0` is overpull past the top 100% end).
    mutating func dragVerticalAbsolute(locationY: CGFloat, trackHeight: CGFloat) -> StepResult {
        if !isActive {
            begin(currentLevel: startLevel)
        }
        if dismissed {
            return .dismiss
        }
        let h = max(1, trackHeight)
        if locationY < 0 {
            let overshoot = -locationY
            if overshoot >= dismissThresholdPt {
                dismissed = true
                return .dismiss
            }
            let progress = min(1, max(0, overshoot / dismissThresholdPt))
            return .update(level: 1.0, overpullProgress: progress)
        }
        let raw = Float(1.0 - locationY / h)
        let clamped = max(0, min(1, raw))
        return .update(level: SingGainCurve.snap(clamped), overpullProgress: 0)
    }

    mutating func end() {
        isActive = false
        dismissed = false
    }
}

/// Uniform provider contract mirroring Android's `SingStreamProvider`:
/// both `LyraSingRemoteClient` and `SingCoreMLSeparator` conform to this protocol.
protocol SingStreamProvider: Sendable {
    func prepare(songId: String, localAudioURL: URL?, tier: String?) -> AsyncStream<SingState>
}
