import SwiftUI

/// Keyboard commands for iPad with a keyboard and for the iPad app on a Mac (docs/ipad.md; the shortcuts
/// YoinMac's Controls menu uses, so the two read the same). Holding ⌘ on iPad lists them.
struct YoinCommands: Commands {
    let model: AppModel

    var body: some Commands {
        // One window (UIApplicationSupportsMultipleScenes is off, docs/ipad.md): no New Window (⌘N).
        CommandGroup(replacing: .newItem) {}
        CommandMenu("Controls") {
            let p = model.player
            let idle = p.currentTrack == nil
            let textInputActive = TextInputFocus.shared.isActive || model.searchActive
            Button(p.isPlaying ? "Pause" : "Play") {
                guard !TextInputFocus.currentResponderIsTextInput(), !model.searchActive else { return }
                p.togglePlayPause()
            }
                .keyboardShortcut(.space, modifiers: [])
                .disabled(idle || textInputActive)
            Button(String(localized: "playback.next", defaultValue: "Next")) { p.next() }
                .keyboardShortcut(.rightArrow, modifiers: .command)
                .disabled(idle)
            Button("Previous") { p.previous() }
                .keyboardShortcut(.leftArrow, modifiers: .command)
                .disabled(idle)
            Button("Skip Forward 5 Seconds") {
                guard !TextInputFocus.currentResponderIsTextInput(), !model.searchActive else { return }
                model.seekFromKeyboard(by: 5)
            }
                .keyboardShortcut(.rightArrow, modifiers: [])
                .disabled(idle || textInputActive)
            Button("Skip Backward 5 Seconds") {
                guard !TextInputFocus.currentResponderIsTextInput(), !model.searchActive else { return }
                model.seekFromKeyboard(by: -5)
            }
                .keyboardShortcut(.leftArrow, modifiers: [])
                .disabled(idle || textInputActive)
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
            Button("Focus Cover") { model.toggleFocusFromKeyboard() }
                .keyboardShortcut("f", modifiers: .command)
                .disabled(idle)
            Button("Lyrics") { model.toggleLyricsFromKeyboard() }
                .keyboardShortcut("l", modifiers: .command)
                .disabled(idle)
            Divider()
            Button("Search") { model.openSearch(from: .bar) }
                .keyboardShortcut("k", modifiers: .command)
            Button("Back") { model.backFromKeyboard() }
                .keyboardShortcut("[", modifiers: .command)
            Button("Dismiss") { model.backFromKeyboard() }
                .keyboardShortcut(.escape, modifiers: [])
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

    /// ⌘[ (Android #200, ShellBackOrder): whatever covers the window goes first (search, Full Now
    /// Playing, a page pushed over the shell), then Home's edit mode, then the surfaces beside one another
    /// close newest first: the Now Playing side panel, the detail column (its stack, then the column),
    /// Memories. Now Playing steps back inside itself before it closes (Android NowPlayingOverlayHost's
    /// layered back): lyrics opened from focus to focus, focus or the expanded page to the resting player,
    /// then Full to its side panel where one fits, then closed.
    func backFromKeyboard() {
        let wideColumn = isPad && padWidth >= 840
        let step = ShellBackOrder.step(
            searchActive: searchActive,
            fullNowPlaying: npExpanded,
            // Two-pane Settings covers the whole window, as a pushed page does.
            pageCoversShell: settingsSplitShown || (!wideColumn && !path.isEmpty),
            panelOpen: isPad && npPanel,
            columnOpen: wideColumn && (padSelection != nil || !path.isEmpty),
            memoriesOpen: section == .home && memories.isOpen,
            homeEditing: section == .home && homeEdit?.isEditing == true,
            staleSelection: isPad && padSelection != nil,
            recency: backRecency
        )
        switch step {
        case .closeSearch: withAnimation(Yoin.spatial) { searchActive = false }
        case .closeFullNowPlaying:
            if npStageBack[.full]?.step() == true { return }
            if isPad, padPanelFits { npFullToPanel() } else { setNowPlaying(expanded: false) }
        case .popPage, .column: back()
        case .closePanel:
            if npStageBack[.panel]?.step() == true { return }
            withAnimation(Yoin.spatial) { npPanel = false }
        case .closeMemories: memories.close()
        case .exitHomeEdit: homeEdit?.exit()
        case .clearSelection: withAnimation(Yoin.spatial) { padSelection = nil }
        case .none: break
        }
    }

    /// The opening order of the side-by-side surfaces (ShellBackOrder.noted).
    func noteBackSurface(_ surface: ShellBackSurface, open: Bool) {
        backRecency = ShellBackOrder.noted(backRecency, surface, open: open)
    }

    /// Now Playing is one surface whether it is the panel or Full: noted when it opens or closes. Going
    /// Full from the panel (the panel steps aside a frame first) notes it again, as entering it further.
    func noteNowPlayingSurface() {
        let open = npPanel || npExpanded
        guard open != nowPlayingNoted else { return }
        nowPlayingNoted = open
        noteBackSurface(.nowPlaying, open: open)
    }
}
