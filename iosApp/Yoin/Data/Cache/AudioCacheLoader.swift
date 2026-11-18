import AVFoundation
import UniformTypeIdentifiers
import os

/// One request AVFoundation makes of a cached stream: content information,
/// a byte range, or both. `AVAssetResourceLoadingRequest` behind an adapter,
/// so tests can drive the loader without AVFoundation.
protocol CacheLoadingRequest: AnyObject {
    /// Content information is asked for and not yet given.
    var wantsInfo: Bool { get }
    func fillInfo(length: Int64, contentType: String, byteRanges: Bool, wholeOnDisk: Bool)
    /// The bytes asked for: from [offset], [length] of them (nil = to the end); nil = information only.
    var dataRange: (offset: Int64, length: Int64?)? { get }
    /// Where the next byte answered lands.
    var currentOffset: Int64 { get }
    func respond(_ data: Data)
    func finish()
    func fail(_ error: Error)
    var isDone: Bool { get }
}

/// The server's answer to one fetch.
struct UpstreamResponse: Sendable {
    var status: Int
    /// Bytes in this body (-1 = unknown).
    var contentLength: Int64
    /// `Content-Range: bytes a-b/total`.
    var rangeStart: Int64?
    var totalLength: Int64?
    var mimeType: String?
}

/// The network under the cache. Every callback lands on the cache's queue.
protocol CacheUpstream: AnyObject {
    /// GET [url], from byte [offset] on when given (a `Range` header), else the whole body.
    func fetch(_ url: URL, from offset: Int64?, onResponse: @escaping (UpstreamResponse) -> Void,
               onData: @escaping (Data) -> Void, onComplete: @escaping (Error?) -> Void) -> CacheUpstreamTask
}

protocol CacheUpstreamTask: AnyObject {
    func cancel()
}

enum AudioCacheError: Error, LocalizedError {
    case http(Int)
    case noContentType

    var errorDescription: String? {
        switch self {
        case .http(let code): "Server answered \(code)"
        case .noContentType: "Unknown audio type"
        }
    }
}

/// Serves one stream's requests from [AudioCache] and fills the gaps from
/// the server (Android `CacheDataSource`): a ranged stream fetches from the
/// first byte missing, a transcode ([sequential]) downloads front to back
/// once and the requests read along behind it. Offline, a whole stream
/// needs no network at all. Queue only (the cache's).
final class AudioCacheLoader: @unchecked Sendable {
    let key: String
    let origin: URL
    /// A transcode: no byte ranges, kept only once whole.
    private(set) var sequential: Bool
    private let cache: AudioCache
    private let upstream: CacheUpstream
    private let log = Logger(subsystem: "com.gpo.yoin", category: "audiocache")

    private final class Fetch {
        let start: Int64
        var next: Int64
        var task: CacheUpstreamTask?
        var answered = false
        init(start: Int64) { self.start = start; next = start }
    }

    private var requests: [CacheLoadingRequest] = []
    private var fetches: [Fetch] = []
    /// A fetch just failed: the requests waiting on its bytes fail with it.
    private var failure: Error?
    /// A transcode's one download ended (whole or not).
    private var sequentialDone = false
    /// …and why it ended short, when it did: every later request fails with it.
    private var sequentialError: Error?
    private var closed = false
    /// Bytes fetched that the cache couldn't write (full or unwritable disk).
    private var spill = SpillBuffer()
    static let spillLimit = 32 * 1024 * 1024

    /// How far ahead of a running fetch a request waits instead of fetching on its own.
    static let lookahead: Int64 = 512 * 1024
    static let chunk = 256 * 1024
    static let maxFetches = 3
    /// A transcode whose server gives no length: a figure large enough to read to the end.
    static let unknownLength: Int64 = 1 << 30

    init(key: String, origin: URL, sequential: Bool, cache: AudioCache, upstream: CacheUpstream) {
        self.key = key
        self.origin = origin
        self.cache = cache
        self.upstream = upstream
        let e = cache.lease(key)
        // A server that answered 200 to a range before is read front to back again.
        self.sequential = sequential || (!e.byteRanges && !e.complete)
        if e.complete { log.info("whole on disk: \(e.length ?? 0) bytes") }
    }

    func add(_ request: CacheLoadingRequest) {
        guard !closed else { request.fail(CancellationError()); return }
        requests.append(request)
        pump()
    }

    func cancel(_ request: CacheLoadingRequest) {
        requests.removeAll { $0 === request }
        pump()
    }

    /// The player let go: stop fetching and give the stream back to the cache.
    func close() {
        guard !closed else { return }
        closed = true
        for f in fetches { f.task?.cancel() }
        fetches = []
        for r in requests where !r.isDone { r.fail(CancellationError()) }
        requests = []
        cache.release(key)
    }

    // MARK: Serving

    private func pump() {
        guard !closed else { return }
        let e = cache.entry(key)
        for r in requests {
            serve(r, e)
        }
        requests.removeAll { $0.isDone }
        failure = nil
        dropIdleFetches()
        // Memory is only a stand-in for the disk: keep what the requests still ahead may read.
        if spill.bytes > Self.spillLimit, let low = requests.filter({ $0.dataRange != nil }).map(\.currentOffset).min() {
            spill.drop(below: low)
        }
    }

    private func serve(_ r: CacheLoadingRequest, _ e: AudioCache.Entry?) {
        guard !r.isDone else { return }
        if r.wantsInfo {
            guard let e, let type = e.contentType, let length = e.length ?? (sequential ? Self.unknownLength : nil) else {
                if let error = failure ?? sequentialError { r.fail(error); return }
                need(0)
                return
            }
            r.fillInfo(length: length, contentType: type, byteRanges: e.complete || !sequential, wholeOnDisk: e.complete)
        }
        guard let range = r.dataRange else { r.finish(); return }
        var end = range.length.map { range.offset + $0 } ?? Int64.max
        if let length = cache.entry(key)?.length, !(sequential && !sequentialDone && !(e?.complete ?? false)) {
            end = min(end, length)
        }
        while !r.isDone {
            let at = r.currentOffset
            if at >= end { r.finish(); return }
            let n = min(cache.available(key, from: at), end - at, Int64(Self.chunk))
            if n > 0, let data = cache.read(key, at: at, count: Int(n)), !data.isEmpty {
                r.respond(data)
                continue
            }
            if let data = spill.read(at: at, max: Int(min(end - at, Int64(Self.chunk)))) {
                r.respond(data)
                continue
            }
            if let error = sequentialError ?? failure { r.fail(error); return }
            if sequential && sequentialDone || cache.entry(key)?.complete == true {
                // The stream ended short of what was asked (a transcode under its estimate).
                r.finish()
                return
            }
            need(at)
            return
        }
    }

    /// Makes sure a fetch is bringing byte [offset].
    private func need(_ offset: Int64) {
        if sequential {
            if fetches.isEmpty && !sequentialDone { start(at: 0) }
            return
        }
        if fetches.contains(where: { $0.next <= offset && offset - $0.next <= Self.lookahead }) { return }
        if fetches.count >= Self.maxFetches, let idle = fetches.first(where: { !isWaitedOn($0) }) {
            stop(idle)
        }
        start(at: offset)
    }

    private func isWaitedOn(_ f: Fetch) -> Bool {
        requests.contains { r in
            guard !r.isDone, r.dataRange != nil else { return r.wantsInfo && f.start == 0 }
            let at = r.currentOffset
            return f.next <= at && at - f.next <= Self.lookahead
        }
    }

    /// A ranged fetch that ran into bytes already held, with no request
    /// waiting on it, has nothing left to bring.
    private func dropIdleFetches() {
        guard !sequential else { return }
        for f in fetches where f.answered && !isWaitedOn(f)
            && (cache.available(key, from: f.next) > 0 || spill.read(at: f.next, max: 1) != nil) {
            stop(f)
        }
        if cache.entry(key)?.complete == true {
            for f in fetches { stop(f) }
        }
    }

    private func stop(_ f: Fetch) {
        f.task?.cancel()
        fetches.removeAll { $0 === f }
    }

    private func start(at offset: Int64) {
        let f = Fetch(start: offset)
        fetches.append(f)
        log.info("fetch from \(offset) \(self.sequential ? "sequential" : "ranged", privacy: .public)")
        f.task = upstream.fetch(origin, from: sequential ? nil : offset,
            onResponse: { [weak self, weak f] response in
                guard let self, let f else { return }
                self.answered(f, response)
            },
            onData: { [weak self, weak f] data in
                guard let self, let f, self.fetches.contains(where: { $0 === f }) else { return }
                // Audio never depends on the disk: bytes the cache couldn't store play from memory.
                if !self.cache.write(self.key, at: f.next, data) { self.spill.write(at: f.next, data) }
                f.next += Int64(data.count)
                self.pump()
            },
            onComplete: { [weak self, weak f] error in
                guard let self, let f else { return }
                self.completed(f, error)
            })
    }

    private func answered(_ f: Fetch, _ r: UpstreamResponse) {
        guard fetches.contains(where: { $0 === f }) else { return }
        f.answered = true
        let type = Self.uti(mime: r.mimeType, url: origin)
        switch r.status {
        case 206 where !sequential:
            let start = r.rangeStart ?? f.start
            f.next = start
            cache.setInfo(key, length: r.totalLength, contentType: type, byteRanges: true)
        case 200, 206:
            // (A 206 here answers a transcode's plain GET: read it as a whole body.)
            if !sequential {
                // The server ignores ranges: read it front to back from here on.
                sequential = true
                for other in fetches where other !== f { stop(other) }
                if f.start != 0 {
                    stop(f)
                    cache.setInfo(key, length: nil, contentType: type, byteRanges: false)
                    start(at: 0)
                    pump()
                    return
                }
            }
            f.next = r.status == 206 ? (r.rangeStart ?? 0) : 0
            cache.setInfo(key, length: r.contentLength > 0 ? r.contentLength : nil, contentType: type, byteRanges: false)
        default:
            stop(f)
            failure = AudioCacheError.http(r.status)
            log.error("fetch answered \(r.status)")
        }
        pump()
    }

    private func completed(_ f: Fetch, _ error: Error?) {
        guard fetches.contains(where: { $0 === f }) else { return }
        fetches.removeAll { $0 === f }
        if let error {
            if (error as NSError).code != NSURLErrorCancelled {
                log.error("fetch failed: \(error.localizedDescription, privacy: .public)")
                failure = error
            }
            if sequential {
                sequentialDone = true
                sequentialError = error
            }
        } else if sequential {
            sequentialDone = true
            cache.finishSequential(key, length: f.next)
        }
        pump()
    }

    // MARK: Types

    /// The response's MIME type (or the file suffix) as a UTI AVFoundation reads.
    static func uti(mime: String?, url: URL) -> String? {
        let m = mime?.split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        let known: [String: String] = [
            "audio/mpeg": UTType.mp3.identifier, "audio/mp3": UTType.mp3.identifier,
            "audio/flac": "org.xiph.flac", "audio/x-flac": "org.xiph.flac",
            "audio/mp4": UTType.mpeg4Audio.identifier, "audio/m4a": UTType.mpeg4Audio.identifier,
            "audio/x-m4a": UTType.mpeg4Audio.identifier, "audio/aac": UTType.mpeg4Audio.identifier,
            "audio/wav": UTType.wav.identifier, "audio/x-wav": UTType.wav.identifier,
            "audio/wave": UTType.wav.identifier, "audio/aiff": UTType.aiff.identifier,
        ]
        if let m, let t = known[m] { return t }
        if let m, let t = UTType(mimeType: m), t.conforms(to: .audio) { return t.identifier }
        // `format=mp3` on a transcode; else the server's generic answer says nothing.
        let format = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
            .first { $0.name == "format" }?.value
        if let format, let t = UTType(filenameExtension: format) { return t.identifier }
        return UTType.mp3.identifier
    }
}

// MARK: - AVFoundation

/// Builds the player's assets: a Subsonic stream goes through the cache
/// under a private scheme; anything else (and a transcode reopened at a
/// `timeOffset`, which is never a whole song) streams as before.
enum CachedAudio {
    static let schemePrefix = "yoin-cache-"

    /// Whether [url] goes through the cache, and under which key.
    static func key(for url: URL) -> String? {
        guard SubsonicStreamURLs.isStream(url) else { return nil }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if items.contains(where: { $0.name == "timeOffset" && ($0.value.flatMap(Int.init) ?? 0) > 0 }) { return nil }
        return SubsonicCacheKey.of(url)
    }

    /// A transcode (`maxBitRate`): read front to back, kept only once whole.
    static func isTranscode(_ url: URL) -> Bool {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.contains { $0.name == "maxBitRate" } == true
    }

    static func asset(for url: URL, cache: AudioCache = .shared,
                      upstream: CacheUpstream = URLSessionUpstream.shared) -> AVURLAsset {
        guard let key = key(for: url), var c = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let scheme = c.scheme else { return AVURLAsset(url: url) }
        c.scheme = schemePrefix + scheme
        guard let custom = c.url else { return AVURLAsset(url: url) }
        let asset = AVURLAsset(url: custom)
        // The loader lives on the cache's queue (its lease included): built there.
        let loader = cache.queue.sync {
            AudioCacheLoader(key: key, origin: url, sequential: isTranscode(url), cache: cache, upstream: upstream)
        }
        let delegate = AudioCacheResourceDelegate(loader: loader, queue: cache.queue)
        asset.resourceLoader.setDelegate(delegate, queue: cache.queue)
        // The loader holds delegates weakly: the asset keeps its own alive.
        objc_setAssociatedObject(asset, &associationKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        return asset
    }

    nonisolated(unsafe) private static var associationKey: UInt8 = 0
}

/// AVFoundation's side of [AudioCacheLoader]. Called on the cache's queue.
final class AudioCacheResourceDelegate: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    private let loader: AudioCacheLoader
    private let queue: DispatchQueue
    private var adapters: [ObjectIdentifier: AVLoadingRequestAdapter] = [:]

    init(loader: AudioCacheLoader, queue: DispatchQueue) {
        self.loader = loader
        self.queue = queue
    }

    deinit {
        let loader = loader
        queue.async { loader.close() }
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader,
                        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest) -> Bool {
        let adapter = AVLoadingRequestAdapter(loadingRequest)
        adapters[ObjectIdentifier(loadingRequest)] = adapter
        adapters = adapters.filter { !$0.value.isDone }
        loader.add(adapter)
        return true
    }

    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        guard let adapter = adapters.removeValue(forKey: ObjectIdentifier(loadingRequest)) else { return }
        loader.cancel(adapter)
    }
}

final class AVLoadingRequestAdapter: CacheLoadingRequest {
    private let request: AVAssetResourceLoadingRequest
    private var infoGiven = false

    init(_ request: AVAssetResourceLoadingRequest) { self.request = request }

    var wantsInfo: Bool { request.contentInformationRequest != nil && !infoGiven }

    func fillInfo(length: Int64, contentType: String, byteRanges: Bool, wholeOnDisk: Bool) {
        infoGiven = true
        guard let info = request.contentInformationRequest else { return }
        info.contentType = contentType
        info.contentLength = length
        info.isByteRangeAccessSupported = byteRanges
        info.isEntireLengthAvailableOnDemand = wholeOnDisk
    }

    var dataRange: (offset: Int64, length: Int64?)? {
        guard let d = request.dataRequest else { return nil }
        return (d.requestedOffset, d.requestsAllDataToEndOfResource ? nil : Int64(d.requestedLength))
    }

    var currentOffset: Int64 { request.dataRequest?.currentOffset ?? 0 }
    func respond(_ data: Data) { request.dataRequest?.respond(with: data) }
    func finish() { if !isDone { request.finishLoading() } }
    func fail(_ error: Error) { if !isDone { request.finishLoading(with: error) } }
    var isDone: Bool { request.isFinished || request.isCancelled }
}

// MARK: - Network

/// [CacheUpstream] on one URLSession whose callbacks run on the cache's queue.
final class URLSessionUpstream: NSObject, CacheUpstream, URLSessionDataDelegate, @unchecked Sendable {
    static let shared = URLSessionUpstream(queue: AudioCache.shared.queue)

    private final class Pending: CacheUpstreamTask {
        let task: URLSessionDataTask
        let onResponse: (UpstreamResponse) -> Void
        let onData: (Data) -> Void
        let onComplete: (Error?) -> Void
        init(task: URLSessionDataTask, onResponse: @escaping (UpstreamResponse) -> Void,
             onData: @escaping (Data) -> Void, onComplete: @escaping (Error?) -> Void) {
            self.task = task
            self.onResponse = onResponse
            self.onData = onData
            self.onComplete = onComplete
        }
        func cancel() { task.cancel() }
    }

    private var session: URLSession!
    private var tasks: [Int: Pending] = [:]

    init(queue: DispatchQueue) {
        super.init()
        let config = URLSessionConfiguration.default
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        config.timeoutIntervalForRequest = 30
        let ops = OperationQueue()
        ops.underlyingQueue = queue
        ops.maxConcurrentOperationCount = 1
        session = URLSession(configuration: config, delegate: self, delegateQueue: ops)
    }

    func fetch(_ url: URL, from offset: Int64?, onResponse: @escaping (UpstreamResponse) -> Void,
               onData: @escaping (Data) -> Void, onComplete: @escaping (Error?) -> Void) -> CacheUpstreamTask {
        var request = URLRequest(url: url)
        if let offset { request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range") }
        let task = session.dataTask(with: request)
        let t = Pending(task: task, onResponse: onResponse, onData: onData, onComplete: onComplete)
        tasks[task.taskIdentifier] = t
        task.resume()
        return t
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let t = tasks[dataTask.taskIdentifier], let http = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            return
        }
        var r = UpstreamResponse(status: http.statusCode, contentLength: http.expectedContentLength,
                                 mimeType: http.mimeType)
        if let range = http.value(forHTTPHeaderField: "Content-Range") {
            (r.rangeStart, r.totalLength) = Self.parseContentRange(range)
        }
        t.onResponse(r)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        tasks[dataTask.taskIdentifier]?.onData(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        tasks.removeValue(forKey: task.taskIdentifier)?.onComplete(error)
    }

    /// `bytes 100-199/1000` → (100, 1000); `bytes */1000` → (nil, 1000).
    static func parseContentRange(_ value: String) -> (Int64?, Int64?) {
        let v = value.trimmingCharacters(in: .whitespaces)
        guard v.lowercased().hasPrefix("bytes") else { return (nil, nil) }
        let body = v.dropFirst(5).trimmingCharacters(in: .whitespaces)
        let parts = body.split(separator: "/", maxSplits: 1)
        let total = parts.count == 2 ? Int64(parts[1]) : nil
        let start = parts.first.flatMap { $0.split(separator: "-").first }.flatMap { Int64($0) }
        return (start, total)
    }
}

/// Fetched bytes the disk wouldn't take, by offset: contiguous runs, appended to as a fetch streams.
struct SpillBuffer {
    private var runs: [(start: Int64, data: Data)] = []

    var bytes: Int { runs.reduce(0) { $0 + $1.data.count } }

    mutating func write(at offset: Int64, _ data: Data) {
        if let i = runs.firstIndex(where: { $0.start + Int64($0.data.count) == offset }) {
            runs[i].data.append(data)
        } else {
            runs.append((offset, data))
        }
    }

    /// Up to [max] bytes from [offset], when a run holds it.
    func read(at offset: Int64, max: Int) -> Data? {
        guard max > 0 else { return nil }
        for run in runs where run.start <= offset && offset < run.start + Int64(run.data.count) {
            let from = Int(offset - run.start)
            let to = min(from + max, run.data.count)
            return run.data.subdata(in: (run.data.startIndex + from)..<(run.data.startIndex + to))
        }
        return nil
    }

    mutating func drop(below offset: Int64) {
        runs.removeAll { $0.start + Int64($0.data.count) <= offset }
    }
}
