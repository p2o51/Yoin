package com.gpo.yoin.data.repository

import com.gpo.yoin.data.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heart's merge rules (P4): a write in flight wins; a landed write holds
 * for the grace, and only within it counts as the user's; past it the newest
 * of the service's answer, the write and the mirror row wins; the track's own
 * flag only when nothing else is known.
 */
class FavoriteStateOverlayTest {

    private var now = 1_000_000L
    private val overlay = FavoriteStateOverlay(clock = { now })
    private val key = FavoriteStateOverlay.Key("profile", MediaId.spotify("t1"))

    @Test
    fun should_followTheWriteInFlight_when_answersSayOtherwise() {
        overlay.recordRemote(key, saved = false)
        val answeredAt = now

        val state = resolve(baseline = false, inFlight = true, mirrorSaved = false, mirrorAtMs = now)

        // The answer's time rides along: a reader can tell it from a new one.
        assertEquals(FavoriteState(isStarred = true, fromUser = true, answeredAtMs = answeredAt), state)
    }

    @Test
    fun should_keepTheLike_when_anAnswerInsideTheGraceSaysNotSaved() {
        overlay.recordWrite(key, saved = true)
        now += 10_000L
        // Spotify hasn't applied the like yet.
        overlay.recordRemote(key, saved = false)

        assertEquals(FavoriteState(isStarred = true, fromUser = true, answeredAtMs = now), resolve(baseline = false))
    }

    @Test
    fun should_followTheAnswer_when_itCameAfterTheGrace() {
        overlay.recordWrite(key, saved = true)
        now += FAVORITE_WRITE_GRACE_MS + 1_000L
        // Unliked in the Spotify app since.
        overlay.recordRemote(key, saved = false)

        assertEquals(answer(isStarred = false, atMs = now), resolve(baseline = true))
    }

    @Test
    fun should_stayUnliked_when_theTracksCopyStillSaysLiked() {
        // An unlike is an explicit false: the queue's copy of the track (isStarred = true) can't bring it back.
        overlay.recordWrite(key, saved = false)
        now += 5 * FAVORITE_WRITE_GRACE_MS

        // Long past the grace it still holds, but no longer as the user's tap.
        assertEquals(FavoriteState(isStarred = false, fromUser = false), resolve(baseline = true))
    }

    @Test
    fun should_stopCountingAsTheUsersWrite_when_theGraceIsOver() {
        overlay.recordWrite(key, saved = true)

        now += FAVORITE_WRITE_GRACE_MS - 1
        assertEquals(FavoriteState(isStarred = true, fromUser = true), resolve(baseline = false))
        now += 1
        assertEquals(FavoriteState(isStarred = true, fromUser = false), resolve(baseline = false))
    }

    @Test
    fun should_preferTheNewerOfAnswerAndMirror_when_bothAreKnown() {
        overlay.recordRemote(key, saved = false)
        // A later sync wrote the track into the mirror: liked in the Spotify app meanwhile.
        val newerMirror = resolve(baseline = false, mirrorSaved = true, mirrorAtMs = now + 1)
        assertEquals(FavoriteState(isStarred = true, answeredAtMs = now), newerMirror)
        // The mirror row is older than the answer: the answer wins.
        val olderMirror = resolve(baseline = true, mirrorSaved = true, mirrorAtMs = now - 1)
        assertEquals(answer(isStarred = false, atMs = now), olderMirror)
    }

    @Test
    fun should_useTheTracksFlag_when_nothingElseIsKnown() {
        assertEquals(FavoriteState(isStarred = true), resolve(baseline = true, entry = null))
        assertEquals(FavoriteState(isStarred = false), resolve(baseline = false, entry = null))
    }

    @Test
    fun should_knowNothing_when_noWriteAnswerOrMirrorRowIsThere() {
        // An album has no flag of its own: nothing learned is "unknown", not "not saved".
        assertNull(resolveLearnedFavoriteState(null, null, mirrorSaved = null, mirrorAtMs = 0L, nowMs = now))
    }

    @Test
    fun should_knowTheState_when_anyOneOfWriteAnswerOrMirrorRowIsThere() {
        overlay.recordRemote(key, saved = false)
        val answered = overlay.entries.value[key]

        assertEquals(
            answer(isStarred = false, atMs = now),
            resolveLearnedFavoriteState(null, answered, mirrorSaved = null, mirrorAtMs = 0L, nowMs = now)
        )
        assertEquals(
            FavoriteState(isStarred = true),
            resolveLearnedFavoriteState(null, null, mirrorSaved = true, mirrorAtMs = now, nowMs = now)
        )
        assertEquals(
            FavoriteState(isStarred = true, fromUser = true),
            resolveLearnedFavoriteState(true, null, mirrorSaved = null, mirrorAtMs = 0L, nowMs = now)
        )
    }

    @Test
    fun should_askOncePerInterval_when_claimedRepeatedly() {
        val other = key.copy(trackId = MediaId.spotify("t2"))

        assertEquals(listOf(key), overlay.claimAsks(listOf(key, key), minIntervalMs = 30_000L))
        now += 29_000L
        assertEquals(listOf(other), overlay.claimAsks(listOf(key, other), minIntervalMs = 30_000L))
        now += 1_000L
        assertEquals(listOf(key), overlay.claimAsks(listOf(key, other), minIntervalMs = 30_000L))
    }

    @Test
    fun should_forgetEverything_when_cleared() {
        overlay.recordRemote(key, saved = true)
        overlay.claimAsks(listOf(key), minIntervalMs = 30_000L)

        overlay.clear()

        assertTrue(overlay.entries.value.isEmpty())
        assertEquals(listOf(key), overlay.claimAsks(listOf(key), minIntervalMs = 30_000L))
    }

    @Test
    fun should_dropTheLongestUntouched_when_overFull() {
        val small = FavoriteStateOverlay(clock = { now }, maxEntries = 10)
        repeat(11) { i ->
            now += 1
            small.recordRemote(key.copy(trackId = MediaId.spotify("t$i")), saved = true)
        }

        val kept = small.entries.value.keys.map { it.trackId.rawId }.toSet()
        assertEquals(9, kept.size)
        assertTrue("t10" in kept && "t0" !in kept && "t1" !in kept)
    }

    /** The service's answer to a check, which came in at [atMs]. */
    private fun answer(isStarred: Boolean, atMs: Long) =
        FavoriteState(isStarred = isStarred, fromAnswer = true, answeredAtMs = atMs)

    private fun resolve(
        baseline: Boolean,
        inFlight: Boolean? = null,
        mirrorSaved: Boolean? = null,
        mirrorAtMs: Long = 0L,
        entry: FavoriteStateOverlay.Entry? = overlay.entries.value[key]
    ): FavoriteState = resolveFavoriteState(baseline, inFlight, entry, mirrorSaved, mirrorAtMs, nowMs = now)
}
