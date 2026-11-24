import CoreML
import Foundation

/// Active lease holding a loaded CoreML `MLModel` plus its backing ODR resource access.
/// Calling `release()` (or letting the lease deallocate) ends ODR access so Yoin returns
/// immediately to 0% idle ML memory while iOS keeps the 8.3 MB model cached via preservation priority.
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

/// Fetches and loads `LyraSingLite.mlmodelc` on demand through Apple On-Demand Resources (`sing_model_v1`),
/// prioritizing Apple Neural Engine + GPU + CPU (`computeUnits = .all`) and following Race-to-Idle release.
actor SingModelODRProvider {
    enum ODRError: LocalizedError {
        case modelResourceMissing(tag: String, resource: String)

        var errorDescription: String? {
            switch self {
            case .modelResourceMissing(let tag, let resource):
                return "\(resource) not found in ODR bundle for tag '\(tag)'"
            }
        }
    }

    static let tag = SingModelAsset.odrTag

    private let bundle: Bundle
    private let resourceName: String
    private let resourceExtension: String
    private var activeRequest: NSBundleResourceRequest?

    init(
        bundle: Bundle = .main,
        resourceName: String = SingModelAsset.compiledResourceName,
        resourceExtension: String = SingModelAsset.compiledResourceExtension
    ) {
        self.bundle = bundle
        self.resourceName = resourceName
        self.resourceExtension = resourceExtension
    }

    /// Loads `LyraSingLite.mlmodelc` via Apple ODR (`sing_model_v1`) with `computeUnits = .all`.
    /// Checks local ODR cache non-blockingly first; downloads from Apple CDN only on first use.
    func loadModel(onProgress: @escaping @Sendable (Double) -> Void) async throws -> SingModelLease {
        // End any stale previous request before opening a fresh access window.
        releaseAfterSongFinished()

        let req = NSBundleResourceRequest(tags: [Self.tag], bundle: bundle)
        req.loadingPriority = NSBundleResourceRequestLoadingPriorityUrgent

        let observation = req.progress.observe(\.fractionCompleted, options: [.new]) { prog, _ in
            onProgress(prog.fractionCompleted)
        }
        defer { observation.invalidate() }

        let alreadyAvailable = await req.conditionallyBeginAccessingResources()
        if !alreadyAvailable {
            do {
                try await req.beginAccessingResources()
            } catch {
                req.endAccessingResources()
                throw error
            }
        }

        // Pin preservation priority high so iOS keeps the ~8.3 MB model cached across songs.
        bundle.setPreservationPriority(SingModelAsset.odrPreservationPriority, forTags: [Self.tag])
        activeRequest = req

        guard let modelURL = bundle.url(forResource: resourceName, withExtension: resourceExtension) else {
            releaseAfterSongFinished()
            throw ODRError.modelResourceMissing(tag: Self.tag, resource: "\(resourceName).\(resourceExtension)")
        }

        let config = MLModelConfiguration()
        config.computeUnits = .all // Apple Neural Engine + GPU + CPU optimal routing
        do {
            let model = try await MLModel.load(contentsOf: modelURL, configuration: config)
            return SingModelLease(
                model: model,
                sourceDescription: "CoreML ANE+ODR (\(Self.tag))",
                odrCached: alreadyAvailable
            ) { [weak self] in
                Task { await self?.releaseAfterSongFinished() }
            }
        } catch {
            releaseAfterSongFinished()
            throw error
        }
    }

    /// Releases the active ODR resource access right after inference completes (Race-to-Idle).
    func releaseAfterSongFinished() {
        activeRequest?.endAccessingResources()
        activeRequest = nil
    }
}
