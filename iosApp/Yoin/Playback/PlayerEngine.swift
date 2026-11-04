import Foundation
import Observation

/// The playing position, split off the engine so only progress UI (scrubber,
/// time labels, lock screen) reads it. Android rule: a per-tick field must
/// never churn the whole Now Playing state; here the engine's own properties
/// change only on real state changes and this object takes the ≤ 4 Hz ticks.
@MainActor
@Observable
final class PlaybackProgress {
    var positionSec: Double = 0
    var durationSec: Double = 0

    var fraction: Double { durationSec > 0 ? min(max(positionSec / durationSec, 0), 1) : 0 }
}

/// Native playback: the queue, its play modes and the transport, driving an
/// [AudioOutput] (AVQueuePlayer in the app, a fake in tests).
///
/// Semantics follow Android's Media3 setup (`player/PlaybackManager.kt`,
/// `PlayMode.kt`):
/// - previous restarts the song past its first 3 s, else goes back a song;
/// - repeat one only repeats on auto-advance; next / previous move on, and
///   wrap around only under repeat all;
/// - repeat off stops at the end of the queue;
/// - play next goes right after the current song; add to queue goes after
///   the songs the user already queued there, ahead of the rest;
/// - the current song can't be removed from the queue.
@MainActor
@Observable
final class PlayerEngine {
    /// Previous restarts the song past this point (Media3 maxSeekToPreviousPosition, owner W2).
    static let previousRestartThresholdSec: Double = 3
    /// Submission rule (Last.fm / Subsonic scrobble): half the song or 4 min, songs over 30 s.
    static let scrobbleMinTrackSec: Double = 30
    static let scrobbleMaxListenSec: Double = 240

    // MARK: Observable state

    /// Play order (shuffled when `shuffle` is on).
    private(set) var queue: [QueueItem] = []
    private(set) var currentIndex: Int?
    /// The user wants sound: true from play until pause, even while buffering.
    private(set) var isPlaying = false
    private(set) var isBuffering = false
    private(set) var shuffle = false
    private(set) var repeatMode: RepeatMode = .all
    /// Ticks live here; see [PlaybackProgress].
    let progress = PlaybackProgress()

    var currentItem: QueueItem? { currentIndex.map { queue[$0] } }
    var currentTrack: PlayableTrack? { currentItem?.track }
    /// Songs after the current one, in play order.
    var upcoming: ArraySlice<QueueItem> {
        guard let i = currentIndex, i + 1 < queue.count else { return [] }
        return queue[(i + 1)...]
    }

    // MARK: Hooks

    /// `submission == false`: the song started ("now playing"); `true`: it
    /// was listened to long enough to count as a play.
    @ObservationIgnored var onScrobble: ((PlayableTrack, _ submission: Bool) -> Void)?
    @ObservationIgnored var onEvent: ((PlayerEvent) -> Void)?
    /// Right before sound starts (activate the audio session).
    @ObservationIgnored var onWillPlay: (() -> Void)?
    /// Track, play state, duration or a seek changed (refresh the lock screen).
    @ObservationIgnored var onStateChange: (() -> Void)?
    /// The Subsonic stream quality, read when a song opens (Settings › Server, the route).
    @ObservationIgnored var streamQuality: () -> StreamQuality = { .original }
    /// The whole stream behind a URL is in the audio cache (Data/Cache): a
    /// stored transcode seeks in its own bytes instead of reopening at `timeOffset`.
    @ObservationIgnored var isStoredWhole: (URL) -> Bool = { _ in false }

    // MARK: Internals

    @ObservationIgnored private let output: any AudioOutput
    @ObservationIgnored private let shuffler: ([QueueItem]) -> [QueueItem]
    /// The queue in its unshuffled order while shuffle is on.
    @ObservationIgnored private var originalOrder: [QueueItem]?
    /// The slot the output has preloaded as its next item.
    @ObservationIgnored private var preloadedID: UUID?
    @ObservationIgnored private var preloadedURL: URL?
    /// Repeat off ran out of queue: the next play starts the last song over.
    @ObservationIgnored private var ended = false
    @ObservationIgnored private var consecutiveFailures = 0
    @ObservationIgnored private var scrobble = ScrobbleState()
    /// Each slot's stream, chosen when it is first opened or preloaded: a
    /// song keeps it through seeks and retries, so the quality never
    /// switches mid-song (Android `SubsonicStreamResolver` pins).
    @ObservationIgnored private var deliveries: [UUID: StreamDelivery] = [:]
    /// Where the current item's stream starts in the song: a transcode
    /// reopened with `timeOffset` reports time from its own start.
    @ObservationIgnored private var timeBaseSec: Double = 0

    private struct ScrobbleState {
        var itemID: UUID?
        var announced = false
        var submitted = false
        var listenedSec: Double = 0
        var lastTickSec: Double?
    }

    init(output: any AudioOutput, shuffler: @escaping ([QueueItem]) -> [QueueItem] = { $0.shuffled() }) {
        self.output = output
        self.shuffler = shuffler
        output.events = self
    }

    // MARK: Starting

    /// Replaces the queue with [tracks] and plays [startAt].
    func play(queue tracks: [PlayableTrack], startAt: Int = 0) {
        guard !tracks.isEmpty else { stop(); return }
        let start = min(max(startAt, 0), tracks.count - 1)
        let items = tracks.map { QueueItem($0) }
        if shuffle {
            originalOrder = items
            queue = [items[start]] + shuffler(items.enumerated().filter { $0.offset != start }.map(\.element))
            currentIndex = 0
        } else {
            originalOrder = nil
            queue = items
            currentIndex = start
        }
        consecutiveFailures = 0
        loadCurrent(play: true)
    }

    /// Plays queue entry [index] (a tap in the queue sheet).
    func jump(to index: Int) {
        guard queue.indices.contains(index) else { return }
        currentIndex = index
        loadCurrent(play: true)
    }

    func stop() {
        output.stop()
        queue = []
        originalOrder = nil
        currentIndex = nil
        isPlaying = false
        isBuffering = false
        ended = false
        preloadedID = nil
        preloadedURL = nil
        deliveries = [:]
        timeBaseSec = 0
        progress.positionSec = 0
        progress.durationSec = 0
        scrobble = ScrobbleState()
        onStateChange?()
    }

    // MARK: Transport

    func togglePlayPause() {
        isPlaying ? pause() : resume()
    }

    func resume() {
        guard currentIndex != nil, !isPlaying else { return }
        if ended {
            ended = false
            loadCurrent(play: true)
            return
        }
        onWillPlay?()
        isPlaying = true
        output.play()
        announceIfNeeded()
        onStateChange?()
    }

    func pause() {
        guard isPlaying else { return }
        isPlaying = false
        output.pause()
        // The output's own clock is the truth at the moment of the pause.
        progress.positionSec = output.currentTimeSec + timeBaseSec
        scrobble.lastTickSec = progress.positionSec
        onStateChange?()
    }

    func next() {
        guard let i = currentIndex else { return }
        if i + 1 < queue.count {
            currentIndex = i + 1
        } else if repeatMode == .all {
            currentIndex = 0
        } else {
            return
        }
        loadCurrent(play: isPlaying || ended)
    }

    func previous() {
        guard let i = currentIndex else { return }
        if progress.positionSec > Self.previousRestartThresholdSec {
            seek(to: 0)
            return
        }
        if i > 0 {
            currentIndex = i - 1
        } else if repeatMode == .all, queue.count > 1 {
            currentIndex = queue.count - 1
        } else {
            seek(to: 0)
            return
        }
        loadCurrent(play: isPlaying || ended)
    }

    func seek(to sec: Double) {
        guard currentIndex != nil else { return }
        let duration = progress.durationSec
        let target = duration > 0 ? min(max(sec, 0), duration) : max(sec, 0)
        if ended {
            ended = false
            loadCurrent(play: false, startSec: target)
            return
        }
        if let item = currentItem, delivery(for: item).seeksByTime {
            reopenTranscode(item, at: target)
            return
        }
        output.seek(to: target)
        progress.positionSec = target
        // A seek is a jump, not listening time.
        scrobble.lastTickSec = target
        onStateChange?()
    }

    // MARK: Modes

    func setShuffle(_ on: Bool) {
        guard on != shuffle else { return }
        shuffle = on
        guard let i = currentIndex else { return }
        let current = queue[i]
        if on {
            originalOrder = queue
            var rest = queue
            rest.remove(at: i)
            queue = [current] + shuffler(rest)
            currentIndex = 0
        } else if let original = originalOrder {
            queue = original
            currentIndex = original.firstIndex { $0.id == current.id } ?? 0
            originalOrder = nil
        }
        syncPreload()
    }

    /// Now Playing's round play-mode button (Android `PlayMode.next()`), one
    /// axis per tap: repeat all → shuffle → shuffle + repeat one → repeat one
    /// → repeat all. From repeat off it goes back to repeat all, shuffle kept.
    func cyclePlayMode() {
        switch (shuffle, repeatMode) {
        case (false, .all): setShuffle(true)
        case (true, .all): setRepeat(.one)
        case (true, .one): setShuffle(false)
        case (false, .one): setRepeat(.all)
        case (_, .off): setRepeat(.all)
        }
    }

    func setRepeat(_ mode: RepeatMode) {
        guard mode != repeatMode else { return }
        repeatMode = mode
        syncPreload()
    }

    // MARK: Queue edits

    /// Right after the current song (stacks: the newest play-next plays first).
    func playNext(_ track: PlayableTrack) {
        insertUserQueued(track, afterQueued: false)
    }

    /// After the songs already queued by the user, ahead of the rest.
    func addToQueue(_ track: PlayableTrack) {
        insertUserQueued(track, afterQueued: true)
    }

    /// Moves the entry at [from] to [to] (play-order indices). Shuffle restore
    /// keeps the original order, as Media3 does.
    func move(from: Int, to: Int) {
        guard from != to, queue.indices.contains(from), queue.indices.contains(to) else { return }
        let currentID = currentItem?.id
        let item = queue.remove(at: from)
        queue.insert(item, at: to)
        currentIndex = queue.firstIndex { $0.id == currentID }
        syncPreload()
    }

    /// `List.onMove` form: [offsets] → before [destination].
    func move(fromOffsets offsets: IndexSet, toOffset destination: Int) {
        let currentID = currentItem?.id
        let moving = offsets.map { queue[$0] }
        guard !moving.contains(where: { $0.id == currentID }) else { return }
        var rest = queue
        for i in offsets.sorted(by: >) { rest.remove(at: i) }
        let insertAt = destination - offsets.filter { $0 < destination }.count
        rest.insert(contentsOf: moving, at: min(max(insertAt, 0), rest.count))
        queue = rest
        currentIndex = queue.firstIndex { $0.id == currentID }
        syncPreload()
    }

    /// Takes entry [index] out. The current song stays (Android rule).
    func remove(at index: Int) {
        guard queue.indices.contains(index), index != currentIndex else { return }
        let id = queue[index].id
        queue.remove(at: index)
        originalOrder?.removeAll { $0.id == id }
        if let i = currentIndex, index < i { currentIndex = i - 1 }
        syncPreload()
    }

    func remove(id: UUID) {
        if let i = queue.firstIndex(where: { $0.id == id }) { remove(at: i) }
    }

    private func insertUserQueued(_ track: PlayableTrack, afterQueued: Bool) {
        let item = QueueItem(track, userQueued: true)
        guard let i = currentIndex else {
            // An empty player takes the song without starting it (Media3 addMediaItems).
            queue = [item]
            originalOrder = shuffle ? [item] : nil
            currentIndex = 0
            loadCurrent(play: false)
            return
        }
        var at = i + 1
        if afterQueued {
            while at < queue.count, queue[at].userQueued { at += 1 }
        }
        queue.insert(item, at: at)
        if var original = originalOrder {
            // Same rule against the unshuffled order, so it survives shuffle off.
            let currentID = queue[i].id
            var o = (original.firstIndex { $0.id == currentID } ?? original.count - 1) + 1
            if afterQueued {
                while o < original.count, original[o].userQueued { o += 1 }
            }
            original.insert(item, at: min(o, original.count))
            originalOrder = original
        }
        syncPreload()
    }

    // MARK: Loading

    /// The entry auto-advance moves to after the current one ends.
    private var autoAdvanceIndex: Int? {
        guard let i = currentIndex else { return nil }
        switch repeatMode {
        case .one: return i
        case .all: return i + 1 < queue.count ? i + 1 : 0
        case .off: return i + 1 < queue.count ? i + 1 : nil
        }
    }

    private func loadCurrent(play: Bool, startSec: Double = 0) {
        guard let item = currentItem else { return }
        ended = false
        let live = Set(queue.map(\.id))
        deliveries = deliveries.filter { live.contains($0.key) }
        let delivery = delivery(for: item)
        // A transcode starts at the second asked for; a file seeks by byte.
        let offset = delivery.seeksByTime ? Self.wholeSeconds(startSec) : 0
        timeBaseSec = Double(offset)
        let start = delivery.seeksByTime ? Double(offset) : startSec
        let next = autoAdvanceIndex.map { queue[$0] }
        preloadedID = next?.id
        preloadedURL = next.map { streamURL(for: $0) }
        progress.positionSec = start
        progress.durationSec = item.track.durationSec
        beginScrobble(item, at: start)
        isBuffering = true
        output.load(streamURL(for: item, timeOffsetSec: offset), next: preloadedURL,
                    startSec: delivery.seeksByTime ? 0 : startSec)
        if play {
            onWillPlay?()
            isPlaying = true
            output.play()
            announceIfNeeded()
        } else {
            isPlaying = false
        }
        onStateChange?()
    }

    /// Tells the output what plays after the current item, when that changed.
    private func syncPreload() {
        let next = autoAdvanceIndex.map { queue[$0] }
        let url = next.map { streamURL(for: $0) }
        guard next?.id != preloadedID || url != preloadedURL else { return }
        preloadedID = next?.id
        preloadedURL = url
        output.preload(url)
    }

    // MARK: Stream quality

    /// The quality changed (Settings, or the route under Automatic): every
    /// song not yet playing chooses again when it opens (the preloaded one included); the current song
    /// keeps its stream.
    func streamQualityDidChange() {
        let currentID = currentItem?.id
        deliveries = deliveries.filter { $0.key == currentID }
        syncPreload()
    }

    private func delivery(for item: QueueItem) -> StreamDelivery {
        if let pinned = deliveries[item.id] { return pinned }
        var planned = StreamDelivery.plan(streamQuality(), track: item.track)
        if planned.seeksByTime, isStoredWhole(SubsonicStreamURLs.url(item.track.streamURL, delivery: planned)) {
            planned.seeksByTime = false
        }
        deliveries[item.id] = planned
        return planned
    }

    private func streamURL(for item: QueueItem, timeOffsetSec: Int = 0) -> URL {
        let delivery = delivery(for: item)
        guard delivery.transcoded else { return item.track.streamURL }
        return SubsonicStreamURLs.url(item.track.streamURL, delivery: delivery, timeOffsetSec: timeOffsetSec)
    }

    /// A seek in a transcode: the server has no bytes to range into, so the
    /// stream reopens at [sec] (`timeOffset`, whole seconds) and the output's
    /// clock counts from there.
    private func reopenTranscode(_ item: QueueItem, at sec: Double) {
        let offset = Self.wholeSeconds(sec)
        timeBaseSec = Double(offset)
        isBuffering = true
        output.load(streamURL(for: item, timeOffsetSec: offset), next: preloadedURL, startSec: 0)
        if isPlaying { output.play() }
        progress.positionSec = Double(offset)
        // A seek is a jump, not listening time.
        scrobble.lastTickSec = Double(offset)
        onStateChange?()
    }

    static func wholeSeconds(_ sec: Double) -> Int {
        sec.isFinite && sec > 0 ? Int(sec.rounded(.down)) : 0
    }

    // MARK: Scrobble

    private func beginScrobble(_ item: QueueItem, at sec: Double) {
        scrobble = ScrobbleState(itemID: item.id, lastTickSec: sec)
    }

    private func announceIfNeeded() {
        guard let track = currentTrack, !scrobble.announced else { return }
        scrobble.announced = true
        onScrobble?(track, false)
    }

    private func countListening(to sec: Double) {
        defer { scrobble.lastTickSec = sec }
        guard isPlaying, let last = scrobble.lastTickSec, let track = currentTrack else { return }
        let delta = sec - last
        // Ticks come every 0.25 s; anything wider is a seek or a stall, not listening.
        guard delta > 0, delta < 2 else { return }
        scrobble.listenedSec += delta
        let duration = progress.durationSec > 0 ? progress.durationSec : track.durationSec
        guard !scrobble.submitted, duration > Self.scrobbleMinTrackSec else { return }
        if scrobble.listenedSec >= min(duration / 2, Self.scrobbleMaxListenSec) {
            scrobble.submitted = true
            onScrobble?(track, true)
        }
    }

    // MARK: Failure

    private func skipAfterFailure(_ message: String) {
        guard let track = currentTrack else { return }
        consecutiveFailures += 1
        onEvent?(.skippedAfterError(track: track, message: message))
        if consecutiveFailures >= queue.count {
            output.stop()
            isPlaying = false
            isBuffering = false
            ended = true
            onEvent?(.queueFailed)
            onStateChange?()
            return
        }
        let i = currentIndex ?? 0
        if i + 1 < queue.count {
            currentIndex = i + 1
        } else if repeatMode != .off {
            currentIndex = 0
        } else {
            reachEnd()
            return
        }
        loadCurrent(play: isPlaying)
    }

    private func reachEnd() {
        isPlaying = false
        isBuffering = false
        ended = true
        preloadedID = nil
        preloadedURL = nil
        progress.positionSec = progress.durationSec
        onEvent?(.queueEnded)
        onStateChange?()
    }
}

// MARK: - AudioOutputEvents

extension PlayerEngine: AudioOutputEvents {
    func outputTimeDidChange(_ outputSec: Double) {
        guard currentIndex != nil, !ended else { return }
        let sec = outputSec + timeBaseSec
        if sec > 0.5 { consecutiveFailures = 0 }
        countListening(to: sec)
        if abs(progress.positionSec - sec) >= 0.01 { progress.positionSec = sec }
    }

    func outputDurationDidChange(_ sec: Double) {
        guard sec.isFinite, sec > 0, abs(progress.durationSec - sec) > 0.5 else { return }
        // A transcode's length is the server's estimate (and only what is left
        // after a `timeOffset`): the song's own duration stays the truth.
        if let item = currentItem, delivery(for: item).transcoded, item.track.durationSec > 0 { return }
        progress.durationSec = sec
        onStateChange?()
    }

    func outputIsBufferingDidChange(_ buffering: Bool) {
        if isBuffering != buffering { isBuffering = buffering }
    }

    func outputDidAdvanceToPreloaded() {
        guard let id = preloadedID, let index = queue.firstIndex(where: { $0.id == id }) else {
            reachEnd()
            return
        }
        currentIndex = index
        let item = queue[index]
        timeBaseSec = 0
        progress.positionSec = 0
        progress.durationSec = item.track.durationSec
        beginScrobble(item, at: 0)
        let next = autoAdvanceIndex.map { queue[$0] }
        preloadedID = next?.id
        preloadedURL = next.map { streamURL(for: $0) }
        output.preload(preloadedURL)
        announceIfNeeded()
        onStateChange?()
    }

    func outputDidReachEnd() {
        reachEnd()
    }

    func outputDidFail(_ message: String) {
        skipAfterFailure(message)
    }

    func outputPlayingDidChangeExternally(_ playing: Bool) {
        guard currentIndex != nil, !ended, playing != isPlaying else { return }
        isPlaying = playing
        if !playing {
            progress.positionSec = output.currentTimeSec + timeBaseSec
            scrobble.lastTickSec = progress.positionSec
        } else {
            announceIfNeeded()
        }
        onStateChange?()
    }
}
