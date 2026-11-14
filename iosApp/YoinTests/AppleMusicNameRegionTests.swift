import XCTest
@testable import Yoin

/// Apple Music song names from another storefront (Android #118 AppleMusicNameResolverTest): which storefront
/// a song asks, which answers are taken, and how the resolver batches, caches and falls back.
@MainActor
final class AppleMusicNameRegionTests: XCTestCase {
    private struct Read: Equatable {
        var storefront: String
        var kind: String
        var ids: [String]
        var language: String
    }

    private var reads: [Read] = []
    private var isrcReads: [Read] = []
    private var answers: (Read) throws -> [String: AppleMusicNameResolver.Names] = { _ in [:] }
    private var isrcAnswers: [String: AppleMusicNameResolver.Names] = [:]

    private func resolver(timeout: Duration = .seconds(4)) -> AppleMusicNameResolver {
        AppleMusicNameResolver(
            timeout: timeout,
            catalog: { [unowned self] storefront, kind, ids, language in
                let read = Read(storefront: storefront, kind: kind, ids: ids, language: language)
                reads.append(read)
                return try answers(read)
            },
            byIsrc: { [unowned self] storefront, isrcs, language in
                isrcReads.append(Read(storefront: storefront, kind: "isrc", ids: isrcs, language: language))
                return isrcAnswers
            })
    }

    private func song(_ id: String, _ title: String, _ artist: String, genre: String? = nil, isrc: String? = nil) -> AMSong {
        AMSong(resourceId: id, libraryId: nil, catalogId: id, title: title, artist: artist, album: "Album \(id)",
               albumId: nil, artistId: nil, durationMs: nil, trackNumber: nil, disc: nil, artwork: nil, genre: genre,
               releaseDate: nil, isrc: isrc)
    }

    private struct Offline: Error {}

    // MARK: Which storefront

    func test_should_go_by_genre_then_isrc_then_script_when_detecting_a_songs_language() {
        XCTAssertEqual(OriginalNameTarget.detect(title: "Sunny", artist: "Yorushika", genre: "J-Pop", isrc: "USXXX0000001"), .japan)
        XCTAssertEqual(OriginalNameTarget.detect(title: "Love", artist: "Someone", genre: nil, isrc: "KRA401500001"), .korea)
        XCTAssertEqual(OriginalNameTarget.detect(title: "x", artist: "y", genre: "华语流行", isrc: nil), .mainlandChina)
        XCTAssertEqual(OriginalNameTarget.detect(title: "夜に駆ける", artist: "YOASOBI", genre: nil, isrc: nil), .japan)
        XCTAssertEqual(OriginalNameTarget.detect(title: "晴天", artist: "周杰伦", genre: nil, isrc: nil), .mainlandChina)
        XCTAssertEqual(OriginalNameTarget.detect(title: "좋은 날", artist: "IU", genre: nil, isrc: nil), .korea)
        XCTAssertNil(OriginalNameTarget.detect(title: "Bad Guy", artist: "Billie Eilish", genre: "Alternative", isrc: "USUM71900764"))
    }

    func test_should_name_the_storefront_and_language_when_a_genre_or_isrc_points_at_one() {
        XCTAssertEqual(OriginalNameTarget.forGenre("Mandopop"), OriginalNameTarget(storefront: "cn", language: "zh-Hans-CN"))
        XCTAssertEqual(OriginalNameTarget.forGenre("K-Pop"), OriginalNameTarget(storefront: "kr", language: "ko"))
        XCTAssertEqual(OriginalNameTarget.forGenre(" Anime "), OriginalNameTarget(storefront: "jp", language: "ja"))
        XCTAssertEqual(OriginalNameTarget.forGenre("Russian Pop")?.storefront, "ru")
        XCTAssertNil(OriginalNameTarget.forGenre("Pop"))
        XCTAssertNil(OriginalNameTarget.forGenre(""))
        XCTAssertEqual(OriginalNameTarget.forIsrc("twa011900001"), .mainlandChina)
        XCTAssertEqual(OriginalNameTarget.forIsrc("HKA0X1900001"), .mainlandChina)
        XCTAssertNil(OriginalNameTarget.forIsrc("GBUM71900001"))
        XCTAssertNil(OriginalNameTarget.forIsrc(nil))
    }

    func test_should_ask_the_fixed_regions_storefront_for_every_song_and_nothing_when_off() {
        XCTAssertEqual(OriginalNameTarget.forRegion(.mainlandChina, title: "Bad Guy", artist: "Billie Eilish", genre: nil, isrc: nil), .mainlandChina)
        XCTAssertEqual(OriginalNameTarget.forRegion(.japan, title: "Bad Guy", artist: "Billie Eilish", genre: nil, isrc: nil), .japan)
        XCTAssertNil(OriginalNameTarget.forRegion(.off, title: "晴る", artist: "ヨルシカ", genre: "J-Pop", isrc: nil))
        XCTAssertNil(OriginalNameTarget.forRegion(.original, title: "Bad Guy", artist: "Billie Eilish", genre: nil, isrc: nil))
    }

    func test_should_take_only_a_name_in_its_own_script_when_the_region_is_original() {
        XCTAssertTrue(OriginalNameTarget.accepts(.original, target: .japan, shown: "Sunny", resolved: "晴る"))
        // The storefront answered in English: nothing original about it.
        XCTAssertFalse(OriginalNameTarget.accepts(.original, target: .japan, shown: "Sunny", resolved: "Haru"))
        XCTAssertFalse(OriginalNameTarget.accepts(.original, target: .japan, shown: "Sunny", resolved: "Café"))
        // A Chinese name has Han characters; kana alone is not one.
        XCTAssertFalse(OriginalNameTarget.accepts(.original, target: .mainlandChina, shown: "x", resolved: "かな"))
        // The same name, or none, is no answer.
        XCTAssertFalse(OriginalNameTarget.accepts(.original, target: .japan, shown: "晴る", resolved: " 晴る "))
        XCTAssertFalse(OriginalNameTarget.accepts(.japan, target: .japan, shown: "Sunny", resolved: "  "))
        // A fixed region shows what its storefront calls the song.
        XCTAssertTrue(OriginalNameTarget.accepts(.japan, target: .japan, shown: "Kim Petras", resolved: "キム"))
        XCTAssertTrue(OriginalNameTarget.accepts(.mainlandChina, target: .mainlandChina, shown: "Sunny", resolved: "Sunny Day"))
    }

    // MARK: The resolver

    func test_should_ask_the_songs_own_storefront_and_rename_what_it_answers_when_original() async {
        answers = { _ in ["1": .init(title: "晴る", artist: "ヨルシカ", album: "晴る - Single")] }
        let songs = [song("1", "Sunny", "Yorushika", genre: "J-Pop"), song("2", "Bad Guy", "Billie Eilish", genre: "Alternative")]

        let renamed = await resolver().songs(.original, accountStorefront: "us", songs)

        XCTAssertEqual(reads, [Read(storefront: "jp", kind: "songs", ids: ["1"], language: "ja")])
        XCTAssertEqual(renamed[0].title, "晴る")
        XCTAssertEqual(renamed[0].artist, "ヨルシカ")
        XCTAssertEqual(renamed[0].album, "晴る - Single")
        XCTAssertEqual(renamed[1], songs[1])
    }

    func test_should_ask_nothing_again_when_a_list_is_read_twice() async {
        let r = resolver()
        let songs = [song("1", "Sunny", "Yorushika", genre: "J-Pop")]

        _ = await r.songs(.original, accountStorefront: "us", songs)
        let again = await r.songs(.original, accountStorefront: "us", songs)

        XCTAssertEqual(reads.count, 1)
        XCTAssertEqual(again, songs)
    }

    func test_should_never_ask_the_accounts_own_storefront() async {
        let songs = [song("1", "夜に駆ける", "YOASOBI", genre: "J-Pop")]
        let r = resolver()

        let a = await r.songs(.original, accountStorefront: "jp", songs)
        let b = await r.songs(.japan, accountStorefront: "jp", songs)

        XCTAssertEqual(a, songs)
        XCTAssertEqual(b, songs)
        XCTAssertTrue(reads.isEmpty)
    }

    func test_should_ask_by_isrc_when_the_storefront_misses_a_song_by_its_id() async {
        isrcAnswers = ["JPXX01900001": .init(title: "夜に駆ける", artist: "YOASOBI", album: "THE BOOK")]
        let songs = [song("1", "Racing Into the Night", "YOASOBI", isrc: "JPXX01900001")]

        let renamed = await resolver().songs(.original, accountStorefront: "us", songs)

        XCTAssertEqual(isrcReads, [Read(storefront: "jp", kind: "isrc", ids: ["JPXX01900001"], language: "ja")])
        XCTAssertEqual(renamed[0].title, "夜に駆ける")
    }

    func test_should_rename_an_album_and_its_tracks_from_cn_when_the_region_is_mainland_china() async {
        answers = { read in
            read.kind == "albums" ? ["10": .init(title: "范特西", artist: "周杰伦", album: nil)]
                : ["1": .init(title: "爱在西元前", artist: "周杰伦", album: "范特西")]
        }
        let album = AMAlbum(resourceId: "10", isLibrary: false, catalogId: "10", title: "Fantasy", artist: "Jay Chou",
                            artistId: nil, artwork: nil, releaseDate: nil, dateAdded: nil, trackCount: 1, isSingle: false,
                            isCompilation: false, genre: "Mandopop", tracks: [song("1", "Love Before BC", "Jay Chou")])

        let renamed = await resolver().album(.mainlandChina, accountStorefront: "us", album)

        XCTAssertTrue(reads.allSatisfy { $0.storefront == "cn" && $0.language == "zh-Hans-CN" })
        XCTAssertEqual(Set(reads.map(\.kind)), ["albums", "songs"])
        XCTAssertEqual(renamed.title, "范特西")
        XCTAssertEqual(renamed.artist, "周杰伦")
        XCTAssertEqual(renamed.tracks.map(\.title), ["爱在西元前"])
    }

    func test_should_keep_the_shown_names_when_the_read_fails() async {
        answers = { _ in throw Offline() }
        let songs = [song("1", "Sunny", "Yorushika", genre: "J-Pop")]
        let r = resolver()

        let renamed = await r.songs(.original, accountStorefront: "us", songs)
        XCTAssertEqual(renamed, songs)
        // A failure isn't kept as a miss: the next read asks again.
        _ = await r.songs(.original, accountStorefront: "us", songs)
        XCTAssertEqual(reads.count, 2)
    }

    func test_should_ask_nothing_when_off_or_for_a_song_without_a_catalog_id() async {
        var upload = song("i.1", "晴る", "ヨルシカ", genre: "J-Pop")
        upload.catalogId = nil
        let songs = [song("1", "Sunny", "Yorushika", genre: "J-Pop")]
        let r = resolver()

        let off = await r.songs(.off, accountStorefront: "us", songs)
        let imported = await r.songs(.japan, accountStorefront: "us", [upload])

        XCTAssertEqual(off, songs)
        XCTAssertEqual(imported, [upload])
        XCTAssertTrue(reads.isEmpty)
    }

    func test_should_ask_each_storefront_at_once() async {
        // Each storefront's read waits until the other's has started: asked one after the other, neither
        // would answer within the resolver's time (Android: one async per storefront, awaited together).
        let r = AppleMusicNameResolver(timeout: .seconds(3), catalog: { [unowned self] storefront, kind, ids, language in
            reads.append(Read(storefront: storefront, kind: kind, ids: ids, language: language))
            let clock = ContinuousClock()
            let deadline = clock.now + .seconds(1)
            while reads.count < 2, clock.now < deadline { try await Task.sleep(for: .milliseconds(10)) }
            guard reads.count == 2 else { throw Offline() }
            return [ids[0]: .init(title: storefront == "jp" ? "晴る" : "좋은 날", artist: nil, album: nil)]
        }, byIsrc: { _, _, _ in [:] })
        let songs = [song("1", "Sunny", "Yorushika", genre: "J-Pop"), song("2", "Good Day", "IU", genre: "K-Pop")]

        let renamed = await r.songs(.original, accountStorefront: "us", songs)

        XCTAssertEqual(Set(reads.map(\.storefront)), ["jp", "kr"])
        XCTAssertEqual(renamed.map(\.title), ["晴る", "좋은 날"])
    }

    func test_should_batch_300_songs_to_a_request() async {
        let songs = (1...301).map { song("\($0)", "Song \($0)", "Artist") }

        _ = await resolver().songs(.japan, accountStorefront: "us", songs)

        XCTAssertEqual(reads.map(\.ids.count), [300, 1])
    }

    func test_should_parse_names_by_id_from_a_catalog_answer() {
        let root = AppleMusicJSON.object(Data(#"""
        {"data":[{"id":"1","type":"songs","attributes":{"name":"晴る","artistName":"ヨルシカ","albumName":"晴る - Single"}},
                 {"id":"2","type":"albums","attributes":{"name":"幻燈","artistName":"ヨルシカ"}},{"id":"3","type":"songs"}]}
        """#.utf8))
        let names = AppleMusicNameResolver.names(in: AppleMusicJSON.resources(root))
        XCTAssertEqual(names["1"], .init(title: "晴る", artist: "ヨルシカ", album: "晴る - Single"))
        XCTAssertEqual(names["2"], .init(title: "幻燈", artist: "ヨルシカ", album: nil))
        XCTAssertNil(names["3"])
    }

    // MARK: The stored choice

    func test_should_keep_each_accounts_region_and_forget_it_when_removed() {
        let defaults = UserDefaults(suiteName: "AppleMusicNameRegionTests")!
        defaults.removePersistentDomain(forName: "AppleMusicNameRegionTests")
        let store = AppleMusicNameRegionStore(defaults: defaults)
        XCTAssertEqual(store.region("a"), .off)

        store.set(.original, for: "a")
        store.set(.japan, for: "b")
        XCTAssertEqual(AppleMusicNameRegionStore(defaults: defaults).region("a"), .original)
        XCTAssertEqual(AppleMusicNameRegionStore(defaults: defaults).region("b"), .japan)

        store.remove("a")
        XCTAssertEqual(AppleMusicNameRegionStore(defaults: defaults).region("a"), .off)
        XCTAssertEqual(store.regions, ["b": .japan])
        XCTAssertEqual(AppleMusicNameRegion.from(key: "cn"), .mainlandChina)
        XCTAssertEqual(AppleMusicNameRegion.from(key: "nope"), .off)
    }

    func test_should_read_android_wording_for_every_region() {
        XCTAssertEqual(AppleMusicNameRegion.allCases.map(ServiceSetupView.title), [
            loc("As Apple Music shows them"), loc("In their original language"),
            loc("As China mainland names them"), loc("As Japan names them"),
        ])
        XCTAssertEqual(AppleMusicNameRegion.allCases.map(\.key), ["off", "original", "cn", "jp"])
    }
}
