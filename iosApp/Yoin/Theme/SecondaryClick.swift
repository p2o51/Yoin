import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

extension View {
    /// A secondary click on this view (a mouse's right button, a trackpad's two-finger click, a
    /// Control-click: the iPad app on a Mac, an iPad with a pointer) runs [action]. Songs use it to open
    /// the sheet their long press opens, so a right-click offers the same actions. A finger never fires
    /// it: touch keeps its tap and its long press as they were.
    ///
    /// Without it a secondary click is a tap (SwiftUI's tap takes any button): a right-click on a song
    /// played it. Attach it INSIDE the view's tap (before `onTapGesture`, or on a Button's label) so the
    /// click is this one's and the tap never sees it. An iPhone has no pointer and gets nothing attached.
    func onSecondaryClick(perform action: @escaping @MainActor () -> Void) -> some View {
        modifier(SecondaryClickTrigger(action: action))
    }
}

private struct SecondaryClickTrigger: ViewModifier {
    var action: @MainActor () -> Void
    /// iPad, and the iPad app on a Mac. Fixed for the run, so the branch below never changes the view.
    private static let hasPointer = UIDevice.current.userInterfaceIdiom != .phone

    func body(content: Content) -> some View {
        if Self.hasPointer {
            content.gesture(SecondaryClick(action: action))
        } else {
            content
        }
    }
}

private struct SecondaryClick: UIGestureRecognizerRepresentable {
    var action: @MainActor () -> Void

    func makeUIGestureRecognizer(context: Context) -> SecondaryClickRecognizer { SecondaryClickRecognizer() }

    func handleUIGestureRecognizerAction(_ recognizer: SecondaryClickRecognizer, context: Context) {
        if recognizer.state == .ended { action() }
    }
}

/// One click of a pointer's secondary button, or of its primary button with Control held (the same
/// request on a Mac and on an iPad). It takes no other event: a finger's or a Pencil's touch and a plain
/// click never reach it (`shouldReceive`), so the gestures around it meet nothing new.
final class SecondaryClickRecognizer: UIGestureRecognizer {
    /// How far the pointer may travel between press and release and still be a click.
    static let slop: CGFloat = 10
    private var down: CGPoint?

    nonisolated static func isSecondary(buttons: UIEvent.ButtonMask, modifiers: UIKeyModifierFlags) -> Bool {
        buttons.contains(.secondary) || (buttons.contains(.primary) && modifiers.contains(.control))
    }

    nonisolated static func isClick(from start: CGPoint, to end: CGPoint) -> Bool {
        hypot(end.x - start.x, end.y - start.y) <= slop
    }

    override func shouldReceive(_ event: UIEvent) -> Bool {
        event.type == .touches && Self.isSecondary(buttons: event.buttonMask, modifiers: event.modifierFlags)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard down == nil, touches.count == 1, let touch = touches.first, touch.type == .indirectPointer,
              Self.isSecondary(buttons: event.buttonMask, modifiers: event.modifierFlags) else {
            state = .failed
            return
        }
        down = touch.location(in: nil)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let down, let now = touches.first?.location(in: nil) else { return }
        if !Self.isClick(from: down, to: now) { state = .failed }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        state = down == nil ? .failed : .ended
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) { state = .failed }

    override func reset() { down = nil }
}
