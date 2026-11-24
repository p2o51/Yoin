package com.gpo.yoin.player.sing

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The accelerator on-device Sing's last session actually ran on (its first good chunk), kept across
 * launches. Settings › Labs shows "Tensor TPU" only while that is the NPU.
 */
class SingAcceleratorReport private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _last = MutableStateFlow(
        prefs.getString(KEY_LAST, null)?.let { name -> SingAccelerator.entries.firstOrNull { it.name == name } },
    )

    val last: StateFlow<SingAccelerator?> = _last.asStateFlow()

    fun record(accelerator: SingAccelerator) {
        if (_last.value == accelerator) return
        _last.value = accelerator
        prefs.edit { putString(KEY_LAST, accelerator.name) }
    }

    companion object {
        private const val PREFS = "sing_backend"
        private const val KEY_LAST = "last_accelerator"

        @Volatile private var instance: SingAcceleratorReport? = null

        fun get(context: Context): SingAcceleratorReport =
            instance ?: synchronized(this) {
                instance ?: SingAcceleratorReport(context.applicationContext).also { instance = it }
            }
    }
}
