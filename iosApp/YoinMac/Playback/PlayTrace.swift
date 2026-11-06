import Foundation
import os

/// The Mac's stand-in for iOS PlayTrace (Yoin/App/PlayTrace.swift is iOS-only: its export uses UIKit):
/// the same calls, written to the system log (Console › subsystem com.gpo.yoin.mac, category playtrace).
enum PlayTrace {
    private static let logger = Logger(subsystem: "com.gpo.yoin.mac", category: "playtrace")

    nonisolated static func log(_ s: String) {
        logger.info("\(s, privacy: .public)")
    }
}
