import Foundation
import MusicKit
import UIKit
import YoinShared

/// What a tester pastes back when a page fails, since a release build logs nothing (Android ErrorDiagnostics
/// `diagnosticReport`): the build, the device, the failure and what it was caused by, and when. For a server's
/// refusal its Subsonic code and HTTP status; for Spotify's its status, or the request it couldn't read as a
/// template; for Apple Music's its status and Apple's code; for a reply that wouldn't decode, where it stopped.
/// Never a raw message (a server's or an error's can quote a URL, a host or a name), a token, or an id from the
/// library.
enum ErrorDiagnostics {
    /// The report for [error], on this build and device.
    @MainActor
    static func report(_ error: Error, now: Date = .now) -> String {
        report(error, now: now, build: "Yoin " + AboutLinks.versionName(), device: deviceLine)
    }

    nonisolated static func report(_ error: Error, now: Date, build: String, device: String) -> String {
        var lines = [build, device, line(error)]
        if let cause = causeLine(of: error) { lines.append("cause " + cause) }
        lines.append(ISO8601DateFormatter().string(from: now))
        return lines.joined(separator: "\n")
    }

    /// One failure in words that name no one: its kind and its codes.
    nonisolated static func line(_ error: Error) -> String {
        if let k = (error as NSError).kotlinException as? KotlinThrowable { return kotlin(k) }
        if let e = error as? SpotifyAPIError {
            return switch e {
            case .unauthorized: "Spotify HTTP 401"
            case .rateLimited(let wait): "Spotify HTTP 429, retry after \(wait)s"
            case .http(let status, _): "Spotify HTTP \(status)"
            case .decoding(let what): "Spotify response unreadable: \(template(what))"
            case .badRequest: "Spotify bad request"
            }
        }
        if let e = error as? MusicDataRequest.Error {
            return "Apple Music HTTP \(e.status), Apple \(e.code)"
        }
        if let e = error as? DecodingError {
            return decoding(e)
        }
        if let e = error as? URLError {
            return "URLError \(e.code.rawValue)"
        }
        if error is CancellationError { return "Cancelled" }
        let ns = error as NSError
        // A Swift error bridges with its type as the domain; a Foundation one keeps its own.
        return ns.domain.isEmpty ? String(describing: type(of: error)) : "\(ns.domain) \(ns.code)"
    }

    /// A failure from the shared Kotlin code: a server's refusal by its codes, anything else by its kind.
    nonisolated static func kotlin(_ k: KotlinThrowable) -> String {
        if let s = k as? SubsonicException {
            return "Subsonic \(s.code)" + (s.httpStatus.map { ", HTTP \($0.intValue)" } ?? "")
        }
        return String(describing: type(of: k))
    }

    /// What [error] was caused by, when it says: the Kotlin exception's cause, or the error underneath.
    nonisolated static func causeLine(of error: Error) -> String? {
        let ns = error as NSError
        if let k = ns.kotlinException as? KotlinThrowable {
            return k.cause.flatMap { $0 === k ? nil : kotlin($0) }
        }
        return (ns.userInfo[NSUnderlyingErrorKey] as? Error).map(line)
    }

    /// Where decoding stopped, by key path only (the context's own words can quote the data).
    nonisolated static func decoding(_ e: DecodingError) -> String {
        func at(_ path: [CodingKey]) -> String {
            guard !path.isEmpty else { return "" }
            return " at " + path.enumerated().map { i, key in
                key.intValue.map { "[\($0)]" } ?? (i == 0 ? "" : ".") + key.stringValue
            }.joined()
        }
        return switch e {
        case .keyNotFound(let key, let c): "DecodingError keyNotFound \(key.stringValue)\(at(c.codingPath))"
        case .typeMismatch(_, let c): "DecodingError typeMismatch\(at(c.codingPath))"
        case .valueNotFound(_, let c): "DecodingError valueNotFound\(at(c.codingPath))"
        case .dataCorrupted(let c): "DecodingError dataCorrupted\(at(c.codingPath))"
        @unknown default: "DecodingError"
        }
    }

    /// A Spotify request as a template (Android: the request as a template): its own words kept, every id, a
    /// user's included, as {id}. A fixed reason ("repeated page") comes through as it is.
    nonisolated static func template(_ request: String) -> String {
        guard request.contains("/") else { return request }
        var afterUsers = false
        let path = request.split(separator: "?", maxSplits: 1).first.map(String.init) ?? request
        return path.split(separator: "/", omittingEmptySubsequences: false).map { part -> String in
            let word = !part.isEmpty && part.allSatisfy { ("a"..."z").contains($0) || $0 == "-" || $0 == "_" }
            defer { afterUsers = part == "users" }
            return part.isEmpty || part == "v1" || (word && !afterUsers) ? String(part) : "{id}"
        }.joined(separator: "/")
    }

    /// "iOS 26.1, iPhone18,1" (Android: "Android 15 (API 35), Google Pixel 9").
    @MainActor
    static var deviceLine: String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        let version = "\(v.majorVersion).\(v.minorVersion)" + (v.patchVersion > 0 ? ".\(v.patchVersion)" : "")
        return "\(UIDevice.current.systemName) \(version), \(model)"
    }

    /// The hardware's model identifier ("iPhone18,1"), or the simulated one's.
    nonisolated static var model: String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return simulated + " (Simulator)"
        }
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
    }
}
