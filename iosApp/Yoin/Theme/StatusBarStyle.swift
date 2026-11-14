import SwiftUI
import UIKit

/// Light status bar icons over a page's own formed top bar (Android AlbumBarStatusBarEffect): the album or
/// playlist page's spectrum once the track list is pulled up, All Memories' bar once the grid's squares have
/// formed it, in the light scheme (in the dark one the icons are light already). Everywhere else the
/// scheme's own icons, as before.
///
/// SwiftUI names no status bar style per view, and under the SwiftUI app life cycle the window's root hosting
/// controller can't be subclassed. So while a page asks for light icons a transparent window that takes no
/// touches stands just above the app's window and its controller names the style; when no page asks, it is
/// hidden and the app's window decides as it always did. Never on a Mac (no status bar there).
@MainActor
final class StatusBarStyleOverlay {
    static let shared = StatusBarStyleOverlay()

    /// The pages asking for light icons now, with the window scene each is in.
    private var asking: [UUID: ObjectIdentifier] = [:]
    /// One overlay per window scene that has needed one (an iPad may show several windows).
    private var windows: [ObjectIdentifier: UIWindow] = [:]

    func set(_ id: UUID, light: Bool, scene: UIWindowScene?) {
        let before = asking
        if light, let scene, !Platform.current.isMac {
            asking[id] = ObjectIdentifier(scene)
            if windows[ObjectIdentifier(scene)] == nil { windows[ObjectIdentifier(scene)] = Self.makeWindow(scene) }
        } else {
            asking[id] = nil
        }
        guard asking != before else { return }
        let lit = Set(asking.values)
        for (key, window) in windows {
            if window.windowScene == nil { windows[key] = nil; continue }
            let show = lit.contains(key)
            guard window.isHidden == show else { continue }
            window.isHidden = !show
            window.rootViewController?.setNeedsStatusBarAppearanceUpdate()
        }
    }

    private static func makeWindow(_ scene: UIWindowScene) -> UIWindow {
        let w = PassThroughWindow(windowScene: scene)
        // Just above the app's window (sheets and alerts present inside it), far below the system's.
        w.windowLevel = .normal + 1
        w.backgroundColor = .clear
        w.isUserInteractionEnabled = false
        w.accessibilityElementsHidden = true
        w.rootViewController = LightStatusBarController()
        w.isHidden = true
        return w
    }

    /// Takes no touch, pointer or drop: they all reach the app's window under it.
    private final class PassThroughWindow: UIWindow {
        override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? { nil }
    }

    private final class LightStatusBarController: UIViewController {
        override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }
        override func loadView() {
            let v = UIView()
            v.backgroundColor = .clear
            v.isUserInteractionEnabled = false
            view = v
        }
    }
}

extension View {
    /// Light status bar icons while [light] and this view is in a window (StatusBarStyleOverlay).
    func statusBarLight(_ light: Bool) -> some View {
        background(StatusBarLightProbe(light: light).allowsHitTesting(false).accessibilityHidden(true))
    }
}

/// Reports its page's ask with the window scene the page is in; a page off screen (a page under another in
/// a navigation stack leaves the window) or gone asks for nothing.
private struct StatusBarLightProbe: UIViewRepresentable {
    var light: Bool

    func makeUIView(context: Context) -> Probe { Probe() }

    func updateUIView(_ uiView: Probe, context: Context) { uiView.light = light }

    static func dismantleUIView(_ uiView: Probe, coordinator: ()) { uiView.withdraw() }

    final class Probe: UIView {
        private let id = UUID()
        var light = false {
            didSet { if light != oldValue { publish() } }
        }

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("not in a nib") }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            publish()
        }

        private func publish() {
            StatusBarStyleOverlay.shared.set(id, light: light && window != nil, scene: window?.windowScene)
        }

        func withdraw() {
            light = false
            StatusBarStyleOverlay.shared.set(id, light: false, scene: nil)
        }
    }
}
