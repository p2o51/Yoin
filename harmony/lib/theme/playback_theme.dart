import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_color_utilities/material_color_utilities.dart' show QuantizerCelebi, Score;

import '../app/providers.dart';
import '../nowplaying/player_snapshot.dart';
import '../ui/component/artwork_image.dart';
import 'theme.dart';

/// The cover's colour field for Now Playing's room (FocusField.kt
/// buildCoverField): the four heaviest hue buckets, most present first, and
/// the most chromatic cell; the box-averaged [grid] focus's field samples the
/// rim from, and the [swatches] dark / light focus take their other hues from.
@immutable
final class CoverField {
  const CoverField({required this.palette, required this.accent, this.grid = const [], this.swatches = const []});

  /// [gridSize]² ARGB samples per side.
  static const int gridSize = 24;

  /// Four colours (padded by repeating the last).
  final List<Color> palette;
  final Color accent;

  /// [gridSize]² ARGB samples, row-major from the cover's top-left; empty = unknown.
  final List<int> grid;

  /// The cover's chromatic colours in order of importance (ARGB; Android:
  /// Palette's swatches, here MCU Score's picks, then the dominant colour).
  final List<int> swatches;

  List<int> get argbPalette => [for (final c in palette) c.toARGB32()];
  int get accentArgb => accent.toARGB32();

  /// The cover's colour at ([u], [v]) in 0..1, nearest sample (the main hue without a grid).
  int at(double u, double v) {
    if (grid.length < gridSize * gridSize) return palette.first.toARGB32();
    final x = (u * gridSize).floor().clamp(0, gridSize - 1);
    final y = (v * gridSize).floor().clamp(0, gridSize - 1);
    return grid[y * gridSize + x];
  }

  CoverField withPalette(List<int> argb) =>
      CoverField(palette: [for (final c in argb) Color(c)], accent: accent, grid: grid, swatches: swatches);

  @override
  bool operator ==(Object other) =>
      other is CoverField &&
      listEquals(other.palette, palette) &&
      other.accent == accent &&
      listEquals(other.grid, grid) &&
      listEquals(other.swatches, swatches);

  @override
  int get hashCode => Object.hash(Object.hashAll(palette), accent, Object.hashAll(grid), Object.hashAll(swatches));
}

/// One cover's colours: the scheme seed and the room field.
@immutable
final class CoverColors {
  const CoverColors({required this.seed, required this.field});
  final Color seed;
  final CoverField field;
}

/// The cover currently themes the app; null = Yoin's default scheme.
@immutable
final class PlaybackThemeState {
  const PlaybackThemeState({this.seed, this.field, this.coverUrl});

  static const none = PlaybackThemeState();

  final Color? seed;
  final CoverField? field;
  final String? coverUrl;

  @override
  bool operator ==(Object other) =>
      other is PlaybackThemeState && other.seed == seed && other.field == field && other.coverUrl == coverUrl;

  @override
  int get hashCode => Object.hash(seed, field, coverUrl);
}

/// Loads a cover and extracts its colours (an interface so tests stay offline).
abstract interface class CoverColorLoader {
  Future<CoverColors?> load(String url);
}

/// Downloads the cover through Flutter's image cache at 200 px, then on a
/// background isolate: MCU Celebi quantizer + Score for the seed (Android
/// uses androidx Palette's vibrant swatch — seeds can differ on some covers),
/// and a 24 × 24 grid for the cover field.
class NetworkCoverColorLoader implements CoverColorLoader {
  const NetworkCoverColorLoader();

  static const int decodeSize = 200;

  @override
  Future<CoverColors?> load(String url) async {
    final provider = artworkImageProvider(url, cacheWidth: decodeSize, cacheHeight: decodeSize);
    final completer = Completer<ui.Image?>();
    final stream = provider.resolve(ImageConfiguration.empty);
    late final ImageStreamListener listener;
    listener = ImageStreamListener(
      (info, _) {
        if (!completer.isCompleted) completer.complete(info.image.clone());
        stream.removeListener(listener);
      },
      onError: (_, _) {
        if (!completer.isCompleted) completer.complete(null);
        stream.removeListener(listener);
      },
    );
    stream.addListener(listener);
    final image = await completer.future;
    if (image == null) return null;
    final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
    final width = image.width;
    final height = image.height;
    image.dispose();
    if (data == null) return null;
    final result = await compute(_extract, (data.buffer.asUint8List(), width, height));
    if (result == null) return null;
    return CoverColors(
      seed: Color(result.$1),
      field: CoverField(
        palette: [for (final c in result.$2) Color(c)],
        accent: Color(result.$3),
        grid: result.$4,
        swatches: result.$5,
      ),
    );
  }
}

Future<(int, List<int>, int, List<int>, List<int>)?> _extract((Uint8List, int, int) input) async {
  final (rgba, width, height) = input;
  final pixels = <int>[];
  for (var i = 0; i + 3 < rgba.length; i += 4) {
    if (rgba[i + 3] < 255) continue;
    pixels.add(0xFF000000 | (rgba[i] << 16) | (rgba[i + 1] << 8) | rgba[i + 2]);
  }
  if (pixels.isEmpty) return null;
  final quantized = await QuantizerCelebi().quantize(pixels, 128);
  final field = buildCoverField(rgba, width, height);
  return (
    pickCoverSeed(quantized.colorToCount),
    [for (final c in field.palette) c.toARGB32()],
    field.accent.toARGB32(),
    field.grid,
    coverSwatches(quantized.colorToCount),
  );
}

/// The cover's swatches for focus's room (FocusField.kt CoverField.swatches):
/// MCU Score's chromatic picks, most suitable first, then the most populous
/// colour. Android reads androidx Palette's swatches here; both give the
/// cover's main colours in order of importance, greys included last.
List<int> coverSwatches(Map<int, int> colorToCount) {
  if (colorToCount.isEmpty) return const [];
  var dominant = colorToCount.keys.first;
  colorToCount.forEach((argb, count) {
    if (count > colorToCount[dominant]!) dominant = argb;
  });
  final picks = Score.score(colorToCount, desired: 5, fallbackColorARGB: dominant, filter: true);
  return [...picks, if (!picks.contains(dominant)) dominant];
}

/// The theme seed from the quantized cover: MCU Score's top pick, but when
/// Score rejects every colour (grey / black-and-white covers, chroma < 5) the
/// most populous colour instead of MCU's Google Blue fallback — Android's
/// CoverSeedExtractor falls back vibrant → dominant → muted, so a grey cover
/// gives a grey-hued scheme, never an unrelated blue.
int pickCoverSeed(Map<int, int> colorToCount) {
  if (colorToCount.isEmpty) return 0xFF808080;
  var dominant = colorToCount.keys.first;
  colorToCount.forEach((argb, count) {
    if (count > colorToCount[dominant]!) dominant = argb;
  });
  return Score.score(colorToCount, desired: 1, fallbackColorARGB: dominant).first;
}

/// FocusField.kt buildCoverField: box-average the image into a 24 × 24 grid;
/// per cell HSL chroma = s × (1 − |2l − 1|), weight 0.15 + chroma, weighted
/// RGB into 12 hue buckets (30° each); palette = the 4 heaviest buckets'
/// means (padded); accent = the most chromatic cell.
CoverField buildCoverField(Uint8List rgba, int width, int height, {int grid = 24, int buckets = 12}) {
  final sums = List.generate(buckets, (_) => [0.0, 0.0, 0.0, 0.0]);
  final cells = List<int>.filled(grid * grid, 0xFF000000);
  var bestChroma = -1.0;
  var accent = const Color(0xFF808080);
  for (var gy = 0; gy < grid; gy++) {
    for (var gx = 0; gx < grid; gx++) {
      final x0 = gx * width ~/ grid, x1 = math.max(x0 + 1, (gx + 1) * width ~/ grid);
      final y0 = gy * height ~/ grid, y1 = math.max(y0 + 1, (gy + 1) * height ~/ grid);
      var r = 0, g = 0, b = 0, n = 0;
      for (var y = y0; y < y1 && y < height; y++) {
        for (var x = x0; x < x1 && x < width; x++) {
          final i = (y * width + x) * 4;
          r += rgba[i];
          g += rgba[i + 1];
          b += rgba[i + 2];
          n++;
        }
      }
      if (n == 0) continue;
      final color = Color.fromARGB(255, r ~/ n, g ~/ n, b ~/ n);
      cells[gy * grid + gx] = color.toARGB32();
      final hsl = HSLColor.fromColor(color);
      final chroma = hsl.saturation * (1 - (2 * hsl.lightness - 1).abs());
      final weight = 0.15 + chroma;
      final bucket = (hsl.hue / (360 / buckets)).floor().clamp(0, buckets - 1);
      final s = sums[bucket];
      s[0] += (r / n) * weight;
      s[1] += (g / n) * weight;
      s[2] += (b / n) * weight;
      s[3] += weight;
      if (chroma > bestChroma) {
        bestChroma = chroma;
        accent = color;
      }
    }
  }
  final order = [for (var i = 0; i < buckets; i++) i]..sort((a, b) => sums[b][3].compareTo(sums[a][3]));
  final palette = <Color>[];
  for (final i in order) {
    final s = sums[i];
    if (s[3] <= 0) break;
    palette.add(Color.fromARGB(255, (s[0] / s[3]).round(), (s[1] / s[3]).round(), (s[2] / s[3]).round()));
    if (palette.length == 4) break;
  }
  if (palette.isEmpty) palette.add(accent);
  while (palette.length < 4) {
    palette.add(palette.last);
  }
  return CoverField(palette: palette, accent: accent, grid: grid == CoverField.gridSize ? cells : const []);
}

final coverColorLoaderProvider = Provider<CoverColorLoader>((ref) => const NetworkCoverColorLoader());

/// How long an emptied queue waits before the default scheme returns.
const Duration playbackThemeClearDelay = Duration(milliseconds: 220);

/// The app-session playback theme (PlaybackThemeState.kt): follows the
/// playing cover (200 px), single-flight (a newer cover wins), keeps the
/// palette while a track without a cover plays, clears 220 ms after the
/// queue empties. The scheme is applied in ONE cut (owner 2026-11-04): no
/// colour tween; [playbackThemeData] memoises one ThemeData per seed.
class PlaybackTheme extends Notifier<PlaybackThemeState> {
  int _generation = 0;
  Timer? _clear;
  String? _requested;

  @override
  PlaybackThemeState build() {
    ref.onDispose(() => _clear?.cancel());
    ref.listen(playerSnapshotProvider.select((s) => s.track?.coverArt), (_, _) => _sync(), fireImmediately: false);
    ref.listen(playerSnapshotProvider.select((s) => s.hasTrack), (_, _) => _sync());
    ref.listen(activeSourceProvider, (_, _) => _sync());
    Future.microtask(_sync);
    return PlaybackThemeState.none;
  }

  void _sync() {
    if (!ref.mounted) return;
    final snapshot = ref.read(playerSnapshotProvider);
    final source = ref.read(activeSourceProvider).value;
    final cover = snapshot.track?.coverArt;
    final url = cover == null ? null : source?.resolveCoverUrl(cover, size: NetworkCoverColorLoader.decodeSize);
    if (url == null) {
      if (snapshot.hasTrack) return; // Keep the current palette.
      _requested = null;
      _clear?.cancel();
      _clear = Timer(playbackThemeClearDelay, () {
        if (ref.mounted && !ref.read(playerSnapshotProvider).hasTrack) {
          _generation++;
          state = PlaybackThemeState.none;
        }
      });
      return;
    }
    _clear?.cancel();
    if (url == _requested) return;
    _requested = url;
    final generation = ++_generation;
    () async {
      CoverColors? colors;
      try {
        colors = await ref.read(coverColorLoaderProvider).load(url);
      } catch (e) {
        debugPrint('cover colours failed: $e');
      }
      if (!ref.mounted || generation != _generation || colors == null) return;
      // Only replace when the seed changed (the field may still refresh).
      state = PlaybackThemeState(seed: colors.seed, field: colors.field, coverUrl: url);
    }();
  }
}

final playbackThemeProvider = NotifierProvider<PlaybackTheme, PlaybackThemeState>(PlaybackTheme.new);

final Map<(int?, Brightness), ThemeData> _themeMemo = {};
final Map<Brightness, ThemeData> _shownByBrightness = {};

/// One ThemeData per (seed, brightness), and the instance already shown when
/// the new scheme equals it (SharedColorScheme dedupe): a same-colour switch
/// never re-themes the tree.
ThemeData playbackThemeData(Color? seed, Brightness brightness) {
  final key = (seed?.toARGB32(), brightness);
  var theme = _themeMemo[key];
  if (theme == null) {
    theme = seed == null ? yoinTheme(brightness) : yoinTheme(brightness, seed: seed);
    if (_themeMemo.length > 16) _themeMemo.remove(_themeMemo.keys.first);
    _themeMemo[key] = theme;
  }
  final shown = _shownByBrightness[brightness];
  if (shown != null && shown.colorScheme == theme.colorScheme) return shown;
  _shownByBrightness[brightness] = theme;
  return theme;
}
