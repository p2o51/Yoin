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
        try? await model.session.signIn(url: server, username: d.string(forKey: "qaUser") ?? "qa",
                                        password: d.string(forKey: "qaPassword") ?? "qa")
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
            case "album", "artist":
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
            default: break
            }
        }
        #endif
    }
}
