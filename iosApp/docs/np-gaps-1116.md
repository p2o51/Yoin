# Now Playing gaps found on device, 2026-11-16

Found by the owner on the iPhone Air Debug build 4e6c29c5 (this branch, which contains all of iOS mainline 111b7cb6). None of the three is a regression: all three are missing on mainline too. Each section has the diagnosis, Android's behaviour, the verified fix plan (checked against the code by a second reader) and what to confirm on the device. Implement on the Mac (it compiles), one commit per section, run the tests, then build to the iPhone and check the device items. Item 2 is a UI change: send the owner a screenshot or recording before the PR.

## 1. Now Playing "Playing from" for Spotify's own song (daylist shows "NOW PLAYING")

**Regression?** This is not a regression. The label was never there on mainline, and 3ryaas only fixes it partly. On 2amedi (111b7cb6), NowPlayingView.playingFrom (git show 111b7cb6:iosApp/Yoin/NowPlaying/NowPlayingView.swift:2389) returns "NOW PLAYING" whenever `model.player.externalTrack != nil`, so Spotify's own song never had a "Playing from" line. SpotifyPlayingFrom.swift does not exist on 111b7cb6 (`git cat-file -e` fails). 3ryaas added it in 3438e92b ("the queue sheet ... says where Spotify plays from"), and NowPlayingView.swift:2411-2419 now asks SpotifyPlayingFrom.shared.label(for:). That works for albums, the user's own playlists, artists and Liked Songs. It still gives no label for Spotify-made lists like daylist (37i9dQZ...). SDKAppRemoteLink.state is the same on both branches (111b7cb6 SpotifyRemote.swift:470-476, 4e6c29c5 SpotifyRemote.swift:496-502) and has never read contextURI/contextTitle.

**Root causes**

- Reason 1, the main one: the only way iOS names a playlist is the Web API, and in Development Mode it returns 404 for Spotify-made lists. SpotifyPlayingFrom.resolve, case .playlist (iosApp/Yoin/NowPlaying/SpotifyPlayingFrom.swift:99-107), tries names[uri] (an in-memory cache that only ever holds Web API results, line 52), then store.playlistSummary(id)?.title (only set if the user has daylist in their Library, LibraryStore.swift:1482), then source.playlistName(id), which is GET playlists/{id}?fields=name (SpotifyPlayingFrom.swift:141-143). For 37i9dQZ... that call returns 404 (SpotifySource.swift:255-257; LibraryStore.swift:1328-1334 handles the same 404 for the page). Then `guard let name else { return nil }` (line 102) drops the whole label, including the PLAYING FROM PLAYLIST kind line, and NowPlayingView.swift:2413 falls back to (nil, "NOW PLAYING").
- Reason 2: App Remote's context is never read, even though the SDK provides it. SpotifyiOS 5.0.1 (pinned at iosApp/project.yml:13-15) declares in SPTAppRemotePlayerState.h `@property (nonatomic, readonly) NSString *contextTitle;` ("e.g. the name of the playlist") and `@property (nonatomic, readonly) NSURL *contextURI;`. Those headers are not vendored in the repo; I fetched the header from spotify/ios-sdk v5.0.1, SpotifyiOS.xcframework/ios-arm64/.../Headers/SPTAppRemotePlayerState.h lines 32-36. RemotePlayerState (SpotifyRemote.swift:7-20) has no context fields, and SDKAppRemoteLink.state (SpotifyRemote.swift:496-502) maps only track, album, image and options. Even while App Remote is connected (after Yoin woke Spotify), the daylist name Spotify sends on every state is thrown away. This is the exact source Android uses.
- Reason 3, a race when App Remote is connected: SpotifyPlayingFrom.observe runs once per song (NowPlayingView.swift:431-433; QueueSheet.swift:162; key guard at SpotifyPlayingFrom.swift:70) and makes a single GET me/player. That read returns nil unless `r.item?.id == trackId` (SpotifyPlayingFrom.swift:134). App Remote's playerState changes externalTrack at once (SpotifyAudioOutput.swift:2277-2305), and me/player can still report the previous song for a moment. The label then stays nil for the whole song. There is no retry: a transient 429 or network error from `try?` at line 75 has the same result.
- Reason 4, for a song started or queued from Yoin: iOS cannot start a daylist or any Made For You list at all. Home's Spotify rows come only from me/player/recently-played and me/albums (SpotifySource.swift:88-114), so there are no playlist or Made For You shelves. A Spotify-made playlist opened from Library loads as tracksUnavailable with no entries (LibraryStore.swift:1328-1334, 1369-1377), and Play/Shuffle are disabled (AppModel.detailPlayUnavailable, AppModel.swift:527-530). A song Yoin starts from Home, Library or Search lists gets AppModel.playingFrom = nil, because PlayingFrom.of only maps album, playlist or artist pages (AppModel.swift:737-746, 765-766), so the line shows "NOW PLAYING" (NowPlayingView.swift:2420-2422). This matches Android's ActivityContext.None. A song Yoin adds with Add to queue while Spotify's daylist plays goes into Spotify's queue (AppModel.swift:820-842) and stays an externalTrack, so it hits reasons 1-3. The "started from Yoin" case therefore only shows a daylist label through the externalTrack path, and that path is what needs fixing.

**Android**

Android does not use the Web API for this label. It subscribes to App Remote's PlayerContext (SpotifyAppRemotePlayer.kt:997-1016) and passes `SpotifyPlaybackContext(uri = ctx.uri, title = ctx.title)` (SpotifyAppRemotePlayer.kt:91-94, 1002) to PlaybackManager.onSpotifyPlayerContext (PlaybackManager.kt:1594-1604). For a song started in Spotify, warm-connect adoption sets the label from `deriveSpotifyActivityContext(lastSpotifyContext) ?: ActivityContext.None` (PlaybackManager.kt:1143-1162). SpotifyContextSync.onReported keeps it in sync, and also when Spotify moves on from a Yoin-started queue (SpotifyContextSync.kt:153-166, 205-216).

deriveSpotifyActivityContext (SpotifyContextSync.kt:272-285) maps `spotify:playlist:<id>` to ActivityContext.Playlist(playlistName = PlayerContext.title), with a blank title becoming "". PlayingFromLabel then shows kind np_from_playlist ("PLAYING FROM PLAYLIST") over that name (NowPlayingScreen.kt:4982-5003). So Android names the daylist with whatever string Spotify's own PlayerContext.title holds. There is no 37i9dQZ special case and no Made For You handling anywhere in app/src/main (grep found none), and Android Home has no Made For You rows either.

When nothing names the context:
- If the title is blank, Android still shows "PLAYING FROM PLAYLIST" with an empty name line (SpotifyContextSync.kt:274, 280-281; NowPlayingScreen.kt:4999-5003).
- If the URI is not an album or playlist (artist radio, collection) or no PlayerContext arrives in the grace period, it becomes ActivityContext.None and shows "NOW PLAYING" (SpotifyContextSync.kt:283, 182-191; NowPlayingScreen.kt:5010-5016).

Android's App Remote connects to a Spotify session started in Spotify, which iOS's does not (facts verified on device), so in practice Android always has the title.

**Review notes on the first plan**

- The diagnosis holds. I checked every citation: SpotifyPlayingFrom.swift:52/74-79/99-107/118/130-143, NowPlayingView.swift:2411-2422 and 431-433, QueueSheet.swift:162/261, SpotifyRemote.swift:7-20/496-502, SpotifyAudioOutput.swift:2277-2305/2410-2418, LibraryStore.swift:1328-1334/1369-1377/1482, AppModel.swift:527-530/737-746/765-766, SpotifySource.swift:88-114/255-257, the 111b7cb6 NowPlayingView line ~2389 guard, and Android SpotifyContextSync.kt:272-285 (prefix "spotify:playlist:" at :306), SpotifyAppRemotePlayer.kt:997-1016, NowPlayingScreen.kt:4982-5016. I also fetched the SDK v5.0.1 header SPTAppRemotePlayerState.h. It is an @protocol <NSObject> inside NS_ASSUME_NONNULL and declares `NSString *contextTitle` and `NSURL *contextURI`. Swift imports these as non-optional `String` and `URL`.
- Compile error: the plan's primary snippet uses `.title.nilIfEmpty`, but `nilIfEmpty` is declared in `private extension String` (iosApp/Yoin/Library/LibraryView.swift:991-993), so SpotifyPlayingFrom.swift cannot see it. The fallback the plan mentions must be used: `.flatMap { $0.isEmpty ? nil : $0 }`.
- Wrong claim: the plan says its new observe() Task 'never overwrites a label heard already set'. Its code still ends with an unconditional `self.label = label`. If App Remote's heard() sets the daylist name while the task is awaiting, the task can then replace it with the Web API name or the 'Spotify' fallback. This happens when App Remote reconnects on foreground mid-song after the poll already started observe. The task needs `self.label == nil` in its final guard. The only other writer is heard(), and App Remote is the better source.
- The KVC read `(s as? NSObject)?.value(forKey: "contextURI")` raises NSUndefinedKeyException, an uncaught ObjC exception, if the concrete class does not answer the key. Add a `responds(to: NSSelectorFromString("contextURI"))` check and cast to NSURL (its `absoluteString` is `String?`). The nil-URL trap the plan wants to avoid is real: URL's unconditional bridge force-unwraps. A nil nonnull NSString bridges to "" safely, so `s.contextTitle` is fine.
- Missed case, and the most likely on-device outcome: for Spotify's own daylist with App Remote refused (Web API polling), nothing in the plan can name it. The label becomes 'PLAYING FROM PLAYLIST / Spotify', not '/ daylist'. The real name shows only when App Remote is or becomes connected in this process (Yoin woke Spotify, then a reconnect on foreground), or when the daylist is a Library row. The plan should say so plainly. A UserDefaults cache of names App Remote gave for 37i9dQZ ids is the only way to name it across launches. I have kept it as an optional step because daylist titles change during the day.
- Reason 3 (a single me/player read per song, with no retry) is only fixed when App Remote speaks. On the polling path a 429 or a mismatched item still leaves no label for the whole song. Because nothing else would be shown, a Spotify-made list then misses even the fallback. The corrected plan retries once after 2 s and re-checks App Remote's heard context before each read.
- Test plan issues: (a) the `heard`-only-caches test cannot observe the private `names` dictionary, and label(for:) needs a songKey that only observe(model:) sets. Test playlistLabel/nameKey instead, with playlistLabel made internal. (b) Write `Context.parse(...)!` explicitly rather than the implicit-member `.parse(...)!`.
- Minor: `heardContext = (trackId, contextURI)` compiles (unlabeled-to-labeled tuple conversion), but labeled elements are clearer. The local constant `heard` in observe shadows the new method name `heard(...)`; rename it to heardURI.
- An Android divergence the plan did not mention, out of scope for this issue: Android's PlayingFromLabel shows LikedSongs as plain 'NOW PLAYING' (NowPlayingScreen.kt:5010-5016), and deriveSpotifyActivityContext maps only the `spotify:playlist:` and `spotify:album:` prefixes. iOS shows 'PLAYING FROM PLAYLIST / Liked Songs' and also handles `spotify:user:<u>:playlist:` and artist contexts. For a blank title Android shows the kind line over an empty name. Android has no 37i9dQZ or Made For You special case.

**Fix plan (corrected)**

Root causes (all confirmed against 4e6c29c5):
(1) The only way iOS names a playlist is the Web API. It answers 404 for Spotify-made 37i9dQZ lists, and then `guard let name else { return nil }` (SpotifyPlayingFrom.swift:102) drops the whole label, so NowPlayingView.swift:2413 shows "NOW PLAYING".
(2) App Remote's SPTAppRemotePlayerState.contextURI and contextTitle (SDK 5.0.1 header) are never read. RemotePlayerState has no context fields, and SDKAppRemoteLink.state maps only track and options.
(3) observe() makes one me/player read per song, needs `item.id == trackId`, and never retries. App Remote changes externalTrack before me/player catches up.
(4) Yoin cannot start a daylist or any Made For You list: there are no Home playlist rows, the Spotify-made page is tracksUnavailable, and Play is disabled. A song Yoin starts from a list gets playingFrom = nil, which matches Android's ActivityContext.None. A daylist label therefore only ever comes through the externalTrack path.

Android names the context with App Remote's PlayerContext.title (SpotifyContextSync.deriveSpotifyActivityContext). With a blank title it shows "PLAYING FROM PLAYLIST" over an empty name. With no or another context it shows "NOW PLAYING".

Resulting order of name sources on iOS: App Remote contextTitle, then the per-context cache, then the Library row, then the Web API (skipped for 37i9dQZ), then the fallback "Spotify" for a Spotify-made list nothing can name. The page's owner fallback (LibraryStore.spotifyMadeUnavailable) uses the same word. Android would show an empty name there; "Spotify" is a product choice the owner can change to "".

== 1) iosApp/Yoin/Playback/SpotifyRemote.swift ==
a) In `struct RemotePlayerState`, after `var albumURI: String? = nil` (line 19), add:
```swift
    /// Where Spotify plays from (SPTAppRemotePlayerState.contextURI / contextTitle; Android PlayerContext
    /// uri / title): the one source that names Spotify's own lists (daylist), which a Development Mode app
    /// can't read through the Web API.
    var contextURI: String? = nil
    var contextTitle: String? = nil
```
The fields are trailing and defaulted, so existing memberwise uses keep compiling (SpotifyBrokenConnectionTests.swift:324 and others).

b) Replace `SDKAppRemoteLink.state` (lines 496-502) with:
```swift
    nonisolated static func state(_ s: SPTAppRemotePlayerState) -> RemotePlayerState {
        RemotePlayerState(uri: s.track.uri, isPaused: s.isPaused, positionMs: s.playbackPosition,
                          durationMs: Int(s.track.duration), shuffling: s.playbackOptions.isShuffling,
                          repeatRaw: s.playbackOptions.repeatMode.rawValue, title: s.track.name,
                          artist: s.track.artist.name, album: s.track.album.name,
                          imageIdentifier: s.track.imageIdentifier, albumURI: s.track.album.uri,
                          contextURI: contextURI(s), contextTitle: s.contextTitle)
    }

    /// contextURI is declared nonnull NSURL, imported as URL, and URL's bridge force-unwraps: a state with no
    /// context would trap. It is read through KVC as NSURL instead. (contextTitle's nil NSString bridges to "".)
    nonisolated static func contextURI(_ s: SPTAppRemotePlayerState) -> String? {
        guard let o = s as? NSObject, o.responds(to: NSSelectorFromString("contextURI")) else { return nil }
        return (o.value(forKey: "contextURI") as? NSURL)?.absoluteString
    }
```

== 2) iosApp/Yoin/Playback/SpotifyAudioOutput.swift, `private func playerState(_ s: RemotePlayerState)` ==
Directly after `guard SpotifyPlayingDevice.shared.name == nil else { return }` (line 2295) and before `connectionSpoke = true`, insert:
```swift
        // Where Spotify plays from, as App Remote names it (Now Playing's "Playing from", audit 11-15 #35).
        SpotifyPlayingFrom.shared.heard(trackURI: uri, contextURI: s.contextURI, title: s.contextTitle)
```
`uri` is the local `ownURI(s.uri)` from line 2278. It is the URI externalTrack(_:) takes its id from: the last ':' component. Both classes are @MainActor. This runs before `apply(State(...))`, so heardContext is set before externalTrack changes and before NowPlayingView's `.task(id:)` calls observe.

== 3) iosApp/Yoin/NowPlaying/SpotifyPlayingFrom.swift ==
a) Below `names` (line 52), and change names' doc comment to "Context (nameKey) → its name":
```swift
    /// App Remote's latest word: the song and the context URI Spotify plays it from.
    @ObservationIgnored private var heardContext: (trackId: String, uri: String)?

    /// The cache key for a context (App Remote and me/player may spell the same list differently).
    nonisolated static func nameKey(_ c: Context) -> String {
        switch c {
        case .album(let id): "album:\(id)"
        case .playlist(let id): "playlist:\(id)"
        case .artist(let id): "artist:\(id)"
        case .likedSongs: "collection"
        }
    }

    /// App Remote said Spotify plays [trackURI] from [contextURI] and calls it [title] (Android
    /// PlayerContext.title): the name is kept, and the song on screen takes it for a playlist.
    func heard(trackURI: String, contextURI: String?, title: String?) {
        guard let trackId = trackURI.split(separator: ":").last.map(String.init), !trackId.isEmpty,
              let contextURI, let context = Context.parse(contextURI) else { heardContext = nil; return }
        if heardContext?.uri != contextURI || heardContext?.trackId != trackId {
            PlayTrace.log("SP context \(contextURI) title=\(title ?? "-")")
        }
        heardContext = (trackId: trackId, uri: contextURI)
        let name = (title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        names[Self.nameKey(context)] = name
        if case .playlist(let id) = context, songKey == "\(SpotifyMapping.provider):\(trackId)" {
            let named = Self.playlistLabel(id, name: name)
            if label != named { label = named }
        }
    }

    /// Internal (not private) so tests can check it.
    static func playlistLabel(_ id: String, name: String) -> Label {
        // Spotify's own lists (37i9dQZ…) open empty in Development Mode (#24): named, not linked.
        let opens = !isSpotifyMade(id)
        return Label(kind: String(localized: "PLAYING FROM PLAYLIST"), name: name,
                     hint: opens ? String(localized: "Go to playlist") : "", route: opens ? .playlist(id) : nil)
    }
```

b) In `observe`, replace the Task (lines 74-80). The new task prefers App Remote's context, retries once, and never overwrites a label heard() set:
```swift
        task = Task { [weak self] in
            var uri: String?
            for attempt in 0..<2 {
                if attempt > 0 { try? await Task.sleep(for: .seconds(2)) }
                guard !Task.isCancelled, let self else { return }
                if let h = self.heardContext, h.trackId == track.id { uri = h.uri; break }
                if let read = try? await source.playerContext(forTrack: track.id) { uri = read; break }
            }
            guard let uri, !Task.isCancelled, let context = Context.parse(uri) else { return }
            let label = await self?.resolve(context, uri: uri, track: track, model: model, source: source)
            // heard() may have named it meanwhile (App Remote): that wins.
            guard !Task.isCancelled, let self, self.songKey == key, self.label == nil, let label else { return }
            self.label = label
        }
```
`key` is the local from line 69. The retry also covers a me/player read that still reports the previous song, and a transient 429. A song with no context costs one extra read.

c) In `resolve`, replace `case .playlist` (lines 99-107):
```swift
        case .playlist(let id):
            let key = Self.nameKey(context)
            let made = Self.isSpotifyMade(id)
            var name = names[key] ?? model.store.playlistSummary(id)?.title.flatMap { $0.isEmpty ? nil : $0 }
            // Spotify's own lists answer 404 to a Development Mode app (#24): no read; App Remote names them.
            if name == nil, !made { name = try? await source.playlistName(id) }
            if let name { names[key] = name }
            // Nothing names a Spotify-made list (App Remote refused, not in Library): its owner, as its page
            // does (LibraryStore.spotifyMadeUnavailable). Android shows the kind line over a blank name then.
            guard let shown = name ?? (made ? "Spotify" : nil) else { return nil }
            return Self.playlistLabel(id, name: shown)
```
The "Spotify" fallback is never cached in `names`. `nilIfEmpty` must not be used: it is `private` in LibraryView.swift. `playlistSummary(_:)` returns `LibraryItem?` (LibraryStore.swift:1482); if `title` is non-optional `String`, write `.title` followed by `.flatMap` on the optional chain as shown, or `(model.store.playlistSummary(id)?.title).flatMap { $0.isEmpty ? nil : $0 }`.

In `case .artist` (lines 108-114), change both `names[uri]` to `names[Self.nameKey(context)]`. `.likedSongs` and `.album` are unchanged. The `uri:` parameter of resolve becomes unused; keep it for a minimal diff or remove it at the single call site.

== 4) iosApp/YoinTests/SpotifyPlayingFromTests.swift (the class is already @MainActor) ==
```swift
    func test_should_shareANameKey_when_aPlaylistIsSpelledEitherWay() {
        XCTAssertEqual(SpotifyPlayingFrom.nameKey(Context.parse("spotify:user:spotify:playlist:37i9dQZF1EP6YuccBxUcC1")!),
                       SpotifyPlayingFrom.nameKey(Context.parse("spotify:playlist:37i9dQZF1EP6YuccBxUcC1")!))
    }
    func test_should_nameButNotLinkASpotifyMadeList_when_AppRemoteNamesIt() {
        let l = SpotifyPlayingFrom.playlistLabel("37i9dQZF1EP6YuccBxUcC1", name: "daylist")
        XCTAssertEqual(l.kind, String(localized: "PLAYING FROM PLAYLIST"))
        XCTAssertEqual(l.name, "daylist"); XCTAssertNil(l.route); XCTAssertEqual(l.hint, "")
    }
    func test_should_cacheOnly_when_noSongIsObserved() {
        let p = SpotifyPlayingFrom()
        p.heard(trackURI: "spotify:track:t1", contextURI: "spotify:playlist:37i9dQZF1EP6YuccBxUcC1", title: "daylist")
        XCTAssertNil(p.label)
    }
```

== 5) Optional, but the only way the real name shows when App Remote is refused ==
In heard(), when `case .playlist(let id) = context` and `isSpotifyMade(id)`, also write the name to `UserDefaults.standard` under the key "spotifyMadeNames". In resolve, read that dictionary after `names[key]`. Trade-off: daylist's title changes during the day ("daylist • … morning" → "… afternoon"), so a persisted name can be stale until App Remote speaks again.

What the user will see:
- App Remote connected, i.e. Yoin woke Spotify or it reconnected on foreground: "PLAYING FROM PLAYLIST / <Spotify's contextTitle>" from the first state. The queue sheet shows "Playing from / <name>", with no link for 37i9dQZ.
- Spotify's own daylist with App Remote refused and Web API polling: "PLAYING FROM PLAYLIST / Spotify". If App Remote named it earlier in this run, or step 5 is taken, the cached name shows instead. If the daylist is a Library row, the row title shows.
- Context unknown or not album/playlist/artist/collection, or me/player fails twice: "NOW PLAYING", as on Android.

Device checks:
(a) With App Remote connected and daylist started in Spotify, read PlayTrace "SP context …". Confirm contextURI is spotify:playlist:37i9dQZ… and see what contextTitle holds; it may be "" on some Spotify versions.
(b) With App Remote refused, confirm GET me/player still returns context.uri for the daylist in Development Mode. If it returns null, only App Remote, the Library row or the persisted name can label it, and even the "Spotify" fallback never shows.
(c) Confirm no crash on a state without a context, e.g. right after authorizeAndPlayURI with a bare track URI.
(d) Change songs inside the daylist and confirm the label shows on the first state.

**Check on the device**

- With App Remote connected (Yoin woke Spotify) and daylist started in Spotify, log RemotePlayerState.contextURI and contextTitle. Confirm the URI is spotify:playlist:37i9dQZ... and see what the title says ('daylist' or 'daylist • <mood> <time>').
- With App Remote refused (daylist started in Spotify, Web API polling): confirm GET me/player in Development Mode still returns context.uri = spotify:playlist:37i9dQZ... for daylist and not a null context. If it is null, only App Remote or the Library row can name it, and the 'Spotify' fallback never shows.
- Check whether GET me/playlists lists a saved daylist, which gives store.playlistSummary(id)?.title, and what title it carries.
- Check that the KVC read of contextURI does not crash on a state with no context, e.g. right after authorizeAndPlayURI with a bare track URI.
- After the fix, with App Remote connected: change songs inside the daylist and confirm the label shows on the first state, without waiting on me/player (the race in reason 3).
- In the queue sheet header (QueueSheet.swift:261), confirm 'Playing from / <daylist name>' shows and does not link anywhere, since the route is nil for 37i9dQZ.

## 2. Now Playing title marquee

**Regression?** This is not a regression. The gap exists on mainline too. On 2amedi (111b7cb6), heroTitle is the same plain Text: `git show 111b7cb6:iosApp/Yoin/NowPlaying/NowPlayingView.swift` lines 2340-2350 have `Text(p.track.title).font(.gsf(36))...` with no marquee. Between 111b7cb6 and 4e6c29c5, MarqueeText.swift, NowPlayingPresentation.swift and the doc are unchanged. NowPlayingView.swift does change (+63/-9), but those hunks do not touch heroTitle, hero or wideTitle. MarqueeText arrived in commit 5f6b0088 ("ios(bar): a long title or artist in the mini player scrolls ... parity C-U9"), which is already an ancestor of mainline, and it was only wired into the bar pill (iosApp/Yoin/Shell/ShellBar.swift:540-546). The docs list the gap as never ported: iosApp/docs/fidelity-home-np.md:171 says "The Now Playing title doesn't marquee." It has nothing to do with Spotify, App Remote or Web API polling. It happens the same way for model.player.externalTrack songs and for songs Yoin queued, because the title always comes from PlaybackSnapshot.track.title.

**Root causes**

- The iOS hero title is a plain Text that is cut with an ellipsis and never scrolls. heroTitle is at iosApp/Yoin/NowPlaying/NowPlayingView.swift:2362-2372: `Text(p.track.title).font(.gsf(36)).foregroundStyle(scheme.onSurface)`, then the stretch scaleEffect (2366), the fold scaleEffect (2367), matchedGeometryEffect position/leading (2368-2369), npFade (2370) and npMorphAnchor(.title) (2371). The one-line limit comes from the parent: `.lineLimit(1)` at 2318 (inline row) and 2352 (stacked VStack). The stacked slot is `.frame(height: 44, alignment: .leading)` at 2349. The inline row is `.frame(height: Tokens.type.displaySmall.lineHeight.cg)` at 2321.
- The one shared title path covers the phone (full), the iPad side panel (panel = true, NowPlayingView.swift:24, matched id "np.title.panel" at 2368), focus, and the tips demo phone. All of them reach heroTitle through `hero(p, inline: budget.inlineHero)` at NowPlayingView.swift:815. Short focus hides it (opacity 0 / height 0 at 817-819). The stacked form calls heroTitle at 2348 and the inline form at 2299.
- The two-column layout (iPad Full in a wide window) has its own title, also a plain Text: wideTitle at NowPlayingView.swift:3625-3659, with the title at 3647-3658, `.font(.gsf(Tokens.type.headlineMedium.size.cg))` and `.lineLimit(twoLines ? 2 : 1)`. It is called at 3419-3424 when the title is not in the top bar. In the oneLine form, Android scrolls this title. The top-bar subline (3563-3568, 'title - artist', labelMedium, lineLimit 1) uses an ellipsis on Android too (NowPlayingScreen.kt:3799-3804), so it is already correct.
- No iOS phone landscape layout exists, so Android's landscape marquee (NowPlayingScreen.kt:3225-3233, headlineSmall) has no counterpart. On iPhone, Info.plist:104-107 allows portrait only. iPad landscape goes through the wide layout (NowPlayingView.swift:561-563, isWide) or the panel.
- MarqueeText (iosApp/Yoin/Theme/MarqueeText.swift) already fits most of the API: `text: String, font: Font, color: Color, velocity = 30, initialDelay = 2.5, repeatDelay = 2` at lines 8-13. Because heroTitle uses `.font(.gsf(36))`, not yoinRole, a Font parameter is enough. `.gsf` is `Font.gsf(_:_:relativeTo:)` at Theme/Theme.swift:71, and scheme.onSurface is a Color (Theme/CoverScheme.swift:30). The view is laid out as the words' own width (base hidden one-line Text, lines 21-26), which keeps the morph anchor's frame the words' frame, as its comment says. It is missing two things: (a) the edge fades that Android NP uses (no mask, a hard `.clipped()` at line 40), and (b) Android NP's timing: its default initialDelay is 2.5 s (line 12), but Android NP uses 1.5 s. Other limits are not blockers but should be known. It is leading/LTR only (offset is always negative, lines 34-37). It is one line only. It has no built-in stretch. Under Reduce Motion it stays still and truncates with an ellipsis (line 42).

**Android**

Android's Now Playing title uses NowPlayingMarqueeTitle (app/src/main/java/com/gpo/yoin/ui/nowplaying/NowPlayingScreen.kt:5283-5345).

**When it scrolls**
- Inside BoxWithConstraints, it measures the title once with no width limit (maxLines 1, softWrap false). It scrolls only when that width is greater than the available width (5292-5307).
- Text that fits keeps its own width, so the one-row hero can put the artist right after it (5311-5320).

**When it overflows**
- The box is `fillMaxWidth().clipToBounds().horizontalFadeMask(edgeWidth = 28.dp)` (5313-5317).
- The Text is maxLines 1, softWrap false, overflow Clip (5321-5327).
- The play/pause stretch (`graphicsLayer scaleX = stretchScale`, origin start-center) sits inside the clip box (5326-5330), so the stretched text is clipped at the slot's edge.
- Scrolling is `basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2000, initialDelayMillis = 1500)` (5335-5340). Everything else is a Compose default: velocity MarqueeDefaults.Velocity = 30 dp/s, spacing MarqueeDefaults.Spacing = fractionOfContainer(1/3), animationMode Immediately.

**The fade**
- horizontalFadeMask (app/src/main/java/com/gpo/yoin/ui/component/PressFeedback.kt:67-94) is an offscreen layer with a DstIn gradient on both edges.
- Each 28 dp ramp uses the smoothstep alpha stops 0, 0.104, 0.352, 0.648, 0.896, 1, evenly spaced (81-87).
- It is static: whenever the title overflows, both edges are faded, the leading edge included, even while the title is resting.

**Where it is used**
- Stacked hero: displaySmall (36/44), onSurface, titleStretchScale, fillMaxWidth (4790-4797).
- One-row hero: displaySmall, `weight(1f, fill=false)` (4751-4757). Here only the end edge is clipped, by the stretch growth, and the artist moves along with the growth (4700-4745).
- Two-column OneLine form: headlineMedium with stretchScale = 1f (2533-2552). The TwoLines form is a plain Text with maxLines 2 and an ellipsis (2535-2543).
- Landscape: headlineSmall with stretchScale 1f (3225-3233).
- The stretch values are 1.05 pressed, 1.03 playing, 0.97 paused (1180-1190), which iOS already matches at NowPlayingView.swift:2384-2389.

**The artist line does not scroll on Now Playing**
- artistText is maxLines 1 with an ellipsis (4686-4699).
- Landscape artist: ellipsis (3234-3240).
- Wide top-bar subline: ellipsis (3799-3804).
- Only the bar pill scrolls both the title and the artist (component/NowPlayingPill.kt:269-275 and 313-330, initialDelay 1500, repeatDelay 2000). So on iOS, only the title should scroll.

**Review notes on the first plan**

- Off-by-one line numbers. The parent `.lineLimit(1)` calls are at NowPlayingView.swift:2319 (inline HStack) and 2351 (stacked VStack), not 2318 and 2352. The other citations check out: 2299, 2321, 2348, 2349, 2362-2372, 3419-3424, 3563-3568, 3625-3659, 561-563 and 815-820 in NowPlayingView.swift; MarqueeText.swift 8-13, 21-47 and 50-64; NowPlayingPresentation.swift 449-457 and 590/601; Theme.swift:71; ShellBar.swift:541/546; fidelity-home-np.md:171/174; Info.plist:104-107; Android NowPlayingScreen.kt 1180-1190, 2533-2554, 3225-3242, 4686-4797, 5283-5348 and 3799-3804; PressFeedback.kt:67-95; NowPlayingPill.kt:269-274.
- Risk 3 is moot. Grep finds the matchedGeometryEffect ids "np.title" and "np.title.panel" only once, at NowPlayingView.swift:2368. Nothing else uses them: the pill's MarqueeText (ShellBar.swift:541) has no matchedGeometryEffect, and panel and full use different ids. So the effect has no partner, does nothing, and cannot be affected by the change. The title morph that matters is the npMorphAnchor/flight path in NowPlayingPresentation (risk 1). The panel and full-page pages are separate NowPlayingView instances with different ids, so no geometry is matched between them.
- One Android Now Playing marquee is missing from android_behaviour: NowPlayingSpineMarquee (NowPlayingScreen.kt:5099-5121, called at 1544). It is labelLarge text with basicMarquee(repeatDelay 2000, default initialDelay) and the same 28 dp horizontalFadeMask, rotated -90 degrees in the band beside a height-capped cover. iOS has no spine view; 'spine' appears only as data in NowPlayingAbout.swift and SongCredits.swift. So nothing needs to change for this issue, but the claim that the title is the only thing on Android NP that marquees is incomplete. It is the only text-row marquee.
- Two-column stretch: Android applies no stretch at all to the DualPane title. The TwoLines form is a plain Text (2536-2543) and OneLine uses stretchScale = 1f (2549). iOS wideTitle already stretches in both forms. The investigator flagged this only for OneLine. It is an existing divergence and is not caused by this patch.
- Minor compile-safety point in the proposed MarqueeWindow: `LinearGradient(colors: Self.ramp.reversed(), ...)` relies on overload resolution picking Sequence.reversed() -> [Color] over BidirectionalCollection's ReversedCollection. It should compile because the parameter type is [Color], but it is simpler and certain to reuse `Self.ramp` with startPoint .trailing / endPoint .leading.
- The static var vs static let concern is a non-issue. Color is Sendable, so a `static let` of [Color] is also fine under SWIFT_VERSION 6.0 / SWIFT_STRICT_CONCURRENCY complete (project.yml:18-19). The computed var works too.
- The vertical mask bleed (padding -fade) is not Android parity. Android's NowPlayingMarqueeTitle box is clipToBounds, which clips vertically as well. It is harmless and arguably better, but it should not be described as matching Android.
- Timing nuance not mentioned: MarqueeText's `start` is @State initialised when the view is created. On the phone the page mounts at the start of the pill-to-page morph, so the 1.5 s first delay partly runs during the open flight, when the page title is hidden (np.inFlight). The visible still time after landing is therefore shorter than 1.5 s. Android's basicMarquee starts its delay when the title is composed, which is the same behaviour, so this is acceptable but worth knowing during device testing.

**Fix plan (corrected)**

Scope: make the Now Playing title marquee as Android's NowPlayingMarqueeTitle does (NowPlayingScreen.kt:5283-5348): it is measured unbounded, scrolls only when it overflows, basicMarquee(initialDelay 1500 ms, repeatDelay 2000 ms, velocity 30 dp/s, spacing 1/3 of the container), and while overflowing it is clipped to its slot with horizontalFadeMask(28.dp), a smoothstep ramp on both edges that is shown all the time (PressFeedback.kt:67-95). The artist line does not marquee on Android NP: hero artistText (4688-4699), landscape (3234-3241) and the wide top-bar subline (3799-3804) all use an ellipsis. So only the title changes. This is not a regression: mainline 111b7cb6 has the same plain Text, and the gap is listed at fidelity-home-np.md:171. It does not depend on Spotify, App Remote or Web API; it applies equally to externalTrack and queued songs.

1) iosApp/Yoin/Theme/MarqueeText.swift

a) After line 13 (`var repeatDelay: Double = 2`), add:
```swift
    /// Soft edges on an overflowing line, in points at each end (Android Now Playing's
    /// horizontalFadeMask(28.dp), PressFeedback.kt:67); 0 keeps the bar's hard clip.
    var fade: CGFloat = 0
```
The memberwise init gets the new label last, so existing calls `MarqueeText(text:font:color:)` (ShellBar.swift:541, 546) are unchanged.

b) Replace lines 39-40:
```swift
                    .frame(width: box, alignment: .leading)
                    .clipped()
```
with:
```swift
                    .frame(width: box, alignment: .leading)
                    .modifier(MarqueeWindow(fade: min(fade, box / 2)))
```
This mirrors Android's `edgeWidthPx.coerceAtMost(size.width / 2f)`. `box > 0` is guaranteed in this branch, because `overflow` requires it.

c) After the struct's closing brace (after line 65), add:
```swift
/// The scrolling line's window: the bar's hard clip, or Android's horizontalFadeMask (both edges on its
/// smoothstep ramp), which also keeps glyph tops and tails whole above and below the line.
private struct MarqueeWindow: ViewModifier {
    var fade: CGFloat

    func body(content: Content) -> some View {
        if fade > 0 {
            content.mask {
                HStack(spacing: 0) {
                    LinearGradient(colors: Self.ramp, startPoint: .leading, endPoint: .trailing)
                        .frame(width: fade)
                    Color.black
                    LinearGradient(colors: Self.ramp, startPoint: .trailing, endPoint: .leading)
                        .frame(width: fade)
                }
                .padding(.vertical, -fade)
            }
        } else {
            content.clipped()
        }
    }

    /// PressFeedback.kt's eased stops (0, .104, .352, .648, .896, 1), evenly spaced.
    private static let ramp: [Color] = [0, 0.104, 0.352, 0.648, 0.896, 1].map { Color.black.opacity($0) }
}
```
Notes on (c):
- `ViewModifier.body(content:)` is implicitly @ViewBuilder; the same pattern is used at NowPlayingPresentation.swift:587-606.
- `mask(alignment:_:)` and `LinearGradient(colors:startPoint:endPoint:)` are standard iOS 15+ API.
- Color is Sendable, so `static let` is fine under Swift 6 complete concurrency.
- Android clips vertically (clipToBounds), so the vertical bleed is a deliberate improvement, not parity.

d) Optional: update the doc comment at lines 3-6 to say the type is also used for Now Playing's title, with fades.

2) iosApp/Yoin/NowPlaying/NowPlayingView.swift:2363-2365 (heroTitle). Replace
```swift
        Text(p.track.title)
            .font(.gsf(36))
            .foregroundStyle(scheme.onSurface)
```
with
```swift
        // A long title scrolls (Android NowPlayingMarqueeTitle: 1.5 s still, 30 pt/s, 2 s rest, 28 pt fades).
        MarqueeText(text: p.track.title, font: .gsf(36), color: scheme.onSurface,
                    initialDelay: 1.5, repeatDelay: 2, fade: 28)
```
Keep lines 2366-2371 unchanged: the stretch `.animation(Self.heroStretchSpring){ $0.scaleEffect(...) }` (works on any View, iOS 17), the fold scale, matchedGeometryEffect, npFade and npMorphAnchor(.title).

This one edit covers every layout that reaches heroTitle through hero(p, inline:) at 815: the phone full page (stacked, 2348), the short-screen inline row (2299), focus (hidden in short focus at 817-820), the iPad side panel (panel = true) and the tips demo phone.

Layout stays the same as the plain Text:
- MarqueeText's layout view is a hidden one-line Text (MarqueeText.swift:23-26), so its width is min(natural, room).
- The npMorphAnchor frame (pageTitleFrame), the inline row's .firstTextBaseline alignment and the layoutPriority(1)/CappedWidth sharing all keep working.
- The 44-high slot (2349) and the displaySmall row height (2321) are unchanged.

3) iosApp/Yoin/NowPlaying/NowPlayingView.swift:3647-3657 (wideTitle, Android DualPane; OneLine marquees at NowPlayingScreen.kt:2545-2553, TwoLines is a plain 2-line ellipsis at 2536-2543). Replace the routeLink(albumRoute...) body with:
```swift
            routeLink(albumRoute(p), label: String(localized: "Go to album")) {
                Group {
                    if twoLines {
                        Text(p.track.title)
                            .font(.gsf(Tokens.type.headlineMedium.size.cg))
                            .lineSpacing(YoinType.lineSpacing(Tokens.type.headlineMedium))
                            .foregroundStyle(scheme.onSurface)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                    } else {
                        // Android DualPane OneLine: NowPlayingMarqueeTitle (NowPlayingScreen.kt:2545).
                        MarqueeText(text: p.track.title, font: .gsf(Tokens.type.headlineMedium.size.cg),
                                    color: scheme.onSurface, initialDelay: 1.5, repeatDelay: 2, fade: 28)
                    }
                }
                .animation(Self.heroStretchSpring) {
                    $0.scaleEffect(x: titleStretch(p), y: 1, anchor: .leading)
                }
            }
```
Android applies no stretch to the DualPane title in either form (TwoLines is a plain Text; OneLine uses stretchScale 1f). iOS already stretches both. That divergence predates this patch and is kept here to stay minimal. For strict parity, delete the `.animation{}` block. The call site at 3419-3423 is unchanged.

Not changed, verified correct as is:
- The wide top-bar subline (3563-3568) keeps its ellipsis, as on Android.
- The lyrics header title (NowPlayingLyricsTools.swift:340) wraps; it is not a marquee on Android.
- iPhone is portrait only (Info.plist:104-107), so Android's phone-landscape marquee (3225-3233) has no counterpart. iPad landscape uses the wide layout (isWide at 561) or the panel.
- Android's NowPlayingSpineMarquee (5099-5121) has no iOS view (no spine UI exists) and is out of scope.

4) iosApp/docs/fidelity-home-np.md:171: remove "The Now Playing title doesn't marquee." and add it to the "Since built" list at line 174, e.g. "the Now Playing title's marquee (`Theme/MarqueeText.swift` with 28 pt fades)".

5) Optional test in iosApp/YoinTests/MarqueeTextTests.swift (the static helpers are unchanged):
```swift
    func test_should_useNowPlayingTiming() {
        XCTAssertEqual(MarqueeText.offset(at: 1.4, natural: 500, box: 343, velocity: 30, initialDelay: 1.5, repeatDelay: 2), 0)
        XCTAssertEqual(MarqueeText.offset(at: 2.5, natural: 500, box: 343, velocity: 30, initialDelay: 1.5, repeatDelay: 2), -30, accuracy: 0.001)
    }
```
Arithmetic: travel = 500 + 114.33 = 614.33, moving = 20.48 s, phase at t = 2.5 is 1.0, so the offset is -30.

Risks / known differences:
- Morph hand-off. During the open/close flight the page title is hidden (NowPlayingPresentation.swift:590). The flight copy (449-457) is an ellipsized gsf(36) Text framed to pageTitleFrame.width. When the flight lands, a long title swaps from "Long title wi…" to the faded, clipped line. On close, a title caught mid-scroll snaps to offset 0. Optional follow-up: give the flight copy the same treatment when its natural width exceeds toTitle.width.
- Stretch placement. On iOS the 1.03/1.05 stretch scales the whole faded window, which overhangs the trailing margin by about 3% (the ellipsized Text already does this today). On Android the stretch is inside clipToBounds (5313-5333), and the inline row's end-only clip and artist ride (4701-4771) are still not ported. For exact parity, add a `stretch` parameter applied to `line` inside the window. That would be a larger change, because the stretch animation would also have to move inside.
- The fades show whenever the title overflows, including the leading 28 pt at rest. This matches Android NowPlayingMarqueeTitle.
- An overflowing title runs a 60 fps TimelineView while the page is mounted, even when it is hidden by opacity (short focus at 817, npFade, inFlight). Cheap, but it never pauses.
- The first-delay clock starts when the view is created. On the phone that is the start of the open morph, so less than 1.5 s of stillness is visible after landing.
- Accessibility. MarqueeText is one element labelled with the full title (46-47). VoiceOver keeps the "Go to album" hint inside routeLink's Button. With no route, the plain Text's static-text trait is replaced by a combined element. Under Reduce Motion the line stays still with an ellipsis (MarqueeText.swift:42). Android scrolls regardless of motion profile; the iOS behaviour is the component's existing choice.
- RTL titles always scroll toward negative x from .leading.
- On the first layout pass box = 0, so the not-yet-measured line renders at width 0 for one frame. This could flash when switching between the inline and stacked hero forms.
- The pill keeps its 2.5 s default first delay (Android pill: 1.5 s, NowPlayingPill.kt:273). Out of scope here. Passing initialDelay: 1.5 at ShellBar.swift:541/546 would close that gap separately.

Device checks:
(a) Long title on the phone full page: still for about 1.5 s, then 30 pt/s, copy a third of the slot behind, 2 s rest, 28 pt fades on both edges. A short title stays plain with no fades and no ellipsis.
(b) Pill to page morph and back with a long title: how visible is the swap? Also check that pageTitleFrame and the landing position are unchanged.
(c) `-screen np-short` inline row: baselines still align, the artist keeps its 40% cap, and the title never runs under the artist.
(d) Play/pause and press stretch on an overflowing title: how big is the trailing overhang?
(e) iPad side panel and iPad two-column OneLine: the title scrolls. TwoLines keeps its 2-line ellipsis; the topBar subline keeps its ellipsis.
(f) Track change mid-scroll: the new title restarts from rest. Pausing does not stop the scroll.
(g) Reduce Motion: still, with an ellipsis. VoiceOver reads the full title plus the hint.
(h) Tall diacritics (Vietnamese, accented capitals in GSF 36) are not clipped by the mask.

**Check on the device**

- Phone, full page, a long title (e.g. a Spotify-own song with a long name, shown via model.player.externalTrack): it stays still for about 1.5 s, moves left at about 30 pt/s, a copy follows one third of the slot behind, it rests 2 s at each lap, and both edges show 28 pt fades. A short title stays still with no fades and no ellipsis.
- Pill to page morph and back with a long title: how visible is the swap from the ellipsized flight copy to the faded marquee when the open lands, and the snap back to offset 0 at the start of a close? Also check that the title's landing position (pageTitleFrame) does not move compared with now.
- Short-screen inline hero (`-screen np-short`): the title and the artist still share the first-text baseline; the artist keeps its 40% cap; the scrolling title stays inside its own slot and never runs under the artist.
- Play/pause and press stretch (1.03 / 0.97 / 1.05) on an overflowing title: how far does the faded window overhang the trailing margin? Is that acceptable, or should the stretch be moved inside MarqueeText?
- iPad side panel ("np.title.panel") and iPad full-window two-column layout in the OneLine title form: the title scrolls. TwoLines keeps the two-line ellipsis. The topBar form keeps the 'title - artist' subline with an ellipsis.
- Track change in the middle of a scroll: the new title restarts from rest (onChange(of: text) resets start). Pausing playback does not affect scrolling, as on Android.
- Reduce Motion on: the title stays still and ends in an ellipsis. VoiceOver reads the full title and the 'Go to album' hint.
- Titles with tall diacritics (e.g. Vietnamese, or É in GSF 36): tops and tails are not clipped by the mask.

## 3. Next song's lyrics preload

**Regression?** This is not a regression. Preloading was missing on mainline too, and 3ryaas only changed how it fails for Spotify's own songs.

(1) On mainline 111b7cb6, `PlayerEngine.upNextTrack` had no externalTrack check (`git show 111b7cb6:iosApp/Yoin/Playback/PlayerEngine.swift:430-433`: `guard repeatMode != .one, let i = autoAdvanceIndex ...`). So under Spotify's own song it prefetched the next song of Yoin's parked queue, which is a song Spotify never plays. The fetch was wasted.

(2) On 3ryaas, commit 30714943 ("Spotify's own song gets its own queue, outro and Go rows") added `externalTrack == nil` to that guard (`iosApp/Yoin/Playback/PlayerEngine.swift:492`), and `iosApp/YoinTests/PlayerEngineTests.swift:189` tests it. Under Spotify's own song nothing is prefetched now.

(3) On both branches the prefetch lives only in a `.task` inside NowPlayingView: mainline NowPlayingView.swift:431, 3ryaas `NowPlayingView.swift:435-440`. That view is mounted only while `model.np.mounted` is true (mainline Shells.swift:120, 3ryaas `iosApp/Yoin/Shell/Shells.swift:120-123`).

(4) `SpotifySource.playerQueue()` and `QueueEntry` are new on 3ryaas (mainline has neither). They are only used by the queue sheet (`iosApp/Yoin/Playback/QueueSheet.swift:171`) and were never wired into lyrics.

Android is the reference and does preload in both cases.

**Root causes**

- 1 (main cause given the device facts). Under Spotify's own song there is no next song to prefetch. `PlayerEngine.upNextTrack` returns nil whenever `externalTrack != nil` (`iosApp/Yoin/Playback/PlayerEngine.swift:491-494`). The prefetch task in Now Playing then stops at once (`iosApp/Yoin/NowPlaying/NowPlayingView.swift:435-436`, `guard let next = model.player.upNextTrack else { return }`). Each new Spotify song's lyrics are only fetched by `prepare()` after Yoin notices the change.
- 2. Yoin notices a change to Spotify's own song late, and the lookup is slow. With App Remote refused, the song change only arrives through the `me/player` poll every 4 s (`iosApp/Yoin/Playback/SpotifyAudioOutput.swift:2380-2387`: `apply(State(...))` from the Web API read, then `externalTrack(_:)` at 2410-2416). Only then does `.task(id: engineTrack…)` run `prepare()` (`iosApp/Yoin/NowPlaying/NowPlayingView.swift:426-429`). That calls `loadProviderLyrics()`, which runs QQ, then NetEase, then LRCLIB one after another (`iosApp/Yoin/NowPlaying/NowPlayingData.swift:154-186`; `shared/src/commonMain/kotlin/com/gpo/yoin/shared/data/lyrics/LyricsRegistry.kt:152-158`). So without a prefetch the lyrics show up several seconds into the song.
- 3. Both the prefetch and `prepare()` live only in NowPlayingView, and on iPhone that view exists only while Now Playing is open. The page is built inside `NowPlayingMorph`, which is mounted only `if model.np.mounted` (`iosApp/Yoin/Shell/Shells.swift:120-123`; iPad overlay `iosApp/Yoin/Shell/PadShell.swift:73-74`; the iPad side panel only `if model.npPanel` at `PadShell.swift:50-51`). `mounted` is set when the page opens and set back to false when the close spring ends (`iosApp/Yoin/NowPlaying/NowPlayingPresentation.swift:22, 77-79, 88-95`). With the mini player only, neither the next song's lyrics nor the current song's are fetched. Android keeps NowPlayingViewModel at shell scope (`app/src/main/java/com/gpo/yoin/ui/navigation/YoinNavHost.kt:231-233`), and its prefetch runs from the ViewModel's init whether or not Now Playing is open (`app/src/main/java/com/gpo/yoin/ui/nowplaying/NowPlayingViewModel.kt:255-310`).
- 4. iOS never reads Spotify's queue for lyrics, and the queue entry lacks the duration the lookup uses. `SpotifySource.QueueEntry` holds only id, uri, title, artist and coverURL (`iosApp/Yoin/Data/Spotify/SpotifySource.swift:484-490`). `queueEntries` drops `SpotifyDTO.Track.durationMs`, which is already decoded (`iosApp/Yoin/Data/Spotify/SpotifyDTOs.swift:46`; `SpotifySource.swift:506-516`). The lyric lookup takes title, artist and duration (`NowPlayingData.swift:170-171`; `LyricsRegistry.kt:118-124`). Album is not used for lyrics. Android takes over Spotify's queue for songs Yoin didn't start: it reads it (`app/src/main/java/com/gpo/yoin/player/SpotifyAppRemotePlayer.kt:538-555`, `mirror.adopt(now, reported.next)`; `app/src/main/java/com/gpo/yoin/player/SpotifyQueueMirror.kt:72-81`) as full Tracks with duration (`app/src/main/java/com/gpo/yoin/data/source/spotify/SpotifyMusicSource.kt:523-534`). `resolvedNextTrack()` then returns the next of that queue (`NowPlayingViewModel.kt:1551-1559`), and that song is prefetched.
- 5 (iOS limitation, not fixed here). While Spotify plays, Yoin plays no audio itself, so iOS can suspend it in the background and no Swift task runs. The only background time Yoin takes is short tasks for Spotify hand-offs (`SpotifyAudioOutput.swift:1087, 1117`). A song that starts while Yoin is in the background only gets its lyrics when Yoin is back and Now Playing is open. Android runs a playback service. This needs checking on the device.
- 6 (minor). A failed prefetch is not retried. On failure the song's lyrics are stored as none and marked for retry (`NowPlayingData.swift:181-185`), so they are only fetched again by `prepare()` when the song starts. Android retries twice, after 15 s and 30 s (`NowPlayingViewModel.kt:67-71, 268-290`). iOS also waits 2 s before prefetching (`NowPlayingView.swift:437`), Android 4 s (`NowPlayingViewModel.kt:65`).
- Ruled out: the cache and its key work. The key is `provider:id` (`NowPlayingData.swift:77`, `iosApp/Yoin/Notes/LocalRatings.swift:103`). A Yoin-queued Spotify item and a Spotify-reported song give the same key (provider `spotify`, id = last part of the URI, `SpotifyAudioOutput.swift:2410-2416`). `prepare()` reuses a prefetched result: `loadProviderLyrics` returns early when `lyrics[k] != nil` (`NowPlayingData.swift:155`) and reads the saved automatic hit first (159-165), which the prefetch wrote (175). An in-flight prefetch leaves `.loading`, so `prepare()` waits for it instead of fetching twice. Cancelling the Swift task does not cancel the Kotlin/Native `autoFetch` (plain `@Throws suspend`, `LyricsRegistry.kt:118`; I found no SKIE in the build files), so the result still lands. For songs from Yoin's own queue (App Remote, single song plus the next one handed, the default since `contextStartEnabled` is opt-in at `SpotifyAudioOutput.swift:553`), the preload works, but only while Now Playing is open (cause 3).

**Android**

NowPlayingViewModel lives at shell scope (`YoinNavHost.kt:231-233`), so it is not tied to the Now Playing page being open.

- Preload: in its init it watches `playbackState.map { it.resolvedNextTrack() }` with collectLatest (`NowPlayingViewModel.kt:255-310`). It waits 4 s (`UP_NEXT_PREFETCH_DELAY_MS`, line 65), then calls `repository.getLoadedLyrics(next.id, title, artist, durationSec*1000)`. A failure is retried twice, after 15 s and 30 s (lines 67-71, 279-287). Hits are kept in `_prefetchedLyrics`, up to 3 entries (line 74).
- Song change: when the song starts, the prefetched entry is shown in the same beat and dropped from the cache. Only when there is none does it call `loadLyrics` (lines 211-237).
- Next song: `resolvedNextTrack()` (lines 1551-1559) uses the backend's `nextTrack`, and otherwise the mirrored queue (null under shuffle or repeat-one).
- Spotify's own songs: for playback started in the Spotify app, `SpotifyAppRemotePlayer.syncQueueWithSpotify` reads `GET me/player/queue` (`playerQueue()`, `SpotifyMusicSource.kt:523-534`, full Tracks with duration) and takes it over with `mirror.adopt(now, reported.next)` (`SpotifyAppRemotePlayer.kt:538-555`; `SpotifyQueueMirror.kt:72-81`, read again at every song). So the next Spotify song's lyrics are prefetched, and the outro also shows it (`upNextTimed`, `NowPlayingViewModel.kt:126-131`).

**Review notes on the first plan**

- Every cited file:line I checked is correct. That covers NowPlayingData.swift:77/101/155/175/184/261-279, NowPlayingView.swift:426-440 and 1891, PlayerEngine.swift:64/491-494, SpotifySource.swift:484-516, SpotifyAudioOutput.swift:153 and 2410-2416, Shells.swift:120-123, PadShell.swift:50-51 and 73-74, AppModel.swift:164 and 842/848, LibraryStore.swift:172, PlayerEngineTests.swift:189, SpotifyQueueReadTests.swift:6, LyricsRegistry.kt:118-124 and 152+, Android NowPlayingViewModel.kt:65-74/211-310/1551-1559, SpotifyAppRemotePlayer.kt:538-555, SpotifyQueueMirror.kt:72-81, SpotifyMusicSource.kt:523-534 and YoinNavHost.kt:231-233. All six root causes are real.
- Imprecise wording: 'prepare() waits for it instead of fetching twice' is not quite right. loadProviderLyrics' guard (line 155) returns at once when lyrics[k] == .loading. The view shows .loading and the in-flight prefetch fills it later. The outcome is the same, but nothing waits.
- Placement bug in (C). Adding `.task(id: model.player.currentTrack ...)` / `.task(id: nextPrefetchKey(model))` straight onto RootView's chain makes RootView.body (the whole app root) depend on PlayerEngine.queue, currentIndex, repeatMode and externalTrack. RootView would then re-evaluate on every queue edit and every song change. It compiles, but a small dedicated view is cleaner. That view must get `model` explicitly: `.environment(model)` earlier in the chain does not reach views added later by .background/.overlay, which is why the existing overlays re-apply .environment(model).
- Retry is not just 'optional'. prefetchLyrics guards `lyrics[k] == nil` (line 263). After one failed prefetch the key holds `.none` and sits in lyricsRetry, so the next song's lyrics are not fetched again until prepare() runs once that song is playing. That is exactly the late load the owner sees. Android retries twice, after 15 s and 30 s. The fix should include a retry, built on the private loadProviderLyrics/loadLyrics retry path, which only code inside NowPlayingData can call.
- Stale queue read is not handled. Android drops a queue read whose currently_playing is not the song it is on (SpotifyAppRemotePlayer.kt:551). The proposed `playerQueue().first` plus `next.id != own.id` gives up when the read is one song behind: the queue's first entry is the current song, and the real next song is the second entry. Fix: skip leading entries equal to the current song.
- `model.store.spotify` is nil unless Spotify is the active source (LibraryStore.swift:172). Spotify's own song played while the user browses another source therefore gets no prefetch. The queue sheet has the same limit, so this is kept for consistency and noted as a limit.
- The DEBUG `-lyricsCreditsDemo` regression the investigator lists is real: RootView's current-song prefetch fills lyrics[k] before prepare() can inject the demo LRC. The fix should skip the current-song prefetch when that flag is on, not just flag it as a risk.
- On iPad or Mac with several windows, each RootView would run its own copy of the tasks. Lyric fetches are de-duplicated by the lyrics[k] guard, but the GET me/player/queue read happens once per window. This is minor and noted.
- The Swift in (A) and (B) compiles as written. The project uses Swift 6 with strict concurrency set to complete. SpotifyMapping is a nonisolated enum, `streamURL(trackId:)` is nonisolated static, nested QueueEntry does not inherit @MainActor, the PlayableTrack memberwise argument order matches the declaration, `try? await x().first` flattens to an optional, and the `var durationMs: Int = 0` default keeps the test's memberwise call compiling.

**Fix plan (corrected)**

The diagnosis holds. Preload fails for three reasons: (1) under Spotify's own song, upNextTrack is nil by design and nothing reads Spotify's queue for lyrics; (2) the only prefetch runs in a .task inside NowPlayingView, which is mounted only while Now Playing is open (model.np.mounted / model.npPanel); (3) a failed prefetch leaves `.none`, which prefetchLyrics' `lyrics[k] == nil` guard never fetches again. The 4 s poll and the sequential QQ→NetEase→LRCLIB lookup make a missed prefetch visible. While iOS has Yoin suspended in the background, nothing in Swift runs; this fix cannot address that case. The cache key `provider:id` is the same for a prefetched song and the playing one, and prepare() reuses what the prefetch stored.

The fix touches 4 files plus 1 new small view. It can live inside YoinApp.swift, so no new file is required.

(A) iosApp/Yoin/Data/Spotify/SpotifySource.swift

1. In `struct QueueEntry` (lines 484-490), add after `var coverURL: URL?`:
```swift
        /// The song's length (Spotify's duration_ms; 0 = unknown): the lyrics lookup weighs it.
        var durationMs: Int = 0
```
The default value keeps SpotifyQueueReadTests.swift:6 compiling.

2. In `queueEntries` (line 512), pass the duration:
```swift
            return QueueEntry(id: i, uri: "spotify:track:\(t.id ?? "")", title: t.name ?? "",
                              artist: (t.artists ?? []).compactMap(\.name).joined(separator: ", "),
                              coverURL: cover.flatMap { URL(string: $0.url) }, durationMs: t.durationMs ?? 0)
```

3. Below the SpotifySource class, at file scope, add:
```swift
extension SpotifySource.QueueEntry {
    /// The song's track id (its URI's last part), as SpotifyAudioOutput.externalTrack names it.
    var trackId: String? {
        let prefix = "spotify:track:"
        guard uri.hasPrefix(prefix) else { return nil }
        let id = String(uri.dropFirst(prefix.count))
        return id.isEmpty ? nil : id
    }

    /// This song as the engine will show it when Spotify gets there: same provider and id, so lyrics
    /// fetched for it are the ones Now Playing finds (key spotify:<id>). Album left empty: the lyrics
    /// lookup uses title, artist and length only.
    var playable: PlayableTrack? {
        guard let id = trackId else { return nil }
        return PlayableTrack(id: id, provider: SpotifyMapping.provider, title: title, artist: artist, album: "",
                             albumId: nil, durationSec: Double(durationMs) / 1000,
                             streamURL: SpotifyAudioOutput.streamURL(trackId: id), coverURL: coverURL)
    }
}
```

(B) iosApp/Yoin/NowPlaying/NowPlayingData.swift (`@MainActor @Observable final class`)

Add after `prefetchLyrics` (after line 279):
```swift
    /// What the next-song prefetch is for: the queue's next song, or, under Spotify's own song, whatever
    /// follows it in Spotify's queue (read again on each song and after an Add to queue).
    static func nextPrefetchKey(_ model: AppModel) -> String? {
        if let next = model.player.upNextTrack { return key(next) }
        guard let own = model.player.externalTrack, own.provider == SpotifyMapping.provider else { return nil }
        return "after:\(key(own))|\(model.spotifyQueueRevision)"
    }

    /// The next song's lyrics ahead of its turn, Now Playing open or not (Android NowPlayingViewModel's
    /// prefetch: shell-scoped, 4 s settle, two retries). Under Spotify's own song the next song is the
    /// first of Spotify's queue (GET me/player/queue), as Android's adopted mirror gives it.
    func prefetchNext(model: AppModel) async {
        try? await Task.sleep(for: .seconds(2))
        guard !Task.isCancelled else { return }
        if let next = model.player.upNextTrack {
            await prefetchWithRetry(next, client: model.session.client)
            return
        }
        guard let own = model.player.externalTrack, own.provider == SpotifyMapping.provider,
              !SpotifyEpisodes.isEpisode(own), let spotify = model.store.spotify,
              let entries = try? await spotify.playerQueue(), !Task.isCancelled else { return }
        // A read taken a beat before Spotify moved on still lists this song first: what follows it is next.
        guard let next = entries.drop(while: { $0.trackId == own.id }).first?.playable else { return }
        await prefetchWithRetry(next, client: nil)
    }

    /// prefetchLyrics, retried after 15 s and 30 s when the lookup failed (Android UP_NEXT_PREFETCH_RETRIES):
    /// a failure leaves `.none` in lyricsRetry, which prefetchLyrics' nil-guard never fetches again.
    private func prefetchWithRetry(_ t: PlayableTrack, client: SubsonicClient?) async {
        await prefetchLyrics(t, client: client)
        let k = Self.key(t)
        for wait in [15, 30] {
            guard lyricsRetry.contains(k), !Task.isCancelled else { return }
            try? await Task.sleep(for: .seconds(wait))
            guard !Task.isCancelled, lyricsRetry.contains(k) else { return }
            if t.provider == AppleMusicMapping.provider || t.provider == SpotifyMapping.provider {
                await loadProviderLyrics(t, k)
            } else if let client, t.provider == MediaRef.companion.PROVIDER_SUBSONIC {
                await loadLyrics(t, k, client)
            }
        }
    }
```
Everything this uses exists:
- `model.player`, `model.session.client`, `model.store.spotify` (LibraryStore.swift:172), `model.spotifyQueueRevision` (AppModel.swift:164).
- `SpotifySource` is @MainActor, and `playerQueue() async throws -> [QueueEntry]` is at line 501.
- `SpotifyEpisodes.isEpisode(_:)`.
- The private members `lyricsRetry`, `loadProviderLyrics(_:_:)` and `loadLyrics(_:_:_:)` are in the same class.
- `loadProviderLyrics`/`loadLyrics` already handle the retry path: they remove from lyricsRetry, set `.none` back to `.loading`, and check accountGeneration.

(C) iosApp/Yoin/App/YoinApp.swift: a small host view, always mounted, so lyrics load with Now Playing closed

Add a new private view in the same file:
```swift
/// Lyrics ahead of Now Playing, open or closed (Android keeps NowPlayingViewModel at shell scope,
/// YoinNavHost.kt:231): the playing song's at once, the next song's once this one has settled.
private struct LyricsPrefetchHost: View {
    let model: AppModel

    var body: some View {
        Color.clear
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .task(id: model.player.currentTrack.map(NowPlayingData.key)) {
                guard let t = model.player.currentTrack else { return }
                #if DEBUG
                // -lyricsCreditsDemo: prepare() puts the demo lyrics in an empty slot; leave it empty.
                if UserDefaults.standard.bool(forKey: "lyricsCreditsDemo") { return }
                #endif
                await NowPlayingData.shared.prefetchLyrics(t, client: model.session.client)
            }
            .task(id: NowPlayingData.nextPrefetchKey(model)) {
                await NowPlayingData.shared.prefetchNext(model: model)
            }
    }
}
```
In RootView.body, add `.background(LyricsPrefetchHost(model: model))` next to the existing `.background(GlassLabGestureInstaller())`. Pass `model` explicitly, because `.environment(model)` earlier in the chain does not reach a view added by `.background`. A separate view also keeps RootView.body from re-running on every queue change or song change.

`currentTrack` is `externalTrack ?? currentItem?.track` (PlayerEngine.swift:64). prefetchLyrics loads lyrics only (no heart, About or Gemini). prepare() later reuses what it stored: the line-155 guard returns early, and the saved automatic hit is read first.

(D) iosApp/Yoin/NowPlaying/NowPlayingView.swift

Delete the block at lines 434-440 (`// The next song's lyrics, once this one has settled ...` / `.task(id: model.player.upNextTrack.map(NowPlayingData.key)) { ... }`). LyricsPrefetchHost now does this, with the retry and the Spotify-own case. Leave the `.task(id: engineTrack...)` prepare() at 426-429 as it is. Do not change PlayerEngine.upNextTrack: its nil under Spotify's own song is tested (PlayerEngineTests.swift:189) and also drives the outro, scrobbling and the queue.

Optional product call, not needed for the preload: Android's outro also shows Spotify's next song. To match, store the PlayableTrack found in prefetchNext (e.g. `private(set) var spotifyNext: PlayableTrack?` in NowPlayingData, cleared when the key changes). Then use `data.upNext(model.player.upNextTrack ?? data.spotifyNext)` at NowPlayingView.swift:1891. This reverses the outro part of commit 30714943.

Remaining limits and risks:
1. This adds one GET me/player/queue per Spotify-own song and one per Add to queue. A 429 response becomes no prefetch via `try?`.
2. Spotify's queue can change after the read: an edit in Spotify, Smart Shuffle, or autoplay after the context ends. A wrong prefetch falls back to today's prepare() load.
3. Track relinking (queue id differs from the polled id) misses the key and also falls back.
4. No prefetch for Spotify's own song while Spotify is not Yoin's active source (store.spotify is nil). The queue sheet has the same limit.
5. Prefetched hits are saved as automatic hits for 30 days, including for songs that are then skipped. Android does the same.
6. Multiple windows on iPad or Mac would each read the queue.
7. Nothing runs while iOS has Yoin suspended in the background.

Device checks:
1. With Spotify's own song (polling), the PlayTrace log shows the queue read about 2 s after each detected song change. `lyrics["spotify:<next id>"]` is `.lines` before the next poll reports that song.
2. The queue's first id equals the id later polled, including on a 37i9dQZ playlist such as daylist.
3. With only the mini player showing, play through a song change, then open Now Playing: lyrics show with no loading beat.
4. Check the Yoin-queued (App Remote) hand-off with Now Playing both open and closed.
5. Check whether the 4 s poll and these tasks stop while Yoin is in the background.

**Check on the device**

- With Spotify's own song (App Remote refused, me/player polled every 4 s), confirm the PlayTrace log or a breakpoint shows `prefetchNext` reading me/player/queue about 2 s after each song change, and that `lyrics["spotify:<id>"]` for the first entry is `.lines` before the next poll reports that song.
- Check that the first `me/player/queue` entry's track id equals the id the next `me/player` poll and App Remote report for that song (no relinked or different ids), including on a Spotify-made playlist such as daylist (37i9dQZ…), whose items return 404 in Development Mode while the queue endpoint should still answer.
- On iPhone with the mini player only (Now Playing closed), play through a song change in the foreground, then open Now Playing: the lyrics should show at once, with no loading beat.
- Watch for HTTP 429 on me/player/queue during a long Spotify-own session.
- Confirm whether iOS suspends Yoin in the background while Spotify plays: does the 4 s poll stop, and do the RootView tasks run at all? This decides how much of the owner's complaint is the background case, which this fix cannot cover.
- Yoin-queued Spotify songs (App Remote connected after Yoin woke Spotify): confirm the next song's lyrics are ready at the hand-off with Now Playing both open and closed.
- Check that a queue entry's `duration_ms` matches the length the poll or App Remote reports, so the provider lookup picks the same version.
