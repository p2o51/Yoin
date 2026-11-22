package com.gpo.yoin.data.integration.neodb

import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.AlbumRatingDao
import com.gpo.yoin.data.local.ExternalMapping
import com.gpo.yoin.data.local.ExternalMappingDao
import com.gpo.yoin.data.local.NeoDBConfig
import com.gpo.yoin.data.local.NeoDBConfigDao
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner R2 (2026-10-06): Yoin's one album text goes to NeoDB as the mark's
 * short comment up to [NeoDbShortCommentMax] characters, as a Review beyond —
 * and the slot it doesn't use is cleared.
 */
class NeoDBCommentRoutingTest {

    private val album = Album(
        id = MediaId.subsonic("al-1"),
        name = "Lantern Letters",
        artist = "月光邮局",
        artistId = null,
        coverArt = null,
        songCount = 5,
        durationSec = 840,
        year = 2011,
        genre = null,
    )

    private val api = mockk<NeoDBApi>()
    private val configDao = mockk<NeoDBConfigDao>()
    private val tokenStore = mockk<NeoDbTokenStore>()
    private val mappingDao = mockk<ExternalMappingDao>()
    private val ratingDao = mockk<AlbumRatingDao>()
    private val service = NeoDBSyncService(api, configDao, tokenStore, mappingDao, ratingDao)
    private val mark = slot<ShelfMarkRequest>()

    private fun given(local: AlbumRating, remote: ShelfItem? = ShelfItem(commentText = "from the web", ratingGrade = 7)) {
        coEvery { configDao.get() } returns NeoDBConfig()
        every { tokenStore.readToken() } returns "token"
        coEvery { mappingDao.findForYoinEntity(any(), any(), any(), any()) } returns ExternalMapping(
            externalService = ExternalMapping.SERVICE_NEODB,
            externalId = "uuid-1",
            provider = MediaId.PROVIDER_SUBSONIC,
            entityType = ExternalMapping.ENTITY_ALBUM,
            entityId = "al-1",
        )
        coEvery { ratingDao.get("al-1", MediaId.PROVIDER_SUBSONIC, "p") } returns local
        coEvery { ratingDao.upsert(any()) } just runs
        coEvery { ratingDao.markRatingPushed(any(), any(), any(), any(), any()) } returns 1
        coEvery { ratingDao.markReviewPushed(any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { api.getShelfItem(any(), any(), "uuid-1") } returns remote
        coEvery { api.postShelfMark(any(), any(), "uuid-1", capture(mark)) } returns Unit
        coEvery { api.deleteReview(any(), any(), "uuid-1") } returns Unit
        coEvery { api.postReview(any(), any(), "uuid-1", any()) } returns ReviewResponse(uuid = "r-1")
    }

    private fun row(rating: Float, review: String?, ratingDirty: Boolean, reviewDirty: Boolean) = AlbumRating(
        profileId = "p",
        albumId = "al-1",
        rating = rating,
        review = review,
        neoDbReviewUuid = null,
        ratingNeedsSync = ratingDirty,
        reviewNeedsSync = reviewDirty,
    )

    @Test
    fun should_sendAShortTextAsTheMarksComment_when_itFits() = runTest {
        given(row(8.4f, "副歌那句一出来，整个下午都亮了。", ratingDirty = true, reviewDirty = true))

        assertTrue(service.pushAlbum("p", album).isSuccess)

        assertEquals("副歌那句一出来，整个下午都亮了。", mark.captured.commentText)
        assertEquals(8, mark.captured.ratingGrade)
        coVerify(exactly = 1) { api.deleteReview(any(), any(), "uuid-1") }
        coVerify(exactly = 0) { api.postReview(any(), any(), any(), any()) }
        // The flags clear only for what was sent (an edit made meanwhile stays dirty), never by upserting the snapshot.
        coVerify(exactly = 1) {
            ratingDao.markReviewPushed("al-1", MediaId.PROVIDER_SUBSONIC, "p", "副歌那句一出来，整个下午都亮了。", null, any())
        }
        coVerify(exactly = 1) { ratingDao.markRatingPushed("al-1", MediaId.PROVIDER_SUBSONIC, "p", 8.4f, any()) }
        coVerify(exactly = 0) { ratingDao.upsert(any()) }
    }

    @Test
    fun should_postAReviewAndClearTheComment_when_theTextIsLong() = runTest {
        val long = "长".repeat(NeoDbShortCommentMax + 1)
        given(row(0f, long, ratingDirty = false, reviewDirty = true))

        assertTrue(service.pushAlbum("p", album).isSuccess)

        assertEquals("", mark.captured.commentText)
        // The rating was not touched here: NeoDB's own grade stays.
        assertEquals(7, mark.captured.ratingGrade)
        coVerify(exactly = 1) { api.postReview(any(), any(), "uuid-1", match { it.body == long }) }
        coVerify(exactly = 0) { api.deleteReview(any(), any(), any()) }
    }

    @Test
    fun should_keepTheWebsComment_when_onlyTheRatingChanged() = runTest {
        given(row(9f, "anything", ratingDirty = true, reviewDirty = false))

        assertTrue(service.pushAlbum("p", album).isSuccess)

        assertEquals("from the web", mark.captured.commentText)
        assertEquals(9, mark.captured.ratingGrade)
        coVerify(exactly = 0) { api.postReview(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.deleteReview(any(), any(), any()) }
        coVerify(exactly = 1) { ratingDao.markRatingPushed("al-1", MediaId.PROVIDER_SUBSONIC, "p", 9f, any()) }
        coVerify(exactly = 0) { ratingDao.markReviewPushed(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_pullTheShortComment_when_thereIsNoReview() = runTest {
        given(row(0f, null, ratingDirty = false, reviewDirty = false))
        coEvery { api.getMyReviewForItem(any(), any(), "uuid-1") } returns null

        val pulled = service.pullAlbum("p", album).getOrThrow()

        assertEquals("from the web", pulled?.review)
        assertEquals(7f, pulled?.rating)
    }

    @Test
    fun should_countCharactersNotBytes_when_judgingAShortComment() {
        assertTrue(isNeoDbShortComment("音".repeat(NeoDbShortCommentMax)))
        assertFalse(isNeoDbShortComment("音".repeat(NeoDbShortCommentMax + 1)))
        // An emoji is one character, two UTF-16 units.
        assertTrue(isNeoDbShortComment("🎧".repeat(NeoDbShortCommentMax)))
    }

    // ── formatting and mentions (notes revamp) ─────────────────────

    private val mentionReview = "# 天堂\n后来在 **[@Andromeda](yoin:track/applemusic:1445?a=Weyes%20Blood)** 里又听到了。"
    private val mentionReviewOnNeoDb = "# 天堂\n后来在 **Andromeda** 里又听到了。"
    private val mentionReviewPlain = "天堂\n后来在 Andromeda 里又听到了。"

    @Test
    fun should_sendPlainWords_when_theReviewIsAShortComment() = runTest {
        given(row(0f, mentionReview, ratingDirty = false, reviewDirty = true))

        assertTrue(service.pushAlbum("p", album).isSuccess)

        // short comments don't render Markdown: no marks, no links
        assertEquals(mentionReviewPlain, mark.captured.commentText)
        // the dirty flag clears against the words as stored, mentions and all
        coVerify(exactly = 1) {
            ratingDao.markReviewPushed("al-1", MediaId.PROVIDER_SUBSONIC, "p", mentionReview, null, any())
        }
    }

    @Test
    fun should_keepMarkdownWithMentionsAsNames_when_theReviewIsLong() = runTest {
        val long = mentionReview + "长".repeat(NeoDbShortCommentMax)
        given(row(0f, long, ratingDirty = false, reviewDirty = true))

        assertTrue(service.pushAlbum("p", album).isSuccess)

        assertEquals("", mark.captured.commentText)
        coVerify(exactly = 1) {
            api.postReview(any(), any(), "uuid-1", match { it.body == mentionReviewOnNeoDb + "长".repeat(NeoDbShortCommentMax) })
        }
    }

    @Test
    fun should_judgeTheLengthOnThePlainWords_when_mentionLinksAreLong() = runTest {
        // Long only because of its link: NeoDB sees a short comment.
        val link = "[@A](yoin:album/spotify:${"x".repeat(NeoDbShortCommentMax)})"
        given(row(0f, "听 $link", ratingDirty = false, reviewDirty = true))

        assertTrue(service.pushAlbum("p", album).isSuccess)

        assertEquals("听 A", mark.captured.commentText)
        coVerify(exactly = 0) { api.postReview(any(), any(), any(), any()) }
    }

    @Test
    fun should_keepTheLocalText_when_theShortCommentIsItsPlainWords() = runTest {
        val local = row(0f, mentionReview, ratingDirty = false, reviewDirty = false).copy(reviewUpdatedAt = 5L)
        given(local, remote = ShelfItem(commentText = mentionReviewPlain, ratingGrade = null))
        coEvery { api.getMyReviewForItem(any(), any(), "uuid-1") } returns null

        val pulled = service.pullAlbum("p", album).getOrThrow()

        assertEquals(mentionReview, pulled?.review)
        assertEquals(5L, pulled?.reviewUpdatedAt)
    }

    @Test
    fun should_keepFormattingOnlyText_when_neoDbHoldsItsProjection() = runTest {
        // no mentions at all: formatting alone must survive a pull too
        val formatted = "**最好**的一张\n- 第五首"
        given(
            row(0f, formatted, ratingDirty = false, reviewDirty = false),
            remote = ShelfItem(commentText = "最好的一张\n第五首", ratingGrade = null),
        )
        coEvery { api.getMyReviewForItem(any(), any(), "uuid-1") } returns null

        assertEquals(formatted, service.pullAlbum("p", album).getOrThrow()?.review)
    }

    @Test
    fun should_takeTheRemoteWords_when_theyWereEditedOnNeoDb() = runTest {
        given(
            row(0f, mentionReview, ratingDirty = false, reviewDirty = false),
            remote = ShelfItem(commentText = "$mentionReviewPlain 网页上补了一句。", ratingGrade = null),
        )
        coEvery { api.getMyReviewForItem(any(), any(), "uuid-1") } returns null

        val pulled = service.pullAlbum("p", album).getOrThrow()

        assertEquals("$mentionReviewPlain 网页上补了一句。", pulled?.review)
    }

    @Test
    fun should_compareReviewBodiesWithTheMarkdownProjection_andCommentsWithThePlainWords() {
        assertTrue(neoDbHoldsLocalReview(mentionReview, "  $mentionReviewOnNeoDb\n", asShortComment = false))
        assertFalse(neoDbHoldsLocalReview(mentionReview, mentionReviewPlain, asShortComment = false))
        assertTrue(neoDbHoldsLocalReview(mentionReview, mentionReviewPlain, asShortComment = true))
        assertFalse(neoDbHoldsLocalReview(mentionReview, mentionReviewOnNeoDb, asShortComment = true))
        assertTrue(neoDbHoldsLocalReview("plain", "plain", asShortComment = true))
        assertFalse(neoDbHoldsLocalReview(null, "x", asShortComment = true))
        assertEquals("", neoDbReviewText(null))
        assertEquals("", neoDbShortCommentText(null))
    }
}
