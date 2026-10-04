package com.gpo.yoin.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.experience.rememberYoinWindowInfo
import com.gpo.yoin.ui.theme.YoinDarkColorScheme
import com.gpo.yoin.ui.theme.YoinLightColorScheme
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Shared harness for the seam preview tests (LibrarySeamPreviewTest,
 * HomeSeamPreviewTest). Instrumentation arguments:
 *  - `seamTheme`  light | dark (default dark)
 *  - `seamMotion` reduced — the power-saving profile (no afterglow, stretch or disorder)
 *  - `seamPath`   true — draw with the pre-33 Path mask instead of the shader
 * Frames are written at 60fps of the paused test clock, so they play back in
 * real time; every frame is a JPEG for the video, named key frames are PNGs.
 */
internal object SeamQa {
    fun dark(arguments: android.os.Bundle): Boolean = arguments.getString("seamTheme") != "light"
    fun reduced(arguments: android.os.Bundle): Boolean = arguments.getString("seamMotion") == "reduced"
    fun path(arguments: android.os.Bundle): Boolean = arguments.getString("seamPath") == "true"

    fun scheme(dark: Boolean): ColorScheme = if (dark) YoinDarkColorScheme else YoinLightColorScheme

    /**
     * Generated covers (no real albums): flat shapes in four-colour palettes.
     * Cover [barTwin] is painted in the bar's own container colour (the
     * "same colour as the bar" case of the lightness yield), [dark] near-black.
     */
    fun covers(directory: File, count: Int, barColor: Color, barTwin: Int, dark: Int): List<File> {
        directory.mkdirs()
        return (0 until count).map { index ->
            val file = File(directory, "seam-cover-$index.png")
            val bitmap = Bitmap.createBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            when (index) {
                barTwin -> paintTwin(canvas, barColor.toArgb())
                dark -> paintCover(canvas, DARK_PALETTE, index)
                else -> paintCover(canvas, PALETTES[index % PALETTES.size], index)
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file
        }
    }

    private fun paintTwin(canvas: Canvas, color: Int) {
        canvas.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFF9C4A3A.toInt() }
        canvas.drawCircle(COVER_PX * .5f, COVER_PX * .32f, COVER_PX * .12f, paint)
    }

    private fun paintCover(canvas: Canvas, palette: IntArray, seed: Int) {
        val random = Random(seed * 7919)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, COVER_PX.toFloat(), palette[0], palette[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, COVER_PX.toFloat(), COVER_PX.toFloat(), paint)
        paint.shader = null
        when (seed % 4) {
            0 -> {
                paint.color = palette[2]
                canvas.drawCircle(COVER_PX * (.3f + random.nextFloat() * .4f), COVER_PX * .45f, COVER_PX * .2f, paint)
                paint.color = palette[3]
                canvas.drawRect(0f, COVER_PX * .7f, COVER_PX.toFloat(), COVER_PX.toFloat(), paint)
            }
            1 -> for (ring in 0 until 5) {
                paint.color = palette[ring % 4]
                canvas.drawCircle(COVER_PX * .5f, COVER_PX * .5f, COVER_PX * (.46f - ring * .085f), paint)
            }
            2 -> for (band in 0 until 5) {
                paint.color = palette[(band + 1) % 4]
                val path = android.graphics.Path()
                val base = COVER_PX * (.16f + band * .17f)
                path.moveTo(0f, COVER_PX.toFloat())
                var x = 0f
                while (x <= COVER_PX) {
                    path.lineTo(x, base + COVER_PX * .05f * sin((x / COVER_PX * 1.6f + band * .23f) * 2 * PI).toFloat())
                    x += 8f
                }
                path.lineTo(COVER_PX.toFloat(), COVER_PX.toFloat())
                path.close()
                canvas.drawPath(path, paint)
            }
            else -> {
                paint.color = palette[2]
                val pill = RectF(COVER_PX * .3f, COVER_PX * .25f, COVER_PX * .7f, COVER_PX * 1.1f)
                canvas.drawRoundRect(pill, COVER_PX * .2f, COVER_PX * .2f, paint)
                paint.color = palette[3]
                canvas.drawCircle(COVER_PX * .62f, COVER_PX * .62f, COVER_PX * .17f, paint)
            }
        }
    }

    private const val COVER_PX = 360

    private val DARK_PALETTE = intArrayOf(
        0xFF0B0C10.toInt(),
        0xFF1B1D24.toInt(),
        0xFF2C2F38.toInt(),
        0xFF8A93A6.toInt(),
    )

    private val PALETTES = listOf(
        intArrayOf(0xFF20324F.toInt(), 0xFFF3C14B.toInt(), 0xFFEF7D57.toInt(), 0xFFFBE9C9.toInt()),
        intArrayOf(0xFF2F3E46.toInt(), 0xFF84A98C.toInt(), 0xFFCAD2C5.toInt(), 0xFFE9C46A.toInt()),
        intArrayOf(0xFF3D405B.toInt(), 0xFFE07A5F.toInt(), 0xFFF2CC8F.toInt(), 0xFF81B29A.toInt()),
        intArrayOf(0xFF0B2545.toInt(), 0xFF3E6D9C.toInt(), 0xFF8DA9C4.toInt(), 0xFFEEF4ED.toInt()),
        intArrayOf(0xFF6B2737.toInt(), 0xFFE08E45.toInt(), 0xFFF8F4A6.toInt(), 0xFF9AD1A8.toInt()),
        intArrayOf(0xFF264653.toInt(), 0xFF2A9D8F.toInt(), 0xFFE9C46A.toInt(), 0xFFF4A261.toInt()),
        intArrayOf(0xFF240046.toInt(), 0xFF7B2CBF.toInt(), 0xFFE0AAFF.toInt(), 0xFFFFD6A5.toInt()),
        intArrayOf(0xFF9D0208.toInt(), 0xFFDC2F02.toInt(), 0xFFFAA307.toInt(), 0xFFFFE8D6.toInt()),
        intArrayOf(0xFF355070.toInt(), 0xFF6D597A.toInt(), 0xFFB56576.toInt(), 0xFFEAAC8B.toInt()),
        intArrayOf(0xFF003049.toInt(), 0xFF669BBC.toInt(), 0xFFFDF0D5.toInt(), 0xFFC1121F.toInt()),
    )
}

/**
 * The window a seam page lives in: theme, motion profile, the window's bar
 * field and a floating bar with the shell's idle pose (two nav halves), so
 * the bottom field has a real bar to work against.
 */
@Composable
internal fun SeamQaWindow(
    dark: Boolean,
    reduced: Boolean,
    onField: (SeamBarField) -> Unit = {},
    content: @Composable () -> Unit,
) {
    YoinTheme(colorSchemeOverride = SeamQa.scheme(dark), darkTheme = dark) {
        val windowInfo = rememberYoinWindowInfo()
        CompositionLocalProvider(
            LocalYoinWindowInfo provides windowInfo,
            LocalMotionProfile provides if (reduced) MotionProfile.AdaptiveReduced else MotionProfile.Full,
        ) {
            ProvideSeamBarField {
                val field = LocalSeamBarField.current
                SideEffect { field?.let(onField) }
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                    FloatingBottomBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        centered = windowInfo.chromeForm == ShellChromeForm.CenteredBar,
                    ) { innerWidth, _ ->
                        val half = (innerWidth - FloatingBarItemGap) / 2
                        Box(
                            modifier = Modifier
                                .width(half)
                                .height(FloatingBarButtonHeight)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                        )
                        Box(modifier = Modifier.width(FloatingBarItemGap))
                        Box(
                            modifier = Modifier
                                .width(half)
                                .height(FloatingBarButtonHeight)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Drives a Compose window frame by frame on a clock the test owns. The
 * compose test rule cannot run here: its Espresso (3.5) idling reflects on
 * `InputManager.getInstance`, which Android 17 removed. So the content gets
 * its own Recomposer on a [BroadcastFrameClock]: every animation, the
 * afterglow follower, flings and marquees advance only when [frame] sends the
 * next 60fps tick, and each tick is captured with PixelCopy after it is drawn
 * — frames play back in real time however long capturing takes.
 */
internal class SeamFrameHarness(
    private val activity: ComponentActivity,
    private val directory: File,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val clock = BroadcastFrameClock()
    private val job = Job()
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + clock + job)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val main = Handler(Looper.getMainLooper())
    private val startUptime = SystemClock.uptimeMillis()
    private var nanos = 0L
    private var index = 0
    private var downTime = 0L
    private var pointer = Offset.Zero
    lateinit var view: ComposeView
        private set

    init {
        directory.deleteRecursively()
        directory.mkdirs()
    }

    val width: Int get() = view.width
    val height: Int get() = view.height
    val density: Float get() = activity.resources.displayMetrics.density

    fun setContent(content: @Composable () -> Unit) {
        instrumentation.runOnMainSync {
            activity.enableEdgeToEdge()
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            view = ComposeView(activity).apply {
                setParentCompositionContext(recomposer)
                setContent(content)
            }
            activity.setContentView(view)
        }
        drawn()
    }

    /** Advances one 60fps frame without capturing; [realMillis] lets async work (image decodes) land. */
    fun tick(realMillis: Long = 0L) {
        if (realMillis > 0) Thread.sleep(realMillis)
        nanos += FRAME_NANOS
        instrumentation.runOnMainSync { clock.sendFrame(nanos) }
        drawn()
    }

    fun frame(key: String? = null) {
        tick()
        val bitmap = capture()
        File(directory, "%04d.jpg".format(index)).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        if (key != null) {
            File(directory, "key-%04d-$key.png".format(index)).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        bitmap.recycle()
        index++
    }

    fun hold(frames: Int, key: String? = null) = repeat(frames) { frame(if (it == frames - 1) key else null) }

    fun down(x: Float, y: Float) {
        downTime = eventTime()
        pointer = Offset(x, y)
        touch(MotionEvent.ACTION_DOWN)
    }

    /** One pointer move per call; pair it with [frame] or [tick] for a steady drag. */
    fun moveBy(dx: Float, dy: Float) {
        pointer += Offset(dx, dy)
        touch(MotionEvent.ACTION_MOVE)
    }

    fun up() = touch(MotionEvent.ACTION_UP)

    val touchSlop: Float get() = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()

    fun release() {
        instrumentation.runOnMainSync {
            recomposer.cancel()
            job.cancel()
        }
    }

    private fun eventTime(): Long = startUptime + nanos / 1_000_000L

    private fun touch(action: Int) {
        val event = MotionEvent.obtain(downTime, eventTime(), action, pointer.x, pointer.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.runOnMainSync { view.dispatchTouchEvent(event) }
        event.recycle()
    }

    /** Lets dispatched work run, then waits for the next drawn frame to be committed. */
    private fun drawn() {
        repeat(2) { instrumentation.runOnMainSync { } }
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.viewTreeObserver.registerFrameCommitCallback { latch.countDown() }
            view.invalidate()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    private fun capture(): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationInWindow(location) }
        val latch = CountDownLatch(1)
        PixelCopy.request(
            activity.window,
            Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
            bitmap,
            { latch.countDown() },
            main,
        )
        latch.await(2, TimeUnit.SECONDS)
        return bitmap
    }

    private companion object {
        const val FRAME_NANOS = 16_666_667L
    }
}
