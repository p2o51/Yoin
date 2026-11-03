package com.gpo.yoin.shared.tokens

/**
 * Yoin's design tokens: the one place both apps read their shared numbers from.
 *
 * Lengths are dp on Android and pt on iOS (1 dp = 1 pt). Values are Doubles so
 * iOS gets the exact decimal (0.74, not the Float nearest it); Android turns
 * them into `Dp` / `Float` where it used `Float` literals before (`x.dp`,
 * `x.toFloat()`), which is the same value it had.
 *
 * Changing a value here changes both apps (iosApp/docs/shared-kmp.md).
 * Android checks the values it adopted against the literals they replaced in
 * YoinTokensTest; iOS reads them through the YoinShared framework.
 */
object YoinTokens {

    /** The floating bottom bar (FloatingBottomBar.kt, WindowAdaptiveRuntime.kt; iOS ShellBar). */
    object Bar {
        /** Outer height of the portrait bar's pill surface (row 68 = 48 button + 10 × 2). */
        const val HEIGHT = 68.0

        /** Height of fixed-height children inside the portrait bar's inner row. */
        const val BUTTON_HEIGHT = 48.0

        /** The inner row's horizontal padding, each side. */
        const val ROW_PADDING = 10.0

        /** Portrait bar: horizontal margin to the window edge. */
        const val HORIZONTAL_MARGIN = 16.0

        /** The portrait bar's vertical margin (top and bottom). */
        const val PORTRAIT_VERTICAL_MARGIN = 12.0

        /** Gap between bar islands (split button / pill / nav buttons). */
        const val ITEM_GAP = 8.0

        /** Detail chrome: the Play split button's fixed width. */
        const val SPLIT_WIDTH = 156.0

        /** M3 SplitButton: gap between the two halves and their inner corner radius. */
        const val SPLIT_GAP = 2.0
        const val SPLIT_INNER_CORNER = 4.0

        /** CenteredBar detail pose: the now-playing pill's fixed width. */
        const val DETAIL_PILL_WIDTH = 200.0

        /** The centred bar (Medium / Wide): 60 = 44 button + 8 × 2. */
        const val CENTERED_HEIGHT = 60.0
        const val CENTERED_BUTTON_HEIGHT = 44.0

        /** CenteredBar: gap from the window bottom (above the nav bar). */
        const val CENTERED_BOTTOM_MARGIN = 24.0
        const val CENTERED_BOTTOM_MARGIN_WIDE = 28.0

        /** CenteredBar: horizontal margin inside a narrow pane (bar = pane − 40, max 600). */
        const val CENTERED_HORIZONTAL_MARGIN = 20.0

        /** CenteredBar: width cap. */
        const val CENTERED_MAX_WIDTH = 600.0
    }

    /** Corner radii (Shape.kt YoinArtworkShapes / YoinContainerShapes; iOS continuous corners). */
    object Shape {
        /** 34–60 dp list / queue / sheet thumbnails. */
        const val THUMB = 4.0

        /** ~100–200 dp shelf / grid covers. */
        const val COVER = 4.0

        /** 170–300 dp hero covers. */
        const val HERO = 8.0

        /** The Now Playing main cover. */
        const val NOW_PLAYING_COVER = 16.0

        /** The Now Playing docked (lyrics-stage) 44 dp cover. */
        const val NOW_PLAYING_COVER_DOCKED = 8.0

        /** Filled cards (bento tiles, Home cards, note cards). */
        const val CARD = 16.0

        /** Large section panels. */
        const val PANEL = 20.0

        /** Press bounds of full-width list rows. */
        const val LIST_ROW = 12.0
    }

    /**
     * Now Playing: the phone column's component sizes and the height budget's
     * slots (NowPlayingBudget.kt, NowPlayingMetrics.kt, NowPlayingScreen.kt;
     * iOS NowPlayingView). `*_NOMINAL` is a slot's resting height, `*_MIN`
     * what it compresses to.
     */
    object NowPlaying {
        /** The column's side padding. */
        const val COLUMN_PADDING = 24.0

        /** The column's bottom edge never sits closer than this to the window bottom. */
        const val COLUMN_BOTTOM_FLOOR = 16.0

        /** Gap between the cover and the rating column. */
        const val RATING_GAP = 12.0

        /** The phone's rating column (the 48 capsule in 56). */
        const val PHONE_RATING_COLUMN = 56.0
        const val RATING_BAR_WIDTH = 48.0

        /** The rating bar is the cover's height minus this (8 gap + 44 heart + 6). */
        const val RATING_BAR_INSET = 58.0
        const val RATING_TO_HEART_GAP = 8.0
        const val HEART_SIZE = 44.0
        const val HEART_BOTTOM_GAP = 6.0

        const val PHONE_COVER_MAX = 312.0
        const val PHONE_FOCUS_COVER_MAX = 420.0
        const val COVER_FLOOR = 96.0
        const val ENLARGED_COVER_COMFORT = 168.0

        const val TOP_BAR_NOMINAL = 56.0
        const val TOP_BAR_MIN = 48.0
        const val COVER_GAP_NOMINAL = 16.0
        const val COVER_GAP_MIN = 8.0
        const val TAB_ROW_NOMINAL = 30.0
        const val TAB_ROW_AIR = 10.0
        const val ENLARGED_TOOL_TAB_ROW = 44.0
        const val TAB_GAP_NOMINAL = 4.0
        const val CONTROLS_SLOT_NOMINAL = 148.0
        const val CONTROLS_AIR_MIN = 8.0
        const val HERO_SLOT_NOMINAL = 86.0
        const val HERO_AIR_MIN = 4.0
        const val HERO_ONE_LINE_AIR = 12.0
        const val ACCESSORY_SLOT_NOMINAL = 68.0
        const val ACCESSORY_AIR_MIN = 8.0
        const val ONE_LINE_ROW_NOMINAL = 44.0
        const val ONE_LINE_AIR_MIN = 4.0
        const val LYRIC_FRAME_HYSTERESIS = 8.0

        /** Focus (tap the cover): it grows at most this much, and only when it grows by max(40, 12 %). */
        const val COVER_FOCUS_MAX_SCALE = 1.5
        const val COVER_FOCUS_MIN_GROWTH = 40.0
        const val COVER_FOCUS_MIN_GROWTH_FRACTION = 0.12

        /** LyricsDisplay geometry: list padding, line gap, bottom fade, active-line anchor. */
        const val LYRICS_LIST_PADDING = 12.0
        const val LYRICS_LIST_LINE_GAP = 2.0
        const val LYRICS_FADE_BOTTOM = 24.0
        const val LYRICS_ANCHOR_FRACTION = 0.22

        /** Clean lyric lines the list keeps: phone / panel 2, enlarged 3. */
        const val PHONE_CLEAN_LINES = 2
        const val ENLARGED_CLEAN_LINES = 3

        /** Transport: 56 controls, rows 56 + 4 + 56. */
        const val CONTROL_SIZE = 56.0
        const val CONTROL_ROW_GAP = 4.0

        /** Pills (Queue / Devices / Write): 44 tall; 48 when the Cast button's touch target shows. */
        const val PILL_HEIGHT = 44.0
        const val PILL_ROW_WITH_CAST = 48.0

        /** Hero: the gap between artist and title lines. */
        const val HERO_LINE_GAP = 2.0
        const val WIDE_TITLE_LINE_GAP = 2.0

        /** Lyric lines carry 4 above and below. */
        const val LYRIC_LINE_PADS = 8.0

        /** Line height when a text style has none: font size × this. */
        const val LINE_HEIGHT_FALLBACK = 1.25

        // DualPane (two-column player).
        const val DUAL_PANE_STACK_WIDTH = 312.0
        const val DUAL_PANE_COVER_MIN = 168.0
        const val DUAL_PANE_COVER_FLOOR = 140.0
        const val WIDE_RATING_ROW = 48.0
        const val WIDE_FRAME_NOMINAL = 16.0
        const val WIDE_FRAME_MIN = 8.0
        const val WIDE_BAR_GAP_NOMINAL = 8.0
        const val WIDE_BAR_GAP_MIN = 4.0
        const val WIDE_COVER_TO_RATING_NOMINAL = 12.0
        const val WIDE_COVER_TO_RATING_MIN = 8.0
        const val WIDE_RATING_TO_TRANSPORT_NOMINAL = 24.0
        const val WIDE_RATING_TO_TRANSPORT_MIN = 12.0
        const val WIDE_TRANSPORT_TO_TITLE_NOMINAL = 16.0
        const val WIDE_TRANSPORT_TO_TITLE_MIN = 8.0
        const val WIDE_COLUMN_GAP = 24.0
        const val WIDE_RIGHT_COLUMN_MIN = 320.0

        // Landscape handset.
        const val LANDSCAPE_RATING_ROW = 48.0
        const val LANDSCAPE_RATING_GAP = 12.0
        const val LANDSCAPE_COVER_WIDTH_SHARE = 0.42
        const val LANDSCAPE_COVER_FLOOR = 120.0
        const val LANDSCAPE_COLUMN_GAP = 28.0
        const val LANDSCAPE_FOCUS_RIGHT_MIN = 360.0
    }

    /** Album / playlist / artist detail pages (AlbumDetailScreen.kt, AlbumSpectrum.kt; iOS AlbumDetailView). */
    object Detail {
        /** The hero cover: min(width × fraction, max), this far below the header. */
        const val ALBUM_COVER_WIDTH_FRACTION = 0.74
        const val ALBUM_COVER_MAX = 300.0
        const val ALBUM_COVER_TOP_GAP = 28.0

        /** Strips the cover breaks into for the spectrum bar. */
        const val SPECTRUM_STRIPS = 28
    }

    /**
     * Type: the M3 type scale both apps set in Google Sans Flex (Type.kt
     * YoinTypography; iOS `Font.gsf` / YoinFont), plus the variable-font axes.
     */
    object Typography {
        /** One text style: size and line height (sp / pt), static-cut weight, tracking (sp / pt). */
        class Style internal constructor(
            val size: Double,
            val lineHeight: Double,
            val weight: Int,
            val letterSpacing: Double,
        )

        /** Google Sans Flex is set solid: 0 tracking, except a hair on the two smallest labels. */
        const val LETTER_SPACING = 0.0
        const val SMALL_LABEL_LETTER_SPACING = 0.1

        val displayLarge = Style(57.0, 64.0, 400, LETTER_SPACING)
        val displayMedium = Style(45.0, 52.0, 400, LETTER_SPACING)
        val displaySmall = Style(36.0, 44.0, 400, LETTER_SPACING)
        val headlineLarge = Style(32.0, 40.0, 400, LETTER_SPACING)
        val headlineMedium = Style(28.0, 36.0, 400, LETTER_SPACING)
        val headlineSmall = Style(24.0, 32.0, 400, LETTER_SPACING)
        val titleLarge = Style(22.0, 28.0, 400, LETTER_SPACING)
        val titleMedium = Style(16.0, 24.0, 500, LETTER_SPACING)
        val titleSmall = Style(14.0, 20.0, 500, LETTER_SPACING)
        val bodyLarge = Style(16.0, 24.0, 400, LETTER_SPACING)
        val bodyMedium = Style(14.0, 20.0, 400, LETTER_SPACING)
        val bodySmall = Style(12.0, 16.0, 400, LETTER_SPACING)
        val labelLarge = Style(14.0, 20.0, 500, LETTER_SPACING)
        val labelMedium = Style(12.0, 16.0, 500, SMALL_LABEL_LETTER_SPACING)
        val labelSmall = Style(11.0, 16.0, 500, SMALL_LABEL_LETTER_SPACING)

        /** Google Sans Flex variable axes (wght, wdth, ROND, opsz). opsz null = follow the point size. */
        class Axes internal constructor(
            val weight: Int,
            val width: Double,
            val round: Double,
            val opticalSize: Double?,
        )

        /** GoogleSansFlexRounded: ROND 100 at Medium / SemiBold (lyric current line, Home titles). */
        val roundedMedium = Axes(500, 100.0, 100.0, null)
        val roundedSemiBold = Axes(600, 100.0, 100.0, null)

        /** GrooveType score: wght 690 (album) / 640 (average), ROND 50; wdth 76 for "10.0", else 92. */
        const val SCORE_WEIGHT_ALBUM = 690
        const val SCORE_WEIGHT_AVERAGE = 640
        const val SCORE_ROUND = 50.0
        const val SCORE_WIDTH_FOUR_CHARACTERS = 76.0
        const val SCORE_WIDTH = 92.0

        /** GrooveType caption / unrated word: wght 600. */
        const val CAPTION_WEIGHT = 600

        /** The variable font's opsz range; opsz follows the type size inside it. */
        const val OPTICAL_SIZE_MIN = 6.0
        const val OPTICAL_SIZE_MAX = 144.0
    }

    /**
     * Motion. Android takes its spatial / effects buckets from M3's
     * `MotionScheme.expressive()` / `standard()` (mirrored here for iOS) and
     * its own springs from the values below. Stiffness in s⁻² (Compose), a
     * damping ratio; SwiftUI: `.spring(response: 2π / √stiffness,
     * dampingFraction: damping)`.
     */
    object Motion {
        class Spring internal constructor(val damping: Double, val stiffness: Double)

        // MD3 Expressive scheme (MotionScheme.expressive()).
        val expressiveFastSpatial = Spring(0.6, 800.0)
        val expressiveDefaultSpatial = Spring(0.8, 380.0)
        val expressiveSlowSpatial = Spring(0.8, 200.0)
        val expressiveFastEffects = Spring(1.0, 3800.0)
        val expressiveDefaultEffects = Spring(1.0, 1600.0)
        val expressiveSlowEffects = Spring(1.0, 800.0)

        // MD3 standard scheme (MotionScheme.standard()).
        val standardFastSpatial = Spring(0.9, 1400.0)
        val standardDefaultSpatial = Spring(0.9, 700.0)
        val standardSlowSpatial = Spring(0.9, 300.0)

        /** The Now Playing stage reshape: fast, near-critical. */
        val stageSettle = Spring(0.85, 700.0)

        /** Home edit mode's kick: light, ringing (threshold in normalised kick units). */
        val homeEditKick = Spring(0.3, 450.0)
        const val HOME_EDIT_KICK_THRESHOLD = 0.01

        /** Artwork dissolve: critically damped, slow. */
        val artworkDissolve = Spring(1.0, 45.0)

        /** Seam halftone flow settle (critically damped equivalent). */
        const val SEAM_FLOW_SETTLE_STIFFNESS = 90.0

        /** Predictive back settle: low-bouncy, medium-low stiffness × this. */
        const val PREDICTIVE_BACK_SPEED_MULTIPLIER = 1.3

        // The landing mascot.
        val mascotLand = Spring(0.62, 340.0)
        val mascotGesture = Spring(0.5, 420.0)
        val mascotHop = Spring(0.42, 520.0)
        val mascotShake = Spring(0.18, 520.0)

        /** Fast scroller: hides this long after scrolling and holding stop, sliding toward the edge. */
        const val FAST_SCROLLER_HIDE_DELAY_MS = 1600
        const val FAST_SCROLLER_HIDE_SLIDE = 32.0
    }

    /**
     * iOS Liquid Glass: the shipped look of each glass surface (the Glass
     * Lab's Balanced preset, iOS GlassLab.swift). Android draws no glass; the
     * values live here so the preset is data, not Swift literals.
     */
    object Glass {
        /**
         * One surface. [variant]: regular / clear / material / none (a plain fill
         * of the tint). [role]: the colour role the tint takes ("auto" = the role
         * Android uses there).
         */
        class Surface internal constructor(
            val id: String,
            val variant: String = "regular",
            val tintOn: Boolean = true,
            val role: String = "auto",
            val tintOpacity: Double,
            val interactive: Boolean = true,
            /** Corner radius; null = the shape's own. */
            val cornerRadius: Double? = null,
            val shadowOpacity: Double = 0.0,
            val shadowRadius: Double = 12.0,
            val shadowY: Double = 6.0,
            /** Shadow under the icon / label on this surface, for contrast. */
            val contrastShadow: Double = 0.0,
        )

        class Extra internal constructor(val id: String, val value: Double)

        /** The Balanced preset (the default since 2026-11-03), one entry per surface. */
        val balanced: List<Surface> = listOf(
            Surface("barUnselected", tintOn = false, tintOpacity = 0.0),
            // 51, 2026-11-03: the selected button's color goes into the glass.
            Surface("barSelected", tintOpacity = 0.85),
            Surface("miniPlayer", tintOn = false, tintOpacity = 0.0),
            Surface("transportPrimary", tintOpacity = 0.55, shadowOpacity = 0.18, contrastShadow = 0.22),
            Surface("transportSecondary", tintOn = false, tintOpacity = 0.0),
            Surface("heart", tintOn = false, tintOpacity = 0.0),
            // 51, 2026-11-03: rating track and the Queue / Devices / Write pills are glass.
            Surface("ratingTrack", role = "surfaceVariant", tintOpacity = 0.3, interactive = false),
            Surface("npPills", role = "primary", tintOpacity = 0.55),
            Surface("npBackground", role = "background", tintOpacity = 0.5, interactive = false, cornerRadius = 44.0),
            Surface("homeCards", variant = "material", tintOpacity = 0.45, interactive = false, cornerRadius = 16.0),
        )

        /** The Balanced preset's non-glass parameters (Glass Lab "extras"). */
        val balancedExtras: List<Extra> = listOf(
            Extra("barSelectedStyle", 1.0),
            Extra("barSelectionPill", 0.55),
            Extra("washOpacity", 0.25),
            Extra("washAmplitude", 4.0),
            Extra("containerSpacing", 3.0),
            Extra("lyricsUnder", 0.0),
            Extra("ratingFill", 1.0),
            Extra("ambientStrength", 0.55),
            Extra("ambientBlur", 90.0),
            Extra("neighbourStrength", 1.0),
            Extra("coverBlur", 26.0),
            Extra("coverStrength", 0.85),
            // The album page's pulled-up top bar: 0 the cover's spectrum (Android),
            // 1 Liquid Glass, 2 the system's progressive scroll-edge blur.
            Extra("detailTopBar", 0.0),
        )

        fun balancedSurface(id: String): Surface? = balanced.firstOrNull { it.id == id }

        fun balancedExtra(id: String): Double? = balancedExtras.firstOrNull { it.id == id }?.value
    }
}
