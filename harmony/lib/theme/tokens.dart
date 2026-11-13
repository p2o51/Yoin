/// Yoin's shared numbers, mirrored from `:shared` YoinTokens.kt (iOS branch,
/// shared/src/commonMain/.../tokens/YoinTokens.kt). Lengths are logical
/// pixels (1 dp = 1 vp = 1 lp). When a value changes there, change it here.
library;

/// The floating bottom bar (FloatingBottomBar.kt, YoinButtonGroup.kt).
abstract final class BarTokens {
  /// Outer height of the portrait bar's pill surface (48 button + 10 × 2).
  static const double height = 68;
  static const double buttonHeight = 48;
  static const double rowPadding = 10;
  static const double horizontalMargin = 16;
  static const double portraitVerticalMargin = 12;

  /// Gap between bar islands.
  static const double itemGap = 8;

  /// The bar surface's corner (MaterialTheme.shapes.extraLarge).
  static const double corner = 28;

  static const double iconSize = 24;
  static const double iconLabelGap = 8;

  /// Centred bar (Medium / Wide windows).
  static const double centeredHeight = 60;
  static const double centeredButtonHeight = 44;
  static const double centeredBottomMargin = 24;
  static const double centeredMaxWidth = 600;
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
}

/// The Library root, Compact (LibraryScreen.kt, SongListItem.kt).
abstract final class LibraryTokens {
  static const double gridGutterH = 12;
  static const double gridGutterV = 16;
  static const double coverToTitle = 5;

  /// Room a list keeps over the bottom bar.
  static const double bottomGroupPadding = 108;

  static const double rowPaddingH = 16;
  static const double rowPaddingV = 10;
  static const double rowThumb = 48;
  static const double rowGap = 12;
}
