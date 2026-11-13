import SwiftUI
import UIKit

/// The smallest window Yoin lets itself be dragged to on a Mac: the smallest one the phone shell is laid
/// out for. Both numbers are Android's own layout lines, not new ones:
/// - width 360: one phone column (Android `PaneMinWidth`; PadShell's columns clamp to the same 360).
/// - height 480: under it Android leaves the portrait shell for the landscape handset's
///   (`ShellChromeForm.EdgeSplit`, WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND), a form iOS doesn't have.
///   At 480 Now Playing's column still holds its cover (`NowPlayingBudget`, WindowFloorTests).
///
/// iPhone has no windows, and an iPad window keeps the system's own minimum (tiles and Slide Over stay as
/// they are): there the rule answers nil.
enum WindowFloor {
    static let width: CGFloat = 360
    static let height: CGFloat = NowPlayingPadFrame.mediumMinHeight

    static func minimumSize(on platform: Platform = .current) -> CGSize? {
        platform.isMac ? CGSize(width: width, height: height) : nil
    }

    /// Gives the scene its floor. A scene without size restrictions (iPhone) is left alone.
    @MainActor
    static func apply(to scene: UIWindowScene?, on platform: Platform = .current) {
        guard let floor = minimumSize(on: platform), let restrictions = scene?.sizeRestrictions else { return }
        if restrictions.minimumSize != floor { restrictions.minimumSize = floor }
    }
}

/// Hands every window scene its floor as its root view lands in a window. A view rather than a scene
/// delegate: a session's delegate class is chosen when the session is first made, so a window the system
/// restores from an earlier build would never get one.
struct WindowFloorInstaller: UIViewRepresentable {
    func makeUIView(context: Context) -> Probe { Probe() }
    func updateUIView(_ uiView: Probe, context: Context) {}

    final class Probe: UIView {
        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("not in a nib") }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            WindowFloor.apply(to: window?.windowScene)
        }
    }
}
