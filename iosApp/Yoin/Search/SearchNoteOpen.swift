import SwiftUI
import YoinShared

/*
 * Opening what Library search's Memories & notes found (Android YoinNavHost openSearchNote /
 * openSearchLedgerAlbum, SearchNotePlayback.kt). Where a note leads is :shared's (`noteTarget`): a note on a song
 * the service returned plays that song and seeks to the note's second once the song is current
 * (`noteSeekReady`, given up after `noteSeekTimeoutMs`); a note standing alone opens its album's scrapbook at the
 * song, in the note's account. Another account's switches first, behind the one-off notice (AccountSwitchGate).
 */

extension AppModel {
    /// The account search places the ledger's hits against: the one in use (a demo's own under
    /// `-searchMemoriesDemo`).
    var searchProfileId: String? {
        #if DEBUG
        if SearchMemoriesDemo.isOn { return SearchMemoriesDemo.activeProfileId }
        #endif
        return profiles.activeId
    }

    /// A note tile: [song] = the returned song its tile is on (nil for a note standing alone). [leave] folds the
    /// search surface away as the song plays or the album opens (after the switch notice, which comes out of the tile).
    func openSearchNote(_ note: YoinNoteHit, song: PlaylistEntry?, gate: AccountSwitchGate, anchor: String,
                        leave: @escaping @MainActor () -> Void) {
        let target = YoinMemorySearch.companion.noteTarget(
            note: note, returnedSongProvider: song?.track.id.provider, returnedSongId: song?.track.id.rawId,
            activeProfileId: searchProfileId)
        if target.action == .playsong, let song {
            leave()
            playSearchNote(song, seekToMs: target.seekToMs?.int64Value)
        } else if target.action == .openalbum, let provider = target.albumProvider, let albumId = target.albumId {
            let songId = target.songProvider.flatMap { p in target.songId.map { MediaId(provider: p, rawId: $0) } }
            let album = searchAlbum(MediaId(provider: provider, rawId: albumId), named: note.album)
            openInSearchAccount(target.profileId, subject: target.subject ?? album.title, gate: gate, anchor: anchor,
                                provider: provider) { [weak self] in
                leave()
                self?.open(album)
                // The scrapbook (page 2), at the song's piece when the note knows it (View in Scrapbook's way).
                self?.focusScrapbook(album.id, song: songId)
            }
        }
    }

    /// A memory tile (an album known from the ledger): its scrapbook, in its own account.
    func openSearchLedgerAlbum(_ ledger: YoinLedgerAlbum, gate: AccountSwitchGate, anchor: String,
                               leave: @escaping @MainActor () -> Void) {
        let album = searchAlbum(MediaId(provider: ledger.provider, rawId: ledger.albumId), named: ledger)
        openInSearchAccount(ledger.profileId, subject: ledger.name, gate: gate, anchor: anchor,
                            provider: ledger.provider) { [weak self] in
            leave()
            self?.open(album)
            self?.focusScrapbook(album.id, song: nil)
        }
    }

    /// A returned album: holding a memory, it opens on its scrapbook (album page 2).
    func openSearchAlbum(_ album: Album, holdsMemory: Bool) {
        open(album)
        if holdsMemory { focusScrapbook(album.id, song: nil) }
    }

    /// Asks the album page just opened for its scrapbook, at [song]'s piece when known. Asked once the page is up
    /// (Search folding away while the page is pushed): a request already waiting as the page was pushed left it on
    /// page 1 now and then (2026-11-25 simulator QA), the way View in Scrapbook asks a page already open.
    private func focusScrapbook(_ album: MediaId, song: MediaId?) {
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(Int(450 / LaunchConfig.animationSpeed)))
            if let song {
                ScrapbookFocusRequest.shared.request(songId: song, openSheet: false)
            } else {
                ScrapbookFocusRequest.shared.requestAlbum(album)
            }
        }
    }

    /// Plays [song] alone (Android playSearchResults(listOf(track))), then seeks to [seekToMs] once it is current.
    func playSearchNote(_ song: PlaylistEntry, seekToMs: Int64?) {
        #if DEBUG
        if SearchMemoriesDemo.isOn { SearchMemoriesDemo.play(song, model: self) } else { play([song], startAt: 0) }
        #else
        play([song], startAt: 0)
        #endif
        guard let seekToMs, seekToMs > 0 else { return }
        let rawId = song.track.id.rawId
        Task { await seekWhenCurrent(rawId: rawId, positionMs: seekToMs) }
    }

    /// Seeks to [positionMs] once [rawId] is the current song and its length is known (`noteSeekReady`); gives up
    /// after [timeoutMs] (a provider that never reports it). Android PlaybackManager.seekWhenCurrent.
    func seekWhenCurrent(rawId: String, positionMs: Int64,
                         timeoutMs: Int64 = YoinMemorySearch.companion.noteSeekTimeoutMs) async {
        guard positionMs > 0 else { return }
        let clock = ContinuousClock()
        let deadline = clock.now + .milliseconds(timeoutMs)
        while !Task.isCancelled {
            let durationMs = Int64((player.progress.durationSec * 1000).rounded())
            if YoinMemorySearch.companion.noteSeekReady(currentRawId: player.currentTrack?.id, durationMs: durationMs,
                                                        targetRawId: rawId) {
                player.seek(to: Double(positionMs) / 1000)
                return
            }
            guard clock.now < deadline else { return }
            try? await Task.sleep(for: .milliseconds(50))
        }
    }

    /// Runs [open] in [profileId]'s account: straight away when it is the account in use (or no longer exists,
    /// Android openInAccount), else after the switch lands.
    private func openInSearchAccount(_ profileId: String, subject: String, gate: AccountSwitchGate, anchor: String,
                                     provider: String, open: @escaping @MainActor () -> Void) {
        #if DEBUG
        if SearchMemoriesDemo.isOn {
            guard profileId != SearchMemoriesDemo.activeProfileId else { return open() }
            return gate.request(from: anchor, account: SearchMemoriesDemo.profileName(profileId), subject: subject,
                                opensAlbum: true) {}
        }
        #endif
        guard let owner = profiles.profile(profileId), profileId != profiles.activeId else { return open() }
        gate.request(from: anchor, account: owner.title, subject: subject, opensAlbum: true) { [weak self] in
            Task { @MainActor in
                guard let self, await self.switchAccount(to: profileId, provider: provider) else { return }
                open()
            }
        }
    }

    /// The album loaded, else a placeholder its page loads (as All Memories and note mentions open one).
    private func searchAlbum(_ id: MediaId, named ledger: YoinLedgerAlbum?) -> Album {
        if id.provider == "mock", let mock = MockLibrary.allAlbums.first(where: { $0.id == id }) { return mock }
        if let loaded = store.album(id.rawId) { return loaded }
        return Album(id: id, title: ledger?.name ?? "", artist: ledger?.artist ?? "", year: 0,
                     palette: store.placeholderPalette(coverId: ledger?.coverKey), tracks: [])
    }
}
