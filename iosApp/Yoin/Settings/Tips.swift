import Foundation

// Tips (Android ui/home/HomeBubbleSay.kt `HomeBubbleTip`, ui/tips/TipReadStore.kt): short lines about what
// the app hides, shown on Settings › About › Tips and brought up one at a time by Home's bubble. Pure and
// unit-tested (TipsTests).
//
// Copy rule (owner): none of it is written here. Every line below is Android's own string (strings.xml,
// values and values-zh-rCN), except `downloads`, whose iOS wording Argon wrote (.argon/ios-tips), because
// downloads are managed elsewhere here (the Subsonic account's page, not Settings › Storage). iOS ships English
// only for now: `title` / `text` are what shows; the Chinese sits beside it for the String Catalog work.

/// One tip. [id] is Android's enum name (the read store's key); [updated] is the day what it shows last
/// changed, "yyyy-MM-dd": a tip read before that day is unread again.
struct Tip: Identifiable, Hashable, Sendable {
    let id: String
    /// A YoinIcon name.
    let icon: String
    let updated: String
    let title: String
    let text: String
    let titleZhHans: String
    let textZhHans: String

    /// What shows: the Chinese when the app runs in Chinese (its UI language, not just the region).
    var shownTitle: String { Tips.showsChinese ? titleZhHans : title }
    var shownText: String { Tips.showsChinese ? textZhHans : text }
}

enum Tips {
    /// The tips, in the order the bubble goes through them. Left out (iOS has no such thing): Android's
    /// ScrollEdge ("Settings › Motion › Scroll edge", the top wave line).
    static let all: [Tip] = [
        // Android #162 (owner's wording, 2026-11-06). No person symbol in YoinSymbols yet: the artist one.
        Tip(id: "AccountQuickSwitch", icon: "artist", updated: "2026-11-06",
            title: "Switch accounts",
            text: "Long-press the avatar and slide to switch accounts",
            titleZhHans: "切换账号",
            textZhHans: "长按头像并滑动以切换账号"),
        Tip(id: "MiniPlayerMenu", icon: "play_filled", updated: "2026-11-04",
            title: "Mini player options",
            text: "Long-press the mini player in the bar to open Song options",
            titleZhHans: "迷你播放器菜单",
            textZhHans: "长按底栏的迷你播放器，直接打开当前歌曲菜单"),
        Tip(id: "FocusCover", icon: "unfold_more", updated: "2026-11-03",
            title: "Tap cover to Focus",
            text: "Tap the cover in Now Playing to enter Focus, and tap again to return",
            titleZhHans: "轻点封面聚焦",
            textZhHans: "正在播放里轻点封面进入聚焦，再点一下回到原样"),
        // Argon's iOS line (.argon/ios-tips/brief.md).
        Tip(id: "Downloads", icon: "download", updated: "2026-11-06",
            title: "Offline downloads",
            text: "Subsonic accounts can download from song, album, or playlist menus; manage in Settings › Subsonic account › Downloads",
            titleZhHans: "离线下载",
            textZhHans: "Subsonic 账号可在歌曲、专辑或歌单菜单里下载，管理在设置 › Subsonic 账号 › 下载"),
        Tip(id: "EditHome", icon: "drag_handle", updated: "2026-10-17",
            title: "Rearrange Home",
            text: "Long-press Home to rearrange sections",
            titleZhHans: "重新排版首页",
            textZhHans: "长按首页，重新排版"),
        Tip(id: "PullForMemories", icon: "chevron_down", updated: "2026-10-17",
            title: "Memories",
            text: "Pull down on Home to open Memories",
            titleZhHans: "Memories",
            textZhHans: "首页往下拉，打开 Memories"),
        Tip(id: "CoverMenu", icon: "album", updated: "2026-11-04",
            title: "Song options",
            text: "Long-press the cover in Now Playing for Song options and quick actions",
            titleZhHans: "歌曲菜单",
            textZhHans: "正在播放里长按封面，打开歌曲菜单与快捷操作"),
        Tip(id: "SongMenu", icon: "queue", updated: "2026-11-04",
            title: "Song quick actions",
            text: "Long-press a song in an album or playlist to play next, queue, favorite, or share",
            titleZhHans: "长按歌曲操作",
            textZhHans: "长按专辑或歌单里的歌，下一首播放、加入队列、收藏或分享"),
        Tip(id: "PlayModes", icon: "shuffle", updated: "2026-10-21",
            title: "Shuffle and repeat",
            text: "Tap to switch shuffle and repeat; long-press to split them and toggle repeat off",
            titleZhHans: "随机与循环",
            textZhHans: "点按切换随机与循环；长按拆成两个按钮，单独开关循环"),
        Tip(id: "NoteMoments", icon: "edit_note", updated: "2026-10-17",
            title: "Notes pin the second",
            text: "Notes written in Now Playing stay pinned to that exact second",
            titleZhHans: "笔记记住那一秒",
            textZhHans: "在正在播放写的笔记，会钉在那一秒"),
        Tip(id: "LibrarySearch", icon: "search", updated: "2026-10-17",
            title: "Quick search",
            text: "Long-press Library in the bar to search right away",
            titleZhHans: "快速搜索",
            textZhHans: "长按底栏的资料库，直接搜索"),
        Tip(id: "KeepScreenOn", icon: "light_mode", updated: "2026-10-17",
            title: "Keep screen on",
            text: "Keep screen on is in the cover's long-press menu",
            titleZhHans: "屏幕常亮",
            textZhHans: "屏幕常亮在封面的长按菜单里"),
        Tip(id: "Name", icon: "sparkle", updated: "2026-10-17",
            title: "Behind the name",
            text: "Yoin comes from the Japanese word \"余韻\" — a lingering resonance",
            titleZhHans: "名字的由来",
            textZhHans: "Yoin 的名字来自日语「余韻」：余音不散"),
    ]

    static func tip(_ id: String) -> Tip? { all.first { $0.id == id } }

    /// Android `tip_updated`: "Updated Nov 4, 2026" (zh-Hans "更新于 %1$s").
    static func updatedLine(_ tip: Tip) -> String {
        String(localized: "Updated \(dayText(tip.updated))")
    }

    /// The app shows its Chinese strings (the localization iOS picked for it).
    static var showsChinese: Bool { Bundle.main.preferredLocalizations.first?.hasPrefix("zh") == true }

    /// "yyyy-MM-dd" as "Nov 4, 2026" (the day, not an instant: no time zone moves it).
    static func dayText(_ day: String, locale: Locale = .current) -> String {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, (1...12).contains(parts[1]) else { return day }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        guard let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) else { return day }
        // The reader's own date order and month names ("Nov 4, 2026", "2026年11月4日").
        var style = Date.FormatStyle(date: .abbreviated, time: .omitted, locale: locale, calendar: calendar)
        style.timeZone = calendar.timeZone
        return date.formatted(style)
    }

    /// Settings' Tips row: Android `settings_tips_read_count` / `settings_tips_all_read`.
    static func readSummary(read: Int, total: Int) -> String {
        read >= total ? String(localized: "All read") : String(localized: "\(read) of \(total) read")
    }

    /// The tips still unread, in the bubble's order.
    static func unread(_ read: Set<String>, in tips: [Tip] = all) -> [Tip] {
        tips.filter { !read.contains($0.id) }
    }

    // MARK: Read store encoding

    /// A read tip is kept as "Id@2026-11-04": its id and the `updated` day the user read. A tip updated
    /// since counts as unread again; one renamed or removed simply drops out (TipReadStore.kt).
    static func encodeRead(_ read: Set<String>, in tips: [Tip] = all) -> [String] {
        tips.filter { read.contains($0.id) }.map { "\($0.id)@\($0.updated)" }
    }

    static func decodeRead(_ saved: [String], in tips: [Tip] = all) -> Set<String> {
        let saved = Set(saved)
        return Set(tips.filter { saved.contains("\($0.id)@\($0.updated)") }.map(\.id))
    }
}

/// Which tips the user has marked read: Home's bubble only brings up the unread ones, and goes quiet once
/// all are read. Kept on this device (UserDefaults), beside the bubble's other memory.
@MainActor
@Observable
final class TipReadStore {
    static let shared = TipReadStore(defaults: .standard)

    private static let key = "tipRead"
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let tips: [Tip]
    /// Ids of the tips read at their current `updated` day.
    private(set) var read: Set<String>

    init(defaults: UserDefaults, tips: [Tip] = Tips.all) {
        self.defaults = defaults
        self.tips = tips
        read = Tips.decodeRead(defaults.stringArray(forKey: Self.key) ?? [], in: tips)
    }

    func isRead(_ tip: Tip) -> Bool { read.contains(tip.id) }

    func setRead(_ tip: Tip, _ isRead: Bool) {
        if isRead { read.insert(tip.id) } else { read.remove(tip.id) }
        persist()
    }

    func setAllUnread() {
        read = []
        persist()
    }

    /// The tips still unread, in the bubble's order.
    var unread: [Tip] { Tips.unread(read, in: tips) }

    private func persist() {
        defaults.set(Tips.encodeRead(read, in: tips), forKey: Self.key)
    }
}

// MARK: - What the bubble says

/// What Home's bubble says (Android `BubbleSay`): a Memory, or a tip.
enum BubbleSay: Hashable, Sendable {
    /// Memories (a latest one, or kept notes only).
    case memory
    case tip(Tip)
}

enum BubbleSayPlan {
    /// What the idle bubble says on one of its turns (Android `idleBubbleSay`): Memories and tips take turns
    /// while there are memories to recall ([sayMemory]); with none, tips only. [tipTurn] steps through the
    /// [unread] tips in order, so every one comes round before one repeats. With every tip read it is
    /// memories only; with neither, nothing (nil).
    static func idle(hasMemory: Bool, sayMemory: Bool, tipTurn: Int, unread: [Tip]) -> BubbleSay? {
        if hasMemory && (sayMemory || unread.isEmpty) { return .memory }
        guard !unread.isEmpty else { return nil }
        let n = unread.count
        return .tip(unread[((tipTurn % n) + n) % n])
    }

    /// Whether turn number [turn] (0, 1, 2…) is Memories' (even turns), else a tip's.
    static func isMemoryTurn(_ turn: Int) -> Bool { turn % 2 == 0 }
}
