/// Settings' and the setup page's numbers, read off Android
/// (SettingsScreen.kt, SettingsComponents.kt, AccountIdentity.kt,
/// service/ServiceSetupScreen.kt). Kept beside the feature until they move
/// into `lib/theme/tokens.dart` (that file is shared; this one is Settings').
abstract final class SettingsMetrics {
  // Add an account (AddAccountSheet / ServiceChoiceRow): the title on the
  // 24dp line, each service a 12-inset card row, its tone circle 40.
  static const double addSheetTitleSide = 24;
  static const double addSheetTitleBottom = 12;
  static const double addSheetBottom = 16;
  static const double choiceRowInset = 12;
  static const double choiceRowPadding = 12;
  static const double choiceRowGap = 16;
  static const double choiceIconBox = 40;
  static const double choiceIcon = 24;
  static const double choiceBadgeGap = 8;
  static const double choiceBadgeH = 8;
  static const double choiceBadgeV = 2;
  static const double choiceDisabledAlpha = 0.5;

  // Page.
  static const double pageHorizontal = 16;
  static const double pageTop = 8;
  static const double pageBottom = 24;
  static const double sectionGap = 28;

  // Large title (SettingsHeadline): 40 above, 36 below, 8 inside the rows' edge.
  static const double headlineTop = 40;
  static const double headlineBottom = 36;
  static const double labelInset = 8;
  static const double groupLabelGap = 8;

  // Back button (SettingsBackButton): its circle on the 24dp line.
  static const double backStart = 20;
  static const double backEnd = 14;
  static const double backTouch = 48;

  // Segments (Pixel SettingsLib): 20 outer, 4 inner, 2 apart.
  static const double segmentOuterRadius = 20;
  static const double segmentInnerRadius = 4;
  static const double segmentGap = 2;

  // Account switcher (AccountCardRow / ProfileCardTile / AddAccountTile).
  static const double cardRowVertical = 6;
  static const double cardGap = 12;
  static const double cardWidth = 200;
  static const double addTileWidth = 112;
  static const double addTileIcon = 28;
  static const double addTileGap = 8;
  static const double cardPaddingStart = 16;
  static const double cardPaddingTop = 16;
  static const double cardPaddingEnd = 4;
  static const double cardPaddingBottom = 16;
  static const double cardTextEnd = 12;
  static const double cardMenuLift = 8;
  static const double cardMenuIcon = 20;
  static const double cardInactiveScale = 0.96;

  /// The card's fixed parts (row padding, card padding, avatar row, pill gap
  /// and padding, slack); the text lines are added at their scaled heights.
  static const double cardFixedHeight = 112;

  // Empty accounts card.
  static const double emptyPadding = 20;
  static const double emptyGap = 12;
  static const double emptyButtonIcon = 18;
  static const double emptyButtonGap = 8;

  // Avatar (AccountAvatar).
  static const double avatarSize = 48;
  static const double avatarGlyphFraction = 0.54;

  /// An account mark's glyph: 60 % of the mark (Android AccountMark).
  static const double markGlyphFraction = 0.6;
  static const double avatarGlyphLeanFraction = 0.07;
  static const double avatarBadgeFraction = 0.46;
  static const double avatarBadgeMin = 18;
  static const double avatarRingMin = 1.5;
  static const double avatarRingMax = 3;
  static const double avatarRingDivisor = 24;
  static const double avatarLetterFraction = 0.5;

  // Service row icon / hero (ServiceHero, SettingsRowIcon).
  static const double heroMark = 96;
  static const double heroGlyph = 44;
  static const double heroGap = 16;
  static const double heroTextGap = 8;
  static const double heroBloomFrom = 0.6;
  static const double heroBloomTurnDegrees = -40;

  // Two columns (ServiceSetupScreen.kt, landscape handset / wide window): the
  // hero side 0.42, the form side 0.58, 32 apart; 4 from the bar, the hero
  // side's blocks 20 apart.
  static const int setupLeadFlex = 42;
  static const int setupSideFlex = 58;
  static const double setupColumnsGap = 32;
  static const double setupColumnTop = 4;
  static const double setupLeadGap = 20;

  // The Preview badge beside Apple Music's name (ServiceHero): labelLarge, 12 × 4, 12 from the name.
  static const double heroBadgeGap = 12;
  static const double heroBadgeHorizontal = 12;
  static const double heroBadgeVertical = 4;

  // "You'll need" chips (RequirementsGroup).
  static const double needGap = 10;
  static const double chipSpacing = 8;
  static const double chipPaddingStart = 10;
  static const double chipPaddingEnd = 14;
  static const double chipPaddingVertical = 8;
  static const double chipIcon = 18;
  static const double chipIconGap = 6;

  // Connect group (SubsonicConnectGroup).
  static const double formPadding = 18;
  static const double formGap = 14;
  static const double buttonGap = 12;
  static const double addressErrorStart = 4;
  static const double addressErrorTop = 8;
  static const double statusIcon = 20;
  static const double statusIconGap = 8;
  static const double statusIndicator = 24;
  static const double statusIndicatorGap = 10;
  static const double secretToggleTouch = 44;
  static const double secretToggleIcon = 18;

  /// The ExpressiveTextField box: 14 + bodyLarge's 24 line + 14.
  static const double fieldBoxHeight = 52;

  // Rows (SettingsItem / SettingsRowIcon): Pixel's two-line list row.
  static const double rowMinHeight = 72;
  static const double rowPaddingHorizontal = 16;
  static const double rowPaddingVertical = 14;
  static const double rowIconBox = 40;
  static const double rowIcon = 24;
  static const double rowIconGap = 12;
  static const double rowTextGap = 2;
  static const double rowTrailingGap = 8;

  /// A radio row (QualityRow): 8 above and below, the radio inset 8.
  static const double radioRowVertical = 8;
  static const double radioInset = 8;

  // The account card's status pill (AccountStatusPill).
  static const double pillTop = 10;
  static const double pillHorizontal = 10;
  static const double pillVertical = 4;

  // Notes under a group (licenses intro) and the inline notice (InlineNotice).
  static const double introBottom = 16;
  static const double noticeHorizontal = 14;
  static const double noticeVertical = 12;
  static const double noticeRadius = 16;
  static const double groupGap = 16;

  // A license's own page.
  static const double licenseTextTop = 4;

  // The chrome seam under Settings' bar (SeamDissolve.kt SeamTop.Chrome): the
  // tide line, on HomeSeamMetrics' numbers (SeamDissolveTokens); it comes down
  // over the first [seamRevealDistance] of scroll.
  static const double seamRevealDistance = 40; // SeamDissolve RevealDistance

  // Cloud sync (CloudSyncScreen.kt).
  /// Where a row's text starts: padding 16 + icon box 40 + gap 12 (SettingsRowTextInset).
  static const double rowTextInset = 68;
  static const double syncLeadTop = 12; // CloudSyncHeadline lead
  static const double syncStatusEnd = 8; // StatusRow: Sync now sits closer to the edge
  static const double syncLoading = 32; // StatusRow's loading indicator
  static const double syncPanelTop = 14; // ActionPanel
  static const double syncPanelBottom = 16;
  static const double syncPanelGap = 12;
  static const double syncButtonGap = 8; // FlowRow spacing of panel / account buttons
  static const double syncActionsGap = 12; // OffContent's notice, details and Try again
  static const double syncExpandBottom = 18; // SettingsExpandableItem body
  static const double syncExpandGap = 14;
  static const double syncListLineGap = 4; // WhatSyncsList
  static const double syncSummaryAlpha = 0.6; // account status line on a service line
  static const double syncDisabledAlpha = 0.38; // a destructive row while it can't be used (M3 disabled content)

  // Settings › AI features (AiSettingsPage.kt): blocks 20 apart, the tier cards (TierCard) 84 high,
  // 8 apart, their corner 22 at rest and 32 chosen, the label 18 in.
  static const double aiBlockGap = 20;
  static const double aiTierGap = 8;
  static const double aiTierHeight = 84;
  static const double aiTierCorner = 22;
  static const double aiTierCornerSelected = 32;
  static const double aiTierPadding = 18;

  // The page's parts (AiSettingsPage.kt / AiSubPages.kt, AI settings v2).
  static const double aiSectionLabelInset = 4; // SectionLabel
  static const double aiGroupGap = 8; // label → its row, Custom's fields
  static const double aiPullReserve = 68; // the model field's text stops short of Fetch (64 dp button + 4)
  static const double aiKeyGap = 4; // key field → its link
  static const double aiChipHeight = 44; // ChoiceChip: 44 high, corner 14 at rest, a pill when picked
  static const double aiChipCorner = 14;
  static const double aiChipPadding = 12;
  static const double aiProviderChipPadding = 4; // the service row's weighted chips
  static const double aiChipGap = 6;
  static const double aiModelChipHeight = 36; // the fetched model ids
  static const double aiModelChipCorner = 12;
  static const int aiMaxModelChips = 24;
  static const double aiThinkingTop = 20;
  static const double aiLinkIcon = 16;
  static const double aiLinkIconGap = 4;
  static const double aiTryHeight = 52; // the 「拿一首歌试试」 / 「试试」 buttons
  static const double aiTryIcon = 20;
  static const double aiTryIconGap = 8;

  // TradeoffChart: a 200-high plot, 52 gutter left for the y values, the x
  // values and label 34 under the plot; dots 5.5 (7.5 picked) on a surface
  // ring 2 wider, a 13 halo when picked; untested tiers hollow in the corner.
  static const double aiChartHeight = 200;
  static const double aiChartLeft = 52;
  static const double aiChartRight = 8;
  static const double aiChartTop = 8;
  static const double aiChartBottom = 34;
  static const int aiChartGridSteps = 4;
  static const double aiChartGridStroke = 1;
  static const double aiChartXEndGap = 4;
  static const double aiChartYEndGap = 6;
  static const double aiChartYWorstLift = 12;
  static const double aiChartXLabelTop = 16;
  static const double aiChartEndText = 10;
  static const double aiChartGap = 4; // plot → legend
  static const double aiDot = 5.5;
  static const double aiDotPicked = 7.5;
  static const double aiDotRing = 7.5;
  static const double aiDotRingPicked = 9.5;
  static const double aiDotHalo = 13;
  static const double aiDotHaloAlpha = 0.18;
  static const double aiDotDimAlpha = 0.3;
  static const double aiDotLabelGap = 10;
  static const double aiUntestedInset = 14;
  static const double aiUntestedStep = 18;
  static const double aiUntestedStroke = 2;
  static const double aiUntestedDash = 6;
  static const double aiUntestedDashGap = 4;
  static const double aiUntestedDimAlpha = 0.45;
  static const double aiLegendDot = 8;
  static const double aiLegendDotGap = 4;
  static const double aiLegendGap = 10;
  static const double aiFlipHeight = 36;
  static const double aiFlipPadding = 12;
  static const double aiFlipPaddingNarrow = 9;
  static const double aiFlipIcon = 18;
  static const double aiFlipGap = 6;
  static const double aiFlipTextMinWidth = 360;

  // The tier page (AiTiersPageContent): two fact cards, corner 28 for the one in use.
  static const double aiTiersGap = 16;
  static const double aiFactsCorner = 20;
  static const double aiFactsCornerPicked = 28;
  static const double aiFactsPadding = 16;
  static const double aiFactsGap = 10;

  // 「拿一首歌试试」 (AiTryPageContent).
  static const double aiTryFieldGap = 8;
  static const double aiTryLoading = 32;
  static const double aiTryLoadingGap = 12;
  static const double aiTryResultGap = 12;
  static const double aiTryCardCorner = 20;
  static const double aiTryCardPadding = 16;
  static const double aiTryFieldMaxWidth = 640;

  // List-detail on wide windows (SettingsTablet; main_split_config.xml splitRatio 0.33).
  static const double splitListFraction = 0.33;
  static const double splitListMinWidth = 360;
  static const double placeholderMark = 148;
  static const double placeholderStroke = 2;
  static const double placeholderGap = 24;
}
