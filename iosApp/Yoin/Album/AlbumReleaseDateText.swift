import Foundation

/// The album's release date as the page writes it (Android `ui/detail/AlbumReleaseDateText.kt`, owner 2026-11-24):
/// always at the precision the service gave (`Album.releaseDate`, ISO "2022-06-12" / "2022-06" / "2022").
///
/// - The header's meta line, in place of the year: "22.6.12", "22.6", the full year "2022" alone (numeric, the same
///   in every language).
/// - Under the last track: the app language's full date ("June 12, 2022" / "2022年6月12日"; "June 2022" /
///   "2022年6月"; the year alone), with no label.
/// - The scrapbook receipt: "Released" ("Jun 12, 2022" / "Jun 2022" / "2022") and "Length" ("47 min" /
///   "1 hr 12 min"), in the receipt's English like its other lines.
enum AlbumReleaseDateText {
    /// A date's parts, as far as the service dated it.
    struct Parts: Equatable {
        var year: Int
        var month: Int?
        var day: Int?
    }

    /// [iso] read back into its parts (ReleaseDateISO wrote it: anything that isn't a real date was dropped then).
    static func parts(_ iso: String?) -> Parts? {
        guard let normal = ReleaseDateISO.parse(iso) else { return nil }
        let p = normal.split(separator: "-").compactMap { Int($0) }
        guard let year = p.first else { return nil }
        return Parts(year: year, month: p.count > 1 ? p[1] : nil, day: p.count > 2 ? p[2] : nil)
    }

    /// The header's date (Android `albumHeaderDate`): "22.6.12" (two-digit year, month and day without leading
    /// zeros), "22.6" for a year and month, the full year "2022" for a year alone; the album's [year] when there is
    /// no date; nil when neither is known.
    static func header(_ iso: String?, year: Int) -> String? {
        guard let d = parts(iso) else { return year > 0 ? String(year) : nil }
        let short = String(format: "%02d", d.year % 100)
        guard let month = d.month else { return String(d.year) }
        guard let day = d.day else { return "\(short).\(month)" }
        return "\(short).\(month).\(day)"
    }

    /// The date under the last track (Android `albumReleaseDateText`), in [locale]'s own words: "October 27, 2023" /
    /// "2023年10月27日" with a day, "October 2023" / "2023年10月" with a month, just the year with a year alone.
    static func full(_ iso: String?, locale: Locale) -> String? {
        guard let d = parts(iso) else { return nil }
        guard let month = d.month else { return String(d.year) }
        return format(d.year, month, d.day, locale: locale, template: d.day == nil ? "yMMMM" : "yMMMMd")
    }

    // The scrapbook receipt is English by design (its other lines are too); the labels are provisional, owned by
    // copy, and kept here to be found and renamed (Android ReceiptReleasedLabel / ReceiptLengthLabel).
    static let receiptReleasedLabel = "Released"
    static let receiptLengthLabel = "Length"

    /// The receipt's Released value (Android `receiptReleaseDate`): "Jun 12, 2022", "Jun 2022", or "2022".
    static func receiptDate(_ iso: String?) -> String? {
        guard let d = parts(iso) else { return nil }
        guard let month = d.month else { return String(d.year) }
        let english = Locale(identifier: "en_US_POSIX")
        return format(d.year, month, d.day, locale: english, pattern: d.day == nil ? "MMM yyyy" : "MMM d, yyyy")
    }

    /// The receipt's Length value (Android `receiptAlbumLength`): "47 min", "1 hr 12 min", "2 hr" (whole minutes;
    /// under a minute reads "1 min"). Nil for no length.
    static func receiptLength(seconds: Int?) -> String? {
        guard let total = seconds, total > 0 else { return nil }
        let minutes = max(1, total / 60)
        let hours = minutes / 60, rest = minutes % 60
        if hours == 0 { return "\(minutes) min" }
        return rest == 0 ? "\(hours) hr" : "\(hours) hr \(rest) min"
    }

    /// The receipt's length source (Android: the album's own duration, else the tracks summed).
    static func albumSeconds(_ album: Album) -> Int? {
        if let d = album.durationSec, d > 0 { return d }
        let sum = album.tracks.map(\.durationSec).reduce(0, +)
        return sum > 0 ? sum : nil
    }

    /// The receipt's first two lines (Android receiptLines): Released, then Length, each left out when unknown.
    static func receiptRows(_ album: Album) -> [(String, String)] {
        var rows: [(String, String)] = []
        if let date = receiptDate(album.releaseDate) { rows.append((receiptReleasedLabel, date)) }
        if let length = receiptLength(seconds: albumSeconds(album)) { rows.append((receiptLengthLabel, length)) }
        return rows
    }

    /// Noon UTC on the day (the 1st for a month), formatted in UTC: no zone can move it to another day.
    private static func format(_ year: Int, _ month: Int, _ day: Int?, locale: Locale,
                               template: String? = nil, pattern: String? = nil) -> String? {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        guard let date = utc.date(from: DateComponents(year: year, month: month, day: day ?? 1, hour: 12)) else {
            return nil
        }
        let formatter = DateFormatter()
        formatter.calendar = utc
        formatter.timeZone = utc.timeZone
        formatter.locale = locale
        if let template { formatter.setLocalizedDateFormatFromTemplate(template) }
        if let pattern { formatter.dateFormat = pattern }
        return formatter.string(from: date)
    }
}
