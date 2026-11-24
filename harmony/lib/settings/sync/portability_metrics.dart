/// Export / import / Transfer Memories pages' numbers (Android SyncCommon.kt
/// SyncPage / SyncHeadline / ProgressHero / OutcomeHero / FlowButtons,
/// BackupScreens.kt and TransferScreens.kt), in dp.
abstract final class PortabilityMetrics {
  // SyncPage's pinned footer.
  static const double footerHorizontal = 16;
  static const double footerTop = 8;
  static const double footerBottom = 16;
  static const double footerGap = 8;
  static const double buttonHeight = 56;
  static const double badgeGap = 10;
  static const double badgeMinHeight = 24;
  static const double badgeHorizontal = 8;
  static const double badgeVertical = 2;
  static const double badgeAlpha = 0.18;

  // SyncHeadline(small = true).
  static const double headTop = 16;
  static const double headInset = 8;
  static const double headLeadTop = 12;
  static const double headBottom = 24;

  /// SyncGroupGap: between groups on these pages.
  static const double groupGap = 28;

  /// Between a group and the note under it.
  static const double noteGap = 8;

  // ProgressHero.
  static const double heroHorizontal = 8;
  static const double heroVertical = 24;
  static const double heroGap = 20;
  static const double heroCookie = 120;
  static const double heroIndicator = 72;
  static const double heroLine = 4;
  static const double serviceGap = 10;

  // OutcomeHero.
  static const double outcomeHorizontal = 8;
  static const double outcomeVertical = 16;
  static const double outcomeGap = 12;
  static const double outcomeMark = 112;
  static const double outcomeGlyph = 44;
  static const double outcomeTurnDegrees = -60;
  static const double outcomeTitleTop = 4;
  static const double outcomeExtraTop = 8;

  // CountTile row.
  static const double tileGap = 10;
  static const double tileRadius = 24;
  static const double tilePadding = 14;
  static const double tileLabelAlpha = 0.8;

  // TreeRow (the export's contents).
  static const double treeIndent = 24;
  static const double treeVertical = 12;
  static const double treeIcon = 20;
  static const double treeBottom = 8;

  // StepBar.
  static const double stepTop = 8;
  static const double stepBottom = 8;
  static const double stepGap = 10;
  static const double stepSegmentGap = 4;
  static const double stepSegmentHeight = 4;

  // Account rows: the service glyph's disc, the pill by the name.
  static const double pillGap = 8;
  static const double pillRunGap = 2;
  static const double radio = 22;
  static const double radioRing = 2;
  static const double radioDot = 4;

  // Import review: an account's destination chips.
  static const double chipGap = 8;
  static const double mutedAlpha = 0.6;

  // Summary: the pair card and the "skipping" banner.
  static const double pairVertical = 20;
  static const double pairGlyph = 52;
  static const double pairGlyphIcon = 0.5;
  static const double pairArrow = 28;
  static const double pairLineGap = 6;
  static const double bannerRadius = 20;
  static const double bannerPadding = 16;
  static const double bannerGap = 12;

  // Review.
  static const double reviewTop = 8;
  static const double reviewGap = 16;
  static const double reviewInset = 8;
  static const double reviewHeaderGap = 12;
  static const double reviewRowGap = 2;
}
