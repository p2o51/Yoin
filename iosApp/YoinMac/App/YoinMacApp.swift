import AppKit
import SwiftUI

@main
struct YoinMacApp: App {
    @State private var model: MacModel

    init() {
        MacFonts.register()
        MacQA.applyAppearance()
        _model = State(initialValue: MacModel())
    }

    var body: some Scene {
        Window("Yoin", id: "main") {
            MacGate()
                .environment(model)
                .onAppear { MacQA.applyWindowSize() }
        }
        .windowStyle(.hiddenTitleBar)
        .windowBackgroundDragBehavior(.enabled)
        .defaultSize(width: 1280, height: 820)
        .commands { MacCommands(model: model) }

        Settings {
            MacSettingsView().environment(model)
        }
    }
}

/// The Controls menu: every playback action and its key.
struct MacCommands: Commands {
    let model: MacModel

    var body: some Commands {
        CommandGroup(replacing: .newItem) {}
        CommandMenu("Controls") {
            let p = model.player
            let typing = model.searchOpen || !model.session.isSignedIn
            Button(p.isPlaying ? "Pause" : "Play") { p.togglePlayPause() }
                .keyboardShortcut(.space, modifiers: [])
                .disabled(typing || p.currentTrack == nil)
            Button("Next") { p.next() }
                .keyboardShortcut(.rightArrow, modifiers: .command)
                .disabled(typing || p.currentTrack == nil)
            Button("Previous") { p.previous() }
                .keyboardShortcut(.leftArrow, modifiers: .command)
                .disabled(typing || p.currentTrack == nil)
            Divider()
            Button(p.shuffle ? "Shuffle Off" : "Shuffle On") { p.setShuffle(!p.shuffle) }
                .disabled(p.currentTrack == nil)
            Menu("Repeat") {
                Button("All") { p.setRepeat(.all) }
                Button("One") { p.setRepeat(.one) }
                Button("Off") { p.setRepeat(.off) }
            }
            Divider()
            Button(model.isLikedCurrent() ? "Unlike" : "Like") { model.toggleLikeCurrent() }
                .keyboardShortcut("l", modifiers: .command)
                .disabled(p.currentTrack == nil)
            Button("Now Playing") { model.toggleNowPlaying() }
                .keyboardShortcut("p", modifiers: [.command, .shift])
                .disabled(p.currentTrack == nil)
            Divider()
            Button("Search") { withAnimation(Yoin.effects) { model.searchOpen = true } }
                .keyboardShortcut("k", modifiers: .command)
                .disabled(!model.session.isSignedIn)
            Button("Back") { model.back() }
                .keyboardShortcut(.escape, modifiers: [])
                .disabled(!model.canGoBack)
        }
    }
}
