import AVFoundation
import CoreTransferable
import Foundation
import UIKit
import UniformTypeIdentifiers

/// The playback diagnostics log (Settings › About › Export log): the last [capacity] lines of
/// playback commands, Spotify Web API and App Remote events, audio session changes and app
/// lifecycle, kept in memory and shared as a .txt from the phone. Never tokens or stream URLs:
/// callers log paths, statuses and ids only.
enum PlayTrace {
    static let capacity = 2000
    private static let queue = DispatchQueue(label: "com.gpo.yoin.playtrace")
    nonisolated(unsafe) private static var lines: [String] = []
    private static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    nonisolated static func log(_ s: String) {
        let now = Date()
        queue.async {
            let line = "\(stamp.string(from: now)) \(redact(s))"
            lines.append(line)
            if lines.count > capacity { lines.removeFirst(lines.count - capacity) }
            #if DEBUG
            print("[PT] \(line)")
            #endif
        }
    }

    /// Belt and braces: anything shaped like a bearer or OAuth token is cut.
    nonisolated static func redact(_ s: String) -> String {
        s.replacingOccurrences(of: #"(access_token=|refresh_token=|code=|Bearer )[^\s&#"]+"#,
                               with: "$1…", options: .regularExpression)
    }

    /// The log as a file in the caches folder, for the share sheet.
    nonisolated static func writeExport() throws -> URL {
        let text: String = queue.sync {
            let device = "Yoin \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] ?? "?") (\(Bundle.main.infoDictionary?["CFBundleVersion"] ?? "?"))"
            return ([device, ""] + lines).joined(separator: "\n") + "\n"
        }
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd-HHmmss"
        let url = dir.appendingPathComponent("yoin-log-\(f.string(from: Date())).txt")
        try text.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    @MainActor static func observeApp() {
        let c = NotificationCenter.default
        for (n, name) in [(UIApplication.willEnterForegroundNotification, "willEnterForeground"),
                          (UIApplication.didBecomeActiveNotification, "didBecomeActive"),
                          (UIApplication.willResignActiveNotification, "willResignActive"),
                          (UIApplication.didEnterBackgroundNotification, "didEnterBackground")] {
            _ = c.addObserver(forName: n, object: nil, queue: .main) { _ in log("app \(name)") }
        }
        _ = c.addObserver(forName: AVAudioSession.silenceSecondaryAudioHintNotification, object: nil, queue: .main) { n in
            log("session silenceSecondaryAudioHint \(n.userInfo?[AVAudioSessionSilenceSecondaryAudioHintTypeKey] ?? "-")")
        }
    }
}

/// The log for ShareLink, written when it is shared.
struct PlayTraceExport: Transferable {
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .plainText) { _ in SentTransferredFile(try PlayTrace.writeExport()) }
    }
}
