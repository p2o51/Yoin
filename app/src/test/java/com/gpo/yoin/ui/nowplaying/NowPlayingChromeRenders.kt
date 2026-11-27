package com.gpo.yoin.ui.nowplaying

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.player.sing.SingMode
import com.gpo.yoin.player.sing.VocalLevel
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review renders of Now Playing's option B chrome (not a check: skipped unless YOIN_RENDER_OUT names a
 * folder). Each size × theme × language draws the expanded Lyrics, About, empty About, Note and asking
 * pages, plus the resting player (on Lyrics and on About), into `<YOIN_RENDER_OUT>/<size>-<theme>-<lang>-<state>.png`.
 *
 * `YOIN_RENDER_OUT=/path ./gradlew :app:testDebugUnitTest --tests '*NowPlayingChromeRenders'`
 *
 * The `p0-*` scenes (review 2026-11-27) draw the states its P0 bugs lived in: landscape 915×412 and
 * 568×320 (resting, lyrics with and without Sing, About, Ask open, launching, can't connect), the foldable's
 * enlarged column 690×840 and the tablet's 800×1280 with Sing, a 393 phone's lyrics with Sing, a 412 phone
 * at rest (the time labels) and a short two-column 860×500.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingChromeRenders {

    @get:Rule
    val rule = createComposeRule()

    private val out: File? = System.getenv("YOIN_RENDER_OUT")?.let(::File)

    @Config(qualifiers = "w475dp-h751dp-notnight")
    @Test fun fatPhoneLightEn() = renderAll("fat-475x751", 475, 751, "light", "en")

    @Config(qualifiers = "w475dp-h751dp-night")
    @Test fun fatPhoneDarkEn() = renderAll("fat-475x751", 475, 751, "dark", "en")

    @Config(qualifiers = "zh-rCN-w475dp-h751dp-notnight")
    @Test fun fatPhoneLightZh() = renderAll("fat-475x751", 475, 751, "light", "zh")

    @Config(qualifiers = "zh-rCN-w475dp-h751dp-night")
    @Test fun fatPhoneDarkZh() = renderAll("fat-475x751", 475, 751, "dark", "zh")

    @Config(qualifiers = "w393dp-h852dp-notnight")
    @Test fun regularLightEn() = renderAll("regular-393x852", 393, 852, "light", "en")

    @Config(qualifiers = "w393dp-h852dp-night")
    @Test fun regularDarkEn() = renderAll("regular-393x852", 393, 852, "dark", "en")

    @Config(qualifiers = "zh-rCN-w393dp-h852dp-notnight")
    @Test fun regularLightZh() = renderAll("regular-393x852", 393, 852, "light", "zh")

    @Config(qualifiers = "zh-rCN-w393dp-h852dp-night")
    @Test fun regularDarkZh() = renderAll("regular-393x852", 393, 852, "dark", "zh")

    @Config(qualifiers = "w412dp-h915dp-notnight")
    @Test fun tallLightEn() = renderAll("tall-412x915", 412, 915, "light", "en")

    @Config(qualifiers = "w412dp-h915dp-night")
    @Test fun tallDarkEn() = renderAll("tall-412x915", 412, 915, "dark", "en")

    @Config(qualifiers = "zh-rCN-w412dp-h915dp-notnight")
    @Test fun tallLightZh() = renderAll("tall-412x915", 412, 915, "light", "zh")

    @Config(qualifiers = "zh-rCN-w412dp-h915dp-night")
    @Test fun tallDarkZh() = renderAll("tall-412x915", 412, 915, "dark", "zh")

    // A 16:9 phone (PR 2): the one-line column, its spine and its top-bar tabs at rest.
    @Config(qualifiers = "w360dp-h640dp-notnight")
    @Test fun shortLightEn() = renderAll("short-360x640", 360, 640, "light", "en")

    @Config(qualifiers = "w360dp-h640dp-night")
    @Test fun shortDarkEn() = renderAll("short-360x640", 360, 640, "dark", "en")

    @Config(qualifiers = "zh-rCN-w360dp-h640dp-notnight")
    @Test fun shortLightZh() = renderAll("short-360x640", 360, 640, "light", "zh")

    @Config(qualifiers = "zh-rCN-w360dp-h640dp-night")
    @Test fun shortDarkZh() = renderAll("short-360x640", 360, 640, "dark", "zh")

    @Config(qualifiers = "w800dp-h1280dp-notnight")
    @Test fun tabletLightEn() = renderAll("tablet-800x1280", 800, 1280, "light", "en", tablet = true)

    @Config(qualifiers = "w800dp-h1280dp-night")
    @Test fun tabletDarkEn() = renderAll("tablet-800x1280", 800, 1280, "dark", "en", tablet = true)

    @Config(qualifiers = "zh-rCN-w800dp-h1280dp-notnight")
    @Test fun tabletLightZh() = renderAll("tablet-800x1280", 800, 1280, "light", "zh", tablet = true)

    @Config(qualifiers = "zh-rCN-w800dp-h1280dp-night")
    @Test fun tabletDarkZh() = renderAll("tablet-800x1280", 800, 1280, "dark", "zh", tablet = true)

    // ── P0 layout states (review 2026-11-27): landscape, foldable, Sing, Ask, the short rating bar ──
    // `<YOIN_RENDER_OUT>/p0-<scene>-<theme>-<lang>-<shot>.png`, one test per scene × theme × language.

    @Config(qualifiers = "w915dp-h412dp-land-notnight")
    @Test fun landWideLightEn() = renderScene(Scene.LandWide, "light", "en")

    @Config(qualifiers = "w915dp-h412dp-land-night")
    @Test fun landWideDarkEn() = renderScene(Scene.LandWide, "dark", "en")

    @Config(qualifiers = "zh-rCN-w915dp-h412dp-land-notnight")
    @Test fun landWideLightZh() = renderScene(Scene.LandWide, "light", "zh")

    @Config(qualifiers = "zh-rCN-w915dp-h412dp-land-night")
    @Test fun landWideDarkZh() = renderScene(Scene.LandWide, "dark", "zh")

    @Config(qualifiers = "w568dp-h320dp-land-notnight")
    @Test fun landShortLightEn() = renderScene(Scene.LandShort, "light", "en")

    @Config(qualifiers = "w568dp-h320dp-land-night")
    @Test fun landShortDarkEn() = renderScene(Scene.LandShort, "dark", "en")

    @Config(qualifiers = "zh-rCN-w568dp-h320dp-land-notnight")
    @Test fun landShortLightZh() = renderScene(Scene.LandShort, "light", "zh")

    @Config(qualifiers = "zh-rCN-w568dp-h320dp-land-night")
    @Test fun landShortDarkZh() = renderScene(Scene.LandShort, "dark", "zh")

    @Config(qualifiers = "w915dp-h412dp-land-notnight")
    @Test fun landWidePendingLightEn() = renderScene(Scene.LandWidePending, "light", "en")

    @Config(qualifiers = "w915dp-h412dp-land-night")
    @Test fun landWidePendingDarkEn() = renderScene(Scene.LandWidePending, "dark", "en")

    @Config(qualifiers = "zh-rCN-w915dp-h412dp-land-notnight")
    @Test fun landWidePendingLightZh() = renderScene(Scene.LandWidePending, "light", "zh")

    @Config(qualifiers = "zh-rCN-w915dp-h412dp-land-night")
    @Test fun landWidePendingDarkZh() = renderScene(Scene.LandWidePending, "dark", "zh")

    @Config(qualifiers = "w568dp-h320dp-land-notnight")
    @Test fun landShortPendingLightEn() = renderScene(Scene.LandShortPending, "light", "en")

    @Config(qualifiers = "w568dp-h320dp-land-night")
    @Test fun landShortPendingDarkEn() = renderScene(Scene.LandShortPending, "dark", "en")

    @Config(qualifiers = "zh-rCN-w568dp-h320dp-land-notnight")
    @Test fun landShortPendingLightZh() = renderScene(Scene.LandShortPending, "light", "zh")

    @Config(qualifiers = "zh-rCN-w568dp-h320dp-land-night")
    @Test fun landShortPendingDarkZh() = renderScene(Scene.LandShortPending, "dark", "zh")

    @Config(qualifiers = "w690dp-h840dp-notnight")
    @Test fun foldLightEn() = renderScene(Scene.Fold, "light", "en")

    @Config(qualifiers = "w690dp-h840dp-night")
    @Test fun foldDarkEn() = renderScene(Scene.Fold, "dark", "en")

    @Config(qualifiers = "zh-rCN-w690dp-h840dp-notnight")
    @Test fun foldLightZh() = renderScene(Scene.Fold, "light", "zh")

    @Config(qualifiers = "zh-rCN-w690dp-h840dp-night")
    @Test fun foldDarkZh() = renderScene(Scene.Fold, "dark", "zh")

    @Config(qualifiers = "w800dp-h1280dp-notnight")
    @Test fun tabletSingLightEn() = renderScene(Scene.Tablet, "light", "en")

    @Config(qualifiers = "w800dp-h1280dp-night")
    @Test fun tabletSingDarkEn() = renderScene(Scene.Tablet, "dark", "en")

    @Config(qualifiers = "zh-rCN-w800dp-h1280dp-notnight")
    @Test fun tabletSingLightZh() = renderScene(Scene.Tablet, "light", "zh")

    @Config(qualifiers = "zh-rCN-w800dp-h1280dp-night")
    @Test fun tabletSingDarkZh() = renderScene(Scene.Tablet, "dark", "zh")

    @Config(qualifiers = "w393dp-h852dp-notnight")
    @Test fun regularSingLightEn() = renderScene(Scene.Regular, "light", "en")

    @Config(qualifiers = "w393dp-h852dp-night")
    @Test fun regularSingDarkEn() = renderScene(Scene.Regular, "dark", "en")

    @Config(qualifiers = "zh-rCN-w393dp-h852dp-notnight")
    @Test fun regularSingLightZh() = renderScene(Scene.Regular, "light", "zh")

    @Config(qualifiers = "zh-rCN-w393dp-h852dp-night")
    @Test fun regularSingDarkZh() = renderScene(Scene.Regular, "dark", "zh")

    @Config(qualifiers = "w412dp-h915dp-notnight")
    @Test fun tallRestLightEn() = renderScene(Scene.Tall, "light", "en")

    @Config(qualifiers = "w412dp-h915dp-night")
    @Test fun tallRestDarkEn() = renderScene(Scene.Tall, "dark", "en")

    @Config(qualifiers = "zh-rCN-w412dp-h915dp-notnight")
    @Test fun tallRestLightZh() = renderScene(Scene.Tall, "light", "zh")

    @Config(qualifiers = "zh-rCN-w412dp-h915dp-night")
    @Test fun tallRestDarkZh() = renderScene(Scene.Tall, "dark", "zh")

    @Config(qualifiers = "w860dp-h500dp-land-notnight")
    @Test fun dualShortLightEn() = renderScene(Scene.DualShort, "light", "en")

    @Config(qualifiers = "w860dp-h500dp-land-night")
    @Test fun dualShortDarkEn() = renderScene(Scene.DualShort, "dark", "en")

    @Config(qualifiers = "zh-rCN-w860dp-h500dp-land-notnight")
    @Test fun dualShortLightZh() = renderScene(Scene.DualShort, "light", "zh")

    @Config(qualifiers = "zh-rCN-w860dp-h500dp-land-night")
    @Test fun dualShortDarkZh() = renderScene(Scene.DualShort, "dark", "zh")

    /** What a P0 shot draws: the state, the stage, the page and whether Sing is on. */
    private data class SceneShot(
        val name: String,
        val kind: Kind = Kind.Playing,
        val stage: NowPlayingStageMode = NowPlayingStageMode.Compact,
        val page: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
        val ask: AskBarState = AskBarState.Idle,
        val sing: Boolean = false,
        val about: Boolean = true,
    )

    private enum class Kind { Playing, Launching, ConnectError }

    private enum class Scene(
        val id: String,
        val width: Int,
        val height: Int,
        val presentation: NowPlayingPresentation,
        val top: Int,
        val bottom: Int,
        val shots: List<SceneShot>,
    ) {
        LandWide(
            "land-915x412", 915, 412, NowPlayingPresentation.Landscape, 24, 16,
            listOf(
                SceneShot("rest"),
                SceneShot("lyrics", stage = NowPlayingStageMode.Expanded),
                SceneShot("about", stage = NowPlayingStageMode.Expanded, page = NowPlayingDetailPage.About),
                SceneShot(
                    "ask-focused",
                    stage = NowPlayingStageMode.Expanded,
                    page = NowPlayingDetailPage.About,
                    ask = AskBarState.Focused,
                ),
                SceneShot("lyrics-sing", stage = NowPlayingStageMode.Expanded, sing = true),
            ),
        ),
        LandShort(
            "land-568x320", 568, 320, NowPlayingPresentation.Landscape, 24, 16,
            listOf(
                SceneShot("rest"),
                SceneShot("lyrics", stage = NowPlayingStageMode.Expanded),
                SceneShot("about", stage = NowPlayingStageMode.Expanded, page = NowPlayingDetailPage.About),
                SceneShot(
                    "ask-focused",
                    stage = NowPlayingStageMode.Expanded,
                    page = NowPlayingDetailPage.About,
                    ask = AskBarState.Focused,
                ),
                SceneShot("lyrics-sing", stage = NowPlayingStageMode.Expanded, sing = true),
            ),
        ),
        // The pending pages in their own runs: a render harness quirk keeps the frame of a shot that follows
        // a launching / error page or the open Sing capsule, so those go last or alone.
        LandWidePending(
            "land-915x412", 915, 412, NowPlayingPresentation.Landscape, 24, 16,
            listOf(
                SceneShot("launching", kind = Kind.Launching),
                SceneShot("connect-error", kind = Kind.ConnectError),
            ),
        ),
        LandShortPending(
            "land-568x320", 568, 320, NowPlayingPresentation.Landscape, 24, 16,
            listOf(
                SceneShot("launching", kind = Kind.Launching),
                SceneShot("connect-error", kind = Kind.ConnectError),
            ),
        ),
        Fold(
            "fold-690x840", 690, 840, NowPlayingPresentation.Enlarged, 24, 24,
            listOf(
                SceneShot("rest"),
                SceneShot("rest-sing", sing = true),
                SceneShot("lyrics-sing", stage = NowPlayingStageMode.Expanded, sing = true),
            ),
        ),
        Tablet(
            "tablet-800x1280", 800, 1280, NowPlayingPresentation.Enlarged, 24, 24,
            listOf(
                SceneShot("about-empty", stage = NowPlayingStageMode.Expanded, page = NowPlayingDetailPage.About, about = false),
                SceneShot("rest-sing", sing = true),
                SceneShot("lyrics-sing", stage = NowPlayingStageMode.Expanded, sing = true),
            ),
        ),
        Regular(
            "regular-393x852", 393, 852, NowPlayingPresentation.Phone, 32, 24,
            listOf(
                SceneShot("rest"),
                SceneShot("about-empty-rest", page = NowPlayingDetailPage.About, about = false),
                SceneShot("lyrics-sing", stage = NowPlayingStageMode.Expanded, sing = true),
            ),
        ),
        Tall(
            "tall-412x915", 412, 915, NowPlayingPresentation.Phone, 32, 24,
            listOf(SceneShot("rest")),
        ),
        DualShort(
            "dual-860x500", 860, 500, NowPlayingPresentation.DualPane, 24, 16,
            listOf(
                SceneShot("rest"),
                SceneShot("launching", kind = Kind.Launching),
                SceneShot("connect-error", kind = Kind.ConnectError),
            ),
        ),
    }

    private fun renderScene(scene: Scene, theme: String, lang: String) {
        val dir = out
        assumeTrue("YOIN_RENDER_OUT not set", dir != null)
        dir!!.mkdirs()
        val zh = lang == "zh"
        var shot by mutableStateOf(scene.shots.first())
        val enlarged = scene.presentation == NowPlayingPresentation.Enlarged
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = scene.width, heightDp = scene.height) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(top = scene.top.dp, bottom = scene.bottom.dp),
                    ) {
                        key(shot) {
                            val sing = if (shot.sing) {
                                NowPlayingSingUi(
                                    mode = SingMode.Ready,
                                    vocalLevel = VocalLevel(0.12f),
                                    isSupported = true,
                                    onEnter = {},
                                    onExit = {},
                                    onSetVocalLevel = {},
                                )
                            } else {
                                null
                            }
                            CompositionLocalProvider(LocalNowPlayingSing provides sing) {
                                NowPlayingScreen(
                                    uiState = when (shot.kind) {
                                        Kind.Playing -> playing(zh)
                                        Kind.Launching -> launching(zh)
                                        Kind.ConnectError -> connectError(zh)
                                    },
                                    positionMs = { 147_000L },
                                    bufferedMs = { 180_000L },
                                    hasAudioSpectrum = false,
                                    onTogglePlayPause = {},
                                    onSkipNext = {},
                                    onSkipPrevious = {},
                                    onSeek = {},
                                    onRatingChange = {},
                                    onToggleFavorite = {},
                                    onAddCurrentToPlaylist = {},
                                    onSkipToQueueItem = {},
                                    aboutUiState = AboutUiState.Ready(if (shot.about) aboutEntries(zh) else emptyList()),
                                    askState = shot.ask,
                                    stageMode = shot.stage,
                                    detailPage = shot.page,
                                    notesState = notes(zh),
                                    presentation = scene.presentation,
                                    enlarged = if (enlarged) nowPlayingEnlargedSpec(scene.width.dp) else null,
                                    topBarAction = if (enlarged) {
                                        { PanelToggleButton(fullscreen = true, onClick = {}) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        for (s in scene.shots) {
            rule.runOnIdle { shot = s }
            rule.mainClock.advanceTimeBy(3_000L)
            save(dir, "p0-${scene.id}-$theme-$lang-${s.name}")
        }
    }

    private fun launching(zh: Boolean) = NowPlayingUiState.Launching(
        songTitle = if (zh) "东京晚安" else "Goodnight, Tokyo",
        artist = if (zh) "子午线" else "The Meridians",
        albumName = if (zh) "玻璃果园" else "Glass Orchard",
        coverArtUrl = null,
        durationMs = 240_000L,
        hint = UiText.Res(R.string.np_msg_connecting_spotify),
    )

    private fun connectError(zh: Boolean) = NowPlayingUiState.ConnectError(
        songTitle = if (zh) "东京晚安" else "Goodnight, Tokyo",
        artist = if (zh) "子午线" else "The Meridians",
        coverArtUrl = null,
        message = UiText.Res(R.string.np_msg_couldnt_start),
        opensSpotify = true,
    )

    private enum class Shot { Rest, RestAbout, Lyrics, About, AboutEmpty, Note, AskLoading }

    private fun renderAll(size: String, widthDp: Int, heightDp: Int, theme: String, lang: String, tablet: Boolean = false) {
        val dir = out
        assumeTrue("YOIN_RENDER_OUT not set", dir != null)
        dir!!.mkdirs()
        val zh = lang == "zh"
        var shot by mutableStateOf(Shot.Rest)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = widthDp, heightDp = heightDp) {
                    // The system bars a phone keeps over the player (Robolectric draws none).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(top = if (tablet) 24.dp else 32.dp, bottom = 24.dp),
                    ) {
                        val about = when (shot) {
                            Shot.AboutEmpty -> AboutUiState.Ready(emptyList())
                            else -> AboutUiState.Ready(aboutEntries(zh))
                        }
                        // A fresh player per shot: the stage starts in its mode (no host animates it here).
                        key(shot) {
                        NowPlayingScreen(
                            uiState = playing(zh),
                            positionMs = { 147_000L },
                            bufferedMs = { 180_000L },
                            hasAudioSpectrum = false,
                            onTogglePlayPause = {},
                            onSkipNext = {},
                            onSkipPrevious = {},
                            onSeek = {},
                            onRatingChange = {},
                            onToggleFavorite = {},
                            onAddCurrentToPlaylist = {},
                            onSkipToQueueItem = {},
                            aboutUiState = about,
                            askState = if (shot == Shot.AskLoading) {
                                AskBarState.Loading(title = if (zh) "桥段是谁唱的？" else "Who sings the bridge?")
                            } else {
                                AskBarState.Idle
                            },
                            stageMode = if (shot == Shot.Rest || shot == Shot.RestAbout) {
                                NowPlayingStageMode.Compact
                            } else {
                                NowPlayingStageMode.Expanded
                            },
                            detailPage = when (shot) {
                                Shot.Rest, Shot.Lyrics -> NowPlayingDetailPage.Lyrics
                                Shot.RestAbout -> NowPlayingDetailPage.About
                                Shot.About, Shot.AboutEmpty, Shot.AskLoading -> NowPlayingDetailPage.About
                                Shot.Note -> NowPlayingDetailPage.Note
                            },
                            notesState = notes(zh),
                            presentation = if (tablet) NowPlayingPresentation.Enlarged else NowPlayingPresentation.Phone,
                            enlarged = if (tablet) nowPlayingEnlargedSpec(widthDp.dp) else null,
                            topBarAction = if (tablet) {
                                { PanelToggleButton(fullscreen = true, onClick = {}) }
                            } else {
                                null
                            },
                        )
                        }
                    }
                }
            }
        }
        for (s in Shot.entries) {
            rule.runOnIdle { shot = s }
            // Let the stage settle (springs) without waiting on the endless indicator.
            rule.mainClock.advanceTimeBy(if (s == Shot.AskLoading) 16L else 3_000L)
            save(dir, "$size-$theme-$lang-${s.fileName()}")
            if (s == Shot.RestAbout) saveScrolledAbout(dir, "$size-$theme-$lang-rest-about-scrolled")
            if (s == Shot.AskLoading) {
                // The indicator on its way: the circle has started to morph.
                rule.mainClock.advanceTimeBy(420L)
                save(dir, "$size-$theme-$lang-ask-loading-morphing")
                rule.mainClock.advanceTimeBy(2_000L)
                save(dir, "$size-$theme-$lang-ask-loading-later")
            }
        }
    }

    /** The collapsed About after a finger scrolls it (where it scrolls: not under the one-line lyric). */
    private fun saveScrolledAbout(dir: File, name: String) {
        val node = rule.onAllNodesWithTag(CollapsedAboutTag).fetchSemanticsNodes().firstOrNull() ?: return
        if (SemanticsActions.ScrollBy !in node.config) return
        rule.onNodeWithTag(CollapsedAboutTag).performTouchInput {
            swipeUp(startY = bottom - 8f, endY = top + 8f, durationMillis = 400L)
        }
        rule.mainClock.advanceTimeBy(2_000L)
        save(dir, name)
    }

    private fun save(dir: File, name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun Shot.fileName(): String = when (this) {
        Shot.Rest -> "rest"
        Shot.RestAbout -> "rest-about"
        Shot.Lyrics -> "lyrics"
        Shot.About -> "about"
        Shot.AboutEmpty -> "about-empty"
        Shot.Note -> "note"
        Shot.AskLoading -> "ask-loading-start"
    }

    private fun playing(zh: Boolean) = NowPlayingUiState.Playing(
        songTitle = if (zh) "东京晚安" else "Goodnight, Tokyo",
        artist = if (zh) "子午线" else "The Meridians",
        albumName = if (zh) "玻璃果园" else "Glass Orchard",
        coverArtUrl = null,
        isPlaying = true,
        durationMs = 240_000L,
        songId = "subsonic:1",
        rating = 8.4f,
        isStarred = true,
        lyrics = lyrics(zh),
        showLyricsTranslation = false,
        lyricsActionInFlight = null,
        lyricsLoading = false,
        queue = listOf(QueueItem("1", "Goodnight, Tokyo", "The Meridians", null)),
        currentQueueIndex = 0,
        playMode = PlayMode.RepeatAll,
        albumId = "a1",
        artistId = "ar1",
        activityContext = ActivityContext.None,
    )

    private fun lyrics(zh: Boolean): List<LyricLine> {
        val lines = if (zh) {
            listOf(
                "抓住午后最后一点光", "纸灯笼，带我回家", "我把傍晚折进纸灯笼", "让它们漂向海港与天相接的地方",
                "每个字都是你我相遇的地方", "每一盏灯都是我没说出口的话", "或许能在光里多停留一会",
                "在潮水决定我们能留下什么之前", "城市哼着我曾熟悉的歌", "纸灯笼，带我入睡",
            )
        } else {
            listOf(
                "Hold on to the afterglow", "Paper lanterns, carry me home", "I folded the evening into paper lanterns",
                "Let them drift where the harbor meets the sky", "Every word a place where you and I",
                "Every light a word I never said", "Could stand a little longer in the glow",
                "Before the tide decides what we can keep", "Oh, the city hums a song I used to know",
                "Paper lanterns, carry me to sleep",
            )
        }
        return lines.mapIndexed { i, text -> LyricLine(startMs = i * 18_000L + 30_000L, text = text) }
    }

    private fun aboutEntries(zh: Boolean): List<SongAboutEntry> = listOf(
        canon(SongAboutEntry.CANON_LYRICIST, "Mara Quinn"),
        canon(SongAboutEntry.CANON_COMPOSER, "Mara Quinn / Theo Hart"),
        canon(SongAboutEntry.CANON_PRODUCER, "Theo Hart"),
        canon(
            SongAboutEntry.CANON_CREATION_TIME,
            if (zh) "2018 年末录音；写于乐队首次日本巡演" else "Late 2018 (recording); written on the band's first Japan tour",
        ),
        canon(
            SongAboutEntry.CANON_REVIEW,
            if (zh) {
                "《东京晚安》是《玻璃果园》的终曲，写于子午线首次日本巡演最后一晚的酒店房间。一首建立在借来的鼓机上的慢板合成器流行情歌，把与城市的告别变成一句谢谢；最后一段副歌落成一个人声，背景是窗外街道的嗡鸣。"
            } else {
                "The closing track of Glass Orchard, written in a hotel room on the last night of The Meridians' first Japan tour. A slow synth-pop ballad built on a borrowed drum machine, it turns a goodbye to the city into a thank-you note; the final chorus drops to a single voice over the hum of the street outside."
            },
        ),
    )

    private fun canon(key: String, answer: String) = SongAboutEntry(
        titleKey = "goodnight tokyo",
        artistKey = "the meridians",
        albumKey = "glass orchard",
        titleDisplay = "Goodnight, Tokyo",
        artistDisplay = "The Meridians",
        albumDisplay = "Glass Orchard",
        kind = SongAboutEntry.KIND_CANONICAL,
        entryKey = key,
        promptText = null,
        titleText = null,
        answerText = answer,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun notes(zh: Boolean): List<SongNote> {
        val texts = if (zh) {
            listOf(
                12_000L to "鼓机听起来像铁皮屋顶上的雨",
                48_000L to "桥段渐渐铺进最后一段副歌",
                112_000L to "每一盏灯都是我没说出口的话 —— **这句要轻轻唱**",
                147_000L to "像是另一首歌的下半场",
            )
        } else {
            listOf(
                12_000L to "The drum machine sounds like rain on a tin roof",
                48_000L to "The bridge is a slow build into the last chorus",
                112_000L to "Every light a word I never said — **sing this one quietly**",
                147_000L to "Plays like the other half of another song",
            )
        }
        return texts.mapIndexed { i, (at, text) ->
            SongNote(
                id = "n$i",
                trackId = "1",
                content = text,
                createdAt = 1L + i,
                updatedAt = 1L + i,
                title = "Goodnight, Tokyo",
                artist = "The Meridians",
                positionMs = at,
            )
        }
    }
}
