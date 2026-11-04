import XCTest
@testable import Yoin

/// Data/Cache: the cache key, the byte ranges, LRU eviction, and the
/// loader serving ranges from disk and the network.
final class SubsonicCacheKeyTests: XCTestCase {
    private let base = "https://Music.example/navidrome/rest/stream.view?id=s1&u=me"

    func test_should_strip_signature_and_protocol_when_keying_a_stream() {
        let url = URL(string: "\(base)&t=tok&s=salt&v=1.16.1&c=Yoin&f=json")!
        XCTAssertEqual(SubsonicCacheKey.of(url), "https://music.example/navidrome/rest/stream.view?id=s1&u=me")
    }

    func test_should_give_one_key_when_salt_and_order_differ() {
        let a = URL(string: "\(base)&t=aaa&s=one&v=1&c=Yoin&f=json&maxBitRate=128&format=mp3")!
        let b = URL(string: "https://music.example/navidrome/rest/stream.view?format=mp3&u=me&id=s1&t=bbb&s=two&maxBitRate=128")!
        XCTAssertEqual(SubsonicCacheKey.of(a), SubsonicCacheKey.of(b))
    }

    func test_should_keep_encodings_users_and_servers_apart() {
        let orig = SubsonicCacheKey.of(URL(string: "\(base)&t=t&s=s")!)
        let capped = SubsonicCacheKey.of(URL(string: "\(base)&t=t&s=s&maxBitRate=128&format=mp3")!)
        let other = SubsonicCacheKey.of(URL(string: "https://music.example/navidrome/rest/stream.view?id=s1&u=you&t=t&s=s")!)
        let server = SubsonicCacheKey.of(URL(string: "https://elsewhere.example/navidrome/rest/stream.view?id=s1&u=me&t=t&s=s")!)
        XCTAssertEqual(Set([orig, capped, other, server]).count, 4)
    }

    func test_should_not_key_unsigned_or_non_rest_urls() {
        XCTAssertNil(SubsonicCacheKey.of(URL(string: "\(base)&p=secret")!))
        XCTAssertNil(SubsonicCacheKey.of(URL(string: "https://music.example/stream?id=1&t=t&s=s")!))
        XCTAssertNil(SubsonicCacheKey.of(URL(string: "applemusic://song/1")!))
    }

    func test_should_bypass_the_cache_when_a_transcode_reopens_at_an_offset() {
        let start = URL(string: "\(base)&t=t&s=s&maxBitRate=128&format=mp3")!
        let reopened = URL(string: "\(base)&t=t&s=s&maxBitRate=128&format=mp3&timeOffset=40")!
        XCTAssertNotNil(CachedAudio.key(for: start))
        XCTAssertTrue(CachedAudio.isTranscode(start))
        XCTAssertNil(CachedAudio.key(for: reopened))
    }
}

final class ByteRangesTests: XCTestCase {
    func test_should_merge_overlapping_and_touching_spans() {
        var r = ByteRanges()
        XCTAssertEqual(r.insert(10, 20), 10)
        XCTAssertEqual(r.insert(30, 40), 10)
        XCTAssertEqual(r.insert(15, 30), 10)
        XCTAssertEqual(r.spans, [ByteRanges.Span(start: 10, end: 40)])
        XCTAssertEqual(r.insert(0, 10), 10)
        XCTAssertTrue(r.covers(40))
        XCTAssertFalse(r.covers(41))
    }

    func test_should_answer_contiguous_bytes_from_an_offset() {
        var r = ByteRanges()
        r.insert(0, 100)
        r.insert(200, 300)
        XCTAssertEqual(r.contiguous(from: 0), 100)
        XCTAssertEqual(r.contiguous(from: 50), 50)
        XCTAssertEqual(r.contiguous(from: 100), 0)
        XCTAssertEqual(r.contiguous(from: 250), 50)
        r.clip(to: 250)
        XCTAssertEqual(r.total, 150)
    }
}

// MARK: - Fakes

final class FakeRequest: CacheLoadingRequest {
    let info: Bool
    let range: (offset: Int64, length: Int64?)?
    var data = Data()
    var finished = false
    var error: Error?
    var filled: (length: Int64, type: String, byteRanges: Bool, whole: Bool)?

    init(info: Bool = false, offset: Int64 = 0, length: Int64? = nil, dataless: Bool = false) {
        self.info = info
        range = dataless ? nil : (offset, length)
    }

    var wantsInfo: Bool { info && filled == nil }
    func fillInfo(length: Int64, contentType: String, byteRanges: Bool, wholeOnDisk: Bool) {
        filled = (length, contentType, byteRanges, wholeOnDisk)
    }
    var dataRange: (offset: Int64, length: Int64?)? { range }
    var currentOffset: Int64 { (range?.offset ?? 0) + Int64(data.count) }
    func respond(_ d: Data) { data += d }
    func finish() { finished = true }
    func fail(_ e: Error) { error = e }
    var isDone: Bool { finished || error != nil }
}

/// Records fetches; the test answers them by hand on the cache's queue.
final class FakeUpstream: CacheUpstream {
    final class Fetch: CacheUpstreamTask {
        let offset: Int64?
        let onResponse: (UpstreamResponse) -> Void
        let onData: (Data) -> Void
        let onComplete: (Error?) -> Void
        var cancelled = false
        init(offset: Int64?, onResponse: @escaping (UpstreamResponse) -> Void, onData: @escaping (Data) -> Void,
             onComplete: @escaping (Error?) -> Void) {
            self.offset = offset
            self.onResponse = onResponse
            self.onData = onData
            self.onComplete = onComplete
        }
        func cancel() { cancelled = true }
    }

    var fetches: [Fetch] = []

    func fetch(_ url: URL, from offset: Int64?, onResponse: @escaping (UpstreamResponse) -> Void,
               onData: @escaping (Data) -> Void, onComplete: @escaping (Error?) -> Void) -> CacheUpstreamTask {
        let f = Fetch(offset: offset, onResponse: onResponse, onData: onData, onComplete: onComplete)
        fetches.append(f)
        return f
    }
}

final class AudioCacheLoaderTests: XCTestCase {
    private var dir: URL!
    private var cache: AudioCache!
    private let upstream = FakeUpstream()
    private let url = URL(string: "https://m.example/rest/stream.view?id=s1&u=me&t=t&s=s")!
    private let key = "k1"
    /// 1 000 000 bytes, each its offset mod 251.
    private let body = Data((0..<1_000_000).map { UInt8($0 % 251) })

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("audiocache-\(UUID().uuidString)")
        cache = AudioCache(directory: dir, maxBytes: 10_000_000)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    private func onQueue<T>(_ body: () -> T) -> T { cache.queue.sync(execute: body) }

    private func loader(sequential: Bool = false, upstream: CacheUpstream? = nil) -> AudioCacheLoader {
        onQueue { AudioCacheLoader(key: key, origin: url, sequential: sequential, cache: cache, upstream: upstream ?? self.upstream) }
    }

    private func ranged(_ f: FakeUpstream.Fetch, total: Int64? = nil) {
        let start = f.offset ?? 0
        let total = total ?? Int64(body.count)
        onQueue {
            f.onResponse(UpstreamResponse(status: 206, contentLength: total - start, rangeStart: start,
                                          totalLength: total, mimeType: "audio/flac"))
        }
    }

    private func send(_ f: FakeUpstream.Fetch, _ range: Range<Int>) {
        onQueue { f.onData(body.subdata(in: range)) }
    }

    func test_should_serve_a_ranged_stream_from_the_network_then_from_disk_offline() throws {
        let l = loader()
        let req = FakeRequest(info: true, offset: 0, length: Int64(body.count))
        onQueue { l.add(req) }
        XCTAssertEqual(upstream.fetches.count, 1)
        XCTAssertEqual(upstream.fetches[0].offset, 0)
        let f = upstream.fetches[0]
        ranged(f)
        XCTAssertEqual(req.filled?.length, Int64(body.count))
        XCTAssertEqual(req.filled?.type, "org.xiph.flac")
        XCTAssertEqual(req.filled?.byteRanges, true)
        for start in stride(from: 0, to: body.count, by: 100_000) { send(f, start..<min(start + 100_000, body.count)) }
        onQueue { f.onComplete(nil) }
        XCTAssertTrue(req.finished)
        XCTAssertEqual(req.data, body)
        onQueue { l.close() }
        XCTAssertTrue(cache.isComplete(key))

        // Offline: a new loader answers every range from disk, asking nothing of the network.
        let offline = FakeUpstream()
        let l2 = loader(upstream: offline)
        let info = FakeRequest(info: true, offset: 0, length: 2)
        let tail = FakeRequest(offset: 900_000, length: 50_000)
        onQueue { l2.add(info); l2.add(tail) }
        XCTAssertTrue(offline.fetches.isEmpty)
        XCTAssertEqual(info.filled?.whole, true)
        XCTAssertEqual(tail.data, body.subdata(in: 900_000..<950_000))
        XCTAssertTrue(tail.finished)
        onQueue { l2.close() }
    }

    func test_should_fetch_from_the_gap_when_a_seek_lands_past_the_held_bytes() {
        let l = loader()
        let head = FakeRequest(info: true, offset: 0, length: 200_000)
        onQueue { l.add(head) }
        let f0 = upstream.fetches[0]
        ranged(f0)
        send(f0, 0..<200_000)
        XCTAssertTrue(head.finished)

        // A seek far ahead: a second, ranged fetch from that byte.
        let seek = FakeRequest(offset: 800_000, length: 100_000)
        onQueue { l.add(seek) }
        XCTAssertEqual(upstream.fetches.count, 2)
        guard upstream.fetches.count == 2 else { return }
        let f1 = upstream.fetches[1]
        XCTAssertEqual(f1.offset, 800_000)
        ranged(f1)
        send(f1, 800_000..<900_000)
        XCTAssertEqual(seek.data, body.subdata(in: 800_000..<900_000))
        XCTAssertTrue(seek.finished)

        // A read across the held bytes and the gap: disk first, then a fetch for the rest.
        let across = FakeRequest(offset: 150_000, length: 100_000)
        onQueue { l.add(across) }
        XCTAssertEqual(across.data.count, 50_000, "the held 150 000–200 000 come from disk at once")
        onQueue { l.close() }
        XCTAssertFalse(cache.isComplete(key))
        XCTAssertEqual(onQueue { cache.entry(key)?.ranges.total }, 300_000, "a ranged stream keeps its partial bytes")
    }

    func test_should_store_a_transcode_only_once_whole_with_its_real_length() {
        let l = loader(sequential: true)
        let req = FakeRequest(info: true, offset: 0, length: nil)
        onQueue { l.add(req) }
        let f = upstream.fetches[0]
        XCTAssertNil(f.offset, "a transcode is fetched whole, no Range")
        // Navidrome's estimate is above what it sends.
        onQueue { f.onResponse(UpstreamResponse(status: 200, contentLength: 1_200_000, mimeType: "audio/mpeg")) }
        XCTAssertEqual(req.filled?.byteRanges, false)
        XCTAssertEqual(req.filled?.length, 1_200_000)
        send(f, 0..<600_000)
        XCTAssertFalse(cache.isComplete(key))
        send(f, 600_000..<1_000_000)
        onQueue { f.onComplete(nil) }
        XCTAssertTrue(req.finished)
        XCTAssertEqual(req.data, body)
        onQueue { l.close() }
        XCTAssertTrue(cache.isComplete(key))
        XCTAssertEqual(onQueue { cache.entry(key)?.length }, 1_000_000)

        // Stored whole, it now answers byte ranges from disk.
        let l2 = loader(sequential: true, upstream: FakeUpstream())
        let info = FakeRequest(info: true, offset: 500_000, length: 10)
        onQueue { l2.add(info) }
        XCTAssertEqual(info.filled?.byteRanges, true)
        XCTAssertEqual(info.data, body.subdata(in: 500_000..<500_010))
        onQueue { l2.close() }
    }

    func test_should_drop_a_partial_transcode_when_the_player_lets_go() {
        let l = loader(sequential: true)
        let req = FakeRequest(info: true)
        onQueue { l.add(req) }
        let f = upstream.fetches[0]
        onQueue { f.onResponse(UpstreamResponse(status: 200, contentLength: 1_000_000, mimeType: "audio/mpeg")) }
        send(f, 0..<300_000)
        onQueue { l.close() }
        XCTAssertTrue(f.cancelled)
        XCTAssertNil(onQueue { cache.entry(key) })
        XCTAssertEqual(cache.sizeBytes(), 0)
    }

    func test_should_fail_waiting_requests_when_offline_and_not_held() {
        let l = loader()
        let req = FakeRequest(info: true, offset: 0, length: 100)
        onQueue { l.add(req) }
        onQueue { upstream.fetches[0].onComplete(URLError(.notConnectedToInternet)) }
        XCTAssertNotNil(req.error)
        onQueue { l.close() }
    }

    func test_should_read_a_server_that_ignores_ranges_front_to_back() {
        let l = loader()
        let req = FakeRequest(info: true, offset: 0, length: nil)
        onQueue { l.add(req) }
        let f = upstream.fetches[0]
        onQueue { f.onResponse(UpstreamResponse(status: 200, contentLength: 1_000_000, mimeType: "audio/mpeg")) }
        send(f, 0..<1_000_000)
        onQueue { f.onComplete(nil) }
        XCTAssertEqual(req.data, body)
        XCTAssertTrue(req.finished)
        onQueue { l.close() }
        XCTAssertTrue(cache.isComplete(key))
    }
}

final class AudioCacheEvictionTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("audiocache-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    func test_should_evict_least_recently_used_but_never_a_leased_stream() {
        var now = Date(timeIntervalSince1970: 1000)
        let cache = AudioCache(directory: dir, maxBytes: 250, clock: { now })
        let chunk = Data(repeating: 7, count: 100)
        func store(_ key: String, keepLease: Bool = false) {
            cache.queue.sync {
                cache.lease(key)
                cache.setInfo(key, length: 100, contentType: "public.mp3", byteRanges: true)
                cache.write(key, at: 0, chunk)
                if !keepLease { cache.release(key) }
            }
            now += 10
        }
        store("a")
        store("b")
        // "a" played again: now "b" is the oldest.
        cache.queue.sync { cache.lease("a"); cache.release("a") }
        now += 10
        store("c", keepLease: true)
        XCTAssertTrue(cache.isComplete("a"))
        XCTAssertFalse(cache.isComplete("b"))
        XCTAssertTrue(cache.isComplete("c"))
        store("d")
        // Over budget again: "a" goes, "c" is held by a player.
        XCTAssertFalse(cache.isComplete("a"))
        XCTAssertTrue(cache.isComplete("c"))
        XCTAssertTrue(cache.isComplete("d"))
        XCTAssertLessThanOrEqual(cache.sizeBytes(), 250)
    }

    func test_should_reload_records_after_a_relaunch_and_clear_everything() async {
        let cache = AudioCache(directory: dir)
        cache.queue.sync {
            cache.lease("x")
            cache.setInfo("x", length: 4, contentType: "public.mp3", byteRanges: true)
            cache.write("x", at: 0, Data([1, 2, 3, 4]))
            cache.release("x")
        }
        let relaunched = AudioCache(directory: dir)
        XCTAssertTrue(relaunched.isComplete("x"))
        XCTAssertEqual(relaunched.sizeBytes(), 4)
        XCTAssertEqual(relaunched.queue.sync { relaunched.read("x", at: 1, count: 2) }, Data([2, 3]))
        await relaunched.clear()
        XCTAssertEqual(relaunched.sizeBytes(), 0)
        XCTAssertFalse(AudioCache(directory: dir).isComplete("x"))
    }
}

final class TestClock: @unchecked Sendable {
    var now: Date
    init(_ now: Date) { self.now = now }
}

final class DetailDiskCacheTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("detailcache-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    func test_should_round_trip_and_skip_writes_from_before_an_edit() async {
        let cache = DetailDiskCache(directory: dir)
        await cache.write(["a", "b"], scope: "me", kind: .list, id: "albums", epoch: 0)
        let hit = await cache.read([String].self, scope: "me", kind: .list, id: "albums")
        XCTAssertEqual(hit?.value, ["a", "b"])
        let other = await cache.read([String].self, scope: "you", kind: .list, id: "albums")
        XCTAssertNil(other, "another account never reads it")

        await cache.remove(scope: "me", kind: .list, id: "albums", epoch: 3)
        await cache.write(["stale"], scope: "me", kind: .list, id: "albums", epoch: 2)
        let gone = await cache.read([String].self, scope: "me", kind: .list, id: "albums")
        XCTAssertNil(gone, "a load begun before the edit can't write the old copy back")
        await cache.write(["fresh"], scope: "me", kind: .list, id: "albums", epoch: 3)
        let fresh = await cache.read([String].self, scope: "me", kind: .list, id: "albums")
        XCTAssertEqual(fresh?.value, ["fresh"])
    }

    func test_should_not_serve_expired_or_corrupt_entries() async throws {
        let clock = TestClock(Date(timeIntervalSince1970: 0))
        let cache = DetailDiskCache(directory: dir, maxAge: 100, clock: { clock.now })
        await cache.write(1, scope: "me", kind: .album, id: "x", epoch: 0)
        clock.now = Date(timeIntervalSince1970: 101)
        let expired = await cache.read(Int.self, scope: "me", kind: .album, id: "x")
        XCTAssertNil(expired)

        await cache.write(2, scope: "me", kind: .album, id: "y", epoch: 0)
        let wrongType = await cache.read([String].self, scope: "me", kind: .album, id: "y")
        XCTAssertNil(wrongType)
        let deleted = await cache.read(Int.self, scope: "me", kind: .album, id: "y")
        XCTAssertNil(deleted, "an entry that doesn't decode is dropped")
    }

    func test_should_trim_least_recently_read_entries_past_the_budget() async {
        let clock = TestClock(Date(timeIntervalSince1970: 1_000_000))
        let cache = DetailDiskCache(directory: dir, maxBytes: 3_000, clock: { clock.now })
        let blob = String(repeating: "x", count: 900)
        for id in ["a", "b", "c"] {
            await cache.write(blob, scope: "me", kind: .album, id: id, epoch: 0)
            clock.now += 10
        }
        // Read "a" so "b" becomes the oldest.
        _ = await cache.read(String.self, scope: "me", kind: .album, id: "a")
        clock.now += 10
        await cache.write(blob, scope: "me", kind: .album, id: "d", epoch: 0)
        let b = await cache.read(String.self, scope: "me", kind: .album, id: "b")
        let a = await cache.read(String.self, scope: "me", kind: .album, id: "a")
        let d = await cache.read(String.self, scope: "me", kind: .album, id: "d")
        XCTAssertNil(b)
        XCTAssertNotNil(a)
        XCTAssertNotNil(d)
        let size = await cache.sizeBytes()
        XCTAssertLessThanOrEqual(size, 2_500)
    }
}

@MainActor
final class CachedTranscodeSeekTests: XCTestCase {
    func test_should_seek_in_the_stored_bytes_when_the_transcode_is_whole_on_disk() {
        let output = FakeAudioOutput()
        let engine = PlayerEngine(output: output)
        engine.streamQuality = { .kbps128 }
        engine.isStoredWhole = { url in
            URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.contains { $0.value == "t1" } == true
        }
        let base = "https://music.example/rest/stream?u=me&t=tok&s=salt&v=1.16.1&c=Yoin&f=json"
        let tracks = (1...2).map { n in
            PlayableTrack(id: "t\(n)", provider: "subsonic", title: "T", artist: "A", album: "Al", albumId: nil,
                          durationSec: 200, streamURL: URL(string: "\(base)&id=t\(n)")!, coverURL: nil, sourceKbps: 320)
        }
        engine.play(queue: tracks)
        let loads = output.loads
        engine.seek(to: 75)
        XCTAssertEqual(output.loads, loads, "no reopen at timeOffset")
        XCTAssertEqual(output.seeks, [75])
        engine.next()
        engine.seek(to: 30)
        XCTAssertEqual(output.seeks, [75], "t2 isn't stored: it reopens at timeOffset")
    }
}
