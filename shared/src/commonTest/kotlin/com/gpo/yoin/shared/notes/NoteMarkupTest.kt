package com.gpo.yoin.shared.notes

import com.gpo.yoin.shared.notes.NoteBlock.Bullet
import com.gpo.yoin.shared.notes.NoteBlock.Checklist
import com.gpo.yoin.shared.notes.NoteBlock.Heading
import com.gpo.yoin.shared.notes.NoteBlock.Paragraph
import com.gpo.yoin.shared.notes.NoteInline.Bold
import com.gpo.yoin.shared.notes.NoteInline.Italic
import com.gpo.yoin.shared.notes.NoteInline.Mention
import com.gpo.yoin.shared.notes.NoteInline.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteMarkupTest {

    private val andromeda = NoteMention(
        kind = MentionKind.Track,
        provider = "applemusic",
        id = "1445555555",
        title = "Andromeda",
        artist = "Weyes Blood",
        album = "Titanic Rising",
    )

    @Test
    fun should_writeTheOwnersFormat_when_serializingAMention() {
        assertEquals(
            "[@Andromeda](yoin:track/applemusic:1445555555?a=Weyes%20Blood&al=Titanic%20Rising)",
            NoteMarkup.serializeMention(andromeda),
        )
    }

    @Test
    fun should_escapeBracketsAndEncodeValues_when_titlesCarryParenthesesAndBrackets() {
        val mention = NoteMention(
            kind = MentionKind.Album,
            provider = "spotify",
            id = "4aawyAB9vmqN3uQ7FjRGTy",
            title = "Detour [Rare N’ Deluxe] (Live)",
            artist = "Kim Petras & Friends",
            album = null,
        )
        val link = NoteMarkup.serializeMention(mention)
        assertEquals(
            "[@Detour \\[Rare N’ Deluxe\\] (Live)](yoin:album/spotify:4aawyAB9vmqN3uQ7FjRGTy?a=Kim%20Petras%20%26%20Friends)",
            link,
        )
        assertEquals(listOf(mention), NoteMarkup.mentions("see $link here"))
    }

    @Test
    fun should_keepAppleLibraryColons_and_encodeAwkwardIds() {
        val library = andromeda.copy(id = "library:l.AbC")
        assertTrue(NoteMarkup.serializeMention(library).contains("applemusic:library:l.AbC?"))
        val awkward = andromeda.copy(provider = "subsonic", id = "a/b?c)d e&f%")
        val link = NoteMarkup.serializeMention(awkward)
        assertEquals(listOf(awkward), NoteMarkup.mentions(link))
        assertFalse(link.substringAfter("(").dropLast(1).contains(')'))
    }

    @Test
    fun should_roundTripUnicodeAndBackslashes_inMentionWords() {
        val mention = NoteMention(MentionKind.Artist, "subsonic", "ar-1", "C:\\Users [x] 苏打绿 🎧", artist = "a=b&c", album = "100%")
        val text = "前 " + NoteMarkup.serializeMention(mention) + " 后"
        assertEquals(listOf(mention), NoteMarkup.mentions(text))
        assertEquals(text, NoteMarkup.serialize(NoteMarkup.parse(text)))
    }

    @Test
    fun should_parseBlocksAndInlines_when_readingThePrototypesNotes() {
        val text = "# 现场版要记得\n- [x] 开头那段哼唱\n- [ ] 第二段副歌的和声\n- 一条\n**这一句**每次都会跟着唱 _轻_"
        val doc = NoteMarkup.parse(text)
        assertEquals(
            listOf(
                Heading(1, listOf(Text("现场版要记得"))),
                Checklist(true, listOf(Text("开头那段哼唱"))),
                Checklist(false, listOf(Text("第二段副歌的和声"))),
                Bullet(listOf(Text("一条"))),
                Paragraph(listOf(Bold(listOf(Text("这一句"))), Text("每次都会跟着唱 "), Italic(listOf(Text("轻"))))),
            ),
            doc.blocks,
        )
        assertEquals(text, NoteMarkup.serialize(doc))
    }

    @Test
    fun should_readTheMentionAsAToken_when_itSitsInsideBold() {
        val link = NoteMarkup.serializeMention(andromeda)
        val doc = NoteMarkup.parse("**像 $link 一样**")
        assertEquals(
            listOf(Paragraph(listOf(Bold(listOf(Text("像 "), Mention(andromeda), Text(" 一样")))))),
            doc.blocks,
        )
        assertEquals(listOf(andromeda), doc.mentions)
    }

    @Test
    fun should_readThePrototypeSpelling_when_kindIsSongAndValuesAreRaw() {
        val doc = NoteMarkup.parse("后来在 [@Andromeda](yoin:song/applemusic:weyes.2) 里")
        val mention = doc.mentions.single()
        assertEquals(MentionKind.Track, mention.kind)
        assertEquals("weyes.2", mention.id)
        assertEquals(null, mention.artist)
        val raw = NoteMarkup.mentions("[@X](yoin:album/spotify:1?a=Weyes Blood&al=&zz=1)").single()
        assertEquals("Weyes Blood", raw.artist)
        assertEquals(null, raw.album)
    }

    @Test
    fun should_fallBackToText_when_inputIsMalformed() {
        val broken = listOf(
            "[@](yoin:track/spotify:1)",
            "[@Title](yoin:track/spotify:)",
            "[@Title](yoin:movie/spotify:1)",
            "[@Title](yoin:track/spo tify:1)",
            "[@Title](yoin:track/spotify:1",
            "[@Title](https://example.com)",
            "[@Title] (yoin:track/spotify:1)",
            "[Title](yoin:track/spotify:1)",
            "[@Tit\nle](yoin:track/spotify:1)",
            "**unclosed",
            "a * b * c",
            "snake_case_name",
            "2*3*4",
            "_ spaced _",
            "#nospace",
            "#### four",
            "-dash",
            "%E4%B8%AD 100% %zz",
        )
        broken.forEach { text ->
            val doc = NoteMarkup.parse(text)
            assertTrue(doc.mentions.isEmpty(), text)
            assertEquals(text, NoteMarkup.serialize(doc), text)
            assertEquals(text.replace("\n", " "), NoteMarkup.plainText(text, " "), text)
        }
        // Not a to-do: a list item whose words start with brackets.
        assertEquals(listOf(Bullet(listOf(Text("[y] not a box")))), NoteMarkup.parse("- [y] not a box").blocks)
        assertEquals(listOf(Bullet(listOf(Text("[ ]x")))), NoteMarkup.parse("- [ ]x").blocks)
        // A lone backslash in a hand-written title stays a backslash.
        assertEquals("a\\b", NoteMarkup.mentions("[@a\\b](yoin:artist/spotify:1)").single().title)
        assertEquals("[@a\\b](yoin:artist/spotify:1)", NoteMarkup.serialize(NoteMarkup.parse("[@a\\b](yoin:artist/spotify:1)")))
    }

    @Test
    fun should_roundTripExactly_when_textMixesEverything() {
        val link = NoteMarkup.serializeMention(andromeda)
        val corpus = listOf(
            "",
            "\n\n",
            "plain words",
            "  leading spaces and trailing  ",
            "# h\n## h2\n### h3\n* star item\n• dot item\n- [X] Upper done\n- [ ] ",
            "*star italic* and _under italic_ and **bold _nested_ $link**",
            "***x***",
            "__x__",
            "**a*b**",
            "[@not closed $link",
            "$link$link",
            "_${link}_",
            "a\r\nb",
            "- $link\n- [x] $link done",
        )
        corpus.forEach { text -> assertEquals(text, NoteMarkup.serialize(NoteMarkup.parse(text)), text) }
    }

    @Test
    fun should_reparseToTheSameDocument_when_serializingABuiltDocument() {
        val doc = NoteDocument(
            listOf(
                Heading(2, listOf(Text("Side A"))),
                Checklist(false, listOf(Text("listen to "), Mention(andromeda))),
                Bullet(listOf(Bold(listOf(Text("best"))), Text(" "), Italic(listOf(Text("quiet"))))),
                Paragraph(emptyList()),
                Paragraph(listOf(Mention(andromeda.copy(kind = MentionKind.Artist, title = "Weyes Blood", album = null)))),
            ),
        )
        assertEquals(doc, NoteMarkup.parse(NoteMarkup.serialize(doc)))
    }

    @Test
    fun should_flattenMentionsToNames_and_keepMarkdown_when_projectingForNeoDb() {
        val link = NoteMarkup.serializeMention(andromeda)
        val review = "# 天堂\n- 吉他像雾\n**后来**在 $link 里又听到了同样的温度。"
        assertEquals("# 天堂\n- 吉他像雾\n**后来**在 Andromeda 里又听到了同样的温度。", NoteMarkup.toNeoDb(review))
        assertFalse(NoteMarkup.toNeoDb(review).contains("yoin:"))
        assertEquals("no mentions **kept**", NoteMarkup.toNeoDb("no mentions **kept**"))
    }

    @Test
    fun should_dropMarksAndKeepNames_when_projectingToPlainText() {
        val link = NoteMarkup.serializeMention(andromeda)
        val text = "# 标题\n- [x] **做完** $link\n_斜_"
        assertEquals("标题\n做完 Andromeda\n斜", NoteMarkup.plainText(text))
        assertEquals("标题 · 做完 Andromeda · 斜", NoteMarkup.snippet("$text\n\n  "))
    }

    @Test
    fun should_rewriteOnlyMappedMentions_and_leaveEverythingElse() {
        val other = andromeda.copy(id = "999", title = "Movies")
        val text = "  ${NoteMarkup.serializeMention(andromeda)} & [@Movies](yoin:song/applemusic:999) **x**  "
        val mapped = NoteMarkup.rewriteMentions(
            text,
            mapOf(andromeda.ref to MentionRef(MentionKind.Track, "spotify", "sp1")),
        )
        assertEquals(
            "  [@Andromeda](yoin:track/spotify:sp1?a=Weyes%20Blood&al=Titanic%20Rising) & [@Movies](yoin:song/applemusic:999) **x**  ",
            mapped,
        )
        assertEquals(text, NoteMarkup.rewriteMentions(text) { null })
        assertEquals(text, NoteMarkup.rewriteMentions(text, emptyMap()))
        assertEquals(listOf(andromeda.ref, other.ref), NoteMarkup.mentions(text).map(NoteMention::ref))
    }

    @Test
    fun should_findTheSameMentionsAsTheParser_when_scanningSpans() {
        val link = NoteMarkup.serializeMention(andromeda)
        val texts = listOf("[@a [@b]$link", "**$link** _${link}_", "x\n$link\n[@y](yoin:track/s:1")
        texts.forEach { text ->
            assertEquals(NoteMarkup.parse(text).mentions, NoteMarkup.mentions(text), text)
            NoteMarkup.mentionSpans(text).forEach { span ->
                assertEquals(span.mention, NoteMarkup.mentions(text.substring(span.start, span.end)).single())
            }
        }
    }

    @Test
    fun should_roundTripRandomText_when_builtFromMarkupFragments() {
        val link = NoteMarkup.serializeMention(andromeda)
        val pieces = listOf("a", " ", "*", "**", "_", "[", "]", "(", ")", "@", "[@", "#", "# ", "- ", "- [ ] ", "\n", "中", link, "\\", "yoin:")
        var seed = 7L
        repeat(500) {
            val text = buildString {
                repeat(12) {
                    seed = (seed * 6364136223846793005L + 1442695040888963407L)
                    append(pieces[((seed ushr 33) % pieces.size).toInt()])
                }
            }
            if (!text.lineSequence().any { it == "- [ ]" || it == "* [ ]" || it == "- [x]" }) {
                assertEquals(text, NoteMarkup.serialize(NoteMarkup.parse(text)), text)
            }
        }
    }

    @Test
    fun should_italicise_when_cjkTextOrAsciiLettersSitAgainstTheMarkers() {
        listOf(
            "听到_副歌_的时候" to '_',
            "听到*副歌*的时候" to '*',
            "a*b*" to '*',
            "**粗**和_斜_" to '_',
        ).forEach { (text, marker) ->
            val doc = NoteMarkup.parse(text)
            val italic = doc.blocks.single().inlines.filterIsInstance<Italic>().single()
            assertEquals(marker, italic.marker, text)
            assertEquals(text, NoteMarkup.serialize(doc), text)
        }
        assertEquals(
            listOf(Paragraph(listOf(Text("听到"), Italic(listOf(Text("副歌"))), Text("的时候")))),
            NoteMarkup.parse("听到_副歌_的时候").blocks,
        )
        // ASCII guards stay: snake_case and arithmetic are words, not italics.
        listOf("snake_case_name", "2*3*4", "x_1_", "a_b_c").forEach { text ->
            assertTrue(NoteMarkup.parse(text).blocks.single().inlines.none { it is Italic }, text)
        }
    }

    @Test
    fun should_fallBackToStar_when_underscoreItalicSitsInsideAnAsciiWord() {
        val doc = NoteDocument(
            listOf(Paragraph(listOf(Text("word"), Italic(listOf(Text("ital"))), Text("ic and 中"), Italic(listOf(Text("文")))))),
        )
        val text = NoteMarkup.serialize(doc)
        assertEquals("word*ital*ic and 中_文_", text)
        assertEquals(
            listOf(Text("word"), Italic(listOf(Text("ital")), '*'), Text("ic and 中"), Italic(listOf(Text("文")))),
            NoteMarkup.parse(text).blocks.single().inlines,
        )
    }

    @Test
    fun should_normaliseABareEmptyToDo_when_reserialized() {
        assertEquals(listOf(Checklist(false, emptyList())), NoteMarkup.parse("- [ ]").blocks)
        assertEquals("- [ ] ", NoteMarkup.serialize(NoteMarkup.parse("- [ ]")))
    }

    @Test
    fun should_decodeUtf8_and_keepStrayPercents() {
        assertEquals("苏打绿 100% %zz", PercentCoding.decode("%E8%8B%8F%E6%89%93%E7%BB%BF 100% %zz"))
        assertEquals("a+b", PercentCoding.decode("a+b"))
        assertEquals("%E8%8B%8F%20%26", PercentCoding.encode("苏 &"))
    }
}
