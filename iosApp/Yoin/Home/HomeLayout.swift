import Foundation
import Observation
import YoinShared

/// The Home sections a listener can show, hide and reorder: Android's
/// catalog (ui/home/HomeSection.kt), in its declaration (= default) order.
/// The ids are persisted verbatim and are Android's own, so a layout
/// document reads the same on both apps. iOS renders all but On Your Other
/// Accounts, which is kept in the layout (its order and visibility ride
/// along) and stays out of the feed and the edit tray until iOS has it.
/// Legacy contract alias preserved for tests after promoting `.madeForYou` into `HomeSection`.
enum MadeForYouSectionContract {
    static let rawId = HomeSection.madeForYou.rawValue
}

enum HomeSection: String, CaseIterable, Hashable, Sendable {
    case activities
    case jumpBackIn = "jump_back_in"
    case recentlyAdded = "recently_added"
    case rediscover
    case recentlyPlayed = "recently_played"
    case yourPlaylists = "your_playlists"
    case otherProfiles = "other_profiles"
    case lyricHighlight = "lyric_highlight"
    case memoryGrid = "memory_grid"
    case madeForYou = "made_for_you"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .activities: String(localized: "Activities")
        case .jumpBackIn: String(localized: "Jump Back In")
        case .recentlyAdded: String(localized: "Recently Added")
        case .rediscover: String(localized: "Rediscover")
        case .recentlyPlayed: String(localized: "Recently Played")
        case .yourPlaylists: String(localized: "Your Playlists")
        case .otherProfiles: String(localized: "On Your Other Accounts")
        case .lyricHighlight: String(localized: "Lyric Cards")
        case .memoryGrid: String(localized: "All Memories")
        case .madeForYou: String(localized: "Made For You")
        }
    }

    /// One line for the tray rows and VoiceOver (not on the feed).
    var supportingText: String {
        switch self {
        case .activities: String(localized: "Your recent plays and visits")
        case .jumpBackIn: String(localized: "Albums, songs and playlists")
        case .recentlyAdded: String(localized: "Added to your library this month")
        case .rediscover: String(localized: "Albums and songs you rated or wrote about, not played in a while")
        case .recentlyPlayed: String(localized: "Albums you played lately, on any device")
        case .yourPlaylists: String(localized: "Playlists in your library")
        case .otherProfiles: String(localized: "Albums and playlists your other accounts play most")
        case .lyricHighlight: String(localized: "Lyrics picked from your songs, tap to play from that line")
        case .memoryGrid: String(localized: "Every note and score, on a grid that grows with you")
        case .madeForYou: String(localized: "Playlists Spotify and Apple Music make for you")
        }
    }

    /// The one line an empty section says while Home is edited (Android `placeholderRes`).
    var placeholder: String {
        switch self {
        case .activities: String(localized: "No recent activity")
        case .jumpBackIn: String(localized: "Nothing to jump back into")
        case .recentlyAdded: String(localized: "Nothing added this month")
        case .rediscover: String(localized: "Nothing to rediscover yet")
        case .recentlyPlayed: String(localized: "Nothing played lately")
        case .yourPlaylists: String(localized: "No playlists in your library")
        case .otherProfiles: String(localized: "Nothing played on your other accounts lately")
        case .lyricHighlight: String(localized: "No lyric cards yet")
        case .memoryGrid: String(localized: "No memories yet")
        case .madeForYou: String(localized: "No made for you playlists yet")
        }
    }

    var defaultEnabled: Bool { true }

    /// Visibility when appended to an already-customized layout (Android Q6a):
    /// Rediscover arrives hidden, badged "New" in the tray.
    var appendEnabled: Bool { self != .rediscover }

    /// iOS renders it (the rest are carried, not shown).
    var supportedOnIOS: Bool {
        switch self {
        case .activities, .jumpBackIn, .recentlyAdded, .rediscover, .recentlyPlayed, .yourPlaylists,
             .otherProfiles, .lyricHighlight, .memoryGrid, .madeForYou: true
        }
    }

    /// Ids of sections removed from the catalog: dropped, never retained.
    static let retiredIds: Set<String> = ["memory_teaser", "memories"]
}

/// One persisted section choice as a document entry (Android `HomeSectionPref`): its id, whether
/// it renders, its settings bag (`{"rows":"xl"}`) and the entry's other keys, both raw JSON objects.
/// Only for building a document by hand (tests, `HomeLayout.reconcile`); the layout itself is the
/// shared state.
struct HomeSectionPref: Hashable, Sendable {
    var id: String
    var enabled: Bool
    /// The `config` object, verbatim; nil = absent (never written as null).
    var config: Data? = nil
    /// The entry's other keys (a JSON object a newer build wrote), verbatim; nil = none.
    var extra: Data? = nil
}

/// One section at its resolved position, as the shared layout holds it (`HomeLayoutSectionState`):
/// whether it renders, its row preset (and whether it was chosen), half of a two-column pair, its own
/// unit count (Lyric Cards), and what this build keeps without reading.
struct HomeSectionState: Hashable, Sendable {
    var section: HomeSection
    var enabled: Bool
    var rows: HomeRowPreset = .default
    var rowsSet: Bool = false
    var half: Bool = false
    var units: Int? = nil
    var configExtrasJson: String? = nil
    var entryExtrasJson: String? = nil

    /// The settings bag as the document writes it (`rows` when chosen or not L, `span`, `units`, the kept keys); nil when empty.
    var config: Data? {
        var bag = configExtrasJson.flatMap { (try? JSONSerialization.jsonObject(with: Data($0.utf8))) as? [String: Any] } ?? [:]
        if rowsSet || rows != .default { bag["rows"] = rows.key }
        if half { bag["span"] = "half" }
        if let units { bag["units"] = units }
        return bag.isEmpty ? nil : try? JSONSerialization.data(withJSONObject: bag, options: [.sortedKeys])
    }
}

/// The resolved, ordered Home layout (Android `HomeLayout`), on the shared core: a value wrapping
/// `HomeLayoutState`, every edit a call into `YoinHomeLayout` (shared/…/home/YoinHomeLayout.kt), so iOS
/// answers exactly as shared/src/commonTest/resources/golden/home-layout.json says. One order and one
/// show / hide state for both widths, plus the two-column pairing memory (`widePairs`, design.md「两栏的并排
/// 记忆」): order and visibility edits keep the memory and set the `"span":"half"` flags from it at the new
/// order (`withRememberedPairs`), so a document iOS writes never carries a stale half. Disabled entries
/// keep their slot; ids this build doesn't know ride along in `retained`. An edit that changes nothing
/// returns an equal value.
struct HomeLayout: Hashable, @unchecked Sendable {
    /// The shared value (immutable).
    let state: HomeLayoutState
    /// [state]'s sections, in render order.
    let sections: [HomeSectionState]

    init(state: HomeLayoutState) {
        self.state = state
        sections = state.sections.compactMap { s in
            guard let section = HomeSection(rawValue: s.id) else { return nil }
            return HomeSectionState(section: section, enabled: s.enabled, rows: HomeRowPreset(key: s.rows) ?? .default,
                                    rowsSet: s.rowsSet, half: s.half, units: s.units.map { Int($0.int32Value) },
                                    configExtrasJson: s.configExtrasJson, entryExtrasJson: s.entryExtrasJson)
        }
    }

    static func == (a: HomeLayout, b: HomeLayout) -> Bool { a.state.isEqual(b.state) }
    func hash(into h: inout Hasher) { h.combine(state.hash) }

    private static var core: YoinHomeLayout { YoinHomeLayout.shared }

    /// The phone default: what a never-customized account shows in one column.
    static let `default` = HomeLayout(state: YoinHomeLayout.shared.defaultState())
    /// The large-screen default (two columns, B2).
    static let wideDefault = HomeLayout(state: YoinHomeLayout.shared.wideDefaultState())

    var enabledSections: [HomeSection] { sections.filter(\.enabled).map(\.section) }
    var hiddenSections: [HomeSection] { sections.filter { !$0.enabled }.map(\.section) }

    /// What iOS draws, in order.
    var shownSections: [HomeSection] { enabledSections.filter(\.supportedOnIOS) }
    /// The edit tray: hidden sections iOS can draw.
    var trayed: [HomeSection] { hiddenSections.filter(\.supportedOnIOS) }

    /// Entries whose id this build doesn't know, kept verbatim.
    var retained: [HomeLayoutRetainedState] { state.retained }
    /// Sections appended hidden to a customized layout on this read (badged "New").
    var newSections: Set<HomeSection> { Set(state.newSections.compactMap(HomeSection.init(rawValue:))) }
    /// The two-column memory, `[left, right]`.
    var widePairs: [[String]] { state.widePairs }

    /// The phone default (rows and the memory included; retained ids and new sections aside).
    var isDefault: Bool { Self.core.isDefault(state: state) }
    /// Either default (the phone's or the two-column one): written, it clears the account back to "never customized".
    var isAnyDefault: Bool { Self.core.isAnyDefault(state: state) }

    /// Same order, visibility, rows and settings, and the same pairing memory (ignores `retained` / `newSections`).
    func sameSections(as other: HomeLayout) -> Bool { Self.core.sameSections(a: state, b: other.state) }

    private func applying(_ result: HomeLayoutResult) -> HomeLayout { result.changed ? HomeLayout(state: result.state) : self }

    /// The catalog arrangement (one column), keeping `retained` and the document's kept keys.
    func reset() -> HomeLayout { applying(Self.core.reset(state: state, twoColumn: false)) }

    func withEnabled(_ section: HomeSection, _ enabled: Bool) -> HomeLayout {
        applying(Self.core.withEnabled(state: state, id: section.id, enabled: enabled))
    }

    /// Refill the enabled slots in [order]; disabled entries keep their absolute index. [unseen]: enabled
    /// sections this device doesn't draw (they keep their place). Unchanged when [order] is the current one
    /// or not a permutation of the enabled sections.
    func withEnabledOrder(_ order: [HomeSection], unseen: [HomeSection] = []) -> HomeLayout {
        applying(Self.core.withEnabledOrder(state: state, order: order.map(\.id), unseen: unseen.map(\.id)))
    }

    /// Move [section] to [index] among the enabled sections (clamped).
    func moved(_ section: HomeSection, toEnabledIndex index: Int) -> HomeLayout {
        applying(Self.core.moved(state: state, id: section.id, toEnabledIndex: Int32(clamping: index)))
    }

    /// Move [section] among the sections iOS shows: the unsupported enabled ones keep their slots, as hidden ones do.
    func movedShown(_ section: HomeSection, to index: Int) -> HomeLayout {
        var shown = shownSections
        guard let from = shown.firstIndex(of: section) else { return self }
        let to = min(max(index, 0), shown.count - 1)
        guard to != from else { return self }
        shown.remove(at: from)
        shown.insert(section, at: to)
        return withEnabledOrder(shown, unseen: enabledSections.filter { !$0.supportedOnIOS })
    }

    /// [section] at the row preset [rows]. Unchanged for a section that takes no presets, or already there.
    func withRows(_ section: HomeSection, _ rows: HomeRowPreset) -> HomeLayout {
        applying(Self.core.withRows(state: state, id: section.id, rowsKey: rows.key))
    }

    /// [section]'s row preset (L when it is not in this layout).
    func rowsOf(_ section: HomeSection) -> HomeRowPreset {
        sections.first { $0.section == section }?.rows ?? .default
    }

    /// The landing's own reorder: [sections] in a new order (hidden ones included), the half flags then
    /// set from the memory at it (`withRememberedPairs`).
    func withSectionsOrdered(_ order: [HomeSection]) -> HomeLayout {
        let byId = Dictionary(state.sections.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let reordered = order.compactMap { byId[$0.id] }
        guard reordered.count == state.sections.count, reordered.map(\.id) != state.sections.map(\.id) else { return self }
        let next = state.doCopy(sections: reordered, retained: state.retained, newSections: state.newSections,
                                widePairs: state.widePairs, retainedWideJson: state.retainedWideJson,
                                retainedDocumentJson: state.retainedDocumentJson)
        return HomeLayout(state: Self.core.withRememberedPairs(state: next).state)
    }

    // MARK: Two columns (design.md「两栏」; Android HomeLayout's two-column half, through the facade)

    /// The default this width resets to: the large-screen one in two columns.
    func isDefault(twoColumn: Bool) -> Bool { Self.core.isDefaultFor(state: state, twoColumn: twoColumn) }

    /// [reset] to this width's default (WideDefault in two columns), keeping `retained` and the kept keys.
    func reset(twoColumn: Bool) -> HomeLayout { applying(Self.core.reset(state: state, twoColumn: twoColumn)) }

    /// What the feed shows at its width: in two columns a never-customized layout is WideDefault.
    func forTwoColumn(_ twoColumn: Bool) -> HomeLayout { applying(Self.core.forTwoColumn(state: state, twoColumn: twoColumn)) }

    /// The memory made what two columns show now (every two-column edit ends with it).
    func rememberingShownPairs() -> HomeLayout { applying(Self.core.rememberingShownPairs(state: state)) }

    /// The draft after a width change in edit mode (shared `homeDraftAtWidth`): untouched it is what entering at
    /// that width would make of [base]; edited it keeps its order and two columns pair it from its memory.
    func atWidth(edited: Bool, base: HomeLayout, twoColumn: Bool) -> HomeLayout {
        let next = Self.core.draftAtWidth(draft: state, edited: edited, base: base.state, twoColumn: twoColumn)
        return next.isEqual(state) ? self : HomeLayout(state: next)
    }

    /// The pairs the enabled order shows now, left → right.
    var pairs: [(left: HomeSection, right: HomeSection)] {
        Self.core.pairs(state: state).compactMap { p in
            guard p.count == 2, let l = HomeSection(rawValue: p[0]), let r = HomeSection(rawValue: p[1]) else { return nil }
            return (l, r)
        }
    }

    /// [section]'s partner in its pair, either side; nil when it is full width.
    func partner(of section: HomeSection) -> HomeSection? {
        Self.core.partnerOf(state: state, id: section.id).flatMap(HomeSection.init(rawValue:))
    }

    /// The unit count a feed row (one section, or a pair left → right) shows in two columns.
    func units(ofRow row: [HomeSection]) -> Int { Int(Self.core.rowUnits(state: state, row: row.map(\.id))) }

    /// [section] (full width) the left half of a pair with the row after it.
    func withHalf(_ section: HomeSection) -> HomeLayout { applying(Self.core.withHalf(state: state, id: section.id)) }

    /// [section] (one of a pair) full width, its partner on its own row after it.
    func withFull(_ section: HomeSection) -> HomeLayout { applying(Self.core.withFull(state: state, id: section.id)) }

    /// [section] (full width) the right half beside the full section before it (VoiceOver's "Place beside previous").
    func withBesidePrevious(_ section: HomeSection) -> HomeLayout {
        applying(Self.core.withBesidePrevious(state: state, id: section.id))
    }

    /// A two-column drop: [order] (the shown enabled sections) with exactly [pairs] as halves.
    func withArrangement(_ order: [HomeSection], pairs: [(HomeSection, HomeSection)], unseen: [HomeSection]) -> HomeLayout {
        applying(Self.core.withArrangement(state: state, order: order.map(\.id), pairs: pairs.map { [$0.0.id, $0.1.id] },
                                           unseen: unseen.map(\.id)))
    }

    /// [section]'s row (and its partner) at [units] in two columns, held in the pair's overlap.
    func withUnits(_ section: HomeSection, _ units: Int) -> HomeLayout {
        applying(Self.core.withUnits(state: state, id: section.id, units: Int32(clamping: units)))
    }

    /// Merge persisted entries with the catalog (Android `HomeLayout.reconcile`), through the document they
    /// make: nil / empty = never customized = default.
    static func reconcile(_ prefs: [HomeSectionPref]?) -> HomeLayout {
        guard let prefs, !prefs.isEmpty else { return .default }
        return HomeLayoutCodec.decode(HomeLayoutCodec.document(prefs)) ?? .default
    }
}

/// The layout document (shared `HomeLayoutCodec`, Android's `home_layout.sectionsJson`):
/// `{"version":1,"sections":[{"id":"activities","enabled":true,"config":{…}}, …],"wide":{"pairs":[…]}}`.
/// Read and written by the shared codec: an unreadable document is "never set", a bad entry drops only
/// itself, and every key this build doesn't read (the document's, each entry's, the memory's) is kept.
enum HomeLayoutCodec {
    /// The layout [json] reads as; nil when it isn't a layout document.
    static func decode(_ json: String) -> HomeLayout? {
        guard YoinShared.HomeLayoutCodec.shared.decode(raw: json) != nil else { return nil }
        return HomeLayout(state: YoinHomeLayout.shared.decode(documentJson: json))
    }

    /// [layout]'s document text, every kept key written back.
    static func encode(_ layout: HomeLayout) -> String { YoinHomeLayout.shared.encode(state: layout.state) }

    /// How persisting [layout] treats the stored row (shared `homePersistAction`): either default with no
    /// retained ids clears it (back to "never customized"); anything else writes its document.
    static func persist(_ layout: HomeLayout) -> HomeLayoutWrite {
        let p = YoinHomeLayout.shared.persist(state: layout.state)
        if p.clear { return .clear }
        return .write(p.documentJson ?? encode(layout))
    }

    /// A document of [prefs] as written by hand (no `wide`: read like an older writer's).
    static func document(_ prefs: [HomeSectionPref]) -> String {
        let sections: [[String: Any]] = prefs.map { p in
            var o = p.extra.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] } ?? [:]
            o["id"] = p.id
            o["enabled"] = p.enabled
            if let c = p.config, let obj = try? JSONSerialization.jsonObject(with: c), obj is [String: Any] { o["config"] = obj }
            return o
        }
        let data = (try? JSONSerialization.data(withJSONObject: ["version": 1, "sections": sections], options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }
}

/// What persisting a layout does to the stored row.
enum HomeLayoutWrite: Equatable {
    case clear
    case write(String)
}

/// Per-account persistence of the Home layout (Android `home_layout`, PK
/// profile id): one document per account scope, in one JSON file in
/// Application Support. A layout equal to what is stored is not rewritten;
/// either default clears the account back to "never customized".
///
/// Each scope keeps the document's exact text and when it last changed:
/// cloud sync carries the text as is (a peer's encoder spells it its own
/// way, and rewriting it would look like a local edit), and a newer change
/// wins.
@MainActor
@Observable
final class HomeLayoutStore {
    static let shared = HomeLayoutStore(fileURL: HomeLayoutStore.defaultURL)

    struct Entry: Equatable {
        /// The document as written or received.
        var json: String
        /// Epoch ms.
        var updatedAt: Int64
        /// What it reads as.
        var layout: HomeLayout
    }

    /// scope → its document.
    private var docs: [String: Entry] = [:]
    /// Bumps when a synced change lands from another device (an open Home rereads it).
    private(set) var remoteRevision = 0
    @ObservationIgnored private let fileURL: URL
    /// A local edit or Reset: cloud sync has something to send.
    @ObservationIgnored static var onLocalChange: (() -> Void)?

    init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let all = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
            for (scope, value) in all {
                if let o = value as? [String: Any], let json = o["json"] as? String {
                    // {"json": "<document>", "updatedAt": ms}
                    guard let layout = HomeLayoutCodec.decode(json) else { continue }
                    docs[scope] = Entry(json: json, updatedAt: (o["updatedAt"] as? NSNumber)?.int64Value ?? 0, layout: layout)
                } else if let doc = try? JSONSerialization.data(withJSONObject: value),
                          let layout = HomeLayoutCodec.decode(String(decoding: doc, as: UTF8.self)) {
                    // The first file format: the document itself.
                    docs[scope] = Entry(json: HomeLayoutCodec.encode(layout), updatedAt: 0, layout: layout)
                }
            }
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("home-layout.json")
    }

    /// [scope] has a stored document (it was customized).
    func isCustomized(_ scope: String) -> Bool { docs[scope] != nil }

    func layout(_ scope: String) -> HomeLayout { docs[scope]?.layout ?? .default }

    /// Persists [layout] for [scope] by the shared rule (`HomeLayoutCodec.persist`): either default with no
    /// retained ids clears the account; anything else writes its document, skipped when the stored one
    /// already says the same.
    func save(_ layout: HomeLayout, scope: String) {
        switch HomeLayoutCodec.persist(layout) {
        case .clear: clear(scope)
        case .write(let json): write(json, layout: layout, scope: scope)
        }
    }

    /// Writes the document [json] (what [layout] encodes to) for [scope]; skipped when the stored document reads the same.
    func write(_ json: String, layout: HomeLayout, scope: String) {
        if let old = docs[scope], old.json == json || HomeLayoutCodec.encode(old.layout) == json { return }
        let old = docs[scope]
        docs[scope] = Entry(json: json, updatedAt: Self.now, layout: HomeLayoutCodec.decode(json) ?? layout)
        guard writeFile() else { docs[scope] = old; return }
        Self.onLocalChange?()
    }

    /// Back to "never customized" (Reset): new sections follow their defaults again.
    func clear(_ scope: String) {
        guard let old = docs[scope] else { return }
        docs[scope] = nil
        guard writeFile() else { docs[scope] = old; return }
        Self.onLocalChange?()
    }

    // MARK: Cloud sync

    /// [scope]'s document as it travels, or nil (the default layout).
    func synced(_ scope: String) -> Entry? { docs[scope] }

    /// Another device's layout for [scope], at [updatedAt]. False when the
    /// text isn't a layout document or the file couldn't be written (nothing changes).
    func applySynced(json: String, updatedAt: Int64, scope: String) -> Bool {
        guard let layout = HomeLayoutCodec.decode(json) else { return false }
        let old = docs[scope]
        docs[scope] = Entry(json: json, updatedAt: updatedAt, layout: layout)
        guard writeFile() else { docs[scope] = old; return false }
        remoteRevision += 1
        return true
    }

    /// Another device went back to the default layout.
    func clearSynced(_ scope: String) -> Bool {
        guard let old = docs[scope] else { return true }
        docs[scope] = nil
        guard writeFile() else { docs[scope] = old; return false }
        remoteRevision += 1
        return true
    }

    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// True once the file holds every document.
    @discardableResult
    private func writeFile() -> Bool {
        var all: [String: Any] = [:]
        for (scope, e) in docs {
            all[scope] = ["json": e.json, "updatedAt": NSNumber(value: e.updatedAt)] as [String: Any]
        }
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try JSONSerialization.data(withJSONObject: all, options: [.sortedKeys])
            try data.write(to: fileURL, options: .atomic)
            return true
        } catch {
            return false
        }
    }
}
