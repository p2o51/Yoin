import Foundation
import SwiftUI

/*
 * Jump Back In's "not now", ported from Android (HomeJbiHidden.kt, owner 2026-10-13): in edit mode a tap on a
 * card opens a menu over it, hide it for three days or a week. Never for good: what the shelf picks is in
 * the library, and the library is where it leaves for good. Kept per account scope (provider|profile), by
 * what the card opens, so the same album hides whether it comes up as a cover or as its memory card.
 */

/// How long a hidden Jump Back In card stays away.
enum JbiHideSpan: CaseIterable, Hashable, Sendable {
    case threeDays, oneWeek

    var days: Int { self == .threeDays ? 3 : 7 }
    var millis: Int64 { Int64(days) * 24 * 60 * 60 * 1000 }

    var title: String {
        switch self {
        case .threeDays: String(localized: "Hide for 3 days")
        case .oneWeek: String(localized: "Hide for a week")
        }
    }
}

/// Which Jump Back In cards are away, until when, per account scope. UserDefaults, one dictionary:
/// `scope␟key` → epoch ms until which the card stays hidden (Android's SharedPreferences, same shape).
final class JbiHiddenStore: @unchecked Sendable {
    static let shared = JbiHiddenStore()

    private let defaults: UserDefaults
    private let storeKey: String
    private let lock = NSLock()
    private static let separator = "\u{1F}"

    init(defaults: UserDefaults = .standard, storeKey: String = "home_jbi_hidden") {
        self.defaults = defaults
        self.storeKey = storeKey
    }

    /// The keys hidden in [scope] past [now]; spans that ran out (in any scope) are dropped.
    func hidden(scope: String, now: Int64) -> Set<String> {
        lock.lock(); defer { lock.unlock() }
        let all = read()
        let prefix = scope + Self.separator
        var live = all
        var out = Set<String>()
        for (entry, until) in all {
            if until <= now {
                live[entry] = nil
            } else if entry.hasPrefix(prefix) {
                out.insert(String(entry.dropFirst(prefix.count)))
            }
        }
        if live.count != all.count { write(live) }
        return out
    }

    func hide(scope: String, key: String, until: Int64) {
        lock.lock(); defer { lock.unlock() }
        var all = read()
        all[scope + Self.separator + key] = until
        write(all)
    }

    private func read() -> [String: Int64] {
        (defaults.dictionary(forKey: storeKey) ?? [:]).compactMapValues { ($0 as? NSNumber)?.int64Value }
    }

    private func write(_ all: [String: Int64]) {
        defaults.set(all.mapValues { NSNumber(value: $0) }, forKey: storeKey)
    }
}

// MARK: - What a hidden card is known by

/// Android `jbiAlbumHideKey` / `jbiSongHideKey`: the album, song or playlist a card opens. A memory card is
/// its album, a noted-track card its song, so hiding one hides the item wherever the shelf would bring it back.
func jbiAlbumHideKey(_ id: MediaId) -> String { "album:\(id.provider):\(id.rawId)" }
func jbiSongHideKey(_ id: MediaId) -> String { "song:\(id.provider):\(id.rawId)" }
func jbiPlaylistHideKey(_ id: MediaId) -> String { "playlist:\(id.provider):\(id.rawId)" }

extension JBIItem {
    var hideKey: String {
        switch self {
        case let .album(a): jbiAlbumHideKey(a.id)
        case let .track(e): jbiSongHideKey(e.track.id)
        case let .playlist(p): jbiPlaylistHideKey(p.id)
        }
    }
}

extension JBISignal {
    var hideKey: String {
        switch kind {
        case .ratedAlbum: jbiAlbumHideKey(album.id)
        case .notedTrack: jbiSongHideKey(song?.track.id ?? album.id)
        }
    }
}

extension JBICard {
    var hideKey: String {
        switch self {
        case let .signal(s): s.hideKey
        case let .cover(c): c.hideKey
        }
    }
}

extension HomeFeed {
    /// The feed without the Jump Back In cards [hidden] names (the mock shelf, which is not built from lists).
    func removingHiddenJbi(_ hidden: Set<String>) -> HomeFeed {
        guard !hidden.isEmpty else { return self }
        var feed = self
        feed.jumpBackIn = jumpBackIn.filter { !hidden.contains($0.hideKey) }
        feed.signals = signals.filter { !hidden.contains($0.hideKey) }
        return feed
    }
}

// MARK: - The menu

extension AnyTransition {
    /// A hidden card folds away: fades on an effects spring while it shrinks a little.
    @MainActor static var jbiRemoval: AnyTransition {
        .opacity.animation(Yoin.effects).combined(with: .scale(scale: 0.92))
    }
}

/// A Jump Back In card's tap: in edit mode it opens the "not now" menu over the card (Android's edit-mode
/// bubble), otherwise it is the card's own action (still swallowed under the hold that entered edit).
@MainActor func jbiCardTap(_ edit: HomeEditController?, open: Binding<Bool>, _ action: () -> Void) {
    if let edit, edit.isEditing {
        if edit.acceptsCardTap { open.wrappedValue = true }
        return
    }
    homeTap(edit, action)
}

private struct JbiHideMenu: ViewModifier {
    @Environment(AppModel.self) private var model
    var key: String
    @Binding var open: Bool

    func body(content: Content) -> some View {
        content.popover(isPresented: $open, attachmentAnchor: .rect(.bounds), arrowEdge: .top) {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(JbiHideSpan.allCases, id: \.self) { span in
                    Button {
                        open = false
                        withAnimation(Yoin.spatial) { model.store.hideJbi(key: key, span: span) }
                    } label: {
                        Text(span.title)
                            .font(.body)
                            .padding(.horizontal, 16).padding(.vertical, 12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("jbi-hide-\(span.days)")
                }
            }
            .frame(minWidth: 200)
            .presentationCompactAdaptation(.popover)
        }
    }
}

extension View {
    func jbiHideMenu(key: String, open: Binding<Bool>) -> some View { modifier(JbiHideMenu(key: key, open: open)) }
}
