import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

import '../data/about/song_about_store.dart';
import '../data/ai/ai_service.dart' show AboutFields;
import '../data/model/model.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/audio_format_tags.dart' show codecLabel;

// The spine (书脊, NowPlayingSpine.kt, owner 2026-11-03): the band between a
// height-capped cover and the rating bar on short windows. It reads the song
// like a record's spine, one fact at a time, turned a quarter round: the
// About credits (the lyrics' own first, then the AI's cached rows — never an
// AI request), and on Subsonic the server's own facts. It
// rotates on its own, very faint; a swipe across it steps, damped; a fact
// longer than the band pans along it before the next. Never cut: the band's
// ends fade.

typedef _T = NowPlayingEffectTokens;

/// One fact on the spine: a small label and its value. A blank label is the fallback line.
@immutable
final class SpineItem {
  const SpineItem(this.label, this.value);
  final String label;
  final String value;

  @override
  bool operator ==(Object other) => other is SpineItem && other.label == label && other.value == value;

  @override
  int get hashCode => Object.hash(label, value);

  @override
  String toString() => 'SpineItem($label, $value)';
}

/// What a Subsonic server says about the song itself, for the spine. Null fields are absent.
@immutable
final class SpineSourceFacts {
  const SpineSourceFacts({
    this.format,
    this.bitRateKbps,
    this.samplingRateHz,
    this.bitDepth,
    this.serverRating,
    this.genre,
    this.year,
    this.trackNumber,
    this.discNumber,
  });

  final String? format;
  final int? bitRateKbps;
  final int? samplingRateHz;
  final int? bitDepth;

  /// The server's own 1–5 rating, not Yoin's.
  final int? serverRating;
  final String? genre;
  final int? year;
  final int? trackNumber;
  final int? discNumber;

  /// The spine's Subsonic facts for [track]; null for every other provider.
  static SpineSourceFacts? of(Track track) {
    if (track.id.provider != MediaId.providerSubsonic) return null;
    final f = track.audioFormat;
    final codec = f?.codec;
    final rating = track.userRating;
    final genre = track.genre;
    return SpineSourceFacts(
      format: codec == null ? null : codecLabel(codec),
      bitRateKbps: f?.bitRateKbps,
      samplingRateHz: f?.sampleRateHz,
      bitDepth: f?.bitDepth,
      serverRating: rating != null && rating >= 1 && rating <= 5 ? rating : null,
      genre: genre != null && genre.trim().isNotEmpty ? genre : null,
      year: (track.year ?? 0) > 0 ? track.year : null,
      trackNumber: (track.trackNumber ?? 0) > 0 ? track.trackNumber : null,
      discNumber: track.discNumber,
    );
  }
}

/// The About credits the spine reads, in the About tab's order (the review is too long for it).
const List<String> spineCreditKeys = [
  AboutFields.creationTime,
  AboutFields.creationLocation,
  AboutFields.lyricist,
  AboutFields.composer,
  AboutFields.producer,
];

/// The lyric credit role that answers an About credit before the AI does; null for the rest.
LyricCreditRole? _lyricCreditRole(String key) => switch (key) {
  AboutFields.lyricist => LyricCreditRole.lyricist,
  AboutFields.composer => LyricCreditRole.composer,
  AboutFields.producer => LyricCreditRole.producer,
  _ => null,
};

/// The labels the spine needs (Android res/values{,-zh-rCN}/strings_nowplaying.xml).
class SpineLabels {
  const SpineLabels({required this.zh});

  factory SpineLabels.of(BuildContext context) =>
      SpineLabels(zh: Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  final bool zh;

  /// An About credit's label (np_about_creation_time … np_about_producer).
  String about(String key) => switch (key) {
    AboutFields.creationTime => zh ? '创作时间' : 'Creation Time',
    AboutFields.creationLocation => zh ? '创作地点' : 'Creation Location',
    AboutFields.lyricist => zh ? '作词' : 'Lyricist',
    AboutFields.composer => zh ? '作曲' : 'Composer',
    AboutFields.producer => zh ? '制作人' : 'Producer',
    _ => key,
  };
  String get quality => zh ? '音质' : 'Quality'; // np_spine_quality
  String get serverRating => zh ? '服务器评分' : 'Server rating'; // np_spine_server_rating
  String get genre => zh ? '流派' : 'Genre'; // np_spine_genre
  String get year => zh ? '年份' : 'Year'; // np_spine_year
  String get track => zh ? '曲目' : 'Track'; // np_spine_track
  String discTrack(int disc, int track) =>
      zh ? '第 $disc 碟 第 $track 首' : 'Disc $disc, track $track'; // np_spine_disc_track
  String kbps(int value) => '$value kbps'; // np_spine_kbps
  String khz(String value) => '$value kHz'; // np_spine_khz
  String bit(int value) => '$value-bit'; // np_spine_bit
}

/// Everything the spine shows, in order (spineItems): the About credits —
/// the lyrics' [credits] answer lyricist / composer / producer before the
/// cached AI [entries] do (never an AI request) — then the Subsonic [facts];
/// when there is neither, one line of album, artist and title.
List<SpineItem> spineItems({
  List<SongAboutEntry> entries = const [],
  LyricCredits? credits,
  required SpineSourceFacts? facts,
  required String albumName,
  required String artist,
  required String title,
  required SpineLabels labels,
}) {
  final items = <SpineItem>[];
  for (final key in spineCreditKeys) {
    final role = _lyricCreditRole(key);
    final names =
        (role == null ? null : credits?.namesFor(role)) ??
        entries.where((e) => e.isCanonical && e.entryKey == key).firstOrNull?.answerText;
    final value = names?.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (value != null && value.isNotEmpty) items.add(SpineItem(labels.about(key), value));
  }
  if (facts != null) {
    final quality = [
      ?facts.format,
      if (facts.bitDepth != null) labels.bit(facts.bitDepth!),
      if (facts.samplingRateHz != null) labels.khz(formatKhz(facts.samplingRateHz!)),
      if (facts.bitRateKbps != null) labels.kbps(facts.bitRateKbps!),
    ];
    if (quality.isNotEmpty) items.add(SpineItem(labels.quality, quality.join('  ')));
    final stars = facts.serverRating;
    if (stars != null) items.add(SpineItem(labels.serverRating, '★' * stars + '☆' * (5 - stars)));
    if (facts.genre != null) items.add(SpineItem(labels.genre, facts.genre!));
    if (facts.year != null) items.add(SpineItem(labels.year, '${facts.year}'));
    final track = facts.trackNumber;
    if (track != null) {
      final disc = facts.discNumber;
      items.add(SpineItem(labels.track, disc != null && disc > 1 ? labels.discTrack(disc, track) : '$track'));
    }
  }
  if (items.isEmpty) {
    final line = [albumName, artist, title].where((s) => s.trim().isNotEmpty).join('   ');
    if (line.isNotEmpty) items.add(SpineItem('', line));
  }
  return items;
}

/// 44100 → "44.1", 96000 → "96".
String formatKhz(int hz) {
  final tenths = (hz / 100).round();
  return tenths % 10 == 0 ? '${tenths ~/ 10}' : '${tenths ~/ 10}.${tenths % 10}';
}

/// The next fact index after a swipe of [travel] px across the band, or
/// [index] when it was too short (past [NowPlayingEffectTokens.spineSwipeStep]
/// it steps; toward the start = the next fact).
int spineIndexAfterSwipe(int index, double travel, int count) {
  if (count < 2 || travel.abs() < _T.spineSwipeStep) return index;
  return index + (travel < 0 ? 1 : -1);
}

/// The fact's displacement under the finger: damped, never past the reach.
double spineDragOffset(double travel) => (travel * _T.spineSwipeDamping).clamp(-_T.spineSwipeReach, _T.spineSwipeReach);

/// The spine itself: [items] one at a time, laid out [length] long (the
/// cover's side) and turned a quarter round so they read bottom to top.
/// [songKey] restarts it at the first fact on a new song.
class NowPlayingSpine extends StatefulWidget {
  const NowPlayingSpine({super.key, required this.items, required this.songKey, required this.length});

  final List<SpineItem> items;
  final String songKey;
  final double length;

  @override
  State<NowPlayingSpine> createState() => _NowPlayingSpineState();
}

class _NowPlayingSpineState extends State<NowPlayingSpine> with TickerProviderStateMixin {
  int _index = 0;
  bool _dragging = false;
  double _travel = 0;
  int _panMs = 0;
  Timer? _dwell;
  late final SpringValue _drag = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  /// The landing (Android's oneLineLyricLanding): the new fact scales up from
  /// 0.94 at its leading edge and rises a sixth of its height while it fades
  /// in; the old one shrinks and fades out fast.
  late final SpringValue _in = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultSpatial);
  late final SpringValue _inAlpha = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  late final SpringValue _out = SpringValue(vsync: this, initial: 1, spring: YoinSprings.standardDefaultSpatial);
  late final SpringValue _outAlpha = SpringValue(vsync: this, initial: 1, spring: YoinSprings.fastEffects);
  SpineItem? _leaving;
  String? _leavingKey;

  int get _current => widget.items.isEmpty ? 0 : _index % widget.items.length;

  @override
  void initState() {
    super.initState();
    _schedule();
  }

  @override
  void didUpdateWidget(NowPlayingSpine oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.songKey != widget.songKey) {
      _land(() {
        _index = 0;
        _panMs = 0;
      }, from: oldWidget.items.isEmpty ? null : oldWidget.items[_index % oldWidget.items.length]);
    } else if (!listEquals(oldWidget.items, widget.items)) {
      _schedule();
    }
  }

  @override
  void dispose() {
    _dwell?.cancel();
    for (final s in [_drag, _in, _inAlpha, _out, _outAlpha]) {
      s.dispose();
    }
    super.dispose();
  }

  bool get _reduced => MediaQuery.maybeDisableAnimationsOf(context) ?? false;

  /// Rest, pan if it is long (the line reports its own pan time), rest, next.
  void _schedule() {
    _dwell?.cancel();
    if (_dragging || widget.items.length < 2) return;
    _dwell = Timer(Duration(milliseconds: _T.spineDwellMs + _panMs), () {
      if (!mounted) return;
      _step(1);
    });
  }

  void _step(int by) => _land(() {
    _index += by;
    _panMs = 0;
  });

  void _land(VoidCallback change, {SpineItem? from}) {
    final leaving = from ?? (widget.items.isEmpty ? null : widget.items[_current]);
    final leavingKey = '${widget.songKey}/$_index';
    setState(() {
      change();
      _leaving = leaving;
      _leavingKey = leavingKey;
    });
    if (_reduced) {
      for (final s in [_in, _inAlpha]) {
        s.snapTo(1);
      }
      _out.snapTo(1);
      _outAlpha.snapTo(1);
      _leaving = null;
    } else {
      _in.snapTo(0);
      _inAlpha.snapTo(0);
      _out.snapTo(0);
      _outAlpha.snapTo(0);
      _in.animateTo(1);
      _inAlpha.animateTo(1);
      _out.animateTo(1);
      _outAlpha.animateTo(1);
    }
    _schedule();
  }

  void _onPanMs(int ms) {
    if (ms == _panMs) return;
    _panMs = ms;
    _schedule();
  }

  @override
  Widget build(BuildContext context) {
    final items = widget.items;
    if (items.isEmpty) return const SizedBox.shrink();
    final current = _current;
    final item = items[current];
    final reduced = _reduced;
    final key = '${widget.songKey}/$_index';
    return RawGestureDetector(
      behavior: HitTestBehavior.opaque,
      gestures: {
        HorizontalDragGestureRecognizer: GestureRecognizerFactoryWithHandlers<HorizontalDragGestureRecognizer>(
          HorizontalDragGestureRecognizer.new,
          (r) => r
            ..onStart = (_) {
              _travel = 0;
              _dragging = true;
              _dwell?.cancel();
            }
            ..onUpdate = (d) {
              _travel += d.delta.dx;
              _drag.snapTo(spineDragOffset(_travel));
            }
            ..onEnd = (_) {
              _dragging = false;
              _drag.animateTo(0);
              final next = spineIndexAfterSwipe(_index, _travel, widget.items.length);
              if (next != _index) {
                _step(next - _index);
              } else {
                _schedule();
              }
            }
            ..onCancel = () {
              _dragging = false;
              _drag.animateTo(0);
              _schedule();
            },
        ),
      },
      child: ClipRect(
        child: AnimatedBuilder(
          animation: Listenable.merge([
            _drag.animation,
            _in.animation,
            _inAlpha.animation,
            _out.animation,
            _outAlpha.animation,
          ]),
          builder: (context, _) {
            Widget frame(SpineItem shown, String frameKey, {required bool entering}) {
              final scale = entering ? 0.94 + 0.06 * _in.value : 1 - 0.06 * _out.value;
              final alpha = entering ? _inAlpha.value : 1 - _outAlpha.value;
              final rise = entering ? (1 - _in.value) / 6 : 0.0;
              return Opacity(
                opacity: alpha.clamp(0.0, 1.0),
                child: FractionalTranslation(
                  translation: Offset(0, rise),
                  child: Transform.scale(
                    scale: scale,
                    alignment: Alignment.centerLeft,
                    child: _SpineLine(
                      key: ValueKey(frameKey),
                      item: shown,
                      length: widget.length,
                      pan: !reduced,
                      onPanMs: entering ? _onPanMs : null,
                    ),
                  ),
                ),
              );
            }

            final leaving = _leaving;
            final showLeaving = leaving != null && _outAlpha.value < 1;
            return Transform.translate(
              // Across the band, in screen space: the finger's axis.
              offset: Offset(_drag.value, 0),
              child: OverflowBox(
                minWidth: widget.length,
                maxWidth: widget.length,
                child: RotatedBox(
                  quarterTurns: 3,
                  child: _FadeEnds(
                    child: Stack(
                      alignment: Alignment.center,
                      children: [
                        if (showLeaving) frame(leaving, '$_leavingKey/out', entering: false),
                        frame(item, key, entering: true),
                      ],
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// The band's ends fade over [NowPlayingEffectTokens.spineFadeEdge] (smoothstep-eased
/// ramps, horizontalFadeMask): nothing is cut.
class _FadeEnds extends StatelessWidget {
  const _FadeEnds({required this.child});

  final Widget child;

  static const _eased = [0.0, 0.104, 0.352, 0.648, 0.896, 1.0];

  @override
  Widget build(BuildContext context) => ShaderMask(
    blendMode: BlendMode.dstIn,
    shaderCallback: (rect) {
      final edge = math.min(_T.spineFadeEdge, rect.width / 2);
      final f = rect.width <= 0 ? 0.0 : edge / rect.width;
      final stops = <double>[for (var i = 0; i < 6; i++) f * i / 5, for (var i = 5; i >= 0; i--) 1 - f * i / 5];
      final colors = <Color>[
        for (final a in _eased) Color.fromRGBO(0, 0, 0, a),
        for (final a in _eased.reversed) Color.fromRGBO(0, 0, 0, a),
      ];
      return LinearGradient(colors: colors, stops: stops).createShader(rect);
    },
    child: child,
  );
}

/// One fact: the label quiet, the value in the line's ink; pans along the
/// band when it is longer (after [NowPlayingEffectTokens.spinePanDelayMs], at
/// [NowPlayingEffectTokens.spinePanSpeed] dp/s).
class _SpineLine extends StatefulWidget {
  const _SpineLine({super.key, required this.item, required this.length, required this.pan, this.onPanMs});

  final SpineItem item;
  final double length;
  final bool pan;
  final ValueChanged<int>? onPanMs;

  @override
  State<_SpineLine> createState() => _SpineLineState();
}

class _SpineLineState extends State<_SpineLine> with SingleTickerProviderStateMixin {
  late final AnimationController _offset = AnimationController.unbounded(vsync: this);
  Timer? _delay;
  double _overflow = -1;

  @override
  void dispose() {
    _delay?.cancel();
    _offset.dispose();
    super.dispose();
  }

  void _measure(double textWidth) {
    final overflow = math.max(0.0, textWidth - (widget.length - _T.spineFadeEdge * 2));
    if (overflow == _overflow) return;
    _overflow = overflow;
    _delay?.cancel();
    _offset.value = 0;
    if (!widget.pan || overflow <= 0) {
      widget.onPanMs?.call(0);
      return;
    }
    final ms = (overflow / _T.spinePanSpeed * 1000).round();
    widget.onPanMs?.call(_T.spinePanDelayMs + ms);
    _delay = Timer(const Duration(milliseconds: _T.spinePanDelayMs), () {
      if (!mounted) return;
      _offset.animateTo(
        -overflow,
        duration: Duration(milliseconds: ms),
        curve: Curves.linear,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelLarge!;
    final ink = colors.onSurfaceVariant;
    final span = TextSpan(
      children: [
        if (widget.item.label.isNotEmpty) ...[
          TextSpan(
            text: widget.item.label,
            style: TextStyle(color: ink.withValues(alpha: 0.6), fontWeight: FontWeight.w400),
          ),
          const TextSpan(text: '  '),
        ],
        TextSpan(text: widget.item.value),
      ],
      style: style.copyWith(color: ink),
    );
    final painter = TextPainter(
      text: span,
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
      maxLines: 1,
    )..layout();
    final textWidth = painter.width;
    painter.dispose();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _measure(textWidth);
    });
    return SizedBox(
      width: widget.length,
      child: AnimatedBuilder(
        animation: _offset,
        builder: (context, child) =>
            Transform.translate(offset: Offset(_T.spineFadeEdge + _offset.value, 0), child: child),
        child: OverflowBox(
          alignment: AlignmentDirectional.centerStart,
          minWidth: 0,
          maxWidth: double.infinity,
          child: Text.rich(span, maxLines: 1, softWrap: false),
        ),
      ),
    );
  }
}
