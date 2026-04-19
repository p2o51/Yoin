package com.gpo.yoin

import android.app.Application
import androidx.annotation.VisibleForTesting

class YoinApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = containerOverrideForTests ?: AppContainer(this)
    }

    companion object {
        @VisibleForTesting
        internal var containerOverrideForTests: AppContainer? = null
    }
}
