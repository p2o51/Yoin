package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.local.AlbumRating
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumNeoDbSyncTest {

    private fun row(rating: Float = 0f, review: String? = null, ratingDirty: Boolean = false, reviewDirty: Boolean = false) =
        AlbumRating(
            albumId = "al-1",
            rating = rating,
            review = review,
            neoDbReviewUuid = null,
            ratingNeedsSync = ratingDirty,
            reviewNeedsSync = reviewDirty,
        )

    @Test
    fun should_offerSignIn_when_noNeoDbAccount() {
        assertEquals(AlbumNeoDbSync.SignedOut, albumNeoDbSync(configured = false, row(8f, ratingDirty = true)))
        assertEquals("Sign in to sync", albumNeoDbLabel(AlbumNeoDbSync.SignedOut))
    }

    @Test
    fun should_waitForTheClose_when_aChangeIsNotSentYet() {
        // Rated before signing in counts too: it goes on the next close.
        assertEquals(AlbumNeoDbSync.Pending, albumNeoDbSync(configured = true, row(8f, ratingDirty = true)))
        assertEquals(AlbumNeoDbSync.Pending, albumNeoDbSync(configured = true, row(review = "", reviewDirty = true)))
    }

    @Test
    fun should_readSynced_when_theRowIsCleanAndHasSomething() {
        assertEquals(AlbumNeoDbSync.Synced, albumNeoDbSync(configured = true, row(7f)))
        assertEquals(AlbumNeoDbSync.Synced, albumNeoDbSync(configured = true, row(review = "good")))
    }

    @Test
    fun should_readIdle_when_nothingIsWrittenYet() {
        assertEquals(AlbumNeoDbSync.Idle, albumNeoDbSync(configured = true, null))
        assertEquals(AlbumNeoDbSync.Idle, albumNeoDbSync(configured = true, row()))
    }
}
