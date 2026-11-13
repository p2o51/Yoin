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

  /// CenteredBar: horizontal margin inside a narrow pane (bar = pane − 40, max 600).
  static const double centeredHorizontalMargin = 20;

  /// CenteredBar: width cap.
  static const double centeredMaxWidth = 600;
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

  /// Predictive back settle: low-bouncy, medium-low stiffness × this.
  static const double predictiveBackSpeedMultiplier = 1.3;

  /// Fast scroller: hides this long after scrolling and holding stop, sliding toward the edge.
  static const int fastScrollerHideDelayMs = 1600;
  static const double fastScrollerHideSlide = 32;
}

