package com.gpo.yoin.shared.ondevice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The golden vectors of the on-device AI words and rules (shared/src/commonTest/resources/golden/ondevice-ai.json):
 * Home's greeting prompt, validator and schedule, and About's follow-up prompt and validator. Each vector is its
 * inputs followed by what [YoinOnDeviceAi] answers ([outputs]); the file is written by `OnDeviceAiGoldenTest`
 * (androidHostTest) from [inputs], [replay] answers every vector again on the JVM and the iOS simulator (through
 * [ONDEVICE_AI_GOLDEN], the file as a Kotlin string), Android's app answers it through its own paths and the
 * pre-move code (app `OnDeviceAiPinTest`), and iOS through the Swift facade (`OnDeviceAITests`).
 */
internal object OnDeviceAiGolden {

    const val FILE = "shared/src/commonTest/resources/golden/ondevice-ai.json"

    val sections: List<String> = listOf(
        "greetingInstructions", "greetingUserPrompt", "greetingSanitize", "greetingValidate", "greetingParse",
        "visualWidth", "conflictsWithNoon", "slotAtHour", "dayOf", "cachedText", "needsGeneration",
        "shouldGenerate", "merged", "history", "pickCues", "followUpPrepare", "followUpParse", "followUpValidate",
        "followUpMerge",
    )

    const val ABOUT = "Golden vectors for the on-device AI (com.gpo.yoin.shared.ondevice, parity p5 2026-11-27): " +
        "Home's three daily greetings (the prompt Gemini Nano / Apple Intelligence gets, the validator of what it " +
        "answers, the slots, when to generate, the upcoming-slot merge, the 7-day phrase history and the listening " +
        "cues) and Now Playing About's suggested follow-up questions (the About text read, the prompt, the parser " +
        "and validator, the merge ahead of the static quick prompts). Generated from :shared Kotlin through " +
        "YoinOnDeviceAi by OnDeviceAiGoldenTest (androidHostTest); do not edit by hand. Every vector is its inputs " +
        "followed by the answer. dayOfWeek is ISO (Monday = 1), month 1-12 (0 = none), times epoch ms, slots " +
        "MORNING / AFTERNOON / EVENING; greetings objects are DailyHomeGreetings (null = none survived)."

    // ── inputs ──────────────────────────────────────────────────────────────────────────────────────────

    private fun play(artist: String, album: String) = obj("artist" to JsonPrimitive(artist), "album" to JsonPrimitive(album))

    private fun cues(m: JsonElement = JsonNull, a: JsonElement = JsonNull, e: JsonElement = JsonNull) =
        obj("morning" to m, "afternoon" to a, "evening" to e)

    private val fullCues = cues(
        play("Radiohead", "In Rainbows"),
        play("宇多田ヒカル", "BADモード"),
        play("Bill Evans", "Waltz for Debby"),
    )
    private val zhCues = cues(play("陈绮贞", "华丽的冒险"), play("草东没有派对", ""), JsonNull)
    private val oneCue = cues(play("Phoebe Bridgers", "Punisher"))
    private val blankCue = cues(play("  ", "Ghost"), play(" Mitski ", " Be the Cowboy "))

    private fun greetings(
        day: Long,
        lang: String,
        m: String?,
        a: String?,
        e: String?,
        fp: String = "",
        refresh: Int = 0,
        profile: String = "p1",
    ) = obj(
        "day" to JsonPrimitive(day), "localeTag" to JsonPrimitive(lang), "profileId" to JsonPrimitive(profile),
        "morning" to str(m), "afternoon" to str(a), "evening" to str(e),
        "cuesFingerprint" to JsonPrimitive(fp), "refreshCount" to JsonPrimitive(refresh),
    )

    private const val TODAY = 20_419L

    private fun aboutEntry(
        kind: String,
        key: String,
        answer: String,
        title: String = "Nude",
        artist: String = "Radiohead",
        titleText: String? = null,
        prompt: String? = null,
    ) = obj(
        "kind" to JsonPrimitive(kind), "entryKey" to JsonPrimitive(key), "titleDisplay" to JsonPrimitive(title),
        "artistDisplay" to JsonPrimitive(artist), "titleText" to str(titleText), "promptText" to str(prompt),
        "answerText" to JsonPrimitive(answer),
    )

    private val review = "Nude spent over a decade as a live staple before Radiohead finally recorded it for In " +
        "Rainbows in 2007, with a slow falsetto melody over a loping bass line."
    private val zhReview = "《Nude》在现场演了十多年才在 2007 年的《In Rainbows》里正式录音，假声旋律缓缓浮在低音线上，" +
        "乐队几次推翻编曲才定下这个版本。"

    val inputs: Map<String, List<JsonObject>> = mapOf(
        "greetingInstructions" to listOf(true, false).flatMap { cue ->
            listOf("en", "en-US", "zh", "zh-Hans-CN", "zh-TW", "ja-JP").map { tag ->
                obj("hasAnyCue" to JsonPrimitive(cue), "localeTag" to JsonPrimitive(tag))
            }
        },
        "greetingUserPrompt" to buildList {
            for (lang in listOf("en", "zh-Hans")) {
                for (day in 1..7) {
                    add(userPromptInput(cues(), day, day, lang, emptyList()))
                }
                for (month in 8..12) add(userPromptInput(oneCue, 5, month, lang, emptyList()))
                add(userPromptInput(fullCues, 5, 11, lang, listOf("Still spinning In Rainbows")))
                add(userPromptInput(zhCues, 7, 10, lang, listOf("  ", "周日早，继续听", "周日早，继续听", "a", "b", "c")))
                add(userPromptInput(blankCue, 2, 0, lang, emptyList()))
                add(userPromptInput(cues(JsonNull, JsonNull, play("Bill Evans", "")), 3, 4, lang, emptyList()))
            }
        },
        "greetingSanitize" to listOf(
            "en" to "Still spinning In Rainbows", "en" to "\"Sunday starts with Abbey Road.\"",
            "en" to "Friday night, turn up!", "en" to "Hi", "en" to "Ryuichi Sakamoto all week long and then some more",
            "en" to "one two three four five six seven eight nine", "en" to "Morning: coffee and Bjork",
            "en" to "Here is your greeting", "en" to "Sure, a greeting", "en" to "Time to relax… maybe",
            "en" to "Wait for it...", "en" to "Coffee ☕ and records", "en" to "Night ★ music", "en" to "Late 🎧 set",
            "en" to "{morning}", "en" to "[1] Good", "en" to "Two\nlines", "en" to "坂本龙一 all week",
            "en" to "12345", "en" to "  Spaced    out   words  ", "en" to "The week top artist",
            "en" to "'Quiet Sunday'", "en" to "Pick an album now", "en" to "Rain ♪ on the window",
            "zh" to "这周常听 Radiohead 的《In Rainbows》", "zh" to "好久没放 Bill Evans 了。", "zh" to "早",
            "zh" to "周五夜，把音量开大！", "zh" to "「周日不着急，慢慢听」", "zh" to "听点舒缓的音乐吧",
            "zh" to "放松一下，听首歌", "zh" to "早上：先泡杯热的", "zh" to "好的，这是问候", "zh" to "以下是三句问候",
            "zh" to "Radiohead all day", "zh" to "这一周你一直都在反复地听宇多田光的那张非常好听的专辑",
            "zh" to "暂无听歌记录", "zh" to "午间挑张专辑听…", "zh" to "天黑了 🌙 放张唱片", "zh" to "周二早，  先泡杯热的",
            "zh" to "晚上好", "zh" to "Ｈｉ朋友", "zh" to "",
        ).map { (lang, raw) -> obj("raw" to JsonPrimitive(raw), "localeTag" to JsonPrimitive(lang)) },
        "greetingValidate" to listOf(
            validateInput("en", "Still spinning In Rainbows", "Utada all week", "Been a while since Bill Evans", fullCues),
            validateInput("en", "Still spinning LEVEL3", "Ryuichi Sakamoto all week", "Been a while since Bill Evans", fullCues),
            validateInput("en", "Still spinning In Rainbows", "🎧🎧", null, fullCues),
            validateInput("en", "Tuesday coffee, press play", "Pick an album now", "Dark out, music on", cues(),
                avoid = listOf("Pick an album now", "Dark out, music on")),
            validateInput("en", "Tuesday coffee, press play", "Pick an album now", "Dark out, music on", cues(),
                avoid = listOf("pick an album NOW!")),
            validateInput("en", "Kind of Blue again", "Norah Jones all week", "Abbey Road tonight", cues()),
            validateInput("en", "Kind of Blue again", "Pick an album now", "Abbey Road tonight",
                cues(play("Miles Davis", "Kind of Blue"))),
            validateInput("zh-Hans", "昨晚的《In Rainbows》还没听完", "这周一直在听宇多田ヒカル", "好久没放 Bill Evans 了", fullCues),
            validateInput("zh-Hans", "周日早，继续听《华丽的冒险》", "午间适合放一整张专辑", "周日晚，留一首歌收尾", zhCues),
            validateInput("zh-Hans", "周日早，继续听《华丽的冒险》", "午间适合放一整张专辑", "周日晚，留一首歌收尾", cues()),
            validateInput("zh-Hans", "Good morning", "午间适合放一整张专辑", null, cues()),
            validateInput("zh-Hans", null, null, null, cues()),
        ),
        "greetingParse" to listOf(
            parseInput("en", "{\"morning\":\"Still spinning In Rainbows\",\"afternoon\":\"Utada all week\",\"evening\":\"Been a while since Bill Evans\"}", fullCues),
            parseInput("en", "```json\n{\"morning\": \"Friday at last\", \"afternoon\": \"Almost the weekend\", \"evening\": \"Friday night, turn up\"}\n```", cues()),
            parseInput("en", "morning = \"Friday at last\"\nafternoon: \"Almost the weekend\"", cues()),
            parseInput("en", "{“morning”:“Friday at last”,“afternoon”:“Almost the weekend”,“evening”:“🎉”}", cues()),
            parseInput("en", "Sorry, I can't help with that.", cues()),
            parseInput("en", "", cues()),
            parseInput("zh-Hans", "{\"morning\":\"周五早上，快到周末了\",\"afternoon\":\"再过半天就周末了\",\"evening\":\"周五夜，把音量开大\"}", cues(),
                avoid = listOf("周五夜，把音量开大")),
            parseInput("zh-Hans", "{\"MORNING\":\"昨晚的《LEVEL3》还没听完\",\"Afternoon\":\"这周一直在听坂本龙一\",\"evening\":\"好久没放 Bill Evans 了\"}", fullCues),
        ),
        "visualWidth" to listOf("", "abc", "周五", "这周常听 Radiohead 的《In Rainbows》", "Ｈｉ", "ᄀ", "ჿ", "🎧")
            .map { obj("text" to JsonPrimitive(it)) },
        "conflictsWithNoon" to listOf("下午好", "Good Afternoon tunes", "中午好", "午间挑张专辑", "after noon")
            .map { obj("text" to JsonPrimitive(it)) },
        "slotAtHour" to (0..23).map { obj("hour" to JsonPrimitive(it)) },
        "dayOf" to listOf(
            0L to 0L, 1_764_201_600_000L to 28_800_000L, 1_764_201_600_000L to -18_000_000L,
            1_764_259_199_999L to 0L, -1L to 0L, 1_764_172_800_000L to 32_400_000L,
        ).map { (ms, off) -> obj("epochMs" to JsonPrimitive(ms), "utcOffsetMs" to JsonPrimitive(off)) },
        "cachedText" to buildList {
            val g = greetings(TODAY, "en", "Still spinning In Rainbows", "Afternoon with Utada", null)
            for (slot in HomeGreetingSlot.entries) {
                for (noon in listOf(false, true)) add(cachedInput(g, slot, TODAY, "en", noon))
            }
            add(cachedInput(g, HomeGreetingSlot.MORNING, TODAY + 1, "en", false))
            add(cachedInput(g, HomeGreetingSlot.MORNING, TODAY, "zh", false))
            add(cachedInput(JsonNull, HomeGreetingSlot.MORNING, TODAY, "en", false))
            add(cachedInput(greetings(TODAY, "zh", "早上先听《BADモード》", "午间挑张专辑听", "下午茶配爵士"),
                HomeGreetingSlot.AFTERNOON, TODAY, "zh", true))
        },
        "needsGeneration" to buildList {
            val g = greetings(TODAY, "en", "A b c", "D e f", "G h i", fp = "x", refresh = 0)
            for (slot in HomeGreetingSlot.entries) add(needsInput(g, TODAY, "en", slot, null))
            add(needsInput(greetings(TODAY, "en", "A b c", "D e f", null, refresh = 2), TODAY, "en", HomeGreetingSlot.MORNING, null))
            add(needsInput(greetings(TODAY, "en", null, null, null), TODAY, "en", HomeGreetingSlot.MORNING, null))
            add(needsInput(JsonNull, TODAY, "en", HomeGreetingSlot.MORNING, null))
            add(needsInput(JsonNull, TODAY, "en", HomeGreetingSlot.MORNING, "$TODAY|en|"))
            add(needsInput(JsonNull, TODAY, "en", HomeGreetingSlot.MORNING, "$TODAY|en|radiohead::"))
            add(needsInput(g, TODAY + 1, "en", HomeGreetingSlot.EVENING, "$TODAY|en|x"))
            add(needsInput(g, TODAY, "zh", HomeGreetingSlot.AFTERNOON, null))
        },
        "shouldGenerate" to buildList {
            val g = greetings(TODAY, "en", "A b c", "D e f", "G h i", fp = "old", refresh = 1)
            for (slot in HomeGreetingSlot.entries) {
                add(shouldInput(g, TODAY, "en", slot, "new", null))
                add(shouldInput(g, TODAY, "en", slot, "old", null))
            }
            add(shouldInput(g, TODAY, "en", HomeGreetingSlot.MORNING, "new", "$TODAY|en|new"))
            add(shouldInput(greetings(TODAY, "en", "A b c", null, null, fp = "old", refresh = 2), TODAY, "en", HomeGreetingSlot.MORNING, "new", null))
            add(shouldInput(JsonNull, TODAY, "en", HomeGreetingSlot.EVENING, "fp", null))
            add(shouldInput(JsonNull, TODAY, "en", HomeGreetingSlot.EVENING, "fp", "$TODAY|en|fp"))
            add(shouldInput(JsonNull, TODAY, "en", HomeGreetingSlot.EVENING, "fp2", "$TODAY|en|fp"))
            add(shouldInput(g, TODAY - 1, "en", HomeGreetingSlot.MORNING, "old", "${TODAY - 1}|en|old"))
        },
        "merged" to buildList {
            val existing = greetings(TODAY, "en", "Old morning line", "Old afternoon line", "Old evening line", fp = "a", refresh = 1)
            val fresh = greetings(TODAY, "en", "New morning line", null, "New evening line", fp = "b")
            for (slot in HomeGreetingSlot.entries) add(mergedInput(existing, fresh, slot, "b"))
            add(mergedInput(JsonNull, fresh, HomeGreetingSlot.AFTERNOON, "c"))
        },
        "history" to listOf(
            historyInput("", TODAY, listOf("Still spinning In Rainbows", "Utada all week")),
            historyInput("${TODAY - 1}\tFriday at last\n${TODAY - 7}\tToo old\n${TODAY - 6}\tSix days ago\n${TODAY}\tToday's own\nbad line\n\tno day\nx\tnot a number\n${TODAY - 2}\t  ",
                TODAY, listOf("friday at last!", "New one")),
            historyInput((1..25).joinToString("\n") { "${TODAY - 1}\tPhrase number $it" }, TODAY, listOf("Fresh phrase")),
            historyInput("${TODAY - 3}\tKeep me", TODAY, emptyList()),
        ),
        "pickCues" to listOf(
            cuesInput(emptyList(), emptyList(), emptyList()),
            cuesInput(
                listOf(
                    cuePlay("Radiohead", "In Rainbows", NOW - HOUR),
                    cuePlay("Utada Hikaru", "BAD Mode", NOW - 2 * HOUR),
                    cuePlay("Bill Evans", "Sunday at the Village Vanguard", NOW - 3 * HOUR),
                    cuePlay("Utada Hikaru", "Fantôme", NOW - DAY),
                    cuePlay("radiohead", "OK Computer", NOW - 2 * DAY),
                    cuePlay("Bill Evans", "Portrait in Jazz", NOW - 8 * DAY),
                    cuePlay("Bill Evans", "Explorations", NOW - 9 * DAY),
                ),
                listOf(play("radiohead", "Kid A"), play("Joni Mitchell", "Blue")),
                listOf(cueAlbum("Sufjan Stevens", "Carrie & Lowell", NOW - 10 * DAY)),
            ),
            cuesInput(
                listOf(cuePlay("  ", "Nameless", NOW), cuePlay("Mitski", "Be the Cowboy", NOW - HOUR)),
                emptyList(),
                listOf(
                    cueAlbum("Mitski", "Puberty 2", NOW - 20 * DAY),
                    cueAlbum("Big Thief", "Dragon", NOW - DAY),
                    cueAlbum("Big Thief", "U.F.O.F.", NOW - 4 * DAY),
                ),
            ),
            cuesInput(
                listOf(cuePlay("A", "a1", NOW - 10 * DAY), cuePlay("B", "b1", NOW - 11 * DAY)),
                listOf(play("A", "a2")),
                listOf(cueAlbum("C", "c1", 0L)),
            ),
            cuesInput(listOf(cuePlay("Solo", "One", NOW)), emptyList(), emptyList()),
        ),
        "followUpPrepare" to listOf(
            prepareInput(listOf(aboutEntry("canonical", "review", review)), emptyList(), "en"),
            prepareInput(
                listOf(
                    aboutEntry("canonical", "creation_time", "2007"),
                    aboutEntry("canonical", "producer", "Nigel Godrich"),
                    aboutEntry("canonical", "review", review),
                    aboutEntry("ask", "who played bass", "Colin Greenwood plays the bass line.", prompt = "Who played bass?"),
                    aboutEntry("own", "my take", "Best live at Glastonbury.", titleText = "My take"),
                    aboutEntry("ask", "q2", "Answer two."),
                    aboutEntry("ask", "q3", "Answer three should be dropped as the fourth non-canonical row."),
                ),
                listOf(" Who played bass? ", "Who played bass?", ""),
                "en-GB",
            ),
            prepareInput(listOf(aboutEntry("canonical", "review", zhReview, title = "Nude ", artist = " Radiohead")), listOf("歌词讲了什么？"), "zh-Hans-CN"),
            prepareInput(listOf(aboutEntry("canonical", "lyricist", "Thom Yorke")), emptyList(), "en"),
            prepareInput(emptyList(), emptyList(), "en"),
            prepareInput(listOf(aboutEntry("canonical", "review", "word ".repeat(400), title = "", artist = "")), emptyList(), "en"),
            prepareInput(listOf(aboutEntry("canonical", "review", review, artist = "")), emptyList(), "zh"),
        ),
        "followUpParse" to listOf(
            parseQInput("en", "[\"Why did the band wait a decade to record it?\",\"How does the live version differ?\",\"Who arranged the strings?\"]"),
            parseQInput("en", "```json\n[\"What inspired the falsetto melody\", \"How was it recorded at the studio?\"]\n```"),
            parseQInput("en", "Here are some questions:\n1. Why did the band wait so long?\n2) How does it compare live?\n- Who mixed the final version?"),
            parseQInput("en", "[\"Too short?\",\"Why did the band wait a decade to record it?\",\"why did the band wait a decade to record it?\"]"),
            parseQInput("en", "[\"Who sampled this song?\",\"What is the story behind the bass line?\"]", asked = listOf("who sampled this song?")),
            parseQInput("en", "[\"为什么等了十年才录？\",\"How did the strings get added late?\"]"),
            parseQInput("en", "[\"Q with \\\"quotes\\\" inside, does it work?\",\"Escaped\\nnewline in the middle here?\"]"),
            parseQInput("zh-Hans", "[\"乐队为什么等了十年才录这首歌？\",\"现场版和录音室版有什么不同\",\"“In Rainbows”的编曲是谁定的？\",\"第四个问题不应该出现？\"]"),
            parseQInput("zh-Hans", "好的，以下是问题：\n• 低音线是谁写的？\n• 假声的灵感来自哪里?"),
            parseQInput("zh", "[\"短？\",\"English only question here?\"]"),
            parseQInput("en", ""),
            parseQInput("en", "{\"questions\": [\"How was the final take chosen in the end?\"]}"),
        ),
        "followUpValidate" to listOf(
            validateQInput("en", listOf("Why did the band wait a decade to record it", "  How does the live version differ?  ", "Who?", "Who arranged the strings on the album?", "One more valid question for the list?")),
            validateQInput("zh-Hans", listOf("乐队为什么等了十年才录", "现场版有什么不同？", "Who arranged it?"), listOf("现场版有什么不同？")),
            validateQInput("en", emptyList()),
        ),
        "followUpMerge" to listOf(
            mergeInput(listOf("Why the decade-long wait?", "How does it differ live?"), listOf("What does this song sample?", "Who sampled this song?", "How was it written?"), listOf("Who sampled this song?")),
            mergeInput(emptyList(), listOf("What does this song sample?", "What are the lyrics about?"), emptyList()),
            mergeInput(listOf("What are the lyrics about?"), listOf("What are the lyrics about?", "How was it received?"), emptyList()),
            mergeInput(listOf("  "), listOf("How was it received?"), listOf("how was it received?")),
        ),
    )

    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L
    private const val NOW = 1_764_244_800_000L

    private fun userPromptInput(cues: JsonObject, day: Int, month: Int, lang: String, avoid: List<String>) = obj(
        "cues" to cues, "dayOfWeek" to JsonPrimitive(day), "month" to JsonPrimitive(month),
        "localeTag" to JsonPrimitive(lang), "avoidRecent" to strings(avoid),
    )

    private fun validateInput(
        lang: String,
        m: String?,
        a: String?,
        e: String?,
        cues: JsonObject,
        avoid: List<String> = emptyList(),
    ) = obj(
        "morning" to str(m), "afternoon" to str(a), "evening" to str(e), "day" to JsonPrimitive(TODAY),
        "localeTag" to JsonPrimitive(lang), "profileId" to JsonPrimitive("p1"), "cues" to cues,
        "avoidRecent" to strings(avoid),
    )

    private fun parseInput(lang: String, answer: String, cues: JsonObject, avoid: List<String> = emptyList()) = obj(
        "answer" to JsonPrimitive(answer), "day" to JsonPrimitive(TODAY), "localeTag" to JsonPrimitive(lang),
        "profileId" to JsonPrimitive("p1"), "cues" to cues, "avoidRecent" to strings(avoid),
    )

    private fun cachedInput(cached: JsonElement, slot: HomeGreetingSlot, today: Long, lang: String, noon: Boolean) = obj(
        "cached" to cached, "slot" to JsonPrimitive(slot.name), "today" to JsonPrimitive(today),
        "lang" to JsonPrimitive(lang), "isNoon" to JsonPrimitive(noon),
    )

    private fun needsInput(cached: JsonElement, today: Long, lang: String, slot: HomeGreetingSlot, last: String?) = obj(
        "cached" to cached, "today" to JsonPrimitive(today), "lang" to JsonPrimitive(lang),
        "currentSlot" to JsonPrimitive(slot.name), "lastAttempt" to str(last),
    )

    private fun shouldInput(
        cached: JsonElement,
        today: Long,
        lang: String,
        slot: HomeGreetingSlot,
        fp: String,
        last: String?,
    ) = obj(
        "cached" to cached, "today" to JsonPrimitive(today), "lang" to JsonPrimitive(lang),
        "currentSlot" to JsonPrimitive(slot.name), "cuesFingerprint" to JsonPrimitive(fp), "lastAttempt" to str(last),
    )

    private fun mergedInput(existing: JsonElement, fresh: JsonObject, slot: HomeGreetingSlot, fp: String) = obj(
        "existing" to existing, "fresh" to fresh, "currentSlot" to JsonPrimitive(slot.name),
        "cuesFingerprint" to JsonPrimitive(fp),
    )

    private fun historyInput(raw: String, today: Long, phrases: List<String>) = obj(
        "raw" to JsonPrimitive(raw), "today" to JsonPrimitive(today), "phrases" to strings(phrases),
    )

    private fun cuePlay(artist: String, album: String, at: Long) = obj(
        "artist" to JsonPrimitive(artist), "album" to JsonPrimitive(album), "playedAt" to JsonPrimitive(at),
    )

    private fun cueAlbum(artist: String, album: String, at: Long) = obj(
        "artist" to JsonPrimitive(artist), "album" to JsonPrimitive(album), "lastPlayedAt" to JsonPrimitive(at),
    )

    private fun cuesInput(recent: List<JsonObject>, rediscover: List<JsonObject>, albums: List<JsonObject>) = obj(
        "recent" to JsonArray(recent), "rediscover" to JsonArray(rediscover), "albums" to JsonArray(albums),
        "now" to JsonPrimitive(NOW),
    )

    private fun prepareInput(entries: List<JsonObject>, asked: List<String>, lang: String) = obj(
        "entries" to JsonArray(entries), "asked" to strings(asked), "localeTag" to JsonPrimitive(lang),
    )

    private fun parseQInput(lang: String, answer: String, asked: List<String> = emptyList()) = obj(
        "answer" to JsonPrimitive(answer), "asked" to strings(asked), "localeTag" to JsonPrimitive(lang),
    )

    private fun validateQInput(lang: String, questions: List<String>, asked: List<String> = emptyList()) = obj(
        "candidates" to strings(questions), "asked" to strings(asked), "localeTag" to JsonPrimitive(lang),
    )

    private fun mergeInput(suggested: List<String>, fallback: List<String>, asked: List<String>) = obj(
        "suggested" to strings(suggested), "fallback" to strings(fallback), "asked" to strings(asked),
    )

    // ── outputs ─────────────────────────────────────────────────────────────────────────────────────────

    private val ai = YoinOnDeviceAi

    fun outputs(section: String, v: JsonObject): JsonObject = when (section) {
        "greetingInstructions" -> obj(
            "instructions" to JsonPrimitive(ai.greetingInstructions(v.bool("hasAnyCue"), v.string("localeTag")!!)),
        )
        "greetingUserPrompt" -> {
            val cues = slotCues(v.getValue("cues").jsonObject)
            obj(
                "prompt" to JsonPrimitive(ai.greetingUserPrompt(cues, v.int("dayOfWeek"), v.int("month"), v.string("localeTag")!!, v.strings("avoidRecent"))),
                "plainPrompt" to JsonPrimitive(ai.greetingPlainPrompt(cues, v.int("dayOfWeek"), v.int("month"), v.string("localeTag")!!, v.strings("avoidRecent"))),
                "fingerprint" to JsonPrimitive(cues.fingerprint()),
            )
        }
        "greetingSanitize" -> obj("clean" to str(HomeGreetingPrompt.sanitize(v.string("raw"), v.string("localeTag")!!)))
        "greetingValidate" -> obj(
            "result" to greetingsJson(
                ai.validateGreetings(
                    v.string("morning"), v.string("afternoon"), v.string("evening"), v.long("day"),
                    v.string("localeTag")!!, v.string("profileId")!!, slotCues(v.getValue("cues").jsonObject),
                    v.strings("avoidRecent"),
                ),
            ),
        )
        "greetingParse" -> obj(
            "result" to greetingsJson(
                ai.parseGreetings(
                    v.string("answer"), v.long("day"), v.string("localeTag")!!, v.string("profileId")!!,
                    slotCues(v.getValue("cues").jsonObject), v.strings("avoidRecent"),
                ),
            ),
        )
        "visualWidth" -> obj("width" to JsonPrimitive(HomeGreetingPrompt.visualWidth(v.string("text")!!)))
        "conflictsWithNoon" -> obj("conflicts" to JsonPrimitive(ai.conflictsWithNoon(v.string("text")!!)))
        "slotAtHour" -> obj(
            "slot" to JsonPrimitive(ai.slotAtHour(v.int("hour")).name),
            "isNoon" to JsonPrimitive(ai.isNoonHour(v.int("hour"))),
        )
        "dayOf" -> obj("day" to JsonPrimitive(ai.dayOf(v.long("epochMs"), v.long("utcOffsetMs"))))
        "cachedText" -> obj(
            "text" to str(
                ai.cachedText(greetings(v["cached"]), slot(v, "slot"), v.long("today"), v.string("lang")!!, v.bool("isNoon")),
            ),
        )
        "needsGeneration" -> obj(
            "needs" to JsonPrimitive(
                ai.needsGeneration(greetings(v["cached"]), v.long("today"), v.string("lang")!!, slot(v, "currentSlot"), v.string("lastAttempt")),
            ),
        )
        "shouldGenerate" -> obj(
            "generate" to JsonPrimitive(
                ai.shouldGenerate(
                    greetings(v["cached"]), v.long("today"), v.string("lang")!!, slot(v, "currentSlot"),
                    v.string("cuesFingerprint")!!, v.string("lastAttempt"),
                ),
            ),
            "attemptValue" to JsonPrimitive(ai.attemptValue(v.long("today"), v.string("lang")!!, v.string("cuesFingerprint")!!)),
        )
        "merged" -> obj(
            "result" to greetingsJson(
                ai.merged(greetings(v["existing"]), greetings(v["fresh"])!!, slot(v, "currentSlot"), v.string("cuesFingerprint")!!),
            ),
        )
        "history" -> obj(
            "recent" to strings(ai.recentHistory(v.string("raw")!!, v.long("today"))),
            "recorded" to str(ai.recordHistory(v.string("raw")!!, v.long("today"), v.strings("phrases"))),
        )
        "pickCues" -> {
            val recent = v.getValue("recent").jsonArray.map { it.jsonObject }
                .map { ai.cuePlay(it.string("artist")!!, it.string("album")!!, it.long("playedAt")) }
            val rediscover = v.getValue("rediscover").jsonArray.map { it.jsonObject }
                .map { ai.recentPlay(it.string("artist")!!, it.string("album")!!) }
            val albums = v.getValue("albums").jsonArray.map { it.jsonObject }
                .map { ai.cueAlbum(it.string("artist")!!, it.string("album")!!, it.long("lastPlayedAt")) }
            val cues = ai.pickCues(recent, rediscover, albums, v.long("now"))
            obj(
                "cues" to cuesJson(cues),
                "fingerprint" to JsonPrimitive(cues.fingerprint()),
                "needsAlbums" to JsonPrimitive(HomeGreetingSchedule.pickCuesNeedsAlbums(recent, rediscover, v.long("now"))),
            )
        }
        "followUpPrepare" -> {
            val entries = v.getValue("entries").jsonArray.map { it.jsonObject }.map {
                ai.aboutEntry(
                    it.string("kind")!!, it.string("entryKey")!!, it.string("titleDisplay")!!,
                    it.string("artistDisplay")!!, it.string("titleText"), it.string("promptText"), it.string("answerText")!!,
                )
            }
            val prepared = ai.prepareFollowUps(entries, v.strings("asked"), v.string("localeTag")!!)
            obj(
                "instructions" to JsonPrimitive(ai.followUpInstructions(v.string("localeTag")!!)),
                "prepared" to (
                    prepared?.let {
                        obj(
                            "title" to JsonPrimitive(it.title), "artist" to JsonPrimitive(it.artist),
                            "context" to JsonPrimitive(it.context), "asked" to strings(it.asked),
                            "localeTag" to JsonPrimitive(it.localeTag), "fingerprint" to JsonPrimitive(it.fingerprint),
                            "userPrompt" to JsonPrimitive(ai.followUpUserPrompt(it)),
                            "plainPrompt" to JsonPrimitive(ai.followUpPlainPrompt(it)),
                        )
                    } ?: JsonNull
                    ),
            )
        }
        "followUpParse" -> obj(
            "questions" to strings(ai.parseFollowUps(v.string("answer"), v.strings("asked"), v.string("localeTag")!!)),
        )
        "followUpValidate" -> obj(
            "questions" to strings(ai.validateFollowUps(v.strings("candidates"), v.strings("asked"), v.string("localeTag")!!)),
        )
        "followUpMerge" -> obj(
            "merged" to strings(ai.mergeFollowUps(v.strings("suggested"), v.strings("fallback"), v.strings("asked"))),
        )
        else -> error("unknown section $section")
    }

    fun constants(): JsonObject = obj(
        "greetingMaxOutputTokens" to JsonPrimitive(ai.greetingMaxOutputTokens),
        "greetingTemperature" to JsonPrimitive(ai.greetingTemperature),
        "greetingTopK" to JsonPrimitive(ai.greetingTopK),
        "maxDailyRefreshes" to JsonPrimitive(ai.maxDailyRefreshes),
        "recentHistoryLimit" to JsonPrimitive(ai.recentHistoryLimit),
        "rediscoverLimit" to JsonPrimitive(ai.rediscoverLimit),
        "albumAggregateLimit" to JsonPrimitive(ai.albumAggregateLimit),
        "rediscoverAgeMs" to JsonPrimitive(ai.rediscoverAgeMs),
        "followUpMaxOutputTokens" to JsonPrimitive(ai.followUpMaxOutputTokens),
        "followUpTemperature" to JsonPrimitive(ai.followUpTemperature),
        "followUpTopK" to JsonPrimitive(ai.followUpTopK),
        "followUpMaxQuestions" to JsonPrimitive(ai.followUpMaxQuestions),
    )

    fun render(): String = buildString {
        append("{\n")
        append("  \"about\": ").append(encode(JsonPrimitive(ABOUT))).append(",\n")
        append("  \"constants\": ").append(encode(constants()))
        sections.forEach { section ->
            val inputs = inputs.getValue(section)
            append(",\n  \"").append(section).append("\": [\n")
            inputs.forEachIndexed { i, input ->
                append("    ").append(encode(JsonObject(input + outputs(section, input))))
                append(if (i == inputs.lastIndex) "\n" else ",\n")
            }
            append("  ]")
        }
        append("\n}\n")
    }

    fun replay(text: String): List<String> {
        val root = Json.parseToJsonElement(text).jsonObject
        val mismatches = mutableListOf<String>()
        if (root.getValue("constants") != constants()) mismatches += "constants differ"
        sections.forEach { section ->
            val vectors = root[section]?.jsonArray ?: return@forEach run { mismatches += "missing section $section" }
            vectors.forEachIndexed { i, element ->
                val vector = element.jsonObject
                outputs(section, vector).forEach { (key, actual) ->
                    if (vector.getValue(key) != actual) {
                        mismatches += "$section[$i] $key: expected ${vector[key]} but was $actual"
                    }
                }
            }
        }
        return mismatches
    }

    // ── json helpers ────────────────────────────────────────────────────────────────────────────────────

    private fun recentPlayOf(e: JsonElement?): HomeGreetingPrompt.RecentPlay? =
        (e as? JsonObject)?.let { HomeGreetingPrompt.RecentPlay(it.string("artist")!!, it.string("album")!!) }

    fun slotCues(o: JsonObject): HomeGreetingPrompt.SlotCues =
        HomeGreetingPrompt.SlotCues(recentPlayOf(o["morning"]), recentPlayOf(o["afternoon"]), recentPlayOf(o["evening"]))

    private fun cuesJson(c: HomeGreetingPrompt.SlotCues): JsonObject {
        fun p(r: HomeGreetingPrompt.RecentPlay?): JsonElement = r?.let { play(it.artist, it.album) } ?: JsonNull
        return obj("morning" to p(c.morning), "afternoon" to p(c.afternoon), "evening" to p(c.evening))
    }

    fun greetings(e: JsonElement?): DailyHomeGreetings? = (e as? JsonObject)?.let {
        DailyHomeGreetings(
            day = it.long("day"), localeTag = it.string("localeTag")!!, profileId = it.string("profileId")!!,
            morning = it.string("morning"), afternoon = it.string("afternoon"), evening = it.string("evening"),
            cuesFingerprint = it.string("cuesFingerprint")!!, refreshCount = it.int("refreshCount"),
        )
    }

    private fun greetingsJson(g: DailyHomeGreetings?): JsonElement = g?.let {
        greetings(it.day, it.localeTag, it.morning, it.afternoon, it.evening, it.cuesFingerprint, it.refreshCount, it.profileId)
    } ?: JsonNull

    private fun slot(v: JsonObject, key: String) = HomeGreetingSlot.valueOf(v.string(key)!!)

    fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    fun obj(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    fun encode(e: JsonElement): String = Json.encodeToString(JsonElement.serializer(), e)
}
