import SwiftUI
import XCTest
import YoinShared
@testable import Yoin

/// The Explicit "E" and the release kinds on iOS (Android PR #343, shared core `YoinReleaseMarks`): the shared
/// golden vectors (shared/src/commonTest/resources/golden/release-marks.json) run through what iOS adds — the
/// Swift enums, the Apple Music / Spotify / Subsonic mappings, Library's kind filter and the badge's size and fit —
/// then each service's mapping, the detail cache (old pages still read, and are read again), the E's layout and
/// the strings.
@MainActor
final class ReleaseMarksTests: XCTestCase {

    // MARK: Golden file

    private static let golden: [String: Any] = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/src/commonTest/resources/golden/release-marks.json")
        let data = try! Data(contentsOf: url)
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }()

    private func vectors(_ section: String) -> [[String: Any]] {
        let v = Self.golden[section] as! [[String: Any]]
        XCTAssertFalse(v.isEmpty, section)
        return v
    }

    private static func string(_ o: Any?) -> String? { o as? String }
    private static func bool(_ o: Any?) -> Bool? { (o as? NSNumber)?.boolValue }
    private static func int(_ o: Any?) -> Int? { (o as? NSNumber)?.intValue }

    /// A Kotlin name ("Explicit", "EP", "SinglesAndEps") as the Swift value, and back.
    private static func rating(_ name: String?) -> ExplicitRating? { name.flatMap(ExplicitRating.init(rawValue:)) }
    private static func kind(_ name: String?) -> ReleaseKind? { name.flatMap(ReleaseKind.init(rawValue:)) }
    private static func filterName(_ f: ReleaseMarks.Filter) -> String {
        switch f {
        case .all: "All"
        case .albums: "Albums"
        case .singlesAndEps: "SinglesAndEps"
        case .compilations: "Compilations"
        }
    }

    func test_should_matchGoldenConstants_when_read() {
        let c = Self.golden["constants"] as! [String: Any]
        XCTAssertEqual(c["ratings"] as? [String], [ExplicitRating.explicit.rawValue, ExplicitRating.clean.rawValue])
        XCTAssertEqual(c["releaseTypes"] as? [String], [ReleaseKind.album, .ep, .single, .compilation].map(\.rawValue))
        XCTAssertEqual(c["filters"] as? [String], ReleaseMarks.Filter.allCases.map(Self.filterName))
        XCTAssertEqual((c["letterShare"] as! NSNumber).doubleValue, Double(ReleaseMarks.letterShare), accuracy: 1e-4)
        XCTAssertEqual((c["cornerShare"] as! NSNumber).doubleValue, Double(ReleaseMarks.cornerShare), accuracy: 1e-4)
        XCTAssertEqual((c["capHeightEm"] as! NSNumber).doubleValue, Double(ReleaseMarks.capHeightEm), accuracy: 1e-4)
    }

    func test_should_readRatingsAsTheGoldenSays_when_mappedToSwift() {
        for v in vectors("appleMusicRating") {
            XCTAssertEqual(ReleaseMarks.appleMusic(Self.string(v["value"])), Self.rating(Self.string(v["rating"])), "\(v)")
        }
        for v in vectors("openSubsonicRating") {
            let r = YoinReleaseMarks.shared.contentRatingFromOpenSubsonic(value: Self.string(v["value"]))
            XCTAssertEqual(ReleaseMarks.rating(r), Self.rating(Self.string(v["rating"])), "\(v)")
        }
        for v in vectors("explicitFlag") {
            XCTAssertEqual(ReleaseMarks.explicitFlag(Self.bool(v["explicit"])), Self.rating(Self.string(v["rating"])), "\(v)")
        }
        for v in vectors("fromTracks") {
            let ratings = (v["ratings"] as! [Any]).map { Self.rating(Self.string($0)) }
            XCTAssertEqual(ReleaseMarks.fromTracks(ratings, complete: Self.bool(v["complete"])!),
                           Self.rating(Self.string(v["rating"])), "\(v)")
        }
        for v in vectors("tracklistComplete") {
            XCTAssertEqual(ReleaseMarks.tracklistComplete(loaded: Self.int(v["loadedTracks"])!,
                                                          totalTracks: Self.int(v["totalTracks"]),
                                                          pageTotal: Self.int(v["pageTotal"])),
                           Self.bool(v["complete"]), "\(v)")
        }
        for v in vectors("storage") {
            XCTAssertEqual(Self.string(v["name"]).flatMap(ExplicitRating.init(rawValue:)),
                           Self.rating(Self.string(v["rating"])), "\(v)")
        }
    }

    func test_should_readKindsAsTheGoldenSays_when_mappedFromEachService() throws {
        for v in vectors("spotifyReleaseType") {
            let a = try SpotifyWebAPI.decoder.decode(SpotifyDTO.Album.self, from: JSONSerialization.data(withJSONObject: [
                "id": "al1", "name": "x", "album_type": v["albumType"] ?? NSNull(), "total_tracks": v["totalTracks"] ?? NSNull(),
            ]))
            let album = try XCTUnwrap(SpotifyMapping.album(a))
            XCTAssertEqual(album.releaseType, Self.kind(Self.string(v["type"])), "\(v)")
        }
        for v in vectors("subsonicReleaseType") {
            let shared = YoinReleaseMarks.shared.subsonicReleaseType(releaseTypes: v["releaseTypes"] as! [String])
            let expected = Self.kind(Self.string(v["type"]))
            XCTAssertEqual(ReleaseMarks.kind(shared), expected, "\(v)")
            // The shared Subsonic model carries it lower-case; the Swift mapping reads it back.
            XCTAssertEqual(ReleaseMarks.subsonicKind(shared.map { $0.name.lowercased() }), expected, "\(v)")
        }
        for v in vectors("appleMusicReleaseType") {
            var attributes: [String: Any] = [:]
            if let name = Self.string(v["name"]) { attributes["name"] = name }
            if let c = Self.bool(v["isCompilation"]) { attributes["isCompilation"] = c }
            if let s = Self.bool(v["isSingle"]) { attributes["isSingle"] = s }
            let expected = Self.kind(Self.string(v["type"]))
            // On a catalog album, and on a library album that has only its catalog album's.
            let own: AppleMusicJSON.Object = ["id": "1", "type": "albums", "attributes": attributes]
            let library: AppleMusicJSON.Object = ["id": "l.1", "type": "library-albums", "attributes": [String: Any](),
                                                  "relationships": ["catalog": ["data": [own]]]]
            for r in [own, library] {
                let album = AppleMusicMapping.album(try XCTUnwrap(AppleMusicMapping.album(r)))
                XCTAssertEqual(album.releaseType, expected, "\(v)")
            }
        }
        for v in vectors("releaseTypeStorage") {
            XCTAssertEqual(Self.string(v["name"]).flatMap(ReleaseKind.init(rawValue:)), Self.kind(Self.string(v["type"])), "\(v)")
        }
    }

    func test_should_nameAndFilterKindsAsTheGoldenSays() {
        for v in vectors("kindSuffix") {
            XCTAssertEqual(ReleaseMarks.nameWithoutKindSuffix(v["name"] as! String, Self.kind(Self.string(v["type"]))),
                           v["shown"] as? String, "\(v)")
        }
        for v in vectors("kindTag") {
            let k = Self.kind(Self.string(v["type"]))
            XCTAssertEqual(ReleaseMarks.showsKindTag(k), Self.bool(v["showsKindTag"]), "\(v)")
            XCTAssertEqual(ReleaseMarks.headerKind(k).rawValue, v["headerKind"] as? String, "\(v)")
        }
        for v in vectors("filterAccepts") {
            let filter = ReleaseMarks.Filter.allCases.first { Self.filterName($0) == v["filter"] as? String }!
            XCTAssertEqual(filter.accepts(Self.kind(Self.string(v["type"]))), Self.bool(v["accepts"]), "\(v)")
        }
        for v in vectors("offeredFilters") {
            let kinds = (v["types"] as! [String]).map(Self.kind)
            XCTAssertEqual(ReleaseMarks.offeredFilters(kinds).map(Self.filterName), v["filters"] as? [String], "\(v)")
            // An unknown kind changes nothing: it is only ever under All.
            XCTAssertEqual(ReleaseMarks.offeredFilters(kinds + [nil]).map(Self.filterName), v["filters"] as? [String], "\(v)")
        }
    }

    func test_should_sizeAndFitTheBadgeAsTheGoldenSays_when_inPoints() {
        for v in vectors("badgeFits") {
            let fits = ReleaseMarks.badgeFits(maxWidth: CGFloat(Self.int(v["maxWidth"])!),
                                              titleRoom: CGFloat(Self.int(v["titleRoom"])!),
                                              naturalTitle: CGFloat(Self.int(v["naturalTitle"])!))
            XCTAssertEqual(fits, Self.bool(v["fits"]), "\(v)")
        }
        var checked = 0
        // iOS sizes in points: the vectors whose limits are the dp ones (9 / 18 / 6).
        for v in vectors("badgeSize") where (v["minSide"] as! NSNumber).doubleValue == 9 {
            let size = CGFloat((v["fontSize"] as! NSNumber).doubleValue)
            let hero = Self.bool(v["hero"])!
            XCTAssertEqual(Double(ReleaseMarks.badgeSide(fontSize: size, hero: hero)),
                           (v["side"] as! NSNumber).doubleValue, accuracy: 1e-3, "\(v)")
            XCTAssertEqual(Int(ReleaseMarks.badgeGap(fontSize: size, hero: hero)), Self.int(v["gap"]), "\(v)")
            checked += 1
        }
        XCTAssertGreaterThan(checked, 10)
    }

    // MARK: Services

    private func json(_ s: String) -> AppleMusicJSON.Object { AppleMusicJSON.object(Data(s.utf8))! }

    func test_should_readAppleMusicRatings_fromTheResourceElseItsCatalog() throws {
        let catalog = try XCTUnwrap(AppleMusicMapping.song(json("""
        {"id":"1440857786","type":"songs","attributes":{"name":"HUMBLE.","contentRating":"explicit"}}
        """)))
        XCTAssertEqual(AppleMusicMapping.track(catalog).contentRating, .explicit)
        XCTAssertTrue(AppleMusicMapping.track(catalog).isExplicit)
        let library = try XCTUnwrap(AppleMusicMapping.song(json("""
        {"id":"i.A","type":"library-songs","attributes":{"name":"HUMBLE.","playParams":{"catalogId":"1440857786"}},
         "relationships":{"catalog":{"data":[{"id":"1440857786","type":"songs","attributes":{"contentRating":"clean"}}]}}}
        """)))
        XCTAssertEqual(library.contentRating, .clean, "a library song without its own takes its catalog song's")
        let unrated = try XCTUnwrap(AppleMusicMapping.song(json(#"{"id":"1","type":"songs","attributes":{"name":"x"}}"#)))
        XCTAssertNil(unrated.contentRating)

        let album = AppleMusicMapping.album(try XCTUnwrap(AppleMusicMapping.album(json("""
        {"id":"l.1","type":"library-albums","attributes":{"name":"Section.80 - EP"},
         "relationships":{"catalog":{"data":[{"id":"9","type":"albums","attributes":{"contentRating":"explicit","isSingle":false}}]}}}
        """))))
        XCTAssertEqual(album.contentRating, .explicit)
        XCTAssertEqual(album.releaseType, .ep)
        XCTAssertEqual(album.title, "Section.80", "the EP's suffix is hidden: the kind says it")
        XCTAssertTrue(album.kindKnown)
        let bare = AppleMusicMapping.album(try XCTUnwrap(AppleMusicMapping.album(json("""
        {"id":"l.2","type":"library-albums","attributes":{"name":"Voice Memo"}}
        """))))
        XCTAssertNil(bare.releaseType, "no flag, no suffix: unknown, so only under All")
        XCTAssertEqual(bare.kind, .album)
    }

    func test_should_rateASpotifyAlbumFromTheTracksInHand_only() throws {
        func track(_ id: String, _ explicit: Bool?) -> String {
            let flag = explicit.map { #","explicit":\#($0)"# } ?? ""
            return #"{"id":"\#(id)","name":"\#(id)","type":"track","artists":[{"id":"ar","name":"A"}]\#(flag)}"#
        }
        let t = try SpotifyWebAPI.decoder.decode(SpotifyDTO.Track.self, from: Data(track("t1", true).utf8))
        XCTAssertEqual(SpotifyMapping.track(t)?.contentRating, .explicit)
        // A saved album: its embedded first page is the whole tracklist (2 of 2), one explicit.
        let saved = try SpotifyWebAPI.decoder.decode(SpotifyDTO.Album.self, from: Data("""
        {"id":"al1","name":"DAMN.","album_type":"album","total_tracks":2,
         "tracks":{"items":[\(track("t1", false)),\(track("t2", true))],"total":2}}
        """.utf8))
        XCTAssertEqual(SpotifyMapping.album(saved)?.contentRating, .explicit)
        XCTAssertEqual(SpotifyMapping.album(saved)?.tracks.count, 0, "a list row still carries no tracks")
        let clean = try SpotifyWebAPI.decoder.decode(SpotifyDTO.Album.self, from: Data("""
        {"id":"al2","name":"Clean","album_type":"album","total_tracks":3,
         "tracks":{"items":[\(track("t1", false)),\(track("t2", false))],"total":3}}
        """.utf8))
        XCTAssertNil(SpotifyMapping.album(clean)?.contentRating, "two of three clean: unknown, not clean")
        XCTAssertEqual(SpotifyMapping.album(clean, tracks: clean.tracks!.items + [
            try SpotifyWebAPI.decoder.decode(SpotifyDTO.Track.self, from: Data(track("t3", false).utf8)),
        ])?.contentRating, .clean, "the album page's whole tracklist, all clean")
        // A search result / artist release: no tracks, no extra request: unknown.
        let simplified = try SpotifyWebAPI.decoder.decode(SpotifyDTO.Album.self, from: Data("""
        {"id":"al3","name":"Single","album_type":"single","total_tracks":5}
        """.utf8))
        XCTAssertNil(SpotifyMapping.album(simplified)?.contentRating)
        XCTAssertEqual(SpotifyMapping.album(simplified)?.releaseType, .ep, "a 5-track single is an EP")
    }

    func test_should_readOpenSubsonicExplicitStatus_throughTheSharedModels() {
        let provider = MediaRef.companion.PROVIDER_SUBSONIC
        let mapping = SubsonicMapping { _ in nil }
        let song = SongInfo(ref: MediaRef(provider: provider, rawId: "s1"), id: "s1", title: "DNA.", artist: "K",
                            artistId: nil, album: "DAMN.", albumId: "al1", track: nil, disc: nil, durationSec: nil,
                            coverArtId: nil, year: nil, genre: nil, starred: false, starredAt: nil, userRating: nil,
                            suffix: nil, contentType: nil, bitRate: nil, size: nil, path: nil, playCount: nil,
                            played: nil, artists: [], audio: nil, bpm: nil, comment: nil, created: nil, composer: nil,
                            contentRating: YoinShared.ContentRating.explicit_)
        XCTAssertEqual(mapping.track(song).contentRating, .explicit)
        let album = AlbumInfo(ref: MediaRef(provider: provider, rawId: "al1"), id: "al1", name: "DAMN.", artist: "K",
                              artistId: nil, year: nil, songCount: nil, durationSec: nil, coverArtId: "al1",
                              starred: false, starredAt: nil, userRating: nil, playCount: nil, created: nil, genre: nil,
                              releaseType: "ep", songs: [song], played: nil, version: nil, recordLabels: [],
                              releaseDate: nil, originalReleaseDate: nil, contentRating: YoinShared.ContentRating.clean)
        let mapped = mapping.album(album)
        XCTAssertEqual(mapped.contentRating, .clean)
        XCTAssertEqual(mapped.releaseType, .ep)
        XCTAssertEqual(mapped.tracks.first?.contentRating, .explicit)
        let plain = AlbumInfo(ref: MediaRef(provider: provider, rawId: "al2"), id: "al2", name: "Old", artist: "K",
                              artistId: nil, year: nil, songCount: nil, durationSec: nil, coverArtId: "al2",
                              starred: false, starredAt: nil, userRating: nil, playCount: nil, created: nil, genre: nil,
                              releaseType: nil, songs: [])
        XCTAssertNil(mapping.album(plain).contentRating, "plain Subsonic never says")
        XCTAssertNil(mapping.album(plain).releaseType, "no releaseTypes: unknown, only under All")
    }

    // MARK: Detail cache

    func test_should_keepTheMarks_andReadOlderPagesAgain_when_cached() throws {
        let codec = DetailCacheCodec.appleMusic
        var album = MockLibrary.allAlbums[0]
        album.contentRating = .explicit
        album.kind = .ep
        album.tracks[0].contentRating = .explicit
        album.tracks[1].contentRating = .clean
        let dto = codec.dto(album)
        XCTAssertFalse(DetailCacheDTO.marksOutdated(dto))
        let back = codec.album(try JSONDecoder().decode(DetailCacheDTO.Album.self, from: JSONEncoder().encode(dto)))
        XCTAssertEqual(back.contentRating, .explicit)
        XCTAssertEqual(back.releaseType, .ep)
        XCTAssertEqual(back.tracks.prefix(2).map(\.contentRating), [.explicit, .clean])

        var unknown = album
        unknown.kindKnown = false
        XCTAssertNil(codec.album(codec.dto(unknown)).releaseType)

        // A page stored before the marks: still reads (unknown everywhere, the kind as it was), but is outdated
        // so the store reads it again at once instead of serving it as fresh for a week.
        var old = try JSONSerialization.jsonObject(with: JSONEncoder().encode(dto)) as! [String: Any]
        old["contentRating"] = nil
        old["marks"] = nil
        old["kindUnknown"] = nil
        old["tracks"] = (old["tracks"] as! [[String: Any]]).map { var t = $0; t["contentRating"] = nil; return t }
        let decoded = try JSONDecoder().decode(DetailCacheDTO.Album.self, from: JSONSerialization.data(withJSONObject: old))
        XCTAssertTrue(DetailCacheDTO.marksOutdated(decoded))
        let read = codec.album(decoded)
        XCTAssertNil(read.contentRating)
        XCTAssertTrue(read.tracks.allSatisfy { $0.contentRating == nil })
        XCTAssertEqual(read.releaseType, .ep, "the stored kind is kept as known, as before")
        let page = DetailCacheDTO.ArtistPage(provider: "applemusic", id: "a", name: "K", starred: false, cover: nil,
                                             releases: [decoded, dto], mostPlayed: [])
        XCTAssertTrue(DetailCacheDTO.marksOutdated(page))
    }

    // MARK: The E

    /// Width of [view] laid out in a [width] row.
    private func width<V: View>(_ view: V, in width: CGFloat) -> CGFloat {
        let host = UIHostingController(rootView: view.frame(width: width, alignment: .leading).fixedSize(horizontal: false, vertical: true))
        return host.sizeThatFits(in: CGSize(width: width, height: 200)).width
    }

    func test_should_layOutTheTitleAsBefore_when_notExplicit() {
        let title = Text("HUMBLE.").font(.gsf(16))
        let plain = UIHostingController(rootView: title.fixedSize()).sizeThatFits(in: CGSize(width: 400, height: 100))
        let wrapped = UIHostingController(rootView: ExplicitTitle(text: "HUMBLE.", explicit: false, fontSize: 16,
                                                                  color: .gray) { title }.fixedSize())
            .sizeThatFits(in: CGSize(width: 400, height: 100))
        XCTAssertEqual(plain, wrapped)
    }

    func test_should_putTheBadgeAfterTheTitle_andDropIt_when_theRowIsTooNarrow() {
        let side = ReleaseMarks.badgeSide(fontSize: 16, hero: false)
        let gap = ReleaseMarks.badgeGap(fontSize: 16, hero: false)
        XCTAssertEqual(side, 16 * 0.716, accuracy: 0.01, "the cap height")
        func laid(_ title: String, _ width: CGFloat) -> CGSize {
            UIHostingController(rootView: ExplicitTitle(text: title, explicit: true, fontSize: 16, color: .gray) {
                Text(title).font(.gsf(16)).lineLimit(1)
            }).sizeThatFits(in: CGSize(width: width, height: 100))
        }
        let natural = UIHostingController(rootView: Text("HUMBLE.").font(.gsf(16)).fixedSize())
            .sizeThatFits(in: CGSize(width: 400, height: 100)).width
        XCTAssertEqual(laid("HUMBLE.", 400).width, natural + gap + side, accuracy: 1, "title, gap, square")
        // A long title in a narrow row: cut, and the E still at the end, inside the row.
        let long = String(repeating: "Mortal Man ", count: 6)
        XCTAssertLessThanOrEqual(laid(long, 200).width, 200.5)
        // Too narrow for the E to leave the title half the row: the E goes, the title takes the row.
        XCTAssertTrue(ReleaseMarks.badgeFits(maxWidth: 200, titleRoom: 200 - side - gap, naturalTitle: 400))
        XCTAssertFalse(ReleaseMarks.badgeFits(maxWidth: 20, titleRoom: 20 - side - gap, naturalTitle: 400))
    }

    func test_should_sizeNowPlayingsBadgeToTheXHeight_cappedAt18() {
        XCTAssertEqual(ReleaseMarks.badgeSide(fontSize: 36, hero: true), 18, accuracy: 0.01)
        XCTAssertEqual(ReleaseMarks.badgeSide(fontSize: 28, hero: true), 28 * 0.5115, accuracy: 0.01)
        XCTAssertEqual(ReleaseMarks.badgeSide(fontSize: 15, hero: false), 15 * 0.716, accuracy: 0.01)
    }

    func test_should_putTheKindAndVersionOnTheArtistLine_forNowPlaying() {
        var album = MockLibrary.allAlbums[0]
        album.kind = .single
        album.version = "Dolby Atmos"
        XCTAssertEqual(ReleaseTags.tags(album), [ReleaseKind.single.label, "Dolby Atmos"])
        album.kind = .album
        album.version = nil
        XCTAssertEqual(ReleaseTags.tags(album), [])
        album.kind = .ep
        album.kindKnown = false
        XCTAssertEqual(ReleaseTags.tags(album), [], "an unknown kind wears no tag")
    }

    func test_should_sayExplicit_in_bothLanguages() {
        for (bundle, word) in [("en", "Explicit"), ("zh-Hans", "含不雅内容")] {
            let b = Bundle(path: Bundle.main.path(forResource: bundle, ofType: "lproj")!)!
            XCTAssertEqual(b.localizedString(forKey: "Explicit", value: nil, table: nil), word, bundle)
        }
    }

    func test_should_nameTheHeadersRealKind() {
        XCTAssertEqual(ReleaseMarks.headerKind(.ep), .ep)
        XCTAssertEqual(ReleaseMarks.headerKind(.compilation), .compilation)
        XCTAssertEqual(ReleaseMarks.headerKind(nil), .album)
    }
}
