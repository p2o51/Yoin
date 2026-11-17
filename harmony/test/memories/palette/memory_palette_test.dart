import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/widgets/cover_palette.dart';
import 'package:yoin/memories/palette/memory_palette.dart';

// Expected values computed from the Kotlin formulas (MemoryPalette.kt) with 8-bit rounding at each mix.
void main() {
  const m1 = MemoryPaletteSamples.m1;

  test('should_matchKotlinTones_when_m1IsLight', () {
    final t = m1.tones(false);
    expect(t.ink, m1.base);
    expect(t.onButton, const Color(0xFFFFFFFF));
    expect(t.tint, m1.base.withValues(alpha: 0.06));
  });

  test('should_matchKotlinTones_when_m1IsDark', () {
    final t = m1.tones(true);
    expect(t.ink.toARGB32(), 0xFF9A90CC);
    expect(t.button.toARGB32(), 0xFF9A90CC);
    expect(t.highlight.toARGB32(), 0xFFE0E2FF);
    expect(t.onButton, m1.deep);
    expect(t.tint, const Color(0xFF9A90CC).withValues(alpha: 0.1));
  });

  test('should_roundEveryChannel_when_mixing', () {
    expect(mixSrgb(const Color(0xFF000000), const Color(0xFFFFFFFF), 0.5).toARGB32(), 0xFF808080); // 127.5 → 128
    expect(mixSrgb(m1.base, m1.soft, 0).toARGB32(), m1.base.toARGB32());
    expect(jsRound(-2.5), -2);
  });

  test('should_holdBaseInInkWindow_when_builtFromBackdrop', () {
    final p = MemoryPalette.fromBackdrop(const Color(0xFFE05A2B), const Color(0x80FFC0A0));
    expect(p.base.toARGB32(), 0xFFC54F26);
    expect(p.deep.toARGB32(), 0xFF592411);
    expect(p.soft.toARGB32(), 0xFFF3DCD4);
    expect(p.accent.a, 1);
  });

  test('should_leaveColourAlone_when_insideLightnessWindow', () {
    expect(holdLightness(m1.base, 0.22, 0.46).toARGB32(), m1.base.toARGB32());
  });

  test('should_holdAccentLightness_when_noCandidateSharesTheHue', () {
    final accent = memoryAccentFor(const Color(0xFF1F4F8F), const [Color(0xFFE5A07C)]);
    final l = HSLColor.fromColor(accent).lightness;
    expect(l, inInclusiveRange(memoryAccentMinLightness - 0.01, memoryAccentMaxLightness + 0.01));
    expect(HSLColor.fromColor(accent).hue, closeTo(HSLColor.fromColor(const Color(0xFF1F4F8F)).hue, 3));
  });

  test('should_buildPaletteFromCoverColours_when_extracted', () {
    final p = memoryPaletteOf(const BackdropColors(Color(0xFFE05A2B), Color(0xFFF2A07E)));
    expect(p.base.toARGB32(), 0xFFC54F26);
  });

  testWidgets('should_showThemeFallback_when_noCover', (tester) async {
    MemoryPalette? seen;
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF3B2D8F))),
        home: MemoryPaletteBuilder(
          cacheKey: null,
          image: null,
          builder: (context, palette) {
            seen = palette;
            return const SizedBox();
          },
        ),
      ),
    );
    final primary = ColorScheme.fromSeed(seedColor: const Color(0xFF3B2D8F)).primary;
    expect(seen, MemoryPalette.fallback(primary));
  });
}
