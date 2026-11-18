import AppIntents
import Foundation

/// The Play widget's play key (Android WidgetPlayActivity): opens Yoin and starts the item — an album or a
/// playlist from its start, an artist's page. It runs in the app (openAppWhenRun), which owns the player and
/// the sign-in; the extension never plays. Compiled into both targets.
struct PlayFromWidgetIntent: AppIntent {
    static let title: LocalizedStringResource = "Play"
    static let openAppWhenRun: Bool = true
    static let isDiscoverable: Bool = false

    @Parameter(title: "Kind") var entity: String
    @Parameter(title: "Provider") var provider: String
    @Parameter(title: "ID") var id: String

    init() {}

    init(_ item: WidgetSnapshot.Play) {
        entity = item.entity.rawValue
        provider = item.provider
        id = item.id
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        if let e = WidgetSnapshot.Play.Entity(rawValue: entity) {
            WidgetRequests.deliver(.play(entity: e, provider: provider, id: id))
        }
        return .result()
    }
}

/// Widget taps for the app: handled at once once the app's model is up, else kept for it.
@MainActor
enum WidgetRequests {
    static var handler: ((WidgetLink) -> Void)? {
        didSet {
            guard let handler, let p = pending else { return }
            pending = nil
            handler(p)
        }
    }
    private(set) static var pending: WidgetLink?

    static func deliver(_ link: WidgetLink) {
        if let handler { handler(link) } else { pending = link }
    }
}
