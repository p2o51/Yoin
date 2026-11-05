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
        case .signedOut: "Sign in to sync"
        case .syncing: "Syncing…"
        case .synced: "Synced"
        case .failed: "Couldn't sync"
        }
    }

    /// The sheet shows the line only for these.
    var isShown: Bool { self != .unknown && self != .idle && self != .pending }
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

    private static func key(_ album: Album) -> String { "\(album.id.provider)\u{1F}\(album.id.rawId)" }

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
        set(album, .syncing)
        do {
            try await service().push(album)
            set(album, .synced)
        } catch {
            set(album, .failed)
            onFailure("Couldn't sync to NeoDB")
        }
    }
}

/// The rate sheet's last line: "NeoDB" and its state. Signed out, the line is the sign-in action;
/// a failure gets Retry.
struct AlbumNeoDBLine: View {
    var state: AlbumNeoDBState
    var scheme: CoverScheme
    var onSignIn: () -> Void
    var onRetry: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text("NeoDB")
                .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                .foregroundStyle(scheme.onSurface)
            Text(state.label)
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(state == .signedOut ? scheme.primary : scheme.onSurfaceVariant)
                .contentTransition(.opacity)
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
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
        .frame(minHeight: 40)
        .contentShape(.rect)
        .onTapGesture { if state == .signedOut { onSignIn() } }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(state == .signedOut ? .isButton : [])
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
            Text(short ? "Short comment" : "Review")
            Spacer()
            Text(short ? "\(count) / \(neoDBShortCommentMax)" : "\(count)")
                .monospacedDigit()
        }
        .yoinRole(Tokens.type.labelMedium)
        .foregroundStyle(scheme.onSurfaceVariant)
        .contentTransition(.opacity)
    }
}
