import CryptoKit
import SwiftUI
import UIKit
import WidgetKit

/*
 * The app's side of the widgets (Android WidgetRefresher): watches what they show — the account's recent
 * listening and album memories, the active account — and, a beat after it changes, writes a new snapshot
 * (WidgetSnapshot) with each cover as a small JPEG, then asks WidgetKit to reload. Nothing runs per playback
 * tick, and an unchanged snapshot reloads nothing. The extension ages "3 Months Ago" on its own timeline.
 */
@MainActor
final class WidgetPublisher {
    static let shared = WidgetPublisher()

    /// Play shows up to six items, Memory up to twelve (Android's limits).
    static let playLimit = 6
    static let memoryLimit = 12
    /// A burst of changes (a play logs several rows) writes once (Android REFRESH_DEBOUNCE_MS).
    static let debounce: Duration = .seconds(2)
    /// Cover files' side: a large widget's cover is 96 pt; 3× is plenty.
    static let coverPixels: CGFloat = 288

    struct PlaySource: Equatable {
        var item: WidgetSnapshot.Play
        var cover: CoverRef?
    }

    struct MemorySource: Equatable {
        var item: WidgetSnapshot.Memory
        var cover: CoverRef?
        var palette: CoverPalette
    }

    /// What the widgets are built from, as read from the stores.
    struct Input: Equatable {
        var account: String
        var play: [PlaySource]
        var memories: [MemorySource]
    }

    private weak var model: AppModel?
    private var last: Input?
    private var pending: Task<Void, Never>?

    func start(_ model: AppModel) {
        self.model = model
        WidgetRequests.handler = { [weak model] link in model?.handleWidget(link) }
        observe()
    }

    private func observe() {
        guard let model else { return }
        let input = withObservationTracking { Self.input(model) } onChange: {
            Task { @MainActor [weak self] in self?.observe() }
        }
        guard input != last else { return }
        last = input
        pending?.cancel()
        pending = Task { [weak self] in
            try? await Task.sleep(for: Self.debounce)
            guard !Task.isCancelled else { return }
            await self?.publish(input)
        }
    }

    // MARK: Reading the stores

    static func input(_ model: AppModel) -> Input {
        let store = model.store
        guard store.isReal, let account = model.profiles.activeId else { return Input(account: "", play: [], memories: []) }
        var seen = Set<String>()
        let play: [PlaySource] = (store.home?.activities ?? []).compactMap { e -> PlaySource? in
            let entity: WidgetSnapshot.Play.Entity
            switch e.entity {
            case .album, .song: entity = .album
            case .playlist: entity = .playlist
            case .artist: entity = .artist
            }
            let id = e.entity == .song ? e.album.id.rawId : e.targetId
            guard !id.isEmpty, !id.hasPrefix("song:") else { return nil }
            let cover = e.album.palette.cover
            let item = WidgetSnapshot.Play(entity: entity, provider: e.album.id.provider, id: id, title: e.album.title,
                                           subtitle: e.album.artist, cover: nil, spotify: cover?.isSpotify == true)
            guard seen.insert(item.key).inserted else { return nil }
            return PlaySource(item: item, cover: cover)
        }
        let memoryStore = AlbumMemoryStore.shared
        let memories = Self.memoryOrder(store.memories).map { m -> MemorySource in
            let provider = m.album.id.provider
            let score = m.albumRating.flatMap { $0 > 0 ? $0 : nil } ?? m.averageSongRating.flatMap { $0 > 0 ? $0 : nil }
            let item = WidgetSnapshot.Memory(
                provider: provider, albumId: m.albumId, albumName: m.album.title,
                artist: m.album.artist.isEmpty ? nil : m.album.artist, cover: nil,
                spotify: m.album.palette.cover?.isSpotify == true, score: score, lastHeardAt: m.lastPlayedAt,
                title: memoryStore.memoryTitle(provider: provider, albumId: m.albumId), palette: .fallback)
            return MemorySource(item: item, cover: m.album.palette.cover, palette: m.album.palette)
        }
        return Input(account: account, play: Array(play.prefix(playLimit)), memories: memories)
    }

    /// Android: the eligible memories (else every memory with something kept), most recently heard first.
    static func memoryOrder(_ all: [AlbumMemory]) -> [AlbumMemory] {
        var pool = all.filter(\.isMemoryEligible)
        if pool.isEmpty { pool = all.filter(HomeHistory.hasMemory) }
        return Array(pool.enumerated().sorted { a, b in
            let x = a.element.lastPlayedAt ?? a.element.firstPlayedAt ?? 0
            let y = b.element.lastPlayedAt ?? b.element.firstPlayedAt ?? 0
            return x != y ? x > y : a.offset < b.offset
        }.map(\.element).prefix(memoryLimit))
    }

    // MARK: Writing

    private func publish(_ input: Input) async {
        guard let folder = WidgetStore.folder else { return }
        let covers = WidgetStore.coversURL(in: folder)
        try? FileManager.default.createDirectory(at: covers, withIntermediateDirectories: true)
        var used = Set<String>()
        func file(_ ref: CoverRef?) async -> String? {
            guard let ref else { return nil }
            let name = Self.coverName(ref)
            let url = covers.appendingPathComponent(name)
            if !FileManager.default.fileExists(atPath: url.path) {
                await CoverArtwork.shared.load(ref)
                guard let image = CoverArtwork.shared.image(ref),
                      let data = Self.thumbnail(image)?.jpegData(compressionQuality: 0.85) else { return nil }
                try? data.write(to: url, options: .atomic)
            }
            used.insert(name)
            return name
        }
        var play: [WidgetSnapshot.Play] = []
        for s in input.play {
            var item = s.item
            item.cover = await file(s.cover)
            play.append(item)
        }
        var memories: [WidgetSnapshot.Memory] = []
        for s in input.memories {
            var item = s.item
            item.cover = await file(s.cover)
            // After the cover landed, its colours are the real ones.
            item.palette = Self.palette(s.palette)
            memories.append(item)
        }
        guard !Task.isCancelled else { return }
        let snapshot = WidgetSnapshot(account: input.account, play: play, memories: memories, neutral: Self.neutral())
        guard Self.shouldWrite(old: WidgetStore.read(from: folder), new: snapshot),
              let data = try? WidgetStore.encode(snapshot) else { return }
        try? data.write(to: WidgetStore.snapshotURL(in: folder), options: .atomic)
        Self.removeCovers(in: covers, keeping: used)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Reload only for a real change: the same snapshot again (the app opened, a row re-read) costs nothing.
    nonisolated static func shouldWrite(old: WidgetSnapshot?, new: WidgetSnapshot) -> Bool { old != new }

    /// A cover's file: its cache key hashed (the key has no sign-in; the URL would).
    nonisolated static func coverName(_ ref: CoverRef) -> String {
        let digest = SHA256.hash(data: Data(ref.key.utf8))
        return digest.prefix(12).map { String(format: "%02x", $0) }.joined() + ".jpg"
    }

    private static func thumbnail(_ image: UIImage) -> UIImage? {
        let side = min(image.size.width, image.size.height)
        guard side > 0 else { return nil }
        let target = min(coverPixels, side * image.scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: target, height: target), format: format).image { _ in
            // Centre-cropped square.
            let s = target / side
            let w = image.size.width * s, h = image.size.height * s
            image.draw(in: CGRect(x: (target - w) / 2, y: (target - h) / 2, width: w, height: h))
        }
    }

    private static func removeCovers(in folder: URL, keeping used: Set<String>) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        for n in names where !used.contains(n) {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(n))
        }
    }

    // MARK: Colours

    /// Android WidgetPalette from the cover's Memories colours (MemoryTones' base, deep and soft).
    static func palette(_ p: CoverPalette) -> WidgetSnapshot.Palette {
        let bd = BackdropColors(p)
        let base = MemoryTones.hold(UIColor(bd.base), min: 0.22, max: 0.46)
        let accent = MemoryTones.hold(UIColor(bd.accent), min: 0.62, max: 0.8)
        return .init(base: argb(base), accent: argb(accent), deep: argb(MemoryTones.mix(base, .black, 0.55)),
                     soft: argb(MemoryTones.mix(base, .white, 0.8)))
    }

    /// Yoin's brand scheme, light and dark (Android: the wallpaper's dynamic neutrals).
    static func neutral() -> WidgetSnapshot.Neutral {
        func colors(_ s: CoverScheme) -> WidgetSnapshot.Neutral.Colors {
            .init(background: argb(UIColor(s.surfaceContainer)), onSurface: argb(UIColor(s.onSurface)),
                  onSurfaceVariant: argb(UIColor(s.onSurfaceVariant)), primary: argb(UIColor(s.primary)),
                  onPrimary: argb(UIColor(s.onPrimary)), shelf: argb(UIColor(s.secondaryContainer)),
                  outline: argb(UIColor(s.outlineVariant)))
        }
        return .init(light: colors(.brand(dark: false)), dark: colors(.brand(dark: true)))
    }

    static func argb(_ c: UIColor) -> UInt32 {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        func ch(_ v: CGFloat) -> UInt32 { UInt32(max(0, min(255, (v * 255).rounded()))) }
        return ch(a) << 24 | ch(r) << 16 | ch(g) << 8 | ch(b)
    }
}

extension AppModel {
    /// A widget tap (WidgetLink): the page it names, Memories at that memory, or a play from it.
    func handleWidget(_ link: WidgetLink) {
        if npExpanded { setNowPlaying(expanded: false) }
        switch link {
        case .app:
            return
        case .memory(let provider, let albumId):
            guard provider == store.provider else { return }
            path.removeAll()
            section = .home
            memories.open(focus: albumId)
        case .open(let entity, let provider, let id), .play(let entity, let provider, let id):
            // A widget left over from another account opens the app as it is.
            guard provider == store.provider else { return }
            let play: Bool = if case .play = link { true } else { false }
            Task { await openFromWidget(entity, id: id, play: play) }
        }
    }

    private func openFromWidget(_ entity: WidgetSnapshot.Play.Entity, id: String, play: Bool) async {
        path.removeAll()
        switch entity {
        case .artist:
            openArtist("", id: id)
        case .album:
            await store.loadAlbum(id)
            guard let a = store.album(id) else { return }
            open(a)
            if play { self.play(a.tracks.map { PlaylistEntry(album: a, track: $0) }) }
        case .playlist:
            openPlaylist(id)
            guard play else { return }
            await store.loadPlaylist(id)
            if let p = store.playlist(id) { self.play(p.entries) }
        }
    }
}
