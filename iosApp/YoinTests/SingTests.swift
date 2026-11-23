import AVFoundation
import XCTest
@testable import Yoin

final class SingTests: XCTestCase {

    // MARK: - 1. STFT / iSTFT bit-exact parity with infer_and_eval.py (< 1e-4)

    func test_should_matchPythonInferAndEvalStftAndIstft_within1e4() {
        let n = 4096
        let sr = 22_050.0
        let sig: [Float] = (0..<n).map { i in
            let t = Double(i) / sr
            return Float(0.5 * sin(2.0 * Double.pi * 440.0 * t) + 0.25 * cos(2.0 * Double.pi * 1760.0 * t))
        }

        let spec = SingDSP.stft(sig)
        XCTAssertEqual(spec.binCount, 513)
        XCTAssertEqual(spec.frameCount, 17)

        // Golden complex bins generated directly by torch.stft(n_fft=1024, hop=256, center=True, pad_mode="reflect", normalized=False).
        let golden: [(Int, Int, Float, Float)] = [
            (0, 0, 7.976996, 0.0),
            (0, 4, -0.003803, 0.0),
            (20, 4, 45.901787, 103.494751),
            (21, 4, -42.008430, -94.714455),
            (82, 7, 59.671608, 13.306963),
            (512, 5, 0.000001, 0.0),
            (20, 16, -99.211082, -12.615911),
        ]
        for (bin, frame, expRe, expIm) in golden {
            let idx = bin * spec.frameCount + frame
            XCTAssertEqual(spec.real[idx], expRe, accuracy: 1e-4, "real mismatch at (\(bin), \(frame))")
            XCTAssertEqual(spec.imag[idx], expIm, accuracy: 1e-4, "imag mismatch at (\(bin), \(frame))")
        }

        let reconstructed = SingDSP.istft(spec, targetLength: n)
        XCTAssertEqual(reconstructed.count, n)
        var maxErr: Float = 0
        for i in 0..<n {
            maxErr = max(maxErr, abs(reconstructed[i] - sig[i]))
        }
        XCTAssertLessThan(maxErr, 1e-4, "iSTFT roundtrip error \(maxErr) exceeded 1e-4")
    }

    // MARK: - 2. 4-channel downmixer & 20 ms gain ramp

    func test_should_mixQuadChannelsExactly_and_smoothGainOver20ms() {
        let mixer = SingQuadDownmixer(initialGain: 0.0, sampleRate: 44_100)
        let iL: [Float] = [0.1, 0.1, 0.1, 0.1]
        let iR: [Float] = [0.2, 0.2, 0.2, 0.2]
        let vL: [Float] = [0.3, 0.3, 0.3, 0.3]
        let vR: [Float] = [0.4, 0.4, 0.4, 0.4]
        var outL = [Float](repeating: 0, count: 4)
        var outR = [Float](repeating: 0, count: 4)

        // 0% vocals -> pure instrumental
        mixer.processNonInterleaved(frameCount: 4, iL: iL, iR: iR, vL: vL, vR: vR, outL: &outL, outR: &outR)
        XCTAssertEqual(outL[0], 0.1, accuracy: 1e-6)
        XCTAssertEqual(outR[0], 0.2, accuracy: 1e-6)

        // 12% default sweet spot -> I + 0.12 * V
        mixer.setVocalGain(0.12, rampSeconds: 0)
        mixer.processNonInterleaved(frameCount: 4, iL: iL, iR: iR, vL: vL, vR: vR, outL: &outL, outR: &outR)
        XCTAssertEqual(outL[0], 0.136, accuracy: 1e-6)
        XCTAssertEqual(outR[0], 0.248, accuracy: 1e-6)

        // 100% vocals -> I + V == original
        mixer.setVocalGain(1.0, rampSeconds: 0)
        mixer.processNonInterleaved(frameCount: 4, iL: iL, iR: iR, vL: vL, vR: vR, outL: &outL, outR: &outR)
        XCTAssertEqual(outL[0], 0.4, accuracy: 1e-6)
        XCTAssertEqual(outR[0], 0.6, accuracy: 1e-6)

        // 20 ms ramp at 44.1 kHz (= 882 samples) from 1.0 -> 0.12 must be monotonic & click-free
        let rampFrames = 1000
        let ones = [Float](repeating: 1.0, count: rampFrames)
        let zeros = [Float](repeating: 0.0, count: rampFrames)
        var rampOutL = [Float](repeating: 0, count: rampFrames)
        var rampOutR = [Float](repeating: 0, count: rampFrames)
        mixer.setVocalGain(0.12, rampSeconds: 0.020)
        mixer.processNonInterleaved(
            frameCount: rampFrames,
            iL: zeros, iR: zeros, vL: ones, vR: ones,
            outL: &rampOutL, outR: &rampOutR
        )
        XCTAssertEqual(rampOutL[0], 1.0, accuracy: 0.005)
        XCTAssertEqual(rampOutL[441], 0.56, accuracy: 0.01)
        XCTAssertEqual(rampOutL[900], 0.12, accuracy: 1e-5)
        for i in 1..<rampFrames {
            XCTAssertLessThanOrEqual(rampOutL[i], rampOutL[i - 1] + 1e-6)
        }
    }

    // MARK: - 3. 4-channel Quad AAC (.m4a) write & readback channel order fidelity

    func test_should_roundtrip4ChannelQuadM4A_preservingChannelOrder() async throws {
        let sr = 44_100.0
        let frames = 44_100
        func tone(freq: Double, amp: Float) -> [Float] {
            (0..<frames).map { i in Float(sin(2.0 * Double.pi * freq * Double(i) / sr)) * amp }
        }
        let instL = tone(freq: 440, amp: 0.10)
        let instR = tone(freq: 660, amp: 0.20)
        let vocL = tone(freq: 880, amp: 0.30)
        let vocR = tone(freq: 1100, amp: 0.40)

        let url = FileManager.default.temporaryDirectory.appendingPathComponent("sing-quad-test-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: url) }

        try await SingQuadM4AWriter.write(
            instL: instL, instR: instR, vocL: vocL, vocR: vocR, sampleRate: sr, to: url
        )

        let file = try AVAudioFile(forReading: url)
        XCTAssertEqual(file.processingFormat.channelCount, 4)
        let buf = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)))
        try file.read(into: buf)
        let chData = try XCTUnwrap(buf.floatChannelData)
        let start = 4096
        let end = Int(buf.frameLength) - 4096
        var peaks: [Float] = []
        for ch in 0..<4 {
            var peak: Float = 0
            for i in start..<end { peak = max(peak, abs(chData[ch][i])) }
            peaks.append(peak)
        }
        XCTAssertEqual(peaks[0], 0.10, accuracy: 0.04, "CH0 (I_L) mismatch")
        XCTAssertEqual(peaks[1], 0.20, accuracy: 0.04, "CH1 (I_R) mismatch")
        XCTAssertEqual(peaks[2], 0.30, accuracy: 0.05, "CH2 (V_L) mismatch")
        XCTAssertEqual(peaks[3], 0.40, accuracy: 0.06, "CH3 (V_R) mismatch")
    }

    // MARK: - 4. Gain curve & sweet-spot detent

    func test_should_mapGainCurve_and_detectSweetSpotDetent() {
        XCTAssertEqual(SingGainCurve.linearGain(forSlider: 0.0), 0.0, accuracy: 1e-6)
        XCTAssertEqual(SingGainCurve.linearGain(forSlider: 0.12), 0.12, accuracy: 1e-6)
        XCTAssertEqual(SingGainCurve.linearGain(forSlider: 1.0), 1.0, accuracy: 1e-6)
        XCTAssertTrue(SingGainCurve.isInDetent(0.10))
        XCTAssertTrue(SingGainCurve.isInDetent(0.12))
        XCTAssertFalse(SingGainCurve.isInDetent(0.0))
        XCTAssertFalse(SingGainCurve.isInDetent(0.5))

        var prev: Float = -1
        for step in 0...100 {
            let g = SingGainCurve.linearGain(forSlider: Float(step) / 100.0)
            XCTAssertGreaterThanOrEqual(g, prev)
            prev = g
        }
    }

    // MARK: - 5. Source routing priority & Lyra auth URLs

    func test_should_enforceSourceRoutingPriority_and_fallbackOfflineToLocal() async {
        XCTAssertFalse(SingCoordinator.supportsSing(origin: .commercialDRM))
        XCTAssertTrue(SingCoordinator.supportsSing(origin: .navidrome(songId: "s1", localAudioURL: nil)))

        let remoteURL = URL(string: "https://lyra.example/api/v1/sing/s1/quad.m4a")!
        let localURL = URL(fileURLWithPath: "/tmp/local-s1.m4a")

        struct FakeProvider: SingStreamProvider {
            let states: [SingState]
            func prepare(songId: String, localAudioURL: URL?, tier: String?) -> AsyncStream<SingState> {
                AsyncStream { c in
                    for s in states { c.yield(s) }
                    c.finish()
                }
            }
        }

        // Commercial DRM -> unavailable
        let drmCoord = SingCoordinator(
            remoteProvider: FakeProvider(states: [.ready(source: .remoteM4a(remoteURL))]),
            localProvider: FakeProvider(states: [.ready(source: .localFile(localURL))])
        )
        var drmStates: [SingState] = []
        for await s in drmCoord.prepare(origin: .commercialDRM) { drmStates.append(s) }
        XCTAssertEqual(drmStates, [.unavailable])

        // Lyra online fails -> seamlessly falls back to local on-device provider
        let fallbackCoord = SingCoordinator(
            remoteProvider: FakeProvider(states: [.failed(.serverError("offline"))]),
            localProvider: FakeProvider(states: [.preparing(stage: .separating, etaSeconds: 5), .ready(source: .localFile(localURL))])
        )
        var fbStates: [SingState] = []
        for await s in fallbackCoord.prepare(origin: .lyra(songId: "s1", localAudioURL: localURL, serverOnline: true)) {
            fbStates.append(s)
        }
        XCTAssertEqual(fbStates.last, .ready(source: .localFile(localURL)))

        // Authenticated quad.m4a URL carries Subsonic u/t/s query parameters
        let cfg = LyraSingServerConfig(baseURL: URL(string: "https://lyra.local:4533")!, username: "51", password: "secret")
        let client = LyraSingRemoteClient(config: cfg)
        let streamURL = client.quadStreamURL(songId: "lt_42")
        let comps = streamURL.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }
        XCTAssertEqual(comps?.path, "/api/v1/sing/lt_42/quad.m4a")
        let names = Set((comps?.queryItems ?? []).map(\.name))
        XCTAssertTrue(names.isSuperset(of: ["u", "t", "s", "v", "c", "f"]))
    }

    // MARK: - 6. LRU stem cache eviction

    func test_should_evictOldestStemFromLruCache_when_overLimit() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sing-cache-test-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let cache = SingStemCache(directory: dir, maxBytes: 1500)

        func makeTemp(_ bytes: Int) throws -> URL {
            let u = FileManager.default.temporaryDirectory.appendingPathComponent("tmp-\(UUID().uuidString).m4a")
            try Data(repeating: 7, count: bytes).write(to: u)
            return u
        }

        _ = try await cache.store(tempFile: makeTemp(900), forKey: "song-old")
        try await Task.sleep(for: .milliseconds(25))
        _ = try await cache.store(tempFile: makeTemp(900), forKey: "song-new")

        let hitOld = await cache.hit(forKey: "song-old")
        let hitNew = await cache.hit(forKey: "song-new")
        XCTAssertNil(hitOld, "Oldest entry should have been evicted past 1500B limit")
        XCTAssertNotNil(hitNew, "Newest entry should remain cached")
    }

    // MARK: - 7. Ease-out cut-in (~0.95s) & ease-in-out cut-out (~0.75s) vocal curves

    func test_should_applyEaseOutCutIn_and_easeInOutCutOut_sampleBySample() {
        let sr = 8000.0 // 8 kHz (minimum downmixer rate) so 8000 samples == 1.0 s
        let mixer = SingQuadDownmixer(initialGain: 1.0, sampleRate: sr)
        let frames = 8000
        let zeros = [Float](repeating: 0, count: frames)
        let ones = [Float](repeating: 1, count: frames)
        var outL = [Float](repeating: 0, count: frames)
        var outR = [Float](repeating: 0, count: frames)

        // Ease-out cubic over 1.0s from 1.0 -> 0.12: at 50% (sample 3999), progress = 1 - 0.5^3 = 0.875
        // Expected gain at 50% = 1.0 + (0.12 - 1.0) * 0.875 = 0.23
        mixer.setVocalGain(0.12, rampSeconds: 1.0, curve: .easeOutCubic)
        mixer.processNonInterleaved(frameCount: frames, iL: zeros, iR: zeros, vL: ones, vR: ones, outL: &outL, outR: &outR)
        XCTAssertEqual(outL[0], 1.0, accuracy: 0.01)
        XCTAssertEqual(outL[3999], 0.23, accuracy: 0.015, "Ease-out cubic should drop faster early and settle softly")
        XCTAssertEqual(outL[7999], 0.12, accuracy: 1e-5)
        for i in 1..<frames {
            XCTAssertLessThanOrEqual(outL[i], outL[i - 1] + 1e-6, "Ease-out cut-in must be monotonically decreasing")
        }

        // Ease-in-out exit over 0.8s (6400 samples) from 0.12 -> 1.0: at 50% (sample 3199), gain == 0.56
        mixer.setVocalGain(1.0, rampSeconds: 0.8, curve: .easeInOut)
        mixer.processNonInterleaved(frameCount: frames, iL: zeros, iR: zeros, vL: ones, vR: ones, outL: &outL, outR: &outR)
        XCTAssertEqual(outL[0], 0.12, accuracy: 0.01)
        XCTAssertEqual(outL[3199], 0.56, accuracy: 0.015)
        XCTAssertEqual(outL[6399], 1.0, accuracy: 1e-5)
        XCTAssertEqual(outL[7999], 1.0, accuracy: 1e-5)
        for i in 1..<6400 {
            XCTAssertGreaterThanOrEqual(outL[i], outL[i - 1] - 1e-6, "Ease-in-out exit must be monotonically rising")
        }
    }

    // MARK: - 8. Two-step anti-accidental-dismiss left-drag rule

    func test_should_requireReleaseAtZeroBeforeLeftDragDismissesSing() {
        var gesture = SingCapsuleDismissGesture(dismissThresholdPt: 28)

        // Step 1: Start from 0.40 and drag way past the left edge (-400pt) in ONE continuous gesture -> NEVER dismiss!
        gesture.begin(currentLevel: 0.40)
        let r1 = gesture.drag(translationX: -400, trackWidth: 108)
        XCTAssertEqual(r1, .update(level: 0, overpullProgress: 0))
        let r2 = gesture.drag(translationX: -800, trackWidth: 108)
        XCTAssertEqual(r2, .update(level: 0, overpullProgress: 0))
        gesture.end()

        // Step 2a: Now parked at 0%, lift finger and start a NEW gesture dragging right -> raises vocal, no dismiss.
        gesture.begin(currentLevel: 0.0)
        let rRight = gesture.drag(translationX: 54, trackWidth: 108)
        if case .update(let level, let pull) = rRight {
            XCTAssertEqual(level, 0.5, accuracy: 0.02)
            XCTAssertEqual(pull, 0)
        } else {
            XCTFail("Dragging right from 0% should raise vocal level, not dismiss")
        }
        gesture.end()

        // Step 2b: Parked at 0%, lift finger and start a NEW gesture dragging left:
        // below threshold -> elastic overpull feedback; at/past threshold -> dismiss!
        gesture.begin(currentLevel: 0.0)
        let rPartial = gesture.drag(translationX: -14, trackWidth: 108)
        XCTAssertEqual(rPartial, .update(level: 0, overpullProgress: 0.5))
        let rDismiss = gesture.drag(translationX: -30, trackWidth: 108)
        XCTAssertEqual(rDismiss, .dismiss)
        gesture.end()
    }

    // MARK: - 9. Session controller DRM gating & cover-menu lyrics expansion

    @MainActor
    func test_should_hideSingForCommercialDRM_and_expandLyricsFromCoverMenu() {
        let controller = SingSessionController()
        let spotifyTrack = PlayableTrack(
            id: "sp1", provider: SpotifyMapping.provider, title: "T", artist: "A", album: "Al",
            durationSec: 180, streamURL: URL(string: "https://open.spotify.com/track/sp1")!
        )
        let appleTrack = PlayableTrack(
            id: "am1", provider: AppleMusicMapping.provider, title: "T", artist: "A", album: "Al",
            durationSec: 180, streamURL: URL(string: "https://music.apple.com/song/am1")!
        )
        let subsonicTrack = PlayableTrack(
            id: "sub1", provider: "subsonic", title: "T", artist: "A", album: "Al",
            durationSec: 180, streamURL: URL(string: "https://lyra.local/rest/stream?id=sub1")!
        )

        XCTAssertFalse(controller.supportsSing(spotifyTrack))
        XCTAssertFalse(controller.supportsSing(appleTrack))
        XCTAssertTrue(controller.supportsSing(subsonicTrack))

        XCTAssertEqual(controller.pendingExpandToLyricsTick, 0)
        controller.enter(track: subsonicTrack, openLyrics: true)
        XCTAssertEqual(controller.pendingExpandToLyricsTick, 1)
        XCTAssertTrue(controller.isEnabled)
        XCTAssertEqual(controller.phase, .preparing)

        controller.exit()
        XCTAssertFalse(controller.isEnabled)
        XCTAssertEqual(controller.phase, .off)

        // Exiting at 0% (two-step left-drag dismiss) resets remembered level to 12% default.
        controller.enter(track: subsonicTrack)
        controller.setSliderPosition(0.0)
        XCTAssertEqual(controller.sliderPosition, 0.0, accuracy: 1e-6)
        controller.exit()
        XCTAssertEqual(controller.sliderPosition, SingGainCurve.defaultSlider, accuracy: 1e-6)

        // Exiting above 0% preserves user's custom level.
        controller.enter(track: subsonicTrack)
        controller.setSliderPosition(0.55)
        controller.exit()
        XCTAssertEqual(controller.sliderPosition, 0.55, accuracy: 1e-6)
    }
}
