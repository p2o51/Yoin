import Foundation

/// Android MemoryVoice and MemoryDates: local fallbacks and the facts Gemini may read (ported from
/// YoinMac MacMemoryVoice; the Memories deck says these when no generated copy is kept).
enum MemoryVoice {
    struct Copy {
        var narration: String?
        var question: String
        var chinese: Bool
        var paragraph: String { [narration, question].compactMap { $0 }.joined(separator: chinese ? "" : " ") }
    }

    struct Input {
        var card: MemoryCard
        var notes: [SongNote]
        var now: Int64
        var targetLanguage: String
        var calendar = Calendar(identifier: .gregorian)

        init(card: MemoryCard, notes: [SongNote], now: Int64, targetLanguage: String,
             calendar: Calendar = Calendar(identifier: .gregorian)) {
            self.card = card
            self.notes = notes.filter { !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            self.now = now
            self.targetLanguage = targetLanguage
            self.calendar = calendar
        }

        var chinese: Bool {
            var han = 0, latin = 0
            for text in [card.review?.text ?? ""] + notes.map(\.content) {
                for c in text.unicodeScalars {
                    switch c.value {
                    case 0x3040...0x30ff, 0xac00...0xd7af: return targetLanguage.contains("Chinese")
                    case 0x3400...0x9fff: han += 1
                    case 65...90, 97...122: latin += 1
                    default: break
                    }
                }
            }
            if han == 0 && latin == 0 { return targetLanguage.contains("Chinese") }
            return han * 2 >= latin
        }
        var datedNotes: [SongNote] { notes.sorted { $0.updatedAt < $1.updatedAt } }
        var latestTrack: String? {
            guard let latest = datedNotes.last else { return nil }
            return card.album.tracks.first { $0.id.rawId == latest.trackId }?.title ?? latest.title
        }
        var noteDays: Int? {
            guard let first = datedNotes.first, let last = datedNotes.last else { return nil }
            return days(first.updatedAt, last.updatedAt) + 1
        }
        var seasons: Int {
            guard let first = card.firstPlayedAt, let last = card.lastHeardAt, card.playCount > 0 else { return 0 }
            return Int(floor(Double(days(first, last)) / 91 + 0.5))
        }
        var top: MemoryCard.DiaryTrack? {
            card.diaryTracks.filter { $0.rating != nil }.reduce(nil) { best, track in
                guard let best else { return track }
                return (track.rating ?? 0) > (best.rating ?? 0) ? track : best
            }
        }
        var ratedTracks: Int {
            card.album.tracks.isEmpty ? card.memory.ratedTrackCount : card.diaryTracks.filter { $0.rating != nil }.count
        }
        var totalTracks: Int { card.album.tracks.isEmpty ? card.memory.totalTracks : card.album.tracks.count }
        var albumScore: Double? { card.memory.albumRating.flatMap { $0 > 0 ? $0 : nil } }
        var hasListening: Bool { card.playCount > 0 && card.firstPlayedAt != nil && card.lastHeardAt != nil }
        var titleWriting: (kind: String, text: String)? {
            if let review = card.review { return ("album review", review.text) }
            return datedNotes.last.map { ("note", $0.content) }
        }

        func days(_ from: Int64, _ to: Int64) -> Int {
            calendar.dateComponents([.day], from: day(from), to: day(to)).day ?? 0
        }
        private func day(_ ms: Int64) -> Date {
            calendar.startOfDay(for: Date(timeIntervalSince1970: Double(ms) / 1000))
        }
        private func parts(_ ms: Int64) -> DateComponents { calendar.dateComponents([.year, .month, .day], from: day(ms)) }

        func since(chinese: Bool) -> String {
            guard let first = card.firstPlayedAt else { return "" }
            let date = parts(first), month = (date.month ?? 1) - 1, year = date.year ?? 0
            let otherYear = date.year != parts(now).year
            if chinese { return (otherYear ? "\(year)年" : "") + zhMonths[month] }
            return enMonths[month] + (otherYear ? " \(year)" : "")
        }
        func ago(chinese: Bool) -> String {
            guard let last = card.lastHeardAt else { return "" }
            let n = days(last, now)
            if chinese {
                if n <= 0 { return "今天" }
                if n == 1 { return "昨天" }
                if n == 2 { return "前天" }
                if n < 7 { return "\(num(n, chinese: true))天前" }
            } else {
                if n <= 0 { return "today" }
                if n == 1 { return "yesterday" }
                if n < 7 { return "\(num(n, chinese: false)) days ago" }
            }
            let date = parts(last), year = date.year ?? 0, month = date.month ?? 1, day = date.day ?? 1
            let otherYear = date.year != parts(now).year
            if chinese { return (otherYear ? "\(year)年" : "") + "\(month)月\(day)日" }
            return "on \(enShortMonths[month - 1]) \(day)" + (otherYear ? ", \(year)" : "")
        }

        /// Android's four narrative branches, including albums never played in Yoin.
        var fallback: Copy? {
            guard card.review == nil else { return nil }
            let zh = chinese
            if notes.count >= 2, let span = noteDays, ratedTracks == 0, albumScore == nil {
                let narration: String
                if zh {
                    narration = "\(num(span, chinese: true))天里记了\(num(notes.count, chinese: true))条笔记，"
                        + (latestTrack.map { "最近一条写在《\($0)》。" } ?? "最近一条写给整张专辑。")
                } else {
                    narration = "\(num(notes.count, chinese: false, capitalize: true)) notes in \(num(span, chinese: false)) day\(span > 1 ? "s" : ""); "
                        + (latestTrack.map { "the latest was on \($0)." } ?? "the latest was about the whole album.")
                }
                return Copy(narration: narration,
                            question: zh ? "合起来看，这张专辑你会怎么说？" : "Put together, what would you say about the album?", chinese: zh)
            }
            let coverage = totalTracks > 0 ? Double(ratedTracks) / Double(totalTracks) : 0
            if coverage >= 0.6, let top, let rating = top.rating {
                let scoreText = score(rating), title = top.track.title
                let topLine = zh ? "最高分是《\(title)》的 \(scoreText)。"
                    : "\(title) scored highest, \(article(scoreText)) \(scoreText)."
                let question = zh ? "那整张专辑呢？" : "And the album as a whole?"
                if seasons >= 2 {
                    return Copy(narration: zh ? "你跨了\(num(seasons, chinese: true))个季节回来听，\(topLine)"
                                : "You came back across \(num(seasons, chinese: false)) seasons. \(topLine)", question: question, chinese: zh)
                }
                if hasListening {
                    return Copy(narration: zh ? "听了 \(card.playCount) 遍，\(topLine)"
                                : "\(plays(card.playCount)) in, \(title) is still your top track at \(scoreText).", question: question, chinese: zh)
                }
                return Copy(narration: topLine, question: question, chinese: zh)
            }
            if let albumScore {
                let scoreText = score(albumScore)
                let question = zh ? "你给了 \(scoreText) 分，是哪里打动了你？"
                    : "You gave it \(article(scoreText)) \(scoreText). What earned it?"
                guard hasListening else { return Copy(narration: nil, question: question, chinese: zh) }
                return Copy(narration: zh ? "\(since(chinese: true))以来听了 \(card.playCount) 遍，最近一次是\(ago(chinese: true))。"
                            : "\(plays(card.playCount)) since \(since(chinese: false)), the last one \(ago(chinese: false)).", question: question, chinese: zh)
            }
            return Copy(narration: nil, question: zh ? "这张专辑，留下了什么？" : "What stays with you from this one?", chinese: zh)
        }

        /// Android narrationBrief. Only note counts, dates and track names leave this path, never their text.
        var facts: [String] {
            guard card.review == nil else { return [] }
            let zh = targetLanguage.contains("Chinese")
            var out: [String] = []
            if hasListening {
                out += ["Plays in Yoin: \(card.playCount)", "Listening since: \(since(chinese: zh))", "Last played: \(ago(chinese: zh))"]
            }
            if seasons >= 2 { out.append("Seasons between the first and the latest play: \(seasons)") }
            if ratedTracks > 0, totalTracks > 0 {
                out.append("Tracks rated: \(ratedTracks) of \(totalTracks)")
            }
            if let top, let rating = top.rating {
                out.append("Highest-rated track: \(zh ? "《\(top.track.title)》" : top.track.title), \(score(rating))")
            }
            if let albumScore { out.append("Album score the listener gave: \(score(albumScore))") }
            if !notes.isEmpty {
                let span = notes.count >= 2 ? noteDays.map { ", over \($0) days" } ?? "" : ""
                let latest = latestTrack.map { "on the track \(zh ? "《\($0)》" : $0)" } ?? "about the whole album"
                out.append("Notes written: \(notes.count)\(span); the latest is \(latest)")
            }
            return out
        }
        var titleHash: String? {
            titleWriting.map { hash("\(card.album.id.provider):\(card.id)|\(card.album.title)|\($0.kind)|\($0.text)") }
        }
        var narrationHash: String {
            let language = targetLanguage == "English" ? "EN" : targetLanguage == "Simplified Chinese" ? "ZH" : targetLanguage
            return "narration-v2:\(hash(language + "\n\n" + facts.map { $0 + "\n" }.joined()))"
        }
    }

    /// Java/Kotlin String.hashCode, for Android's synced prompt hashes.
    static func hash(_ text: String) -> String {
        String(text.utf16.reduce(Int32(0)) { ($0 &* 31) &+ Int32($1) })
    }
    private static func num(_ n: Int, chinese: Bool, capitalize: Bool = false) -> String {
        let words = chinese ? ["零", "一", "两", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"]
            : ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"]
        let word = words.indices.contains(n) ? words[n] : String(n)
        return capitalize ? word.prefix(1).uppercased() + word.dropFirst() : word
    }
    private static func score(_ value: Double) -> String {
        let tenths = Int(floor(value * 10 + 0.5))
        return "\(tenths / 10).\(tenths % 10)"
    }
    private static func article(_ score: String) -> String { score.hasPrefix("8") ? "an" : "a" }
    private static func plays(_ n: Int) -> String { n == 1 ? "1 play" : "\(n) plays" }
    private static let enMonths = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
    private static let enShortMonths = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    private static let zhMonths = ["一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月"]
}
