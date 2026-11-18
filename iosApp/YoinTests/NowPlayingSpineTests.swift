import XCTest
import YoinShared
@testable import Yoin

/// Android SpineCreditsTest, plus the band, the swipe and the server record.
@MainActor
final class NowPlayingSpineTests: XCTestCase {
    private let labels = SpineLabels(
        about: { $0 },
        quality: "Quality", serverRating: "Server rating", genre: "Genre", year: "Year", track: "Track",
        discTrack: { "Disc \($0), track \($1)" },
        kbps: { "\($0) kbps" },
        khz: { "\($0) kHz" },
        bit: { "\($0)-bit" }
    )

    private func row(_ kind: String, _ key: String, _ answer: String) -> SongAboutStore.Entry {
        SongAboutStore.Entry(titleKey: "t", artistKey: "a", albumKey: "al", titleDisplay: "T", artistDisplay: "A",
                             albumDisplay: "AL", kind: kind, entryKey: key, promptText: nil, titleText: nil,
                             answerText: answer, createdAt: 0, updatedAt: 0, language: "en")
    }

    private func items(_ entries: [SongAboutStore.Entry], _ facts: SpineSourceFacts? = nil,
                       credits: SongCredits? = nil) -> [SpineItem] {
        NowPlayingSpine.items(entries: entries, facts: facts, album: "Album", artist: "Artist", title: "Title",
                              labels: labels, credits: credits)
    }

    func test_should_listCanonicalCreditsInAboutOrder_when_cached() {
        let entries = [
            row(SongAboutStore.Kind.canonical, SongAboutStore.Canon.producer, "P"),
            row(SongAboutStore.Kind.canonical, SongAboutStore.Canon.lyricist, "L\nL2"),
            row(SongAboutStore.Kind.canonical, SongAboutStore.Canon.review, "a long review"),
            row(SongAboutStore.Kind.ask, SongAboutStore.Canon.composer, "not a credit"),
        ]
        XCTAssertEqual(items(entries), [SpineItem(label: "lyricist", value: "L L2"),
                                        SpineItem(label: "producer", value: "P")])
    }

    // MARK: The lyrics' credits (Android spineItems `credits`, I12)

    func test_should_preferTheLyricsCredits_when_theyNameTheRole() {
        let entries = [
            row(SongAboutStore.Kind.canonical, SongAboutStore.Canon.lyricist, "Gemini's lyricist"),
            row(SongAboutStore.Kind.canonical, SongAboutStore.Canon.composer, "Gemini's composer"),
            row(SongAboutStore.Kind.canonical, SongAboutStore.Canon.producer, "Gemini's producer"),
            row(SongAboutStore.Kind.canonical, SongAboutStore.Canon.creationTime, "2021"),
        ]
        let credits = SongCredits(lyricist: "唐恬", arranger: "钱雷", producer: "钱雷 / 高飞")
        XCTAssertEqual(items(entries, credits: credits), [
            SpineItem(label: SongAboutStore.Canon.creationTime, value: "2021"),
            SpineItem(label: SongAboutStore.Canon.lyricist, value: "唐恬"),
            // The lyrics name no composer: Gemini's answer stands.
            SpineItem(label: SongAboutStore.Canon.composer, value: "Gemini's composer"),
            SpineItem(label: SongAboutStore.Canon.producer, value: "钱雷 / 高飞"),
        ])
    }

    func test_should_showTheLyricsCredits_when_aboutHasNoAnswer() {
        let credits = SongCredits(lyricist: "米津玄師", composer: "米津玄師",
                                  others: [.init(role: "混音", names: "某人")], notices: ["℗ 2018"])
        // The arranger, the other roles and the notices stay off the spine, as on Android.
        XCTAssertEqual(items([], credits: SongCredits(arranger: "A")),
                       [SpineItem(label: "", value: "Album   Artist   Title")])
        XCTAssertEqual(items([], credits: credits), [
            SpineItem(label: SongAboutStore.Canon.lyricist, value: "米津玄師"),
            SpineItem(label: SongAboutStore.Canon.composer, value: "米津玄師"),
        ])
    }

    func test_should_fallBackToAbout_when_theLyricsHaveNoCredits() {
        let entries = [row(SongAboutStore.Kind.canonical, SongAboutStore.Canon.composer, "Gemini's composer")]
        let expected = [SpineItem(label: SongAboutStore.Canon.composer, value: "Gemini's composer")]
        XCTAssertEqual(items(entries, credits: nil), expected)
        XCTAssertEqual(items(entries, credits: SongCredits(others: [.init(role: "吉他", names: "高飞")])), expected)
    }

    func test_should_carryTheParsedCredits_when_lyricsLoad() {
        let raw = "[00:00.00]作词 : 唐恬\n[00:00.50]作曲 : 钱雷\n[00:01.00]吉他 : 高飞\n"
            + "[00:22.29]都 是勇敢的\n[00:36.96]都 不必隐藏"
        let loaded = NowPlayingData.LoadedLyrics(LrcParser.shared.parse(raw: raw))
        XCTAssertEqual(loaded.lines, ["都 是勇敢的", "都 不必隐藏"])
        XCTAssertEqual(loaded.credits, SongCredits(lyricist: "唐恬", composer: "钱雷",
                                                   others: [.init(role: "吉他", names: "高飞")]))
        XCTAssertNil(NowPlayingData.LoadedLyrics(LrcParser.shared.parse(raw: "[00:01.00]One\n[00:02.00]Two")).credits)
    }

    func test_should_addTheServerFacts_when_theTrackIsSubsonic() {
        let facts = SpineSourceFacts(format: "FLAC", bitRateKbps: 2304, samplingRateHz: 96_000, bitDepth: 24,
                                     serverRating: 4, genre: "K-Pop", year: 2026, trackNumber: 3, discNumber: 2)
        XCTAssertEqual(items([], facts), [
            SpineItem(label: "Quality", value: "FLAC  24-bit  96 kHz  2304 kbps"),
            SpineItem(label: "Server rating", value: "★★★★☆"),
            SpineItem(label: "Genre", value: "K-Pop"),
            SpineItem(label: "Year", value: "2026"),
            SpineItem(label: "Track", value: "Disc 2, track 3"),
        ])
    }

    func test_should_showTheTrackAlone_when_theFirstDisc() {
        let facts = SpineSourceFacts(trackNumber: 5, discNumber: 1)
        XCTAssertEqual(items([], facts), [SpineItem(label: "Track", value: "5")])
    }

    func test_should_fallBackToAlbumArtistTitle_when_thereIsNothingElse() {
        let entries = [row(SongAboutStore.Kind.canonical, SongAboutStore.Canon.composer, "  ")]
        XCTAssertEqual(items(entries, SpineSourceFacts()), [SpineItem(label: "", value: "Album   Artist   Title")])
    }

    func test_should_dropZerosAndOutOfRangeRatings_when_readingTheServer() {
        let facts = SpineSourceFacts(serverRating: 0, genre: " ", year: 0, trackNumber: 0)
        XCTAssertEqual(facts, SpineSourceFacts())
    }

    func test_should_readTheFormatAsTheTagsDo_when_fromTheServerRecord() {
        let wav = SpineSourceFacts(suffix: "wav", bitRate: 320, audio: TrackAudio(samplingRate: 44_100, channelCount: 2),
                                   userRating: nil, genre: "Ambient", year: 2022, track: 1, disc: 1)
        XCTAssertEqual(items([], wav).first, SpineItem(label: "Quality", value: "WAV  44.1 kHz  320 kbps"))
        // An MP4 the server couldn't read is no AAC: no quality at all.
        let unread = SpineSourceFacts(suffix: "m4a", bitRate: 0, audio: TrackAudio(samplingRate: 0, bitDepth: 0,
                                                                                    channelCount: 0),
                                      userRating: 3, genre: nil, year: nil, track: nil, disc: nil)
        XCTAssertNil(unread.format)
        XCTAssertEqual(items([], unread), [SpineItem(label: "Server rating", value: "★★★☆☆")])
    }

    func test_should_openTheBand_when_theCoverIsHeightCapped() {
        // Android spineBand: stage − 2 × 24 − 12 − rating column − cover.
        XCTAssertEqual(NowPlayingSpine.band(stageWidth: 402, cover: 260, ratingColumn: 56), 26)
        // A width-capped cover fills the row: no band.
        XCTAssertEqual(NowPlayingSpine.band(stageWidth: 402, cover: 286, ratingColumn: 56), 0)
    }

    private func band(width: CGFloat, height: CGFloat) -> CGFloat {
        let b = NowPlayingBudget(width: width, height: height, forceShort: false)
        return NowPlayingSpine.band(stageWidth: b.stageWidth, cover: b.rest.cover,
                                    ratingColumn: Tokens.np.PHONE_RATING_COLUMN.cg)
    }

    func test_should_leaveRoomForTheSpine_when_theColumnIsShort() {
        // A 16:9-short column (`-screen np-short -npShortHeight 560`) caps the cover by height.
        XCTAssertGreaterThanOrEqual(band(width: 402, height: 560), NowPlayingSpine.minWidth)
    }

    func test_should_keepTheSpineAway_when_anOrdinaryPhoneColumn() {
        // iPhone 17 Pro, SE and the ~700 pt reference: the cover fills the row's width.
        XCTAssertLessThan(band(width: 402, height: 778), NowPlayingSpine.minWidth)
        XCTAssertLessThan(band(width: 375, height: 631), NowPlayingSpine.minWidth)
        XCTAssertLessThan(band(width: 402, height: 700), NowPlayingSpine.minWidth)
    }

    func test_should_stepOnlyPastTheThreshold_when_swiped() {
        XCTAssertNil(NowPlayingSpine.swipeStep(index: 0, travel: -31, count: 3))
        XCTAssertEqual(NowPlayingSpine.swipeStep(index: 0, travel: -32, count: 3), 1)
        XCTAssertEqual(NowPlayingSpine.swipeStep(index: 2, travel: 40, count: 3), 1)
        XCTAssertNil(NowPlayingSpine.swipeStep(index: 0, travel: -80, count: 1))
        XCTAssertEqual(NowPlayingSpine.dragOffset(-200), -14)
        XCTAssertEqual(NowPlayingSpine.dragOffset(40), 6, accuracy: 0.0001)
    }
}
