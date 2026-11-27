import XCTest
import YoinShared
@testable import Yoin

/// A model that answers what it's told and records what it was asked (no Apple Intelligence needed).
final class FakeOnDeviceTextModel: OnDeviceTextModel, @unchecked Sendable {
    var modelStatus: OnDeviceModelStatus = .available
    var languages: Set<String> = ["en", "zh-Hans"]
    var greetingReply: OnDeviceTyped<OnDeviceGreetingSlots> = .failed
    var followUpReply: OnDeviceTyped<[String]> = .failed
    var textReply: OnDeviceAnswer = .failed
    private(set) var greetingCalls: [(instructions: String, prompt: String)] = []
    private(set) var followUpCalls: [(instructions: String, prompt: String)] = []
    private(set) var textCalls: [String] = []
    private(set) var lastTemperature: Double?
    private(set) var lastMaxTokens: Int?

    func status() -> OnDeviceModelStatus { modelStatus }
    func supportsLanguage(_ language: String) -> Bool { languages.contains(language) }

    func greetings(instructions: String, prompt: String, temperature: Double, maxTokens: Int) async
        -> OnDeviceTyped<OnDeviceGreetingSlots> {
        greetingCalls.append((instructions, prompt))
        lastTemperature = temperature
        lastMaxTokens = maxTokens
        return greetingReply
    }

    func followUps(instructions: String, prompt: String, temperature: Double, maxTokens: Int) async
        -> OnDeviceTyped<[String]> {
        followUpCalls.append((instructions, prompt))
        lastTemperature = temperature
        lastMaxTokens = maxTokens
        return followUpReply
    }

    func text(_ prompt: String, temperature: Double, maxTokens: Int) async -> OnDeviceAnswer {
        textCalls.append(prompt)
        return textReply
    }
}

/// Parity p5: Home's on-device greetings and About's suggested follow-ups on Apple Intelligence. The shared golden
/// vectors (shared/src/commonTest/resources/golden/ondevice-ai.json) run through the Swift facade; the store, the
/// cue reads, the generator, the Labs switches and every silent fallback run over a fake model.
@MainActor
final class OnDeviceAITests: XCTestCase {

    private let ai = YoinOnDeviceAi.shared

    /// XCTAssertEqual without the autoclosure, so an awaited value can be passed straight in.
    private func check<T: Equatable>(_ a: T, _ b: T, _ message: String = "", file: StaticString = #filePath,
                                     line: UInt = #line) {
        XCTAssertEqual(a, b, message, file: file, line: line)
    }
    private let zone = TimeZone(identifier: "Asia/Shanghai")!

    // MARK: Golden file

    private static let golden: [String: Any] = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/src/commonTest/resources/golden/ondevice-ai.json")
        let data = try! Data(contentsOf: url)
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }()

    private func vectors(_ section: String) -> [[String: Any]] { Self.golden[section] as! [[String: Any]] }

    private func str(_ v: Any?) -> String? { v is NSNull ? nil : v as? String }
    private func strings(_ v: Any?) -> [String] { v as? [String] ?? [] }
    private func i64(_ v: Any?) -> Int64 { (v as! NSNumber).int64Value }

    private func cues(_ o: Any?) -> HomeGreetingPrompt.SlotCues {
        let d = o as! [String: Any]
        func play(_ k: String) -> HomeGreetingPrompt.RecentPlay? {
            guard let p = d[k] as? [String: Any] else { return nil }
            return ai.recentPlay(artist: p["artist"] as! String, album: p["album"] as! String)
        }
        return ai.slotCues(morning: play("morning"), afternoon: play("afternoon"), evening: play("evening"))
    }

    private func greetings(_ o: Any?) -> DailyHomeGreetings? {
        guard let d = o as? [String: Any] else { return nil }
        return StoredHomeGreetings(day: i64(d["day"]), localeTag: d["localeTag"] as! String,
                                   profileId: d["profileId"] as! String, morning: str(d["morning"]),
                                   afternoon: str(d["afternoon"]), evening: str(d["evening"]),
                                   cuesFingerprint: d["cuesFingerprint"] as! String,
                                   refreshCount: (d["refreshCount"] as! NSNumber).intValue).kotlin
    }

    private func same(_ g: DailyHomeGreetings?, _ expected: Any?, _ msg: String) {
        guard let e = expected as? [String: Any] else { return XCTAssertNil(g, msg) }
        guard let g else { return XCTFail("nil: \(msg)") }
        XCTAssertEqual(StoredHomeGreetings(g), StoredHomeGreetings(day: i64(e["day"]), localeTag: e["localeTag"] as! String,
                                                                   profileId: e["profileId"] as! String,
                                                                   morning: str(e["morning"]), afternoon: str(e["afternoon"]),
                                                                   evening: str(e["evening"]),
                                                                   cuesFingerprint: e["cuesFingerprint"] as! String,
                                                                   refreshCount: (e["refreshCount"] as! NSNumber).intValue), msg)
    }

    private func slot(_ name: Any?) -> HomeGreetingSlot {
        switch name as! String {
        case "MORNING": .morning
        case "AFTERNOON": .afternoon
        default: .evening
        }
    }

    func testGoldenGreetingPromptsThroughTheSwiftFacade() {
        for v in vectors("greetingInstructions") {
            XCTAssertEqual(ai.greetingInstructions(hasAnyCue: v["hasAnyCue"] as! Bool, localeTag: v["localeTag"] as! String),
                           v["instructions"] as? String)
        }
        for v in vectors("greetingUserPrompt") {
            let c = cues(v["cues"])
            let day = Int32((v["dayOfWeek"] as! NSNumber).intValue), month = Int32((v["month"] as! NSNumber).intValue)
            let lang = v["localeTag"] as! String, avoid = strings(v["avoidRecent"])
            XCTAssertEqual(ai.greetingUserPrompt(cues: c, dayOfWeek: day, month: month, localeTag: lang, avoidRecent: avoid),
                           v["prompt"] as? String)
            XCTAssertEqual(ai.greetingPlainPrompt(cues: c, dayOfWeek: day, month: month, localeTag: lang, avoidRecent: avoid),
                           v["plainPrompt"] as? String)
            XCTAssertEqual(c.fingerprint(), v["fingerprint"] as? String)
        }
    }

    func testGoldenGreetingValidationThroughTheSwiftFacade() {
        for v in vectors("greetingValidate") {
            same(ai.validateGreetings(morning: str(v["morning"]), afternoon: str(v["afternoon"]), evening: str(v["evening"]),
                                      day: i64(v["day"]), localeTag: v["localeTag"] as! String,
                                      profileId: v["profileId"] as! String, cues: cues(v["cues"]),
                                      avoidRecent: strings(v["avoidRecent"])), v["result"], "\(v)")
        }
        for v in vectors("greetingParse") {
            same(ai.parseGreetings(answer: str(v["answer"]), day: i64(v["day"]), localeTag: v["localeTag"] as! String,
                                   profileId: v["profileId"] as! String, cues: cues(v["cues"]),
                                   avoidRecent: strings(v["avoidRecent"])), v["result"], "\(v)")
        }
    }

    func testGoldenScheduleThroughTheSwiftFacade() {
        for v in vectors("slotAtHour") {
            let h = Int32((v["hour"] as! NSNumber).intValue)
            XCTAssertEqual(ai.slotAtHour(hour: h), slot(v["slot"]))
            XCTAssertEqual(ai.isNoonHour(hour: h), v["isNoon"] as! Bool)
        }
        for v in vectors("dayOf") {
            XCTAssertEqual(ai.dayOf(epochMs: i64(v["epochMs"]), utcOffsetMs: i64(v["utcOffsetMs"])), i64(v["day"]))
        }
        for v in vectors("cachedText") {
            XCTAssertEqual(ai.cachedText(cached: greetings(v["cached"]), slot: slot(v["slot"]), today: i64(v["today"]),
                                         lang: v["lang"] as! String, isNoon: v["isNoon"] as! Bool), str(v["text"]), "\(v)")
        }
        for v in vectors("needsGeneration") {
            XCTAssertEqual(ai.needsGeneration(cached: greetings(v["cached"]), today: i64(v["today"]), lang: v["lang"] as! String,
                                              currentSlot: slot(v["currentSlot"]), lastAttempt: str(v["lastAttempt"])),
                           v["needs"] as! Bool, "\(v)")
        }
        for v in vectors("shouldGenerate") {
            XCTAssertEqual(ai.shouldGenerate(cached: greetings(v["cached"]), today: i64(v["today"]), lang: v["lang"] as! String,
                                             currentSlot: slot(v["currentSlot"]),
                                             cuesFingerprint: v["cuesFingerprint"] as! String,
                                             lastAttempt: str(v["lastAttempt"])), v["generate"] as! Bool, "\(v)")
        }
        for v in vectors("merged") {
            same(ai.merged(existing: greetings(v["existing"]), fresh: greetings(v["fresh"])!, currentSlot: slot(v["currentSlot"]),
                           cuesFingerprint: v["cuesFingerprint"] as! String), v["result"], "\(v)")
        }
        for v in vectors("history") {
            XCTAssertEqual(ai.recentHistory(raw: v["raw"] as! String, today: i64(v["today"])), strings(v["recent"]))
            XCTAssertEqual(ai.recordHistory(raw: v["raw"] as! String, today: i64(v["today"]), phrases: strings(v["phrases"])),
                           str(v["recorded"]))
        }
        for v in vectors("pickCues") {
            let recent = (v["recent"] as! [[String: Any]]).map {
                ai.cuePlay(artist: $0["artist"] as! String, album: $0["album"] as! String, playedAt: i64($0["playedAt"]))
            }
            let rediscover = (v["rediscover"] as! [[String: Any]]).map {
                ai.recentPlay(artist: $0["artist"] as! String, album: $0["album"] as! String)
            }
            let albums = (v["albums"] as! [[String: Any]]).map {
                ai.cueAlbum(artist: $0["artist"] as! String, album: $0["album"] as! String, lastPlayedAt: i64($0["lastPlayedAt"]))
            }
            let got = ai.pickCues(recent: recent, rediscover: rediscover, albums: albums, now: i64(v["now"]))
            XCTAssertEqual(got.fingerprint(), v["fingerprint"] as? String, "\(v)")
        }
    }

    func testGoldenFollowUpsThroughTheSwiftFacade() {
        for v in vectors("followUpPrepare") {
            let entries = (v["entries"] as! [[String: Any]]).map {
                ai.aboutEntry(kind: $0["kind"] as! String, entryKey: $0["entryKey"] as! String,
                              titleDisplay: $0["titleDisplay"] as! String, artistDisplay: $0["artistDisplay"] as! String,
                              titleText: str($0["titleText"]), promptText: str($0["promptText"]),
                              answerText: $0["answerText"] as! String)
            }
            let lang = v["localeTag"] as! String
            XCTAssertEqual(ai.followUpInstructions(localeTag: lang), v["instructions"] as? String)
            let p = ai.prepareFollowUps(entries: entries, asked: strings(v["asked"]), localeTag: lang)
            guard let e = v["prepared"] as? [String: Any] else { XCTAssertNil(p); continue }
            guard let p else { XCTFail("\(v)"); continue }
            XCTAssertEqual(p.context, e["context"] as? String)
            XCTAssertEqual(p.fingerprint, e["fingerprint"] as? String)
            XCTAssertEqual(p.asked, strings(e["asked"]))
            XCTAssertEqual(ai.followUpUserPrompt(prepared: p), e["userPrompt"] as? String)
            XCTAssertEqual(ai.followUpPlainPrompt(prepared: p), e["plainPrompt"] as? String)
        }
        for v in vectors("followUpParse") {
            XCTAssertEqual(ai.parseFollowUps(answer: str(v["answer"]), asked: strings(v["asked"]), localeTag: v["localeTag"] as! String),
                           strings(v["questions"]), "\(v)")
        }
        for v in vectors("followUpValidate") {
            XCTAssertEqual(ai.validateFollowUps(questions: strings(v["candidates"]), asked: strings(v["asked"]),
                                                localeTag: v["localeTag"] as! String), strings(v["questions"]))
        }
        for v in vectors("followUpMerge") {
            XCTAssertEqual(ai.mergeFollowUps(suggested: strings(v["suggested"]), fallback: strings(v["fallback"]),
                                             asked: strings(v["asked"])), strings(v["merged"]))
        }
    }

    /// iOS's About rows map onto the shared entry field for field (kind, key, headings, answer).
    func testAboutRowsMapOntoTheSharedEntry() {
        let row = aboutRow(kind: SongAboutStore.Kind.ask, key: "who played bass?", answer: "Colin.", prompt: "Who played bass?",
                           title: "Bass")
        let e = AboutFollowUpGenerator.entries([row]).first!
        XCTAssertEqual(e.kind, "ask")
        XCTAssertEqual(e.entryKey, "who played bass?")
        XCTAssertEqual(e.promptText, "Who played bass?")
        XCTAssertEqual(e.titleText, "Bass")
        XCTAssertEqual(e.answerText, "Colin.")
        XCTAssertEqual(SongAboutStore.Kind.canonical, AboutFollowUpEntry.companion.KIND_CANONICAL)
        XCTAssertEqual(SongAboutStore.Canon.review, AboutFollowUpEntry.companion.CANON_REVIEW)
    }

    // MARK: Greeting store

    private func defaults() -> UserDefaults {
        let name = "OnDeviceAITests.\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        return d
    }

    /// 2026-11-27 (a Friday) at [hour]:[minute] in Shanghai.
    private func date(_ hour: Int, _ minute: Int = 0, dayOffset: Int = 0) -> Date {
        var c = DateComponents()
        c.year = 2026; c.month = 11; c.day = 27 + dayOffset; c.hour = hour; c.minute = minute
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        return cal.date(from: c)!
    }

    private final class Clock { var now: Date; init(_ d: Date) { now = d } }

    private func store(_ clock: Clock, language: String = "en", defaults d: UserDefaults? = nil) -> HomeGreetingStore {
        let d = d ?? defaults()
        return HomeGreetingStore(defaults: d, now: { clock.now }, timeZone: { [zone] in zone }, language: { language })
    }

    private var radioheadCues: HomeGreetingPrompt.SlotCues {
        ai.slotCues(morning: ai.recentPlay(artist: "Radiohead", album: "In Rainbows"),
                    afternoon: ai.recentPlay(artist: "Utada Hikaru", album: "BAD Mode"),
                    evening: ai.recentPlay(artist: "Bill Evans", album: "Waltz for Debby"))
    }

    private let goodSlots = OnDeviceGreetingSlots(morning: "Still spinning In Rainbows", afternoon: "Utada all week",
                                                  evening: "Been a while since Bill Evans")

    func testGreetingIsGeneratedOnceADayAndShownPerSlot() async {
        let clock = Clock(date(9))
        let s = store(clock)
        let model = FakeOnDeviceTextModel()
        model.greetingReply = .value(goodSlots)
        XCTAssertNil(s.text(at: clock.now, profileId: "p1"), "nothing yet: Home keeps its static greeting")
        XCTAssertTrue(s.needsGeneration(profileId: "p1"))

        let outcome = await s.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model)
        XCTAssertEqual(outcome, .generated)
        XCTAssertEqual(model.greetingCalls.count, 1)
        let call = model.greetingCalls[0]
        XCTAssertEqual(call.instructions, ai.greetingInstructions(hasAnyCue: true, localeTag: "en"))
        XCTAssertEqual(call.prompt, ai.greetingUserPrompt(cues: radioheadCues, dayOfWeek: 5, month: 11, localeTag: "en",
                                                          avoidRecent: []))
        XCTAssertTrue(call.prompt.hasPrefix("November · Friday"))
        XCTAssertEqual(model.lastTemperature ?? 0, 0.55, accuracy: 1e-6)
        XCTAssertEqual(model.lastMaxTokens, 128)

        XCTAssertEqual(s.text(at: date(9), profileId: "p1"), "Still spinning In Rainbows")
        XCTAssertEqual(s.text(at: date(15), profileId: "p1"), "Utada all week")
        XCTAssertEqual(s.text(at: date(21), profileId: "p1"), "Been a while since Bill Evans")
        XCTAssertEqual(s.text(at: date(2), profileId: "p1"), "Been a while since Bill Evans")
        XCTAssertNil(s.text(at: date(9), profileId: "p2"), "another account's greetings are its own")
        XCTAssertNil(s.text(at: date(9, dayOffset: 1), profileId: "p1"), "tomorrow: static until regenerated")

        // Same day, same cues: no second call.
        check(await s.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model), .alreadyReady)
        XCTAssertEqual(model.greetingCalls.count, 1)

        // Read back from disk by a fresh store.
        let d = defaults()
        let a = store(clock, defaults: d)
        _ = await a.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model)
        XCTAssertEqual(store(clock, defaults: d).text(at: date(9), profileId: "p1"), "Still spinning In Rainbows")
    }

    func testNoonDropsAnAfternoonLineThatSaysAfternoon() async {
        let clock = Clock(date(8))
        let s = store(clock, language: "zh-Hans")
        let model = FakeOnDeviceTextModel()
        model.greetingReply = .value(OnDeviceGreetingSlots(morning: "早上先听《BAD Mode》", afternoon: "下午茶配一张爵士",
                                                           evening: "好久没放 Bill Evans 了"))
        check(await s.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model), .generated)
        XCTAssertNil(s.text(at: date(12), profileId: "p1"), "noon: 中午好 (static) rather than a 下午 line")
        XCTAssertEqual(s.text(at: date(14), profileId: "p1"), "下午茶配一张爵士")
        // The language changed: the Chinese day doesn't show in English.
        XCTAssertNil(store(clock, language: "en").text(at: date(14), profileId: "p1"))
    }

    func testGreetingFallbacksStaySilent() async {
        let clock = Clock(date(9))
        let s = store(clock)
        let model = FakeOnDeviceTextModel()

        model.greetingReply = .busy
        check(await s.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model), .busy)
        model.greetingReply = .failed
        check(await s.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model), .failed)
        XCTAssertEqual(model.greetingCalls.count, 2, "busy and failed record no attempt: the next visit asks again")
        XCTAssertNil(s.text(at: clock.now, profileId: "p1"))

        // An answer that doesn't survive the checks (emoji, too long): unusable, and not asked again today.
        model.greetingReply = .value(OnDeviceGreetingSlots(morning: "🎧🎧🎧", afternoon: String(repeating: "long ", count: 20),
                                                           evening: "ok"))
        check(await s.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model), .unusable)
        XCTAssertNil(s.text(at: clock.now, profileId: "p1"))
        check(await s.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model), .alreadyReady)
        XCTAssertEqual(model.greetingCalls.count, 3)
    }

    func testUndecodableGuidedAnswerFallsBackToThePlainPrompt() async {
        let clock = Clock(date(9))
        let s = store(clock)
        let model = FakeOnDeviceTextModel()
        model.greetingReply = .unsupported
        model.textReply = .text("```json\n{\"morning\":\"Friday at last\",\"afternoon\":\"Almost the weekend\",\"evening\":\"Friday night, turn up\"}\n```")
        check(await s.generateIfNeeded(profileId: "p1", cues: ai.slotCues(morning: nil, afternoon: nil, evening: nil),
                                                model: model), .generated)
        XCTAssertEqual(model.textCalls, [ai.greetingPlainPrompt(cues: ai.slotCues(morning: nil, afternoon: nil, evening: nil),
                                                                 dayOfWeek: 5, month: 11, localeTag: "en", avoidRecent: [])])
        XCTAssertEqual(s.text(at: date(20), profileId: "p1"), "Friday night, turn up")

        let other = store(Clock(date(9)))
        model.textReply = .busy
        check(await other.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model), .busy)
    }

    func testNewCuesRefreshOnlyTheUpcomingSlotsTwiceADay() async {
        let clock = Clock(date(9))
        let s = store(clock)
        let model = FakeOnDeviceTextModel()
        model.greetingReply = .value(goodSlots)
        _ = await s.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model)

        // Something new played this morning: the afternoon and evening lines may change, the morning one stays.
        let newCues = ai.slotCues(morning: ai.recentPlay(artist: "Mitski", album: "Be the Cowboy"),
                                  afternoon: ai.recentPlay(artist: "Big Thief", album: "Dragon"), evening: nil)
        model.greetingReply = .value(OnDeviceGreetingSlots(morning: "Mitski first thing", afternoon: "Big Thief all week",
                                                           evening: "One last record tonight"))
        XCTAssertTrue(s.needsGeneration(profileId: "p1"))
        check(await s.generateIfNeeded(profileId: "p1", cues: newCues, model: model), .generated)
        XCTAssertEqual(s.text(at: date(9), profileId: "p1"), "Still spinning In Rainbows")
        XCTAssertEqual(s.text(at: date(15), profileId: "p1"), "Big Thief all week")
        XCTAssertEqual(s.text(at: date(20), profileId: "p1"), "One last record tonight")
        XCTAssertEqual(s.greetings["p1"]?.refreshCount, 1)
        // The phrases shown go into the 7-day history the next prompt is told to avoid… from tomorrow.
        XCTAssertFalse(model.greetingCalls[1].prompt.contains("Used recently"))

        // Afternoon: one more refresh touches only the evening; then the cap (2) holds.
        clock.now = date(14)
        model.greetingReply = .value(OnDeviceGreetingSlots(morning: "x y z", afternoon: "Changed afternoon line",
                                                           evening: "Evening refreshed again"))
        let third = ai.slotCues(morning: ai.recentPlay(artist: "Joni Mitchell", album: "Blue"), afternoon: nil, evening: nil)
        check(await s.generateIfNeeded(profileId: "p1", cues: third, model: model), .generated)
        XCTAssertEqual(s.text(at: date(15), profileId: "p1"), "Big Thief all week")
        XCTAssertEqual(s.text(at: date(20), profileId: "p1"), "Evening refreshed again")
        XCTAssertFalse(s.needsGeneration(profileId: "p1"), "two refreshes a day at most")
        let fourth = ai.slotCues(morning: ai.recentPlay(artist: "Sufjan Stevens", album: "Carrie & Lowell"),
                                 afternoon: nil, evening: nil)
        check(await s.generateIfNeeded(profileId: "p1", cues: fourth, model: model), .alreadyReady)

        // The next day's prompt avoids what was shown.
        clock.now = date(9, dayOffset: 1)
        model.greetingReply = .value(goodSlots)
        _ = await s.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model)
        XCTAssertTrue(model.greetingCalls.last!.prompt.contains("Used recently (vary phrasing): Still spinning In Rainbows"))
    }

    func testEveningNeverRefreshes() async {
        let clock = Clock(date(19))
        let s = store(clock)
        let model = FakeOnDeviceTextModel()
        model.greetingReply = .value(goodSlots)
        _ = await s.generateIfNeeded(profileId: "p1", cues: radioheadCues, model: model)
        XCTAssertFalse(s.needsGeneration(profileId: "p1"))
        let other = ai.slotCues(morning: ai.recentPlay(artist: "Mitski", album: ""), afternoon: nil, evening: nil)
        check(await s.generateIfNeeded(profileId: "p1", cues: other, model: model), .alreadyReady)
        XCTAssertEqual(model.greetingCalls.count, 1)
    }

    // MARK: Cues from history

    private func play(_ song: String, _ artist: String, _ album: String, albumId: String?, at: Int64) -> PlayEvent {
        PlayEvent(id: "\(song)-\(at)", scope: "s", provider: "subsonic", songId: song, albumId: albumId, artistId: nil,
                  title: song, album: album, artist: artist, coverKey: nil, startedAt: at, durationSec: 200)
    }

    func testCuesReadHistoryAsAndroidsQueriesDo() {
        let day: Int64 = 86_400_000
        let now: Int64 = 1_764_244_800_000
        let plays = [
            play("s1", "Radiohead", "In Rainbows", albumId: "a1", at: now - 3_600_000),
            play("s2", "Utada Hikaru", "BAD Mode", albumId: "a2", at: now - 2 * 3_600_000),
            play("s3", "Utada Hikaru", "BAD Mode", albumId: "a2", at: now - day),
            play("s4", "Joni Mitchell", "Blue", albumId: "a3", at: now - 20 * day),
            play("s5", "Bill Evans", "Waltz for Debby", albumId: "a4", at: now - 30 * day),
            play("s6", "Mitski", "Puberty 2", albumId: "", at: now - 40 * day),
        ]
        let rating: (String) -> Double? = { ["s4": 4, "s5": 5][$0] }
        // Rediscover: rated or noted, unplayed 14+ days; best rated first, then the longest unplayed.
        let r = HomeGreetingCues.rediscover(plays: plays, rating: rating, noteCount: ["s6": 1, "s1": 2], now: now)
        XCTAssertEqual(r.map(\.artist), ["Bill Evans", "Joni Mitchell", "Mitski"])
        // Albums by their newest play (albumless plays left out).
        XCTAssertEqual(HomeGreetingCues.albums(plays: plays).map(\.album), ["In Rainbows", "BAD Mode", "Blue", "Waltz for Debby"])

        let recent = plays.sorted { $0.startedAt > $1.startedAt }
        let c = HomeGreetingCues.cues(recent: recent, plays: plays, rating: rating, noteCount: [:], now: now)
        XCTAssertEqual(c.morning?.artist, "Radiohead")
        XCTAssertEqual(c.afternoon?.artist, "Utada Hikaru")
        XCTAssertEqual(c.evening?.artist, "Bill Evans")
        let none = HomeGreetingCues.cues(recent: [], plays: [], rating: { _ in nil }, noteCount: [:], now: now)
        XCTAssertFalse(none.hasAny(), "no history: the everyday-moment prompt")
    }

    // MARK: About follow-ups

    private func aboutRow(kind: String, key: String, answer: String, prompt: String? = nil, title: String? = nil) -> SongAboutStore.Entry {
        SongAboutStore.Entry(titleKey: "nude", artistKey: "radiohead", albumKey: "in rainbows", titleDisplay: "Nude",
                             artistDisplay: "Radiohead", albumDisplay: "In Rainbows", kind: kind, entryKey: key,
                             promptText: prompt, titleText: title, answerText: answer, createdAt: 0, updatedAt: 0)
    }

    private var aboutRows: [SongAboutStore.Entry] {
        [aboutRow(kind: SongAboutStore.Kind.canonical, key: SongAboutStore.Canon.review,
                  answer: "Nude spent over a decade as a live staple before Radiohead finally recorded it for In Rainbows.")]
    }

    private final class Flags { var enabled = true; var foreground = true }

    private func generator(_ model: FakeOnDeviceTextModel, _ flags: Flags, language: String = "en") -> AboutFollowUpGenerator {
        AboutFollowUpGenerator(model: model, enabled: { flags.enabled }, isForeground: { flags.foreground },
                               language: { language })
    }

    func testFollowUpsAreMadeOnceAndCached() async {
        let model = FakeOnDeviceTextModel()
        model.followUpReply = .value(["Why did the band wait a decade to record it", "Who?", "How does the live version differ?"])
        let g = generator(model, Flags())
        XCTAssertNil(g.peekCached(aboutRows, asked: []))
        let out = await g.suggest(aboutRows, asked: [])
        XCTAssertEqual(out, ["Why did the band wait a decade to record it?", "How does the live version differ?"])
        let prepared = ai.prepareFollowUps(entries: AboutFollowUpGenerator.entries(aboutRows), asked: [], localeTag: "en")!
        XCTAssertEqual(model.followUpCalls.first?.instructions, ai.followUpInstructions(localeTag: "en"))
        XCTAssertEqual(model.followUpCalls.first?.prompt, ai.followUpUserPrompt(prepared: prepared))
        XCTAssertEqual(model.lastTemperature ?? 0, 0.45, accuracy: 1e-6)
        XCTAssertEqual(g.peekCached(aboutRows, asked: []), out)
        _ = await g.suggest(aboutRows, asked: [])
        XCTAssertEqual(model.followUpCalls.count, 1, "the same About snapshot is answered from the cache")
        // Asking one changes the snapshot (and the prompt says what was asked).
        _ = await g.suggest(aboutRows, asked: ["How does the live version differ?"])
        XCTAssertEqual(model.followUpCalls.count, 2)
        XCTAssertTrue(model.followUpCalls[1].prompt.contains("Already asked (skip): How does the live version differ?"))
    }

    func testFollowUpsFallBackSilently() async {
        let flags = Flags()
        let model = FakeOnDeviceTextModel()
        model.followUpReply = .value(["Why did the band wait a decade to record it?"])
        let g = generator(model, flags)

        flags.enabled = false
        check(await g.suggest(aboutRows, asked: []), [], "Labs switch off")
        flags.enabled = true
        flags.foreground = false
        check(await g.suggest(aboutRows, asked: []), [], "Yoin in the background")
        flags.foreground = true
        for status in [OnDeviceModelStatus.notEnabled, .notReady, .unsupported] {
            model.modelStatus = status
            check(await g.suggest(aboutRows, asked: []), [], "\(status)")
        }
        model.modelStatus = .available
        check(await generator(model, flags, language: "ja").suggest(aboutRows, asked: []), [], "language not written")
        check(await g.suggest([aboutRow(kind: SongAboutStore.Kind.canonical, key: SongAboutStore.Canon.lyricist,
                                                 answer: "Thom Yorke")], asked: []), [], "too little About to ground a question")
        XCTAssertTrue(model.followUpCalls.isEmpty)

        model.followUpReply = .busy
        check(await g.suggest(aboutRows, asked: []), [])
        model.followUpReply = .failed
        check(await g.suggest(aboutRows, asked: []), [])
        XCTAssertNil(g.peekCached(aboutRows, asked: []), "nothing cached from a refusal: asked again next time")

        // The guided answer didn't decode: the plain prompt and its parser.
        model.followUpReply = .unsupported
        model.textReply = .text("[\"乐队为什么等了十年才录这首歌？\",\"现场版和录音室版有什么不同\"]")
        let zh = generator(model, flags, language: "zh-Hans")
        check(await zh.suggest(aboutRows, asked: []), ["乐队为什么等了十年才录这首歌？", "现场版和录音室版有什么不同？"])
        XCTAssertTrue(model.textCalls.last!.hasPrefix("你是一个音乐应用里的追问建议助手"))
    }

    /// The Ask field's quick questions: the model's first, then the static ones less what was asked.
    func testAskPromptsPutTheModelsQuestionsFirst() {
        let asked = ["Who sampled this song?"]
        let merged = ai.mergeFollowUps(suggested: ["Why the decade-long wait?"],
                                       fallback: QuickPrompts.remaining(asked: asked), asked: asked)
        XCTAssertEqual(merged.first, "Why the decade-long wait?")
        XCTAssertFalse(merged.contains("Who sampled this song?"))
        XCTAssertEqual(Array(merged.dropFirst()), QuickPrompts.remaining(asked: asked))
        XCTAssertEqual(ai.mergeFollowUps(suggested: [], fallback: QuickPrompts.all, asked: []), QuickPrompts.all,
                       "no model: exactly today's static questions")
    }

    // MARK: Labs switches and the visit

    func testLabsSwitchesStartAsTheOptInAndTheIntroTurnsThemOn() {
        let d = defaults()
        XCTAssertFalse(LyricHighlightStore(fileURL: nil, defaults: d).greetingsEnabled)
        d.set(true, forKey: "lyricHighlightOptedIn")
        let opted = LyricHighlightStore(fileURL: nil, defaults: d)
        XCTAssertTrue(opted.greetingsEnabled, "never set: follows the Lyric Cards opt-in (Android)")
        XCTAssertTrue(opted.aboutFollowUpsEnabled)

        let fresh = defaults()
        let store = LyricHighlightStore(fileURL: nil, defaults: fresh)
        store.aboutFollowUpsEnabled = false
        let processor = LyricHighlightProcessor(model: AppleOnDeviceLyricModel(), textModel: FakeOnDeviceTextModel(),
                                                store: store, greetings: HomeGreetingStore(defaults: fresh))
        processor.optIn()
        processor.appResignedActive()
        XCTAssertTrue(store.optedIn)
        XCTAssertTrue(store.greetingsEnabled, "the intro turns the greeting on")
        XCTAssertFalse(store.aboutFollowUpsEnabled, "…but keeps a switch the listener set")
        XCTAssertFalse(LyricHighlightStore(fileURL: nil, defaults: fresh).aboutFollowUpsEnabled, "persisted")
    }

    func testVisitMakesTheGreetingsWithinTheBudget() async {
        let d = defaults()
        let store = LyricHighlightStore(fileURL: nil, defaults: d)
        let greetings = HomeGreetingStore(defaults: d, now: { Date() }, timeZone: { .current }, language: { "en" })
        let model = FakeOnDeviceTextModel()
        model.greetingReply = .value(goodSlots)
        let processor = LyricHighlightProcessor(model: AppleOnDeviceLyricModel(), textModel: model, store: store,
                                                greetings: greetings)
        let account = LedgerAccount(provider: "subsonic", scope: "ondevice-ai-test-\(UUID().uuidString)")

        // A language the model doesn't write: nothing asked, the visit carries on.
        model.languages = []
        let unsupported = await processor.runDailyGreetings(profileId: "p1", account: account)
        XCTAssertTrue(unsupported)
        XCTAssertTrue(model.greetingCalls.isEmpty)

        model.languages = [OnDeviceLanguage.app]
        let before = store.budget.rolled(LyricHighlightProcessor.today(LyricHighlightProcessor.nowMs)).usedToday
        let ran = await processor.runDailyGreetings(profileId: "p1", account: account)
        XCTAssertTrue(ran)
        XCTAssertEqual(model.greetingCalls.count, 1)
        XCTAssertEqual(store.budget.usedToday, before + 1, "a greeting costs one of the day's 40")
        XCTAssertNotNil(greetings.greetings["p1"])
        let again = await processor.runDailyGreetings(profileId: "p1", account: account)
        XCTAssertTrue(again)
        XCTAssertEqual(model.greetingCalls.count, 1, "already made today")

        // Busy backs the budget off and stops the visit.
        let other = HomeGreetingStore(defaults: defaults(), now: { Date() }, timeZone: { .current }, language: { "en" })
        model.greetingReply = .busy
        let busyProcessor = LyricHighlightProcessor(model: AppleOnDeviceLyricModel(), textModel: model, store: store,
                                                    greetings: other)
        let busy = await busyProcessor.runDailyGreetings(profileId: "p1", account: account)
        XCTAssertFalse(busy)
        XCTAssertGreaterThan(store.budget.pausedUntil, LyricHighlightProcessor.nowMs)
    }

    /// The timeout wins over a model that never answers, and the work is cancelled.
    func testTimeoutReturnsTheFallback() async {
        let r = await OnDeviceTimeout.run(.milliseconds(50), fallback: "timed out") {
            try? await Task.sleep(for: .seconds(30))
            return "answered"
        }
        XCTAssertEqual(r, "timed out")
        let quick = await OnDeviceTimeout.run(.seconds(5), fallback: "timed out") { "answered" }
        XCTAssertEqual(quick, "answered")
    }

    /// Apple Intelligence itself, where this run has it (a Mac with Apple Intelligence on runs the simulator's
    /// model): one real greeting and one real follow-up call, logged. Skipped where the model isn't available;
    /// asserts only that a call comes back (the words vary).
    func testRealAppleIntelligenceGeneration() async throws {
        let model = AppleOnDeviceTextModel(timeout: .seconds(60))
        try XCTSkipUnless(model.status() == .available, "Apple Intelligence: \(model.status())")
        for language in ["en", "zh-Hans"] where model.supportsLanguage(language) {
            let lang = ai.normalizeLocale(languageTag: language)
            let reply = await model.greetings(
                instructions: ai.greetingInstructions(hasAnyCue: true, localeTag: lang),
                prompt: ai.greetingUserPrompt(cues: radioheadCues, dayOfWeek: 5, month: 11, localeTag: lang, avoidRecent: []),
                temperature: Double(ai.greetingTemperature), maxTokens: Int(ai.greetingMaxOutputTokens))
            if case .value(let slots) = reply {
                let checked = ai.validateGreetings(morning: slots.morning, afternoon: slots.afternoon, evening: slots.evening,
                                                   day: 1, localeTag: lang, profileId: "p", cues: radioheadCues, avoidRecent: [])
                print("[real-ai] greetings \(language): \(slots) -> kept: \(checked.map { "\(StoredHomeGreetings($0))" } ?? "none")")
            } else {
                print("[real-ai] greetings \(language): \(reply)")
            }
            let rows = [aboutRow(kind: SongAboutStore.Kind.canonical, key: SongAboutStore.Canon.review,
                                 answer: lang == "zh"
                                     ? "《Nude》在现场演了十多年才在 2007 年的《In Rainbows》里正式录音，假声旋律缓缓浮在低音线上。"
                                     : "Nude spent over a decade as a live staple before Radiohead finally recorded it for In Rainbows in 2007.")]
            let prepared = ai.prepareFollowUps(entries: AboutFollowUpGenerator.entries(rows), asked: [], localeTag: language)!
            let q = await model.followUps(instructions: ai.followUpInstructions(localeTag: lang),
                                          prompt: ai.followUpUserPrompt(prepared: prepared),
                                          temperature: Double(ai.followUpTemperature),
                                          maxTokens: Int(ai.followUpMaxOutputTokens))
            if case .value(let raw) = q {
                print("[real-ai] follow-ups \(language): \(raw) -> kept: \(ai.validateFollowUps(questions: raw, asked: [], localeTag: lang))")
            } else {
                print("[real-ai] follow-ups \(language): \(q)")
            }
        }
    }
}
