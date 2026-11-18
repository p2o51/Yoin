import AppIntents

/// Discoverable Shortcuts / Spotlight actions scoped strictly to the main `Yoin` target
/// (never shared with the `YoinWidgets` extension).
struct PlayPausePlaybackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play or Pause"
    static let description = IntentDescription("Toggle playback in Yoin.")
    static let isDiscoverable = true
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult {
        AppModel.shared.player.togglePlayPause()
        return .result()
    }
}

struct NextTrackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Next Song"
    static let description = IntentDescription("Skip to the next song in Yoin.")
    static let isDiscoverable = true
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult {
        AppModel.shared.player.next()
        return .result()
    }
}

struct PreviousTrackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Previous Song"
    static let description = IntentDescription("Go back to the previous song in Yoin.")
    static let isDiscoverable = true
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult {
        AppModel.shared.player.previous()
        return .result()
    }
}

struct YoinShortcutsProvider: AppShortcutsProvider {
    @AppShortcutsBuilder
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: PlayPausePlaybackIntent(),
            phrases: [
                "Play or pause in \(.applicationName)",
                "Toggle playback in \(.applicationName)",
            ],
            shortTitle: "Play or Pause",
            systemImageName: "playpause"
        )
        AppShortcut(
            intent: NextTrackIntent(),
            phrases: [
                "Next song in \(.applicationName)",
                "Skip track in \(.applicationName)",
            ],
            shortTitle: "Next Song",
            systemImageName: "forward"
        )
        AppShortcut(
            intent: PreviousTrackIntent(),
            phrases: [
                "Previous song in \(.applicationName)",
                "Go back in \(.applicationName)",
            ],
            shortTitle: "Previous Song",
            systemImageName: "backward"
        )
    }
}
