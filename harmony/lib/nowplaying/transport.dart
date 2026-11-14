import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'haptics.dart';
import 'play_mode_cycle.dart';
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
/// under the wave when the row is narrower than transportLabelsInlineMinWidth
/// (stage_budget.dart).
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
    required this.onSetPlayMode,
    required this.onSeek,
    required this.pressed,
    this.showPlayModeSplitOnFullCycle = false,
    this.noteAnchorsMs = const [],
    this.onShowedPlayModeSplit,
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
  final ValueChanged<PlayMode> onSetPlayMode;

  /// A run round the play-mode cycle splits the button once to show it can.
  final bool showPlayModeSplitOnFullCycle;

  /// Song-moment notes the wave bar snaps to.
  final List<int> noteAnchorsMs;
  final VoidCallback? onShowedPlayModeSplit;
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
                PlayModeButton(
                  mode: widget.playMode,
                  onSetPlayMode: widget.onSetPlayMode,
                  showSplitOnFullCycle: widget.showPlayModeSplitOnFullCycle,
                  onShowedSplit: widget.onShowedPlayModeSplit,
                ),
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
                      noteAnchorsMs: widget.noteAnchorsMs,
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

// PlaybackControls.kt private vals (Android keeps them local to the button too).
const double _playModeSegmentGap = 2;
const double _playModeInnerCornerRatio = 0.18;
const double _playModeGlyphInset = 10;

/// Below this much split the second symbol sits exactly under the first: one is drawn.
const double _playModeSplitShownFrom = 0.02;

/// The play-mode pill when split: two segments of 0.8 × the control size (playModeNaturalWidth).
double playModeNaturalWidth(double controlSize) => controlSize * 1.6 + _playModeSegmentGap;

final Stopwatch _uptime = Stopwatch()..start();

/// The play-mode button (owner 2026-10-17, PlaybackControls.kt PlayModeButton):
/// a round button by default, its symbol showing the mode; a tap steps
/// [PlayMode.next]. A long-press splits it into a pill of two segments —
/// shuffle on the left, repeat on the right — each tap on a segment switching
/// only its own axis; left alone for [playModeSplitHoldMs] (or long-pressed
/// again) it merges back into the circle. Width, gap, inner corners and the
/// symbols' travel share one spatial spring; colours ride the effects
/// spring. A segment lights up (primary) when its axis leaves the default;
/// the circle lights up for any mode but repeat all. The repeat segment steps
/// all → one → off ([PlayMode.nextRepeat]); repeat off draws its symbol
/// struck through, split or not.
class PlayModeButton extends StatefulWidget {
  const PlayModeButton({
    super.key,
    required this.mode,
    required this.onSetPlayMode,
    this.height = NowPlayingTokens.controlSize,
    this.splitWidth = NowPlayingTokens.controlSize * 1.6 + _playModeSegmentGap,
    this.iconSize = _iconSize,
    this.showSplitOnFullCycle = false,
    this.onShowedSplit,
    this.nowMs,
  });

  final PlayMode mode;
  final ValueChanged<PlayMode> onSetPlayMode;
  final double height;
  final double splitWidth;
  final double iconSize;

  /// Taps all the way round the cycle split it once, to show it can (PlayModeCycle).
  final bool showSplitOnFullCycle;
  final VoidCallback? onShowedSplit;

  /// Monotonic clock for the cycle (tests).
  final int Function()? nowMs;

  @override
  State<PlayModeButton> createState() => PlayModeButtonState();
}

class PlayModeButtonState extends State<PlayModeButton> with TickerProviderStateMixin {
  final PlayModeCycle _cycle = PlayModeCycle();
  bool _split = false;
  Timer? _hold;

  late final SpringValue _f = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardDefaultSpatial);
  late final SpringValue _leftLit = SpringValue(vsync: this, initial: _leftTarget, spring: YoinSprings.defaultEffects);
  late final SpringValue _rightLit = SpringValue(
    vsync: this,
    initial: _rightTarget,
    spring: YoinSprings.defaultEffects,
  );
  late final SpringValue _slash = SpringValue(
    vsync: this,
    initial: widget.mode.isRepeatOff ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  /// Whether the button is split into its two segments.
  bool get isSplit => _split;

  double get _leftTarget => (_split ? widget.mode.shuffled : widget.mode != PlayMode.repeatAll) ? 1 : 0;

  // Split, the repeat segment is lit against this mode with repeat all.
  double get _rightTarget =>
      (_split ? widget.mode != widget.mode.withRepeatAll : widget.mode != PlayMode.repeatAll) ? 1 : 0;

  bool get _reduced => MediaQuery.maybeDisableAnimationsOf(context) ?? false;

  void _drive(SpringValue v, double target) => _reduced ? v.snapTo(target) : v.animateTo(target);

  void _syncTargets() {
    _drive(_f, _split ? 1 : 0);
    _drive(_leftLit, _leftTarget);
    _drive(_rightLit, _rightTarget);
    _drive(_slash, widget.mode.isRepeatOff ? 1 : 0);
  }

  @override
  void didUpdateWidget(PlayModeButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncTargets();
  }

  @override
  void dispose() {
    _hold?.cancel();
    _f.dispose();
    _leftLit.dispose();
    _rightLit.dispose();
    _slash.dispose();
    super.dispose();
  }

  /// Every split, and every tap while split, restarts the quiet spell before the merge.
  void _holdOpen() {
    _hold?.cancel();
    if (!_split) return;
    _hold = Timer(const Duration(milliseconds: playModeSplitHoldMs), () {
      if (mounted) _setSplit(false);
    });
  }

  void _setSplit(bool split) {
    setState(() => _split = split);
    _holdOpen();
    _syncTargets();
  }

  void _onRoundTap() {
    YoinHaptics.tick();
    final from = widget.mode;
    final next = from.next();
    widget.onSetPlayMode(next);
    final now = widget.nowMs?.call() ?? _uptime.elapsedMilliseconds;
    final roundTheCycle = _cycle.onTap(from, next, now);
    if (widget.showSplitOnFullCycle && roundTheCycle) {
      _setSplit(true);
      widget.onShowedSplit?.call();
    }
  }

  void _onRoundLongPress() {
    YoinHaptics.longPress();
    _setSplit(true);
  }

  void _merge() {
    YoinHaptics.longPress();
    _setSplit(false);
  }

  void _onShuffleTap() {
    YoinHaptics.tick();
    _holdOpen();
    widget.onSetPlayMode(widget.mode.withShuffle(!widget.mode.shuffled));
  }

  void _onRepeatTap() {
    YoinHaptics.tick();
    _holdOpen();
    widget.onSetPlayMode(widget.mode.nextRepeat());
  }

  String _state(NowPlayingStrings s) => switch (widget.mode) {
    PlayMode.repeatAll => s.repeatAll,
    PlayMode.shuffle => s.shuffle,
    PlayMode.shuffleRepeatOne => s.shuffleRepeatOne,
    PlayMode.repeatOne => s.repeatOne,
    PlayMode.repeatOff => s.repeatOff,
    PlayMode.shuffleRepeatOff => s.shuffleRepeatOff,
  };

  String _repeatState(NowPlayingStrings s) => widget.mode.isRepeatOne
      ? s.repeatOne
      : widget.mode.isRepeatOff
      ? s.repeatOff
      : s.repeatAll;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    final mode = widget.mode;
    final h = widget.height;
    final splitW = widget.splitWidth;
    final merged = _PlayModeSymbol.of(mode);
    final leftSymbol = _split ? _PlayModeSymbol.shuffle : merged;
    final rightSymbol = !_split
        ? merged
        : mode.isRepeatOne
        ? _PlayModeSymbol.repeatOne
        : _PlayModeSymbol.repeatAll;

    return AnimatedBuilder(
      animation: Listenable.merge([_f.animation, _leftLit.animation, _rightLit.animation, _slash.animation]),
      builder: (context, _) {
        final f = _f.value;
        final width = h + (splitW - h) * f;
        final gap = _playModeSegmentGap * f;
        final segment = math.max(0.0, (width - gap) / 2);
        final outer = h / 2;
        // Square inner edges at rest, so the two halves close into one circle.
        final inner = math.max(0.0, h * _playModeInnerCornerRatio * f);
        final glyphSplit = math.min(widget.iconSize, (splitW - _playModeSegmentGap) / 2 - _playModeGlyphInset);
        final glyph = widget.iconSize + (glyphSplit - widget.iconSize) * f.clamp(0.0, 1.0);
        Color container(double lit) => Color.lerp(colors.tertiaryContainer, colors.primary, lit.clamp(0.0, 1.0))!;
        Color content(double lit) => Color.lerp(colors.onTertiaryContainer, colors.onPrimary, lit.clamp(0.0, 1.0))!;
        final leftBg = container(_leftLit.value);
        final rightBg = container(_rightLit.value);
        final leftFg = content(_leftLit.value);
        final rightFg = content(_rightLit.value);
        final leftRadius = BorderRadius.horizontal(left: Radius.circular(outer), right: Radius.circular(inner));
        final rightRadius = BorderRadius.horizontal(left: Radius.circular(inner), right: Radius.circular(outer));
        final centre = width / 2;
        final leftCentre = centre + (segment / 2 - centre) * f;
        final rightCentre = centre + (width - segment / 2 - centre) * f;
        final slash = _slash.value.clamp(0.0, 1.0);
        final showRight = f > _playModeSplitShownFrom || _split;

        Widget touch({required ShapeBorder shape, required VoidCallback onTap, required VoidCallback onLongPress}) =>
            Material(
              type: MaterialType.transparency,
              child: InkWell(customBorder: shape, onTap: onTap, onLongPress: onLongPress),
            );

        return SizedBox(
          width: width,
          height: h,
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              Positioned(
                left: 0,
                top: 0,
                bottom: 0,
                width: segment,
                child: DecoratedBox(
                  decoration: BoxDecoration(color: leftBg, borderRadius: leftRadius),
                ),
              ),
              Positioned(
                right: 0,
                top: 0,
                bottom: 0,
                width: segment,
                child: DecoratedBox(
                  decoration: BoxDecoration(color: rightBg, borderRadius: rightRadius),
                ),
              ),
              // The symbols: one in the middle of the circle; split, two copies
              // travel out to their segments' middles (and back).
              Positioned(
                left: leftCentre - glyph / 2,
                top: (h - glyph) / 2,
                child: IgnorePointer(
                  // Merged, the one symbol carries repeat off; split, the repeat segment's does.
                  child: _PlayModeGlyph(
                    symbol: leftSymbol,
                    size: glyph,
                    color: leftFg,
                    halo: leftBg,
                    slash: slash * (1 - f).clamp(0.0, 1.0),
                  ),
                ),
              ),
              if (showRight)
                Positioned(
                  left: rightCentre - glyph / 2,
                  top: (h - glyph) / 2,
                  child: IgnorePointer(
                    child: _PlayModeGlyph(
                      symbol: rightSymbol,
                      size: glyph,
                      color: rightFg,
                      halo: rightBg,
                      slash: slash * f.clamp(0.0, 1.0),
                    ),
                  ),
                ),
              if (!_split)
                Positioned.fill(
                  child: Semantics(
                    button: true,
                    label: strings.cdPlayMode,
                    value: _state(strings),
                    onLongPressHint: strings.cdPlayModeSplit,
                    excludeSemantics: true,
                    child: touch(shape: const CircleBorder(), onTap: _onRoundTap, onLongPress: _onRoundLongPress),
                  ),
                )
              else ...[
                Positioned(
                  left: 0,
                  top: 0,
                  bottom: 0,
                  width: segment,
                  child: Semantics(
                    label: strings.shuffle,
                    toggled: mode.shuffled,
                    excludeSemantics: true,
                    child: touch(
                      shape: RoundedRectangleBorder(borderRadius: leftRadius),
                      onTap: _onShuffleTap,
                      onLongPress: _merge,
                    ),
                  ),
                ),
                Positioned(
                  right: 0,
                  top: 0,
                  bottom: 0,
                  width: segment,
                  child: Semantics(
                    button: true,
                    label: strings.repeat,
                    value: _repeatState(strings),
                    excludeSemantics: true,
                    child: touch(
                      shape: RoundedRectangleBorder(borderRadius: rightRadius),
                      onTap: _onRepeatTap,
                      onLongPress: _merge,
                    ),
                  ),
                ),
              ],
            ],
          ),
        );
      },
    );
  }
}

enum _PlayModeSymbol {
  repeatAll,
  shuffle,
  shuffleRepeatOne,
  repeatOne;

  // PlaybackControls.kt toSymbol(): the off modes are struck through by the slash.
  static _PlayModeSymbol of(PlayMode mode) => switch (mode) {
    PlayMode.repeatAll || PlayMode.repeatOff => repeatAll,
    PlayMode.shuffle || PlayMode.shuffleRepeatOff => shuffle,
    PlayMode.shuffleRepeatOne => shuffleRepeatOne,
    PlayMode.repeatOne => repeatOne,
  };
}

/// One play-mode symbol, [slash] of a repeat-off stroke drawn over it.
class _PlayModeGlyph extends StatelessWidget {
  const _PlayModeGlyph({
    required this.symbol,
    required this.size,
    required this.color,
    required this.halo,
    required this.slash,
  });

  final _PlayModeSymbol symbol;
  final double size;
  final Color color;
  final Color halo;
  final double slash;

  @override
  Widget build(BuildContext context) {
    final icon = switch (symbol) {
      _PlayModeSymbol.repeatAll => YoinSymbols.repeat,
      _PlayModeSymbol.shuffle || _PlayModeSymbol.shuffleRepeatOne => YoinSymbols.shuffle,
      _PlayModeSymbol.repeatOne => YoinSymbols.repeatOne,
    };
    return SizedBox.square(
      dimension: size,
      child: CustomPaint(
        foregroundPainter: _SlashPainter(progress: slash, color: color, halo: halo),
        child: Stack(
          clipBehavior: Clip.none,
          alignment: Alignment.center,
          children: [
            Icon(icon, size: size, color: color),
            // Shuffle + repeat one: Android has a dedicated animated symbol; v1 badges a "1".
            if (symbol == _PlayModeSymbol.shuffleRepeatOne)
              Positioned(
                right: -size * 0.14,
                bottom: -size * 0.18,
                child: Text(
                  '1',
                  style: Theme.of(context).textTheme.labelSmall!.copyWith(color: color, fontWeight: FontWeight.w700),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// repeatOffSlash: a diagonal stroke from the top-left, [progress] of it drawn,
/// with a [halo]-coloured gap so it reads over the strands it crosses.
class _SlashPainter extends CustomPainter {
  const _SlashPainter({required this.progress, required this.color, required this.halo});

  final double progress;
  final Color color;
  final Color halo;

  @override
  void paint(Canvas canvas, Size size) {
    if (progress <= 0) return;
    final inset = size.shortestSide * 0.08;
    final start = Offset(inset, inset);
    final end = Offset(size.width - inset, size.height - inset);
    final head = Offset.lerp(start, end, progress.clamp(0.0, 1.0))!;
    final stroke = size.shortestSide * 0.09;
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
          maxLines: 1,
          softWrap: false,
          style: Theme.of(context).textTheme.labelLarge!.copyWith(
            color: colors.onSurfaceVariant,
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
        ),
      ),
    );
  }
}
