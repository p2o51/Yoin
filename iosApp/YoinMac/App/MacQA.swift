import AppKit
import SwiftUI

/// Debug launch arguments for silent QA runs and screenshots (Release ignores them):
/// `-qaServer URL -qaUser NAME -qaPassword PW` signs in when signed out (a local fake server);
/// `-macScreen library|album|artist|np-panel|np-full|np-focus|search`; `-macAppearance light|dark`;
/// `-macWindowSize 1280x820`.
@MainActor
enum MacQA {
    static func applyAppearance() {
        #if DEBUG
        switch UserDefaults.standard.string(forKey: "macAppearance") {
        case "dark": NSApplication.shared.appearance = NSAppearance(named: .darkAqua)
        case "light": NSApplication.shared.appearance = NSAppearance(named: .aqua)
        default: break
        }
        #endif
    }

    static func applyWindowSize() {
        #if DEBUG
        guard let raw = UserDefaults.standard.string(forKey: "macWindowSize") else { return }
        let parts = raw.split(separator: "x").compactMap { Double($0) }
        guard parts.count == 2 else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            guard let w = NSApplication.shared.windows.first(where: { $0.isVisible && $0.canBecomeMain }) else { return }
            var f = w.frame
            f.origin.y += f.height - parts[1]
            f.size = NSSize(width: parts[0], height: parts[1])
            w.setFrame(f, display: true)
        }
        #endif
    }

    static func autoSignIn(_ model: MacModel) async {
        #if DEBUG
        let d = UserDefaults.standard
        guard !model.session.isSignedIn, let server = d.string(forKey: "qaServer") else { return }
        // A late answer never replaces an account signed in meanwhile.
        let token = model.authToken
        try? await model.session.signIn(url: server, username: d.string(forKey: "qaUser") ?? "qa",
                                        password: d.string(forKey: "qaPassword") ?? "qa") {
            token == model.authToken && !Task.isCancelled && !model.session.isSignedIn
        }
        #endif
    }

    /// `-macScreen tour`: the landing first (the shell's steps follow once it goes).
    static func endTourLanding(_ model: MacModel) async {
        #if DEBUG
        guard MacLaunch.screen == "tour", model.landing != nil else { return }
        try? await Task.sleep(for: .seconds(7))
        withAnimation(Yoin.spatial) { model.landing = nil }
        #endif
    }

    static func applyScreen(_ model: MacModel) {
        #if DEBUG
        guard let screen = MacLaunch.screen else { return }
        Task {
            await model.library.loadHome()
            for _ in 0..<50 where model.library.home.recentlyAdded.isEmpty { try? await Task.sleep(for: .milliseconds(100)) }
            let albums = model.library.home.recentlyAdded
            switch screen {
            case "library":
                model.select(.library)
            case "home-activity":
                // A few visits first (album, artist, playlist), so Activities has something; then Home.
                await model.library.loadPlaylists()
                for a in albums.prefix(2) {
                    let full = await model.library.fullAlbum(a)
                    model.open(.album(full))
                    try? await Task.sleep(for: .seconds(1.2))
                    if let id = full.artistId {
                        model.open(.artist(id: id, name: full.artist))
                        try? await Task.sleep(for: .seconds(1.2))
                    }
                }
                if let p = model.library.playlists.first {
                    model.open(.playlist(id: p.id.rawId, title: p.title))
                    try? await Task.sleep(for: .seconds(1.2))
                }
                model.closeDetail()
                model.closeDetail()
                model.closeDetail()
                model.select(.home)
            case "library-all", "library-favorites":
                model.libraryKind = screen == "library-all" ? .all : .favorites
                model.select(.library)
            case "search-songs":
                // ⌘K with a filter: a type's full page and its Load More.
                model.searchQuery = "a"
                model.searchKind = .songs
                withAnimation { model.searchOpen = true }
            case "queue":
                // Now Playing with songs the listener queued, the queue open (Next in queue over Next up).
                guard albums.count > 1 else { return }
                let full = await model.library.fullAlbum(albums[0])
                model.play(album: full, startAt: 0)
                try? await Task.sleep(for: .milliseconds(800))
                model.player.pause()
                let other = await model.library.fullAlbum(albums[1])
                for t in other.tracks.prefix(2) { model.addToQueue(PlaylistEntry(album: other, track: t)) }
                model.toggleNowPlaying()
                try? await Task.sleep(for: .seconds(1))
                NotificationCenter.default.post(name: Notification.Name("YoinQAQueue"), object: nil)
            case "connect-step":
                // The welcome guide's picks after it closes: Step 1 of 2 (nothing is asked of a service until clicked).
                model.startConnecting([.spotify, .appleMusic])
            case "backups":
                // A backup of the first playlist on this QA copy's own store, its versions open in Settings › Backups.
                await model.library.loadPlaylists()
                if let p = model.library.playlists.first, let id = model.session.activeId,
                   let source = MacBackupAccounts.shared.source(id) {
                    _ = try? await PlaylistBackups.shared.backUp(rawId: p.id.rawId, from: source)
                    source.close()
                }
            case "album", "artist", "scrapbook":
                if let a = albums.first {
                    let full = await model.library.fullAlbum(a)
                    if screen == "artist", let id = full.artistId { model.open(.artist(id: id, name: full.artist)) }
                    else { model.open(.album(full)) }
                    if let t = full.tracks.dropFirst().first { model.selectedTrack = t.id.rawId }
                }
            case "np-panel", "np-full", "np-focus":
                guard let a = model.library.home.recentlyPlayed.first ?? albums.first else { return }
                let full = await model.library.fullAlbum(a)
                model.play(album: full, startAt: min(1, max(0, full.tracks.count - 1)))
                try? await Task.sleep(for: .milliseconds(800))
                model.player.seek(to: 52)
                model.toggleNowPlaying()
                if screen != "np-panel" { model.expandNowPlaying() }
                if screen == "np-focus" { model.toggleFocus() }
            case "search":
                withAnimation { model.searchOpen = true }
            case "columns", "columns-artist", "columns-playlist", "columns-scrapbook", "columns-search":
                // 51's three-column window (1106d): Library, a detail page and the Now Playing panel at once.
                model.libraryKind = .albums
                model.select(.library)
                await model.library.loadAlbums()
                if let a = albums.first ?? model.library.albums.first {
                    let full = await model.library.fullAlbum(a)
                    model.play(album: full, startAt: min(1, max(0, full.tracks.count - 1)))
                    try? await Task.sleep(for: .milliseconds(800))
                    model.player.pause()
                    model.player.seek(to: 108)
                    switch screen {
                    case "columns-artist":
                        if let id = full.artistId { model.open(.artist(id: id, name: full.artist)) }
                    case "columns-playlist":
                        await model.library.loadPlaylists()
                        if let p = model.library.playlists.first { model.open(.playlist(id: p.id.rawId, title: p.title)) }
                    default:
                        model.open(.album(full))
                    }
                    model.toggleNowPlaying()
                    try? await Task.sleep(for: .seconds(1.5))
                    if screen == "columns-scrapbook" { NotificationCenter.default.post(name: Notification.Name("YoinQAAlbumPage"), object: 1) }
                    if screen == "columns-search" { withAnimation { model.searchOpen = true } }
                }
            case "tour":
                // Screenshots of one launch: the landing, then an album's scrapbook, then Library (its sort from
                // `-librarySort.Albums …`).
                if let a = model.library.home.recentlyPlayed.first ?? albums.first {
                    model.open(.album(await model.library.fullAlbum(a)))
                }
                try? await Task.sleep(for: .seconds(8))
                model.closeDetail()
                model.libraryKind = .albums
                model.select(.library)
            default: break
            }
        }
        #endif
    }
}
