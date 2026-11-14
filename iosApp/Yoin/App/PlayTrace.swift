import AVFoundation
import CoreTransferable
import Foundation
import UIKit
import UniformTypeIdentifiers

/// The playback diagnostics log (Settings › About › Export log): the last [capacity] lines of
/// playback commands, Spotify Web API and App Remote events, audio session changes and app
/// lifecycle, kept in memory and shared as a .txt from the phone. Never tokens, stream URLs or the
/// listener's server: callers log paths, statuses and ids only; [redact] cuts anything shaped like a
/// token, a Subsonic credential or a URL's address from every line, AVAudioOutput.describe is how a
/// stream URL may be logged and [maskHosts] is how an error message may be (Android PlayTrace).
enum PlayTrace {
    static let capacity = 2000
    private static let queue = DispatchQueue(label: "com.gpo.yoin.playtrace")
    nonisolated(unsafe) private static var lines: [String] = []
    /// Each line's time, with the day as Android's log has it (MM-dd HH:mm:ss.SSS): a log kept on disk
    /// across relaunches can span days.
    static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MM-dd HH:mm:ss.SSS"
        return f
    }()

    nonisolated static func log(_ s: String) {
        let now = Date()
        queue.async {
            let line = "\(stamp.string(from: now)) \(redact(s))"
            lines.append(line)
            if lines.count > capacity { lines.removeFirst(lines.count - capacity) }
            persist(line)
            #if DEBUG
            print("[PT] \(line)")
            #endif
        }
    }

    /// The same lines on disk (Library/Caches/playtrace.log), so a log survives a relaunch and can
    /// be pulled from a connected phone; cut back to [capacity] lines when it doubles.
    nonisolated(unsafe) private static var handle: FileHandle?
    nonisolated(unsafe) private static var written = 0
    private static var fileURL: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("playtrace.log")
    }

    private static func persist(_ line: String) {
        if handle == nil {
            if !FileManager.default.fileExists(atPath: fileURL.path) {
                FileManager.default.createFile(atPath: fileURL.path, contents: nil)
            } else if lines.count == 1, let old = try? String(contentsOf: fileURL, encoding: .utf8) {
                // A relaunch: the previous run's tail comes first, cleaned as today's lines are (a build
                // before the server was kept out wrote stream lines with its address), and the file
                // starts again from that clean tail.
                let tail = old.split(separator: "\n").suffix(capacity - 1).map { redact(String($0)) }
                try? (tail.joined(separator: "\n") + (tail.isEmpty ? "" : "\n")).write(to: fileURL, atomically: true, encoding: .utf8)
                lines = tail + lines
            }
            handle = try? FileHandle(forWritingTo: fileURL)
            _ = try? handle?.seekToEnd()
        }
        try? handle?.write(contentsOf: Data((line + "\n").utf8))
        written += 1
        if written >= capacity {
            written = 0
            try? handle?.close()
            handle = nil
            try? (lines.joined(separator: "\n") + "\n").write(to: fileURL, atomically: true, encoding: .utf8)
        }
    }

    /// Belt and braces (Android PlayTrace.redact): anything shaped like a bearer or OAuth token, a Subsonic
    /// credential parameter or a URL's user:password is cut, and so is every URL's address ("https://…/").
    nonisolated static func redact(_ s: String) -> String {
        s.replacingOccurrences(of: #"(access_token=|refresh_token=|code=|Bearer )[^\s&#"]+"#,
                               with: "$1…", options: .regularExpression)
            .replacingOccurrences(of: #"([?&](?:u|t|s|p|apiKey|token|salt)=)[^\s&#"]+"#,
                                  with: "$1…", options: .regularExpression)
            .replacingOccurrences(of: #"(://)[^/\s?#"]+"#, with: "$1…", options: .regularExpression)
    }

    /// An error message as it may be logged (Android PlayTrace.maskHosts): a URL's address, a host name
    /// and an IPv4 / IPv6 literal become "…", so a network failure ("A server with the specified hostname
    /// could not be found", "…music.example.com:4533…") never names the listener's server. Dotted names
    /// that are not hosts are cut as well: only for error words, never for a line of Yoin's own.
    nonisolated static func maskHosts(_ s: String?) -> String {
        (s ?? "")
            .replacingOccurrences(of: #"(://)[^/\s?#"]+"#, with: "$1…", options: .regularExpression)
            .replacingOccurrences(of: #"\[[0-9a-fA-F:.]+\]|\b(?:\d{1,3}\.){3}\d{1,3}\b|\b(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}\b"#,
                                  with: "…", options: .regularExpression)
    }

    /// The log as a file in the caches folder, for the share sheet.
    nonisolated static func writeExport() throws -> URL {
        let text: String = queue.sync {
            let device = "Yoin \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] ?? "?") (\(Bundle.main.infoDictionary?["CFBundleVersion"] ?? "?"))"
            // Cleaned once more on the way out: whatever an older build kept never leaves the phone.
            return ([device, ""] + lines.map(redact)).joined(separator: "\n") + "\n"
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
