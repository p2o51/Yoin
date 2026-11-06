import SwiftUI

/// Android's tips (HomeBubbleTip, strings_home.xml), word for word: the same thirteen, in the same order,
/// each with the day it last changed. Not translated or reworded here.
enum MacTip: String, CaseIterable, Identifiable {
    case MiniPlayerMenu, FocusCover, Downloads, EditHome, PullForMemories, CoverMenu, SongMenu, PlayModes,
         NoteMoments, LibrarySearch, KeepScreenOn, ScrollEdge, Name

    var id: String { rawValue }

    var title: String {
        switch self {
        case .MiniPlayerMenu: "Mini player options"
        case .FocusCover: "Tap cover to Focus"
        case .Downloads: "Offline downloads"
        case .EditHome: "Rearrange Home"
        case .PullForMemories: "Memories"
        case .CoverMenu: "Song options"
        case .SongMenu: "Song quick actions"
        case .PlayModes: "Shuffle and repeat"
        case .NoteMoments: "Notes pin the second"
        case .LibrarySearch: "Quick search"
        case .KeepScreenOn: "Keep screen on"
        case .ScrollEdge: "Scroll edge"
        case .Name: "Behind the name"
        }
    }

    var text: String {
        switch self {
        case .MiniPlayerMenu: "Long-press the mini player in the bar to open Song options"
        case .FocusCover: "Tap the cover in Now Playing to enter Focus, and tap again to return"
        case .Downloads: "Subsonic accounts can download from song, album, or playlist menus; manage in Settings › Storage › Downloads"
        case .EditHome: "Long-press Home to rearrange sections"
        case .PullForMemories: "Pull down on Home to open Memories"
        case .CoverMenu: "Long-press the cover in Now Playing for Song options and quick actions"
        case .SongMenu: "Long-press a song in an album or playlist to play next, queue, favorite, or share"
        case .PlayModes: "Tap to switch shuffle and repeat; long-press to split them and toggle repeat off"
        case .NoteMoments: "Notes written in Now Playing stay pinned to that exact second"
        case .LibrarySearch: "Long-press Library in the bar to search right away"
        case .KeepScreenOn: "Keep screen on is in the cover's long-press menu"
        case .ScrollEdge: "Change the top wave line in Settings › Motion › Scroll edge"
        case .Name: "Yoin comes from the Japanese word \"余韻\" — a lingering resonance"
        }
    }

    /// Android `updated` (yyyy-MM-dd): a tip changed since it was read counts as unread again.
    var updated: String {
        switch self {
        case .MiniPlayerMenu, .Downloads, .CoverMenu, .SongMenu: "2026-11-04"
        case .FocusCover: "2026-11-03"
        case .PlayModes: "2026-10-21"
        default: "2026-10-17"
        }
    }

    /// Yoin Symbols nearest Android's (play_circle, open_in_full and memory have no Mac symbol yet).
    var icon: String {
        switch self {
        case .MiniPlayerMenu: "play_filled"
        case .FocusCover: "unfold_more"
        case .Downloads: "download"
        case .EditHome: "drag_handle"
        case .PullForMemories: "note"
        case .CoverMenu: "album"
        case .SongMenu: "queue"
        case .PlayModes: "shuffle"
        case .NoteMoments: "edit_note"
        case .LibrarySearch: "search"
        case .KeepScreenOn: "light_mode"
        case .ScrollEdge: "settings"
        case .Name: "sparkle"
        }
    }

    var updatedDate: Date? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.date(from: updated)
    }
}

/// Which tips were read on this Mac (Android TipReadStore: this device only), kept as "Name@day".
@MainActor
@Observable
final class MacTipReads {
    static let shared = MacTipReads()
    private static let key = "tipsRead"

    private(set) var saved: Set<String> = Set(UserDefaults.standard.stringArray(forKey: key) ?? [])

    func isRead(_ tip: MacTip) -> Bool { saved.contains("\(tip.rawValue)@\(tip.updated)") }
    var readCount: Int { MacTip.allCases.filter(isRead).count }

    func setRead(_ tip: MacTip, _ read: Bool) {
        saved = saved.filter { !$0.hasPrefix("\(tip.rawValue)@") }
        if read { saved.insert("\(tip.rawValue)@\(tip.updated)") }
        UserDefaults.standard.set(Array(saved), forKey: Self.key)
    }

    func setAllUnread() {
        saved = []
        UserDefaults.standard.set([String](), forKey: Self.key)
    }

    /// After [current], the next unread tip (wrapping); nil once every tip is read.
    func nextUnread(after current: MacTip) -> MacTip? {
        let all = MacTip.allCases
        guard let start = all.firstIndex(of: current) else { return nil }
        return (1..<all.count).map { all[(start + $0) % all.count] }.first { !isRead($0) && $0 != current }
    }
}

/// Settings › About › Tips (Android TipsPage): every tip, read ones checked; a click opens the reader there.
struct MacTipsSection: View {
    @State private var reads = MacTipReads.shared
    @State private var open: MacTip?

    var body: some View {
        Section {
            ForEach(MacTip.allCases) { tip in
                Button { open = tip } label: {
                    HStack(spacing: 12) {
                        YoinIcon(tip.icon, size: 18).foregroundStyle(Yoin.inkVariant).frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tip.title)
                            Text(tip.text).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        Spacer()
                        if reads.isRead(tip) { YoinIcon("check", size: 16).foregroundStyle(Yoin.violet) }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if reads.readCount > 0 {
                Button("Mark all as unread") { reads.setAllUnread() }
            }
        } header: {
            HStack {
                Text("Tips")
                Spacer()
                Text(reads.readCount >= MacTip.allCases.count ? "All read"
                     : "\(reads.readCount) of \(MacTip.allCases.count) read")
                    .foregroundStyle(.secondary)
            }
        }
        .sheet(item: $open) { tip in MacTipReader(start: tip) }
    }
}

/// One tip a page (Android TipScreen): its position, the tip, when it changed; Read marks it, Next goes to
/// the next unread one, Done once all are read. ← → page through them all.
private struct MacTipReader: View {
    @State private var current: MacTip
    @State private var reads = MacTipReads.shared
    @Environment(\.dismiss) private var dismiss

    init(start: MacTip) { _current = State(initialValue: start) }

    private var index: Int { MacTip.allCases.firstIndex(of: current) ?? 0 }

    var body: some View {
        let next = reads.nextUnread(after: current)
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("\(index + 1) / \(MacTip.allCases.count)").font(.gsf(13).monospacedDigit())
                    .foregroundStyle(Yoin.inkVariant)
                Spacer()
                Button { step(-1) } label: { YoinIcon("chevron_left", size: 18) }
                    .buttonStyle(.plain).disabled(index == 0).keyboardShortcut(.leftArrow, modifiers: [])
                Button { step(1) } label: { YoinIcon("chevron_right", size: 18) }
                    .buttonStyle(.plain).disabled(index == MacTip.allCases.count - 1)
                    .keyboardShortcut(.rightArrow, modifiers: [])
            }
            VStack(alignment: .leading, spacing: 12) {
                YoinIcon(current.icon, size: 30).foregroundStyle(Yoin.violet)
                Text(current.title).font(.gsf(22, .semibold)).foregroundStyle(Yoin.ink)
                Text(current.text).font(.gsf(15)).foregroundStyle(Yoin.ink)
                    .fixedSize(horizontal: false, vertical: true)
                if let d = current.updatedDate {
                    Text("Updated \(d.formatted(date: .abbreviated, time: .omitted))")
                        .font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant)
                }
            }
            .id(current)
            .transition(.opacity.combined(with: .move(edge: .trailing)))
            .frame(maxWidth: .infinity, minHeight: 150, alignment: .topLeading)
            HStack {
                Toggle("Read", isOn: Binding(get: { reads.isRead(current) }, set: { reads.setRead(current, $0) }))
                Spacer()
                Button(next == nil ? "Done" : "Next") {
                    reads.setRead(current, true)
                    if let next { withAnimation(Yoin.spatial) { current = next } } else { dismiss() }
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 440)
    }

    private func step(_ by: Int) {
        let all = MacTip.allCases
        let i = index + by
        guard all.indices.contains(i) else { return }
        withAnimation(Yoin.spatial) { current = all[i] }
    }
}
