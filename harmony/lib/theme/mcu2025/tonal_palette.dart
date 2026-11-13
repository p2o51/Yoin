import 'package:material_color_utilities/material_color_utilities.dart' show Hct, KeyColor;

/// Hue bands the 2025 spec treats specially (vendored Kotlin `hct/Hct.kt`).
abstract final class HueBands {
  /// Blue: 250 ≤ hue < 270.
  static bool isBlue(double hue) => hue >= 250 && hue < 270;

  /// Yellow: 105 ≤ hue < 125. Yellow surfaces go one tone lighter and
  /// tone 99 is averaged so it does not read as cream.
  static bool isYellow(double hue) => hue >= 105 && hue < 125;

  /// Cyan: 170 ≤ hue < 207. Cyan accents are capped at tone 88.
  static bool isCyan(double hue) => hue >= 170 && hue < 207;
}

/// A hue + chroma that yields a colour at any tone, as the vendored Kotlin
/// MCU (`palettes/TonalPalette.kt`) does it.
///
/// Differs from the Dart package's `TonalPalette` in one rule the 2025 spec
/// relies on: on a yellow hue, tone 99 is the RGB average of tones 98 and
/// 100. The key colour search (`KeyColor`) is the same algorithm in both, so
/// it is reused.
final class TonalPalette {
  TonalPalette._(this.hue, this.chroma, this.keyColor);

  /// A palette of [hue] at the requested [chroma]; the key colour is the
  /// tone closest to T50 that can hold that chroma.
  factory TonalPalette.fromHueAndChroma(double hue, double chroma) =>
      TonalPalette._(hue, chroma, KeyColor(hue, chroma).create());

  /// A palette whose hue and chroma are [hct]'s, keyed on [hct] itself.
  factory TonalPalette.fromHct(Hct hct) => TonalPalette._(hct.hue, hct.chroma, hct);

  /// A palette from an ARGB colour's hue and chroma.
  factory TonalPalette.fromInt(int argb) => TonalPalette.fromHct(Hct.fromInt(argb));

  final double hue;
  final double chroma;
  final Hct keyColor;
  final Map<int, int> _cache = {};

  /// ARGB at an integer [tone].
  int tone(int tone) => _cache.putIfAbsent(tone, () {
    if (tone == 99 && HueBands.isYellow(hue)) {
      return _averageArgb(this.tone(98), this.tone(100));
    }
    return Hct.from(hue, chroma, tone.toDouble()).toInt();
  });

  /// HCT at any [tone].
  Hct getHct(double tone) {
    if (tone == 99.0 && HueBands.isYellow(hue)) return Hct.fromInt(this.tone(99));
    return Hct.from(hue, chroma, tone);
  }

  static int _averageArgb(int a, int b) {
    int avg(int shift) => ((((a >> shift) & 0xff) + ((b >> shift) & 0xff)) / 2).round() & 0xff;
    return 0xff000000 | (avg(16) << 16) | (avg(8) << 8) | avg(0);
  }

  @override
  String toString() => 'TonalPalette($hue, $chroma)';
}
