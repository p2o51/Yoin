import Foundation
import YoinShared

/// The scrapbook's credits line (Android `ui/detail/AlbumCredits.kt`, owner + Carbon 2026-11-25): under the
/// album's title beside the cover, in place of Last Play (which stays on page 1 and on the receipt). Its rules
/// (item order, the service's label first, mastering → mix, the reissue's first release, where the line may
/// break) are :shared's `YoinAlbumAbout`, pinned by shared/src/commonTest/resources/golden/album-about.json, so
/// iOS writes the line Android writes; this file supplies the words (Localizable) and the date format.
enum AlbumCredits {
    /// The line's items for one album: the service's own labels and dates first, then the AI's credits.
    static func items(_ album: Album, ai: AlbumAiCredits) -> [AlbumCreditItem] {
        YoinAlbumAbout.shared.creditItems(sourceLabels: album.recordLabels, releaseDate: album.releaseDate,
                                          originalReleaseDate: album.originalReleaseDate, ai: ai)
    }

    /// The line in the app's language; nil when no item is known (the line is left out, never a placeholder).
    static func line(_ items: [AlbumCreditItem], labels: AlbumCreditLabels = labels(),
                     locale: Locale = appLocale) -> AlbumCreditsLine? {
        YoinAlbumAbout.shared.creditsLine(items: items, labels: labels, dateText: dateText(items, locale: locale))
    }

    /// The credits one by one (label, value), for the About sheet's receipt.
    static func rows(_ items: [AlbumCreditItem]) -> [AlbumCreditRow] { line(items)?.rows ?? [] }

    /// The words the line is built with (Android `detail_album_credit_*`): 厂牌 / Label, 制作 / Producer, …,
    /// the name separator "、" / ", ", and the time-and-place format "%1$@，%2$@" / "%1$@, %2$@" (handed to :shared
    /// with Android's `%1$s` placeholders, which it fills in).
    static func labels(bundle: Bundle = .main) -> AlbumCreditLabels {
        func s(_ key: String) -> String { bundle.localizedString(forKey: key, value: nil, table: nil) }
        return AlbumCreditLabels(label: s("albumCredit.label"), producer: s("albumCredit.producer"),
                                 mastering: s("albumCredit.mastering"), mixing: s("albumCredit.mixing"),
                                 recorded: s("albumCredit.recorded"), source: s("albumCredit.source"),
                                 firstReleased: s("albumCredit.firstReleased"),
                                 nameSeparator: s("albumCredit.nameSeparator"),
                                 // Localizable keeps Swift's placeholders; :shared fills in Android's.
                                 timeAndPlace: s("%1$@, %2$@").replacingOccurrences(of: "$@", with: "$s"))
    }

    /// The service-dated first release (`AlbumCreditItem.date`) in [locale]'s long date ("May 20, 2014",
    /// "2014年5月20日"; "May 2014" / "2014年5月" for a month; the year alone as it is). Nil when none is dated.
    static func dateText(_ items: [AlbumCreditItem], locale: Locale = appLocale) -> String? {
        guard let iso = items.lazy.compactMap({ $0.date }).first else { return nil }
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard let year = parts.first else { return nil }
        guard parts.count >= 2 else { return String(year) }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        guard let date = utc.date(from: DateComponents(year: year, month: parts[1],
                                                       day: parts.count >= 3 ? parts[2] : 1)) else { return nil }
        let formatter = DateFormatter()
        formatter.calendar = utc
        formatter.timeZone = utc.timeZone
        formatter.locale = locale
        formatter.setLocalizedDateFormatFromTemplate(parts.count >= 3 ? "yMMMMd" : "yMMMM")
        return formatter.string(from: date)
    }

    /// The app's own language (what Localizable resolved to), for dates that sit among its words.
    static var appLocale: Locale {
        Locale(identifier: Bundle.main.preferredLocalizations.first ?? "en")
    }
}
