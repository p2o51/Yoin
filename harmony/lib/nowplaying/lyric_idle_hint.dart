/// The 16:9 one-line lyric row's once-a-day unfold hint (NowPlayingScreen.kt
/// OneLineLyricRow + UnfoldHintSymbol, NowPlayingViewModel.claimLyricIdleHint,
/// NowPlayingPresentation.kt lyricIdleHintAllowed): when a line holds for
/// [LyricIdleHintTokens.delayMs] during playback (an intro, a break, a long
/// note) the row cross-fades — at most once a day, by local date — to the
/// unfold symbol, shown only while the symbol moves, then fades back.
///
/// The symbol is Yoin Symbols' static `unfoldMore`, animated with transforms:
/// its upper and lower halves (one chevron each) are the same glyph clipped
/// at the centre line, pressed toward it and sprung back out. Android draws
/// those two chevrons on a Canvas because the symbol has no animated twin;
/// the app may not draw icons, and the clipped glyph moves the same way.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../platform/storage/stores.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'lyrics_logic.dart';
import 'now_playing_view_model.dart' show LyricsReady, LyricsState;
import 'playhead_clock.dart';

/// [now]'s local calendar day as days since 1970-01-01 (LocalDate.toEpochDay).
int localEpochDay(DateTime now) {
  final local = now.toLocal();
  return DateTime.utc(local.year, local.month, local.day).millisecondsSinceEpoch ~/ Duration.millisecondsPerDay;
}

/// True when the hint has not shown yet on [today]'s local date.
bool lyricIdleHintAllowed(int? lastShownEpochDay, DateTime today) => lastShownEpochDay != localEpochDay(today);

/// The line playing at [positionMs]: the last timed line that has started; −1 before the first.
int activeLyricLine(LyricSheet sheet, int positionMs) {
  for (var i = sheet.rows.length - 1; i >= 0; i--) {
    final start = sheet.rows[i].startMs;
    if (start != null && positionMs >= start) return i;
  }
  return -1;
}

/// Today's single hint, claimed once (NowPlayingViewModel.claimLyricIdleHint):
/// the day is stored the moment it is claimed, so a second window can't show
/// it again. Stored under Android's key, as a local epoch day.
class LyricIdleHintClaims {
  LyricIdleHintClaims(this._store);

  /// Android SharedPrefsLyricHintStore's key.
  static const key = 'lyric_idle_hint_day';

  final KeyValueStore? _store;
  int? _lastShown;

  /// Re-read on every claim (Android reads the prefs each time): another window's claim counts too.
  Future<void> _load() async {
    final store = _store;
    if (store == null) return;
    try {
      final stored = int.tryParse(await store.read(key) ?? '');
      if (stored != null) _lastShown = stored;
    } catch (_) {
      // No readable store: only this session remembers.
    }
  }

  /// True exactly once per local day.
  Future<bool> claim([DateTime? now]) async {
    await _load();
    final today = now ?? DateTime.now();
    if (!lyricIdleHintAllowed(_lastShown, today)) return false;
    final day = localEpochDay(today);
    _lastShown = day;
    try {
      await _store?.write(key, '$day');
    } catch (_) {
      // Kept for this session.
    }
    return true;
  }
}

final lyricIdleHintClaimsProvider = Provider<LyricIdleHintClaims>((ref) {
  KeyValueStore? store;
  try {
    store = ref.watch(keyValueStoreProvider);
  } catch (_) {
    store = null;
  }
  return LyricIdleHintClaims(store);
});

/// Wraps the one-line lyric row ([child]): while [enabled], a line that
/// holds for [LyricIdleHintTokens.delayMs] during playback of synced lyrics
/// claims today's hint and cross-fades the row to the moving unfold symbol
/// for [LyricIdleHintTokens.showMs]. A new line, a pause or another song
/// takes the hint back at once.
class LyricIdleHint extends ConsumerStatefulWidget {
  const LyricIdleHint({
    super.key,
    required this.enabled,
    required this.lyrics,
    required this.songKey,
    required this.clock,
    required this.child,
    this.inset = NowPlayingTokens.columnPadding,
  });

  final bool enabled;
  final LyricsState lyrics;
  final String songKey;
  final PlayheadClock clock;
  final Widget child;

  /// The row's side padding: the symbol sits where the line starts.
  final double inset;

  @override
  ConsumerState<LyricIdleHint> createState() => _LyricIdleHintState();
}

class _LyricIdleHintState extends ConsumerState<LyricIdleHint> with SingleTickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  Timer? _wait;
  Timer? _hold;
  Object? _watched;
  bool _showing = false;

  /// Bumped per showing: the symbol plays once each time.
  int _play = 0;

  /// The clock this state listens to: only while [LyricIdleHint.enabled], so
  /// a row that can't show the hint never reads the ~30 Hz playhead.
  PlayheadClock? _listening;

  void _listen() {
    final clock = widget.enabled ? widget.clock : null;
    if (identical(clock, _listening)) return;
    _listening?.removeListener(_onClock);
    _listening = clock?..addListener(_onClock);
  }

  @override
  void initState() {
    super.initState();
    _listen();
    _onClock();
  }

  @override
  void didUpdateWidget(LyricIdleHint oldWidget) {
    super.didUpdateWidget(oldWidget);
    _listen();
    _onClock();
  }

  @override
  void dispose() {
    _listening?.removeListener(_onClock);
    _wait?.cancel();
    _hold?.cancel();
    _alpha.dispose();
    super.dispose();
  }

  bool get _reduced => MediaQuery.maybeDisableAnimationsOf(context) ?? false;

  /// What the wait is keyed on (Android's LaunchedEffect keys): the line, playing, the song, synced lyrics.
  Object? _key() {
    if (!widget.enabled) return (false, '', -1, false, false);
    final lyrics = widget.lyrics;
    final sheet = lyrics is LyricsReady ? lyrics.sheet : null;
    final synced = sheet?.isSynced ?? false;
    final playing = widget.clock.isPlaying;
    final line = sheet == null ? -1 : activeLyricLine(sheet, widget.clock.positionMs());
    return (widget.enabled, widget.songKey, line, playing, synced);
  }

  void _onClock() {
    final key = _key();
    if (key == _watched) return;
    _watched = key;
    _wait?.cancel();
    _hold?.cancel();
    _setShowing(false);
    final (enabled, _, _, playing, synced) = key! as (bool, String, int, bool, bool);
    if (!enabled || !playing || !synced) return;
    _wait = Timer(const Duration(milliseconds: LyricIdleHintTokens.delayMs), _claim);
  }

  Future<void> _claim() async {
    final key = _watched;
    final claimed = await ref.read(lyricIdleHintClaimsProvider).claim();
    // The line moved on (or the song, or a pause) while the claim was stored.
    if (!mounted || !claimed || key != _watched) return;
    _play++;
    _setShowing(true);
    // A fixed hold: under reduced motion the symbol stays still and the hint simply stays ~2 s.
    _hold = Timer(const Duration(milliseconds: LyricIdleHintTokens.showMs), () => _setShowing(false));
  }

  void _setShowing(bool showing) {
    if (_showing == showing) return;
    _showing = showing;
    final target = showing ? 1.0 : 0.0;
    if (mounted && _reduced) {
      _alpha.snapTo(target);
    } else {
      _alpha.animateTo(target);
    }
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.primary;
    return AnimatedBuilder(
      animation: _alpha.animation,
      child: widget.child,
      builder: (context, child) {
        final a = _alpha.value.clamp(0.0, 1.0);
        return Stack(
          fit: StackFit.passthrough,
          children: [
            // Whole again once the hint has (all but) gone.
            Opacity(opacity: a <= 0.01 ? 1 : 1 - a, child: child),
            if (a > 0.01)
              Positioned.fill(
                child: IgnorePointer(
                  child: Opacity(
                    opacity: a,
                    child: Padding(
                      padding: EdgeInsetsDirectional.only(start: widget.inset),
                      child: Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: UnfoldHintSymbol(play: _showing ? _play : 0, color: color),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

/// `unfold_more`, moving: the two chevrons press [LyricIdleHintTokens.press]
/// toward the centre line (fast spatial spring), then spring
/// [LyricIdleHintTokens.bounce] outward and settle (default spatial). Each
/// new [play] (> 0) plays it once; under reduced motion it stays still.
class UnfoldHintSymbol extends StatefulWidget {
  const UnfoldHintSymbol({super.key, required this.play, required this.color, this.size = LyricIdleHintTokens.symbol});

  final int play;
  final Color color;
  final double size;

  @override
  State<UnfoldHintSymbol> createState() => _UnfoldHintSymbolState();
}

class _UnfoldHintSymbolState extends State<UnfoldHintSymbol> with SingleTickerProviderStateMixin {
  /// Negative: toward the centre line (the upper chevron moves down, the lower one up).
  late final AnimationController _d = AnimationController.unbounded(vsync: this);
  int _run = 0;
  bool _started = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Once: the first play reads reduced motion, so it waits for the dependencies.
    if (_started) return;
    _started = true;
    _start();
  }

  @override
  void didUpdateWidget(UnfoldHintSymbol oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.play != widget.play) _start();
  }

  @override
  void dispose() {
    _d.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    final run = ++_run;
    _d.stop();
    _d.value = 0;
    if (widget.play <= 0 || (MediaQuery.maybeDisableAnimationsOf(context) ?? false)) return;
    Future<void> to(SpringDescription spring, double target) async {
      if (run != _run || !mounted) return;
      await _d.animateWith(SpringSimulation(spring, _d.value, target, _d.velocity)).orCancel.catchError((_) {});
    }

    await to(YoinSprings.fastSpatial, -LyricIdleHintTokens.press);
    await to(YoinSprings.defaultSpatial, LyricIdleHintTokens.bounce);
    await to(YoinSprings.defaultSpatial, 0);
  }

  @override
  Widget build(BuildContext context) {
    final size = widget.size;
    final glyph = Icon(YoinSymbols.unfoldMore, size: size, color: widget.color);
    // One chevron per half of the glyph, each clipped at the centre line.
    Widget half({required bool upper}) => ClipRect(
      child: Align(alignment: upper ? Alignment.topCenter : Alignment.bottomCenter, heightFactor: 0.5, child: glyph),
    );
    return ExcludeSemantics(
      child: SizedBox.square(
        dimension: size,
        child: AnimatedBuilder(
          animation: _d,
          builder: (context, _) {
            // dp on the 24 grid, scaled with the symbol.
            final d = _d.value * size / LyricIdleHintTokens.symbol;
            return Column(
              children: [
                Transform.translate(offset: Offset(0, -d), child: half(upper: true)),
                Transform.translate(offset: Offset(0, d), child: half(upper: false)),
              ],
            );
          },
        ),
      ),
    );
  }
}
