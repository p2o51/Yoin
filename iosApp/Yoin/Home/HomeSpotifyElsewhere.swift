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
}

/// Looks once each time the app comes to the front; the answer stays [SpotifyElsewhere.showSeconds].
@MainActor
@Observable
final class SpotifyElsewhereWatch {
    private(set) var shown: SpotifyElsewhere?
    private var peek: Task<Void, Never>?
    private var hide: Task<Void, Never>?
    private var generation = 0

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
            withAnimation(Yoin.spatial) { self.shown = answer }
            self.hide = Task { [weak self] in
                try? await Task.sleep(for: .seconds(SpotifyElsewhere.showSeconds))
                guard !Task.isCancelled else { return }
                self?.dismiss()
            }
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
