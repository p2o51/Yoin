import XCTest
import YoinShared
@testable import Yoin

/// Open in Yoin and the clipboard music-link card (OpenIn/, docs/open-in-yoin.md): the shared parser as Swift
/// reads it, hit vs found routing, the card's requests, the clipboard gate and its setting, the ranking, the
/// no-login metadata, the share hand-off.
@MainActor
final class OpenInTests: XCTestCase {
    private func profile(_ id: String, _ service: Profile.Service, created: Int64, name: String? = nil) -> Profile {
        var p = Profile(id: id, service: service, createdAt: created)
        if service == .subsonic { p.baseUrl = "http://127.0.0.1:4995"; p.username = name ?? "demo" }
        if service == .spotify { p.spotifyName = name }
        return p
    }

    private let spotifyTrack = "https://open.spotify.com/track/003vvx7Niy0yvhvHt4a68B?si=abc"
    private let appleAlbumSong = "https://music.apple.com/jp/album/%E5%A4%9C%E3%81%AB%E9%A7%86%E3%81%91%E3%82%8B/1472008429?i=1472008431"

    // MARK: Parser (shared Kotlin through YoinShared)

    func testParserReadsSpotifyAndAppleMusicLinksThroughShared() throws {
        let s = try XCTUnwrap(OpenInLink.findFirst("听这首：\(spotifyTrack)，好听"))
        XCTAssertEqual(s.service, .spotify)
        XCTAssertEqual(s.kind, .track)
        XCTAssertEqual(s.id, "003vvx7Niy0yvhvHt4a68B")
        XCTAssertEqual(s.key, "Spotify:Track:003vvx7Niy0yvhvHt4a68B")
        XCTAssertEqual(s.canonicalUrl, "https://open.spotify.com/track/003vvx7Niy0yvhvHt4a68B")

        let a = try XCTUnwrap(OpenInLink.findFirst(appleAlbumSong))
        XCTAssertEqual(a.service, .appleMusic)
        XCTAssertEqual(a.kind, .track, "an album URL with ?i= is that song")
        XCTAssertEqual(a.id, "1472008431")
        XCTAssertEqual(a.storefront, "jp")
        XCTAssertEqual(a.canonicalUrl, "https://music.apple.com/jp/song/1472008431")

        let uri = try XCTUnwrap(OpenInLink.parse("spotify:album:4yP0hdKOZPNshxUOjY0cZj"))
        XCTAssertEqual(uri.kind, .album)
        let short = try XCTUnwrap(OpenInLink.findFirst("https://spotify.link/AbCdEf1234"))
        XCTAssertTrue(short.needsRedirectResolution)
        XCTAssertEqual(short.kind, .shortLink)
        XCTAssertNil(OpenInLink.findFirst("https://open.spotify.com/episode/4rOoJ6Egrf8K2IrywzwOMk"))
        XCTAssertNil(OpenInLink.findFirst("just some words"))
        XCTAssertEqual(OpenInLink.defaultStorefront, "us")
    }

    // MARK: Routing (owner 2026-11-24)

    func testLinkWithAnAccountOnItsServiceIsAHitThere() throws {
        let link = try XCTUnwrap(OpenInLink.findFirst(spotifyTrack))
        let profiles = [profile("sub", .subsonic, created: 1), profile("sp", .spotify, created: 2, name: "51")]
        XCTAssertEqual(OpenInRouting.route(link, profiles: profiles, activeId: "sub"), .hit(profileId: "sp", profileName: "51"))
        XCTAssertEqual(OpenInRouting.hitAction(link), .playTrack("003vvx7Niy0yvhvHt4a68B"))
    }

    func testLinkWithoutAnAccountOnItsServiceIsOnlyFound() throws {
        let link = try XCTUnwrap(OpenInLink.findFirst(spotifyTrack))
        let profiles = [profile("sub", .subsonic, created: 1), profile("am", .appleMusic, created: 2)]
        XCTAssertEqual(OpenInRouting.route(link, profiles: profiles, activeId: "am"), .found)
        let apple = try XCTUnwrap(OpenInLink.findFirst(appleAlbumSong))
        XCTAssertEqual(OpenInRouting.route(apple, profiles: profiles, activeId: "sub"), .hit(profileId: "am", profileName: "Apple Music"))
    }

    func testSeveralAccountsOnTheServicePreferTheOneInUseElseTheOldest() throws {
        let link = try XCTUnwrap(OpenInLink.findFirst(spotifyTrack))
        let profiles = [profile("new", .spotify, created: 20), profile("old", .spotify, created: 10), profile("sub", .subsonic, created: 1)]
        XCTAssertEqual(OpenInRouting.route(link, profiles: profiles, activeId: "new"), .hit(profileId: "new", profileName: "Spotify"))
        XCTAssertEqual(OpenInRouting.route(link, profiles: profiles, activeId: "sub"), .hit(profileId: "old", profileName: "Spotify"))
    }

    func testHitActionsByKind() throws {
        XCTAssertEqual(OpenInRouting.hitAction(try XCTUnwrap(OpenInLink.parse("https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj"))),
                       .openAlbum("4yP0hdKOZPNshxUOjY0cZj"))
        XCTAssertEqual(OpenInRouting.hitAction(try XCTUnwrap(OpenInLink.parse("https://music.apple.com/us/artist/the-killers/6483093"))),
                       .openArtist("6483093"))
        XCTAssertEqual(OpenInRouting.hitAction(try XCTUnwrap(OpenInLink.parse("https://music.apple.com/us/playlist/x/pl.f4d106fed2bd41149aaacabb233eb5eb"))),
                       .openPlaylist("pl.f4d106fed2bd41149aaacabb233eb5eb"))
        XCTAssertNil(OpenInRouting.hitAction(try XCTUnwrap(OpenInLink.parse("https://spotify.link/AbCdEf1234"))))
    }

    // MARK: Coordinator: the card and the share

    private final class FakeLookup: MusicLinkLookup {
        var metadata: MusicLinkMetadata?
        var redirect: OpenInLink?
        func resolve(_ link: OpenInLink) async -> OpenInLink? { link.needsRedirectResolution ? redirect : link }
        func metadata(_ link: OpenInLink) async -> MusicLinkMetadata? { metadata }
    }

    private func coordinator(_ profiles: [Profile], active: String?, lookup: FakeLookup = FakeLookup())
        -> (OpenInCoordinator, Box) {
        let c = OpenInCoordinator()
        c.lookup = lookup
        c.profiles = { profiles }
        c.activeId = { active }
        let box = Box()
        c.perform = { box.requests.append($0) }
        return (c, box)
    }

    private final class Box { var requests: [OpenInRequest] = [] }

    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
    }

    func testPastedHitLinkShowsRichCardAndListenOpensItInItsAccount() async throws {
        let lookup = FakeLookup()
        lookup.metadata = MusicLinkMetadata(kind: .track, title: "Mr. Brightside", artist: "The Killers")
        let (c, box) = coordinator([profile("sp", .spotify, created: 1)], active: nil, lookup: lookup)
        c.offerPaste()
        XCTAssertEqual(c.prompt?.paste, true, "nothing read before the tap: the Paste card")
        c.pasted(spotifyTrack)
        await settle()
        let card = try XCTUnwrap(c.prompt)
        XCTAssertFalse(card.paste)
        XCTAssertTrue(card.target.isHit)
        XCTAssertTrue(card.listens)
        XCTAssertEqual(card.metadata?.title, "Mr. Brightside")
        c.accept()
        await settle()
        XCTAssertNil(c.prompt)
        let link = try XCTUnwrap(OpenInLink.findFirst(spotifyTrack))
        XCTAssertEqual(box.requests, [.openHit(link, profileId: "sp", metadata: lookup.metadata)])
    }

    func testFoundLinkSearchesAndNeverPlaysByItself() async throws {
        let lookup = FakeLookup()
        lookup.metadata = MusicLinkMetadata(kind: .track, title: "Mr. Brightside", artist: "The Killers")
        let (c, box) = coordinator([profile("sub", .subsonic, created: 1)], active: "sub", lookup: lookup)
        c.offerPaste()
        c.pasted("look \(spotifyTrack)")
        await settle()
        XCTAssertEqual(c.prompt?.target, .found)
        c.accept()
        await settle()
        let link = try XCTUnwrap(OpenInLink.findFirst(spotifyTrack))
        XCTAssertEqual(box.requests, [.showResults(link, metadata: lookup.metadata)])
    }

    func testPastedTextWithoutAMusicLinkSaysSoThenTheCardGoes() async throws {
        let (c, box) = coordinator([profile("sub", .subsonic, created: 1)], active: "sub")
        c.noLinkHold = .milliseconds(50)
        c.offerPaste()
        c.pasted("https://example.com/not-music")
        XCTAssertEqual(c.prompt?.noLink, true, "No music link found, instead of closing silently")
        XCTAssertEqual(c.prompt?.paste, false)
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertNil(c.prompt)
        XCTAssertTrue(box.requests.isEmpty)
        c.offerPaste()
        c.pasted("")
        XCTAssertEqual(c.prompt?.noLink, true, "nothing readable pasted counts as no music link")
    }

    func testANewCardOutlivesTheNoLinkNotice() async throws {
        let (c, _) = coordinator([profile("sp", .spotify, created: 1)], active: "sp")
        c.noLinkHold = .milliseconds(50)
        c.pasted("nothing here")
        c.pasted(spotifyTrack)
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(c.prompt?.link?.id, "003vvx7Niy0yvhvHt4a68B", "the notice's timer doesn't take the next card")
    }

    func testNoCardBeforeTheFirstAccount() throws {
        let (c, _) = coordinator([], active: nil)
        XCTAssertFalse(c.offer(try XCTUnwrap(OpenInLink.findFirst(spotifyTrack))))
        XCTAssertNil(c.prompt)
    }

    func testSharedLinkRoutesWithoutACardAndOtherTextSearches() async throws {
        let (c, box) = coordinator([profile("sp", .spotify, created: 1)], active: "sp")
        c.offerShared("Mr. Brightside by The Killers \(spotifyTrack)")
        await settle()
        let link = try XCTUnwrap(OpenInLink.findFirst(spotifyTrack))
        XCTAssertEqual(box.requests, [.openHit(link, profileId: "sp", metadata: nil)])
        XCTAssertNil(c.prompt)
        box.requests = []
        c.offerShared("  Mr.   Brightside  ")
        await settle()
        XCTAssertEqual(box.requests, [.searchLibrary("Mr. Brightside")])
    }

    func testShortLinkThatLeadsNowhereSearchesTheRestOfTheText() async {
        let (c, box) = coordinator([profile("sp", .spotify, created: 1)], active: "sp")
        c.offerShared("Brightside https://spotify.link/AbCdEf1234")
        await settle()
        XCTAssertEqual(box.requests, [.searchLibrary("Brightside")])
        box.requests = []
        c.offerShared("https://spotify.link/AbCdEf1234")
        await settle()
        XCTAssertTrue(box.requests.isEmpty, "only the dead link was shared: nothing")
    }

    func testRequestsWaitForTheShell() async throws {
        let c = OpenInCoordinator()
        c.lookup = FakeLookup()
        c.profiles = { [] }
        c.offerShared("Brightside")
        await settle()
        var got: [OpenInRequest] = []
        c.perform = { got.append($0) }
        XCTAssertEqual(got, [.searchLibrary("Brightside")])
    }

    // MARK: Clipboard gate and the setting

    func testClipboardDetectionIsOnByDefaultAndPersists() {
        let suite = "OpenInTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = OpenInSettings(defaults: defaults)
        XCTAssertTrue(settings.clipboardDetectionEnabled)
        XCTAssertEqual(settings.summary, loc("Clipboard detection on"))
        settings.setClipboardDetectionEnabled(false)
        XCTAssertFalse(OpenInSettings(defaults: defaults).clipboardDetectionEnabled)
        XCTAssertEqual(settings.summary, loc("Clipboard detection off"))
    }

    func testGateLooksOnlyWhenOnWithAnAccountAndANewClip() {
        XCTAssertTrue(ClipboardLinkGate.shouldDetect(enabled: true, hasAccounts: true, landingOpen: false, changeCount: 5, lastSeen: 4))
        XCTAssertTrue(ClipboardLinkGate.shouldDetect(enabled: true, hasAccounts: true, landingOpen: false, changeCount: 5, lastSeen: nil))
        XCTAssertFalse(ClipboardLinkGate.shouldDetect(enabled: false, hasAccounts: true, landingOpen: false, changeCount: 5, lastSeen: 4))
        XCTAssertFalse(ClipboardLinkGate.shouldDetect(enabled: true, hasAccounts: false, landingOpen: false, changeCount: 5, lastSeen: 4))
        XCTAssertFalse(ClipboardLinkGate.shouldDetect(enabled: true, hasAccounts: true, landingOpen: true, changeCount: 5, lastSeen: 4))
        XCTAssertFalse(ClipboardLinkGate.shouldDetect(enabled: true, hasAccounts: true, landingOpen: false, changeCount: 5, lastSeen: 5))
    }

    private struct FakeProbe: PasteboardProbe {
        var changeCount: Int
        var mayHoldLink = true
        var webURL = true
        func probablyWebURL() async -> Bool { webURL }
    }

    func testWatcherOffersPasteOncePerClipAndNeverWithTheSettingOff() async {
        let suite = "OpenInTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = OpenInSettings(defaults: defaults)
        var watcher = ClipboardLinkWatcher(settings: settings, probe: FakeProbe(changeCount: 7))
        let first = await watcher.check(hasAccounts: true, landingOpen: false)
        XCTAssertTrue(first)
        let again = await watcher.check(hasAccounts: true, landingOpen: false)
        XCTAssertFalse(again, "the same clip on the next return")
        watcher = ClipboardLinkWatcher(settings: settings, probe: FakeProbe(changeCount: 8, webURL: false))
        let notALink = await watcher.check(hasAccounts: true, landingOpen: false)
        XCTAssertFalse(notALink)
        settings.setClipboardDetectionEnabled(false)
        watcher = ClipboardLinkWatcher(settings: settings, probe: FakeProbe(changeCount: 9))
        let off = await watcher.check(hasAccounts: true, landingOpen: false)
        XCTAssertFalse(off)
        XCTAssertEqual(settings.lastSeenChangeCount, 8, "with the setting off the clip isn't even looked at")
    }

    // MARK: Ranking (shared TrackMatcher)

    private func entry(_ id: String, _ title: String, _ artist: String, _ seconds: Int) -> PlaylistEntry {
        var album = MockLibrary.allAlbums[0]
        album.title = "Hot Fuss"
        album.artist = artist
        var track = album.tracks[0]
        track.id = MediaId(provider: "subsonic", rawId: id)
        track.title = title
        track.artist = artist
        track.durationSec = seconds
        return PlaylistEntry(album: album, track: track)
    }

    func testRankingPutsTheSameRecordingFirstAndCallsItExact() {
        let m = MusicLinkMetadata(kind: .track, title: "Mr. Brightside", artist: "The Killers", album: "Hot Fuss", durationSec: 222)
        let searched = [entry("live", "Mr. Brightside (Live)", "The Killers", 238),
                        entry("cover", "Mr Brightside", "Tribute Band", 219),
                        entry("same", "Mr. Brightside", "The Killers", 223)]
        let r = OpenInMatcher.rankTracks(OpenInMatcher.sourceTrack(m), byIsrc: [], searched: searched)
        XCTAssertEqual(r.items.map(\.track.id.rawId).first, "same")
        XCTAssertEqual(Set(r.items.map(\.track.id.rawId)), ["same", "live", "cover"])
        XCTAssertTrue(r.exact)
        let onlyLive = OpenInMatcher.rankTracks(OpenInMatcher.sourceTrack(m), byIsrc: [], searched: [searched[0]])
        XCTAssertFalse(onlyLive.exact, "a live take is another recording: the page says it couldn't confirm")
        XCTAssertEqual(OpenInMatcher.searchQuery(m), "Mr. Brightside The Killers")
    }

    func testIsrcMatchesLead() {
        let m = MusicLinkMetadata(kind: .track, title: "Mr. Brightside", artist: "The Killers", isrc: "USIR20400274", durationSec: 222)
        let r = OpenInMatcher.rankTracks(OpenInMatcher.sourceTrack(m), byIsrc: [entry("isrc", "Mr. Brightside", "The Killers", 222)],
                                         searched: [entry("other", "Mr. Brightside", "The Killers", 223)])
        XCTAssertEqual(r.items.map(\.track.id.rawId), ["isrc", "other"])
        XCTAssertTrue(r.exact)
    }

    func testAlbumsRankByNameThenArtist() {
        var a = MockLibrary.allAlbums[0]; a.title = "Hot Fuss"; a.artist = "The Killers"
        var b = MockLibrary.allAlbums[1]; b.title = "Hot Fuss (Deluxe)"; b.artist = "Someone Else"
        var c = MockLibrary.allAlbums[2]; c.title = "Sam's Town"; c.artist = "The Killers"
        let r = OpenInMatcher.rankAlbums(name: "Hot Fuss", artist: "The Killers", [c, b, a])
        XCTAssertEqual(r.items.first?.title, "Hot Fuss")
        XCTAssertTrue(r.exact)
    }

    // MARK: Metadata without login

    func testSpotifyPageMetaReadsTitleArtistAndCover() {
        let html = """
        <html><head><title>Mr. Brightside - Song and Lyrics by The Killers | Spotify</title>
        <meta property="og:title" content="Mr. Brightside"/>
        <meta property="og:description" content="The Killers · Hot Fuss · Song · 2004"/>
        <meta property="og:type" content="music.song"/>
        <meta name="music:musician_description" content="The Killers"/>
        <meta name="music:duration" content="222"/>
        <meta property="og:image" content="https://i.scdn.co/image/ab67616d0000b273ccdddd46119a4ff53eaf1f5d"/>
        </head></html>
        """
        let m = MusicLinkMetadata.fromSpotifyPage(.track, html: html)
        XCTAssertEqual(m.title, "Mr. Brightside")
        XCTAssertEqual(m.artist, "The Killers")
        XCTAssertEqual(m.album, "Hot Fuss")
        XCTAssertEqual(m.durationSec, 222)
        XCTAssertEqual(m.artworkUrl?.host(), "i.scdn.co")
    }

    func testAppleMusicCatalogResourceReadsSongAndTriesTheLinksStorefrontFirst() async throws {
        let asked = AskedStorefronts()
        let resolver = MusicLinkResolver(fetch: { _, _ in throw URLError(.notConnectedToInternet) },
                                         catalog: { storefront, type, _ in
                                             await asked.add("\(storefront)/\(type)")
                                             return storefront == "jp" ? [] : [OpenInTests.yoasobi()]
                                         })
        let link = try XCTUnwrap(OpenInLink.findFirst(appleAlbumSong))
        let read = await resolver.metadata(link)
        let m = try XCTUnwrap(read)
        XCTAssertEqual(m.title, "夜に駆ける")
        XCTAssertEqual(m.artist, "YOASOBI")
        XCTAssertEqual(m.isrc, "JPP301900501")
        XCTAssertEqual(m.durationSec, 261)
        XCTAssertEqual(m.artworkUrl?.absoluteString, "https://is1-ssl.mzstatic.com/image/600x600bb.jpg")
        let order = await asked.all
        XCTAssertEqual(order, ["jp/songs", "us/songs"], "the link's storefront, then us")
    }

    nonisolated private static func yoasobi() -> AppleMusicJSON.Object {
        [
            "id": "1472008431", "type": "songs",
            "attributes": ["name": "夜に駆ける", "artistName": "YOASOBI", "albumName": "夜に駆ける",
                           "isrc": "JPP301900501", "durationInMillis": 261_000,
                           "artwork": ["url": "https://is1-ssl.mzstatic.com/image/{w}x{h}bb.jpg"]],
        ]
    }

    private actor AskedStorefronts {
        var all: [String] = []
        func add(_ s: String) { all.append(s) }
    }

    func testShortLinkIsFollowedByHandUntilItNamesAnItem() async throws {
        let resolver = MusicLinkResolver(fetch: { url, follow in
            XCTAssertFalse(follow)
            if url.host() == "spotify.link" {
                return .init(status: 302, location: "https://spotify.app.link/x?$full_url=1", body: Data())
            }
            return .init(status: 307, location: "https://open.spotify.com/track/003vvx7Niy0yvhvHt4a68B?si=1", body: Data())
        }, catalog: { _, _, _ in [] })
        let short = try XCTUnwrap(OpenInLink.findFirst("https://spotify.link/AbCdEf1234"))
        let followed = await resolver.resolve(short)
        XCTAssertEqual(followed?.id, "003vvx7Niy0yvhvHt4a68B")
        XCTAssertEqual(followed?.kind, .track)
    }

    // MARK: Share hand-off and strings

    func testInboxHandsTheShareOverOnceAndDropsAStaleOne() {
        let suite = "OpenInTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let now = Date()
        OpenInInbox.put(spotifyTrack, now: now, into: defaults)
        XCTAssertEqual(OpenInInbox.take(now: now.addingTimeInterval(5), from: defaults), spotifyTrack)
        XCTAssertNil(OpenInInbox.take(now: now, from: defaults), "taken once")
        OpenInInbox.put("old", now: now, into: defaults)
        XCTAssertNil(OpenInInbox.take(now: now.addingTimeInterval(OpenInInbox.maxAge + 1), from: defaults))
        OpenInInbox.put("fresh", now: now, into: defaults)
        XCTAssertEqual(OpenInInbox.take(now: now.addingTimeInterval(OpenInInbox.maxAge), from: defaults), "fresh",
                       "ten minutes is still in time")
    }

    func testCopyIsAndroidsApprovedStrings() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") == true
        XCTAssertEqual(loc("Music links"), zh ? "音乐链接" : "Music links")
        XCTAssertEqual(loc("Choose a match"), zh ? "选择匹配结果" : "Choose a match")
        XCTAssertEqual(loc("Listen"), zh ? "收听" : "Listen")
        XCTAssertEqual(loc("Link in clipboard"), zh ? "剪贴板里有链接" : "Link in clipboard")
        XCTAssertEqual(loc("No music link found"), zh ? "未识别到音乐链接" : "No music link found")
        XCTAssertEqual(String(localized: "\("Spotify") link in clipboard", bundle: .main),
                       zh ? "剪贴板里有 Spotify 链接" : "Spotify link in clipboard")
    }
}
