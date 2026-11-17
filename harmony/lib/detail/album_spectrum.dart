/// The album spectrum (专辑色谱, owner 2026-10-09; Android AlbumSpectrum.kt):
/// when the cover leaves view it breaks into vertical strips that sort
/// themselves into bands, one band per colour of the cover, each as wide as
/// that colour's share of it. Every colour keeps its hue and chroma but has
/// its lightness squeezed into a band white text always reads on (OKLab L
/// 0.30–0.46, ≥ 7:1) — no blur, no scrim.
library;

import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';

import '../theme/tokens.dart';
import '../ui/component/artwork_image.dart';

/// Spectrum numbers (AlbumSpectrum.kt). Detail-local: the strip count is
/// [DetailTokens.spectrumStrips].
abstract final class AlbumSpectrumTokens {
  /// The cover is decoded this big for the strips (SpectrumRequestSize).
  static const int requestSize = 256;

  /// k-means: colours, rounds.
  static const int clusters = 5;
  static const int kMeansRounds = 10;

  /// OKLab lightness band every spectrum colour is squeezed into.
  static const double lightnessMin = 0.30;
  static const double lightnessMax = 0.46;
  static const double chromaBoost = 1.15;

  /// White text on a band never drops below this (WCAG ratio).
  static const double minContrast = 7;
  static const double lightnessStep = 0.005;
}

/// One cover's spectrum: for each source column `x` of the cover, the bar
/// slot it travels to ([target]) and the tone-locked colour it becomes
/// ([color]). Slots are contiguous per colour, so the bar reads as bands.
@immutable
final class AlbumSpectrum {
  const AlbumSpectrum({required this.color, required this.target});

  final List<Color> color;
  final List<int> target;

  int get columns => color.length;

  /// The colour sitting at bar slot [slot].
  Color colorAtSlot(int slot) {
    final i = target.indexOf(slot.clamp(0, columns - 1));
    return color[i < 0 ? 0 : i];
  }

  /// A spectrum made of [colors] in equal bands, each column staying where it is
  /// (the stand-in until the cover resolves, and with no cover).
  factory AlbumSpectrum.fallback(List<Color> colors, {int columns = DetailTokens.spectrumStrips}) {
    final locked = (colors.isEmpty ? const [Color(0xFF808080)] : colors).map((c) => Color(lockTone(c.toARGB32())));
    final list = locked.toList();
    return AlbumSpectrum(
      color: [for (var x = 0; x < columns; x++) list[x * list.length ~/ columns]],
      target: [for (var x = 0; x < columns; x++) x],
    );
  }
}

/// A loaded cover for the bar: the spectrum, plus the image its strips are cut from.
final class AlbumSpectrumSource {
  const AlbumSpectrumSource(this.spectrum, this.image);

  final AlbumSpectrum spectrum;

  /// Decoded at [AlbumSpectrumTokens.requestSize]; owned by the cache.
  final ui.Image? image;
}

final _cache = <String, AlbumSpectrumSource>{};
final _inFlight = <String, Future<AlbumSpectrumSource?>>{};
const _cacheSize = 12;

/// The spectrum of the cover at [url] (cached, a few covers deep). null when
/// the image can't be read.
Future<AlbumSpectrumSource?> albumSpectrumSource(String url) {
  final hit = _cache.remove(url);
  if (hit != null) {
    _cache[url] = hit;
    return Future.value(hit);
  }
  return _inFlight[url] ??= _load(url).then(
    (source) {
      _inFlight.remove(url);
      if (source != null) {
        _cache[url] = source;
        while (_cache.length > _cacheSize) {
          final oldest = _cache.keys.first;
          _cache.remove(oldest)?.image?.dispose();
        }
      }
      return source;
    },
    onError: (Object _) {
      _inFlight.remove(url);
      return null;
    },
  );
}

/// What a spectrum layer may have already, without waiting.
AlbumSpectrumSource? cachedAlbumSpectrumSource(String url) => _cache[url];

Future<ui.Image?> _decode(ImageProvider provider) {
  final completer = Completer<ui.Image?>();
  final stream = provider.resolve(ImageConfiguration.empty);
  late final ImageStreamListener listener;
  listener = ImageStreamListener(
    (info, _) {
      if (!completer.isCompleted) completer.complete(info.image.clone());
      info.dispose();
      stream.removeListener(listener);
    },
    onError: (_, _) {
      if (!completer.isCompleted) completer.complete(null);
      stream.removeListener(listener);
    },
  );
  stream.addListener(listener);
  return completer.future;
}

Future<AlbumSpectrumSource?> _load(String url) async {
  const n = DetailTokens.spectrumStrips;
  const big = AlbumSpectrumTokens.requestSize;
  final image = await _decode(artworkImageProvider(url, cacheWidth: big, cacheHeight: big));
  if (image == null) return null;
  final small = await _decode(artworkImageProvider(url, cacheWidth: n, cacheHeight: n));
  if (small == null) return AlbumSpectrumSource(AlbumSpectrum.fallback(const []), image);
  final data = await small.toByteData(format: ui.ImageByteFormat.rawRgba);
  small.dispose();
  if (data == null) return AlbumSpectrumSource(AlbumSpectrum.fallback(const []), image);
  final rgba = data.buffer.asUint8List();
  final pixels = Int32List(n * n);
  for (var i = 0; i < n * n; i++) {
    pixels[i] = (0xFF << 24) | (rgba[i * 4] << 16) | (rgba[i * 4 + 1] << 8) | rgba[i * 4 + 2];
  }
  final spectrum = await compute(_spectrumFromPixels, pixels);
  return AlbumSpectrumSource(spectrum, image);
}

AlbumSpectrum _spectrumFromPixels(Int32List pixels) => computeAlbumSpectrum(pixels, DetailTokens.spectrumStrips);

final class _Cluster {
  _Cluster(this.lab, this.share);

  final List<double> lab;
  final double share;

  double get sortKey {
    final chroma = math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
    return chroma < 0.04 ? -10 + lab[0] : math.atan2(lab[2], lab[1]);
  }
}

double _distance(List<double> a, List<double> b) {
  final d0 = a[0] - b[0], d1 = a[1] - b[1], d2 = a[2] - b[2];
  return d0 * d0 + d1 * d1 + d2 * d2;
}

/// The spectrum of an [n]×[n] ARGB cover sample. Pure, deterministic: the
/// same pixels always give the same bands (Android computeAlbumSpectrum).
AlbumSpectrum computeAlbumSpectrum(List<int> pixels, int n) {
  assert(pixels.length == n * n);
  final px = [for (final p in pixels) rgbToOklab(p)];

  // Farthest-point seeds, then a few rounds of k-means.
  final centres = <List<double>>[px[px.length ~/ 2]];
  while (centres.length < AlbumSpectrumTokens.clusters) {
    var best = 0.0;
    var bestIndex = 0;
    for (var i = 0; i < px.length; i++) {
      final d = centres.map((c) => _distance(px[i], c)).reduce(math.min);
      if (d > best) {
        best = d;
        bestIndex = i;
      }
    }
    if (best < 0.002) break;
    centres.add(px[bestIndex]);
  }
  final assign = List<int>.filled(px.length, 0);
  int nearest(List<double> p) {
    var bi = 0;
    var bd = double.infinity;
    for (var c = 0; c < centres.length; c++) {
      final d = _distance(p, centres[c]);
      if (d < bd) {
        bd = d;
        bi = c;
      }
    }
    return bi;
  }

  for (var round = 0; round < AlbumSpectrumTokens.kMeansRounds; round++) {
    for (var i = 0; i < px.length; i++) {
      assign[i] = nearest(px[i]);
    }
    for (var c = 0; c < centres.length; c++) {
      final sum = [0.0, 0.0, 0.0];
      var count = 0;
      for (var i = 0; i < px.length; i++) {
        if (assign[i] != c) continue;
        count++;
        for (var k = 0; k < 3; k++) {
          sum[k] += px[i][k];
        }
      }
      if (count > 0) centres[c] = [for (final v in sum) v / count];
    }
  }

  // Colours by share; a small colour survives only if it is clearly unlike the big ones.
  final candidates = [
    for (var c = 0; c < centres.length; c++) _Cluster(centres[c], assign.where((a) => a == c).length / px.length),
  ].where((c) => c.share > 0.01).toList()..sort((a, b) => b.share.compareTo(a.share));
  final kept = <_Cluster>[];
  for (final cluster in candidates) {
    if (kept.isEmpty ||
        cluster.share >= 0.05 ||
        kept.map((k) => _distance(cluster.lab, k.lab)).reduce(math.min) > 0.03) {
      kept.add(cluster);
    }
  }
  // A cover of one colour still gets two bands: the same hue, darker and lighter.
  if (kept.length == 1) {
    final only = kept.single;
    kept
      ..[0] = _Cluster([only.lab[0] - 0.12, only.lab[1], only.lab[2]], only.share / 2)
      ..add(_Cluster([only.lab[0] + 0.12, only.lab[1], only.lab[2]], only.share / 2));
  }

  // Bands in hue order (neutrals first, dark to light); widths by share, at least two strips each.
  final order = [for (var i = 0; i < kept.length; i++) i]..sort((a, b) => kept[a].sortKey.compareTo(kept[b].sortKey));
  final total = kept.fold(0.0, (s, c) => s + c.share);
  final raw = [for (final i in order) kept[i].share / total * n];
  final counts = [for (final r in raw) math.max(2, r.floor())];
  int sum() => counts.fold(0, (s, c) => s + c);
  int argMax(List<num> v) {
    var bi = 0;
    for (var i = 1; i < v.length; i++) {
      if (v[i] > v[bi]) bi = i;
    }
    return bi;
  }

  while (sum() < n) {
    final i = argMax([for (var j = 0; j < raw.length; j++) raw[j] - counts[j]]);
    counts[i]++;
    raw[i] = -1;
  }
  while (sum() > n) {
    counts[argMax(counts)]--;
  }
  final slots = <int>[];
  for (var j = 0; j < order.length; j++) {
    for (var k = 0; k < counts[j]; k++) {
      slots.add(order[j]);
    }
  }

  // Each source column goes to the free slot whose colour it is nearest, keeping left-to-right order
  // as a tie-break; the columns that look most like a band choose first.
  final columnLab = [
    for (var x = 0; x < n; x++)
      [for (var k = 0; k < 3; k++) Iterable<int>.generate(n).fold(0.0, (s, y) => s + px[y * n + x][k]) / n],
  ];
  final free = [for (var s = 0; s < n; s++) s];
  final target = List<int>.filled(n, 0);
  final columnCluster = List<int>.filled(n, 0);
  double fit(int x) => kept.map((k) => _distance(columnLab[x], k.lab)).reduce(math.min);
  final byFit = [for (var x = 0; x < n; x++) x]
    ..sort((a, b) {
      final c = fit(a).compareTo(fit(b));
      return c != 0 ? c : a.compareTo(b);
    });
  for (final x in byFit) {
    var bestSlot = free.first;
    var bestCost = double.infinity;
    for (final s in free) {
      final cost = _distance(columnLab[x], kept[slots[s]].lab) + 0.0004 * (s - x).abs();
      if (cost < bestCost) {
        bestCost = cost;
        bestSlot = s;
      }
    }
    free.remove(bestSlot);
    target[x] = bestSlot;
    columnCluster[x] = slots[bestSlot];
  }
  final locked = [for (final c in kept) Color(lockTone(oklabToArgb(c.lab)))];
  return AlbumSpectrum(color: [for (var x = 0; x < n; x++) locked[columnCluster[x]]], target: target);
}

// ---------------------------------------------------------------------------
// OKLab and the tone lock.
// ---------------------------------------------------------------------------

/// Keep hue and chroma, put lightness in the band; ARGB in and out. Gamut
/// mapping and 8-bit rounding can leave a saturated light colour just short
/// of the contrast floor, so the result is checked and stepped darker.
int lockTone(int argb) {
  final lab = rgbToOklab(argb);
  var l =
      AlbumSpectrumTokens.lightnessMin +
      (AlbumSpectrumTokens.lightnessMax - AlbumSpectrumTokens.lightnessMin) * lab[0].clamp(0.0, 1.0);
  final a = lab[1] * AlbumSpectrumTokens.chromaBoost;
  final b = lab[2] * AlbumSpectrumTokens.chromaBoost;
  var out = oklabToArgb([l, a, b]);
  while (contrastWithWhite(out) < AlbumSpectrumTokens.minContrast && l > AlbumSpectrumTokens.lightnessMin) {
    l = math.max(AlbumSpectrumTokens.lightnessMin, l - AlbumSpectrumTokens.lightnessStep);
    out = oklabToArgb([l, a, b]);
  }
  return out;
}

/// WCAG contrast of white on [argb].
double contrastWithWhite(int argb) {
  final luminance =
      0.2126 * _toLinear((argb >> 16) & 0xFF) +
      0.7152 * _toLinear((argb >> 8) & 0xFF) +
      0.0722 * _toLinear(argb & 0xFF);
  return 1.05 / (luminance + 0.05);
}

double _toLinear(int channel) {
  final v = channel / 255;
  return v <= 0.04045 ? v / 12.92 : math.pow((v + 0.055) / 1.055, 2.4).toDouble();
}

int _toSrgb(double linear) {
  final v = linear.clamp(0.0, 1.0);
  final s = v <= 0.0031308 ? 12.92 * v : 1.055 * math.pow(v, 1 / 2.4) - 0.055;
  return (s * 255 + 0.5).toInt().clamp(0, 255);
}

double _cbrt(double x) => x < 0 ? -math.pow(-x, 1 / 3).toDouble() : math.pow(x, 1 / 3).toDouble();

List<double> rgbToOklab(int argb) {
  final r = _toLinear((argb >> 16) & 0xFF);
  final g = _toLinear((argb >> 8) & 0xFF);
  final b = _toLinear(argb & 0xFF);
  final l = _cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  final m = _cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  final s = _cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
  ];
}

List<double> _oklabToLinear(List<double> lab) {
  double cube(double v) => v * v * v;
  final l = cube(lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2]);
  final m = cube(lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2]);
  final s = cube(lab[0] - 0.0894841775 * lab[1] - 1.2914855480 * lab[2]);
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
  ];
}

int _pack(List<double> lin) => (0xFF << 24) | (_toSrgb(lin[0]) << 16) | (_toSrgb(lin[1]) << 8) | _toSrgb(lin[2]);

/// OKLab → sRGB, pulling chroma in until the colour fits the gamut.
int oklabToArgb(List<double> lab) {
  var a = lab[1];
  var b = lab[2];
  for (var i = 0; i < 24; i++) {
    final lin = _oklabToLinear([lab[0], a, b]);
    if (lin.every((v) => v >= -0.0005 && v <= 1.0005)) return _pack(lin);
    a *= 0.9;
    b *= 0.9;
  }
  return _pack(_oklabToLinear([lab[0], 0, 0]));
}

// ---------------------------------------------------------------------------
// The flight (AlbumSpectrumBar.kt drawSpectrum), read at paint time.
// ---------------------------------------------------------------------------

/// How many bands sit in the bar at [progress] (each lands as its flight ends).
int albumSpectrumLanded(AlbumSpectrum spectrum, double progress) {
  final p = progress.clamp(0.0, 1.0);
  final n = spectrum.columns;
  if (n == 0 || p <= 0.001) return 0;
  var landed = 0;
  for (var x = 0; x < n; x++) {
    if (p >= 0.6 + 0.22 * spectrum.target[x] / n) landed++;
  }
  return landed;
}

double _smoothStep(double e0, double e1, double x) {
  final t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

double _lerp(double a, double b, double t) => a + (b - a) * t;

/// Paints the cover's strips flying from [cover] into [bar] at [progress]
/// (0 = cover, 1 = bar): each strip lags by its slot, flattens from the cover's
/// pixels into its band colour, and lands as one band of the bar. [previous]
/// with [swap] (0 → 1) hands the colours over when the cover's own spectrum
/// replaces the stand-in. Reduced motion: the bands fade in where they end.
void paintAlbumSpectrum(
  Canvas canvas, {
  required AlbumSpectrumSource source,
  required Rect cover,
  required Rect bar,
  required double progress,
  AlbumSpectrum? previous,
  double swap = 1,
  bool reduced = false,
  bool rtl = false,
}) {
  if (progress <= 0.001) return;
  final spectrum = source.spectrum;
  final n = spectrum.columns;
  final slotWidth = bar.width / n;
  Color slotColor(int x) {
    final now = spectrum.color[x];
    if (previous == null || swap >= 1) return now;
    return Color.lerp(previous.colorAtSlot(spectrum.target[x]), now, swap)!;
  }

  double slotLeft(int x) => bar.left + (rtl ? n - 1 - spectrum.target[x] : spectrum.target[x]) * slotWidth;
  final paint = Paint();
  if (reduced) {
    for (var x = 0; x < n; x++) {
      paint.color = slotColor(x).withValues(alpha: progress.clamp(0.0, 1.0));
      canvas.drawRect(Rect.fromLTWH(slotLeft(x), bar.top, slotWidth + 1.5, bar.height), paint);
    }
    return;
  }
  final image = source.image;
  final imagePaint = Paint()..filterQuality = FilterQuality.medium;
  for (var x = 0; x < n; x++) {
    final lag = 0.22 * spectrum.target[x] / n;
    final q = _smoothStep(0.06 + lag, 0.6 + lag, progress);
    final w = _lerp(cover.width / n, slotWidth, q) + 1.5;
    final left = _lerp(cover.left + x * cover.width / n, slotLeft(x), q);
    final top = _lerp(cover.top, bar.top, q);
    final h = _lerp(cover.height, bar.height, q);
    final flat = _smoothStep(0, 0.55, q);
    final dst = Rect.fromLTWH(left, top, w, h);
    if (image != null && flat < 0.999) {
      final srcLeft = x * image.width / n;
      final srcRight = (x + 1) * image.width / n;
      imagePaint.color = Color.fromRGBO(0, 0, 0, 1 - flat);
      canvas.drawImageRect(
        image,
        Rect.fromLTRB(srcLeft, 0, math.max(srcLeft + 1, srcRight), image.height.toDouble()),
        dst,
        imagePaint,
      );
    }
    if (flat > 0) {
      paint.color = slotColor(x).withValues(alpha: flat);
      canvas.drawRect(dst, paint);
    }
  }
}
