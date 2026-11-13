import Observation
import SwiftUI

/// Where NeoDB stands for one album, as the rate sheet's last line reads it (Android `AlbumNeoDbSync`).
enum AlbumNeoDBState: Equatable, Sendable {
    /// Not read yet: no line.
    case unknown
    /// No NeoDB account: the line offers sign-in.
    case signedOut
    /// Signed in, nothing written for this album yet: no line.
    case idle
    /// A change waits; it goes when the sheet closes: no line.
    case pending
    case syncing
    case synced
    case failed

    /// Android `albumNeoDbSync`: the account and the album's dirty flags decide.
    static func of(configured: Bool, record: AlbumMemoryStore.AlbumRecord?,
                   pending: NeoDBPendingStore.Entry?) -> AlbumNeoDBState {
        guard configured else { return .signedOut }
        guard let record else { return .idle }
        if pending?.isDirty == true { return .pending }
        if record.rating > 0 || (record.review?.neoDBHasText ?? false) { return .synced }
        return .idle
    }

    /// The words after "NeoDB" on the line (Android `albumNeoDbLabel`). Pending and idle say nothing.
    var label: String {
        switch self {
        case .unknown, .idle, .pending: ""
        case .signedOut: String(localized: "Sign in to sync")
        case .syncing: String(localized: "Syncing…")
        case .synced: String(localized: "Synced")
        case .failed: String(localized: "Couldn't sync")
        }
    }

    /// The sheet shows the line once NeoDB's state is read, even with nothing to say: "What others say"
    /// sits at its end (Android, owner 2026-11-09: the line stays for it).
    var isShown: Bool { self != .unknown }
}

/// The rate sheet's NeoDB life (Android AlbumDetailViewModel's neoDb, owner R3 2026-10-06): opened → read
/// where NeoDB stands; edits → pending again; closed → push when signed in and something changed;
/// a failure gets Retry and a toast. Kept per album here, not in the sheet, so a push still in flight
/// when the sheet comes back reads as Syncing.
@MainActor
@Observable
final class AlbumNeoDBSync {
    static let shared = AlbumNeoDBSync()

    private(set) var states: [String: AlbumNeoDBState] = [:]

    @ObservationIgnored private let service: @MainActor () -> NeoDBSyncService
    @ObservationIgnored private let account: NeoDBAccount
    @ObservationIgnored private let albums: AlbumMemoryStore
    @ObservationIgnored private let pending: NeoDBPendingStore
    @ObservationIgnored private let onFailure: @MainActor (String) -> Void

    init(service: @escaping @MainActor () -> NeoDBSyncService = { .shared }, account: NeoDBAccount = .shared,
         albums: AlbumMemoryStore = .shared, pending: NeoDBPendingStore = .shared,
         onFailure: @escaping @MainActor (String) -> Void = { PlaylistActions.shared.show($0) }) {
        self.service = service
        self.account = account
        self.albums = albums
        self.pending = pending
        self.onFailure = onFailure
    }

    /// Per account too (Codex, 2026-11-06): two Subsonic servers can both have an album "1".
    private static func key(_ album: Album) -> String {
        let scope = AccountScope.shared.scope(for: album.id.provider) ?? ""
        return "\(scope)\u{1F}\(album.id.provider)\u{1F}\(album.id.rawId)"
    }

    func state(_ album: Album) -> AlbumNeoDBState { states[Self.key(album)] ?? .unknown }

    private func set(_ album: Album, _ state: AlbumNeoDBState) { states[Self.key(album)] = state }

    private func computed(_ album: Album, configured: Bool) -> AlbumNeoDBState {
        AlbumNeoDBState.of(configured: configured,
                           record: albums.album(provider: album.id.provider, albumId: album.id.rawId),
                           pending: pending.entry(provider: album.id.provider, albumId: album.id.rawId))
    }

    func sheetOpened(_ album: Album) {
        guard state(album) != .syncing else { return }
        set(album, computed(album, configured: account.isSignedIn))
    }

    /// A score set or words typed while the sheet is open: a shown Synced becomes Pending again.
    /// Unknown, signed out, syncing and failed keep their own owners.
    func edited(_ album: Album, unsavedWords: Bool) {
        let shown = state(album)
        guard shown == .synced || shown == .idle || shown == .pending else { return }
        set(album, unsavedWords ? .pending : computed(album, configured: true))
    }

    /// The sheet closed (its words already saved): push when signed in and something waits.
    func sheetClosed(_ album: Album) async { await sync(album) }

    func retry(_ album: Album) async { await sync(album) }

    private func sync(_ album: Album) async {
        let now = computed(album, configured: account.isSignedIn)
        guard now == .pending else {
            set(album, now)
            return
        }
        // The album's own account's line, even if the account in use changes during the push.
        let key = Self.key(album)
        states[key] = .syncing
        do {
            try await service().push(album)
            states[key] = .synced
        } catch NeoDBSyncService.Failure.accountChanged {
            // The mark went to NeoDB; its flag clears on that account's next push. No error to show.
            states[key] = .pending
        } catch {
            states[key] = .failed
            onFailure(String(localized: "Couldn't sync to NeoDB"))
        }
    }
}

/// The rate sheet's last line: "NeoDB" and its state. Signed out, the line is the sign-in action;
/// a failure gets Retry. At its end, "What others say" opens what other listeners wrote
/// (NeoDBVoicesView), signed in or not (Android AlbumNeoDbLine).
struct AlbumNeoDBLine: View {
    var state: AlbumNeoDBState
    var scheme: CoverScheme
    var onSignIn: () -> Void
    var onRetry: () -> Void
    var onVoices: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text("NeoDB")
                .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                .foregroundStyle(scheme.onSurface)
            Text(state.label)
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(state == .signedOut ? scheme.primary : scheme.onSurfaceVariant)
                .contentTransition(.opacity)
                .accessibilityAddTraits(state == .signedOut ? .isButton : [])
                .accessibilityAction { if state == .signedOut { onSignIn() } }
            if state == .failed {
                Button("Retry", action: onRetry)
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(scheme.primary)
                    .buttonStyle(.plain)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 40)
                    .contentShape(.rect)
                    .transition(.opacity)
            }
            Spacer(minLength: 0)
            Button(action: onVoices) {
                Text("What others say")
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(scheme.primary)
                    .lineLimit(1)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 40)
                    .contentShape(.rect)
            }
            .buttonStyle(PressScale())
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
        .frame(minHeight: 40)
        .contentShape(.rect)
        .onTapGesture { if state == .signedOut { onSignIn() } }
        .accessibilityElement(children: .contain)
        .animation(Yoin.effects, value: state)
    }
}

/// Under the album's words once there are some: where they go on NeoDB — a short comment up to 360
/// characters (with the count), a review beyond (Android AlbumReviewField's counter line).
struct AlbumReviewRoute: View {
    var text: String
    var scheme: CoverScheme

    var body: some View {
        let count = text.unicodeScalars.count
        let short = isNeoDBShortComment(text)
        HStack {
            Text(short ? String(localized: "Short comment") : String(localized: "Review"))
            Spacer()
            Text(short ? String(localized: "\(count) / \(neoDBShortCommentMax)") : "\(count)")
                .monospacedDigit()
        }
        .yoinRole(Tokens.type.labelMedium)
        .foregroundStyle(scheme.onSurfaceVariant)
        .contentTransition(.opacity)
    }
}
