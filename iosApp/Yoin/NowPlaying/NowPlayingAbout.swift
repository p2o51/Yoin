import SwiftUI
import YoinShared

/// What the About page says about a song: labelled facts in a fixed order.
///
/// Android fills About from Gemini (creation time, place, lyricist, composer,
/// producer, a review: `SongAboutEntry.CANONICAL_ORDER`). iOS has no Gemini
/// yet, so the page reads the server's own record of the song (`getSong`)
/// in Android's About language: label over value, nothing else.
struct SongFacts: Equatable {
    struct Fact: Equatable, Identifiable {
        var label: String
        var value: String
        var id: String { label }
    }

    var facts: [Fact]
    /// False when the server couldn't be asked: ask again next time.
    var complete = true

    /// From the queue entry alone (Apple Music, or the server unreachable).
    init(_ t: PlayableTrack, complete: Bool = true) {
        var f: [Fact] = []
        Self.add(&f, "Album", t.album)
        Self.add(&f, "Artist", t.artist)
        if t.durationSec > 0 { Self.add(&f, "Duration", Self.duration(Int(t.durationSec.rounded()))) }
        facts = f
        self.complete = complete
    }

    /// The mock snapshot (screenshots without a server).
    init(album: Album, track: Track) {
        var f: [Fact] = []
        Self.add(&f, "Album", album.title)
        Self.add(&f, "Artist", album.artist)
        if album.year > 0 { Self.add(&f, "Year", String(album.year)) }
        Self.add(&f, "Duration", Self.duration(track.durationSec))
        facts = f
    }

    /// From the server's record (Subsonic `getSong`).
    init(_ s: SongInfo, fallback t: PlayableTrack) {
        var f: [Fact] = []
        Self.add(&f, "Album", s.album ?? t.album)
        Self.add(&f, "Artist", s.artist ?? t.artist)
        if let y = s.year?.intValue, y > 0 { Self.add(&f, "Year", String(y)) }
        Self.add(&f, "Genre", s.genre)
        if let n = s.track?.intValue, n > 0 {
            let disc = s.disc?.intValue ?? 1
            Self.add(&f, "Track", disc > 1 ? "\(n), disc \(disc)" : String(n))
        }
        let sec = s.durationSec?.intValue ?? Int(t.durationSec.rounded())
        if sec > 0 { Self.add(&f, "Duration", Self.duration(sec)) }
        Self.add(&f, "Format", Self.format(suffix: s.suffix, contentType: s.contentType, bitRate: s.bitRate?.intValue))
        if let bytes = s.size?.int64Value, bytes > 0 {
            Self.add(&f, "Size", ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file))
        }
        if let plays = s.playCount?.intValue { Self.add(&f, "Plays", String(plays)) }
        Self.add(&f, "Last played", s.played.flatMap(Self.date))
        facts = f
    }

    private static func add(_ f: inout [Fact], _ label: String, _ value: String?) {
        guard let v = value?.trimmingCharacters(in: .whitespacesAndNewlines), !v.isEmpty else { return }
        f.append(Fact(label: label, value: v))
    }

    static func duration(_ sec: Int) -> String { String(format: "%d:%02d", sec / 60, sec % 60) }

    /// "FLAC 1,411 kbps": the file type, then its bit rate.
    static func format(suffix: String?, contentType: String?, bitRate: Int?) -> String? {
        let kind = suffix.flatMap { $0.isEmpty ? nil : $0.uppercased() }
            ?? contentType.flatMap { $0.split(separator: "/").last.map { $0.uppercased() } }
        let rate = bitRate.flatMap { $0 > 0 ? "\($0.formatted()) kbps" : nil }
        let parts = [kind, rate].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    /// The server's ISO-8601 time as a medium date.
    static func date(_ iso: String) -> String? {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let parsed = parser.date(from: iso) ?? {
            parser.formatOptions = [.withInternetDateTime]
            return parser.date(from: iso)
        }()
        guard let d = parsed else { return nil }
        return d.formatted(date: .abbreviated, time: .omitted)
    }
}

/// The About page in both sizes (Android `SongInfoDisplay` in the small
/// window, `AboutFullscreenPane` expanded): label in primary over the value
/// in onSurface. Small: labelMedium / bodyMedium, 3 apart, the foot fading
/// out. Expanded: titleMedium / bodyLarge, 8 apart, scrolling.
struct NowPlayingAboutView: View {
    var state: NowPlayingData.AboutState
    var expanded: Bool
    var scheme: CoverScheme
    var bottomPadding: CGFloat = 56

    var body: some View {
        ZStack(alignment: .topLeading) {
            switch state {
            case .loading:
                Text("Loading")
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .transition(.opacity)
            case .ready(let facts):
                list(facts.facts)
                    .transition(.opacity)
            }
        }
        .animation(Yoin.effects, value: state)
    }

    @ViewBuilder
    private func list(_ facts: [SongFacts.Fact]) -> some View {
        let column = VStack(alignment: .leading, spacing: 0) {
            ForEach(facts) { fact in
                VStack(alignment: .leading, spacing: expanded ? 2 : 0) {
                    Text(fact.label)
                        .yoinRole(expanded ? Tokens.type.titleMedium : Tokens.type.labelMedium)
                        .foregroundStyle(scheme.primary)
                    Text(fact.value)
                        .yoinRole(expanded ? Tokens.type.bodyLarge : Tokens.type.bodyMedium)
                        .foregroundStyle(scheme.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, expanded ? 8 : 3)
                .accessibilityElement(children: .combine)
            }
            Spacer().frame(height: bottomPadding)
        }
        // A scroll view takes exactly the height it is given (the small
        // window holds still; the page scrolls).
        ScrollView(.vertical, showsIndicators: false) { column }
            .scrollDisabled(!expanded)
            .mask(Self.fade(top: 0, bottom: expanded ? 64 : 48))
    }

    /// A vertical mask: clear at the edges, solid between.
    static func fade(top: CGFloat, bottom: CGFloat) -> some View {
        VStack(spacing: 0) {
            if top > 0 {
                LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom).frame(height: top)
            }
            Color.black
            LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom).frame(height: bottom)
        }
    }
}
