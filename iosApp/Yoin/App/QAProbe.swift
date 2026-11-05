import os
import QuartzCore
import SwiftUI

/// Animation QA, in any build (profiling needs Release): off unless a launch
/// argument asks for it.
///
/// `-qaHitches YES` logs every frame that came late (category "hitch"), with
/// what was moving, and marks it as a signpost for Instruments.
/// `-qaCycle np|search|sheet` opens and closes that surface every few
/// seconds, so a profile or a recording catches several runs.
@MainActor
enum QAProbe {
    static let log = Logger(subsystem: "com.gpo.yoin", category: "hitch")
    static let signposter = OSSignposter(subsystem: "com.gpo.yoin", category: .pointsOfInterest)

    static func start(_ model: AppModel) {
        let d = UserDefaults.standard
        if d.bool(forKey: "qaHitches") { HitchMonitor.shared.start(model) }
        if let cycle = d.string(forKey: "qaCycle") {
            Task { await run(cycle, model) }
        }
    }

    private static func run(_ cycle: String, _ model: AppModel) async {
        try? await Task.sleep(for: .seconds(2))
        for i in 0..<4 {
            let open = signposter.beginInterval("open", id: signposter.makeSignpostID(), "\(cycle) \(i)")
            set(cycle, true, model)
            try? await Task.sleep(for: .seconds(1.5))
            signposter.endInterval("open", open)
            let close = signposter.beginInterval("close", id: signposter.makeSignpostID(), "\(cycle) \(i)")
            set(cycle, false, model)
            try? await Task.sleep(for: .seconds(1.5))
            signposter.endInterval("close", close)
        }
    }

    private static func set(_ cycle: String, _ open: Bool, _ model: AppModel) {
        HitchMonitor.shared.phase = "\(cycle) \(open ? "open" : "close")"
        print("[hitch] phase \(HitchMonitor.shared.phase)")
        switch cycle {
        case "np": model.setNowPlaying(expanded: open)
        case "nptap":
            // As a finger does it: down on the pill (prewarm), a tap's length, then open.
            if open {
                model.np.prewarm()
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(110))
                    model.setNowPlaying(expanded: true)
                }
            } else {
                model.setNowPlaying(expanded: false)
            }
        case "search":
            if open { model.openSearch(from: .bar) } else {
                AppModel.dismissKeyboard()
                withAnimation(Yoin.spatial) { model.searchActive = false }
            }
        case "sheet":
            if open {
                let p = model.playback
                PlaylistActions.shared.openMenu(PlaylistEntry(album: p.album, track: p.track), context: .nowPlaying)
            } else {
                PlaylistActions.shared.menu = nil
            }
        default: break
        }
    }
}

/// Late frames, measured on the display link: a frame is late when the gap
/// since the last one is over 1.5 refresh intervals.
@MainActor
final class HitchMonitor: NSObject {
    static let shared = HitchMonitor()
    var phase = "idle"
    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0
    private weak var model: AppModel?

    func start(_ model: AppModel) {
        self.model = model
        let l = CADisplayLink(target: self, selector: #selector(tick(_:)))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        l.add(to: .main, forMode: .common)
        link = l
    }

    @objc private func tick(_ l: CADisplayLink) {
        defer { last = l.timestamp }
        guard last > 0 else { return }
        let gap = l.timestamp - last
        let expected = l.targetTimestamp - l.timestamp
        guard expected > 0, gap > expected * 1.5 else { return }
        let np = model?.np.progress ?? 0
        let rise = SheetRise.shared.progress
        let ms = Int((gap * 1000).rounded())
        QAProbe.log.log("late \(ms, privacy: .public)ms phase=\(self.phase, privacy: .public) np=\(np, format: .fixed(precision: 2), privacy: .public) rise=\(rise, format: .fixed(precision: 2), privacy: .public)")
        QAProbe.signposter.emitEvent("late", "\(ms)ms \(self.phase)")
        // Also on stdout: `devicectl device process launch --console` shows it.
        print(String(format: "[hitch] late %dms phase=%@ np=%.2f rise=%.2f", ms, phase, np, rise))
    }
}
