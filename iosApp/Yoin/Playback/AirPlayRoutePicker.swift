import AVKit
import SwiftUI

/// Opens the system AirPlay / output route picker from any Yoin control.
/// AVRoutePickerView only presents from its own (SF Symbol) button, so the
/// picker sits invisible behind the control and a tap forwards to it.
@MainActor
final class AirPlayRouteTrigger {
    fileprivate weak var picker: AVRoutePickerView?

    func present() {
        guard let picker else {
            AVAudioOutput.log.error("route picker: not mounted")
            return
        }
        guard let button = Self.firstButton(in: picker) else {
            AVAudioOutput.log.error("route picker: no button in \(String(describing: picker.subviews), privacy: .public)")
            return
        }
        AVAudioOutput.log.info("route picker: present \(String(describing: type(of: button)), privacy: .public) menu=\(button.showsMenuAsPrimaryAction) targets=\(button.allTargets.count) events=\(button.allControlEvents.rawValue)")
        button.sendActions(for: .touchUpInside)
        button.sendActions(for: .primaryActionTriggered)
    }

    /// The picker's button sits a level or two down, depending on the OS.
    private static func firstButton(in view: UIView) -> UIButton? {
        for sub in view.subviews {
            if let b = sub as? UIButton { return b }
            if let b = firstButton(in: sub) { return b }
        }
        return nil
    }
}

struct AirPlayRoutePicker: UIViewRepresentable {
    var trigger: AirPlayRouteTrigger

    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView(frame: .zero)
        view.prioritizesVideoDevices = false
        view.alpha = 0.011
        trigger.picker = view
        return view
    }

    func updateUIView(_ view: AVRoutePickerView, context: Context) {
        trigger.picker = view
    }
}
