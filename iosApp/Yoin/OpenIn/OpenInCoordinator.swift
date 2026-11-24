import Foundation
import Observation

/// The card at the bottom of the shell (Android OpenInPrompt). [paste]: the clipboard probably holds a web
/// link, not read yet; the card offers the system Paste button. Otherwise [link] as offered, then as followed
/// once a short link's target is known ([resolved]); [metadata] arrives a beat later, or never (the card shows
/// its plain line until then). A hit is drawn rich (cover, title, artist, Listen / Open); a found link is text
/// only, with Search.
struct OpenInPrompt: Equatable, Sendable {
    var serial: Int
    var paste: Bool = false
    var link: OpenInLink?
    var target: OpenInTarget = .found
    var metadata: MusicLinkMetadata?
    var resolved = true

    /// Listen for a song, Open for anything else (and for a short link whose target isn't known yet).
    var listens: Bool { link?.kind == .track }
}

/// What the shell does next, from a share or a tapped card.
enum OpenInRequest: Equatable, Sendable {
    /// Open (or play) [link] on [profileId]'s account, switching to it first if another is in use.
    /// [metadata]: what was already read (an artist page's name before it loads).
    case openHit(OpenInLink, profileId: String, metadata: MusicLinkMetadata?)
    /// Search the account in use for what [link] names and let the user choose.
    case showResults(OpenInLink, metadata: MusicLinkMetadata?)
    /// Shared text that holds no music link: Library search with it.
    case searchLibrary(String)
}

/// "Open in Yoin" (the share extension) and the clipboard card, behind one router (Android OpenInCoordinator):
/// a link goes to an account on its own service when the user has one (hit), else it is searched on the
/// account in use (found). Holds no clipboard text: only the parsed link.
@MainActor
@Observable
final class OpenInCoordinator {
    private(set) var prompt: OpenInPrompt?

    @ObservationIgnored var lookup: MusicLinkLookup = MusicLinkResolver()
    @ObservationIgnored var profiles: @MainActor () -> [Profile] = { [] }
    @ObservationIgnored var activeId: @MainActor () -> String? = { nil }
    /// The shell's hand: set once it is up. Requests made before (a share that starts Yoin cold, the
    /// landing) wait for it.
    @ObservationIgnored var perform: (@MainActor (OpenInRequest) -> Void)? {
        didSet { flush() }
    }
    @ObservationIgnored private var pending: [OpenInRequest] = []
    @ObservationIgnored private var serial = 0
    @ObservationIgnored private var fillTask: Task<Void, Never>?

    /// The clipboard probably holds a web link: the card offers Paste.
    func offerPaste() {
        serial += 1
        fillTask?.cancel()
        prompt = OpenInPrompt(serial: serial, paste: true)
    }

    /// What the system Paste button gave. A supported link becomes the card proper; anything else closes it.
    func pasted(_ text: String) {
        guard let link = OpenInLink.findFirst(text) else {
            dismiss()
            return
        }
        if !offer(link) { dismiss() }
    }

    /// Show the card for [link]. False when there is no account to open or search it in (before the first
    /// one is added): nothing is shown.
    @discardableResult
    func offer(_ link: OpenInLink) -> Bool {
        let accounts = profiles()
        guard !accounts.isEmpty else { return false }
        serial += 1
        let card = OpenInPrompt(serial: serial, link: link, target: OpenInRouting.route(link, profiles: accounts, activeId: activeId()),
                                resolved: !link.needsRedirectResolution)
        prompt = card
        fillTask?.cancel()
        fillTask = Task { [weak self] in await self?.fill(card) }
        return true
    }

    /// The card's way to its link's target and title: the short link followed, then its metadata read.
    private func fill(_ card: OpenInPrompt) async {
        guard let first = card.link else { return }
        let link = await lookup.resolve(first)
        guard !Task.isCancelled else { return }
        update(card.serial) { $0.link = link ?? $0.link; $0.resolved = true }
        guard let link, let metadata = await lookup.metadata(link), !Task.isCancelled else { return }
        update(card.serial) { $0.metadata = metadata }
    }

    private func update(_ serial: Int, _ change: (inout OpenInPrompt) -> Void) {
        guard var p = prompt, p.serial == serial else { return }
        change(&p)
        prompt = p
    }

    /// Fills the card as it would be once read (QA screenshots).
    func show(_ card: OpenInPrompt) {
        fillTask?.cancel()
        prompt = card
    }

    /// The card's button: open the hit, or search for the found link. The card goes either way.
    func accept() {
        guard let card = prompt, let first = card.link else { return }
        prompt = nil
        fillTask?.cancel()
        Task { [weak self] in
            guard let self else { return }
            let resolvedLink: OpenInLink? = card.resolved ? first : await lookup.resolve(first)
            guard let link = resolvedLink else { return }
            switch card.target {
            case .hit(let profileId, _): send(.openHit(link, profileId: profileId, metadata: card.metadata))
            case .found: send(.showResults(link, metadata: card.metadata))
            }
        }
    }

    func dismiss() {
        fillTask?.cancel()
        prompt = nil
    }

    /// Text shared to "Open in Yoin". Its first music link opens as a tapped card would, without a card: the
    /// user already said what they want. No link: Library search with the text.
    func offerShared(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task { [weak self] in
            guard let self else { return }
            let found = OpenInLink.findFirst(trimmed)
            let link: OpenInLink? = if let found { await lookup.resolve(found) } else { nil }
            guard let link else {
                if let query = Self.searchQuery(trimmed, deadLink: found) { send(.searchLibrary(query)) }
                return
            }
            switch OpenInRouting.route(link, profiles: profiles(), activeId: activeId()) {
            case .hit(let profileId, _): send(.openHit(link, profileId: profileId, metadata: nil))
            case .found: send(.showResults(link, metadata: nil))
            }
        }
    }

    /// Library search with [text], minus a link that led nowhere; nil when only that link was shared.
    nonisolated static func searchQuery(_ text: String, deadLink: OpenInLink?) -> String? {
        var query = deadLink.map { text.replacingOccurrences(of: $0.url, with: " ") } ?? text
        query = query.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        query = String(query.prefix(200))
        return query.isEmpty ? nil : query
    }

    private func send(_ request: OpenInRequest) {
        if let perform { perform(request) } else { pending.append(request) }
    }

    private func flush() {
        guard let perform, !pending.isEmpty else { return }
        let waiting = pending
        pending = []
        waiting.forEach(perform)
    }
}
