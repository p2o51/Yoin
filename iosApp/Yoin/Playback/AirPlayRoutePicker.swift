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
        guard let control = Self.firstControl(in: picker) else {
            AVAudioOutput.log.error("route picker: no control in \(String(describing: picker.subviews), privacy: .public)")
            return
        }
        AVAudioOutput.log.info("route picker: present \(String(describing: type(of: control)), privacy: .public) menu=\(control.showsMenuAsPrimaryAction) targets=\(control.allTargets.count) events=\(control.allControlEvents.rawValue)")
        control.sendActions(for: .touchUpInside)
        control.sendActions(for: .primaryActionTriggered)
    }

    /// Finds the picker's internal button (`UIButton` first, falling back to any `UIControl` such as
    /// private Mac/iPad-on-Mac route controls).
    static func firstControl(in view: UIView) -> UIControl? {
        if let button = firstSubview(of: UIButton.self, in: view) { return button }
        return firstSubview(of: UIControl.self, in: view)
    }

    private static func firstSubview<T: UIView>(of type: T.Type, in view: UIView) -> T? {
        for sub in view.subviews {
            if let match = sub as? T { return match }
            if let match = firstSubview(of: type, in: sub) { return match }
        }
        return nil
    }
}

final class AirPlayRouteContainerView: UIView {
    let picker: AVRoutePickerView = {
        let view = AVRoutePickerView(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        view.prioritizesVideoDevices = false
        view.alpha = 0.011
        return view
    }()

    override init(frame: CGRect) {
        super.init(frame: frame.isEmpty ? CGRect(x: 0, y: 0, width: 44, height: 44) : frame)
        backgroundColor = .clear
        isUserInteractionEnabled = true
        addSubview(picker)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        picker.frame = bounds
    }

    override func sizeThatFits(_ size: CGSize) -> CGSize {
        let w = size.width.isFinite && size.width > 0 ? max(size.width, 44) : 44
        let h = size.height.isFinite && size.height > 0 ? max(size.height, 44) : 44
        return CGSize(width: w, height: h)
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard isUserInteractionEnabled, !isHidden, self.bounds.contains(point) else { return nil }
        let pickerPoint = convert(point, to: picker)
        if let hit = picker.hitTest(pickerPoint, with: event) { return hit }
        return AirPlayRouteTrigger.firstControl(in: picker) ?? picker
    }
}

struct AirPlayRoutePicker: UIViewRepresentable {
    var trigger: AirPlayRouteTrigger

    func makeUIView(context: Context) -> AirPlayRouteContainerView {
        let container = AirPlayRouteContainerView(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        trigger.picker = container.picker
        return container
    }

    func updateUIView(_ container: AirPlayRouteContainerView, context: Context) {
        trigger.picker = container.picker
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: AirPlayRouteContainerView, context: Context) -> CGSize? {
        let w = max(proposal.width ?? 44, 44)
        let h = max(proposal.height ?? 44, 44)
        return CGSize(width: w, height: h)
    }
}
