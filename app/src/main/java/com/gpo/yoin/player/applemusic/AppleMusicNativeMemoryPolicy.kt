package com.gpo.yoin.player.applemusic

import android.app.ActivityManager
import android.content.Context

/** Configure the vendor's process-memory guard before JavaCPP's Pointer class initializes. */
internal object AppleMusicNativeMemoryPolicy {
    private const val Property = "org.bytedeco.javacpp.maxPhysicalBytes"
    private const val LegacyProperty = "org.bytedeco.javacpp.maxphysicalbytes"
    private const val MaximumIncreaseBytes = 1024L * 1024 * 1024

    fun initialize(context: Context) {
        if (System.getProperty(Property) != null || System.getProperty(LegacyProperty) != null) return
        val memoryInfo = ActivityManager.MemoryInfo()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.getMemoryInfo(memoryInfo)
        val budget = physicalMemoryBudget(Runtime.getRuntime().maxMemory(), memoryInfo.totalMem)
        // The bundled MusicKit JavaCPP reads this property once in Pointer's static initializer.
        // Keep maxBytes, native pointer collection and retries at their vendor defaults.
        System.setProperty(Property, budget.toString())
    }

    internal fun physicalMemoryBudget(maxHeapBytes: Long, totalMemoryBytes: Long): Long {
        require(maxHeapBytes > 0)
        val vendorDefault = scaledHeap(maxHeapBytes, 2)
        if (totalMemoryBytes <= 0) return vendorDefault
        // Newer JavaCPP uses four heaps for whole-process memory. Bound our increase by
        // one GiB and one quarter of device RAM; never lower the bundled vendor default.
        val boundedIncrease = minOf(scaledHeap(maxHeapBytes, 4), MaximumIncreaseBytes, totalMemoryBytes / 4)
        return maxOf(vendorDefault, boundedIncrease)
    }

    private fun scaledHeap(bytes: Long, factor: Long): Long =
        if (bytes > Long.MAX_VALUE / factor) Long.MAX_VALUE else bytes * factor
}
