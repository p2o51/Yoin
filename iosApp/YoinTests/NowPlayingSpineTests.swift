import XCTest
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

    private func items(_ entries: [SongAboutStore.Entry], _ facts: SpineSourceFacts? = nil) -> [SpineItem] {
        NowPlayingSpine.items(entries: entries, facts: facts, album: "Album", artist: "Artist", title: "Title",
                              labels: labels)
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
