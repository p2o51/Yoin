package com.gpo.yoin.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.detail.AlbumDetailActivity
import com.gpo.yoin.ui.detail.ArtistDetailActivity
import com.gpo.yoin.ui.detail.PlaylistDetailActivity
import com.gpo.yoin.ui.detail.prefetchDetail
import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.navigation.YoinSection
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps placed widgets current while the process lives: new plays / visits, memory signals (ratings, notes,
 * reviews) and profile switches re-render them. Nothing runs per playback tick, and nothing re-renders when no
 * widget is placed. The system's own period (res/xml) covers "3 Months Ago" ageing while the app is dead.
 */
internal object WidgetRefresher {
    // A refresher that fails just stops (logged): it must never take the app down, and in Robolectric — which
    // builds a fresh Application per test — a late failure must not leak into whichever test runs next.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            Log.w(TAG, "Widget refresh stopped", error)
        },
    )
    private var job: Job? = null

    @OptIn(FlowPreview::class)
    fun start(context: Context, container: AppContainer) {
        val app = context.applicationContext
        // One refresher per process, on the newest container.
        job?.cancel()
        job = scope.launch {
            // Off the cold-start path: the repository and its flows spin up after first frame.
            delay(START_DELAY_MS)
            merge(
                container.repository.getRecentActivities(6)
                    .map { events -> events.map { it.entityId to it.timestamp } }
                    .distinctUntilChanged()
                    .map { Unit },
                container.repository.observeMemorySignalStamp().distinctUntilChanged().map { Unit },
                container.musicConfigurationRevision.map { Unit },
            )
                .debounce(REFRESH_DEBOUNCE_MS)
                .collect { refreshPlaced(app) }
        }
    }

    suspend fun refreshPlaced(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        runCatching {
            if (manager.getGlanceIds(PlayWidget::class.java).isNotEmpty()) PlayWidget().updateAll(context)
            if (manager.getGlanceIds(MemoryWidget::class.java).isNotEmpty()) MemoryWidget().updateAll(context)
        }
    }

    private const val TAG = "WidgetRefresher"
    private const val REFRESH_DEBOUNCE_MS = 2_000L
    private const val START_DELAY_MS = 4_000L
}

/**
 * MainActivity's side of [WidgetIntents]: a widget tap lands on the shell, which then shows the target — a
 * detail page pushed over it (so back returns to Home), or Memories opened at that memory.
 */
internal object WidgetLaunch {
    fun handle(activity: android.app.Activity, intent: Intent?, container: AppContainer): Boolean {
        intent ?: return false
        when (intent.action) {
            WidgetIntents.ACTION_OPEN_MEMORY -> {
                val session = intent.getLongExtra(WidgetIntents.EXTRA_SESSION, Long.MIN_VALUE)
                if (session == Long.MIN_VALUE) return false
                val store = container.experienceSessionStore
                store.setSelectedSection(YoinSection.HOME)
                store.requestMemoriesFocus(session)
                store.setHomeSurface(HomeSurface.Memories)
            }
            WidgetIntents.ACTION_OPEN_DETAIL -> {
                val entity = intent.getStringExtra(WidgetIntents.EXTRA_ENTITY)
                    ?.let { runCatching { WidgetEntity.valueOf(it) }.getOrNull() } ?: return false
                val id = MediaId.parseOrNull(intent.getStringExtra(WidgetIntents.EXTRA_ID)) ?: return false
                val target = when (entity) {
                    WidgetEntity.ALBUM -> AlbumDetailActivity.intent(activity, id.toString())
                    WidgetEntity.PLAYLIST -> PlaylistDetailActivity.intent(activity, id.toString())
                    WidgetEntity.ARTIST -> ArtistDetailActivity.intent(activity, id.toString())
                }
                // The page's load starts now, not once its Activity is up (ui/detail/DetailPrefetch.kt).
                prefetchOnceSourceIsUp(activity, container, target)
                activity.startActivity(target)
            }
            else -> return false
        }
        return true
    }

    /**
     * Prefetch [target]'s page — on a cold start only once the source is built: a load started without one
     * would fail at once, and a page that joined it would show that error. The wait is tied to the shell under
     * the page and capped at [PREFETCH_SOURCE_WAIT_MS]; past that the page has loaded on its own.
     */
    private fun prefetchOnceSourceIsUp(activity: android.app.Activity, container: AppContainer, target: Intent) {
        val activeSource = container.profileManager.activeSource
        if (activeSource.value != null) {
            container.repository.prefetchDetail(target)
            return
        }
        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: return
        scope.launch {
            withTimeoutOrNull(PREFETCH_SOURCE_WAIT_MS) { activeSource.first { it != null } } ?: return@launch
            container.repository.prefetchDetail(target)
        }
    }

    private const val PREFETCH_SOURCE_WAIT_MS = 3_000L
}
