import SwiftUI
import YoinShared

/*
 * The spine (Android NowPlayingSpine.kt, owner 2026-11-03): the band between a
 * height-capped cover and the rating bar on short windows. It reads the song
 * like a record's spine, one fact at a time, turned a quarter round: the
 * credits the lyrics carried, else the cached About credits (never a Gemini
 * request), and on Subsonic the server's own facts. It turns to the next fact on its own, very faint; a swipe across
 * it steps, damped; a fact longer than the band pans along it before the
 * next. Never cut: the band's ends fade.
 */

/// One fact on the spine: a small label and its value. A blank label is the fallback line.
struct SpineItem: Equatable {
    var label: String
    var value: String
}

/// What a Subsonic server says about the song itself, for the spine and About's receipt (Android
/// SpineSourceFacts). Nil fields are absent.
struct SpineSourceFacts: Equatable {
    var format: String?
    var bitRateKbps: Int?
    var samplingRateHz: Int?
    var bitDepth: Int?
    /// The server's own 1–5 rating, not Yoin's.
    var serverRating: Int?
    var genre: String?
    var year: Int?
    var trackNumber: Int?
    var discNumber: Int?
    // About's receipt shows everything the server says (owner 2026-11-05); the spine keeps to the above.
    var durationSec: Int?
    var channels: Int?
    var sizeBytes: Int64?
    var playCount: Int?
    var bpm: Int?
    var composer: String?
    var comment: String?
    /// When the file was added to the library, ISO-8601 as the server sent it.
    var added: String?
    var path: String?

    /// Android `Track.spineSourceFacts`: the format as the format tags read it (`subsonicAudioFormat`),
    /// the rest as the server sent it, zeros and blanks dropped.
    init(format: String? = nil, bitRateKbps: Int? = nil, samplingRateHz: Int? = nil, bitDepth: Int? = nil,
         serverRating: Int? = nil, genre: String? = nil, year: Int? = nil, trackNumber: Int? = nil,
         discNumber: Int? = nil, durationSec: Int? = nil, channels: Int? = nil, sizeBytes: Int64? = nil,
         playCount: Int? = nil, bpm: Int? = nil, composer: String? = nil, comment: String? = nil,
         added: String? = nil, path: String? = nil) {
        self.format = format
        self.bitRateKbps = bitRateKbps
        self.samplingRateHz = samplingRateHz
        self.bitDepth = bitDepth
        self.serverRating = serverRating.flatMap { (1...5).contains($0) ? $0 : nil }
        self.genre = genre.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        self.year = year.flatMap { $0 > 0 ? $0 : nil }
        self.trackNumber = trackNumber.flatMap { $0 > 0 ? $0 : nil }
        self.discNumber = discNumber
        self.durationSec = durationSec
        self.channels = channels
        self.sizeBytes = sizeBytes
        self.playCount = playCount
        self.bpm = bpm
        self.composer = composer
        self.comment = comment
        self.added = added
        self.path = path
    }

    init(suffix: String?, bitRate: Int?, audio: TrackAudio?, userRating: Int?, genre: String?, year: Int?,
         track: Int?, disc: Int?) {
        let f = SongFormat.subsonic(suffix: suffix, bitRateKbps: bitRate, audio: audio)
        self.init(format: f?.codec.map(FormatTags.codecLabel), bitRateKbps: f?.bitRateKbps,
                  samplingRateHz: f?.sampleRateHz, bitDepth: f?.bitDepth, serverRating: userRating,
                  genre: genre, year: year, trackNumber: track, discNumber: disc)
    }

    /// From the server's record (Subsonic `getSong`), with what only the receipt reads.
    init(_ s: SongInfo) {
        let audio = s.audio.map {
            TrackAudio(samplingRate: $0.samplingRate?.intValue, bitDepth: $0.bitDepth?.intValue,
                       channelCount: $0.channelCount?.intValue)
        }
        self.init(suffix: s.suffix, bitRate: s.bitRate?.intValue, audio: audio, userRating: s.userRating?.intValue,
                  genre: s.genre, year: s.year?.intValue, track: s.track?.intValue, disc: s.disc?.intValue)
        durationSec = (s.durationSec?.intValue).flatMap { $0 > 0 ? $0 : nil }
        channels = (audio?.channelCount).flatMap { $0 > 0 ? $0 : nil }
        sizeBytes = (s.size?.int64Value).flatMap { $0 > 0 ? $0 : nil }
        playCount = s.playCount?.intValue
        bpm = s.bpm?.intValue
        composer = Self.text(s.composer)
        comment = Self.text(s.comment)
        added = Self.text(s.created)
        path = Self.text(s.path)
    }

    private static func text(_ s: String?) -> String? {
        s.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
    }

    /// Android `aboutReceiptRows`' server part: everything the server says, in its order (genre, year,
    /// track, length, quality, channels, size, BPM, plays, its rating, when it was added, the comment, the
    /// file). A value runs onto one line; the quality's parts keep the spine's two spaces.
    func receiptRows(_ labels: AboutLabels) -> [SongFacts.Fact] {
        var rows: [SongFacts.Fact] = []
        func add(_ label: String, _ value: String?) {
            guard let v = value?.split(whereSeparator: \.isWhitespace).joined(separator: " "), !v.isEmpty else { return }
            rows.append(.init(label: label, value: v))
        }
        let spine = labels.spine
        add(spine.genre, genre)
        add(spine.year, year.map(String.init))
        if let track = trackNumber {
            let value = if let disc = discNumber, disc > 1 { spine.discTrack(disc, track) } else { String(track) }
            add(spine.track, value)
        }
        add(labels.length, durationSec.map(SongFacts.duration))
        let quality = [
            format,
            bitDepth.map(spine.bit),
            samplingRateHz.map { spine.khz(FormatTags.khzLabel($0)) },
            bitRateKbps.map(spine.kbps),
        ].compactMap { $0 }
        if !quality.isEmpty { rows.append(.init(label: spine.quality, value: quality.joined(separator: "  "))) }
        add(labels.channels, channels.map(labels.channelCount))
        add(labels.size, sizeBytes.map { labels.megabytes(String(format: "%.1f", Double($0) / 1_048_576)) })
        add(labels.bpm, bpm.map(String.init))
        add(labels.playCount, playCount.map(String.init))
        if let stars = serverRating {
            add(spine.serverRating, String(repeating: "★", count: stars) + String(repeating: "☆", count: 5 - stars))
        }
        add(labels.added, added.map { String($0.prefix(10)) })
        add(labels.comment, comment)
        add(labels.file, path)
        return rows
    }
}

/// The labels About's receipt needs on top of the spine's (Android AboutLabels), so the rows stay a pure
/// function.
struct AboutLabels {
    var spine: SpineLabels
    var length: String
    var channels: String
    var channelCount: (Int) -> String
    var size: String
    var megabytes: (String) -> String
    var bpm: String
    var playCount: String
    var added: String
    var comment: String
    var file: String

    /// Android `np_about_*`.
    @MainActor static let localized = AboutLabels(
        spine: .localized,
        length: String(localized: "Length"),
        channels: String(localized: "Channels"),
        channelCount: { n in
            switch n {
            case 1: String(localized: "Mono")
            case 2: String(localized: "Stereo")
            default: String(localized: "\(n) channels")
            }
        },
        size: String(localized: "File size"),
        megabytes: { String(localized: "\($0) MB") },
        bpm: String(localized: "BPM"),
        playCount: String(localized: "Plays"),
        added: String(localized: "about.added", defaultValue: "Added"),
        comment: String(localized: "Comment"),
        file: String(localized: "File")
    )
}

/// The labels the spine needs, so the item list stays a pure function (Android SpineLabels).
struct SpineLabels {
    var about: (String) -> String
    var quality: String
    var serverRating: String
    var genre: String
    var year: String
    var track: String
    var discTrack: (Int, Int) -> String
    var kbps: (Int) -> String
    var khz: (String) -> String
    var bit: (Int) -> String

    /// Android `np_spine_*` and the expanded About labels (`np_about_*`).
    @MainActor static let localized = SpineLabels(
        about: { NowPlayingAboutView.label($0, expanded: true) },
        quality: String(localized: "Quality"),
        serverRating: String(localized: "Server rating"),
        genre: String(localized: "Genre"),
        year: String(localized: "Year"),
        track: String(localized: "Track"),
        discTrack: { String(localized: "Disc \($0), track \($1)") },
        kbps: { String(localized: "\($0) kbps") },
        khz: { String(localized: "\($0) kHz") },
        bit: { String(localized: "\($0)-bit") }
    )
}

enum NowPlayingSpine {
    /// Android SpineMinWidth: narrower than this, the band stays empty.
    static let minWidth: CGFloat = 24
    /// How long a fact rests before the next one comes in (Android SpineDwellMs).
    static let dwell: Double = 9
    /// A fact longer than the band waits this long, then pans along it at `panSpeed` (pt/s).
    static let panDelay: Double = 1.2
    static let panSpeed: CGFloat = 18
    /// A swipe moves the fact at this share of the finger, never more than `swipeReach`
    /// (the band is narrow), and steps past `swipeStep` of finger travel.
    static let swipeDamping: CGFloat = 0.15
    static let swipeReach: CGFloat = 14
    static let swipeStep: CGFloat = 32
    /// The band's ends fade over this much; nothing is cut.
    static let fadeEdge: CGFloat = 20

    /// Android `spineBand`: what the row has between the resting cover and the rating column's gap.
    static func band(stageWidth: CGFloat, cover: CGFloat, ratingColumn: CGFloat) -> CGFloat {
        stageWidth - Tokens.np.COLUMN_PADDING.cg * 2 - Tokens.np.RATING_GAP.cg - ratingColumn - cover
    }

    /// The About credits the spine reads, in the About tab's order (the review is too long for it).
    static let creditKeys = [
        SongAboutStore.Canon.creationTime,
        SongAboutStore.Canon.creationLocation,
        SongAboutStore.Canon.lyricist,
        SongAboutStore.Canon.composer,
        SongAboutStore.Canon.producer,
    ]

    /// Android `spineItems`: the About credits, then the Subsonic facts; when there is neither, one line
    /// of album, artist and title. `credits` (the lyrics source's own) answer lyricist, composer and
    /// producer before the cached About answer does.
    static func items(entries: [SongAboutStore.Entry], facts: SpineSourceFacts?, album: String, artist: String,
                      title: String, labels: SpineLabels, credits: SongCredits? = nil) -> [SpineItem] {
        var items: [SpineItem] = []
        for key in creditKeys {
            guard let answer = credits?.names(forAboutKey: key)
                ?? entries.first(where: { $0.kind == SongAboutStore.Kind.canonical && $0.entryKey == key })?
                .answerText else { continue }
            let v = answer.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            if !v.isEmpty { items.append(SpineItem(label: labels.about(key), value: v)) }
        }
        if let facts {
            let quality = [
                facts.format,
                facts.bitDepth.map(labels.bit),
                facts.samplingRateHz.map { labels.khz(FormatTags.khzLabel($0)) },
                facts.bitRateKbps.map(labels.kbps),
            ].compactMap { $0 }
            if !quality.isEmpty { items.append(SpineItem(label: labels.quality, value: quality.joined(separator: "  "))) }
            if let stars = facts.serverRating {
                items.append(SpineItem(label: labels.serverRating,
                                       value: String(repeating: "★", count: stars) + String(repeating: "☆", count: 5 - stars)))
            }
            if let g = facts.genre { items.append(SpineItem(label: labels.genre, value: g)) }
            if let y = facts.year { items.append(SpineItem(label: labels.year, value: String(y))) }
            if let track = facts.trackNumber {
                let value = if let disc = facts.discNumber, disc > 1 { labels.discTrack(disc, track) } else { String(track) }
                items.append(SpineItem(label: labels.track, value: value))
            }
        }
        if items.isEmpty {
            let line = [album, artist, title].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                .joined(separator: "   ")
            if !line.isEmpty { items.append(SpineItem(label: "", value: line)) }
        }
        return items
    }

    /// The next index after a swipe of `travel` points across the band (left steps forward, right back);
    /// nil when it is too short to step or there is nothing to step to.
    static func swipeStep(index: Int, travel: CGFloat, count: Int) -> Int? {
        guard count > 1, abs(travel) >= swipeStep else { return nil }
        return index + (travel < 0 ? 1 : -1)
    }

    /// The finger's travel as the fact follows it: damped, within the reach.
    static func dragOffset(_ travel: CGFloat) -> CGFloat {
        min(max(travel * swipeDamping, -swipeReach), swipeReach)
    }
}

/// Where the spine stands in the cover row: the band's width and the resting cover's side.
struct SpineSlot: Equatable {
    var band: CGFloat
    var length: CGFloat
}

/// The spine itself (Android NowPlayingSpine): `items` one at a time, laid out
/// `length` long (the cover's side) and turned a quarter round so they read
/// bottom to top. A new `songKey` restarts it at the first fact.
struct NowPlayingSpineView: View {
    var items: [SpineItem]
    var songKey: String
    var length: CGFloat
    var band: CGFloat
    var ink: Color

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var index = 0
    @State private var dragging = false
    @State private var drag: CGFloat = 0
    /// The finger's own travel across the band (`drag` is it damped).
    @State private var travel: CGFloat = 0
    /// The current fact's pan time, which the dwell waits out too.
    @State private var panTime: Double = 0

    private struct Frame: Hashable {
        var songKey: String
        var index: Int
    }

    private struct DwellKey: Hashable {
        var frame: Frame
        var count: Int
        var dragging: Bool
        var panTime: Double
    }

    var body: some View {
        let count = items.count
        let current = count == 0 ? 0 : ((index % count) + count) % count
        let frame = Frame(songKey: songKey, index: current)
        ZStack {
            if count > 0 {
                SpineLine(item: items[current], length: length, ink: ink, pan: !reduceMotion) { t in
                    panTime = t
                }
                .id(frame)
                // Android oneLineLyricLanding: the next fact grows from 94 % at its start and rises a
                // sixth of the line; the old one settles back and fades.
                .transition(.asymmetric(
                    insertion: .scale(scale: 0.94, anchor: .leading)
                        .combined(with: .offset(y: OneLineLyricLine.rise))
                        .combined(with: .opacity.animation(Yoin.effects)),
                    removal: .scale(scale: 0.94, anchor: .leading)
                        .combined(with: .opacity.animation(Yoin.spatialFast))))
            }
        }
        .frame(width: length, height: band)
        .mask(SpineFade(length: length))
        .animation(Yoin.spatial, value: frame)
        .rotationEffect(.degrees(-90))
        // Across the band: the finger's axis.
        .offset(x: drag)
        .frame(width: band, height: length)
        .contentShape(.rect)
        .gesture(AlbumAxisPan(
            claims: { axis, _ in axis == .horizontal && count > 0 },
            changed: { _, t in
                dragging = true
                travel = t.x
                drag = NowPlayingSpine.dragOffset(t.x)
            },
            ended: { _, _, cancelled in
                if !cancelled, let next = NowPlayingSpine.swipeStep(index: current, travel: travel, count: count) {
                    index = next
                }
                travel = 0
                dragging = false
                withAnimation(Yoin.spatial) { drag = 0 }
            }
        ))
        .task(id: DwellKey(frame: frame, count: count, dragging: dragging, panTime: panTime)) {
            // Rest, pan if it is long (the line reports its own pan time), rest, next.
            guard !dragging, count > 1 else { return }
            try? await Task.sleep(for: .seconds((NowPlayingSpine.dwell + panTime) / LaunchConfig.animationSpeed))
            guard !Task.isCancelled else { return }
            index = current + 1
        }
        .onChange(of: songKey) { index = 0 }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(count > 0 ? [items[current].label, items[current].value].filter { !$0.isEmpty }
            .joined(separator: ", ") : "")
    }
}

/// The band's ends fade over `NowPlayingSpine.fadeEdge` (Android horizontalFadeMask).
private struct SpineFade: View {
    var length: CGFloat

    var body: some View {
        let e = min(0.5, NowPlayingSpine.fadeEdge / max(length, 1))
        LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: e),
                               .init(color: .black, location: 1 - e), .init(color: .clear, location: 1)],
                       startPoint: .leading, endPoint: .trailing)
    }
}

/// One fact: the label quiet, the value in the line's ink; pans along the band when it is longer.
private struct SpineLine: View {
    var item: SpineItem
    var length: CGFloat
    var ink: Color
    var pan: Bool
    var onPanTime: (Double) -> Void

    @State private var width: CGFloat = 0
    @State private var offset: CGFloat = 0

    var body: some View {
        let room = length - NowPlayingSpine.fadeEdge * 2
        let overflow = max(0, width - room)
        text
            .fixedSize()
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
            .offset(x: NowPlayingSpine.fadeEdge + offset)
            .frame(width: length, alignment: .leading)
            .frame(maxHeight: .infinity)
            .task(id: PanKey(overflow: overflow, pan: pan)) {
                offset = 0
                guard pan, overflow > 0 else {
                    onPanTime(0)
                    return
                }
                let seconds = Double(overflow / NowPlayingSpine.panSpeed)
                onPanTime(NowPlayingSpine.panDelay + seconds)
                try? await Task.sleep(for: .seconds(NowPlayingSpine.panDelay / LaunchConfig.animationSpeed))
                guard !Task.isCancelled else { return }
                // A marquee: constant speed along the band, as Android's linear pan.
                withAnimation(.linear(duration: seconds / LaunchConfig.animationSpeed)) { offset = -overflow }
            }
    }

    private struct PanKey: Hashable {
        var overflow: CGFloat
        var pan: Bool
    }

    private var text: Text {
        let role = Tokens.type.labelLarge
        var value = AttributedString(item.value)
        value.foregroundColor = ink
        guard !item.label.isEmpty else {
            return Text(value).font(.gsf(role.size.cg, Font.Weight(gsf: role.weight))).tracking(role.letterSpacing.cg)
        }
        var label = AttributedString(item.label + "  ")
        label.foregroundColor = ink.opacity(0.6)
        label.font = .gsf(role.size.cg, .regular)
        return Text(label + value).font(.gsf(role.size.cg, Font.Weight(gsf: role.weight)))
            .tracking(role.letterSpacing.cg)
    }
}
