import Foundation
import YoinShared

/// The production credits the lyrics source wrote into the lyrics ("作词 : 唐恬", "Mixed by …",
/// copyright notices), as Now Playing reads them (Android `LyricCredits`). The shared parser takes
/// them out of the lines into `Lyrics.credits` (shared LyricCreditsExtractor, Android's rule), so the
/// lyrics view shows only what is sung; the spine and About show them instead.
struct SongCredits: Equatable, Sendable {
    /// A credit that isn't one of the four headline roles (Android `LyricCredits.others`).
    struct Credit: Equatable, Sendable {
        /// The role as the source wrote it ("混音", "Bass Guitar"); empty for a free-form line
        /// ("Recorded at …").
        var role: String
        var names: String
    }

    /// The four headline roles, each merged across lines ("A / B"; Android `namesFor`).
    var lyricist: String?
    var composer: String?
    var arranger: String?
    var producer: String?
    /// Every other credit, in file order.
    var others: [Credit]
    /// Copyright and licensing lines, shown small at the foot of About.
    var notices: [String]

    init(lyricist: String? = nil, composer: String? = nil, arranger: String? = nil, producer: String? = nil,
         others: [Credit] = [], notices: [String] = []) {
        self.lyricist = lyricist
        self.composer = composer
        self.arranger = arranger
        self.producer = producer
        self.others = others
        self.notices = notices
    }

    /// From the shared parse; nil when the lyrics had none.
    init?(_ credits: LyricCredits?) {
        guard let credits else { return nil }
        self.init(lyricist: credits.namesFor(role: .lyricist),
                  composer: credits.namesFor(role: .composer),
                  arranger: credits.namesFor(role: .arranger),
                  producer: credits.namesFor(role: .producer),
                  others: credits.others.map { Credit(role: $0.role, names: $0.names) },
                  notices: credits.notices)
    }

    /// Android `credits.namesFor(key.lyricCreditRole())`: the names the lyrics give for an About
    /// canonical key they can answer (lyricist, composer, producer); nil for the rest.
    func names(forAboutKey key: String) -> String? {
        switch key {
        case SongAboutStore.Canon.lyricist: lyricist
        case SongAboutStore.Canon.composer: composer
        case SongAboutStore.Canon.producer: producer
        default: nil
        }
    }
}
