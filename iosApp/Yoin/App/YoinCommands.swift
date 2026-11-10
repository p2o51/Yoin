import SwiftUI

/// Keyboard commands for iPad with a keyboard and for the iPad app on a Mac (docs/ipad.md; the shortcuts
/// YoinMac's Controls menu uses, so the two read the same). Holding ⌘ on iPad lists them.
struct YoinCommands: Commands {
    let model: AppModel

    var body: some Commands {
        CommandMenu("Controls") {
            let p = model.player
            let idle = p.currentTrack == nil
            Button(p.isPlaying ? "Pause" : "Play") { p.togglePlayPause() }
                .keyboardShortcut(.space, modifiers: [])
                .disabled(idle || model.searchActive)
            Button("Next") { p.next() }
                .keyboardShortcut(.rightArrow, modifiers: .command)
                .disabled(idle)
            Button("Previous") { p.previous() }
                .keyboardShortcut(.leftArrow, modifiers: .command)
                .disabled(idle)
            Divider()
            Button(p.shuffle ? "Shuffle Off" : "Shuffle On") { p.setShuffle(!p.shuffle) }
                .keyboardShortcut("s", modifiers: [.command, .option])
                .disabled(idle)
            Menu("Repeat") {
                Button("All") { p.setRepeat(.all) }
                Button("One") { p.setRepeat(.one) }
                Button("Off") { p.setRepeat(.off) }
            }
            .disabled(idle)
            Divider()
            Button("Like") {
                if let t = p.currentTrack {
                    NowPlayingData.shared.toggleFavorite(t, client: model.session.client, apple: model.store.apple)
                }
            }
            .keyboardShortcut("d", modifiers: .command)
            .disabled(p.externalTrack != nil || idle)
            Button("Now Playing") { model.toggleNowPlayingFromKeyboard() }
                .keyboardShortcut("p", modifiers: [.command, .shift])
                .disabled(idle)
            Divider()
            Button("Search") { model.openSearch(from: .bar) }
                .keyboardShortcut("k", modifiers: .command)
            Button("Back") { model.backFromKeyboard() }
                .keyboardShortcut("[", modifiers: .command)
            Button("Settings") { model.openSettings() }
                .keyboardShortcut(",", modifiers: .command)
        }
        CommandMenu("Go") {
            Button("Home") { model.select(.home) }
                .keyboardShortcut("1", modifiers: .command)
            Button("Library") { model.select(.library) }
                .keyboardShortcut("2", modifiers: .command)
            Button("All Memories") { model.path.append(.allMemories) }
                .keyboardShortcut("3", modifiers: .command)
        }
    }
}

extension AppModel {
    /// ⌘⇧P: Now Playing opens (the iPad panel when there is room) or closes.
    func toggleNowPlayingFromKeyboard() {
        if npExpanded { setNowPlaying(expanded: false); return }
        if isPad, npPanel { withAnimation(Yoin.spatial) { npPanel = false }; return }
        if isPad { openNowPlayingOnPad() } else { setNowPlaying(expanded: true) }
    }

    /// ⌘[: whatever is in front goes first (search, Now Playing), then the pushed page.
    func backFromKeyboard() {
        if searchActive { withAnimation(Yoin.spatial) { searchActive = false }; return }
        if npExpanded { setNowPlaying(expanded: false); return }
        if !path.isEmpty { back(); return }
        if isPad, padSelection != nil { withAnimation(Yoin.spatial) { padSelection = nil } }
    }
}
