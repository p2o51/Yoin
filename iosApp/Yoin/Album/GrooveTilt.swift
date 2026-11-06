#if os(iOS)
import CoreMotion
import UIKit
#endif
import SwiftUI

/*
 * 唱片刻纹 · tilt. Port of Android ui/memories/emblem/GrooveTilt.kt
 * (groove.js v4 `tiltVars` / `tiltDriver`). The tilt is read as COLOUR, not
 * gloss: the outer ring lines turn lighter (toward the accent) on the side
 * the tilt points to and deeper on the far side; at rest nothing shows.
 *
 * Relative tilt around a slow baseline (τ 1.6 s, so a holding posture
 * becomes neutral), ±18° → ±1, capped to the unit disc, followed by one
 * spring (dampingRatio .9, stiffness 90). CoreMotion runs only while at
 * least one emblem asks for it, and never under Reduce Motion or Low Power
 * Mode (Android: the user's reduced motion or adaptive pressure park the
 * sensor).
 */

/// Prototype `tiltVars`: direction `ta` (degrees, screen axes) and size `tm` (0 at rest).
struct GrooveTiltVars {
    var ta: Double
    var tm: Double

    init(x: Double, y: Double) {
        var tx = x.isNaN ? 0 : x, ty = y.isNaN ? 0 : y
        let mg = hypot(tx, ty)
        if mg > 1 { tx /= mg; ty /= mg }
        ta = atan2(ty, tx) * 180 / .pi
        tm = min(1, hypot(tx, ty))
    }
}

#if os(macOS)
/// The Mac has no device tilt: the emblem stays at rest (ScoreEmblem reads x / y = 0).
@MainActor
final class GrooveTilt {
    static let shared = GrooveTilt()
    let x = 0.0
    let y = 0.0
    func acquire() {}
    func release() {}
}
#else
/// The shared device-tilt follower. Read `x` / `y` (−1…1, +x right, +y down).
@MainActor
@Observable
final class GrooveTilt {
    static let shared = GrooveTilt()

    private(set) var x = 0.0
    private(set) var y = 0.0

    @ObservationIgnored private let motion = CMMotionManager()
    @ObservationIgnored private var users = 0
    // GrooveTiltFilter
    @ObservationIgnored private var baseBeta = 0.0
    @ObservationIgnored private var baseGamma = 0.0
    @ObservationIgnored private var hasBase = false
    @ObservationIgnored private var lastSample: TimeInterval = -1
    // GrooveTiltSpring
    @ObservationIgnored private var px = 0.0
    @ObservationIgnored private var py = 0.0
    @ObservationIgnored private var vx = 0.0
    @ObservationIgnored private var vy = 0.0
    @ObservationIgnored private var goalX = 0.0
    @ObservationIgnored private var goalY = 0.0

    private static let baselineTau = 1.6
    private static let fullTiltDeg = 18.0
    private static let rebaseAfter = 1.5
    private static let stiffness = 90.0
    private static let damping = 2 * 0.9 * 90.0.squareRoot()

    /// Static under Reduce Motion / Low Power Mode, or with no motion hardware (the simulator).
    static var available: Bool {
        !UIAccessibility.isReduceMotionEnabled && !ProcessInfo.processInfo.isLowPowerModeEnabled
    }

    func acquire() {
        users += 1
        guard users == 1, Self.available, motion.isDeviceMotionAvailable else { return }
        motion.deviceMotionUpdateInterval = 1.0 / 60
        motion.startDeviceMotionUpdates(to: .main) { [weak self] data, _ in
            guard let data else { return }
            MainActor.assumeIsolated { self?.onSample(data) }
        }
    }

    func release() {
        users = max(0, users - 1)
        guard users == 0 else { return }
        motion.stopDeviceMotionUpdates()
        hasBase = false
        lastSample = -1
        x = 0; y = 0; px = 0; py = 0; vx = 0; vy = 0
    }

    private func onSample(_ data: CMDeviceMotion) {
        // W3C beta (top edge up = +) / gamma (right edge down = +), as the
        // prototype was tuned; flipped for an upside-down iPad.
        var beta = data.attitude.pitch * 180 / .pi
        var gamma = data.attitude.roll * 180 / .pi
        let orientation = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.effectiveGeometry
            .interfaceOrientation
        if orientation == .portraitUpsideDown { beta = -beta; gamma = -gamma }
        let now = data.timestamp
        if !hasBase || now - lastSample > Self.rebaseAfter {
            baseBeta = beta; baseGamma = gamma; hasBase = true
        }
        let dt = lastSample < 0 ? 0.25 : min(0.25, max(0, now - lastSample))
        let a = 1 - exp(-dt / Self.baselineTau)
        baseBeta += (beta - baseBeta) * a
        baseGamma += (gamma - baseGamma) * a
        lastSample = now
        // the light leans away from gravity
        var gx = -(gamma - baseGamma) / Self.fullTiltDeg
        var gy = -(beta - baseBeta) / Self.fullTiltDeg
        let mg = hypot(gx, gy)
        if mg > 1 { gx /= mg; gy /= mg }
        goalX = gx; goalY = gy
        // follow spring: semi-implicit Euler, dt capped at 34 ms
        let h = min(0.034, max(0.001, dt))
        vx += (Self.stiffness * (goalX - px) - Self.damping * vx) * h
        px += vx * h
        vy += (Self.stiffness * (goalY - py) - Self.damping * vy) * h
        py += vy * h
        // only publish visible changes (each publish redraws the emblems)
        if abs(px - x) + abs(py - y) > 0.0005 { x = px; y = py }
    }
}
#endif
