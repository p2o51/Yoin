/// Yoin's shared numbers, mirrored from `:shared` YoinTokens.kt (iOS branch,
/// shared/src/commonMain/.../tokens/YoinTokens.kt). Lengths are logical
/// pixels (1 dp = 1 vp = 1 lp). When a value changes there, change it here.
library;

/// The floating bottom bar (FloatingBottomBar.kt, WindowAdaptiveRuntime.kt; iOS ShellBar).
abstract final class BarTokens {
  /// The bar surface's corner (MaterialTheme.shapes.extraLarge). Dart-only.
  static const double corner = 28;

  /// Bar glyph size and glyph → label gap (YoinButtonGroup BarIconSize / BarIconLabelRow). Dart-only.
  static const double iconSize = 24;
  static const double iconLabelGap = 8;

  /// Outer height of the portrait bar's pill surface (row 68 = 48 button + 10 × 2).
  static const double height = 68;

  /// Height of fixed-height children inside the portrait bar's inner row.
  static const double buttonHeight = 48;

  /// The inner row's horizontal padding, each side.
  static const double rowPadding = 10;

  /// Portrait bar: horizontal margin to the window edge.
  static const double horizontalMargin = 16;

  /// The portrait bar's vertical margin (top and bottom).
  static const double portraitVerticalMargin = 12;

  /// Gap between bar islands (split button / pill / nav buttons).
  static const double itemGap = 8;

  /// Detail chrome: the Play split button's fixed width.
  static const double splitWidth = 156;

  /// M3 SplitButton: gap between the two halves and their inner corner radius.
  static const double splitGap = 2;
  static const double splitInnerCorner = 4;

  /// CenteredBar detail pose: the now-playing pill's fixed width.
  static const double detailPillWidth = 200;

  /// The centred bar (Medium / Wide): 60 = 44 button + 8 × 2.
  static const double centeredHeight = 60;
  static const double centeredButtonHeight = 44;

  /// CenteredBar: gap from the window bottom (above the nav bar).
  static const double centeredBottomMargin = 24;
  static const double centeredBottomMarginWide = 28;

  /// Floating bar → the content above it (FloatingBarContentGap): part of the centred bar's reserve.
  static const double floatingContentGap = 12;

  /// CenteredBar: horizontal margin inside a narrow pane (bar = pane − 40, max 600).
  static const double centeredHorizontalMargin = 20;

  /// CenteredBar: width cap.
  static const double centeredMaxWidth = 600;

  /// The merged bar (Wide detail column open) spans both columns: up to 720.
  static const double mergedMaxWidth = 720;
}

/// Now Playing: the phone column's component sizes and the height budget's slots (NowPlayingBudget.kt, NowPlayingMetrics.kt, NowPlayingScreen.kt; iOS NowPlayingView). `_NOMINAL` is a slot's resting height, `_MIN` what it compresses to.
abstract final class NowPlayingTokens {
  /// The column's side padding.
  static const double columnPadding = 24;

  /// The column's bottom edge never sits closer than this to the window bottom.
  static const double columnBottomFloor = 16;

  /// Gap between the cover and the rating column.
  static const double ratingGap = 12;

  /// The phone's rating column (the 48 capsule in 56).
  static const double phoneRatingColumn = 56;
  static const double ratingBarWidth = 48;

  /// The rating bar is the cover's height minus this (8 gap + 44 heart + 6).
  static const double ratingBarInset = 58;
  static const double ratingToHeartGap = 8;
  static const double heartSize = 44;
  static const double heartBottomGap = 6;

  static const double phoneCoverMax = 312;
  static const double phoneFocusCoverMax = 420;
  static const double coverFloor = 96;
  static const double enlargedCoverComfort = 168;

  static const double topBarNominal = 56;
  static const double topBarMin = 48;
  static const double coverGapNominal = 16;
  static const double coverGapMin = 8;
  static const double tabRowNominal = 30;
  static const double tabRowAir = 10;
  static const double enlargedToolTabRow = 44;
  static const double tabGapNominal = 4;
  static const double controlsSlotNominal = 148;
  static const double controlsAirMin = 8;
  static const double heroSlotNominal = 86;
  static const double heroAirMin = 4;
  static const double heroOneLineAir = 12;
  static const double accessorySlotNominal = 68;
  static const double accessoryAirMin = 8;
  static const double oneLineRowNominal = 44;
  static const double oneLineAirMin = 4;
  static const double lyricFrameHysteresis = 8;

  /// Focus (tap the cover): it grows at most this much, and only when it grows by max(40, 12 %).
  static const double coverFocusMaxScale = 1.5;
  static const double coverFocusMinGrowth = 40;
  static const double coverFocusMinGrowthFraction = 0.12;

  /// LyricsDisplay geometry: list padding, line gap, bottom fade, active-line anchor.
  static const double lyricsListPadding = 12;
  static const double lyricsListLineGap = 2;
  static const double lyricsFadeBottom = 24;
  static const double lyricsAnchorFraction = 0.22;

  /// Clean lyric lines the list keeps: phone / panel 2, enlarged 3.
  static const int phoneCleanLines = 2;
  static const int enlargedCleanLines = 3;

  /// Transport: 56 controls, rows 56 + 4 + 56.
  static const double controlSize = 56;
  static const double controlRowGap = 4;

  /// Pills (Queue / Devices / Write): 44 tall; 48 when the Cast button's touch target shows.
  static const double pillHeight = 44;
  static const double pillRowWithCast = 48;

  /// Hero: the gap between artist and title lines.
  static const double heroLineGap = 2;
  static const double wideTitleLineGap = 2;

  /// Lyric lines carry 4 above and below.
  static const double lyricLinePads = 8;

  /// Line height when a text style has none: font size × this.
  static const double lineHeightFallback = 1.25;

  /// DualPane (two-column player).
  static const double dualPaneStackWidth = 312;
  static const double dualPaneCoverMin = 168;
  static const double dualPaneCoverFloor = 140;
  static const double wideRatingRow = 48;
  static const double wideFrameNominal = 16;
  static const double wideFrameMin = 8;
  static const double wideBarGapNominal = 8;
  static const double wideBarGapMin = 4;
  static const double wideCoverToRatingNominal = 12;
  static const double wideCoverToRatingMin = 8;
  static const double wideRatingToTransportNominal = 24;
  static const double wideRatingToTransportMin = 12;
  static const double wideTransportToTitleNominal = 16;
  static const double wideTransportToTitleMin = 8;
  static const double wideColumnGap = 24;
  static const double wideRightColumnMin = 320;

  /// Landscape handset.
  static const double landscapeRatingRow = 48;
  static const double landscapeRatingGap = 12;
  static const double landscapeCoverWidthShare = 0.42;
  static const double landscapeCoverFloor = 120;
  static const double landscapeColumnGap = 28;
  static const double landscapeFocusRightMin = 360;

  /// Phone column details (NowPlayingScreen.kt, PlaybackControls.kt, RatingSlider.kt).
  /// StageTopBar: the ⌄ ChevronDown glyph.
  static const double chevronGlyph = 28;

  /// CompactTextTabs: tabs 12 apart; the selected one stretches × this from its leading edge.
  static const double tabGap = 12;
  static const double tabSelectedStretch = 1.08;

  /// Transport rows: gap between PAUSE / Next, and Previous → the progress row.
  static const double transportGap = 8;

  /// PlaybackTimeLabel: min widths of the elapsed / remaining labels, and their gap to the bar.
  static const double timeLabelMinStart = 44;
  static const double timeLabelMinEnd = 52;
  static const double timeLabelGap = 8;

  /// Hero on one row (short screens): artist 12 after the title, capped at this share of the row.
  static const double heroOneLineGap = 12;
  static const double heroOneLineArtistShare = 0.4;

  /// RatingSlider: the number's colour crosses onSurfaceVariant → onPrimary from this fill to full.
  static const double ratingLabelCrossover = 0.8;

  /// The Expanded stage's tab group row (FullscreenTabGroup / YoinTabGroup) and its gap to the page.
  static const double expandedTabRow = 52;
  static const double expandedTabGap = 12;
  static const double tabGroupGap = 8;

  /// StageTabs: the text tabs lift this far as they fade out, the group settles from this far below;
  /// a pressed text tab dips to [tabPressedStretch], an unselected one reads at [tabIdleAlpha].
  static const double tabCompactLift = 6;
  static const double tabGroupDrop = 8;
  static const double tabPressedStretch = 0.92;
  static const double tabIdleAlpha = 0.5;

  /// BottomPills: the gap between Queue and Write.
  static const double pillGap = 8;
}

/// Now Playing's notes (NoteContent.kt NoteLine / NoteSortToggle / NoteWriteBarDefaults,
/// NoteFullscreenPane.kt, compact/NoteCompactPane.kt).
abstract final class NowPlayingNoteTokens {
  /// NoteLine: the fixed time-stamp column; rows' vertical padding; the un-lit scale.
  static const double stampColumn = 40;
  static const double glanceRowPadV = 4;
  static const double pageRowPadV = 6;
  static const double glanceInactiveScale = 0.94;
  static const double pageInactiveScale = 0.97;
  static const double metaGap = 2;

  /// A legacy note with no moment: a hollow dot in the stamp column.
  static const double hollowDot = 6;
  static const double hollowDotStroke = 1.5;
  static const double hollowDotInset = 2;

  /// Compact window: line gap, list padding, edge fades.
  static const double compactLineGap = 2;
  static const double compactListPadV = 12;
  static const double compactFadeTop = 16;
  static const double compactFadeBottom = 24;

  /// Expanded page: list padding and fades, header gap, the undo row, the empty label's room.
  static const double pageTopPadding = 4;
  static const double pageBottomPadding = 48;
  static const double pageFadeTop = 24;
  static const double pageFadeBottom = 64;
  static const double headerBottomGap = 6;
  static const double deletedRowHeight = 48;
  static const double emptyMinHeight = 64;

  /// The "Writing…" row's dashed underline.
  static const double draftDash = 4;
  static const double draftDashGap = 3;
  static const double draftStroke = 1;
  static const double draftUnderlineGap = 3;

  /// NoteSortToggle: the capsule's inset and each option's padding.
  static const double sortInset = 3;
  static const double sortOptionPadH = 10;
  static const double sortOptionPadV = 4;

  /// NoteWriteBar: resting capsule, the gap above it in the accessory slot, corners, insets.
  static const double barIdleHeight = 56;
  static const double barTopGap = 12;
  static const double barIdleCorner = 28;
  static const double barOpenCorner = 20;
  static const double barPadStart = 10;
  static const double barPadEnd = 14;
  static const double barPadV = 10;
  static const double barItemGap = 10;
  static const double barFooterGap = 8;
  static const double barFooterInset = 4;
  static const int barOpenMinLines = 2;
  static const int barOpenMaxLines = 6;
  static const double barVeilAlpha = 0.92;
  static const double barWriteGlyph = 20;

  /// The bar's stamp chip.
  static const double stampChipHeight = 36;
  static const double stampChipPadH = 12;
  static const double stampChipGlyph = 16;
  static const double stampChipGlyphGap = 6;
  static const double stampChipTintAlpha = 0.12;

  /// JournalSavePill.
  static const double savePillPadH = 16;
  static const double savePillPadV = 8;
  static const double savePillGlyph = 16;
  static const double savePillGap = 6;
}

/// Album / playlist / artist detail pages (AlbumDetailScreen.kt, AlbumSpectrum.kt; iOS AlbumDetailView, AlbumScrapbookView, ArtistDetailView).
abstract final class DetailTokens {
  /// The hero cover: min(width × fraction, max), this far below the header.
  static const double albumCoverWidthFraction = 0.74;
  static const double albumCoverMax = 300;
  static const double albumCoverTopGap = 28;

  /// Strips the cover breaks into for the spectrum bar.
  static const int spectrumStrips = 28;

  /// Detail headers: back button → title (AlbumDetailScreen.kt, ArtistDetailScreen.kt).
  static const double headerTitleGap = 14;

  /// Pulled up, the cover docks as a stamp at the title row's end (AlbumSpectrumBar.kt).
  static const double albumStamp = 48;
  static const double albumStampEndInset = 16;

  /// The title gives up the stamp plus this gap when pulled up (AlbumDetailScreen.kt).
  static const double albumBarTitleGap = 12;

  /// Playlist header: room the ⋮ takes at the end, and the stamp's extra gap before it (PlaylistDetailScreen.kt).
  static const double playlistMenuReserve = 40;
  static const double playlistMenuGap = 4;

  /// Album page 2, the scrapbook (AlbumScrapbookPage.kt).
  /// Pieces sit this far apart down the page.
  static const double scrapbookPieceGap = 28;

  /// Opening cover, and the score emblem overhanging it by these shares of its size (x / y).
  static const double scrapbookCover = 148;
  static const double scrapbookBadge = 64;
  static const double scrapbookBadgeOverhangX = 0.3;
  static const double scrapbookBadgeOverhangY = 0.24;

  /// A track's notes ride this far up over its ticket.
  static const double scrapbookPieceOverlap = 12;

  /// The album-note sticky tucks this far over the review; its width cap.
  static const double scrapbookStickyTuck = 6;
  static const double scrapbookStickyMaxWidth = 280;

  /// Ticket: the number stub's width (perforation at its edge) and the notch radius.
  static const double scrapbookTicketStub = 48;
  static const double scrapbookTicketNotch = 7;

  /// Dot paper: pitch and dot radius.
  static const double scrapbookDotSpacing = 16;
  static const double scrapbookDotRadius = 1.2;

  /// scrapLayer: while the pager drags, a piece trails by depth × this share of the page and leans this much further.
  static const double scrapbookParallaxShare = 0.12;
  static const double scrapbookLeanDegrees = 3;

  /// Artist page (ArtistDetailScreen.kt, DetailBackdrops.kt).
  /// Round portrait: min(width × fraction, max).
  static const double artistPortraitWidthFraction = 0.52;
  static const double artistPortraitMax = 216;

  /// The pinwheel band is the portrait plus this tall.
  static const double artistBandExtra = 150;

  /// The Yoin mark behind the portrait: portrait × scale; rests at this angle, turns this much per px scrolled.
  static const double artistMarkScale = 2.1;
  static const double artistMarkRestDegrees = 30;
  static const double artistMarkDegreesPerPx = 0.06;

  /// Discography: the year column's width, and the releases shown before "Show all".
  static const double artistYearColumn = 48;
  static const int artistCollapsedCount = 8;

  /// The column's bottom padding, so the last release clears the bar.
  static const double artistBottomPadding = 120;
}

/// Android MetaLine (component/MetaLine.kt): groups never joined by a glyph.
abstract final class MetaLineTokens {
  /// Gap between groups.
  static const double groupGap = 12;

  /// A Stat's number → unit gap.
  static const double innerGap = 4;

  /// A Stat's unit (and a muted group) at this alpha of the ink.
  static const double mutedAlpha = 0.6;
}

/// An album's version tag after its name (component/AlbumVersionTitle.kt).
abstract final class AlbumVersionTagTokens {
  /// Name → tag (TagGap).
  static const double gap = 6;
}

/// Home's phone feed (HomeEditorialContent.kt, HomeRowPresets.kt, HomeWidgetGrid.kt, RediscoverSection.kt; iOS HomeView).
abstract final class HomeTokens {
  /// The greeting / title keep this clear of the account button (HomeMemoryPill.kt).
  static const double headerTitleBreathing = 24;

  /// Activities bento: row height and the gap between cards (HomeRowPresets.kt).
  static const double activityRowHeight = 118;
  static const double activityRowGap = 10;

  /// Card container = lerp(surfaceContainerLow, backdrop base, this).
  static const double cardWashFraction = 0.30;

  /// Jump Back In: the 1×1 cover, and the grid's column / row gaps (HomeWidgetGrid.kt).
  static const double jbiCover = 100;
  static const double jbiColumnGap = 12;
  static const double jbiRowGap = 16;

  /// Recently Added: track-tile cover, album-card cover, and the gap inside the track grid.
  static const double recentlyAddedTrackCover = 52;
  static const double recentlyAddedAlbumCover = 82;
  static const double recentlyAddedTileGap = 8;

  /// Rediscover card (RediscoverSection.kt): height, backdrop, padding, shelf gap.
  static const double rediscoverCardHeight = 132;
  static const double rediscoverBackdrop = 104;
  static const double rediscoverPadding = 14;
  static const double rediscoverGap = 12;

  /// Rediscover score badge: height, horizontal padding, how far it hangs past the backdrop.
  static const double rediscoverBadgeHeight = 24;
  static const double rediscoverBadgePadding = 8;
  static const double rediscoverBadgeHang = 6;
}

/// Home's Memories bubble (Android ui/home/HomeMemoryBubble.kt): the resting
/// ⌄ at the page's top centre and the bubble it speaks from.
abstract final class HomeBubbleTokens {
  /// The body: one or two lines where the header's title was; fully round ends.
  static const double bodyHeight = 60;
  static const double corner = 30;

  /// The left round block (a memory's cover, a tip's symbol tile), its inset and the gap to the lines.
  static const double coverSize = 40;
  static const double coverInset = 15;
  static const double coverTitleGap = 12;
  static const double tipIconSize = 22;

  /// The lines stop this far before the body's right end.
  static const double contentTrail = 20;

  /// With this much room beside the cover, a memory says when it was written too.
  static const double saysMoreFrom = 200;

  /// The tail: its sharp tip this far above the chevron, the chevron this far
  /// above the body's top, each flank's base this far either side of it, and
  /// how the flanks leave the tip (out, then down).
  static const double tipAboveChevron = 26;
  static const double chevronAboveBody = 10;
  static const double tailFlank = 46;
  static const double tipLean = 6;
  static const double tipDrop = 18;

  /// The body ends this far right of the chevron; at least [minLeft] left of
  /// it, at most [maxWidth] wide.
  static const double reach = 80;
  static const double minLeft = 150;
  static const double maxWidth = 440;

  /// The resting ⌄: its touch box, glyph, distance below the status bar,
  /// the pull's nudge, and its ink at rest (full inside a speaking bubble).
  static const double arrowTouch = 48;
  static const double arrowIcon = 20;
  static const double arrowBelowTop = 2;
  static const double arrowPullNudge = 2;
  static const double arrowRestAlpha = 0.62;

  /// The arrow fades over the first stretch of scroll (the pull only works at the top).
  static const double arrowScrollFade = 48;

  /// Flying back into the arrow: shrinks by this much toward the tail's tip and lifts this far.
  static const double flyShrink = 0.75;
  static const double flyLift = 20;

  /// Body colour = lerp(surfaceContainerHigh, the cover's base, this).
  static const double coverWash = 0.30;
}

/// Memories, the album-first journal pulled down from Home (Android
/// BackMotionTokens.Memories*, MemoriesTopBarTokens, MemoryCardTokens,
/// MemoryDiaryTokens, MemoriesLayoutTokens, MemoriesMorphTokens).
abstract final class MemoriesTokens {
  // ---- the reveal q (Home ⇄ Memories) ----
  /// Pull from Home: release past this fraction of the page, or faster than
  /// [revealFlingFraction] page heights a second, opens (RevealState defaults).
  static const double revealPositionThreshold = 0.5;
  static const double revealFlingFraction = 1.6;

  /// Overshoot past closed saturates at this fraction (iOS rubber band).
  static const double revealRubberBand = 0.30;

  /// Retreat to Home from the card body: commit past this travel (also the
  /// system back's full travel) or an upward release faster than [dismissFling] dp/s.
  static const double dismissTrigger = 112;
  static const double dismissFling = 600;

  /// The same from the 64 dp top bar.
  static const double barDismissTrigger = 56;
  static const double barDismissFling = 450;

  /// A flick back the other way at this speed (dp/s) returns, past any threshold.
  static const double flickBack = 350;

  /// Home waits behind the open deck at this scale / alpha.
  static const double homeBehindScale = 0.94;
  static const double homeBehindAlpha = 0.5;

  /// The bar leaves with the reveal: alpha 1 − hide × this, and slides this far.
  static const double barHideAlphaGain = 1.4;
  static const double barHideTravel = 120;

  // ---- the diary p (card ⇄ diary) ----
  /// p = travel / this.
  static const double diaryMorphDistance = 320;

  /// The first dp of a pull past the top of the diary text move p at half speed.
  static const double diaryPullBand = 24;

  /// The card's own pull-down: 0.3× rubber band, floored at −90 dp.
  static const double diaryRubberBand = 0.3;
  static const double diaryRubberBandFloor = 90;

  /// Touch slop of the router (prototype 8 dp).
  static const double touchSlop = 8;

  // ---- layout ----
  static const double mediumFrom = 600;
  static const double shortBelow = 760;
  static const double phoneCover = 256;
  static const double phoneCoverShort = 168;
  static const double phoneSeal = 96;
  static const double phoneSealShort = 72;
  static const double phoneAir1 = 59;
  static const double phoneAir1Short = 24;
  static const double mediumCoverMax = 360;
  static const double mediumCoverOffset = 580;
  static const double mediumSealRatio = 0.375;
  static const double sealMin = 96;
  static const double sealMax = 124;
  static const double mediumAir1Min = 24;
  static const double mediumAir1Max = 120;
  static const double mediumAir1Ratio = 0.2;

  // ---- spread (≥ 900 wide; phone landscape) ----
  static const double spreadFrom = 900;
  static const double landscapeBelow = 480;
  static const double spreadLeftRatio = 0.46;
  static const double spreadLeftMin = 400;
  static const double spreadLeftMax = 600;
  static const double spreadGutters = 104;
  static const double spreadMeasureMin = 360;
  static const double spreadMeasureMax = 560;
  static const double spreadCoverMin = 300;
  static const double spreadCoverOfLeft = 96;
  static const double spreadCoverOfHeight = 386;
  static const double spreadSealRatio = 0.4;
  static const double spreadMinCover = 200;
  static const double landscapeLeftMin = 280;
  static const double landscapeMeasureMin = 240;
  static const double landscapeMinCover = 88;
  static const double landscapeSealRatio = 0.55;
  static const double landscapeSealMin = 48;
  static const double landscapeSealMax = 72;
  static const double pillStartSpread = 32;
  static const double dotsEndSpread = 26;

  /// The left page: 32 each side; its citation 420 and album row 400 at most.
  static const double spreadPagePadding = 32;
  static const double spreadCiteMax = 420;
  static const double spreadAlbumMax = 400;
  static const double spreadGoHeight = 48;

  /// The right page: 40 in from the left page, ≥ 24 at its end, 44 under the ending.
  static const double spreadRightStart = 40;
  static const double spreadRightEndMin = 24;
  static const double spreadRightBottom = 44;
  static const double spreadCentreClearance = 160;
  static const double spreadRightMinTop = 24;
  static const double spreadLandscapeParagraphGap = 28;

  /// Ladder: the cover steps down by this until the left page fits.
  static const double spreadLadderStep = 8;

  /// Medium: the card in a 480 column, the diary in a 640 one.
  static const double cardColumn = 480;
  static const double diaryColumn = 640;

  // ---- top bar ----
  static const double barHeight = 64;
  static const double pillHeight = 36;
  static const double pillWidth = 88;
  static const double pillWidthDiary = 36;
  static const double hitHeight = 48;
  static const double pillStartPhone = 16;
  static const double pillStartMedium = 24;
  static const double dotsEndPhone = 12;
  static const double dotsEndMedium = 18;
  static const double dotPitch = 18;
  static const double dotBox = 32;
  static const double dotOverhang = 7;
  static const double dotSize = 7;
  static const double dotSizeCurrent = 12;
  static const double barCover = 40;
  static const double slotClearOfDots = 12;

  // ---- card ----
  static const double cardSidePadding = 28;
  static const double sealOverhangRight = 0.3;
  static const double sealOverhangBottom = 0.24;
  static const double excerptClear = 24;
  static const double excerptGap = 16;
  static const double excerptAttributionGap = 8;
  static const double buttonHeight = 48;
  static const double buttonGap = 10;
  static const double teaserBottom = 40;
  static const double teaserBottomShort = 28;
  static const double diaryTonalAlpha = 0.14;
  static const double coverCornerCard = 8;
  static const double coverCornerBar = 4;

  // ---- diary ----
  static const double diarySide = 24;
  static const double diaryTopInset = 12;
  static const double diaryBottomInset = 100;
  static const double diaryEmblem = 48;
  static const double diaryEmblemGap = 14;
  static const double diaryHeadGap = 28;
  static const double diaryHeadGapWithAsk = 24;
  static const double diaryParagraphTop = 10;
  static const double diaryRowMin = 48;
  static const double diaryRowBleed = 12;
  static const double diaryEndGap = 56;
  static const double diaryRunOut = 28;
  static const double diaryRunOutBottom = 22;
  static const double diaryStatGap = 48;
  static const double diaryGoTop = 32;
  static const double diaryBottomFade = 40;
  static const double diaryLinerColumn = 40;
  static const double diaryWriterInset = 14;
  static const double diaryRailWidth = 2;

  // ---- morph (choreography windows of p, not back thresholds) ----
  static const double titleHold = 0.2;
  static const double titleSwapFrom = 0.42;
  static const double titleSwapTo = 0.78;
  static const double emblemSwapFrom = 0.6;
  static const double emblemSwapTo = 0.95;
  static const double albumRowLift = 36;
  static const double albumRowFadeTo = 0.32;
  static const double teaserDrop = 56;
  static const double teaserFadeTo = 0.3;
  static const double excerptFadeTo = 0.26;
  static const double blockFadeFrom = 0.26;
  static const double blockFadeStep = 0.04;
  static const double blockFadeTo = 0.92;
  static const double blockStagger = 28;
  static const double blockLagExtra = 36;
  static const double slotAFadeTo = 0.45;
  static const double slotALift = 6;
  static const double slotBFadeFrom = 0.55;
  static const double slotBFadeTo = 0.92;
  static const double slotBDrop = 10;
  static const double pillNarrowTo = 0.6;
  static const double pillLabelFadeTo = 0.4;
  static const double reducedSwap = 0.5;

  /// Fallback A: the album-name title flies to the bar's album name and is gone by here.
  static const double albumTitleFadeFrom = 0.36;
  static const double albumTitleFadeTo = 0.72;

  /// The 96's "Avg." caption leaves first (by this much of its travel).
  static const double captionFadeTo = 0.4;

  /// The flying cover hands over to the bar's 40 dp cover inside
  /// [coverFloorFrom]…1 / [coverSwapFrom]…[coverSwapTo] of its flight.
  static const double coverSwapFrom = 0.62;
  static const double coverSwapTo = 0.9;
  static const double coverFloorFrom = 0.75;

  /// The page's own cover belongs to the bar once it flies: its alpha meets the bar's parallax this fast.
  static const double coverParallaxLead = 5;

  /// Diary blocks past this index share its timing.
  static const int blockMaxIndex = 6;

  /// Frozen collapse (a diary closed with its title under the bar): the cover
  /// waits in the bar until the sinking text is gone (fp = p / [frozenCoverSpan]),
  /// the text fades over [frozenTextFadeFrom] + k·step … [frozenTextFadeTo], the
  /// card's title and emblem come back over [frozenCardFadeFrom]…[frozenCardFadeTo].
  static const double frozenCoverSpan = 0.55;
  static const double frozenTextFadeFrom = 0.55;
  static const double frozenTextFadeStep = 0.02;
  static const double frozenTextFadeTo = 0.95;
  static const double frozenCardFadeFrom = 0.3;
  static const double frozenCardFadeTo = 0.55;

  /// Frozen: the diary's scroll returns to its top, unseen, below this p.
  static const double frozenResetBelow = 0.05;

  /// The diary is composed (and drawn) above this p.
  static const double diaryHiddenBelow = 0.0005;

  /// The card's title and buttons take taps only below this p.
  static const double cardInputBelow = 0.05;

  /// Slot B's album line (15 × 1.3) and artist line (13 × 1.3): fallback A's flight lands on the first.
  static const double barAlbumLine = 19.5;
  static const double barArtistLine = 16.9;

  /// Slot B: the album column, then this gap and the 18 dp ⌄.
  static const double slotBChevronGap = 6;
  static const double slotBChevron = 18;

  /// The award starts once the reveal is this open (q ≤ this).
  static const double awardRevealGate = 0.15;
}

/// Library root, Compact (LibraryScreen.kt, LibraryFilterRow.kt, ExpressiveComponents.kt, SongListItem.kt; iOS LibraryView, SearchView).
abstract final class LibraryTokens {
  /// ExpressiveSegmentChip: height, corner, padding (h / v), gap between chips.
  static const double chipHeight = 44;
  static const double chipCorner = 18;
  static const double chipPaddingH = 16;
  static const double chipPaddingV = 10;
  static const double chipGap = 8;

  /// LibraryFilterRow: the ✕ glyph.
  static const double clearIcon = 20;

  /// The grid: column / row gaps, top padding, cover → title.
  static const double gridGutterH = 12;
  static const double gridGutterV = 16;
  static const double gridTopPadding = 8;
  static const double coverToTitle = 5;

  /// floatingBottomGroupContentPadding: room the list keeps over the bottom bar.
  static const double bottomGroupPadding = 108;

  /// LibraryScrollerTopInset: the fast scroller's track starts this far into the grid.
  static const double scrollerTopInset = 8;

  /// LibrarySortRow: the press area bleeds this far past the label (padded, then pulled back).
  static const double sortRowPressBleed = 8;

  /// SongListItem: padding (h / v), the Thumb cover, cover → text.
  static const double rowPaddingH = 16;
  static const double rowPaddingV = 10;
  static const double rowThumb = 48;
  static const double rowGap = 12;

  /// ExpressiveMetaPill: padding (h / v), its surfaceContainerHighest alpha.
  static const double metaPillPaddingH = 10;
  static const double metaPillPaddingV = 6;
  static const double metaPillAlpha = 0.9;

  /// EmptyState panel: padding (h / v), its surfaceContainerHighest alpha.
  static const double emptyStatePaddingH = 20;
  static const double emptyStatePaddingV = 18;
  static const double emptyStateAlpha = 0.72;
}

/// Library search (LibraryScreen.kt search results; iOS SearchView).
abstract final class SearchTokens {
  /// LibrarySearchFilterRow's vertical padding: it sits this far under the field.
  static const double filterRowPaddingV = 8;

  /// SearchResultsBottomReserve.
  static const double resultsBottomReserve = 24;

  /// SectionHeader: padding above / below.
  static const double sectionHeaderTop = 18;
  static const double sectionHeaderBottom = 6;
}

/// Settings' account list (HarmonyOS v1; the Kotlin side has no Settings
/// tokens yet — add these there when it follows).
abstract final class SettingsTokens {
  static const double pagePadding = 16;
  static const double sectionTop = 8;
  static const double sectionLabelInset = 8;
  static const double sectionLabelGap = 12;
  static const double rowPadding = 16;
  static const double rowGap = 2;
}

/// Corner radii (Shape.kt YoinArtworkShapes / YoinContainerShapes).
abstract final class ShapeTokens {
  static const double thumb = 4;
  static const double cover = 4;
  static const double hero = 8;
  static const double nowPlayingCover = 16;
  static const double card = 16;
  static const double panel = 20;
  static const double listRow = 12;
}

/// One text style: size and line height, static-cut weight, tracking.
final class TypeStyleToken {
  const TypeStyleToken(this.size, this.lineHeight, this.weight, this.letterSpacing);

  final double size;
  final double lineHeight;
  final int weight;
  final double letterSpacing;
}

/// The M3 type scale set in Google Sans Flex (Type.kt YoinTypography).
abstract final class TypeTokens {
  /// Set solid: 0 tracking, except a hair on the two smallest labels.
  static const double letterSpacing = 0;
  static const double smallLabelLetterSpacing = 0.1;

  static const displayLarge = TypeStyleToken(57, 64, 400, letterSpacing);
  static const displayMedium = TypeStyleToken(45, 52, 400, letterSpacing);
  static const displaySmall = TypeStyleToken(36, 44, 400, letterSpacing);
  static const headlineLarge = TypeStyleToken(32, 40, 400, letterSpacing);
  static const headlineMedium = TypeStyleToken(28, 36, 400, letterSpacing);
  static const headlineSmall = TypeStyleToken(24, 32, 400, letterSpacing);
  static const titleLarge = TypeStyleToken(22, 28, 400, letterSpacing);
  static const titleMedium = TypeStyleToken(16, 24, 500, letterSpacing);
  static const titleSmall = TypeStyleToken(14, 20, 500, letterSpacing);
  static const bodyLarge = TypeStyleToken(16, 24, 400, letterSpacing);
  static const bodyMedium = TypeStyleToken(14, 20, 400, letterSpacing);
  static const bodySmall = TypeStyleToken(12, 16, 400, letterSpacing);
  static const labelLarge = TypeStyleToken(14, 20, 500, letterSpacing);
  static const labelMedium = TypeStyleToken(12, 16, 500, smallLabelLetterSpacing);
  static const labelSmall = TypeStyleToken(11, 16, 500, smallLabelLetterSpacing);

  /// The variable font's opsz range; opsz follows the type size inside it.
  static const double opticalSizeMin = 6;
  static const double opticalSizeMax = 144;
}

/// A spring: damping ratio and stiffness (s⁻², Compose units, mass 1).
final class SpringToken {
  const SpringToken(this.damping, this.stiffness);

  final double damping;
  final double stiffness;
}

/// Motion. The spatial / effects buckets are M3's MotionScheme.expressive()
/// and standard(); the rest are Yoin's own springs.
abstract final class MotionTokens {
  static const expressiveFastSpatial = SpringToken(0.6, 800);
  static const expressiveDefaultSpatial = SpringToken(0.8, 380);
  static const expressiveSlowSpatial = SpringToken(0.8, 200);
  static const expressiveFastEffects = SpringToken(1, 3800);
  static const expressiveDefaultEffects = SpringToken(1, 1600);
  static const expressiveSlowEffects = SpringToken(1, 800);

  static const standardFastSpatial = SpringToken(0.9, 1400);
  static const standardDefaultSpatial = SpringToken(0.9, 700);
  static const standardSlowSpatial = SpringToken(0.9, 300);

  /// The Now Playing stage reshape: fast, near-critical.
  static const stageSettle = SpringToken(0.85, 700);

  /// Artwork dissolve: critically damped, slow.
  static const artworkDissolve = SpringToken(1, 45);

  /// Home edit mode's kick: light, ringing.
  static const homeEditKick = SpringToken(0.3, 450);

  /// Seam halftone flow settle (critically damped equivalent).
  static const double seamFlowSettleStiffness = 90;

  /// The landing mascot's own springs (YoinMotion.mascot*Spring; a character,
  /// not a surface): land = the fins flying into the mark; gesture = a wave,
  /// a nod, a cheer; hop = the vertical kick of a nod or a cheer; shake = the
  /// head ringing out a "no" (also the refused form's sideways shake).
  static const mascotLand = SpringToken(0.62, 340);
  static const mascotGesture = SpringToken(0.5, 420);
  static const mascotHop = SpringToken(0.42, 520);
  static const mascotShake = SpringToken(0.18, 520);

  /// Predictive back settle: low-bouncy, medium-low stiffness × this.
  static const double predictiveBackSpeedMultiplier = 1.3;

  /// Fast scroller: hides this long after scrolling and holding stop, sliding toward the edge.
  static const int fastScrollerHideDelayMs = 1600;
  static const double fastScrollerHideSlide = 32;
}

/// The shared sheets: the queue sheet's editing (QueueSheet.kt), the song
/// sheet (TrackMenu.kt) and Add to playlist (AddToPlaylistSheet.kt).
abstract final class SheetTokens {
  /// QueueRowHeight: every queue row is this tall, so a drag reads its slot from its offset alone.
  static const double queueRowHeight = 64;
  static const double queueCover = 48;
  static const double queueRowPaddingH = 24;

  /// The drag handle's touch box, and the row's end padding when it shows.
  static const double queueHandleBox = 48;
  static const double queueHandlePaddingEnd = 8;

  /// A held row's lift (shadow elevation).
  static const double queueLiftElevation = 8;

  /// Swipe to remove: past this fraction of the row's width, or flung faster
  /// than this (px/s), the row leaves.
  static const double swipeRemoveFraction = 0.4;
  static const double swipeRemoveVelocity = 800;

  /// TrackMenuCoverSize, TrackMenuRowHeight, TrackMenuRowGap, TrackMenuGroupGap, TrackMenuQuickHeight.
  static const double trackMenuCover = 56;
  static const double trackMenuRowHeight = 56;
  static const double trackMenuRowGap = 2;
  static const double trackMenuGroupGap = 12;
  static const double trackMenuQuickHeight = 76;

  /// ButtonGroupDefaults.ConnectedSpaceBetween: the top row's gap.
  static const double trackMenuQuickGap = 2;

  /// The song sheet's side padding, row padding (h), icon → label, cover → text.
  static const double trackMenuPaddingH = 16;
  static const double trackMenuRowPaddingH = 20;
  static const double trackMenuIconGap = 16;

  /// Settings-style segmented group: outer corner, inner corner.
  static const double segmentOuterCorner = 20;
  static const double segmentInnerCorner = 4;

  /// Add to playlist: the 40 dp thumb, row padding (h / v), thumb → text.
  static const double playlistThumb = 40;
  static const double playlistRowPaddingH = 24;
  static const double playlistRowPaddingV = 8;
  static const double playlistRowGap = 12;
}

/// Library's New playlist button (M3 ExtendedFloatingActionButton, LibraryScreen.kt PlaylistsTabContent).
abstract final class PlaylistFabTokens {
  static const double height = 56;
  static const double corner = 16;
  static const double paddingH = 16;
  static const double iconGap = 12;
  static const double elevation = 3;
  static const double endMargin = 16;
}

/// The user's own Memory title editor (Android showcase MemoryTitleEditor +
/// DiaryWriterTokens it shares).
abstract final class MemoryTitleTokens {
  /// The empty field's hint (DiaryWriterTokens.PlaceholderAlpha).
  static const double placeholderAlpha = 0.7;

  /// Above the Cancel / Save row.
  static const double rowTop = 6;

  /// The edit row's height: its 48 dp hit row plus [rowTop].
  static const double rowHeight = 54;

  /// The journal Save pill (DiaryWriterTokens.PillHeight).
  static const double pillHeight = 36;

  /// Air kept between the title block and the keyboard (DiaryWriterTokens.ImeClearance).
  static const double imeClearance = 24;

  /// What a scrolled field keeps visible under it: the row and the clearance.
  static const double scrollClearance = rowHeight + imeClearance;

  /// Above the album name a written title brings onto the card (MemoryCardMetrics.albumTop).
  static const double cardAlbumTop = 20;
  static const double cardAlbumTopShort = 16;

  /// The album name under a written title on the spread's left page (spreadAlbumStyle).
  static const double spreadAlbum = 20;
  static const double spreadAlbumTight = 18;
}

/// All Memories, the memory ledger (Android ui/memories/ledger
/// MemoryLedgerScreen, MemoryGridView, ui/home/HomeMemoryLedger).
abstract final class MemoryLedgerTokens {
  // ---- the grid ----
  static const double weekGap = 5;
  static const double dayGap = 3;

  /// The gap between a month's four quads.
  static const double monthQuadGap = 2;

  /// The grid's cap on the page: phone / large screen.
  static const double gridMax = 640;
  static const double gridMaxWide = 760;

  // ---- the page ----
  static const double pageSide = 16;
  static const double pageTop = 8;
  static const double pageBottom = 24;
  static const double statsTop = 8;
  static const double gridTop = 24;
  static const double barTop = 20;
  static const double barBottom = 4;
  static const double sinceTop = 28;
  static const double emptyTop = 32;
  static const double statGap = 12;
  static const double statGapWide = 24;
  static const double statFigure = 24;
  static const double statFigureWide = 40;

  /// The view / sort buttons under the grid.
  static const double menuHeight = 36;
  static const double menuIcon = 18;

  /// Month headers on the timeline, phone / large screen.
  static const double monthHeader = 30;
  static const double monthHeaderWide = 40;
  static const double monthTop = 36;
  static const double monthTopWide = 48;

  /// A timeline album's cover: this share of the column on a phone, capped.
  static const double timelineCoverShare = 0.66;
  static const double timelineCoverMax = 300;
  static const double timelineCoverCorner = 16;
  static const double cardCorner = 20;
  static const double cardCoverCorner = 12;
  static const double noteCorner = 24;
  static const double listAlbumCover = 56;
  static const double listSongCover = 40;
  static const double songCardCover = 44;
  static const double noteCover = 32;

  // ---- Home's Memories card ----
  static const double homeCardMax = 560;
  static const double homeCardCorner = 28;
}

/// Now Playing FOCUS (tap the cover; NowPlayingBudget.kt focus solver,
/// FocusRetreat.kt, NowPlayingScreen.kt focus idle) and the lyrics outro
/// (UpNextTiming.kt, LastLineStretch.kt, LyricsFullscreenPane.kt).
abstract final class NowPlayingFocusTokens {
  /// The cover's top margin never drops below this; the air under the
  /// transport + hero in focus (nominal / min).
  static const double topMin = 12;
  static const double bottomNominal = 20;
  static const double bottomMin = 8;

  /// Short windows (one resting lyric line): the cover's top in focus, and the
  /// narrowest the cover (and the lyrics lined up with it) gives.
  static const double topShort = 16;
  static const double shortMinColumn = 216;

  /// Focus lyrics: printed this much larger, the current line pinned to the
  /// top with one whole line above; three clean lines, or two.
  static const double lyricScale = 1.6;
  static const double lyricsAnchorFraction = 0;
  static const int linesAbove = 1;
  static const int cleanLines = 3;
  static const int minCleanLines = 2;

  /// Equal margins hold while the cover keeps this share of its resting size;
  /// the transport's narrowest designed width.
  static const double equalMarginCoverKeep = 0.7;
  static const double minColumn = 272;

  /// The top bar lifts this far as it retreats (fade over the first 1/1.4).
  static const double retreatLift = 10;
  static const double retreatFadeRate = 1.4;

  /// The rating column slides this far toward the end as it retreats.
  static const double ratingRetreatShift = 72;

  /// The pills sink this far as they retreat.
  static const double pillRetreatDrop = 8;

  /// Focus at ease: this long without a touch while synced lyrics play.
  static const int idleMs = 4000;

  /// Focus lyrics: the line just sung and the ones before it.
  static const double aboveAlpha = 0.42;
  static const double farAboveAlpha = 0.12;
  static const double fadeTop = 12;

  /// Lyrics outro: the next song shows from this long before the end, hands
  /// over this long before it (never closer than the min lead); the last line
  /// keeps the stage at least this long; quantized steps; tick slack.
  static const int upNextRevealLeadMs = 10000;
  static const int upNextHandoffLeadMs = 1400;
  static const int upNextHandoffMinLeadMs = 900;
  static const int upNextMinLastLineMs = 2000;
  static const int upNextRevealSteps = 24;
  static const int upNextHandoffTickSlackMs = 300;

  /// The next title surfaces faintly in the outro's second half, rising this far.
  static const double upNextRevealAlpha = 0.4;
  static const double upNextRevealLateStart = 0.5;
  static const double upNextRise = 28;
  static const double upNextRiseWindow = 24;
  static const double upNextGap = 24;

  /// The held last line widens by this much at the outro's peak.
  static const double lastLineStretchMax = 0.08;

  /// Next-song lyrics prefetch: wait, then retry this many times, backing off.
  static const int upNextPrefetchDelayMs = 4000;
  static const int upNextPrefetchRetries = 2;
  static const int upNextPrefetchRetryMs = 15000;
}

/// Pointer and keyboard input on PC windows (MateBook 2in1, later Windows):
/// the M3 state layers (hover 8 %, focus 10 %) and focus indicator (3 dp,
/// 2 dp outside the target), and the keyboard seek step (Now Playing ←/→).
abstract final class DesktopInputTokens {
  static const double hoverStateLayerAlpha = 0.08;
  static const double focusStateLayerAlpha = 0.10;
  static const double focusRingWidth = 3;
  static const double focusRingOffset = 2;

  /// The ring's corner where the target's own shape isn't known.
  static const double focusRingCorner = 12;

  static const int seekStepMs = 5000;
}

/// The first-run landing (ui/landing/: LandingStage.kt, LandingScenes.kt,
/// LandingPill.kt, LandingConnect.kt, LandingHomeStep.kt). Dart-only for now.
abstract final class LandingTokens {
  /// The floating window: corner, its gap above the pill, the scene's padding.
  static const double windowCorner = 28;
  static const double windowGap = 12;
  static const double scenePaddingH = 18;
  static const double scenePaddingV = 20;

  /// Scenes slide this far along the axis of travel while they cross-fade.
  static const double sceneSlide = 28;

  /// The column: 16 from the window edges, at most 560; two columns: at most
  /// 520 or 44 % of the window, 48 (or 6 %) from the end.
  static const double columnMargin = 16;
  static const double columnMax = 560;
  static const double twoColumnMax = 520;
  static const double twoColumnFraction = 0.44;
  static const double twoColumnEndMin = 48;
  static const double twoColumnEndFraction = 0.06;

  /// The hello pill: the whole bar on a phone, at most 380 elsewhere.
  static const double helloPillMax = 380;

  /// The window's room: below the status bar (two columns 28, else 18 + the
  /// mascot row + 14), and its minimum height (wide / medium).
  static const double topGapWide = 28;
  static const double topGap = 18;
  static const double mascotRowGap = 14;
  static const double minHeightWide = 380;
  static const double minHeightWideFraction = 0.62;
  static const double minHeightMedium = 360;
  static const double minHeightMediumFraction = 0.55;

  /// The mascot: 88 (medium 120) on the window, growing to 132 (170) with
  /// room; 300 (phone: 62 % of the width, at most 250) saying hello; 220 in
  /// the left column; 56 at the greeting corner after the hand-off.
  static const double mascotBase = 88;
  static const double mascotBaseMedium = 120;
  static const double mascotGrown = 132;
  static const double mascotGrownMedium = 170;
  static const double mascotHello = 300;
  static const double mascotHelloPhone = 250;
  static const double mascotHelloPhoneFraction = 0.62;
  static const double mascotColumn = 220;
  static const double mascotCorner = 56;
  static const double mascotCornerX = 44;
  static const double mascotCornerY = 32;

  /// The speech bubble: corner, padding, tail (22 × 11) and widths.
  static const double bubbleCorner = 22;
  static const double bubblePaddingStart = 18;
  static const double bubblePaddingTop = 13;
  static const double bubblePaddingBottom = 14;
  static const double bubbleTail = 11;
  static const double bubbleTailEdge = 8;
  static const double bubbleSideTailY = 26;
  static const double bubbleMaxWide = 360;
  static const double bubbleMaxWideFraction = 0.40;
  static const double bubbleMaxMedium = 420;
  static const double bubbleMax = 340;
  static const double bubbleTitleSize = 19;
  static const double bubbleTitleLine = 25;
  static const double bubbleBodySize = 15;
  static const double bubbleBodyLine = 21;
  static const double bubbleBodyGap = 4;

  /// The pill's step dots (current 22 wide), back glyph, primary padding.
  static const double dot = 8;
  static const double dotCurrent = 22;
  static const double dotGap = 6;
  static const double backGlyph = 22;
  static const double primaryPadding = 22;
  static const double primaryArrow = 20;
  static const double primaryArrowGap = 8;

  /// The pill rises this far in at first launch.
  static const double pillRise = 120;

  /// Pick tiles: corner 24 (32 picked), 44 avatar, 26 check.
  static const double tileCorner = 24;
  static const double tileCornerPicked = 32;
  static const double tileAvatar = 44;
  static const double tileGlyph = 24;
  static const double tileCheck = 26;
  static const double tileCheckGlyph = 18;
  static const double tileCheckStroke = 2;
  static const double tileGap = 8;
  static const double tileMorphTurn = 22.5;

  /// Groups (About, Apple's token rows, Home's sections): 22 / 20 outer, 4 inner, 2 apart.
  static const double groupCorner = 22;
  static const double rowOuter = 20;
  static const double rowInner = 4;
  static const double rowGap = 2;
  static const double markSize = 24;
  static const double markGlyph = 16;
  static const double serviceMarkGlyph = 15;

  /// A connect step's header mark and its button.
  static const double headerMark = 48;
  static const double headerGlyph = 26;
  static const double ctaHeight = 56;

  /// A refused form shakes sideways at this speed (px/s).
  static const double shakeVelocity = 900;

  /// Home step rows: the handle's box, the section sketch, the lift.
  static const double handleWidth = 36;
  static const double handleHeight = 44;
  static const double sectionGlyph = 36;
  static const double liftScale = 1.02;

  /// Ready: the account avatar.
  static const double readyAvatar = 40;
}

/// The tip pages (Android ui/tips TipScreen.kt, TipDemoPhone.kt,
/// TipIllustration.kt): the pager's chrome, the tile, and the demo phone.
abstract final class TipTokens {
  /// The bar row with the back button and "3 / 20".
  static const double barHeight = 64;
  static const double positionEnd = 24;

  /// A page: 24 around, at most the prose width, 28 under the phone, 16 between title and text.
  static const double pagePadding = 24;
  static const double proseMaxWidth = 640;
  static const double illustrationGap = 28;
  static const double titleGap = 16;
  static const double textGap = 16;
  static const double updatedGap = 12;

  /// The illustration's card (surfaceContainerLow) and its vertical padding.
  static const double illustrationCorner = 28;
  static const double illustrationPaddingV = 16;

  /// The symbol on its Expressive shape: 64 with a 30 glyph; it turns 40° and
  /// shrinks a fifth as its page slides away.
  static const double tileSize = 64;
  static const double tileSymbol = 30;
  static const double tileTurnDegrees = 40;
  static const double tileShrink = 0.2;

  /// Read / Next: 56 tall, 12 apart, 24 / 12 around; at least 16 off the bottom edge.
  static const double buttonHeight = 56;
  static const double buttonGap = 12;
  static const double buttonsPaddingH = 24;
  static const double buttonsPaddingV = 12;
  static const double bottomFloor = 16;

  /// The demo phone: 168 × 300 on the page, laid out as a 360-wide phone and
  /// drawn scaled down; a tall (20:9) one lays out 412 wide.
  static const double phoneWidth = 168;
  static const double phoneHeight = 300;
  static const double tallAspect = 9 / 20;
  static const double screenWidth = 360;
  static const double tallScreenWidth = 412;
  static const double phoneCorner = 24;
  static const double phoneBorder = 1.5;

  /// A tall window grows the phone, up to 36 % of its height, half its width and 1.7×.
  static const double phoneHeightShare = 0.36;
  static const double phoneWidthShare = 0.5;
  static const double phoneMaxGrow = 1.7;

  /// The demo touch: its radius, its dot, the ripple's spread and the hold ring.
  static const double fingerRadius = 14;
  static const double fingerDot = 0.42;
  static const double fingerPressShrink = 0.15;
  static const double rippleSpread = 1.4;
  static const double rippleStroke = 2;
  static const double ringRadius = 1.3;
  static const double ringStroke = 2.5;

  /// The script's clock (ms): a hold is the long-press timeout; the demo
  /// waits before it starts and between loops; a user's touch keeps it away this long.
  static const int holdMs = 550;
  static const int startDelayMs = 500;
  static const int loopGapMs = 700;
  static const int takeoverIdleMs = 5000;

  /// The touch's own short fades (ms): appear, press, ripple, lift.
  static const int fingerAppearMs = 180;
  static const int fingerTapMs = 70;
  static const int fingerHoldPressMs = 90;
  static const int fingerDragPressMs = 80;
  static const int rippleMs = 420;
  static const int liftMs = 220;
}

/// The tip scenes' own layout on the demo phone's 360-wide screen (Android
/// ui/tips scenes): where the made-up pages put their parts.
abstract final class TipSceneTokens {
  /// A page's top air, its side padding, a large title's gap to what follows.
  static const double pageTop = 24;
  static const double pagePaddingH = 16;
  static const double titleGap = 12;

  /// A sheet's top corners and its handle (32 × 4 in 22 of air each side).
  static const double sheetCorner = 28;
  static const double handleWidth = 32;
  static const double handleHeight = 4;
  static const double handlePadding = 22;

  /// The album page: cover, gap to the name, gap to the songs.
  static const double albumCover = 168;
  static const double albumTitleGap = 16;
  static const double albumRowsGap = 16;

  /// Library's grid: top and bottom padding (room for the bar).
  static const double gridTop = 8;
  static const double gridBottom = 108;

  /// The bar's gap to the screen's bottom.
  static const double barBottom = 16;

  /// Now Playing: top air (rest / focus), the cover (rest / focus), the
  /// gaps to the title and to the transport.
  static const double npTop = 56;
  static const double npFocusTop = 96;
  static const double npCover = 280;
  static const double npFocusCover = 312;
  static const double npTitleGap = 20;
  static const double npTransportGap = 16;

  /// Where the demo song is when a scene starts (ms).
  static const int demoPositionMs = 42000;

  /// A pull's refresh reads this long in the phone (ms).
  static const int refreshMs = 1200;

  /// The keyboard demo's tray: padding, key gap, a key's height, how far it sinks, its corner.
  static const double keyTrayPadding = 12;
  static const double keyGap = 8;
  static const double keyHeight = 48;
  static const double keyTravel = 4;
  static const double keyCorner = 10;

  /// Library's search field: its height, and how far it drops in from.
  static const double searchField = 56;
  static const double searchDrop = 24;

  /// The lyric scenes start the demo song here (ms): the second line is being sung.
  static const int lyricsStartMs = 5000;

  /// Home: a section's padding, its slot, the covers in it and their gap, a held section's lift, the avatar.
  static const double sectionPadding = 12;
  static const double sectionSlot = 164;
  static const double sectionCover = 68;
  static const double coverGap = 8;
  static const double liftScale = 1.02;
  static const double avatar = 40;

  /// The account fan: each avatar's step down from the last, the one under the finger grown.
  static const double fanStep = 52;
  static const double fanOverScale = 1.15;

  /// Memories: the bar, the card's cover, the cover in the bar, the diary's rise.
  static const double barHeight = 64;
  static const double memoryCover = 200;
  static const double barCover = 40;
  static const double diaryRise = 56;

  /// A pull's travel for the whole way; the share of it the demo's drag covers.
  static const double pullDistance = 320;
  static const double pullShare = 0.4;
  static const double diaryDragShare = 0.6;

  /// All Memories' cards view: a card's edge.
  static const double ledgerCard = 76;

  /// The phone's home screen: icons, their corner, the mark's inset, gaps, padding, the menu's top.
  static const double appIcon = 60;
  static const double appIconCorner = 16;
  static const double appIconInset = 6;
  static const double appGap = 22;
  static const double desktopPadding = 20;
  static const double desktopTop = 56;
  static const double menuTop = 300;

  /// A 2×4 service widget: its height, padding, cover and play symbol.
  static const double cardSlot = 116;
  static const double cardPadding = 14;
  static const double cardCover = 88;
  static const double cardPlay = 36;

  /// The Welcome guide's mascot, and the name page's mark.
  static const double mascot = 200;
  static const double mark = 160;

  /// The name page's notes: how long a ring lives and the echo between its three rings (ms).
  static const int noteLifeMs = 2400;
  static const int noteEchoMs = 220;
}

/// NeoDB: the "别人怎么说" page (Android NeoDbVoicesScreen.kt), the rate
/// sheet's NeoDB line (AlbumDetailComponents AlbumNeoDbLine) and the Memories
/// diary's (MemoryDiary DiaryNeoDbLine).
abstract final class NeoDbTokens {
  /// The page's column on a wide window (YoinPageWidths.Feed).
  static const double pageMaxWidth = 720;

  // ---- top bar ----
  static const double topBarPaddingH = 12;
  static const double topBarPaddingV = 8;
  static const double topBarGap = 12;

  // ---- the list ----
  static const double sidePadding = 16;
  static const double listTop = 8;
  static const double listBottom = 16;
  static const double gridGap = 8;
  static const double messagePadding = 32;
  static const double emptyPaddingV = 24;
  static const double tailPaddingV = 8;
  static const double tailIndicator = 32;

  // ---- the spread ----
  static const double spreadPaddingV = 8;
  static const double spreadGap = 10;
  static const double spreadColumnGap = 8;
  static const double spreadLabelGap = 6;
  static const double spreadBarMax = 92;
  static const double spreadBarMin = 3;
  static const double spreadBarTopCorner = 12;
  static const double spreadBarBottomCorner = 4;
  static const double spreadAverageGap = 8;
  static const double spreadRatedBottom = 3;

  // ---- other catalogues' scores ----
  static const double referenceCorner = 18;
  static const double referencePaddingH = 14;
  static const double referencePaddingV = 10;
  static const double referenceMinWidth = 72;
  static const double referenceScaleBottom = 2;

  // ---- one voice ----
  static const double cardCorner = 24;
  static const double cardPaddingH = 16;
  static const double cardPaddingTop = 14;
  static const double cardPaddingBottom = 12;
  static const double cardGap = 10;
  static const double avatar = 20;
  static const double bylineGap = 8;
  static const double grade = 10;
  static const double gradeGap = 2;

  // ---- the ways out ----
  static const double waysOutGap = 8;
  static const double waysOutTop = 8;
  static const double waysOutBottom = 12;
  static const double openNeoDbHeight = 52;
  static const double otherWayOutHeight = 40;

  // ---- the rate sheet's line ----
  /// Between the review field and the line (the sheet's column spacing).
  static const double sheetLineTop = 20;
  static const double sheetLineCorner = 12;
  static const double sheetLinePaddingH = 4;
  static const double sheetLinePaddingV = 6;
  static const double sheetLineGap = 8;

  // ---- the Memories diary's line ----
  static const double diaryLineHeight = 48;
  static const double diaryLinePaddingH = 16;
  static const double diaryLineTop = 8;

  // ---- Settings › NeoDB (SettingsExpandableItem as a page) ----
  static const double settingsPanelPaddingH = 16;
  static const double settingsPanelPaddingV = 18;
  static const double settingsPanelGap = 14;
  static const double settingsTokenGap = 12;
  static const double settingsButtonGap = 8;
}
