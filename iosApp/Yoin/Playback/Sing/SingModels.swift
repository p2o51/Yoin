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

/// Failure category for Sing preparation so the UI can show clear, restrained guidance.
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
    /// Haptic detent zone (`10%–12%`) signalling the cleanest artifact-free vocal floor.
    static let detentRange: ClosedRange<Float> = 0.10...0.12
    /// Seamless cut-in ramp duration from `1.0` (original) down to `defaultSlider` (`250 ms`).
    static let cutInRampSeconds: Double = 0.250
    /// Per-sample linear gain smoothing window (`20 ms`) preventing zipper/click noise during slider drags.
    static let smoothingWindowSeconds: Double = 0.020

    /// Perceptual slider-to-linear-gain mapping (`g(0) = 0`, `g(0.12) ≈ 0.12`, `g(1) = 1`, smooth monotonic).
    /// Uses a mild power curve (`p^1.15` blended so `0.12` stays right around `-18 dB` / `0.12` linear,
    /// giving fine control near the bottom without compressing the upper half).
    static func linearGain(forSlider position: Float) -> Float {
        let p = max(0, min(1, position))
        if p <= 0.0001 { return 0 }
        if p >= 0.9999 { return 1 }
        // Piece-wise smooth C1 curve anchored at (0, 0), (0.12, 0.12), (1, 1):
        // linear in [0, 0.12] so 12% slider == exact 0.12 (-18.4 dB) tested in ear-audit clips,
        // gentle perceptual curve above 0.12 so pulling down from 100% feels responsive immediately.
        if p <= defaultSlider {
            return p
        }
        let t = (p - defaultSlider) / (1.0 - defaultSlider)
        return defaultSlider + (1.0 - defaultSlider) * powf(t, 1.35)
    }

    /// True when the slider position sits inside the `10%–12%` haptic sweet-spot detent.
    static func isInDetent(_ position: Float) -> Bool {
        detentRange.contains(position)
    }
}

/// Uniform provider contract mirroring Android's `SingStreamProvider`:
/// both `LyraSingRemoteClient` and `SingCoreMLSeparator` conform to this protocol.
protocol SingStreamProvider: Sendable {
    func prepare(songId: String, localAudioURL: URL?, tier: String?) -> AsyncStream<SingState>
}
