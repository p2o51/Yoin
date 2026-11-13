import UIKit

/// The surfaces that sit side by side in the shell window: the iPad's Now Playing side panel, the
/// iPad's detail column, and Home's Memories over the shell column (Android ShellBackOwner).
enum ShellBackSurface: Equatable {
    case nowPlaying, column, memories
}

/// What one ⌘[ does (Android ShellBackResolver, PR #200).
enum ShellBackStep: Equatable {
    case closeSearch
    case closeFullNowPlaying
    /// A page pushed over the whole shell (the phone, an iPad too narrow for a column) pops.
    case popPage
    case closePanel
    /// The column's own stack pops, then the column closes (`AppModel.back()`).
    case column
    case closeMemories
    /// An album left picked with no column to show it (a narrowed iPad) is let go.
    case clearSelection
    case none
}

/// Back undoes the last step first (owner 2026-11-12: 「返回是进入的反序」). Whatever covers the
/// window goes first: search, Full Now Playing, a page pushed over the whole shell. The surfaces
/// beside one another close in reverse order of opening: `recency` lists them oldest first as the
/// shell saw them open, and the latest one still open answers. Going further inside an open one
/// (another page in the column, Now Playing's lyrics or focus, the panel growing to Full) counts
/// as entering it again. One missing from `recency` ranks oldest; ties fall back to
/// Now Playing › column › Memories, Android's order.
enum ShellBackOrder {
    static func step(
        searchActive: Bool,
        fullNowPlaying: Bool,
        pageCoversShell: Bool,
        panelOpen: Bool,
        columnOpen: Bool,
        memoriesOpen: Bool,
        staleSelection: Bool = false,
        recency: [ShellBackSurface] = []
    ) -> ShellBackStep {
        if searchActive { return .closeSearch }
        if fullNowPlaying { return .closeFullNowPlaying }
        if pageCoversShell { return .popPage }
        var open: [ShellBackSurface] = []
        if panelOpen { open.append(.nowPlaying) }
        if columnOpen { open.append(.column) }
        if memoriesOpen { open.append(.memories) }
        // The first of the equally recent keeps the default order (Kotlin's maxByOrNull).
        var newest: ShellBackSurface?
        var newestRank = Int.min
        for s in open {
            let rank = recency.lastIndex(of: s) ?? -1
            if rank > newestRank { newest = s; newestRank = rank }
        }
        switch newest {
        case .nowPlaying: return .closePanel
        case .column: return .column
        case .memories: return .closeMemories
        case nil: return staleSelection ? .clearSelection : .none
        }
    }

    /// The opening order after `surface` opened or was entered further (moves to the end), or
    /// closed (leaves). Android `noteShellSurface`.
    static func noted(_ recency: [ShellBackSurface], _ surface: ShellBackSurface, open: Bool) -> [ShellBackSurface] {
        var r = recency.filter { $0 != surface }
        if open { r.append(surface) }
        return r
    }
}

@MainActor
extension QAProbe {
    #if DEBUG
    /// `-backOrderDemo <a>-<b>` (iPad), each of `panel`, `column`, `memories`: opens those surfaces in that
    /// order, then presses ⌘[ once: the one opened last closes, the other stays (ShellBackOrder). The panel
    /// and the column only fit side by side in a 13-inch iPad's landscape.
    static func backOrderDemo(_ order: String, _ model: AppModel) async {
        try? await Task.sleep(for: .seconds(1.5))
        model.padSelection = nil
        model.path.removeAll()
        try? await Task.sleep(for: .seconds(0.8))
        for step in order.split(separator: "-") {
            switch step {
            case "panel": model.openNowPlayingOnPad()
            case "memories": model.memories.open()
            default: model.open(MockLibrary.allAlbums[0])
            }
            try? await Task.sleep(for: .seconds(1.6))
        }
        model.backFromKeyboard()
    }
    #endif
}
