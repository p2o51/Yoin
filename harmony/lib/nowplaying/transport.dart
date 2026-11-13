import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'haptics.dart';
import 'nowplaying_strings.dart';
import '../ui/component/expressive/expressive_shapes.dart';
import '../ui/component/expressive/expressive_surface_button.dart';
import '../playback/play_mode.dart';
import 'playhead_clock.dart';
import 'wave_progress_bar.dart';

/// The pressed key of the PLAY | NEXT group (hoisted: the hero breathes with it).
enum TransportKey { none, play, next }

/// Key-group widths (PlaybackControls.kt transportKeyWidths): the pressed key
/// gains 15 % of its own width from its neighbour; the total stays fixed.
(double, double) transportKeyWidths(double play, double next, {double playPress = 0, double nextPress = 0}) {
  final fromPlay = 0.15 * play * playPress;
  final fromNext = 0.15 * next * nextPress;
  return (play + fromPlay - fromNext, next + fromNext - fromPlay);
}

/// Both transport rows (PlaybackControls.kt): [PLAY|PAUSE][8][NEXT] …
/// [play mode] over [PREVIOUS][elapsed][wave][-remaining]; labels drop
/// under the wave when the row is narrower than 360.
class TransportControls extends StatefulWidget {
  const TransportControls({
    super.key,
    required this.isPlaying,
    required this.isWavePlaying,
    required this.playMode,
    required this.clock,
    required this.durationMs,
    required this.labelsUnder,
    required this.waveVisible,
    required this.onPlayPause,
    required this.onNext,
    required this.onPrevious,
    required this.onPlayMode,
    required this.onSeek,
    required this.pressed,
  });

  final bool isPlaying;
  final bool isWavePlaying;
  final PlayMode playMode;
  final PlayheadClock clock;
  final int? durationMs;
  final bool labelsUnder;
  final bool waveVisible;
  final VoidCallback onPlayPause;
  final VoidCallback onNext;
  final VoidCallback onPrevious;
  final VoidCallback onPlayMode;
  final ValueChanged<double> onSeek;

  /// Which key is held (the hero reads it to stretch).
  final ValueNotifier<TransportKey> pressed;

  @override
  State<TransportControls> createState() => _TransportControlsState();
}

class _TransportControlsState extends State<TransportControls> with TickerProviderStateMixin {
  late final SpringValue _playPress = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardDefaultSpatial);
  late final SpringValue _nextPress = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardDefaultSpatial);

  @override
  void dispose() {
    _playPress.dispose();
    _nextPress.dispose();
    super.dispose();
  }

  void _setPressed(TransportKey key) {
    widget.pressed.value = key;
    _playPress.animateTo(key == TransportKey.play ? 1 : 0);
    _nextPress.animateTo(key == TransportKey.next ? 1 : 0);
  }

  @override
  Widget build(BuildContext context) {
    const size = NowPlayingTokens.controlSize;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            height: size,
            child: Row(
              children: [
                AnimatedBuilder(
                  animation: Listenable.merge([_playPress.animation, _nextPress.animation]),
                  builder: (context, _) => _PlayNextGroup(
                    isPlaying: widget.isPlaying,
                    playPress: _playPress.value,
                    nextPress: _nextPress.value,
                    onPlayPause: widget.onPlayPause,
                    onNext: widget.onNext,
                    onPressed: _setPressed,
                  ),
                ),
                const Spacer(),
                PlayModeButton(mode: widget.playMode, onTap: widget.onPlayMode),
              ],
            ),
          ),
          const SizedBox(height: NowPlayingTokens.controlRowGap),
          SizedBox(
            height: size,
            child: Row(
              children: [
                _RoundKey(
                  icon: YoinSymbols.skipPreviousFilled,
                  semantics: NowPlayingStrings.of(context).cdSkipPrevious,
                  onTap: () {
                    YoinHaptics.tick();
                    widget.onPrevious();
                  },
                ),
                const SizedBox(width: NowPlayingTokens.transportGap),
                if (!widget.labelsUnder) ...[
                  _TimeLabel(clock: widget.clock, durationMs: widget.durationMs, remaining: false),
                  const SizedBox(width: NowPlayingTokens.timeLabelGap),
                ],
                Expanded(
                  child: Padding(
                    // The wave's indicator sits at the bottom of its 40 box; centre the box.
                    padding: const EdgeInsets.only(bottom: 4),
                    child: WaveProgressBar(
                      clock: widget.clock,
                      isPlaying: widget.isWavePlaying,
                      durationMs: widget.durationMs,
                      visible: widget.waveVisible,
                      onSeek: widget.onSeek,
                    ),
                  ),
                ),
                if (!widget.labelsUnder) ...[
                  const SizedBox(width: NowPlayingTokens.timeLabelGap),
                  _TimeLabel(clock: widget.clock, durationMs: widget.durationMs, remaining: true),
                ],
              ],
            ),
          ),
          if (widget.labelsUnder)
            Padding(
              padding: const EdgeInsets.only(left: size + NowPlayingTokens.transportGap),
              child: Row(
                children: [
                  _TimeLabel(clock: widget.clock, durationMs: widget.durationMs, remaining: false),
                  const Spacer(),
                  _TimeLabel(clock: widget.clock, durationMs: widget.durationMs, remaining: true),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// PLAY|PAUSE pill + NEXT. The pill's word box springs between the measured
/// widths of PLAY and PAUSE (one spring); the word stretches in X from its
/// leading edge: 1.10 held, 1.06 playing, 0.97 paused. Non-English locales
/// show the glyph in the same box.
class _PlayNextGroup extends StatefulWidget {
  const _PlayNextGroup({
    required this.isPlaying,
    required this.playPress,
    required this.nextPress,
    required this.onPlayPause,
    required this.onNext,
    required this.onPressed,
  });

  final bool isPlaying;
  final double playPress;
  final double nextPress;
  final VoidCallback onPlayPause;
  final VoidCallback onNext;
  final ValueChanged<TransportKey> onPressed;

  @override
  State<_PlayNextGroup> createState() => _PlayNextGroupState();
}

class _PlayNextGroupState extends State<_PlayNextGroup> with TickerProviderStateMixin {
  late final SpringValue _box = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);
  late final SpringValue _pad = SpringValue(
    vsync: this,
    initial: widget.isPlaying ? 24 : 16,
    spring: YoinSprings.standardFastSpatial,
  );
  late final SpringValue _stretch = SpringValue(
    vsync: this,
    initial: widget.isPlaying ? 1.06 : 0.97,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _glyph = SpringValue(
    vsync: this,
    initial: widget.isPlaying ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  bool _held = false;
  bool _boxReady = false;

  @override
  void dispose() {
    _box.dispose();
    _pad.dispose();
    _stretch.dispose();
    _glyph.dispose();
    super.dispose();
  }

  TextStyle _wordStyle(BuildContext context, bool playing) {
    final base = Theme.of(context).textTheme.titleLarge!;
    return base.copyWith(
      fontSize: base.fontSize! * 0.9,
      fontWeight: playing ? FontWeight.w700 : FontWeight.w500,
      fontVariations: [FontVariation.weight(playing ? 700 : 500)],
      letterSpacing: playing ? 0.5 : 0,
      color: Theme.of(context).colorScheme.onPrimary,
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    final playing = widget.isPlaying;
    final style = _wordStyle(context, playing);
    final word = playing ? strings.pauseWord : strings.playWord;
    final scaler = MediaQuery.textScalerOf(context);
    // np_transport_*_word is untranslatable: every locale measures the
    // English word, so the pill is the same size whether it spells the word
    // or shows the glyph in its box (PlaybackControls.kt wordTargetWidth).
    final measured = (TextPainter(
      text: TextSpan(text: word, style: style),
      textDirection: TextDirection.ltr,
      textScaler: scaler,
      maxLines: 1,
    )..layout()).width;
    final reduce = MediaQuery.disableAnimationsOf(context);
    if (!_boxReady || reduce) {
      _box.snapTo(measured);
      _boxReady = true;
    } else {
      _box.animateTo(measured);
    }
    _pad.animateTo(playing ? 24 : 16);
    _stretch.spring = _held ? YoinSprings.standardFastSpatial : YoinSprings.standardDefaultSpatial;
    _stretch.animateTo(_held ? 1.10 : (playing ? 1.06 : 0.97));
    _glyph.animateTo(playing ? 1 : 0);

    return AnimatedBuilder(
      animation: Listenable.merge([_box.animation, _pad.animation, _stretch.animation, _glyph.animation]),
      builder: (context, _) {
        // FilledTonalButton's intrinsic width never drops below ButtonDefaults.MinWidth.
        final playNatural = math.max(M3eButtonTokens.minWidth, _box.value + 2 * _pad.value);
        final (playW, nextW) = transportKeyWidths(
          playNatural,
          NowPlayingTokens.controlSize,
          playPress: widget.playPress,
          nextPress: widget.nextPress,
        );
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            _PressKey(
              width: math.max(0.0, playW),
              color: colors.primary,
              semantics: playing ? strings.cdPause : strings.cdPlay,
              onPressed: (down) {
                _held = down;
                widget.onPressed(down ? TransportKey.play : TransportKey.none);
                setState(() {});
              },
              onTap: () {
                YoinHaptics.click();
                widget.onPlayPause();
              },
              // The word's box carries the pill's width; the content is centred
              // in it. English lays the word out at its own width (unbounded)
              // and stretches it in X from its own leading edge; every other
              // locale shows the glyph, swelling in X and Y about its centre.
              child: SizedBox(
                width: _box.value,
                child: strings.isEnglish
                    ? OverflowBox(
                        minWidth: 0,
                        maxWidth: double.infinity,
                        child: Transform.scale(
                          scaleX: _stretch.value,
                          alignment: Alignment.centerLeft,
                          child: Text(word, style: style, maxLines: 1, softWrap: false),
                        ),
                      )
                    : Transform.scale(
                        scale: _stretch.value,
                        child: Stack(
                          alignment: Alignment.center,
                          children: [
                            Opacity(
                              opacity: (1 - _glyph.value).clamp(0.0, 1.0),
                              child: Icon(YoinSymbols.playFilled, size: _iconSize, color: colors.onPrimary),
                            ),
                            Opacity(
                              opacity: _glyph.value.clamp(0.0, 1.0),
                              child: Icon(YoinSymbols.pauseFilled, size: _iconSize, color: colors.onPrimary),
                            ),
                          ],
                        ),
                      ),
              ),
            ),
            const SizedBox(width: NowPlayingTokens.transportGap),
            _PressKey(
              width: math.max(0.0, nextW),
              color: colors.primary,
              semantics: strings.cdSkipNext,
              onPressed: (down) => widget.onPressed(down ? TransportKey.next : TransportKey.none),
              onTap: () {
                YoinHaptics.tick();
                widget.onNext();
              },
              child: Icon(YoinSymbols.skipNextFilled, size: _iconSize, color: colors.onPrimary),
            ),
          ],
        );
      },
    );
  }
}

/// One key of the PLAY | NEXT group: a filled stadium (FilledTonalButton
/// with shapes.extraLarge / FilledIconButton's round filledShape — neither
/// morphs on press; the group's width exchange is the press response).
class _PressKey extends StatelessWidget {
  const _PressKey({
    required this.width,
    required this.color,
    required this.semantics,
    required this.onPressed,
    required this.onTap,
    required this.child,
  });

  final double width;
  final Color color;
  final String semantics;
  final ValueChanged<bool> onPressed;
  final VoidCallback onTap;
  final Widget child;

  @override
  Widget build(BuildContext context) => SizedBox(
    width: width,
    child: ExpressiveSurfaceButton(
      shapes: const InteractionShapes.fixed(CornerRadii.full),
      height: NowPlayingTokens.controlSize,
      color: color,
      contentColor: Theme.of(context).colorScheme.onPrimary,
      semanticLabel: semantics,
      onPressChanged: onPressed,
      onTap: onTap,
      child: child,
    ),
  );
}

/// PREVIOUS: FilledIconButton (round, no press morph) in secondary / onSecondary.
class _RoundKey extends StatelessWidget {
  const _RoundKey({required this.icon, required this.semantics, required this.onTap});

  final IconData icon;
  final String semantics;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return SizedBox.square(
      dimension: NowPlayingTokens.controlSize,
      child: ExpressiveSurfaceButton(
        shapes: const InteractionShapes.fixed(CornerRadii.full),
        height: NowPlayingTokens.controlSize,
        color: colors.secondary,
        contentColor: colors.onSecondary,
        semanticLabel: semantics,
        onTap: onTap,
        child: Icon(icon, size: _iconSize, color: colors.onSecondary),
      ),
    );
  }
}

/// controlIconSize = controlSize × 0.5 (56 → 28).
const double _iconSize = NowPlayingTokens.controlSize * 0.5;

/// The round play-mode key: tertiaryContainer at repeat-all, lit (primary)
/// otherwise; Off modes draw the repeat glyph with a slash drawn in on the
/// effects spring. Tap walks the 4-mode cycle.
class PlayModeButton extends StatefulWidget {
  const PlayModeButton({super.key, required this.mode, required this.onTap});

  final PlayMode mode;
  final VoidCallback onTap;

  @override
  State<PlayModeButton> createState() => _PlayModeButtonState();
}

class _PlayModeButtonState extends State<PlayModeButton> with TickerProviderStateMixin {
  late final SpringValue _lit = SpringValue(
    vsync: this,
    initial: (widget.mode != PlayMode.repeatAll) ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  late final SpringValue _slash = SpringValue(
    vsync: this,
    initial: widget.mode.isRepeatOff ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(PlayModeButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    _lit.animateTo((widget.mode != PlayMode.repeatAll) ? 1 : 0);
    _slash.animateTo(widget.mode.isRepeatOff ? 1 : 0);
  }

  @override
  void dispose() {
    _lit.dispose();
    _slash.dispose();
    super.dispose();
  }

  String _state(NowPlayingStrings s) => switch (widget.mode) {
    PlayMode.repeatAll => s.repeatAll,
    PlayMode.shuffle => s.shuffle,
    PlayMode.shuffleRepeatOne => s.shuffleRepeatOne,
    PlayMode.repeatOne => s.repeatOne,
    PlayMode.repeatOff => s.repeatOff,
    PlayMode.shuffleRepeatOff => s.shuffleRepeatOff,
  };

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    final mode = widget.mode;
    final glyph = switch (mode) {
      PlayMode.repeatAll || PlayMode.repeatOff => YoinSymbols.repeat,
      PlayMode.shuffle || PlayMode.shuffleRepeatOne || PlayMode.shuffleRepeatOff => YoinSymbols.shuffle,
      PlayMode.repeatOne => YoinSymbols.repeatOne,
    };
    return Semantics(
      button: true,
      label: strings.cdPlayMode,
      value: _state(strings),
      excludeSemantics: true,
      child: AnimatedBuilder(
        animation: Listenable.merge([_lit.animation, _slash.animation]),
        builder: (context, _) {
          final lit = _lit.value.clamp(0.0, 1.0);
          final container = Color.lerp(colors.tertiaryContainer, colors.primary, lit)!;
          final content = Color.lerp(colors.onTertiaryContainer, colors.onPrimary, lit)!;
          // A circle (clip(CircleShape) + combinedClickable: ripple, no press morph).
          return SizedBox.square(
            dimension: NowPlayingTokens.controlSize,
            child: ExpressiveSurfaceButton(
              shapes: const InteractionShapes.fixed(CornerRadii.full),
              height: NowPlayingTokens.controlSize,
              color: container,
              contentColor: content,
              onTap: () {
                YoinHaptics.tick();
                widget.onTap();
              },
              child: SizedBox.square(
                dimension: NowPlayingTokens.controlSize,
                child: CustomPaint(
                  foregroundPainter: _SlashPainter(
                    progress: _slash.value.clamp(0.0, 1.0),
                    color: content,
                    halo: container,
                  ),
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      Icon(glyph, size: 28, color: content),
                      // Shuffle + repeat one: Android has a dedicated symbol; v1 badges a "1".
                      if (mode == PlayMode.shuffleRepeatOne)
                        Positioned(
                          right: 10,
                          bottom: 9,
                          child: Text(
                            '1',
                            style: Theme.of(
                              context,
                            ).textTheme.labelSmall!.copyWith(color: content, fontWeight: FontWeight.w700),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

class _SlashPainter extends CustomPainter {
  const _SlashPainter({required this.progress, required this.color, required this.halo});

  final double progress;
  final Color color;
  final Color halo;

  @override
  void paint(Canvas canvas, Size size) {
    if (progress <= 0) return;
    final glyph = Rect.fromCenter(center: size.center(Offset.zero), width: 28, height: 28);
    final inset = glyph.width * 0.08;
    final start = glyph.topLeft + Offset(inset, inset);
    final end = glyph.bottomRight - Offset(inset, inset);
    final head = Offset.lerp(start, end, progress)!;
    final stroke = math.min(glyph.width, glyph.height) * 0.09;
    canvas.drawLine(
      start,
      head,
      Paint()
        ..color = halo
        ..strokeWidth = stroke * 2.2
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawLine(
      start,
      head,
      Paint()
        ..color = color
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.round,
    );
  }

  @override
  bool shouldRepaint(_SlashPainter old) => old.progress != progress || old.color != color || old.halo != halo;
}

/// Elapsed / -remaining (labelLarge, tabular): rebuilds only when the shown
/// second changes, reading the shared clock.
class _TimeLabel extends StatefulWidget {
  const _TimeLabel({required this.clock, required this.durationMs, required this.remaining});

  final PlayheadClock clock;
  final int? durationMs;
  final bool remaining;

  @override
  State<_TimeLabel> createState() => _TimeLabelState();
}

class _TimeLabelState extends State<_TimeLabel> {
  late String _text = _format();

  @override
  void initState() {
    super.initState();
    widget.clock.addListener(_onTick);
  }

  @override
  void didUpdateWidget(_TimeLabel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.clock != widget.clock) {
      oldWidget.clock.removeListener(_onTick);
      widget.clock.addListener(_onTick);
    }
    _text = _format();
  }

  @override
  void dispose() {
    widget.clock.removeListener(_onTick);
    super.dispose();
  }

  String _format() {
    final position = widget.clock.positionMs();
    if (!widget.remaining) return formatPlaybackTime(position);
    final duration = widget.durationMs ?? 0;
    return '-${formatPlaybackTime(math.max(0, duration - position))}';
  }

  void _onTick() {
    final next = _format();
    if (next != _text) setState(() => _text = next);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return ConstrainedBox(
      constraints: BoxConstraints(
        minWidth: widget.remaining ? NowPlayingTokens.timeLabelMinEnd : NowPlayingTokens.timeLabelMinStart,
      ),
      child: Transform.translate(
        offset: const Offset(0, 6),
        child: Text(
          _text,
          textAlign: widget.remaining ? TextAlign.end : TextAlign.start,
          style: Theme.of(context).textTheme.labelLarge!.copyWith(
            color: colors.onSurfaceVariant,
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
        ),
      ),
    );
  }
}
