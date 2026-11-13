import SwiftUI

/// What a signed-in Spotify account that is not the one in use is playing (51, 2026-11-10; Android
/// HomeSpotifyElsewherePill.kt): Home's header shows it for 5 s, and a tap switches to that account.
/// Read with GET me/player and the account's own saved tokens, never through App Remote, so the
/// Spotify app is not woken to find out.
struct SpotifyElsewhere: Equatable, Sendable {
    var profileId: String
    var title: String
    var artist: String
    var coverURL: URL?
    var isPlaying: Bool

    /// One account's answer to show: one playing first, else the first paused one.
    static func pick(_ answers: [SpotifyElsewhere]) -> SpotifyElsewhere? {
        answers.first { $0.isPlaying } ?? answers.first
    }

    /// Up only while Yoin itself plays nothing.
    static func visible(_ e: SpotifyElsewhere?, yoinPlaying: Bool) -> Bool { e != nil && !yoinPlaying }

    /// How long it stays (51): playing or paused alike. Left alone, the user means to stay where they are.
    static let showSeconds: Double = 5

    /// Whether the 5 s run (Android SpotifyElsewhereTimer, 8cd973e6c): only while there is an answer
    /// and Home's header can be seen. Hidden, the count stops; back on screen it starts over at 5 s,
    /// so the answer is never spent unseen.
    static func counts(shown: Bool, onScreen: Bool) -> Bool { shown && onScreen }

    /// Whether Home's header can be seen (Android: `uiState is Content && !homeCovered`, where covered
    /// is Now Playing or the detail pane). iOS adds what else lies over the header here: a pushed page,
    /// Search, Memories, and the app not in front.
    static func headerOnScreen(feedLoaded: Bool, sceneActive: Bool, nowPlaying: Bool, detailColumn: Bool,
                               pushed: Bool, search: Bool, memories: Bool) -> Bool {
        feedLoaded && sceneActive && !nowPlaying && !detailColumn && !pushed && !search && !memories
    }
}

/// Looks once each time the app comes to the front; the answer stays [SpotifyElsewhere.showSeconds]
/// of time on screen ([setOnScreen]).
@MainActor
@Observable
final class SpotifyElsewhereWatch {
    private(set) var shown: SpotifyElsewhere?
    private var peek: Task<Void, Never>?
    private var hide: Task<Void, Never>?
    private var generation = 0
    /// Home's header can be seen right now (HomeView): the count runs only then.
    private(set) var onScreen = false
    /// The wait behind the count; tests swap it.
    @ObservationIgnored var sleep: @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }

    /// HomeView's word on whether the header can be seen. Going off stops the count; coming back
    /// gives the whole 5 s again (Android SpotifyElsewhereTimer keyed on onScreen).
    func setOnScreen(_ value: Bool) {
        guard value != onScreen else { return }
        onScreen = value
        rearm()
    }

    /// An answer to show; its 5 s start once the header can be seen.
    func show(_ answer: SpotifyElsewhere) {
        withAnimation(Yoin.spatial) { shown = answer }
        rearm()
    }

    private func rearm() {
        hide?.cancel()
        hide = nil
        guard SpotifyElsewhere.counts(shown: shown != nil, onScreen: onScreen) else { return }
        let sleep = sleep
        hide = Task { [weak self] in
            try? await sleep(.seconds(SpotifyElsewhere.showSeconds))
            guard !Task.isCancelled else { return }
            self?.dismiss()
        }
    }

    func look(_ model: AppModel) {
        guard peek == nil else { return }
        dismiss()
        let profiles = model.profiles
        // Only from an account that isn't Spotify (its own playback is already in the mini player).
        guard profiles.active?.service != .spotify else { return }
        let asked = profiles.activeId
        let others = profiles.profiles.filter {
            $0.service == .spotify && $0.id != asked && !profiles.needsSignIn($0)
        }
        guard !others.isEmpty else { return }
        generation += 1
        let gen = generation
        peek = Task { [weak self] in
            var answers: [SpotifyElsewhere] = []
            for p in others {
                // A source on the account's saved tokens: a refresh is saved back before it answers.
                guard let source = model.spotifySource(forProfile: p.id),
                      let now = try? await source.playerNow(),
                      let title = now.trackName, now.trackURI?.hasPrefix("spotify:track:") == true
                else { continue }
                answers.append(SpotifyElsewhere(profileId: p.id, title: title, artist: now.artistName ?? "",
                                                coverURL: now.coverURL, isPlaying: now.isPlaying))
            }
            guard let self, gen == self.generation else { return }
            self.peek = nil
            // An answer for the account that asked: a switch meanwhile drops it.
            guard !Task.isCancelled, model.profiles.activeId == asked,
                  let answer = SpotifyElsewhere.pick(answers) else { return }
            self.show(answer)
        }
    }

    func dismiss() {
        hide?.cancel()
        hide = nil
        guard shown != nil else { return }
        withAnimation(Yoin.spatial) { shown = nil }
    }

    /// Leaving (background, a switch): no answer outlives it.
    func reset() {
        generation += 1
        peek?.cancel()
        peek = nil
        dismiss()
    }
}

/// The header pill: the cover (nothing drawn on it: Spotify's artwork rule), the title, and
/// "Spotify · artist". No device name (51). A tap switches to the account.
struct SpotifyElsewherePill: View {
    var elsewhere: SpotifyElsewhere
    var scheme: CoverScheme
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 8) {
                Group {
                    if let url = elsewhere.coverURL {
                        RemoteCover(ref: CoverRef(url: url), placeholder: scheme.surfaceContainerHighest)
                    } else {
                        scheme.surfaceContainerHighest
                    }
                }
                .frame(width: 28, height: 28)
                .clipShape(.rect(cornerRadius: 4))
                VStack(alignment: .leading, spacing: 0) {
                    Text(elsewhere.title)
                        .font(YoinFont.rounded(13, relativeTo: .footnote))
                        .foregroundStyle(scheme.onSurface)
                    Text("Spotify · \(elsewhere.artist)")
                        .font(.system(size: 11))
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
                .lineLimit(1)
            }
            .padding(.leading, 6)
            .padding(.trailing, 14)
            .frame(height: 40)
            .frame(maxWidth: 220, alignment: .leading)
            .background(scheme.surfaceContainerHigh, in: .capsule)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(String(localized: "Playing on Spotify: \(elsewhere.title), \(elsewhere.artist)"))
        .accessibilityHint(String(localized: "Switches to Spotify"))
        .transition(.scale(scale: 0.85, anchor: .trailing).combined(with: .opacity))
    }
}

#if DEBUG
/// Screenshots and QA with no Spotify account: `-spotifyElsewhereSeed YES` puts a made-up answer in
/// the header 2 s after Home shows; with `-screen np`, `-spotifyElsewhereSeedCloseNP 8` closes Now
/// Playing at 8 s, so the pill is seen to wait under it and count only once it can be seen.
@MainActor
enum SpotifyElsewhereSeed {
    private static var started = false
    /// The Home on screen's watch (Home is rebuilt once at launch).
    private static weak var watch: SpotifyElsewhereWatch?

    static func run(_ watch: SpotifyElsewhereWatch, model: AppModel) async {
        let d = UserDefaults.standard
        guard d.bool(forKey: "spotifyElsewhereSeed") else { return }
        self.watch = watch
        guard !started else { return }
        started = true
        // Its own task: the first Home's .task is cancelled when Home is rebuilt.
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(2))
            // The mock song stands paused: the pill is up only while Yoin plays nothing.
            model.playback.isPlaying = false
            self.watch?.show(SpotifyElsewhere(profileId: "seed", title: "Pink + White", artist: "Frank Ocean",
                                              coverURL: nil, isPlaying: true))
            let close = d.double(forKey: "spotifyElsewhereSeedCloseNP")
            guard close > 2 else { return }
            try? await Task.sleep(for: .seconds(close - 2))
            model.setNowPlaying(expanded: false)
        }
    }
}
#endif
