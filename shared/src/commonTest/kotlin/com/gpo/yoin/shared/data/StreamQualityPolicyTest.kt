package com.gpo.yoin.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Mirrors Android's StreamQualityTest (origin/main 39ece83ca) for the parts that are policy. */
class StreamQualityPolicyTest {
    private val settings = StreamQualitySettings(wifi = StreamQuality.Original, mobile = StreamQuality.Kbps192)

    @Test
    fun should_useWifiChoice_when_networkUnmetered() {
        assertEquals(StreamQuality.Original, settings.forNetwork(unmetered = true))
    }

    @Test
    fun should_useMobileChoice_when_networkMetered() {
        // A phone's hotspot is metered too.
        assertEquals(StreamQuality.Kbps192, settings.forNetwork(unmetered = false))
    }

    @Test
    fun should_streamFileOnWifiAndFollowOutputOnMobile_when_nothingChosen() {
        val defaults = StreamQualitySettings()
        assertEquals(StreamQuality.Original, defaults.forNetwork(unmetered = true))
        assertEquals(StreamQuality.Auto, defaults.forNetwork(unmetered = false))
    }

    @Test
    fun should_followOutput_when_qualityAuto() {
        assertEquals(StreamQuality.Original, StreamQuality.Auto.forOutput(AudioOutputKind.Wired))
        assertEquals(StreamQuality.Kbps320, StreamQuality.Auto.forOutput(AudioOutputKind.Bluetooth))
        assertEquals(StreamQuality.Kbps192, StreamQuality.Auto.forOutput(AudioOutputKind.Speaker))
    }

    @Test
    fun should_keepFixedChoice_when_qualityNotAuto() {
        assertEquals(StreamQuality.Kbps128, StreamQuality.Kbps128.forOutput(AudioOutputKind.Wired))
    }

    @Test
    fun should_preferWired_when_wiredAndBluetoothConnected() {
        val both = listOf(AudioOutputKind.Speaker, AudioOutputKind.Bluetooth, AudioOutputKind.Wired)
        assertEquals(AudioOutputKind.Wired, AudioOutputKind.strongest(both))
    }

    @Test
    fun should_readBluetooth_when_onlyBluetoothHeadphones() {
        val kinds = listOf(AudioOutputKind.Speaker, AudioOutputKind.Bluetooth)
        assertEquals(AudioOutputKind.Bluetooth, AudioOutputKind.strongest(kinds))
    }

    @Test
    fun should_readSpeaker_when_onlySpeakerOrNothing() {
        assertEquals(AudioOutputKind.Speaker, AudioOutputKind.strongest(listOf(AudioOutputKind.Speaker)))
        assertEquals(AudioOutputKind.Speaker, AudioOutputKind.strongest(emptyList()))
    }

    @Test
    fun should_transcodeAt320_when_autoOnBluetoothOverMobile() {
        val auto = StreamQualitySettings(wifi = StreamQuality.Original, mobile = StreamQuality.Auto)
        assertEquals(
            StreamPlan(320, "mp3"),
            StreamQualityPolicy.planFor(auto, unmetered = false, output = AudioOutputKind.Bluetooth, sourceKbps = null),
        )
    }

    @Test
    fun should_planEveryRouteAndNetwork_when_defaults() {
        val d = StreamQualitySettings.Defaults
        for (output in AudioOutputKind.entries) {
            assertEquals(StreamPlan.Original, StreamQualityPolicy.planFor(d, true, output, 1411), output.name)
        }
        assertEquals(StreamPlan.Original, StreamQualityPolicy.planFor(d, false, AudioOutputKind.Wired, 1411))
        assertEquals(StreamPlan(320, "mp3"), StreamQualityPolicy.planFor(d, false, AudioOutputKind.Bluetooth, 1411))
        assertEquals(StreamPlan(192, "mp3"), StreamQualityPolicy.planFor(d, false, AudioOutputKind.Speaker, 1411))
    }

    @Test
    fun should_planOriginal_when_qualityOriginal() {
        assertFalse(StreamQualityPolicy.plan(StreamQuality.Original, 900).transcoded)
        assertFalse(StreamQualityPolicy.plan(StreamQuality.Auto, 900).transcoded)
    }

    @Test
    fun should_planMp3_when_qualityHasBitRate() {
        assertEquals(StreamPlan(128, "mp3"), StreamQualityPolicy.plan(StreamQuality.Kbps128, 900))
        assertEquals(StreamPlan(320, "mp3"), StreamQualityPolicy.plan(StreamQuality.Kbps320, null))
        assertEquals(StreamPlan(320, "mp3"), StreamQualityPolicy.planKbps(StreamQuality.Kbps320, 0))
    }

    @Test
    fun should_playFile_when_fileAlreadyUnderCap() {
        assertEquals(StreamPlan.Original, StreamQualityPolicy.plan(StreamQuality.Kbps320, 320))
        assertEquals(StreamPlan.Original, StreamQualityPolicy.planKbps(StreamQuality.Kbps192, 128))
    }

    @Test
    fun should_roundTripKeysAndLabels_when_stored() {
        assertEquals(listOf("auto", "original", "320", "192", "128"), StreamQuality.all.map { it.key })
        assertEquals(listOf("Auto", "Original", "320 kbps", "192 kbps", "128 kbps"), StreamQuality.all.map { it.label })
        StreamQuality.all.forEach { assertEquals(it, StreamQuality.fromKey(it.key)) }
        assertNull(StreamQuality.fromKey("automatic"))
        assertEquals("Wired Original   Bluetooth 320 kbps   Speaker 192 kbps", AudioOutputKind.autoDetail)
    }

    @Test
    fun should_migrateOldSingleChoice_when_newKeysAbsent() {
        val defaults = StreamQualitySettings.Defaults
        assertEquals(defaults, StreamQualitySettings.fromStored(null, null, null))
        assertEquals(defaults, StreamQualitySettings.fromStored(null, null, "automatic"))
        assertEquals(
            StreamQualitySettings(StreamQuality.Kbps192, StreamQuality.Kbps192),
            StreamQualitySettings.fromStored(null, null, "192"),
        )
        assertEquals(
            StreamQualitySettings(StreamQuality.Original, StreamQuality.Original),
            StreamQualitySettings.fromStored(null, null, "original"),
        )
        // New keys win over the old one; an unknown key is its default.
        assertEquals(
            StreamQualitySettings(StreamQuality.Kbps320, StreamQuality.Auto),
            StreamQualitySettings.fromStored("320", "bogus", "128"),
        )
    }
}
