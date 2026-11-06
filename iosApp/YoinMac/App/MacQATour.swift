#if DEBUG
import AppKit
import MusicKit
import SwiftUI

/// `-macScreen fulltour` (debug, QA): every main screen once, for each account (Subsonic, Spotify, Apple Music
/// when its access is already granted — no authorization dialog is asked for), with nothing played. Each step
/// is logged as "TOUR …" (subsystem com.gpo.yoin.mac). The account in use at launch is back in use at the end.
@MainActor
enum MacQATour {
    static let settingsTab = Notification.Name("YoinQASettingsTab")
    static let accountPage = Notification.Name("YoinQAAccountPage")
    static let albumPage = Notification.Name("YoinQAAlbumPage")

    static func run(_ model: MacModel, openWindow: OpenWindowAction, dismissWindow: DismissWindowAction,
                    openSettings: OpenSettingsAction) async {
        guard MacLaunch.screen == "fulltour" else { return }
        func step(_ s: String, wait: Double = 2.5) async {
            PlayTrace.log("TOUR \(s)")
            try? await Task.sleep(for: .milliseconds(Int(wait * 1000)))
        }
        await step("start", wait: 4)
        let original = model.session.activeId
        var profiles = model.session.profiles.filter { $0.service != .appleMusic }
        if MusicAuthorization.currentStatus == .authorized,
           let apple = model.session.profiles.first(where: { $0.service == .appleMusic }) {
            profiles.append(apple)
        }
        for p in profiles {
            if p.id != model.session.activeId {
                model.switchAccount(p.id)
                await step("switched to \(p.service.rawValue)", wait: 5)
            }
            let tag = p.service.rawValue
            model.select(.home)
            await model.library.loadHome()
            await step("\(tag) home")
            model.select(.library)
            for k in MacModel.LibraryKind.allCases {
                model.libraryKind = k
                await step("\(tag) library \(k.rawValue)", wait: 3)
            }
            let albums = model.library.home.recentlyAdded + model.library.home.recentlyPlayed + model.library.albums
            if let a = albums.first {
                model.open(.album(await model.library.fullAlbum(a)))
                await step("\(tag) album songs")
                NotificationCenter.default.post(name: albumPage, object: 1)
                await step("\(tag) album scrapbook")
                NotificationCenter.default.post(name: albumPage, object: 0)
                if let id = a.artistId {
                    model.open(.artist(id: id, name: a.artist))
                    await step("\(tag) artist", wait: 3)
                }
                model.closeDetail()
                model.closeDetail()
            }
            await model.library.loadPlaylists()
            if let pl = model.library.playlists.first {
                model.open(.playlist(id: pl.id.rawId, title: pl.title))
                await step("\(tag) playlist", wait: 3)
                model.closeDetail()
            }
            model.searchOpen = true
            await step("\(tag) search")
            model.searchOpen = false
            model.toggleNowPlaying()
            await step("\(tag) np panel")
            model.expandNowPlaying()
            await step("\(tag) np full")
            model.toggleFocus()
            await step("\(tag) np focus")
            model.toggleFocus()
            model.back()
            model.back()
            openWindow(id: MacMiniPlayer.windowID)
            await step("\(tag) mini player")
            dismissWindow(id: MacMiniPlayer.windowID)
            openSettings()
            for t in ["accounts", "storage", "general", "sync", "backups", "neodb", "about"] {
                NotificationCenter.default.post(name: settingsTab, object: t)
                await step("\(tag) settings \(t)", wait: 1.5)
            }
            NotificationCenter.default.post(name: settingsTab, object: "accounts")
            NotificationCenter.default.post(name: accountPage, object: model.session.activeId)
            await step("\(tag) account page")
            NotificationCenter.default.post(name: accountPage, object: nil)
            NSApp.windows.first { $0.identifier?.rawValue.contains("Settings") == true || $0.title == "Accounts" }?.close()
        }
        if let original, original != model.session.activeId {
            model.switchAccount(original)
            await step("back to the account in use at launch", wait: 4)
        }
        model.select(.home)
        PlayTrace.log("TOUR done")
    }
}
#endif
