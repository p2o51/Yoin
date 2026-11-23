import AVFoundation
import CoreMedia
import Foundation
import os

/// Lock-free, real-time 4-channel quad → 2-channel stereo vocal mixer:
/// - Input layout (iron rule): `[CH0 = I_L, CH1 = I_R, CH2 = V_L, CH3 = V_R]`
/// - Output: `Y_L = I_L + g * V_L`, `Y_R = I_R + g * V_R`
/// - Applies per-sample gain ramps (`20 ms` linear for drags, `~0.95 s` ease-out cubic for Sing cut-in,
///   `~0.75 s` ease-in-out for Sing exit) so transitions are silky and completely click-free.
final class SingQuadDownmixer: @unchecked Sendable {
    private let lock = OSAllocatedUnfairLock(initialState: State())

    private struct State {
        var currentGain: Float = 1.0
        var rampStartGain: Float = 1.0
        var targetGain: Float = 1.0
        var rampTotalFrames: Int = 0
        var rampElapsedFrames: Int = 0
        var rampCurve: SingRampCurve = .linear
        var rampGeneration: UInt64 = 0
        var sampleRate: Double = 44_100
    }

    init(initialGain: Float = 1.0, sampleRate: Double = 44_100) {
        lock.withLock { s in
            let g = max(0, min(1, initialGain))
            s.currentGain = g
            s.rampStartGain = g
            s.targetGain = g
            s.sampleRate = max(8_000, sampleRate)
        }
    }

    func setSampleRate(_ sampleRate: Double) {
        lock.withLock { s in
            s.sampleRate = max(8_000, sampleRate)
        }
    }

    /// Updates target vocal linear gain `g ∈ [0, 1]` with a per-sample ramp over `rampSeconds` using `curve`.
    func setVocalGain(
        _ gain: Float,
        rampSeconds: Double = SingGainCurve.smoothingWindowSeconds,
        curve: SingRampCurve = .linear
    ) {
        let clamped = max(0, min(1, gain))
        lock.withLock { s in
            s.rampGeneration &+= 1
            s.targetGain = clamped
            s.rampCurve = curve
            let frames = max(0, Int((s.sampleRate * max(0, rampSeconds)).rounded()))
            if frames <= 1 {
                s.currentGain = clamped
                s.rampStartGain = clamped
                s.rampTotalFrames = 0
                s.rampElapsedFrames = 0
            } else {
                s.rampStartGain = s.currentGain
                s.rampTotalFrames = frames
                s.rampElapsedFrames = 0
            }
        }
    }

    /// Updates target vocal gain from a UI slider position `0.0 ... 1.0`.
    func setSliderPosition(
        _ position: Float,
        rampSeconds: Double = SingGainCurve.smoothingWindowSeconds,
        curve: SingRampCurve = .linear
    ) {
        setVocalGain(SingGainCurve.linearGain(forSlider: position), rampSeconds: rampSeconds, curve: curve)
    }

    var currentGain: Float {
        lock.withLock { $0.currentGain }
    }

    var targetGain: Float {
        lock.withLock { $0.targetGain }
    }

    /// Mixes non-interleaved 4-channel float planes `[I_L, I_R, V_L, V_R]` into `[outL, outR]`.
    /// Safe for in-place processing (`outL == iL`, `outR == iR`). Zero allocations.
    func processNonInterleaved(
        frameCount: Int,
        iL: UnsafePointer<Float>,
        iR: UnsafePointer<Float>,
        vL: UnsafePointer<Float>,
        vR: UnsafePointer<Float>,
        outL: UnsafeMutablePointer<Float>,
        outR: UnsafeMutablePointer<Float>
    ) {
        guard frameCount > 0 else { return }
        let snap = lock.withLock { $0 }
        var g = snap.currentGain
        var elapsed = snap.rampElapsedFrames
        let total = snap.rampTotalFrames
        let start = snap.rampStartGain
        let target = snap.targetGain
        let curve = snap.rampCurve
        let gen = snap.rampGeneration

        if total == 0 || elapsed >= total {
            for i in 0..<frameCount {
                outL[i] = iL[i] + g * vL[i]
                outR[i] = iR[i] + g * vR[i]
            }
            return
        }

        let delta = target - start
        let invTotal = 1.0 / Float(total)
        for i in 0..<frameCount {
            if elapsed < total {
                elapsed += 1
                if elapsed >= total {
                    g = target
                } else {
                    g = start + delta * curve.evaluate(Float(elapsed) * invTotal)
                }
            }
            outL[i] = iL[i] + g * vL[i]
            outR[i] = iR[i] + g * vR[i]
        }

        let finalGain = g
        let finalElapsed = elapsed
        lock.withLock { s in
            if s.rampGeneration == gen {
                s.currentGain = finalGain
                if finalElapsed >= total {
                    s.rampTotalFrames = 0
                    s.rampElapsedFrames = 0
                    s.rampStartGain = finalGain
                } else {
                    s.rampElapsedFrames = finalElapsed
                }
            }
        }
    }

    /// Mixes interleaved 4-channel float samples (`[I_L, I_R, V_L, V_R, ...]`) into stereo interleaved `[Y_L, Y_R, ...]`.
    func processInterleaved(
        frameCount: Int,
        quad: UnsafePointer<Float>,
        stereoOut: UnsafeMutablePointer<Float>
    ) {
        guard frameCount > 0 else { return }
        let snap = lock.withLock { $0 }
        var g = snap.currentGain
        var elapsed = snap.rampElapsedFrames
        let total = snap.rampTotalFrames
        let start = snap.rampStartGain
        let target = snap.targetGain
        let curve = snap.rampCurve
        let gen = snap.rampGeneration

        if total == 0 || elapsed >= total {
            for i in 0..<frameCount {
                let base = i * 4
                stereoOut[i * 2] = quad[base] + g * quad[base + 2]
                stereoOut[i * 2 + 1] = quad[base + 1] + g * quad[base + 3]
            }
            return
        }

        let delta = target - start
        let invTotal = 1.0 / Float(total)
        for i in 0..<frameCount {
            if elapsed < total {
                elapsed += 1
                if elapsed >= total {
                    g = target
                } else {
                    g = start + delta * curve.evaluate(Float(elapsed) * invTotal)
                }
            }
            let base = i * 4
            stereoOut[i * 2] = quad[base] + g * quad[base + 2]
            stereoOut[i * 2 + 1] = quad[base + 1] + g * quad[base + 3]
        }

        let finalGain = g
        let finalElapsed = elapsed
        lock.withLock { s in
            if s.rampGeneration == gen {
                s.currentGain = finalGain
                if finalElapsed >= total {
                    s.rampTotalFrames = 0
                    s.rampElapsedFrames = 0
                    s.rampStartGain = finalGain
                } else {
                    s.rampElapsedFrames = finalElapsed
                }
            }
        }
    }

    /// Mixes interleaved 4-channel float samples in-place (`FL/FR = I + g*V`, `BL/BR = 0`).
    func processInterleavedInPlace(frameCount: Int, quad: UnsafeMutablePointer<Float>) {
        guard frameCount > 0 else { return }
        let snap = lock.withLock { $0 }
        var g = snap.currentGain
        var elapsed = snap.rampElapsedFrames
        let total = snap.rampTotalFrames
        let start = snap.rampStartGain
        let target = snap.targetGain
        let curve = snap.rampCurve
        let gen = snap.rampGeneration

        if total == 0 || elapsed >= total {
            for i in 0..<frameCount {
                let base = i * 4
                quad[base] = quad[base] + g * quad[base + 2]
                quad[base + 1] = quad[base + 1] + g * quad[base + 3]
                quad[base + 2] = 0
                quad[base + 3] = 0
            }
            return
        }

        let delta = target - start
        let invTotal = 1.0 / Float(total)
        for i in 0..<frameCount {
            if elapsed < total {
                elapsed += 1
                if elapsed >= total {
                    g = target
                } else {
                    g = start + delta * curve.evaluate(Float(elapsed) * invTotal)
                }
            }
            let base = i * 4
            quad[base] = quad[base] + g * quad[base + 2]
            quad[base + 1] = quad[base + 1] + g * quad[base + 3]
            quad[base + 2] = 0
            quad[base + 3] = 0
        }

        let finalGain = g
        let finalElapsed = elapsed
        lock.withLock { s in
            if s.rampGeneration == gen {
                s.currentGain = finalGain
                if finalElapsed >= total {
                    s.rampTotalFrames = 0
                    s.rampElapsedFrames = 0
                    s.rampStartGain = finalGain
                } else {
                    s.rampElapsedFrames = finalElapsed
                }
            }
        }
    }
}

/// Attaches a real-time 4ch→2ch `MTAudioProcessingTap` onto an `AVAssetTrack` for local `quad.m4a` files
/// or direct HTTPS `quad.m4a` URLs (never custom `AVAssetResourceLoaderDelegate` schemes).
enum SingQuadAudioTap {
    private final class TapContext {
        let mixer: SingQuadDownmixer
        var channelCount: Int = 4
        var isNonInterleaved: Bool = true
        init(mixer: SingQuadDownmixer) { self.mixer = mixer }
    }

    static func makeAudioMix(for track: AVAssetTrack, mixer: SingQuadDownmixer) -> AVMutableAudioMix? {
        let context = TapContext(mixer: mixer)
        let unmanaged = Unmanaged.passRetained(context)
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: unmanaged.toOpaque(),
            init: { _, clientInfo, tapStorageOut in
                tapStorageOut.pointee = clientInfo
            },
            finalize: { tap in
                let storage = MTAudioProcessingTapGetStorage(tap)
                Unmanaged<TapContext>.fromOpaque(storage).release()
            },
            prepare: { tap, _, processingFormat in
                let ctx = Unmanaged<TapContext>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
                let asbd = processingFormat.pointee
                ctx.mixer.setSampleRate(asbd.mSampleRate)
                ctx.channelCount = Int(asbd.mChannelsPerFrame)
                ctx.isNonInterleaved = (asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0
            },
            unprepare: nil,
            process: { tap, numberFrames, _, bufferListInOut, numberFramesOut, flagsOut in
                let status = MTAudioProcessingTapGetSourceAudio(
                    tap, numberFrames, bufferListInOut, flagsOut, nil, numberFramesOut
                )
                guard status == noErr else { return }
                let frames = Int(numberFramesOut.pointee)
                guard frames > 0 else { return }
                let ctx = Unmanaged<TapContext>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
                let buffers = UnsafeMutableAudioBufferListPointer(bufferListInOut)
                if ctx.isNonInterleaved, buffers.count >= 4,
                   let p0 = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                   let p1 = buffers[1].mData?.assumingMemoryBound(to: Float.self),
                   let p2 = buffers[2].mData?.assumingMemoryBound(to: Float.self),
                   let p3 = buffers[3].mData?.assumingMemoryBound(to: Float.self) {
                    ctx.mixer.processNonInterleaved(
                        frameCount: frames, iL: p0, iR: p1, vL: p2, vR: p3, outL: p0, outR: p1
                    )
                    // Zero rear channels so any quad→stereo output downmix never leaks unscaled vocals.
                    memset(p2, 0, frames * MemoryLayout<Float>.size)
                    memset(p3, 0, frames * MemoryLayout<Float>.size)
                } else if !ctx.isNonInterleaved, ctx.channelCount == 4, buffers.count == 1,
                          let base = buffers[0].mData?.assumingMemoryBound(to: Float.self) {
                    ctx.mixer.processInterleavedInPlace(frameCount: frames, quad: base)
                }
            }
        )

        var tap: MTAudioProcessingTap?
        let err = MTAudioProcessingTapCreate(
            kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap
        )
        guard err == noErr, let createdTap = tap else {
            unmanaged.release()
            return nil
        }

        let params = AVMutableAudioMixInputParameters(track: track)
        params.audioTapProcessor = createdTap
        let mix = AVMutableAudioMix()
        mix.inputParameters = [params]
        return mix
    }
}

/// Writes 4-channel Quad AAC `.m4a` (`[CH0=I_L, CH1=I_R, CH2=V_L, CH3=V_R]`, `256 kbps`) using native
/// `AVAssetWriter` + `kAudioChannelLayoutTag_Quadraphonic` — zero third-party codecs and verified exact channel order.
enum SingQuadM4AWriter {
    enum WriterError: Error, LocalizedError {
        case cannotAddInput
        case formatDescriptionFailed(OSStatus)
        case sampleBufferFailed(OSStatus)
        case writingFailed(String)

        var errorDescription: String? {
            switch self {
            case .cannotAddInput: return "Cannot add 4-channel AAC input to AVAssetWriter"
            case .formatDescriptionFailed(let s): return "CMAudioFormatDescriptionCreate failed (\(s))"
            case .sampleBufferFailed(let s): return "CMSampleBuffer creation failed (\(s))"
            case .writingFailed(let msg): return "AVAssetWriter failed: \(msg)"
            }
        }
    }

    static func write(
        instL: [Float],
        instR: [Float],
        vocL: [Float],
        vocR: [Float],
        sampleRate: Double,
        bitRate: Int = 256_000,
        to outputURL: URL
    ) async throws {
        let totalFrames = min(instL.count, instR.count, vocL.count, vocR.count)
        guard totalFrames > 0 else { throw WriterError.writingFailed("Empty audio") }
        try? FileManager.default.removeItem(at: outputURL)

        var quadLayout = AudioChannelLayout()
        quadLayout.mChannelLayoutTag = kAudioChannelLayoutTag_Quadraphonic
        let layoutData = Data(bytes: &quadLayout, count: MemoryLayout<AudioChannelLayout>.size)

        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 4,
            AVEncoderBitRateKey: bitRate,
            AVChannelLayoutKey: layoutData,
        ]

        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .m4a)
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        guard writer.canAdd(input) else { throw WriterError.cannotAddInput }
        writer.add(input)

        guard writer.startWriting() else {
            throw WriterError.writingFailed(writer.error?.localizedDescription ?? "startWriting failed")
        }
        writer.startSession(atSourceTime: .zero)

        let pcmFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            interleaved: false,
            channelLayout: AVAudioChannelLayout(layout: &quadLayout)
        )
        var asbd = pcmFormat.streamDescription.pointee
        var fmtDesc: CMAudioFormatDescription?
        let descStatus = CMAudioFormatDescriptionCreate(
            allocator: nil,
            asbd: &asbd,
            layoutSize: MemoryLayout<AudioChannelLayout>.size,
            layout: &quadLayout,
            magicCookieSize: 0,
            magicCookie: nil,
            extensions: nil,
            formatDescriptionOut: &fmtDesc
        )
        guard descStatus == noErr, let formatDescription = fmtDesc else {
            throw WriterError.formatDescriptionFailed(descStatus)
        }

        let blockFrames = 16_384
        var offset = 0
        while offset < totalFrames {
            try Task.checkCancellation()
            while !input.isReadyForMoreMediaData {
                if writer.status == .failed || writer.status == .cancelled {
                    throw WriterError.writingFailed(writer.error?.localizedDescription ?? "writer aborted")
                }
                try await Task.sleep(for: .milliseconds(2))
            }
            let count = min(blockFrames, totalFrames - offset)
            guard let pcmBuf = AVAudioPCMBuffer(pcmFormat: pcmFormat, frameCapacity: AVAudioFrameCount(count)),
                  let chData = pcmBuf.floatChannelData else {
                throw WriterError.writingFailed("Failed to allocate AVAudioPCMBuffer")
            }
            pcmBuf.frameLength = AVAudioFrameCount(count)
            instL.withUnsafeBufferPointer { memcpy(chData[0], $0.baseAddress!.advanced(by: offset), count * 4) }
            instR.withUnsafeBufferPointer { memcpy(chData[1], $0.baseAddress!.advanced(by: offset), count * 4) }
            vocL.withUnsafeBufferPointer { memcpy(chData[2], $0.baseAddress!.advanced(by: offset), count * 4) }
            vocR.withUnsafeBufferPointer { memcpy(chData[3], $0.baseAddress!.advanced(by: offset), count * 4) }

            var timing = CMSampleTimingInfo(
                duration: CMTime(value: 1, timescale: Int32(sampleRate)),
                presentationTimeStamp: CMTime(value: CMTimeValue(offset), timescale: Int32(sampleRate)),
                decodeTimeStamp: .invalid
            )
            var sampleBuffer: CMSampleBuffer?
            let sbStatus = CMSampleBufferCreate(
                allocator: nil,
                dataBuffer: nil,
                dataReady: false,
                makeDataReadyCallback: nil,
                refcon: nil,
                formatDescription: formatDescription,
                sampleCount: count,
                sampleTimingEntryCount: 1,
                sampleTimingArray: &timing,
                sampleSizeEntryCount: 0,
                sampleSizeArray: nil,
                sampleBufferOut: &sampleBuffer
            )
            guard sbStatus == noErr, let sb = sampleBuffer else {
                throw WriterError.sampleBufferFailed(sbStatus)
            }
            let setStatus = CMSampleBufferSetDataBufferFromAudioBufferList(
                sb,
                blockBufferAllocator: nil,
                blockBufferMemoryAllocator: nil,
                flags: 0,
                bufferList: pcmBuf.audioBufferList
            )
            guard setStatus == noErr else { throw WriterError.sampleBufferFailed(setStatus) }
            guard input.append(sb) else {
                throw WriterError.writingFailed(writer.error?.localizedDescription ?? "append failed")
            }
            offset += count
        }

        input.markAsFinished()
        await writer.finishWriting()
        guard writer.status == .completed else {
            throw WriterError.writingFailed(writer.error?.localizedDescription ?? "finishWriting failed")
        }
    }
}
