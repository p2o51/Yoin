package com.gpo.yoin.data.lyrics

import com.gpo.yoin.data.model.LyricCredit
import com.gpo.yoin.data.model.LyricCreditRole
import com.gpo.yoin.data.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Shapes taken from real NetEase and QQ files (sung lines shortened). The rule
 * was tuned on 132 such files: see [LyricCreditsExtractor].
 */
class LyricCreditsExtractorTest {

    private fun synced(raw: String) = LrcParser.parse(raw) as Lyrics.Synced
    private fun texts(raw: String) = synced(raw).lines.map { it.text }

    @Test
    fun should_moveNeteaseHeadCreditsAndNotices_when_theyOpenTheFile() {
        val lyrics = synced(
            """
            [00:00.000] 作词 : 唐恬
            [00:00.463] 作曲 : 钱雷
            [00:00.926] 编曲 : 钱雷
            [00:01.389] 制作人 : 钱雷
            [00:01.852] 吉他 : 高飞
            [00:03.704] 混音 : 周天澈
            [00:04.630]词版权管理方：北京梦织音传媒有限公司
            [00:05.000]联合出品：拳头游戏/腾讯游戏/腾讯视频
            [00:22.290]都 是勇敢的
            [00:36.960]都 不必隐藏
            """.trimIndent(),
        )

        assertEquals(listOf("都 是勇敢的", "都 不必隐藏"), lyrics.lines.map { it.text })
        val credits = lyrics.credits!!
        assertEquals("唐恬", credits.namesFor(LyricCreditRole.Lyricist))
        assertEquals("钱雷", credits.namesFor(LyricCreditRole.Composer))
        assertEquals("钱雷", credits.namesFor(LyricCreditRole.Arranger))
        assertEquals("钱雷", credits.namesFor(LyricCreditRole.Producer))
        assertEquals(listOf(LyricCredit("吉他", "高飞"), LyricCredit("混音", "周天澈")), credits.others)
        assertEquals(
            listOf("词版权管理方：北京梦织音传媒有限公司", "联合出品：拳头游戏/腾讯游戏/腾讯视频"),
            credits.notices,
        )
    }

    @Test
    fun should_moveTheTailBlockToo_when_theHeadHadCredits() {
        val raw = """
            [00:00.000] 作词 : 李荣浩
            [00:01.000] 作曲 : 李荣浩
            [00:22.837]山隔壁还是山
            [03:28.781]麻雀也有明天
            [03:34.635]
            [04:07.757] 吉他 : 李荣浩
            [04:10.253] 音乐制作助理 : 青格乐
            [04:12.333] 母带后期处理录音室 : Studio21A
        """.trimIndent()

        assertEquals(listOf("山隔壁还是山", "麻雀也有明天"), texts(raw))
        assertEquals(3, synced(raw).credits!!.others.size)
    }

    @Test
    fun should_dropQqTitleLine_when_creditsFollowIt() {
        val raw = """
            [ti:Lemon]
            [00:00.00]Lemon - 米津玄師 (よねづ けんし)
            [00:00.53]词：米津玄師
            [00:01.06]曲：米津玄師
            [00:01.54]夢ならば
            [00:02.88]どれほどよかったでしょう
        """.trimIndent()

        assertEquals(listOf("夢ならば", "どれほどよかったでしょう"), texts(raw))
        assertEquals("米津玄師", synced(raw).credits!!.namesFor(LyricCreditRole.Lyricist))
    }

    @Test
    fun should_readEnglishRoles_when_qqListsTheStudioCredits() {
        val raw = """
            [00:00.00]Dynamite - BTS (防弹少年团)
            [00:00.01]Lyrics by：David Stewart/Jessica Agombar
            [00:00.02]Composed by：David Stewart/Jessica Agombar
            [00:00.05]Bass Guitar：David Stewart
            [00:00.08]Pads：David Stewart
            [00:00.13]Recording Engineer - Pdogg @ Dogg Bounce
            [00:05.30]'Cause ah ah I'm in the stars tonight
            [00:09.10]So watch me bring the fire
        """.trimIndent()

        assertEquals(listOf("'Cause ah ah I'm in the stars tonight", "So watch me bring the fire"), texts(raw))
        assertEquals(
            listOf("Bass Guitar", "Pads", "Recording Engineer"),
            synced(raw).credits!!.others.map { it.role },
        )
    }

    @Test
    fun should_takeFreeFormStudioLines_when_theTailListsThem() {
        val raw = """
            [00:00.000] 作词 : Freddie Mercury
            [00:00.136]Is this the real life
            [05:42.946]Anyway the wind blows
            [05:48.500]Lead & Backing Vocals : Freddie Mercury
            [05:50.500]Drums, Timpani & Gong : Roger Taylor
            [05:51.500]Recorded at Rockfield Studio, Roundhouse
            [05:52.000]ISRC TW-A45-99-62502
        """.trimIndent()

        assertEquals(listOf("Is this the real life", "Anyway the wind blows"), texts(raw))
        assertEquals(
            listOf("Recorded at Rockfield Studio, Roundhouse", "ISRC TW-A45-99-62502"),
            synced(raw).credits!!.others.filter { it.role.isEmpty() }.map { it.names },
        )
    }

    @Test
    fun should_keepDialogue_when_itFollowsTheCredits() {
        val raw = """
            [00:00.000] 作词 : 刘弢
            [00:00.239] 作曲 : 杨绍昆
            [00:00.479]天笑：好好好
            [00:03.739]龙宝：哦.....欸？哪首
            [00:06.499]小马/雀阳：一个短篇
        """.trimIndent()

        assertEquals(listOf("天笑：好好好", "龙宝：哦.....欸？哪首", "小马/雀阳：一个短篇"), texts(raw))
    }

    @Test
    fun should_leaveLyricsAlone_when_colonsAreSung() {
        val raw = """
            [00:10.00]她说：别走
            [00:13.00]我说：好
            [00:16.00]Chorus: 一起唱
            [01:00.00]作词：你的名字在信封上
            [02:40.00]Rap: 最后一段
            [02:48.00]谢谢收听
        """.trimIndent()

        assertEquals(6, texts(raw).size)
        assertNull(synced(raw).credits)
    }

    @Test
    fun should_keepSingerMarkers_when_theyAreLyricsNotCredits() {
        val raw = """
            [00:00.00]词：唐恬
            [00:01.00]曲：钱雷
            [00:10.00]宿涵：
            [00:12.00]第一句
            [00:20.00]合：
            [00:22.00]第二句
        """.trimIndent()

        assertEquals(listOf("宿涵：", "第一句", "合：", "第二句"), texts(raw))
    }

    @Test
    fun should_notCountTheBlock_when_itHasNoHeadlineRoleAndFewRoles() {
        val raw = """
            [00:00.00]吉他：某人
            [00:05.00]第一句
            [00:10.00]第二句
        """.trimIndent()

        assertEquals(3, texts(raw).size)
    }

    @Test
    fun should_keepEverything_when_fewerThanTwoLinesWouldBeLeft() {
        val raw = """
            [00:00.00]作曲：某人
            [00:05.00]纯音乐，请欣赏
        """.trimIndent()

        assertEquals(listOf("作曲：某人", "纯音乐，请欣赏"), texts(raw))
    }

    @Test
    fun should_extractFromPlainText_when_lyricsAreUnsynced() {
        val lyrics = LrcParser.parse("作词：A\n作曲：B\n\nFirst line\nSecond line") as Lyrics.Unsynced

        assertEquals("First line\nSecond line", lyrics.text)
        assertEquals("A", lyrics.credits!!.namesFor(LyricCreditRole.Lyricist))
    }

    @Test
    fun should_keepCredits_when_extractionIsOff() {
        val raw = "[00:00.00]作词：A\n[00:01.00]作曲：B\n[00:05.00]One\n[00:06.00]Two"

        assertEquals(4, (LrcParser.parse(raw, extractCredits = false) as Lyrics.Synced).lines.size)
    }

    @Test
    fun should_readHeadlineRoles_when_creditsUseADash() {
        val raw = "[00:00.00]Lyrics by - Alice\n[00:01.00]Composed by - Bob\n[00:05.00]One\n[00:06.00]Two"

        assertEquals(listOf("One", "Two"), texts(raw))
        assertEquals("Alice", synced(raw).credits!!.namesFor(LyricCreditRole.Lyricist))
        assertEquals("Bob", synced(raw).credits!!.namesFor(LyricCreditRole.Composer))
    }

    @Test
    fun should_keepAParenthesisedSungLine_when_noCreditSitsRightAboveIt() {
        val raw = """
            [00:00.00]作词：A
            [00:05.00]One
            [00:06.00]Two
            [00:07.00](Never gonna give you up)
        """.trimIndent()

        assertEquals(listOf("One", "Two", "(Never gonna give you up)"), texts(raw))
        assertEquals("A", synced(raw).credits!!.namesFor(LyricCreditRole.Lyricist))
    }

    @Test
    fun should_readBilingualRoles_when_vsingerWritesThemInChineseAndEnglish() {
        val raw = """
            [00:00.000] 作词 Lyricist : 石井 伸昂 Nobutaka Ishii
            [00:00.251] 中文翻译 Translator : haTami
            [00:01.004] 调声 Tuning : 周小蚕
            [00:01.500] 古筝 Guzheng : 猛虎蔷薇
            [00:12.000]霞む瞳に
            [00:15.000]降り注ぐ
            [03:43.719] 出品人 Publisher : 徐博(波吉)
            [03:44.034] 录音版权Sub Publishing : 上海哔哩哔哩科技有限公司/Vsinger
            [03:44.400] 艺人经纪 Manager: 刘伯澍@太合音乐
        """.trimIndent()

        assertEquals(listOf("霞む瞳に", "降り注ぐ"), texts(raw))
        val credits = synced(raw).credits!!
        assertEquals("石井 伸昂 Nobutaka Ishii", credits.namesFor(LyricCreditRole.Lyricist))
        assertEquals(listOf("中文翻译", "调声", "古筝", "艺人经纪"), credits.others.map { it.role })
    }

    @Test
    fun should_takeACreditRunAfterTheIntro_when_theHeadCounted() {
        val raw = """
            [00:00.00] 作词 : 唯Tu
            [00:01.00] 作曲 : 芹菜猪肉大馄饨
            [00:03.32] 回忆像是阴霾中的烟火
            [00:07.80] 凝住眼泪才能看清轮廓
            [00:29.79] 编曲 Arrange: HYPER SLASH超级斩
            [00:31.12] 调声 Tuning: 流绪
            [00:32.47] 吉他 Guitar: 文件夹
            [00:45.78] 我像个贝壳一样怕触碰
            [00:50.46] 小心翼翼地将心珍藏着
        """.trimIndent()

        assertEquals(
            listOf("回忆像是阴霾中的烟火", "凝住眼泪才能看清轮廓", "我像个贝壳一样怕触碰", "小心翼翼地将心珍藏着"),
            texts(raw),
        )
        assertEquals("HYPER SLASH超级斩", synced(raw).credits!!.namesFor(LyricCreditRole.Arranger))
    }
}
