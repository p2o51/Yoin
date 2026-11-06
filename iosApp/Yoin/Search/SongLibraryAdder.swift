import SwiftUI

/// Whether a song is in the service's library (Android LibraryMembership).
enum LibraryMembership: Sendable { case unknown, notAdded, pending, added }

/// What the library write answered, shown under the song's row (Android LibraryActionFeedback).
struct LibraryAddFeedback: Equatable, Sendable {
    var message: String
    var isError: Bool
}

/// The two calls the search rows' + / ✓ button makes of a service that adds songs to a library.
@MainActor
protocol LibraryAddBackend: AnyObject, Sendable {
    /// nil: unknown (the check failed).
    func isInLibrary(_ rawId: String) async -> Bool?
    func addToLibrary(_ rawId: String) async throws
}

extension AppleMusicSource: LibraryAddBackend {}

/// Library membership of the songs a search lists, and adding one (Android LibraryViewModel
/// observeSearchLibraryMembership / addSongToLibrary): the state per song, which are working, and the
/// message under each row.
@MainActor
@Observable
final class SongLibraryAdder {
    private(set) var membership: [String: LibraryMembership] = [:]
    private(set) var working: Set<String> = []
    private(set) var feedback: [String: LibraryAddFeedback] = [:]
    /// Songs the service accepted but did not confirm yet: a tap rechecks them without posting again.
    @ObservationIgnored private var pendingCheck: Set<String> = []
    @ObservationIgnored private var backend: LibraryAddBackend?

    /// Points the adder at the active service's library (nil: it adds nothing), forgetting the old one's.
    func use(_ backend: LibraryAddBackend?) {
        guard backend !== self.backend else { return }
        self.backend = backend
        membership = [:]; working = []; feedback = [:]; pendingCheck = []
    }

    var canAdd: Bool { backend != nil }

    func state(_ id: String) -> LibraryMembership { membership[id] ?? .unknown }

    /// Reads the membership of [ids] not read yet, three at a time (Android Semaphore(3)).
    func refresh(_ ids: [String]) async {
        guard let backend else { return }
        let todo = ids.filter { membership[$0] == nil }
        var start = 0
        while start < todo.count {
            let batch = Array(todo[start..<min(start + 3, todo.count)])
            start += 3
            let tasks = batch.map { id in Task { (id, await backend.isInLibrary(id)) } }
            var answers: [(String, Bool?)] = []
            for t in tasks { answers.append(await t.value) }
            guard self.backend === backend else { return }
            for (id, known) in answers where membership[id] == nil || known != nil {
                membership[id] = known.map { $0 ? .added : .notAdded } ?? .unknown
            }
        }
    }

    /// Adds the song. A write that is accepted but not yet confirmed reads Pending (the tap again rechecks).
    func add(_ id: String) async {
        guard let backend, !working.contains(id), membership[id] != .added else { return }
        feedback[id] = nil
        working.insert(id)
        defer { if self.backend === backend { working.remove(id) } }
        do {
            if !pendingCheck.contains(id) { try await backend.addToLibrary(id) }
            guard self.backend === backend else { return }
            if await backend.isInLibrary(id) == true {
                pendingCheck.remove(id)
                membership[id] = .added
                feedback[id] = .init(message: String(localized: "Added to library"), isError: false)
            } else {
                pendingCheck.insert(id)
                membership[id] = .pending
                feedback[id] = .init(message: String(localized: "Waiting for Apple Music"), isError: false)
            }
        } catch {
            guard self.backend === backend else { return }
            feedback[id] = .init(message: String(localized: "Couldn't add this song to the library."), isError: true)
        }
    }
}

/// The + / ✓ at the end of a search song row (Android TrackLibraryButton): + to add, a spinner while the
/// write runs, a refresh mark while Apple Music hasn't confirmed, a check once it's in.
struct SongLibraryButton: View {
    var membership: LibraryMembership
    var working: Bool
    var scheme: CoverScheme
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                if working {
                    ProgressView().controlSize(.small)
                } else {
                    switch membership {
                    case .added: YoinIcon("check", size: 22).foregroundStyle(scheme.primary)
                    case .pending: YoinIcon("refresh", size: 22).foregroundStyle(scheme.onSurfaceVariant)
                    default: YoinIcon("add", size: 22).foregroundStyle(scheme.onSurfaceVariant)
                    }
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(.rect)
            .animation(Yoin.effects, value: working)
            .animation(Yoin.effects, value: membership)
        }
        .buttonStyle(.plain)
        .disabled(working || membership == .added)
        .accessibilityLabel(label)
    }

    private var label: String {
        switch membership {
        case .added: String(localized: "In Apple Music library")
        case .pending: String(localized: "Check library addition")
        default: String(localized: "Add to Apple Music library")
        }
    }
}
