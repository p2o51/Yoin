import Foundation
import UIKit
import os

/// Downloads that keep going while Yoin is suspended or after the system ended it (Codex review #12, Android's
/// WorkManager downloads): one background URLSession. Each task is named by its download key, so a relaunch
/// finds the transfer already running and waits on it instead of starting a second one; a file that finishes
/// while nothing waits is kept in `finished/` under its key until the downloads pick it up.
final class BackgroundTransfers: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    static let identifier = "com.gpo.yoin.offline"
    static let shared = BackgroundTransfers(identifier: identifier)

    private let lock = OSAllocatedUnfairLock<State>(initialState: State())
    private struct State {
        var waiting: [String: CheckedContinuation<(URL, URLResponse), Error>] = [:]
        var mobile = false
        var session: URLSession?
    }
    private let identifier: String
    let finishedDirectory: URL
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "offline")
    /// The system's completion handler after it woke Yoin for this session's events.
    @MainActor static var systemCompletion: (() -> Void)?

    init(identifier: String) {
        self.identifier = identifier
        finishedDirectory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("YoinOffline", isDirectory: true).appendingPathComponent("finished", isDirectory: true)
        super.init()
        try? FileManager.default.createDirectory(at: finishedDirectory, withIntermediateDirectories: true)
    }

    /// The session, built on first use (or again after the mobile-data choice changed).
    private func session(overMobileData: Bool) -> URLSession {
        lock.withLock { s in
            if let existing = s.session, s.mobile == overMobileData { return existing }
            s.session?.finishTasksAndInvalidate()
            let c = URLSessionConfiguration.background(withIdentifier: identifier)
            c.allowsExpensiveNetworkAccess = overMobileData
            c.allowsConstrainedNetworkAccess = overMobileData
            c.sessionSendsLaunchEvents = true
            c.isDiscretionary = false
            c.timeoutIntervalForResource = 60 * 60 * 6
            let made = URLSession(configuration: c, delegate: self, delegateQueue: nil)
            s.session = made
            s.mobile = overMobileData
            return made
        }
    }

    /// Wakes the session after a relaunch, so events the system held are delivered.
    func reconnect(overMobileData: Bool) { _ = session(overMobileData: overMobileData) }

    /// A file that finished while nothing waited on it, for [key]; the caller takes it.
    func takeFinished(key: String) -> URL? {
        let url = finishedDirectory.appendingPathComponent(OfflineDownloads.fileName(key))
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Downloads [url] for [key] to a temporary file the caller moves; a transfer already running for [key]
    /// (from before a relaunch) is waited on instead of started again.
    func download(_ url: URL, key: String, overMobileData: Bool) async throws -> (URL, URLResponse) {
        let session = session(overMobileData: overMobileData)
        let tasks = await session.allTasks
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<(URL, URLResponse), Error>) in
                lock.withLock { $0.waiting[key]?.resume(throwing: CancellationError()); $0.waiting[key] = c }
                if let running = tasks.first(where: { $0.taskDescription == key && $0.state == .running }) {
                    Self.log.info("download reattached")
                    _ = running
                } else {
                    let task = session.downloadTask(with: url)
                    task.taskDescription = key
                    task.resume()
                }
            }
        } onCancel: {
            Task {
                for t in await session.allTasks where t.taskDescription == key { t.cancel() }
            }
        }
    }

    // MARK: URLSessionDownloadDelegate

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let key = downloadTask.taskDescription, let response = downloadTask.response else { return }
        // The file at [location] is gone once this returns: moved first, then handed over.
        let kept = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        do { try FileManager.default.moveItem(at: location, to: kept) } catch { return }
        let waiter = lock.withLock { $0.waiting.removeValue(forKey: key) }
        if let waiter {
            waiter.resume(returning: (kept, response))
        } else if let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) {
            // Nobody waits (Yoin was ended): kept for the downloads to pick up.
            let dest = finishedDirectory.appendingPathComponent(OfflineDownloads.fileName(key))
            try? FileManager.default.removeItem(at: dest)
            try? FileManager.default.moveItem(at: kept, to: dest)
            let type = http.value(forHTTPHeaderField: "Content-Type") ?? ""
            try? type.write(to: dest.appendingPathExtension("type"), atomically: true, encoding: .utf8)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let key = task.taskDescription else { return }
        lock.withLock { $0.waiting.removeValue(forKey: key) }?.resume(throwing: error)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in
            Self.systemCompletion?()
            Self.systemCompletion = nil
        }
    }
}

/// The system woke Yoin for the downloads' session (UIApplicationDelegate).
final class YoinAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == BackgroundTransfers.identifier else { return completionHandler() }
        MainActor.assumeIsolated {
            BackgroundTransfers.systemCompletion = completionHandler
            BackgroundTransfers.shared.reconnect(overMobileData: OfflineDownloads.shared.overMobileData)
        }
    }
}
