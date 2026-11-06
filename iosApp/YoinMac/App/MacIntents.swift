import AppIntents
import Foundation

// Shortcuts and Siri on the Mac (App Intents in the app itself: no extension, no new entitlement): play /
// pause, next, previous, like the song playing, and play an album or playlist of the account in use by name.

/// An album of the account in use, as Shortcuts names it.
struct AlbumEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Album"
    static let defaultQuery = AlbumQuery()
    var id: String
    var title: String
    var artist: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(title)", subtitle: "\(artist)") }
}

struct AlbumQuery: EntityStringQuery {
    @MainActor private func all() async -> [AlbumEntity] {
        guard let lib = MacModel.current?.library else { return [] }
        await lib.loadAlbums()
        let scope = MacModel.current?.session.accountKey ?? ""
        return lib.albums.map { AlbumEntity(id: ScopedId.make(scope, $0.id.rawId), title: $0.title, artist: $0.artist) }
    }

    func entities(for identifiers: [String]) async throws -> [AlbumEntity] {
        await all().filter { identifiers.contains($0.id) }
    }

    func entities(matching string: String) async throws -> [AlbumEntity] {
        let q = string.trimmingCharacters(in: .whitespaces)
        return await all().filter { $0.title.localizedCaseInsensitiveContains(q) || $0.artist.localizedCaseInsensitiveContains(q) }
    }

    func suggestedEntities() async throws -> [AlbumEntity] { Array(await all().prefix(30)) }
}

/// A playlist of the account in use.
struct PlaylistEntity: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Playlist"
    static let defaultQuery = PlaylistQuery()
    var id: String
    var title: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(title)") }
}

struct PlaylistQuery: EntityStringQuery {
    @MainActor private func all() async -> [PlaylistEntity] {
        guard let lib = MacModel.current?.library else { return [] }
        await lib.loadPlaylists()
        let scope = MacModel.current?.session.accountKey ?? ""
        return lib.playlists.map { PlaylistEntity(id: ScopedId.make(scope, $0.id.rawId), title: $0.title) }
    }

    func entities(for identifiers: [String]) async throws -> [PlaylistEntity] {
        await all().filter { identifiers.contains($0.id) }
    }

    func entities(matching string: String) async throws -> [PlaylistEntity] {
        let q = string.trimmingCharacters(in: .whitespaces)
        return await all().filter { $0.title.localizedCaseInsensitiveContains(q) }
    }

    func suggestedEntities() async throws -> [PlaylistEntity] { Array(await all().prefix(30)) }
}

/// An entity's id carries the account it came from ("<account>\u{1F}<raw id>"): a shortcut saved for one
/// account never plays another account's album or playlist that happens to share the raw id.
enum ScopedId {
    static func make(_ scope: String, _ raw: String) -> String { "\(scope)\u{1F}\(raw)" }

    /// The raw id when [id] belongs to the account in use; else nil.
    @MainActor static func raw(_ id: String, in m: MacModel) -> String? {
        let parts = id.split(separator: "\u{1F}", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2, String(parts[0]) == m.session.accountKey else { return nil }
        return String(parts[1])
    }
}

struct YoinIntentError: Error, CustomLocalizedStringResourceConvertible {
    var localizedStringResource: LocalizedStringResource
}

@MainActor
private func model() throws -> MacModel {
    guard let m = MacModel.current, m.session.isSignedIn else {
        throw YoinIntentError(localizedStringResource: "Open Yoin and sign in first")
    }
    return m
}

struct PlayPauseIntent: AppIntent {
    static let title: LocalizedStringResource = "Play or Pause"
    static let description = IntentDescription("Plays or pauses what Yoin is playing.")

    @MainActor func perform() async throws -> some IntentResult {
        let m = try model()
        guard m.player.currentTrack != nil else { throw YoinIntentError(localizedStringResource: "Nothing is playing") }
        m.player.togglePlayPause()
        return .result()
    }
}

struct NextTrackIntent: AppIntent {
    static let title: LocalizedStringResource = "Next Song"
    @MainActor func perform() async throws -> some IntentResult {
        try model().player.next()
        return .result()
    }
}

struct PreviousTrackIntent: AppIntent {
    static let title: LocalizedStringResource = "Previous Song"
    @MainActor func perform() async throws -> some IntentResult {
        try model().player.previous()
        return .result()
    }
}

struct LikeCurrentSongIntent: AppIntent {
    static let title: LocalizedStringResource = "Like the Current Song"
    static let description = IntentDescription("Likes the song Yoin is playing (Apple Music: adds it to the library).")

    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        let m = try model()
        guard let t = m.player.currentTrack else { throw YoinIntentError(localizedStringResource: "Nothing is playing") }
        if !m.library.isStarred(songId: t.id, fallback: false) {
            do { try await m.library.like(songId: t.id) } catch {
                throw YoinIntentError(localizedStringResource: "Couldn't like \(t.title)")
            }
        }
        return .result(dialog: "Liked \(t.title)")
    }
}

struct PlayAlbumIntent: AppIntent {
    static let title: LocalizedStringResource = "Play Album"
    @Parameter(title: "Album") var album: AlbumEntity

    @MainActor func perform() async throws -> some IntentResult {
        let m = try model()
        guard let raw = ScopedId.raw(album.id, in: m), let a = m.library.albums.first(where: { $0.id.rawId == raw }) else {
            throw YoinIntentError(localizedStringResource: "That album isn't in the account in use")
        }
        // Awaited through the load, under the request / account guards play(album:) uses.
        guard await m.playAlbum(a) else { throw YoinIntentError(localizedStringResource: "Couldn't play that album") }
        return .result()
    }
}

struct PlayPlaylistIntent: AppIntent {
    static let title: LocalizedStringResource = "Play Playlist"
    @Parameter(title: "Playlist") var playlist: PlaylistEntity

    @MainActor func perform() async throws -> some IntentResult {
        let m = try model()
        guard let raw = ScopedId.raw(playlist.id, in: m) else {
            throw YoinIntentError(localizedStringResource: "That playlist isn't in the account in use")
        }
        guard await m.playPlaylist(raw) else { throw YoinIntentError(localizedStringResource: "Couldn't play that playlist") }
        return .result()
    }
}

/// The Shortcuts tiles' icons are SF Symbol names: the system draws them, and it takes no app symbol there.
struct YoinShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: PlayPauseIntent(),
                    phrases: ["Play or pause \(.applicationName)", "\(.applicationName) 播放或暂停"],
                    shortTitle: "Play or Pause", systemImageName: "playpause")
        AppShortcut(intent: NextTrackIntent(),
                    phrases: ["Next song in \(.applicationName)", "\(.applicationName) 下一首"],
                    shortTitle: "Next Song", systemImageName: "forward")
        AppShortcut(intent: PreviousTrackIntent(),
                    phrases: ["Previous song in \(.applicationName)", "\(.applicationName) 上一首"],
                    shortTitle: "Previous Song", systemImageName: "backward")
        AppShortcut(intent: LikeCurrentSongIntent(),
                    phrases: ["Like this song in \(.applicationName)", "\(.applicationName) 喜欢这首歌"],
                    shortTitle: "Like Song", systemImageName: "heart")
        AppShortcut(intent: PlayAlbumIntent(),
                    phrases: ["Play \(\.$album) in \(.applicationName)", "用 \(.applicationName) 播放专辑 \(\.$album)"],
                    shortTitle: "Play Album", systemImageName: "square.stack")
        AppShortcut(intent: PlayPlaylistIntent(),
                    phrases: ["Play the playlist \(\.$playlist) in \(.applicationName)", "用 \(.applicationName) 播放歌单 \(\.$playlist)"],
                    shortTitle: "Play Playlist", systemImageName: "music.note.list")
    }
}
