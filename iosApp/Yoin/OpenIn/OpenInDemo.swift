#if DEBUG
import SwiftUI

/// QA screenshots of Open in Yoin (debug builds only): `-openInDemo <mode>` with `-screen home`.
///
/// - `hit` / `found`: the clipboard card for a Spotify song link, as a hit (a Spotify account) or found (none).
///   Its title, artist and cover are read live from the open.spotify.com page, as the app reads them without
///   a login; offline it falls back to the same words without a cover.
/// - `paste`: the card before the clipboard is read ("Link in clipboard", the system Paste button).
/// - `share-confirm`: the share extension's confirmation (YoinShare/ShareConfirmation.swift) over Home.
/// - `nolink`: after a paste with no music link in it ("No music link found").
/// - `results`: Choose a match for that link, rows from the mock library.
/// - `settings`: Settings › Features and appearance › Music links.
@MainActor
enum OpenInDemo {
    static var mode: String? { UserDefaults.standard.string(forKey: "openInDemo") }

    static let link = OpenInLink.parse("https://open.spotify.com/track/003vvx7Niy0yvhvHt4a68B?si=demo")!
    static let fallback = MusicLinkMetadata(kind: .track, title: "Mr. Brightside", artist: "The Killers",
                                            album: "Hot Fuss", durationSec: 222)

    static func launch(_ model: AppModel) {
        guard let mode else { return }
        switch mode {
        case "settings":
            model.settingsDestination = .musicLinks
            model.path = [.settings]
        case "results":
            model.path = [.openInResults(OpenInResultsRequest(link: link, metadata: fallback))]
        case "paste":
            Task {
                try? await Task.sleep(for: .milliseconds(1200))
                withAnimation(Yoin.spatial) { model.openIn.offerPaste() }
            }
        case "share-confirm":
            // The share extension's confirmation as it shows over the app it was shared from.
            model.openIn.demoShareConfirm = true
        case "nolink":
            // What Paste shows when the clipboard held no music link, held long enough for a screenshot.
            model.openIn.noLinkHold = .seconds(60)
            Task {
                try? await Task.sleep(for: .milliseconds(1200))
                withAnimation(Yoin.spatial) { model.openIn.pasted("https://example.com/not-music") }
            }
        case "hit", "found":
            Task {
                let target: OpenInTarget = mode == "hit" ? .hit(profileId: "demo", profileName: "Spotify") : .found
                let read = await model.openIn.lookup.metadata(link)
                try? await Task.sleep(for: .milliseconds(600))
                withAnimation(Yoin.spatial) {
                    model.openIn.show(OpenInPrompt(serial: 1, link: link, target: target, metadata: read ?? fallback))
                }
            }
        default: break
        }
    }

    /// Choose a match's rows for the demo link: versions of the song on the mock library's covers.
    static func results(for request: OpenInResultsRequest) -> OpenInResultsModel.State? {
        guard mode == "results", request.link == link else { return nil }
        let albums = MockLibrary.allAlbums
        let titles = [("Mr. Brightside", "The Killers", 222), ("Mr. Brightside (Live)", "The Killers", 238),
                      ("Mr. Brightside (Jacques Lu Cont Remix)", "The Killers", 524),
                      ("Mr Brightside", "Hot Fuss Tribute Band", 219)]
        let rows = titles.enumerated().map { i, t in
            var album = albums[i % albums.count]
            album.title = i == 1 ? "Live from the Royal Albert Hall" : "Hot Fuss"
            album.artist = t.1
            var track = album.tracks[0]
            track.id = MediaId(provider: "mock", rawId: "openin-\(i)")
            track.title = t.0
            track.artist = t.1
            track.durationSec = t.2
            return OpenInResultRow.song(PlaylistEntry(album: album, track: track))
        }
        return .results(rows, exact: false, service: "Subsonic")
    }
}
#endif
