import Observation
import UIKit

/// Tracks whether any UIKit or SwiftUI text input is currently accepting keyboard input,
/// so bare-key transport shortcuts (`Space`, `←`, `→`) never swallow keystrokes while typing.
@MainActor
@Observable
final class TextInputFocus {
    static let shared = TextInputFocus()

    /// Active text editors notified via UIKit `textDidBeginEditing` / `textDidEndEditing`
    /// so SwiftUI `Commands` invalidates `.disabled(...)` dynamically as focus enters/leaves fields.
    private(set) var activeEditorCount = 0

    /// Optional explicit focus depth for custom text views without UIKit field notifications.
    private(set) var explicitFocusDepth = 0

    /// Test hook returning a synthetic first responder instead of querying `UIApplication`.
    @ObservationIgnored var testResponderProvider: (() -> AnyObject?)?

    @ObservationIgnored private var editors: [ObjectIdentifier: WeakBox] = [:]
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    private init() {
        let center = NotificationCenter.default
        let queue = OperationQueue.main
        observers.append(center.addObserver(forName: UITextField.textDidBeginEditingNotification, object: nil, queue: queue) { [weak self] note in
            let box = WeakSendableBox(note.object as AnyObject?)
            Task { @MainActor in self?.beginEditing(box.value) }
        })
        observers.append(center.addObserver(forName: UITextField.textDidEndEditingNotification, object: nil, queue: queue) { [weak self] note in
            let box = WeakSendableBox(note.object as AnyObject?)
            Task { @MainActor in self?.endEditing(box.value) }
        })
        observers.append(center.addObserver(forName: UITextView.textDidBeginEditingNotification, object: nil, queue: queue) { [weak self] note in
            let box = WeakSendableBox(note.object as AnyObject?)
            Task { @MainActor in self?.beginEditing(box.value) }
        })
        observers.append(center.addObserver(forName: UITextView.textDidEndEditingNotification, object: nil, queue: queue) { [weak self] note in
            let box = WeakSendableBox(note.object as AnyObject?)
            Task { @MainActor in self?.endEditing(box.value) }
        })
    }

    var isActive: Bool {
        pruneStaleEditors()
        return activeEditorCount > 0 || explicitFocusDepth > 0 || Self.currentResponderIsTextInput()
    }

    /// Live responder inspection (never cached) for command action closures and tests.
    static func currentResponderIsTextInput() -> Bool {
        if let provider = shared.testResponderProvider {
            return isTextInputResponder(provider())
        }
        return isTextInputResponder(currentFirstResponder())
    }

    /// The field with the keyboard holds marked text: an input method (pinyin, kana) is composing.
    static func currentResponderIsComposing() -> Bool {
        let responder = shared.testResponderProvider?() ?? currentFirstResponder()
        return (responder as? UITextInput)?.markedTextRange != nil
    }

    /// Walks up the responder chain from `responder` to check whether it is or sits inside a
    /// `UITextField`, `UITextView`, `UISearchBar`, or `UITextInput` control.
    static func isTextInputResponder(_ responder: AnyObject?) -> Bool {
        var cursor = responder
        while let current = cursor {
            if current is UITextField || current is UITextView || current is UISearchBar {
                return true
            }
            if current is UITextInput {
                return true
            }
            cursor = (current as? UIResponder)?.next
        }
        return false
    }

    func pushExplicitFocus() {
        explicitFocusDepth += 1
    }

    func popExplicitFocus() {
        explicitFocusDepth = max(0, explicitFocusDepth - 1)
    }

    private func beginEditing(_ object: AnyObject?) {
        guard let object else { return }
        editors[ObjectIdentifier(object)] = WeakBox(object)
        pruneStaleEditors()
    }

    private func endEditing(_ object: AnyObject?) {
        if let object {
            editors.removeValue(forKey: ObjectIdentifier(object))
        }
        pruneStaleEditors()
    }

    private func pruneStaleEditors() {
        editors = editors.filter { _, box in
            guard let value = box.value else { return false }
            if let view = value as? UIView {
                return view.window != nil
            }
            return true
        }
        if activeEditorCount != editors.count {
            activeEditorCount = editors.count
        }
    }

    /// Queries UIKit's active responder chain safely via `sendAction(_:to:nil:)`,
    /// falling back to scanning connected key windows if no chain responded.
    private static func currentFirstResponder() -> UIResponder? {
        let probe = FirstResponderProbe()
        UIApplication.shared.sendAction(#selector(UIResponder.yoin_recordFirstResponder(_:)), to: nil, from: probe, for: nil)
        if let found = probe.responder {
            return found
        }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for window in scenes.flatMap(\.windows) where window.isKeyWindow {
            if let found = findFirstResponder(in: window) {
                return found
            }
        }
        return nil
    }

    private static func findFirstResponder(in view: UIView) -> UIResponder? {
        if view.isFirstResponder { return view }
        for subview in view.subviews {
            if let found = findFirstResponder(in: subview) {
                return found
            }
        }
        return nil
    }
}

private final class WeakBox {
    weak var value: AnyObject?
    init(_ value: AnyObject) { self.value = value }
}

private struct WeakSendableBox: @unchecked Sendable {
    weak var value: AnyObject?
    init(_ value: AnyObject?) { self.value = value }
}

private final class FirstResponderProbe: NSObject {
    weak var responder: UIResponder?
}

private extension UIResponder {
    @objc func yoin_recordFirstResponder(_ sender: Any?) {
        guard let probe = sender as? FirstResponderProbe else { return }
        probe.responder = self
    }
}
