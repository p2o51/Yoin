import AppKit
import SwiftUI

@main
struct YoinMacApp: App {
    @NSApplicationDelegateAdaptor(MacAppDelegate.self) private var appDelegate
    @State private var model: MacModel

    init() {
        MacFonts.register()
        MacSettings.applyAppearance()
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

        Window("Mini Player", id: MacMiniPlayer.windowID) {
            MacMiniPlayer().environment(model)
        }
        .windowStyle(.hiddenTitleBar)
        .windowResizability(.contentSize)
        .windowBackgroundDragBehavior(.enabled)
        .defaultPosition(.topTrailing)

        MenuBarExtra {
            MacMenuBarCard().environment(model)
        } label: {
            Image("yoin.music_note")
        }
        .menuBarExtraStyle(.window)

        Settings {
            MacSettingsView().environment(model)
        }
    }
}

/// The Controls menu: every playback action and its key.
struct MacCommands: Commands {
    let model: MacModel
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissWindow) private var dismissWindow
    @AppStorage(MacMiniPlayer.floatsKey) private var miniFloats = true

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
                .keyboardShortcut("d", modifiers: .command)
                .disabled(p.currentTrack == nil)
            Divider()
            Button("Now Playing") { model.toggleNowPlaying() }
                .keyboardShortcut("p", modifiers: [.command, .shift])
                .disabled(p.currentTrack == nil)
            Button(model.npMode == .full ? "Leave Full Window" : "Full Window Lyrics") { model.toggleFullNowPlaying() }
                .keyboardShortcut("l", modifiers: .command)
                .disabled(p.currentTrack == nil)
            Button(model.focus ? "Leave Focus" : "Focus") { model.toggleFocusFromAnywhere() }
                .keyboardShortcut("f", modifiers: .command)
                .disabled(typing || p.currentTrack == nil)
            Button(model.miniOpen ? "Close Mini Player" : "Mini Player") {
                if model.miniOpen { dismissWindow(id: MacMiniPlayer.windowID) } else { openWindow(id: MacMiniPlayer.windowID) }
            }
            .keyboardShortcut("m", modifiers: [.command, .option])
            .disabled(!model.session.isSignedIn)
            Toggle("Float Mini Player on Top", isOn: $miniFloats)
            if model.session.isSpotify, model.spotifyDevice != nil {
                Button("Play on This Mac") { model.spotifyPlayHere() }
            }
            if model.session.profiles.count > 1 {
                Menu("Switch Account") {
                    ForEach(model.session.profiles) { p in
                        Button(p.title) { model.switchAccount(p.id) }
                            .disabled(p.id == model.session.activeId && model.session.isSignedIn)
                    }
                }
            }
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
