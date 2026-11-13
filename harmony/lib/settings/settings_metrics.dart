/// Settings' and the setup page's numbers, read off Android
/// (SettingsScreen.kt, SettingsComponents.kt, AccountIdentity.kt,
/// service/ServiceSetupScreen.kt). Kept beside the feature until they move
/// into `lib/theme/tokens.dart` (that file is shared; this one is Settings').
abstract final class SettingsMetrics {
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
}
