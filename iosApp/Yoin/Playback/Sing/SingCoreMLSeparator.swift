import Compression
import CoreML
import CryptoKit
import Foundation

/// Bounded LRU disk cache for 4-channel Quad AAC `.m4a` stems (`[I_L, I_R, V_L, V_R]`).
actor SingStemCache {
    let directory: URL
    let maxBytes: Int64

    init(directory: URL? = nil, maxBytes: Int64 = 500 * 1024 * 1024) {
        if let directory {
            self.directory = directory
        } else {
            let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            self.directory = caches.appendingPathComponent("YoinSingStems", isDirectory: true)
        }
        self.maxBytes = maxBytes
        try? FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
    }

    func url(forKey key: String) -> URL {
        let hash = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent("\(hash).m4a")
    }

    /// Returns cached `.m4a` URL if present and touches its modification date for LRU ordering.
    func hit(forKey key: String) -> URL? {
        let target = url(forKey: key)
        guard FileManager.default.fileExists(atPath: target.path) else { return nil }
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path)
        return target
    }

    /// Commits a finished temporary `.m4a` atomically into the cache and trims LRU entries past `maxBytes`.
    func store(tempFile: URL, forKey key: String) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = url(forKey: key)
        if FileManager.default.fileExists(atPath: target.path) {
            try? FileManager.default.removeItem(at: target)
        }
        try FileManager.default.moveItem(at: tempFile, to: target)
        evictIfNeeded(keeping: target)
        return target
    }

    private func evictIfNeeded(keeping protectedURL: URL) {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else { return }

        struct Entry {
            let url: URL
            let bytes: Int64
            let modified: Date
        }
        var items: [Entry] = entries.compactMap { url in
            guard let vals = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]) else { return nil }
            return Entry(url: url, bytes: Int64(vals.fileSize ?? 0), modified: vals.contentModificationDate ?? .distantPast)
        }
        var total = items.reduce(Int64(0)) { $0 + $1.bytes }
        guard total > maxBytes else { return }
        items.sort { $0.modified < $1.modified }
        for item in items where total > maxBytes {
            if item.url.standardizedFileURL == protectedURL.standardizedFileURL { continue }
            try? fm.removeItem(at: item.url)
            total -= item.bytes
        }
    }
}

/// Manages on-demand download, SHA-256 verification, pure-Swift ZIP extraction, and one-time CoreML compilation
/// of `LyraSingLite.mlpackage.zip` so the main app bundle carries 0 MB of ML weights.
actor SingModelStore {
    let baseDirectory: URL
    private let expectedSha256: String

    init(baseDirectory: URL? = nil, expectedSha256: String = SingModelAsset.zipSha256) {
        if let baseDirectory {
            self.baseDirectory = baseDirectory
        } else {
            let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            self.baseDirectory = appSupport.appendingPathComponent("YoinSingModels/\(SingModelAsset.version)", isDirectory: true)
        }
        self.expectedSha256 = expectedSha256.lowercased()
    }

    var compiledModelURL: URL {
        baseDirectory.appendingPathComponent("LyraSingLite.mlmodelc", isDirectory: true)
    }

    var isInstalled: Bool {
        FileManager.default.fileExists(atPath: compiledModelURL.path)
    }

    /// Installs directly from a local `.mlpackage` (used in unit tests and local dev verification).
    func installFromLocalPackage(_ mlpackageURL: URL) async throws -> URL {
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)
        let compiledTemp = try await MLModel.compileModel(at: mlpackageURL)
        let dest = compiledModelURL
        if FileManager.default.fileExists(atPath: dest.path) {
            try FileManager.default.removeItem(at: dest)
        }
        try FileManager.default.moveItem(at: compiledTemp, to: dest)
        return dest
    }

    /// Downloads `LyraSingLite.mlpackage.zip`, verifies pinned SHA-256, extracts, and compiles `.mlmodelc`.
    func downloadAndInstall(
        from remoteZipURL: URL,
        session: URLSession = .shared,
        onProgress: @Sendable (Double) -> Void
    ) async throws -> URL {
        if isInstalled { return compiledModelURL }
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)

        let tempZip = baseDirectory.appendingPathComponent("download-\(UUID().uuidString).zip")
        defer { try? FileManager.default.removeItem(at: tempZip) }

        let (bytes, response) = try await session.bytes(from: remoteZipURL)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw NSError(domain: "SingModelStore", code: -10, userInfo: [NSLocalizedDescriptionKey: "Model download failed"])
        }
        let expectedLen = max(http.expectedContentLength, SingModelAsset.expectedZipBytes)
        FileManager.default.createFile(atPath: tempZip.path, contents: nil)
        let handle = try FileHandle(forWritingTo: tempZip)
        defer { try? handle.close() }

        var hasher = SHA256()
        var buffer = Data()
        buffer.reserveCapacity(65_536)
        var received: Int64 = 0
        for try await byte in bytes {
            try Task.checkCancellation()
            buffer.append(byte)
            if buffer.count >= 65_536 {
                hasher.update(data: buffer)
                try handle.write(contentsOf: buffer)
                received += Int64(buffer.count)
                buffer.removeAll(keepingCapacity: true)
                onProgress(min(0.95, Double(received) / Double(expectedLen)))
            }
        }
        if !buffer.isEmpty {
            hasher.update(data: buffer)
            try handle.write(contentsOf: buffer)
            received += Int64(buffer.count)
        }
        try handle.close()

        let actualSha = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        guard actualSha == expectedSha256 else {
            throw SingModelError.checksumMismatch(expected: expectedSha256, actual: actualSha)
        }

        let unpackDir = baseDirectory.appendingPathComponent("unpack-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: unpackDir) }
        try SingZipExtractor.extract(zipURL: tempZip, to: unpackDir)

        let pkgURL = unpackDir.appendingPathComponent(SingModelAsset.packageName, isDirectory: true)
        guard FileManager.default.fileExists(atPath: pkgURL.path) else {
            throw NSError(domain: "SingModelStore", code: -11, userInfo: [NSLocalizedDescriptionKey: "Missing \(SingModelAsset.packageName) in archive"])
        }
        let compiled = try await installFromLocalPackage(pkgURL)
        onProgress(1.0)
        return compiled
    }

    enum SingModelError: Error {
        case checksumMismatch(expected: String, actual: String)
    }
}

/// Minimal pure-Swift ZIP extractor (stored + DEFLATE via Apple's `Compression` framework) for `.mlpackage.zip`.
enum SingZipExtractor {
    static func extract(zipURL: URL, to destination: URL) throws {
        let data = try Data(contentsOf: zipURL)
        let fm = FileManager.default
        try fm.createDirectory(at: destination, withIntermediateDirectories: true)

        // Locate End of Central Directory (0x06054b50) from the tail.
        let minEocd = 22
        guard data.count >= minEocd else {
            throw NSError(domain: "SingZipExtractor", code: 1, userInfo: [NSLocalizedDescriptionKey: "Truncated ZIP"])
        }
        let searchStart = max(0, data.count - 65_557)
        var eocdOffset = -1
        for i in stride(from: data.count - minEocd, through: searchStart, by: -1) {
            if readUInt32(data, i) == 0x0605_4b50 {
                eocdOffset = i
                break
            }
        }
        guard eocdOffset >= 0 else {
            throw NSError(domain: "SingZipExtractor", code: 2, userInfo: [NSLocalizedDescriptionKey: "Invalid ZIP archive"])
        }
        let totalEntries = Int(readUInt16(data, eocdOffset + 10))
        let cdOffset = Int(readUInt32(data, eocdOffset + 16))

        var cursor = cdOffset
        let canonicalDest = destination.standardizedFileURL.path
        for _ in 0..<totalEntries {
            guard cursor + 46 <= data.count, readUInt32(data, cursor) == 0x0201_4b50 else {
                throw NSError(domain: "SingZipExtractor", code: 3, userInfo: [NSLocalizedDescriptionKey: "Corrupt ZIP directory"])
            }
            let method = readUInt16(data, cursor + 10)
            let compSize = Int(readUInt32(data, cursor + 20))
            let uncompSize = Int(readUInt32(data, cursor + 24))
            let nameLen = Int(readUInt16(data, cursor + 28))
            let extraLen = Int(readUInt16(data, cursor + 30))
            let commentLen = Int(readUInt16(data, cursor + 32))
            let localHeaderOffset = Int(readUInt32(data, cursor + 42))

            let nameStart = cursor + 46
            let nameData = data.subdata(in: nameStart..<(nameStart + nameLen))
            let rawName = String(data: nameData, encoding: .utf8) ?? ""
            cursor = nameStart + nameLen + extraLen + commentLen

            if rawName.isEmpty || rawName.hasSuffix("/") { continue }
            let targetURL = destination.appendingPathComponent(rawName).standardizedFileURL
            // Zip-slip path traversal guard.
            guard targetURL.path.hasPrefix(canonicalDest + "/") else {
                throw NSError(domain: "SingZipExtractor", code: 4, userInfo: [NSLocalizedDescriptionKey: "Unsafe ZIP path"])
            }

            guard localHeaderOffset + 30 <= data.count, readUInt32(data, localHeaderOffset) == 0x0403_4b50 else {
                throw NSError(domain: "SingZipExtractor", code: 5, userInfo: [NSLocalizedDescriptionKey: "Corrupt local header"])
            }
            let localNameLen = Int(readUInt16(data, localHeaderOffset + 26))
            let localExtraLen = Int(readUInt16(data, localHeaderOffset + 28))
            let payloadStart = localHeaderOffset + 30 + localNameLen + localExtraLen
            let payloadEnd = payloadStart + compSize
            guard payloadEnd <= data.count else {
                throw NSError(domain: "SingZipExtractor", code: 6, userInfo: [NSLocalizedDescriptionKey: "Truncated ZIP entry"])
            }

            let outData: Data
            if method == 0 {
                outData = data.subdata(in: payloadStart..<payloadEnd)
            } else if method == 8 {
                if uncompSize == 0 {
                    outData = Data()
                } else {
                    var dst = Data(count: uncompSize)
                    let decodedCount: Int = dst.withUnsafeMutableBytes { dstBuf in
                        data.withUnsafeBytes { srcBuf in
                            guard let d = dstBuf.baseAddress?.assumingMemoryBound(to: UInt8.self),
                                  let s = srcBuf.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
                            return compression_decode_buffer(
                                d, uncompSize,
                                s.advanced(by: payloadStart), compSize,
                                nil,
                                COMPRESSION_ZLIB
                            )
                        }
                    }
                    guard decodedCount == uncompSize else {
                        throw NSError(domain: "SingZipExtractor", code: 7, userInfo: [NSLocalizedDescriptionKey: "DEFLATE decode failed"])
                    }
                    outData = dst
                }
            } else {
                throw NSError(domain: "SingZipExtractor", code: 8, userInfo: [NSLocalizedDescriptionKey: "Unsupported ZIP method \(method)"])
            }

            try fm.createDirectory(at: targetURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try outData.write(to: targetURL)
        }
    }

    private static func readUInt16(_ data: Data, _ offset: Int) -> UInt16 {
        UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
    }

    private static func readUInt32(_ data: Data, _ offset: Int) -> UInt32 {
        UInt32(data[offset])
            | (UInt32(data[offset + 1]) << 8)
            | (UInt32(data[offset + 2]) << 16)
            | (UInt32(data[offset + 3]) << 24)
    }
}

/// On-device `Lyra-Sing-Lite` CoreML separator implementing the Race-to-Idle strategy:
/// - Single fullband decode (zero MP3 priming offset)
/// - 22,050 Hz STFT → `[1, 4, 512, 256]` RMS-normalized CoreML chunks → Hann OLA → iSTFT → upsample
/// - `I_full = X_full - V_full` (100% untouched sub-bass `<85 Hz` and air/cymbals `>10.5 kHz`)
/// - Writes 4ch Quad AAC `.m4a` into `SingStemCache`, then drops `MLModel` immediately (`0%` idle memory).
final class SingCoreMLSeparator: SingStreamProvider, @unchecked Sendable {
    let modelStore: SingModelStore
    let stemCache: SingStemCache
    private let remoteModelZipURL: URL?

    /// Speed-gate threshold from the handoff spec: 1 chunk (`2.97 s`) taking `> 2.0 s` (`RTF > 0.7`) is too slow.
    static let maxAllowedChunkSeconds: Double = 2.0

    init(modelStore: SingModelStore = SingModelStore(), stemCache: SingStemCache = SingStemCache(), remoteModelZipURL: URL? = nil) {
        self.modelStore = modelStore
        self.stemCache = stemCache
        self.remoteModelZipURL = remoteModelZipURL
    }

    func prepare(songId: String, localAudioURL: URL?, tier: String? = nil) -> AsyncStream<SingState> {
        AsyncStream { continuation in
            let task = Task(priority: .userInitiated) {
                await self.run(songId: songId, localAudioURL: localAudioURL, continuation: continuation)
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func run(
        songId: String,
        localAudioURL: URL?,
        continuation: AsyncStream<SingState>.Continuation
    ) async {
        defer { continuation.finish() }
        let cacheKey = "local|\(SingModelAsset.version)|\(songId)"
        if let cached = await stemCache.hit(forKey: cacheKey) {
            continuation.yield(.ready(source: .localFile(cached)))
            return
        }
        guard let localAudioURL else {
            continuation.yield(.failed(.separationFailed("Audio file not cached locally yet")))
            return
        }
        if ProcessInfo.processInfo.thermalState == .critical {
            continuation.yield(.failed(.thermalThrottled))
            return
        }

        let compiledURL: URL
        if await modelStore.isInstalled {
            compiledURL = await modelStore.compiledModelURL
        } else if let remoteModelZipURL {
            continuation.yield(.downloadingModel(progress: 0))
            do {
                compiledURL = try await modelStore.downloadAndInstall(from: remoteModelZipURL) { progress in
                    continuation.yield(.downloadingModel(progress: progress))
                }
            } catch SingModelStore.SingModelError.checksumMismatch {
                continuation.yield(.failed(.modelChecksumMismatch))
                return
            } catch {
                continuation.yield(.failed(.modelDownloadFailed(error.localizedDescription)))
                return
            }
        } else {
            continuation.yield(.needsModelDownload)
            return
        }

        continuation.yield(.preparing(stage: .warming, etaSeconds: nil))
        do {
            let outputURL = try await separateToQuadM4A(
                inputURL: localAudioURL,
                compiledModelURL: compiledURL,
                cacheKey: cacheKey,
                onStage: { stage, eta in
                    continuation.yield(.preparing(stage: stage, etaSeconds: eta))
                }
            )
            continuation.yield(.ready(source: .localFile(outputURL)))
        } catch let err as SpeedGateError {
            continuation.yield(.failed(.deviceTooSlow(chunkSeconds: err.chunkSeconds)))
        } catch is CancellationError {
            return
        } catch {
            continuation.yield(.failed(.separationFailed(error.localizedDescription)))
        }
    }

    struct SpeedGateError: Error {
        let chunkSeconds: Double
    }

    /// Fullband separation into a cached 4-channel Quad AAC `.m4a`. Loads `MLModel` locally and lets ARC
    /// deallocate it before returning (Race-to-Idle).
    func separateToQuadM4A(
        inputURL: URL,
        compiledModelURL: URL,
        cacheKey: String,
        onStage: @Sendable (SingStage, Int?) -> Void = { _, _ in }
    ) async throws -> URL {
        try Task.checkCancellation()
        let (mixL, mixR, sr) = try SingDSP.decodeFullbandStereo(url: inputURL)
        try Task.checkCancellation()

        let (mix22L, mix22R) = try SingDSP.resampleStereo(left: mixL, right: mixR, from: sr, to: SingDSP.coreSampleRate)
        let n22 = min(mix22L.count, mix22R.count)

        let specL = SingDSP.stft(mix22L)
        let specR = SingDSP.stft(mix22R)
        let tTot = min(specL.frameCount, specR.frameCount)
        let hiBins = SingDSP.hiBins // 512 (bins 1..512)
        let chunkT = SingDSP.chunkFrames // 256
        let stepT = SingDSP.chunkStep // 160

        var starts: [Int] = []
        var st = 0
        while st <= max(0, tTot - chunkT) {
            starts.append(st)
            st += stepT
        }
        if starts.isEmpty {
            starts.append(0)
        } else if let last = starts.last, last + chunkT < tTot {
            starts.append(max(0, tTot - chunkT))
        }

        let (vocSpecL, vocSpecR) = try runCoreMLChunks(
            compiledModelURL: compiledModelURL,
            specL: specL,
            specR: specR,
            tTot: tTot,
            starts: starts,
            onStage: onStage
        )
        // `MLModel` is now completely released (Race-to-Idle).
        try Task.checkCancellation()

        onStage(.encoding, 2)
        let voc22L = SingDSP.istft(vocSpecL, targetLength: n22)
        let voc22R = SingDSP.istft(vocSpecR, targetLength: n22)
        let (vocFullL, vocFullR) = try SingDSP.resampleStereo(left: voc22L, right: voc22R, from: SingDSP.coreSampleRate, to: sr)

        let nFull = min(mixL.count, mixR.count, vocFullL.count, vocFullR.count)
        var instL = [Float](repeating: 0, count: nFull)
        var instR = [Float](repeating: 0, count: nFull)
        var clippedVocL = [Float](repeating: 0, count: nFull)
        var clippedVocR = [Float](repeating: 0, count: nFull)
        for i in 0..<nFull {
            let vl = vocFullL[i]
            let vr = vocFullR[i]
            clippedVocL[i] = vl
            clippedVocR[i] = vr
            instL[i] = mixL[i] - vl
            instR[i] = mixR[i] - vr
        }
        _ = hiBins

        let tempOut = FileManager.default.temporaryDirectory.appendingPathComponent("sing-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: tempOut) }
        try await SingQuadM4AWriter.write(
            instL: instL,
            instR: instR,
            vocL: clippedVocL,
            vocR: clippedVocR,
            sampleRate: sr,
            to: tempOut
        )
        return try await stemCache.store(tempFile: tempOut, forKey: cacheKey)
    }

    private func runCoreMLChunks(
        compiledModelURL: URL,
        specL: SingDSP.Spectrogram,
        specR: SingDSP.Spectrogram,
        tTot: Int,
        starts: [Int],
        onStage: @Sendable (SingStage, Int?) -> Void
    ) throws -> (SingDSP.Spectrogram, SingDSP.Spectrogram) {
        let config = MLModelConfiguration()
        config.computeUnits = .all
        let model = try MLModel(contentsOf: compiledModelURL, configuration: config)

        let hiBins = SingDSP.hiBins
        let chunkT = SingDSP.chunkFrames
        let frameStride = hiBins * chunkT
        let elemCount = 4 * frameStride

        let inputArray = try MLMultiArray(shape: [1, 4, NSNumber(value: hiBins), NSNumber(value: chunkT)], dataType: .float32)
        let inPtr = inputArray.dataPointer.assumingMemoryBound(to: Float.self)

        var outAcc = [Float](repeating: 0, count: 4 * hiBins * tTot)
        var wtAcc = [Float](repeating: 0, count: tTot)
        let olaWin = SingDSP.chunkOLAWindow

        for (chunkIdx, start) in starts.enumerated() {
            try Task.checkCancellation()
            let valid = min(chunkT, tTot - start)
            memset(inPtr, 0, elemCount * MemoryLayout<Float>.size)

            var sumSq: Double = 0
            for f in 0..<hiBins {
                let srcBin = f + 1 // Drop DC bin 0 → bins 1..512
                let srcRow = srcBin * tTot + start
                let dstRow = f * chunkT
                for t in 0..<valid {
                    let lr = specL.real[srcRow + t]
                    let li = specL.imag[srcRow + t]
                    let rr = specR.real[srcRow + t]
                    let ri = specR.imag[srcRow + t]
                    inPtr[0 * frameStride + dstRow + t] = lr
                    inPtr[1 * frameStride + dstRow + t] = li
                    inPtr[2 * frameStride + dstRow + t] = rr
                    inPtr[3 * frameStride + dstRow + t] = ri
                    sumSq += Double(lr * lr + li * li + rr * rr + ri * ri)
                }
            }
            let rms = Float(sqrt(sumSq / Double(elemCount) + 1e-6))
            let invRms = 1.0 / rms
            for i in 0..<elemCount { inPtr[i] *= invRms }

            let t0 = CFAbsoluteTimeGetCurrent()
            let provider = try MLDictionaryFeatureProvider(dictionary: ["mix_spec": MLFeatureValue(multiArray: inputArray)])
            let prediction = try model.prediction(from: provider)
            let elapsed = CFAbsoluteTimeGetCurrent() - t0

            if chunkIdx == 0 {
                if elapsed > Self.maxAllowedChunkSeconds {
                    throw SpeedGateError(chunkSeconds: elapsed)
                }
                let estRemain = max(1, Int((elapsed * Double(max(0, starts.count - 1)) + 2.0).rounded()))
                onStage(.separating, estRemain)
            }

            guard let outArray = prediction.featureValue(for: "voc_spec")?.multiArrayValue else {
                throw NSError(domain: "SingCoreMLSeparator", code: -20, userInfo: [NSLocalizedDescriptionKey: "Missing voc_spec output"])
            }
            let outPtr = outArray.dataPointer.assumingMemoryBound(to: Float.self)
            for c in 0..<4 {
                let cInOff = c * frameStride
                let cOutOff = c * hiBins * tTot
                for f in 0..<hiBins {
                    let inRow = cInOff + f * chunkT
                    let outRow = cOutOff + f * tTot + start
                    for t in 0..<valid {
                        outAcc[outRow + t] += (outPtr[inRow + t] * rms) * olaWin[t]
                    }
                }
            }
            for t in 0..<valid {
                wtAcc[start + t] += olaWin[t]
            }
        }

        // Restore zero DC bin (index 0) and unpack into L/R complex spectrograms.
        var vocRealL = [Float](repeating: 0, count: SingDSP.nBins * tTot)
        var vocImagL = [Float](repeating: 0, count: SingDSP.nBins * tTot)
        var vocRealR = [Float](repeating: 0, count: SingDSP.nBins * tTot)
        var vocImagR = [Float](repeating: 0, count: SingDSP.nBins * tTot)
        let plane = hiBins * tTot
        for f in 0..<hiBins {
            let dstRow = (f + 1) * tTot
            let srcRow = f * tTot
            for t in 0..<tTot {
                let w = max(wtAcc[t], 1e-8)
                let invW = 1.0 / w
                vocRealL[dstRow + t] = outAcc[0 * plane + srcRow + t] * invW
                vocImagL[dstRow + t] = outAcc[1 * plane + srcRow + t] * invW
                vocRealR[dstRow + t] = outAcc[2 * plane + srcRow + t] * invW
                vocImagR[dstRow + t] = outAcc[3 * plane + srcRow + t] * invW
            }
        }
        return (
            SingDSP.Spectrogram(binCount: SingDSP.nBins, frameCount: tTot, real: vocRealL, imag: vocImagL),
            SingDSP.Spectrogram(binCount: SingDSP.nBins, frameCount: tTot, real: vocRealR, imag: vocImagR)
        )
    }
}
