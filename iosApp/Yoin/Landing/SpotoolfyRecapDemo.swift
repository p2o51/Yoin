#if DEBUG
import SwiftUI

// Screenshots and recordings of the Spotoolfy recap without an export (debug builds):
//   -spotoolfyRecapDemo words|notes|ratings   which recap: a full burst with lyrics, too few notes, ratings only
//   -recapFromSettings YES                    the Settings reopening (Done instead of Join Yoin)
//   -recapPage 1                              open on the second page
//   -recapDetail word|lyric|note              open that detail card once the burst has played
// The cover wall uses the covers of the library in use (the demo server's), when it has any.

struct SpotoolfyRecapDemoHost: ViewModifier {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @State private var recap: SpotoolfyRecap?

    func body(content: Content) -> some View {
        content
            .task {
                guard let mode = UserDefaults.standard.string(forKey: "spotoolfyRecapDemo") else { return }
                // Give the library a moment to load its albums, for the wall's covers.
                for _ in 0..<20 where model.store.albums.isEmpty { try? await Task.sleep(for: .milliseconds(250)) }
                let covers = model.store.albums.compactMap { $0.palette.cover?.url.absoluteString }
                recap = Self.recap(mode, covers: covers)
            }
            .fullScreenCover(isPresented: Binding(get: { recap != nil }, set: { if !$0 { recap = nil } })) {
                if let recap {
                    let d = UserDefaults.standard
                    SpotoolfyRecapView(recap: recap, scheme: CoverScheme(seed: Yoin.violet, dark: colorScheme == .dark),
                                       fromSettings: d.bool(forKey: "recapFromSettings"),
                                       onClose: { self.recap = nil },
                                       startPage: d.integer(forKey: "recapPage"),
                                       startDetail: Self.detail(d.string(forKey: "recapDetail"), recap))
                }
            }
    }

    static func detail(_ kind: String?, _ r: SpotoolfyRecap) -> RecapDetail? {
        switch kind {
        case "word": r.keywords.first.map { .word($0) }
        case "lyric": r.lyricSnippets.first.map { .lyric($0) }
        case "note": r.fallbackNotes.first.map { .note($0) }
        default: nil
        }
    }

    private static let day: Int64 = 86_400_000
    private static let first: Int64 = 1_730_000_000_000

    static func recap(_ mode: String, covers: [String]) -> SpotoolfyRecap {
        let songs = ["Blue Rooms", "Paper Moons", "Low Tide", "Glass Harbour", "Night Bus", "Small Hours"]
        let artists = ["Okto", "Mira Vale", "The Lanterns"]
        func note(_ text: String, _ i: Int) -> RecapNote {
            RecapNote(text: text, trackTitle: songs[i % songs.count], artist: artists[i % artists.count],
                      writtenAt: first + Int64(i * 23) * day)
        }
        let lyrics = [
            RecapLyric(lyric: "把灯关掉，让副歌自己亮起来", trackTitle: "Blue Rooms", artist: "Okto",
                       capturedAt: first + 12 * day, note: "第一次听就记住了这一句。"),
            RecapLyric(lyric: "潮水退去的时候，我们还在原地", trackTitle: "Low Tide", artist: "Mira Vale",
                       capturedAt: first + 590 * day, note: "适合深夜回家的路上。"),
        ]
        switch mode {
        case "notes":
            let notes = ["前奏的钢琴像在下雨", "副歌一出来就想跟着唱", "鼓点很克制，留白很多",
                         "适合周末早上", "贝斯线太好听了"].enumerated().map { note($1, $0) }
            return SpotoolfyRecap(noteCount: notes.count, trackCount: 5, artistCount: 3, ratedTrackCount: 4,
                                  firstRecordAt: first, lastRecordAt: first + 300 * day, coverUrls: covers,
                                  keywords: [], emoji: [], lyricSnippets: [lyrics[0]], fallbackNotes: notes)
        case "ratings":
            return SpotoolfyRecap(noteCount: 0, trackCount: 42, artistCount: 17, ratedTrackCount: 42,
                                  firstRecordAt: first, lastRecordAt: first + 210 * day, coverUrls: covers,
                                  keywords: [], emoji: [], lyricSnippets: [], fallbackNotes: [])
        default:
            let words = ["副歌", "夏天", "温柔", "鼓点", "夜晚", "合成器", "想念", "贝斯", "旋律", "前奏", "雨",
                         "人声", "海边", "失眠", "吉他", "回家", "循环", "电台", "黄昏", "耳机", "城市", "未来",
                         "风", "梦", "孤独", "热烈"]
            let keywords = words.enumerated().map { i, w in
                RecapKeyword(text: w, count: max(2, 34 - i * 2 + (i % 3)),
                             notes: [note("这首的\(w)真的很打动我。", i), note("又想起了\(w)，循环了一晚上。", i + 7)])
            }
            return SpotoolfyRecap(noteCount: 188, trackCount: 64, artistCount: 21, ratedTrackCount: 52,
                                  firstRecordAt: first, lastRecordAt: first + 590 * day, coverUrls: covers,
                                  keywords: keywords,
                                  emoji: [RecapEmoji(emoji: "🔥", count: 9), RecapEmoji(emoji: "🌊", count: 6)],
                                  lyricSnippets: lyrics, fallbackNotes: [])
        }
    }
}
#endif
