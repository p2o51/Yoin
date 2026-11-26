import Compression
import CoreML
import CryptoKit
import Foundation
import UniformTypeIdentifiers
import os

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

    /// Total bytes occupied by the compiled `.mlmodelc` directory on disk, or `nil` when not installed.
    func installedSizeBytes() -> Int64? {
        guard isInstalled else { return nil }
        let fm = FileManager.default
        guard let enumerator = fm.enumerator(
            at: compiledModelURL,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey]
        ) else {
            return 0
        }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            guard let vals = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                  vals.isRegularFile == true,
                  let size = vals.fileSize else { continue }
            total += Int64(size)
        }
        return total
    }

    /// Deletes the compiled `.mlmodelc` directory from `Application Support` (used by Yoin Labs).
    func removeInstalledModel() throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: compiledModelURL.path) {
            try fm.removeItem(at: compiledModelURL)
        }
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

    /// Verifies (optional) SHA-256, extracts `LyraSingLite.mlpackage.zip` from an ODR or local file URL,
    /// and compiles `LyraSingLite.mlmodelc` once into `Application Support`.
    func installFromLocalZip(_ zipURL: URL, verifyChecksum: Bool = true) async throws -> URL {
        if isInstalled { return compiledModelURL }
        try FileManager.default.createDirectory(at: baseDirectory, withIntermediateDirectories: true)

        if verifyChecksum {
            let actualSha = try Self.sha256Hex(of: zipURL)
            guard actualSha == expectedSha256 else {
                throw SingModelError.checksumMismatch(expected: expectedSha256, actual: actualSha)
            }
        }

        let unpackDir = baseDirectory.appendingPathComponent("unpack-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: unpackDir) }
        try SingZipExtractor.extract(zipURL: zipURL, to: unpackDir)

        let pkgURL = unpackDir.appendingPathComponent(SingModelAsset.packageName, isDirectory: true)
        guard FileManager.default.fileExists(atPath: pkgURL.path) else {
            throw NSError(domain: "SingModelStore", code: -11, userInfo: [NSLocalizedDescriptionKey: "Missing \(SingModelAsset.packageName) in archive"])
        }
        return try await installFromLocalPackage(pkgURL)
    }

    private static func sha256Hex(of fileURL: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            try Task.checkCancellation()
            guard let chunk = try handle.read(upToCount: 65_536), !chunk.isEmpty else { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
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
/// - Acquires `LyraSingLite.mlmodelc` on demand via Apple ODR (`sing_model_v1`, `computeUnits = .all`)
///   with fallback to `SingModelStore` (local dev/test `.mlpackage` or HTTPS SHA-256 ZIP).
/// - Single fullband decode (zero MP3 priming offset)
/// - 22,050 Hz STFT → `[1, 4, 512, 256]` RMS-normalized CoreML chunks (ANE stride-safe) → Hann OLA → iSTFT → upsample
/// - `I_full = X_full - V_full` (100% untouched sub-bass `<85 Hz` and air/cymbals `>10.5 kHz`)
/// - Releases `MLModel` + ODR access immediately after inference (`0%` idle memory), then writes 4ch Quad AAC `.m4a` into `SingStemCache`.
final class SingCoreMLSeparator: SingStreamProvider, @unchecked Sendable {
    private static let logger = Logger(subsystem: "com.gpo.yoin", category: "SingCoreML")

    let odrProvider: SingModelODRProvider?
    let modelStore: SingModelStore
    let stemCache: SingStemCache
    private let remoteModelZipURL: URL?

    /// Speed-gate threshold from the handoff spec: 1 chunk (`2.97 s`) taking `> 2.0 s` (`RTF > 0.7`) is too slow.
    static let maxAllowedChunkSeconds: Double = 2.0

    /// Per-song timing telemetry captured during `separateToQuadM4A` (useful for on-device ANE profiling).
    struct SeparationMetrics: Sendable {
        let modelSource: String
        let odrCached: Bool
        let modelAcquireMs: Double
        let decodeStftMs: Double
        let chunkCount: Int
        let firstChunkMs: Double
        let meanChunkMs: Double
        let p50ChunkMs: Double
        let inferTotalMs: Double
        let istftResampleMs: Double
        let encodeMs: Double
        let totalMs: Double
    }

    init(
        odrProvider: SingModelODRProvider? = nil,
        modelStore: SingModelStore = SingModelStore(),
        stemCache: SingStemCache = SingStemCache(),
        remoteModelZipURL: URL? = nil
    ) {
        self.modelStore = modelStore
        self.odrProvider = odrProvider ?? SingModelODRProvider(modelStore: modelStore)
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
            Self.logger.info("Stem cache hit for \(songId, privacy: .public)")
            continuation.yield(.ready(source: .localFile(cached)))
            return
        }
        guard let audioURL = localAudioURL else {
            continuation.yield(.failed(.separationFailed("Audio source URL unavailable")))
            return
        }
        if ProcessInfo.processInfo.thermalState == .critical {
            continuation.yield(.failed(.thermalThrottled))
            return
        }

        // If the track is streaming (not offline-downloaded), reuse a complete disk-cached stream from
        // `AudioCache` via an extension-tagged hard-link when available; otherwise fetch to a temp file.
        let decodeInputURL: URL
        var tempDownloadedAudio: URL?
        if audioURL.isFileURL {
            decodeInputURL = audioURL
        } else if let cached = AudioCache.shared.completeCachedFile(for: audioURL),
                  let linked = try? Self.stageCachedAudioFile(cached.url, contentType: cached.contentType, originURL: audioURL) {
            Self.logger.info("Reusing complete AudioCache stream for \(songId, privacy: .public)")
            decodeInputURL = linked
            tempDownloadedAudio = linked
        } else {
            continuation.yield(.preparing(stage: .warming, etaSeconds: nil))
            do {
                let fetched = try await fetchRemoteAudioToTempFile(from: audioURL)
                decodeInputURL = fetched
                tempDownloadedAudio = fetched
            } catch is CancellationError {
                return
            } catch {
                Self.logger.error("Audio download failed for \(songId, privacy: .public): \(error.localizedDescription, privacy: .public)")
                continuation.yield(.failed(.separationFailed("Audio download failed: \(error.localizedDescription)")))
                return
            }
        }
        defer {
            if let tempDownloadedAudio {
                try? FileManager.default.removeItem(at: tempDownloadedAudio)
            }
        }

        let acquireStart = CFAbsoluteTimeGetCurrent()
        let lease: SingModelLease
        do {
            lease = try await acquireModelLease(continuation: continuation)
        } catch is CancellationError {
            return
        } catch SingModelStore.SingModelError.checksumMismatch {
            continuation.yield(.failed(.modelChecksumMismatch))
            return
        } catch let err as ModelUnavailableError {
            if err.needsDownload {
                continuation.yield(.needsModelDownload)
            } else {
                continuation.yield(.failed(.modelDownloadFailed(err.message)))
            }
            return
        } catch {
            continuation.yield(.failed(.modelDownloadFailed(error.localizedDescription)))
            return
        }
        let acquireMs = (CFAbsoluteTimeGetCurrent() - acquireStart) * 1000.0

        continuation.yield(.preparing(stage: .warming, etaSeconds: nil))
        do {
            let (outputURL, _) = try await separateToQuadM4A(
                inputURL: decodeInputURL,
                modelLease: lease,
                modelAcquireMs: acquireMs,
                cacheKey: cacheKey,
                onStage: { stage, eta in
                    continuation.yield(.preparing(stage: stage, etaSeconds: eta))
                }
            )
            continuation.yield(.ready(source: .localFile(outputURL)))
        } catch let err as SpeedGateError {
            lease.release()
            continuation.yield(.failed(.deviceTooSlow(chunkSeconds: err.chunkSeconds)))
        } catch is CancellationError {
            lease.release()
            return
        } catch {
            lease.release()
            continuation.yield(.failed(.separationFailed(error.localizedDescription)))
        }
    }

    private struct ModelUnavailableError: Error {
        let needsDownload: Bool
        let message: String
    }

    private static func stageCachedAudioFile(_ cachedBinURL: URL, contentType: String?, originURL: URL) throws -> URL {
        let ext = inferAudioExtension(contentType: contentType, url: originURL)
        let staged = FileManager.default.temporaryDirectory
            .appendingPathComponent("sing-cached-\(UUID().uuidString).\(ext)")
        try? FileManager.default.removeItem(at: staged)
        do {
            try FileManager.default.linkItem(at: cachedBinURL, to: staged)
        } catch {
            try FileManager.default.copyItem(at: cachedBinURL, to: staged)
        }
        return staged
    }

    private static func inferAudioExtension(contentType: String?, url: URL) -> String {
        if let uti = contentType {
            if uti == "org.xiph.flac" || uti.lowercased().contains("flac") { return "flac" }
            if let ext = UTType(uti)?.preferredFilenameExtension?.lowercased(), !ext.isEmpty {
                return ext
            }
        }
        if let format = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
            .first(where: { $0.name == "format" })?.value?.lowercased(),
           !format.isEmpty, format != "raw" {
            return format
        }
        let urlExt = url.pathExtension.lowercased()
        if !urlExt.isEmpty && urlExt != "view" { return urlExt }
        return "flac"
    }

    private func fetchRemoteAudioToTempFile(from remoteURL: URL) async throws -> URL {
        var req = URLRequest(url: remoteURL)
        req.timeoutInterval = 30
        let (tmpURL, response) = try await URLSession.shared.download(for: req)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            try? FileManager.default.removeItem(at: tmpURL)
            throw NSError(domain: "SingCoreMLSeparator", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "HTTP \(http.statusCode)"])
        }
        let ext = Self.inferAudioExtension(response: response, url: remoteURL)
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("sing-src-\(UUID().uuidString).\(ext)")
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmpURL, to: dest)
        return dest
    }

    private static func inferAudioExtension(response: URLResponse, url: URL) -> String {
        if let mime = response.mimeType?.lowercased() {
            if mime.contains("flac") { return "flac" }
            if mime.contains("mpeg") || mime.contains("mp3") { return "mp3" }
            if mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") { return "m4a" }
            if mime.contains("wav") { return "wav" }
            if mime.contains("aiff") { return "aiff" }
            if mime.contains("ogg") || mime.contains("opus") { return "ogg" }
        }
        if let suggested = response.suggestedFilename {
            let ext = URL(fileURLWithPath: suggested).pathExtension.lowercased()
            if !ext.isEmpty && ext != "tmp" && ext != "bin" { return ext }
        }
        let urlExt = url.pathExtension.lowercased()
        if !urlExt.isEmpty && urlExt != "view" { return urlExt }
        return "flac"
    }

    /// Primary acquisition: Apple ODR (`sing_model_v1`, `computeUnits = .all`).
    /// Fallback: pre-installed local `.mlmodelc` in `SingModelStore` or optional HTTPS ZIP download.
    private func acquireModelLease(
        continuation: AsyncStream<SingState>.Continuation
    ) async throws -> SingModelLease {
        var odrFailureMessage: String?
        if let odrProvider {
            continuation.yield(.downloadingModel(progress: 0))
            do {
                return try await odrProvider.loadModel { progress in
                    continuation.yield(.downloadingModel(progress: progress))
                }
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                odrFailureMessage = error.localizedDescription
                Self.logger.notice("ODR load failed (\(error.localizedDescription, privacy: .public)); checking local fallback store")
            }
        }

        let compiledURL: URL
        if await modelStore.isInstalled {
            compiledURL = await modelStore.compiledModelURL
        } else if let remoteModelZipURL {
            continuation.yield(.downloadingModel(progress: 0))
            compiledURL = try await modelStore.downloadAndInstall(from: remoteModelZipURL) { progress in
                continuation.yield(.downloadingModel(progress: progress))
            }
        } else if let odrFailureMessage {
            throw ModelUnavailableError(needsDownload: false, message: odrFailureMessage)
        } else {
            throw ModelUnavailableError(needsDownload: true, message: "Sing model not installed")
        }

        let config = MLModelConfiguration()
        config.computeUnits = .all
        let model = try await MLModel.load(contentsOf: compiledURL, configuration: config)
        return SingModelLease(model: model, sourceDescription: "CoreML LocalStore", odrCached: true)
    }

    struct SpeedGateError: Error {
        let chunkSeconds: Double
    }

    /// Convenience entry point for tests / local `.mlmodelc` verification.
    func separateToQuadM4A(
        inputURL: URL,
        compiledModelURL: URL,
        cacheKey: String,
        onStage: @Sendable (SingStage, Int?) -> Void = { _, _ in }
    ) async throws -> URL {
        let t0 = CFAbsoluteTimeGetCurrent()
        let config = MLModelConfiguration()
        config.computeUnits = .all
        let model = try await MLModel.load(contentsOf: compiledModelURL, configuration: config)
        let loadMs = (CFAbsoluteTimeGetCurrent() - t0) * 1000.0
        let lease = SingModelLease(model: model, sourceDescription: "CoreML Direct", odrCached: true)
        let (url, _) = try await separateToQuadM4A(
            inputURL: inputURL,
            modelLease: lease,
            modelAcquireMs: loadMs,
            cacheKey: cacheKey,
            onStage: onStage
        )
        return url
    }

    /// Fullband separation into a cached 4-channel Quad AAC `.m4a`.
    /// Releases `modelLease` (dropping `MLModel` + calling `endAccessingResources()`) as soon as
    /// CoreML inference completes — before AAC encoding begins (Race-to-Idle).
    func separateToQuadM4A(
        inputURL: URL,
        modelLease: SingModelLease,
        modelAcquireMs: Double = 0,
        cacheKey: String,
        onStage: @Sendable (SingStage, Int?) -> Void = { _, _ in }
    ) async throws -> (URL, SeparationMetrics) {
        let totalStart = CFAbsoluteTimeGetCurrent()
        defer { modelLease.release() }

        try Task.checkCancellation()
        let decodeStart = CFAbsoluteTimeGetCurrent()
        let (mixL, mixR, sr) = try SingDSP.decodeFullbandStereo(url: inputURL)
        try Task.checkCancellation()

        let (mix22L, mix22R) = try SingDSP.resampleStereo(left: mixL, right: mixR, from: sr, to: SingDSP.coreSampleRate)
        let n22 = min(mix22L.count, mix22R.count)

        let specL = SingDSP.stft(mix22L)
        let specR = SingDSP.stft(mix22R)
        let decodeStftMs = (CFAbsoluteTimeGetCurrent() - decodeStart) * 1000.0

        let tTot = min(specL.frameCount, specR.frameCount)
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

        let inferStart = CFAbsoluteTimeGetCurrent()
        let (vocSpecL, vocSpecR, chunkTimesMs) = try runCoreMLChunks(
            model: modelLease.model,
            specL: specL,
            specR: specR,
            tTot: tTot,
            starts: starts,
            onStage: onStage
        )
        let inferTotalMs = (CFAbsoluteTimeGetCurrent() - inferStart) * 1000.0
        // Race-to-Idle: release ODR resource access right after inference burst.
        modelLease.release()
        try Task.checkCancellation()

        onStage(.encoding, 2)
        let istftStart = CFAbsoluteTimeGetCurrent()
        let outSr = SingDSP.outputSampleRate(for: sr)
        let (outMixL, outMixR): ([Float], [Float])
        if outSr == sr {
            (outMixL, outMixR) = (mixL, mixR)
        } else {
            (outMixL, outMixR) = try SingDSP.resampleStereo(left: mixL, right: mixR, from: sr, to: outSr)
        }
        let voc22L = SingDSP.istft(vocSpecL, targetLength: n22)
        let voc22R = SingDSP.istft(vocSpecR, targetLength: n22)
        let (vocFullL, vocFullR) = try SingDSP.resampleStereo(left: voc22L, right: voc22R, from: SingDSP.coreSampleRate, to: outSr)

        let nFull = min(outMixL.count, outMixR.count, vocFullL.count, vocFullR.count)
        var instL = [Float](repeating: 0, count: nFull)
        var instR = [Float](repeating: 0, count: nFull)
        var clippedVocL = [Float](repeating: 0, count: nFull)
        var clippedVocR = [Float](repeating: 0, count: nFull)
        for i in 0..<nFull {
            let vl = vocFullL[i]
            let vr = vocFullR[i]
            clippedVocL[i] = vl
            clippedVocR[i] = vr
            instL[i] = outMixL[i] - vl
            instR[i] = outMixR[i] - vr
        }
        let istftResampleMs = (CFAbsoluteTimeGetCurrent() - istftStart) * 1000.0

        let encodeStart = CFAbsoluteTimeGetCurrent()
        let tempOut = FileManager.default.temporaryDirectory.appendingPathComponent("sing-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: tempOut) }
        try await SingQuadM4AWriter.write(
            instL: instL,
            instR: instR,
            vocL: clippedVocL,
            vocR: clippedVocR,
            sampleRate: outSr,
            to: tempOut
        )
        let storedURL = try await stemCache.store(tempFile: tempOut, forKey: cacheKey)
        let encodeMs = (CFAbsoluteTimeGetCurrent() - encodeStart) * 1000.0
        let totalMs = (CFAbsoluteTimeGetCurrent() - totalStart) * 1000.0

        let sortedChunks = chunkTimesMs.sorted()
        let firstChunkMs = chunkTimesMs.first ?? 0
        let steadyChunks = chunkTimesMs.count > 1 ? Array(chunkTimesMs.dropFirst()) : chunkTimesMs
        let meanChunkMs = steadyChunks.isEmpty ? 0 : steadyChunks.reduce(0, +) / Double(steadyChunks.count)
        let sortedSteady = steadyChunks.sorted()
        let p50ChunkMs = sortedSteady.isEmpty ? (sortedChunks.first ?? 0) : sortedSteady[sortedSteady.count / 2]

        let metrics = SeparationMetrics(
            modelSource: modelLease.sourceDescription,
            odrCached: modelLease.odrCached,
            modelAcquireMs: modelAcquireMs,
            decodeStftMs: decodeStftMs,
            chunkCount: chunkTimesMs.count,
            firstChunkMs: firstChunkMs,
            meanChunkMs: meanChunkMs,
            p50ChunkMs: p50ChunkMs,
            inferTotalMs: inferTotalMs,
            istftResampleMs: istftResampleMs,
            encodeMs: encodeMs,
            totalMs: totalMs
        )
        let summary = String(
            format: "[SingCoreML] source=%@ odrCached=%@ acquire=%.1fms decodeStft=%.1fms chunks=%d first=%.2fms mean=%.2fms p50=%.2fms infer=%.1fms istft=%.1fms encode=%.1fms total=%.1fms",
            metrics.modelSource,
            metrics.odrCached ? "yes" : "no",
            metrics.modelAcquireMs,
            metrics.decodeStftMs,
            metrics.chunkCount,
            metrics.firstChunkMs,
            metrics.meanChunkMs,
            metrics.p50ChunkMs,
            metrics.inferTotalMs,
            metrics.istftResampleMs,
            metrics.encodeMs,
            metrics.totalMs
        )
        Self.logger.info("\(summary, privacy: .public)")
        #if DEBUG
        print(summary)
        #endif

        return (storedURL, metrics)
    }

    private func runCoreMLChunks(
        model: MLModel,
        specL: SingDSP.Spectrogram,
        specR: SingDSP.Spectrogram,
        tTot: Int,
        starts: [Int],
        onStage: @Sendable (SingStage, Int?) -> Void
    ) throws -> (SingDSP.Spectrogram, SingDSP.Spectrogram, [Double]) {
        let hiBins = SingDSP.hiBins
        let chunkT = SingDSP.chunkFrames
        let frameStride = hiBins * chunkT
        let elemCount = 4 * frameStride

        let inputArray = try MLMultiArray(shape: [1, 4, NSNumber(value: hiBins), NSNumber(value: chunkT)], dataType: .float32)
        let inStrides = inputArray.strides.map(\.intValue)
        let inS1 = inStrides.count >= 4 ? inStrides[1] : frameStride
        let inS2 = inStrides.count >= 4 ? inStrides[2] : chunkT
        let inS3 = inStrides.count >= 4 ? inStrides[3] : 1
        let inContiguous = (inS3 == 1 && inS2 == chunkT && inS1 == frameStride)
        let inPtr = inputArray.dataPointer.assumingMemoryBound(to: Float.self)
        let inBackingCount = max(elemCount, 4 * inS1)

        var outAcc = [Float](repeating: 0, count: 4 * hiBins * tTot)
        var wtAcc = [Float](repeating: 0, count: tTot)
        let olaWin = SingDSP.chunkOLAWindow
        var chunkTimesMs: [Double] = []
        chunkTimesMs.reserveCapacity(starts.count)

        for (chunkIdx, start) in starts.enumerated() {
            try Task.checkCancellation()
            let valid = min(chunkT, tTot - start)
            memset(inPtr, 0, inBackingCount * MemoryLayout<Float>.size)

            var sumSq: Double = 0
            if inContiguous {
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
                let (outArray, elapsed) = try predictChunk(model: model, inputArray: inputArray)
                chunkTimesMs.append(elapsed * 1000.0)
                try handleChunkTiming(chunkIdx: chunkIdx, elapsed: elapsed, totalChunks: starts.count, onStage: onStage)
                accumulateOutput(outArray: outArray, rms: rms, hiBins: hiBins, chunkT: chunkT, tTot: tTot, start: start, valid: valid, olaWin: olaWin, outAcc: &outAcc)
            } else {
                for f in 0..<hiBins {
                    let srcBin = f + 1
                    let srcRow = srcBin * tTot + start
                    let dstRow = f * inS2
                    for t in 0..<valid {
                        let lr = specL.real[srcRow + t]
                        let li = specL.imag[srcRow + t]
                        let rr = specR.real[srcRow + t]
                        let ri = specR.imag[srcRow + t]
                        let col = dstRow + t * inS3
                        inPtr[0 * inS1 + col] = lr
                        inPtr[1 * inS1 + col] = li
                        inPtr[2 * inS1 + col] = rr
                        inPtr[3 * inS1 + col] = ri
                        sumSq += Double(lr * lr + li * li + rr * rr + ri * ri)
                    }
                }
                let rms = Float(sqrt(sumSq / Double(elemCount) + 1e-6))
                let invRms = 1.0 / rms
                for c in 0..<4 {
                    let cOff = c * inS1
                    for f in 0..<hiBins {
                        let row = cOff + f * inS2
                        for t in 0..<valid {
                            inPtr[row + t * inS3] *= invRms
                        }
                    }
                }
                let (outArray, elapsed) = try predictChunk(model: model, inputArray: inputArray)
                chunkTimesMs.append(elapsed * 1000.0)
                try handleChunkTiming(chunkIdx: chunkIdx, elapsed: elapsed, totalChunks: starts.count, onStage: onStage)
                accumulateOutput(outArray: outArray, rms: rms, hiBins: hiBins, chunkT: chunkT, tTot: tTot, start: start, valid: valid, olaWin: olaWin, outAcc: &outAcc)
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
            SingDSP.Spectrogram(binCount: SingDSP.nBins, frameCount: tTot, real: vocRealR, imag: vocImagR),
            chunkTimesMs
        )
    }

    private func predictChunk(model: MLModel, inputArray: MLMultiArray) throws -> (MLMultiArray, Double) {
        let t0 = CFAbsoluteTimeGetCurrent()
        let provider = try MLDictionaryFeatureProvider(dictionary: ["mix_spec": MLFeatureValue(multiArray: inputArray)])
        let prediction = try model.prediction(from: provider)
        let elapsed = CFAbsoluteTimeGetCurrent() - t0
        guard let outArray = prediction.featureValue(for: "voc_spec")?.multiArrayValue else {
            throw NSError(domain: "SingCoreMLSeparator", code: -20, userInfo: [NSLocalizedDescriptionKey: "Missing voc_spec output"])
        }
        return (outArray, elapsed)
    }

    private func handleChunkTiming(
        chunkIdx: Int,
        elapsed: Double,
        totalChunks: Int,
        onStage: @Sendable (SingStage, Int?) -> Void
    ) throws {
        // Chunk 0 on a fresh install/cold CoreML load includes one-time ANE/GPU program specialization
        // (~1-3 s on real iPhones), so only enforce the speed gate on steady-state chunk 1+.
        if chunkIdx == 0 {
            onStage(.separating, nil)
        } else if chunkIdx == 1 {
            if elapsed > Self.maxAllowedChunkSeconds {
                throw SpeedGateError(chunkSeconds: elapsed)
            }
            let estRemain = max(1, Int((elapsed * Double(max(0, totalChunks - 2)) + 1.0).rounded()))
            onStage(.separating, estRemain)
        }
    }

    /// Accumulates `voc_spec` into the Hann OLA spectrogram buffer while honoring ANE's potentially padded
    /// `MLMultiArray.strides` (64-byte row alignment on Apple Neural Engine).
    private func accumulateOutput(
        outArray: MLMultiArray,
        rms: Float,
        hiBins: Int,
        chunkT: Int,
        tTot: Int,
        start: Int,
        valid: Int,
        olaWin: [Float],
        outAcc: inout [Float]
    ) {
        let frameStride = hiBins * chunkT
        let strides = outArray.strides.map(\.intValue)
        let s1 = strides.count >= 4 ? strides[1] : frameStride
        let s2 = strides.count >= 4 ? strides[2] : chunkT
        let s3 = strides.count >= 4 ? strides[3] : 1

        if outArray.dataType == .float32 {
            let outPtr = outArray.dataPointer.assumingMemoryBound(to: Float.self)
            if s3 == 1 && s2 == chunkT && s1 == frameStride {
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
            } else {
                for c in 0..<4 {
                    let cInOff = c * s1
                    let cOutOff = c * hiBins * tTot
                    for f in 0..<hiBins {
                        let inRow = cInOff + f * s2
                        let outRow = cOutOff + f * tTot + start
                        for t in 0..<valid {
                            outAcc[outRow + t] += (outPtr[inRow + t * s3] * rms) * olaWin[t]
                        }
                    }
                }
            }
        } else {
            // Defensive fallback if CoreML ever delivers Float16/Double multiarray.
            for c in 0..<4 {
                let cOutOff = c * hiBins * tTot
                for f in 0..<hiBins {
                    let outRow = cOutOff + f * tTot + start
                    for t in 0..<valid {
                        let idx = c * s1 + f * s2 + t * s3
                        let v = outArray[idx].floatValue
                        outAcc[outRow + t] += (v * rms) * olaWin[t]
                    }
                }
            }
        }
    }
}
