import Foundation

/// A new note's unsaved draft, kept outside Now Playing (Android keeps `noteDraft` on
/// NowPlayingViewModel): collapsing Now Playing unmounts its view, and the words, the moment they are
/// filed at and the song they are for used to go with it. One draft per account (scope), in
/// UserDefaults, so a relaunch keeps it too.
@MainActor
final class NoteDraftStore {
    static let shared = NoteDraftStore(defaults: .standard)

    struct Draft: Codable, Equatable {
        var words: String
        var anchorMs: Int64?
        var target: Target?

        struct Target: Codable, Equatable {
            var provider: String
            var rawId: String
            var title: String
            var artist: String
        }
    }

    private let defaults: UserDefaults
    private let key: String
    private var drafts: [String: Draft]

    init(defaults: UserDefaults, key: String = "yoin.noteDrafts") {
        self.defaults = defaults
        self.key = key
        drafts = defaults.data(forKey: key).flatMap { try? JSONDecoder().decode([String: Draft].self, from: $0) } ?? [:]
    }

    func draft(scope: String) -> Draft? { drafts[scope] }

    /// Keeps [draft] for [scope]; nil or blank words drop it.
    func save(_ draft: Draft?, scope: String) {
        let kept = draft.flatMap { $0.words.contains(where: { !$0.isWhitespace }) ? $0 : nil }
        guard drafts[scope] != kept else { return }
        drafts[scope] = kept
        if let data = try? JSONEncoder().encode(drafts) { defaults.set(data, forKey: key) }
    }
}

extension NoteDraftStore.Draft.Target {
    init(_ t: NoteTarget) { self.init(provider: t.provider, rawId: t.rawId, title: t.title, artist: t.artist) }
    var noteTarget: NoteTarget { NoteTarget(provider: provider, rawId: rawId, title: title, artist: artist) }
}
