package com.gpo.yoin.ui.tips

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.player.continueon.ContinueOnWindows
import com.gpo.yoin.ui.home.HomeBubbleTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TipReadStoreTest {
    private val android17 = HomeBubbleTip.available(sdk = ContinueOnWindows.API)
    private val android16 = HomeBubbleTip.available(sdk = ContinueOnWindows.API - 1)

    @Test
    fun should_leaveOutTheContinueOnTip_when_androidHasNoContinueOn() {
        assertEquals(HomeBubbleTip.entries.toList(), android17)
        assertEquals(HomeBubbleTip.entries - HomeBubbleTip.ContinueOn, android16)
        // Neither the bubble nor "Next" on the tip's page comes to it.
        assertFalse(HomeBubbleTip.ContinueOn in emptySet<HomeBubbleTip>().unreadTips(android16))
        assertTrue(HomeBubbleTip.ContinueOn in emptySet<HomeBubbleTip>().unreadTips(android17))
        // ContinueOn sits by its day (11-08), after SettingsRegrouped; the tip after that is LyricHighlight.
        assertEquals(HomeBubbleTip.LyricHighlight, nextUnreadTip(HomeBubbleTip.SettingsRegrouped, emptySet(), android16))
        assertEquals(HomeBubbleTip.ContinueOn, nextUnreadTip(HomeBubbleTip.SettingsRegrouped, emptySet(), android17))
    }

    @Test
    fun should_leaveOutSubsonicOnlyTips_when_accountCannotUseThem() {
        val sdk = ContinueOnWindows.API
        val subsonic = ServiceFeatureCatalog.subsonic.capabilities
        val spotify = ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_SPOTIFY).capabilities
        val subsonicOnly = setOf(HomeBubbleTip.VolumeLeveling, HomeBubbleTip.Downloads)

        assertEquals(HomeBubbleTip.entries.toList(), HomeBubbleTip.available(sdk, subsonic))
        assertEquals(HomeBubbleTip.entries - subsonicOnly, HomeBubbleTip.available(sdk, spotify))
        // No account: nothing can use them either.
        assertEquals(HomeBubbleTip.entries - subsonicOnly, HomeBubbleTip.available(sdk, emptySet()))
        // Unknown (previews, the demos): none left out.
        assertEquals(HomeBubbleTip.entries.toList(), HomeBubbleTip.available(sdk, null))
        // Each needs only its own feature.
        assertTrue(HomeBubbleTip.VolumeLeveling in HomeBubbleTip.available(sdk, setOf(Capability.REPLAY_GAIN)))
        assertFalse(HomeBubbleTip.Downloads in HomeBubbleTip.available(sdk, setOf(Capability.REPLAY_GAIN)))
        assertTrue(HomeBubbleTip.Downloads in HomeBubbleTip.available(sdk, setOf(Capability.OFFLINE_DOWNLOAD)))
        // Neither the bubble nor Next on a tip's page comes to them.
        val spotifyTips = HomeBubbleTip.available(sdk, spotify)
        assertTrue(emptySet<HomeBubbleTip>().unreadTips(spotifyTips).none { it in subsonicOnly })
        assertEquals(HomeBubbleTip.Downloads, nextUnreadTip(HomeBubbleTip.FocusCover, emptySet(), HomeBubbleTip.available(sdk, subsonic)))
        assertEquals(HomeBubbleTip.EditHome, nextUnreadTip(HomeBubbleTip.FocusCover, emptySet(), spotifyTips))
    }

    @Test
    fun should_keepATipRead_when_theAccountInUseChanges() {
        val sdk = ContinueOnWindows.API
        val store = InMemoryTipReadStore()
        store.setRead(HomeBubbleTip.VolumeLeveling, true)
        store.setRead(HomeBubbleTip.Name, true)
        val saved = encodeReadTips(store.read.value)

        // On Spotify the tip is out of sight; on Subsonic again it is still read.
        val spotify = ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_SPOTIFY).capabilities
        assertFalse(HomeBubbleTip.VolumeLeveling in store.read.value.unreadTips(HomeBubbleTip.available(sdk, spotify)))
        val reloaded = decodeReadTips(saved)
        val subsonicTips = HomeBubbleTip.available(sdk, ServiceFeatureCatalog.subsonic.capabilities)
        assertFalse(HomeBubbleTip.VolumeLeveling in reloaded.unreadTips(subsonicTips))
        assertTrue(HomeBubbleTip.Downloads in reloaded.unreadTips(subsonicTips))
    }

    @Test
    fun should_callEveryTipRead_when_theOnlyUnreadOneIsNotForThisAndroid() {
        val read = HomeBubbleTip.entries.toSet() - HomeBubbleTip.ContinueOn
        assertEquals(emptyList<HomeBubbleTip>(), read.unreadTips(android16))
        assertNull(nextUnreadTip(HomeBubbleTip.Name, read, android16))
    }

    @Test
    fun should_goToTheNextUnreadTip_when_currentIsRead() {
        val read = setOf(HomeBubbleTip.EditHome, HomeBubbleTip.PullForMemories, HomeBubbleTip.CoverMenu)
        assertEquals(HomeBubbleTip.SongMenu, nextUnreadTip(HomeBubbleTip.EditHome, read))
    }

    @Test
    fun should_wrapAround_when_theRestAfterCurrentIsRead() {
        val read = HomeBubbleTip.entries.toSet() - HomeBubbleTip.EditHome
        assertEquals(HomeBubbleTip.EditHome, nextUnreadTip(HomeBubbleTip.Name, read))
    }

    @Test
    fun should_returnNull_when_everyOtherTipIsRead() {
        val read = HomeBubbleTip.entries.toSet() - HomeBubbleTip.PlayModes
        assertNull(nextUnreadTip(HomeBubbleTip.PlayModes, read))
    }

    @Test
    fun should_markAndUnmark_when_toggled() {
        val store = InMemoryTipReadStore()
        store.setRead(HomeBubbleTip.Name, true)
        store.setRead(HomeBubbleTip.PlayModes, true)
        store.setRead(HomeBubbleTip.Name, false)
        assertEquals(setOf(HomeBubbleTip.PlayModes), store.read.value)
        assertEquals(android17 - HomeBubbleTip.PlayModes, store.read.value.unreadTips(android17))
        store.setAllUnread()
        assertEquals(emptySet<HomeBubbleTip>(), store.read.value)
    }

    @Test
    fun should_keepReadTips_when_savedAndLoaded() {
        val read = setOf(HomeBubbleTip.Downloads, HomeBubbleTip.Name)
        assertEquals(read, decodeReadTips(encodeReadTips(read)))
    }

    @Test
    fun should_bringATipBack_when_itWasUpdatedAfterItWasRead() {
        val saved = setOf("${HomeBubbleTip.SongMenu.name}@2026-10-17", "${HomeBubbleTip.Name.name}@${HomeBubbleTip.Name.updated}")
        assertEquals(setOf(HomeBubbleTip.Name), decodeReadTips(saved))
    }

    @Test
    fun should_readBareNamesAsTheOldTips_when_savedBeforeTipsHadADay() {
        val saved = setOf(HomeBubbleTip.EditHome.name, HomeBubbleTip.PlayModes.name, HomeBubbleTip.SongMenu.name, "Gone")
        // SongMenu was updated after bare names were last written: unread again.
        assertEquals(setOf(HomeBubbleTip.EditHome, HomeBubbleTip.PlayModes), decodeReadTips(saved))
    }
}
