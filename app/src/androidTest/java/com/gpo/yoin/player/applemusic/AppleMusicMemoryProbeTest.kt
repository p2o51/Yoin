package com.gpo.yoin.player.applemusic

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bytedeco.javacpp.Pointer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, unauthorized-controller diagnostics. This does not measure subscribed playback. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AppleMusicMemoryProbeTest {
    @Test
    fun should_reportIdleMemoryAndReleaseCycles_when_probeExplicitlyRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("musickitMemoryProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var player: AppleMusicMedia3Player? = null
        fun create() = instrumentation.runOnMainSync {
            player = AppleMusicMedia3Player(instrumentation.targetContext, "not-authorized", "not-authorized") {}
        }
        fun release() = instrumentation.runOnMainSync {
            player?.release()
            player = null
        }
        fun sample(phase: String) {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("phase", phase)
                putLong("uptimeMs", SystemClock.uptimeMillis())
                putLong("heapMaxBytes", Runtime.getRuntime().maxMemory())
                putLong("maxPhysicalBytes", Pointer.maxPhysicalBytes())
                putLong("maxBytes", Pointer.maxBytes())
                putLong("physicalBytes", Pointer.physicalBytes())
                putLong("trackedBytes", Pointer.totalBytes())
                putLong("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
                putString("artGcCount", Debug.getRuntimeStat("art.gc.gc-count"))
                putString("artGcTimeMs", Debug.getRuntimeStat("art.gc.gc-time"))
                putInt("javaThreadCount", Thread.getAllStackTraces().size)
            })
        }
        try {
            create()
            sample("idle_start")
            repeat(4) { index ->
                SystemClock.sleep(5_000)
                sample("idle_${(index + 1) * 5}s")
            }
            release()
            SystemClock.sleep(2_000)
            sample("released")
            repeat(5) { index ->
                create()
                SystemClock.sleep(1_000)
                release()
                SystemClock.sleep(1_000)
                sample("released_cycle_${index + 1}")
            }
        } finally {
            release()
        }
    }
}
