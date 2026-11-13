/// Home numbers that lib/theme/tokens.dart does not carry yet (HomeTokens has
/// only part of the feed). Values are Android's, with the Kotlin source in the
/// comment. TODO(harmony): move into tokens.dart (HomeTokens / a SeamTokens
/// block) so the Kotlin side can follow — tokens.dart is shared and was not
/// editable from the Home task.
library;

/// The feed frame and list (FeedUnits.kt, HomeEditorialContent.kt).
abstract final class HomeFeedMetrics {
  /// FeedCappedMargin / FeedLandscapeMargin / FeedWideMargin.
  static const double cappedMargin = 16;
  static const double landscapeMargin = 24;

  /// The Capped frame's container cap; its content is this minus both margins.
  static const double cappedContainerMax = 720;

  /// Gap between list items (landscape handset: [itemSpacingLandscape]).
  static const double itemSpacing = 18;
  static const double itemSpacingLandscape = 10;

  /// The header's gap under the status bar, and its min height.
  static const double headerTopPadding = 12;
  static const double headerMinHeight = 48;

  /// Landscape handset: bottom clearance (the bar is in the side band).
  static const double landscapeBottomPadding = 16;

  /// The feed rises this far on its first entrance (and settles back on leave).
  static const double entranceRise = 16;

  /// Feed units (FeedUnits.kt): content narrower than this is PhoneNarrow.
  static const double phoneNarrowBelow = 310;

  /// feedCoverColumns = floor((c + 12) / 124) in 3..10.
  static const double coverColumnUnit = 124;
  static const double coverColumnGutter = 12;
}

/// Account control (HomeAccountSwitcher.kt).
abstract final class HomeHeaderMetrics {
  static const double touchBox = 48;
  static const double avatar = 32;
  static const double avatarGap = 2;
  static const double avatarRing = 1.5;
}

/// Section titles and section columns (HomeWidgetGrid.kt HomeSectionTitle).
abstract final class HomeSectionMetrics {
  static const double titleSize = 18;
  static const double titleBottomPadding = 6;

  /// Column spacing: Activities 10, Jump Back In 16, shelves 12.
  static const double activitiesSpacing = 10;
  static const double jbiSpacing = 16;
  static const double shelfSpacing = 12;
}

/// Activities bento (HomeEditorialContent.kt ActivityHeroCard … StripCard).
abstract final class HomeBentoMetrics {
  static const double heroCover = 96;
  static const double wideCover = 80;
  static const double smallCover = 48;
  static const double heroHeight = 124;
  static const double heroPadding = 14;
  static const double heroGap = 14;
  static const double heroColumnSpacing = 3;
  static const double smallPadding = 10;
  static const double smallSpacing = 4;
  static const double smallGap = 10;
  static const double smallTitleLineHeight = 17;
  static const double widePadding = 12;
  static const double wideGap = 12;
  static const double wideColumnSpacing = 2;
  static const double stripPaddingH = 16;
  static const double stripPaddingV = 12;
  static const double stripGap = 10;
  static const double stripSubtitleGap = 12;
  static const double stripSubtitleAlpha = 0.6;

  /// Landscape handset recipe: hero 2 / small 1 / wide 1.4 in one 124 row, 12 apart.
  static const double landscapeRowHeight = 124;
  static const double landscapeGap = 12;
  static const double landscapeHeroWeight = 2;
  static const double landscapeSmallWeight = 1;
  static const double landscapeWideWeight = 1.4;

  /// The phone takes this many entries; the hero is picked from the first [heroWindow].
  static const int phoneEntries = 8;
  static const int heroWindow = 6;

  /// Seated supporting cards at the default L preset on a phone.
  static const int phoneSupporting = 3;
}

/// Jump Back In (HomeWidgetGrid.kt, HomeRowPresets.kt).
abstract final class HomeJbiMetrics {
  static const int phoneColumns = 3;
  static const int phoneRows = 4;
  static const int phoneCells = 12;
  static const double captionGap = 5;
  static const double titleSize = 16;
  static const double titleLineHeight = 22;

  /// Medium / Wide fallback: cover = clamp(column × fraction, min, max).
  static const double wideCoverFraction = 0.84;
  static const double wideCoverMax = 128;
  static const int landscapeMaxColumns = 6;
  static const int landscapeRows = 2;
}

/// Recently Added / Recently Played / Your Playlists (HomeEditorialContent.kt, HomeFeedDensity.kt).
abstract final class HomeShelfMetrics {
  static const double itemGap = 14;
  static const double trackGridRowGap = 14;
  static const double trackTitleSize = 13;
  static const double albumCardCaptionGap = 6;
  static const double trackGridMin = 226.78;
  static const double trackGridMax = 340;
  static const double trackGridShare = 2.6 / 3.6;
  static const double trackGridLandscapeShare = 0.45;
  static const int phoneTrackRows = 2;
  static const int phoneTrackColumns = 2;
  static const int phoneAlbums = 12;

  /// horizontalEdgeFadeOnScroll width.
  static const double edgeFade = 24;
}

/// The backdrop cover (HomeWidgetGrid.kt WidgetBackdropArtwork).
abstract final class HomeBackdropMetrics {
  static const double artworkFraction = 0.72;
  static const double fallbackIcon = 24;
  static const double tonalElevation = 1;
}

/// ExpressiveSectionPanel (empty Activities card).
abstract final class HomeEmptyCardMetrics {
  static const double padding = 18;
  static const double spacing = 8;
}

/// The Error page (HomeScreen.kt error Column).
abstract final class HomeErrorMetrics {
  static const double gap = 8;
  static const double buttonGap = 12;
}

/// Press feedback (PressFeedback.kt).
abstract final class HomePressMetrics {
  static const double pressedScale = 0.97;
  static const double hoverScale = 1.01;
}

/// Album version tag (AlbumVersionTitle.kt).
abstract final class HomeVersionTagMetrics {
  static const double radius = 6;
  static const double paddingH = 6;
  static const double paddingV = 1;
  static const double gap = 6;
}

/// Status-bar seam: tide line + text fade (SeamDissolve.kt SeamDissolveTokens, SeamTide.kt).
abstract final class HomeSeamMetrics {
  static const double revealDistance = 40;
  static const double tideRest = 2;
  static const double tideHidden = 8;
  static const double tideAmplitude = 2.4;
  static const double tideAmplitudeStretched = 3.2;
  static const double tideFrontWavelength = 72;
  static const double tideBackWavelength = 116;
  static const double tideBackDrop = 6;
  static const double tideBackAmplitude = 1.1;
  static const double tideBackAlpha = 0.55;
  static const double tidePhaseTravel = 88;
  static const double tideHarmonic = 0.18;
  static const double tideStep = 3;
  static const double tideMargin = 20;
  static const double textBand = 10;
  static const double textBandStretched = 26;
  static const double textSizeFraction = 0.75;
  static const double textLead = 0.05;
  static const double stretchOnsetSpeed = 150;
  static const double stretchSpanSpeed = 1300;
  static const double restSpeed = 2;

  /// The afterglow's lag cap: lerp(band, bandStretched, stretch) × flowLag.
  static const double band = 12;
  static const double bandStretched = 34;
  static const double flowLag = 0.5;
}

/// Timings (HomeScreen.kt, HomeViewModel.kt, StagedReveal.kt, MarqueeText.kt).
abstract final class HomeTimings {
  static const Duration loadingIndicatorDelay = Duration(milliseconds: 180);
  static const Duration activityDebounce = Duration(milliseconds: 1000);
  static const Duration paletteWarmup = Duration(milliseconds: 350);
  static const Duration gridPoolsTtl = Duration(hours: 6);
  static const Duration activeSourceWait = Duration(seconds: 4);
  static const Duration greetingTickSlack = Duration(milliseconds: 500);
  static const List<Duration> stagedRevealDelays = [
    Duration.zero,
    Duration(milliseconds: 90),
    Duration(milliseconds: 170),
  ];
  static const Duration marqueeInitialDelay = Duration(milliseconds: 1200);
  static const Duration marqueeRepeatDelay = Duration(milliseconds: 1800);

  /// basicMarquee's default velocity, dp/s.
  static const double marqueeVelocity = 30;
}

/// Staged reveal rises (StagedReveal.kt use sites).
abstract final class HomeRevealMetrics {
  static const double heroRise = 18;
  static const double heroScaleFrom = 0.97;
  static const double rise = 16;
}

/// Loading indicator scale-in from (HOME_LOADING_INDICATOR_SCALE).
const double homeLoadingIndicatorScaleFrom = 0.6;
