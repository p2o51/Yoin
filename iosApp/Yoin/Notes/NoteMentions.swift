import Foundation
import Observation
import SwiftUI
import YoinShared

/// Where a mention takes the reader (Android NoteDestination, by kind).
enum NoteMentionTarget: Equatable {
    /// An album: its scrapbook (page 2). [id] is the account's own album id.
    case albumScrapbook(provider: String, id: String, title: String, artist: String?)
    /// A song: its album opens on the song's scrapbook piece.
    case track(provider: String, id: String, title: String, artist: String?)
    case artist(provider: String, id: String, name: String)
}

/// The account mentions are read under: its provider (whose ids it understands) and its history scope.
struct NoteMentionAccount: Equatable, Sendable {
    var provider: String
    var scope: String
}

/// A name the account knows (a play or a note), to match another provider's mention against.
struct NoteMentionName: Sendable {
    var id: String
    var title: String
    var artist: String?
    var album: String?
}

/**
 * The mentions in drawn notes for the account in use (Android NoteMentionController + LocalMentionResolver,
 * owner 2026-11-22). A mention on the account's own provider resolves at once (its id is the item). Another
 * provider's is matched by name in the background against what this account knows locally — its plays'
 * songs and albums and its notes' songs ([YoinNoteReader] / MentionMatcher, the playlist transfer's rules);
 * an artist has nothing local to match, so it stays unresolved. Unresolved mentions draw as plain words and
 * take no tap. Answers are the account's: another account starts over.
 */
@MainActor
@Observable
final class NoteMentions {
    static let shared = NoteMentions(account: NoteMentions.currentAccount, names: NoteMentions.currentNames,
                                     navigate: NoteMentions.navigate)

    /// Name-matched answers, by account scope and mention link: the matched raw id, or nil (no match).
    private(set) var answers: [String: String?] = [:]
    @ObservationIgnored private var asked: Set<String> = []
    @ObservationIgnored private let account: @MainActor () -> NoteMentionAccount?
    @ObservationIgnored private let names: @MainActor (NoteMentionAccount, MentionKind) -> [NoteMentionName]
    @ObservationIgnored private let navigate: @MainActor (NoteMentionTarget) -> Void
    @ObservationIgnored private var openedAt: Date?

    /// How long after opening a page another tap is taken as the same one (Android OPEN_COOLDOWN_MS).
    static let openCooldown: TimeInterval = 1

    init(account: @escaping @MainActor () -> NoteMentionAccount?,
         names: @escaping @MainActor (NoteMentionAccount, MentionKind) -> [NoteMentionName],
         navigate: @escaping @MainActor (NoteMentionTarget) -> Void) {
        self.account = account
        self.names = names
        self.navigate = navigate
    }

    /// How [mention] draws here; read in a view's body, so an answer landing later redraws it.
    func draw(_ mention: YoinNoteMention, links: Bool) -> NoteMentionDraw {
        guard resolvedId(mention) != nil else { return .plain }
        guard links, let url = URL(string: mention.link) else { return .styled }
        return .linked(url)
    }

    /// The account's id for what [mention] names; nil while unresolved (a name match may still be running).
    func resolvedId(_ mention: YoinNoteMention) -> String? {
        guard let current = account() else { return nil }
        if mention.provider == current.provider { return mention.id }
        let key = Self.key(current, mention)
        if let answer = answers[key] { return answer }
        guard mention.kind != .artist, asked.insert(key).inserted else { return nil }
        let rows = names(current, mention.kind)
        // A Kotlin data class: immutable, and Kotlin/Native objects may cross threads.
        nonisolated(unsafe) let asking = mention
        Task { [weak self] in
            let found = await Task.detached(priority: .utility) { Self.match(asking, in: rows) }.value
            // Kept under the account it was asked for; another account's answers are dropped on read.
            self?.answers[key] = .some(found)
        }
        return nil
    }

    /// A tapped link from a note's text: a resolved mention in [text] opens what it names. Any other `yoin:`
    /// link is swallowed (never handed to the system); other links go to the system as usual.
    func handle(_ url: URL, in text: String) -> OpenURLAction.Result { take(url, in: text) ? .handled : .systemAction }

    /// [handle]'s decision: true when Yoin takes the link (a mention opened, or a `yoin:` link dropped).
    func take(_ url: URL, in text: String) -> Bool {
        guard url.scheme?.lowercased() == "yoin" else { return false }
        if let mention = Self.mention(for: url, in: text) { open(mention) }
        return true
    }

    /// Opens what [mention] names: an album's scrapbook, a song's album at its piece, an artist. One open at a time.
    func open(_ mention: YoinNoteMention) {
        if let at = openedAt, Date().timeIntervalSince(at) < Self.openCooldown { return }
        guard let current = account(), let id = resolvedId(mention),
              let target = Self.target(mention, id: id, provider: current.provider) else { return }
        openedAt = Date()
        navigate(target)
    }

    // MARK: Pure parts

    /// The mention in [text] whose link [url] is.
    nonisolated static func mention(for url: URL, in text: String) -> YoinNoteMention? {
        let link = url.absoluteString
        return YoinNoteReader.shared.mentions(text: text).first { $0.link == link }
    }

    nonisolated static func target(_ mention: YoinNoteMention, id: String, provider: String) -> NoteMentionTarget? {
        switch mention.kind {
        case .album: .albumScrapbook(provider: provider, id: id, title: mention.title, artist: mention.artist)
        case .track: .track(provider: provider, id: id, title: mention.title, artist: mention.artist)
        case .artist: .artist(provider: provider, id: id, name: mention.title)
        default: nil
        }
    }

    /// Android LocalMentionResolver.byName: the first name that matches (for a song, one naming the same
    /// album first); nil when none does.
    nonisolated static func match(_ mention: YoinNoteMention, in rows: [NoteMentionName]) -> String? {
        let reader = YoinNoteReader.shared
        if mention.kind == .album {
            return rows.first { reader.namesMatchAlbum(mention: mention, title: $0.title, artist: $0.artist) }?.id
        }
        guard mention.kind == .track else { return nil }
        let hits = rows.filter { reader.namesMatchTrack(mention: mention, title: $0.title, artist: $0.artist, album: $0.album) }
        let sameAlbum = !(mention.album ?? "").isEmpty
        return (hits.first { sameAlbum && !($0.album ?? "").isEmpty } ?? hits.first)?.id
    }

    private static func key(_ account: NoteMentionAccount, _ mention: YoinNoteMention) -> String {
        "\(account.provider)|\(account.scope)|\(mention.link)"
    }

    // MARK: The app's account, names and pages

    private static func currentAccount() -> NoteMentionAccount? {
        let store = AppModel.shared.store
        // The mock library (screenshots, signed out) reads its own "mock" ids.
        guard store.isReal else { return NoteMentionAccount(provider: "mock", scope: "") }
        return NoteMentionAccount(provider: store.provider, scope: store.historyScope)
    }

    /// One row per song (or album) this account played, then its noted songs.
    private static func currentNames(_ account: NoteMentionAccount, _ kind: MentionKind) -> [NoteMentionName] {
        let plays = HistoryStore.shared.plays(scope: account.scope, provider: account.provider)
        var seen: Set<String> = []
        var rows: [NoteMentionName] = []
        if kind == .album {
            for p in plays.reversed() {
                guard let id = p.albumId, !id.isEmpty, !id.hasPrefix("song:"), seen.insert(id).inserted else { continue }
                rows.append(NoteMentionName(id: id, title: p.album, artist: p.artist, album: nil))
            }
            return rows
        }
        for p in plays.reversed() where seen.insert(p.songId).inserted {
            rows.append(NoteMentionName(id: p.songId, title: p.title, artist: p.artist, album: p.album))
        }
        for n in NotesStore.shared.notes where n.provider == account.provider && seen.insert(n.trackId).inserted {
            rows.append(NoteMentionName(id: n.trackId, title: n.title, artist: n.artist, album: nil))
        }
        return rows
    }

    /// Opens [target] on the scrapbook the way the song menu's View in Scrapbook does (an album on no
    /// particular song, Android requestAlbum), or an artist as Go to artist does.
    static func navigate(_ target: NoteMentionTarget) {
        let model = AppModel.shared
        withAnimation(Yoin.spatial) { model.searchActive = false }
        if model.npExpanded { model.setNowPlaying(expanded: false) }
        switch target {
        case let .artist(provider, id, name):
            model.openArtist(name, id: provider == "mock" ? nil : id)
        case let .albumScrapbook(provider, id, title, artist):
            ScrapbookFocusRequest.shared.requestAlbum(MediaId(provider: provider, rawId: id))
            model.open(album(provider: provider, id: id, title: title, artist: artist))
        case let .track(provider, id, title, artist):
            Task { await openTrack(provider: provider, id: id, title: title, artist: artist) }
        }
    }

    /// A song opens its album's scrapbook on the song's piece (Android: ScrapbookFocusRequest, newPageOnly).
    private static func openTrack(provider: String, id: String, title: String, artist: String?) async {
        let model = AppModel.shared
        let songId = MediaId(provider: provider, rawId: id)
        if provider == "mock" {
            guard let album = MockLibrary.allAlbums.first(where: { $0.tracks.contains { $0.id == songId } }) else { return }
            ScrapbookFocusRequest.shared.request(songId: songId, openSheet: false)
            return model.open(album)
        }
        let store = model.store
        let play = HistoryStore.shared.mostRecentPlay(scope: store.historyScope, provider: provider, songId: id)
        var albumId = play?.albumId.flatMap { $0.isEmpty || $0.hasPrefix("song:") ? nil : $0 }
        if albumId == nil {
            let track = Track(id: songId, number: 1, title: title, durationSec: 1, artist: artist)
            albumId = await store.loadSongLinks(for: track).albumId
        }
        guard let albumId else { return }
        ScrapbookFocusRequest.shared.request(songId: songId, openSheet: false)
        model.open(album(provider: provider, id: albumId, title: play?.album ?? title, artist: play?.artist ?? artist))
    }

    /// The loaded album, else a placeholder its page loads (as All Memories opens one).
    private static func album(provider: String, id: String, title: String, artist: String?) -> Album {
        if provider == "mock", let mock = MockLibrary.allAlbums.first(where: { $0.id.rawId == id }) { return mock }
        let store = AppModel.shared.store
        return store.album(id) ?? Album(id: MediaId(provider: provider, rawId: id), title: title, artist: artist ?? "",
                                        year: 0, palette: store.placeholderPalette(coverId: nil), tracks: [])
    }
}
