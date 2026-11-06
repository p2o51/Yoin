import AppKit

/// The Dock icon's menu: the song playing (a title line), Play / Pause, Next, Previous, Like / Unlike, and
/// Show Mini Player. Built each time it opens, so it always matches the player.
@MainActor
final class MacAppDelegate: NSObject, NSApplicationDelegate {
    func applicationDockMenu(_ sender: NSApplication) -> NSMenu? {
        guard let model = MacModel.current else { return nil }
        let menu = NSMenu()
        let p = model.player
        if let t = p.currentTrack {
            let title = NSMenuItem(title: "\(t.title) — \(t.artist)", action: nil, keyEquivalent: "")
            title.isEnabled = false
            menu.addItem(title)
        } else {
            let idle = NSMenuItem(title: "Not Playing", action: nil, keyEquivalent: "")
            idle.isEnabled = false
            menu.addItem(idle)
        }
        menu.addItem(.separator())
        let playing = p.currentTrack != nil
        menu.addItem(BlockItem(p.isPlaying ? "Pause" : "Play", enabled: playing) { p.togglePlayPause() })
        menu.addItem(BlockItem("Next", enabled: playing) { p.next() })
        menu.addItem(BlockItem("Previous", enabled: playing) { p.previous() })
        if let t = p.currentTrack {
            let liked = model.isLikedCurrent()
            let label = model.session.isAppleMusic ? (liked ? "In Library" : "Add to Library") : (liked ? "Unlike" : "Like")
            menu.addItem(BlockItem(label, enabled: !(model.session.isAppleMusic && liked)) {
                model.toggleLikeCurrent()
            })
        }
        menu.addItem(.separator())
        menu.addItem(BlockItem(model.miniOpen ? "Hide Mini Player" : "Show Mini Player", enabled: true) {
            model.toggleMiniPlayer?()
        })
        return menu
    }
}

/// A menu item that runs a closure (AppKit calls menu actions on the main thread).
@MainActor
private final class BlockItem: NSMenuItem {
    private let run: @MainActor () -> Void

    init(_ title: String, enabled: Bool, _ run: @escaping @MainActor () -> Void) {
        self.run = run
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
        isEnabled = enabled
    }

    required init(coder: NSCoder) { fatalError("not used") }

    @objc private func fire() { run() }
}
