#if DEBUG
import AppKit
import Foundation

/// Debug only (`-spotifyExperiment YES`, compiled out of Release): runs the Spotify Connect play-sequence
/// experiments against this Mac's Spotify app with the token Yoin already holds, and writes each request,
/// status and what /me/player shows 0.5 / 2.5 / 5 s later to Application Support/Yoin/spotify-experiments.txt
/// (the sandbox container). Never targets another device: it stops when one is active. The desktop's
/// Spotify volume goes to 0 first (re-checked after every play) and back to what it was at the end.
@MainActor
enum MacSpotifyExperiment {
    private static var out = ""
    private static var t0 = Date()

    static func runIfAsked(_ model: MacModel) {
        let d = UserDefaults.standard
        if d.bool(forKey: "spotifyE2E") || d.object(forKey: "spotifyE2EAfter") != nil {
            Task {
                for _ in 0..<60 where model.session.spotify == nil { try? await Task.sleep(for: .milliseconds(500)) }
                guard let s = model.session.spotify else { line("no Spotify account in use"); return }
                if let v = d.object(forKey: "spotifyE2EAfter") as? String, let restore = Int(v) {
                    await after(model, s, restoreVolume: restore)
                } else {
                    await e2e(model, s)
                }
            }
            return
        }
        guard d.bool(forKey: "spotifyExperiment") else { return }
        Task {
            for _ in 0..<60 where model.session.spotify == nil { try? await Task.sleep(for: .milliseconds(500)) }
            guard let s = model.session.spotify else { line("no Spotify account in use"); flush(); return }
            await run(s)
            flush()
        }
    }

    private static func line(_ s: String) {
        let t = String(format: "%7.2f", Date().timeIntervalSince(t0))
        out += "[\(t)] \(s)\n"
        // Also in the system log (subsystem com.gpo.yoin.mac): the container file is out of a shell's reach.
        PlayTrace.log("EXP [\(t)] \(s)")
    }

    private static func flush() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Yoin", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? out.write(to: dir.appendingPathComponent("spotify-experiments.txt"), atomically: true, encoding: .utf8)
    }

    private static func tail(_ id: String?) -> String { id.map { "…" + String($0.suffix(4)) } ?? "nil" }

    /// One request; logs method, path, body and status (never the token).
    @discardableResult
    private static func call(_ s: SpotifySource, _ method: String, _ path: String, _ q: [String: String] = [:],
                             body: [String: Any]? = nil) async -> Int {
        let json = body.flatMap { try? JSONSerialization.data(withJSONObject: $0, options: [.sortedKeys]) }
        var shown = q
        if let d = shown["device_id"] { shown["device_id"] = tail(d) }
        let desc = "\(method) /\(path) \(shown.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: "&"))"
            + (json.map { " body=" + (String(data: $0, encoding: .utf8) ?? "") } ?? "")
        do {
            try await s.api.send(method, path, q, json: json)
            line("→ \(desc)  ⇒ 2xx")
            return 200
        } catch SpotifyAPIError.http(let status, let message) {
            line("→ \(desc)  ⇒ \(status) \(message ?? "")")
            return status
        } catch {
            line("→ \(desc)  ⇒ error \(error)")
            return -1
        }
    }

    struct Seen { var device: String?; var name: String; var playing: Bool; var uri: String?; var progress: Int }

    private static func player(_ s: SpotifySource) async -> Seen? {
        guard let data = try? await s.api.send("GET", "me/player"), !data.isEmpty,
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let dev = o["device"] as? [String: Any]
        let item = o["item"] as? [String: Any]
        return Seen(device: dev?["id"] as? String, name: dev?["name"] as? String ?? "?",
                    playing: o["is_playing"] as? Bool ?? false, uri: item?["uri"] as? String,
                    progress: o["progress_ms"] as? Int ?? 0)
    }

    private static func describe(_ p: Seen?) -> String {
        guard let p else { return "204 (nothing active)" }
        return "device=\(tail(p.device)) \(p.name) is_playing=\(p.playing) item=\(p.uri ?? "nil") progress=\(p.progress)"
    }

    private static func devices(_ s: SpotifySource) async -> [[String: Any]] {
        guard let data = try? await s.api.send("GET", "me/player/devices"),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
        return o["devices"] as? [[String: Any]] ?? []
    }

    private static var desktop = ""
    private static var aborted = false

    /// Reads /me/player at 0.5, 2.5 and 5 s after the last call, and the desktop's volume after a play.
    private static func observe(_ s: SpotifySource, expect: String?) async -> Bool {
        var marks: [Double] = [0.5, 2.5, 5]
        var last = 0.0
        var final: Seen?
        for m in marks {
            try? await Task.sleep(for: .milliseconds(Int((m - last) * 1000)))
            last = m
            final = await player(s)
            line("   @\(m)s  \(describe(final))")
            if let f = final, f.device != nil, f.device != desktop {
                line("!! another device became active — stopping")
                aborted = true
                return false
            }
            if !(await volumeIsZero(s)) { return false }
        }
        marks.removeAll()
        let ok = final?.playing == true && (expect == nil || final?.uri == expect)
        line("   result: \(ok ? "OK (playing the expected song)" : "NOT OK")")
        return ok
    }

    private static func volumeIsZero(_ s: SpotifySource) async -> Bool {
        let d = await devices(s).first { ($0["id"] as? String) == desktop }
        let v = d?["volume_percent"] as? Int
        if let v, v != 0 {
            line("!! desktop volume is \(v), not 0 — setting it back to 0")
            await call(s, "PUT", "me/player/volume", ["volume_percent": "0", "device_id": desktop])
        }
        return true
    }

    private static func engine(_ model: MacModel) -> String {
        let p = model.player
        return "engine: track=\(p.currentTrack.map { "\($0.title) (\($0.id))" } ?? "nil") playing=\(p.isPlaying) "
            + "position=\(String(format: "%.1f", p.progress.positionSec)) device=\(model.spotifyDevice ?? "this Mac")"
    }

    private static func step(_ model: MacModel, _ s: SpotifySource, _ what: String, wait: Double) async {
        try? await Task.sleep(for: .milliseconds(Int(wait * 1000)))
        line("   \(what) +\(wait)s  \(engine(model))")
        line("   \(what) +\(wait)s  spotify: \(describe(await player(s)))")
    }

    /// End to end through Yoin itself: an album from the account, then pause / play / next / seek, each
    /// read back from the engine and from Spotify. Leaves the desktop playing at volume 0 for the relaunch
    /// check (`-spotifyE2EAfter <volume>`).
    private static func e2e(_ model: MacModel, _ s: SpotifySource) async {
        t0 = Date()
        line("E2E (Mac Yoin drives Spotify itself)")
        guard let mine = await devices(s).first(where: { ($0["type"] as? String) == "Computer"
                && ($0["name"] as? String)?.caseInsensitiveCompare(Host.current().localizedName ?? "") == .orderedSame }),
              let id = mine["id"] as? String else { line("this Mac's Spotify app isn't a device"); return }
        desktop = id
        line("desktop \(tail(id)) volume=\(mine["volume_percent"].map { "\($0)" } ?? "nil")")
        await call(s, "PUT", "me/player/volume", ["volume_percent": "0", "device_id": id])
        // Something playing on the desktop first, so the start goes there (Yoin targets the active device).
        await call(s, "PUT", "me/player/play", ["device_id": id], body: ["uris": ["spotify:track:3dTyQ8CON5t5jreYf2GRhA"]])
        try? await Task.sleep(for: .seconds(3))
        await model.library.loadHome()
        for _ in 0..<50 where model.library.home.recentlyAdded.isEmpty && model.library.home.recentlyPlayed.isEmpty {
            try? await Task.sleep(for: .milliseconds(200))
        }
        guard let a = model.library.home.recentlyAdded.first ?? model.library.home.recentlyPlayed.first else {
            line("no album on Home"); return
        }
        let full = await model.library.fullAlbum(a)
        line("album: \(full.title) — \(full.tracks.count) songs; first \(full.tracks.first?.title ?? "-")")
        model.play(album: full)
        model.toggleNowPlaying()
        model.expandNowPlaying()
        await step(model, s, "album start", wait: 5)
        _ = await volumeIsZero(s)
        model.player.pause()
        await step(model, s, "pause", wait: 2.5)
        model.player.resume()
        await step(model, s, "play", wait: 2.5)
        model.player.next()
        await step(model, s, "next", wait: 5)
        _ = await volumeIsZero(s)
        model.player.seek(to: 60)
        await step(model, s, "seek 60", wait: 2.5)
        line("E2E part 1 done; desktop left playing at volume 0 (restore: \(mine["volume_percent"].map { "\($0)" } ?? "nil"))")
    }

    /// Launched while the desktop plays: Now Playing should show that song within 10 s.
    private static func after(_ model: MacModel, _ s: SpotifySource, restoreVolume: Int) async {
        t0 = Date()
        line("E2E part 2: relaunched while the desktop plays")
        line("   spotify: \(describe(await player(s)))")
        for i in 1...12 {
            try? await Task.sleep(for: .seconds(1))
            if model.player.currentTrack != nil { line("   shown after \(i)s  \(engine(model))"); break }
            if i == 12 { line("   nothing shown after 12 s  \(engine(model))") }
        }
        model.toggleNowPlaying()
        model.expandNowPlaying()
        try? await Task.sleep(for: .seconds(4))
        line("   \(engine(model))")
        model.player.pause()
        try? await Task.sleep(for: .seconds(2))
        line("   paused through Yoin: \(describe(await player(s)))")
        if let id = await devices(s).first(where: { ($0["type"] as? String) == "Computer" })?["id"] as? String {
            await call(s, "PUT", "me/player/volume", ["volume_percent": String(restoreVolume), "device_id": id])
        }
        line("E2E part 2 done")
    }

    private static func run(_ s: SpotifySource) async {
        t0 = Date()
        line("Spotify play-sequence experiments (Mac Yoin, debug hook). Target: this Mac's Spotify app only.")
        let host = Host.current().localizedName ?? ""
        let all = await devices(s)
        for d in all {
            line("device \(tail(d["id"] as? String)) name=\(d["name"] as? String ?? "?") type=\(d["type"] as? String ?? "?") active=\(d["is_active"] as? Bool ?? false) volume=\(d["volume_percent"].map { "\($0)" } ?? "nil")")
        }
        guard let mine = all.first(where: { ($0["type"] as? String) == "Computer"
                && ($0["name"] as? String)?.caseInsensitiveCompare(host) == .orderedSame }),
              let id = mine["id"] as? String else {
            line("this Mac's Spotify app isn't a device — stopping"); return
        }
        desktop = id
        let originalVolume = mine["volume_percent"] as? Int
        line("desktop = \(tail(id)), original volume_percent = \(originalVolume.map(String.init) ?? "nil")")
        if let other = all.first(where: { ($0["is_active"] as? Bool) == true && ($0["id"] as? String) != id }) {
            line("!! another device is active (\(other["name"] as? String ?? "?")) — leaving it alone, stopping")
            return
        }

        // Songs: the listener's recent ones, four different tracks.
        var uris: [String] = []
        if let data = try? await s.api.send("GET", "me/player/recently-played", ["limit": "50"]),
           let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let items = o["items"] as? [[String: Any]] {
            for i in items {
                if let u = (i["track"] as? [String: Any])?["uri"] as? String, !uris.contains(u) { uris.append(u) }
            }
        }
        guard uris.count >= 5 else { line("not enough recent songs"); return }
        let (A, B, C, D, E) = (uris[0], uris[1], uris[2], uris[3], uris[4])
        line("songs A=\(A) B=\(B) C=\(C) D=\(D) E=\(E)")

        // Silence first: Spotify's own volume on the desktop (the Mac's volume isn't touched).
        await call(s, "PUT", "me/player/volume", ["volume_percent": "0", "device_id": id])
        try? await Task.sleep(for: .seconds(1))
        let v = await devices(s).first { ($0["id"] as? String) == id }?["volume_percent"] as? Int
        line("desktop volume_percent read back = \(v.map(String.init) ?? "nil")")
        guard v == 0 else { line("!! volume isn't 0 — stopping before any play"); return }

        func playing(_ song: String) async {
            line("-- setup: desktop plays \(song)")
            await call(s, "PUT", "me/player/play", ["device_id": id], body: ["uris": [song]])
            _ = await observe(s, expect: song)
        }

        defer {}
        var results: [String] = []
        func record(_ name: String, _ ok: Bool) { results.append("\(name): \(ok ? "OK" : "FAIL")") }

        // (a) play [B,C] position_ms 0, over A, ×3
        for i in 1...3 where !aborted {
            line("== (a) run \(i): play over a playing song, uris [B,C] position_ms 0, no modes")
            await playing(A); if aborted { break }
            await call(s, "PUT", "me/player/play", ["device_id": id], body: ["uris": [B, C], "position_ms": 0])
            record("(a)\(i)", await observe(s, expect: B))
        }
        // (b) modes right after play (the old order) ×2
        for i in 1...2 where !aborted {
            line("== (b) run \(i): play [B,C] position_ms 0, then shuffle=false, repeat=off")
            await playing(A); if aborted { break }
            await call(s, "PUT", "me/player/play", ["device_id": id], body: ["uris": [B, C], "position_ms": 0])
            await call(s, "PUT", "me/player/shuffle", ["state": "false", "device_id": id])
            await call(s, "PUT", "me/player/repeat", ["state": "off", "device_id": id])
            record("(b)\(i)", await observe(s, expect: B))
        }
        // (c) modes before play ×2
        for i in 1...2 where !aborted {
            line("== (c) run \(i): shuffle=false, repeat=off, then play [B,C] position_ms 0")
            await playing(A); if aborted { break }
            await call(s, "PUT", "me/player/shuffle", ["state": "false", "device_id": id])
            await call(s, "PUT", "me/player/repeat", ["state": "off", "device_id": id])
            await call(s, "PUT", "me/player/play", ["device_id": id], body: ["uris": [B, C], "position_ms": 0])
            record("(c)\(i)", await observe(s, expect: B))
        }
        // (d) body variants
        let variants: [(String, [String: Any], String)] = [
            ("offset {position:0}", ["uris": [C, D], "offset": ["position": 0]], C),
            ("no position_ms", ["uris": [D, E]], D),
            ("single uri", ["uris": [E]], E),
            ("single uri + position_ms 0", ["uris": [B], "position_ms": 0], B),
        ]
        for (name, body, expect) in variants where !aborted {
            line("== (d) \(name)")
            await playing(A); if aborted { break }
            await call(s, "PUT", "me/player/play", ["device_id": id], body: body)
            record("(d) \(name)", await observe(s, expect: expect))
        }
        // (e) idle: paused for 15 s, then play with device_id
        if !aborted {
            line("== (e) desktop paused 15 s, then play [C] with device_id")
            await call(s, "PUT", "me/player/pause", ["device_id": id])
            try? await Task.sleep(for: .seconds(15))
            line("   before: \(describe(await player(s)))")
            await call(s, "PUT", "me/player/play", ["device_id": id], body: ["uris": [C]])
            record("(e) idle", await observe(s, expect: C))
        }
        // (f) commands right after a switch
        if !aborted {
            line("== (f) pause / play / next / seek right after a switch")
            await playing(A)
            if !aborted {
                await call(s, "PUT", "me/player/play", ["device_id": id], body: ["uris": [B, C]])
                await call(s, "PUT", "me/player/pause", ["device_id": id])
                line("   pause +0: \(describe(await player(s)))")
                try? await Task.sleep(for: .milliseconds(800))
                await call(s, "PUT", "me/player/play", ["device_id": id])
                try? await Task.sleep(for: .milliseconds(300))
                await call(s, "POST", "me/player/next", ["device_id": id])
                try? await Task.sleep(for: .milliseconds(300))
                await call(s, "PUT", "me/player/seek", ["position_ms": "30000", "device_id": id])
                record("(f) next→C", await observe(s, expect: C))
                line("   (f) again with 2 s between commands")
                await call(s, "PUT", "me/player/play", ["device_id": id], body: ["uris": [D, E]])
                try? await Task.sleep(for: .seconds(2))
                await call(s, "PUT", "me/player/pause", ["device_id": id])
                try? await Task.sleep(for: .seconds(2))
                await call(s, "PUT", "me/player/play", ["device_id": id])
                try? await Task.sleep(for: .seconds(2))
                await call(s, "POST", "me/player/next", ["device_id": id])
                try? await Task.sleep(for: .seconds(2))
                await call(s, "PUT", "me/player/seek", ["position_ms": "30000", "device_id": id])
                record("(f) spaced next→E", await observe(s, expect: E))
            }
        }

        // Leave it as it was: paused, the volume back.
        line("== end: pause, restore volume")
        await call(s, "PUT", "me/player/pause", ["device_id": id])
        if let originalVolume {
            await call(s, "PUT", "me/player/volume", ["volume_percent": String(originalVolume), "device_id": id])
        }
        line("final: \(describe(await player(s)))")
        line("SUMMARY")
        for r in results { line("  " + r) }
    }
}
#endif
