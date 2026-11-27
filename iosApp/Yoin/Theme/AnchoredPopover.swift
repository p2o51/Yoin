import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

/// iPad round 2 (owner 2026-11-27): in a regular-width iPad window the small surfaces that act on one thing (the
/// song sheet, the queue, Spotify's devices) pop over from what opened them, instead of rising as a sheet in the
/// middle of a 13" screen (iPadOS HIG). A compact window (Slide Over, a narrow Split View) and the iPhone keep
/// their sheets. A right-click on a song still opens the song sheet (not the system context menu): as a popover
/// at the pointer here.
enum AnchoredPopover {
    /// Popovers instead of sheets: an iPad (or the iPad app on a Mac) in a regular-width window.
    static func enabled(isPad: Bool, horizontal: UserInterfaceSizeClass?) -> Bool {
        isPad && horizontal == .regular
    }

    /// The song sheet's width as a popover (its height is its content's, ≤ 640 as the sheet's detent).
    static let songMenuWidth: CGFloat = 380
    static let queueSize = CGSize(width: 400, height: 600)
    static let devicesSize = CGSize(width: 380, height: 480)

    /// A menu opened by a press or a click pops from that point when the touch went down no longer ago than
    /// this (a long press is 0.4 s; an iPad row's hold opens on release).
    static let touchMaxAge: TimeInterval = 3

    /// The anchor rect for a point (a popover's arrow needs a rect, not a point).
    static func anchor(at point: CGPoint) -> CGRect {
        CGRect(x: point.x - 2, y: point.y - 2, width: 4, height: 4)
    }

    /// The last touch's point when it is recent enough to anchor a menu to.
    static func recent(point: CGPoint?, at time: TimeInterval, now: TimeInterval) -> CGPoint? {
        guard let point, now - time >= 0, now - time <= touchMaxAge else { return nil }
        return point
    }
}

extension EnvironmentValues {
    /// The view is shown in a popover (its own size, no sheet detents to follow).
    @Entry var inPopover = false
}

extension View {
    /// A popover from this view while [isPresented] (nothing when [enabled] is false: the caller keeps its sheet).
    /// [arrowEdge]: the popover's edge its arrow is on (`.top`: it hangs below a top bar's button; `.bottom`: it
    /// stands above a control at the window's foot; nil: wherever it fits). An optional, so iOS honours it (the
    /// non-optional overload is macOS-only).
    func anchoredPopover<C: View>(isPresented: Bool, enabled: Bool, arrowEdge: Edge? = .top,
                                  onDismiss: @escaping () -> Void,
                                  @ViewBuilder content: @escaping () -> C) -> some View {
        popover(isPresented: Binding(get: { enabled && isPresented }, set: { if !$0 { onDismiss() } }),
                arrowEdge: arrowEdge) {
            content()
                .environment(\.inPopover, true)
                .presentationCompactAdaptation(.sheet)
        }
    }
}

/// Where the last finger, pencil or pointer went down in the window (window points). The song sheet opened
/// from anywhere (a row's hold, a card's long press, a right-click) pops from there on iPad.
@MainActor
enum LastTouch {
    private(set) static var point: CGPoint?
    private(set) static var time: TimeInterval = 0

    static func note(_ p: CGPoint, at t: TimeInterval) {
        point = p
        time = t
    }

    /// The last touch-down when it is recent enough to be what opened a menu.
    static var recent: CGPoint? {
        AnchoredPopover.recent(point: point, at: time, now: ProcessInfo.processInfo.systemUptime)
    }
}

/// Notes every touch-down in the window and fails at once: it never takes, delays or cancels a touch, so the
/// gestures under it see nothing new.
final class LastTouchRecognizer: UIGestureRecognizer {
    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        if let touch = touches.first { LastTouch.note(touch.location(in: nil), at: touch.timestamp) }
        state = .failed
    }

    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool { false }
}

/// Puts a `LastTouchRecognizer` on the window once (iPad and the iPad app on a Mac; the iPhone has no popovers).
struct LastTouchInstaller: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.isUserInteractionEnabled = false
        guard UIDevice.current.userInterfaceIdiom != .phone else { return v }
        DispatchQueue.main.async {
            guard let window = v.window,
                  !(window.gestureRecognizers ?? []).contains(where: { $0 is LastTouchRecognizer }) else { return }
            window.addGestureRecognizer(LastTouchRecognizer(target: nil, action: nil))
        }
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// Where Now Playing's Queue and Devices pop over from on iPad (NowPlayingView.popoverHost).
enum NowPlayingPopoverHost: Equatable { case topBar, pills }
