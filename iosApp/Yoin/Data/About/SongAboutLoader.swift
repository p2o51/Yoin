import Foundation
import Observation

/// Fetches a song's About from Gemini once and keeps it in `SongAboutStore`:
/// Android `YoinRepository.ensureCanonicalAbout` plus NowPlayingViewModel's
/// `_aboutLoading` / `_aboutError` (one fetch in flight per song).
@MainActor
@Observable
final class SongAboutLoader {
    static let shared = SongAboutLoader()

    enum Phase: Equatable {
        case idle
        case loading
        /// No key, and nothing cached (Android `ApiKeyMissing`).
        case noKey
        case failed(String)
    }

    private(set) var phases: [SongAboutStore.Entry.SongKey: Phase] = [:]

    @ObservationIgnored let store: SongAboutStore
    @ObservationIgnored let settings: GeminiSettings
    @ObservationIgnored private let client: () -> GeminiClient
    @ObservationIgnored private var inFlight: Set<SongAboutStore.Entry.SongKey> = []

    init(store: SongAboutStore? = nil, settings: GeminiSettings? = nil,
         client: @escaping () -> GeminiClient = { GeminiClient() }) {
        self.store = store ?? .shared
        self.settings = settings ?? .shared
        self.client = client
    }

    func phase(_ key: SongAboutStore.Entry.SongKey) -> Phase { phases[key] ?? .idle }

    /// Cached answers stand; otherwise one grounded call, unless one is
    /// already running. `retry` asks again over the cache. A song without a
    /// title or artist isn't asked (Android).
    func ensure(title: String, artist: String, album: String, retry: Bool = false) async {
        guard !title.trimmingCharacters(in: .whitespaces).isEmpty,
              !artist.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let key = SongAboutStore.key(title: title, artist: artist, album: album)
        guard !inFlight.contains(key) else { return }
        if !retry, store.hasCanonical(key) {
            phases[key] = .idle
            return
        }
        let apiKey = settings.requestKey
        guard !apiKey.isEmpty else {
            phases[key] = store.entries(key).isEmpty ? .noKey : .idle
            return
        }
        let language = settings.targetLanguage
        inFlight.insert(key)
        phases[key] = .loading
        defer { inFlight.remove(key) }
        // Its own task: leaving the About page mustn't throw away a paid call.
        let call = client()
        let request = Task {
            try await call.canonicalAbout(apiKey: apiKey, title: title, artist: artist, album: album,
                                          targetLanguage: language)
        }
        do {
            let values = try await request.value
            store.saveCanonical(title: title, artist: artist, album: album, values: values, language: language)
            phases[key] = .idle
        } catch is CancellationError {
            phases[key] = .idle
        } catch {
            phases[key] = .failed((error as? GeminiClient.Failure)?.message ?? "Failed to load song about info")
        }
    }

    /// A key was just saved: songs that said "no key" may ask now.
    func keyChanged() {
        for (k, p) in phases where p == .noKey { phases[k] = .idle }
    }
}
