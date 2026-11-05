import Foundation
import Observation

/// The Home sections a listener can show, hide and reorder: Android's
/// catalog (ui/home/HomeSection.kt), in its declaration (= default) order.
/// The ids are persisted verbatim and are Android's own, so a layout
/// document reads the same on both apps. iOS renders the first four today;
/// the other three are kept in the layout (their order and visibility ride
/// along) and stay out of the feed and the edit tray until iOS has them.
enum HomeSection: String, CaseIterable, Hashable, Sendable {
    case activities
    case jumpBackIn = "jump_back_in"
    case recentlyAdded = "recently_added"
    case rediscover
    case recentlyPlayed = "recently_played"
    case yourPlaylists = "your_playlists"
    case otherProfiles = "other_profiles"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .activities: "Activities"
        case .jumpBackIn: "Jump Back In"
        case .recentlyAdded: "Recently Added"
        case .rediscover: "Rediscover"
        case .recentlyPlayed: "Recently Played"
        case .yourPlaylists: "Your Playlists"
        case .otherProfiles: "On Your Other Accounts"
        }
    }

    /// One line for the tray rows and VoiceOver (not on the feed).
    var supportingText: String {
        switch self {
        case .activities: "Your recent plays and visits"
        case .jumpBackIn: "Albums, songs and playlists"
        case .recentlyAdded: "Added to your library this month"
        case .rediscover: "Albums and songs you rated or wrote about, not played in a while"
        case .recentlyPlayed: "Albums you played lately, on any device"
        case .yourPlaylists: "Playlists in your library"
        case .otherProfiles: "Albums and playlists your other accounts play most"
        }
    }

    var defaultEnabled: Bool { true }

    /// Visibility when appended to an already-customized layout (Android Q6a):
    /// Rediscover arrives hidden, badged "New" in the tray.
    var appendEnabled: Bool { self != .rediscover }

    /// iOS renders it (the rest are carried, not shown).
    var supportedOnIOS: Bool {
        switch self {
        case .activities, .jumpBackIn, .recentlyAdded, .rediscover: true
        case .recentlyPlayed, .yourPlaylists, .otherProfiles: false
        }
    }

    /// Ids of sections removed from the catalog: dropped, never retained.
    static let retiredIds: Set<String> = ["memory_teaser", "memories"]
}

/// One persisted section choice (Android `HomeSectionPref`): its id, whether
/// it renders, and its settings bag (`{"rows":"xl"}` on Android) kept as raw
/// JSON so nothing a newer build wrote is lost on re-encode.
struct HomeSectionPref: Hashable, Sendable {
    var id: String
    var enabled: Bool
    /// The `config` object, verbatim; nil = absent (never written as null).
    var config: Data? = nil
}

/// One section and whether it renders, at its resolved position.
struct HomeSectionState: Hashable, Sendable {
    var section: HomeSection
    var enabled: Bool
    var config: Data? = nil
}

/// The resolved, ordered Home layout (Android `HomeLayout`). Disabled
/// entries keep their slot, so "Show" returns a section home; ids this build
/// doesn't know ride along in `retained`. Every operation returns an equal
/// value when it changes nothing.
struct HomeLayout: Hashable, Sendable {
    var sections: [HomeSectionState]
    var retained: [HomeSectionPref] = []
    /// Sections appended hidden to a customized layout on this read (badged "New").
    var newSections: Set<HomeSection> = []

    static let `default` = HomeLayout(sections: HomeSection.allCases.map { HomeSectionState(section: $0, enabled: $0.defaultEnabled) })

    var enabledSections: [HomeSection] { sections.filter(\.enabled).map(\.section) }
    var hiddenSections: [HomeSection] { sections.filter { !$0.enabled }.map(\.section) }

    /// What iOS draws, in order.
    var shownSections: [HomeSection] { enabledSections.filter(\.supportedOnIOS) }
    /// The edit tray: hidden sections iOS can draw.
    var trayed: [HomeSection] { hiddenSections.filter(\.supportedOnIOS) }

    var isDefault: Bool { sections == Self.default.sections }

    func toPrefs() -> [HomeSectionPref] {
        sections.map { HomeSectionPref(id: $0.section.id, enabled: $0.enabled, config: $0.config) } + retained
    }

    /// Same order and visibility (ignores `retained` / `newSections`).
    func sameSections(as other: HomeLayout) -> Bool { sections == other.sections }

    func reset() -> HomeLayout {
        isDefault ? self : HomeLayout(sections: Self.default.sections, retained: retained)
    }

    func withEnabled(_ section: HomeSection, _ enabled: Bool) -> HomeLayout {
        guard sections.contains(where: { $0.section == section && $0.enabled != enabled }) else { return self }
        var next = self
        next.sections = sections.map { s in
            var s = s
            if s.section == section { s.enabled = enabled }
            return s
        }
        if enabled { next.newSections.remove(section) }
        return next
    }

    /// Refill the enabled slots in [order]; disabled entries keep their
    /// absolute index (proto.js withEnabledOrder). Unchanged when [order] is
    /// the current one or not a permutation of the enabled sections.
    func withEnabledOrder(_ order: [HomeSection]) -> HomeLayout {
        let enabled = enabledSections
        guard order != enabled, order.count == enabled.count, Set(order) == Set(enabled),
              Set(order).count == order.count else { return self }
        let byId = Dictionary(uniqueKeysWithValues: sections.filter(\.enabled).map { ($0.section, $0) })
        var it = order.makeIterator()
        var next = self
        next.sections = sections.map { s in s.enabled ? byId[it.next()!]! : s }
        return next
    }

    /// Move [section] to [index] among the enabled sections (clamped).
    func moved(_ section: HomeSection, toEnabledIndex index: Int) -> HomeLayout {
        var enabled = enabledSections
        guard let from = enabled.firstIndex(of: section) else { return self }
        let to = min(max(index, 0), enabled.count - 1)
        guard to != from else { return self }
        enabled.remove(at: from)
        enabled.insert(section, at: to)
        return withEnabledOrder(enabled)
    }

    /// Move [section] among the sections iOS shows: the unsupported enabled
    /// ones keep their slots, as hidden ones do.
    func movedShown(_ section: HomeSection, to index: Int) -> HomeLayout {
        var shown = shownSections
        guard let from = shown.firstIndex(of: section) else { return self }
        let to = min(max(index, 0), shown.count - 1)
        guard to != from else { return self }
        shown.remove(at: from)
        shown.insert(section, at: to)
        var it = shown.makeIterator()
        let order = enabledSections.map { $0.supportedOnIOS ? it.next()! : $0 }
        return withEnabledOrder(order)
    }

    /// Merge a persisted layout with the catalog (Android `HomeLayout.reconcile`):
    /// saved order, flags and settings for known ids (first occurrence wins);
    /// retired ids dropped, other unknown ids retained in order; catalog
    /// sections the save never knew appended at `appendEnabled` (hidden
    /// ones become `newSections`). nil / empty = never customized = default.
    static func reconcile(_ prefs: [HomeSectionPref]?) -> HomeLayout {
        guard let prefs, !prefs.isEmpty else { return .default }
        var seen = Set<HomeSection>()
        var retainedIds = Set<String>()
        var ordered: [HomeSectionState] = []
        var retained: [HomeSectionPref] = []
        for p in prefs {
            if let s = HomeSection(rawValue: p.id) {
                if seen.insert(s).inserted { ordered.append(HomeSectionState(section: s, enabled: p.enabled, config: p.config)) }
            } else if p.id.trimmingCharacters(in: .whitespaces).isEmpty || HomeSection.retiredIds.contains(p.id) {
                continue
            } else if retainedIds.insert(p.id).inserted {
                retained.append(p)
            }
        }
        let appended = HomeSection.allCases.filter { seen.insert($0).inserted }
        ordered += appended.map { HomeSectionState(section: $0, enabled: $0.appendEnabled) }
        return HomeLayout(sections: ordered, retained: retained,
                          newSections: Set(appended.filter { !$0.appendEnabled }))
    }
}

/// The layout document (Android HomeLayoutStore's `SectionsDto`):
/// `{"version":1,"sections":[{"id":"activities","enabled":true}, …]}`,
/// decoded entry by entry: an unreadable document is "never set", a bad
/// entry drops only itself.
enum HomeLayoutCodec {
    static let schemaVersion = 1

    static func encode(_ prefs: [HomeSectionPref]) -> Data {
        let sections: [[String: Any]] = prefs.map { p in
            var o: [String: Any] = ["id": p.id, "enabled": p.enabled]
            if let c = p.config, let obj = try? JSONSerialization.jsonObject(with: c), obj is [String: Any] { o["config"] = obj }
            return o
        }
        let doc: [String: Any] = ["version": schemaVersion, "sections": sections]
        return (try? JSONSerialization.data(withJSONObject: doc, options: [.sortedKeys])) ?? Data()
    }

    static func decode(_ data: Data) -> [HomeSectionPref]? {
        guard let doc = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let raw = doc["sections"] as? [Any] else { return nil }
        return raw.compactMap { entry in
            guard let o = entry as? [String: Any], let id = o["id"] as? String,
                  let enabledValue = o["enabled"], let n = enabledValue as? NSNumber,
                  CFGetTypeID(n) == CFBooleanGetTypeID() else { return nil }
            var config: Data?
            if let c = o["config"] as? [String: Any] { config = try? JSONSerialization.data(withJSONObject: c, options: [.sortedKeys]) }
            return HomeSectionPref(id: id, enabled: n.boolValue, config: config)
        }
    }
}

/// Per-account persistence of the Home layout (Android `home_layout`, PK
/// profile id): one document per account scope, in one JSON file in
/// Application Support. A layout equal to what is stored is not rewritten;
/// Reset clears the account back to "never customized".
@MainActor
@Observable
final class HomeLayoutStore {
    static let shared = HomeLayoutStore(fileURL: HomeLayoutStore.defaultURL)

    /// scope → the layout document's prefs.
    private var docs: [String: [HomeSectionPref]] = [:]
    @ObservationIgnored private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let all = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            for (scope, value) in all {
                guard let doc = try? JSONSerialization.data(withJSONObject: value) else { continue }
                if let prefs = HomeLayoutCodec.decode(doc) { docs[scope] = prefs }
            }
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("home-layout.json")
    }

    /// The saved prefs for [scope]; nil = never customized.
    func prefs(_ scope: String) -> [HomeSectionPref]? { docs[scope] }

    func layout(_ scope: String) -> HomeLayout { HomeLayout.reconcile(docs[scope]) }

    /// Saves [layout] for [scope]; skipped when the stored document already holds it.
    func save(_ layout: HomeLayout, scope: String) {
        let prefs = layout.toPrefs()
        guard docs[scope] != prefs else { return }
        docs[scope] = prefs
        write()
    }

    /// Back to "never customized" (Reset): new sections follow their defaults again.
    func clear(_ scope: String) {
        guard docs[scope] != nil else { return }
        docs[scope] = nil
        write()
    }

    private func write() {
        var all: [String: Any] = [:]
        for (scope, prefs) in docs {
            if let obj = try? JSONSerialization.jsonObject(with: HomeLayoutCodec.encode(prefs)) { all[scope] = obj }
        }
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try JSONSerialization.data(withJSONObject: all, options: [.sortedKeys])
            try data.write(to: fileURL, options: .atomic)
        } catch {
            // Best effort: the session's draft stays authoritative; the next write heals.
        }
    }
}
