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
    /// ScrollEdge ("Settings › Motion › Scroll edge", the top wave line), ScreenCorners (the system draws
    /// them) and SettingsRegrouped (iOS never shipped the old Settings). Not yet, each waits for its feature:
    /// AiServices (the AI services page), ContinueOn (Handoff), and Labs' 11-12 day (Gemini Nano
    /// diagnostics, which iOS has no counterpart for).
    static let all: [Tip] = [
        // All Memories (Home grid + page, Android #186 / #178 / #202): Android's line and day (Argon's copy for
        // the wide layout and the pixel bar), first in the list as on Android.
        Tip(id: "MemoryLedger", icon: "edit_note", updated: "2026-11-12",
            title: "All Memories",
            text: "Tap All Memories on Home, tap a square on the grid to see just that time, and scroll down as the squares fly into a pixel bar at the top; switch between Timeline, Cards, and a sortable List below on the right, with Timeline and Cards in 2–3 columns on large screens",
            titleZhHans: "全部回忆",
            textZhHans: "点首页的「全部回忆」，在日历格点一格只看那段时间，往下滚格子会飞到顶栏拼成像素条；下方右侧可切换时间线、卡片或可排序的列表，大屏上时间线和卡片排成 2–3 列"),
        // Home's Spotify pill (HomeSpotifyElsewhere.swift; Android PR #197, owner 2026-11-10); Argon's wording.
        // No swap symbol in YoinSymbols yet: the music note.
        Tip(id: "SpotifyElsewhere", icon: "music_note", updated: "2026-11-10",
            title: "Switch to Spotify",
            text: "While in another account, if Spotify is playing or paused on a song, tap the pill at the top of Home to switch to it",
            titleZhHans: "切换到 Spotify",
            textZhHans: "在其他账号下，已登录的 Spotify 有歌正在播放或暂停时，点首页顶部的胶囊即可切换过去"),
        // Lyric Cards with Apple Intelligence (Android #180); Argon's iOS wording.
        Tip(id: "LyricHighlight", icon: "lyrics", updated: "2026-11-10",
            title: "Lyric Cards",
            text: "On devices with Apple Intelligence, tap a lyric card on Home to play from that line, or swipe for more; lines shared or saved in Lyrics also show up",
            titleZhHans: "歌词卡片",
            textZhHans: "支持 Apple 智能的设备上，点首页歌词卡片从那句开播，左右滑动翻看；歌词里分享或保存的句子也会加入"),
        // The Memories deck deals every account's memories (Android #178); Android's own strings
        // (home_tip_memories_all_accounts). No memory symbol in YoinSymbols on iOS yet: the note one.
        Tip(id: "MemoriesAllAccounts", icon: "edit_note", updated: "2026-11-13",
            title: "Memories across accounts",
            text: "Memories includes all your accounts, and switches to that account first when you play or go to the album",
            titleZhHans: "各账号的回忆",
            textZhHans: "Memories 会显示你所有账号的回忆，播放或去专辑会先切换到该账号"),
        // Speakers Spotify lists without an id, and the ones used before (Android SpotifySpeakers, #176; iOS
        // SpotifyDevicesSheet / SpotifyDeviceMemory); Android's own strings (home_tip_spotify_speakers). No
        // speaker symbol in YoinSymbols on iOS yet: the cast one, as the sheet's More devices… row uses.
        Tip(id: "SpotifySpeakers", icon: "cast", updated: "2026-11-08",
            title: "Connect more speakers",
            text: "Tap More devices… in Now Playing > Devices to switch Spotify to Sonos, PS5, and more; recently used speakers stay in the list",
            titleZhHans: "连接更多音箱",
            textZhHans: "正在播放 > 设备里点「更多设备…」，将 Spotify 切到 Sonos、PS5 等音箱；用过的会留在列表里"),
        // Yoin Labs (Android #189); Argon's iOS wording.
        Tip(id: "Labs", icon: "sparkle", updated: "2026-11-10",
            title: "Yoin Labs",
            text: "Experimental features are now in Settings > Yoin Labs, including the Lyric Cards toggle",
            titleZhHans: "Yoin Labs",
            textZhHans: "实验功能现在在 设置 > Yoin Labs，歌词卡片的开关也在那里"),
        // Volume leveling (Android #139); Argon's iOS wording (the Subsonic account's page).
        Tip(id: "VolumeLeveling", icon: "equalizer", updated: "2026-11-10",
            title: "Volume leveling",
            text: "In Settings > your Subsonic account > Volume leveling, turn on ReplayGain by track or by album to keep volume consistent",
            titleZhHans: "音量平衡",
            textZhHans: "设置 > 点开 Subsonic 账号 > 音量平衡，可按曲目或按专辑开启 ReplayGain，让歌曲音量一致"),
        // Home screen widgets (Widgets/, parity A-F10); Argon's wording (.argon/ios-widgets-tip), the widget
        // names as the gallery shows them.
        Tip(id: "HomeScreenWidgets", icon: "library", updated: "2026-11-07",
            title: "Add Home Screen widgets",
            text: "Long-press an empty area on the Home Screen and tap Edit to add the Play and Memory widgets",
            titleZhHans: "添加桌面小组件",
            textZhHans: "长按主屏幕空白处，在左上角「编辑」里添加「播放」和「Memories」小组件"),
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
        // Android moved it to 11-07 when Library's song rows got the sheet (c1c717d25, 803294971); iOS's Library
        // and search rows already open it on a long-press (LibraryView, SearchView `.trackRow`), and its pages
        // leave search first (TrackMenuSheet.leaveOverlays). Android's line as given.
        Tip(id: "SongMenu", icon: "queue", updated: "2026-11-07",
            title: "Song quick actions",
            text: "Long-press a song in an album, playlist, or Library to play next, queue, favorite, or share",
            titleZhHans: "长按歌曲操作",
            textZhHans: "长按专辑、歌单或资料库里的歌，下一首播放、加入队列、收藏或分享"),
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

    /// After [current] is read, the next unread tip after it (wrapping); nil once every other tip is read
    /// (Android `nextUnreadTip`): the tip page's Next goes there, and reads Done when there is none.
    static func nextUnread(after current: String, read: Set<String>, in tips: [Tip] = all) -> Tip? {
        guard let start = tips.firstIndex(where: { $0.id == current }) else { return unread(read, in: tips).first }
        return (1..<tips.count).lazy.map { tips[(start + $0) % tips.count] }.first { !read.contains($0.id) }
    }

    /// The tip page's place in the list: Android `tip_position`, "3 / 19".
    static func positionLine(index: Int, total: Int) -> String {
        String(localized: "\(index + 1) / \(total)")
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
