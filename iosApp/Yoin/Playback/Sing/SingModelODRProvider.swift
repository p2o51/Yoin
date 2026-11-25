import CoreML
import Foundation
import os

/// Active lease holding a loaded CoreML `MLModel` plus optional cleanup on release.
/// Dropping the lease releases `MLModel` immediately so Yoin returns to 0% idle ML memory (Race-to-Idle).
final class SingModelLease: @unchecked Sendable {
    let model: MLModel
    let sourceDescription: String
    let odrCached: Bool
    private let lock = NSLock()
    private var onRelease: (@Sendable () -> Void)?

    init(
        model: MLModel,
        sourceDescription: String,
        odrCached: Bool,
        onRelease: @escaping @Sendable () -> Void = {}
    ) {
        self.model = model
        self.sourceDescription = sourceDescription
        self.odrCached = odrCached
        self.onRelease = onRelease
    }

    func release() {
        let cleanup: (@Sendable () -> Void)?
        lock.lock()
        cleanup = onRelease
        onRelease = nil
        lock.unlock()
        cleanup?()
    }

    deinit {
        release()
    }
}

/// Fetches and installs `LyraSingLite.mlpackage.zip` on demand through Apple On-Demand Resources
/// (`sing_model_v1`), compiles once into `SingModelStore` (`Application Support/YoinSingModels/v1/LyraSingLite.mlmodelc`),
/// and loads with `computeUnits = .all` (ANE + GPU + CPU).
///
/// Why a single `.zip` file in ODR instead of a raw `.mlpackage` directory:
/// Xcode compiles a tagged `.mlpackage` into a `.mlmodelc` directory inside `.assetpack`, but its
/// `OnDemandResources.plist` generator emits `[""]` (an empty string) instead of the directory name,
/// causing `Bundle.main.url(forResource: "LyraSingLite", withExtension: "mlmodelc")` to return `nil`
/// on TestFlight / App Store installs. A single `.zip` file is indexed verbatim as
/// `"LyraSingLite.mlpackage.zip"` in `OnDemandResources.plist`, resolves reliably across all build
/// configurations, and unpacks once into `Application Support` so subsequent songs need zero ODR wait.
final class SingModelODRProvider: Sendable {
    private static let logger = Logger(subsystem: "com.gpo.yoin", category: "SingODR")

    enum ODRError: LocalizedError {
        case modelResourceMissing(tag: String, resource: String)
        case timedOut(tag: String, seconds: Int)

        var errorDescription: String? {
            switch self {
            case .modelResourceMissing(let tag, let resource):
                return "\(resource) not found in ODR bundle for tag '\(tag)'"
            case .timedOut(let tag, let seconds):
                return "ODR download for tag '\(tag)' timed out after \(seconds)s"
            }
        }
    }

    static let tag = SingModelAsset.odrTag
    static let downloadTimeoutSeconds: Int = 45

    private let bundle: Bundle
    private let zipResourceName: String
    private let zipResourceExtension: String
    private let modelStore: SingModelStore

    init(
        bundle: Bundle = .main,
        zipResourceName: String = SingModelAsset.odrZipResourceName,
        zipResourceExtension: String = SingModelAsset.odrZipResourceExtension,
        modelStore: SingModelStore = SingModelStore()
    ) {
        self.bundle = bundle
        self.zipResourceName = zipResourceName
        self.zipResourceExtension = zipResourceExtension
        self.modelStore = modelStore
    }

    /// Loads `LyraSingLite.mlmodelc` with `computeUnits = .all`.
    /// Fast-path: if already unpacked & compiled in `SingModelStore`, loads directly in ~15 ms with zero ODR IPC.
    /// Cold-path: fetches `LyraSingLite.mlpackage.zip` via cancellable, timeout-bounded `NSBundleResourceRequest`,
    /// unpacks + compiles once into `SingModelStore`, releases the ODR request immediately, and returns the lease.
    func loadModel(onProgress: @escaping @Sendable (Double) -> Void) async throws -> SingModelLease {
        let config = MLModelConfiguration()
        config.computeUnits = .all

        if await modelStore.isInstalled {
            let compiledURL = await modelStore.compiledModelURL
            Self.logger.info("Loading cached CoreML model from SingModelStore")
            let model = try await MLModel.load(contentsOf: compiledURL, configuration: config)
            return SingModelLease(
                model: model,
                sourceDescription: "CoreML ANE+ODR (\(Self.tag), installed)",
                odrCached: true
            )
        }

        try Task.checkCancellation()
        let req = NSBundleResourceRequest(tags: [Self.tag], bundle: bundle)
        req.loadingPriority = NSBundleResourceRequestLoadingPriorityUrgent

        let observation = req.progress.observe(\.fractionCompleted, options: [.new]) { prog, _ in
            onProgress(min(0.9, prog.fractionCompleted * 0.9))
        }
        defer {
            observation.invalidate()
            req.endAccessingResources()
        }

        let alreadyAvailable = await req.conditionallyBeginAccessingResources()
        if !alreadyAvailable {
            Self.logger.info("Fetching ODR tag '\(Self.tag, privacy: .public)' from Apple CDN")
            try await Self.beginAccessingWithTimeout(req, seconds: Self.downloadTimeoutSeconds)
        }
        try Task.checkCancellation()

        guard let zipURL = bundle.url(forResource: zipResourceName, withExtension: zipResourceExtension) else {
            Self.logger.error("ODR resource '\(self.zipResourceName, privacy: .public).\(self.zipResourceExtension, privacy: .public)' missing in bundle for tag '\(Self.tag, privacy: .public)'")
            throw ODRError.modelResourceMissing(tag: Self.tag, resource: "\(zipResourceName).\(zipResourceExtension)")
        }

        let compiledURL = try await modelStore.installFromLocalZip(zipURL)
        // Model is now compiled and persisted in `Application Support`; drop ODR preservation
        // priority to 0.0 so iOS can purge the duplicate 7.6 MB `.zip` assetpack on demand.
        bundle.setPreservationPriority(0.0, forTags: [Self.tag])
        onProgress(1.0)
        try Task.checkCancellation()

        let model = try await MLModel.load(contentsOf: compiledURL, configuration: config)
        return SingModelLease(
            model: model,
            sourceDescription: "CoreML ANE+ODR (\(Self.tag))",
            odrCached: alreadyAvailable
        )
    }

    private final class RequestBox: @unchecked Sendable {
        let req: NSBundleResourceRequest
        init(_ req: NSBundleResourceRequest) { self.req = req }
    }

    /// Thread-safe one-shot gate guaranteeing a `CheckedContinuation` is resumed exactly once,
    /// even when `ondemandd` ignores `req.progress.cancel()` and never invokes its completion handler.
    private final class OneShotGate: Sendable {
        private struct State {
            var continuation: CheckedContinuation<Void, Error>?
            var pendingResult: Result<Void, Error>?
            var resolved = false
        }

        private let state = OSAllocatedUnfairLock<State>(initialState: State())

        func install(_ continuation: CheckedContinuation<Void, Error>) {
            let early: Result<Void, Error>? = state.withLock { s in
                if s.resolved {
                    let r = s.pendingResult
                    s.pendingResult = nil
                    return r
                }
                s.continuation = continuation
                return nil
            }
            if let early {
                continuation.resume(with: early)
            }
        }

        func finish(with result: Result<Void, Error>) {
            let cont: CheckedContinuation<Void, Error>? = state.withLock { s in
                guard !s.resolved else { return nil }
                s.resolved = true
                if let existing = s.continuation {
                    s.continuation = nil
                    return existing
                }
                s.pendingResult = result
                return nil
            }
            cont?.resume(with: result)
        }
    }

    /// Runs `req.beginAccessingResources` with immediate Swift `Task` cancellation & hard timeout
    /// via a one-shot continuation so a hung `ondemandd` daemon never blocks structured concurrency exit.
    private static func beginAccessingWithTimeout(_ req: NSBundleResourceRequest, seconds: Int) async throws {
        let box = RequestBox(req)
        try await runWithHardTimeout(
            seconds: Double(seconds),
            timeoutError: ODRError.timedOut(tag: Self.tag, seconds: seconds),
            onCancelOrTimeout: { box.req.progress.cancel() },
            start: { finish in
                box.req.beginAccessingResources { error in
                    if let error {
                        finish(.failure(error))
                    } else {
                        finish(.success(()))
                    }
                }
            }
        )
    }

    /// Exposed internally for unit tests verifying hard timeout & cancellation even if `start` never completes.
    static func runWithHardTimeout(
        seconds: Double,
        timeoutError: Error,
        onCancelOrTimeout: @escaping @Sendable () -> Void,
        start: @escaping @Sendable (@escaping @Sendable (Result<Void, Error>) -> Void) -> Void
    ) async throws {
        let gate = OneShotGate()
        let timeoutTask = Task {
            try await Task.sleep(for: .seconds(seconds))
            onCancelOrTimeout()
            gate.finish(with: .failure(timeoutError))
        }
        defer { timeoutTask.cancel() }

        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                gate.install(cont)
                start { result in
                    gate.finish(with: result)
                }
            }
        } onCancel: {
            timeoutTask.cancel()
            onCancelOrTimeout()
            gate.finish(with: .failure(CancellationError()))
        }
    }
}
