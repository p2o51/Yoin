#if DEBUG
import AVFoundation
import Foundation
import YoinShared

/// `-searchMemoriesDemo YES` (screenshots, QA, with the mock library): a made-up ledger for Library search, two
/// accounts' notes, album notes, reviews, titles (one the AI's) and scores on the mock albums, so the Memories
/// chip, the tiled All page and play-from-a-note's-second show without real accounts. Try "night" (or "夜").
/// A note's song plays a generated silent file through the real engine, so the seek to the note's second is
/// the app's own.
enum SearchMemoriesDemo {
    static var isOn: Bool { UserDefaults.standard.bool(forKey: "searchMemoriesDemo") }

    static let activeProfileId = "demo-mira"
    static let otherProfileId = "demo-okto"

    static func profileName(_ id: String) -> String { id == otherProfileId ? "Okto" : "Mira" }

    static var snapshot: SearchLedgerSnapshot? {
        guard isOn else { return nil }
        return build(now: Date())
    }

    /// The demo's ledger as of [now].
    static func build(now: Date) -> SearchLedgerSnapshot {
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        func daysAgo(_ d: Double) -> Int64 { nowMs - Int64(d * 86_400_000) }
        let albums = MockLibrary.allAlbums
        var covers: [String: CoverPalette] = [:]
        func mine(_ i: Int) -> YoinLedgerAlbum {
            let a = albums[i]
            let album = YoinLedgerAlbum(profileId: activeProfileId, provider: a.id.provider, albumId: a.id.rawId,
                                        name: a.title, artist: a.artist, coverKey: nil)
            covers[album.key] = a.palette
            return album
        }
        let harbour = YoinLedgerAlbum(profileId: otherProfileId, provider: SpotifyMapping.provider, albumId: "demo-harbour",
                                      name: "Harbour Lights", artist: "Okto", coverKey: nil)
        covers[harbour.key] = albums[8].palette
        func note(_ id: String, _ album: YoinLedgerAlbum, track: Int?, title: String, text: String, at: Int64,
                  positionMs: Int64, profileId: String = activeProfileId, songId: String? = nil) -> YoinLedgerEntry {
            let song = songId ?? "\(album.albumId)-t\(track ?? 1)"
            return YoinLedgerEntry(id: "song-note:\(id)", kind: .songnote, at: at, profileId: profileId, album: album,
                                   text: text, trackTitle: title, songId: song, songProvider: album.provider,
                                   positionMs: KotlinLong(value: positionMs), score: nil)
        }
        func entry(_ id: String, _ kind: YoinLedgerKind, _ album: YoinLedgerAlbum, text: String? = nil,
                   score: Double? = nil, at: Int64, profileId: String = activeProfileId) -> YoinLedgerEntry {
            YoinLedgerEntry(id: id, kind: kind, at: at, profileId: profileId, album: album, text: text, trackTitle: nil,
                            songId: nil, songProvider: nil, positionMs: nil, score: score.map { KotlinFloat(value: Float($0)) })
        }
        let afterglow = mine(0), tidal = mine(1), neon = mine(3), atlas = mine(6), lumen = mine(11)
        let entries: [YoinLedgerEntry] = [
            note("n1", neon, track: 2, title: "Night Market",
                 text: "The synth at 1:12 is the lanterns over the **night market**, every time.",
                 at: daysAgo(2), positionMs: 72_000),
            note("n2", afterglow, track: 2, title: "Late Bus",
                 text: "Missed my stop on purpose. Some nights are too good to end.", at: daysAgo(5), positionMs: 104_000),
            note("n3", afterglow, track: 1, title: "Paper Moon", text: "夜里骑车回家，耳机里一直放着这首。",
                 at: daysAgo(8), positionMs: 45_000),
            note("o1", harbour, track: nil, title: "Coast Road",
                 text: "Night drive on the coast road; the bass line carried the whole car.", at: daysAgo(3),
                 positionMs: 63_000, profileId: otherProfileId, songId: "demo-coast-road"),
            entry("title:tidal", .title, tidal, text: "Night swims", at: daysAgo(10)),
            entry("review:tidal", .review, tidal, text: "Best at night with the window open. The tide on side B is real.",
                  at: daysAgo(12)),
            entry("album-rating:tidal", .albumrating, tidal, score: 8.6, at: daysAgo(12)),
            entry("album-note:lumen", .albumnote, lumen,
                  text: "Bought the vinyl at a night market in Taipei; the sleeve still smells of rain.", at: daysAgo(20)),
            entry("title:lumen", .title, lumen, text: "夏天的田野笔记", at: daysAgo(21)),
            entry("album-rating:lumen", .albumrating, lumen, score: 9.3, at: daysAgo(21)),
            entry("album-rating:neon", .albumrating, neon, score: 9.0, at: daysAgo(2)),
            entry("review:atlas", .review, atlas, text: "Blue hour turns to night halfway through Meridian.",
                  at: daysAgo(30)),
            entry("title:harbour", .title, harbour, text: "Nights by the harbour", at: daysAgo(15), profileId: otherProfileId),
            entry("album-rating:harbour", .albumrating, harbour, score: 7.8, at: daysAgo(15), profileId: otherProfileId),
        ]
        let aiTitles = [YoinAiTitle(profileId: activeProfileId, provider: atlas.provider, albumId: atlas.albumId,
                                    title: "Night maps")]
        return SearchLedgerSnapshot(entries: entries, aiTitles: aiTitles, covers: covers)
    }

    /// Plays [song] through the real engine from a silent file as long as the song (the mock has no streams).
    @MainActor
    static func play(_ song: PlaylistEntry, model: AppModel) {
        guard let url = try? silence(seconds: Double(song.track.durationSec)) else { return }
        let track = PlayableTrack(id: song.track.id.rawId, provider: song.track.id.provider, title: song.track.title,
                                  artist: song.track.artist ?? song.album.artist, album: song.album.title,
                                  albumId: song.album.id.rawId, durationSec: Double(song.track.durationSec),
                                  streamURL: url, coverURL: nil)
        model.player.play(queue: [track], startAt: 0)
    }

    private static func silence(seconds: Double) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("search-demo-silence-\(Int(seconds)).caf")
        if FileManager.default.fileExists(atPath: url.path) { return url }
        guard let format = AVAudioFormat(standardFormatWithSampleRate: 8000, channels: 1),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(8000 * seconds))
        else { throw CocoaError(.fileWriteUnknown) }
        buffer.frameLength = buffer.frameCapacity
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        try file.write(from: buffer)
        return url
    }
}
#endif
