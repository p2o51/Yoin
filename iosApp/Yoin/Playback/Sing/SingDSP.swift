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
    /// Apple's `AVAssetWriter` MPEG-4 AAC encoder caps at 48 kHz; Hi-Res tracks (88.2 / 96 / 192 kHz FLAC)
    /// must be downsampled to 44.1 kHz or 48 kHz before writing `quad.m4a`.
    static let maxAACOutputSampleRate: Double = 48_000
    static let nFFT = 1024

    /// Picks a lossless-aligned AAC-safe output sample rate (<= 48 kHz) for a decoded track.
    static func outputSampleRate(for sourceSampleRate: Double) -> Double {
        guard sourceSampleRate > maxAACOutputSampleRate else { return sourceSampleRate }
        let rem44 = sourceSampleRate.truncatingRemainder(dividingBy: 44_100)
        if abs(rem44) < 1.0 || abs(rem44 - 44_100) < 1.0 {
            return 44_100
        }
        return maxAACOutputSampleRate
    }
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

    /// Forward real-to-complex STFT matching `torch.stft(center=True, pad_mode="reflect", normalized=False, window=periodicHann)`.
    static func stft(_ signal: [Float]) -> Spectrogram {
        let pad = halfFFT
        let nSig = signal.count
        let paddedCount = pad + nSig + pad
        var padded = [Float](repeating: 0, count: paddedCount)
        if nSig > 0 {
            _ = signal.withUnsafeBufferPointer { src in
                padded.withUnsafeMutableBufferPointer { dst in
                    memcpy(dst.baseAddress!.advanced(by: pad), src.baseAddress!, nSig * MemoryLayout<Float>.size)
                }
            }
            // PyTorch reflect padding: left mirrors signal[pad], ..., signal[1]; right mirrors signal[n-2], ..., signal[n-1-pad].
            if nSig > 1 {
                for i in 0..<pad {
                    let leftSrc = reflectIndex(pad - i, count: nSig)
                    padded[i] = signal[leftSrc]
                    let rightSrc = reflectIndex((nSig - 2) - i, count: nSig)
                    padded[pad + nSig + i] = signal[rightSrc]
                }
            } else {
                for i in 0..<pad {
                    padded[i] = signal[0]
                    padded[pad + nSig + i] = signal[0]
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

    /// Wraps any out-of-bounds index by triangle reflection in `0 ..< count` (matching `torch.nn.functional.pad(..., mode="reflect")`).
    private static func reflectIndex(_ idx: Int, count: Int) -> Int {
        guard count > 1 else { return 0 }
        let period = 2 * (count - 1)
        var m = idx % period
        if m < 0 { m += period }
        return m < count ? m : (period - m)
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
        let scaledHann: [Float] = periodicHann.map { $0 * invScale }
        let winSq: [Float] = periodicHann.map { $0 * $0 }

        acc.withUnsafeMutableBufferPointer { accBuf in
            winSqAcc.withUnsafeMutableBufferPointer { winAccBuf in
                scaledHann.withUnsafeBufferPointer { shBuf in
                    winSq.withUnsafeBufferPointer { wsqBuf in
                        guard let accBase = accBuf.baseAddress,
                              let winAccBase = winAccBuf.baseAddress,
                              let shBase = shBuf.baseAddress,
                              let wsqBase = wsqBuf.baseAddress else { return }
                        let vLen = vDSP_Length(nFFT)
                        for t in 0..<frameCount {
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
                            let accDst = accBase.advanced(by: start)
                            let winDst = winAccBase.advanced(by: start)
                            timeFrame.withUnsafeBufferPointer { tfBuf in
                                guard let tfBase = tfBuf.baseAddress else { return }
                                vDSP_vma(tfBase, 1, shBase, 1, accDst, 1, accDst, 1, vLen)
                            }
                            vDSP_vadd(winDst, 1, wsqBase, 1, winDst, 1, vLen)
                        }
                    }
                }
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

    /// Exact zero-phase polyphase FIR resampler matching `scipy.signal.resample_poly(x, up, down)`
    /// and Android `PolyphaseResampler.kt`:
    /// - Prototype filter: `firwin(2 * 10 * max(up, down) + 1, 1 / max(up, down), window=('kaiser', 5.0)) * up`
    /// - Strictly linear-phase with **0 samples** of group delay (unlike `AVAudioConverter` Mastering mode,
    ///   which leaves +2304 samples / +52.2 ms of uncompensated FIR latency across the 44.1 ↔ 22.05 kHz round-trip
    ///   and turns `I = X - V` into vocal amplification + slapback echo).
    struct PolyphaseResampler: Sendable {
        let up: Int
        let down: Int
        let isIdentity: Bool
        private let halfLength: Int
        private let numTaps: Int
        private let phaseTaps: [Float]
        private let phaseOffsets: [Int]
        private let phaseCounts: [Int]

        private static let kaiserBeta: Double = 5.0

        init(fromRate: Int, toRate: Int) {
            precondition(fromRate > 0 && toRate > 0, "Sample rates must be positive")
            let g = Self.gcd(fromRate, toRate)
            self.up = toRate / g
            self.down = fromRate / g
            self.isIdentity = (self.up == 1 && self.down == 1)
            if self.isIdentity {
                self.halfLength = 0
                self.numTaps = 0
                self.phaseTaps = []
                self.phaseOffsets = []
                self.phaseCounts = []
            } else {
                let maxRate = max(self.up, self.down)
                let half = 10 * maxRate
                let count = 2 * half + 1
                self.halfLength = half
                self.numTaps = count
                let taps = Self.lowPass(numTaps: count, cutoff: 1.0 / Double(maxRate), gain: self.up)

                var flatTaps: [Float] = []
                flatTaps.reserveCapacity(count)
                var offsets = [Int](repeating: 0, count: self.up)
                var counts = [Int](repeating: 0, count: self.up)
                for p in 0..<self.up {
                    let pCount = (count - 1 - p) / self.up + 1
                    offsets[p] = flatTaps.count
                    counts[p] = pCount
                    // Store each phase reversed so output m reads taps forward while input j advances forward.
                    for i in 0..<pCount {
                        flatTaps.append(Float(taps[p + self.up * (pCount - 1 - i)]))
                    }
                }
                self.phaseTaps = flatTaps
                self.phaseOffsets = offsets
                self.phaseCounts = counts
            }
        }

        /// `ceil(inputLength * up / down)`, matching `scipy.signal.resample_poly`.
        func outputLength(for inputLength: Int) -> Int {
            guard inputLength > 0 else { return 0 }
            let n = Int64(inputLength) * Int64(up)
            let d = Int64(down)
            return Int((n + d - 1) / d)
        }

        func resample(_ input: [Float]) -> [Float] {
            let inputLength = input.count
            guard inputLength > 0 else { return [] }
            if isIdentity { return input }

            let outLen = outputLength(for: inputLength)
            var output = [Float](repeating: 0, count: outLen)
            let lastTap = Int64(numTaps - 1)
            let up64 = Int64(up)
            let down64 = Int64(down)
            let half64 = Int64(halfLength)
            let maxIn = Int64(inputLength - 1)

            input.withUnsafeBufferPointer { inBuf in
                phaseTaps.withUnsafeBufferPointer { tapBuf in
                    phaseOffsets.withUnsafeBufferPointer { offBuf in
                        phaseCounts.withUnsafeBufferPointer { cntBuf in
                            output.withUnsafeMutableBufferPointer { outBuf in
                                guard let inBase = inBuf.baseAddress,
                                      let tapBase = tapBuf.baseAddress,
                                      let offBase = offBuf.baseAddress,
                                      let cntBase = cntBuf.baseAddress,
                                      let outBase = outBuf.baseAddress else { return }

                                for m in 0..<outLen {
                                    let c = Int64(m) * down64 + half64
                                    let rawLow = c - lastTap
                                    let jLow = rawLow <= 0 ? 0 : Int((rawLow + up64 - 1) / up64)
                                    let jHigh = Int(min(maxIn, c / up64))
                                    if jLow > jHigh { continue }

                                    let p = Int(c % up64)
                                    let pLen = cntBase[p]
                                    let pOff = offBase[p]
                                    let k = pLen - 1 - Int(c / up64 - Int64(jLow))
                                    let n = jHigh - jLow + 1
                                    var acc: Float = 0
                                    vDSP_dotpr(
                                        tapBase.advanced(by: pOff + k), 1,
                                        inBase.advanced(by: jLow), 1,
                                        &acc,
                                        vDSP_Length(n)
                                    )
                                    outBase[m] = acc
                                }
                            }
                        }
                    }
                }
            }
            return output
        }

        /// `firwin(numTaps, cutoff, window=('kaiser', 5.0))` scaled by `gain`.
        static func lowPass(numTaps: Int, cutoff: Double, gain: Int) -> [Double] {
            let alpha = 0.5 * Double(numTaps - 1)
            let i0Beta = besselI0(kaiserBeta)
            var h = [Double](repeating: 0, count: numTaps)
            var sum = 0.0
            for n in 0..<numTaps {
                let m = Double(n) - alpha
                let ratio = m / alpha
                let window = besselI0(kaiserBeta * sqrt(max(0.0, 1.0 - ratio * ratio))) / i0Beta
                let v = cutoff * sinc(cutoff * m) * window
                h[n] = v
                sum += v
            }
            let scale = Double(gain) / sum
            for n in 0..<numTaps { h[n] *= scale }
            return h
        }

        private static func sinc(_ x: Double) -> Double {
            x == 0.0 ? 1.0 : sin(Double.pi * x) / (Double.pi * x)
        }

        /// Modified Bessel function of the first kind, order 0 (exact match to `scipy.special.i0`).
        static func besselI0(_ x: Double) -> Double {
            let q = x * x / 4.0
            var term = 1.0
            var sum = 1.0
            var k = 1.0
            while term > sum * 1e-17 {
                term *= q / (k * k)
                sum += term
                k += 1.0
            }
            return sum
        }

        private static func gcd(_ a: Int, _ b: Int) -> Int {
            var x = abs(a)
            var y = abs(b)
            while y != 0 {
                let r = x % y
                x = y
                y = r
            }
            return x
        }
    }

    /// Resamples stereo non-interleaved float PCM from `sourceRate` to `targetRate` with strictly
    /// zero-phase Kaiser-windowed polyphase FIR (`scipy.signal.resample_poly` / Android `PolyphaseResampler`).
    static func resampleStereo(
        left: [Float],
        right: [Float],
        from sourceRate: Double,
        to targetRate: Double
    ) throws -> (left: [Float], right: [Float]) {
        let count = min(left.count, right.count)
        guard count > 0 else { return ([], []) }
        let fromInt = Int(sourceRate.rounded())
        let toInt = Int(targetRate.rounded())
        guard fromInt > 0, toInt > 0 else {
            throw NSError(domain: "SingDSP", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid sample rate"])
        }
        if fromInt == toInt {
            return (Array(left.prefix(count)), Array(right.prefix(count)))
        }
        let resampler = PolyphaseResampler(fromRate: fromInt, toRate: toInt)
        let srcL = left.count == count ? left : Array(left.prefix(count))
        let srcR = right.count == count ? right : Array(right.prefix(count))
        return (resampler.resample(srcL), resampler.resample(srcR))
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
