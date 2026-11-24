package com.gpo.yoin.ui.notes.editor

import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMention
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden vectors other ports of the @ popup's ranking are tested against (HarmonyOS's Dart port:
 * harmony/test/data/notes/mention_ranking_golden_test.dart): [MentionRanking.fold] / [MentionRanking.compact],
 * [MentionRanking.tier], [MentionRanking.rank] and the editor's live filtering ([mentionRowsWhileSearching],
 * [mentionRowsAfterSearch]). Every expected value is what this Kotlin computes; the test fails when the
 * checked-in file drifts from it.
 *
 * Pinyin is the platform's (ICU here, another library elsewhere), so the vectors carry their own
 * romanizations ("romanizations": name → syllables) and both sides rank with that table: the ranking is
 * what must agree, given the same spelling.
 *
 * File: shared/src/commonTest/resources/golden/mention-ranking.json (next to note-markup.json). Regenerate
 * after an intentional change:
 * `YOIN_WRITE_NOTE_GOLDEN=1 ./gradlew :app:testDebugUnitTest --tests '*MentionRankingGoldenTest*'`.
 */
class MentionRankingGoldenTest {

    @Test
    fun should_matchCheckedInGolden_when_kotlinComputesEveryVector() {
        val json = goldenJson()
        val file = goldenFile()
        if (System.getenv("YOIN_WRITE_NOTE_GOLDEN") == "1" || !file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(json)
        }
        assertEquals(file.readText(), json)
    }

    private fun goldenFile(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "shared").isDirectory && File(it, "settings.gradle.kts").isFile }
        return File(root, "shared/src/commonTest/resources/golden/mention-ranking.json")
    }

    // ── the romanizer both sides use ─────────────────────────────────

    private val romanizations = linkedMapOf(
        "纽约" to "niu yue",
        "晴天" to "qing tian",
        "周杰伦" to "zhou jie lun",
        "你好 (Live)" to "ni hao (Live)",
        "灯塔" to "deng ta",
        "七里香" to "qi li xiang",
        "南山南" to "nan shan nan",
        "马頔" to "ma di",
        "陈奕迅" to "chen yi xun",
        "十年" to "shi nian",
        "夜曲" to "ye qu",
        "叶惠美" to "ye hui mei",
        "新的心跳" to "xin de xin tiao",
        "王菲" to "wang fei",
        "红豆" to "hong dou",
        "纽约纽约 New York" to "niu yue niu yue New York",
    )
    private val romanizer = MentionRomanizer { romanizations[it] }

    // ── corpora ──────────────────────────────────────────────────────

    private val foldCorpus = listOf(
        "New York", "Ｎｅｗ Yörk", "Björk", "Beyoncé", "Sigur Rós", "Ólafur Arnalds", "Mötley Crüe",
        "  Spaced  Out ", "niu'yue", "niu yue", "Don’t Stop", "a·b.c_d-e", "ＡＢＣ１２３", "纽约", "晴天 🌤️",
        "ÀÉÎÕÜ àéîõü", "Ça Plane Pour Moi", "Łódź", "Ñandú", "", "   ", "N", "＠",
    )

    private data class Row(
        val kind: MentionKind,
        val id: String,
        val title: String,
        val artist: String? = null,
        val source: MentionCandidate.Source = MentionCandidate.Source.Search,
    )

    private fun row(r: Row) = MentionCandidate(
        mention = NoteMention(r.kind, "subsonic", r.id, r.title, r.artist?.takeIf { r.kind != MentionKind.Artist }),
        coverUrl = null,
        artist = r.artist?.takeIf { r.kind != MentionKind.Artist },
        source = r.source,
    )

    private fun t(title: String, artist: String? = null, source: MentionCandidate.Source = MentionCandidate.Source.Search, id: String = "t-$title") =
        Row(MentionKind.Track, id, title, artist, source)

    private fun al(title: String, artist: String? = null, source: MentionCandidate.Source = MentionCandidate.Source.Search, id: String = "al-$title") =
        Row(MentionKind.Album, id, title, artist, source)

    private fun ar(name: String, source: MentionCandidate.Source = MentionCandidate.Source.Search, id: String = "ar-$name") =
        Row(MentionKind.Artist, id, name, null, source)

    private val cur = MentionCandidate.Source.Current
    private val rec = MentionCandidate.Source.Recent
    private val sea = MentionCandidate.Source.Search
    private val seen = MentionCandidate.Source.Seen

    private val tierRows = listOf(
        t("New York"), t("Nights"), t("Animal"), t("Sunny"), t("Night Yard"), t("Nyx"), t("Pony"),
        t("Holy York", artist = "Ny Band"), t("纽约"), t("晴天", artist = "周杰伦"), t("你好 (Live)"), t("Björk Live"),
        t("Don’t Stop Me Now", artist = "Queen"), t("七里香", artist = "周杰伦"), t("南山南", artist = "马頔"),
        t("十年", artist = "陈奕迅"), t("纽约纽约 New York"), t("Ça Plane Pour Moi", artist = "Plastic Bertrand"),
        t("ＡＢＣ"), t("a-ha Take On Me", artist = "a-ha"), al("叶惠美", artist = "周杰伦"), ar("王菲"),
        ar("Sigur Rós"), t("红豆", artist = "王菲"), t("夜曲", artist = "周杰伦"),
    )

    private val queries = listOf(
        "N", "n", "ny", "new", "New York", "newyork", "new y", "ｎｅｗ", "york", "niu", "niuyue", "niu'yue", "niu yue",
        "yue", "纽", "纽约", "qt", "qing", "zjl", "zhoujie", "nhl", "nihao", "live", "bjo", "BJÖ", "dont", "don't",
        "dsmn", "qlx", "nsn", "md", "cyx", "sn", "shinian", "ca", "ça", "abc", "aha", "a-ha", "tom", "yhm", "wf",
        "rós", "ros", "sigur", "hd", "fei", "yq", "queen", "zz", "", "  ", "x", "o",
    )

    private fun rankCases(): List<Pair<String, Triple<List<Row>, List<Row>, List<Row>>>> = listOf(
        "N" to Triple(listOf(t("Animal"), t("Sunny"), t("New York"), t("Nights")), emptyList(), emptyList()),
        "ny" to Triple(listOf(t("Holy York", artist = "Ny Band"), t("Pony"), t("Night Yard"), t("Nyx")), emptyList(), emptyList()),
        "niu'yue" to Triple(listOf(t("晴天"), t("纽约"), t("Nuyorican Soul")), emptyList(), emptyList()),
        "qt" to Triple(listOf(t("晴天"), t("纽约"), t("Nuyorican Soul")), emptyList(), emptyList()),
        "n" to Triple(listOf(t("Nights", source = sea), t("New York", source = rec), t("Nothing", source = cur)), emptyList(), emptyList()),
        "new" to Triple(
            listOf(t("Elsewhere", source = sea), t("Unrelated", source = rec), t("Older", source = seen), t("New York", source = sea)),
            emptyList(),
            emptyList(),
        ),
        "new" to Triple(listOf(t("Hello Yorktown")), listOf(al("New York")), emptyList()),
        "" to Triple(listOf(t("B", source = rec), t("A")), listOf(al("C")), emptyList()),
        "  " to Triple(listOf(t("B", source = rec), t("A")), emptyList(), listOf(ar("D"))),
        "zjl" to Triple(
            listOf(t("晴天", artist = "周杰伦", source = rec), t("夜曲", artist = "周杰伦"), t("Zhou's Tune")),
            listOf(al("叶惠美", artist = "周杰伦")),
            listOf(ar("周杰伦"), ar("Zjl Band")),
        ),
        "wf" to Triple(listOf(t("红豆", artist = "王菲"), t("Wolf")), emptyList(), listOf(ar("王菲", source = cur))),
        "sigur" to Triple(listOf(t("Hoppípolla", artist = "Sigur Rós")), listOf(al("Takk...", artist = "Sigur Rós")), listOf(ar("Sigur Rós"))),
        "x" to Triple(listOf(t("A", source = rec), t("B", source = cur)), listOf(al("C", source = cur)), listOf(ar("D", source = cur))),
        "x" to Triple(listOf(t("A", source = sea), t("B", source = sea)), listOf(al("C")), listOf(ar("D"))),
        "nan" to Triple(
            listOf(t("南山南", artist = "马頔", source = rec), t("Banana", source = sea), t("Nana", source = sea), t("Anan", source = cur)),
            listOf(al("南山南")),
            listOf(ar("Nana Mouskouri")),
        ),
        "york" to Triple(
            listOf(t("New York", source = sea), t("Yorktown", source = rec), t("纽约纽约 New York", source = cur)),
            listOf(al("York Minster")),
            emptyList(),
        ),
        "a" to Triple(
            listOf(t("Ab", source = seen), t("Ac", source = sea), t("Ad", source = rec), t("Ae", source = cur), t("Ba", source = cur)),
            listOf(al("Aa", source = seen), al("Ab")),
            listOf(ar("Ax", source = rec)),
        ),
        "dont" to Triple(listOf(t("Don’t Stop Me Now", artist = "Queen"), t("Dontcha")), emptyList(), emptyList()),
    )

    private fun liveCases(): List<Triple<String, Triple<List<Row>, List<Row>, List<Row>>, Triple<List<Row>, List<Row>, List<Row>>>> = listOf(
        // query, pool (what the editor was offered before), found (the search's answer)
        Triple(
            "ny",
            Triple(listOf(t("Clair de Lune", source = rec), t("New York", source = sea), t("纽约", source = cur)), emptyList(), emptyList()),
            Triple(listOf(t("Nylon"), t("New York")), emptyList(), emptyList()),
        ),
        Triple(
            "",
            Triple(listOf(t("Old", source = rec)), emptyList(), emptyList()),
            Triple(listOf(t("Playing", source = cur), t("Recent", source = rec)), listOf(al("Its Album", source = cur)), listOf(ar("Its Artist", source = cur))),
        ),
        Triple(
            "n",
            Triple(
                listOf(t("N1", source = rec), t("N2", source = rec), t("N3", source = rec), t("N4", source = rec), t("N5", source = rec), t("N6", source = rec), t("N7", source = sea)),
                emptyList(),
                emptyList(),
            ),
            Triple(listOf(t("N8"), t("Other")), listOf(al("Nine")), emptyList()),
        ),
        Triple(
            "zjl",
            Triple(listOf(t("晴天", artist = "周杰伦", source = cur)), listOf(al("叶惠美", artist = "周杰伦", source = cur)), listOf(ar("周杰伦", source = cur))),
            Triple(listOf(t("七里香", artist = "周杰伦")), emptyList(), listOf(ar("周杰伦"))),
        ),
        Triple(
            "q",
            Triple(listOf(t("Quiet", source = sea), t("Elsewhere", source = sea)), emptyList(), emptyList()),
            Triple(listOf(t("Something Else")), emptyList(), emptyList()),
        ),
    )

    // ── JSON ─────────────────────────────────────────────────────────

    private fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))

    private fun rowJson(r: Row) = obj(
        "id" to str(r.id),
        "title" to str(r.title),
        "artist" to str(r.artist),
        "source" to str(r.source.name),
    )

    private fun rowsJson(rows: Triple<List<Row>, List<Row>, List<Row>>) = obj(
        "tracks" to JsonArray(rows.first.map(::rowJson)),
        "albums" to JsonArray(rows.second.map(::rowJson)),
        "artists" to JsonArray(rows.third.map(::rowJson)),
    )

    private fun candidates(rows: Triple<List<Row>, List<Row>, List<Row>>) = MentionCandidates(
        tracks = rows.first.map(::row),
        albums = rows.second.map(::row),
        artists = rows.third.map(::row),
    )

    /** The answer as ids per kind, the kinds' order, and every row in listing order (what Enter walks). */
    private fun rankedJson(ranked: MentionCandidates) = obj(
        "order" to JsonArray(ranked.order.map { str(it.name) }),
        "tracks" to JsonArray(ranked.tracks.map { str(it.mention.id) }),
        "albums" to JsonArray(ranked.albums.map { str(it.mention.id) }),
        "artists" to JsonArray(ranked.artists.map { str(it.mention.id) }),
        "all" to JsonArray(ranked.all.map { str(it.mention.id) }),
        "sources" to JsonArray(ranked.all.map { str(it.source.name) }),
    )

    private fun goldenJson(): String {
        val sections = linkedMapOf(
            "fold" to foldCorpus.map { text ->
                obj("text" to str(text), "fold" to str(MentionRanking.fold(text)), "compact" to str(MentionRanking.compact(text)))
            },
            "tier" to queries.flatMap { q ->
                tierRows.map { r ->
                    obj(
                        "query" to str(q),
                        "kind" to str(r.kind.name),
                        "title" to str(r.title),
                        "artist" to str(r.artist),
                        "tier" to str(MentionRanking.tier(q, row(r), romanizer).name),
                    )
                }
            },
            "rank" to rankCases().map { (q, rows) ->
                obj("query" to str(q), "rows" to rowsJson(rows), "ranked" to rankedJson(MentionRanking.rank(q, candidates(rows), romanizer)))
            },
            "live" to liveCases().map { (q, pool, found) ->
                obj(
                    "query" to str(q),
                    "pool" to rowsJson(pool),
                    "found" to rowsJson(found),
                    "whileSearching" to rankedJson(mentionRowsWhileSearching(q, candidates(pool), romanizer)),
                    "afterSearch" to rankedJson(mentionRowsAfterSearch(q, candidates(found), candidates(pool), romanizer)),
                )
            },
        )
        val about = "Golden vectors for the @ popup's ranking (MentionRanking + the editor's live filtering), " +
            "generated from the Android app's Kotlin by MentionRankingGoldenTest. Do not edit by hand. " +
            "Rank with \"romanizations\" as the romanizer (pinyin differs by platform)."
        val compact = Json { prettyPrint = false }
        return buildString {
            append("{\n")
            append("  \"about\": ").append(compact.encodeToString(JsonElement.serializer(), str(about))).append(",\n")
            append("  \"romanizations\": ")
                .append(compact.encodeToString(JsonElement.serializer(), JsonObject(romanizations.mapValues { str(it.value) })))
            sections.forEach { (name, vectors) ->
                append(",\n  \"").append(name).append("\": [\n")
                vectors.forEachIndexed { i, vector ->
                    append("    ").append(compact.encodeToString(JsonElement.serializer(), vector))
                    append(if (i == vectors.lastIndex) "\n" else ",\n")
                }
                append("  ]")
            }
            append("\n}\n")
        }
    }
}
