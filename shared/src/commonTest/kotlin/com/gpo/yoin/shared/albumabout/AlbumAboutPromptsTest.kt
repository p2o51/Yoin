package com.gpo.yoin.shared.albumabout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Moved with the prompts from Android (app AlbumAboutPromptsTest); runs on the JVM and the iOS simulator. */
class AlbumAboutPromptsTest {

    private val grounding = AlbumAboutGrounding(
        album = "Tide Tables (10th Anniversary)",
        artist = "Morikawa",
        version = "10th Anniversary",
        releaseDate = "2024-03-08",
        originalReleaseDate = "2014-05-20",
        labels = listOf("Northern Lights Records"),
        releaseType = "Album",
        genre = "Folk",
        tracks = listOf("Dawn Chorus", "Glass House"),
        totalDurationSec = 2_460,
    )

    @Test
    fun should_groundThePromptInTheServicesFacts_when_built() {
        val prompt = YoinAlbumAbout.aboutPrompt(grounding, "English", "Use the search tool to find accurate information.")

        assertTrue(prompt.contains("Album: Tide Tables (10th Anniversary)"))
        assertTrue(prompt.contains("Artist: Morikawa"))
        assertTrue(prompt.contains("Edition: 10th Anniversary"))
        assertTrue(prompt.contains("This release's date: 2024-03-08"))
        assertTrue(prompt.contains("Original release date: 2014-05-20"))
        assertTrue(prompt.contains("Record label (from the service): Northern Lights Records"))
        assertTrue(prompt.contains("Tracks (2):\n1. Dawn Chorus\n2. Glass House"))
        assertTrue(prompt.contains("Use the search tool to find accurate information."))
        assertTrue(prompt.contains("[CREDITS]") && prompt.contains("[/CREDITS]"))
        assertTrue(prompt.contains("[ABOUT]") && prompt.contains("[/ABOUT]"))
        assertTrue(prompt.contains("Unknown = null"))
        assertTrue(prompt.contains("\"label\": null when the service already gave the record label above"))
        assertTrue(prompt.contains("120 to 160 words"))
    }

    @Test
    fun should_leaveUnknownFactsOut_when_theServiceDoesNotSay() {
        val lines = YoinAlbumAbout.groundingLines(AlbumAboutGrounding(album = "A", artist = "B"))

        assertEquals("Album: A\nArtist: B", lines)
    }

    @Test
    fun should_askForChineseCharacters_when_theAiLanguageIsChinese() {
        val prompt = YoinAlbumAbout.aboutPrompt(grounding, "Simplified Chinese", "lead")

        assertTrue(prompt.contains("One objective paragraph in Simplified Chinese, 180 to 240 Chinese characters"))
    }

    @Test
    fun should_capTheTrackList_when_theAlbumIsLong() {
        val long = grounding.copy(tracks = (1..55).map { "Track $it" })

        val lines = YoinAlbumAbout.groundingLines(long)

        assertTrue(lines.contains("Tracks (55):"))
        assertTrue(lines.contains("40. Track 40"))
        assertFalse(lines.contains("41. Track 41"))
        assertTrue(lines.endsWith("…"))
    }

    @Test
    fun should_askTheAlbumQuestion_withTheSameTaggedReplyAsSongs() {
        val prompt = YoinAlbumAbout.askPrompt(grounding, "Who played the organ?", "English", "lead")

        assertTrue(prompt.startsWith("Answer the following question about the album \"Tide Tables (10th Anniversary)\" by Morikawa."))
        assertTrue(prompt.contains("[TITLE]") && prompt.contains("[ANSWER]"))
        assertTrue(prompt.endsWith("Question: Who played the organ?"))
    }

    @Test
    fun should_parseEveryConfidentItem_when_theReplyIsWellFormed() {
        val reply = """
            [CREDITS]
            {
              "label": null,
              "producers": {"names": ["Kenji Arai", "Mei Lin"], "confidence": "high"},
              "mastering": {"names": ["Bob Ludwig"], "confidence": "medium"},
              "mixing": {"names": ["Someone"], "confidence": "HIGH"},
              "recorded": {"time": "Sep–Dec 2013", "studios": ["Studio Mare, Kamakura"], "confidence": "high"},
              "source": null,
              "first_released": {"date": "May 2014", "confidence": "high"}
            }
            [/CREDITS]
            [ABOUT]
            Tide Tables was Morikawa's second album.
            It was cut by the sea.
            [/ABOUT]
        """.trimIndent()

        val parsed = YoinAlbumAbout.parseReply(reply)

        assertEquals(
            AlbumAiCredits(
                producers = listOf("Kenji Arai", "Mei Lin"),
                mastering = listOf("Bob Ludwig"),
                mixing = listOf("Someone"),
                recordedTime = "Sep–Dec 2013",
                recordedStudios = listOf("Studio Mare, Kamakura"),
                firstReleased = "May 2014",
            ),
            parsed.credits,
        )
        assertEquals("Tide Tables was Morikawa's second album. It was cut by the sea.", parsed.blurb)
    }

    @Test
    fun should_dropLowAndMissingConfidence_when_parsing() {
        val credits = YoinAlbumAbout.parseCredits(
            """{"producers": {"names": ["Guess"], "confidence": "low"},
               "mastering": {"names": ["No confidence"]},
               "label": {"names": ["ECM"], "confidence": "high"}}""",
        )

        assertEquals(AlbumAiCredits(label = listOf("ECM")), credits)
    }

    @Test
    fun should_dropPlaceholders_when_valuesSayUnknown() {
        val credits = YoinAlbumAbout.parseCredits(
            """{"producers": {"names": ["N/A", "unknown", " ", "Manfred Eicher", "Manfred Eicher"], "confidence": "high"},
               "mastering": {"names": ["未知"], "confidence": "high"},
               "recorded": {"time": "不详", "studios": [], "confidence": "high"},
               "source": {"work": "None", "confidence": "high"},
               "first_released": {"date": null, "confidence": "high"}}""",
        )

        assertEquals(AlbumAiCredits(producers = listOf("Manfred Eicher")), credits)
        assertFalse(credits.isEmpty)
    }

    @Test
    fun should_readTheJson_when_fencedOrSurroundedByProse() {
        val credits = YoinAlbumAbout.parseCredits(
            "Here you go:\n```json\n{\"producers\": {\"names\": \"A、B\", \"confidence\": \"high\"},}\n```",
        )

        assertEquals(listOf("A", "B"), credits.producers)
    }

    @Test
    fun should_returnNoCredits_when_theJsonIsBrokenOrMissing() {
        assertEquals(AlbumAiCredits.None, YoinAlbumAbout.parseCredits("{\"producers\": "))
        assertEquals(AlbumAiCredits.None, YoinAlbumAbout.parseCredits(null))
        assertEquals(AlbumAiCredits.None, YoinAlbumAbout.parseReply("no tags at all").credits)
        assertNull(YoinAlbumAbout.parseReply("no tags at all").blurb)
    }

    @Test
    fun should_keepOnlyTheFirstNames_when_aModelListsTooMany() {
        val credits = YoinAlbumAbout.parseCredits(
            """{"producers": {"names": ["1", "2", "3", "4", "5", "6"], "confidence": "high"}}""",
        )

        assertEquals(YoinAlbumAbout.maxNames, credits.producers.size)
    }

    @Test
    fun should_giveNoParagraph_when_theAiWritesNA() {
        assertNull(YoinAlbumAbout.parseReply("[CREDITS]{}[/CREDITS][ABOUT]N/A[/ABOUT]").blurb)
    }

    @Test
    fun should_foldChineseLinesWithoutSpaces_when_cleaningTheParagraph() {
        assertEquals("第一句。第二句。", YoinAlbumAbout.cleanBlurb("第一句。\n第二句。"))
        assertEquals("Bold text here.", YoinAlbumAbout.cleanBlurb("**Bold** text\nhere."))
    }

    @Test
    fun should_readATruncatedReply_when_theClosingTagIsMissing() {
        val parsed = YoinAlbumAbout.parseReply("[CREDITS]{}[/CREDITS]\n[ABOUT]\nCut off mid")

        assertEquals("Cut off mid", parsed.blurb)
    }

    @Test
    fun should_askForTheCreditsOnly_when_theServiceWroteTheNotes() {
        val withNotes = grounding.copy(serviceNotes = "Apple's own words about the album.")

        val prompt = YoinAlbumAbout.aboutPrompt(withNotes, "English", "lead")

        assertTrue(prompt.contains("[CREDITS]") && prompt.contains("[/CREDITS]"))
        assertFalse(prompt.contains("[ABOUT]"))
        assertTrue(prompt.contains("Album notes (from the service): Apple's own words about the album."))
        assertTrue(prompt.contains("Respond strictly with the tagged block below"))
    }

    @Test
    fun should_capTheServicesNotes_inThePrompt() {
        val lines = YoinAlbumAbout.groundingLines(grounding.copy(serviceNotes = "x".repeat(5_000)))

        val notes = lines.lines().single { it.startsWith("Album notes (from the service): ") }
        assertEquals(YoinAlbumAbout.maxNotesChars, notes.removePrefix("Album notes (from the service): ").length)
    }

    @Test
    fun should_tellAnEmptyAnswer_fromNoAnswer() {
        // The model answered in the format and knew nothing: kept as done.
        assertTrue(YoinAlbumAbout.parseReply("[CREDITS]{}[/CREDITS]\n[ABOUT]N/A[/ABOUT]").answered)
        assertTrue(YoinAlbumAbout.parseReply("[CREDITS]\n{\"producers\": null}\n[/CREDITS]").answered)
        // Not an answer (refused, off-format, empty): a failure, asked again next time.
        assertFalse(YoinAlbumAbout.parseReply("I'm sorry, I can't help with that.").answered)
        assertFalse(YoinAlbumAbout.parseReply("").answered)
    }

    @Test
    fun should_dropTheAisParagraph_when_theServiceWroteTheNotes() {
        val reply = YoinAlbumAbout.storedReply("[CREDITS]{}[/CREDITS]\n[ABOUT]An AI paragraph.[/ABOUT]", hasServiceNotes = true)

        assertNull(reply.blurb)
        assertTrue(reply.answered)
        assertTrue(reply.isEmpty)
    }

    @Test
    fun should_leadTheSearch_byWhereTheFactsComeFrom() {
        assertEquals("Use the search tool to find accurate information.", YoinAlbumAbout.searchLead(true, 0))
        assertEquals("Use the search results provided below to find accurate information.", YoinAlbumAbout.searchLead(false, 2))
        assertTrue(YoinAlbumAbout.searchLead(false, 0).startsWith("Answer from what you reliably know"))
    }

    @Test
    fun should_keyAQuestion_likeTheSongAbout() {
        assertEquals("fake love", YoinAlbumAbout.questionKey("  Fake   Love (feat. X) "))
    }
}
