import SwiftUI

/// The shell's side of Open in Yoin (docs/open-in-yoin.md; Android YoinNavHost's OpenInRequest collector).
extension AppModel {
    func configureOpenIn() {
        let store = store
        openIn.lookup = MusicLinkResolver(activeSpotify: { store.spotify })
        openIn.profiles = { [weak self] in self?.profiles.profiles ?? [] }
        openIn.activeId = { [weak self] in self?.profiles.activeId }
    }

    /// A hit opens or plays in the account on the link's service (switched to first), a found link opens
    /// Choose a match, other shared text opens Search with it.
    func handleOpenIn(_ request: OpenInRequest) {
        switch request {
        case let .openHit(link, profileId, metadata):
            Task { await openHit(link, profileId: profileId, metadata: metadata) }
        case let .showResults(link, metadata):
            stepAsideForOpenIn()
            path.append(.openInResults(OpenInResultsRequest(link: link, metadata: metadata)))
        case .searchLibrary(let query):
            if npExpanded { setNowPlaying(expanded: false) }
            searchStartQuery = query
            if searchActive {
                // Search is up: it takes the text now.
                NotificationCenter.default.post(name: .yoinSearchStartQuery, object: nil)
            } else {
                openSearch(from: .bar)
            }
        }
    }

    /// Now Playing and Search step aside for a page Open in Yoin opens (Android collapses Now Playing).
    private func stepAsideForOpenIn() {
        if npExpanded { setNowPlaying(expanded: false) }
        if searchActive { withAnimation(Yoin.spatial) { searchActive = false } }
    }

    private func openHit(_ link: OpenInLink, profileId: String, metadata: MusicLinkMetadata?) async {
        guard let action = OpenInRouting.hitAction(link) else { return }
        let before = store.generation
        let switching = profileId != profiles.activeId
        if switching {
            await profiles.switchTo(profileId)
            guard profiles.activeId == profileId else {
                profiles.clearFailure()
                PlaylistActions.shared.show(String(localized: "Couldn't switch account"))
                return
            }
        }
        // Just switched (or started cold by a share): the account's library follows a moment later.
        guard await libraryFollows(profileId, provider: link.service.provider, since: switching ? before : nil) else {
            PlaylistActions.shared.show(String(localized: "Couldn't load this song here."))
            return
        }
        switch action {
        case .playTrack(let id):
            let entry: PlaylistEntry? = switch link.service {
            case .spotify: try? await store.spotify?.track(id)
            case .appleMusic: await store.apple?.catalogEntry(id)
            }
            guard let entry else {
                PlaylistActions.shared.show(String(localized: "Couldn't load this song here."))
                return
            }
            // The song alone, as Android plays it (playSearchResults(listOf(track))).
            play([entry], startAt: 0)
        case .openAlbum(let id):
            stepAsideForOpenIn()
            await store.loadAlbum(id)
            guard let album = store.album(id) else {
                PlaylistActions.shared.show(String(localized: "Couldn't open the album"))
                return
            }
            open(album)
        case .openArtist(let id):
            stepAsideForOpenIn()
            openArtist(metadata?.title ?? "", id: id)
        case .openPlaylist(let id):
            stepAsideForOpenIn()
            openPlaylist(id)
        }
    }

    /// The shell takes Open in Yoin's requests from here on ([up]), or holds them (the first-run landing).
    func openInShellUp(_ up: Bool) {
        if up {
            openIn.perform = { [weak self] request in self?.handleOpenIn(request) }
        } else {
            openIn.perform = nil
        }
    }

    /// A share the extension left in the App Group, taken once (OpenInInbox). True when there was one.
    @discardableResult
    func takeOpenInShare() -> Bool {
        guard let shared = OpenInInbox.take() else { return false }
        openIn.offerShared(shared)
        return true
    }

    /// Yoin came to the front: a share waiting in the App Group is taken; otherwise the clipboard is looked
    /// at once.
    func openInReturned() async {
        if takeOpenInShare() { return }
        if await clipboardWatcher.check(hasAccounts: !profiles.profiles.isEmpty, landingOpen: landing != nil) {
            withAnimation(Yoin.spatial) { openIn.offerPaste() }
        }
    }
}

extension Notification.Name {
    /// Search is already open and Open in Yoin hands it text (AppModel.searchStartQuery).
    static let yoinSearchStartQuery = Notification.Name("yoinSearchStartQuery")
}

/// RootView's Open in Yoin: the card over the shell, the clipboard check once each time Yoin comes back to
/// the front (armed at launch and on willEnterForeground, so Control Center's inactive → active doesn't
/// count), and the shell's hand once the first-run landing is gone.
struct OpenInHost: ViewModifier {
    let model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var armed = true

    func body(content: Content) -> some View {
        content
            .overlay { OpenInCardLayer() }
            #if DEBUG
            .overlay {
                if model.openIn.demoShareConfirm { ShareConfirmation { model.openIn.demoShareConfirm = false } }
            }
            #endif
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                armed = true
            }
            .onChange(of: scenePhase, initial: true) { _, phase in
                guard phase == .active, armed else { return }
                armed = false
                Task { await model.openInReturned() }
            }
            // A share saved while Yoin is already in front (iPad beside another app, a Mac): taken now.
            .onReceive(NotificationCenter.default.publisher(for: OpenInShareSignal.name)) { _ in
                if scenePhase == .active { model.takeOpenInShare() }
            }
            .onAppear { OpenInShareSignal.listen() }
            // Requests wait while the first-run landing covers the shell.
            .onChange(of: model.landing == nil, initial: true) { _, shellUp in model.openInShellUp(shellUp) }
    }
}

/// The extension's Darwin notification (OpenInInbox.darwinNotification), passed on as a NotificationCenter
/// one on the main queue.
enum OpenInShareSignal {
    static let name = Notification.Name("yoinOpenInShared")
    @MainActor private static var listening = false

    @MainActor static func listen() {
        guard !listening else { return }
        listening = true
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), nil, { _, _, _, _, _ in
            DispatchQueue.main.async { NotificationCenter.default.post(name: OpenInShareSignal.name, object: nil) }
        }, OpenInInbox.darwinNotification as CFString, nil, .deliverImmediately)
    }
}
