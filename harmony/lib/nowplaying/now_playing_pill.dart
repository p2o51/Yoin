import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/desktop_input.dart';
import '../playback/playback_strings.dart';
import '../shell/edge_split_strings.dart';
import 'cover_swap.dart';
import 'flight.dart';
import 'haptics.dart';
import 'marquee.dart';
import 'now_playing_view_model.dart';
import 'nowplaying_strings.dart';
import 'overlay_controller.dart';
import 'playhead_clock.dart';
import 'player_snapshot.dart';

/// Pill geometry (NowPlayingPill.kt L587-659; PillTokens not in tokens.dart yet).
abstract final class PillMetrics {
  static const double cover = 34;
  static const double rowPadding = 10;
  static const double gap = 10;
  static const double pushTravel = 30;
  static const Duration pushOut = Duration(milliseconds: 90);
  static const double waveAmplitude = 4;
  static const double washAlpha = 0.25;
  static const double artistAlpha = 0.72;
  static const int waveSegments = 20;
}

/// The now-playing pill in the bar (NowPlayingPill.kt): 34 dp cover, marquee
/// title over marquee artist, and a primary@25 % wash filling to the playback
/// fraction with a travelling sine edge. A track change pushes the old row
/// out to the left (90 ms) and the new one in from the right (Expressive
/// default spatial); the wash stays put. Tap opens Now Playing.
class NowPlayingPill extends ConsumerStatefulWidget {
  const NowPlayingPill({super.key, this.onPressChanged, this.onLongPress});

  /// Press echo for the bar (the pill squeezes its neighbours).
  final ValueChanged<bool>? onPressChanged;

  /// Long-press on a song: the song sheet for it (showTrackMenuSheet, nowPlaying: true).
  final ValueChanged<Track>? onLongPress;

  @override
  ConsumerState<NowPlayingPill> createState() => _NowPlayingPillState();
}

class _NowPlayingPillState extends ConsumerState<NowPlayingPill> with TickerProviderStateMixin {
  /// 0 = no track, 1 = a track (container / content colours).
  late final SpringValue _presence;

  /// Wave amplitude: 0 paused, 1 playing (Standard default spatial).
  late final SpringValue _amplitude;

  /// Track-change push: −1 (entering from the right) … 0 … 1 (left out).
  late final AnimationController _push = AnimationController.unbounded(vsync: this, value: 0);

  Track? _shown;
  String? _pendingKey;

  @override
  void initState() {
    super.initState();
    // Created eagerly: a lazy initializer that reads `ref` would first run
    // in dispose() (when the value never changed) and throw there.
    final snapshot = ref.read(playerSnapshotProvider);
    _presence = SpringValue(vsync: this, initial: snapshot.hasTrack ? 1 : 0, spring: YoinSprings.defaultEffects);
    _amplitude = SpringValue(
      vsync: this,
      initial: snapshot.isPlaying ? 1 : 0,
      spring: YoinSprings.standardDefaultSpatial,
    );
    _shown = snapshot.track;
  }

  @override
  void dispose() {
    _presence.dispose();
    _amplitude.dispose();
    _push.dispose();
    super.dispose();
  }

  Future<void> _onTrack(Track? next) async {
    final shown = _shown;
    // Idle ⇄ first track, or the same song refreshed: swap silently.
    if (shown == null || next == null || shown.id == next.id) {
      setState(() => _shown = next);
      return;
    }
    final key = next.id.toString();
    _pendingKey = key;
    if (_push.isAnimating) _push.value = 0;
    if (MediaQuery.disableAnimationsOf(context)) {
      setState(() => _shown = next);
      return;
    }
    await _push.animateTo(1, duration: PillMetrics.pushOut, curve: Curves.linear);
    if (!mounted || _pendingKey != key) return;
    setState(() => _shown = ref.read(playerSnapshotProvider).track ?? next);
    _push.value = -1;
    _push.animateWith(SpringSimulation(YoinSprings.defaultSpatial, -1, 0, 0));
  }

  void _open() {
    if (!ref.read(playerSnapshotProvider).hasTrack) return;
    YoinHaptics.contextClick();
    NowPlayingOverlayScope.read(context)?.openPlayer();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(playerSnapshotProvider.select((s) => s.track), (_, next) => _onTrack(next));
    ref.listen(playerSnapshotProvider.select((s) => s.hasTrack), (_, has) => _presence.animateTo(has ? 1 : 0));
    ref.listen(
      playerSnapshotProvider.select((s) => s.isPlaying),
      (_, playing) => _amplitude.animateTo(playing ? 1 : 0),
    );
    final unavailable = ref.watch(playerSnapshotProvider.select(isPlaybackUnavailable));
    final errorKind = ref.watch(playerSnapshotProvider.select((s) => s.errorKind));
    final clock = ref.watch(playheadClockProvider);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    final shown = _shown;

    final title = shown?.title ?? (unavailable ? strings.pillPlaybackUnavailable : strings.pillNothingPlaying);
    final artist =
        shown?.artist ??
        (shown == null && errorKind != null ? PlaybackStrings.of(context).errorMessage(errorKind) : '');
    final dpr = MediaQuery.devicePixelRatioOf(context);

    final VoidCallback? long = switch ((widget.onLongPress, shown)) {
      (final long?, final track?) => () => long(track),
      _ => null,
    };
    return Semantics(
      button: true,
      label: shown?.title == null ? strings.pillTapOpen : '${shown!.title}, ${strings.pillTapOpen}',
      excludeSemantics: true,
      // Mouse / keyboard: hover layer, click cursor, focus ring, Enter opens,
      // right click = the long-press song sheet.
      child: YoinHover(
        onTap: _open,
        onSecondaryTap: long,
        focusStadium: true,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapDown: (_) => widget.onPressChanged?.call(true),
          onTapUp: (_) => widget.onPressChanged?.call(false),
          onTapCancel: () => widget.onPressChanged?.call(false),
          onTap: _open,
          onLongPress: long,
          child: AnimatedBuilder(
            animation: _presence.animation,
            builder: (context, child) {
              final p = _presence.value.clamp(0.0, 1.0);
              return DecoratedBox(
                decoration: ShapeDecoration(
                  color: Color.lerp(colors.surfaceContainerHighest, colors.primaryContainer, p),
                  shape: const StadiumBorder(),
                ),
                child: IconTheme.merge(
                  data: IconThemeData(color: Color.lerp(colors.onSurfaceVariant, colors.onPrimaryContainer, p)),
                  child: child!,
                ),
              );
            },
            child: ClipPath(
              clipper: const ShapeBorderClipper(shape: StadiumBorder()),
              child: SizedBox(
                height: BarTokens.buttonHeight,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    if (shown != null)
                      RepaintBoundary(
                        child: CustomPaint(
                          painter: _WashPainter(
                            clock: clock,
                            amplitude: _amplitude,
                            color: colors.primary.withValues(alpha: PillMetrics.washAlpha),
                          ),
                        ),
                      ),
                    AnimatedBuilder(
                      animation: _push,
                      builder: (context, child) {
                        final p = _push.value;
                        return Opacity(
                          opacity: (1 - 0.9 * p.abs()).clamp(0.0, 1.0),
                          child: Transform.translate(offset: Offset(-PillMetrics.pushTravel * p, 0), child: child),
                        );
                      },
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: PillMetrics.rowPadding),
                        child: Row(
                          children: [
                            FlightSlot(
                              element: FlightElement.cover,
                              pill: true,
                              child: _PillCover(
                                cover: shown?.coverArt,
                                requestSize: (pillCoverRequestDp * dpr).round(),
                                label: shown?.title ?? strings.pillCurrentTrack,
                              ),
                            ),
                            const SizedBox(width: PillMetrics.gap),
                            Expanded(
                              child: AnimatedBuilder(
                                animation: _presence.animation,
                                builder: (context, _) {
                                  final content = Color.lerp(
                                    colors.onSurfaceVariant,
                                    colors.onPrimaryContainer,
                                    _presence.value.clamp(0.0, 1.0),
                                  )!;
                                  return Column(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      FlightSlot(
                                        element: FlightElement.title,
                                        pill: true,
                                        child: Marquee(
                                          key: ValueKey('pill-title-${shown?.id}'),
                                          text: title,
                                          enabled: shown?.title != null,
                                          style: text.labelLarge!.copyWith(color: content),
                                        ),
                                      ),
                                      if (artist.isNotEmpty)
                                        FlightSlot(
                                          element: FlightElement.artist,
                                          pill: true,
                                          child: Marquee(
                                            key: ValueKey('pill-artist-${shown?.id}'),
                                            text: artist,
                                            enabled: shown != null,
                                            initialDelay: const Duration(milliseconds: 2500),
                                            style: text.labelSmall!.copyWith(
                                              color: content.withValues(alpha: PillMetrics.artistAlpha),
                                            ),
                                          ),
                                        ),
                                    ],
                                  );
                                },
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The upright now-playing pill for the edge-split capsule
/// (NowPlayingPill.kt NowPlayingPillVertical, ShellChromeForm.EdgeSplit): 52
/// wide, the cover at the bottom, title over artist turned to read bottom to
/// top above it, and the primary@25 % wash rising from the bottom to the
/// playback fraction under a travelling sine edge. A track change pushes the
/// old song out upward (90 ms) and the new one in from below (Expressive
/// default spatial). Tap opens Now Playing; long-press on a song opens its
/// sheet. Only the cover flies to the player (the text is sideways).
class NowPlayingPillVertical extends ConsumerStatefulWidget {
  const NowPlayingPillVertical({super.key, this.onLongPress, this.enabled = true});

  /// Long-press on a song: the song sheet for it.
  final ValueChanged<Track>? onLongPress;

  /// False while it steps out on the way into Home's edit pose: no press, tap or haptic.
  final bool enabled;

  @override
  ConsumerState<NowPlayingPillVertical> createState() => _NowPlayingPillVerticalState();
}

class _NowPlayingPillVerticalState extends ConsumerState<NowPlayingPillVertical> with TickerProviderStateMixin {
  late final SpringValue _presence;
  late final SpringValue _amplitude;

  /// Track-change push: −1 (entering from below) … 0 … 1 (out at the top).
  late final AnimationController _push = AnimationController.unbounded(vsync: this, value: 0);

  Track? _shown;
  String? _pendingKey;

  @override
  void initState() {
    super.initState();
    final snapshot = ref.read(playerSnapshotProvider);
    _presence = SpringValue(vsync: this, initial: snapshot.hasTrack ? 1 : 0, spring: YoinSprings.defaultEffects);
    _amplitude = SpringValue(
      vsync: this,
      initial: snapshot.isPlaying ? 1 : 0,
      spring: YoinSprings.standardDefaultSpatial,
    );
    _shown = snapshot.track;
  }

  @override
  void dispose() {
    _presence.dispose();
    _amplitude.dispose();
    _push.dispose();
    super.dispose();
  }

  Future<void> _onTrack(Track? next) async {
    final shown = _shown;
    if (shown == null || next == null || shown.id == next.id) {
      setState(() => _shown = next);
      return;
    }
    final key = next.id.toString();
    _pendingKey = key;
    if (_push.isAnimating) _push.value = 0;
    if (MediaQuery.disableAnimationsOf(context)) {
      setState(() => _shown = next);
      return;
    }
    await _push.animateTo(1, duration: PillMetrics.pushOut, curve: Curves.linear);
    if (!mounted || _pendingKey != key) return;
    setState(() => _shown = ref.read(playerSnapshotProvider).track ?? next);
    _push.value = -1;
    _push.animateWith(SpringSimulation(YoinSprings.defaultSpatial, -1, 0, 0));
  }

  void _open() {
    if (!ref.read(playerSnapshotProvider).hasTrack) return;
    YoinHaptics.contextClick();
    NowPlayingOverlayScope.read(context)?.openPlayer();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(playerSnapshotProvider.select((s) => s.track), (_, next) => _onTrack(next));
    ref.listen(playerSnapshotProvider.select((s) => s.hasTrack), (_, has) => _presence.animateTo(has ? 1 : 0));
    ref.listen(
      playerSnapshotProvider.select((s) => s.isPlaying),
      (_, playing) => _amplitude.animateTo(playing ? 1 : 0),
    );
    final unavailable = ref.watch(playerSnapshotProvider.select(isPlaybackUnavailable));
    final errorKind = ref.watch(playerSnapshotProvider.select((s) => s.errorKind));
    final clock = ref.watch(playheadClockProvider);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    final shown = _shown;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final edgeStrings = EdgeSplitStrings.of(context);
    final title = shown?.title ?? (unavailable ? edgeStrings.pillPlaybackUnavailable : edgeStrings.pillNothingPlaying);
    final artist =
        shown?.artist ??
        (shown == null && errorKind != null ? PlaybackStrings.of(context).errorMessage(errorKind) : '');
    final VoidCallback? long = switch ((widget.enabled ? widget.onLongPress : null, shown)) {
      (final long?, final track?) => () => long(track),
      _ => null,
    };

    Widget pushed(Widget child) => AnimatedBuilder(
      animation: _push,
      builder: (context, child) {
        final p = _push.value;
        return Opacity(
          opacity: (1 - 0.9 * p.abs()).clamp(0.0, 1.0),
          child: Transform.translate(offset: Offset(0, -PillMetrics.pushTravel * p), child: child),
        );
      },
      child: child,
    );

    return Semantics(
      button: true,
      enabled: widget.enabled,
      label: shown?.title == null ? strings.pillTapOpen : '${shown!.title}, ${strings.pillTapOpen}',
      excludeSemantics: true,
      onLongPressHint: long == null ? null : edgeStrings.pillSongOptions,
      child: IgnorePointer(
        ignoring: !widget.enabled,
        child: YoinHover(
          onTap: _open,
          onSecondaryTap: long,
          focusRadius: BorderRadius.circular(EdgeSplitTokens.pillCorner),
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: _open,
            onLongPress: long,
            child: AnimatedBuilder(
              animation: _presence.animation,
              builder: (context, child) {
                final p = _presence.value.clamp(0.0, 1.0);
                return DecoratedBox(
                  decoration: ShapeDecoration(
                    color: Color.lerp(colors.surfaceContainerHighest, colors.primaryContainer, p),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(EdgeSplitTokens.pillCorner)),
                  ),
                  child: child,
                );
              },
              child: Stack(
                fit: StackFit.expand,
                children: [
                  if (shown != null)
                    RepaintBoundary(
                      child: CustomPaint(
                        painter: _WashPainter(
                          clock: clock,
                          amplitude: _amplitude,
                          color: colors.primary.withValues(alpha: PillMetrics.washAlpha),
                          upright: true,
                        ),
                      ),
                    ),
                  // Rectangular: the push may lift the row past the round ends for a frame or two.
                  ClipRect(
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        Padding(
                          padding: const EdgeInsets.only(
                            top: EdgeSplitTokens.pillTextTop,
                            bottom: EdgeSplitTokens.pillTextBottom,
                          ),
                          child: pushed(
                            Align(
                              alignment: Alignment.bottomCenter,
                              child: AnimatedBuilder(
                                animation: _presence.animation,
                                builder: (context, _) {
                                  final content = Color.lerp(
                                    colors.onSurfaceVariant,
                                    colors.onPrimaryContainer,
                                    _presence.value.clamp(0.0, 1.0),
                                  )!;
                                  // Reads bottom to top: the line's start sits on the cover.
                                  return RotatedBox(
                                    quarterTurns: 3,
                                    child: Column(
                                      mainAxisSize: MainAxisSize.min,
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      children: [
                                        Text(
                                          title,
                                          maxLines: 1,
                                          softWrap: false,
                                          overflow: TextOverflow.ellipsis,
                                          style: text.labelLarge!.copyWith(
                                            fontSize: EdgeSplitTokens.pillTitleSize,
                                            height: EdgeSplitTokens.pillTitleLine / EdgeSplitTokens.pillTitleSize,
                                            fontWeight: FontWeight.w600,
                                            fontVariations: const [FontVariation.weight(600)],
                                            color: content,
                                          ),
                                        ),
                                        Text(
                                          artist,
                                          maxLines: 1,
                                          softWrap: false,
                                          overflow: TextOverflow.ellipsis,
                                          style: text.labelSmall!.copyWith(
                                            fontSize: EdgeSplitTokens.pillArtistSize,
                                            height: EdgeSplitTokens.pillArtistLine / EdgeSplitTokens.pillArtistSize,
                                            color: content.withValues(alpha: EdgeSplitTokens.pillArtistAlpha),
                                          ),
                                        ),
                                      ],
                                    ),
                                  );
                                },
                              ),
                            ),
                          ),
                        ),
                        Positioned(
                          left: 0,
                          right: 0,
                          bottom: EdgeSplitTokens.pillCoverBottom,
                          child: Center(
                            child: pushed(
                              FlightSlot(
                                element: FlightElement.cover,
                                pill: true,
                                child: _PillCover(
                                  cover: shown?.coverArt,
                                  requestSize: (pillCoverRequestDp * dpr).round(),
                                  label: shown?.title ?? strings.pillCurrentTrack,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PillCover extends StatelessWidget {
  const _PillCover({required this.cover, required this.requestSize, required this.label});

  final CoverRef? cover;
  final int requestSize;
  final String label;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      label: label,
      image: true,
      child: SizedBox.square(
        dimension: PillMetrics.cover,
        child: cover == null
            ? DecoratedBox(
                decoration: BoxDecoration(
                  color: colors.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(ShapeTokens.thumb),
                ),
                child: Icon(YoinSymbols.musicNote, size: 18, color: colors.onSurfaceVariant),
              )
            : CoverSwap(
                cover: cover!,
                size: PillMetrics.cover,
                radius: ShapeTokens.thumb,
                requestSize: requestSize,
                spring: YoinSprings.defaultEffects,
                fallback: ColoredBox(
                  color: colors.surfaceContainerHighest,
                  child: Icon(YoinSymbols.musicNote, size: 18, color: colors.onSurfaceVariant),
                ),
              ),
      ),
    );
  }
}

/// The progress wash (NowPlayingPill.kt drawWithContent): from the top-left
/// corner to the front on the top edge, then down a 20-segment sine edge
/// (x = front + sin(phase + y/h·2π)·4 dp·amplitude) and back along the
/// bottom; primary @ 25 %, clipped by the stadium, phase from the app-wide
/// wave clock.
class _WashPainter extends CustomPainter {
  _WashPainter({required this.clock, required this.amplitude, required this.color, this.upright = false})
    : super(repaint: Listenable.merge([clock, amplitude.animation]));

  final PlayheadClock clock;
  final SpringValue amplitude;
  final Color color;

  /// The upright pill: the wash rises from the bottom under a 12-step sine
  /// edge (x = f·w, y = h·(1 − front) + sin(phase + f·2π)·4 dp·amplitude),
  /// kept inside the pill's round ends by geometry, not a clip.
  final bool upright;

  @override
  void paint(Canvas canvas, Size size) {
    final now = clock.nowMs;
    final front = clock.progress(now);
    if (front <= 0) return;
    if (upright) return _paintUpright(canvas, size, front, now);
    final x = size.width * front;
    final phase = clock.wavePhase(now);
    final amp = PillMetrics.waveAmplitude * amplitude.value.clamp(0.0, 1.5);
    final path = Path()
      ..moveTo(0, 0)
      ..lineTo(x, 0);
    for (var i = 0; i <= PillMetrics.waveSegments; i++) {
      final f = i / PillMetrics.waveSegments;
      path.lineTo(x + math.sin(phase + f * 2 * math.pi) * amp, f * size.height);
    }
    path
      ..lineTo(0, size.height)
      ..close();
    canvas.drawPath(path, Paint()..color = color);
  }

  void _paintUpright(Canvas canvas, Size size, double front, int now) {
    final y = size.height * (1 - front);
    final phase = clock.wavePhase(now);
    final amp = PillMetrics.waveAmplitude * amplitude.value.clamp(0.0, 1.5);
    final wave = Path()..moveTo(0, size.height);
    for (var i = 0; i <= EdgeSplitTokens.pillWaveSteps; i++) {
      final f = i / EdgeSplitTokens.pillWaveSteps;
      wave.lineTo(f * size.width, y + math.sin(phase + f * 2 * math.pi) * amp);
    }
    wave
      ..lineTo(size.width, size.height)
      ..close();
    final pill = Path()
      ..addRRect(RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(EdgeSplitTokens.pillCorner)));
    canvas.drawPath(Path.combine(PathOperation.intersect, wave, pill), Paint()..color = color);
  }

  @override
  bool shouldRepaint(_WashPainter old) =>
      old.color != color || old.clock != clock || old.amplitude != amplitude || old.upright != upright;
}
