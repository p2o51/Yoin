import Accelerate
import AVFoundation
import Foundation

/// Exact PyTorch / `infer_and_eval.py` STFT, iSTFT, and polyphase resampling for `Lyra-Sing-Lite`:
/// - Core sample rate: `22,050 Hz`
/// - `n_fft = 1024`, `hop_length = 256`
/// - **Periodic Hann window**: `w[n] = 0.5 * (1 - cos(2π n / 1024))` (`n = 0 ..< 1024`)
/// - `center = true` (zero-pad `512` samples on left and right, matching `scipy.signal.stft(..., boundary="zeros", padded=True)`)
/// - `normalized = false` (unnormalized forward DFT, exact match to `torch.stft`)
enum SingDSP {
    static let coreSampleRate: Double = 22_050
    static let nFFT = 1024
    static let hopLength = 256
    static let halfFFT = nFFT / 2 // 512
    static let nBins = halfFFT + 1 // 513 (DC + bins 1..512)
    static let hiBins = halfFFT // 512 (bins 1..512, DC dropped)
    static let chunkFrames = 256
    static let chunkStep = 160

    /// Periodic Hann window matching `torch.hann_window(1024, periodic=True)` and `np.hanning(1025)[:-1]`.
    static let periodicHann: [Float] = {
        let n = Double(nFFT)
        return (0..<nFFT).map { i in
            Float(0.5 * (1.0 - cos(2.0 * Double.pi * Double(i) / n)))
        }
    }()

    /// OLA temporal chunk weight (`max(np.hanning(256), 1e-4)` matching `infer_and_eval.py:82-83`).
    static let chunkOLAWindow: [Float] = {
        let n = chunkFrames
        return (0..<n).map { i in
            let w = 0.5 * (1.0 - cosf(2.0 * Float.pi * Float(i) / Float(n - 1)))
            return max(w, 1e-4)
        }
    }()

    /// Complex spectrogram layout: `real[bin * frameCount + t]`, `imag[bin * frameCount + t]` for `bin ∈ 0..<513`.
    struct Spectrogram: Sendable {
        let binCount: Int
        let frameCount: Int
        var real: [Float]
        var imag: [Float]
    }

    /// Forward real-to-complex STFT matching `torch.stft(center=True, normalized=False, window=periodicHann)`
    /// and `scipy.signal.stft(..., boundary="zeros", padded=True) * sum(win)`.
    static func stft(_ signal: [Float]) -> Spectrogram {
        let pad = halfFFT
        let nSig = signal.count
        // Match scipy.signal.stft(boundary="zeros", padded=True):
        // left pad = n_fft/2, right pad >= n_fft/2 so trailing samples are covered cleanly by hopLength.
        let rightExtra = (hopLength - ((nSig + pad) % hopLength)) % hopLength
        let paddedCount = pad + nSig + pad + rightExtra
        var padded = [Float](repeating: 0, count: paddedCount)
        if nSig > 0 {
            signal.withUnsafeBufferPointer { src in
                padded.withUnsafeMutableBufferPointer { dst in
                    memcpy(dst.baseAddress!.advanced(by: pad), src.baseAddress!, nSig * MemoryLayout<Float>.size)
                }
            }
        }

        let frameCount = max(1, (paddedCount - nFFT) / hopLength + 1)
        var outReal = [Float](repeating: 0, count: nBins * frameCount)
        var outImag = [Float](repeating: 0, count: nBins * frameCount)

        let log2n = vDSP_Length(10) // 2^10 = 1024
        guard let fftSetup = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2)) else {
            return Spectrogram(binCount: nBins, frameCount: frameCount, real: outReal, imag: outImag)
        }
        defer { vDSP_destroy_fftsetup(fftSetup) }

        var windowed = [Float](repeating: 0, count: nFFT)
        var splitReal = [Float](repeating: 0, count: halfFFT)
        var splitImag = [Float](repeating: 0, count: halfFFT)

        for t in 0..<frameCount {
            let start = t * hopLength
            padded.withUnsafeBufferPointer { pBuf in
                periodicHann.withUnsafeBufferPointer { wBuf in
                    vDSP_vmul(pBuf.baseAddress!.advanced(by: start), 1, wBuf.baseAddress!, 1, &windowed, 1, vDSP_Length(nFFT))
                }
            }
            windowed.withUnsafeBufferPointer { wPtr in
                splitReal.withUnsafeMutableBufferPointer { rPtr in
                    splitImag.withUnsafeMutableBufferPointer { iPtr in
                        var split = DSPSplitComplex(realp: rPtr.baseAddress!, imagp: iPtr.baseAddress!)
                        wPtr.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: halfFFT) { cPtr in
                            vDSP_ctoz(cPtr, 2, &split, 1, vDSP_Length(halfFFT))
                        }
                        vDSP_fft_zrip(fftSetup, &split, 1, log2n, FFTDirection(kFFTDirection_Forward))
                        // vDSP_fft_zrip forward scales by 2.0 relative to standard unnormalized DFT.
                        // Also split.realp[0] holds DC, split.imagp[0] holds Nyquist (bin 512).
                        let half: Float = 0.5
                        outReal[0 * frameCount + t] = rPtr[0] * half
                        outImag[0 * frameCount + t] = 0
                        outReal[halfFFT * frameCount + t] = iPtr[0] * half
                        outImag[halfFFT * frameCount + t] = 0
                        for k in 1..<halfFFT {
                            outReal[k * frameCount + t] = rPtr[k] * half
                            outImag[k * frameCount + t] = iPtr[k] * half
                        }
                    }
                }
            }
        }

        return Spectrogram(binCount: nBins, frameCount: frameCount, real: outReal, imag: outImag)
    }

    /// Inverse STFT matching `torch.istft(center=True, normalized=False, window=periodicHann)`
    /// and `scipy.signal.istft(..., boundary=True)` on unnormalized DFT bins.
    static func istft(_ spec: Spectrogram, targetLength: Int) -> [Float] {
        let frameCount = spec.frameCount
        guard frameCount > 0, targetLength > 0 else { return [Float](repeating: 0, count: max(0, targetLength)) }

        let paddedLen = (frameCount - 1) * hopLength + nFFT
        var acc = [Float](repeating: 0, count: paddedLen)
        var winSqAcc = [Float](repeating: 0, count: paddedLen)

        let log2n = vDSP_Length(10)
        guard let fftSetup = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2)) else {
            return [Float](repeating: 0, count: targetLength)
        }
        defer { vDSP_destroy_fftsetup(fftSetup) }

        var splitReal = [Float](repeating: 0, count: halfFFT)
        var splitImag = [Float](repeating: 0, count: halfFFT)
        var timeFrame = [Float](repeating: 0, count: nFFT)
        let invScale: Float = 1.0 / Float(nFFT)
        let winSq: [Float] = periodicHann.map { $0 * $0 }

        for t in 0..<frameCount {
            // Pack DC into real[0] and Nyquist into imag[0] for vDSP_fft_zrip inverse.
            // Because vDSP forward scaled by 2 and vDSP inverse returns N * x(n) (from 2*X),
            // feeding unscaled X gives N/2 * x(n) — wait: for vDSP_fft_zrip, inverse(forward(x)) == 2*N * x.
            // Since we multiplied forward by 0.5, feeding X directly into inverse yields N * x(n),
            // so dividing by N (= 1024) recovers exact x(n)!
            splitReal[0] = spec.real[0 * frameCount + t]
            splitImag[0] = spec.real[halfFFT * frameCount + t]
            for k in 1..<halfFFT {
                splitReal[k] = spec.real[k * frameCount + t]
                splitImag[k] = spec.imag[k * frameCount + t]
            }

            splitReal.withUnsafeMutableBufferPointer { rPtr in
                splitImag.withUnsafeMutableBufferPointer { iPtr in
                    var split = DSPSplitComplex(realp: rPtr.baseAddress!, imagp: iPtr.baseAddress!)
                    vDSP_fft_zrip(fftSetup, &split, 1, log2n, FFTDirection(kFFTDirection_Inverse))
                    timeFrame.withUnsafeMutableBufferPointer { tPtr in
                        tPtr.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: halfFFT) { cPtr in
                            vDSP_ztoc(&split, 1, cPtr, 2, vDSP_Length(halfFFT))
                        }
                    }
                }
            }

            let start = t * hopLength
            for n in 0..<nFFT {
                acc[start + n] += timeFrame[n] * invScale * periodicHann[n]
                winSqAcc[start + n] += winSq[n]
            }
        }

        let pad = halfFFT
        var output = [Float](repeating: 0, count: targetLength)
        for i in 0..<targetLength {
            let idx = pad + i
            if idx < paddedLen {
                let denom = winSqAcc[idx]
                output[i] = denom > 1e-8 ? (acc[idx] / denom) : 0
            }
        }
        return output
    }

    /// Resamples stereo non-interleaved float PCM from `sourceRate` to `targetRate` using Apple's
    /// highest-quality linear-phase polyphase `AVAudioConverter`.
    static func resampleStereo(
        left: [Float],
        right: [Float],
        from sourceRate: Double,
        to targetRate: Double
    ) throws -> (left: [Float], right: [Float]) {
        let count = min(left.count, right.count)
        guard count > 0 else { return ([], []) }
        if abs(sourceRate - targetRate) < 0.5 {
            return (Array(left.prefix(count)), Array(right.prefix(count)))
        }
        guard let inFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sourceRate, channels: 2, interleaved: false),
              let outFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: targetRate, channels: 2, interleaved: false),
              let converter = AVAudioConverter(from: inFmt, to: outFmt) else {
            throw NSError(domain: "SingDSP", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot create AVAudioConverter"])
        }
        converter.sampleRateConverterAlgorithm = AVSampleRateConverterAlgorithm_Mastering
        converter.sampleRateConverterQuality = AVAudioQuality.max.rawValue
        converter.primeMethod = .none

        let expectedOut = Int((Double(count) * targetRate / sourceRate).rounded())
        var outL: [Float] = []
        var outR: [Float] = []
        outL.reserveCapacity(expectedOut + 256)
        outR.reserveCapacity(expectedOut + 256)

        let chunkSize = 32_768
        final class OffsetBox: @unchecked Sendable { var value = 0 }
        let inputOffset = OffsetBox()
        let outCap = AVAudioFrameCount(32_768)
        guard let outBuf = AVAudioPCMBuffer(pcmFormat: outFmt, frameCapacity: outCap) else {
            throw NSError(domain: "SingDSP", code: -2, userInfo: [NSLocalizedDescriptionKey: "Cannot allocate output buffer"])
        }

        while true {
            var error: NSError?
            let status = converter.convert(to: outBuf, error: &error) { requestedFrames, outStatus in
                let remaining = count - inputOffset.value
                if remaining <= 0 {
                    outStatus.pointee = .endOfStream
                    return nil
                }
                let take = min(Int(requestedFrames), min(chunkSize, remaining))
                guard let inBuf = AVAudioPCMBuffer(pcmFormat: inFmt, frameCapacity: AVAudioFrameCount(take)),
                      let ch = inBuf.floatChannelData else {
                    outStatus.pointee = .noDataNow
                    return nil
                }
                inBuf.frameLength = AVAudioFrameCount(take)
                left.withUnsafeBufferPointer { memcpy(ch[0], $0.baseAddress!.advanced(by: inputOffset.value), take * 4) }
                right.withUnsafeBufferPointer { memcpy(ch[1], $0.baseAddress!.advanced(by: inputOffset.value), take * 4) }
                inputOffset.value += take
                outStatus.pointee = .haveData
                return inBuf
            }
            if let error { throw error }
            let produced = Int(outBuf.frameLength)
            if produced > 0, let ch = outBuf.floatChannelData {
                outL.append(contentsOf: UnsafeBufferPointer(start: ch[0], count: produced))
                outR.append(contentsOf: UnsafeBufferPointer(start: ch[1], count: produced))
            }
            if status == .endOfStream || (status == .inputRanDry && inputOffset.value >= count && produced == 0) {
                break
            }
        }
        return (outL, outR)
    }

    /// Reads any AVFoundation-readable stereo/mono audio file (`flac`, `mp3`, `m4a`, `wav`) into a single
    /// decoded float32 stereo PCM buffer at its native sample rate (guaranteeing `X` and `V` share the exact
    /// same decoder priming delay — zero 25 ms MP3 offset!).
    static func decodeFullbandStereo(url: URL) throws -> (left: [Float], right: [Float], sampleRate: Double) {
        let file = try AVAudioFile(forReading: url)
        let sr = file.processingFormat.sampleRate
        let chCount = Int(file.processingFormat.channelCount)
        guard let stereoFmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sr, channels: 2, interleaved: false) else {
            throw NSError(domain: "SingDSP", code: -3, userInfo: [NSLocalizedDescriptionKey: "Unsupported audio format"])
        }
        let totalEst = max(1, Int(file.length))
        var left: [Float] = []
        var right: [Float] = []
        left.reserveCapacity(totalEst)
        right.reserveCapacity(totalEst)

        let blockFrames: AVAudioFrameCount = 32_768
        guard let buf = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: blockFrames) else {
            throw NSError(domain: "SingDSP", code: -4, userInfo: [NSLocalizedDescriptionKey: "Cannot allocate decode buffer"])
        }
        _ = stereoFmt
        while file.framePosition < file.length {
            try file.read(into: buf, frameCount: blockFrames)
            let n = Int(buf.frameLength)
            if n == 0 { break }
            guard let ch = buf.floatChannelData else { break }
            left.append(contentsOf: UnsafeBufferPointer(start: ch[0], count: n))
            if chCount >= 2 {
                right.append(contentsOf: UnsafeBufferPointer(start: ch[1], count: n))
            } else {
                right.append(contentsOf: UnsafeBufferPointer(start: ch[0], count: n))
            }
        }
        return (left, right, sr)
    }
}
