import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/theme/playback_theme.dart';

import '../support/nowplaying_fakes.dart';

Uint8List _image(int w, int h, Color Function(int x, int y) color) {
  final bytes = Uint8List(w * h * 4);
  for (var y = 0; y < h; y++) {
    for (var x = 0; x < w; x++) {
      final c = color(x, y);
      final i = (y * w + x) * 4;
      bytes[i] = (c.r * 255).round();
      bytes[i + 1] = (c.g * 255).round();
      bytes[i + 2] = (c.b * 255).round();
      bytes[i + 3] = 255;
    }
  }
  return bytes;
}

void main() {
  test('should_rankHeaviestHueFirst_when_buildingCoverField', () {
    const red = Color(0xFFE53935);
    const blue = Color(0xFF1E88E5);
    // Three quarters red, one quarter blue.
    final field = buildCoverField(_image(48, 48, (x, y) => x < 36 ? red : blue), 48, 48);
    expect(field.palette, hasLength(4));
    expect(field.palette[0].toARGB32(), red.toARGB32());
    expect(field.palette[1].toARGB32(), blue.toARGB32());
    expect(field.palette[3], field.palette[1], reason: 'padded by repeating the last bucket');
  });

  test('should_seedWithDominantGrey_when_coverHasNoChroma', () {
    const grey = 0xFF777777;
    const nearBlack = 0xFF111111;
    final seed = pickCoverSeed({grey: 300, nearBlack: 100});
    expect(seed, grey, reason: 'never MCU fallback Google Blue');
    expect(seed, isNot(0xFF4285F4));
  });

  test('should_seedWithScoredColour_when_coverIsChromatic', () {
    const red = 0xFFE53935;
    expect(pickCoverSeed({red: 50, 0xFF777777: 500}), red);
  });

  test('should_reuseShownTheme_when_schemeUnchanged', () {
    const seed = Color(0xFF3366CC);
    final a = playbackThemeData(seed, Brightness.light);
    final b = playbackThemeData(seed, Brightness.light);
    expect(identical(a, b), isTrue);
    expect(identical(playbackThemeData(null, Brightness.dark), playbackThemeData(null, Brightness.dark)), isTrue);
  });

  test('should_followCoverThenClear_when_queueEmpties', () async {
    final tracks = nowPlayingTracks(1);
    final controller = NowPlayingFakeController(playingState(tracks));
    final loader = NowPlayingFakeColorLoader(
      const CoverColors(
        seed: Color(0xFF00AA55),
        field: CoverField(palette: [Color(0xFF00AA55)], accent: Color(0xFF00AA55)),
      ),
    );
    final c = ProviderContainer(
      overrides: nowPlayingOverrides(controller: controller, colors: loader),
    );
    addTearDown(c.dispose);
    c.listen(playbackThemeProvider, (_, _) {});
    await c.read(activeSourceProvider.future);
    for (var i = 0; i < 5; i++) {
      await Future<void>.delayed(Duration.zero);
    }
    expect(loader.requests.single, contains('c0'));
    expect(c.read(playbackThemeProvider).seed, const Color(0xFF00AA55));

    controller.emit(NowPlayingState.idle);
    await Future<void>.delayed(playbackThemeClearDelay);
    // Real timers: under a loaded full run the clear can land a little late.
    final deadline = DateTime.now().add(const Duration(seconds: 2));
    while (c.read(playbackThemeProvider).seed != null && DateTime.now().isBefore(deadline)) {
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
    expect(c.read(playbackThemeProvider).seed, isNull);
  });
}
