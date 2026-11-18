import MediaPlayer
import XCTest
@testable import Yoin

@MainActor
final class AboutParityTests: XCTestCase {
    private func entry(_ kind: String, _ key: String, answer: String, title: String? = nil) -> SongAboutStore.Entry {
        SongAboutStore.Entry(titleKey: "t", artistKey: "a", albumKey: "al", titleDisplay: "T", artistDisplay: "A",
                             albumDisplay: "Al", kind: kind, entryKey: key, promptText: nil, titleText: title,
                             answerText: answer, createdAt: 0, updatedAt: 0, language: "en")
    }

    private var facts: SongFacts {
        SongFacts(PlayableTrack(id: "1", provider: "subsonic", title: "T", artist: "Artist", album: "Album", albumId: nil,
                                durationSec: 200, streamURL: URL(string: "https://x.example/s")!, coverURL: nil, sourceKbps: nil))
    }

    // MARK: About (C-U6)

    func test_should_keep_server_facts_when_gemini_rows_exist() {
        let rows = NowPlayingAboutView.rows(entries: [
            entry(SongAboutStore.Kind.canonical, SongAboutStore.Canon.composer, answer: "Bellamy"),
            entry(SongAboutStore.Kind.canonical, SongAboutStore.Canon.review, answer: "A review"),
        ], facts: facts, expanded: true)
        let labels = rows.map(\.label)
        XCTAssertTrue(labels.contains(loc("Album")))
        XCTAssertTrue(labels.contains(loc("Length")))
        XCTAssertTrue(rows.contains { $0.value == "Artist" })
    }

    // MARK: The server's receipt (Android aboutReceiptRows, owner 2026-11-05)

    func test_should_list_everything_the_server_says_in_androids_order() {
        let facts = SpineSourceFacts(format: "FLAC", bitRateKbps: 2304, samplingRateHz: 96_000, bitDepth: 24,
                                     serverRating: 4, genre: "Alternative", year: 2006, trackNumber: 3, discNumber: 2,
                                     durationSec: 3725, channels: 2, sizeBytes: 9_437_184, playCount: 12, bpm: 128,
                                     comment: "Live\n take", added: "2024-05-01T10:20:30Z", path: "Muse/BH&R/03.flac")
        let rows = facts.receiptRows(.localized)
        XCTAssertEqual(rows.map(\.label), [loc("Genre"), loc("Year"), loc("Track"), loc("Length"), loc("Quality"),
                                           loc("Channels"), loc("File size"), loc("BPM"), loc("Plays"),
                                           loc("Server rating"), loc("about.added"), loc("Comment"), loc("File")])
        XCTAssertEqual(rows.map(\.value), ["Alternative", "2006", loc("Disc \(2), track \(3)"), "1:02:05",
                                           "FLAC  \(loc("\(24)-bit"))  \(loc("\("96") kHz"))  \(loc("\(2304) kbps"))",
                                           loc("Stereo"), loc("\("9.0") MB"), "128", "12", "★★★★☆", "2024-05-01",
                                           "Live take", "Muse/BH&R/03.flac"])
        XCTAssertEqual(SpineSourceFacts(channels: 1).receiptRows(.localized).map(\.value), [loc("Mono")])
        XCTAssertEqual(SpineSourceFacts(channels: 6).receiptRows(.localized).map(\.value), [loc("\(6) channels")])
        XCTAssertEqual(SpineSourceFacts().receiptRows(.localized), [])
    }

    func test_should_credit_the_servers_composer_before_gemini_after_the_lyrics() {
        var song = facts
        song.spine = SpineSourceFacts(composer: "Bellamy")
        let gemini = [entry(SongAboutStore.Kind.canonical, SongAboutStore.Canon.composer, answer: "Gemini C")]
        let server = NowPlayingAboutView.rows(entries: gemini, facts: song, expanded: true)
        XCTAssertEqual(server.first?.value, "Bellamy")
        XCTAssertEqual(server.first?.label, loc("Composer"))
        XCTAssertNil(server.first?.entry)
        let lyrics = NowPlayingAboutView.rows(entries: gemini, facts: song, expanded: true,
                                              credits: SongCredits(composer: "钱雷"))
        XCTAssertEqual(lyrics.first?.value, "钱雷")
        XCTAssertEqual(NowPlayingAboutView.rows(entries: gemini, facts: facts, expanded: true).first?.value, "Gemini C")
    }

    func test_should_order_credits_then_facts_then_review_then_asks() {
        let rows = NowPlayingAboutView.rows(entries: [
            entry(SongAboutStore.Kind.ask, "q1", answer: "Because", title: "Why?"),
            entry(SongAboutStore.Kind.canonical, SongAboutStore.Canon.review, answer: "A review"),
            entry(SongAboutStore.Kind.canonical, SongAboutStore.Canon.creationTime, answer: "2009"),
            entry(SongAboutStore.Kind.canonical, SongAboutStore.Canon.lyricist, answer: "Matt"),
        ], facts: facts, expanded: true)
        let values = rows.map(\.value)
        XCTAssertEqual(values.first, "Matt")
        XCTAssertEqual(values[1], "2009")
        let album = values.firstIndex(of: "Album")!
        let review = values.firstIndex(of: "A review")!
        let ask = values.firstIndex(of: "Because")!
        XCTAssertTrue(1 < album && album < review && review < ask)
    }

    func test_should_show_facts_alone_when_there_are_no_entries() {
        let rows = NowPlayingAboutView.rows(entries: [], facts: facts, expanded: false)
        XCTAssertEqual(rows.count, facts.facts.count)
        XCTAssertTrue(rows.allSatisfy { $0.entry == nil })
    }

    // MARK: The lyrics' credits (Android aboutReceiptRows `credited`, I12)

    func test_should_prefer_the_lyrics_credits_then_gemini_when_both_answer() {
        let rows = NowPlayingAboutView.rows(entries: [
            entry(SongAboutStore.Kind.canonical, SongAboutStore.Canon.lyricist, answer: "Gemini L"),
            entry(SongAboutStore.Kind.canonical, SongAboutStore.Canon.composer, answer: "Gemini C"),
            entry(SongAboutStore.Kind.canonical, SongAboutStore.Canon.creationTime, answer: "2009"),
        ], facts: nil, expanded: true, credits: SongCredits(lyricist: "唐恬", arranger: "钱雷", producer: "钱雷"))
        XCTAssertEqual(rows.map(\.value), ["唐恬", "Gemini C", "钱雷", "钱雷", "2009"])
        XCTAssertEqual(rows.map(\.label), [loc("Lyricist"), loc("Composer"), loc("Arranger"), loc("Producer"),
                                            loc("Creation Time")])
        // The lyrics' rows are no Gemini rows: no long-press sheet.
        XCTAssertNil(rows[0].entry)
        XCTAssertNotNil(rows[1].entry)
    }

    func test_should_show_the_lyrics_credits_over_the_facts_when_there_is_no_gemini() {
        let rows = NowPlayingAboutView.rows(entries: [], facts: facts, expanded: false,
                                            credits: SongCredits(composer: "钱雷"))
        XCTAssertEqual(rows.first?.value, "钱雷")
        XCTAssertEqual(rows.count, facts.facts.count + 1)
        XCTAssertTrue(rows.allSatisfy(\.receipt))
    }

    func test_should_show_only_the_latest_ask_in_the_small_window() {
        let asks = [entry(SongAboutStore.Kind.ask, "q1", answer: "A1", title: "One"),
                    entry(SongAboutStore.Kind.own, "o1", answer: "A2", title: "Two")]
        XCTAssertEqual(NowPlayingAboutView.rows(entries: asks, facts: nil, expanded: false).count, 1)
        XCTAssertEqual(NowPlayingAboutView.rows(entries: asks, facts: nil, expanded: true).count, 2)
    }

}
