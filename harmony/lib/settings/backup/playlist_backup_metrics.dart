/// Playlist backups' numbers, read off Android (ui/settings/backup/
/// PlaylistBackupsPage.kt, PlaylistBackupScreen.kt, PlaylistRestoreSheet.kt).
/// Kept beside the feature, as Settings keeps [SettingsMetrics]; they move
/// into `lib/theme/tokens.dart` with the rest of Settings'.
library;

abstract final class PlaylistBackupMetrics {
  // The list (PlaylistBackupsList): empty line padding, the following dot.
  static const double emptyHorizontal = 8;
  static const double emptyVertical = 24;
  static const double dot = 8;
  static const double dotGap = 6;

  // The page: sections 28 apart, the actions row 16 under the status card, 56 high, 2 between
  // (ButtonGroupDefaults.ConnectedSpaceBetween).
  static const double sectionTop = 28;
  static const double sectionLabelBottom = 4;
  static const double actionsTop = 16;
  static const double actionHeight = 56;
  static const double actionGap = 2;
  static const double actionIconGap = 8;
  static const double actionLoading = 24;

  // Status card (BackupStatusCard): corner 32, padding 20, rows 18 apart; the service cookie 52 with a
  // 26 glyph, 14 to the text; the state dot 10, 8 to its line; date tiles 8 apart, corner 20, h14 v12,
  // their fill onContainer at 7%.
  static const double cardRadius = 32;
  static const double cardPadding = 20;
  static const double cardGap = 18;
  static const double cardMark = 52;
  static const double cardGlyph = 26;
  static const double cardMarkGap = 14;
  static const double cardDot = 10;
  static const double cardDotGap = 8;
  static const double tileGap = 8;
  static const double tileRadius = 20;
  static const double tileHorizontal = 14;
  static const double tileVertical = 12;
  static const double tileFillAlpha = 0.07;
  static const double tileLabelAlpha = 0.75;
  static const double goneTimeAlpha = 0.7;

  // Song and unexported rows (BackupTrackRow / UnexportedRow): 56 high, h8 v8, 16 between; the number 24 wide.
  static const double songRowHeight = 56;
  static const double songRowPadding = 8;
  static const double songRowGap = 16;
  static const double songNumberWidth = 24;

  // Rows' counts (RestoreCounts) 12 apart; a version row's counts 10 apart, its Current pill h8 v2;
  // its Restore button h14.
  static const double countsGap = 12;
  static const double versionGap = 10;
  static const double pillHorizontal = 8;
  static const double pillVertical = 2;
  static const double versionButtonHorizontal = 14;

  // The restore sheet: titles h24 v8; segments 2 apart; target rows 76 high, h16 v12, 16 between.
  static const double sheetHorizontal = 24;
  static const double sheetTitleVertical = 8;
  static const double sheetListHorizontal = 16;
  static const double sheetBottom = 16;
  static const double segmentGap = 2;
  static const double targetRowHeight = 76;
  static const double targetRowHorizontal = 16;
  static const double targetRowVertical = 12;
  static const double targetRowGap = 16;
  static const double versionLineBottom = 2;

  // Matching: the cookie 120 holding a 72 indicator, items 20 apart, top 16 bottom 32; the service row 10 apart.
  static const double matchingCookie = 120;
  static const double matchingIndicator = 72;
  static const double matchingGap = 20;
  static const double matchingTop = 16;
  static const double matchingBottom = 32;
  static const double matchingServiceGap = 10;
  static const double progressHeight = 4;

  // Review: header top 4 bottom 12, 12 between; summary bar 14 high, parts 4 apart, legend 16 apart with an
  // 8 dot 6 in, counts and bar 10 apart; filters 14 above, 8 below; rows 72 high, start 12 end 16 v10, 14
  // between; the mark 44 holding a 36 shape and a 20 tick, turned 40° when checked; candidates 12 in,
  // 60 high, h14 v8; bottom bar t8 b16, 8 between, buttons 56 high; the count badge 24 high, h8 v2.
  static const double reviewHeaderTop = 4;
  static const double reviewHeaderBottom = 12;
  static const double reviewHeaderGap = 12;
  static const double summaryBarHeight = 14;
  static const double summaryPartGap = 4;
  static const double summaryLegendGap = 16;
  static const double summaryDot = 8;
  static const double summaryDotGap = 6;
  static const double summaryGap = 10;
  static const double filtersTop = 14;
  static const double filtersBottom = 8;
  static const double filterHeight = 40;
  static const double filterOuterRadius = 20;
  static const double filterInnerRadius = 8;
  static const double reviewRowHeight = 72;
  static const double reviewRowStart = 12;
  static const double reviewRowEnd = 16;
  static const double reviewRowVertical = 10;
  static const double reviewRowGap = 14;
  static const double markTouch = 44;
  static const double markShape = 36;
  static const double markTick = 20;
  static const double markTurnDegrees = 40;
  static const double statusPillHorizontal = 10;
  static const double statusPillVertical = 4;
  static const double searchButtonHorizontal = 14;
  static const double searchIcon = 18;
  static const double searchIconGap = 6;
  static const double candidatesInset = 12;
  static const double candidateHeight = 60;
  static const double candidateHorizontal = 14;
  static const double candidateVertical = 8;
  static const double candidateGap = 12;
  static const double barTop = 8;
  static const double barBottom = 16;
  static const double barGap = 8;
  static const double barButtonHeight = 56;
  static const double badgeHeight = 24;
  static const double badgeHorizontal = 8;
  static const double badgeVertical = 2;
  static const double badgeAlpha = 0.18;
  static const double badgeGap = 10;

  // Search sheet: 92% of the window high; the song card corner 20, h16 v12; the field t16 b8; the
  // "nothing" line 32 down.
  static const double searchSheetHeightFraction = 0.92;
  static const double searchCardRadius = 20;
  static const double searchCardHorizontal = 16;
  static const double searchCardVertical = 12;
  static const double searchFieldTop = 16;
  static const double searchFieldBottom = 8;
  static const double searchEmptyTop = 32;

  // Outcome: the burst 112 holding a 44 glyph, springing in from -60°; items 20 apart, t8 b16; tiles 10
  // apart, corner 24, padding 14; their label at 80%.
  static const double outcomeMark = 112;
  static const double outcomeGlyph = 44;
  static const double outcomeTurnDegrees = -60;
  static const double outcomeGap = 20;
  static const double outcomeTop = 8;
  static const double countTileGap = 10;
  static const double countTileRadius = 24;
  static const double countTilePadding = 14;
  static const double countTileLabelAlpha = 0.8;
}
