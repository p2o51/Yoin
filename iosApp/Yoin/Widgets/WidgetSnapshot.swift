import Foundation
import SwiftUI

/*
 * Home screen widgets (Android widget/, parity A-F10): what the app hands its widget extension. The app
 * writes one snapshot — the account's recent listening (Play) and its album memories (Memory) — with each
 * cover as a small image file beside it, in the App Group's container; the extension only reads them. No
 * URLs (a Subsonic cover URL carries the sign-in), no credentials, no network in the extension.
 *
 * This file is compiled into both the app and the extension.
 */
struct WidgetSnapshot: Codable, Equatable, Sendable {
    static let currentVersion = 1

    var version = WidgetSnapshot.currentVersion
    /// The account the snapshot is of (its profile id, never a credential).
    var account: String
    /// Recent listening, newest first (Android WidgetData.playItems).
    var play: [Play]
    /// Album memories, most recently heard first (Android WidgetData.memoryItems).
    var memories: [Memory]
    /// The app's neutral colours (Yoin's brand scheme), light and dark: what Play and the timeline sit on.
    var neutral: Neutral

    /// One "keep listening" item.
    struct Play: Codable, Equatable, Sendable, Identifiable {
        enum Entity: String, Codable, Sendable { case album, playlist, artist }
        var entity: Entity
        var provider: String
        var id: String
        var title: String
        var subtitle: String
        /// The cover's file in the snapshot's covers folder.
        var cover: String?
        /// Spotify artwork: nothing is drawn over it.
        var spotify: Bool
    }

    /// One album memory.
    struct Memory: Codable, Equatable, Sendable, Identifiable {
        var provider: String
        var albumId: String
        var albumName: String
        var artist: String?
        var cover: String?
        var spotify: Bool
        /// The album's score, else the tracks' average; nil = unrated (no score anywhere).
        var score: Double?
        /// The last play in Yoin (epoch ms); nil = never played here.
        var lastHeardAt: Int64?
        /// The listener's own or the generated title, when there is one.
        var title: String?
        var palette: Palette
        var id: String { "\(provider):\(albumId)" }
    }

    /// A cover's colours as ARGB (Android WidgetPalette).
    struct Palette: Codable, Equatable, Sendable {
        var base: UInt32
        var accent: UInt32
        var deep: UInt32
        var soft: UInt32

        static let fallback = Palette(base: 0xFF6B_5B95, accent: 0xFFF1_D9A7, deep: 0xFF2C_2540, soft: 0xFFE9_E2F2)
    }

    struct Neutral: Codable, Equatable, Sendable {
        struct Colors: Codable, Equatable, Sendable {
            var background: UInt32
            var onSurface: UInt32
            var onSurfaceVariant: UInt32
            var primary: UInt32
            var onPrimary: UInt32
            var shelf: UInt32
            var outline: UInt32
        }
        var light: Colors
        var dark: Colors

        static let fallback = Neutral(
            light: Colors(background: 0xFFF7_F2FA, onSurface: 0xFF1D_1B20, onSurfaceVariant: 0xFF49_4551,
                          primary: 0xFF6E_4BC4, onPrimary: 0xFFFF_FFFF, shelf: 0xFFE8_DEF8, outline: 0xFFCA_C4D0),
            dark: Colors(background: 0xFF1D_1B20, onSurface: 0xFFE6_E0E9, onSurfaceVariant: 0xFFCA_C4D0,
                         primary: 0xFFD0_BCFF, onPrimary: 0xFF38_1E72, shelf: 0xFF4A_4458, outline: 0xFF49_4551))
    }

    static let empty = WidgetSnapshot(account: "", play: [], memories: [], neutral: .fallback)
}

extension WidgetSnapshot.Play {
    var key: String { "\(entity.rawValue):\(provider):\(id)" }
}

/// Where the snapshot lives: the App Group shared by the app and the extension.
enum WidgetStore {
    static let appGroup = "group.com.gpo.yoin"
    /// Widget kinds (WidgetCenter reloads them by kind).
    static let playKind = "com.gpo.yoin.widget.play"
    static let memoryKind = "com.gpo.yoin.widget.memory"

    static var folder: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?
            .appendingPathComponent("Widgets", isDirectory: true)
    }

    static func snapshotURL(in folder: URL) -> URL { folder.appendingPathComponent("snapshot.json") }
    static func coversURL(in folder: URL) -> URL { folder.appendingPathComponent("covers", isDirectory: true) }

    static func encode(_ s: WidgetSnapshot) throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return try e.encode(s)
    }

    /// A snapshot written by another version, or unreadable: none (the widget shows its empty state).
    static func decode(_ data: Data) -> WidgetSnapshot? {
        guard let s = try? JSONDecoder().decode(WidgetSnapshot.self, from: data),
              s.version == WidgetSnapshot.currentVersion else { return nil }
        return s
    }

    static func read(from folder: URL? = folder) -> WidgetSnapshot? {
        guard let folder, let data = try? Data(contentsOf: snapshotURL(in: folder)) else { return nil }
        return decode(data)
    }

    static func cover(_ name: String?, in folder: URL? = folder) -> URL? {
        guard let name, let folder else { return nil }
        return coversURL(in: folder).appendingPathComponent(name)
    }
}

/// Taps from a widget: what the app opens (yoin://widget/…, handled in YoinApp's onOpenURL).
enum WidgetLink: Equatable, Sendable {
    case open(entity: WidgetSnapshot.Play.Entity, provider: String, id: String)
    case play(entity: WidgetSnapshot.Play.Entity, provider: String, id: String)
    case memory(provider: String, albumId: String)
    case app

    var url: URL {
        var c = URLComponents()
        c.scheme = "yoin"
        c.host = "widget"
        switch self {
        case .open(let e, let p, let id), .play(let e, let p, let id):
            c.path = self.isPlay ? "/play" : "/open"
            c.queryItems = [.init(name: "entity", value: e.rawValue), .init(name: "provider", value: p),
                            .init(name: "id", value: id)]
        case .memory(let p, let id):
            c.path = "/memory"
            c.queryItems = [.init(name: "provider", value: p), .init(name: "id", value: id)]
        case .app:
            c.path = "/app"
        }
        return c.url!
    }

    private var isPlay: Bool { if case .play = self { true } else { false } }

    init?(url: URL) {
        guard url.scheme == "yoin", url.host == "widget" else { return nil }
        let q = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func v(_ n: String) -> String? { q.first { $0.name == n }?.value }
        switch url.path {
        case "/open", "/play":
            guard let e = v("entity").flatMap(WidgetSnapshot.Play.Entity.init(rawValue:)),
                  let p = v("provider"), let id = v("id"), !id.isEmpty else { return nil }
            self = url.path == "/play" ? .play(entity: e, provider: p, id: id) : .open(entity: e, provider: p, id: id)
        case "/memory":
            guard let p = v("provider"), let id = v("id"), !id.isEmpty else { return nil }
            self = .memory(provider: p, albumId: id)
        case "/app":
            self = .app
        default:
            return nil
        }
    }
}

/// The widgets' words (Android widget_* strings).
enum WidgetCopy {
    /// "9.2": one decimal, half up, as the Memories seal reads it.
    static func score(_ value: Double) -> String {
        let tenths = Int((value * 10).rounded(.toNearestOrAwayFromZero))
        return "\(tenths / 10).\(abs(tenths % 10))"
    }

    /// "3 Months Ago", "Today": how long since it was last heard; nil when never played in Yoin.
    static func lastHeard(_ at: Int64?, now: Date = Date()) -> String? {
        guard let at else { return nil }
        let days = max(0, Int((now.timeIntervalSince1970 * 1000 - Double(at)) / 86_400_000))
        if days < 1 { return String(localized: "Today") }
        if days < 31 { return String(localized: "\(days) Days Ago") }
        if days < 365 { return String(localized: "\(max(1, days / 30)) Months Ago") }
        return String(localized: "\(days / 365) Years Ago")
    }
}

extension Color {
    init(argb: UInt32) {
        self.init(.sRGB,
                  red: Double((argb >> 16) & 0xFF) / 255,
                  green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255,
                  opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}
