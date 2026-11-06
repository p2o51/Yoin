import Foundation

/// How many rows a section shows (Android `HomeRowPreset`, D1): presets, not numbers. Each screen
/// turns one into its own composition, so the choice travels with the account across devices.
/// L is the composition from before presets existed: a section nobody resized stores nothing.
enum HomeRowPreset: String, CaseIterable, Hashable, Sendable {
    case s, m, l, xl

    /// The key stored in the section's settings bag (`{"rows":"xl"}`): Android's own.
    var key: String { rawValue }
    static let `default`: HomeRowPreset = .l

    /// The preset stored under [key]; nil for a key this build doesn't know.
    init?(key: String?) {
        guard let key, let p = HomeRowPreset(rawValue: key) else { return nil }
        self = p
    }

    var ordinal: Int { Self.allCases.firstIndex(of: self) ?? 0 }
}

extension HomeSection {
    /// Activities and Jump Back In take row presets (Android `supportsRows`); the others' phone
    /// shelves have no rows.
    var supportsRows: Bool { self == .activities || self == .jumpBackIn }
}

/// The settings bag as Android reads it (HomeSectionState.fromPref / config()): a readable `rows`
/// is the preset, every other key stays verbatim, and a `rows` this build can't read stays in the
/// bag too, so a re-encode loses nothing.
enum HomeRowsConfig {
    static let rowsKey = "rows"

    static func object(_ config: Data?) -> [String: Any]? {
        guard let config, let o = (try? JSONSerialization.jsonObject(with: config)) as? [String: Any] else { return nil }
        return o
    }

    /// The preset in [config]; L when absent or unreadable.
    static func rows(_ config: Data?) -> HomeRowPreset {
        (object(config)?[rowsKey] as? String).flatMap { HomeRowPreset(key: $0) } ?? .default
    }

    /// [config] at [rows]: `rows` is written unless it is the default, the other keys untouched;
    /// nil when nothing is left.
    static func setting(_ rows: HomeRowPreset, in config: Data?) -> Data? {
        var bag = object(config) ?? [:]
        if HomeRowPreset(key: bag[rowsKey] as? String) != nil { bag[rowsKey] = nil }
        if rows != .default { bag[rowsKey] = rows.key }
        guard !bag.isEmpty else { return nil }
        return try? JSONSerialization.data(withJSONObject: bag, options: [.sortedKeys])
    }
}

extension HomeSectionState {
    var rows: HomeRowPreset { HomeRowsConfig.rows(config) }
}

extension HomeLayout {
    /// [section]'s row preset (L when it is not in this layout).
    func rowsOf(_ section: HomeSection) -> HomeRowPreset {
        sections.first { $0.section == section }.map(\.rows) ?? .default
    }

    /// [section] at [rows]. Unchanged for a section without presets, absent, or already there.
    func withRows(_ section: HomeSection, _ rows: HomeRowPreset) -> HomeLayout {
        guard section.supportsRows, sections.contains(where: { $0.section == section && $0.rows != rows }) else { return self }
        var next = self
        next.sections = sections.map { s in
            var s = s
            if s.section == section { s.config = HomeRowsConfig.setting(rows, in: s.config) }
            return s
        }
        return next
    }
}

// MARK: - Ladders

/// One detent of a section's ladder on this screen: the presets that resolve to [payload]
/// (smallest first), how many rows it shows and the composition itself.
struct HomeRowStop<T: Hashable>: Hashable {
    var presets: [HomeRowPreset]
    var rowCount: Int
    var payload: T

    /// The preset to store for this stop, coming from [from] (Android `presetFrom`): [from] itself
    /// when it is one of them (the stored choice is kept), else L, else the nearest.
    func presetFrom(_ from: HomeRowPreset) -> HomeRowPreset {
        if presets.contains(from) { return from }
        if presets.contains(.default) { return .default }
        return presets.min { abs($0.ordinal - from.ordinal) < abs($1.ordinal - from.ordinal) } ?? .default
    }
}

/// Every preset resolved by [resolve] (composition, row count), consecutive presets with an equal
/// composition merged into one stop (Android `homeRowLadder`).
func homeRowLadder<T: Hashable>(_ resolve: (HomeRowPreset) -> (T, Int)) -> [HomeRowStop<T>] {
    var stops: [HomeRowStop<T>] = []
    for preset in HomeRowPreset.allCases {
        let (payload, rows) = resolve(preset)
        if let last = stops.last, last.payload == payload {
            stops[stops.count - 1].presets.append(preset)
        } else {
            stops.append(HomeRowStop(presets: [preset], rowCount: rows, payload: payload))
        }
    }
    return stops
}

extension Array {
    /// The stop [preset] resolves to (the first, for a preset not on the ladder).
    func stopIndex<T: Hashable>(of preset: HomeRowPreset) -> Int where Element == HomeRowStop<T> {
        firstIndex { $0.presets.contains(preset) } ?? 0
    }
}

extension Array {
    /// The composition of the stop [preset] resolves to.
    func stopPayload<T: Hashable>(for preset: HomeRowPreset) -> T where Element == HomeRowStop<T> {
        self[stopIndex(of: preset)].payload
    }

    /// The element at [index], or nil past either end.
    func entry(at index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

// MARK: - Activities (the phone bento)

enum ActivitySlotKind: Hashable, Sendable { case hero, small, wide, strip }

enum ActivityRowShape: Hashable, Sendable {
    /// The hero alone, full width.
    case hero
    /// Cards side by side by weight.
    case cards
    /// One strip, full width.
    case strip
}

/// One card of a row: its kind, its share of the row and its entry (index into the supporting
/// entries; -1 = the hero). An entry past the supply is a spacer of the same weight.
struct ActivityCodeSlot: Hashable, Sendable {
    var kind: ActivitySlotKind
    var weight: Double
    var entryIndex: Int
}

struct ActivityCodeRow: Hashable, Sendable {
    var shape: ActivityRowShape
    var slots: [ActivityCodeSlot]
}

enum HomeRowPresets {
    /// The phone bento at [preset] with [supportingCount] supporting entries on offer (Android
    /// `activityCodeRows`, Phone recipe): S = the hero, M = + small | wide, L = + one strip
    /// (today), XL = + a mirrored wide | small before the strip, so L's strip grows into XL's wide
    /// card. Entries go out in reading order; a row whose first entry is past the supply is
    /// dropped, a later one becomes a spacer. Without a hero, S is the first card row.
    static func activityRows(preset: HomeRowPreset, hasHero: Bool, supportingCount: Int) -> [ActivityCodeRow] {
        var next = 0
        func slot(_ kind: ActivitySlotKind, _ weight: Double) -> ActivityCodeSlot {
            defer { next += 1 }
            return ActivityCodeSlot(kind: kind, weight: weight, entryIndex: next)
        }
        var rows: [ActivityCodeRow] = []
        if hasHero {
            rows.append(ActivityCodeRow(shape: .hero, slots: [ActivityCodeSlot(kind: .hero, weight: 1, entryIndex: -1)]))
        }
        func cardRow(_ slots: [ActivityCodeSlot]) {
            if slots[0].entryIndex < supportingCount { rows.append(ActivityCodeRow(shape: .cards, slots: slots)) }
        }
        if preset == .s && hasHero { return rows }
        cardRow([slot(.small, 1), slot(.wide, 2)])
        if preset.ordinal <= HomeRowPreset.m.ordinal { return rows }
        if preset == .xl { cardRow([slot(.wide, 2), slot(.small, 1)]) }
        let strip = slot(.strip, 1)
        if strip.entryIndex < supportingCount {
            rows.append(ActivityCodeRow(shape: .strip, slots: [strip]))
        }
        return rows
    }

    /// Supporting entries [preset] seats (every slot but the hero's).
    static func activitySeats(preset: HomeRowPreset, hasHero: Bool) -> Int {
        activityRows(preset: preset, hasHero: hasHero, supportingCount: .max)
            .reduce(0) { $0 + $1.slots.filter { $0.entryIndex >= 0 }.count }
    }

    /// The Activities ladder with [supportingCount] supporting entries on offer: presets the
    /// supply can't tell apart merge, so a short history gets fewer detents.
    static func activityLadder(hasHero: Bool, supportingCount: Int) -> [HomeRowStop<[ActivityCodeRow]>] {
        homeRowLadder { preset in
            let seated = min(supportingCount, activitySeats(preset: preset, hasHero: hasHero))
            let rows = activityRows(preset: preset, hasHero: hasHero, supportingCount: seated)
            return (rows, rows.count)
        }
    }

    // MARK: Jump Back In (the phone's packed shelf, 3 columns)

    /// Rows of Jump Back In at [preset] on the phone: 2 / 3 / 4 (today) / 6 (Android `jbiPresetRows`).
    static func jbiRows(_ preset: HomeRowPreset) -> Int {
        switch preset {
        case .s: 2
        case .m: 3
        case .l: 4
        case .xl: 6
        }
    }

    static let jbiColumns = 3
    private static let jbiPhoneShelfCells = 12
    private static let jbiTwoSignalMinCells = 9
    private static let jbiCellsPerExtraSignal = 6

    /// Signal cards a [rows]-row grid of [cells] cells takes (Android `jbiSignalLimit`): one on a
    /// single row or under nine cells, two up to the phone's twelve, one more for every six past.
    static func jbiSignalLimit(rows: Int, cells: Int) -> Int {
        if rows <= 1 || cells < jbiTwoSignalMinCells { return 1 }
        if cells <= jbiPhoneShelfCells { return 2 }
        return 2 + (cells - jbiPhoneShelfCells) / jbiCellsPerExtraSignal
    }

    /// The signals a phone shelf at [preset] takes of [available].
    static func jbiSignals(_ preset: HomeRowPreset, available: Int) -> Int {
        let rows = jbiRows(preset)
        return min(available, jbiSignalLimit(rows: rows, cells: rows * jbiColumns))
    }
}
